"""One Temporal execution per open issue, with stage-named entries in its native timeline."""

# The SDK validates dynamic handlers against this exact annotation on Python 3.9.
from typing import Sequence  # noqa: UP035

from temporalio import workflow
from temporalio.common import RawValue, SearchAttributeKey

QUEUE = "issue-status"
STAGE = SearchAttributeKey.for_keyword("DeliveryStage")


@workflow.defn(name="IssueDelivery")
class IssueDelivery:
    def __init__(self):
        self.latest = {}
        self.count = 0

    @workflow.run
    async def run(self, carried: dict) -> dict:
        if carried and not self.latest:
            self.record(carried)
        await workflow.wait_condition(
            lambda: (
                self.latest
                and (
                    self.latest["issue"]["state"] == "closed"
                    or self.count >= 500
                    or workflow.info().is_continue_as_new_suggested()
                )
            )
        )
        await workflow.wait_condition(workflow.all_handlers_finished)
        if self.latest["issue"]["state"] == "closed":
            return self.latest
        workflow.continue_as_new(self.latest)

    @workflow.update(dynamic=True)
    def stage_changed(self, name: str, args: Sequence[RawValue]) -> int:
        event = workflow.payload_converter().from_payload(args[0].payload, dict)
        if event["sequence"] > self.latest.get("sequence", 0):
            self.record(event)
        return self.latest["sequence"]

    def record(self, event):
        item = event["issue"]
        self.latest = event
        self.count += 1
        workflow.upsert_search_attributes([STAGE.value_set(item["stage"])])
        workflow.upsert_memo(
            {
                "DeliveryStage": item["stage"],
                "Issue": item["url"],
                "RecordedAt": event["recorded"],
            }
        )
        workflow.set_current_details(
            f"**{item['stage']}**\n\n{item['detail']}\n\n"
            f"[Issue #{item['number']}]({item['url']}) · Recorded by GitHub {event['recorded']}"
        )

    @workflow.query
    def current_stage(self) -> dict:
        return self.latest
