package vn.glass.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Lịch Ngoại hạng Anh của vòng đang/sắp đá, từ API công khai của Fantasy Premier League (trang chính chủ, không cần key).
// Danh sách đội + vòng hiện tại lưu đệm 3 tiếng; lịch/tỉ số của vòng lấy mới mỗi lần gọi. Logo đội tải một lần, lưu trên box.
// Chạy ở luồng nền; lỗi mạng thì trả dữ liệu cũ (nếu có) hoặc null.
final class Epl {
    static final class Match {
        long kick;            // giờ đá (ms, UTC)
        int home, away;       // id đội
        int hs = -1, as = -1; // tỉ số (-1: chưa có)
        boolean started, finished;
        int minutes;
    }

    static final class Team { String name; int code; Bitmap badge, small; }

    /** Một dòng bảng xếp hạng: tính từ các trận đã đá xong của cả mùa. */
    static final class Standing {
        int team, played, won, drawn, lost, gf, ga, pts;
        int move;   // so với trước vòng gần nhất: dương = lên bao nhiêu bậc, âm = tụt
        int gd() { return gf - ga; }
    }

    static final class Data {
        int round;
        List<Match> matches = new ArrayList<>();
        Map<Integer, Team> teams = new HashMap<>();
        List<Standing> table = new ArrayList<>();
        boolean live() { for (Match m : matches) if (m.started && !m.finished) return true; return false; }

        /** Có trận đã tới giờ đá mà chưa xong (kể cả lúc API chưa kịp báo "đang đá"): cần cập nhật từng phút. */
        boolean inPlayWindow(long now) {
            for (Match m : matches) if (!m.finished && m.kick <= now && now - m.kick < 3 * 3600_000L) return true;
            return live();
        }

        /** Giờ bóng lăn của trận sắp tới gần nhất (chưa bắt đầu), -1 nếu không còn. */
        long nextKick(long now) {
            for (Match m : matches) if (!m.started && m.kick > now) return m.kick;
            return -1;
        }
    }

    private static final String API = "https://fantasy.premierleague.com/api/";
    private static final String BADGE = "https://resources.premierleague.com/premierleague/badges/100/t%d.png";
    private static final long META_MS = 3 * 3600_000L;

    // tên gọn cho tiếng Việt (giữ đúng tên quen gọi, bỏ đuôi City/Town khi không gây nhầm)
    private static final Map<String, String> NICE = new HashMap<>();
    static {
        NICE.put("Man Utd", "Man United");
        NICE.put("Nott'm Forest", "Nottingham");
        NICE.put("Coventry City", "Coventry");
        NICE.put("Hull City", "Hull");
        NICE.put("Ipswich Town", "Ipswich");
        NICE.put("Leicester City", "Leicester");
        NICE.put("Norwich City", "Norwich");
        NICE.put("Stoke City", "Stoke");
        NICE.put("Cardiff City", "Cardiff");
        NICE.put("Swansea City", "Swansea");
    }

    private static Data last;
    /** Cỡ logo khi vẽ (điểm ảnh màn hình); logo được thu sẵn đúng cỡ này để vẽ 1:1, không co giãn lúc vẽ. */
    static volatile int badgePx = 28, smallPx = 22;

    // bảng xếp hạng: tải cả mùa (~380 trận) nên không lấy mỗi phút; có trận đang đá thì 2 phút, không thì 30 phút
    private static List<Standing> table = new ArrayList<>();
    private static long tableAt;

    /**
     * Thu logo (100px) về vừa khung size x size, giữ tỉ lệ: thu từng nửa (lọc song tuyến 2:1 = trung bình 4 điểm,
     * không bỏ sót chi tiết) rồi bước cuối về đúng cỡ. Thu một phát 100 -> 28 thì nét gai, mất chi tiết.
     */
    private static Bitmap fit(Bitmap b, int size) {
        if (b == null) return null;
        float s = size / (float) Math.max(b.getWidth(), b.getHeight());
        int w = Math.max(1, Math.round(b.getWidth() * s)), h = Math.max(1, Math.round(b.getHeight() * s));
        while (b.getWidth() / 2 >= w && b.getHeight() / 2 >= h) {
            b = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
        }
        return b.getWidth() == w && b.getHeight() == h ? b : Bitmap.createScaledBitmap(b, w, h, true);
    }

    static Data load(Context ctx) {
        try {
            File dir = new File(ctx.getFilesDir(), "epl");
            dir.mkdirs();
            File meta = new File(dir, "meta.json");
            JSONObject m;
            if (!meta.exists() || System.currentTimeMillis() - meta.lastModified() > META_MS) {
                m = buildMeta(get(API + "bootstrap-static/"));
                write(meta, m.toString());
            } else {
                m = new JSONObject(read(meta));
            }
            Data d = new Data();
            // vòng hiện tại: giữ tới hết NGÀY HÔM SAU của trận cuối (để xem lại kết quả), rồi mới sang vòng kế tiếp
            int cur = m.optInt("cur", -1), next = m.optInt("next", -1);
            JSONArray fx = cur > 0 ? new JSONArray(get(API + "fixtures/?event=" + cur)) : null;
            if (fx == null || (next > 0 && roundOver(fx))) {
                d.round = next;
                fx = new JSONArray(get(API + "fixtures/?event=" + next));
            } else {
                d.round = cur;
            }
            JSONArray ts = m.getJSONArray("teams");
            for (int i = 0; i < ts.length(); i++) {
                JSONObject t = ts.getJSONObject(i);
                Team team = new Team();
                team.name = t.getString("name");
                team.code = t.getInt("code");
                Bitmap raw = badgeRaw(dir, team.code);
                team.badge = fit(raw, badgePx);
                team.small = fit(raw, smallPx);
                d.teams.put(t.getInt("id"), team);
            }
            for (int i = 0; i < fx.length(); i++) {
                JSONObject f = fx.getJSONObject(i);
                if (f.isNull("kickoff_time")) continue;   // trận hoãn chưa xếp lịch
                Match x = new Match();
                x.kick = java.time.Instant.parse(f.getString("kickoff_time")).toEpochMilli();
                x.home = f.getInt("team_h");
                x.away = f.getInt("team_a");
                if (!f.isNull("team_h_score")) x.hs = f.getInt("team_h_score");
                if (!f.isNull("team_a_score")) x.as = f.getInt("team_a_score");
                x.started = f.optBoolean("started");
                x.finished = f.optBoolean("finished") || f.optBoolean("finished_provisional");
                x.minutes = f.optInt("minutes");
                d.matches.add(x);
            }
            d.matches.sort((a, b) -> Long.compare(a.kick, b.kick));
            long age = System.currentTimeMillis() - tableAt;
            if (table.isEmpty() || age > (d.live() ? 2 : 30) * 60_000L) {
                try { table = standings(new JSONArray(get(API + "fixtures/"))); tableAt = System.currentTimeMillis(); }
                catch (Exception ignored) {}   // lỗi mạng: giữ bảng cũ
            }
            d.table = table;
            last = d;
            return d;
        } catch (Exception e) {
            return last;
        }
    }

    /** Mọi trận của vòng đã xong (bỏ trận hoãn chưa có lịch) và đã qua hết ngày hôm sau của trận cuối (giờ máy). */
    private static boolean roundOver(JSONArray fx) throws Exception {
        long lastKick = 0;
        for (int i = 0; i < fx.length(); i++) {
            JSONObject f = fx.getJSONObject(i);
            if (f.isNull("kickoff_time")) continue;
            if (!(f.optBoolean("finished") || f.optBoolean("finished_provisional"))) return false;
            lastKick = Math.max(lastKick, java.time.Instant.parse(f.getString("kickoff_time")).toEpochMilli());
        }
        if (lastKick == 0) return true;
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(lastKick);
        c.add(java.util.Calendar.DAY_OF_MONTH, 2);   // 0 giờ của ngày kia = hết ngày hôm sau
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return System.currentTimeMillis() >= c.getTimeInMillis();
    }

    /** Vòng hiện tại và vòng kế tiếp (chọn hiện vòng nào thì quyết ở load), kèm danh sách đội (gọn). */
    private static JSONObject buildMeta(String boot) throws Exception {
        JSONObject b = new JSONObject(boot);
        JSONArray ev = b.getJSONArray("events");
        int cur = -1, next = -1;
        for (int i = 0; i < ev.length(); i++) {
            JSONObject e = ev.getJSONObject(i);
            if (e.optBoolean("is_current")) cur = e.getInt("id");
            if (e.optBoolean("is_next")) next = e.getInt("id");
        }
        if (cur < 0) cur = next;   // đầu mùa: chưa có vòng hiện tại
        JSONArray teams = new JSONArray();
        JSONArray ts = b.getJSONArray("teams");
        for (int i = 0; i < ts.length(); i++) {
            JSONObject t = ts.getJSONObject(i);
            String name = t.getString("name");
            teams.put(new JSONObject().put("id", t.getInt("id")).put("code", t.getInt("code"))
                    .put("name", NICE.containsKey(name) ? NICE.get(name) : name));
        }
        return new JSONObject().put("cur", cur).put("next", next).put("teams", teams);
    }

    /**
     * Bảng hiện tại, kèm số bậc mỗi đội lên/xuống so với bảng trước vòng gần nhất (vòng mới nhất có trận đã xong).
     */
    private static List<Standing> standings(JSONArray all) throws Exception {
        int latest = 0;
        for (int i = 0; i < all.length(); i++) {
            JSONObject f = all.getJSONObject(i);
            if (f.optBoolean("finished") || f.optBoolean("finished_provisional")) latest = Math.max(latest, f.optInt("event"));
        }
        List<Standing> now = tableOf(all, Integer.MAX_VALUE), before = tableOf(all, latest);
        Map<Integer, Integer> was = new HashMap<>();
        for (int i = 0; i < before.size(); i++) was.put(before.get(i).team, i);
        for (int i = 0; i < now.size(); i++) {
            Integer w = was.get(now.get(i).team);
            now.get(i).move = w == null || latest <= 1 ? 0 : w - i;
        }
        return now;
    }

    /** Điểm, hiệu số, bàn thắng giảm dần, như bảng chính thức. Chỉ tính trận đã kết thúc của các vòng trước `beforeEvent`. */
    private static List<Standing> tableOf(JSONArray all, int beforeEvent) throws Exception {
        Map<Integer, Standing> by = new HashMap<>();
        for (int i = 0; i < all.length(); i++) {
            JSONObject f = all.getJSONObject(i);
            if (!(f.optBoolean("finished") || f.optBoolean("finished_provisional"))) continue;
            if (f.optInt("event") >= beforeEvent) continue;
            if (f.isNull("team_h_score") || f.isNull("team_a_score")) continue;
            int h = f.getInt("team_h"), a = f.getInt("team_a"), hs = f.getInt("team_h_score"), as = f.getInt("team_a_score");
            Standing sh = by.computeIfAbsent(h, k -> { Standing s = new Standing(); s.team = k; return s; });
            Standing sa = by.computeIfAbsent(a, k -> { Standing s = new Standing(); s.team = k; return s; });
            sh.played++; sa.played++;
            sh.gf += hs; sh.ga += as; sa.gf += as; sa.ga += hs;
            if (hs > as) { sh.won++; sa.lost++; sh.pts += 3; }
            else if (hs < as) { sa.won++; sh.lost++; sa.pts += 3; }
            else { sh.drawn++; sa.drawn++; sh.pts++; sa.pts++; }
        }
        // đội chưa đá trận nào (đầu mùa) vẫn có dòng
        for (int i = 0; i < all.length(); i++) {
            JSONObject f = all.getJSONObject(i);
            for (String k : new String[]{"team_h", "team_a"}) {
                int t = f.getInt(k);
                by.computeIfAbsent(t, x -> { Standing s = new Standing(); s.team = x; return s; });
            }
        }
        List<Standing> out = new ArrayList<>(by.values());
        out.sort((x, y) -> x.pts != y.pts ? y.pts - x.pts : x.gd() != y.gd() ? y.gd() - x.gd() : x.gf != y.gf ? y.gf - x.gf : x.team - y.team);
        return out;
    }

    private static Bitmap badgeRaw(File dir, int code) {
        File f = new File(dir, "t" + code + ".png");
        try {
            if (!f.exists()) {
                byte[] png = bytes(String.format(java.util.Locale.ROOT, BADGE, code));
                File tmp = new File(dir, "t" + code + ".part");
                try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(png); }
                tmp.renameTo(f);
            }
            Bitmap raw = BitmapFactory.decodeFile(f.getPath());
            return raw == null ? null : dropTrademark(raw, code);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Ký hiệu ® dính liền viền logo (Man United, góc dưới phải): ở cỡ 28px thành chấm trắng lem. Xoá các điểm ảnh
     * trắng/xám (ít màu) trong đúng vùng góc đó của ảnh gốc 100x100; phần huy hiệu màu đỏ vàng không bị đụng.
     * Không dùng "giữ khối lớn nhất": chữ LFC của Liverpool là mảnh tách rời thật, sẽ bị xoá nhầm.
     */
    private static final Map<Integer, int[]> TRADEMARK = new HashMap<>();
    static {
        TRADEMARK.put(1, new int[]{71, 84, 100, 100});   // Man United: x0, y0, x1, y1 trên ảnh 100x100
    }

    private static Bitmap dropTrademark(Bitmap src, int code) {
        int[] r = TRADEMARK.get(code);
        if (r == null || src.getWidth() != 100) return src;
        Bitmap b = src.copy(Bitmap.Config.ARGB_8888, true);
        float[] hsv = new float[3];
        for (int y = r[1]; y < Math.min(r[3], b.getHeight()); y++) {
            for (int x = r[0]; x < Math.min(r[2], b.getWidth()); x++) {
                int c = b.getPixel(x, y);
                if ((c >>> 24) == 0) continue;
                android.graphics.Color.colorToHSV(c, hsv);
                if (hsv[1] < 0.3f) b.setPixel(x, y, 0);
            }
        }
        return b;
    }

    private static String get(String u) throws Exception { return new String(bytes(u), "UTF-8"); }

    private static byte[] bytes(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) GlassHome/0.1");
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private static String read(File f) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
    }

    private static void write(File f, String s) throws Exception {
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(s.getBytes("UTF-8")); }
    }

    private Epl() {}
}
