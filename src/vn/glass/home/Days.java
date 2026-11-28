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

// Ngày đáng nhớ trên bảng lịch: ngày lễ Việt Nam cài sẵn + ngày riêng của nhà (giỗ, sinh nhật, mùng 1, rằm...) đọc từ
// một Google Sheet công khai. Đường dẫn sheet chỉ nằm trên box (files/lich/sheet.txt), không nằm trong mã.
// Sheet viết cho người không rành máy tính: cột nhận theo tên tiêu đề (đổi thứ tự cột không sao), gồm Ngày (số),
// Tháng (số, hoặc chữ "Hằng tháng"), Lịch (Âm / Dương), Tên, Lời nhắc, Nhắc trước mấy ngày, Nhắc lại mấy ngày một lần,
// Hiện ở màn hình khoá (Có / Không). Dòng không có ngày (tiêu đề, hướng dẫn, dòng trống) tự bỏ qua. Sheet kiểu cũ
// (cột Ngày ghi "26/10", cột Loại) vẫn đọc được.
// Nhắc trước 1 ngày thì chỉ nhắc hôm trước; nhắc trước từ 2 ngày trở lên thì nhắc cả đúng hôm đó. Ngày âm đổi ra ngày dương theo từng năm (giỗ mỗi năm rơi một ngày khác). Bản đọc gần
// nhất cất trong prefs, 3 tiếng đọc lại một lần.
final class Days {
    static final class Ev {
        int d, m, y;          // y = 0: lặp lại hằng năm
        boolean lunar, mine;  // mine: ngày của nhà (từ sheet), không phải lễ chung
        String name, note;
        int before, every;    // nhắc trước bao nhiêu ngày (0: không nhắc), cứ bao nhiêu ngày nhắc lại
        boolean lock;         // cột "Màn hình khoá" = Yes / Có: dòng báo xoay vòng trên màn hình khoá
        /** Bao nhiêu ngày trước thì dòng cuối bảng lịch / màn hình khoá bắt đầu báo. */
        int window() { return !mine ? 7 : before > 0 ? before : 14; }
        boolean monthly() { return m == 0; }   // Tháng = "Hằng tháng"
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
                    hit = (e.monthly() || (l[3] == 0 && l[1] == e.m)) && (l[0] == e.d || (e.d == 30 && l[0] == 29 && lastOfMonth(dd, mm, yy)));
                } else {
                    hit = e.d == dd && (e.monthly() || e.m == mm);
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
    static Due reminder(Context c, java.util.Set<String> shown, boolean wake) { return next(c, shown, wake, false); }

    /** Mốc nhắc gần nhất đã tới khi còn n ngày (số ngày trước sự kiện của mốc đó), chưa tới mốc nào thì -1. */
    private static int slot(Ev e, int n) {
        if (n == 1) return 1;
        if (n == 0) return e.before >= 2 ? 0 : -1;
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
                String nm = e.name;
                if (e.monthly() && e.lunar) {   // "Mùng 1" -> "Mùng 1 tháng Mười Một" (mùng 1 tháng Giêng là Tết)
                    int[] l = Lunar.fromSolar(dd, mm, yy);
                    nm += l[1] == 1 && e.d == 1 && l[3] == 0 ? " Tết" : " tháng " + Lunar.monthName(l[1], l[3] == 1);
                }
                String what = nm + (e.note.isEmpty() ? "" : ", " + lower(e.note));
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
            readWeatherTab(c, u);
            return changed;
        } catch (Exception e) {
            android.util.Log.i("GlassDays", "sheet loi " + e);
            synchronized (Days.class) { readAt = System.currentTimeMillis() - EVERY + 15 * 60_000L; }   // 15 phút thử lại
            return false;
        }
    }

    /** Tab "Thời tiết" cùng sheet: ngưỡng nhiệt độ và lời khuyên mặc đồ cho bản tin. Tìm tab theo tên, lỗi thì giữ bản cũ. */
    private static void readWeatherTab(Context c, String u) {
        Matcher m = SHEET_ID.matcher(u);
        if (!m.find()) return;
        try {
            String csv = get("https://docs.google.com/spreadsheets/d/" + m.group(1)
                    + "/gviz/tq?tqx=out:csv&headers=1&sheet=" + java.net.URLEncoder.encode("Thời tiết", "UTF-8").replace("+", "%20"));
            if (csv.trim().startsWith("<")) throw new Exception("khong phai csv");
            int n = Morning.setRules(csv);
            if (n > 0) prefs(c).edit().putString("thoitiet_csv", csv).apply();
            android.util.Log.i("GlassDays", "tab thoi tiet " + n + " muc");
        } catch (Exception e) {
            android.util.Log.i("GlassDays", "tab thoi tiet loi " + e);
        }
    }

    /** Ngưỡng mặc đồ đã lưu từ lần đọc trước (dùng khi chưa đọc lại được sheet). */
    static String weatherCsv(Context c) { return prefs(c).getString("thoitiet_csv", ""); }

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

    private static String low(String s) { return s == null ? "" : s.trim().toLowerCase(new java.util.Locale("vi")); }

    /** Vị trí các cột theo tên tiêu đề; chưa thấy tiêu đề thì theo thứ tự kiểu cũ. */
    private static final class Cols {
        int day = 0, month = -1, kind = 1, name = 2, note = 3, before = 4, every = 5, lock = 6;
        boolean read(List<String> f) {
            int d = -1, mo = -1, k = -1, n = -1, no = -1, b = -1, e = -1, l = -1;
            for (int i = 0; i < f.size(); i++) {
                String h = low(f.get(i));
                if (h.contains("nhắc trước") || h.contains("nhac truoc")) b = i;
                else if (h.contains("nhắc lại") || h.contains("nhac lai")) e = i;
                else if (h.contains("khoá") || h.contains("khóa") || h.contains("khoa")) l = i;
                else if (h.contains("lời nhắc") || h.contains("ghi chú")) no = i;
                else if (h.startsWith("tháng")) mo = i;
                else if (h.startsWith("ngày")) d = i;
                else if (h.startsWith("lịch") || h.startsWith("loại") || h.contains("âm")) k = i;
                else if (h.startsWith("tên")) n = i;
            }
            if (d < 0 || n < 0) return false;
            day = d; month = mo; kind = k; name = n; note = no; before = b; every = e; lock = l;
            return true;
        }
    }

    private static String at(List<String> f, int i) { return i >= 0 && i < f.size() ? f.get(i).trim() : ""; }

    private static final Pattern DAY_ONLY = Pattern.compile("^\\s*'?(\\d{1,2})\\s*$");

    static List<Ev> parse(String csv) {
        List<Ev> out = new ArrayList<>();
        if (csv == null || csv.isEmpty()) return out;
        Cols col = new Cols();
        for (String line : csv.split("\r?\n")) {
            List<String> f = cells(line);
            if (f.size() < 3) continue;
            String dayCell = at(f, col.day);
            Matcher full = DATE.matcher(dayCell), only = DAY_ONLY.matcher(dayCell);
            Ev e = new Ev();
            if (col.month >= 0 && only.find()) {
                // kiểu mới: Ngày là số, Tháng là số hoặc "Hằng tháng"
                e.d = Integer.parseInt(only.group(1));
                String mo = low(at(f, col.month));
                if (mo.contains("hằng") || mo.contains("hàng") || mo.contains("mọi") || mo.contains("hang") || mo.equals("*")) e.m = 0;
                else { e.m = num(mo); if (e.m < 1 || e.m > 12) continue; }
            } else if (full.find()) {
                // kiểu cũ: "26/10" hoặc "26/10/2027" (việc một lần)
                e.d = Integer.parseInt(full.group(1)); e.m = Integer.parseInt(full.group(2));
                e.y = full.group(3) == null ? 0 : Integer.parseInt(full.group(3));
                if (e.m < 1 || e.m > 12) continue;
            } else {
                if (col.read(f)) continue;   // dòng tiêu đề: nhớ vị trí cột
                continue;                    // dòng hướng dẫn, dòng trống
            }
            if (e.d < 1 || e.d > 31) continue;
            String kind = low(at(f, col.kind));
            e.lunar = kind.startsWith("âm") || kind.startsWith("am") || kind.equals("al");
            e.name = at(f, col.name);
            if (e.name.isEmpty()) continue;
            e.note = at(f, col.note);
            e.before = num(at(f, col.before));
            e.every = num(at(f, col.every));
            String lk = low(at(f, col.lock));
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
    static List<String> cells(String line) {
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
