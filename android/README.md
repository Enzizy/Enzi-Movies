# Enzi Movies for Android

Enzi Movies is the Android port of Streambert. It uses the upstream React catalog and library in Android WebView. A Rust JNI library handles TMDB requests and validates media links. The Android app uses Media3 for direct MP4 and HLS playback, Android's file picker for local videos, and DownloadManager for direct-file downloads. Compatible providers supply embedded video and controls inside the fullscreen Enzi Movies player. The embedded player runs without access to the app bridge.

Enter a TMDB Read Access Token to browse the catalog. You can also choose **Use media links or local videos** during setup and open a direct HTTPS link or a video on your device. The Android build supports Android 7.0 and newer on arm64-v8a, armeabi-v7a, and x86_64.

Tap **Play** on a movie or select a series episode to start fullscreen playback in Enzi Movies. Use **Servers** in the top-right corner to switch catalog sources while the player is open. Switching can restart the title, depending on the provider. Use **Close player** or Android Back to return to the title.

Android provider availability depends on each third-party site. This port omits HiAnime scraping, providers that rely on Electron request rewriting, HLS downloads, and desktop utilities such as Discord presence and the updater.

## Build

Install JDK 17, Node.js 22.12 or newer, Rust, Android SDK Platform 36, and side-by-side NDK 28.2.13676358. Set `JAVA_HOME` and `ANDROID_HOME` to those installations.

From the repository root on Windows, run:

```powershell
npm.cmd ci --ignore-scripts
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
.\android\gradlew.bat -p android assembleDebug
```

On macOS or Linux, use `npm ci --ignore-scripts` and `sh ./android/gradlew -p android assembleDebug`. Gradle builds and packages both the web assets and the Rust library. The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.

To build only for an emulator, pass `-PstreambertAbis=x86_64`.
