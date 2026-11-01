package vn.glass.home;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Danh sách ô trên dock, theo thứ tự hiển thị. key = tên ảnh trong assets/cards (null: app tự thêm không có ô vẽ sẵn,
// vẽ ô từ banner của app).
final class Apps {
    static final class App {
        final String key, pkg, label;
        final int color;          // màu nền dự phòng khi app không có ảnh nội dung
        App(String key, String pkg, String label, int color) {
            this.key = key; this.pkg = pkg; this.label = label; this.color = color;
        }
        boolean isSettings() { return pkg == null; }
    }

    static List<App> base() {
        List<App> a = new ArrayList<>();
        a.add(new App("youtube", "com.google.android.youtube.tv", "YouTube", 0xFF8E1B1B));
        a.add(new App("netflix", "com.netflix.ninja", "Netflix", 0xFF5A0A0E));
        a.add(new App("fptplay", "net.fptplay.ottbox", "FPT Play", 0xFF7A3510));
        a.add(new App("tv360", "com.viettel.tv360.tv", "TV360", 0xFF0B3A66));
        a.add(new App("play", "com.android.vending", "Cửa hàng Play", 0xFF1E4D3A));
        // VieON vào bảng Tất cả ứng dụng; Cài đặt là nút bánh răng ở góc màn hình; HBO Max đã gỡ
        return a;
    }

    /** App cài thêm nhưng có ô vẽ sẵn trong assets/cards (logo đẹp, cùng tông với dock): dùng ô đó thay banner của app. */
    private static final java.util.Map<String, String> CARD_FOR = new java.util.HashMap<>();
    static {
        CARD_FOR.put("com.iqiyi.i18n.tv", "iqiyi");
    }

    /**
     * Dock thật: các app cố định + app cài thêm về sau, chen ngay trước Play Store theo thứ tự cài.
     * Lần chạy đầu chụp lại mọi app đang có ("đã biết"): chỉ app cài SAU mốc đó mới tự lên dock,
     * nên những app đã cố ý bỏ khỏi dock (VieON...) không quay lại. Gỡ app thì ô tự biến mất.
     */
    static List<App> dock(Context ctx) {
        List<App> a = base();
        PackageManager pm = ctx.getPackageManager();
        SharedPreferences p = ctx.getSharedPreferences("glass", Context.MODE_PRIVATE);
        Set<String> now = launchable(pm);
        Set<String> known = p.getStringSet("known_pkgs", null);
        if (known == null) {
            p.edit().putStringSet("known_pkgs", now).apply();
            return a;
        }
        Set<String> fixed = new HashSet<>();
        for (App x : a) fixed.add(x.pkg);
        fixed.add(ctx.getPackageName());
        fixed.add("vn.glass.allapps");

        List<String> extra = new ArrayList<>();
        String saved = p.getString("extra_pkgs", "");
        if (!saved.isEmpty()) extra.addAll(Arrays.asList(saved.split(",")));
        extra.removeIf(pkg -> !now.contains(pkg));               // đã gỡ
        List<String> fresh = new ArrayList<>();
        for (String pkg : now) if (!known.contains(pkg) && !fixed.contains(pkg) && !extra.contains(pkg)) fresh.add(pkg);
        fresh.sort((x, y) -> Long.compare(installedAt(pm, x), installedAt(pm, y)));
        extra.addAll(fresh);

        // "đã biết" = mốc ban đầu trừ các app đã gỡ: gỡ rồi cài lại thì vẫn tự lên dock
        Set<String> k = new HashSet<>(known);
        k.retainAll(now);
        p.edit().putString("extra_pkgs", String.join(",", extra)).putStringSet("known_pkgs", k).apply();

        int at = a.size() - 1;                                      // ngay trước Play Store
        for (String pkg : extra) {
            String label = pkg;
            try { label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); } catch (Exception ignored) {}
            a.add(at++, new App(CARD_FOR.get(pkg), pkg, label, 0xFF2C2C30));
        }
        return a;
    }

    private static Set<String> launchable(PackageManager pm) {
        Set<String> s = new HashSet<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                s.add(r.activityInfo.packageName);
            }
        }
        return s;
    }

    private static long installedAt(PackageManager pm, String pkg) {
        try { return pm.getPackageInfo(pkg, 0).firstInstallTime; } catch (Exception e) { return 0; }
    }

    private Apps() {}
}
