"""Compatibility shim for older editable-install tooling.

Package metadata and dependencies live in pyproject.toml. Keeping this file
metadata-free prevents release versions from drifting between two sources.
"""

from setuptools import setup

setup()
