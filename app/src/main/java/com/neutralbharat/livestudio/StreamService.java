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
import com.arthenica.ffmpegkit.ReturnCode;

public class StreamService extends Service {
    public static final String ACTION_START = "com.neutralbharat.livestudio.START";
    public static final String ACTION_STOP = "com.neutralbharat.livestudio.STOP";
    public static final String EXTRA_FILE = "video_file";
    public static final String EXTRA_KEY = "stream_key";
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
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(intent.getAction())) {
            String file = intent.getStringExtra(EXTRA_FILE);
            String key = intent.getStringExtra(EXTRA_KEY);
            if (file == null || key == null) { stopSelf(); return START_NOT_STICKY; }
            startForeground(7, buildNotification("YouTube लाइव तैयार हो रहा है"));
            String url = "rtmp://a.rtmp.youtube.com/live2/" + key;
            String[] args = new String[]{
                "-hide_banner", "-loglevel", "warning",
                "-re", "-stream_loop", "-1", "-i", file,
                "-c:v", "libx264", "-preset", "veryfast",
                "-b:v", "2500k", "-maxrate", "2500k", "-bufsize", "5000k",
                "-pix_fmt", "yuv420p", "-g", "60",
                "-c:a", "aac", "-b:a", "128k", "-ar", "44100",
                "-f", "flv", url
            };
            activeSessionId = FFmpegKit.executeWithArgumentsAsync(args, session -> {
                activeSessionId = -1;
                int code = session.getReturnCode() == null ? -1 : session.getReturnCode().getValue();
                updateNotification(code == 0 ? "लाइव समाप्त" : "लाइव रुक गया — ऐप खोलकर दोबारा प्रयास करें");
                stopForeground(false);
                stopSelf();
            });
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

    @Override public IBinder onBind(Intent intent) { return null; }
}
