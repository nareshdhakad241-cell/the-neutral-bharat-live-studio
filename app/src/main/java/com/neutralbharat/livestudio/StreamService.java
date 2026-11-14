package com.neutralbharat.livestudio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

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

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if (ACTION_STOP.equals(intent.getAction())) {
            if (activeSessionId != -1) FFmpegKit.cancel(activeSessionId);
            activeSessionId = -1;
            publishStatus("लाइव बंद कर दी गई।");
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(intent.getAction())) {
            String file = intent.getStringExtra(EXTRA_FILE);
            String key = intent.getStringExtra(EXTRA_KEY);
            if (file == null || key == null) { publishStatus("त्रुटि: वीडियो फ़ाइल या Stream Key नहीं मिली।"); stopSelf(); return START_NOT_STICKY; }
            try {
                startForeground(7, buildNotification("स्ट्रीमिंग सेवा शुरू हो रही है"));
                publishStatus("Android स्ट्रीमिंग सेवा शुरू हुई। वीडियो जाँची जा रही है...");
                java.io.File video = new java.io.File(file);
                if (!video.exists() || !video.canRead()) {
                    publishStatus("त्रुटि: चुनी गई वीडियो फ़ाइल पढ़ी नहीं जा रही। वीडियो दोबारा चुनें।");
                    stopForeground(false);
                    stopSelf();
                    return START_NOT_STICKY;
                }
                String url = "rtmp://a.rtmp.youtube.com/live2/" + key;
                String[] args = new String[]{
                    "-hide_banner", "-loglevel", "info",
                    "-re", "-stream_loop", "-1", "-i", file,
                    "-c:v", "h264_mediacodec", 
                    "-b:v", "2500k", "-maxrate", "2500k", "-bufsize", "5000k",
                    "-pix_fmt", "yuv420p", "-g", "60",
                    "-c:a", "aac", "-b:a", "128k", "-ar", "44100",
                    "-f", "flv", url
                };
                publishStatus("FFmpeg शुरू किया जा रहा है। अब लाइव लॉग की जाँच होगी...");
                FFmpegKitConfig.enableLogCallback(log -> {
                    String message = log.getMessage();
                    if (message == null || message.trim().isEmpty()) return;
                    String lower = message.toLowerCase(java.util.Locale.ROOT);
                    if (lower.contains("error") || lower.contains("failed") ||
                        lower.contains("opening connection") || lower.contains("connected") ||
                        lower.contains("output #") || lower.contains("stream mapping") ||
                        lower.contains("frame=") || lower.contains("invalid") ||
                        lower.contains("encoder")) {
                        String safe = message.replace(key, "[Stream Key छिपाई गई]").trim();
                        if (safe.length() > 280) safe = safe.substring(safe.length() - 280);
                        publishStatus("लाइव जाँच: " + safe);
                    }
                });
                FFmpegSession runningSession = FFmpegKit.executeWithArgumentsAsync(args, session -> {
                    activeSessionId = -1;
                    int code = session.getReturnCode() == null ? -1 : session.getReturnCode().getValue();
                    String output = session.getOutput();
                    if (output == null) output = "";
                    output = output.replace(key, "[Stream Key छिपाई गई]");
                    if (output.length() > 700) output = output.substring(output.length() - 700);
                    String result = code == 0 ? "FFmpeg प्रक्रिया समाप्त हुई (कोड 0)।" : "FFmpeg विफल हुआ (कोड " + code + ")।";
                    if (!output.trim().isEmpty()) result += "\\nलॉग: " + output.trim();
                    publishStatus(result);
                    stopForeground(false);
                    stopSelf();
                });
                activeSessionId = runningSession.getSessionId();
                publishStatus("FFmpeg सत्र बनाया गया (ID " + activeSessionId + ")। कनेक्शन परिणाम की प्रतीक्षा है...");
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
                String message = details.toString();
                try { publishStatus("स्ट्रीम शुरू नहीं हुई: " + message); } catch (Throwable ignored) {}
                stopForeground(false);
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        return START_NOT_STICKY;
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
