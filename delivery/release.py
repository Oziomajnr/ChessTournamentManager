"""Serialized release controller. The durable record lives on the delivery-state branch."""

import argparse
import hashlib
import json
import os
import subprocess
import tempfile
from pathlib import Path
from zipfile import ZIP_STORED, ZipFile

from delivery.adapters import github as gh
from delivery.adapters.play import Play, PlayError, VersionConflict
from delivery.agents import config
from delivery.policy import (
    REPAIR_LIMIT,
    artifact_id,
    health_status,
    release_action,
    utc_now,
)


def command(*args):
    return subprocess.check_output(
        ["gh", *map(str, args), "--repo", gh.REPO], text=True
    )


def select():
    gh.ensure_branch()
    record = gh.Record("releases")
    if not record.data.get("active"):
        for item in gh.issues("delivery-release"):
            n = item["number"]
            task = gh.Record(f"tasks/{n}").data
            if not task.get("merged") or not task.get("pr"):
                continue
            pr = gh.api(f"{gh.API}/pulls/{task['pr']}")
            if not pr["merged_at"] or task["merged"] != pr["merge_commit_sha"]:
                continue
            record.data["active"] = {
                "issue": n,
                "pr": task["pr"],
                "revision": task["merged"],
                "notes": task["plan"]["release_notes"],
                "supersedes": task.get("supersedes", []),
            }
            record.save("Select merged change for release")
            break
    c = record.data.get("active")
    gh.output("active", "true" if c else "false")
    if c:
        gh.output("revision", c["revision"])


def tag(c):
    return f"delivery-{c['build']}-{c['revision'][:12]}"


def existing(c):
    cp = subprocess.run(
        [
            "gh",
            "release",
            "view",
            tag(c),
            "--repo",
            gh.REPO,
            "--json",
            "isDraft,targetCommitish,assets",
        ],
        capture_output=True,
        text=True,
    )
    if cp.returncode:
        if "not found" in cp.stderr.lower():
            return None
        raise RuntimeError("Could not read the candidate storage release")
    release = json.loads(cp.stdout)
    if not release["isDraft"] or release["targetCommitish"] != c["revision"]:
        raise ValueError("Unexpected release asset owner or target revision")
    return release


def get_bundle(c, folder):
    zip_path = folder / "candidate.zip"
    command(
        "release",
        "download",
        tag(c),
        "--pattern",
        "candidate.zip",
        "--output",
        zip_path,
    )
    with ZipFile(zip_path) as z:
        if sorted(z.namelist()) != ["app-release.aab", "manifest.json"]:
            raise ValueError("Invalid release archive")
        proof = json.loads(z.read("manifest.json"))
        path = folder / "app-release.aab"
        path.write_bytes(z.read("app-release.aab"))
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    identifier = {"revision": c["revision"], "build": c["build"], "sha256": digest}
    if proof != identifier or (c.get("sha256") and c["sha256"] != digest):
        raise ValueError("Release archive does not match the selected artifact")
    return path, digest


def register():
    record = gh.Record("releases")
    c = record.data["active"]
    if c["revision"] != os.environ["CANDIDATE_REVISION"] or c["build"] != int(
        os.environ["CANDIDATE_BUILD"]
    ):
        raise ValueError("Signing result belongs to a different candidate")
    with tempfile.TemporaryDirectory() as temp:
        folder = Path(temp)
        release = existing(c)
        if not release:
            command(
                "release",
                "create",
                tag(c),
                "--target",
                c["revision"],
                "--draft",
                "--title",
                f"Candidate {c['build']}",
                "--notes",
                f"Internal delivery record for issue #{c['issue']}.",
            )
            release = existing(c)
        if not any(a["name"] == "candidate.zip" for a in release["assets"]):
            bundle = Path(os.environ["SIGNED_BUNDLE"])
            digest = hashlib.sha256(bundle.read_bytes()).hexdigest()
            proof = {"revision": c["revision"], "build": c["build"], "sha256": digest}
            with ZipFile(
                folder / "candidate.zip", "w", compression=ZIP_STORED
            ) as archive:
                archive.write(bundle, "app-release.aab")
                archive.writestr("manifest.json", json.dumps(proof))
            command("release", "upload", tag(c), folder / "candidate.zip")
        with tempfile.TemporaryDirectory() as check:
            _, digest = get_bundle(c, Path(check))
        c["sha256"] = digest
        record.save("Record verified signed release archive")


def environment_reviewers():
    env = gh.api(gh.API + "/environments/production")
    owners = {
        r["reviewer"]["login"]
        for rule in env["protection_rules"]
        if rule["type"] == "required_reviewers"
        for r in rule["reviewers"]
        if r["type"] == "User"
    }
    branches = gh.api(gh.API + "/environments/production/deployment-branch-policies")[
        "branch_policies"
    ]
    if (
        not owners
        or env.get("can_admins_bypass") is not False
        or not env["deployment_branch_policy"]["custom_branch_policies"]
        or [(b["type"], b["name"]) for b in branches] != [("branch", "main")]
    ):
        raise RuntimeError(
            "Production environment lacks its expected reviewer or branch protection"
        )
    return owners


def approval_state(c):
    attempt = c["approval_request"]
    if attempt["artifact"] != artifact_id(c):
        raise ValueError("Approval request belongs to a different artifact")
    reviewers = environment_reviewers()
    reviews = gh.api(f"{gh.API}/actions/runs/{int(attempt['run'])}/approvals")
    for review in reviews:
        if (
            any(e["name"] == "production" for e in review["environments"])
            and review["user"]["login"] in reviewers
        ):
            return review["state"].lower(), review["user"]["login"]
    return "pending", None


def approve():
    record = gh.Record("releases")
    c = record.data["active"]
    if os.environ["GITHUB_RUN_ID"] != c["approval_request"]["run"]:
        raise ValueError("The approval run does not match this request")
    state, reviewer = approval_state(c)
    if state != "approved":
        raise ValueError("The protected environment has no approval for this artifact")
    c["approval"] = {
        "artifact": artifact_id(c),
        "reviewer": reviewer,
        "run": os.environ["GITHUB_RUN_ID"],
    }
    record.save("Record production authorization for exact signed artifact")


def verify():
    play = Play()
    versions = []
    for track in ("internal", "production"):
        page = play.request("GET", play.base + "/tracks/" + track + "/releases")
        versions.extend(
            a["versionCode"]
            for r in page.get("releases", [])
            for a in r.get("activeArtifacts", [])
        )
    for prefix in ("crash", "anr"):
        play.request(
            "GET",
            f"https://playdeveloperreporting.googleapis.com/v1beta1/apps/{play.package}/{prefix}RateMetricSet",
        )
    environment_reviewers()
    gh.output("build", max(versions, default=0) + 1)
    gh.output("action", "build")
    gh.summary(
        "Verified both Play tracks, Android vitals and the production reviewer configuration. This run builds and verifies the upload signature locally on its runner."
    )


def smoke():
    record = gh.Record("releases")
    c = record.data["active"]
    proof = json.loads(os.environ["SMOKE_ARTIFACT"])
    actor = os.environ["GITHUB_ACTOR"]
    permission = gh.api(f"{gh.API}/collaborators/{actor}/permission")["permission"]
    if permission not in ("admin", "write", "maintain") or proof != artifact_id(c):
        raise ValueError("Smoke evidence does not match the active candidate or tester")
    result, evidence = os.environ["SMOKE_RESULT"], os.environ["SMOKE_EVIDENCE"]
    if (
        result not in ("passed", "failed")
        or not evidence.strip()
        or len(evidence) > 1600
    ):
        raise ValueError("Invalid smoke report")
    if "smoke-" + str(c["build"]) not in record.data.get("notices", []):
        raise ValueError(
            "The controller has not confirmed this internal release is available"
        )
    if c.get("smoke"):
        if c["smoke"]["artifact"] == proof and c["smoke"]["result"] == result:
            return
        raise ValueError("The verified smoke result already exists for this artifact")
    c["smoke"] = {
        "artifact": proof,
        "result": result,
        "evidence": evidence,
        "actor": actor,
    }
    record.save("Record test of the store-installed candidate")


def request_fix(record, c, reason):
    n = c["issue"]
    task = gh.Record(f"tasks/{n}")
    generation = task.data.get("generation", 0)
    gh.notify_once(
        record,
        n,
        "fix-" + str(c["build"]),
        f"Build {c['build']} requires a fix. {reason}",
    )
    if generation >= REPAIR_LIMIT:
        c["blocked"] = reason
        gh.labels(n, add=["delivery-attention"])
        record.save("Hold release for maintainer")
        return
    if c["build"] not in task.data.get("supersedes", []):
        prior = task.data.get("supersedes", []) + [c["build"]]
        plan = task.data["plan"]
        plan["acceptance"].append(
            "Fix the issue found during release verification: " + reason
        )
        task.data = {
            "generation": generation + 1,
            "supersedes": prior,
            "plan": plan,
            "requeued_by_release": True,
            "intake": task.data["intake"],
        }
        task.save("Open a repair cycle for the canonical issue")
    gh.labels(n, add=["agent-ready"], remove=["delivery-release"])
    record.data["active"] = None
    record.save("Return failed candidate to implementation")


def drive():
    record = gh.Record("releases")
    c = record.data["active"]
    play = Play()

    def save():
        record.save(f"Update release for task {c['issue']}")

    def mutate(action, pct):
        with tempfile.TemporaryDirectory() as temp:
            path = get_bundle(c, Path(temp))[0] if action == "publish_test" else None
            try:
                play.mutate(c, save, action, pct, path)
            except VersionConflict:
                if (
                    action != "publish_test"
                    or c.get("smoke")
                    or c.get("approval_request")
                ):
                    raise
                retired = c.setdefault("unusable_builds", [])
                retired.append(c["build"])
                if len(retired) > REPAIR_LIMIT:
                    c["blocked"] = (
                        "Play rejected three version codes. Reconcile the Play and delivery histories."
                    )
                    gh.notify_once(
                        record,
                        c["issue"],
                        "version-conflict-" + str(c["build"]),
                        c["blocked"],
                    )
                else:
                    for field in ("build", "sha256", "intent", "submitted"):
                        c.pop(field, None)
                save()

    if c.get("blocked"):
        gh.summary(c["blocked"])
        return
    if "build" not in c:
        c["build"] = max(play.highest_build(), record.data.get("last_build", 0)) + 1
        if c["build"] > 2_100_000_000:
            raise ValueError("Android version code limit reached")
        record.data["last_build"] = c["build"]
        save()
    gh.output("build", c["build"])
    if not c.get("sha256"):
        release = existing(c)
        if release and any(a["name"] == "candidate.zip" for a in release["assets"]):
            with tempfile.TemporaryDirectory() as path:
                _, c["sha256"] = get_bundle(c, Path(path))
            save()
        else:
            gh.output("action", "build")
            return
    if c.get("intent"):
        intent = c["intent"]
        mutate(intent["action"], intent["percent"])
        return
    obs = play.observe(c["build"])
    public = obs["production"]
    if c.get("approval_request") and not c.get("approval"):
        state, reviewer = approval_state(c)
        if state == "approved":
            c["approval"] = {
                "artifact": artifact_id(c),
                "reviewer": reviewer,
                "run": c["approval_request"]["run"],
            }
            save()
        elif state == "rejected":
            c["blocked"] = "Production approval was declined for this artifact."
            save()
            gh.notify_once(
                record, c["issue"], "rejected-" + str(c["build"]), c["blocked"]
            )
            return
    cfg = config()["health"]
    health = "insufficient"
    if (
        public["status"] == "live"
        and c.get("observed_percent") == public["percent"]
        and public["percent"] in (10, 50)
    ):
        now = utc_now()
        rows = play.health(c["build"], c["stage_since"], now)
        health = health_status(
            rows,
            c["stage_since"],
            now,
            cfg["min_daily_users"],
            {"crash": cfg["max_crash_percent"], "anr": cfg["max_anr_percent"]},
            cfg["max_age_days"],
        )
    decision = release_action(c, obs["internal"]["status"], public, health)
    gh.summary(f"Build {c['build']} at {c['revision']}. {decision.reason}.")
    action = decision.action
    if action in ("publish_test", "promote", "halt"):
        if c.get("submitted") == {"action": action, "percent": decision.percent}:
            return
        mutate(action, decision.percent)
    elif action == "observe":
        c.update(observed_percent=decision.percent, stage_since=utc_now())
        save()
    elif action == "request_smoke":
        gh.notify_once(
            record,
            c["issue"],
            "smoke-" + str(c["build"]),
            f"Internal build {c['build']} is available. Waiting for the trusted Play-installed smoke worker.",
        )
    elif action == "request_approval":
        if c.get("approval_request"):
            old = gh.api(f"{gh.API}/actions/runs/{c['approval_request']['run']}")
            if old["status"] == "completed":
                c["blocked"] = (
                    "The approval run ended without an approval. Inspect the run before retrying."
                )
                save()
            return
        environment_reviewers()
        c["approval_request"] = {
            "artifact": artifact_id(c),
            "run": os.environ["GITHUB_RUN_ID"],
        }
        save()
        requested_checks = gh.Record(f"tasks/{c['issue']}").data["plan"]["checks"]
        checklist = "\n".join("- " + check for check in requested_checks)
        gh.summary(
            f"### Production approval for build {c['build']}\n\nRevision `{c['revision']}`\n\nSHA-256 `{c['sha256']}`\n\n"
            f"**Release notes**\n\n{c['notes']}\n\n[PR and CI results](https://github.com/{gh.REPO}/pull/{c['pr']}/checks)\n\n"
            f"**Store smoke test**\n\n{c['smoke']['evidence']}\n\n**Requested feature checks**\n\n{checklist}\n\n"
            "The store smoke test covers startup and tournament navigation. Treat a feature check as unverified unless its result appears in the linked CI or device evidence."
        )
        gh.output("action", "approve")
    elif action == "needs_attention":
        reason = (
            c.get("smoke", {}).get("evidence")
            if c.get("smoke", {}).get("result") == "failed"
            else decision.reason
        )
        request_fix(record, c, reason)
    elif action == "complete":
        n = c["issue"]
        task = gh.Record(f"tasks/{n}")
        task.data["production"] = {
            "artifact": artifact_id(c),
            "percent": 100,
            "verified_at": utc_now(),
            "approval": c["approval"],
        }
        task.save("Record verified full production availability")
        gh.notify_once(
            record,
            n,
            "live-" + str(c["build"]),
            f"Build {c['build']} is published to 100% of production. SHA-256 `{c['sha256']}`.",
        )
        gh.api(
            f"{gh.API}/issues/{n}",
            "PATCH",
            {"state": "closed", "state_reason": "completed"},
        )
        gh.labels(n, remove=["delivery-release"])
        record.data["active"] = None
        record.save("Finish released task")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    commands = {
        "select": select,
        "drive": drive,
        "register": register,
        "approve": approve,
        "smoke": smoke,
        "verify": verify,
    }
    parser.add_argument("command", choices=commands.keys())
    try:
        commands[parser.parse_args().command]()
    except PlayError as err:
        if err.status == 400 and "in review" in str(err).lower():
            gh.summary(
                "Play has changes in review. The existing review is preserved. The next run will retry."
            )
        else:
            raise
