package vn.glass.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import java.util.Calendar;

// Bảng lịch mở từ đồng hồ (bản đầy đủ của đồng hồ, như bảng xếp hạng đầy đủ của thẻ bóng đá): một thẻ kính thả xuống
// ngay dưới giờ. Trên cùng là hôm nay (dương + âm), hai ô nhỏ thời tiết và không khí Hà Nội, rồi lịch tháng (số âm
// nhỏ nằm dưới số dương, ngày lễ / ngày của nhà có chấm nhỏ bên dưới), cuối thẻ là dòng báo ngày sắp tới (giỗ, lễ)
// hoặc đếm ngược tới Tết. Vẽ hết trên một View (không dựng hàng chục TextView),
// chỉ tồn tại lúc bảng mở. Trái / phải xem tháng trước, tháng sau.
final class CalView extends View {
    interface Fonts { Typeface at(int weight, float size); }

    static final int W = 640;
    private static final int PAD = 32, TILE_Y = 116, TILE_H = 104, HEAD_Y = 262, WEEK_Y = 294, GRID_Y = 312, CELL_H = 50;
    private static final int H_TET = 706, H_PLAIN = 640, H_SUB = 26;   // H_SUB: thêm dòng ngày rơi vào

    private static final int TXT = 0xEBFFFFFF, SUB = 0x99FFFFFF, DIM = 0x61FFFFFF, HEAD = 0xA6FFFFFF;
    private static final int SUN_RED = 0xD9F59A90, WARM = 0xE6FFC478, TILE = 0x12FFFFFF, LINE = 0x1AFFFFFF;
    private static final int DARK = 0xFF141418;
    private static final String[] WEEK = {"T2", "T3", "T4", "T5", "T6", "T7", "CN"};
    private static final String[] THU = {"Chủ Nhật", "Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy"};

    private final float s;
    private final Fonts fonts;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint ic = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path path = new Path(), path2 = new Path();

    private Weather.Now wx;
    private int monthShift;            // 0: tháng này
    private float gridF = 1f, gridDir; // hiện dần lưới tháng mới (trượt nhẹ theo chiều bấm)
    private float wxF = 1f;            // hiện dần số thời tiết mới
    private ValueAnimator gridAnim, wxAnim;
    private final Context ctx;
    private String tet, tetSub = "";
    private static final android.view.animation.Interpolator EASE =
            new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f);

    CalView(Context c, float scale, Fonts fonts, Weather.Now wx) {
        super(c);
        this.s = scale; this.fonts = fonts; this.wx = wx;
        ctx = c;
        tet = bottomLine();
        setFocusable(false);
    }

    /** Ngày của nhà / lễ sắp tới, không có thì đếm ngược Tết. */
    private String bottomLine() {
        Days.Due u = Days.upcomingDue(ctx);
        tetSub = u == null ? "" : u.when();
        return u == null ? Lunar.tetCountdown(Calendar.getInstance()) : u.text;
    }

    /** Sheet ngày đáng nhớ vừa đọc lại: chấm và dòng cuối theo bản mới (thẻ giữ nguyên cỡ). */
    void daysChanged() {
        String oldSub = tetSub, t = bottomLine();
        if (tet.isEmpty() || t.isEmpty() || oldSub.isEmpty() != tetSub.isEmpty()) tetSub = oldSub;   // giữ cỡ thẻ
        else tet = t;
        invalidate();
    }

    int designHeight() { return tet.isEmpty() ? H_PLAIN : H_TET + (tetSub.isEmpty() ? 0 : H_SUB); }

    void setWeather(Weather.Now n) {
        if (n == null || n == wx) return;
        wx = n;
        if (wxAnim != null) wxAnim.cancel();
        wxAnim = ValueAnimator.ofFloat(0f, 1f);
        wxAnim.setDuration(520);
        wxAnim.setInterpolator(EASE);
        wxAnim.addUpdateListener(a -> { wxF = (float) a.getAnimatedValue(); invalidate(); });
        wxAnim.start();
    }

    /** Sang tháng trước (-1) / sau (+1); tối đa một năm mỗi phía. */
    boolean shift(int d) {
        int to = monthShift + d;
        if (to < -12 || to > 12) return false;
        monthShift = to;
        gridDir = d;
        if (gridAnim != null) gridAnim.cancel();
        gridAnim = ValueAnimator.ofFloat(0f, 1f);
        gridAnim.setDuration(460);
        gridAnim.setInterpolator(EASE);
        gridAnim.addUpdateListener(a -> { gridF = (float) a.getAnimatedValue(); invalidate(); });
        gridAnim.start();
        return true;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (gridAnim != null) gridAnim.cancel();
        if (wxAnim != null) wxAnim.cancel();
    }

    // ---------- vẽ ----------

    private float d(float v) { return v * s; }

    private static int fade(int c, float f) { return (Math.round((c >>> 24) * f) << 24) | (c & 0xFFFFFF); }

    private void font(int weight, float size, int color) {
        p.setTypeface(fonts.at(weight, size));
        p.setTextSize(d(size));
        p.setColor(color);
        p.setTextAlign(Paint.Align.LEFT);
    }

    private void text(Canvas c, String t, float x, float y, Paint.Align a) {
        p.setTextAlign(a);
        c.drawText(t, d(x), d(y), p);
    }

    @Override
    protected void onDraw(Canvas c) {
        Calendar now = Calendar.getInstance();
        int ty = now.get(Calendar.YEAR), tm = now.get(Calendar.MONTH) + 1, td = now.get(Calendar.DAY_OF_MONTH);

        // hôm nay
        font(500, 32, TXT);
        text(c, THU[now.get(Calendar.DAY_OF_WEEK) - 1] + ", " + td + " tháng " + tm, PAD, 58, Paint.Align.LEFT);
        font(400, 19, SUB);
        text(c, Lunar.line(td, tm, ty), PAD, 92, Paint.Align.LEFT);

        // hai ô: thời tiết, không khí
        float hw = (W - PAD * 2 - 14) / 2f;
        drawTile(c, PAD, hw);
        drawTile(c, PAD + hw + 14, hw);
        drawWeather(c, PAD, hw);
        drawAir(c, PAD + hw + 14, hw);

        // lịch tháng
        Calendar m = Calendar.getInstance();
        m.set(Calendar.DAY_OF_MONTH, 1);
        m.add(Calendar.MONTH, monthShift);
        int my = m.get(Calendar.YEAR), mm = m.get(Calendar.MONTH) + 1, days = m.getActualMaximum(Calendar.DAY_OF_MONTH);
        int start = (m.get(Calendar.DAY_OF_WEEK) + 5) % 7;   // thứ Hai đứng đầu tuần
        float gf = gridF, dx = gridDir * 24 * (1f - gf);
        font(550, 18, fade(HEAD, gf));
        text(c, "THÁNG " + mm + ", " + my, PAD + dx, HEAD_Y, Paint.Align.LEFT);
        int[] la = Lunar.fromSolar(1, mm, my), lb = Lunar.fromSolar(days, mm, my);
        String lm = la[1] == lb[1] && la[3] == lb[3] ? Lunar.monthName(la[1], la[3] == 1)
                : Lunar.monthName(la[1], la[3] == 1) + ", " + Lunar.monthName(lb[1], lb[3] == 1);
        font(450, 18, fade(SUB, gf));
        text(c, "Âm lịch tháng " + lm, W - PAD + dx, HEAD_Y, Paint.Align.RIGHT);

        float cw = (W - 48) / 7f, x0 = 24;
        font(500, 15, DIM);
        for (int i = 0; i < 7; i++) {
            p.setColor(i == 6 ? fade(SUN_RED, 0.75f) : DIM);
            text(c, WEEK[i], x0 + cw * i + cw / 2, WEEK_Y + 5, Paint.Align.CENTER);
        }
        for (int day = 1; day <= days; day++) {
            int idx = start + day - 1, row = idx / 7, col = idx % 7;
            float cx = x0 + cw * col + cw / 2 + dx, cy = GRID_Y + CELL_H * row + CELL_H / 2f;
            int[] l = Lunar.fromSolar(day, mm, my);
            boolean today = monthShift == 0 && day == td;
            int sc = col == 6 ? SUN_RED : TXT, lc = l[0] == 1 || l[0] == 15 ? WARM : DIM;
            if (today) {
                p.setColor(fade(0xECFFFFFF, gf));
                r.set(d(cx - cw / 2 + 6), d(cy - CELL_H / 2f + 3), d(cx + cw / 2 - 6), d(cy + CELL_H / 2f - 3));
                c.drawRoundRect(r, d(16), d(16), p);
                sc = DARK; lc = 0xA8141418;
            }
            font(450, 25, fade(sc, gf));
            text(c, String.valueOf(day), cx, cy + 1, Paint.Align.CENTER);
            font(450, 13, fade(lc, gf));
            text(c, l[0] == 1 ? l[0] + "/" + l[1] : String.valueOf(l[0]), cx, cy + 16, Paint.Align.CENTER);
            // chấm ngày đáng nhớ: ấm cho ngày của nhà, trắng nhạt cho lễ chung
            java.util.List<Days.Ev> evs = Days.on(ctx, day, mm, my);
            if (!evs.isEmpty()) {
                int dc = evs.get(0).mine ? WARM : 0x8CFFFFFF;
                if (today) dc = evs.get(0).mine ? 0xFFD9822B : 0x8C141418;
                p.setColor(fade(dc, gf));
                c.drawCircle(d(cx), d(cy + 21), d(2.5f), p);
            }
        }

        // đếm ngược Tết: nằm sát đáy thẻ, tách khỏi lưới bằng vạch mảnh
        if (!tet.isEmpty()) {
            float H = designHeight(), base = tetSub.isEmpty() ? H - 32 : H - 58;
            p.setColor(LINE);
            c.drawRect(d(PAD), d(base - 40), d(W - PAD), d(base - 40) + Math.max(1, d(1)), p);
            fitLine(c, tet, 450, 19, WARM, base);
            if (!tetSub.isEmpty()) fitLine(c, tetSub, 400, 17, SUB, H - 30);
        }
    }

    /** Một dòng chữ vừa bề ngang thẻ: dài quá thì thu nhỏ chữ (tối thiểu 14), không cắt. */
    private void fitLine(Canvas c, String t, int weight, float size, int color, float y) {
        float max = W - PAD * 2, sz = size;
        font(weight, sz, color);
        while (sz > 14 && p.measureText(t) / s > max) { sz -= 0.5f; font(weight, sz, color); }
        text(c, t, PAD, y, Paint.Align.LEFT);
    }

    private void drawTile(Canvas c, float x, float w) {
        p.setColor(TILE);
        r.set(d(x), d(TILE_Y), d(x + w), d(TILE_Y + TILE_H));
        c.drawRoundRect(r, d(18), d(18), p);
    }

    private void drawWeather(Canvas c, float x, float w) {
        if (wx == null || !wx.hasWx()) {
            font(400, 17, DIM);
            text(c, "Đang lấy thời tiết", x + w / 2, TILE_Y + TILE_H / 2f + 6, Paint.Align.CENTER);
            return;
        }
        float f = wxF;
        icon(c, kind(wx.code, wx.day), x + 16, TILE_Y + 12, 52, f);
        font(400, 40, fade(TXT, f));
        text(c, wx.temp + "°", x + 80, TILE_Y + 54, Paint.Align.LEFT);
        font(400, 17, fade(SUB, f));
        String desc = Weather.describe(wx.code);
        text(c, (desc.isEmpty() ? "" : desc + ", ") + wx.hi + "° / " + wx.lo + "°", x + 18, TILE_Y + 86, Paint.Align.LEFT);
        // khả năng mưa: góc phải trên, chỉ khi đáng kể
        if (wx.rain >= 20) {
            font(450, 16, fade(0xCC9CC8FF, f));
            text(c, "Mưa " + wx.rain + "%", x + w - 16, TILE_Y + 36, Paint.Align.RIGHT);
        }
    }

    private void drawAir(Canvas c, float x, float w) {
        if (wx == null || wx.aqi < 0) {
            font(400, 17, DIM);
            text(c, "Đang lấy chỉ số không khí", x + w / 2, TILE_Y + TILE_H / 2f + 6, Paint.Align.CENTER);
            return;
        }
        float f = wxF;
        int col = Weather.aqiColor(wx.aqi);
        font(500, 40, fade(col, f));
        String n = String.valueOf(wx.aqi);
        text(c, n, x + 18, TILE_Y + 54, Paint.Align.LEFT);
        float nw = p.measureText(n) / s;
        font(600, 19, fade(col, f));
        text(c, "AQI, " + Weather.aqiName(wx.aqi), x + 18 + nw + 14, TILE_Y + 46, Paint.Align.LEFT);
        font(400, 17, fade(SUB, f));
        String line = !wx.aqiSrc.isEmpty() ? "Trạm đo lúc " + wx.aqiSrc : wx.pm25 >= 0 ? "PM2.5 " + wx.pm25 + " µg/m³, ước tính" : "Hà Nội";
        float max = w - 36;
        while (p.measureText(line) / s > max && line.length() > 4) line = line.substring(0, line.length() - 1);   // tên trạm quá dài
        text(c, line, x + 18, TILE_Y + 86, Paint.Align.LEFT);
    }

    // ---------- biểu tượng thời tiết (vẽ vector, mép sắc ở mọi cỡ) ----------

    private static final int SUN = 0, MOON = 1, PART_DAY = 2, PART_NIGHT = 3, CLOUD = 4, FOG = 5, DRIZZLE = 6,
            RAIN = 7, STORM = 8, SNOW = 9;

    private static int kind(int code, boolean day) {
        if (code <= 1) return day ? SUN : MOON;
        if (code == 2) return day ? PART_DAY : PART_NIGHT;
        if (code == 3) return CLOUD;
        if (code == 45 || code == 48) return FOG;
        if (code >= 51 && code <= 57) return DRIZZLE;
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return RAIN;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return SNOW;
        if (code >= 95) return STORM;
        return CLOUD;
    }

    private void icon(Canvas c, int k, float x, float y, float size, float f) {
        float S = d(size), X = d(x), Y = d(y);
        int cloud = fade(0xFFEBEEF5, f), blue = fade(0xFF84BBFF, f), sun = fade(0xFFFFCF5C, f);
        ic.setStyle(Paint.Style.FILL);
        switch (k) {
            case SUN: drawSun(c, X + S * .5f, Y + S * .5f, S * .2f, sun); return;
            case MOON: drawMoon(c, X + S * .5f, Y + S * .5f, S * .26f, fade(0xFFF2EBD6, f)); return;
            case PART_DAY: drawSun(c, X + S * .64f, Y + S * .34f, S * .15f, sun); drawCloud(c, X, Y + S * .1f, S, cloud); return;
            case PART_NIGHT: drawMoon(c, X + S * .66f, Y + S * .32f, S * .18f, fade(0xFFF2EBD6, f)); drawCloud(c, X, Y + S * .1f, S, cloud); return;
            case FOG:
                drawCloud(c, X, Y - S * .1f, S, fade(0xFFB8BCC6, f));
                ic.setColor(cloud); ic.setStyle(Paint.Style.STROKE); ic.setStrokeCap(Paint.Cap.ROUND); ic.setStrokeWidth(S * .06f);
                c.drawLine(X + S * .18f, Y + S * .76f, X + S * .82f, Y + S * .76f, ic);
                c.drawLine(X + S * .28f, Y + S * .90f, X + S * .72f, Y + S * .90f, ic);
                return;
            default: break;
        }
        drawCloud(c, X, Y - (k == CLOUD ? 0 : S * .08f), S, cloud);
        if (k == CLOUD) return;
        ic.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 3; i++) {
            float dx = X + S * (.32f + i * .18f);
            if (k == DRIZZLE) { ic.setColor(blue); c.drawCircle(dx, Y + S * .84f, S * .04f, ic); }
            else if (k == SNOW) { ic.setColor(cloud); c.drawCircle(dx, Y + S * .84f, S * .045f, ic); }
            else if (k == RAIN) {
                ic.setColor(blue); ic.setStyle(Paint.Style.STROKE); ic.setStrokeCap(Paint.Cap.ROUND); ic.setStrokeWidth(S * .055f);
                c.drawLine(dx, Y + S * .72f, dx - S * .06f, Y + S * .92f, ic);
                ic.setStyle(Paint.Style.FILL);
            }
        }
        if (k == STORM) {
            ic.setColor(sun);
            path.reset();
            path.moveTo(X + S * .54f, Y + S * .62f); path.lineTo(X + S * .40f, Y + S * .82f); path.lineTo(X + S * .50f, Y + S * .82f);
            path.lineTo(X + S * .44f, Y + S * .98f); path.lineTo(X + S * .62f, Y + S * .74f); path.lineTo(X + S * .52f, Y + S * .74f);
            path.close();
            c.drawPath(path, ic);
        }
    }

    private void drawCloud(Canvas c, float X, float Y, float S, int col) {
        ic.setStyle(Paint.Style.FILL);
        ic.setColor(col);
        path.reset();
        path.addCircle(X + S * .29f, Y + S * .56f, S * .19f, Path.Direction.CW);
        path.addCircle(X + S * .50f, Y + S * .40f, S * .22f, Path.Direction.CW);
        path.addCircle(X + S * .71f, Y + S * .53f, S * .19f, Path.Direction.CW);
        path2.reset();
        path2.addRoundRect(X + S * .18f, Y + S * .52f, X + S * .84f, Y + S * .74f, S * .11f, S * .11f, Path.Direction.CW);
        path.op(path2, Path.Op.UNION);   // một hình liền: màu có độ mờ cũng không đậm lên ở chỗ chồng
        c.drawPath(path, ic);
    }

    private void drawSun(Canvas c, float cx, float cy, float rad, int col) {
        ic.setStyle(Paint.Style.FILL);
        ic.setColor(col);
        c.drawCircle(cx, cy, rad, ic);
        ic.setStyle(Paint.Style.STROKE);
        ic.setStrokeCap(Paint.Cap.ROUND);
        ic.setStrokeWidth(rad * .32f);
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            float c0 = (float) Math.cos(a), s0 = (float) Math.sin(a);
            c.drawLine(cx + c0 * rad * 1.45f, cy + s0 * rad * 1.45f, cx + c0 * rad * 1.9f, cy + s0 * rad * 1.9f, ic);
        }
        ic.setStyle(Paint.Style.FILL);
    }

    private void drawMoon(Canvas c, float cx, float cy, float rad, int col) {
        ic.setStyle(Paint.Style.FILL);
        ic.setColor(col);
        path.reset();
        path.addCircle(cx, cy, rad, Path.Direction.CW);
        path2.reset();
        path2.addCircle(cx + rad * .55f, cy - rad * .35f, rad * .85f, Path.Direction.CW);
        path.op(path2, Path.Op.DIFFERENCE);
        c.drawPath(path, ic);
    }
}
