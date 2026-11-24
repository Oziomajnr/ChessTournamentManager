import unittest

from delivery.adapters.play import PUBLISHED, track_state, updated_track
from delivery.workflow import decide


class PlayTrackTest(unittest.TestCase):
    def setUp(self):
        self.old = {"versionCodes": ["5"], "status": "completed"}
        self.new = {"versionCodes": ["6"], "status": "inProgress", "userFraction": 0.1}
        self.track = {"track": "production", "releases": [self.old]}

    def test_stage_retains_previous_serving_release_until_full(self):
        stage = updated_track(self.track, 6, "promote", 10, "Notes")
        self.assertIn(self.old, stage["releases"])
        self.assertEqual(stage["releases"][0]["versionCodes"], ["6"])
        self.assertEqual(
            updated_track(stage, 6, "promote", 50, "Notes")["releases"][0][
                "userFraction"
            ],
            0.5,
        )
        complete = updated_track(stage, 6, "promote", 100, "Notes")["releases"]
        self.assertEqual(len(complete), 1)
        self.assertEqual(complete[0]["status"], "completed")
        self.assertNotIn("userFraction", complete[0])

    def test_halt_and_replacement_scope(self):
        self.track["releases"].insert(0, self.new)
        halted = updated_track(self.track, 6, "halt", 10, "Notes")
        self.assertEqual(halted["releases"][0]["status"], "halted")
        with self.assertRaises(ValueError):
            updated_track(halted, 7, "promote", 10, "Notes")
        replacement = updated_track(halted, 7, "promote", 10, "Notes", supersedes=[6])
        self.assertEqual(replacement["releases"][0]["versionCodes"], ["7"])

    def test_unrelated_draft_and_newer_version_are_preserved(self):
        for r in [
            {"versionCodes": ["4"], "status": "draft"},
            {"versionCodes": ["9"], "status": "completed"},
        ]:
            with self.assertRaises(ValueError):
                updated_track(
                    {"track": "internal", "releases": [r]},
                    6,
                    "publish_test",
                    100,
                    "Notes",
                )

    def test_exact_version_requires_distributor_publication(self):
        done = {
            "track": "production",
            "releases": [{"versionCodes": ["6"], "status": "completed"}],
        }

        def life(code, flag):
            return {
                "activeArtifacts": [{"versionCode": code}],
                "releaseLifecycleState": flag,
            }

        self.assertEqual(
            track_state(done, [life(5, PUBLISHED)], 6)["status"], "pending"
        )
        self.assertEqual(
            track_state(done, [life(6, "RELEASE_LIFECYCLE_STATE_IN_REVIEW")], 6)[
                "status"
            ],
            "pending",
        )
        self.assertEqual(
            track_state(done, [life(6, PUBLISHED)], 6),
            {"status": "live", "percent": 100},
        )

    def test_portable_intake_contract(self):
        self.assertEqual(
            decide({"kind": "task", "state": {"planned": False, "has_change": False}}),
            {"action": "plan"},
        )
        self.assertEqual(
            decide(
                {
                    "kind": "task",
                    "state": {"planned": True, "has_change": True, "checks": "passed"},
                }
            ),
            {"action": "review"},
        )


class PlayRetryTest(unittest.TestCase):
    def test_lost_commit_response_does_not_upload_or_commit_twice(self):
        import copy
        import hashlib
        import tempfile
        from pathlib import Path

        from delivery.adapters.play import Play, PlayError

        class Store(Play):
            def __init__(self):
                self.base = "https://example.invalid/api"
                self.track = {"track": "internal", "releases": []}
                self.edits = {}
                self.bundles = []
                self.created = self.uploads = self.commits = 0

            def new_edit(self):
                self.created += 1
                edit = str(self.created)
                self.edits[edit] = copy.deepcopy(self.track)
                return edit

            def request(self, method, url, body=None, media=False):
                edit = url.split("/edits/")[1].split("/")[0].split(":")[0]
                if edit not in self.edits:
                    raise PlayError(404, "Unknown edit")
                if ":commit" in url:
                    self.assert_safe_commit(url)
                    self.commits += 1
                    self.track = self.edits.pop(edit)
                    raise TimeoutError("Commit succeeded but the response was lost")
                if url.endswith("/bundles"):
                    return {"bundles": self.bundles}
                if "/bundles?" in url:
                    self.uploads += 1
                    self.bundles = [
                        {"versionCode": 6, "sha256": hashlib.sha256(body).hexdigest()}
                    ]
                    return self.bundles[0]
                if url.endswith("/tracks/internal"):
                    if method == "PUT":
                        self.edits[edit] = copy.deepcopy(body)
                    return copy.deepcopy(self.edits[edit])
                if method == "DELETE":
                    del self.edits[edit]
                return {}

            @staticmethod
            def assert_safe_commit(url):
                if "changesInReviewBehavior=ERROR_IF_IN_REVIEW" not in url:
                    raise AssertionError("Unprotected commit")

        store = Store()
        with tempfile.TemporaryDirectory() as folder:
            bundle = Path(folder) / "app.aab"
            bundle.write_bytes(b"fixture")
            c = {
                "build": 6,
                "notes": "Test",
                "sha256": hashlib.sha256(bundle.read_bytes()).hexdigest(),
            }
            durable = {}

            def save():
                durable.clear()
                durable.update(copy.deepcopy(c))

            with self.assertRaises(TimeoutError):
                store.mutate(c, save, "publish_test", 0, bundle)
            self.assertIn("intent", durable)
            c = copy.deepcopy(durable)
            store.mutate(c, save, "publish_test", 0, bundle)
            self.assertNotIn("intent", durable)
            self.assertEqual((store.uploads, store.commits), (1, 1))
