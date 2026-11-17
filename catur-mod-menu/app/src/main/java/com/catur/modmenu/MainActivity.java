package com.catur.modmenu;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_OVERLAY = 11;
    private static final int REQ_CAPTURE = 12;
    private static final int REQ_NOTIFY = 13;

    private SharedPreferences prefs;
    private Spinner spinColor, spinDepth;
    private SeekBar skill;
    private EditText fenInput;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("catur", Context.MODE_PRIVATE);

        spinColor = findViewById(R.id.spin_color);
        spinDepth = findViewById(R.id.spin_depth);
        skill = findViewById(R.id.skill);
        fenInput = findViewById(R.id.fen_input);
        status = findViewById(R.id.status);

        spinColor.setSelection(prefs.getInt("white", 1));
        String[] depths = getResources().getStringArray(R.array.depths);
        String savedDepth = String.valueOf(prefs.getInt("depth", 12));
        for (int i = 0; i < depths.length; i++)
            if (depths[i].equals(savedDepth)) spinDepth.setSelection(i);
        skill.setProgress(prefs.getInt("skill", 10));

        skill.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int v, boolean b) { }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { }
        });

        findViewById(R.id.btn_start).setOnClickListener(v -> startOverlay());
        findViewById(R.id.btn_stop).setOnClickListener(v -> {
            stopService(new Intent(this, ModMenuService.class));
            status.setText("overlay stopped");
        });
        findViewById(R.id.btn_overlay_permission).setOnClickListener(v -> requestOverlay());
        findViewById(R.id.btn_a11y_permission).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.btn_fen).setOnClickListener(v -> {
            String fen = fenInput.getText().toString().trim();
            if (fen.isEmpty()) { toast("paste a FEN first"); return; }
            try { Chess.Position.fromFen(fen); }
            catch (Exception e) { toast("that FEN does not parse"); return; }
            if (ModMenuService.instance == null) { toast("start the overlay first"); return; }
            ModMenuService.analyzeFen(fen);
            status.setText("analysing pasted FEN");
        });
        findViewById(R.id.btn_scan).setOnClickListener(v -> {
            if (ModMenuService.instance == null) toast("start the overlay first");
            else toast("tap SCAN BOARD on the floating panel");
        });

        if (!Settings.canDrawOverlays(this)) requestOverlay();
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
    }

    private void requestOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(i, REQ_OVERLAY);
        } else toast("overlay permission already granted");
    }

    private void startOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            toast("grant draw-over-apps first");
            requestOverlay();
            return;
        }
        prefs.edit()
                .putInt("white", spinColor.getSelectedItemPosition())
                .putInt("depth", Integer.parseInt(spinDepth.getSelectedItem().toString()))
                .putInt("skill", skill.getProgress())
                .apply();

        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_OVERLAY) {
            status.setText(Settings.canDrawOverlays(this) ? "overlay allowed" : "overlay denied");
        } else if (req == REQ_CAPTURE) {
            if (res != RESULT_OK || data == null) { toast("screen capture not granted — move list still works"); }
            else {
                MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                MediaProjectionHolder.set(mpm.getMediaProjection(res, data));
            }
            Intent i = new Intent(this, ModMenuService.class);
            i.putExtra("depth", prefs.getInt("depth", 12));
            i.putExtra("whitePlayer", prefs.getInt("white", 1) == 0);
            i.putExtra("skill", prefs.getInt("skill", 10));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
            else startService(i);
            status.setText("overlay running — switch to the chess app");
        }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
