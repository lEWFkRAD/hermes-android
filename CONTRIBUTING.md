# Contributing to hermes-android

Hermes Bridge is security-sensitive remote-control software. A change is ready
to submit only when it is narrowly scoped, tested, documented, and safe to run
against a real device.

## Development setup

### Python toolset

Requirements: Python 3.11 or newer. `uv` is recommended, but standard `pip`
works.

```bash
uv sync --extra dev
uv run pytest
uv run ruff check .
uv build
```

Equivalent `pip` setup:

```bash
python -m venv .venv
.venv/bin/pip install -e ".[dev]"
.venv/bin/python -m pytest
.venv/bin/ruff check .
```

### Android bridge

Requirements: JDK 17 and an Android SDK. The Gradle wrapper downloads the
declared Gradle distribution and resolves the Android plugin and libraries.

```bash
cd hermes-android-bridge
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

See [docs/dependencies.md](docs/dependencies.md) for exact direct versions and
the lockfiles that enumerate transitive dependencies.

## Submission rules

1. Open an issue before large protocol, permission, authentication, or storage
   changes. Small fixes do not need a design issue.
2. Never commit pairing codes, access tokens, private server addresses, device
   screenshots, phone numbers, contacts, messages, locations, recordings,
   signing keys, or real reviewer credentials.
3. Preserve Android's permission boundary. USB access must remain explicitly
   user-authorized per device. Sensitive runtime permissions must remain
   revocable and feature-scoped.
4. On-device commands that purchase, send, call, delete, factory-reset, unlock,
   or weaken security require an explicit user confirmation at execution time.
5. New endpoints require authentication, bounded inputs, redacted error/log
   output, and regression tests for denied, malformed, and oversized requests.
6. A Python tool change must be applied to both `tools/` and
   `hermes-android-plugin/`, or generated from a single source in the same PR.
7. A bug fix requires a regression test in `tests/` or the Android unit-test
   tree. Hardware-only behavior also needs a reproducible manual-test entry.
8. Do not weaken TLS guidance, enable public bind addresses by default, or add
   analytics/advertising SDKs without a security and privacy review.
9. Keep versions aligned across `pyproject.toml`, the Android app, and plugin
   metadata. Run `python scripts/check_versions.py`.
10. All required CI checks must pass before merge. Do not merge your own change
    while a requested security review is unresolved.

## Pull request checklist

- [ ] The change is scoped and described in user-facing terms.
- [ ] Tests cover success and failure paths.
- [ ] `pytest`, Ruff, Android unit tests, Android lint, and the debug build pass.
- [ ] No secrets, device data, screenshots, recordings, or signing files are included.
- [ ] Permission, privacy, and store-policy impact is documented.
- [ ] README, changelog, tool count, and protocol documentation are current.
- [ ] Manual device checks list the Android version and form factor without identifying the device owner.

## Commit and review style

Use Conventional Commits, commonly `feat(bridge):`, `fix(bridge):`,
`test(bridge):`, or `docs:`. Keep mechanical formatting separate from behavior
changes when that makes review clearer.

Report vulnerabilities privately as described in [SECURITY.md](SECURITY.md),
not in a public issue.
