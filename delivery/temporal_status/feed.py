"""Read published status snapshots in commit order, including changes missed while offline."""

import base64
import json
from pathlib import Path

from delivery.adapters import github as gh


def revisions_after(cursor):
    pending = []
    page = 1
    while True:
        commits = gh.api(
            f"{gh.API}/commits?sha={gh.STATE_BRANCH}&path=status.json&per_page=100&page={page}"
        )
        for c in commits:
            if c["sha"] == cursor:
                return pending[::-1]
            pending.append((c["sha"], c["commit"]["committer"]["date"]))
        if len(commits) < 100:
            if cursor:
                raise ValueError(
                    "The saved status revision is missing from delivery-state history."
                )
            return pending[::-1]
        page += 1


def read_issues(revision):
    f = gh.api(f"{gh.API}/contents/status.json?ref={revision}")
    if f["encoding"] != "base64":
        # Large files have metadata only in the contents response.
        f = gh.api(f"{gh.API}/git/blobs/{f['sha']}")
    data = json.loads(base64.b64decode(f["content"]))
    if data.get("version") != 1 or not isinstance(data.get("issues"), list):
        raise ValueError("Unsupported delivery status snapshot")
    return {str(int(i["number"])): i for i in data["issues"]}


def changes(previous, current):
    for key, item in current.items():
        old = previous.get(key)
        if item != old and not (
            old
            and old["state"] == item["state"] == "closed"
            and old["stage"] == item["stage"]
        ):
            label = (
                item["stage"]
                if not old or item["stage"] != old["stage"]
                else "Issue details updated"
            )
            yield label, item
    for key in previous.keys() - current.keys():
        old = previous[key]
        if old["state"] != "closed":
            yield (
                "No longer tracked",
                {
                    **old,
                    "state": "closed",
                    "step": "closed",
                    "stage": "No longer tracked",
                    "detail": "The issue left the repository's published status view.",
                },
            )


def read_checkpoint(path):
    return (
        json.loads(path.read_text())
        if path.is_file()
        else {"revision": None, "sequence": 0, "issues": {}}
    )


def save_checkpoint(path: Path, value):
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(value, ensure_ascii=False))
    tmp.replace(path)
