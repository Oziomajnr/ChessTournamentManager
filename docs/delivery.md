# Task delivery

The delivery policy accepts JSON and returns the next action. It runs with Python 3.9 or later and the standard library. The task host supplies issue state, review results and build checks. The distributor supplies testing availability, production availability, rollout percentage and health measurements. The artifact identity combines the source revision, build identifier and SHA-256 digest. Another project can replace the adapters and use the same policy.

```sh
echo '{"kind":"task","state":{"planned":true,"has_change":true,"checks":"passed","review":"pending"}}' | python3 -m delivery.workflow
python3 -m unittest discover -s delivery/tests
```

## Task intake and merge

Add `agent-ready` to a GitHub issue as a maintainer. From chat or a shell, create the canonical issue with the following command and the approved task description. The issue stays open through release.

```sh
python3 -m delivery.adapters.github "Task title" task.md
```

The scheduled worker snapshots the issue, posts acceptance criteria, proposes source changes on a task branch and opens a pull request. A separate model invocation reviews the actual diff. GitHub runs the JVM tests, lint, debug and unsigned release builds, the bundle policy scan, and API 36 emulator tests. The controller checks the exact PR revision and permits two repair attempts before it leaves the issue with `delivery-attention`. After checks and review pass, GitHub merges the PR and the controller queues the merged commit for release.

The OpenAI adapter accepts structured requests and returns proposed text edits. It receives the approved source files and acceptance criteria. A separate job applies validated edits. The allowed source roots are configured in `delivery/config.json`. Changes to delivery controls, build configuration or other files outside those roots go through a maintainer PR.

## Internal testing and production

One candidate advances at a time. The controller reserves a new version code, builds the merged revision, verifies the package and version inside the AAB, and signs it in a separate job. A draft GitHub release stores one archive containing the signed AAB and its manifest. The controller verifies its SHA-256 before internal upload and checks the version and SHA returned by Play. Subsequent stages promote the same version.

Use a dedicated Android device or emulator enrolled in internal testing with Play automatic updates enabled. Run the worker from a trusted checkout with ADB on PATH and an authenticated maintainer GitHub CLI. It waits for Google Play to install the exact candidate, launches the app, opens Tournaments and reports the result against the candidate identity.

```sh
python3 -m delivery.adapters.android_smoke --serial DEVICE_SERIAL --watch
```

The smoke evidence covers install provenance, version, launch and tournament navigation. Account-dependent features such as native Play review-card visibility need separate evidence. The production approval summary links to the PR checks, release notes and device report. An unavailable device holds the release on internal testing.

Approve the pending `production` environment deployment once per artifact. The controller reconciles that approval against the recorded run and artifact before changing the production track. It starts at 10%, advances to 50%, then to 100%. Each partial stage requires complete, fresh daily measurements for that version after the stage became available. The defaults require 100 users for each metric, a period ending within four days, crash rate below 1.09% and ANR rate below 0.47%. Edit the project configuration to change these thresholds. [Android vitals](https://developer.android.com/topic/performance/vitals)

Sparse samples or pending Play review hold the current stage. A measured regression halts a partial rollout and returns the issue for a repair PR. A failed device test also starts a repair. Its replacement receives a new version, digest and approval. Commits to the Play API use `ERROR_IF_IN_REVIEW`. The controller closes the canonical issue after Play reports that exact version published at 100%. [Google Play edit commit](https://developers.google.com/android-publisher/api-ref/rest/v3/edits/commit) · [Release publication states](https://developers.google.com/android-publisher/api-ref/rest/v3/applications.tracks.releases)

## GitHub configuration

| Environment | Values | Purpose |
| --- | --- | --- |
| `delivery-agent` | `OPENAI_API_KEY` | Planner, code proposals and independent review |
| `android-build` | `FIREBASE_PROJECT_ID`, `FIREBASE_API_KEY`, `FIREBASE_WRITE_KEY` | Client configuration for the app’s Publish to web feature |
| `android-signing` | `ANDROID_UPLOAD_KEYSTORE_B64`, `ANDROID_UPLOAD_KEY_ALIAS`, `ANDROID_UPLOAD_STORE_PASSWORD`, `ANDROID_UPLOAD_KEY_PASSWORD` | Android upload signature |
| `play-publishing` | `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` | Internal distribution, approved production transitions and Android vitals |
| `production` | Required maintainer reviewer, administrator bypass disabled | Records approval for the exact signed artifact |

Restrict each environment to the `main` branch. Set repository variable `DELIVERY_AGENT_ENABLED=true` when its key is configured and `DELIVERY_RELEASE_ENABLED=true` when release jobs are commissioned. The optional `DELIVERY_MODEL` variable selects the OpenAI model. The controller uses a separate `delivery-state` branch with conditional GitHub writes and a draft release per candidate. The app build, model and distribution jobs each receive their own environment.

Run the following hosted verification to exercise protected credential reads, the production reviewer configuration, Android packaging and signing without submitting an app release.

```sh
gh workflow run release.yml --ref main -f mode=verify
```
