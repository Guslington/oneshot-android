# Oshi

Oshi is a native Android client (Kotlin + Jetpack Compose) for the
[OneShotChat](https://api.oneshotchat.com/llms.txt) protocol. It passes the `#conformance` bot.

Features: Ed25519 identity create/import/export, room browser, join/create rooms, send, edit,
react, optional message signing with verification, mention autocomplete, tripcodes, WebSocket
with polling catch-up, and presence heartbeat.

## Requirements

- macOS with [Homebrew](https://brew.sh)
- **JDK 21** (AGP 8.7 needs JDK 17–21; newer JDKs such as 27 break the Android `jlink` step)
- Android SDK: platform 35, build-tools 35.0.0, platform-tools
- Gradle (only to generate the wrapper jar if it's missing)

## Set up the build environment

```bash
brew install --cask temurin@21
brew install --cask android-commandlinetools
brew install --cask android-platform-tools   # adb
brew install gradle
```

Add to `~/.zshrc`, then `source ~/.zshrc`:

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export ANDROID_HOME="/opt/homebrew/share/android-commandlinetools"   # Intel Macs: /usr/local/share/...
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
```

Install the SDK packages and accept licenses:

```bash
sdkmanager --licenses
sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
```

`sdkmanager` prints a deprecation warning; it still works.

If you don't want to set `ANDROID_HOME`, create `local.properties` in the project root
(it is git-ignored):

```properties
sdk.dir=/opt/homebrew/share/android-commandlinetools
```

### Gradle wrapper

If `gradle/wrapper/gradle-wrapper.jar` or `./gradlew` is missing:

```bash
gradle wrapper --gradle-version 8.10.2
```

Make sure Gradle runs on JDK 21 (`echo $JAVA_HOME`). Homebrew's `gradle` formula pulls in
its own newest `openjdk`, which will be used if `JAVA_HOME` isn't set.

## Test

```bash
./gradlew :app:testDebugUnitTest
```

These JVM tests need no device. They check the protocol's Appendix A test vectors (base32,
login/message/edit/retire signatures), identity file round-trips, tripcodes, and the signing
origin.

## Build

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Install and run

On a device with USB debugging enabled:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or run `./gradlew installDebug`. You can also copy the APK to the phone and open it,
allowing installs from unknown sources.

To check protocol conformance, join `#conformance` in the app and send `!test`. Passing
clients are listed at `GET https://api.oneshotchat.com/v1/clients`.

## Troubleshooting

| Problem | Fix |
|---|---|
| `SDK location not found` | Set `ANDROID_HOME` or create `local.properties` with `sdk.dir`. |
| `jlink` / `JdkImageTransform` failure | Gradle is running on a JDK newer than 21. Set `JAVA_HOME` to JDK 21. |
| `SDK XML version 4` warning | Harmless: the command-line tools are newer than the SDK parser. |
| `sdkmanager` deprecation warning | Harmless; it still installs packages. |

## Project layout

```
app/src/main/java/com/oneshotchat/android/
  crypto/   Base32, Ed25519, identity file, signed strings, tripcodes, mentions
  net/      HTTP API client, WebSocket, wire models
  data/     Storage (identity, settings, contacts) and ChatRepository (session, signing)
  ui/       ChatViewModel, MainActivity, theme, and screens
app/src/test/  Protocol test vectors
```

## Security notes

- The `.identity` file **is** your identity. Anyone holding it is you. Never commit or share it.
  `.gitignore` excludes `*.identity`.
- The server operator can read every room, and presence in listed rooms is public.
