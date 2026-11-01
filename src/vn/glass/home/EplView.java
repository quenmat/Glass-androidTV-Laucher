package vn.glass.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

// Thẻ lịch Ngoại hạng Anh (vẽ tay cho thẳng hàng tuyệt đối): tiêu đề, rồi các ngày, mỗi trận một dòng
// "giờ | đội nhà [logo] – [logo] đội khách". Đang đá: cột giờ là phút thi đấu màu xanh, giữa là tỉ số.
// Đá xong: cột giờ "KT", tỉ số nhạt hơn. Toạ độ theo thiết kế 1920 rộng, nhân hệ số k khi vẽ.
// Chế độ bảng xếp hạng: 20 đội, mỗi dòng "hạng | logo tên | trận | hiệu số | điểm"; vạch mờ dưới hạng 4 và trên hạng 18.
final class EplView extends View {
    static final int W = 560;
    private static final int PAD = 24, TITLE = 60, HEAD = 40, ROW = 42, BOTTOM = 14, TIME_COL = 70;
    private static final int T_HEAD = 34, T_ROW = 27;
    private static final String[] THU = {"Chủ Nhật", "Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy"};

    private static final int TEAM = 0xC7FFFFFF, TEAM_DONE = 0x94FFFFFF, TIME = 0x8FFFFFFF;
    private final float k;
    private final Paint label, day, time, team, mid, sub, img = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG), rule = new Paint();
    private Epl.Data data;
    private boolean table;   // đang hiện bảng xếp hạng thay cho lịch
    private int fav = 1;     // mã đội yêu thích (mã cố định của Premier League, 1 = Man United)
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG), mv;
    private final RectF bandRect = new RectF();
    private final RectF r = new RectF();

    EplView(Context c, float k, Typeface semibold, Typeface medium) {
        super(c);
        this.k = k;
        // chữ dịu (không trắng tinh): nhiều dòng chữ nhỏ trên kính tối, trắng tinh trông gắt
        label = paint(semibold, 17, 0x80FFFFFF);
        day = paint(medium, 17, 0x61FFFFFF);
        time = paint(medium, 20, TIME);
        team = paint(medium, 20, TEAM);
        mid = paint(medium, 20, 0x5CFFFFFF);
        time.setFontFeatureSettings("tnum");
        mid.setFontFeatureSettings("tnum");
        dot.setColor(0xFF4CE07A);
        rule.setColor(0x1FFFFFFF);
        sub = paint(medium, 18, 0x8FFFFFFF);
        sub.setFontFeatureSettings("tnum");
        mv = paint(medium, 14, 0x8FFFFFFF);
        mv.setFontFeatureSettings("tnum");
        band.setColor(0x24FFFFFF);
        setFocusable(false);
    }

    private static Paint paint(Typeface tf, float size, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(tf);
        p.setTextSize(size);
        p.setColor(color);
        return p;
    }

    void setData(Epl.Data d) { data = d; requestLayout(); invalidate(); }

    Epl.Data data() { return data; }

    boolean hasTable() { return data != null && data.table.size() >= 10; }

    void setFavorite(int code) { fav = code; invalidate(); }

    private boolean isFav(int teamId) {
        Epl.Team t = data == null ? null : data.teams.get(teamId);
        return t != null && t.code == fav;
    }

    /** Dải sáng mờ sau dòng của đội yêu thích (toạ độ thiết kế, đã trong phép phóng k). */
    private void drawBand(Canvas c, float top, float bottom) {
        bandRect.set(PAD - 12, top, W - PAD + 12, bottom);
        c.drawRoundRect(bandRect, 10, 10, band);
    }

    void setTable(boolean on) { table = on && hasTable(); requestLayout(); invalidate(); }

    boolean table() { return table; }

    /** Chiều cao thiết kế: tiêu đề + mỗi ngày một dòng ngày + các trận. */
    int designHeight() {
        if (table && hasTable()) return TITLE + T_HEAD + data.table.size() * T_ROW + BOTTOM;
        if (data == null || data.matches.isEmpty()) return 0;
        int h = TITLE + BOTTOM;
        long lastDay = -1;
        for (Epl.Match m : data.matches) {
            long d = dayKey(m.kick);
            if (d != lastDay) { h += HEAD; lastDay = d; }
            h += ROW;
        }
        return h;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(Math.round(W * k), Math.round(designHeight() * k));
    }

    private static long dayKey(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        return c.get(Calendar.YEAR) * 1000L + c.get(Calendar.DAY_OF_YEAR);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (table && hasTable()) { drawTable(c); return; }
        if (data == null || data.matches.isEmpty()) return;
        c.save();
        c.scale(k, k);
        label.setTextAlign(Paint.Align.LEFT);
        c.drawText("NGOẠI HẠNG ANH", PAD, 40, label);
        label.setTextAlign(Paint.Align.RIGHT);
        c.drawText("Vòng " + data.round, W - PAD, 40, label);

        float cx = PAD + TIME_COL + (W - 2 * PAD - TIME_COL) / 2f;   // giữa cột trận
        // khoảng giữa dùng chung cho mọi dòng: vòng đã có trận bắt đầu thì rộng bằng tỉ số "0 – 0" (chữ số đều nhau),
        // để logo và tên đội thẳng cột dù dòng là tỉ số hay "–"
        boolean anyStarted = false;
        for (Epl.Match m : data.matches) if (m.started) { anyStarted = true; break; }
        float slot = mid.measureText(anyStarted ? "0 – 0" : "–") / 2f;
        float y = TITLE;
        long lastDay = -1;
        Calendar cal = Calendar.getInstance();
        for (Epl.Match m : data.matches) {
            long dk = dayKey(m.kick);
            cal.setTimeInMillis(m.kick);
            if (dk != lastDay) {
                y += HEAD;
                lastDay = dk;
                c.drawText(THU[cal.get(Calendar.DAY_OF_WEEK) - 1] + ", " + cal.get(Calendar.DAY_OF_MONTH) + "/"
                        + (cal.get(Calendar.MONTH) + 1), PAD, y - 10, day);
            }
            y += ROW;
            float base = y - 6, center = y - 14;
            boolean live = m.started && !m.finished;
            boolean mine = isFav(m.home) || isFav(m.away);
            if (mine) drawBand(c, center - 18, center + 18);

            // cột giờ: giờ đá / phút đang đá (xanh, có chấm) / KT
            if (live) {
                c.drawCircle(PAD + 4, center, 4, dot);
                time.setColor(0xFF7FE3A0);
                c.drawText(m.minutes > 0 ? m.minutes + "'" : "Đang đá", PAD + 14, base, time);
            } else if (m.finished) {
                time.setColor(0x61FFFFFF);
                c.drawText("KT", PAD, base, time);
            } else {
                time.setColor(TIME);
                c.drawText(String.format(java.util.Locale.ROOT, "%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)), PAD, base, time);
            }

            // giữa: "–" hoặc tỉ số
            String midText = m.hs >= 0 && m.as >= 0 && m.started ? m.hs + " – " + m.as : "–";
            mid.setColor(live ? 0xD9FFFFFF : m.finished ? 0x8CFFFFFF : 0x5CFFFFFF);
            mid.setTextAlign(Paint.Align.CENTER);
            c.drawText(midText, cx, base, mid);
            float half = Math.max(slot, mid.measureText(midText) / 2f);

            Epl.Team h = data.teams.get(m.home), a = data.teams.get(m.away);
            team.setTextAlign(Paint.Align.RIGHT);
            team.setColor(isFav(m.home) ? 0xF5FFFFFF : m.finished ? TEAM_DONE : TEAM);
            if (h != null) c.drawText(h.name, cx - half - 40, base, team);
            team.setTextAlign(Paint.Align.LEFT);
            team.setColor(isFav(m.away) ? 0xF5FFFFFF : m.finished ? TEAM_DONE : TEAM);
            if (a != null) c.drawText(a.name, cx + half + 40, base, team);
            badges.add(new Object[]{h, (cx - half - 22) * k, center * k});
            badges.add(new Object[]{a, (cx + half + 22) * k, center * k});
        }
        c.restore();
        // logo vẽ ngoài phép phóng k, đúng 1:1 ở toạ độ điểm ảnh nguyên: ảnh đã thu sẵn đúng cỡ, không bị lấy mẫu lại
        for (Object[] o : badges) drawBadge(c, (Epl.Team) o[0], (float) o[1], (float) o[2]);
        badges.clear();
    }

    private final List<Object[]> badges = new ArrayList<>();

    private void drawTable(Canvas c) {
        c.save();
        c.scale(k, k);
        label.setTextAlign(Paint.Align.LEFT);
        c.drawText("NGOẠI HẠNG ANH", PAD, 40, label);
        label.setTextAlign(Paint.Align.RIGHT);
        c.drawText("Bảng xếp hạng", W - PAD, 40, label);
        float colPts = W - PAD, colGd = W - PAD - 64, colPl = W - PAD - 132, colMove = PAD + 30, colBadge = PAD + 82, colName = PAD + 102;
        float y = TITLE + T_HEAD - 10;
        day.setTextAlign(Paint.Align.RIGHT);
        c.drawText("Tr", colPl, y, day);
        c.drawText("HS", colGd, y, day);
        c.drawText("Đ", colPts, y, day);
        day.setTextAlign(Paint.Align.LEFT);
        y = TITLE + T_HEAD;
        sub.setTextAlign(Paint.Align.RIGHT);
        team.setTextAlign(Paint.Align.LEFT);
        for (int i = 0; i < data.table.size(); i++) {
            Epl.Standing st = data.table.get(i);
            Epl.Team t = data.teams.get(st.team);
            float base = y + T_ROW - 8, center = y + T_ROW / 2f;
            boolean mine = t != null && t.code == fav;
            if (mine) drawBand(c, y + 1, y + T_ROW - 1);
            sub.setColor(mine ? 0xD9FFFFFF : 0x73FFFFFF);
            c.drawText(String.valueOf(i + 1), PAD + 22, base, sub);
            // lên/xuống so với trước vòng gần nhất: ▲2 xanh dịu, ▼1 đỏ dịu, đứng yên thì gạch mờ
            if (st.move > 0) { mv.setColor(0xCC7FD99C); c.drawText("▲" + st.move, colMove, base - 1, mv); }
            else if (st.move < 0) { mv.setColor(0xCCF59A90); c.drawText("▼" + (-st.move), colMove, base - 1, mv); }
            else { mv.setColor(0x40FFFFFF); c.drawText("–", colMove + 3, base - 1, mv); }
            team.setColor(mine ? 0xF5FFFFFF : TEAM);
            team.setTextSize(18);
            if (t != null) c.drawText(t.name, colName, base, team);
            sub.setColor(0x8FFFFFFF);
            c.drawText(String.valueOf(st.played), colPl, base, sub);
            int gd = st.gd();
            c.drawText(gd > 0 ? "+" + gd : String.valueOf(gd), colGd, base, sub);
            sub.setColor(0xD9FFFFFF);
            c.drawText(String.valueOf(st.pts), colPts, base, sub);
            if (i == 3 || i == data.table.size() - 4)
                c.drawRect(PAD, y + T_ROW, W - PAD, y + T_ROW + 1, rule);
            if (t != null && t.small != null) tableBadges.add(new Object[]{t.small, colBadge * k, center * k});
            y += T_ROW;
        }
        team.setTextSize(20);
        c.restore();
        for (Object[] o : tableBadges) {
            Bitmap b = (Bitmap) o[0];
            c.drawBitmap(b, Math.round((float) o[1] - b.getWidth() / 2f), Math.round((float) o[2] - b.getHeight() / 2f), img);
        }
        tableBadges.clear();
    }

    private final List<Object[]> tableBadges = new ArrayList<>();

    /** Logo đội (đã thu sẵn đúng cỡ) vẽ 1:1, tâm gần (x, y), góc trên trái làm tròn về điểm ảnh nguyên. */
    private void drawBadge(Canvas c, Epl.Team t, float x, float y) {
        if (t == null || t.badge == null) return;
        Bitmap b = t.badge;
        c.drawBitmap(b, Math.round(x - b.getWidth() / 2f), Math.round(y - b.getHeight() / 2f), img);
    }
}
