# Google Play review prompt for Chess Tournament Manager

## When to ask

Request the native Google Play review card on the tournament overview after **Generate Round** saves new pairings for round 2, 3, or 4 of a running Swiss or round-robin tournament. The paired round must precede the final round and contain at least one two-player board. A three-round event qualifies only at round 2.

Allow one request per tournament. Across an installation, the first eligible pairing can prompt immediately. Wait 15 days after the first request, 30 after the second, and 90 after every subsequent request. Check the interval only when a new round is paired. Every call to request a review counts, including calls that Play declines.

## Implementation

1. `TournamentViewModel.generateNextRound` advances the expected round, inserts pairings, and applies any bye scoring in one Room transaction. Only a successful commit can notify the overview that initiated generation.
2. The overview waits for the committed round to appear. It checks the current destination lifecycle, tournament, round, and window before requesting and again before launching the Play card. Leaving the overview discards that visit's pending opportunity.
3. `ReviewAttemptStore` serializes the history check and atomic write in the installation's no-backup directory. It records the timestamp, attempt count, and tournament IDs before calling Play. If the overview leaves during the write, it restores the previous history.
4. Google Play owns the card and keeps its visibility and submission private. An existing account review is one reason Play can suppress the card. [Kotlin integration guide](https://developer.android.com/guide/playcore/in-app-review/kotlin-java) · [Google Play eligibility](https://developer.android.com/guide/playcore/in-app-review/test)

## Acceptance checks

| Organizer action | Expected result |
| --- | --- |
| Pairs round 2 in a three-round event on a fresh installation | One Play request from the overview. |
| Opens an existing round, enters results, or returns to the overview | Zero requests. |
| Generates the opening round, final round, or a bye-only round | Zero requests. |
| Attempts to generate with outstanding results or a failed database write | Zero requests. A failed commit rolls back the round, boards, and bye scores together. |
| Runs eligible tournaments on days 0, 15, 45, 135, and 225 | Each can request once. An earlier pairing inside the cooldown leaves a later round eligible. |
| Double taps Generate, rotates, or opens another screen while Play responds | Each tournament makes at most one request. A stale response cannot launch a card. |

Run `:app:testDebugUnitTest` for the persisted schedule and `:app:connectedDebugAndroidTest` for the pairing and navigation checks. To verify the native card, use a Play internal tester who installed the app through Play and has no existing review, then repeat with an account that already reviewed. Use a fresh local attempt history for each account so the app's cooldown does not mask Play's behavior. [Google Play testing guide](https://developer.android.com/guide/playcore/in-app-review/test)
