package vn.glass.home;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Hàng FPT Play: các trận Ngoại hạng Anh đang đá và sắp đá, mỗi ô là banner của trận, bấm vào là mở thẳng trang trận
// trong app FPT Play. Lịch đọc từ trang lịch thi đấu công khai (fptplay.vn/lich-thi-dau/ngoai-hang-anh, trang làm sẵn
// dữ liệu cho máy tìm kiếm); banner đọc từ trang sự kiện của từng trận, nhớ lại theo mã trận nên mỗi trận chỉ tải một lần.
// Hiện mọi trận chưa xong của vòng hiện tại (vòng của trận chưa xong sớm nhất); trận xong thì bỏ.
// Xếp: đang đá trước, rồi theo giờ; cùng giờ thì trận có đội yêu thích (thẻ EPL) đứng trước.
final class FptFeed {
    static final String PKG = "net.fptplay.ottbox";
    private static final String SITE = "https://fptplay.vn";
    private static final String TAG = "GlassFpt";
    private static final long MATCH_MS = 2 * 3600_000L + 15 * 60_000L;   // một trận kể cả bù giờ, nghỉ giữa hiệp

    private static volatile List<Shelf.Item> items = Collections.emptyList();
    private static String lastSig = "";
    private static final Map<String, String> posters = new HashMap<>();   // mã trận -> banner (chỉ của các trận đang hiện)

    static List<Shelf.Item> items() { return items; }

    private static final class M { String ev, home, away, homeCode, awayCode, round, slug; long at; boolean live, fav; }

    /** Tải lại (luồng nền). favAbbr: mã 3 chữ của đội yêu thích (vd MUN), có thể null. Trả true khi hàng đổi. */
    static boolean refresh(String favAbbr, int row) {
        try {
            String h = get(SITE + "/lich-thi-dau/ngoai-hang-anh");
            JSONObject data = nextData(h);
            List<JSONObject> raw = new ArrayList<>();
            collect(data.getJSONObject("props").getJSONObject("pageProps").opt("fixturesSrrData"), raw);
            long now = System.currentTimeMillis();
            List<M> ms = new ArrayList<>();
            for (JSONObject o : raw) {
                JSONObject mt = o.optJSONObject("match");
                JSONArray ps = o.optJSONArray("participants");
                if (mt == null || ps == null || ps.length() < 2) continue;
                JSONObject refs = mt.optJSONObject("internalRefs");
                String ev = refs == null ? null : refs.optString("eventId", null);
                if (ev == null || !ev.matches("[0-9a-f]{24}")) continue;
                M m = new M();
                m.ev = ev;
                m.at = vnTime(mt.optString("scheduledAt", ""));
                String st = mt.optString("status", "").toLowerCase(java.util.Locale.ROOT);
                boolean done = st.contains("finish") || st.contains("final") || st.contains("ended") || st.contains("full") || st.contains("complete")
                        || st.contains("cancel") || st.contains("postpon") || !mt.isNull("scoreFinal")
                        || (m.at > 0 && now > m.at + MATCH_MS);
                if (done || m.at == 0) continue;
                boolean started = st.contains("live") || st.contains("progress") || st.contains("play") || st.contains("half");
                m.live = started || now >= m.at;
                JSONObject th = ps.getJSONObject(0).optJSONObject("team"), ta = ps.getJSONObject(1).optJSONObject("team");
                if (th == null || ta == null) continue;
                m.home = th.optString("name", ""); m.away = ta.optString("name", "");
                m.homeCode = th.optString("shortCode", ""); m.awayCode = ta.optString("shortCode", "");
                // app FPT Play chỉ nhận link sự kiện dạng /su-kien/<tên-trận>-<mã> (phải có gạch ngang trước mã);
                // thiếu tên thì không mở được trang trận, launcher rơi về mở app ở màn đang dở
                String sh = th.optString("slug", ""), sa = ta.optString("slug", "");
                m.slug = (sh.isEmpty() || sa.isEmpty() ? "tran-dau" : sh + "-" + sa).replaceAll("[^a-z0-9-]", "");
                JSONObject rd = o.optJSONObject("round");
                m.round = rd != null ? rd.optString("id", "") : mt.optString("roundId", "");
                m.fav = favAbbr != null && (favAbbr.equalsIgnoreCase(m.homeCode) || favAbbr.equalsIgnoreCase(m.awayCode));
                ms.add(m);
            }
            // vòng hiện tại: vòng của trận chưa xong đá sớm nhất; chỉ giữ trận của vòng đó
            String cur = null; long first = Long.MAX_VALUE;
            for (M m : ms) if (m.at < first) { first = m.at; cur = m.round; }
            if (cur != null) { String c = cur; ms.removeIf(m -> !c.equals(m.round)); }
            ms.sort((x, y) -> {
                if (x.live != y.live) return x.live ? -1 : 1;
                if (x.at != y.at) return Long.compare(x.at, y.at);
                return Boolean.compare(y.fav, x.fav);
            });
            List<Shelf.Item> out = new ArrayList<>();
            Map<String, String> keep = new HashMap<>();
            for (M m : ms) {
                if (out.size() >= row) break;
                String poster;
                synchronized (posters) { poster = posters.get(m.ev); }
                if (poster == null) poster = poster(m.ev);
                if (poster == null) continue;   // chưa có banner: bỏ ô, lượt sau thử lại
                keep.put(m.ev, poster);
                Shelf.Item it = new Shelf.Item();
                it.pkg = PKG;
                it.title = "";                  // banner đã in tên hai đội, giờ, vòng: không ghi chữ, không dải tối
                it.art = poster;
                it.live = m.live;
                it.intent = "intent://fptplay.vn/su-kien/" + m.slug + "-" + m.ev
                        + "#Intent;scheme=https;action=android.intent.action.VIEW;package=" + PKG + ";end";
                out.add(it);
            }
            synchronized (posters) { posters.clear(); posters.putAll(keep); }   // trận đã bỏ khỏi hàng: quên banner
            android.util.Log.i(TAG, "tran=" + ms.size() + " o=" + out.size());
            return set(out);
        } catch (Exception e) {
            android.util.Log.i(TAG, "loi " + e);   // mất mạng, trang đổi cấu trúc: giữ hàng cũ
            return false;
        }
    }

    private static boolean set(List<Shelf.Item> out) {
        StringBuilder sig = new StringBuilder();
        for (Shelf.Item i : out) sig.append(i.intent).append(i.live).append('|');
        synchronized (FptFeed.class) {
            if (sig.toString().equals(lastSig)) return false;
            lastSig = sig.toString();
            items = Collections.unmodifiableList(out);
            return true;
        }
    }

    /** Banner 16:9 của trận, lấy ở trang sự kiện (ảnh JPEG cỡ 1080 cho nhẹ việc giải mã). */
    private static String poster(String ev) {
        try {
            JSONObject pp = nextData(get(SITE + "/su-kien/" + ev)).getJSONObject("props").getJSONObject("pageProps");
            String u = pp.optString("ssrPosterUrl", null);
            if (u == null) return null;
            return u.replace("fmt=webp", "fmt=jpg");
        } catch (Exception e) {
            android.util.Log.i(TAG, "banner loi " + e);
            return null;
        }
    }

    /** Mọi mục trận ("matches") ở bất kỳ độ sâu nào của dữ liệu trang. */
    private static void collect(Object o, List<JSONObject> out) {
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            JSONArray names = j.names();
            for (int i = 0; names != null && i < names.length(); i++) {
                String k = names.optString(i);
                Object v = j.opt(k);
                if ("matches".equals(k) && v instanceof JSONArray) {
                    JSONArray a = (JSONArray) v;
                    for (int n = 0; n < a.length(); n++) if (a.opt(n) instanceof JSONObject) out.add(a.optJSONObject(n));
                } else collect(v, out);
            }
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) collect(a.opt(i), out);
        }
    }

    private static final Pattern NEXT = Pattern.compile("<script id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", Pattern.DOTALL);

    private static JSONObject nextData(String html) throws Exception {
        Matcher m = NEXT.matcher(html);
        if (!m.find()) throw new java.io.IOException("khong thay du lieu trang");
        return new JSONObject(m.group(1));
    }

    /** Giờ trên lịch là giờ Việt Nam, không ghi múi. */
    private static long vnTime(String s) {
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));
            return f.parse(s.length() >= 19 ? s.substring(0, 19) : s).getTime();
        } catch (Exception e) { return 0; }
    }

    private static String get(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) GlassHome");
        try {
            int code = c.getResponseCode();
            if (code >= 400) throw new java.io.IOException("HTTP " + code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int k;
            while ((k = in.read(buf)) > 0) b.write(buf, 0, k);
            return b.toString("UTF-8");
        } finally { c.disconnect(); }
    }

    private FptFeed() {}
}
