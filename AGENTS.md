# Issue delivery

When the maintainer asks to pick up an `agent-ready` issue, run the delivery commands from the trusted main checkout. A status request uses `python3 -m delivery.local list`.

1. Run `python3 -m delivery.local pickup` for the oldest available issue, or append the requested issue number. The command checks GitHub, claims the issue and prints its isolated worktree, approved task and saved plan. Resume an existing claim by specifying its issue number.
2. Reuse the saved plan. If the issue has none, write the acceptance criteria, checks and user-facing release notes as JSON and pipe that document to `python3 -m delivery.local plan ISSUE`. The local delivery guide shows the format.
3. Implement and test the task in the printed worktree. Commit the intended changes to its task branch. Use the issue's checks in addition to the repository's normal tests. For an approved AndroidX Fragment dependency upgrade, the local handoff accepts a pinned one-line Gradle dependency constraint.
4. From the trusted main checkout, run `python3 -m delivery.local submit ISSUE`. It independently reviews the committed diff using local ChatGPT-authenticated Codex and hands that exact commit to GitHub. Follow the review and PR checks, repair findings in the same worktree and resubmit. GitHub handles PR creation, protected merge and the release queue. Production approval belongs to the owner.

[Local delivery commands](docs/delivery.md#local-codex-pickup) · [Live issue status](https://github.com/Oziomajnr/ChessTournamentManager/blob/delivery-state/STATUS.md)
