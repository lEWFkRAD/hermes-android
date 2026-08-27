# Hermes Bridge privacy notice

Last updated: 2026-08-26

Hermes Bridge is a self-hosted Android companion that sends commands and
device-derived data between a device and a server selected by the device owner.
The project does not operate a hosted relay, advertising network, or analytics
service. The operator of the configured relay is responsible for that server's
security, retention, access control, and legal notices.

## Data the app can access

Only after the device owner grants the corresponding Android capability, the
app can process:

- on-screen text, UI structure, screenshots, and short screen recordings;
- notifications, clipboard content, installed-app metadata, and accessibility events;
- microphone recordings, location, contacts, SMS recipients/content, and call targets;
- metadata and bounded transfers for user-authorized USB peripherals; and
- connection metadata needed to authenticate and route bridge commands.

The exact access depends on the features enabled by the device owner. Android
permissions, Accessibility, notification access, screen-capture approval, and
per-device USB approval can be denied or revoked independently.

## How data is used and shared

The Android app uses data only to execute authenticated commands and return
results to the device owner's configured Hermes relay or direct client. It does
not sell data or use it for advertising or cross-service profiling. Data may be
visible to the relay operator, the AI/model services that operator configures,
and anyone the operator authorizes to use that relay.

The current prototype supports cleartext local HTTP/WebSocket connections.
For traffic outside a trusted private network, operators must use a private
overlay network or a TLS-terminating reverse proxy. See [SECURITY.md](SECURITY.md).

## Storage and retention

- Pairing and server configuration is stored in app-private preferences.
- Completed microphone recordings remain in app-private storage. The app keeps
  the 10 newest completed WAV files and removes older completed files.
- USB sessions are process-local and close on detach or disconnect.
- The app has no project-operated cloud database. Relay logs, model history,
  backups, and media copied from the device follow the operator's own policy.
- Uninstalling the app removes its app-private data under normal Android behavior.

## Control and deletion

Device owners can disconnect the bridge, revoke individual Android permissions,
disable Accessibility/notification access, clear app storage, remove retained
recordings, or uninstall the app. Data already transmitted to a configured
relay must be deleted by that relay's operator.

## Children

Hermes Bridge is a technical remote-control tool and is not directed to children.

## Contact and policy changes

Use this repository's issue tracker for non-sensitive questions and GitHub's
private vulnerability-reporting channel for security or privacy reports. A
store submission must replace this paragraph with the publisher's legal name,
support address, and jurisdiction-appropriate contact details, and must expose
the published policy URL inside the app.
