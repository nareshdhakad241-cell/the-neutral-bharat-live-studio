package com.neutralbharat.livestudio;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.content.pm.PackageManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class MainActivity extends Activity {
    private static final int PICK_VIDEO = 101;
    private static final int ASK_NOTIFICATION = 102;
    private EditText keyInput;
    private TextView fileLabel;
    private TextView statusLabel;
    private boolean receiverRegistered = false;
    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent != null) showStatus(intent.getStringExtra(StreamService.EXTRA_STATUS));
        }
    };
    private File selectedVideo;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        buildScreen();
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, ASK_NOTIFICATION);
        }
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 36, 28, 24);
        root.setBackgroundColor(0xFFF5F7FA);

        TextView title = new TextView(this);
        title.setText("THE NEUTRAL BHARAT\nLIVE STUDIO");
        title.setTextSize(25);
        title.setTextColor(0xFF102A43);
        title.setPadding(0, 0, 0, 24);
        root.addView(title);

        TextView info = new TextView(this);
        info.setText("YouTube पर वीडियो को उसके अपने ऑडियो के साथ लगातार चलाएँ।");
        info.setTextSize(16);
        info.setTextColor(0xFF334E68);
        root.addView(info);

        Button choose = new Button(this);
        choose.setText("वीडियो चुनें");
        root.addView(choose);
        fileLabel = new TextView(this);
        fileLabel.setText("अभी कोई वीडियो नहीं चुना गया");
        fileLabel.setPadding(0, 8, 0, 18);
        root.addView(fileLabel);
        choose.setOnClickListener(v -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.setType("video/*");
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(pick, PICK_VIDEO);
        });

        TextView keyLabel = new TextView(this);
        keyLabel.setText("YouTube Stream Key");
        keyLabel.setTextSize(16);
        root.addView(keyLabel);
        keyInput = new EditText(this);
        keyInput.setSingleLine(true);
        keyInput.setHint("YouTube Studio से Stream Key डालें");
        keyInput.setInputType(129);
        root.addView(keyInput);

        Button start = new Button(this);
        start.setText("▶  LIVE शुरू करें");
        root.addView(start);
        Button stop = new Button(this);
        stop.setText("■  LIVE बंद करें");
        root.addView(stop);

        TextView statusTitle = new TextView(this);
        statusTitle.setText("लाइव की स्थिति");
        statusTitle.setTextSize(17);
        statusTitle.setTextColor(0xFF102A43);
        statusTitle.setPadding(0, 18, 0, 4);
        root.addView(statusTitle);
        statusLabel = new TextView(this);
        statusLabel.setText("अभी स्ट्रीम शुरू नहीं हुई है।");
        statusLabel.setTextSize(14);
        statusLabel.setTextColor(0xFF334E68);
        statusLabel.setPadding(0, 4, 0, 12);
        root.addView(statusLabel);

        TextView note = new TextView(this);
        note.setText("\nज़रूरी: फोन चालू, चार्जिंग और स्थिर इंटरनेट पर रखें। Android की बैटरी/बैकग्राउंड सीमाएँ, नेटवर्क या YouTube की समस्या से लाइव रुक सकता है। 24×7 चलने की गारंटी नहीं है। Stream Key किसी से साझा न करें।");
        note.setTextSize(13);
        note.setTextColor(0xFF52606D);
        root.addView(note);

        start.setOnClickListener(v -> startStream());
        stop.setOnClickListener(v -> {
            Intent stopIntent = new Intent(this, StreamService.class);
            stopIntent.setAction(StreamService.ACTION_STOP);
            startService(stopIntent);
            Toast.makeText(this, "लाइव बंद करने का अनुरोध भेजा गया", Toast.LENGTH_SHORT).show();
        });
        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(StreamService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(statusReceiver, filter);
        receiverRegistered = true;
        showStatus(getSharedPreferences(StreamService.PREFS, MODE_PRIVATE).getString(StreamService.PREF_STATUS, "अभी स्ट्रीम शुरू नहीं हुई है।"));
    }

    @Override protected void onPause() {
        if (receiverRegistered) {
            unregisterReceiver(statusReceiver);
            receiverRegistered = false;
        }
        super.onPause();
    }

    private void showStatus(String message) {
        if (statusLabel != null && message != null && !message.trim().isEmpty()) statusLabel.setText(message);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_VIDEO && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            try {
                File target = new File(getCacheDir(), "selected-live-video");
                try (InputStream in = getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(target)) {
                    if (in == null) throw new Exception("वीडियो पढ़ा नहीं जा सका");
                    byte[] buffer = new byte[1024 * 1024];
                    int count;
                    while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                }
                selectedVideo = target;
                fileLabel.setText("चुना गया: " + queryName(uri));
            } catch (Exception e) {
                Toast.makeText(this, "वीडियो खोलने में समस्या: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    private String queryName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) {}
        return "वीडियो चुना गया";
    }

    private void startStream() {
        if (selectedVideo == null || !selectedVideo.exists()) {
            Toast.makeText(this, "पहले वीडियो चुनें", Toast.LENGTH_SHORT).show();
            return;
        }
        String key = keyInput.getText().toString().trim();
        if (key.isEmpty() || key.contains("/") || key.contains(" ")) {
            Toast.makeText(this, "सही YouTube Stream Key डालें", Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = new Intent(this, StreamService.class);
        intent.setAction(StreamService.ACTION_START);
        intent.putExtra(StreamService.EXTRA_FILE, selectedVideo.getAbsolutePath());
        intent.putExtra(StreamService.EXTRA_KEY, key);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
        keyInput.setText("");
        showStatus("ऐप ने अनुरोध भेजा है। FFmpeg की पुष्टि का इंतज़ार करें...");
        Toast.makeText(this, "लाइव शुरू करने का अनुरोध भेजा गया", Toast.LENGTH_SHORT).show();
    }
}
