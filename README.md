# Nuclear Wars: Android APK project

Offline Capacitor app. Everything (game, 3D engine, fonts) is bundled in `www/`; the game works with no internet.
The app is locked to landscape and runs full screen.

## Easiest: build online with GitHub (no installs)
1. Make a free GitHub account, create a new empty repository, and upload every file in this folder (drag and drop works).
2. Open the repository's Actions tab, choose "Build Nuclear Wars APK", press Run workflow.
3. When it turns green (about 5 minutes), open the run and download `NuclearWars-debug-apk`. Unzip it to get `app-debug.apk`.
4. Send the APK to a phone, tap it, allow "install unknown apps" once, and install.

## Or build on a computer
Needs Node 20+, JDK 17 and Android Studio (or the Android SDK).
```
npm install
npx cap sync android
cd android && ./gradlew assembleDebug      # Windows: gradlew.bat assembleDebug
```
The APK is at `android/app/build/outputs/apk/debug/app-debug.apk`.

## Updating the game
Replace `www/index.html` with the new game file (keep the name), run `npx cap sync android`, rebuild.

## Notes
- Debug-signed APKs install fine for classroom sideloading. A Play Store release needs your own signing key.
- Camera permission is only used to scan the Versus invite QR code.
