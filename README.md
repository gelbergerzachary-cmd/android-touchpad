# Touchpad — use a Fire tablet as a Mac trackpad

Two parts:
- `android/` — the tablet app (Java, no dependencies). APK: `android/app/build/outputs/apk/debug/app-debug.apk`
- `mac-server/` — a small Swift program that receives gestures over Wi‑Fi (UDP 50505) and turns them into real macOS input.

## Use it
1. On the Mac: `./start-server.sh` (keep the window open). It prints a 4-digit PIN.
   The first time, macOS asks for Accessibility permission for your terminal app — allow it, then restart the server.
2. On the tablet: open **Touchpad**, tap the status pill at the top → **Find Mac** → enter the PIN → Save.

## Gestures
| Gesture | Does |
|---|---|
| 1 finger | move pointer (with acceleration) |
| tap / double-tap | click / double-click |
| double-tap and hold, then move | drag (select text, move windows) |
| 2 fingers move | scroll, with momentum and natural scrolling |
| 2-finger pinch / twist | zoom / rotate |
| 2-finger tap | right-click |
| 3-finger tap | Look Up |
| 3 or 4-finger swipe left/right | switch Spaces / full-screen apps |
| 3 or 4-finger swipe up / down | Mission Control / App Exposé |
| 4-finger pinch in / spread | Apps / Show Desktop |

Speed, scroll direction and tap-to-click are in the settings sheet.

## Rebuild the app
```
cd android
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```
