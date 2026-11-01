# VibeTV Home

Apple TV style home screen (launcher) for Android TV. Kotlin + Jetpack Compose.

- Big hero banner for the selected app, background colour follows the app artwork
- App row with zoom + glow on focus, "Recently used" row
- Clock, Settings and Live TV / Inputs shortcuts
- No ads, no sponsored rows

## Build
Push to GitHub. The `Build APK` workflow (Actions tab) produces `app-debug.apk` as an artifact.

## Install
`adb install -r app-debug.apk`
`adb shell cmd package set-home-activity dev.vibetv.launcher/.MainActivity`

## Revert
`adb shell cmd package set-home-activity com.google.android.tvlauncher/com.google.android.tvlauncher.MainActivity`
`adb uninstall dev.vibetv.launcher`

## Customise
Favourite app order: `favourites` list in `AppRepository.kt`. Hidden apps: `hidden` set in the same file.
