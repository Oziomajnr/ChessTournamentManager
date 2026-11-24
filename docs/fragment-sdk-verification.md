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

## Verification on November 25, 2026

The release AAB built successfully. Its `base/root/META-INF/androidx.fragment_fragment.version` entry contained `1.8.9`. The release policy check and all 25 JVM tests passed. All 9 `RoundReviewTest` cases passed on an API 36 emulator.

After Google Play processes the new version code, record the version-specific SDK report in [issue #7](https://github.com/Oziomajnr/ChessTournamentManager/issues/7).
