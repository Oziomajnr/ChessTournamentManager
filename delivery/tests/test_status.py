import json
import tempfile
import unittest
from pathlib import Path
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
        position = status.release_stage(advance, [])
        self.assertEqual(
            (position.stage, position.step), ("Production rollout", "rollout")
        )
        self.assertIn("10% was confirmed", position.detail)
        self.assertIn("confirm 50%", position.detail)
        self.assertEqual(
            self.stage({"production": {"percent": 100}}), "Published at 100%"
        )
        self.issue["state"] = "closed"
        self.assertEqual(self.stage({}), "Closed")

    def test_board_escapes_titles_links_evidence_and_skips_unchanged_commits(self):
        with patch.object(status.gh, "trusted_intake", return_value=True):
            board = status.collect(
                [self.issue], {7: {"claim": ["123.2", "plan", "sha"]}}, {}
            )
        view = status.render(board)
        self.assertIn(r"Update \[SDK\] \| &lt;b&gt;", view)
        self.assertIn("actions/runs/123/attempts/2", view)
        self.assertNotIn("Update [SDK]", "\n".join(status.mermaid(board)))
        with (
            tempfile.TemporaryDirectory() as temp,
            patch.object(status.task, "commit_files") as write,
        ):
            root = Path(temp)
            status.commit_view(root, "head", board, view)
            branch, head, _, files = write.call_args.args
            self.assertEqual((branch, head), (status.gh.STATE_BRANCH, "head"))
            self.assertEqual(set(files), {"STATUS.md", "status.json"})
            self.assertEqual(json.loads(files["status.json"]), board)
            for name, value in files.items():
                (root / name).write_text(value)
            status.commit_view(root, "head", board, view)
            write.assert_called_once()

    def test_board_only_offers_pickup_for_maintainer_approved_labels(self):
        with patch.object(status.gh, "trusted_intake", return_value=False):
            view = status.render(status.collect([self.issue], {}, {}))
        self.assertIn("Awaiting maintainer", view)
        self.assertNotIn("Ready for local pickup", view)

    def test_graph_moves_each_issue_once_and_keeps_other_issues_in_place(self):
        other = {**self.issue, "number": 8}
        closed = {**self.issue, "number": 9, "state": "closed"}
        cases = [
            ({}, {}, "ready", "#7 #8"),
            ({"local": {"actor": "owner"}}, {}, "plan", "#8"),
            (
                {"plan": {"checks": ["test"]}, "local": {"actor": "owner"}},
                {},
                "code",
                "#8",
            ),
            ({"local": {"reviewing": "abc"}}, {}, "review", "#8"),
            ({"merged": "revision"}, {}, "queue", "#8"),
            (
                {},
                {
                    "active": {
                        "issue": 7,
                        "sha256": "signed",
                        "approval_request": {"run": 2},
                    }
                },
                "approval",
                "#8",
            ),
            ({"production": {"percent": 100}}, {}, "published", "#8"),
        ]
        with patch.object(status.gh, "trusted_intake", return_value=True):
            for state, release, step, ready in cases:
                with self.subTest(step=step):
                    board = status.collect(
                        [self.issue, other, closed], {7: state}, release
                    )
                    graph = "\n".join(status.mermaid(board))
                    self.assertEqual(graph.count("#7"), 1)
                    self.assertIn(
                        ready,
                        next(line for line in graph.splitlines() if 'ready["' in line),
                    )
                    self.assertIn(
                        "#7",
                        next(
                            line for line in graph.splitlines() if f'{step}["' in line
                        ),
                    )
                    self.assertIn(
                        '#9"]:::occupied',
                        next(line for line in graph.splitlines() if 'closed["' in line),
                    )
                    self.assertEqual(board["issues"][0]["step"], step)

    def test_no_issues_still_draws_the_route_without_occupied_nodes(self):
        text = status.render(status.collect([], {}, {}))
        self.assertIn("development --> integration --> distribution", text)
        self.assertNotIn(":::occupied", text)
        self.assertIn("No open issues.", text)

    def test_racing_state_write_rebuilds_the_graph_from_new_records(self):
        with tempfile.TemporaryDirectory() as temp:
            state_dir = Path(temp) / ".delivery"
            (state_dir / "tasks").mkdir(parents=True)

            def advance(*_, **kwargs):
                (state_dir / "tasks/7.json").write_text('{"local":{"actor":"owner"}}')

            with (
                patch.object(status.gh, "issues", return_value=[self.issue]),
                patch.object(status.gh, "trusted_intake", return_value=True),
                patch.object(
                    status.subprocess, "check_output", side_effect=["first", "new"]
                ),
                patch.object(status.subprocess, "run", side_effect=advance),
                patch.object(status.gh, "api", return_value={"object": {"sha": "new"}}),
                patch.object(
                    status.task,
                    "commit_files",
                    side_effect=[status.gh.GitHubError("changed", 409), None],
                ) as write,
            ):
                status.refresh(state_dir, False, True)
            previous, current = [c.args[3] for c in write.call_args_list]
            self.assertEqual(
                json.loads(previous["status.json"])["issues"][0]["step"], "ready"
            )
            self.assertEqual(
                json.loads(current["status.json"])["issues"][0]["step"], "plan"
            )
            self.assertIn(
                'plan["Planning<br/>Planning locally<br/>#7"]:::occupied',
                current["STATUS.md"],
            )
