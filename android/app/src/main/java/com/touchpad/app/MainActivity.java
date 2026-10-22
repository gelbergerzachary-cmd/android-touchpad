package com.touchpad.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

public class MainActivity extends Activity implements Connection.Listener {

    private static final String GESTURE_HELP =
            "1 finger: move  •  Tap: click  •  Double-tap & hold: drag\n"
            + "2 fingers: scroll, pinch to zoom, rotate  •  2-finger tap: right click\n"
            + "3-finger tap: look up  •  3/4-finger swipe ←→: switch Spaces\n"
            + "3/4-finger swipe ↑: Mission Control  •  ↓: App Exposé\n"
            + "4-finger pinch in: Apps  •  spread: Show Desktop";

    private SharedPreferences prefs;
    private Connection conn;
    private TrackpadView pad;
    private TextView status;
    private GradientDrawable dot;
    private final TrackpadView.Settings settings = new TrackpadView.Settings();

    // Discovery results shown in the settings dialog while it is open.
    private final Map<String, String> found = new LinkedHashMap<>();
    private LinearLayout foundList;
    private EditText hostField;
    private EditText pinField;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("touchpad", MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        loadSettings();

        conn = new Connection(this, this);
        pad = new TrackpadView(this, conn);
        pad.setSettings(settings);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.addView(pad, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(buildStatusPill(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        setContentView(root);
    }

    private View buildStatusPill() {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        int ph = (int) (14 * d), pv = (int) (6 * d);
        pill.setPadding(ph, pv, ph, pv);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC1C1C1E);
        bg.setCornerRadius(20 * d);
        pill.setBackground(bg);

        View dotView = new View(this);
        dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(0xFFFF9F0A);
        dotView.setBackground(dot);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams((int) (8 * d), (int) (8 * d));
        dl.rightMargin = (int) (8 * d);
        pill.addView(dotView, dl);

        status = new TextView(this);
        status.setTextColor(0xFFAEAEB2);
        status.setTextSize(13);
        status.setText("Starting…");
        pill.addView(status);

        TextView gear = new TextView(this);
        gear.setText("  ⚙");
        gear.setTextColor(0xFFAEAEB2);
        gear.setTextSize(15);
        pill.addView(gear);

        pill.setOnClickListener(v -> showSettings());
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(0, (int) (20 * d), 0, 0);
        wrap.addView(pill);
        return wrap;
    }

    @Override
    protected void onStart() {
        super.onStart();
        conn.start();
        String host = prefs.getString("host", "");
        conn.setTarget(host, prefs.getString("pin", ""));
        if (host.isEmpty()) {
            pad.setHint("Tap the status bar at the top to connect to your Mac");
            pad.post(this::showSettings);
        }
    }

    @Override
    protected void onStop() {
        conn.stop();
        super.onStop();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    // ---------------------------------------------------------------- Connection.Listener

    @Override
    public void onStatus(boolean connected, String message) {
        status.setText(message);
        dot.setColor(connected ? 0xFF30D158 : message.startsWith("Wrong") ? 0xFFFF453A : 0xFFFF9F0A);
        if (connected) pad.setHint("");
        else if (!prefs.getString("host", "").isEmpty()) pad.setHint("Waiting for the Mac server…");
    }

    @Override
    public void onDiscovered(String host, String name) {
        if (found.containsKey(host)) return;
        found.put(host, name);
        if (foundList == null) return;
        Button b = new Button(this);
        b.setText(name + "  (" + host + ")");
        b.setAllCaps(false);
        b.setOnClickListener(v -> {
            hostField.setText(host);
            pinField.requestFocus();
        });
        foundList.addView(b);
        if (hostField != null && hostField.getText().length() == 0) hostField.setText(host);
    }

    // ---------------------------------------------------------------- settings

    private void loadSettings() {
        settings.pointerSpeed = prefs.getFloat("pointerSpeed", 1f);
        settings.scrollSpeed = prefs.getFloat("scrollSpeed", 1f);
        settings.naturalScroll = prefs.getBoolean("natural", true);
        settings.tapToClick = prefs.getBoolean("tapToClick", true);
    }

    private void showSettings() {
        float d = getResources().getDisplayMetrics().density;
        int pad16 = (int) (16 * d);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad16 + (int) (8 * d), pad16, pad16 + (int) (8 * d), 0);

        box.addView(label("Run TouchpadServer on your Mac, then tap Find Mac (or type its IP address)."));

        hostField = new EditText(this);
        hostField.setHint("Mac IP address, e.g. 192.168.1.20");
        hostField.setSingleLine(true);
        hostField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        hostField.setText(prefs.getString("host", ""));
        box.addView(hostField);

        pinField = new EditText(this);
        pinField.setHint("PIN shown on the Mac");
        pinField.setSingleLine(true);
        pinField.setInputType(InputType.TYPE_CLASS_NUMBER);
        pinField.setText(prefs.getString("pin", ""));
        box.addView(pinField);

        Button findBtn = new Button(this);
        findBtn.setText("Find Mac");
        findBtn.setOnClickListener(v -> {
            found.clear();
            foundList.removeAllViews();
            conn.discover();
        });
        box.addView(findBtn);

        foundList = new LinearLayout(this);
        foundList.setOrientation(LinearLayout.VERTICAL);
        box.addView(foundList);
        found.clear();
        conn.discover();

        box.addView(space(d));
        SeekBar pointer = slider(box, "Tracking speed", settings.pointerSpeed);
        SeekBar scroll = slider(box, "Scrolling speed", settings.scrollSpeed);

        Switch natural = new Switch(this);
        natural.setText("Natural scrolling");
        natural.setChecked(settings.naturalScroll);
        box.addView(natural);

        Switch tap = new Switch(this);
        tap.setText("Tap to click");
        tap.setChecked(settings.tapToClick);
        box.addView(tap);

        box.addView(space(d));
        TextView help = label(GESTURE_HELP);
        help.setTextSize(12);
        box.addView(help);
        box.addView(space(d));

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("Touchpad")
                .setView(sv)
                .setPositiveButton("Save", (dlg, w) -> {
                    settings.pointerSpeed = fromSlider(pointer);
                    settings.scrollSpeed = fromSlider(scroll);
                    settings.naturalScroll = natural.isChecked();
                    settings.tapToClick = tap.isChecked();
                    String host = hostField.getText().toString().trim();
                    String pin = pinField.getText().toString().trim();
                    prefs.edit()
                            .putString("host", host)
                            .putString("pin", pin)
                            .putFloat("pointerSpeed", settings.pointerSpeed)
                            .putFloat("scrollSpeed", settings.scrollSpeed)
                            .putBoolean("natural", settings.naturalScroll)
                            .putBoolean("tapToClick", settings.tapToClick)
                            .apply();
                    conn.setTarget(host, pin);
                    pad.setHint(host.isEmpty() ? "Tap the status bar at the top to connect to your Mac" : "Connecting…");
                })
                .setNegativeButton("Cancel", null)
                .setOnDismissListener(dlg -> {
                    foundList = null;
                    hostField = null;
                    pinField = null;
                    hideSystemBars();
                })
                .show();
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        return t;
    }

    private View space(float d) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, (int) (12 * d)));
        return v;
    }

    // Sliders map 0..100 to 0.25x..3x on a log scale, with 1x in the middle-ish.
    private SeekBar slider(LinearLayout box, String title, float value) {
        box.addView(label(title));
        SeekBar s = new SeekBar(this);
        s.setMax(100);
        s.setProgress((int) Math.round((Math.log(value / 0.25) / Math.log(12)) * 100));
        box.addView(s);
        return s;
    }

    private float fromSlider(SeekBar s) {
        return (float) (0.25 * Math.pow(12, s.getProgress() / 100.0));
    }
}
