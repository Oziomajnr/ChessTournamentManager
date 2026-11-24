"""GitHub task controller. Always executed from the trusted default branch."""

import argparse
import base64
import json
import os
import re
import subprocess
from pathlib import Path

from delivery.adapters import github as gh
from delivery.agents import config, editable, replacements
from delivery.policy import next_task

REQUEST = Path("task-input.json")
RESULT = Path("agent-result.json")


def source_tree(sha, roots):
    subprocess.run(["git", "fetch", "--quiet", "--no-tags", "origin", sha], check=True)
    entries = subprocess.check_output(["git", "ls-tree", "-rz", sha]).split(b"\0")
    result = {}
    for entry in filter(None, entries):
        meta, name = entry.decode().split("\t", 1)
        if not editable(name, roots):
            continue
        mode, kind, obj = meta.split()
        if mode not in ("100644", "100755") or kind != "blob":
            raise ValueError(f"Unsupported source tree entry: {name}")
        data = subprocess.check_output(["git", "cat-file", "blob", obj]).decode()
        result[name] = data
    if sum(len(v) for v in result.values()) > 550_000:
        raise ValueError("Task source exceeds the configured text input limit")
    return result


def check_result(sha):
    runs = gh.api(
        f"{gh.API}/actions/workflows/android.yml/runs?head_sha={sha}&per_page=30"
    )["workflow_runs"]
    runs = [
        r
        for r in runs
        if r["head_sha"] == sha and r["event"] in ("workflow_dispatch", "pull_request")
    ]
    if not runs:
        return "missing", ""
    latest = max(runs, key=lambda r: (r["run_number"], r["run_attempt"]))
    if latest["status"] != "completed":
        return "pending", latest["html_url"]
    if latest["conclusion"] == "success":
        return "passed", latest["html_url"]
    log = subprocess.run(
        ["gh", "run", "view", str(latest["id"]), "--repo", gh.REPO, "--log-failed"],
        capture_output=True,
        text=True,
    )
    return "failed", latest["html_url"] + "\n" + log.stdout[-12000:]


def dispatch_ci(branch):
    gh.api(
        gh.API + "/actions/workflows/android.yml/dispatches", "POST", {"ref": branch}
    )


def review_gate(pr_number):
    pr = gh.api(f"{gh.API}/pulls/{int(pr_number)}")
    sha = pr["head"]["sha"]
    # Commit statuses belong to a SHA. A second PR with the same head cannot relax it.
    associated = [pr]
    page = 1
    while True:
        batch = gh.api(f"{gh.API}/commits/{sha}/pulls?per_page=100&page={page}")
        associated.extend(batch)
        if len(batch) < 100:
            break
        page += 1
    state, description = (
        "success",
        "Regular pull request follows repository review rules",
    )
    for change in associated:
        head = change["head"]
        tracked = re.fullmatch(r"delivery/task-(\d+)(?:-fix-\d+)?", head["ref"])
        if (
            not tracked
            or head["sha"] != sha
            or (head.get("repo") or {}).get("full_name") != gh.REPO
        ):
            continue
        task = gh.Record(f"tasks/{int(tracked.group(1))}").data
        review = task.get("review", {})
        if task.get("pr") != change["number"] or review.get("sha") != sha:
            state, description = (
                "pending",
                "Waiting for independent review of this revision",
            )
            break
        if not review["passed"]:
            state, description = "failure", "Review requires changes"
            break
        description = "Independent review passed"
    gh.api(
        f"{gh.API}/statuses/{sha}",
        "POST",
        {"state": state, "context": "Task review policy", "description": description},
    )


def stop(record, issue, reason):
    gh.notify_once(record, issue, "needs-attention", reason)
    gh.labels(issue, add=["delivery-attention"], remove=["agent-ready"])


def change_title(issue, title):
    title = " ".join(title.split())[:150]
    if re.search(
        r"\b(?:close[sd]?|fix(?:es|ed)?|resolve[sd]?)\s*:?\s+(?:\S*/\S*)?#\d+",
        title,
        re.IGNORECASE,
    ):
        return f"Implement delivery task {issue}"
    return title or f"Implement delivery task {issue}"


def task_branch(issue, state):
    branch = f"delivery/task-{int(issue)}"
    return branch + (
        f"-fix-{int(state['generation'])}" if state.get("generation") else ""
    )


def task_pulls(branch):
    owner = gh.REPO.split("/")[0]
    return gh.api(f"{gh.API}/pulls?head={owner}:{branch}&base=main&state=all")


def select(item, record):
    n = item["number"]
    s = record.data
    if "intake" not in s:
        s["intake"] = {"title": item["title"], "body": item.get("body") or ""}
        record.save(f"Record approved issue {n}")
    item = {**item, **s["intake"]}
    item["title"] = change_title(n, item["title"])
    branch = task_branch(n, s)
    target = gh.api(gh.API + "/git/ref/heads/main")["object"]["sha"]
    existing = gh.optional(f"{gh.API}/git/ref/heads/{branch}")
    head = existing["object"]["sha"] if existing else target
    pr = gh.api(f"{gh.API}/pulls/{s['pr']}") if s.get("pr") else None
    if pr and (
        pr["base"]["ref"] != "main"
        or pr["base"]["repo"]["full_name"] != gh.REPO
        or pr["head"]["ref"] != branch
        or (pr["head"]["repo"] or {}).get("full_name") != gh.REPO
    ):
        stop(
            record,
            n,
            "The task pull request's source or target changed. " + pr["html_url"],
        )
        return
    if pr and pr["merged_at"]:
        s.update(merged=pr["merge_commit_sha"])
        record.save(f"Task {n} merged")
        gh.labels(n, add=["delivery-release"], remove=["agent-ready"])
        return
    if pr and pr["state"] == "closed":
        stop(
            record,
            n,
            "The task pull request was closed. A maintainer can review it and requeue the task.",
        )
        return
    if s.get("failures", 0) >= 3:
        stop(
            record,
            n,
            "The agent could not complete its proposal after three attempts. See the delivery runs.",
        )
        return
    # Recover a branch or PR created just before the previous runner stopped.
    if existing and not pr and s.get("plan"):
        links = task_pulls(branch)
        if links:
            s["pr"] = links[0]["number"]
            record.save("Record existing task pull request")
            return
        if head != target and gh.api(f"{gh.API}/compare/{target}...{head}")["ahead_by"]:
            gh.output("role", "open-pr")
            gh.output("issue", n)
            gh.output("revision", head)
            return True
    if not s.get("plan"):
        role, detail = next_task(False, bool(pr)), ""
    elif not pr:
        role, detail = next_task(True, False), ""
    else:
        if pr["head"]["sha"] != head:
            raise ValueError("Task pull request has an unexpected head")
        relation = gh.api(f"{gh.API}/compare/{target}...{head}")
        if relation["behind_by"]:
            try:
                gh.api(gh.API + "/merges", "POST", {"base": branch, "head": target})
            except gh.GitHubError as err:
                if err.status != 409:
                    raise
                stop(
                    record,
                    n,
                    "The task branch has a merge conflict with main. " + pr["html_url"],
                )
            return
        checks, detail = check_result(head)
        if checks == "missing":
            dispatch_ci(branch)
            return
        reviewed = s.get("review", {})
        review = (
            ("passed" if reviewed.get("passed") else "failed")
            if reviewed.get("sha") == head
            else "pending"
        )
        decision = next_task(True, True, checks, review, s.get("repairs", 0))
        if decision == "merge":
            review_gate(pr["number"])
            try:
                gh.api(
                    f"{gh.API}/pulls/{pr['number']}/merge",
                    "PUT",
                    {"sha": head, "merge_method": "squash"},
                )
            except gh.GitHubError as err:
                # A required check, review, or new revision will be reconciled on the next run.
                if err.status not in (405, 409):
                    raise
            return
        if decision == "needs_attention":
            stop(
                record,
                n,
                "The change still fails CI or independent review after two repairs. "
                + pr["html_url"],
            )
            return
        if decision == "repair":
            role = "repair"
            detail += "\n" + "\n".join(reviewed.get("findings", []))
        elif decision == "review":
            role = "review"
        else:
            return
    roots = config()["editable_roots"]
    files = source_tree(head, roots)
    req = {
        "role": role,
        "issue": n,
        "title": item["title"],
        "task": item.get("body") or "",
        "base": target,
        "revision": head,
        "editable_roots": roots,
        "plan": s.get("plan"),
        "feedback": detail,
        "files": files,
    }
    if role == "review":
        subprocess.run(
            ["git", "fetch", "--quiet", "--no-tags", "origin", target], check=True
        )
        patch = subprocess.check_output(
            ["git", "diff", "--no-ext-diff", target, head, "--"], text=True
        )
        req["patch"] = patch
    run = os.environ["GITHUB_RUN_ID"] + "." + os.environ["GITHUB_RUN_ATTEMPT"]
    if s.get("claim") != [run, role, head]:
        s["claim"] = [run, role, head]
        if role == "repair":
            s["repairs"] = s.get("repairs", 0) + 1
        s["failures"] = s.get("failures", 0) + 1
        record.save(f"Claim {role} for task {n}")
    req["claim"] = run
    REQUEST.write_text(json.dumps(req))
    gh.output("role", role)
    gh.output("issue", n)
    return True


def choose():
    gh.ensure_branch()
    for item in gh.issues("agent-ready"):
        n = item["number"]
        if gh.trusted_intake(n):
            if select(item, gh.Record(f"tasks/{n}")):
                return
    gh.output("role", "")


def prepare_pr():
    """Validate the live issue and branch with a read-only token before minting an App token."""
    if os.environ["GITHUB_REF"] != "refs/heads/main":
        raise ValueError("PR creation requires the main branch")
    n, head = int(os.environ["TASK_ISSUE"]), os.environ["TASK_REVISION"]
    if n < 1 or not re.fullmatch(r"[0-9a-f]{40}", head):
        raise ValueError("Invalid task issue or revision")
    issue = gh.api(f"{gh.API}/issues/{n}")
    if (
        issue["state"] != "open"
        or "pull_request" in issue
        or not any(label["name"] == "agent-ready" for label in issue["labels"])
        or not gh.trusted_intake(n)
    ):
        return
    s = gh.Record(f"tasks/{n}").data
    if s.get("pr") or not s.get("plan") or not s.get("intake"):
        return
    branch = task_branch(n, s)
    ref = gh.optional(f"{gh.API}/git/ref/heads/{branch}")
    if not ref or ref["object"]["sha"] != head or task_pulls(branch):
        return
    if gh.api(f"{gh.API}/compare/main...{head}")["ahead_by"] < 1:
        return
    body = {
        "title": change_title(n, s["intake"]["title"]),
        "head": branch,
        "base": "main",
        "body": f"Refs #{n}. The delivery controller will close the issue after production availability.",
    }
    (Path(os.environ["RUNNER_TEMP"]) / "task-pr.json").write_text(json.dumps(body))
    gh.output("create", "true")


def commit_files(branch, head, title, changes):
    files = {"additions": [], "deletions": []}
    for name, value in changes.items():
        if value:
            files["additions"].append(
                {"path": name, "contents": base64.b64encode(value.encode()).decode()}
            )
        else:
            files["deletions"].append({"path": name})
    q = """mutation($input:CreateCommitOnBranchInput!){createCommitOnBranch(input:$input){commit{oid}}}"""
    args = {
        "branch": {"repositoryNameWithOwner": gh.REPO, "branchName": branch},
        "expectedHeadOid": head,
        "message": {"headline": title},
        "fileChanges": files,
    }
    result = gh.api("graphql", "POST", {"query": q, "variables": {"input": args}})
    return result["data"]["createCommitOnBranch"]["commit"]["oid"]


def apply():
    req = json.loads(REQUEST.read_text())
    result = json.loads(RESULT.read_text())
    role, n, head = (req[k] for k in ("role", "issue", "revision"))
    record = gh.Record(f"tasks/{n}")
    s = record.data
    if s.get("claim") != [req["claim"], role, head]:
        raise ValueError("Task claim has changed")
    issue = gh.api(f"{gh.API}/issues/{n}")
    if issue["state"] != "open" or not gh.trusted_intake(n):
        raise ValueError("Task is no longer approved for delivery")
    if role == "plan":
        s["plan"] = result
        record.save("Persist task plan")
        gh.notify_once(
            record,
            n,
            "plan",
            "**Acceptance criteria**\n\n"
            + "\n".join("- " + x for x in result["acceptance"])
            + "\n\n**Checks**\n\n"
            + "\n".join("- " + x for x in result["checks"]),
        )
    elif role == "review":
        pr = gh.api(f"{gh.API}/pulls/{s['pr']}")
        if pr["head"]["sha"] != head:
            return
        s["review"] = {
            "sha": head,
            "passed": result["passed"],
            "findings": result["findings"],
        }
        record.save("Record independent review")
        review_gate(s["pr"])
        if result["findings"]:
            gh.notify_once(
                record,
                n,
                "review-" + head,
                "**Independent review**\n\n"
                + "\n".join("- " + x for x in result["findings"]),
            )
    else:
        branch = task_branch(n, s)
        files = source_tree(head, config()["editable_roots"])
        changes = replacements(files, result["edits"], config()["editable_roots"])
        if not gh.optional(f"{gh.API}/git/ref/heads/{branch}"):
            gh.api(
                gh.API + "/git/refs",
                "POST",
                {"ref": "refs/heads/" + branch, "sha": head},
            )
        revision = commit_files(
            branch, head, f"Task {n}: " + req["title"][:90], changes
        )
        if s.get("pr"):
            dispatch_ci(branch)
        else:
            gh.output("issue", n)
            gh.output("revision", revision)
    s["failures"] = 0
    record.save("Finish task agent turn")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    commands = {"choose": choose, "apply": apply, "prepare-pr": prepare_pr}
    parser.add_argument("command", choices=commands)
    commands[parser.parse_args().command]()
