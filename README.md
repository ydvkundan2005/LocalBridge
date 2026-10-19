# LocalBridge

**Fast, local-network file and clipboard sharing between Android and macOS.**

LocalBridge is a cross-platform application designed to make transferring files and sharing text between Android devices and Mac computers simple over a local network.

No cloud storage account is required for the intended local-network workflow.

## Features

- **File transfer:** Send files between Android and macOS.
- **Clipboard sharing:** Share text between supported devices.
- **Device discovery:** Discover compatible devices on the local network.
- **Drag and drop:** Use a native macOS file-drop interface.
- **Quick access on Android:** Includes Quick Settings integration, a notification-based sync option, and a home-screen widget.
- **Text sharing:** Supports Android's contextual text-selection and sharing actions.
- **Modern interface:** Minimalist dark UI with transfer status information.
- **Cross-platform architecture:** Android and macOS implementations use a shared transfer protocol.

> **Note:** Clipboard features depend on Android version, system restrictions, permissions, and the selected capture method. Some optional clipboard-monitoring functionality may require additional setup.

## Supported Platforms

| Platform | Requirements |
|---|---|
| Android | Android 10 or later |
| macOS | macOS 13 or later |

LocalBridge's Android application uses the package ID `dev.localbridge.android`.

## Download

Visit the Releases page to find the latest available builds:

**[Download LocalBridge](https://github.com/ydvkundan2005/LocalBridge/releases)**

Available release formats include:

- **Android:** APK package. The currently prepared v6.0 artifact is a debug APK.
- **macOS:** DMG installer image.

Release availability and filenames may change between versions. Review each release's notes before installing.

### Installing on Android

1. Download the APK from the desired GitHub Release.
2. Open the downloaded APK on your Android device.
3. If Android requests permission to install apps from that source, review the request and enable it only if you trust the APK.
4. Complete installation and open LocalBridge.

Android may display warnings for APKs installed outside Google Play. Install only builds you trust.

### Installing on macOS

1. Download the DMG from the desired GitHub Release.
2. Open the downloaded disk image.
3. Follow the installation instructions provided with that release.

The installation experience may vary depending on code signing, notarization, and macOS security settings. Do not bypass security warnings unless you have independently verified the application and trust its source.

## How It Works

LocalBridge uses a local-network communication architecture to connect its Android and macOS applications.

The project includes:

- Android application code written in Kotlin.
- A macOS application implemented in Swift.
- SwiftNIO networking components on macOS.
- WebSocket-based communication.
- Bonjour-based device discovery.
- Shared protocol test fixtures.

For the best results, connect both devices to the same trusted local network and follow the connection instructions included with the installed version.

## Build from Source

### Requirements

**Android**

- Android Studio or a compatible Android development environment.
- JDK 21.
- Android SDK components required by the project.
- Gradle wrapper included in the repository.

The Android app is currently configured with minimum SDK 29, target SDK 36, and compile SDK 37.

Build the debug APK from the project root:

```bash
cd android
./gradlew assembleDebug
```

The generated APK is normally located at:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

**macOS**

- macOS 13 or later.
- Swift toolchain compatible with Swift 6.2.
- Xcode Command Line Tools or an appropriate Swift development environment.

Build the macOS executable:

```bash
cd mac
swift build -c release
```

The resulting executable is normally located at:

```text
mac/.build/release/LocalBridgeMac
```

These commands build the application components; packaging a distributable macOS DMG may require additional packaging steps.

## Repository Structure

```text
LocalBridge/
├── android/                 # Android application and Gradle configuration
├── mac/                     # macOS application and Swift package
├── shared/                  # Shared protocol test fixtures
├── docs/                    # Architecture decision records
├── test-evidence/           # Development test evidence
├── STATUS.md                # Development status and release notes
├── .gitignore
└── README.md
```

## Network and Security

LocalBridge is intended for local-network communication.

**Important security considerations:**

- Use trusted networks, particularly when transferring sensitive files.
- The current Android network security configuration permits cleartext traffic.
- The configuration also trusts system and user-installed certificates.
- Do not assume all connections are encrypted or protected against interception.
- Review the network security configuration and connection implementation before using LocalBridge with confidential information.
- Only install application builds from sources you trust.
- Review Android permissions and any additional setup required for optional clipboard-monitoring functionality.

Local-network operation does not automatically guarantee confidentiality, authentication, or protection from other devices on the network.

## Current Version

**Version:** 6.0  
**Android application ID:** `dev.localbridge.android`

Consult the GitHub Releases page and the project's `STATUS.md` file for release-specific information. Features described in development notes may depend on the exact build being used.

## Contributing

Contributions, bug reports, documentation improvements, and suggestions are welcome.

Before submitting a contribution:

1. Check existing issues for related reports.
2. Describe the problem or proposed improvement clearly.
3. Include reproduction steps for bug reports.
4. Avoid committing credentials, signing keys, local SDK paths, crash dumps, or generated build artifacts.

Please open an issue or pull request in the repository once the project is published.

## License

A license has not yet been selected for this repository.

Until a license is added, do not assume that the source code may be copied, modified, or redistributed. A license should be chosen by the project owner before inviting external contributions.

---

**LocalBridge — Bridge your Android and Mac over your local network.**