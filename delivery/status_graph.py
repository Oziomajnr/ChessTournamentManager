"""Workflow steps published in the GitHub diagram and the Temporal observer's feed."""

import re
from collections import defaultdict
from textwrap import wrap


def lane(key, label, steps):
    return {
        "id": key,
        "label": label,
        "steps": [{"id": k, "label": v} for k, v in steps],
    }


LANES = [
    lane(
        "development",
        "Development",
        [
            ("backlog", "Backlog"),
            ("ready", "Ready for pickup"),
            ("plan", "Planning"),
            ("code", "Implementation"),
            ("review", "Code review"),
        ],
    ),
    lane(
        "integration",
        "GitHub checks",
        [
            ("pull", "Pull request"),
            ("ci", "CI checks"),
            ("merge", "Merge"),
            ("queue", "Release queue"),
        ],
    ),
    lane(
        "distribution",
        "Google Play",
        [
            ("build", "Build and sign"),
            ("internal", "Internal distribution"),
            ("device", "Device check"),
            ("approval", "Owner approval"),
            ("rollout", "Production rollout"),
            ("published", "Published at 100%"),
        ],
    ),
    lane(
        "exceptions",
        "Other states",
        [("attention", "Needs attention"), ("closed", "Closed outside delivery")],
    ),
]


def mermaid(board):
    occupants = defaultdict(dict)
    for issue in board["issues"]:
        occupants[issue["step"]].setdefault(issue["stage"], []).append(
            int(issue["number"])
        )
    lines = [
        "```mermaid",
        "flowchart LR",
        "    accTitle: Current issue positions in the delivery workflow",
        "    accDescr: Development flows into GitHub checks and Google Play. Each issue number appears at its current step.",
    ]
    for group in board["lanes"]:
        separate = group["id"] == "exceptions"
        steps = [s for s in group["steps"] if not separate or occupants[s["id"]]]
        if not steps:
            continue
        lines.extend(
            [f'    subgraph {group["id"]}["{group["label"]}"]', "        direction TB"]
        )
        for step in steps:
            key = step["id"]
            labels = [step["label"]]
            for stage, ids in occupants[key].items():
                if stage != step["label"] and re.fullmatch(r"[A-Za-z0-9 %]+", stage):
                    labels.append(stage)
                labels.extend(
                    wrap(
                        " ".join(f"#{n}" for n in ids), width=23, break_long_words=False
                    )
                )
            label = "<br/>".join(labels)
            style = ":::occupied" if occupants[key] else ""
            lines.append(f'        {key}["{label}"]{style}')
        if not separate:
            lines.append("        " + " --> ".join(s["id"] for s in steps))
        lines.append("    end")
    lines.extend(
        [
            "    development --> integration --> distribution",
            "    classDef occupied fill:#165b51,color:#ffffff,stroke:#38bba0,stroke-width:3px",
            "```",
        ]
    )
    return lines
