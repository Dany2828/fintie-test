# Fintie Scroll Test v4

Diagnostic APK for the Fintie EB092 on Android 14+.

## v4 adds
- Immediate-then-decelerating vertical scrolling (same feel as v2)
- Horizontal scrolling from `AXIS_HSCROLL`
- Pinch zoom from `AXIS_GESTURE_PINCH_SCALE_FACTOR` or Ctrl+wheel-style firmware
- Left click forwarding as a touchscreen tap
- Secondary/right click forwarding; a Fintie two-finger tap should work when the firmware reports it as `BUTTON_SECONDARY`
- Context-click accessibility action with long-press fallback

## Build
Push to GitHub and use **Actions -> Build test APK**, then download the `FintieScrollTest-debug` artifact.

## Safety
The accessibility service begins with mouse capture OFF. Use the touchscreen to press **STOP MOUSE CAPTURE** if any input behavior is undesirable.
