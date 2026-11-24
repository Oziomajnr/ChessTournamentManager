import json
import os
import unittest
from contextlib import ExitStack
from unittest.mock import Mock, patch

from delivery import release, status
from delivery.agents import staged_plan
from delivery.policy import artifact_id, release_action


class PostUploadTest(unittest.TestCase):
    def setUp(self):
        self.check = "Check the SDK report for this version"
        self.candidate = {
            "issue": 7,
            "revision": "revision",
            "build": 6,
            "sha256": "digest",
            "post_upload_checks": [self.check],
        }
        self.record = Mock(
            data={"active": self.candidate, "notices": ["post-upload-6"]}
        )
        stack = ExitStack()
        self.addCleanup(stack.close)
        stack.enter_context(
            patch.object(release.gh, "Record", return_value=self.record)
        )
        self.api = stack.enter_context(
            patch.object(release.gh, "api", return_value={"permission": "write"})
        )
        stack.enter_context(patch.object(release.gh, "notify_once"))
        stack.enter_context(
            patch.dict(
                os.environ,
                {
                    "REPORT_ARTIFACT": json.dumps(artifact_id(self.candidate)),
                    "REPORT_CHECK": "1",
                    "REPORT_RESULT": "passed",
                    "REPORT_EVIDENCE": "Build 6 SDK report checked",
                    "GITHUB_ACTOR": "tester",
                },
            )
        )

    def action(self, track="live"):
        return release_action(self.candidate, track, {"status": "absent"}).action

    def test_distribution_report_smoke_and_owner_approval_in_order(self):
        self.candidate["post_upload_checks"].append("Inspect a second report")
        self.assertEqual(self.action("absent"), "publish_test")
        self.assertEqual(self.action("pending"), "wait")
        self.assertEqual(self.action(), "request_report")
        self.assertEqual(
            status.release_stage(self.candidate, ["post-upload-6"]).step, "reports"
        )
        release.record_evidence("post-upload")
        release.record_evidence("post-upload")
        self.record.save.assert_called_once()
        self.assertEqual(self.action(), "request_report")
        with patch.dict(
            os.environ,
            {"REPORT_CHECK": "2", "REPORT_EVIDENCE": "Second report verified"},
        ):
            release.record_evidence("post-upload")
        self.assertEqual(self.action(), "request_smoke")
        self.record.data["notices"].append("smoke-6")
        release.record_evidence("smoke")
        self.assertEqual(self.action(), "request_approval")
        self.candidate.update(approval={"artifact": artifact_id(self.candidate)})
        self.assertEqual(self.action(), "promote")
        self.candidate["sha256"] = "replacement"
        self.assertEqual(self.action(), "request_report")

    def test_requires_live_candidate_permission_and_matching_check(self):
        for values in (
            {"REPORT_ARTIFACT": "{}"},
            {"REPORT_CHECK": "0"},
            {"REPORT_CHECK": "2"},
            {"REPORT_RESULT": "unknown"},
            {"REPORT_EVIDENCE": " "},
        ):
            with (
                self.subTest(values),
                patch.dict(os.environ, values),
                self.assertRaises(ValueError),
            ):
                release.record_evidence("post-upload")
        self.api.return_value = {"permission": "read"}
        with self.assertRaises(ValueError):
            release.record_evidence("post-upload")
        self.api.return_value = {"permission": "write"}
        self.record.data["notices"].clear()
        with self.assertRaises(ValueError):
            release.record_evidence("post-upload")
        self.record.save.assert_not_called()

    def test_failed_check_cannot_be_overwritten_or_approved(self):
        with patch.dict(os.environ, {"REPORT_RESULT": "failed"}):
            release.record_evidence("post-upload")
        self.assertEqual(self.action(), "needs_attention")
        with self.assertRaises(ValueError):
            release.record_evidence("post-upload")
        self.candidate["approval_request"] = {"run": "123"}
        with (
            patch.dict(os.environ, {"GITHUB_RUN_ID": "123"}),
            self.assertRaisesRegex(ValueError, "verification"),
        ):
            release.approve()

    def test_legacy_plans_and_explicit_release_checks(self):
        before = "Run release unit tests before merge"
        later = "Post-release validation: check the processed candidate"
        plan = {"checks": [before, later], "post_upload_checks": [later, self.check]}
        staged = staged_plan(plan)
        self.assertEqual(staged["checks"], [before])
        self.assertEqual(staged["post_upload_checks"], [later, self.check])
        self.assertEqual(staged_plan(staged), staged)
        self.assertEqual(plan["checks"], [before, later])
        self.assertEqual(staged_plan({"checks": [before]})["post_upload_checks"], [])
        with self.assertRaises(ValueError):
            staged_plan({"checks": [before], "post_upload_checks": "ambiguous"})

    def test_controller_loads_legacy_check_and_requests_it_after_publication(self):
        later = "Post-release validation: inspect the processed version"
        del self.candidate["post_upload_checks"]
        self.record.data["notices"].clear()
        task = Mock(data={"plan": {"checks": ["Run JVM tests", later]}})
        with (
            patch.object(release.gh, "ensure_branch"),
            patch.object(
                release.gh,
                "Record",
                side_effect=lambda key: self.record if key == "releases" else task,
            ),
            patch.object(release.gh, "output") as output,
            patch.object(release.gh, "summary"),
            patch.object(release, "Play") as play,
        ):
            release.select()
            self.assertEqual(self.candidate["post_upload_checks"], [later])
            play.return_value.observe.return_value = {
                "internal": {"status": "live"},
                "production": {"status": "absent"},
            }
            release.drive()
            marker = release.gh.notify_once.call_args.args
            self.assertEqual(marker[1:3], (7, "post-upload-6"))
            self.assertIn(later, marker[3])
            self.assertNotIn("approval_request", self.candidate)
            self.assertNotIn(
                ("action", "approve"), [call.args for call in output.call_args_list]
            )
