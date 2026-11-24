import base64
import unittest
from unittest.mock import patch

from delivery import status


class IssueStatusTest(unittest.TestCase):
    def setUp(self):
        self.issue = {
            "number": 7,
            "state": "open",
            "title": "Update [SDK] | <b>x</b>",
            "labels": [{"name": "agent-ready"}],
        }
        self.pr = {"head": {"sha": "new"}, "state": "open", "merged_at": None}

    def stage(self, state, **kwargs):
        return status.issue_stage(self.issue, state, {}, [], **kwargs)[0]

    def test_existing_plans_wait_for_local_pickup_and_claims_start_local_work(self):
        self.assertEqual(
            self.stage({"plan": {"checks": ["test"]}}), "Ready for local pickup"
        )
        self.assertEqual(self.stage({"local": {"actor": "owner"}}), "Planning locally")
        self.assertEqual(
            self.stage({"local": {"actor": "owner"}, "plan": {"checks": ["test"]}}),
            "Implementing locally",
        )
        self.assertEqual(
            self.stage({"local": {"reviewing": "new"}}), "Local review requested"
        )
        self.assertEqual(
            self.stage({"local": {"reviewing": "new", "review_error": "Retry review"}}),
            "Local review needs attention",
        )
        self.issue["labels"] = []
        self.assertEqual(self.stage({}), "Backlog")

    def test_pr_stages_follow_the_current_revision_and_ci(self):
        state = {
            "local": {"submitted": "new"},
            "review": {"sha": "old", "passed": True},
        }
        ci = {"status": "completed", "conclusion": "success"}
        self.assertEqual(self.stage(state, pr=self.pr), "CI pending")
        self.assertEqual(self.stage(state, pr=self.pr, ci=ci), "Awaiting code review")
        state["review"] = {"sha": "new", "passed": False}
        self.assertEqual(self.stage(state, pr=self.pr, ci=ci), "Changes requested")
        state["review"]["passed"] = True
        self.assertEqual(self.stage(state, pr=self.pr, ci=ci), "Awaiting merge")
        self.assertEqual(
            self.stage(state, pr=self.pr, ci={**ci, "conclusion": "failure"}),
            "CI needs attention",
        )

    def test_release_progress_separates_requested_and_confirmed_rollouts(self):
        c = {"issue": 7, "build": 6, "sha256": "digest"}
        self.assertEqual(status.release_stage(c, ["smoke-6"])[0], "Internal testing")
        self.assertEqual(
            status.release_stage({**c, "approval_request": {"run": 9}}, [])[0],
            "Awaiting production approval",
        )
        advance = {
            **c,
            "approval": {"run": 9},
            "observed_percent": 10,
            "submitted": {"action": "promote", "percent": 50},
        }
        stage, detail = status.release_stage(advance, [])
        self.assertEqual(stage, "Production rollout")
        self.assertIn("10% was confirmed", detail)
        self.assertIn("confirm 50%", detail)
        self.assertEqual(
            self.stage({"production": {"percent": 100}}), "Published at 100%"
        )
        self.issue["state"] = "closed"
        self.assertEqual(self.stage({}), "Closed")

    def test_board_escapes_titles_links_evidence_and_skips_unchanged_commits(self):
        with patch.object(status.gh, "trusted_intake", return_value=True):
            view = status.render(
                [self.issue], {7: {"claim": ["123.2", "plan", "sha"]}}, {}
            )
        self.assertIn(r"Update \[SDK\] \| &lt;b&gt;", view)
        self.assertIn("actions/runs/123/attempts/2", view)
        existing = {"sha": "abc", "content": base64.b64encode(view.encode()).decode()}
        with (
            patch.object(status.gh, "optional", return_value=existing),
            patch.object(status.gh, "api") as write,
        ):
            status.publish(view)
            write.assert_not_called()

    def test_board_only_offers_pickup_for_maintainer_approved_labels(self):
        with patch.object(status.gh, "trusted_intake", return_value=False):
            view = status.render([self.issue], {}, {})
        self.assertIn("Awaiting maintainer", view)
        self.assertNotIn("Ready for local pickup", view)
