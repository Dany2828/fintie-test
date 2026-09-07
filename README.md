# Fintie Scroll Test

A deliberately tiny diagnostic Android app for the Fintie EB092 on Android 14+.

## What it tests

1. Whether an AccessibilityService receives the EB092 mouse wheel as `MotionEvent.ACTION_SCROLL` / `AXIS_VSCROLL`.
2. Whether `dispatchGesture()` produces the smooth finger-like swipe desired.
3. Whether Samsung continues normal cursor/click routing while the service requests `SOURCE_MOUSE` motion events.

The third point is the key unknown. Android's public documentation says requested motion-event sources are not sent to the rest of the system, so the likely result is that cursor/clicks stop while capture is enabled. This test is intentionally designed so capture starts OFF and can be stopped by touching the screen.

## Build in Android Studio

- Open this folder as a project.
- Let Gradle sync.
- Build > Build APK(s), or Run directly on the tablet.
- Minimum Android version: Android 14 (API 34).

## Test procedure

1. Connect the Fintie EB092.
2. Open **Fintie Scroll Test**.
3. Tap **OPEN ACCESSIBILITY SETTINGS** and enable the service.
4. Return to the app.
5. Tap **TEST SMOOTH SWIPE**. The screen should perform a short smooth touchscreen-like swipe.
6. Tap **START MOUSE CAPTURE**.
7. Test:
   - cursor movement
   - left click
   - two-finger scrolling
8. Watch the status text for `ACTION_SCROLL` and `VSCROLL` values.
9. If the cursor stops, use your finger to tap **STOP MOUSE CAPTURE**.

## Safety

This test does not request Internet, Bluetooth, storage, contacts, location, or account permissions. The only special capability is an Accessibility Service with gesture injection enabled. It does not auto-start and does not persist any background behavior beyond the enabled service.
