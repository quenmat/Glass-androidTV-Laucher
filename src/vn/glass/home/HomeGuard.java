package vn.glass.home;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashSet;
import java.util.Set;

// Giữ Glass Home làm màn hình chính khi không đặt được bằng máy tính (Google TV không có chỗ chọn launcher).
// Dịch vụ Hỗ trợ tiếp cận, người dùng bật một lần trong Cài đặt:
// - hệ thống mở dịch vụ lúc khởi động -> đưa Glass Home lên luôn (bật tivi là vào Glass Home)
// - bắt nút Home trước hệ thống -> mở Glass Home, không loé màn hình gốc
// - màn hình gốc lỡ hiện ra (thoát app, app tự đóng) -> đưa Glass Home lên lại
// Chỉ nghe nút Home và biết launcher nào vừa hiện; không đọc nội dung gì khác.
// Máy đã đặt Glass Home làm màn hình chính thật (adb set-home-activity) thì dịch vụ đứng yên, để hệ thống tự lo.
public class HomeGuard extends AccessibilityService {

    private long lastLaunch;
    private Set<String> launchers = new HashSet<>();

    @Override
    protected void onServiceConnected() {
        launchers = otherLaunchers(this);
        if (!isSystemHome(this)) bringHome();   // vừa khởi động (hoặc vừa bật dịch vụ): vào Glass Home
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.KEYCODE_HOME || isSystemHome(this)) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) bringHome();
        return true;   // nuốt cả lúc nhấn lẫn lúc nhả: hệ thống không mở màn hình gốc
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.getPackageName() == null) return;
        String pkg = e.getPackageName().toString();
        if (launchers.contains(pkg) && !isSystemHome(this)) bringHome();
    }

    @Override
    public void onInterrupt() {}

    private void bringHome() {
        long now = SystemClock.uptimeMillis();
        if (now - lastLaunch < 800) return;   // nhiều sự kiện dồn dập cùng lúc: chỉ mở một lần
        lastLaunch = now;
        Intent i = new Intent(Intent.ACTION_MAIN).setClass(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try { startActivity(i); } catch (Exception ignored) {}
    }

    /** Các launcher khác trên máy (màn hình gốc của Google TV, launcher hãng...). */
    private static Set<String> otherLaunchers(Context c) {
        Set<String> s = new HashSet<>();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo r : c.getPackageManager().queryIntentActivities(home, 0))
            if (!c.getPackageName().equals(r.activityInfo.packageName)) s.add(r.activityInfo.packageName);
        return s;
    }

    /** Hệ thống đã coi Glass Home là màn hình chính (đặt bằng adb): không cần can thiệp. */
    static boolean isSystemHome(Context c) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo r = c.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return r != null && r.activityInfo != null && c.getPackageName().equals(r.activityInfo.packageName);
    }

    /** Người dùng đã bật dịch vụ này trong Cài đặt -> Hỗ trợ tiếp cận chưa. */
    static boolean enabled(Context c) {
        String on = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (on == null) return false;
        String me = new ComponentName(c, HomeGuard.class).flattenToString();
        String shortMe = new ComponentName(c, HomeGuard.class).flattenToShortString();
        for (String s : on.split(":")) if (s.equalsIgnoreCase(me) || s.equalsIgnoreCase(shortMe)) return true;
        return false;
    }
}
