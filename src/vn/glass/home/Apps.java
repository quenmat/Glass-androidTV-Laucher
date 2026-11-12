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
        String cardFile;          // ô game: ảnh ô riêng (trên box), thay cho ảnh trong assets/cards
        String launchUri, launchActivity;   // ô game: mở thẳng file game bằng app giả lập
        final List<String> thumbs = new ArrayList<>();   // ô game: ảnh hiện ở hàng nội dung khi đang chọn ô
        String bgFile;            // ô game: ảnh nền cả màn hình khi đang chọn ô (kiểu PS4)
        boolean tv;               // ô Truyền hình: hàng là các kênh, bấm mở bảng kênh (xem Tv)
        App(String key, String pkg, String label, int color) {
            this.key = key; this.pkg = pkg; this.label = label; this.color = color;
        }
        /** Ô "Apps" cuối dock: không phải app, bấm vào mở bảng tất cả ứng dụng. */
        boolean isAllApps() { return pkg == null; }
    }

    /** Ô vẽ sẵn (logo đẹp, cùng tông với dock) của một app nếu có: tên ảnh trong assets/cards, không có thì null. */
    static String cardKey(String pkg) {
        for (App x : base()) if (x.pkg != null && x.pkg.equals(pkg)) return x.key;
        return CARD_FOR.get(pkg);
    }

    static List<App> base() {
        List<App> a = new ArrayList<>();
        a.add(new App("youtube", "com.google.android.youtube.tv", "YouTube", 0xFF8E1B1B));
        a.add(new App("netflix", "com.netflix.ninja", "Netflix", 0xFF5A0A0E));
        a.add(new App("fptplay", "net.fptplay.ottbox", "FPT Play", 0xFF7A3510));
        a.add(new App("tv360", "com.viettel.tv360.tv", "TV360", 0xFF0B3A66));
        // VieON vào bảng Tất cả ứng dụng; Cài đặt là nút bánh răng ở góc màn hình; HBO Max đã gỡ
        return a;
    }

    /** App cài thêm nhưng có ô vẽ sẵn trong assets/cards (logo đẹp, cùng tông với dock): dùng ô đó thay banner của app. */
    private static final java.util.Map<String, String> CARD_FOR = new java.util.HashMap<>();
    static {
        CARD_FOR.put("com.iqiyi.i18n.tv", "iqiyi");
        CARD_FOR.put("com.vieon.tv", "vieon");
        CARD_FOR.put("com.wbd.stream", "hbo");     // HBO Max bản hiện tại
        CARD_FOR.put("com.hbo.hbonow", "hbo");     // HBO Max bản cũ
    }

    /**
     * Dock thật: các app cố định (chỉ app đã cài) + app đã có trên dock từ trước, rồi ô "Apps" ở cuối.
     * Lần chạy đầu: đưa luôn các app TV đang có lên dock (bỏ app hệ thống như Cài đặt, launcher khác).
     * App cài về sau KHÔNG tự lên dock nữa: nằm trong bảng Apps (cùng Cửa hàng Play). Gỡ app thì ô tự biến mất.
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
        p.edit().putString("extra_pkgs", String.join(",", extra)).apply();

        for (String pkg : extra) a.add(new App(CARD_FOR.get(pkg), pkg, label(pm, pkg), 0xFF2C2C30));
        // Truyền hình (IPTV, phát bằng Just Player hoặc VLC): có trình phát và danh sách kênh thì ô nằm trước ô Apps
        if (Tv.available(ctx, now)) {
            String player = Tv.player(now)[0];
            App t = new App("tv", player, "Truyền hình", 0xFF181616);
            t.tv = true;
            a.add(t);
        }
        a.add(new App("apps", null, "Ứng dụng", 0xFF2C2C30));
        return a;
    }

    /**
     * Ô game: mỗi game là một cặp file trong Android/data/vn.glass.home/files/games/ do chủ máy tự thêm:
     * <tên>.json {"title", "pkg" (app giả lập), "activity" (tuỳ chọn), "uri" (file game), "thumbs" (ảnh cho hàng nội
     * dung, tên file trong cùng thư mục), "bg" (ảnh nền cả màn hình khi chọn ô)} và <tên>.png (ảnh ô 464x290).
     * Game nằm trong bảng Apps (không lên dock); bấm là app giả lập mở thẳng game. Ảnh và game nằm riêng trên box,
     * không đóng vào APK.
     */
    static List<App> games(Context ctx) { return games(ctx, launchable(ctx.getPackageManager())); }

    private static List<App> games(Context ctx, Set<String> installed) {
        List<App> out = new ArrayList<>();
        java.io.File dir = ctx.getExternalFilesDir("games");
        java.io.File[] fs = dir == null ? null : dir.listFiles(f -> f.getName().endsWith(".json"));
        if (fs == null) return out;
        Arrays.sort(fs);
        for (java.io.File f : fs) {
            try {
                org.json.JSONObject o = new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"));
                String pkg = o.getString("pkg");
                if (!installed.contains(pkg)) continue;   // chưa cài app giả lập: không hiện ô bấm không chạy
                App g = new App("game:" + f.getName(), pkg, o.optString("title", pkg), 0xFF2C2C30);
                java.io.File png = new java.io.File(dir, f.getName().replace(".json", ".png"));
                if (png.exists()) g.cardFile = png.getPath();
                g.launchUri = o.getString("uri");
                g.launchActivity = o.optString("activity", null);
                java.io.File bg = new java.io.File(dir, o.optString("bg", ""));
                if (bg.isFile()) g.bgFile = bg.getPath();
                org.json.JSONArray th = o.optJSONArray("thumbs");
                for (int i = 0; th != null && i < th.length(); i++) {
                    java.io.File t = new java.io.File(dir, th.getString(i));
                    if (t.exists()) g.thumbs.add(t.getPath());
                }
                out.add(g);
            } catch (Exception ignored) {}
        }
        return out;
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
