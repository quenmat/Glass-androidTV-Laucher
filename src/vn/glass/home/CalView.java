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
    private final RectF r = new RectF();

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
            evs.removeIf(Days.Ev::monthly);   // mùng 1, rằm hằng tháng đã tô màu ấm sẵn, khỏi chấm
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

    // biểu tượng thời tiết dùng chung với thẻ bản tin sáng (WxIcon)
    private final WxIcon wxi = new WxIcon();

    private void icon(Canvas c, int k, float x, float y, float size, float f) { wxi.draw(c, k, d(x), d(y), d(size), f); }

    private static int kind(int code, boolean day) { return WxIcon.kind(code, day); }
}
