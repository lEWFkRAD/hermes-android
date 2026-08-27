#!/usr/bin/env python3
"""Fail when release versions drift across project metadata."""

from __future__ import annotations

import re
import sys
import tomllib
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def require_match(path: Path, pattern: str, label: str) -> str:
    match = re.search(pattern, path.read_text(encoding="utf-8"), flags=re.MULTILINE)
    if not match:
        raise RuntimeError(f"Could not find {label} in {path.relative_to(ROOT)}")
    return match.group(1)


def main() -> int:
    with (ROOT / "pyproject.toml").open("rb") as handle:
        canonical = tomllib.load(handle)["project"]["version"]

    versions = {
        "pyproject.toml": canonical,
        "Android versionName": require_match(
            ROOT / "hermes-android-bridge/app/build.gradle.kts",
            r'^\s*versionName\s*=\s*"([^"]+)"',
            "versionName",
        ),
        "plugin.yaml": require_match(
            ROOT / "hermes-android-plugin/plugin.yaml",
            r"^version:\s*([^\s#]+)",
            "plugin version",
        ),
    }

    mismatches = {name: value for name, value in versions.items() if value != canonical}
    if mismatches:
        print(f"Canonical version: {canonical}", file=sys.stderr)
        for name, value in mismatches.items():
            print(f"Version mismatch: {name} has {value}", file=sys.stderr)
        return 1

    print(f"Version metadata aligned at {canonical}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
