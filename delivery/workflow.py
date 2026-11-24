"""Portable JSON protocol: one observation on stdin produces one action on stdout."""

import json
import sys
from dataclasses import asdict

from delivery.policy import next_task, release_action


def decide(observation):
    data = observation["state"]
    if observation["kind"] == "task":
        return {"action": next_task(**data)}
    if observation["kind"] == "release":
        return asdict(release_action(**data))
    raise ValueError("Expected a task or release observation")


if __name__ == "__main__":
    print(json.dumps(decide(json.load(sys.stdin))))
