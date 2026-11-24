"""GitHub CLI adapter. Credentials are supplied by the hosting job."""

import base64
import json
import os
import re
import subprocess
from pathlib import Path

REPO = os.environ.get("GITHUB_REPOSITORY", "Oziomajnr/ChessTournamentManager")
API = "repos/" + REPO
STATE_BRANCH = "delivery-state"


class GitHubError(RuntimeError):
    def __init__(self, message, status):
        super().__init__(message)
        self.status = status


def api(path, method="GET", body=None):
    args = ["gh", "api", "--method", method, path]
    if body is not None:
        args += ["--input", "-"]
    cp = subprocess.run(
        args,
        input=json.dumps(body) if body is not None else None,
        text=True,
        capture_output=True,
        check=False,
    )
    if cp.returncode:
        status = re.search(r"HTTP (\d{3})", cp.stderr)
        raise GitHubError(
            f"{method} {path.split('?')[0]} failed: {cp.stderr[:400]}",
            int(status.group(1)) if status else None,
        )
    return json.loads(cp.stdout) if cp.stdout.strip() else None


def optional(path):
    try:
        return api(path)
    except GitHubError as err:
        if err.status != 404:
            raise
        return None


def ensure_branch():
    if optional(f"{API}/git/ref/heads/{STATE_BRANCH}"):
        return
    head = api(f"{API}/git/ref/heads/main")["object"]["sha"]
    try:
        api(
            f"{API}/git/refs",
            "POST",
            {"ref": f"refs/heads/{STATE_BRANCH}", "sha": head},
        )
    except GitHubError as err:
        if err.status != 422 or not optional(f"{API}/git/ref/heads/{STATE_BRANCH}"):
            raise


class Record:
    def __init__(self, name):
        self.path = f"{API}/contents/.delivery/{name}.json"
        old = optional(self.path + "?ref=" + STATE_BRANCH)
        self.sha = old["sha"] if old else None
        self.data = json.loads(base64.b64decode(old["content"])) if old else {}

    def save(self, message):
        content = base64.b64encode(
            (json.dumps(self.data, indent=2) + "\n").encode()
        ).decode()
        body = {"branch": STATE_BRANCH, "message": message, "content": content}
        if self.sha:
            body["sha"] = self.sha
        self.sha = api(self.path, "PUT", body)["content"]["sha"]


def output(key, value):
    value = str(value)
    if "\n" in value or "\r" in value:
        raise ValueError("Job outputs must be single-line")
    with open(os.environ["GITHUB_OUTPUT"], "a") as f:
        print(f"{key}={value}", file=f)


def summary(msg):
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as f:
            print(msg, file=f)
    else:
        print(msg)


def comment(issue, message):
    return api(f"{API}/issues/{int(issue)}/comments", "POST", {"body": message})


def notify_once(record, issue, key, message):
    if key in record.data.setdefault("notices", []):
        return
    marker = f"<!-- delivery:{key} -->"
    for page in range(1, 101):
        comments = api(f"{API}/issues/{int(issue)}/comments?per_page=100&page={page}")
        if any(
            c["user"]["login"] == "github-actions[bot]" and marker in c["body"]
            for c in comments
        ):
            break
        if len(comments) < 100:
            comment(issue, marker + "\n" + message)
            break
    record.data["notices"].append(key)
    record.save("Record delivery notification")


def labels(issue, add=(), remove=()):
    if add:
        api(f"{API}/issues/{int(issue)}/labels", "POST", {"labels": list(add)})
    for name in remove:
        try:
            api(f"{API}/issues/{int(issue)}/labels/{name}", "DELETE")
        except GitHubError as err:
            if err.status != 404:
                raise


def dispatch(workflow):
    api(f"{API}/actions/workflows/{workflow}/dispatches", "POST", {"ref": "main"})


def issues(label=None, state="open"):
    page = 1
    label_filter = f"&labels={label}" if label else ""
    while True:
        batch = api(
            f"{API}/issues?state={state}{label_filter}&sort=created&direction=asc&per_page=100&page={page}"
        )
        yield from (item for item in batch if "pull_request" not in item)
        if len(batch) < 100:
            return
        page += 1


def handoff(title, path):
    body = Path(path).read_text() if path != "-" else __import__("sys").stdin.read()
    item = api(
        f"{API}/issues",
        "POST",
        {"title": title, "body": body, "labels": ["agent-ready"]},
    )
    print(item["html_url"])


def trusted_intake(issue):
    page, actor = 1, None
    while True:
        events = api(f"{API}/issues/{issue}/events?per_page=100&page={page}")
        for event in events:
            if event.get("label", {}).get("name") == "agent-ready":
                if event["event"] == "labeled":
                    actor = (event.get("actor") or {}).get("login")
                elif event["event"] == "unlabeled":
                    actor = None
        if len(events) < 100:
            break
        page += 1
    if not actor:
        return False
    if actor == "github-actions[bot]":
        return bool(Record(f"tasks/{int(issue)}").data.get("requeued_by_release"))
    permission = optional(f"{API}/collaborators/{actor}/permission")
    return bool(permission) and permission["permission"] in (
        "admin",
        "write",
        "maintain",
    )


if __name__ == "__main__":
    import argparse

    p = argparse.ArgumentParser(description="Hand a task to the delivery workflow")
    p.add_argument("title")
    p.add_argument("body_file", help="Markdown file, or - for stdin")
    args = p.parse_args()
    handoff(args.title, args.body_file)
