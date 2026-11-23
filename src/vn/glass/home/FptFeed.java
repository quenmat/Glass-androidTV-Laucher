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
// Ít hơn 10 trận thì lấp cho đủ 10 ô bằng phim đáng chú ý (xem phần "phim đáng chú ý" bên dưới).
final class FptFeed {
    static final String PKG = "net.fptplay.ottbox";
    private static final String SITE = "https://fptplay.vn";
    private static final String TAG = "GlassFpt";
    private static final long MATCH_MS = 2 * 3600_000L + 15 * 60_000L;   // một trận kể cả bù giờ, nghỉ giữa hiệp

    private static volatile List<Shelf.Item> items = Collections.emptyList();
    private static String lastSig = "";
    private static final Map<String, String> posters = new HashMap<>();   // mã trận -> banner (chỉ của các trận đang hiện)

    static List<Shelf.Item> items() { return items; }

    /** Nạp hàng đã lưu lúc launcher vừa khởi động (chưa có lượt tải nào); lượt tải đầu sẽ thay. */
    static void restore(List<Shelf.Item> l) {
        synchronized (FptFeed.class) {
            if (items.isEmpty() && !l.isEmpty()) items = Collections.unmodifiableList(l);
            // ô phim (không có giờ đá) của hàng đã lưu: dùng tạm làm danh sách phim cho tới lượt lọc phim đầu tiên,
            // khỏi để lượt tải trận đầu xoá mất phim (chỉ còn trận) trong lúc chờ lọc lại cả sitemap
            if (films.isEmpty()) {
                List<Shelf.Item> fl = new ArrayList<>();
                for (Shelf.Item it : l) if (it.startAt <= 0) fl.add(it);
                films = fl;
            }
        }
    }

    private static final class M { String ev, home, away, homeCode, awayCode, round, slug; long at, dur; boolean live, fav; }

    /** Tải lại (luồng nền). favAbbr: mã 3 chữ của đội yêu thích (vd MUN), có thể null. Trả true khi hàng đổi. */
    static boolean refresh(String favAbbr, int row) {
        List<Shelf.Item> matches = matches(favAbbr, row);
        if (matches == null) matches = lastMatches;   // lịch lỗi (mất mạng, trang đổi): giữ trận cũ
        lastMatches = matches;
        lastRow = row;
        return set(compose(matches, films, row));
    }

    /** Lọc lại phim khi đến hạn (gọi sau cùng: tải nhiều trang, khỏi bắt hàng trận / hàng khác chờ). Trả true khi hàng đổi. */
    static boolean refreshFilms() {
        List<Shelf.Item> before = films;
        if (films() == before) return false;
        return set(compose(lastMatches, films, lastRow));
    }

    private static volatile int lastRow = 12;

    /** Trận trước; phim chỉ để lấp cho hàng đủ MIN_ROW ô, đủ trận rồi thì không chen phim. */
    private static List<Shelf.Item> compose(List<Shelf.Item> matches, List<Shelf.Item> fl, int row) {
        List<Shelf.Item> out = new ArrayList<>(matches);
        if (out.size() < MIN_ROW) for (Shelf.Item f : fl) if (out.size() < Math.min(MIN_ROW, row)) out.add(f);
        return out;
    }

    private static volatile List<Shelf.Item> lastMatches = Collections.emptyList();
    private static final Pattern EVENT_OF = Pattern.compile("/su-kien/[a-z0-9-]*-([0-9a-f]{24})#");

    // Các giải khác FPT Play cũng phát trực tiếp (V.League, U17 World Cup, NBA, VBA, FA Cup, Shopee Cup): mỗi giải một
    // trang lịch công khai cùng dạng trang Ngoại hạng Anh. Chỉ lấy trận đang diễn ra hoặc sắp đá trong OTHER_SOON
    // (khỏi tràn hàng bằng lịch cả tuần); lịch các giải này nửa tiếng đọc lại một lần (ít đổi), trạng thái trực tiếp
    // tính theo giờ như trận EPL. Đường dẫn sai thì trang trả về lịch Ngoại hạng Anh: nhận ra và bỏ.
    private static final String[] OTHER_PAGES = {"v-league", "fifa-u17-world-cup", "nba", "vba", "fa-cup",
            "asean-club-championship-shopee-cup"};
    private static final long OTHER_EVERY = 30 * 60_000L, OTHER_SOON = 3 * 3600_000L;
    private static final long BASKET_MS = 2 * 3600_000L + 45 * 60_000L;   // một trận bóng rổ kể cả nghỉ, hội ý
    private static long othersAt;
    private static List<M> others = Collections.emptyList();

    /** Trận có sự kiện FPT trong dữ liệu một trang lịch; listed nhận mã mọi trận trang còn liệt kê (kể cả đã xong). */
    private static List<M> parsePage(String h, String favAbbr, long now, java.util.Set<String> listed, String expectSlug) throws Exception {
        JSONObject pp = nextData(h).getJSONObject("props").getJSONObject("pageProps");
        if (expectSlug != null && "premier-league".equals(pp.optString("activeCompetitionSlug", ""))) return new ArrayList<>();
        List<JSONObject> raw = new ArrayList<>();
        collect(pp.opt("fixturesSrrData"), raw);
        List<M> ms = new ArrayList<>();
        for (JSONObject o : raw) {
            JSONObject mt = o.optJSONObject("match");
            JSONArray ps = o.optJSONArray("participants");
            if (mt == null || ps == null || ps.length() < 2) continue;
            JSONObject refs = mt.optJSONObject("internalRefs");
            String ev = refs == null ? null : refs.optString("eventId", null);
            if (ev == null || !ev.matches("[0-9a-f]{24}")) continue;
            if (listed != null) listed.add(ev);
            M m = new M();
            m.ev = ev;
            m.at = vnTime(mt.optString("scheduledAt", ""));
            m.dur = "Sport:basketball".equals(mt.optString("sportId", "")) ? BASKET_MS : MATCH_MS;
            String st = mt.optString("status", "").toLowerCase(java.util.Locale.ROOT);
            boolean done = st.contains("finish") || st.contains("final") || st.contains("ended") || st.contains("full") || st.contains("complete")
                    || st.contains("cancel") || st.contains("postpon") || !mt.isNull("scoreFinal")
                    || (m.at > 0 && now > m.at + m.dur);
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
        return ms;
    }

    /** Trận các giải khác (đọc lại khi tới hạn; lỗi hết thì giữ danh sách cũ, thử lại sau 5 phút). */
    private static List<M> others(String favAbbr, long now) {
        if (othersAt != 0 && now - othersAt < OTHER_EVERY) return others;
        List<M> all = new ArrayList<>();
        boolean ok = false;
        for (String page : OTHER_PAGES) {
            try { all.addAll(parsePage(get(SITE + "/lich-thi-dau/" + page), favAbbr, now, null, page)); ok = true; }
            catch (Exception e) { android.util.Log.i(TAG, "lich " + page + " loi " + e); }
        }
        if (ok) { others = all; othersAt = now; } else othersAt = now - OTHER_EVERY + 5 * 60_000L;
        return others;
    }

    /** Ô trận EPL của vòng hiện tại + trận đang / sắp diễn ra của các giải khác; lỗi trang EPL thì null. */
    private static List<Shelf.Item> matches(String favAbbr, int row) {
        try {
            long now = System.currentTimeMillis();
            java.util.Set<String> listed = new java.util.HashSet<>();
            List<M> ms = parsePage(get(SITE + "/lich-thi-dau/ngoai-hang-anh"), favAbbr, now, listed, null);
            // vòng hiện tại: vòng của trận chưa xong đá sớm nhất; chỉ giữ trận của vòng đó
            String cur = null; long first = Long.MAX_VALUE;
            for (M m : ms) if (m.at < first) { first = m.at; cur = m.round; }
            if (cur != null) { String c = cur; ms.removeIf(m -> !c.equals(m.round)); }
            int epl = ms.size();
            // giải khác: đang diễn ra hoặc sắp đá trong OTHER_SOON (trạng thái tính lại theo giờ hiện tại)
            for (M m : others(favAbbr, now)) {
                listed.add(m.ev);
                if (now > m.at + m.dur || m.at - now > OTHER_SOON) continue;
                m.live = now >= m.at;
                ms.add(m);
            }
            ms.sort((x, y) -> {
                if (x.live != y.live) return x.live ? -1 : 1;
                if (x.at != y.at) return Long.compare(x.at, y.at);
                return Boolean.compare(y.fav, x.fav);
            });
            List<Shelf.Item> out = new ArrayList<>();
            Map<String, String> keep = new HashMap<>();
            java.util.Set<String> arts = new java.util.HashSet<>();
            for (M m : ms) {
                if (out.size() >= row) break;
                String poster;
                synchronized (posters) { poster = posters.get(m.ev); }
                if (poster == null) poster = poster(m.ev);
                if (poster == null) continue;   // chưa có banner: bỏ ô, lượt sau thử lại
                keep.put(m.ev, poster);
                // vài giải dùng chung một ảnh cho mọi trận (U17 World Cup): chỉ giữ ô sớm nhất, khỏi một hàng banner y hệt
                if (!arts.add(poster.replaceAll("\\?.*$", ""))) continue;
                Shelf.Item it = new Shelf.Item();
                it.pkg = PKG;
                it.title = "";                  // banner đã in tên hai đội, giờ, vòng: không ghi chữ, không dải tối
                it.art = poster;
                it.live = m.live;
                it.startAt = m.at;              // tới giờ thì tự thành "trực tiếp", quá giờ thì tự bỏ, không đợi lượt tải
                it.until = m.at + m.dur;
                it.intent = "intent://fptplay.vn/su-kien/" + m.slug + "-" + m.ev
                        + "#Intent;scheme=https;action=android.intent.action.VIEW;package=" + PKG + ";end";
                out.add(it);
            }
            // trang lịch bỏ hẳn trận đang đá khỏi danh sách (chỉ còn trận chưa đá): ô trận đã hiện mà tới giờ, chưa hết
            // giờ, trang không còn nhắc tới thì giữ lại ở đầu hàng cho tới hết giờ (kể cả ô nạp lại lúc launcher khởi động)
            List<Shelf.Item> carry = new ArrayList<>();
            for (Shelf.Item it : items) {
                if (it.startAt <= 0 || now < it.startAt || now > it.until || it.intent == null) continue;
                Matcher em = EVENT_OF.matcher(it.intent);
                if (!em.find() || listed.contains(em.group(1))) continue;
                it.live = true;
                carry.add(it);
                synchronized (posters) { if (it.art != null) keep.put(em.group(1), it.art); }
            }
            if (!carry.isEmpty()) {
                out.addAll(0, carry);
                while (out.size() > row) out.remove(out.size() - 1);
            }
            synchronized (posters) { posters.clear(); posters.putAll(keep); }   // trận đã bỏ khỏi hàng: quên banner
            android.util.Log.i(TAG, "epl=" + epl + " giaikhac=" + (ms.size() - epl) + " giu=" + carry.size() + " o=" + out.size());
            return out;
        } catch (Exception e) {
            android.util.Log.i(TAG, "loi " + e);
            return null;
        }
    }

    // ---------- phim đáng chú ý ----------
    // Trang chủ FPT Play lấy danh sách phim qua API có chữ ký nên không đọc được; nguồn công khai là sitemap của
    // fptplay.vn (bộ phim điện ảnh Galaxy Play họ đưa lên cho máy tìm kiếm) và trang từng phim (tên, năm, điểm,
    // ảnh ngang đã in tên phim). Nửa ngày lọc lại một lần: phim mới phát hành trước, cùng năm thì điểm cao trước
    // (điểm có trọng số theo số lượt chấm, bỏ phim quá ít lượt hoặc điểm thấp).

    private static final int FILM_N = 10;
    private static final int MIN_ROW = 10;   // hàng luôn có ít nhất chừng này ô (trận trước, thiếu thì lấp bằng phim)
    private static final long FILM_EVERY = 12 * 3600_000L;
    private static long filmsAt;
    private static List<Shelf.Item> films = Collections.emptyList();
    private static final Map<String, Film> filmInfo = new HashMap<>();   // đường dẫn phim -> thông tin (phim không đổi, nhớ luôn)

    private static final class Film { String path, art; int year; double score; boolean ok; }

    private static List<Shelf.Item> films() {
        long now = System.currentTimeMillis();
        if (filmsAt != 0 && now - filmsAt < FILM_EVERY) return films;
        try {
            // sitemap thỉnh thoảng trả trang dự phòng thay cho XML (lúc được lúc không): thử lại vài lần ngay
            String x = null;
            for (int t = 0; t < 3 && (x == null || !x.contains("<urlset")); t++) {
                if (t > 0) Thread.sleep(3000);
                try { x = get(SITE + "/sitemap.xml"); } catch (java.io.IOException e) { x = null; }
            }
            if (x == null || !x.contains("<urlset")) throw new java.io.IOException("sitemap khong dung dang");
            Matcher m = Pattern.compile("<loc>https://fptplay\\.vn(/[a-z0-9-]+/xem-video/[a-z0-9-]+-[0-9a-f]{24})</loc>").matcher(x);
            List<Film> all = new ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            while (m.find()) {
                String path = m.group(1);
                if (!seen.add(path)) continue;
                Film f = filmInfo.get(path);
                if (f == null) { f = film(path); if (f == null) continue; filmInfo.put(path, f); }
                if (f.ok) all.add(f);
            }
            all.sort((a, b) -> a.year != b.year ? Integer.compare(b.year, a.year) : Double.compare(b.score, a.score));
            List<Shelf.Item> out = new ArrayList<>();
            for (Film f : all) {
                if (out.size() >= FILM_N) break;
                Shelf.Item it = new Shelf.Item();
                it.pkg = PKG;
                it.title = "";   // ảnh đã in tên phim
                it.art = f.art;
                it.intent = "intent://fptplay.vn" + f.path + "#Intent;scheme=https;action=android.intent.action.VIEW;package=" + PKG + ";end";
                out.add(it);
            }
            if (out.isEmpty()) throw new java.io.IOException("khong phim nao dat");   // trang phim lỗi hàng loạt: giữ list cũ
            films = out;
            filmsAt = now;
            android.util.Log.i(TAG, "phim=" + all.size() + " chon=" + out.size());
        } catch (Exception e) {
            android.util.Log.i(TAG, "phim loi " + e);
            filmsAt = now - FILM_EVERY + 5 * 60_000L;   // lỗi: 5 phút sau thử lại, giữ danh sách cũ
        }
        return films;
    }

    /** Thông tin một phim từ trang của nó; trang lỗi thì null (lần sau thử lại), phim không đạt thì ok=false. */
    private static Film film(String path) {
        try {
            JSONObject d = nextData(get(SITE + path)).getJSONObject("props").getJSONObject("pageProps").optJSONObject("ssrContentDetail");
            Film f = new Film();
            f.path = path;
            if (d == null) return f;
            JSONObject img = d.optJSONObject("image");
            String art = img == null ? null : img.optString("landscape_title", null);
            JSONObject r = d.optJSONObject("rating");
            double avg = r == null ? 0 : r.optDouble("avg_rate", 0);
            int cnt = r == null ? 0 : r.optInt("count_origin", 0);
            f.year = d.optInt("movie_release_date", 0);
            f.score = (avg * cnt + 4.0 * 5) / (cnt + 5);   // điểm có trọng số: ít lượt chấm thì kéo về 4
            f.art = art == null || art.isEmpty() ? null : art + (art.contains("?") ? "&" : "?") + "w=1280";
            f.ok = f.art != null && cnt >= 5 && f.score >= 3.8;
            return f;
        } catch (Exception e) {
            return null;
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
