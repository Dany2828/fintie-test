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
        title.setText("Fintie Scroll Test v4");
        title.setTextSize(28);
        title.setTextColor(Color.BLACK);
        root.addView(title);

        TextView explainer = new TextView(this);
        explainer.setText(
                "v4 keeps the v2 scroll feel and adds the missing gestures:\n" +
                "• corrected vertical direction\n" +
                "• smooth horizontal two-finger scrolling\n" +
                "• pinch zoom (native pinch axis or Ctrl+wheel encoding)\n" +
                "• primary click -> touchscreen tap\n" +
                "• secondary/two-finger tap -> context/right click\n\n" +
                "Still diagnostic: click-drag is not forwarded yet."
        );
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

        TextView testHeading = new TextView(this);
        testHeading.setText("\nScrollable test area");
        testHeading.setTextSize(20);
        testHeading.setTextColor(Color.BLACK);
        root.addView(testHeading);

        StringBuilder filler = new StringBuilder();
        for (int i = 1; i <= 70; i++) {
            filler.append("Scroll test line ").append(i)
                    .append(" — use the Fintie or TEST SMOOTH SWIPE.\n\n");
        }
        TextView fillerView = new TextView(this);
        fillerView.setText(filler.toString());
        fillerView.setTextSize(16);
        fillerView.setPadding(0, dp(12), 0, dp(40));
        root.addView(fillerView);

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
        StringBuilder b = new StringBuilder("Connected Fintie devices:\n");
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d != null && d.getName().toLowerCase().contains("fintie")) {
                b.append("• ").append(d.getName())
                        .append("  id=").append(id)
                        .append(" sources=0x").append(Integer.toHexString(d.getSources()))
                        .append("\n");
            }
        }
        try {
            int reverse = Settings.System.getInt(getContentResolver(), "mouse_reverse_vertical_scrolling", 0);
            b.append("\nmouse_reverse_vertical_scrolling=").append(reverse);
        } catch (Exception ignored) { }
        b.append("\n\nService starts with mouse capture OFF.");
        return b.toString();
    }

    private void addButton(LinearLayout root, String text, android.view.View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, dp(6), 0, dp(6));
        root.addView(button, p);
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }
}
