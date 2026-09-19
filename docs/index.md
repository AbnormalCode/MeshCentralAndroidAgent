# MeshAgent for Android

MeshAgent for Android connects an Android phone, tablet, or other compatible
device to a [MeshCentral](https://www.meshcentral.com) server. It gives
administrators and support teams a secure way to monitor device information,
view a shared screen with the device user's consent, exchange files, deliver
notifications, and handle MeshCentral push-based two-factor authentication
requests.

The Android agent is a native Kotlin application and is separate from the
MeshCentral agents for Windows, Linux, macOS, and FreeBSD. Remote desktop is
view only until the device user turns on remote input and enables the
MeshCentral accessibility service, after which an operator can also tap, swipe
and type on the device.

## Get MeshAgent

- [Install MeshAgent for Android from Google Play](https://play.google.com/store/apps/details?id=com.meshcentral.agent2)
- [Download the APK or AAB from the latest release](https://github.com/Ylianst/MeshCentralAndroidAgent/releases/latest)
- [View the source code on GitHub](https://github.com/Ylianst/MeshCentralAndroidAgent)

Android 6.0 (API 23) or later and a MeshCentral server configured to enroll
Android agents are required. Pair a device by scanning a MeshCentral QR code,
opening an `mc://` pairing link, or entering the pairing link manually.

## Capabilities

- Report device, network, storage, battery, location, and uptime information.
- Share the device screen after Android MediaProjection consent.
- Control the device with taps, swipes and typing once the device user enables
  remote input.
- Browse and transfer media, and any folder the device user shares with the
  agent, including folder creation and renaming.
- List and launch installed applications, read and set the clipboard, adjust
  the volume, and ring the device to find it.
- Receive server notifications and supported console commands.
- Approve or reject MeshCentral push-based two-factor authentication requests.

Android shows a foreground notification while screen sharing is active. The
device user controls screen-capture consent and can deny or stop sharing at any
time.

## Documentation

- [Repository overview](overview.md) - architecture, components, configuration,
  data flows, and development notes.
- [Remote desktop](remote-desktop.md) - screen capture, consent, encoding, and
  Android platform limitations.
- [Remote input](remote-input.md) - consent, input mapping, console commands,
  and accessibility limits.
- [File access](file-access.md) - storage roots, granting a folder, and what
  each root supports.
- [Tunnel authentication](tunnel-authentication.md) - control-channel
  authentication, certificate pinning, and relay tunnel trust.
- [Push two-factor authentication](two-factor-authentication.md) - request
  validation, approval flow, and lifecycle behavior.
- [Creating a release](releasing.md) - versioning, signing, GitHub Actions, and
  publishing APK and AAB artifacts.
- [Main project README](../README.md) - requirements and local build commands.

## Project Links

- [MeshCentral Android Agent repository](https://github.com/Ylianst/MeshCentralAndroidAgent)
- [MeshCentral website](https://www.meshcentral.com)
- [MeshCentral repository](https://github.com/Ylianst/MeshCentral)
- [MeshCentral subreddit](https://www.reddit.com/r/MeshCentral/)