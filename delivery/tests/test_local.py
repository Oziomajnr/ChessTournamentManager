import copy
import io
import os
import subprocess
import tempfile
import unittest
from contextlib import ExitStack, redirect_stdout
from pathlib import Path
from unittest.mock import Mock, patch

from delivery import local


class LocalHandoffTest(unittest.TestCase):
    def setUp(self):
        stack = ExitStack()
        self.addCleanup(stack.close)
        folder = Path(stack.enter_context(tempfile.TemporaryDirectory()))
        self.root, self.remote = folder / "checkout", folder / "remote.git"
        self.root.mkdir()
        self.git("init", "--bare", str(self.remote))
        self.git("init", "-b", "main")
        self.git("config", "user.name", "Delivery test")
        self.git("config", "user.email", "test@example.invalid")
        self.git("remote", "add", "origin", str(self.remote))
        (self.root / "docs").mkdir()
        (self.root / "docs/test.md").write_text("Initial\n")
        self.git("add", ".")
        self.git("commit", "-m", "Initial")
        self.git("push", "origin", "main")
        self.base = self.git("rev-parse", "HEAD")
        self.issue = {
            "number": 7,
            "title": "Approved task",
            "body": "Acceptance criteria",
            "state": "open",
            "html_url": "https://example.invalid/7",
            "labels": [{"name": "agent-ready"}],
        }
        self.data = {
            "plan": {
                "acceptance": ["Works"],
                "checks": ["Test it"],
                "release_notes": "Updated.",
            }
        }
        self.trusted = True
        self.output = stack.enter_context(redirect_stdout(io.StringIO()))
        stack.enter_context(
            patch.object(local, "checkout_root", return_value=self.root)
        )
        stack.enter_context(
            patch.object(local.gh, "Record", side_effect=lambda _: self.record())
        )
        stack.enter_context(patch.object(local.gh, "api", side_effect=self.api))
        stack.enter_context(patch.object(local.gh, "optional", return_value=None))
        stack.enter_context(patch.object(local.gh, "issues", return_value=[self.issue]))
        stack.enter_context(
            patch.object(local.gh, "trusted_intake", side_effect=lambda _: self.trusted)
        )
        self.dispatch = stack.enter_context(patch.object(local.gh, "dispatch"))
        self.review = stack.enter_context(
            patch.object(
                local,
                "independent_review",
                return_value={"passed": True, "findings": []},
            )
        )

    def git(self, *args, cwd=None):
        cp = subprocess.run(
            ["git", *args],
            cwd=cwd or self.root,
            capture_output=True,
            text=True,
            check=True,
        )
        return cp.stdout.strip()

    def api(self, path):
        answers = {
            "user": {"login": "owner"},
            f"{local.gh.API}/issues/7": self.issue,
            f"{local.gh.API}/git/ref/heads/main": {"object": {"sha": self.base}},
        }
        return answers[path]

    def record(self):
        initial = copy.deepcopy(self.data)
        record = Mock(data=copy.deepcopy(initial))

        def save(_):
            nonlocal initial
            self.assertEqual(initial, self.data, "Stale task write")
            self.data = copy.deepcopy(record.data)
            initial = copy.deepcopy(self.data)

        record.save.side_effect = save
        return record

    def commit_change(self):
        local.pickup(None)
        work = local.area(self.root, "delivery/task-7")
        (work / "docs/test.md").write_text("Implemented\n")
        self.git("commit", "-am", "Implement", cwd=work)
        return self.git("rev-parse", "HEAD", cwd=work)

    def test_pickup_and_submit_to_remote_reuse_the_plan_and_exact_review(self):
        head = self.commit_change()
        client = local.client_id(self.root)
        local.pickup(7)
        self.assertEqual(self.git("rev-parse", "HEAD"), self.base)
        self.assertEqual(self.data["plan"]["acceptance"], ["Works"])
        local.submit(7, self.root, "owner", client)
        self.assertEqual(
            self.git(
                "--git-dir", str(self.remote), "rev-parse", "refs/heads/delivery/task-7"
            ),
            head,
        )
        self.assertEqual(self.data["review"]["sha"], head)
        self.assertEqual(self.data["local"]["submitted"], head)
        local.submit(7, self.root, "owner", client)
        self.review.assert_called_once()
        self.dispatch.assert_any_call("tasks.yml")
        self.dispatch.assert_any_call("delivery-status.yml")

    def test_another_checkout_cannot_steal_the_claim_or_submit(self):
        local.pickup(7)
        with (
            patch.object(local, "client_id", return_value="another"),
            self.assertRaises(ValueError),
        ):
            local.pickup(7)
        with self.assertRaises(ValueError):
            local.submit(7, self.root, "someone-else", "another")
        self.review.assert_not_called()

    def test_reviewer_receives_staged_checks_from_the_existing_plan(self):
        later = "Post-release validation: inspect the distributor SDK report."
        self.data["plan"]["checks"].append(later)
        self.commit_change()
        local.submit(7, self.root, "owner", local.client_id(self.root))
        reviewed = self.review.call_args.args[1]
        self.assertEqual(reviewed["checks"], ["Test it"])
        self.assertEqual(reviewed["post_upload_checks"], [later])
        self.assertEqual(self.data["plan"]["checks"], ["Test it", later])

    def test_removed_approval_during_review_prevents_push_and_submission(self):
        self.commit_change()

        def finish(*_):
            self.trusted = False
            return {"passed": True, "findings": []}

        self.review.side_effect = finish
        with self.assertRaisesRegex(ValueError, "agent-ready"):
            local.submit(7, self.root, "owner", local.client_id(self.root))
        self.assertNotIn("submitted", self.data["local"])
        self.assertEqual(
            self.git(
                "--git-dir", str(self.remote), "branch", "--list", "delivery/task-7"
            ),
            "",
        )

    def test_interrupted_review_can_retry_and_findings_require_a_new_commit(self):
        head = self.commit_change()
        client = local.client_id(self.root)
        self.review.side_effect = [
            OSError("Codex unavailable"),
            {"passed": False, "findings": ["Missing check"]},
            {"passed": True, "findings": []},
        ]
        with self.assertRaises(OSError):
            local.submit(7, self.root, "owner", client)
        self.assertIn("review_error", self.data["local"])
        self.assertNotIn("submitted", self.data["local"])
        local.submit(7, self.root, "owner", client)
        self.assertNotIn("review_error", self.data["local"])
        self.assertEqual(self.data["review"]["sha"], head)
        self.assertFalse(self.data["review"]["passed"])
        work = local.area(self.root, "delivery/task-7")
        (work / "docs/test.md").write_text("Implemented and checked\n")
        self.git("commit", "-am", "Repair", cwd=work)
        local.submit(7, self.root, "owner", client)
        self.assertEqual(self.data["repairs"], 1)
        self.assertTrue(self.data["review"]["passed"])
        self.assertEqual(
            self.data["review"]["sha"], self.git("rev-parse", "HEAD", cwd=work)
        )

    def test_linked_file_is_outside_the_allowed_source_changes(self):
        self.commit_change()
        work = local.area(self.root, "delivery/task-7")
        (work / "docs/link.md").symlink_to("../outside.md")
        self.git("add", "docs/link.md", cwd=work)
        self.git("commit", "-m", "Add link", cwd=work)
        with self.assertRaisesRegex(ValueError, "approved pinned dependency"):
            local.submit(7, self.root, "owner", local.client_id(self.root))
        self.review.assert_not_called()

    def test_local_prompt_cannot_queue_itself_or_overwrite_a_plan(self):
        with (
            patch.object(local.gh, "optional", return_value={"value": "true"}),
            self.assertRaises(ValueError),
        ):
            local.pickup(7)
        self.trusted = False
        with self.assertRaises(ValueError):
            local.pickup(7)
        with self.assertRaises(ValueError):
            local.save_plan(
                7, self.record(), {**self.data["plan"], "checks": ["Different"]}
            )
        self.assertNotIn("local", self.data)


class LocalReviewTest(unittest.TestCase):
    def test_only_pinned_configured_constraints_are_allowed_as_build_edits(self):
        original = "plugins { foo() }\ndependencies {\n    implementation(libs.core)\n}"
        candidate = original.replace(
            "    implementation",
            '    constraints { implementation("androidx.fragment:fragment:1.8.9") }\n    implementation',
        )
        allowed = ["androidx.fragment:fragment"]
        self.assertTrue(local.dependency_constraint_edit(original, candidate, allowed))
        for unwanted in (
            candidate.replace("1.8.9", "+"),
            candidate.replace("fragment:fragment", "other:other"),
            candidate.replace("foo()", "bar()"),
            candidate.replace("libs.core", "libs.plugin"),
            original
            + '\n    constraints { implementation("androidx.fragment:fragment:1.8.9") }',
        ):
            with self.subTest(unwanted=unwanted):
                self.assertFalse(
                    local.dependency_constraint_edit(original, unwanted, allowed)
                )

    def test_independent_process_uses_chatgpt_and_receives_only_review_material(self):
        def run(args, **kwargs):
            self.assertNotIn("OPENAI_API_KEY", kwargs["env"])
            self.assertNotIn("GH_TOKEN", kwargs["env"])
            if args[1] == "login":
                return subprocess.CompletedProcess(
                    args, 0, "", "Logged in using ChatGPT"
                )
            self.assertIn('forced_login_method="chatgpt"', args)
            self.assertEqual(args[args.index("--sandbox") + 1], "read-only")
            self.assertIn("test diff", kwargs["input"])
            Path(args[args.index("--output-last-message") + 1]).write_text(
                '{"passed":false,"findings":["Missing test"]}'
            )
            return subprocess.CompletedProcess(args, 0)

        with (
            patch.dict(os.environ, {"OPENAI_API_KEY": "test", "GH_TOKEN": "test"}),
            patch.object(subprocess, "run", side_effect=run),
        ):
            result = local.independent_review({}, {}, "test diff")
        self.assertEqual(result, {"passed": False, "findings": ["Missing test"]})
