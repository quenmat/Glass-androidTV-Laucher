package vn.glass.home;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

/**
 * Dịch vụ nền nhỏ chạy KeepAudio suốt lúc box bật, kể cả khi đang ở app khác (phim tạm dừng).
 * Launcher mở nó mỗi lần hiện; chạy dạng foreground để box ít RAM không giết mất.
 */
public class KeepAudioService extends Service {
    private static final String CH = "keep_audio";
    private KeepAudio keep;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "Giữ loa ngoài", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, CH).setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
                .setContentTitle("Giữ loa ngoài luôn kết nối").setOngoing(true).build();
        try { startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK); } catch (Exception ignored) {}
        keep = new KeepAudio(this);
        keep.start();
    }

    @Override
    public int onStartCommand(Intent i, int flags, int id) { return START_STICKY; }

    @Override
    public void onDestroy() {
        if (keep != null) keep.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) { return null; }
}
