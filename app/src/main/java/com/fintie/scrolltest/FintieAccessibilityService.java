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
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Fintie EB092 diagnostic accessibility service.
 *
 * v4 keeps v2's immediate-then-decelerating scroll feel and adds:
 * - corrected vertical direction (same mapping introduced in v3)
 * - horizontal scrolling using AXIS_HSCROLL
 * - pinch zoom from either Android's pinch-scale axis or Ctrl+wheel style firmware
 * - primary click forwarding as a touchscreen tap
 * - secondary/right-click forwarding, including two-finger tap when the EB092 reports
 *   that gesture as BUTTON_SECONDARY
 */
public class FintieAccessibilityService extends AccessibilityService {
    public static final String ACTION_COMMAND = "com.fintie.scrolltest.COMMAND";
    public static final String ACTION_STATUS = "com.fintie.scrolltest.STATUS";
    public static final String CMD_START = "start";
    public static final String CMD_STOP = "stop";
    public static final String CMD_TEST_SWIPE = "test_swipe";

    private static final long SEGMENT_MS = 16L;
    private static final long LIFT_MS = 10L;
    private static final float FRICTION_PER_SEGMENT = 0.84f;
    private static final float STOP_VELOCITY_PX_PER_MS = 0.045f;
    private static final float MAX_VELOCITY_PX_PER_MS = 9.0f;

    private static final long TAP_MS = 32L;
    private static final long LONG_PRESS_MS = 560L;
    private static final long PINCH_MS = 72L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean capturing = false;

    // Synthetic one-finger scroll state. X and Y share one continued stroke so diagonal
    // scroll gestures also remain coherent.
    private boolean gestureRunning = false;
    private boolean lifting = false;
    private GestureDescription.StrokeDescription currentStroke;
    private float fingerX;
    private float fingerY;
    private float velocityXPxPerMs = 0f;
    private float velocityYPxPerMs = 0f;
    private float impulseXPxPerMs = 2.3f;
    private float impulseYPxPerMs = 2.3f;
    private boolean testGestureActive = false;

    // Button forwarding state.
    private boolean primaryButtonDown = false;
    private boolean secondaryButtonDown = false;
    private int forwardedClicks = 0;
    private int forwardedRightClicks = 0;

    // Pinch queue. Wheel events from the EB092 arrive as discrete steps, so accumulating
    // pending steps makes a longer physical pinch produce proportionally more zoom.
    private boolean pinchBusy = false;
    private float pendingPinchSteps = 0f;
    private float pendingPinchX = -1f;
    private float pendingPinchY = -1f;

    private int verticalScrollEvents = 0;
    private int horizontalScrollEvents = 0;
    private int pinchEvents = 0;

    private final BroadcastReceiver commandReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String cmd = intent.getStringExtra("command");
            if (CMD_START.equals(cmd)) {
                setCapture(true);
            } else if (CMD_STOP.equals(cmd)) {
                setCapture(false);
            } else if (CMD_TEST_SWIPE.equals(cmd)) {
                testGestureActive = true;
                addScrollImpulse(+1f, 0f, true);
                sendStatus("Test impulse added.\nIt should move immediately, then decelerate.");
            }
        }
    };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        registerReceiver(commandReceiver, new IntentFilter(ACTION_COMMAND), Context.RECEIVER_NOT_EXPORTED);
        recalculateImpulses();
        setCapture(false);
        sendStatus("Accessibility service connected.\nMouse capture: OFF");
    }

    private void recalculateImpulses() {
        WindowManager wm = getSystemService(WindowManager.class);
        Rect bounds = wm.getMaximumWindowMetrics().getBounds();
        float desiredYDistance = bounds.height() * 0.10f;
        float desiredXDistance = bounds.width() * 0.10f;
        impulseYPxPerMs = desiredYDistance * (1f - FRICTION_PER_SEGMENT) / SEGMENT_MS;
        impulseXPxPerMs = desiredXDistance * (1f - FRICTION_PER_SEGMENT) / SEGMENT_MS;
    }

    private void setCapture(boolean enabled) {
        AccessibilityServiceInfo info = getServiceInfo();
        if (Build.VERSION.SDK_INT >= 34) {
            // Touchpads normally deliver MotionEvents as SOURCE_MOUSE even when the input
            // device itself also advertises SOURCE_TOUCHPAD, so SOURCE_MOUSE is intentional.
            info.setMotionEventSources(enabled ? InputDevice.SOURCE_MOUSE : 0);
            setServiceInfo(info);
            capturing = enabled;
            if (!enabled) {
                stopSyntheticMotion();
            }
            sendStatus("Mouse capture: " + (enabled ? "ON" : "OFF") +
                    (enabled
                            ? "\nv4: vertical + horizontal + pinch + left/right click forwarding active."
                            : "\nNormal mouse routing restored."));
        } else {
            sendStatus("Requires Android API 34+ for mouse MotionEvent capture.");
        }
    }

    @Override public void onMotionEvent(MotionEvent event) {
        InputDevice device = event.getDevice();
        String name = device == null ? "unknown" : device.getName();
        if (!name.toLowerCase().contains("fintie")) return;

        final int action = event.getActionMasked();
        final float v = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
        final float h = event.getAxisValue(MotionEvent.AXIS_HSCROLL);
        final float pinchScale = Build.VERSION.SDK_INT >= 34
                ? event.getAxisValue(MotionEvent.AXIS_GESTURE_PINCH_SCALE_FACTOR) : 0f;

        if (action == MotionEvent.ACTION_SCROLL) {
            // Prefer a genuine touchpad pinch axis when one is present.
            if (isNativePinch(event, pinchScale)) {
                float steps = pinchScaleToSteps(pinchScale);
                if (Math.abs(steps) > 0.001f) {
                    pinchEvents++;
                    enqueuePinch(steps, event.getX(), event.getY());
                    reportMotion(name, action, v, h, pinchScale, "native pinch");
                    return;
                }
            }

            // Many inexpensive keyboard/trackpad firmwares encode pinch as Ctrl + wheel.
            if (event.isCtrlPressed() && Math.abs(v) > 0.001f) {
                float effectiveV = v;
                // The normal Android mouse path applies Samsung's reverse-wheel preference,
                // while accessibility capture gives us the raw wheel direction. Applying the
                // preference here makes pinch direction match the user's already-correct zoom.
                if (getMouseReverseSetting() == 1) effectiveV = -effectiveV;
                pinchEvents++;
                enqueuePinch(effectiveV, event.getX(), event.getY());
                reportMotion(name, action, v, h, pinchScale, "Ctrl+wheel pinch");
                return;
            }

            if (Math.abs(v) > 0.001f || Math.abs(h) > 0.001f) {
                if (Math.abs(v) > 0.001f) verticalScrollEvents++;
                if (Math.abs(h) > 0.001f) horizontalScrollEvents++;
                addScrollImpulse(v, h, false);
                reportMotion(name, action, v, h, pinchScale, "scroll");
                return;
            }
        }

        handleMouseButtons(event);
    }

    private boolean isNativePinch(MotionEvent event, float pinchScale) {
        if (Build.VERSION.SDK_INT < 34) return false;
        boolean classified = event.getClassification() == MotionEvent.CLASSIFICATION_PINCH;
        // getAxisValue() returns 0 when the axis is absent; 1 is the neutral scale value.
        boolean hasScale = pinchScale > 0f && Math.abs(pinchScale - 1f) > 0.002f;
        return classified || hasScale;
    }

    private float pinchScaleToSteps(float scale) {
        if (scale <= 0f) return 0f;
        // Turn small proportional changes into roughly wheel-sized impulses while preserving
        // sign. Clamp so a malformed sample cannot create an enormous synthetic pinch.
        return clamp((scale - 1f) * 10f, -3f, 3f);
    }

    private void handleMouseButtons(MotionEvent event) {
        int action = event.getActionMasked();
        int actionButton = event.getActionButton();
        int buttons = event.getButtonState();

        boolean primaryPressed = (buttons & MotionEvent.BUTTON_PRIMARY) != 0;
        boolean secondaryPressed = (buttons & MotionEvent.BUTTON_SECONDARY) != 0;
        boolean primaryAction = actionButton == MotionEvent.BUTTON_PRIMARY;
        boolean secondaryAction = actionButton == MotionEvent.BUTTON_SECONDARY;

        if ((action == MotionEvent.ACTION_BUTTON_PRESS && primaryAction) ||
                (action == MotionEvent.ACTION_DOWN && primaryPressed && !secondaryPressed)) {
            if (!primaryButtonDown) {
                primaryButtonDown = true;
                forwardedClicks++;
                dispatchTapAt(event.getX(), event.getY());
            }
        }

        if ((action == MotionEvent.ACTION_BUTTON_PRESS && secondaryAction) ||
                (action == MotionEvent.ACTION_DOWN && secondaryPressed)) {
            if (!secondaryButtonDown) {
                secondaryButtonDown = true;
                forwardedRightClicks++;
                dispatchContextClickAt(event.getX(), event.getY());
            }
        }

        if ((action == MotionEvent.ACTION_BUTTON_RELEASE && primaryAction) ||
                action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            primaryButtonDown = false;
        }
        if ((action == MotionEvent.ACTION_BUTTON_RELEASE && secondaryAction) ||
                action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            secondaryButtonDown = false;
        }
    }

    /** Add velocity in either axis. More wheel events accumulate into a longer/faster glide. */
    private void addScrollImpulse(float rawVScroll, float rawHScroll, boolean testButton) {
        recalculateImpulses();

        // v3's mapping: accessibility receives the raw wheel sign, so do not compensate
        // Samsung's reverse setting for normal scrolling. Positive wheel -> finger upward.
        float addY = -rawVScroll * impulseYPxPerMs;
        float addX = -rawHScroll * impulseXPxPerMs;

        if (testButton) addY = -impulseYPxPerMs;

        // If the new input strongly opposes the current glide, make reversal responsive.
        float dot = velocityXPxPerMs * addX + velocityYPxPerMs * addY;
        if (dot < 0f) {
            velocityXPxPerMs *= 0.30f;
            velocityYPxPerMs *= 0.30f;
        }

        velocityXPxPerMs = clamp(velocityXPxPerMs + addX,
                -MAX_VELOCITY_PX_PER_MS, MAX_VELOCITY_PX_PER_MS);
        velocityYPxPerMs = clamp(velocityYPxPerMs + addY,
                -MAX_VELOCITY_PX_PER_MS, MAX_VELOCITY_PX_PER_MS);

        if (!gestureRunning && !lifting && !pinchBusy) startGesture();
    }

    private void startGesture() {
        if (speed() < STOP_VELOCITY_PX_PER_MS || pinchBusy) return;

        WindowManager wm = getSystemService(WindowManager.class);
        Rect bounds = wm.getMaximumWindowMetrics().getBounds();

        fingerX = velocityXPxPerMs < 0f ? bounds.width() * 0.76f
                : velocityXPxPerMs > 0f ? bounds.width() * 0.24f : bounds.width() * 0.50f;
        fingerY = velocityYPxPerMs < 0f ? bounds.height() * 0.76f
                : velocityYPxPerMs > 0f ? bounds.height() * 0.24f : bounds.height() * 0.50f;

        float nextX = nextSafeX(bounds, fingerX + velocityXPxPerMs * SEGMENT_MS);
        float nextY = nextSafeY(bounds, fingerY + velocityYPxPerMs * SEGMENT_MS);
        Path path = linePath(fingerX, fingerY, nextX, nextY);
        currentStroke = new GestureDescription.StrokeDescription(path, 0, SEGMENT_MS, true);
        fingerX = nextX;
        fingerY = nextY;
        gestureRunning = true;
        dispatchCurrentSegment(currentStroke);
    }

    private void dispatchNextSegment() {
        if (!capturing && !testGestureActive) {
            requestLift();
            return;
        }
        if (pinchBusy) {
            requestLift();
            return;
        }

        velocityXPxPerMs *= FRICTION_PER_SEGMENT;
        velocityYPxPerMs *= FRICTION_PER_SEGMENT;

        if (speed() < STOP_VELOCITY_PX_PER_MS) {
            velocityXPxPerMs = 0f;
            velocityYPxPerMs = 0f;
            requestLift();
            return;
        }

        WindowManager wm = getSystemService(WindowManager.class);
        Rect bounds = wm.getMaximumWindowMetrics().getBounds();
        float proposedX = fingerX + velocityXPxPerMs * SEGMENT_MS;
        float proposedY = fingerY + velocityYPxPerMs * SEGMENT_MS;
        float minX = bounds.width() * 0.08f;
        float maxX = bounds.width() * 0.92f;
        float minY = bounds.height() * 0.08f;
        float maxY = bounds.height() * 0.92f;

        if (proposedX < minX || proposedX > maxX || proposedY < minY || proposedY > maxY) {
            requestLiftAndRestart();
            return;
        }

        Path path = linePath(fingerX, fingerY, proposedX, proposedY);
        GestureDescription.StrokeDescription next =
                currentStroke.continueStroke(path, 0, SEGMENT_MS, true);
        currentStroke = next;
        fingerX = proposedX;
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
        stay.moveTo(fingerX, fingerY);
        GestureDescription.StrokeDescription finalStroke =
                currentStroke.continueStroke(stay, 0, LIFT_MS, false);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(finalStroke).build();
        boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gestureDescription) {
                gestureRunning = false;
                lifting = false;
                currentStroke = null;
                if (pinchBusy) return;
                if (speed() >= STOP_VELOCITY_PX_PER_MS && (capturing || testGestureActive)) {
                    handler.post(FintieAccessibilityService.this::startGesture);
                } else {
                    velocityXPxPerMs = 0f;
                    velocityYPxPerMs = 0f;
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
                if (!pinchBusy) handler.post(FintieAccessibilityService.this::startGesture);
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

    private void enqueuePinch(float steps, float x, float y) {
        stopSyntheticScrollForDirectGesture();
        pendingPinchSteps = clamp(pendingPinchSteps + steps, -6f, 6f);
        pendingPinchX = x;
        pendingPinchY = y;
        if (!pinchBusy) dispatchNextPinch();
    }

    private void dispatchNextPinch() {
        if (Math.abs(pendingPinchSteps) < 0.01f) {
            pinchBusy = false;
            pendingPinchSteps = 0f;
            return;
        }

        pinchBusy = true;
        float step = clamp(pendingPinchSteps, -2f, 2f);
        pendingPinchSteps -= step;

        WindowManager wm = getSystemService(WindowManager.class);
        Rect bounds = wm.getMaximumWindowMetrics().getBounds();
        float cx = pendingPinchX >= 0 ? pendingPinchX : bounds.width() * 0.50f;
        float cy = pendingPinchY >= 0 ? pendingPinchY : bounds.height() * 0.50f;
        cx = clamp(cx, bounds.width() * 0.22f, bounds.width() * 0.78f);
        cy = clamp(cy, bounds.height() * 0.22f, bounds.height() * 0.78f);

        boolean zoomIn = step > 0f;
        float magnitude = Math.min(2f, Math.abs(step));
        float innerGap = Math.min(bounds.width(), bounds.height()) * 0.055f;
        float outerGap = innerGap + Math.min(bounds.width(), bounds.height()) * (0.045f + 0.025f * magnitude);
        float startGap = zoomIn ? innerGap : outerGap;
        float endGap = zoomIn ? outerGap : innerGap;

        Path left = linePath(cx - startGap, cy, cx - endGap, cy);
        Path right = linePath(cx + startGap, cy, cx + endGap, cy);
        GestureDescription.StrokeDescription s1 =
                new GestureDescription.StrokeDescription(left, 0, PINCH_MS, false);
        GestureDescription.StrokeDescription s2 =
                new GestureDescription.StrokeDescription(right, 0, PINCH_MS, false);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(s1)
                .addStroke(s2)
                .build();

        boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gestureDescription) {
                pinchBusy = false;
                handler.post(FintieAccessibilityService.this::dispatchNextPinch);
            }

            @Override public void onCancelled(GestureDescription gestureDescription) {
                pinchBusy = false;
                pendingPinchSteps = 0f;
            }
        }, handler);
        if (!accepted) {
            pinchBusy = false;
            pendingPinchSteps = 0f;
        }
    }

    private void dispatchTapAt(float x, float y) {
        stopSyntheticScrollForDirectGesture();
        Path tap = new Path();
        tap.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(tap, 0, TAP_MS, false);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        dispatchGesture(gesture, null, handler);
    }

    /**
     * Forward right click semantically when possible. Android exposes ACTION_CONTEXT_CLICK
     * for nodes that understand a real context click. If the target does not expose it,
     * fall back to a touchscreen long press at the cursor, which is Android's usual context
     * gesture and works in many apps.
     */
    private void dispatchContextClickAt(float x, float y) {
        stopSyntheticScrollForDirectGesture();
        AccessibilityNodeInfo node = findDeepestNodeAt(getRootInActiveWindow(), x, y);
        if (node != null) {
            boolean performed = node.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_CONTEXT_CLICK.getId());
            node.recycle();
            if (performed) return;
        }
        dispatchLongPressAt(x, y);
    }

    private void dispatchLongPressAt(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(p, 0, LONG_PRESS_MS, false);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        dispatchGesture(gesture, null, handler);
    }

    private AccessibilityNodeInfo findDeepestNodeAt(AccessibilityNodeInfo node, float x, float y) {
        if (node == null) return null;
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (!r.contains((int) x, (int) y)) {
            node.recycle();
            return null;
        }

        for (int i = node.getChildCount() - 1; i >= 0; i--) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo hit = findDeepestNodeAt(child, x, y);
            if (hit != null) {
                node.recycle();
                return hit;
            }
        }
        return node;
    }

    private void stopSyntheticMotion() {
        velocityXPxPerMs = 0f;
        velocityYPxPerMs = 0f;
        pendingPinchSteps = 0f;
        pinchBusy = false;
        testGestureActive = false;
        if (gestureRunning) requestLift();
    }

    private void stopSyntheticScrollForDirectGesture() {
        velocityXPxPerMs = 0f;
        velocityYPxPerMs = 0f;
        testGestureActive = false;
        // A new dispatchGesture cancels any existing synthetic stroke. Clear our state first
        // so the cancellation callback cannot restart an old inertial glide.
        gestureRunning = false;
        lifting = false;
        currentStroke = null;
    }

    private float speed() {
        return (float) Math.hypot(velocityXPxPerMs, velocityYPxPerMs);
    }

    private int getMouseReverseSetting() {
        try {
            return Settings.System.getInt(getContentResolver(),
                    "mouse_reverse_vertical_scrolling", 0);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private void reportMotion(String name, int action, float v, float h,
                              float pinchScale, String mode) {
        sendStatus("Mouse capture: ON\nDevice: " + name +
                "\nMode: " + mode +
                "\nAction: " + MotionEvent.actionToString(action) +
                "\nVSCROLL: " + v + "  HSCROLL: " + h +
                "\nPinch scale: " + pinchScale +
                "\nV/H/Pinch events: " + verticalScrollEvents + "/" +
                horizontalScrollEvents + "/" + pinchEvents +
                "\nLeft/right clicks: " + forwardedClicks + "/" + forwardedRightClicks +
                "\nVelocity: x=" + String.format("%.2f", velocityXPxPerMs) +
                " y=" + String.format("%.2f", velocityYPxPerMs));
    }

    private static Path linePath(float x1, float y1, float x2, float y2) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        return p;
    }

    private static float nextSafeX(Rect bounds, float x) {
        return clamp(x, bounds.width() * 0.08f, bounds.width() * 0.92f);
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
