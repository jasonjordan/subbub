# subbub

Real-time English subtitles for Android TV and Google TV.
Created by Jason Jordan.

## Supported devices

Android TV / Google TV 13, 14, or 15 only.

## Building

No Android Studio required. This project builds with Gradle from the command line or via GitHub Actions.

### Prerequisites for local builds

- **Java JDK 17 or 21** (Java 25 is not supported by the Android Gradle Plugin)
- Android SDK command-line tools (or Android Studio if you prefer)

### Local build

```bash
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

### GitHub Actions

This repository includes a workflow at `.github/workflows/android.yml` that automatically builds the debug APK on every push to `main` or `master`.

1. Push this repository to GitHub.
2. Go to **Actions** → **Build subbub APK**.
3. Wait for the workflow to finish.
4. Download the APK artifact from the run summary.

## Usage

1. Install the APK on your Android TV or Google TV device.
2. Open **subbub**.
3. Grant the requested permissions (overlay, microphone, screen capture).
4. Select your audio source and language, then tap **Start Subtitles**.
5. Switch to YouTube or live TV and watch with real-time English subtitles.
6. Pull down the notification shade and tap **Stop** to end subtitles.

For detailed help, tap the **Help** button inside the app.
