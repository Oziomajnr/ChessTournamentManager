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

## License

See repository license (add one if you publish publicly).
