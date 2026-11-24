import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from delivery.temporal_status import feed


class TemporalFeedTest(unittest.TestCase):
    def test_catches_up_in_order_across_pages_and_stops_at_checkpoint(self):
        commits = [
            {"sha": str(n), "commit": {"committer": {"date": str(n)}}}
            for n in range(103, 0, -1)
        ]
        with patch.object(feed.gh, "api", side_effect=[commits[:100], commits[100:]]):
            seen = feed.revisions_after("2")
        self.assertEqual([sha for sha, _ in seen], [str(n) for n in range(3, 104)])
        with patch.object(feed.gh, "api", return_value=commits[:1]):
            self.assertEqual(feed.revisions_after("103"), [])
            with self.assertRaisesRegex(ValueError, "missing"):
                feed.revisions_after("unrelated")

    def test_updates_only_changed_issues_and_records_departures(self):
        waiting = {"number": 7, "stage": "Ready for local pickup", "state": "open"}
        coding = {**waiting, "stage": "Implementing locally", "step": "code"}
        self.assertEqual(list(feed.changes({"7": waiting}, {"7": waiting})), [])
        self.assertEqual(
            list(feed.changes({"7": waiting}, {"7": coding})),
            [("Implementing locally", coding)],
        )
        changed_note = {**coding, "detail": "Run checks"}
        self.assertEqual(
            list(feed.changes({"7": coding}, {"7": changed_note})),
            [("Issue details updated", changed_note)],
        )
        ((stage, departed),) = feed.changes({"7": coding}, {})
        self.assertEqual((stage, departed["state"]), ("No longer tracked", "closed"))
        self.assertEqual(list(feed.changes({"7": departed}, {})), [])

    def test_checkpoint_round_trip(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "cursor.json"
            cursor = feed.read_checkpoint(path)
            self.assertIsNone(cursor["revision"])
            saved = {**cursor, "sequence": 1, "revision": "a"}
            feed.save_checkpoint(path, saved)
            self.assertEqual(feed.read_checkpoint(path), saved)
