"""Decisions shared by all task hosts, build systems and distribution providers."""

from dataclasses import dataclass
from datetime import datetime, timezone
from math import isfinite

REPAIR_LIMIT = 2
ROLLOUT = (10, 50, 100)


def task_action(has_change, checks, review, repairs):
    if not has_change:
        return "implement"
    if "failed" in (checks, review):
        return "repair" if repairs < REPAIR_LIMIT else "needs_attention"
    if checks == review == "passed":
        return "merge"
    return "wait"


def next_task(planned, has_change, checks="pending", review="pending", repairs=0):
    if not planned:
        return "plan"
    decision = task_action(has_change, checks, review, repairs)
    if decision == "wait" and checks == "missing":
        return "test"
    if decision == "wait" and review == "pending":
        return "review"
    return decision


@dataclass(frozen=True)
class Decision:
    action: str
    reason: str
    percent: int = 0


def artifact_id(candidate):
    return {field: candidate[field] for field in ("revision", "build", "sha256")}


def matches(record, artifact):
    return bool(record) and record.get("artifact") == artifact


def post_upload_decision(candidate):
    """Require one observation per planned check on the exact distributed artifact."""
    reports = candidate.get("post_upload_reports", {})
    waiting = None
    for number, check in enumerate(candidate.get("post_upload_checks", []), 1):
        report = reports.get(check)
        if matches(report, artifact_id(candidate)):
            if report.get("result") == "failed":
                return Decision(
                    "needs_attention",
                    f"Post-upload check {number} failed: {report['evidence']}",
                )
            if report.get("result") == "passed":
                continue
        if waiting is None:
            waiting = Decision(
                "request_report", f"Waiting for post-upload check {number}: {check}"
            )
    return waiting


def health_status(rows, stage_start, now, min_users, limits, max_age_days):
    """Use a complete, fresh period for every metric on the candidate's current stage."""
    if (
        min_users < 1
        or not limits
        or any(not isfinite(v) or v <= 0 for v in limits.values())
    ):
        raise ValueError("Invalid rollout health settings")
    if max_age_days < 1:
        raise ValueError("Expected a positive freshness window")
    result = "healthy"
    for metric, limit in limits.items():
        row = rows.get(metric)
        if not row or not (stage_start <= row["start"] < row["end"] <= now):
            result = "insufficient"
            continue
        if (
            now - row["end"] > max_age_days * 86400
            or not isfinite(row["users"])
            or row["users"] < min_users
        ):
            result = "insufficient"
            continue
        value = row["rate"]
        if not isfinite(value) or not 0 <= value <= 100:
            result = "insufficient"
        elif value >= limit:
            return "regression"
    return result


def release_action(candidate, test_status, production, health="insufficient"):
    artifact = artifact_id(candidate)
    observed = production.get("status", "absent")
    percent = production.get("percent", 0)
    if observed in ("halted", "rejected") or test_status in ("halted", "rejected"):
        return Decision(
            "needs_attention", "The distributor halted or rejected the candidate"
        )
    if test_status == "absent":
        return Decision("publish_test", "Publish this build to testers")
    if test_status != "live":
        return Decision("wait", "Waiting for availability on the testing channel")
    report = post_upload_decision(candidate)
    if report:
        return report
    smoke = candidate.get("smoke")
    if not matches(smoke, artifact):
        return Decision(
            "request_smoke", "Waiting for a smoke test of the distributed build"
        )
    if smoke.get("result") != "passed":
        return Decision("needs_attention", "The installed build failed its smoke test")
    if not matches(candidate.get("approval"), artifact):
        return Decision(
            "request_approval", "Production requires approval for this artifact"
        )
    if observed == "absent":
        return Decision("promote", "Start the approved rollout", ROLLOUT[0])
    if observed != "live":
        return Decision("wait", "Waiting for distributor review or publication")
    if percent == ROLLOUT[-1]:
        return Decision("complete", "This build is available at 100 percent")
    if percent not in ROLLOUT:
        return Decision(
            "needs_attention", "The distributor reports an unexpected rollout fraction"
        )
    if candidate.get("observed_percent") != percent:
        return Decision("observe", "Record when this stage became available", percent)
    if health == "regression":
        return Decision(
            "halt", "The candidate exceeded a configured health threshold", percent
        )
    if health != "healthy":
        return Decision("wait", "Waiting for sufficient fresh data for this stage")
    return Decision(
        "promote",
        "This stage passed its health checks",
        ROLLOUT[ROLLOUT.index(percent) + 1],
    )


def utc_now():
    return int(datetime.now(timezone.utc).timestamp())
