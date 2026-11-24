import unittest
from unittest.mock import Mock, patch
from delivery import task


class ReviewGateTest(unittest.TestCase):
    def test_a_second_pr_cannot_override_review_for_the_same_commit(self):
        managed = {
            "number": 7,
            "head": {
                "sha": "abc",
                "ref": "delivery/task-5",
                "repo": {"full_name": task.gh.REPO},
            },
        }
        other = {
            "number": 8,
            "head": {
                "sha": "abc",
                "ref": "another-branch",
                "repo": {"full_name": "other/repo"},
            },
        }
        writes = []

        def api(path, method="GET", body=None):
            if method == "POST":
                writes.append(body)
            elif "/commits/" in path:
                return [managed, other]
            else:
                return other

        with (
            patch.object(task.gh, "api", side_effect=api),
            patch.object(
                task.gh,
                "Record",
                return_value=Mock(
                    data={"pr": 7, "review": {"sha": "older", "passed": True}}
                ),
            ),
        ):
            task.review_gate(8)
        self.assertEqual(writes[-1]["state"], "pending")
