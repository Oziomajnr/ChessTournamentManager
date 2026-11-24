# Chess Tournament Manager

Android app for running chess tournaments: Swiss (FIDE C.04.1 style) and Round Robin pairings, standings, tiebreaks, backup/restore, and optional web publishing.

## Requirements

- JDK 17
- Android SDK Platform 36
- Android Gradle Plugin 8.13.2 (via the version catalog)
- Gradle 8.13 (via the Gradle wrapper)

## Setup

1. Clone the repo.
2. Copy `local.properties.example` to `local.properties`.
3. Set `sdk.dir` to your Android SDK path.
4. Optional: add Firebase keys for “Publish to web” (see `web/` and `FirebaseConfig.kt`).
5. Optional: configure release signing in `local.properties` (keystore is not in the repo).

## Build

```bash
./gradlew assembleDebug
```

Release bundle:

```bash
./gradlew bundleRelease
```

## Tests

```bash
./gradlew testDebugUnitTest
```

Includes simulation-based property tests for Swiss pairing (`SwissPairingFideRulesTest`).

Release-policy verification:

```bash
./scripts/verify_play_policy.sh
```

This verifies the resolved release dependency graph, merged release manifest, and—when already
built—the release AAB. It fails if the app stops targeting API 36 or if Google Play Billing is
added back to the release.

## Monetization

The app is free and has no subscriptions or in-app purchases. Features are not gated behind a
Pro tier. Do not add Google Play Billing or the `com.android.vending.BILLING` permission without
revisiting the product decision, store declarations, privacy policy, and release-policy checks.

## Web viewer

Static site and Firebase rules live under `web/`. Deploy with the Firebase CLI as needed.

## Publishing to GitHub

Use a **new** personal access token (never reuse a token that was pasted in chat). Install `gh` (`brew install gh`), then in your **local** terminal:

```bash
export GITHUB_TOKEN='ghp_...'   # classic PAT with repo scope
./scripts/github-create-and-push.sh
# optional: ./scripts/github-create-and-push.sh my-repo-name
# optional: GITHUB_OWNER=otheruser ./scripts/github-create-and-push.sh
```

Or: `gh auth login`, then `gh repo create Oziomajnr/chess-tournament-manager --public --source=. --remote=origin --push`.

## License

See repository license (add one if you publish publicly).

## Automated delivery

A maintainer can hand a task to the delivery workflow from chat or by labeling a GitHub issue. The workflow tracks that issue through tests, independent review, internal distribution, production approval and rollout. [Delivery workflow and device setup](docs/delivery.md)
