---
summary: "Build, runtime, test, platform, and optional infrastructure dependencies."
read_when:
  - "Setting up a development machine"
  - "Auditing or updating dependencies"
  - "Preparing a release SBOM"
---

# Dependency inventory

This document lists the project's direct dependencies and points to the files
that enumerate resolved transitive dependencies. It intentionally contains no
device addresses, credentials, or reviewer secrets.

## Android bridge

### Toolchain and platform

| Dependency | Version / range | Source | Purpose |
|---|---:|---|---|
| JDK | 17 | CI + Gradle config | Java/Kotlin compiler runtime |
| Gradle wrapper | 8.6 | `gradle/wrapper/gradle-wrapper.properties` | Reproducible build runner |
| Android Gradle Plugin | 8.3.0 | `gradle/libs.versions.toml` | Android build and lint |
| Kotlin Android plugin | 1.9.22 | `gradle/libs.versions.toml` | Kotlin compiler integration |
| Android compile SDK | 34 | `app/build.gradle.kts` | Compile-time Android API |
| Android target SDK | 34 | `app/build.gradle.kts` | Runtime compatibility behavior |
| Android minimum SDK | 26 | `app/build.gradle.kts` | Oldest supported Android version |

The current target is acceptable for private APK distribution but is a public
Google Play submission blocker after 2026-08-31. See
[store-submission.md](store-submission.md) before changing it: moving to API 36
requires behavior and policy testing, not just changing two integers.

### Runtime libraries

| Library | Direct version | Purpose |
|---|---:|---|
| `io.ktor:ktor-server-core` | 2.3.7 | Local authenticated HTTP server |
| `io.ktor:ktor-server-netty` | 2.3.7 | Embedded Netty server engine |
| `io.ktor:ktor-server-content-negotiation` | 2.3.7 | Request/response content handling |
| `io.ktor:ktor-serialization-gson` | 2.3.7 | JSON serialization |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.7.3 | Android coroutine dispatch |
| `com.google.code.gson:gson` | 2.10.1 | JSON objects used by bridge commands |
| `com.squareup.okhttp3:okhttp` | 4.12.0 | Outbound relay WebSocket client |

### Android test libraries

| Library | Direct version | Purpose |
|---|---:|---|
| `junit:junit` | 4.13.2 | JVM unit test runner |
| `io.mockk:mockk` | 1.13.10 | Kotlin mocking |
| `org.robolectric:robolectric` | 4.11.1 | Android behavior on the JVM |
| `org.jetbrains.kotlinx:kotlinx-coroutines-test` | 1.7.3 | Deterministic coroutine tests |

`hermes-android-bridge/app/gradle.lockfile` records resolved app configurations,
and `hermes-android-bridge/settings-gradle.lockfile` records settings/plugin
resolution. Regenerate them only as part of an intentional dependency update:

```bash
cd hermes-android-bridge
./gradlew :app:dependencies --write-locks
./gradlew testDebugUnitTest lintDebug assembleDebug
```

## Python toolset and relay

Python 3.11 or newer is required.

### Runtime packages

| Package | Declared range | Purpose |
|---|---:|---|
| `requests` | `>=2.28.0,<3` | Synchronous direct bridge calls and binary fetches |
| `aiohttp` | `>=3.9.0,<4` | Async WebSocket/HTTP relay |

### Development packages

| Package | Declared range | Purpose |
|---|---:|---|
| `build` | `>=1.2.2,<2` | sdist/wheel build verification |
| `pytest` | `>=8.3,<10` | Unit and integration-style tests |
| `pytest-mock` | `>=3.14,<4` | Pytest mock fixture |
| `responses` | `>=0.25,<1` | HTTP response mocking |
| `ruff` | `>=0.12,<1` | Formatting-independent lint/import checks |

`uv.lock` records the resolved Python dependency graph. `pyproject.toml` is the
metadata source of truth; `requirements.txt` remains a small compatibility list
for runtime-only `pip` installs.

## Android OS capabilities

These are platform capabilities, not third-party SDKs. The app declares them in
`AndroidManifest.xml`; most require explicit user approval or a special-access
screen.

- Internet and wake lock for the bridge transport and foreground lifetime.
- Accessibility service for UI inspection and user-authorized automation.
- Overlay, notification listener, and MediaProjection approval.
- Camera, microphone, coarse/fine location, contacts, SMS, phone, notifications,
  and scoped media access for their corresponding optional tools.
- USB host support and Android's per-device USB authorization prompt.
- Installed-package visibility and broad storage declarations, which are
  public-store policy blockers unless removed or formally approved.

## Optional operator infrastructure

None of these are linked into the APK:

- `hermes-agent` and an AI/model provider selected by the operator;
- Linux `systemd` for the sample relay service;
- a TLS reverse proxy such as Caddy or nginx for internet-facing deployments;
- a private overlay network such as Tailscale for direct-device development;
- Android Platform Tools (`adb`) for USB or Wi-Fi installation; and
- a USB-C OTG adapter/peripheral for USB-host features.

## Update policy

Dependabot proposes Gradle, Python, and GitHub Actions updates weekly. Every
update must pass Python tests, Ruff, package build, Android unit tests, Android
lint, and APK assembly. Security-sensitive transport, permission, or serializer
updates also require a real-device smoke test and a changelog entry.
