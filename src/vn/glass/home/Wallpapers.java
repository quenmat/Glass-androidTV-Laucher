package vn.glass.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

// Hai bộ ảnh: nền màn hình chính ("home") và màn hình chờ ("lock"). Mỗi bộ có ảnh kèm sẵn trong APK
// (assets/bg/home, assets/bg/lock) và ảnh của người dùng (gửi từ điện thoại hoặc đồng bộ từ máy tính,
// nằm ở Android/data/vn.glass.home/files/bg/chung và bg/khoa). Cài đặt chọn dùng bộ nào, đổi ảnh bao lâu.
final class Wallpapers {
    static final String ASSET = "asset:///";
    static final String HOME = "home", LOCK = "lock";
    static final String BUILTIN = "builtin", MINE = "mine", MIX = "mix";

    private Wallpapers() {}

    /** Thư mục ảnh của người dùng (giữ tên cũ để ảnh đã đồng bộ bằng tools/sync_wallpapers.py vẫn dùng được). */
    static File userDir(Context c, String set) {
        File d = c.getExternalFilesDir(HOME.equals(set) ? "bg/chung" : "bg/khoa");
        if (d != null) d.mkdirs();
        return d;
    }

    static List<File> user(Context c, String set) {
        List<File> out = new ArrayList<>();
        File d = userDir(c, set);
        File[] in = d == null ? null : d.listFiles(f -> {
            String n = f.getName().toLowerCase(java.util.Locale.ROOT);
            return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp");
        });
        if (in != null) out.addAll(Arrays.asList(in));
        Collections.sort(out);
        return out;
    }

    static List<String> builtin(Context c, String set) {
        List<String> out = new ArrayList<>();
        try {
            String[] names = c.getAssets().list("bg/" + set);
            if (names != null) {
                Arrays.sort(names);
                for (String n : names) out.add(ASSET + "bg/" + set + "/" + n);
            }
        } catch (Exception ignored) {}
        return out;
    }

    static String source(Context c, String set) { return prefs(c).getString(set + "_src", MIX); }

    static void setSource(Context c, String set, String src) { prefs(c).edit().putString(set + "_src", src).apply(); }

    /** Danh sách ảnh đang xoay vòng theo nguồn đã chọn; chọn "ảnh của bạn" mà chưa có ảnh nào thì dùng ảnh kèm sẵn. */
    static List<String> active(Context c, String set) {
        String src = source(c, set);
        List<String> built = builtin(c, set), mine = new ArrayList<>();
        for (File f : user(c, set)) mine.add(Uri.fromFile(f).toString());
        if (BUILTIN.equals(src) || mine.isEmpty()) return built;
        if (MINE.equals(src)) return mine;
        return interleave(mine, built);   // trộn: xen kẽ để hai bộ đều được hiện đều đặn
    }

    private static List<String> interleave(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>();
        int n = Math.max(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            if (i < a.size()) out.add(a.get(i));
            if (i < b.size()) out.add(b.get(i));
        }
        return out;
    }

    /** Phút đổi ảnh: nền 10 phút, màn hình chờ 15 phút (mặc định). */
    static int everyMin(Context c, String set) { return prefs(c).getInt(set + "_every", HOME.equals(set) ? 10 : 15); }

    static void setEveryMin(Context c, String set, int m) { prefs(c).edit().putInt(set + "_every", m).apply(); }

    /** Phút đứng yên trước khi bật màn hình chờ; 0 là không bật. */
    static int saverAfterMin(Context c) { return prefs(c).getInt("saver_after", 5); }

    static void setSaverAfterMin(Context c, int m) { prefs(c).edit().putInt("saver_after", m).apply(); }

    /** Mã đội yêu thích (mã cố định của Premier League), mặc định 1 = Manchester United. */
    static int favTeam(Context c) { return prefs(c).getInt("fav_team", 1); }

    static void setFavTeam(Context c, int code) { prefs(c).edit().putInt("fav_team", code).apply(); }

    /** btc, eth, gold, epl. Ba ô giá trước đây bật/tắt chung ("prices"): chưa chỉnh riêng thì theo cài đặt chung đó. */
    static boolean widget(Context c, String name) {
        boolean legacy = prefs(c).getBoolean("w_prices", true);
        boolean asset = "btc".equals(name) || "eth".equals(name) || "gold".equals(name);
        return prefs(c).getBoolean("w_" + name, !asset || legacy);
    }

    static void setWidget(Context c, String name, boolean on) { prefs(c).edit().putBoolean("w_" + name, on).apply(); }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("glass", Context.MODE_PRIVATE); }
}
