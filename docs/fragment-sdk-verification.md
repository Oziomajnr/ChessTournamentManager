# AndroidX Fragment in the release bundle

Google Play flagged Fragment 1.1.0 in version code 5 and requested 1.2.1 or later. The app now constrains the transitive dependency to the stable 1.8.9 release, which builds with the app’s Kotlin 1.9.22 toolchain. [AndroidX release notes](https://developer.android.com/jetpack/androidx/releases/fragment#1.8.9)

## Release dependency check

```sh
./gradlew :app:dependencyInsight --configuration releaseRuntimeClasspath --dependency androidx.fragment:fragment
```

| Dependency path | Fragment requested | Fragment selected |
| --- | --- | --- |
| Play In-App Review Kotlin extensions 2.0.2, directly | 1.1.0 | 1.8.9 |
| Play In-App Review 2.0.2 and its Kotlin extensions, via Play services basement 18.4.0 | 1.1.0 | 1.8.9 |
| Firebase Analytics 22.1.2, via Play services measurement and basement 18.4.0 | 1.1.0 | 1.8.9 |
| Firebase Analytics 22.1.2, via Play services measurement and base 18.5.0 | 1.0.0 | 1.8.9 |

Gradle printed these module selections in the release report.

```text
androidx.fragment:fragment:1.8.9
androidx.fragment:fragment:1.0.0 -> 1.8.9
androidx.fragment:fragment:1.1.0 -> 1.8.9
```

## Verification on November 25, 2026

The full `:app:testDebugUnitTest` and `:app:connectedDebugAndroidTest` runs produced these JUnit XML totals. Both the local ARM64 and GitHub x86_64 Android 16 (API 36) emulators passed. The downloaded GitHub reports matched the local results. [JVM and lint reports](https://github.com/Oziomajnr/ChessTournamentManager/actions/runs/45440465195/artifacts/14943930974) · [Device test report](https://github.com/Oziomajnr/ChessTournamentManager/actions/runs/45440465195/artifacts/14943696696)

| Suite | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| `BbpPairingsTest` | 2 | 0 | 0 | 0 |
| `SwissPairingFideRulesTest` | 18 | 0 | 0 | 0 |
| `ReviewAttemptStoreTest` | 5 | 0 | 0 | 0 |
| `RoundReviewTest` (full device suite) | 9 | 0 | 0 | 0 |

The same Gradle invocation built the debug APK and release AAB and passed debug lint. The local build log ended with `BUILD SUCCESSFUL in 1m 6s`. The bundle passed `./scripts/verify_play_policy.sh --require-bundle`. Reading the packaged version directly returned `1.8.9`. [GitHub build and policy log](https://github.com/Oziomajnr/ChessTournamentManager/actions/runs/45440465195/job/138609832631)

```sh
unzip -p app/build/outputs/bundle/release/app-release.aab base/root/META-INF/androidx.fragment_fragment.version
```

## Google Play follow-up

The release workflow assigns a new version code to the merged commit, signs it and submits it to internal testing. In Play Console, **Test and release → For your next release → Fragment warning → Read more** lists the affected version codes. On November 25 the list contained only production version code 5. After Play processes the candidate, record its version code and SDK result on the open issue. [Release workflow](delivery.md#internal-testing-and-production) · [Issue #7](https://github.com/Oziomajnr/ChessTournamentManager/issues/7)
