"""Android Publisher and Play Developer Reporting adapters for a single application."""

import hashlib
import json
import os
from contextlib import contextmanager
from datetime import date, datetime, time, timedelta
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from zoneinfo import ZoneInfo

from delivery.adapters.google_auth import token
from delivery.agents import config

PACIFIC = ZoneInfo("America/Los_Angeles")
PUBLISHED = "RELEASE_LIFECYCLE_STATE_PUBLISHED"


class PlayError(RuntimeError):
    def __init__(self, status, message):
        self.status = status
        super().__init__(f"Play returned {status}: {message[:380]}")


def track_state(track, summaries, build):
    code = str(build)
    rows = [r for r in track.get("releases", []) if code in r.get("versionCodes", [])]
    lives = [
        r["releaseLifecycleState"]
        for r in summaries
        if any(str(a["versionCode"]) == code for a in r.get("activeArtifacts", []))
    ]
    if not rows:
        return {"status": "pending" if lives else "absent", "percent": 0}
    if len(rows) != 1 or rows[0]["versionCodes"] != [code]:
        return {"status": "ambiguous", "percent": 0}
    r = rows[0]
    if r["status"] == "halted":
        return {"status": "halted", "percent": 0}
    if any(s.endswith("_NOT_APPROVED") for s in lives):
        return {"status": "rejected", "percent": 0}
    fraction = (
        100 if r["status"] == "completed" else round(r.get("userFraction", 0) * 100, 4)
    )
    live = (
        bool(lives)
        and all(s == PUBLISHED for s in lives)
        and r["status"] in ("completed", "inProgress")
    )
    return {"status": "live" if live else "pending", "percent": fraction}


def updated_track(track, code, action, pct, notes, supersedes=()):
    code = str(code)
    versions = track.get("releases", [])
    own = [r for r in versions if code in r.get("versionCodes", [])]
    if len(own) > 1 or (own and own[0]["versionCodes"] != [code]):
        raise ValueError("Candidate shares a release with other artifacts")
    other = [r for r in versions if r not in own]
    allowed_halts = {str(v) for v in supersedes}
    other = [
        r
        for r in other
        if not (
            r.get("status") == "halted"
            and r.get("versionCodes")
            and set(r["versionCodes"]).issubset(allowed_halts)
        )
    ]
    if any(r.get("status") != "completed" for r in other):
        raise ValueError("Another release is active on this track")
    if any(int(v) > int(code) for r in other for v in r.get("versionCodes", [])):
        raise ValueError("A newer artifact is already on this track")
    if action == "halt":
        if not own or own[0].get("status") not in ("inProgress", "halted"):
            raise ValueError("A halt requires an active staged release")
        release = dict(own[0], status="halted")
    else:
        release = (
            dict(own[0])
            if own
            else {
                "versionCodes": [code],
                "releaseNotes": [{"language": "en-US", "text": notes[:500]}],
            }
        )
        if own and own[0].get("status") == "halted":
            raise ValueError("A maintainer must resolve a halted release")
        if pct == 100:
            release["status"] = "completed"
            release.pop("userFraction", None)
        else:
            current = (
                100
                if release.get("status") == "completed"
                else release.get("userFraction", 0) * 100
            )
            if current > pct:
                raise ValueError("Refusing to reduce a live rollout fraction")
            release.update(status="inProgress", userFraction=pct / 100)
    if release["status"] == "completed":
        other = []
    return {"track": track["track"], "releases": [release, *other]}


class Play:
    def __init__(self):
        settings = config()
        self.package = settings["package"]
        self.base = (
            "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/"
            + self.package
        )
        credential = json.loads(os.environ["GOOGLE_PLAY_SERVICE_ACCOUNT_JSON"])
        if credential.get("client_email") != settings["publisher"]:
            raise ValueError("The configured key belongs to a different Play publisher")
        self.token = token(credential)

    def request(self, method, url, body=None, media=False):
        data = (
            body if media else json.dumps(body).encode() if body is not None else None
        )
        headers = {
            "Authorization": "Bearer " + self.token,
            "Content-Type": "application/octet-stream" if media else "application/json",
        }
        try:
            with urlopen(
                Request(url, data=data, method=method, headers=headers), timeout=180
            ) as res:
                raw = res.read()
        except HTTPError as e:
            try:
                detail = json.loads(e.read())["error"]["message"]
            except (KeyError, ValueError, TypeError):
                detail = "Request failed"
            raise PlayError(e.code, detail) from None
        return json.loads(raw) if raw else {}

    def new_edit(self):
        return self.request("POST", self.base + "/edits", {})["id"]

    @contextmanager
    def inspection(self):
        edit = self.new_edit()
        try:
            yield self.base + "/edits/" + edit
        finally:
            try:
                self.request("DELETE", self.base + "/edits/" + edit)
            except PlayError as e:
                if e.status != 404:
                    raise

    def highest_build(self):
        with self.inspection() as url:
            bundles = self.request("GET", url + "/bundles").get("bundles", [])
            tracks = self.request("GET", url + "/tracks").get("tracks", [])
        versions = [int(b["versionCode"]) for b in bundles]
        versions += [
            int(v)
            for t in tracks
            for r in t.get("releases", [])
            for v in r.get("versionCodes", [])
        ]
        return max(versions, default=0)

    def observe(self, build):
        with self.inspection() as url:
            tracks = {
                name: self.read_track(url, name) for name in ("internal", "production")
            }
        return {
            name: track_state(
                t,
                self.request("GET", self.base + "/tracks/" + name + "/releases").get(
                    "releases", []
                ),
                build,
            )
            for name, t in tracks.items()
        }

    def read_track(self, edit_url, name):
        try:
            return self.request("GET", edit_url + "/tracks/" + name)
        except PlayError as error:
            if error.status == 404:
                return {"track": name, "releases": []}
            raise

    def mutate(self, candidate, save, action, pct, bundle=None):
        """Persist and reconcile an edit across process loss, upload failures and commit timeouts."""
        track = "internal" if action == "publish_test" else "production"
        intent = candidate.get("intent")
        if not intent:
            intent = candidate["intent"] = {"action": action, "percent": pct}
            save()
        if (action, pct) != (intent["action"], intent["percent"]):
            raise ValueError("There is another release change in flight")
        edit = intent.get("edit")
        if edit:
            try:
                self.request("GET", self.base + "/edits/" + edit)
            except PlayError as e:
                if e.status not in (404, 410) and not (
                    e.status == 400 and "expir" in str(e).lower()
                ):
                    raise
                edit = None
        resumed_edit = bool(edit)
        if not edit:
            edit = intent["edit"] = self.new_edit()
            save()
        url = self.base + "/edits/" + edit
        current = self.read_track(url, track)
        desired = updated_track(
            current,
            candidate["build"],
            action,
            pct or 100,
            candidate["notes"],
            candidate.get("supersedes", []),
        )
        if action == "publish_test":
            existing = self.request("GET", url + "/bundles").get("bundles", [])
            same = next(
                (b for b in existing if int(b["versionCode"]) == candidate["build"]),
                None,
            )
            if same is None:
                data = Path(bundle).read_bytes()
                if hashlib.sha256(data).hexdigest() != candidate["sha256"]:
                    raise ValueError("Stored AAB digest mismatch")
                upload = url.replace(
                    "/androidpublisher/v3/", "/upload/androidpublisher/v3/"
                )
                same = self.request(
                    "POST", upload + "/bundles?uploadType=media", data, media=True
                )
            if (
                int(same["versionCode"]) != candidate["build"]
                or same.get("sha256", "").lower() != candidate["sha256"]
            ):
                raise ValueError(
                    "Play returned a different version or SHA-256 for this bundle"
                )
        # If a previous commit succeeded before its acknowledgement arrived, the new
        # edit already contains the desired release. A saved uncommitted edit still needs commit.
        if current != desired:
            self.request("PUT", url + "/tracks/" + track, desired)
        if current == desired and not resumed_edit:
            self.request("DELETE", url)
        else:
            self.request(
                "POST", url + ":commit?changesInReviewBehavior=ERROR_IF_IN_REVIEW"
            )
        candidate.pop("intent")
        candidate["submitted"] = {"action": action, "percent": pct}
        save()

    def health(self, build, stage_start, now):
        cfg = config()["health"]
        today = datetime.fromtimestamp(now, PACIFIC).date()
        first = max(
            datetime.fromtimestamp(stage_start, PACIFIC).date(),
            today - timedelta(days=cfg["max_age_days"] + 1),
        )
        if first >= today:
            return {}

        def period(d):
            return {
                "year": d.year,
                "month": d.month,
                "day": d.day,
                "timeZone": {"id": "America/Los_Angeles"},
            }

        def timestamp(d):
            return int(datetime.combine(d, time.min, PACIFIC).timestamp())

        rows = {}
        for prefix, metric in [
            ("crash", "userPerceivedCrashRate"),
            ("anr", "userPerceivedAnrRate"),
        ]:
            url = f"https://playdeveloperreporting.googleapis.com/v1beta1/apps/{self.package}/{prefix}RateMetricSet:query"
            info = self.request("GET", url.removesuffix(":query")).get(
                "freshnessInfo", {}
            )
            freshness = next(
                (
                    x.get("latestEndTime")
                    for x in info.get("freshnesses", [])
                    if x["aggregationPeriod"] == "DAILY"
                ),
                None,
            )
            if not freshness:
                continue
            end_day = min(
                today, date(freshness["year"], freshness["month"], freshness["day"])
            )
            if end_day <= first:
                continue
            body = {
                "timelineSpec": {
                    "aggregationPeriod": "DAILY",
                    "startTime": period(first),
                    "endTime": period(end_day),
                },
                "dimensions": ["versionCode"],
                "metrics": [metric, "distinctUsers"],
                "filter": f"versionCode = {int(build)}",
            }
            records = self.request("POST", url, body).get("rows", [])
            for entry in records:
                dims = {
                    v["dimension"]: v.get("int64Value")
                    for v in entry.get("dimensions", [])
                }
                if dims.get("versionCode") != str(build):
                    continue
                d = entry["startTime"]
                day = date(d["year"], d["month"], d["day"])
                start, end = timestamp(day), timestamp(day + timedelta(days=1))
                if start < stage_start or end > now:
                    continue
                values = {
                    v["metric"]: float(v["decimalValue"]["value"])
                    for v in entry["metrics"]
                }
                if (
                    metric in values
                    and "distinctUsers" in values
                    and end > rows.get(prefix, {}).get("end", 0)
                ):
                    rows[prefix] = {
                        "start": start,
                        "end": end,
                        "users": values["distinctUsers"],
                        "rate": values[metric],
                    }
        return rows
