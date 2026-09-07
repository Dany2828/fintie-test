# Fintie Scroll Test v2

Diagnostic Android project for the Fintie EB092 on Android 14+ / One UI.

v2 changes the synthetic scroll model from a fixed 170 ms swipe per wheel event to a velocity-driven continued touch gesture:

- immediate fast response followed by deceleration
- repeated wheel events accumulate velocity and distance
- compensates for Samsung's `mouse_reverse_vertical_scrolling=1` setting
- avoids status/UI broadcasts on ordinary cursor motion
- includes a deliberately long scrollable test page

Build with the included GitHub Actions workflow, install the debug APK, enable the Accessibility Service, then use **TEST SMOOTH SWIPE** and **START MOUSE CAPTURE**.
