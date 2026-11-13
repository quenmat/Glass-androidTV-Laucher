package vn.glass.home;

import android.content.Context;
import android.content.SharedPreferences;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lịch phát sóng cho ô thông tin kênh: nguồn XMLTV ghi ở đầu kenh.m3u (url-tvg, vd VNEPG epg.io.vn). Tải ở nền vài
 * tiếng một lần (máy chủ nén gzip ~0,7MB, hỏi kèm ETag nên không đổi thì gần như không tốn gì), chỉ giữ kênh có trong
 * danh sách (tvg-id) và khoảng từ 2 giờ trước tới 30 giờ sau, ghi gọn ra tv/epg.json. Chọn kênh thì đọc từ bộ nhớ: tức thì.
 */
final class Epg {
    static final class Prog {
        final long start, stop;
        final String title, desc;   // desc: tóm tắt chương trình nếu nguồn có (ít khi có), null nếu không
        Prog(long start, long stop, String title, String desc) { this.start = start; this.stop = stop; this.title = title; this.desc = desc; }
    }

    private static final long REFRESH_MS = 4 * 60 * 60_000L;
    private static volatile Map<String, List<Prog>> data;

    /** Lịch trong bộ nhớ (null nếu chưa nạp); nạp từ tệp gọi ở luồng nền. */
    static Map<String, List<Prog>> cached() { return data; }

    static synchronized Map<String, List<Prog>> load(Context c) {
        if (data != null) return data;
        Map<String, List<Prog>> m = new HashMap<>();
        try {
            File f = new File(Tv.dir(c), "epg.json");
            if (f.isFile()) {
                org.json.JSONObject o = new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                    String id = it.next();
                    org.json.JSONArray a = o.getJSONArray(id);
                    List<Prog> l = new ArrayList<>(a.length());
                    for (int i = 0; i < a.length(); i++) {
                        org.json.JSONArray p = a.getJSONArray(i);
                        l.add(new Prog(p.getLong(0), p.getLong(1), p.getString(2), p.length() > 3 ? p.optString(3, null) : null));
                    }
                    m.put(id, l);
                }
            }
        } catch (Exception ignored) {}
        data = m;
        return m;
    }

    /** Chương trình đang chiếu và chương trình kế (phần tử null nếu không có). */
    static Prog[] nowNext(String id, long now) {
        Map<String, List<Prog>> m = data;
        List<Prog> l = m == null || id == null ? null : m.get(id);
        if (l == null) return new Prog[]{null, null};
        for (int i = 0; i < l.size(); i++) {
            Prog p = l.get(i);
            if (p.start <= now && now < p.stop) return new Prog[]{p, i + 1 < l.size() ? l.get(i + 1) : null};
            if (p.start > now) return new Prog[]{null, p};
        }
        return new Prog[]{null, null};
    }

    /** Lịch cũ hơn 4 giờ (hoặc chưa có) thì tải lại; gọi ở luồng nền. Trả về true nếu có lịch mới. */
    static boolean refreshIfStale(Context c) {
        File d = Tv.dir(c);
        if (d == null) return false;
        File out = new File(d, "epg.json");
        SharedPreferences p = c.getSharedPreferences("glass", Context.MODE_PRIVATE);
        long last = p.getLong("epg_checked", 0);
        if (out.isFile() && System.currentTimeMillis() - last < REFRESH_MS) return false;
        String url = Tv.epgUrl(c);
        if (url == null) return false;
        Set<String> want = new HashSet<>();
        for (Tv.Channel ch : Tv.load(c)) if (ch.tvgId != null) want.add(ch.tvgId);
        if (want.isEmpty()) return false;
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new URL(url).openConnection();
            h.setConnectTimeout(8000); h.setReadTimeout(30000);
            h.setRequestProperty("User-Agent", "GlassHome/1.0");
            String etag = p.getString("epg_etag", null);
            if (etag != null && out.isFile()) h.setRequestProperty("If-None-Match", etag);
            int code = h.getResponseCode();
            if (code == 304) { p.edit().putLong("epg_checked", System.currentTimeMillis()).apply(); return false; }
            if (code != 200) return false;
            long now = System.currentTimeMillis(), from = now - 2 * 3600_000L, to = now + 30 * 3600_000L;
            Map<String, List<Prog>> m = new HashMap<>();
            XmlPullParser x = android.util.Xml.newPullParser();
            x.setInput(h.getInputStream(), "UTF-8");   // HttpURLConnection tự giải nén gzip
            String id = null; long s = 0, e = 0; boolean inTitle = false, inDesc = false; String title = null, desc = null;
            for (int ev = x.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = x.next()) {
                if (ev == XmlPullParser.START_TAG) {
                    if ("programme".equals(x.getName())) {
                        id = x.getAttributeValue(null, "channel");
                        if (!want.contains(id)) { id = null; continue; }
                        s = time(x.getAttributeValue(null, "start")); e = time(x.getAttributeValue(null, "stop"));
                        title = null; desc = null;
                    } else if (id != null && "title".equals(x.getName()) && title == null) inTitle = true;
                    else if (id != null && "desc".equals(x.getName()) && desc == null) inDesc = true;
                } else if (ev == XmlPullParser.TEXT && inTitle) {
                    title = x.getText().trim();
                } else if (ev == XmlPullParser.TEXT && inDesc) {
                    desc = x.getText().trim();
                } else if (ev == XmlPullParser.END_TAG) {
                    if ("title".equals(x.getName())) inTitle = false;
                    else if ("desc".equals(x.getName())) inDesc = false;
                    else if ("programme".equals(x.getName()) && id != null) {
                        if (e > from && s < to && title != null && !title.isEmpty()) {
                            List<Prog> l = m.get(id);
                            if (l == null) m.put(id, l = new ArrayList<>());
                            l.add(new Prog(s, e, clean(title), desc == null || desc.isEmpty() ? null : desc));
                        }
                        id = null;
                    }
                }
            }
            org.json.JSONObject o = new org.json.JSONObject();
            for (Map.Entry<String, List<Prog>> en : m.entrySet()) {
                en.getValue().sort((a, b) -> Long.compare(a.start, b.start));
                org.json.JSONArray a = new org.json.JSONArray();
                for (Prog g : en.getValue()) {
                    org.json.JSONArray r = new org.json.JSONArray().put(g.start).put(g.stop).put(g.title);
                    if (g.desc != null) r.put(g.desc);
                    a.put(r);
                }
                o.put(en.getKey(), a);
            }
            File tmp = new File(d, "epg.json.tmp");
            java.nio.file.Files.write(tmp.toPath(), o.toString().getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(out)) { out.delete(); tmp.renameTo(out); }
            p.edit().putLong("epg_checked", System.currentTimeMillis()).putString("epg_etag", h.getHeaderField("ETag")).apply();
            data = m;
            return true;
        } catch (Exception ex) {
            return false;
        } finally { if (h != null) h.disconnect(); }
    }

    /** Nguồn ghi kiểu "Chuyên mục: Tên" mà tên lại lặp chuyên mục ("Vận may gọi tên ai: Vận may gọi tên ai ? Tập 11"): bỏ phần lặp. */
    static String clean(String t) {
        int k = t.indexOf(": ");
        if (k > 0) {
            String head = t.substring(0, k), rest = t.substring(k + 2);
            if (rest.toLowerCase(java.util.Locale.ROOT).startsWith(head.toLowerCase(java.util.Locale.ROOT))) return rest;
        }
        return t;
    }

    /** "20261112210000 +0700" -> mili giây. */
    private static long time(String v) {
        if (v == null) return 0;
        try { return new java.text.SimpleDateFormat("yyyyMMddHHmmss Z", java.util.Locale.ROOT).parse(v.trim()).getTime(); }
        catch (Exception e) {
            try { return new java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.ROOT).parse(v.trim().substring(0, 14)).getTime(); }
            catch (Exception e2) { return 0; }
        }
    }

    private Epg() {}
}
