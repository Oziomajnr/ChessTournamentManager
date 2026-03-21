# Chess Tournament Manager

Android app for running chess tournaments: Swiss (FIDE C.04.1 style) and Round Robin pairings, standings, tiebreaks, backup/restore, optional web publishing, and Google Play Billing for Pro features.

## Requirements

- JDK 17
- Android SDK (API 35 compile / target)

## Setup

1. Clone the repo.
2. Copy `local.properties.example` to `local.properties`.
3. Set `sdk.dir` to your Android SDK path.
4. Optional: add Firebase keys for “Publish to web” (see `web/` and `FirebaseConfig.kt`).
5. Optional: configure release signing in `local.properties` (keystore is not in the repo).
6. **Premium / Remote Config**: Premium is disabled by default via Firebase Remote Config (`premium_required` = false). To enable premium later: add your project’s `google-services.json` to `app/`, add the Android app in Firebase Console, create the `premium_required` parameter in Remote Config and set it to `true`.

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
