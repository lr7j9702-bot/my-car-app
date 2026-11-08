# Car Launcher

A home-screen (launcher) app for Android car screens / head units.

- **Map** (OpenStreetMap, no API key) that follows your position, with a night mode
- **Live speed from GPS**, in km/h (tap the speed card to switch to mph)
- **Bottom bar** with your pinned apps: open "All apps", long-press an app to pin or unpin it
- **Split screen**: press the split button, pick two apps (the first can be the Map), and they open side by side
- English and Arabic interface

Requires Android 5.0+ (split screen needs 7.0+).

## Get the APK

### Option A: GitHub (no PC tools needed)
1. Create a new GitHub repository and upload everything in this folder, including the hidden `.github` folder.
2. Open the repository's **Actions** tab, choose **Build APK**, press **Run workflow**.
3. When it finishes, open the run and download **CarLauncher-debug-apk**. Unzip it to get `app-debug.apk`.

### Option B: Android Studio
Open this folder in Android Studio, wait for the Gradle sync, then **Build > Build APK(s)**.
The file is `app/build/outputs/apk/debug/app-debug.apk`.

### Option C: command line
With JDK 17 and the Android SDK installed: `./gradlew assembleDebug`

## Install on the car screen
1. Copy `app-debug.apk` to a USB stick and plug it into the head unit (or use `adb install app-debug.apk`).
2. Allow installs from unknown sources when asked, then open the file from the head unit's file manager.
3. Press the Home button and choose **Car Launcher** > **Always**. If the head unit has no such prompt, look for a default Home app setting in its Settings.
4. Allow the location permission when asked.

## Turn on split screen (one time)
Android only lets an app start split screen through an accessibility service.
1. Tap the split button once; the app offers to open Accessibility settings.
2. Turn on **Car Launcher split screen**. It only performs the split action and does not read the screen.
3. Android 13 and newer: if the switch is greyed out, open **Settings > Apps > Car Launcher**, tap the three-dot menu, choose **Allow restricted settings**, then try again.

If you are already in split screen, exit it first; the helper toggles split mode.

## Notes
- The map needs internet (for example a phone hotspot). Tiles are cached, so places you have viewed load faster next time.
- Speed comes from the head unit's GPS receiver. If it shows "GPS is off" or never gets a fix, check the unit's location settings.
- Some head-unit firmwares disable multi-window. If split screen does nothing, that firmware is the cause.
- If the second app opens too early or too late, adjust `TOGGLE_DELAY_MS` and `SECOND_DELAY_MS` in `SplitScreenService.kt`.
- The map uses the public OpenStreetMap tile server, which is fine for personal use.
