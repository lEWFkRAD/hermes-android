---
summary: "Distribution status, policy blockers, and submission checklists."
read_when:
  - "Publishing an APK or AAB"
  - "Preparing Google Play or Galaxy Store review"
  - "Changing permissions or Accessibility behavior"
---

# Distribution and store-submission rules

Policy snapshot: **2026-08-26**. Store policies change; re-check every linked
primary source immediately before submission.

## Supported release path today

The current app is intended for **private, owner-controlled distribution** from
GitHub Releases or an internal enterprise channel. It is a high-trust remote
control bridge with Accessibility, notification, screen, microphone, location,
contacts, SMS, phone, package visibility, storage, and USB capabilities.

For a private release:

1. Build from a clean tag with the repository release workflow.
2. Use a dedicated release keystore stored only in GitHub Actions secrets or an
   offline signing system. Never commit it.
3. Publish the signed APK plus SHA-256 checksums and release notes.
4. Document the minimum Android version, permissions, plaintext-transport risk,
   and operator responsibility.
5. Test fresh install, upgrade, permission denial/revocation, reboot recovery,
   folded/unfolded layouts, network loss, relay auth failure, and uninstall.

## Google Play: not eligible as currently designed

Do **not** submit the current full build to Google Play and expect approval.
Google's current policy says the Accessibility API is not designed for an app
that autonomously initiates, plans, and executes actions or decisions. Hermes
Bridge's defining AI-agent control behavior falls directly into that category.
Google also lists remote control of a user's phone as an invalid SMS permission
use case. See Google's [Accessibility API policy][play-accessibility] and
[SMS/Call Log policy][play-sms].

Additional blockers:

- The app targets API 34. Starting 2026-08-31, Google Play requires new phone
  apps and updates to target API 36 or higher. See the official
  [target API requirement][play-target].
- `SEND_SMS`, `MANAGE_EXTERNAL_STORAGE`, and `QUERY_ALL_PACKAGES` are restricted
  or high-risk declarations requiring removal or an approved core use case.
- Camera is currently staged in the manifest before its capture tool is shipped,
  conflicting with least-permission expectations.
- A non-accessibility-tool AccessibilityService needs an in-app prominent
  disclosure, affirmative consent before opening Android Settings, a Play
  declaration, and a review video. A privacy policy or store description alone
  is insufficient. See Google's [disclosure guidance][play-disclosure].
- The publisher must complete Play's Data safety form and accurately describe
  relay/model access to device data. See [Data safety][play-data-safety].
- Production traffic must not rely on cleartext internet transport.

A separate Play flavor would need a materially different product: no autonomous
agent execution, no direct SMS, no broad storage/package visibility, only
user-initiated and policy-eligible actions, feature-scoped permission prompts,
prominent consent, TLS-only transport, target API 36+, and a policy review before
implementation. Changing the marketing text alone is not sufficient.

## Samsung Galaxy Store: review candidate after remediation

Galaxy Store is the more plausible public channel for the Samsung-focused app,
but acceptance is not guaranteed. Before submission:

- Remove every unused permission and justify every remaining sensitive access.
- Add an in-app privacy-policy link and publisher contact. Samsung requires a
  policy for apps that access, collect, use, transmit, or share user data, and
  requires the same URL in Seller Portal. The policy must describe data types,
  purposes, third parties, retention/deletion, revision notice, and user rights.
- Add clear, feature-specific consent before sending device data to a relay or
  model, and preserve a decline path.
- Use a non-debuggable release build. Test install, launch, rotation/fold state,
  denial paths, background lifetime, network interruption, and all advertised
  features without crashes or layout errors. Samsung's
  [self-check list][galaxy-check] calls these out explicitly.
- Register an APK or AAB in Seller Portal. If using AAB, configure Galaxy Store
  signing. Provide listing text, countries, user-data disclosures, support and
  privacy URLs, screenshots, icon assets, and review comments.
- Give reviewers a dedicated, non-production relay and a short-lived test flow.
  Never put reviewer credentials or pairing codes in this repository. If a
  reviewer login is required, enter it only in Seller Portal's review field.
- Explain Accessibility, screen capture, notification access, microphone,
  location, contacts, SMS/phone, background service, package visibility, and USB
  behavior in the review notes. Include a video showing each permission prompt,
  the decline path, persistent foreground notification, and disconnect control.
- Submit for review only after the content is in Seller Portal's registering
  state. Samsung reviews the app before sale; see [Submit App][galaxy-submit].

Samsung's [App Distribution Guide][galaxy-policy] requires legitimate consent,
minimum necessary permissions, and compliance with the laws of every selected
country. A successful sideload test is not evidence of store-policy approval.

## Submission artifact checklist

- [ ] Unique application ID, semantic version, and monotonically increasing version code.
- [ ] Signed release APK/AAB; `android:debuggable=false` verified.
- [ ] SHA-256 checksums and software bill of materials/dependency inventory.
- [ ] App name, short/long descriptions, category, support contact, and privacy URL.
- [ ] High-resolution icon, phone/Fold screenshots, and permission walkthrough video.
- [ ] Data inventory and retention/deletion answers match `PRIVACY.md` and actual code.
- [ ] Accessibility and sensitive-permission declarations completed where applicable.
- [ ] Test relay/account supplied privately to the review team, never committed.
- [ ] Fresh-install, upgrade, revoke, offline, reboot, fold/rotate, and uninstall tests pass.
- [ ] Release notes describe new permissions and security-relevant behavior.
- [ ] Publisher has rights to every name, logo, screenshot, font, sound, and image.

## GitHub contribution submissions

Code submissions follow [CONTRIBUTING.md](../CONTRIBUTING.md). In particular,
permission additions, new command endpoints, and public-bind behavior require
tests, threat analysis, privacy documentation, and maintainer review.

[play-accessibility]: https://support.google.com/googleplay/android-developer/answer/16558241?hl=en
[play-sms]: https://support.google.com/googleplay/android-developer/answer/10208820?hl=en
[play-target]: https://developer.android.com/google/play/requirements/target-sdk
[play-disclosure]: https://support.google.com/googleplay/android-developer/answer/11150561?hl=en
[play-data-safety]: https://support.google.com/googleplay/android-developer/answer/10787469?hl=en
[galaxy-policy]: https://developer.samsung.com/galaxy-store/distribution-guide.html?lang=en
[galaxy-check]: https://developer.samsung.com/galaxy-store/self-check-list-galaxy.html?lang=en
[galaxy-submit]: https://developer.samsung.com/galaxy-store/galaxy-store-developer-api/content-publish-api/submit-app.html
