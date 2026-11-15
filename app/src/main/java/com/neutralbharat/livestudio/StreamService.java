package com.neutralbharat.livestudio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.FFmpegKitConfig;

public class StreamService extends Service {
    public static final String ACTION_START = "com.neutralbharat.livestudio.START";
    public static final String ACTION_STOP = "com.neutralbharat.livestudio.STOP";
    public static final String EXTRA_FILE = "video_file";
    public static final String EXTRA_KEY = "stream_key";
    public static final String ACTION_STATUS = "com.neutralbharat.livestudio.STATUS";
    public static final String EXTRA_STATUS = "status_text";
    public static final String PREFS = "live_stream_status";
    public static final String PREF_STATUS = "last_status";
    private static final String CHANNEL = "live_stream_channel";
    private static long activeSessionId = -1;
    private static volatile boolean sessionStartingOrRunning = false;
    private PowerManager.WakeLock wakeLock;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if (ACTION_STOP.equals(intent.getAction())) {
            sessionStartingOrRunning = false;
            if (activeSessionId != -1) FFmpegKit.cancel(activeSessionId);
            activeSessionId = -1;
            releaseCpuLock();
            publishStatus("लाइव बंद कर दी गई।");
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(intent.getAction())) {
            if (sessionStartingOrRunning) {
                publishStatus("लाइव पहले से शुरू है। दूसरी स्ट्रीम शुरू नहीं की गई।");
                return START_NOT_STICKY;
            }
            sessionStartingOrRunning = true;
            String file = intent.getStringExtra(EXTRA_FILE);
            String key = intent.getStringExtra(EXTRA_KEY);
            if (file == null || key == null) { sessionStartingOrRunning = false; publishStatus("त्रुटि: वीडियो फ़ाइल या Stream Key नहीं मिली।"); stopSelf(); return START_NOT_STICKY; }
            try {
                startForeground(7, buildNotification("स्ट्रीमिंग सेवा शुरू हो रही है"));
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":LiveStream");
                    wakeLock.setReferenceCounted(false);
                    wakeLock.acquire();
                }
                publishStatus("Android स्ट्रीमिंग सेवा शुरू हुई। वीडियो जाँची जा रही है...");
                java.io.File video = new java.io.File(file);
                if (!video.exists() || !video.canRead()) {
                    sessionStartingOrRunning = false;
                    releaseCpuLock();
                    publishStatus("त्रुटि: चुनी गई वीडियो फ़ाइल पढ़ी नहीं जा रही। वीडियो दोबारा चुनें।");
                    stopForeground(false);
                    stopSelf();
                    return START_NOT_STICKY;
                }
                // Use YouTube standard RTMP ingest; this FFmpeg build does not include the RTMPS protocol.
                String url = "rtmp://a.rtmp.youtube.com/live2/" + key;
                String[] args = new String[]{
                    "-hide_banner", "-loglevel", "verbose",
                    "-re", "-stream_loop", "-1", "-i", file,
                    // Downscale 1080p sources to 720p/24fps to help mid-range phones keep up in real time.
                    "-vf", "scale=1280:720:force_original_aspect_ratio=decrease,pad=1280:720:(ow-iw)/2:(oh-ih)/2,fps=24",
                    "-c:v", "h264_mediacodec",
                    "-b:v", "1000k", "-maxrate", "1000k", "-bufsize", "2000k",
                    "-pix_fmt", "yuv420p", "-r", "24", "-g", "48",
                    "-c:a", "aac", "-b:a", "96k", "-ar", "44100",
                    "-f", "flv", "-rw_timeout", "20000000", url
                };
                publishStatus("FFmpeg शुरू किया जा रहा है। अब YouTube RTMP कनेक्शन की जाँच होगी...");
                FFmpegKitConfig.enableLogCallback(log -> {
                    String message = log.getMessage();
                    if (message == null || message.trim().isEmpty()) return;
                    String lower = message.toLowerCase(java.util.Locale.ROOT);
                    if (lower.contains("error") || lower.contains("failed") ||
                        lower.contains("connection refused") || lower.contains("connected") ||
                        lower.contains("output #") || lower.contains("stream mapping") ||
                        lower.contains("rtmp") || lower.contains("tcp") || lower.contains("publish") ||
                        lower.contains("netstream") || lower.contains("invalid") ||
                        lower.contains("encoder") || lower.contains("handshake") ||
                        lower.contains("io error")) {
                        String safe = message.replace(key, "[Stream Key छिपाई गई]").trim();
                        if (safe.length() > 280) safe = safe.substring(safe.length() - 280);
                        publishStatus("लाइव जाँच: " + safe);
                    }
                });
                FFmpegSession runningSession = FFmpegKit.executeWithArgumentsAsync(args, session -> {
                    activeSessionId = -1;
                    sessionStartingOrRunning = false;
                    releaseCpuLock();
                    int code = session.getReturnCode() == null ? -1 : session.getReturnCode().getValue();
                    String output = session.getOutput();
                    if (output == null) output = "";
                    output = output.replace(key, "[Stream Key छिपाई गई]");
                    if (output.length() > 3000) output = output.substring(output.length() - 3000);
                    String result = code == 0 ? "FFmpeg प्रक्रिया समाप्त हुई (कोड 0)।" : "FFmpeg विफल हुआ (कोड " + code + ")।";
                    if (!output.trim().isEmpty()) result += "\nलॉग: " + output.trim();
                    publishStatus(result);
                    stopForeground(false);
                    stopSelf();
                });
                activeSessionId = runningSession.getSessionId();
                publishStatus("FFmpeg सत्र बनाया गया (ID " + activeSessionId + ")। YouTube RTMP कनेक्शन परिणाम की प्रतीक्षा है...");
            } catch (Throwable error) {
                StringBuilder details = new StringBuilder(error.getClass().getSimpleName());
                if (error.getMessage() != null && !error.getMessage().trim().isEmpty()) {
                    details.append(": ").append(error.getMessage());
                }
                Throwable cause = error.getCause();
                if (cause != null && cause != error) {
                    details.append(" | कारण: ").append(cause.getClass().getSimpleName());
                    if (cause.getMessage() != null) details.append(": ").append(cause.getMessage());
                }
                sessionStartingOrRunning = false;
                releaseCpuLock();
                String message = details.toString();
                try { publishStatus("स्ट्रीम शुरू नहीं हुई: " + message); } catch (Throwable ignored) {}
                stopForeground(false);
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        return START_NOT_STICKY;
    }

    private void releaseCpuLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {}
        wakeLock = null;
    }

    @Override public void onDestroy() {
        releaseCpuLock();
        super.onDestroy();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "YouTube Live", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
            (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0) | PendingIntent.FLAG_UPDATE_CURRENT);
        if (Build.VERSION.SDK_INT >= 26) {
            return new Notification.Builder(this, CHANNEL)
                .setContentTitle("The Neutral Bharat Live Studio")
                .setContentText(text).setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pi).setOngoing(true).build();
        }
        return new Notification.Builder(this)
            .setContentTitle("The Neutral Bharat Live Studio")
            .setContentText(text).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi).setOngoing(true).build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(7, buildNotification(text));
    }

    private void publishStatus(String text) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_STATUS, text).apply();
        Intent status = new Intent(ACTION_STATUS);
        status.setPackage(getPackageName());
        status.putExtra(EXTRA_STATUS, text);
        sendBroadcast(status);
        updateNotification(text.length() > 180 ? text.substring(0, 180) : text);
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}