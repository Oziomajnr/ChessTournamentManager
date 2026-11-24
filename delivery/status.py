"""Publish an issue-by-issue view of the existing task and release records."""

import argparse
import html
import json
import os
import re
import subprocess
from pathlib import Path
from typing import NamedTuple

from delivery import task
from delivery.adapters import github as gh
from delivery.status_graph import LANES, mermaid

REPO_URL = f"https://github.com/{gh.REPO}"
_owner, _repo = gh.REPO.split("/")
PAGES_URL = f"https://{_owner.lower()}.github.io/{_repo}/"


class Position(NamedTuple):
    stage: str
    detail: str
    step: str


def release_stage(candidate, notices):
    build = candidate.get("build")
    if candidate.get("blocked"):
        return Position("Needs attention", candidate["blocked"], "attention")
    if not candidate.get("sha256"):
        return Position(
            "Building and signing", "Verify and archive the signed bundle.", "build"
        )
    mutation = candidate.get("intent") or candidate.get("submitted") or {}
    if mutation.get("action") == "halt":
        return Position(
            "Halting rollout",
            "Confirm the halt with Play and return the issue for repair.",
            "rollout",
        )
    if candidate.get("approval"):
        live, requested = candidate.get("observed_percent"), mutation.get("percent")
        if mutation.get("action") == "promote" and requested != live:
            detail = f"{live}% was confirmed live. " if live else ""
            return Position(
                "Production rollout",
                detail + f"Waiting for Play to confirm {requested}%.",
                "rollout",
            )
        if live:
            return Position(
                f"Live at {live}%",
                "Wait for sufficient healthy crash and ANR data.",
                "rollout",
            )
        return Position("Production approved", "Start the staged rollout.", "approval")
    if candidate.get("approval_request"):
        return Position(
            "Awaiting production approval",
            "Review this signed build in the production environment.",
            "approval",
        )
    smoke = candidate.get("smoke", {})
    if smoke.get("result") == "failed":
        return Position(
            "Device check failed", "Prepare a repair for the same issue.", "device"
        )
    if smoke.get("result") == "passed":
        return Position(
            "Device check passed", "Request owner approval for this build.", "device"
        )
    if f"smoke-{build}" in notices:
        return Position(
            "Internal testing",
            "Verify the Play-installed build on an enrolled tester device.",
            "device",
        )
    if mutation.get("action") == "publish_test":
        return Position(
            "Internal distribution",
            "Waiting for Play to confirm internal availability.",
            "internal",
        )
    return Position(
        "Signed bundle ready",
        "Upload the verified bundle to internal testing.",
        "build",
    )


def issue_stage(issue, state, candidate, notices, pr=None, ci=None, hosted=False):
    labels = {label["name"] for label in issue["labels"]}
    if state.get("production", {}).get("percent") == 100:
        return Position(
            "Published at 100%", "Production availability confirmed.", "published"
        )
    if candidate and candidate["issue"] == issue["number"]:
        return release_stage(candidate, notices)
    if issue["state"] == "closed":
        return Position(
            "Closed", "Issue closed outside verified production delivery.", "closed"
        )
    if "delivery-attention" in labels:
        return Position(
            "Needs attention",
            state.get("attention") or "See the delivery update on the issue.",
            "attention",
        )
    if (
        state.get("merged")
        or "delivery-release" in labels
        or (pr and pr.get("merged_at"))
    ):
        return Position(
            "Release queue",
            "Select the recorded merge for internal distribution.",
            "queue",
        )
    if "agent-ready" not in labels:
        return Position(
            "Backlog", "A maintainer can label this issue agent-ready.", "backlog"
        )
    local, review = state.get("local", {}), state.get("review", {})
    if local.get("review_error"):
        return Position("Local review needs attention", local["review_error"], "review")
    if local.get("reviewing"):
        return Position(
            "Local review requested",
            "Complete the independent review for the submitted commit.",
            "review",
        )
    if pr:
        if pr["state"] == "closed":
            return Position(
                "Needs attention", "The task PR closed without merging.", "attention"
            )
        head = pr["head"]["sha"]
        if review.get("sha") == head and not review.get("passed"):
            return Position(
                "Changes requested",
                "Address the independent review and submit a new commit.",
                "review",
            )
        if ci and ci["status"] == "completed" and ci["conclusion"] != "success":
            return Position(
                "CI needs attention",
                "Inspect the failed or canceled checks and repair the task.",
                "ci",
            )
        if local and local.get("submitted") != head:
            return Position(
                "Awaiting local submission",
                "Submit the current PR commit from the owning checkout.",
                "code",
            )
        if not ci or ci["status"] != "completed":
            return Position(
                "CI pending",
                "Wait for build, policy and device checks on the PR commit.",
                "ci",
            )
        if review.get("sha") != head:
            return Position(
                "Awaiting code review", "Review the current PR commit.", "review"
            )
        if pr.get("mergeable_state") == "behind":
            return Position(
                "Branch update needed",
                "Sync with main and submit the new commit for review.",
                "merge",
            )
        return Position(
            "Awaiting merge", "Merge when GitHub branch protection permits it.", "merge"
        )
    if local.get("submitted"):
        return Position(
            "Opening pull request",
            "The main-branch GitHub App job processes the submitted commit.",
            "pull",
        )
    if local:
        if not state.get("plan"):
            return Position(
                "Planning locally",
                f"{local['actor']} records acceptance criteria and checks.",
                "plan",
            )
        return Position(
            "Implementing locally",
            f"{local['actor']} implements the saved plan in the task worktree.",
            "code",
        )
    if not hosted:
        detail = "The plan is saved. " if state.get("plan") else ""
        return Position(
            "Ready for local pickup",
            detail + "Ask local Codex to pick up this issue.",
            "ready",
        )
    if state.get("failures") and state.get("claim"):
        return Position(
            "Hosted task attempt",
            f"See the {state['claim'][1]} run for progress or errors.",
            {
                "plan": "plan",
                "review": "review",
                "implement": "code",
                "repair": "code",
            }.get(state["claim"][1], "ready"),
        )
    if state.get("plan"):
        return Position(
            "Ready for implementation",
            "The hosted controller picks up the saved plan.",
            "code",
        )
    return Position(
        "Queued for planning",
        "The hosted controller selects a maintainer-approved issue.",
        "ready",
    )


def cell(value):
    # Issue titles and comments are user content, including Markdown and HTML.
    value = html.escape(" ".join(str(value).split()), quote=False)
    return re.sub(r"([\\|*\x60_~\[\]])", r"\\\1", value)


def collect(issues, states, release, hosted=False):
    rows = []
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
            links.append(
                {
                    "label": f"PR #{int(pr_number)}",
                    "url": f"{REPO_URL}/pull/{int(pr_number)}",
                }
            )
            if issue["state"] == "open" and not state.get("merged"):
                pr = gh.api(f"{gh.API}/pulls/{int(pr_number)}")
                if pr["state"] == "open":
                    ci = task.latest_ci(pr["head"]["sha"])
                    if ci:
                        links.append(
                            {
                                "label": "CI",
                                "url": f"{REPO_URL}/actions/runs/{int(ci['id'])}",
                            }
                        )
        claim = state.get("claim", [])
        if claim and not local and re.fullmatch(r"\d+\.\d+", claim[0]):
            run, attempt = claim[0].split(".")
            links.append(
                {
                    "label": "Task run",
                    "url": f"{REPO_URL}/actions/runs/{run}/attempts/{attempt}",
                }
            )
        if re.fullmatch(r"[0-9a-f]{40}", local.get("submitted", "")):
            revision = local["submitted"]
            links.append(
                {
                    "label": f"Commit {revision[:7]}",
                    "url": f"{REPO_URL}/commit/{revision}",
                }
            )
        if active.get("issue") == number and active.get("approval_request"):
            run = int(active["approval_request"]["run"])
            links.append(
                {
                    "label": "Production approval",
                    "url": f"{REPO_URL}/actions/runs/{run}",
                }
            )
        position = issue_stage(
            issue, state, active, release.get("notices", []), pr, ci, hosted
        )
        if position.stage in (
            "Ready for local pickup",
            "Queued for planning",
            "Ready for implementation",
        ) and not gh.trusted_intake(number):
            position = Position(
                "Awaiting maintainer",
                "A maintainer must apply the agent-ready label to authorize pickup.",
                "attention",
            )
        rows.append(
            {
                "number": number,
                "title": issue["title"],
                "url": f"{REPO_URL}/issues/{number}",
                "state": issue["state"],
                **position._asdict(),
                "links": links,
            }
        )
    return {"version": 1, "lanes": LANES, "issues": rows}


def render(board):
    text = [
        "# Issue delivery status",
        "",
        f"[Watch the live workflow graph]({PAGES_URL})",
        "",
        "Issue numbers mark their current stage. The live viewer refreshes automatically.",
        "",
        *mermaid(board),
        "",
    ]
    for state, heading in (("open", "Open issues"), ("closed", "Closed issues")):
        items = [i for i in board["issues"] if i["state"] == state]
        if not items and state == "closed":
            continue
        text.extend([f"## {heading}", ""])
        if not items:
            text.extend(["No open issues.", ""])
            continue
        text.extend(
            ["| Issue | Stage | Next step | Evidence |", "| --- | --- | --- | --- |"]
        )
        for item in items:
            link = f"[#{item['number']} {cell(item['title'])}]({item['url']})"
            evidence = (
                " · ".join(f"[{e['label']}]({e['url']})" for e in item["links"]) or "—"
            )
            text.append(
                f"| {link} | {cell(item['stage'])} | {cell(item['detail'])} | {evidence} |"
            )
        text.append("")
    return "\n".join(text)


def commit_view(root, revision, board, markdown):
    files = {
        "STATUS.md": markdown,
        "status.json": json.dumps(board, ensure_ascii=False, indent=2) + "\n",
    }
    changes = {
        name: value
        for name, value in files.items()
        if not (root / name).is_file() or (root / name).read_text() != value
    }
    if changes:
        task.commit_files(
            gh.STATE_BRANCH, revision, "Refresh issue delivery graph", changes
        )


def refresh(state_dir, hosted, publish):
    root = state_dir.parent
    for attempt in range(3):
        revision = (
            subprocess.check_output(
                ["git", "-C", root, "rev-parse", "HEAD"], text=True
            ).strip()
            if publish
            else None
        )
        states = {
            int(p.stem): json.loads(p.read_text())
            for p in (state_dir / "tasks").glob("*.json")
        }
        releases = state_dir / "releases.json"
        release = json.loads(releases.read_text()) if releases.is_file() else {}
        board = collect(gh.issues(state="all"), states, release, hosted)
        markdown = render(board)
        if not publish:
            return markdown
        try:
            commit_view(root, revision, board, markdown)
            return markdown
        except gh.GitHubError:
            current = gh.api(f"{gh.API}/git/ref/heads/{gh.STATE_BRANCH}")["object"][
                "sha"
            ]
            if current == revision or attempt == 2:
                raise
            # A controller advanced while we rendered. Read its new records before retrying.
            subprocess.run(
                [
                    "git",
                    "-C",
                    root,
                    "-c",
                    "credential.helper=!gh auth git-credential",
                    "pull",
                    "--ff-only",
                    "origin",
                    gh.STATE_BRANCH,
                ],
                check=True,
            )


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
    gh.summary(
        refresh(
            args.state_dir, os.getenv("HOSTED_AGENT_ENABLED") == "true", args.publish
        )
    )


if __name__ == "__main__":
    main()
