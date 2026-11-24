"""Follow the GitHub status feed in the local Temporal Web UI."""

import argparse
import asyncio
import logging
import subprocess
from datetime import timedelta
from pathlib import Path

from temporalio.client import Client, WorkflowExecutionStatus
from temporalio.common import WorkflowIDConflictPolicy
from temporalio.exceptions import TemporalError
from temporalio.service import RPCError, RPCStatusCode
from temporalio.worker import Worker

from delivery.adapters import github as gh
from delivery.temporal_status import feed
from delivery.temporal_status.workflow import QUEUE, IssueDelivery

LOG = logging.getLogger("delivery.temporal")


async def observe(client, label, event):
    item = event["issue"]
    workflow_id = f"issue-{int(item['number'])}"
    handle = client.get_workflow_handle(workflow_id)
    try:
        description = await handle.describe()
        if description.status == WorkflowExecutionStatus.COMPLETED:
            # A closing update can finish just before the observer saves its checkpoint.
            previous = await handle.result()
            if previous["sequence"] >= event["sequence"]:
                return
    except RPCError as exc:
        if exc.status != RPCStatusCode.NOT_FOUND:
            raise
    handle = await client.start_workflow(
        IssueDelivery.run,
        {},
        id=workflow_id,
        task_queue=QUEUE,
        id_conflict_policy=WorkflowIDConflictPolicy.USE_EXISTING,
        static_summary=f"#{item['number']} · {item['title']}",
        static_details=f"[GitHub issue #{item['number']}]({item['url']})",
    )
    await handle.execute_update(
        label, event, id=event["revision"], rpc_timeout=timedelta(seconds=25)
    )


async def sync(client, checkpoint):
    saved = feed.read_checkpoint(checkpoint)
    for revision, recorded in await asyncio.to_thread(
        feed.revisions_after, saved["revision"]
    ):
        current = await asyncio.to_thread(feed.read_issues, revision)
        sequence = saved["sequence"] + 1
        for label, issue in feed.changes(saved["issues"], current):
            await observe(
                client,
                label,
                {
                    "revision": revision,
                    "sequence": sequence,
                    "recorded": recorded,
                    "issue": issue,
                },
            )
        saved = {"revision": revision, "sequence": sequence, "issues": current}
        feed.save_checkpoint(checkpoint, saved)
        LOG.info("Synced GitHub status %s", revision[:12])


async def watch(args):
    while True:
        try:
            client = await Client.connect(args.address, namespace=args.namespace)
            break
        except (OSError, RuntimeError) as exc:
            LOG.warning("Waiting for the local Temporal service: %s", exc)
            await asyncio.sleep(3)
    async with Worker(client, task_queue=QUEUE, workflows=[IssueDelivery]):
        while True:
            try:
                await sync(client, args.checkpoint)
            except (
                gh.GitHubError,
                TemporalError,
                subprocess.SubprocessError,
                OSError,
                ValueError,
            ) as exc:
                LOG.warning("Status sync will retry: %s", exc)
            await asyncio.sleep(30)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--address", default="127.0.0.1:7233")
    parser.add_argument("--namespace", default="chess-delivery")
    parser.add_argument("--checkpoint", type=Path, required=True)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
    asyncio.run(watch(parser.parse_args()))
