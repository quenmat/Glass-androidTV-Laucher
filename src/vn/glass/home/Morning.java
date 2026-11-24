package vn.glass.home;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

// Bản tin thời tiết mỗi lần bật tivi; buổi sáng ngày thường (thứ Hai tới thứ Sáu, 5:30 tới 8:30) thì đọc to: cả nhà đi học, đi làm bằng xe máy, nên báo trời đang thế nào,
// nhiệt độ, gió, không khí, và khuyên mặc đồ theo ngưỡng nhiệt độ (đi xe máy lạnh hơn đi bộ). Đang mưa hoặc gió lớn
// thì tính như lạnh hơn 1 độ. Một nội dung cho cả thẻ trên màn hình lẫn giọng đọc.
final class Morning {
    static final class Tip {
        final int color; final String text; final boolean strong;
        Tip(int color, String text, boolean strong) { this.color = color; this.text = text; this.strong = strong; }
    }

    static final class Brief {
        String head, desc, sub, speech;
        int temp, icon, aqi = -1;
        final List<Tip> tips = new ArrayList<>();
    }

    private static final int WARM = 0xFFFFC478, BLUE = 0xFF84BBFF, RED = 0xFFF5847A, GREEN = 0xFF7FD99C;
    private static final String[] THU = {"CHỦ NHẬT", "THỨ HAI", "THỨ BA", "THỨ TƯ", "THỨ NĂM", "THỨ SÁU", "THỨ BẢY"};

    /** Giờ phát bản tin: ngày thường, 5:30 tới 8:30. */
    static boolean window(Calendar c) {
        int dow = c.get(Calendar.DAY_OF_WEEK);
        if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) return false;
        int m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        return m >= 5 * 60 + 30 && m <= 8 * 60 + 30;
    }

    static boolean raining(Weather.Now w) {
        int k = w.code;
        return w.precip >= 0.1f || (k >= 51 && k <= 67) || (k >= 80 && k <= 82) || k >= 95;
    }

    static boolean windy(Weather.Now w) { return w.wind >= 20 || w.gust >= 40; }

    static Brief build(Weather.Now w) {
        Brief b = new Brief();
        Calendar c = Calendar.getInstance();
        int hr = c.get(Calendar.HOUR_OF_DAY);
        String part = hr >= 4 && hr < 10 ? "SÁNG NAY" : hr < 13 && hr >= 10 ? "TRƯA NAY" : hr >= 13 && hr < 18 ? "CHIỀU NAY" : "TỐI NAY";
        b.head = part + ", " + THU[c.get(Calendar.DAY_OF_WEEK) - 1] + " " + c.get(Calendar.DAY_OF_MONTH) + "/" + (c.get(Calendar.MONTH) + 1);
        b.temp = w.temp;
        b.icon = WxIcon.kind(w.code, w.day);
        b.aqi = w.aqi;
        boolean rain = raining(w), wind = windy(w);
        String sky = Weather.describe(w.code);
        if (sky.isEmpty()) sky = rain ? "Có mưa" : "Trời ổn";
        b.desc = sky + (wind ? ", gió khá mạnh" : "");
        b.sub = "Gió " + w.wind + " km/h" + (w.gust >= 40 ? ", giật " + w.gust : "") + ", cảm giác như " + w.feels + "°";

        // mặc gì khi đi xe máy (đang mưa / gió lớn: lạnh hơn 1 độ)
        int t = w.temp - (rain || wind ? 1 : 0);
        List<String> say = new ArrayList<>();
        if (t >= 24) add(b, say, WARM, "Trời ấm áp, mặc mỏng cho mát", true);
        else if (t >= 20) add(b, say, WARM, "Đi xe máy nên đem theo áo khoác mỏng", true);
        else if (t >= 16) add(b, say, WARM, "Đi xe máy nên mặc áo khoác dày", true);
        else if (t >= 13) add(b, say, WARM, "Nên mặc áo phao cho ấm", true);
        else if (t >= 10) add(b, say, RED, "Hôm nay rét rất đậm, hãy mặc ấm nhất có thể khi ra đường", true);
        else {
            add(b, say, RED, "Rét hại, cân nhắc cho trẻ nghỉ học", true);
            add(b, say, WARM, "Phụ huynh nên mặc áo thật ấm khi ra đường", false);
        }
        // mưa
        if (rain) add(b, say, BLUE, "Trời đang mưa, nhớ mặc áo mưa", false);
        else if (w.rainSoon >= 50) add(b, say, BLUE, "Sáng nay có thể mưa, mang theo áo mưa", false);
        // không khí
        // không khí: thẻ ghi lời khuyên, giọng đọc kèm luôn chỉ số (một câu, khỏi nhắc hai lần)
        String idx = "chỉ số " + w.aqi;
        if (w.aqi > 200) addSay(b, say, RED, "Chất lượng không khí rất xấu, đeo khẩu trang chống bụi mịn, hạn chế ra ngoài",
                "Chất lượng không khí rất xấu, " + idx + ", đeo khẩu trang chống bụi mịn, hạn chế ra ngoài");
        else if (w.aqi > 150) addSay(b, say, RED, "Chất lượng không khí xấu, nhớ đeo khẩu trang chống bụi mịn",
                "Chất lượng không khí xấu, " + idx + ", nhớ đeo khẩu trang chống bụi mịn");
        else if (w.aqi > 100) addSay(b, say, WARM, "Chất lượng không khí kém, nên đeo khẩu trang", "Chất lượng không khí kém, " + idx + ", nên đeo khẩu trang");
        else if (w.aqi >= 0) {
            String q = "Chất lượng không khí " + Weather.aqiName(w.aqi).toLowerCase(new java.util.Locale("vi"));
            if (b.tips.size() < 3) b.tips.add(new Tip(GREEN, q, false));
            say.add(q + ", " + idx);
        }
        while (b.tips.size() > 3) b.tips.remove(b.tips.size() - 1);

        StringBuilder sp = new StringBuilder("Chào buổi sáng. Bây giờ ngoài trời ").append(w.temp).append(" độ, ")
                .append(b.desc.toLowerCase(new java.util.Locale("vi"))).append(". ");
        for (String s : say) sp.append(s).append(". ");
        b.speech = sp.toString().trim();
        return b;
    }

    private static void addSay(Brief b, List<String> say, int color, String text, String spoken) {
        b.tips.add(new Tip(color, text, false));
        say.add(spoken);
    }

    private static void add(Brief b, List<String> say, int color, String text, boolean strong) {
        b.tips.add(new Tip(color, text, strong));
        if (say != null) say.add(text);
    }

    private Morning() {}
}
