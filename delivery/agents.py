"""Transport-independent request/response format for the planner, implementer and reviewer."""

import json
import re
from pathlib import Path, PurePosixPath


def obj(**fields):
    return {
        "type": "object",
        "properties": fields,
        "required": list(fields),
        "additionalProperties": False,
    }


TEXT = {"type": "string"}
STRINGS = {"type": "array", "items": TEXT}
EDIT = obj(path=TEXT, before=TEXT, after=TEXT)
SCHEMAS = {
    "plan": obj(acceptance=STRINGS, checks=STRINGS, release_notes=TEXT),
    "implement": obj(edits={"type": "array", "items": EDIT}, summary=TEXT),
    "repair": obj(edits={"type": "array", "items": EDIT}, summary=TEXT),
    "review": obj(passed={"type": "boolean"}, findings=STRINGS),
}
INSTRUCTIONS = {
    "plan": "Write concrete acceptance criteria, test cases and a short user-facing release note for this task.",
    "implement": "Implement the task and its tests. Return literal, unique before/after replacements. For a new file use empty before. For deletion match the entire file and use empty after.",
    "repair": "Fix the reported failure and retain the requested behavior. Return literal, unique before/after replacements against the supplied current files.",
    "review": "Independently review the proposed changes against the task and tests. Check reliability, data integrity, security, regressions, and missing tests. Pass only if the current changes satisfy the acceptance criteria. Give actionable findings.",
}


def editable(path, roots):
    p = PurePosixPath(path)
    return (
        bool(re.fullmatch(r"[A-Za-z0-9_./ -]+", path))
        and str(p) == path
        and not p.is_absolute()
        and all(not s.startswith(".") for s in p.parts)
        and "\\" not in path
        and len(path) < 250
        and p.suffix in {".kt", ".java", ".xml", ".md", ".txt", ".json"}
        and any(path.startswith(root) for root in roots)
    )


def replacements(files, edits, roots):
    if not 1 <= len(edits) <= 80:
        raise ValueError("Expected between 1 and 80 source edits")
    modified = {}
    for edit in edits:
        name, before, after = (edit[k] for k in ("path", "before", "after"))
        if not editable(name, roots) or len(after.encode()) > 128_000:
            raise ValueError("Proposed edit is outside the supported source scope")
        old = modified.get(name, files.get(name))
        if old is None:
            if before or not after:
                raise ValueError("New file must have empty before and nonempty after")
            modified[name] = after
        elif not before or old.count(before) != 1:
            raise ValueError(f"Edit context is ambiguous or out of date in {name}")
        else:
            modified[name] = old.replace(before, after, 1)
    changed = {k: v for k, v in modified.items() if v != files.get(k)}
    if not changed:
        raise ValueError("No change in the proposed edits")
    return changed


def config():
    return json.loads((Path(__file__).parent / "config.json").read_text())
