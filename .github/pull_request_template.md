## Summary

Describe the user-visible change and why it is needed.

## Validation

- [ ] Python tests pass.
- [ ] Ruff passes.
- [ ] Android unit tests pass.
- [ ] Android lint passes.
- [ ] The debug APK builds.
- [ ] Hardware-only behavior has a reproducible manual-test note.

## Security and privacy

- [ ] No pairing code, token, private address, device screenshot, recording,
      signing material, or personal device data is included.
- [ ] New endpoints authenticate requests, bound inputs, and redact errors.
- [ ] Permission, privacy, and store-policy effects are documented.
- [ ] Destructive device actions still require explicit user confirmation.

## Release impact

- [ ] Versions remain aligned (`python scripts/check_versions.py`).
- [ ] Documentation and `CHANGELOG.md` are updated when required.
- [ ] This change does not require release-signing or reviewer credentials.
