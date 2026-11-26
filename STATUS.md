# Issue delivery status

Issue numbers mark their current stage. Follow a single issue's live timeline on your Mac. [Local Temporal viewer](https://github.com/Oziomajnr/ChessTournamentManager/blob/main/docs/delivery.md#local-temporal-viewer)

```mermaid
flowchart LR
    accTitle: Current issue positions in the delivery workflow
    accDescr: Development flows into GitHub checks and Google Play. Each issue number appears at its current step.
    subgraph development["Development"]
        direction TB
        backlog["Backlog<br/>#6 #13"]:::occupied
        ready["Ready for pickup"]
        plan["Planning"]
        code["Implementation"]
        review["Code review"]
        backlog --> ready --> plan --> code --> review
    end
    subgraph integration["GitHub checks"]
        direction TB
        pull["Pull request"]
        ci["CI checks"]
        merge["Merge"]
        queue["Release queue"]
        pull --> ci --> merge --> queue
    end
    subgraph distribution["Google Play"]
        direction TB
        build["Build and sign"]
        internal["Internal distribution"]
        reports["Post-upload checks"]
        device["Device check"]
        approval["Owner approval"]
        rollout["Production rollout<br/>#7"]:::occupied
        published["Published at 100%"]
        build --> internal --> reports --> device --> approval --> rollout --> published
    end
    development --> integration --> distribution
    classDef occupied fill:#165b51,color:#ffffff,stroke:#38bba0,stroke-width:3px
```

## Open issues

| Issue | Stage | Next step | Evidence |
| --- | --- | --- | --- |
| [#6 Raise release DEX obfuscation from 0% to at least 25% by February 2027](https://github.com/Oziomajnr/ChessTournamentManager/issues/6) | Backlog | A maintainer can label this issue agent-ready. | — |
| [#7 Update the transitive AndroidX Fragment dependency flagged by Google Play](https://github.com/Oziomajnr/ChessTournamentManager/issues/7) | Production rollout | Waiting for Play to confirm 10%. | [PR #11](https://github.com/Oziomajnr/ChessTournamentManager/pull/11) · [Commit aa6417c](https://github.com/Oziomajnr/ChessTournamentManager/commit/aa6417cbf203aefbf552946f59f74e62709a514f) · [Production approval](https://github.com/Oziomajnr/ChessTournamentManager/actions/runs/45740723162) |
| [#13 Invite organizers to support app development with a one-time donation](https://github.com/Oziomajnr/ChessTournamentManager/issues/13) | Backlog | A maintainer can label this issue agent-ready. | — |
