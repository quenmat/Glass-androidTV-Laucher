package vn.glass.home;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Hàng YouTube: video của vài kênh chọn sẵn đứng trước gợi ý chung của app; kênh đang phát trực tiếp luôn lên đầu.
// Danh sách kênh và khoá API chỉ nằm trên box (Android/data/vn.glass.home/files/youtube/):
//   kenh.json  [{"id":"UC...","ten":"...","lay":"moi"|"gan_nhat"|"live","so":3,"luon":true}]
//     moi: vài video mới nhất (bỏ Shorts) | gan_nhat: đang live thì live, không thì 1 video mới nhất | live: chỉ khi đang live
//     luon: kênh luôn giữ một ô trong hàng (đứng ngay sau các ô live)
//   Xếp hàng: mọi video đang live trước, rồi ô của kênh "luon", rồi video thường mới nhất của mọi kênh.
//   key.txt    khoá YouTube Data API v3
// Launcher gọi 10 phút một lượt; mỗi lượt tốn ~1 đơn vị hạn mức mỗi kênh + 1 (hạn mức miễn phí 10.000/ngày).
final class YtFeed {
    static final String PKG = "com.google.android.youtube.tv";
    private static final String API = "https://www.googleapis.com/youtube/v3/";
    private static final String TAG = "GlassYt";

    private static volatile List<Shelf.Item> items = Collections.emptyList();
    private static String lastSig = "";

    /** Danh sách đang có (live trước), đọc được từ luồng giao diện. */
    static List<Shelf.Item> items() { return items; }

    /** Nạp hàng đã lưu lúc launcher vừa khởi động (chưa có lượt tải nào); lượt tải đầu sẽ thay. */
    static void restore(List<Shelf.Item> l) {
        synchronized (YtFeed.class) { if (items.isEmpty() && !l.isEmpty()) items = Collections.unmodifiableList(l); }
    }

    private static final class Ch { String id, name, mode; int n; boolean always; }
    private static final class Vid { String id, title, art, channel; boolean live, upcoming, isShort; long published; }

    /** Tải lại (gọi ở luồng nền). Trả true khi danh sách đổi, để hàng vẽ lại. */
    static boolean refresh(Context ctx, int row) {
        File dir = new File(ctx.getExternalFilesDir(null), "youtube");
        List<Ch> chans = channels(new File(dir, "kenh.json"));
        String key = readText(new File(dir, "key.txt"));
        if (chans.isEmpty() || key == null) { return set(Collections.emptyList()); }
        key = key.trim();
        try {
            // 1. vài mục mới nhất trong danh sách tải lên của từng kênh (UC... -> UU...)
            Map<String, List<String>> idsOf = new HashMap<>();
            Map<String, String> liveIdOf = new HashMap<>();
            Set<String> all = new LinkedHashSet<>();
            for (Ch c : chans) {
                int want = "moi".equals(c.mode) ? Math.max(c.n, 1) * 3 + 2 : 5;
                JSONObject r = json(API + "playlistItems?part=contentDetails&maxResults=" + Math.min(want, 15)
                        + "&playlistId=UU" + c.id.substring(2) + "&key=" + enc(key));
                List<String> ids = new ArrayList<>();
                JSONArray a = r.optJSONArray("items");
                for (int i = 0; a != null && i < a.length(); i++) {
                    String v = a.getJSONObject(i).getJSONObject("contentDetails").optString("videoId", null);
                    if (v != null) { ids.add(v); all.add(v); }
                }
                idsOf.put(c.id, ids);
                // buổi live đặt lịch từ trước nằm sâu dưới các Shorts mới đăng, danh sách trên không với tới: hỏi
                // thẳng trang /live của kênh (không tốn hạn mức); bước 2 vẫn xác nhận video đó đang phát thật
                String lv = liveNow(c.id);
                if (lv != null) { liveIdOf.put(c.id, lv); all.add(lv); }
            }
            // 2. chi tiết mọi video trong một lần gọi: tên, ảnh, đang live hay không, độ dài
            Map<String, Vid> vids = new HashMap<>();
            List<String> list = new ArrayList<>(all);
            for (int from = 0; from < list.size(); from += 50) {
                String ids = android.text.TextUtils.join(",", list.subList(from, Math.min(list.size(), from + 50)));
                JSONObject r = json(API + "videos?part=snippet,contentDetails&id=" + ids + "&key=" + enc(key));
                JSONArray a = r.optJSONArray("items");
                for (int i = 0; a != null && i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i), sn = o.getJSONObject("snippet");
                    Vid v = new Vid();
                    v.id = o.getString("id");
                    v.title = sn.optString("title", "");
                    v.channel = sn.optString("channelTitle", "");
                    String lb = sn.optString("liveBroadcastContent", "none");
                    v.live = "live".equals(lb);
                    v.upcoming = "upcoming".equals(lb);
                    v.art = art(sn.optJSONObject("thumbnails"));
                    v.published = parseTime(sn.optString("publishedAt", ""));
                    long sec = seconds(o.optJSONObject("contentDetails") == null ? "" : o.getJSONObject("contentDetails").optString("duration", ""));
                    v.isShort = !v.live && sec > 0 && sec <= 180;   // Shorts dài tối đa 3 phút; RSS sẽ chỉnh lại bên dưới
                    vids.put(v.id, v);
                }
            }
            // 3. RSS chính thức đánh dấu Shorts chính xác (đường dẫn /shorts/): dùng nó cho kênh cần lấy video thường
            for (Ch c : chans) {
                if ("live".equals(c.mode)) continue;
                Map<String, Boolean> sh = rssShorts(c.id);
                for (String id : idsOf.get(c.id)) {
                    Vid v = vids.get(id);
                    Boolean s = sh.get(id);
                    if (v != null && s != null && !v.live) v.isShort = s;
                }
            }
            // 4. ghép hàng (đúng số ô của hàng): live trước (chừa một ô cho kênh "luon"), rồi kênh "luon",
            //    rồi video thường của mọi kênh, mới đăng nhất trước
            List<Vid> live = new ArrayList<>(), pinned = new ArrayList<>(), rest = new ArrayList<>();
            for (Ch c : chans) {
                boolean hasLive = false;
                Set<String> cand = new LinkedHashSet<>(idsOf.get(c.id));
                if (liveIdOf.containsKey(c.id)) cand.add(liveIdOf.get(c.id));
                for (String id : cand) {
                    Vid v = vids.get(id);
                    if (v != null && v.live) { live.add(v); hasLive = true; }
                }
                if ("live".equals(c.mode) || (hasLive && (c.always || "gan_nhat".equals(c.mode)))) continue;
                int want = "gan_nhat".equals(c.mode) ? 1 : Math.max(c.n, 1), got = 0;
                for (String id : idsOf.get(c.id)) {
                    Vid v = vids.get(id);
                    if (v == null || v.live || v.upcoming || v.isShort) continue;
                    (c.always && got == 0 ? pinned : rest).add(v);
                    if (++got >= want) break;
                }
            }
            live.sort((x, y) -> Long.compare(y.published, x.published));
            rest.sort((x, y) -> Long.compare(y.published, x.published));
            List<Shelf.Item> out = new ArrayList<>();
            int liveRoom = Math.max(0, row - pinned.size());
            for (Vid v : live) if (out.size() < liveRoom) out.add(item(v));
            for (Vid v : pinned) if (out.size() < row) out.add(item(v));
            for (Vid v : rest) if (out.size() < row) out.add(item(v));
            android.util.Log.i(TAG, "live=" + live.size() + " luon=" + pinned.size() + " video=" + rest.size() + " o=" + out.size());
            return set(out);
        } catch (Exception e) {
            // mất mạng, hết hạn mức, khoá sai: giữ danh sách cũ
            android.util.Log.i(TAG, "loi " + e);
            return false;
        }
    }

    private static boolean set(List<Shelf.Item> out) {
        StringBuilder sig = new StringBuilder();
        for (Shelf.Item i : out) sig.append(i.intent).append(i.live).append(i.title).append('|');
        synchronized (YtFeed.class) {
            if (sig.toString().equals(lastSig)) return false;
            lastSig = sig.toString();
            items = Collections.unmodifiableList(out);
            return true;
        }
    }

    private static Shelf.Item item(Vid v) {
        Shelf.Item i = new Shelf.Item();
        i.pkg = PKG;
        i.title = v.title;
        i.sub = v.channel;
        i.art = v.art;
        i.live = v.live;
        i.intent = "intent://www.youtube.com/watch?v=" + v.id
                + "#Intent;scheme=https;action=android.intent.action.VIEW;package=" + PKG + ";end";
        return i;
    }

    /** Ảnh 16:9: maxres nếu có; không thì high (480x360 có viền đen trên dưới, ô thumb cắt giữa vừa khít phần 16:9). */
    private static String art(JSONObject t) {
        if (t == null) return null;
        for (String k : new String[]{"maxres", "high", "medium"}) {
            JSONObject o = t.optJSONObject(k);
            if (o != null && o.optString("url", null) != null) return o.optString("url");
        }
        return null;
    }

    private static Map<String, Boolean> rssShorts(String channelId) {
        Map<String, Boolean> m = new HashMap<>();
        try {
            String x = get("https://www.youtube.com/feeds/videos.xml?channel_id=" + channelId);
            Matcher mm = Pattern.compile("href=\"https://www\\.youtube\\.com/(shorts/|watch\\?v=)([\\w-]{11})\"").matcher(x);
            while (mm.find()) m.put(mm.group(2), mm.group(1).startsWith("shorts"));
        } catch (Exception ignored) {}
        return m;
    }

    private static final Pattern LIVE_LINK =
            Pattern.compile("<link rel=\"canonical\" href=\"([^\"]+)\"");
    private static final Pattern WATCH_ID = Pattern.compile("^https://www\\.youtube\\.com/watch\\?v=([\\w-]{11})$");

    /** Video kênh đang phát trực tiếp (hoặc sắp phát), null nếu không có. Trang /live bản di động đặt đường dẫn
     *  chuẩn ngay đầu trang (vài KB đầu), nên chỉ đọc tối đa 32 KB rồi ngắt, khỏi tải cả trang ~1 MB. */
    private static String liveNow(String channelId) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("https://m.youtube.com/channel/" + channelId + "/live").openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Mobile");
            c.setRequestProperty("Accept-Language", "vi");
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int k;
            while (b.size() < 32 * 1024 && (k = in.read(buf)) > 0) {
                b.write(buf, 0, k);
                Matcher m = LIVE_LINK.matcher(b.toString("UTF-8"));
                if (m.find()) {   // trỏ tới một video: đang live (hoặc sắp); trỏ về kênh: không live
                    Matcher w = WATCH_ID.matcher(m.group(1));
                    return w.matches() ? w.group(1) : null;
                }
            }
        } catch (Exception ignored) {
        } finally { if (c != null) c.disconnect(); }
        return null;
    }

    private static List<Ch> channels(File f) {
        List<Ch> out = new ArrayList<>();
        String s = readText(f);
        if (s == null) return out;
        try {
            JSONArray a = new JSONArray(s);
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Ch c = new Ch();
                c.id = o.optString("id", "");
                c.name = o.optString("ten", "");
                c.mode = o.optString("lay", "moi");
                c.n = o.optInt("so", 1);
                c.always = o.optBoolean("luon", false);
                if (c.id.startsWith("UC") && c.id.length() == 24 && seen.add(c.id)) out.add(c);
            }
        } catch (Exception e) { android.util.Log.i(TAG, "kenh.json loi " + e); }
        return out;
    }

    private static long seconds(String iso) {
        Matcher m = Pattern.compile("P(?:(\\d+)D)?T?(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?").matcher(iso);
        if (!m.matches()) return 0;
        long d = n(m.group(1)), h = n(m.group(2)), mi = n(m.group(3)), s = n(m.group(4));
        return ((d * 24 + h) * 60 + mi) * 60 + s;
    }

    private static long n(String s) { return s == null ? 0 : Long.parseLong(s); }

    private static long parseTime(String s) {
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return f.parse(s.length() >= 19 ? s.substring(0, 19) : s).getTime();
        } catch (Exception e) { return 0; }
    }

    private static String readText(File f) {
        try { return f.exists() ? new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8") : null; }
        catch (Exception e) { return null; }
    }

    private static String enc(String s) throws Exception { return URLEncoder.encode(s, "UTF-8"); }

    private static JSONObject json(String u) throws Exception { return new JSONObject(get(u)); }

    private static String get(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(8000);
        try {
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int k;
            while (in != null && (k = in.read(buf)) > 0) b.write(buf, 0, k);
            String s = b.toString("UTF-8");
            if (code >= 400) throw new java.io.IOException("HTTP " + code + " " + (s.length() > 200 ? s.substring(0, 200) : s));
            return s;
        } finally { c.disconnect(); }
    }

    private YtFeed() {}
}
