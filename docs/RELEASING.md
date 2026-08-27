---
summary: "Release checklist — Python pyproject version bump + APK latest-build tag flow."
read_when:
  - "Cutting a new release"
  - "Verifying what 'shipped' means for this repo"
---

# Releasing

"Shipped" = a release **git tag**, not a merge to `main`. The debug APK is published continuously to the `latest-build` release on every push to `main`; a versioned release is the explicit, tagged cut.

## Checklist

1. **Review distribution policy** in [store-submission.md](store-submission.md). A GitHub release does not imply Google Play eligibility.
2. **CHANGELOG.md** — move items from `## [Unreleased]` into a new `## [X.Y.Z]` section (dated). Reset `[Unreleased]` to empty. One bullet per entry; preserve `#PR` + contributor credit.
3. **Bump versions** in `pyproject.toml`, `hermes-android-bridge/app/build.gradle.kts`, and `hermes-android-plugin/plugin.yaml`; bump Android `versionCode`. Update visible README/plugin strings.
4. **Verify version alignment:** `python scripts/check_versions.py`.
5. **Quality gates:** `uv run pytest`, `uv run ruff check .`, `uv build`, and `cd hermes-android-bridge && ./gradlew testDebugUnitTest lintDebug assembleDebug`.
6. **Signing:** configure the four `ANDROID_KEYSTORE_*` repository secrets described below. Never use a debug key for a versioned release.
7. **Commit:** `chore(release): vX.Y.Z` (explicit paths only).
8. **Tag:** create and push `vX.Y.Z`. The release workflow builds signed APK/AAB assets, checksums them, and creates the GitHub Release.
9. **Verify:** download the release assets, verify SHA-256, install/upgrade on a clean test device, and confirm the plugin version via `/plugins`.
10. **Reopen `[Unreleased]`** at the top of CHANGELOG.md.

## Release-signing secrets

Configure these encrypted GitHub Actions secrets before pushing a version tag:

- `ANDROID_KEYSTORE_BASE64` — base64 of the release keystore file.
- `ANDROID_KEYSTORE_PASSWORD` — keystore password.
- `ANDROID_KEY_ALIAS` — signing-key alias.
- `ANDROID_KEY_PASSWORD` — signing-key password.

Keep an offline, encrypted backup of the release key. Losing it can prevent
future upgrades for users of the APK-signing lineage.

## Guardrails

- Do NOT push or publish without explicit confirmation.
- Update compare/tag links at the bottom of CHANGELOG.md.
- Run quality gates (`/fix`) before tagging.
- Never put reviewer accounts, relay tokens, pairing codes, or keystores in the repository or release notes.
