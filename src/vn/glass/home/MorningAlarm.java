package vn.glass.home;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.Calendar;

// Lấy sẵn số thời tiết + AQI lúc 6:40 các ngày thường (tivi còn tắt, box tự dậy): gần 7 giờ bé dậy bật tivi là bản
// tin sáng đọc ngay bằng số mới, khỏi chờ mạng. Wi-Fi vừa dậy có thể chưa nối lại: thử vài lần, cách nhau vài giây.
public final class MorningAlarm extends BroadcastReceiver {
    private static final int HOUR = 6, MINUTE = 40;

    @Override
    public void onReceive(Context c, Intent i) {
        Context app = c.getApplicationContext();
        PendingResult done = goAsync();
        new Thread(() -> {
            try {
                for (int k = 0; k < 5; k++) {
                    Weather.Now n = Weather.refreshNow(app);
                    if (n != null && System.currentTimeMillis() - n.wxAt < 5 * 60_000L) {
                        android.util.Log.i("GlassWx", "lay san luc sang xong (lan " + (k + 1) + ")");
                        break;
                    }
                    Thread.sleep(3000);
                }
            } catch (Exception ignored) {
            } finally {
                schedule(app);
                done.finish();
            }
        }, "glass-sang").start();
    }

    /** Hẹn lần lấy kế tiếp: 6:40 ngày thường gần nhất (đã qua thì sang ngày sau). */
    static void schedule(Context c) {
        Calendar t = Calendar.getInstance();
        t.set(Calendar.HOUR_OF_DAY, HOUR);
        t.set(Calendar.MINUTE, MINUTE);
        t.set(Calendar.SECOND, 0);
        t.set(Calendar.MILLISECOND, 0);
        if (t.getTimeInMillis() <= System.currentTimeMillis() + 60_000L) t.add(Calendar.DAY_OF_MONTH, 1);
        while (t.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || t.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY)
            t.add(Calendar.DAY_OF_MONTH, 1);
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(c, 41, new Intent(c, MorningAlarm.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        // đúng 6:40, đánh thức cả khi box đang ngủ sâu (hẹn kiểu thường hệ thống cho trễ tới 1 tiếng: không kịp cho bé);
        // máy không cho hẹn chính xác thì hẹn trong khung 6:36 tới 6:40
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.getTimeInMillis(), pi);
        else am.setWindow(AlarmManager.RTC_WAKEUP, t.getTimeInMillis() - 4 * 60_000L, 4 * 60_000L, pi);
    }
}
