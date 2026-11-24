import unittest

from delivery.policy import artifact_id, health_status, release_action, task_action


class DeliveryPolicyTest(unittest.TestCase):
    def setUp(self):
        self.c = {"revision": "abc", "build": 6, "sha256": "digest"}
        self.proof = {"artifact": artifact_id(self.c), "result": "passed"}
        self.p = {"status": "absent"}

    def action(self, test="live", health="insufficient"):
        return release_action(self.c, test, self.p, health)

    def test_one_approval_follows_the_exact_tested_artifact(self):
        self.assertEqual(self.action("absent").action, "publish_test")
        self.assertEqual(self.action("pending").action, "wait")
        self.assertEqual(self.action().action, "request_smoke")
        self.c["smoke"] = self.proof
        self.assertEqual(self.action().action, "request_approval")
        self.c["approval"] = self.proof
        self.assertEqual(self.action().percent, 10)
        self.c["sha256"] = "replacement"
        self.assertEqual(self.action().action, "request_smoke")
        self.c["smoke"] = {"artifact": artifact_id(self.c), "result": "passed"}
        self.assertEqual(self.action().action, "request_approval")

    def test_only_confirmed_publication_completes_a_task(self):
        self.c.update(smoke=self.proof, approval=self.proof)
        self.p.update(status="pending", percent=100)
        self.assertEqual(self.action().action, "wait")
        self.p["status"] = "live"
        self.assertEqual(self.action().action, "complete")

    def test_stage_samples_and_halts(self):
        self.c.update(smoke=self.proof, approval=self.proof)
        for pct, target in ((10, 50), (50, 100)):
            self.p.update(status="live", percent=pct)
            self.assertEqual(self.action(health="healthy").action, "observe")
            self.c["observed_percent"] = pct
            self.assertEqual(self.action().action, "wait")
            self.assertEqual(self.action(health="regression").action, "halt")
            self.assertEqual(self.action(health="healthy").percent, target)
        self.p["status"] = "halted"
        self.assertEqual(self.action().action, "needs_attention")

    def test_health_requires_complete_recent_candidate_periods(self):
        limits = {"crash": 1.0, "anr": 0.5}
        row = {"start": 100, "end": 200, "users": 100, "rate": 0.1}

        def verdict(**rows):
            return health_status(rows, 100, 250, 100, limits, 3)

        self.assertEqual(verdict(crash=row, anr=row), "healthy")
        for field, value in [
            ("start", 99),
            ("end", 251),
            ("users", 99),
            ("rate", float("nan")),
        ]:
            self.assertEqual(
                verdict(crash=dict(row, **{field: value}), anr=row), "insufficient"
            )
        self.assertEqual(verdict(anr=dict(row, rate=0.5)), "regression")
        self.assertEqual(verdict(crash=row), "insufficient")

    def test_two_repairs_and_independent_review(self):
        self.assertEqual(task_action(True, "passed", "pending", 0), "wait")
        for n in (0, 1):
            self.assertEqual(task_action(True, "failed", "passed", n), "repair")
        self.assertEqual(task_action(True, "failed", "passed", 2), "needs_attention")
        self.assertEqual(task_action(True, "passed", "passed", 2), "merge")
