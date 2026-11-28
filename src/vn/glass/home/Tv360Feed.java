package vn.glass.home;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

// Hàng TV360: trận Cúp C1 (UEFA Champions League) đang đá hoặc sắp tới giờ đá đứng đầu, rồi mọi chương trình đang phát
// trực tiếp, các chương trình sắp phát trong 4 giờ tới, rồi các trận C1 còn lại của lượt hiện tại; chưa đủ 5 ô thì lấp
// bằng phim nổi bật của trang Phim. Dữ liệu lấy từ các mục công khai của tv360.vn: lịch C1 (sports/get-match-box),
// mục "Trực tiếp" của trang chủ và trang Phim (composite/film/get-home). Bấm ô trận / chương trình là mở thẳng kênh
// trong app TV360 (trang trực tiếp, theo mã kênh, đúng như nút "Xem ngay" của lịch C1 trong app); ô phim mở trang phim.
// Hàng chỉ bật khi trang trực tiếp của app nhận lệnh mở từ Glass Home; không thì app TV360 giữ hàng như cũ.
final class Tv360Feed {
    static final String PKG = "com.viettel.tv360.tv";
    private static final String LIVE_SCREEN = PKG + ".screen.container.ContainerActivity";
    private static final String SITE = "https://tv360.vn/public/v1/";
    private static final String TAG = "GlassTv360";
    private static final long MATCH_MS = 2 * 3600_000L + 15 * 60_000L;
    /**
     * Đêm đấu của một trận: ngày theo giờ Việt Nam lùi 12 tiếng. Trận 23:45 tối thứ Ba, 0:45 và 3:00 sáng thứ Tư cùng
     * thuộc đêm thứ Ba, đúng một đêm đấu bên châu Âu; người xem quan tâm theo đêm đấu, không theo ngày lịch.
     */
    private static long night(long at) { return Math.floorDiv(at + 7 * 3600_000L - 12 * 3600_000L, 86_400_000L); }
    private static final TimeZone VN = TimeZone.getTimeZone("Asia/Ho_Chi_Minh");
    private static final long UPCOMING_MS = 4 * 3600_000L;   // chương trình sắp phát trong chừng này thì lên hàng
    private static final int MIN_ROW = 5;                    // ít hơn chừng này ô trận / chương trình thì lấp bằng phim
    private static final long FILM_EVERY = 12 * 3600_000L;
    private static long filmsAt;
    private static List<Shelf.Item> films = Collections.emptyList();

    private static volatile List<Shelf.Item> items = Collections.emptyList();
    private static String lastSig = "";

    static List<Shelf.Item> items() { return items; }

    /** Nạp hàng đã lưu lúc launcher vừa khởi động (chưa có lượt tải nào); lượt tải đầu sẽ thay. */
    static void restore(List<Shelf.Item> l) {
        synchronized (Tv360Feed.class) { if (items.isEmpty() && !l.isEmpty()) items = Collections.unmodifiableList(l); }
    }

    /** Trang trực tiếp của app TV360 có nhận lệnh mở từ Glass Home không. */
    static boolean canOpen(Context ctx) {
        try {
            return ctx.getPackageManager().getActivityInfo(new ComponentName(PKG, LIVE_SCREEN), 0).exported;
        } catch (Exception e) { return false; }
    }

    private static final class M { String id, round, home, away, homeLogo, awayLogo, channel, banner; long at; boolean live, fav; }

    /** Tải lại (luồng nền). favName: tên đội yêu thích, có thể null. Trả true khi hàng đổi. */
    static boolean refresh(Context ctx, String favName, int row) {
        if (!canOpen(ctx)) return set(Collections.emptyList());
        try {
            long now = System.currentTimeMillis();
            // mục "Trực tiếp": banner của trận / chương trình (có cỡ 1280x720), và các chương trình đang phát
            List<JSONObject> events = new ArrayList<>();
            for (int off = 0; off < 100; off += 50) {
                JSONObject d = json(SITE + "watch-log/get-recommend?id=rcm_live_now&itemType=LIVE&boxType=RECOMMEND&page=home&offset=" + off + "&limit=50").optJSONObject("data");
                JSONArray a = d == null ? null : d.optJSONArray("content");
                for (int i = 0; a != null && i < a.length(); i++) events.add(a.getJSONObject(i));
                if (a == null || a.length() < 50) break;
            }
            // lịch C1: trận chưa xong của lượt hiện tại (lượt của trận chưa xong sớm nhất)
            List<M> ms = new ArrayList<>();
            JSONObject box = json(SITE + "sports/get-match-box?leagueid=2&sportcode=1").optJSONObject("data");
            JSONArray a = box == null ? null : box.optJSONArray("content");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                JSONArray info = o.optJSONArray("info");
                String ch = o.optString("liveId", "");
                if (info == null || info.length() < 2 || !ch.matches("\\d+")) continue;
                M m = new M();
                m.id = o.optString("id");
                m.at = o.optLong("matchStartTime", 0);
                if (m.at == 0 || now > m.at + MATCH_MS) continue;
                m.round = o.optString("round", "");
                m.channel = ch;
                m.live = now >= m.at;
                m.home = info.getJSONObject(0).optString("name", ""); m.away = info.getJSONObject(1).optString("name", "");
                m.homeLogo = info.getJSONObject(0).optString("coverImage", null); m.awayLogo = info.getJSONObject(1).optString("coverImage", null);
                m.fav = favName != null && (same(favName, m.home) || same(favName, m.away));
                m.banner = bannerOf(events, m);
                ms.add(m);
            }
            String cur = null; long first = Long.MAX_VALUE;
            for (M m : ms) if (m.at < first) { first = m.at; cur = m.round; }
            if (cur != null) { String c = cur; ms.removeIf(m -> !c.equals(m.round)); }
            ms.sort((x, y) -> {
                if (x.live != y.live) return x.live ? -1 : 1;
                if (x.at != y.at) return Long.compare(x.at, y.at);
                return Boolean.compare(y.fav, x.fav);
            });

            // thứ tự hàng: cái gì đang trực tiếp luôn đứng đầu: (1) trận C1 đang đá, (2) mọi chương trình trực tiếp khác
            // (mới bắt đầu trước), rồi (3) mọi trận còn lại của đêm đấu gần nhất (cả trận sớm lẫn trận 3 giờ sáng),
            // (3b) chương trình sắp phát, (4) các trận C1 của đêm đấu sau trong cùng lượt. Trận C1 luôn hiện đủ cả lượt:
            // chương trình khác chỉ chen vào chỗ còn lại sau khi đã chừa chỗ cho mọi trận C1
            long firstNight = Long.MAX_VALUE;
            for (M m : ms) firstNight = Math.min(firstNight, night(m.at));
            List<M> hot = new ArrayList<>(), next = new ArrayList<>(), later = new ArrayList<>();
            for (M m : ms) (m.live ? hot : night(m.at) == firstNight ? next : later).add(m);
            Set<String> usedChannels = new HashSet<>();
            for (M m : hot) usedChannels.add(m.channel);   // kênh đang đá C1: khỏi hiện thêm ô "đang phát" trùng
            List<JSONObject> live = new ArrayList<>();
            for (JSONObject e : events) {
                long bt = e.optLong("epochBt", 0), et = e.optLong("epochEt", 0);
                String ch = e.optString("itemId", "");
                if (bt <= now && now < et && ch.matches("\\d+") && !usedChannels.contains(ch) && e.optString("coverImage", null) != null) live.add(e);
            }
            live.sort((x, y) -> Long.compare(y.optLong("epochBt"), x.optLong("epochBt")));

            List<Shelf.Item> out = new ArrayList<>();
            Set<String> keep = new HashSet<>();
            File dir = new File(ctx.getCacheDir(), "tv360");
            for (M m : hot) addMatch(ctx, m, dir, keep, out, row);
            for (JSONObject e : live) {
                if (out.size() >= row - next.size() - later.size()) break;   // chừa chỗ cho mọi trận C1 còn lại: lịch luôn đủ
                if (!usedChannels.add(e.optString("itemId"))) continue;
                out.add(item(e.optString("itemId"), big(e.optString("coverImage")), true, e.optLong("epochBt"), e.optLong("epochEt")));
            }
            for (M m : next) addMatch(ctx, m, dir, keep, out, row);
            // (3b) chương trình sắp phát trong UPCOMING_MS (sớm trước): tới giờ thì ô tự thành "trực tiếp", không đợi lượt tải;
            // mỗi kênh một ô (khung giờ sớm nhất), kênh đã có ô đang phát thì thôi
            List<JSONObject> soon = new ArrayList<>();
            for (JSONObject e : events) {
                long bt = e.optLong("epochBt", 0);
                String ch = e.optString("itemId", "");
                if (bt <= now || bt - now > UPCOMING_MS || !ch.matches("\\d+") || !e.optString("coverImage", "").startsWith("http")) continue;
                boolean c1 = false;   // chính là một trận C1 đã có ô riêng: bỏ
                for (M m : ms) if (m.channel.equals(ch) && Math.abs(m.at - bt) < 30 * 60_000L) { c1 = true; break; }
                if (!c1) soon.add(e);
            }
            soon.sort((x, y) -> Long.compare(x.optLong("epochBt"), y.optLong("epochBt")));
            for (JSONObject e : soon) {
                if (out.size() >= row - later.size()) break;
                if (!usedChannels.add(e.optString("itemId"))) continue;
                out.add(item(e.optString("itemId"), big(e.optString("coverImage")), false, e.optLong("epochBt"), e.optLong("epochEt")));
            }
            for (M m : later) addMatch(ctx, m, dir, keep, out, row);
            // (4) vẫn dưới MIN_ROW ô (sáng sớm, ngày vắng): lấp bằng phim nổi bật của trang Phim
            int nLive = out.size();
            for (Shelf.Item f : films()) {
                if (out.size() >= Math.min(row, Math.max(MIN_ROW, nLive))) break;
                out.add(f);
            }
            // ô tự vẽ của trận đã xong: xoá
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) if (!keep.contains(f.getName())) f.delete();
            android.util.Log.i(TAG, "c1=" + ms.size() + " dangphat=" + live.size() + " sap=" + soon.size() + " phim=" + (out.size() - nLive) + " o=" + out.size());
            return set(out);
        } catch (Exception e) {
            android.util.Log.i(TAG, "loi " + e);   // mất mạng, trang đổi cấu trúc: giữ hàng cũ
            return false;
        }
    }

    /** Thêm ô một trận C1: banner TV360 nếu có, không thì ô tự vẽ (logo hai đội, giờ đá) nhớ trong bộ đệm. */
    private static void addMatch(Context ctx, M m, File dir, Set<String> keep, List<Shelf.Item> out, int row) {
        if (out.size() >= row) return;
        String art = m.banner;
        if (art == null) {
            // nền ô: ảnh chủ máy tự đặt trên box (Android/data/vn.glass.home/files/tv360/nen-c1.jpg), không có thì trời sao
            File bg = new File(ctx.getExternalFilesDir(null), "tv360/nen-c1.jpg");
            String kind = bg.exists() ? "n" + bg.lastModified() : "s";
            File f = new File(dir, "c1v5-" + kind + "-" + m.id + ".png");   // đổi mẫu ô / đổi nền thì tên đổi, vẽ lại
            if (!f.exists() && !card(ctx, m, bg.exists() ? bg : null, f)) return;
            keep.add(f.getName());
            art = android.net.Uri.fromFile(f).toString();
        }
        out.add(item(m.channel, art, m.live, m.at, m.at + MATCH_MS));
    }

    private static Shelf.Item item(String channel, String art, boolean live, long startAt, long until) {
        Shelf.Item it = new Shelf.Item();
        it.pkg = PKG;
        it.title = "";   // banner, ô tự vẽ đã có tên trận: không ghi chữ, không dải tối
        it.art = art;
        it.live = live;
        it.startAt = startAt;   // tới giờ thì tự thành "trực tiếp", quá giờ thì tự bỏ, không đợi lượt tải
        it.until = until;
        it.intent = "intent:#Intent;component=" + PKG + "/" + LIVE_SCREEN
                + ";S.SCREEN_TYPE=LIVE_DETAIL;S.ITEM_ID=" + channel + ";end";
        return it;
    }

    /**
     * Phim nổi bật: mục banner đầu trang Phim của TV360 (composite/film/get-home), ảnh ngang 1280x720 đã in tên phim.
     * Nửa ngày lấy lại một lần; lỗi thì giữ danh sách cũ, 15 phút sau thử lại. Bấm ô mở trang phim trong app
     * (màn FILM_DETAIL theo mã phim, đúng như app tự mở khi bấm banner).
     */
    private static List<Shelf.Item> films() {
        long now = System.currentTimeMillis();
        if (filmsAt != 0 && now - filmsAt < FILM_EVERY) return films;
        try {
            JSONArray boxes = json(SITE + "composite/film/get-home").optJSONArray("data");
            List<Shelf.Item> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (int b = 0; boxes != null && b < boxes.length(); b++) {
                JSONObject box = boxes.getJSONObject(b);
                if (!"BANNER".equalsIgnoreCase(box.optString("type"))) continue;
                JSONArray c = box.optJSONArray("content");
                for (int i = 0; c != null && i < c.length(); i++) {
                    JSONObject e = c.getJSONObject(i);
                    String id = e.optString("itemId", "");
                    String art = e.optString("coverImage", "");
                    if (!"FILM".equalsIgnoreCase(e.optString("type")) || !id.matches("\\d+") || !art.startsWith("http") || !seen.add(id)) continue;
                    Shelf.Item it = new Shelf.Item();
                    it.pkg = PKG;
                    it.title = "";   // ảnh đã in tên phim
                    it.art = big(art);
                    it.intent = "intent:#Intent;component=" + PKG + "/" + LIVE_SCREEN
                            + ";S.SCREEN_TYPE=FILM_DETAIL;S.ITEM_ID=" + id + ";end";
                    out.add(it);
                }
            }
            if (out.isEmpty()) throw new java.io.IOException("khong co phim");
            films = out;
            filmsAt = now;
        } catch (Exception e) {
            android.util.Log.i(TAG, "phim loi " + e);
            filmsAt = now - FILM_EVERY + 15 * 60_000L;
        }
        return films;
    }

    private static boolean set(List<Shelf.Item> out) {
        StringBuilder sig = new StringBuilder();
        for (Shelf.Item i : out) sig.append(i.intent).append(i.art).append(i.live).append('|');
        synchronized (Tv360Feed.class) {
            if (sig.toString().equals(lastSig)) return false;
            lastSig = sig.toString();
            items = Collections.unmodifiableList(out);
            return true;
        }
    }

    /** Banner của trận ở mục "Trực tiếp": cùng kênh, giờ bắt đầu lệch dưới 30 phút, hoặc mô tả có tên hai đội. */
    private static String bannerOf(List<JSONObject> events, M m) {
        for (JSONObject e : events) {
            String img = e.optString("coverImage", null);
            if (img == null) continue;
            String d = e.optString("description", "").toLowerCase(Locale.ROOT);
            boolean names = d.contains(m.home.toLowerCase(Locale.ROOT)) && d.contains(m.away.toLowerCase(Locale.ROOT));
            boolean slot = m.channel.equals(e.optString("itemId")) && Math.abs(e.optLong("epochBt") - m.at) < 30 * 60_000L;
            if (names || slot) return big(img);
        }
        return null;
    }

    /** Ảnh 1280x720 thay cho bản 640x360 mặc định (ô thumb cần gấp đôi cỡ hiển thị mới nét). */
    private static String big(String u) { return u.replace("_640_360.", "_1280_720."); }

    private static boolean same(String a, String b) {
        String x = a.toLowerCase(Locale.ROOT).trim(), y = b.toLowerCase(Locale.ROOT).trim();
        return !x.isEmpty() && !y.isEmpty() && (x.equals(y) || x.contains(y) || y.contains(x));
    }

    // ---------- ô tự vẽ cho trận chưa có banner (mẫu "đối đầu") ----------
    // Logo hai đội hai bên, giờ đá to ở giữa, ngày bên dưới, nhãn giải trên cùng; chữ Inter như cả launcher.
    // Nền: ảnh chủ máy đặt, mỗi trận một "góc máy" riêng (phóng, xoay, dời, lật) tính từ mã trận, như banner kênh.
    // Vẽ ở 960x540 (gấp đôi ô hiển thị); logo thu bằng bộ lọc theo từng nấc một nửa cho viền mịn.

    private static final String[] THU = {"Chủ Nhật", "Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy"};
    private static final int CW = 960, CH = 540;
    private static final int WHITE = 0xFFFFFFFF, DIM = 0xAAFFFFFF, FAINT = 0x78FFFFFF;

    private static boolean card(Context ctx, M m, File bgFile, File f) {
        Bitmap b = null;
        try {
            b = Bitmap.createBitmap(CW, CH, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.drawColor(0xFF0A1B45);
            if (!drawBg(c, bgFile, m.id.hashCode())) {
                Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
                glow.setColor(0xFF132C66);
                c.drawCircle(CW / 2f, CH * 1.25f, CH * 0.95f, glow);
                stars(c, CW, CH, m.id.hashCode());
            }
            // đáy tối dần: tên đội luôn nổi trên nền
            Paint g = new Paint();
            g.setShader(new android.graphics.LinearGradient(0, 300, 0, CH, 0x00020620, 0xAA020620, android.graphics.Shader.TileMode.CLAMP));
            c.drawRect(0, 300, CW, CH, g);

            int lcy = 262, ls = 190;
            float lx = 205, rx = CW - 205;
            drawLogo(c, m.homeLogo, lx, lcy, ls);
            drawLogo(c, m.awayLogo, rx, lcy, ls);

            Paint t = text(ctx, 600, 24, DIM);
            t.setLetterSpacing(0.17f);
            mid(c, "UEFA CHAMPIONS LEAGUE", CW / 2f, 64, t);
            mid(c, m.round, CW / 2f, 98, text(ctx, 400, 24, FAINT));

            Calendar k = Calendar.getInstance(VN);
            k.setTimeInMillis(m.at);
            mid(c, String.format(Locale.ROOT, "%02d:%02d", k.get(Calendar.HOUR_OF_DAY), k.get(Calendar.MINUTE)), CW / 2f, lcy - 14, text(ctx, 650, 96, WHITE));
            mid(c, THU[k.get(Calendar.DAY_OF_WEEK) - 1] + " " + k.get(Calendar.DAY_OF_MONTH) + "/" + (k.get(Calendar.MONTH) + 1),
                    CW / 2f, lcy + 60, text(ctx, 450, 32, DIM));

            float ny = lcy + ls / 2f + 39;   // tên cách đáy logo ~18px: tên và logo đọc thành một khối
            Paint name = text(ctx, 600, 42, WHITE);
            mid(c, shortName(m.home, name, 330), lx, ny, name);
            name = text(ctx, 600, 42, WHITE);
            mid(c, shortName(m.away, name, 330), rx, ny, name);

            File dir = f.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) { b.compress(Bitmap.CompressFormat.PNG, 100, o); }
            return tmp.renameTo(f);
        } catch (Exception e) {
            android.util.Log.i(TAG, "ve o loi " + e);
            return false;
        } finally { if (b != null) b.recycle(); }
    }

    private static final java.util.Map<String, Typeface> fonts = new java.util.HashMap<>();

    /** Bút chữ Inter (font của launcher) đúng độ đậm và opsz theo cỡ; khử răng cưa, căn giữa. */
    private static Paint text(Context ctx, int weight, float size, int color) {
        int opsz = Math.max(14, Math.min(32, Math.round(size)));
        String key = weight + "/" + opsz;
        Typeface tf;
        synchronized (fonts) {
            tf = fonts.get(key);
            if (tf == null) {
                try {
                    tf = new Typeface.Builder(ctx.getAssets(), "fonts/Inter.ttf")
                            .setFontVariationSettings("'wght' " + weight + ", 'opsz' " + opsz).build();
                } catch (Exception e) { tf = Typeface.create("sans-serif-medium", Typeface.NORMAL); }
                fonts.put(key, tf);
            }
        }
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG | Paint.LINEAR_TEXT_FLAG);
        p.setTypeface(tf);
        p.setTextSize(size);
        p.setColor(color);
        p.setTextAlign(Paint.Align.CENTER);
        return p;
    }

    /** Vẽ chữ căn giữa theo cả chiều dọc (cy là tâm dòng chữ). */
    private static void mid(Canvas c, String s, float x, float cy, Paint p) {
        Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText(s, x, cy - (fm.ascent + fm.descent) / 2f, p);
    }

    /** Tên gọn cho vừa max: tên quen thuộc thì dùng tên ngắn, không thì bỏ chữ đầu, rồi thu cỡ chữ, cuối cùng mới cắt. */
    private static String shortName(String s, Paint p, float max) {
        String[][] known = {{"Borussia Dortmund", "Dortmund"}, {"Paris Saint-Germain", "PSG"}, {"Paris Saint Germain", "PSG"},
                {"Bayern Munich", "Bayern"}, {"Bayern München", "Bayern"}, {"Manchester City", "Man City"},
                {"Manchester United", "Man United"}, {"Atletico Madrid", "Atlético"}, {"Atlético Madrid", "Atlético"},
                {"Bayer Leverkusen", "Leverkusen"}, {"Inter Milan", "Inter"}, {"Tottenham Hotspur", "Tottenham"},
                {"Newcastle United", "Newcastle"}, {"Eintracht Frankfurt", "Frankfurt"}, {"Borussia Monchengladbach", "Gladbach"}};
        for (String[] k : known) if (k[0].equalsIgnoreCase(s.trim())) { s = k[1]; break; }
        if (p.measureText(s) > max && s.contains(" ")) {
            String rest = s.substring(s.indexOf(' ') + 1);
            if (p.measureText(rest) <= max) return rest;
        }
        float size = p.getTextSize();
        while (p.measureText(s) > max && size > 32) { size -= 2; p.setTextSize(size); }
        if (p.measureText(s) <= max) return s;
        while (s.length() > 1 && p.measureText(s + "…") > max) s = s.substring(0, s.length() - 1);
        return s.trim() + "…";
    }

    /**
     * Nền theo trận: cùng ảnh chủ máy đặt nhưng mỗi trận một góc riêng (lật ngang, phóng 1,15..1,45, xoay ±12°, dời),
     * luôn phóng đủ lớn theo góc xoay để khung không lộ mép; phủ một lớp xanh tối nhẹ để chữ trắng đọc rõ.
     */
    private static boolean drawBg(Canvas c, File bgFile, int seed) {
        if (bgFile == null) return false;
        Bitmap bg = null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(bgFile.getPath(), o);
            int s = 1;
            while (o.outWidth / (s * 2) >= CW * 1.5f && o.outHeight / (s * 2) >= CH * 1.5f) s *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = s;
            bg = BitmapFactory.decodeFile(bgFile.getPath(), d);
            if (bg == null) return false;
            java.util.Random r = new java.util.Random(seed);
            boolean flip = r.nextBoolean();
            float z = 1.15f + r.nextFloat() * 0.30f;
            float ang = -12f + r.nextFloat() * 24f;
            double th = Math.toRadians(Math.abs(ang));
            float need = (float) Math.max((CW * Math.cos(th) + CH * Math.sin(th)) / CW, (CW * Math.sin(th) + CH * Math.cos(th)) / CH);
            float k = Math.max(CW / (float) bg.getWidth(), CH / (float) bg.getHeight()) * Math.max(z, need * 1.02f);
            float sw = bg.getWidth() * k, sh = bg.getHeight() * k;
            float cx = sw / 2f + (r.nextFloat() * 2 - 1) * Math.max(0, (sw - need * CW) / 2f);
            float cy = sh / 2f + (r.nextFloat() * 2 - 1) * Math.max(0, (sh - need * CH) / 2f);
            android.graphics.Matrix mx = new android.graphics.Matrix();
            if (flip) mx.postScale(-1, 1, bg.getWidth() / 2f, 0);
            mx.postScale(k, k);
            mx.postTranslate(-cx, -cy);
            mx.postRotate(ang);
            mx.postTranslate(CW / 2f, CH / 2f);
            c.drawBitmap(bg, mx, new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
            c.drawColor(0x40040A28);
            return true;
        } catch (Exception e) {
            return false;
        } finally { if (bg != null) bg.recycle(); }
    }

    /** Trời sao (dự phòng khi box chưa có ảnh nền): chấm nhỏ mờ và vài ngôi sao năm cánh; cùng trận thì cùng bầu trời. */
    private static void stars(Canvas c, int w, int h, int seed) {
        java.util.Random r = new java.util.Random(seed);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        for (int i = 0; i < 140; i++) {
            p.setAlpha(40 + r.nextInt(120));
            c.drawCircle(r.nextFloat() * w, r.nextFloat() * h, 0.8f + r.nextFloat() * 1.8f, p);
        }
        android.graphics.Path star = new android.graphics.Path();
        for (int i = 0; i < 9; i++) {
            float cx = r.nextFloat() * w, cy = r.nextFloat() * h, R = 7 + r.nextFloat() * 9;
            star.reset();
            for (int k = 0; k < 10; k++) {
                double ang = -Math.PI / 2 + k * Math.PI / 5;
                float rad = k % 2 == 0 ? R : R * 0.42f;
                float x = cx + (float) (Math.cos(ang) * rad), y = cy + (float) (Math.sin(ang) * rad);
                if (k == 0) star.moveTo(x, y); else star.lineTo(x, y);
            }
            star.close();
            p.setAlpha(110 + r.nextInt(90));
            c.drawPath(star, p);
        }
    }

    /** Logo đội: thu về đúng cỡ bằng bộ lọc theo từng nấc một nửa (viền mịn), bóng đổ mềm phía dưới. */
    private static void drawLogo(Canvas c, String url, float cx, float cy, int size) {
        if (url == null) return;
        Bitmap l = null, shadow = null;
        try {
            byte[] d = bytes(url);
            l = BitmapFactory.decodeByteArray(d, 0, d.length);
            if (l == null) return;
            float s = size / (float) Math.max(l.getWidth(), l.getHeight());
            int tw = Math.max(1, Math.round(l.getWidth() * s)), th = Math.max(1, Math.round(l.getHeight() * s));
            while (l.getWidth() / 2 >= tw && l.getHeight() / 2 >= th) {
                Bitmap h = Bitmap.createScaledBitmap(l, l.getWidth() / 2, l.getHeight() / 2, true);
                if (h != l) l.recycle();
                l = h;
            }
            if (l.getWidth() != tw || l.getHeight() != th) {
                Bitmap h = Bitmap.createScaledBitmap(l, tw, th, true);
                if (h != l) l.recycle();
                l = h;
            }
            Paint blur = new Paint();
            blur.setMaskFilter(new android.graphics.BlurMaskFilter(16, android.graphics.BlurMaskFilter.Blur.NORMAL));
            int[] off = new int[2];
            shadow = l.extractAlpha(blur, off);
            Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            sp.setColor(0x8C000000);
            c.drawBitmap(shadow, cx - tw / 2f + off[0], cy - th / 2f + off[1] + 8, sp);
            c.drawBitmap(l, cx - tw / 2f, cy - th / 2f, new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
        } catch (Exception ignored) {
        } finally {
            if (l != null) l.recycle();
            if (shadow != null) shadow.recycle();
        }
    }

    // ---------- mạng ----------

    private static JSONObject json(String u) throws Exception { return new JSONObject(new String(bytes(u), "UTF-8")); }

    private static byte[] bytes(String u) throws Exception {
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
            return b.toByteArray();
        } finally { c.disconnect(); }
    }

    private Tv360Feed() {}
}
