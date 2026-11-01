package vn.glass.home;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Đọc nội dung các app đẩy lên hệ thống (hàng "Xem tiếp" và hàng kênh) qua TvProvider.
// Đây là nguồn ảnh cho nền và kệ trên (top shelf) của từng app.
final class Shelf {
    static final class Item {
        String pkg, title, sub, art, intent;
        float progress;
        long engaged;
        boolean continueWatching;
    }

    private static final String WATCH_NEXT = "content://android.media.tv/watch_next_program";
    private static final String PREVIEW = "content://android.media.tv/preview_program";

    /** Khoá chứa "Xem tiếp" chung của mọi app, dùng khi app đang chọn không có nội dung. */
    static final String ALL = "*";

    /** Theo package: "Xem tiếp" (mới xem nhất trước, gồm cả phim đã nhớ) rồi tới các mục gợi ý. */
    static Map<String, List<Item>> load(Context ctx) {
        ContentResolver cr = ctx.getContentResolver();
        Map<String, List<Item>> out = new HashMap<>();
        List<Item> wn = remember(ctx, query(cr, WATCH_NEXT));
        wn.sort((a, b) -> Long.compare(b.engaged, a.engaged));
        for (Item i : wn) { i.continueWatching = true; add(out, i.pkg, i); add(out, ALL, i); }
        List<Item> pv = query(cr, PREVIEW);
        for (Item i : pv) add(out, i.pkg, i);
        // ghi số mục mỗi app cung cấp để soi khi hàng nội dung thiếu ô (adb logcat -s GlassShelf)
        Map<String, int[]> n = new HashMap<>();
        for (Item i : wn) { int[] c = n.computeIfAbsent(String.valueOf(i.pkg), k -> new int[2]); c[0]++; }
        for (Item i : pv) { int[] c = n.computeIfAbsent(String.valueOf(i.pkg), k -> new int[2]); c[1]++; }
        for (Map.Entry<String, int[]> e : n.entrySet())
            android.util.Log.i("GlassShelf", e.getKey() + " xem_tiep=" + e.getValue()[0] + " goi_y=" + e.getValue()[1]);
        // kênh của từng app (app chỉ đăng nội dung lên kênh đang được bật hiển thị), để soi khi app không có nội dung
        try (Cursor c = cr.query(Uri.parse("content://android.media.tv/channel"),
                new String[]{"package_name", "display_name", "browsable", "type"}, null, null, null)) {
            if (c != null) while (c.moveToNext())
                android.util.Log.i("GlassShelf", "kenh " + c.getString(0) + " '" + c.getString(1) + "' browsable=" + c.getInt(2) + " " + c.getString(3));
        } catch (Exception e) {
            android.util.Log.i("GlassShelf", "kenh: khong doc duoc " + e);
        }
        // một đường dẫn ảnh mẫu mỗi app, để soi khi ô nội dung trống ảnh
        Set<String> logged = new HashSet<>();
        for (List<Item> l : new List[]{wn, pv}) for (Item i : l)
            if (i.art != null && logged.add(String.valueOf(i.pkg))) android.util.Log.i("GlassShelf", "anh " + i.pkg + " " + i.art);
        return out;
    }

    // ---------- nhớ "Xem tiếp" ----------
    // App như Netflix chỉ trả vài phim xem dở gần nhất và tự đẩy phim cũ ra khi xem phim mới. Launcher nhớ lại
    // các phim đã từng thấy (tối đa KEEP mỗi app) để hàng của app đó dần đủ ô, toàn nội dung của chính app.
    private static final int KEEP = 8;
    private static final String MEMORY = "xem_tiep.json";

    private static List<Item> remember(Context ctx, List<Item> fresh) {
        File f = new File(ctx.getFilesDir(), MEMORY);
        List<Item> all = new ArrayList<>(fresh);
        Set<String> seen = new HashSet<>();
        for (Item i : fresh) seen.add(idOf(i));
        try {
            if (f.exists()) {
                JSONArray a = new JSONArray(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"));
                for (int k = 0; k < a.length(); k++) {
                    JSONObject o = a.getJSONObject(k);
                    Item i = new Item();
                    i.pkg = o.optString("pkg", null); i.title = o.optString("title", null);
                    i.sub = o.optString("sub", null); i.art = o.optString("art", null);
                    i.intent = o.optString("intent", null);
                    i.progress = (float) o.optDouble("progress", 0); i.engaged = o.optLong("engaged", 0);
                    if (i.pkg != null && i.art != null && seen.add(idOf(i))) all.add(i);
                }
            }
        } catch (Exception ignored) {}
        // mỗi app giữ KEEP phim mới xem nhất (phim app còn trả về luôn mới hơn phim đã bị đẩy ra)
        all.sort((x, y) -> Long.compare(y.engaged, x.engaged));
        Map<String, Integer> per = new HashMap<>();
        List<Item> kept = new ArrayList<>();
        JSONArray save = new JSONArray();
        for (Item i : all) {
            int n = per.merge(String.valueOf(i.pkg), 1, Integer::sum);
            if (n > KEEP) continue;
            kept.add(i);
            try {
                save.put(new JSONObject().put("pkg", i.pkg).put("title", i.title).put("sub", i.sub)
                        .put("art", i.art).put("intent", i.intent).put("progress", i.progress).put("engaged", i.engaged));
            } catch (Exception ignored) {}
        }
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(save.toString().getBytes("UTF-8")); } catch (Exception ignored) {}
        return kept;
    }

    /** Cùng một phim: cùng app + cùng tên (đường dẫn mở phim có thể đổi theo phiên). */
    private static String idOf(Item i) { return i.pkg + "|" + (i.title != null ? i.title : i.art); }

    private static void add(Map<String, List<Item>> m, String key, Item i) {
        if (key == null || i.art == null) return;
        List<Item> l = m.get(key);
        if (l == null) { l = new ArrayList<>(); m.put(key, l); }
        if (l.size() < 12) l.add(i);
    }

    private static List<Item> query(ContentResolver cr, String uri) {
        List<Item> out = new ArrayList<>();
        try (Cursor c = cr.query(Uri.parse(uri), null, null, null, null)) {
            if (c == null) return out;
            while (c.moveToNext()) {
                Item i = new Item();
                i.pkg = str(c, "package_name");
                i.title = str(c, "title");
                String ep = str(c, "episode_title");
                String desc = str(c, "short_description");
                i.sub = ep != null ? ep : desc;
                String poster = str(c, "poster_art_uri");
                String thumb = str(c, "thumbnail_uri");
                i.art = poster != null ? poster : thumb;
                i.intent = str(c, "intent_uri");
                long pos = num(c, "last_playback_position_millis");
                long dur = num(c, "duration_millis");
                i.progress = dur > 0 ? Math.min(1f, (float) pos / dur) : 0f;
                i.engaged = num(c, "last_engagement_time_utc_millis");
                out.add(i);
            }
        } catch (Exception e) {
            // không có quyền đọc, hoặc box không có TvProvider: dùng nền dự phòng
        }
        return out;
    }

    private static String str(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        if (i < 0 || c.isNull(i)) return null;
        String s = c.getString(i);
        return s == null || s.isEmpty() ? null : s;
    }

    private static long num(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? 0 : c.getLong(i);
    }

    private Shelf() {}
}
