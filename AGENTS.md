# ClickControl

## Purpose and layout

Android app in `app/` and desktop app in `desktop/`, including a Python BLE worker. GPL-3.0-or-later and dependency notices must be preserved.

## Working agreements

- Inspect the code and instructions first; make the smallest correct change and
  preserve unrelated staged, unstaged and untracked work. Never commit secrets.
- Plan and independently review substantial changes in proportion to their risk,
  using available tools. Make routine reversible decisions without approval loops.
- Give delegated workers a bounded scope, working directory and acceptance
  criteria. Assigned workers do not recursively delegate. Serialize shared edits
  and device control, and inspect results before integration.
- Commit, push and publish only when authorized. Report actual providers/fallbacks,
  checks performed and concrete limitations; never imply an unavailable review ran.
- Read the repository root guide when working in a subdirectory. Read
  `../AGENTS.md` for workspace publishing guidance when present; essential project
  safeguards below remain applicable without ancestor-file discovery.

## Setup and commands

From root use JDK 17 for Android; JDK 21 for desktop. Android: `./gradlew :app:assembleDebug`. Desktop: `./gradlew createDistributable packageDistributionForCurrentOS -PskipAndroid=true`. Python worker packaging uses Python 3.11 and PyInstaller as described in desktop CI.

## Verification

From root: `./gradlew :app:testDebugUnitTest :app:lintDebug` for Android; `./gradlew :desktop:test -PskipAndroid=true` for desktop. For release workflow changes also run `python3 -m unittest discover -s scripts/tests -v` and `./gradlew :app:assembleRelease`. For Python worker changes run `python3 -m unittest discover -s desktop/src/test/python -v` and exercise the relevant packaging path when affected.

## Android QA

- For Android UI/flow changes, test relevant normal, error and persistence
  scenarios on an appropriate device when available; report blocked scenarios.
- Prefer an available GPT-6.1 Terra or Gemini Flash worker with both adb shell and
  screenshot viewing. Report the actual provider and any fallback.
- Record device serial, API, package, build variant and source commit. Use
  `adb -s SERIAL` for device operations; inspect UI-tree bounds before taps.
- Capture screenshots and scoped logs without secrets. Report pass/fail/blocked
  outcomes with evidence. Unit tests alone do not establish emulator validation.
- Serialize shared-device control. Do not perform production actions or reset
  user data without authorization.

## Release and boundaries

Preserve signing identity, immutable stable tags and published assets. A stable release requires an explicit request; ordinary pushes authorize no stable release. Follow the repository release guide for source versions, default-branch ancestry, checks, notes and publication; monitor workflows and verify downloads before claiming success. Do not clean up releases without explicit scoped authorization. APK/AAB or metadata alone does not imply Play or F-Droid publication.

Default-branch pushes update the signed Android `development` prerelease and publish automatic desktop alpha pre-releases via the separate desktop workflow per user instruction. Release signing requires the dedicated key, never a debug fallback.

The historical desktop release downloads were removed by explicit user decision;
do not restore them or apply the superseded retention policy from initial setup.

## Sources

[README](README.md), [release guide](docs/releases/README.md), [desktop workflow](.github/workflows/build-all-desktop.yml), [Android workflow](.github/workflows/android-release.yml).
