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
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

public class FintieAccessibilityService extends AccessibilityService {
    public static final String ACTION_COMMAND = "com.fintie.scrolltest.COMMAND";
    public static final String ACTION_STATUS = "com.fintie.scrolltest.STATUS";
    public static final String CMD_START = "start";
    public static final String CMD_STOP = "stop";
    public static final String CMD_TEST_SWIPE = "test_swipe";

    private boolean capturing = false;

    private final BroadcastReceiver commandReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String cmd = intent.getStringExtra("command");
            if (CMD_START.equals(cmd)) setCapture(true);
            else if (CMD_STOP.equals(cmd)) setCapture(false);
            else if (CMD_TEST_SWIPE.equals(cmd)) {
                boolean ok = dispatchSmoothSwipe(1f);
                sendStatus("Synthetic swipe dispatched: " + ok + "\nCapture: " + capturing);
            }
        }
    };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        registerReceiver(commandReceiver, new IntentFilter(ACTION_COMMAND), Context.RECEIVER_NOT_EXPORTED);
        setCapture(false);
        sendStatus("Accessibility service connected.\nMouse capture: OFF");
    }

    private void setCapture(boolean enabled) {
        AccessibilityServiceInfo info = getServiceInfo();
        if (Build.VERSION.SDK_INT >= 34) {
            info.setMotionEventSources(enabled ? InputDevice.SOURCE_MOUSE : 0);
            setServiceInfo(info);
            capturing = enabled;
            sendStatus("Mouse capture: " + (enabled ? "ON" : "OFF") +
                    (enabled ? "\nTry cursor movement, click, then two-finger scroll." : "\nNormal mouse routing restored."));
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

        sendStatus("Mouse capture: ON\nDevice: " + name +
                "\nAction: " + action +
                "\nVSCROLL: " + v + "  HSCROLL: " + h +
                "\nSource: 0x" + Integer.toHexString(event.getSource()));

        if (event.getActionMasked() == MotionEvent.ACTION_SCROLL &&
                name.toLowerCase().contains("fintie") && Math.abs(v) > 0.001f) {
            // For the diagnostic we only care that a wheel notch can trigger a real touch gesture.
            dispatchSmoothSwipe(v);
        }
    }

    private boolean dispatchSmoothSwipe(float wheelSign) {
        WindowManager wm = getSystemService(WindowManager.class);
        Rect bounds = wm.getMaximumWindowMetrics().getBounds();
        float x = bounds.width() * 0.50f;
        float centerY = bounds.height() * 0.55f;
        float distance = bounds.height() * 0.10f;

        // Direction is intentionally easy to flip after the diagnostic if Samsung's sign differs.
        float endY = centerY + (wheelSign > 0 ? -distance : distance);

        Path path = new Path();
        path.moveTo(x, centerY);
        path.lineTo(x, endY);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 170);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        return dispatchGesture(gesture, null, null);
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
