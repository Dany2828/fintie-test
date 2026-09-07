package com.fintie.scrolltest;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * Diagnostic accessibility service for the Fintie EB092.
 *
 * v2 uses a velocity/impulse model rather than one fixed swipe per wheel notch.
 * Each Fintie wheel event adds velocity immediately; the synthetic finger then
 * decelerates over a chain of continued accessibility gestures.
 */
public class FintieAccessibilityService extends AccessibilityService {
    public static final String ACTION_COMMAND = "com.fintie.scrolltest.COMMAND";
    public static final String ACTION_STATUS = "com.fintie.scrolltest.STATUS";
    public static final String CMD_START = "start";
    public static final String CMD_STOP = "stop";
    public static final String CMD_TEST_SWIPE = "test_swipe";

    // Animation tuning. One isolated wheel notch travels about 10% of the display,
    // matching v1, but now it starts immediately and eases out.
    private static final long SEGMENT_MS = 16L;
    private static final long LIFT_MS = 10L;
    private static final float FRICTION_PER_SEGMENT = 0.84f;
    private static final float STOP_VELOCITY_PX_PER_MS = 0.045f;
    private static final float MAX_VELOCITY_PX_PER_MS = 9.0f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean capturing = false;

    // Synthetic-finger state.
    private boolean gestureRunning = false;
    private boolean lifting = false;
    private GestureDescription.StrokeDescription currentStroke;
    private float fingerX;
    private float fingerY;
    private float velocityPxPerMs = 0f;
    private float impulsePxPerMs = 2.30f; // recalculated from display size
    private int wheelEvents = 0;
    private boolean testGestureActive = false;

    private final BroadcastReceiver commandReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String cmd = intent.getStringExtra("command");
            if (CMD_START.equals(cmd)) setCapture(true);
            else if (CMD_STOP.equals(cmd)) setCapture(false);
            else if (CMD_TEST_SWIPE.equals(cmd)) {
                // Use the exact same velocity engine as real wheel input.
                testGestureActive = true;
                addWheelImpulse(+1f, true);
                sendStatus("Test impulse added.\nIf this page is scrollable, it should move immediately then decelerate.");
            }
        }
    };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        registerReceiver(commandReceiver, new IntentFilter(ACTION_COMMAND), Context.RECEIVER_NOT_EXPORTED);
        recalculateImpulse();
        setCapture(false);
        sendStatus("Accessibility service connected.\nMouse capture: OFF");
    }

    private void recalculateImpulse() {
        WindowManager wm = getSystemService(WindowManager.class);
        Rect bounds = wm.getMaximumWindowMetrics().getBounds();
        // Desired total finger travel for one isolated notch ~= 10% screen height.
        float desiredDistance = bounds.height() * 0.10f;
        // Geometric sum: distance ~= v0 * dt / (1-friction).
        impulsePxPerMs = desiredDistance * (1f - FRICTION_PER_SEGMENT) / SEGMENT_MS;
    }

    private void setCapture(boolean enabled) {
        AccessibilityServiceInfo info = getServiceInfo();
        if (Build.VERSION.SDK_INT >= 34) {
            info.setMotionEventSources(enabled ? InputDevice.SOURCE_MOUSE : 0);
            setServiceInfo(info);
            capturing = enabled;
            if (!enabled) {
                velocityPxPerMs = 0f;
                requestLift();
            }
            sendStatus("Mouse capture: " + (enabled ? "ON" : "OFF") +
                    (enabled
                            ? "\nVelocity scrolling v2 active. Scroll slowly, then quickly, and compare distance."
                            : "\nNormal mouse routing restored."));
        } else {
            sendStatus("Requires Android API 34+ for mouse MotionEvent capture.");
        }
    }

    @Override public void onMotionEvent(MotionEvent event) {
        InputDevice d = event.getDevice();
        String name = d == null ? "unknown" : d.getName();
        float v = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
        float h = event.getAxisValue(MotionEvent.AXIS_HSCROLL);
        String action = MotionEvent.actionToString(event.getActionMasked());

        if (event.getActionMasked() == MotionEvent.ACTION_SCROLL &&
                name.toLowerCase().contains("fintie") && Math.abs(v) > 0.001f) {
            wheelEvents++;
            addWheelImpulse(v, false);
        }

        // Avoid broadcasting UI updates for every cursor-motion event; that created
        // needless main-thread work in v1. Only report actual wheel events.
        if (event.getActionMasked() == MotionEvent.ACTION_SCROLL && Math.abs(v) > 0.001f) {
            int reverse = getMouseReverseSetting();
            sendStatus("Mouse capture: ON\nDevice: " + name +
                    "\nAction: " + action +
                    "\nVSCROLL: " + v + "  HSCROLL: " + h +
                    "\nWheel events: " + wheelEvents +
                    "\nSystem reverse setting: " + reverse +
                    "\nSynthetic velocity: " + String.format("%.2f", velocityPxPerMs) + " px/ms");
        }
    }

    /**
     * Adds an immediate velocity impulse. More/faster wheel events accumulate, so a
     * longer physical scroll travels farther and reaches a higher initial velocity.
     */
    private void addWheelImpulse(float rawVScroll, boolean testButton) {
        recalculateImpulse();

        float v = rawVScroll;
        // Samsung's hidden mouse_reverse_vertical_scrolling setting has already flipped
        // the wheel direction before this callback on the user's tablet. Undo that here
        // so our synthetic finger preserves the physical/natural direction.
        if (!testButton && getMouseReverseSetting() == 1) {
            v = -v;
        }

        // Preserve v1's basic mapping: positive wheel => finger moves upward.
        float impulse = -Math.signum(v) * impulsePxPerMs * Math.max(1f, Math.abs(v));

        // If the user reverses direction strongly, respond immediately instead of making
        // them wait for the previous tail to decay through zero.
        if (velocityPxPerMs != 0f && Math.signum(velocityPxPerMs) != Math.signum(impulse)) {
            velocityPxPerMs *= 0.30f;
        }

        velocityPxPerMs += impulse;
        velocityPxPerMs = clamp(velocityPxPerMs, -MAX_VELOCITY_PX_PER_MS, MAX_VELOCITY_PX_PER_MS);

        if (!gestureRunning && !lifting) {
            startGesture();
        }
    }

    private void startGesture() {
        if (Math.abs(velocityPxPerMs) < STOP_VELOCITY_PX_PER_MS) return;

        WindowManager wm = getSystemService(WindowManager.class);
        Rect bounds = wm.getMaximumWindowMetrics().getBounds();
        fingerX = bounds.width() * 0.50f;

        // Start toward the opposite side of the display so a burst has lots of travel room.
        fingerY = velocityPxPerMs < 0f ? bounds.height() * 0.76f : bounds.height() * 0.24f;

        float nextY = nextSafeY(bounds, fingerY + velocityPxPerMs * SEGMENT_MS);
        Path path = linePath(fingerX, fingerY, fingerX, nextY);
        currentStroke = new GestureDescription.StrokeDescription(path, 0, SEGMENT_MS, true);
        fingerY = nextY;
        gestureRunning = true;
        dispatchCurrentSegment(currentStroke);
    }

    private void dispatchNextSegment() {
        if (!capturing && !testGestureActive) {
            // Test-button gestures are allowed with capture off; real capture being turned
            // off should terminate the current synthetic pointer.
            requestLift();
            return;
        }

        // Friction applies after every frame: fast immediately, then progressively slower.
        velocityPxPerMs *= FRICTION_PER_SEGMENT;

        if (Math.abs(velocityPxPerMs) < STOP_VELOCITY_PX_PER_MS) {
            velocityPxPerMs = 0f;
            requestLift();
            return;
        }

        WindowManager wm = getSystemService(WindowManager.class);
        Rect bounds = wm.getMaximumWindowMetrics().getBounds();
        float proposedY = fingerY + velocityPxPerMs * SEGMENT_MS;
        float minY = bounds.height() * 0.08f;
        float maxY = bounds.height() * 0.92f;

        // If the synthetic finger is running out of screen, end it at near-zero movement,
        // then restart from the other side while retaining velocity. This avoids edge stalls.
        if (proposedY < minY || proposedY > maxY) {
            requestLiftAndRestart();
            return;
        }

        Path path = linePath(fingerX, fingerY, fingerX, proposedY);
        GestureDescription.StrokeDescription next =
                currentStroke.continueStroke(path, 0, SEGMENT_MS, true);
        currentStroke = next;
        fingerY = proposedY;
        dispatchCurrentSegment(next);
    }

    private void dispatchCurrentSegment(GestureDescription.StrokeDescription stroke) {
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gestureDescription) {
                handler.post(FintieAccessibilityService.this::dispatchNextSegment);
            }

            @Override public void onCancelled(GestureDescription gestureDescription) {
                gestureRunning = false;
                lifting = false;
                currentStroke = null;
                // Keep accumulated velocity. A later wheel event can start a fresh gesture.
            }
        }, handler);

        if (!accepted) {
            gestureRunning = false;
            lifting = false;
            currentStroke = null;
        }
    }

    private void requestLift() {
        if (!gestureRunning || lifting || currentStroke == null) {
            gestureRunning = false;
            lifting = false;
            currentStroke = null;
            return;
        }
        lifting = true;
        Path stay = new Path();
        stay.moveTo(fingerX, fingerY); // zero-length continuation; pointer remains still, then lifts
        GestureDescription.StrokeDescription finalStroke =
                currentStroke.continueStroke(stay, 0, LIFT_MS, false);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(finalStroke).build();
        boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gestureDescription) {
                gestureRunning = false;
                lifting = false;
                currentStroke = null;
                if (Math.abs(velocityPxPerMs) >= STOP_VELOCITY_PX_PER_MS && (capturing || testGestureActive)) {
                    handler.post(FintieAccessibilityService.this::startGesture);
                } else {
                    velocityPxPerMs = 0f;
                    testGestureActive = false;
                }
            }

            @Override public void onCancelled(GestureDescription gestureDescription) {
                gestureRunning = false;
                lifting = false;
                currentStroke = null;
                testGestureActive = false;
            }
        }, handler);
        if (!accepted) {
            gestureRunning = false;
            lifting = false;
            currentStroke = null;
        }
    }

    private void requestLiftAndRestart() {
        if (!gestureRunning || lifting || currentStroke == null) return;
        lifting = true;
        Path stay = new Path();
        stay.moveTo(fingerX, fingerY);
        GestureDescription.StrokeDescription finalStroke =
                currentStroke.continueStroke(stay, 0, LIFT_MS, false);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(finalStroke).build();
        boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gestureDescription) {
                gestureRunning = false;
                lifting = false;
                currentStroke = null;
                handler.post(FintieAccessibilityService.this::startGesture);
            }

            @Override public void onCancelled(GestureDescription gestureDescription) {
                gestureRunning = false;
                lifting = false;
                currentStroke = null;
                testGestureActive = false;
            }
        }, handler);
        if (!accepted) {
            gestureRunning = false;
            lifting = false;
            currentStroke = null;
        }
    }

    private int getMouseReverseSetting() {
        try {
            return Settings.System.getInt(getContentResolver(), "mouse_reverse_vertical_scrolling", 0);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static Path linePath(float x1, float y1, float x2, float y2) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        return p;
    }

    private static float nextSafeY(Rect bounds, float y) {
        return clamp(y, bounds.height() * 0.08f, bounds.height() * 0.92f);
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private void sendStatus(String message) {
        Intent i = new Intent(ACTION_STATUS);
        i.setPackage(getPackageName());
        i.putExtra("status", message);
        sendBroadcast(i);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }

    @Override public void onDestroy() {
        try { unregisterReceiver(commandReceiver); } catch (Exception ignored) { }
        super.onDestroy();
    }
}
