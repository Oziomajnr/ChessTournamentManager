"""Publish an issue-by-issue view of the existing task and release records."""

import argparse
import base64
import html
import json
import os
import re
from pathlib import Path

from delivery import task
from delivery.adapters import github as gh

REPO_URL = f"https://github.com/{gh.REPO}"


def release_stage(candidate, notices):
    build = candidate.get("build")
    if candidate.get("blocked"):
        return "Needs attention", candidate["blocked"]
    if not candidate.get("sha256"):
        return "Building and signing", "Verify and archive the signed bundle."
    mutation = candidate.get("intent") or candidate.get("submitted") or {}
    if mutation.get("action") == "halt":
        return (
            "Halting rollout",
            "Confirm the halt with Play and return the issue for repair.",
        )
    if candidate.get("approval"):
        live, requested = candidate.get("observed_percent"), mutation.get("percent")
        if mutation.get("action") == "promote" and requested != live:
            detail = f"{live}% was confirmed live. " if live else ""
            return (
                "Production rollout",
                detail + f"Waiting for Play to confirm {requested}%.",
            )
        if live:
            return f"Live at {live}%", "Wait for sufficient healthy crash and ANR data."
        return "Production approved", "Start the staged rollout."
    if candidate.get("approval_request"):
        return (
            "Awaiting production approval",
            "Review this signed build in the production environment.",
        )
    smoke = candidate.get("smoke", {})
    if smoke.get("result") == "failed":
        return "Device check failed", "Prepare a repair for the same issue."
    if smoke.get("result") == "passed":
        return "Device check passed", "Request owner approval for this build."
    if f"smoke-{build}" in notices:
        return (
            "Internal testing",
            "Verify the Play-installed build on an enrolled tester device.",
        )
    if mutation.get("action") == "publish_test":
        return (
            "Internal distribution",
            "Waiting for Play to confirm internal availability.",
        )
    return "Signed bundle ready", "Upload the verified bundle to internal testing."


def issue_stage(issue, state, candidate, notices, pr=None, ci=None, hosted=False):
    labels = {label["name"] for label in issue["labels"]}
    if state.get("production", {}).get("percent") == 100:
        return "Published at 100%", "Production availability confirmed."
    if candidate and candidate["issue"] == issue["number"]:
        return release_stage(candidate, notices)
    if issue["state"] == "closed":
        return "Closed", "Issue closed outside verified production delivery."
    if "delivery-attention" in labels:
        return "Needs attention", state.get(
            "attention"
        ) or "See the delivery update on the issue."
    if (
        state.get("merged")
        or "delivery-release" in labels
        or (pr and pr.get("merged_at"))
    ):
        return "Release queue", "Select the recorded merge for internal distribution."
    if "agent-ready" not in labels:
        return "Backlog", "A maintainer can label this issue agent-ready."
    local, review = state.get("local", {}), state.get("review", {})
    if local.get("review_error"):
        return "Local review needs attention", local["review_error"]
    if local.get("reviewing"):
        return (
            "Local review requested",
            "Complete the independent review for the submitted commit.",
        )
    if pr:
        if pr["state"] == "closed":
            return "Needs attention", "The task PR closed without merging."
        head = pr["head"]["sha"]
        if review.get("sha") == head and not review.get("passed"):
            return (
                "Changes requested",
                "Address the independent review and submit a new commit.",
            )
        if ci and ci["status"] == "completed" and ci["conclusion"] != "success":
            return (
                "CI needs attention",
                "Inspect the failed or canceled checks and repair the task.",
            )
        if local and local.get("submitted") != head:
            return (
                "Awaiting local submission",
                "Submit the current PR commit from the owning checkout.",
            )
        if not ci or ci["status"] != "completed":
            return (
                "CI pending",
                "Wait for build, policy and device checks on the PR commit.",
            )
        if review.get("sha") != head:
            return "Awaiting code review", "Review the current PR commit."
        if pr.get("mergeable_state") == "behind":
            return (
                "Branch update needed",
                "Sync with main and submit the new commit for review.",
            )
        return "Awaiting merge", "Merge when GitHub branch protection permits it."
    if local.get("submitted"):
        return (
            "Opening pull request",
            "The main-branch GitHub App job processes the submitted commit.",
        )
    if local:
        if not state.get("plan"):
            return (
                "Planning locally",
                f"{local['actor']} records acceptance criteria and checks.",
            )
        return (
            "Implementing locally",
            f"{local['actor']} implements the saved plan in the task worktree.",
        )
    if not hosted:
        detail = "The plan is saved. " if state.get("plan") else ""
        return (
            "Ready for local pickup",
            detail + "Ask local Codex to pick up this issue.",
        )
    if state.get("failures") and state.get("claim"):
        return (
            "Hosted task attempt",
            f"See the {state['claim'][1]} run for progress or errors.",
        )
    if state.get("plan"):
        return (
            "Ready for implementation",
            "The hosted controller picks up the saved plan.",
        )
    return (
        "Queued for planning",
        "The hosted controller selects a maintainer-approved issue.",
    )


def cell(value):
    # Issue titles and comments are user content, including Markdown and HTML.
    value = html.escape(" ".join(str(value).split()), quote=False)
    return re.sub(r"([\\|*\x60_~\[\]])", r"\\\1", value)


def render(issues, states, release, hosted=False):
    rows = {"Open issues": [], "Closed issues": []}
    active = release.get("active") or {}
    for issue in issues:
        number = int(issue["number"])
        state = states.get(number, {})
        pr = ci = None
        links = []
        pr_number = state.get("pr")
        local = state.get("local", {})
        if not pr_number and local.get("submitted") and issue["state"] == "open":
            pulls = task.task_pulls(task.task_branch(number, state))
            pr_number = pulls[0]["number"] if pulls else None
        if pr_number:
            links.append(f"[PR #{pr_number}]({REPO_URL}/pull/{int(pr_number)})")
            if issue["state"] == "open" and not state.get("merged"):
                pr = gh.api(f"{gh.API}/pulls/{int(pr_number)}")
                if pr["state"] == "open":
                    ci = task.latest_ci(pr["head"]["sha"])
                    if ci:
                        links.append(f"[CI]({REPO_URL}/actions/runs/{int(ci['id'])})")
        claim = state.get("claim", [])
        if claim and not local and re.fullmatch(r"\d+\.\d+", claim[0]):
            run, attempt = claim[0].split(".")
            links.append(
                f"[Task run]({REPO_URL}/actions/runs/{run}/attempts/{attempt})"
            )
        if re.fullmatch(r"[0-9a-f]{40}", local.get("submitted", "")):
            revision = local["submitted"]
            links.append(f"[Commit {revision[:7]}]({REPO_URL}/commit/{revision})")
        if active.get("issue") == number and active.get("approval_request"):
            run = int(active["approval_request"]["run"])
            links.append(f"[Production approval]({REPO_URL}/actions/runs/{run})")
        stage, detail = issue_stage(
            issue, state, active, release.get("notices", []), pr, ci, hosted
        )
        if stage in (
            "Ready for local pickup",
            "Queued for planning",
            "Ready for implementation",
        ) and not gh.trusted_intake(number):
            stage, detail = (
                "Awaiting maintainer",
                "A maintainer must apply the agent-ready label to authorize pickup.",
            )
        link = f"[#{number} {cell(issue['title'])}]({REPO_URL}/issues/{number})"
        section = "Open issues" if issue["state"] == "open" else "Closed issues"
        rows[section].append(
            f"| {link} | {cell(stage)} | {cell(detail)} | {' · '.join(links) or '—'} |"
        )
    text = [
        "# Issue delivery status",
        "",
        "GitHub Actions refreshes this view from the issue queue, saved delivery records and PR checks.",
        "",
    ]
    for section, items in rows.items():
        if section == "Closed issues" and not items:
            continue
        text.extend([f"## {section}", ""])
        if items:
            text.extend(
                [
                    "| Issue | Stage | Next step | Evidence |",
                    "| --- | --- | --- | --- |",
                    *items,
                    "",
                ]
            )
        else:
            text.extend(["No open issues.", ""])
    return "\n".join(text)


def publish(text):
    path = f"{gh.API}/contents/STATUS.md"
    content = base64.b64encode(text.encode()).decode()
    for attempt in range(3):
        old = gh.optional(path + "?ref=" + gh.STATE_BRANCH)
        if old and base64.b64decode(old["content"]).decode() == text:
            return
        body = {
            "branch": gh.STATE_BRANCH,
            "message": "Refresh issue delivery status",
            "content": content,
        }
        if old:
            body["sha"] = old["sha"]
        try:
            gh.api(path, "PUT", body)
            return
        except gh.GitHubError as err:
            if err.status != 409 or attempt == 2:
                raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--state-dir",
        type=Path,
        required=True,
        help=".delivery in a snapshot of delivery-state",
    )
    parser.add_argument("--publish", action="store_true")
    args = parser.parse_args()
    states = {
        int(p.stem): json.loads(p.read_text())
        for p in (args.state_dir / "tasks").glob("*.json")
    }
    releases = args.state_dir / "releases.json"
    release = json.loads(releases.read_text()) if releases.is_file() else {}
    text = render(
        gh.issues(state="all"),
        states,
        release,
        os.getenv("HOSTED_AGENT_ENABLED") == "true",
    )
    gh.summary(text)
    if args.publish:
        publish(text)


if __name__ == "__main__":
    main()
