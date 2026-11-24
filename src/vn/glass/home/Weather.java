package vn.glass.home;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

// Thời tiết hôm nay + chất lượng không khí ở Hà Nội cho bảng lịch. Thời tiết từ Open-Meteo (miễn phí, không khoá).
// AQI ưu tiên WAQI (aqicn.org): số đo thật ở trạm Hà Nội, thang US AQI, sát IQAir; cần token miễn phí, token chỉ nằm
// trên box (files/aqi/token.txt), không nằm trong mã. Không có token hay WAQI lỗi thì dùng AQI ước tính của
// Open-Meteo (số mô hình, ở Hà Nội thường thấp hơn trạm đo nhiều). Lần lấy gần nhất cất vào prefs nên
// mở bảng là có số ngay (kể cả lúc mới bật box), số mới về thì thay vào. Thời tiết 30 phút đọc lại, AQI mỗi giờ.
final class Weather {
    static final class Now {
        int temp, hi, lo, rain, hum, code, aqi = -1, pm25 = -1;
        int feels, wind, gust, rainSoon;   // cảm giác như (°C), gió / gió giật (km/h), khả năng mưa cao nhất 3 giờ tới (%)
        float precip;                      // lượng mưa giờ hiện tại (mm)
        String aqiSrc = "";   // giờ đo ở trạm WAQI ("" khi là số ước tính Open-Meteo)
        boolean day = true;
        String rise = "", set = "";
        long wxAt, aqiAt;
        boolean hasWx() { return wxAt > 0; }
    }

    private static final String LAT = "21.0285", LON = "105.8542";
    private static final String WX = "https://api.open-meteo.com/v1/forecast?latitude=" + LAT + "&longitude=" + LON
            + "&current=temperature_2m,relative_humidity_2m,weather_code,is_day,apparent_temperature,wind_speed_10m,wind_gusts_10m,precipitation"
            + "&hourly=precipitation_probability&forecast_hours=4"
            + "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset"
            + "&timezone=Asia%2FBangkok&forecast_days=1";
    private static final String AQ = "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=" + LAT
            + "&longitude=" + LON + "&current=us_aqi,pm2_5&timezone=Asia%2FBangkok";
    private static final String WAQI = "https://api.waqi.info/feed/hanoi/?token=";
    private static final long WX_EVERY = 30 * 60_000L, AQ_EVERY = 60 * 60_000L;

    private static Now cur;

    /** Số đang có (từ bộ nhớ hoặc prefs); chưa từng lấy được thì null. */
    static synchronized Now cached(Context c) {
        if (cur == null) cur = restore(prefs(c));
        return cur;
    }

    /** Số thời tiết đã cũ hơn maxAgeMs (hoặc chưa có): bản tin sáng cần số mới, đọc lại trước khi hiện. */
    static boolean olderThan(Context c, long maxAgeMs) {
        Now n = cached(c);
        return n == null || System.currentTimeMillis() - n.wxAt > maxAgeMs;
    }

    /** Đọc lại ngay cả phần chưa tới hạn (bản tin sáng): coi như số cũ đã hết hạn. */
    static Now refreshNow(Context c) { return refresh(c, true); }

    static boolean stale(Context c) {
        Now n = cached(c);
        long now = System.currentTimeMillis();
        return n == null || now - n.wxAt > WX_EVERY || aqiDue(c, n, now);
    }

    /** Đọc lại phần đã cũ (chạy ở luồng nền); trả về số mới nhất, lỗi thì giữ số cũ. */
    static Now refresh(Context c) { return refresh(c, false); }

    /** force: bản tin sáng, thời tiết cũ hơn 10 phút hay AQI cũ hơn 20 phút cũng đọc lại. */
    private static Now refresh(Context c, boolean force) {
        Now old = cached(c);
        Now n = new Now();
        if (old != null) copy(old, n);
        long now = System.currentTimeMillis();
        boolean changed = false;
        if (old == null || now - old.wxAt > (force ? 10 * 60_000L : WX_EVERY)) {
            try {
                JSONObject o = new JSONObject(get(WX));
                JSONObject k = o.getJSONObject("current"), d = o.getJSONObject("daily");
                n.temp = (int) Math.round(k.getDouble("temperature_2m"));
                n.hum = k.optInt("relative_humidity_2m", 0);
                n.code = k.optInt("weather_code", 0);
                n.day = k.optInt("is_day", 1) == 1;
                n.feels = (int) Math.round(k.optDouble("apparent_temperature", k.getDouble("temperature_2m")));
                n.wind = (int) Math.round(k.optDouble("wind_speed_10m", 0));
                n.gust = (int) Math.round(k.optDouble("wind_gusts_10m", 0));
                n.precip = (float) k.optDouble("precipitation", 0);
                n.rainSoon = 0;
                org.json.JSONArray pp = o.optJSONObject("hourly") == null ? null : o.getJSONObject("hourly").optJSONArray("precipitation_probability");
                for (int i = 0; pp != null && i < Math.min(4, pp.length()); i++) n.rainSoon = Math.max(n.rainSoon, pp.optInt(i, 0));
                n.hi = (int) Math.round(d.getJSONArray("temperature_2m_max").getDouble(0));
                n.lo = (int) Math.round(d.getJSONArray("temperature_2m_min").getDouble(0));
                n.rain = d.getJSONArray("precipitation_probability_max").optInt(0, 0);
                n.rise = hhmm(d.getJSONArray("sunrise").optString(0, ""));
                n.set = hhmm(d.getJSONArray("sunset").optString(0, ""));
                n.wxAt = now;
                changed = true;
            } catch (Exception e) { android.util.Log.i("GlassWx", "thoi tiet loi " + e); }
        }
        if (old == null || aqiDue(c, old, now) || (force && now - old.aqiAt > 20 * 60_000L)) {
            String token = token(c);
            if (token != null) try {
                JSONObject o = new JSONObject(get(WAQI + java.net.URLEncoder.encode(token, "UTF-8")));
                if (!"ok".equals(o.optString("status"))) throw new Exception("waqi " + o.optString("data"));
                JSONObject d = o.getJSONObject("data");
                int v = d.optInt("aqi", -1);   // trạm mất số thì là "-"
                if (v < 0) throw new Exception("waqi khong co so");
                n.aqi = v;
                n.pm25 = -1;   // WAQI chỉ cho chỉ số con, không cho nồng độ µg/m³
                // tên trạm quá dài cho ô nhỏ: ghi giờ đo ("19:00") để biết số mới hay cũ
                String at = d.optJSONObject("time") != null ? d.getJSONObject("time").optString("s", "") : "";
                n.aqiSrc = at.length() >= 16 ? at.substring(11, 16) : "?";
                n.aqiAt = now;
                changed = true;
                token = null;   // xong, khỏi dự phòng
            } catch (Exception e) { android.util.Log.i("GlassWx", "waqi loi " + e.getClass().getSimpleName()); }
            if (n.aqiAt != now) try {
                JSONObject k = new JSONObject(get(AQ)).getJSONObject("current");
                n.aqi = (int) Math.round(k.getDouble("us_aqi"));
                n.pm25 = (int) Math.round(k.optDouble("pm2_5", -1));
                n.aqiSrc = "";
                n.aqiAt = now;
                changed = true;
            } catch (Exception e) { android.util.Log.i("GlassWx", "aqi loi " + e); }
        }
        if (!changed) return old;
        synchronized (Weather.class) { cur = n; }
        save(prefs(c), n);
        return n;
    }

    /** Mô tả ngắn theo mã thời tiết WMO. */
    static String describe(int code) {
        switch (code) {
            case 0: return "Trời quang";
            case 1: return "Ít mây";
            case 2: return "Có mây";
            case 3: return "Nhiều mây";
            case 45: case 48: return "Sương mù";
            case 51: return "Mưa phùn nhẹ";
            case 53: case 56: return "Mưa phùn";
            case 55: case 57: return "Mưa phùn dày";
            case 61: return "Mưa nhỏ";
            case 63: case 66: return "Mưa vừa";
            case 65: case 67: return "Mưa to";
            case 71: case 73: case 75: case 77: case 85: case 86: return "Có tuyết";
            case 80: return "Mưa rào nhẹ";
            case 81: return "Mưa rào";
            case 82: return "Mưa rào to";
            case 95: return "Có dông";
            case 96: case 99: return "Dông, mưa đá";
            default: return "";
        }
    }

    /** Mức AQI (thang Việt Nam dùng cùng ngưỡng US AQI): {tên, màu}. */
    static String aqiName(int v) {
        return v <= 50 ? "Tốt" : v <= 100 ? "Trung bình" : v <= 150 ? "Kém" : v <= 200 ? "Xấu" : v <= 300 ? "Rất xấu" : "Nguy hại";
    }

    static int aqiColor(int v) {
        // cùng tông dịu với xanh/đỏ của thẻ giá, không bão hoà gắt
        return v <= 50 ? 0xFF7FD99C : v <= 100 ? 0xFFEFD27A : v <= 150 ? 0xFFFFA866
                : v <= 200 ? 0xFFF5847A : v <= 300 ? 0xFFC59AE8 : 0xFFC0707E;
    }

    /** AQI tới lượt đọc lại: quá 1 giờ, hoặc đang là số ước tính mà đã có token WAQI (đọc lại sau 5 phút). */
    private static boolean aqiDue(Context c, Now n, long now) {
        if (now - n.aqiAt > AQ_EVERY) return true;
        return n.aqiSrc.isEmpty() && now - n.aqiAt > 5 * 60_000L && token(c) != null;
    }

    /** Token WAQI trên box (files/aqi/token.txt); không có thì null. Không bao giờ ghi token ra log. */
    private static String token(Context c) {
        try {
            java.io.File f = new java.io.File(new java.io.File(c.getExternalFilesDir(null), "aqi"), "token.txt");
            if (!f.isFile()) return null;
            String t = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8").trim();
            return t.isEmpty() ? null : t;
        } catch (Exception e) { return null; }
    }

    private static String hhmm(String iso) {
        int t = iso.indexOf('T');
        return t >= 0 && iso.length() >= t + 6 ? iso.substring(t + 1, t + 6) : "";
    }

    private static void copy(Now a, Now b) {
        b.temp = a.temp; b.hi = a.hi; b.lo = a.lo; b.rain = a.rain; b.hum = a.hum; b.code = a.code; b.day = a.day;
        b.rise = a.rise; b.set = a.set; b.wxAt = a.wxAt; b.aqi = a.aqi; b.pm25 = a.pm25; b.aqiAt = a.aqiAt;
        b.aqiSrc = a.aqiSrc;
        b.feels = a.feels; b.wind = a.wind; b.gust = a.gust; b.precip = a.precip; b.rainSoon = a.rainSoon;
    }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("glass", Context.MODE_PRIVATE); }

    private static void save(SharedPreferences p, Now n) {
        try {
            JSONObject o = new JSONObject();
            o.put("t", n.temp).put("hi", n.hi).put("lo", n.lo).put("r", n.rain).put("h", n.hum).put("c", n.code)
                    .put("d", n.day).put("sr", n.rise).put("ss", n.set).put("wa", n.wxAt)
                    .put("q", n.aqi).put("pm", n.pm25).put("qa", n.aqiAt).put("qs", n.aqiSrc)
                    .put("fl", n.feels).put("wi", n.wind).put("gu", n.gust).put("pr", n.precip).put("rs", n.rainSoon);
            p.edit().putString("weather", o.toString()).apply();
        } catch (Exception ignored) {}
    }

    private static Now restore(SharedPreferences p) {
        try {
            String s = p.getString("weather", null);
            if (s == null) return null;
            JSONObject o = new JSONObject(s);
            Now n = new Now();
            n.temp = o.optInt("t"); n.hi = o.optInt("hi"); n.lo = o.optInt("lo"); n.rain = o.optInt("r");
            n.hum = o.optInt("h"); n.code = o.optInt("c"); n.day = o.optBoolean("d", true);
            n.rise = o.optString("sr", ""); n.set = o.optString("ss", ""); n.wxAt = o.optLong("wa");
            n.aqi = o.optInt("q", -1); n.pm25 = o.optInt("pm", -1); n.aqiAt = o.optLong("qa");
            n.aqiSrc = o.optString("qs", "");
            n.feels = o.optInt("fl", n.temp); n.wind = o.optInt("wi"); n.gust = o.optInt("gu");
            n.precip = (float) o.optDouble("pr", 0); n.rainSoon = o.optInt("rs");
            if (!o.has("wi")) n.wxAt = 0;   // lưu từ bản cũ (chưa có gió, cảm giác như): coi như hết hạn, đọc lại
            return n;
        } catch (Exception e) { return null; }
    }

    private static String get(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(7000);
        c.setRequestProperty("User-Agent", "GlassHome/0.1");
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private Weather() {}
}
