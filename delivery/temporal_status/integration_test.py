"""Exercise the native UI's history and state on a separate ephemeral Temporal service."""

import os
import shutil
import unittest

from temporalio.client import WorkflowExecutionStatus
from temporalio.testing import WorkflowEnvironment
from temporalio.worker import Worker

from delivery.temporal_status.__main__ import observe
from delivery.temporal_status.workflow import QUEUE, STAGE, IssueDelivery


class TemporalObserverTest(unittest.IsolatedAsyncioTestCase):
    async def test_stage_updates_retries_closure_and_reopening(self):
        cli = os.environ.get("TEMPORAL_CLI") or shutil.which("temporal")
        self.assertTrue(cli, "Set TEMPORAL_CLI to the local Temporal executable")
        async with (
            await WorkflowEnvironment.start_local(
                dev_server_existing_path=cli, search_attributes=[STAGE]
            ) as env,
            Worker(env.client, task_queue=QUEUE, workflows=[IssueDelivery]),
        ):

            def event(seq, stage, state="open"):
                return {
                    "revision": f"commit-{seq}",
                    "sequence": seq,
                    "recorded": "2026-01-01T00:00:00Z",
                    "issue": {
                        "number": 42,
                        "title": "Test issue",
                        "url": "https://example.invalid/issues/42",
                        "stage": stage,
                        "state": state,
                        "detail": "Next action",
                    },
                }

            waiting, coding = (
                event(1, "Ready for local pickup"),
                event(2, "Implementing locally"),
            )
            for e in (waiting, coding, coding):
                await observe(env.client, e["issue"]["stage"], e)
            h = env.client.get_workflow_handle("issue-42")
            self.assertEqual(await h.query(IssueDelivery.current_stage), coding)
            described = await h.describe()
            self.assertEqual(
                described.typed_search_attributes[STAGE], "Implementing locally"
            )
            events = (await h.fetch_history()).events
            names = [
                e.workflow_execution_update_accepted_event_attributes.accepted_request.input.name
                for e in events
                if e.HasField("workflow_execution_update_accepted_event_attributes")
            ]
            self.assertEqual(names, ["Ready for local pickup", "Implementing locally"])
            closed = event(3, "Published at 100%", "closed")
            await observe(env.client, closed["issue"]["stage"], closed)
            self.assertEqual((await h.result())["sequence"], 3)
            await observe(env.client, closed["issue"]["stage"], closed)
            self.assertEqual((await h.describe()).run_id, described.run_id)
            reopened = event(4, "Ready for local pickup")
            await observe(env.client, reopened["issue"]["stage"], reopened)
            latest = await h.describe()
            self.assertNotEqual(latest.run_id, described.run_id)
            self.assertEqual(latest.status, WorkflowExecutionStatus.RUNNING)
            self.assertEqual(await h.query(IssueDelivery.current_stage), reopened)
