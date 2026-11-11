<h1 align="center">Android release channels</h1>

<p align="center"><strong>Project reference</strong><br>Setup, usage, and maintenance guidance.</p>

<p align="center">
  <a href="../../README.md">Project overview</a>
</p>

ClickControl uses GPL-3.0-or-later (SPDX). See the root LICENSE. Dependency
licenses and notices remain applicable.

Every push is checked. Pushes to main automatically build signed APK/AAB files
and replace the assets of one **Development build — automatic** prerelease at
`development`. Its release ID is reused and its tag moves only after replacement
uploads and downloads verify. Asset and checksum filenames identify the source
version and workflow run. Superseded development assets are removed only after
all replacements pass verification. Concurrent release runs are queued, never
cancelled mid-publication. A stale main build retains Actions artifacts but does
not replace the current development release.

A manual workflow run defaults to `publish=false`: it validates and builds the
selected main commit or matching stable tag without publishing. PRs and other
branches only run checks and unsigned builds, without signing credentials.
Desktop packaging runs in its separate workflow and automatically publishes
pre-releases (Alpha) for desktop installers and portable bundles on main pushes.

## Signing migration and secrets

**Older ClickControl APKs were signed with an Android debug certificate. Back up
app data, uninstall the old APK, then install the newly signed release. Android
cannot update that installation across these unrelated certificates.** Future
updates must retain the newly configured release certificate.

Required repository secrets: `ANDROID_KEYSTORE_BASE64`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.
Required public repository variable: `RELEASE_CERT_SHA256`.
The expected certificate is
`a9bfa9b78db935c46c7cc3be63ac338c37c2b121f329fff390d9173cc682702e`.
The private backup is outside the checkout at
`~/.local/share/android-signing/ClickControl`; copy it to a secure offline backup.
Never paste these secrets into chats or commit them. GitHub does not allow
reading secret values back. Local unsigned release builds are permitted, but
publication refuses missing secrets and requires signature verification.

## Stable releases: explicit user request only

An agent must receive an explicit stable release request before creating/pushing
a numbered tag. Do not issue a stable release as part of ordinary push work.

1. Start from current main, inspect changes and choose an unused numeric X.Y.Z.
2. Prepare one release commit: set literal `versionName` in
   `app/build.gradle.kts`, add `docs/releases/X.Y.Z.md`, and set literal
   `versionCode` to `1000000 + 2 * (current_commit_count + 1) + 1`. The extra
   commit is the release preparation commit. Recalculate after any additional
   commit, merge or rebase. Check full history with `git rev-list --count HEAD`.
3. Run Python release tests, Android unit tests and lint. Commit the prepared
   release, push main, wait for the signed development workflow to pass.
4. Verify the committed default code equals `1000000 + 2 * commit_count + 1`
   at that exact commit; tag it `vX.Y.Z` and push that tag. The workflow requires
   exact tag/version matching, nonempty release notes and main ancestry.
5. Wait for Actions, verify the stable release is Latest, assets/checksums,
   signatures, application ID/version metadata, tag commit and source notes.

The fixed epoch is 1000000. Development code is `1000000 + 2 * commit_count`;
stable is one greater at the same commit, and next main development is greater
again. Code must not exceed 2100000000. Do not rewrite main history or change the
epoch after distribution. A later development build can be newer than the latest
stable: returning to an older stable may require clearing/reinstalling the app.
Historical stable releases without a version-code metadata marker cannot be
checked automatically; inspect their APK metadata before migration/release.

Stable releases start as drafts. Surviving drafts are reused by release ID;
publication requires complete verified downloads. Published stable assets are
never overwritten: reruns verify source, inventory and published checksums.

## Obtainium, F-Droid and Google Play

In Obtainium add `https://github.com/thies2005/AnkiMywhooshControl`. For stable
updates exclude prereleases; for development enable prereleases and filter to
tag `development`. Filter APK assets to `^ClickControl-android-.*\.apk$` so desktop
assets and AABs are ignored. A signed AAB is for store upload, not direct install.

For rolling development updates, turn on **Release Date as Version** and **Use Latest Asset Date as Release Date**, and turn off **Verify Latest Tag**. The `development` tag stays constant, so tracking its name alone will miss replacement builds. Keep the tag filter at `^development$` and the APK filter at `\.apk$`. Before the first new stable release, use this development configuration. See the [Obtainium source options](https://wiki.obtainium.imranr.dev/sources/) and [version tracking](https://wiki.obtainium.imranr.dev/app_tracking/).

The `fastlane/metadata/android/en-US` text and
`fdroid/metadata/com.ankiride.bridge.yml` are preparation drafts. The metadata is
disabled until an immutable clean build recipe, dependency/license scan, and
reproducibility/signing review are complete. GPL selection removes the missing
license blocker; it does not prove F-Droid acceptance. F-Droid normally builds
from source and signs independently. Keeping the developer signature requires
their reproducible-build verification process. No submission has been made.

AABs are produced for future Google Play onboarding; no Play credentials or
automatic store uploads are configured. For cross-store update compatibility,
choose Play App Signing onboarding that retains the same app signing identity.
An upload key may be distinct from the app signing key. Follow Play Console's
current requirements and review target SDK, accessibility declarations, privacy
policy and store listing before submission.
