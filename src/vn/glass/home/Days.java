package vn.glass.home;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Ngày đáng nhớ trên bảng lịch: ngày lễ Việt Nam cài sẵn + ngày riêng của nhà (giỗ, sinh nhật...) đọc từ một Google
// Sheet công khai. Đường dẫn sheet chỉ nằm trên box (files/lich/sheet.txt), không nằm trong mã. Sheet gồm các cột
// Ngày (d/m, hoặc d/m/yyyy cho việc một lần), Loại ("âm" hoặc "dương", bỏ trống là dương), Tên, Ghi chú, Nhắc trước
// (số ngày), Nhắc lại (cứ bao nhiêu ngày nhắc một lần); dòng tiêu đề và dòng sai dạng tự bỏ qua. Ngày âm đổi ra ngày dương theo từng năm (giỗ mỗi năm rơi một ngày khác). Bản đọc gần
// nhất cất trong prefs, 3 tiếng đọc lại một lần.
final class Days {
    static final class Ev {
        int d, m, y;          // y = 0: lặp lại hằng năm
        boolean lunar, mine;  // mine: ngày của nhà (từ sheet), không phải lễ chung
        String name, note;
        int before, every;    // nhắc trước bao nhiêu ngày (0: không nhắc), cứ bao nhiêu ngày nhắc lại
        boolean lock;         // cột "Màn hình khoá" = Yes / Có: dòng báo xoay vòng trên màn hình khoá
        /** Bao nhiêu ngày trước thì dòng cuối bảng lịch / màn hình khoá bắt đầu báo. */
        int window() { return mine ? Math.max(14, before) : 7; }
    }

    /** Một lần nhắc: sự kiện, còn bao nhiêu ngày, ngày dương rơi vào (khoá để biết đã nhắc chưa). */
    static final class Due {
        Ev e; int days, dd, mm, yy; String key, text;
        /** "Thứ Sáu 4/12, 26 tháng Mười âm lịch" (ngày dương thì chỉ thứ và ngày). */
        String when() {
            Calendar on = Calendar.getInstance();
            on.set(yy, mm - 1, dd);
            String s = THU[on.get(Calendar.DAY_OF_WEEK) - 1] + " " + dd + "/" + mm;
            return e.lunar ? s + ", " + e.d + " tháng " + Lunar.monthName(e.m, false) + " âm lịch" : s;
        }
    }

    private static final long EVERY = 3 * 3600_000L;
    private static final String[] THU = {"Chủ Nhật", "Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy"};
    private static List<Ev> mine;
    private static long readAt;

    private static final List<Ev> HOLIDAYS = new ArrayList<>();
    static {
        h(1, 1, false, "Tết Dương lịch"); h(8, 3, false, "Quốc tế Phụ nữ"); h(30, 4, false, "Ngày Thống nhất");
        h(1, 5, false, "Quốc tế Lao động"); h(1, 6, false, "Quốc tế Thiếu nhi"); h(2, 9, false, "Quốc khánh");
        h(20, 10, false, "Phụ nữ Việt Nam"); h(20, 11, false, "Nhà giáo Việt Nam"); h(24, 12, false, "Giáng sinh");
        h(1, 1, true, "Tết Nguyên đán"); h(15, 1, true, "Rằm tháng Giêng"); h(10, 3, true, "Giỗ Tổ Hùng Vương");
        h(5, 5, true, "Tết Đoan ngọ"); h(15, 7, true, "Rằm tháng Bảy"); h(15, 8, true, "Tết Trung thu");
        h(23, 12, true, "Ông Công ông Táo");
    }

    private static void h(int d, int m, boolean lunar, String name) {
        Ev e = new Ev(); e.d = d; e.m = m; e.lunar = lunar; e.name = name; e.note = "";
        HOLIDAYS.add(e);
    }

    /** Sự kiện rơi vào một ngày dương (ngày của nhà đứng trước lễ chung). */
    static List<Ev> on(Context c, int dd, int mm, int yy) {
        List<Ev> out = new ArrayList<>();
        int[] l = null;
        for (int pass = 0; pass < 2; pass++) {
            for (Ev e : pass == 0 ? mine(c) : HOLIDAYS) {
                if (e.y != 0 && e.y != yy) continue;
                boolean hit;
                if (e.lunar) {
                    if (l == null) l = Lunar.fromSolar(dd, mm, yy);
                    hit = l[3] == 0 && l[1] == e.m && (l[0] == e.d || (e.d == 30 && l[0] == 29 && lastOfMonth(dd, mm, yy)));
                } else {
                    hit = e.d == dd && e.m == mm;
                }
                if (hit) out.add(e);
            }
        }
        return out;
    }

    /** Ngày âm 29 là ngày cuối tháng (tháng thiếu): giỗ ngày 30 thì làm vào hôm đó. */
    private static boolean lastOfMonth(int dd, int mm, int yy) {
        Calendar n = Calendar.getInstance();
        n.set(yy, mm - 1, dd);
        n.add(Calendar.DAY_OF_MONTH, 1);
        return Lunar.fromSolar(n.get(Calendar.DAY_OF_MONTH), n.get(Calendar.MONTH) + 1, n.get(Calendar.YEAR))[0] == 1;
    }

    /**
     * Dòng báo ngày sắp tới cho đáy bảng lịch: ngày của nhà trong 14 ngày tới, lễ chung trong 7 ngày tới; không có thì "".
     * "Hôm nay là Giỗ cụ, về quê", "Mai là ...", "Còn 5 ngày nữa là ...".
     */
    static String upcoming(Context c) {
        Due u = next(c, null, false, false);
        return u == null ? "" : u.text;
    }

    /** Ngày sắp tới cho dòng cuối bảng lịch (kèm ngày rơi vào); không có thì null. */
    static Due upcomingDue(Context c) { return next(c, null, false, false); }

    /** Dòng cho màn hình khoá: ngày của nhà có cột "Màn hình khoá" = Yes, đang trong khoảng nhắc; không có thì "". */
    static String lockLine(Context c) {
        Due u = next(c, null, false, true);
        return u == null ? "" : u.text;
    }

    /**
     * Hôm nay có phải ngày nhắc không: ngày của nhà có "Nhắc trước" N ngày, nhắc lần đầu đúng N ngày trước, rồi cứ
     * "Nhắc lại" ngày nhắc một lần (trống thì chỉ lần đầu), thêm hôm trước và đúng hôm đó. Nhắc bù: lần nhắc đã tới
     * mà chưa hiện (box tắt đúng hôm đó, ngày mới thêm vào sheet) thì hiện ở lần mở kế tiếp. Mỗi lần nhắc (khoá
     * theo ngày sự kiện + mốc nhắc) chỉ hiện một lần; shown là các khoá đã hiện. Riêng đúng ngày nhắc thì mỗi lần bật
     * tivi (wake) đều nhắc lại. Không có thì null.
     */
    static Due reminder(Context c, java.util.Set<String> shown, boolean wake) {
        Due u = next(c, shown, wake, false);
        return u != null ? u : incense(shown, wake);
    }

    /**
     * Trước mùng 1, rằm (15 âm lịch) và ông Công ông Táo (23 tháng Chạp) đúng một ngày: "Mai mùng 1 tháng Mười Một,
     * nhớ chuẩn bị đồ thắp hương". Cùng
     * nhịp với ngày nhắc của nhà: hôm đó mỗi lần bật tivi nhắc một lần (ngày của nhà đến hạn thì nhường ngày của nhà).
     */
    private static Due incense(java.util.Set<String> shown, boolean wake) {
        Calendar t = Calendar.getInstance();
        t.add(Calendar.DAY_OF_MONTH, 1);
        int dd = t.get(Calendar.DAY_OF_MONTH), mm = t.get(Calendar.MONTH) + 1, yy = t.get(Calendar.YEAR);
        int[] l = Lunar.fromSolar(dd, mm, yy);
        boolean tao = l[0] == 23 && l[1] == 12 && l[3] == 0;
        if (l[0] != 1 && l[0] != 15 && !tao) return null;
        Due u = new Due();
        Ev e = new Ev();
        e.d = l[0]; e.m = l[1]; e.lunar = true; e.name = tao ? "Ông Công ông Táo" : l[0] == 1 ? "Mùng 1" : "Rằm"; e.note = "";
        u.e = e; u.days = 1; u.dd = dd; u.mm = mm; u.yy = yy;
        u.key = "huong@" + dd + "/" + mm + "/" + yy;
        if (shown.contains(u.key) && !wake) return null;
        String what = tao ? "ông Công ông Táo"
                : l[0] == 1 && l[1] == 1 && l[3] == 0 ? "mùng 1 Tết"
                : (l[0] == 1 ? "mùng 1" : "rằm") + " tháng " + Lunar.monthName(l[1], l[3] == 1);
        u.text = "Mai " + what + ", nhớ chuẩn bị đồ thắp hương";
        return u;
    }

    /** Mốc nhắc gần nhất đã tới khi còn n ngày (số ngày trước sự kiện của mốc đó), chưa tới mốc nào thì -1. */
    private static int slot(Ev e, int n) {
        if (n <= 1) return n;
        int best = -1;
        for (int s = e.before; s > 1; s -= e.every > 0 ? e.every : e.before) if (s >= n) best = s;
        return best >= 0 ? best : (n <= 1 ? n : -1);
    }

    private static Due next(Context c, java.util.Set<String> shown, boolean wake, boolean lockOnly) {
        boolean dueOnly = shown != null;
        int max = 14;
        for (Ev e : mine(c)) max = Math.max(max, e.window());
        Calendar d = Calendar.getInstance();
        for (int n = 0; n <= Math.min(max, 400); n++) {
            int dd = d.get(Calendar.DAY_OF_MONTH), mm = d.get(Calendar.MONTH) + 1, yy = d.get(Calendar.YEAR);
            for (Ev e : on(c, dd, mm, yy)) {
                if (n > e.window()) continue;
                if (lockOnly && !(e.mine && e.lock)) continue;
                if (e.lunar && e.d == 1 && e.m == 1 && !e.mine) continue;   // Tết đã có dòng đếm ngược riêng
                if (dueOnly) {
                    if (!e.mine || e.before <= 0 || n > e.before) continue;
                }
                Due u = new Due();
                u.e = e; u.days = n; u.dd = dd; u.mm = mm; u.yy = yy;
                u.key = e.name + "@" + dd + "/" + mm + "/" + yy + "#" + (dueOnly ? slot(e, n) : n);
                int sl = dueOnly ? slot(e, n) : n;
                if (dueOnly && (sl < 0 || (shown.contains(u.key) && !(wake && sl == n)))) continue;
                String what = e.name + (e.note.isEmpty() ? "" : ", " + lower(e.note));
                u.text = n == 0 ? "Hôm nay là " + what : n == 1 ? "Mai là " + what : "Còn " + n + " ngày nữa là " + what;
                return u;
            }
            d.add(Calendar.DAY_OF_MONTH, 1);
        }
        return null;
    }

    private static String lower(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toLowerCase(new java.util.Locale("vi")) + s.substring(1);
    }

    // ---------- ngày của nhà từ Google Sheet ----------

    static synchronized List<Ev> mine(Context c) {
        if (mine == null) {
            SharedPreferences p = prefs(c);
            mine = parse(p.getString("ngay_csv", ""));
            readAt = p.getLong("ngay_at", 0);
        }
        return mine;
    }

    static boolean stale(Context c) {
        mine(c);
        return sheetUrl(c) != null && System.currentTimeMillis() - readAt > EVERY;
    }

    /** Đọc lại sheet (luồng nền); true khi danh sách đổi. Lỗi thì giữ bản cũ. */
    static boolean refresh(Context c) {
        String u = sheetUrl(c);
        if (u == null) return false;
        try {
            String csv = get(u);
            if (csv.trim().startsWith("<")) throw new Exception("khong phai csv");   // sheet chưa công khai: trang đăng nhập
            List<Ev> l = parse(csv);
            SharedPreferences p = prefs(c);
            boolean changed = !csv.equals(p.getString("ngay_csv", ""));
            p.edit().putString("ngay_csv", csv).putLong("ngay_at", System.currentTimeMillis()).apply();
            synchronized (Days.class) { mine = l; readAt = System.currentTimeMillis(); }
            android.util.Log.i("GlassDays", "sheet " + l.size() + " ngay");
            return changed;
        } catch (Exception e) {
            android.util.Log.i("GlassDays", "sheet loi " + e);
            synchronized (Days.class) { readAt = System.currentTimeMillis() - EVERY + 15 * 60_000L; }   // 15 phút thử lại
            return false;
        }
    }

    private static final Pattern SHEET_ID = Pattern.compile("/spreadsheets/d/([A-Za-z0-9_-]{20,})");

    /** Đường dẫn tải CSV từ link sheet ghi trong files/lich/sheet.txt (link chia sẻ hay link CSV đều được). */
    private static String sheetUrl(Context c) {
        try {
            File f = new File(new File(c.getExternalFilesDir(null), "lich"), "sheet.txt");
            if (!f.isFile()) return null;
            String s = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8").trim();
            Matcher m = SHEET_ID.matcher(s);
            if (!m.find()) return s.startsWith("https://") ? s : null;
            Matcher g = Pattern.compile("[#?&]gid=(\\d+)").matcher(s);
            return "https://docs.google.com/spreadsheets/d/" + m.group(1) + "/export?format=csv"
                    + (g.find() ? "&gid=" + g.group(1) : "");
        } catch (Exception e) { return null; }
    }

    private static final Pattern DATE = Pattern.compile("^\\s*'?(\\d{1,2})\\s*[/.-]\\s*(\\d{1,2})(?:\\s*[/.-]\\s*(\\d{4}))?\\s*$");

    static List<Ev> parse(String csv) {
        List<Ev> out = new ArrayList<>();
        if (csv == null || csv.isEmpty()) return out;
        for (String line : csv.split("\r?\n")) {
            List<String> f = cells(line);
            if (f.size() < 3) continue;
            Matcher m = DATE.matcher(f.get(0));
            if (!m.find()) continue;   // dòng tiêu đề, dòng trống
            Ev e = new Ev();
            e.d = Integer.parseInt(m.group(1)); e.m = Integer.parseInt(m.group(2));
            e.y = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
            if (e.d < 1 || e.d > 31 || e.m < 1 || e.m > 12) continue;
            String kind = f.get(1).trim().toLowerCase(new java.util.Locale("vi"));
            e.lunar = kind.startsWith("âm") || kind.startsWith("am") || kind.equals("al");
            e.name = f.get(2).trim();
            if (e.name.isEmpty()) continue;
            e.note = f.size() > 3 ? f.get(3).trim() : "";
            e.before = f.size() > 4 ? num(f.get(4)) : 0;
            e.every = f.size() > 5 ? num(f.get(5)) : 0;
            String lk = f.size() > 6 ? f.get(6).trim().toLowerCase(new java.util.Locale("vi")) : "";
            e.lock = lk.startsWith("y") || lk.startsWith("có") || lk.startsWith("co") || lk.equals("x") || lk.equals("true");
            e.mine = true;
            out.add(e);
        }
        return Collections.unmodifiableList(out);
    }

    private static int num(String s) {
        Matcher m = Pattern.compile("(\\d{1,3})").matcher(s);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** Tách một dòng CSV (có ô trong ngoặc kép). */
    private static List<String> cells(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder b = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (q) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { b.append('"'); i++; }
                else if (ch == '"') q = false;
                else b.append(ch);
            } else if (ch == '"') q = true;
            else if (ch == ',') { out.add(b.toString()); b.setLength(0); }
            else b.append(ch);
        }
        out.add(b.toString());
        return out;
    }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("glass", Context.MODE_PRIVATE); }

    private static String get(String u) throws Exception {
        for (int hop = 0; hop < 4; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "GlassHome/0.1");
            try {
                int code = c.getResponseCode();
                if (code / 100 == 3) { u = c.getHeaderField("Location"); continue; }   // Google chuyển sang máy chủ tải
                if (code != 200) throw new Exception("http " + code);
                try (InputStream in = c.getInputStream(); ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0 && bo.size() < 512 * 1024) bo.write(buf, 0, n);
                    return bo.toString("UTF-8");
                }
            } finally {
                c.disconnect();
            }
        }
        throw new Exception("chuyen huong qua nhieu");
    }

    private Days() {}
}
