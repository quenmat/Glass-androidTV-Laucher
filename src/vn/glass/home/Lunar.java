package vn.glass.home;

// Âm lịch Việt Nam (múi giờ +7), theo thuật toán thiên văn của Hồ Ngọc Đức: tính ngày sóc và trung khí
// từ toạ độ mặt trời/mặt trăng, nên đúng cả tháng nhuận, không cần bảng tra theo năm.
final class Lunar {
    private static final double TZ = 7.0;

    /** Âm lịch của một ngày dương: {ngày, tháng, năm, nhuận (1/0)}. */
    static int[] fromSolar(int dd, int mm, int yy) {
        int dayNumber = jd(dd, mm, yy);
        int k = (int) Math.floor((dayNumber - 2415021.076998695) / 29.530588853);
        int monthStart = newMoon(k + 1);
        if (monthStart > dayNumber) monthStart = newMoon(k);
        int a11 = month11(yy), b11 = a11;
        int lunarYear;
        if (a11 >= monthStart) {
            lunarYear = yy;
            a11 = month11(yy - 1);
        } else {
            lunarYear = yy + 1;
            b11 = month11(yy + 1);
        }
        int lunarDay = dayNumber - monthStart + 1;
        int diff = (monthStart - a11) / 29;
        int leap = 0;
        int lunarMonth = diff + 11;
        if (b11 - a11 > 365) {
            int leapDiff = leapOffset(a11);
            if (diff >= leapDiff) {
                lunarMonth = diff + 10;
                if (diff == leapDiff) leap = 1;
            }
        }
        if (lunarMonth > 12) lunarMonth -= 12;
        if (lunarMonth >= 11 && diff < 4) lunarYear -= 1;
        return new int[]{lunarDay, lunarMonth, lunarYear, leap};
    }

    private static final String[] CAN = {"Giáp", "Ất", "Bính", "Đinh", "Mậu", "Kỷ", "Canh", "Tân", "Nhâm", "Quý"};
    private static final String[] CHI = {"Tý", "Sửu", "Dần", "Mão", "Thìn", "Tỵ", "Ngọ", "Mùi", "Thân", "Dậu", "Tuất", "Hợi"};
    private static final String[] THANG = {"Giêng", "Hai", "Ba", "Tư", "Năm", "Sáu", "Bảy", "Tám", "Chín", "Mười", "Mười Một", "Chạp"};

    /** "Mùng 7 tháng Chín năm Bính Ngọ" (có "nhuận" nếu là tháng nhuận). */
    static String describe(int dd, int mm, int yy) {
        int[] l = fromSolar(dd, mm, yy);
        String day = l[0] <= 10 ? "Mùng " + l[0] : String.valueOf(l[0]);
        String month = "tháng " + THANG[l[1] - 1] + (l[3] == 1 ? " nhuận" : "");
        String year = CAN[(l[2] + 6) % 10] + " " + CHI[(l[2] + 8) % 12];
        return day + " " + month + " năm " + year;
    }

    /**
     * Dòng đếm ngược tới Tết (mùng 1 tháng Giêng) gần nhất tính từ hôm nay:
     * "Còn 113 ngày nữa là Tết Đinh Mùi", "Mai là Tết Đinh Mùi", đúng mùng 1: "Chúc mừng năm mới Đinh Mùi".
     */
    static String tetCountdown(java.util.Calendar today) {
        // chỉ hiện từ tháng Chín âm lịch tới hết tháng Chạp (mùa chờ Tết), cộng lời chúc đúng mùng 1 Tết
        int[] now = fromSolar(today.get(java.util.Calendar.DAY_OF_MONTH), today.get(java.util.Calendar.MONTH) + 1,
                today.get(java.util.Calendar.YEAR));
        boolean tetDay = now[0] == 1 && now[1] == 1 && now[3] == 0;
        if (now[1] < 9 && !tetDay) return "";
        java.util.Calendar c = (java.util.Calendar) today.clone();
        for (int n = 0; n <= 400; n++) {
            int d = c.get(java.util.Calendar.DAY_OF_MONTH), m = c.get(java.util.Calendar.MONTH) + 1, y = c.get(java.util.Calendar.YEAR);
            int[] l = fromSolar(d, m, y);
            if (l[0] == 1 && l[1] == 1 && l[3] == 0) {
                String nam = CAN[(l[2] + 6) % 10] + " " + CHI[(l[2] + 8) % 12];
                if (n == 0) return "Chúc mừng năm mới " + nam;
                if (n == 1) return "Mai là Tết " + nam;
                return "Còn " + n + " ngày nữa là Tết " + nam;
            }
            c.add(java.util.Calendar.DAY_OF_MONTH, 1);
        }
        return "";
    }

    private static int jd(int dd, int mm, int yy) {
        int a = (14 - mm) / 12, y = yy + 4800 - a, m = mm + 12 * a - 3;
        int jd = dd + (153 * m + 2) / 5 + 365 * y + y / 4 - y / 100 + y / 400 - 32045;
        if (jd < 2299161) jd = dd + (153 * m + 2) / 5 + 365 * y + y / 4 - 32083;
        return jd;
    }

    private static int newMoon(int k) {
        double T = k / 1236.85, T2 = T * T, T3 = T2 * T, dr = Math.PI / 180;
        double jd1 = 2415020.75933 + 29.53058868 * k + 0.0001178 * T2 - 0.000000155 * T3;
        jd1 += 0.00033 * Math.sin((166.56 + 132.87 * T - 0.009173 * T2) * dr);
        double M = 359.2242 + 29.10535608 * k - 0.0000333 * T2 - 0.00000347 * T3;
        double Mpr = 306.0253 + 385.81691806 * k + 0.0107306 * T2 + 0.00001236 * T3;
        double F = 21.2964 + 390.67050646 * k - 0.0016528 * T2 - 0.00000239 * T3;
        double c1 = (0.1734 - 0.000393 * T) * Math.sin(M * dr) + 0.0021 * Math.sin(2 * dr * M);
        c1 = c1 - 0.4068 * Math.sin(Mpr * dr) + 0.0161 * Math.sin(dr * 2 * Mpr);
        c1 = c1 - 0.0004 * Math.sin(dr * 3 * Mpr);
        c1 = c1 + 0.0104 * Math.sin(dr * 2 * F) - 0.0051 * Math.sin(dr * (M + Mpr));
        c1 = c1 - 0.0074 * Math.sin(dr * (M - Mpr)) + 0.0004 * Math.sin(dr * (2 * F + M));
        c1 = c1 - 0.0004 * Math.sin(dr * (2 * F - M)) - 0.0006 * Math.sin(dr * (2 * F + Mpr));
        c1 = c1 + 0.0010 * Math.sin(dr * (2 * F - Mpr)) + 0.0005 * Math.sin(dr * (2 * Mpr + M));
        double deltat = T < -11
                ? 0.001 + 0.000839 * T + 0.0002261 * T2 - 0.00000845 * T3 - 0.000000081 * T * T3
                : -0.000278 + 0.000265 * T + 0.000262 * T2;
        return (int) Math.floor(jd1 + c1 - deltat + 0.5 + TZ / 24);
    }

    private static int sunLongitude(int jdn) {
        double T = (jdn - 2451545.5 - TZ / 24) / 36525, T2 = T * T, dr = Math.PI / 180;
        double M = 357.52910 + 35999.05030 * T - 0.0001559 * T2 - 0.00000048 * T * T2;
        double L0 = 280.46645 + 36000.76983 * T + 0.0003032 * T2;
        double DL = (1.914600 - 0.004817 * T - 0.000014 * T2) * Math.sin(dr * M);
        DL += (0.019993 - 0.000101 * T) * Math.sin(dr * 2 * M) + 0.000290 * Math.sin(dr * 3 * M);
        double L = (L0 + DL) * dr;
        L = L - Math.PI * 2 * Math.floor(L / (Math.PI * 2));
        return (int) Math.floor(L / Math.PI * 6);
    }

    private static int month11(int yy) {
        int off = jd(31, 12, yy) - 2415021;
        int k = (int) Math.floor(off / 29.530588853);
        int nm = newMoon(k);
        if (sunLongitude(nm) >= 9) nm = newMoon(k - 1);
        return nm;
    }

    private static int leapOffset(int a11) {
        int k = (int) Math.floor((a11 - 2415021.076998695) / 29.530588853 + 0.5);
        int last, i = 1;
        int arc = sunLongitude(newMoon(k + i));
        do {
            last = arc;
            i++;
            arc = sunLongitude(newMoon(k + i));
        } while (arc != last && i < 14);
        return i - 1;
    }

    private Lunar() {}
}
