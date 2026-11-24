import json
import os
import tempfile
import unittest
from contextlib import ExitStack
from pathlib import Path
from unittest.mock import Mock, call, patch

from delivery import task


class TaskPullRequestTest(unittest.TestCase):
    def setUp(self):
        stack = ExitStack()
        self.addCleanup(stack.close)
        self.issue = {"number": 7, "state": "open", "labels": [{"name": "agent-ready"}]}
        self.state = {
            "plan": {"acceptance": ["pair round 2"]},
            "intake": {"title": "Pair round 2"},
        }
        self.record = Mock(data=self.state)
        self.head, self.main = "a" * 40, "b" * 40
        self.pr = {
            "base": {"ref": "main", "repo": {"full_name": task.gh.REPO}},
            "head": {
                "ref": "delivery/task-7",
                "sha": self.head,
                "repo": {"full_name": task.gh.REPO},
            },
            "number": 9,
            "state": "open",
            "merged_at": None,
        }
        self.ref = {"object": {"sha": self.head}}
        self.pulls = []
        self.ahead = 1
        self.trusted = True
        self.temp = Path(stack.enter_context(tempfile.TemporaryDirectory()))
        self.payload = self.temp / "task-pr.json"
        self.env = {
            "GITHUB_REF": "refs/heads/main",
            "TASK_ISSUE": "7",
            "TASK_REVISION": self.head,
            "RUNNER_TEMP": str(self.temp),
        }
        stack.enter_context(patch.dict(os.environ, self.env))
        stack.enter_context(patch.object(task.gh, "Record", return_value=self.record))
        self.read = stack.enter_context(
            patch.object(task.gh, "api", side_effect=self.api)
        )
        self.refs = stack.enter_context(
            patch.object(task.gh, "optional", side_effect=lambda _: self.ref)
        )
        stack.enter_context(
            patch.object(task.gh, "trusted_intake", side_effect=lambda _: self.trusted)
        )
        self.output = stack.enter_context(patch.object(task.gh, "output"))

    def api(self, path):
        if path == f"{task.gh.API}/issues/7":
            return self.issue
        if path == f"{task.gh.API}/git/ref/heads/main":
            return {"object": {"sha": self.main}}
        if path.startswith(f"{task.gh.API}/pulls?"):
            return self.pulls
        if path.startswith(f"{task.gh.API}/compare/"):
            return {"ahead_by": self.ahead}
        self.fail(f"Unexpected request {path}")

    def test_main_job_builds_one_request_from_approved_state(self):
        self.state["generation"] = 2
        self.state["intake"]["title"] = "Fixes #8"
        task.prepare_pr()
        payload = json.loads(self.payload.read_text())
        self.assertEqual(payload["head"], "delivery/task-7-fix-2")
        self.assertEqual(payload["base"], "main")
        self.assertEqual(payload["title"], "Implement delivery task 7")
        self.assertIn("Refs #7.", payload["body"])
        self.refs.assert_called_once_with(
            f"{task.gh.API}/git/ref/heads/delivery/task-7-fix-2"
        )
        self.output.assert_called_once_with("create", "true")

    def test_changed_head_unlabeled_closed_or_untrusted_issue_cannot_open_pr(self):
        for changes in (
            {"env": {"TASK_REVISION": self.main}},
            {"issue": {"labels": []}},
            {"issue": {"state": "closed"}},
            {"issue": {"pull_request": {}}},
            {"trusted": False},
            {"ahead": 0},
            {"ref": None},
            {"pulls": [{"number": 9, "state": "closed"}]},
            {"state": {"pr": 9}},
        ):
            with self.subTest(changes=changes), ExitStack() as stack:
                stack.enter_context(patch.dict(os.environ, changes.get("env", {})))
                stack.enter_context(patch.dict(self.issue, changes.get("issue", {})))
                stack.enter_context(patch.dict(self.state, changes.get("state", {})))
                for attr in ("trusted", "ahead", "ref", "pulls"):
                    if attr in changes:
                        stack.enter_context(patch.object(self, attr, changes[attr]))
                task.prepare_pr()
                self.assertFalse(self.payload.exists())
                self.output.assert_not_called()

    def test_feature_branch_and_invalid_revision_fail_before_any_api_access(self):
        for change in (
            {"GITHUB_REF": "refs/heads/feature"},
            {"TASK_REVISION": "main"},
            {"TASK_ISSUE": "0"},
        ):
            with (
                self.subTest(change=change),
                patch.dict(os.environ, change),
                self.assertRaises(ValueError),
            ):
                task.prepare_pr()
        self.read.assert_not_called()

    def test_recovery_after_branch_commit_schedules_the_opener_without_mutating_github(
        self,
    ):
        self.assertTrue(task.select(self.issue, self.record))
        self.assertEqual(
            [c.args for c in self.output.call_args_list],
            [("role", "open-pr"), ("issue", 7), ("revision", self.head)],
        )
        self.record.save.assert_not_called()

    def test_recovery_after_creation_adopts_the_existing_pr_including_a_closed_one(
        self,
    ):
        for state in ("open", "closed"):
            with self.subTest(state=state):
                self.state.pop("pr", None)
                self.pulls = [{"number": 9, "state": state}]
                task.select(self.issue, self.record)
                self.assertEqual(self.state["pr"], 9)
        self.output.assert_not_called()

    def test_current_review_and_ci_merge_only_the_exact_commit_under_branch_rules(self):
        self.state.update(pr=9, review={"sha": self.head, "passed": True})
        for blocked in (None, 405, 409):
            with (
                self.subTest(blocked=blocked),
                patch.object(task, "check_result", return_value=("passed", "")),
                patch.object(task, "review_gate") as gate,
            ):
                result = (
                    task.gh.GitHubError("Held by GitHub", blocked)
                    if blocked
                    else {"merged": True}
                )
                self.read.side_effect = [
                    {"object": {"sha": self.main}},
                    self.pr,
                    {"behind_by": 0},
                    result,
                ]
                task.select(self.issue, self.record)
                gate.assert_called_once_with(9)
                self.assertEqual(
                    self.read.call_args,
                    call(
                        f"{task.gh.API}/pulls/9/merge",
                        "PUT",
                        {"sha": self.head, "merge_method": "squash"},
                    ),
                )
                self.assertNotIn("merged", self.state)

    def test_sync_updates_the_task_branch_with_contents_permission_and_rechecks_later(
        self,
    ):
        self.state["pr"] = 9
        self.read.side_effect = [
            {"object": {"sha": self.main}},
            self.pr,
            {"behind_by": 1},
            {"sha": "updated"},
        ]
        task.select(self.issue, self.record)
        self.assertEqual(
            self.read.call_args,
            call(
                task.gh.API + "/merges",
                "POST",
                {"base": "delivery/task-7", "head": self.main},
            ),
        )
