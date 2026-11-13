package vn.glass.home;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Truyền hình qua IPTV: danh sách kênh là tệp kenh.m3u (kèm ảnh <tên>.png) trong Android/data/vn.glass.home/files/tv/,
 * do chủ máy tự đặt (địa chỉ kênh là luồng udp://@... của nhà mạng, chỉ xem được trong mạng nhà, không đưa lên repo).
 * Launcher hiện kênh; bấm kênh thì trình phát mở thẳng luồng: Just Player nếu có (nhẹ, vào hình ~1 giây), không thì
 * VLC (~2 giây, phải để tăng tốc phần cứng Đầy đủ thì chip của box mới tự khử xen kẽ). Bấm Back là trình phát đóng,
 * về đúng chỗ đang đứng trên launcher. Cả hai phát ở 60Hz: luồng truyền hình không ghi tốc độ khung hình nên trình
 * phát không biết để chuyển 50Hz (chủ máy đã chọn chấp nhận).
 */
final class Tv {
    // trình phát theo thứ tự ưu tiên: gói + màn phát (gọi thẳng màn phát nên nhận cả địa chỉ udp://)
    private static final String[][] PLAYERS = {
            {"com.brouken.player", "com.brouken.player.PlayerActivity"},
            {"org.videolan.vlc", "org.videolan.vlc.gui.video.VideoPlayerActivity"}};

    /** Trình phát đã cài đầu tiên theo thứ tự ưu tiên, null nếu chưa có cái nào. */
    static String[] player(java.util.Set<String> installed) {
        for (String[] p : PLAYERS) if (installed.contains(p[0])) return p;
        return null;
    }

    static final class Channel {
        String name, group, url, art;   // art: đường dẫn ảnh kênh trên box (null nếu chưa có)
        String tvgId;                   // mã kênh trong lịch phát sóng (Epg), null nếu không có
        String desc;                    // một dòng mô tả kênh (glass-desc), null nếu không có
    }

    static File dir(Context c) { return c.getExternalFilesDir("tv"); }

    /** Có đủ điều kiện hiện ô Truyền hình: có trình phát và có danh sách kênh. */
    static boolean available(Context c, java.util.Set<String> installed) {
        File d = dir(c);
        return player(installed) != null && d != null && new File(d, "kenh.m3u").isFile();
    }

    /** Kênh theo thứ tự trong kenh.m3u; ảnh lấy theo tvg-logo nếu có, không thì <tên-không-dấu>.png. */
    static List<Channel> load(Context c) {
        List<Channel> out = new ArrayList<>();
        File d = dir(c);
        if (d == null) return out;
        try {
            String m3u = new String(java.nio.file.Files.readAllBytes(new File(d, "kenh.m3u").toPath()), StandardCharsets.UTF_8);
            Channel ch = null;
            for (String line : m3u.split("\n")) {
                line = line.trim();
                if (line.startsWith("#EXTINF")) {
                    int comma = line.lastIndexOf(',');
                    if (comma < 0) { ch = null; continue; }
                    ch = new Channel();
                    ch.name = line.substring(comma + 1).trim();
                    ch.group = attr(line, "group-title");
                    ch.tvgId = attr(line, "tvg-id");
                    ch.desc = attr(line, "glass-desc");
                    String logo = attr(line, "tvg-logo");
                    File art = new File(d, logo != null && !logo.isEmpty() ? logo : slug(ch.name) + ".png");
                    if (art.isFile()) ch.art = art.getPath();
                } else if (ch != null && !line.isEmpty() && !line.startsWith("#")) {
                    ch.url = line;
                    out.add(ch);
                    ch = null;
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** Nguồn lịch phát sóng ghi ở dòng đầu kenh.m3u (#EXTM3U url-tvg="..."), null nếu không có. */
    static String epgUrl(Context c) {
        File d = dir(c);
        if (d == null) return null;
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(new File(d, "kenh.m3u")))) {
            String first = r.readLine();
            return first == null ? null : attr(first, "url-tvg");
        } catch (Exception e) { return null; }
    }

    static Channel find(Context c, String name) {
        for (Channel ch : load(c)) if (ch.name.equals(name)) return ch;
        return null;
    }

    /** Kênh xem gần đây lên trước (tối đa n), rồi các kênh còn lại theo thứ tự danh sách. */
    static List<Channel> recentFirst(Context c, List<Channel> all, int n) {
        List<Channel> out = new ArrayList<>();
        for (String name : prefs(c).getString("tv_recent", "").split("\n")) {
            for (Channel ch : all) if (ch.name.equals(name) && !out.contains(ch)) out.add(ch);
        }
        for (Channel ch : all) { if (out.size() >= n) break; if (!out.contains(ch)) out.add(ch); }
        return out.size() > n ? out.subList(0, n) : out;
    }

    static String lastWatched(Context c) {
        String s = prefs(c).getString("tv_recent", "");
        int n = s.indexOf('\n');
        return n < 0 ? s : s.substring(0, n);
    }

    static void remember(Context c, String name) {
        List<String> l = new ArrayList<>();
        l.add(name);
        for (String s : prefs(c).getString("tv_recent", "").split("\n")) if (!s.isEmpty() && !s.equals(name) && l.size() < 8) l.add(s);
        prefs(c).edit().putString("tv_recent", String.join("\n", l)).apply();
    }

    /** Trình phát mở thẳng luồng của kênh; trả về false nếu không mở được. */
    static boolean play(Context c, Channel ch, Bundle anim) {
        if (ch == null || ch.url == null) return false;
        java.util.Set<String> installed = new java.util.HashSet<>();
        for (String[] p : PLAYERS) {
            try { c.getPackageManager().getPackageInfo(p[0], 0); installed.add(p[0]); } catch (Exception ignored) {}
        }
        String[] p = player(installed);
        if (p == null) return false;
        remember(c, ch.name);
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(android.net.Uri.parse(ch.url), "video/*")
                .setClassName(p[0], p[1])
                .putExtra("title", ch.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { c.startActivity(i, anim); return true; } catch (Exception e) { return false; }
    }

    private static String attr(String line, String key) {
        int k = line.indexOf(key + "=\"");
        if (k < 0) return null;
        int s = k + key.length() + 2, e = line.indexOf('"', s);
        return e < 0 ? null : line.substring(s, e);
    }

    /** Tên kênh -> tên tệp ảnh: thường, bỏ dấu, đ->d, ký tự khác thành "-" (giống tools/make_tv.py). */
    static String slug(String name) {
        String s = name.toLowerCase(java.util.Locale.ROOT).replace("đ", "d");
        s = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return s.replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("glass", Context.MODE_PRIVATE); }

    private Tv() {}
}
