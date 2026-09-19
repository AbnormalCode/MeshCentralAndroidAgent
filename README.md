# MeshCentral Android Agent

MeshCentral Android Agent connects an Android device to a
[MeshCentral](https://www.meshcentral.com) server for remote monitoring and
support. It is a native Kotlin application and is separate from the MeshCentral
agents used on Windows, Linux, macOS, and FreeBSD.

Pair a device by scanning a MeshCentral QR code, opening an `mc://` pairing
link, or entering the link manually. After enrollment, the app maintains an
authenticated connection to the server and can:

- Report device, network, storage, battery, location, and uptime information.
- Share the device screen after Android MediaProjection consent.
- Control the device with taps, swipes and typing, once the device user enables
  remote input.
- Browse and transfer media, and any folder the device user shares with the
  agent, including folder creation and renaming.
- List and launch installed applications, read and set the clipboard, adjust the
  volume, and ring the device to find it.
- Receive server notifications and console commands.
- Approve or reject MeshCentral push-based two-factor authentication requests.

Remote desktop is **view only by default**. Control needs two separate opt-ins
from the device user: the Remote Input setting in the app, and the MeshCentral
accessibility service in Android's settings. Turning either one off ends remote
control immediately. Android displays a foreground notification while screen
sharing is active, and the user can deny or stop capture at any time.

## Install

Install MeshAgent for Android from [Google Play](https://play.google.com/store/apps/details?id=com.meshcentral.agent2).

Or download the APK or AAB from the
[latest release](https://github.com/Ylianst/MeshCentralAndroidAgent/releases/latest).

## Requirements

- Android 6.0 (API 23) or later.
- A MeshCentral server configured to enroll Android agents.
- Android Studio or JDK 17 and an Android SDK for local development.

## Build

Open the repository in Android Studio, or build and test it from the repository
root:

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

The debug APK is generated under `app/build/outputs/apk/debug/`. Production
releases require a dedicated signing keystore; see the release guide below.

## Documentation

- [Documentation site](https://ylianst.github.io/MeshCentralAndroidAgent/) -
	published project documentation.
- [Documentation home](docs/index.md) - introduction, installation, key
	capabilities, and links to all project resources.
- [Repository overview](docs/overview.md) - architecture, components, project
	configuration, and development notes.
- [Remote desktop](docs/remote-desktop.md) - screen-capture flow, consent,
	encoding, and Android platform limitations.
- [Remote input](docs/remote-input.md) - consent, how mouse and keyboard map to
	Android gestures, and console commands.
- [File access](docs/file-access.md) - storage roots, granting a folder, and
	what each root supports.
- [Tunnel authentication](docs/tunnel-authentication.md) - control-channel
	authentication, certificate pinning, and relay tunnel trust.
- [Push two-factor authentication](docs/two-factor-authentication.md) - FCM
	request validation, approval flow, and lifecycle behavior.
- [Creating a release](docs/releasing.md) - versioning, signing, GitHub Actions,
	and publishing APK and AAB artifacts.

## Community

- [MeshCentral website](https://www.meshcentral.com)
- [MeshCentral subreddit](https://www.reddit.com/r/MeshCentral/)