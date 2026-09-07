package com.fintie.scrolltest;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private TextView status;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (FintieAccessibilityService.ACTION_STATUS.equals(intent.getAction())) {
                status.setText(intent.getStringExtra("status"));
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Fintie Scroll Test");
        title.setTextSize(28);
        title.setTextColor(Color.BLACK);
        root.addView(title);

        TextView explainer = new TextView(this);
        explainer.setText("1. Enable the Accessibility Service.\n2. Test synthetic swipe.\n3. Tap START MOUSE CAPTURE.\n\nIf the cursor stops moving, use your finger on the screen to tap STOP MOUSE CAPTURE. That result means Android consumes the whole mouse source, not only wheel events.");
        explainer.setTextSize(16);
        explainer.setPadding(0, dp(16), 0, dp(16));
        root.addView(explainer);

        status = new TextView(this);
        status.setText(buildDeviceSummary());
        status.setTextSize(15);
        status.setTextColor(Color.DKGRAY);
        status.setPadding(0, dp(12), 0, dp(20));
        root.addView(status);

        addButton(root, "OPEN ACCESSIBILITY SETTINGS", v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        addButton(root, "TEST SMOOTH SWIPE", v -> sendCommand(FintieAccessibilityService.CMD_TEST_SWIPE));
        addButton(root, "START MOUSE CAPTURE", v -> sendCommand(FintieAccessibilityService.CMD_START));
        addButton(root, "STOP MOUSE CAPTURE", v -> sendCommand(FintieAccessibilityService.CMD_STOP));

        TextView note = new TextView(this);
        note.setText("Expected successful diagnostic:\n• wheel appears as ACTION_SCROLL / AXIS_VSCROLL\n• synthetic swipe visibly glides\n• we observe whether cursor/clicks survive mouse capture\n\nThis build does not auto-start, change Bluetooth settings, or run persistently.");
        note.setPadding(0, dp(24), 0, 0);
        root.addView(note);

        setContentView(scroll);
    }

    @Override protected void onResume() {
        super.onResume();
        status.setText(buildDeviceSummary());
    }

    @Override protected void onStart() {
        super.onStart();
        registerReceiver(receiver, new IntentFilter(FintieAccessibilityService.ACTION_STATUS), Context.RECEIVER_NOT_EXPORTED);
    }

    @Override protected void onStop() {
        unregisterReceiver(receiver);
        super.onStop();
    }

    private void sendCommand(String command) {
        Intent i = new Intent(FintieAccessibilityService.ACTION_COMMAND);
        i.setPackage(getPackageName());
        i.putExtra("command", command);
        sendBroadcast(i);
    }

    private String buildDeviceSummary() {
        StringBuilder b = new StringBuilder("Connected input devices:\n");
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d != null && d.getName().toLowerCase().contains("fintie")) {
                b.append("• ").append(d.getName())
                        .append("  id=").append(id)
                        .append(" sources=0x").append(Integer.toHexString(d.getSources()))
                        .append("\n");
            }
        }
        b.append("\nService starts with mouse capture OFF.");
        return b.toString();
    }

    private void addButton(LinearLayout root, String text, android.view.View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, dp(6), 0, dp(6));
        root.addView(button, p);
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
