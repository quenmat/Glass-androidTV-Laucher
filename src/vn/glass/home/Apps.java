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
     * Dock thật: các app cố định (chỉ app đã cài) + app cài thêm, chen ngay trước Play Store.
     * Lần chạy đầu: đưa luôn các app TV đang có lên dock (bỏ app hệ thống như Cài đặt, launcher khác), rồi chụp
     * lại mọi app làm mốc "đã biết"; về sau chỉ app cài SAU mốc đó mới tự lên dock, nên app đã cố ý bỏ khỏi dock
     * (VieON...) không quay lại. Gỡ app thì ô tự biến mất.
     */
    static List<App> dock(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        SharedPreferences p = ctx.getSharedPreferences("glass", Context.MODE_PRIVATE);
        Set<String> now = launchable(pm);
        List<App> a = base();
        Set<String> fixed = new HashSet<>();
        for (App x : a) fixed.add(x.pkg);
        fixed.add(ctx.getPackageName());
        fixed.add("vn.glass.allapps");
        a.removeIf(x -> !now.contains(x.pkg));                    // app chưa cài: không để ô bấm không chạy
        Set<String> known = p.getStringSet("known_pkgs", null);
        if (known == null) {
            List<String> first = new ArrayList<>();
            for (String pkg : tvApps(pm)) if (!fixed.contains(pkg) && !hidden(pm, pkg)) first.add(pkg);
            first.sort((x, y) -> label(pm, x).compareToIgnoreCase(label(pm, y)));
            p.edit().putStringSet("known_pkgs", now).putString("extra_pkgs", String.join(",", first)).apply();
            known = now;
        }

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

        int at = a.size();                                          // ngay trước Play Store (nếu có)
        for (int i = 0; i < a.size(); i++) if ("com.android.vending".equals(a.get(i).pkg)) at = i;
        for (String pkg : extra) a.add(at++, new App(CARD_FOR.get(pkg), pkg, label(pm, pkg), 0xFF2C2C30));
        return a;
    }

    private static String label(PackageManager pm, String pkg) {
        try { return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); } catch (Exception e) { return pkg; }
    }

    /** App có giao diện TV (mục Ứng dụng của Google TV): ứng viên lên dock lần đầu. */
    private static Set<String> tvApps(PackageManager pm) {
        Set<String> s = new HashSet<>();
        for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0))
            s.add(r.activityInfo.packageName);
        return s;
    }

    // tiện ích hệ thống không nên chiếm chỗ trên dock lần đầu (vẫn có trong bảng Tất cả ứng dụng)
    private static final Set<String> SYSTEM_TOOLS = new HashSet<>(Arrays.asList(
            "com.android.tv.settings", "com.google.android.tv", "com.google.android.katniss",
            "com.google.android.tvrecommendations", "com.google.android.backdrop", "com.android.documentsui",
            "com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher", "com.spocky.projengmenu",
            "com.google.android.apps.tv.dreamx", "com.android.providers.downloads.ui"));

    /** Tiện ích hệ thống, hoặc một launcher khác (có màn hình Home riêng): không tự đưa lên dock. */
    private static boolean hidden(PackageManager pm, String pkg) {
        if (SYSTEM_TOOLS.contains(pkg)) return true;
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(pkg);
        return !pm.queryIntentActivities(home, 0).isEmpty();
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
