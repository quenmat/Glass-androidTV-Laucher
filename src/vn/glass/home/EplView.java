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

// Thẻ Ngoại hạng Anh (vẽ tay cho thẳng hàng tuyệt đối), ba chế độ:
// - LỊCH: tiêu đề, rồi các ngày, mỗi trận một dòng "giờ | đội nhà [logo] – [logo] đội khách". Đang đá: cột giờ là
//   phút thi đấu màu xanh, giữa là tỉ số. Đá xong: cột giờ "KT", tỉ số nhạt hơn.
// - BẢNG XẾP HẠNG: 20 đội "hạng | lên/xuống | logo tên | trận | hiệu số | điểm"; vạch mờ dưới hạng 4 và trên hạng 18.
// - THU GỌN (hẹp như ô giá): trận đang đá (một dòng, tên viết tắt 3 chữ) (nếu có), không thì trận tới của đội yêu thích; dưới là hạng 1, 2 và đội yêu thích.
// Toạ độ theo thiết kế 1920 rộng, nhân hệ số k khi vẽ.
final class EplView extends View {
    static final int W = 560;
    static final int FIXTURES = 0, TABLE = 1, COMPACT = 2;
    private static final int PAD = 24, TITLE = 60, HEAD = 40, ROW = 42, BOTTOM = 14, TIME_COL = 70;
    private static final int T_HEAD = 34, T_ROW = 27, MAX_LIVE = 3;
    private static final String[] THU = {"Chủ Nhật", "Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy"};

    private static final int TEAM = 0xC7FFFFFF, TEAM_DONE = 0x94FFFFFF, TIME = 0x8FFFFFFF;
    private final float k;
    private final Paint label, day, time, team, mid, sub, img = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG), rule = new Paint();
    private Epl.Data data;
    private int mode = FIXTURES;
    private int fav = 1;     // mã đội yêu thích (mã cố định của Premier League, 1 = Man United)
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG), mv;
    private final RectF bandRect = new RectF();

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

    void setFavorite(int code) { fav = code; requestLayout(); invalidate(); }

    /** Đổi chế độ; bảng xếp hạng chưa có thì ở lại lịch. */
    void setMode(int m) { mode = m == TABLE && !hasTable() ? FIXTURES : m; requestLayout(); invalidate(); }

    int mode() { return mode; }

    private boolean isFav(int teamId) {
        Epl.Team t = data == null ? null : data.teams.get(teamId);
        return t != null && t.code == fav;
    }

    /** Dải sáng mờ sau dòng của đội yêu thích (toạ độ thiết kế, đã trong phép phóng k). */
    private void drawBand(Canvas c, float top, float bottom) {
        bandRect.set(PAD - 12, top, W - PAD + 12, bottom);
        c.drawRoundRect(bandRect, 10, 10, band);
    }

    /** Chiều cao thiết kế theo chế độ đang hiện. */
    int designHeight() {
        if (mode == TABLE && hasTable()) return TITLE + T_HEAD + data.table.size() * T_ROW + BOTTOM;
        if (mode == COMPACT) return compactHeight();
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
        setMeasuredDimension(Math.round(designWidth() * k), Math.round(designHeight() * k));
    }

    private static long dayKey(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        return c.get(Calendar.YEAR) * 1000L + c.get(Calendar.DAY_OF_YEAR);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (data == null) return;
        if (mode == TABLE && hasTable()) { drawTable(c); return; }
        if (mode == COMPACT) { drawCompact(c); return; }
        if (data.matches.isEmpty()) return;
        c.save();
        c.scale(k, k);
        title(c, "Vòng " + data.round);
        float cx = matchCenter(), slot = slotFor(data.matches);
        float y = TITLE;
        long lastDay = -1;
        for (Epl.Match m : data.matches) {
            long dk = dayKey(m.kick);
            if (dk != lastDay) {
                y += HEAD;
                lastDay = dk;
                c.drawText(dayLabel(m.kick), PAD, y - 10, day);
            }
            y += ROW;
            drawMatch(c, m, y, cx, slot);
        }
        c.restore();
        flushBadges(c);
    }

    // ---------- dùng chung ----------

    private void title(Canvas c, String right) {
        label.setTextAlign(Paint.Align.LEFT);
        c.drawText("NGOẠI HẠNG ANH", PAD, 40, label);
        if (right == null) return;
        label.setTextAlign(Paint.Align.RIGHT);
        c.drawText(right, W - PAD, 40, label);
    }

    private static float matchCenter() { return PAD + TIME_COL + (W - 2 * PAD - TIME_COL) / 2f; }

    /**
     * Khoảng giữa dùng chung cho mọi dòng: có trận đã bắt đầu thì rộng bằng tỉ số "0 – 0" (chữ số đều nhau),
     * để logo và tên đội thẳng cột dù dòng là tỉ số hay "–".
     */
    private float slotFor(List<Epl.Match> ms) {
        boolean anyStarted = false;
        for (Epl.Match m : ms) if (m.started) { anyStarted = true; break; }
        return mid.measureText(anyStarted ? "0 – 0" : "–") / 2f;
    }

    private static String dayLabel(long kick) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(kick);
        return THU[cal.get(Calendar.DAY_OF_WEEK) - 1] + ", " + cal.get(Calendar.DAY_OF_MONTH) + "/" + (cal.get(Calendar.MONTH) + 1);
    }

    /** Một trận, đáy dòng ở y (toạ độ thiết kế). Logo gom lại vẽ sau ở toạ độ điểm ảnh nguyên. */
    private void drawMatch(Canvas c, Epl.Match m, float y, float cx, float slot) {
        float base = y - 6, center = y - 14;
        boolean live = m.started && !m.finished;
        if (isFav(m.home) || isFav(m.away)) drawBand(c, center - 18, center + 18);

        // cột giờ: giờ đá / phút đang đá (xanh, có chấm) / KT
        if (live) {
            c.drawCircle(PAD + 4, center, 4, dot);
            time.setColor(0xFF7FE3A0);
            c.drawText(m.minutes > 0 ? m.minutes + "'" : "Đang đá", PAD + 14, base, time);
        } else if (m.finished) {
            time.setColor(0x61FFFFFF);
            c.drawText("KT", PAD, base, time);
        } else {
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(m.kick);
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
        if (h != null && h.badge != null) badges.add(new Object[]{h.badge, (cx - half - 22) * k, center * k});
        if (a != null && a.badge != null) badges.add(new Object[]{a.badge, (cx + half + 22) * k, center * k});
    }

    /** Một dòng bảng xếp hạng (hạng i), đỉnh dòng ở y. full = có cột số trận và hiệu số. */
    private void drawStanding(Canvas c, int i, float y, boolean full) {
        float colPts = W - PAD, colGd = W - PAD - 64, colPl = W - PAD - 132, colMove = PAD + 30, colBadge = PAD + 82, colName = PAD + 102;
        Epl.Standing st = data.table.get(i);
        Epl.Team t = data.teams.get(st.team);
        float base = y + T_ROW - 8, center = y + T_ROW / 2f;
        boolean mine = t != null && t.code == fav;
        if (mine) drawBand(c, y + 1, y + T_ROW - 1);
        sub.setTextAlign(Paint.Align.RIGHT);
        sub.setColor(mine ? 0xD9FFFFFF : 0x73FFFFFF);
        c.drawText(String.valueOf(i + 1), PAD + 22, base, sub);
        // lên/xuống so với trước vòng gần nhất: ▲2 xanh dịu, ▼1 đỏ dịu, đứng yên thì gạch mờ
        if (st.move > 0) { mv.setColor(0xCC7FD99C); c.drawText("▲" + st.move, colMove, base - 1, mv); }
        else if (st.move < 0) { mv.setColor(0xCCF59A90); c.drawText("▼" + (-st.move), colMove, base - 1, mv); }
        else { mv.setColor(0x40FFFFFF); c.drawText("–", colMove + 3, base - 1, mv); }
        team.setTextAlign(Paint.Align.LEFT);
        team.setColor(mine ? 0xF5FFFFFF : TEAM);
        team.setTextSize(18);
        if (t != null) c.drawText(t.name, colName, base, team);
        team.setTextSize(20);
        sub.setColor(0x8FFFFFFF);
        if (full) {
            c.drawText(String.valueOf(st.played), colPl, base, sub);
            int gd = st.gd();
            c.drawText(gd > 0 ? "+" + gd : String.valueOf(gd), colGd, base, sub);
        }
        sub.setColor(0xD9FFFFFF);
        c.drawText(String.valueOf(st.pts), colPts, base, sub);
        if (t != null && t.small != null) badges.add(new Object[]{t.small, colBadge * k, center * k});
    }

    // ---------- bảng xếp hạng đầy đủ ----------

    private void drawTable(Canvas c) {
        c.save();
        c.scale(k, k);
        title(c, "Bảng xếp hạng");
        float y = TITLE + T_HEAD - 10;
        day.setTextAlign(Paint.Align.RIGHT);
        c.drawText("Tr", W - PAD - 132, y, day);
        c.drawText("HS", W - PAD - 64, y, day);
        c.drawText("Đ", W - PAD, y, day);
        day.setTextAlign(Paint.Align.LEFT);
        y = TITLE + T_HEAD;
        for (int i = 0; i < data.table.size(); i++) {
            drawStanding(c, i, y, true);
            if (i == 3 || i == data.table.size() - 4) c.drawRect(PAD, y + T_ROW, W - PAD, y + T_ROW + 1, rule);
            y += T_ROW;
        }
        c.restore();
        flushBadges(c);
    }

    // ---------- thu gọn ----------
    // Hẹp (CW), xếp dọc như ô giá: mỗi đội một dòng, tỉ số bên phải; dưới là hạng 1, 2 và đội yêu thích.
    // Chữ cùng cỡ với thẻ đầy đủ (18 tới 20) để nhìn từ xa vẫn đọc được.

    static final int CW = 300;
    private static final int C_PAD = 20, C_TITLE = 48, C_DATE = 30, C_MATCH = 40, C_RANK = 32, C_RULE = 22, C_DOTS = 22;

    /** Đội yêu thích không liền ngay sau top 2 (từ hạng 4 trở xuống): cần dòng "…" ngăn cách. */
    private static boolean gapBefore(List<Integer> rs) { return rs.size() == 3 && rs.get(2) > 2; }

    // khu đang được chọn bằng điều khiển trong thẻ gọn: lịch (trên) hoặc bảng xếp hạng (dưới)
    static final int SEL_NONE = 0, SEL_MATCH = 1, SEL_TABLE = 2;
    private int sel;
    private float selA;
    private android.animation.ValueAnimator selAnim;
    private final Paint selFill = new Paint(Paint.ANTI_ALIAS_FLAG), selEdge = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** Chọn một khu (hoặc bỏ chọn): khung sáng hiện dần ở khu đó. */
    void setSelected(int which) {
        if (which == SEL_NONE) {
            if (selAnim != null) selAnim.cancel();
            selAnim = android.animation.ValueAnimator.ofFloat(selA, 0f);
            selAnim.setDuration(180);
            selAnim.addUpdateListener(a -> { selA = (float) a.getAnimatedValue(); if (selA == 0f) sel = SEL_NONE; invalidate(); });
            selAnim.start();
            return;
        }
        boolean fresh = sel == SEL_NONE;
        sel = which;
        if (selAnim != null) selAnim.cancel();
        selAnim = android.animation.ValueAnimator.ofFloat(fresh ? 0f : selA, 1f);
        selAnim.setDuration(fresh ? 200 : 160);
        selAnim.setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f));
        selAnim.addUpdateListener(a -> { selA = (float) a.getAnimatedValue(); invalidate(); });
        selAnim.start();
    }

    int selected() { return sel; }

    /** Thẻ gọn có khu lịch (trận đang đá / trận tới) không. */
    boolean hasMatchPart() { return !compactMatches().isEmpty(); }

    /** Thẻ gọn có khu bảng xếp hạng không. */
    boolean hasTablePart() { return !compactRanks().isEmpty(); }

    /** Bề ngang thiết kế theo chế độ: thu gọn hẹp hơn. */
    int designWidth() { return mode == COMPACT ? CW : W; }

    /** Trận cần hiện: các trận đang đá (đội yêu thích lên đầu, tối đa 3); không có thì trận tới của đội yêu thích. */
    private List<Epl.Match> compactMatches() {
        List<Epl.Match> out = new ArrayList<>();
        if (data == null) return out;
        for (Epl.Match m : data.matches) if (m.started && !m.finished) out.add(m);
        if (!out.isEmpty()) {
            out.sort((x, y) -> Boolean.compare(isFav(y.home) || isFav(y.away), isFav(x.home) || isFav(x.away)));
            return out.size() > MAX_LIVE ? new ArrayList<>(out.subList(0, MAX_LIVE)) : out;
        }
        List<Epl.Match> pool = data.season.isEmpty() ? data.matches : data.season;
        Epl.Match next = null, last = null;
        for (Epl.Match m : pool) {
            if (!(isFav(m.home) || isFav(m.away))) continue;
            if (!m.finished && next == null) next = m;
            if (m.finished) last = m;
        }
        if (next != null) out.add(next);
        else if (last != null) out.add(last);   // hết mùa: trận cuối của đội
        return out;
    }

    private static boolean live(Epl.Match m) { return m.started && !m.finished; }

    /** Hạng 1, 2 và đội yêu thích; đội yêu thích nằm trong top 2 thì chỉ hai dòng. */
    private List<Integer> compactRanks() {
        List<Integer> out = new ArrayList<>();
        if (!hasTable()) return out;
        int favAt = -1;
        for (int i = 0; i < data.table.size(); i++) {
            Epl.Team t = data.teams.get(data.table.get(i).team);
            if (t != null && t.code == fav) { favAt = i; break; }
        }
        out.add(0); out.add(1);
        if (favAt >= 2) out.add(favAt);
        return out;
    }

    private int compactHeight() {
        if (data == null) return 0;
        List<Epl.Match> ms = compactMatches();
        int h = C_TITLE;
        if (!ms.isEmpty()) h += (live(ms.get(0)) ? 0 : C_DATE) + ms.size() * C_MATCH;
        List<Integer> rs = compactRanks();
        if (!rs.isEmpty()) h += (ms.isEmpty() ? 0 : C_RULE) + rs.size() * C_RANK + (gapBefore(rs) ? C_DOTS : 0);
        return h + 16;
    }

    private void drawCompact(Canvas c) {
        List<Epl.Match> ms = compactMatches();
        boolean lv = !ms.isEmpty() && live(ms.get(0));
        c.save();
        c.scale(k, k);
        if (sel != SEL_NONE && selA > 0f) {
            // khung chọn: nền sáng mờ + viền trắng mảnh quanh đúng khu đang chọn
            float matchEnd = C_TITLE + (ms.isEmpty() ? 0 : (lv ? 0 : C_DATE) + ms.size() * C_MATCH);
            List<Integer> rsel = compactRanks();
            float tableTop = matchEnd + (ms.isEmpty() ? 0 : C_RULE);
            float tableEnd = tableTop + rsel.size() * C_RANK + (gapBefore(rsel) ? C_DOTS : 0);
            if (sel == SEL_MATCH) bandRect.set(8, C_TITLE - 2, CW - 8, matchEnd + 6);
            else bandRect.set(8, tableTop - 6, CW - 8, tableEnd + 4);
            selFill.setColor(((int) (0x2E * selA) << 24) | 0xFFFFFF);
            selEdge.setStyle(Paint.Style.STROKE);
            selEdge.setStrokeWidth(2.5f);
            selEdge.setColor(((int) (0xCC * selA) << 24) | 0xFFFFFF);
            c.drawRoundRect(bandRect, 12, 12, selFill);
            c.drawRoundRect(bandRect, 12, 12, selEdge);
        }
        float right = CW - C_PAD;
        label.setTextAlign(Paint.Align.LEFT);
        c.drawText("NGOẠI HẠNG ANH", C_PAD, 34, label);
        if (lv) {
            // một trận: phút thi đấu ở góc phải tiêu đề; nhiều trận: mỗi trận có dòng phút riêng
            String r1 = ms.size() == 1 ? minuteOf(ms.get(0)) : "Đang đá";
            time.setTextAlign(Paint.Align.RIGHT);
            time.setColor(0xFF7FE3A0);
            c.drawText(r1, right, 34, time);
            c.drawCircle(right - time.measureText(r1) - 10, 28, 4, dot);
            time.setTextAlign(Paint.Align.LEFT);
        } else if (!ms.isEmpty()) {
            label.setTextAlign(Paint.Align.RIGHT);
            label.setColor(0x61FFFFFF);
            c.drawText(ms.get(0).finished ? "Trận vừa đá" : "Trận tới", right, 34, label);
            label.setColor(0x80FFFFFF);
            label.setTextAlign(Paint.Align.LEFT);
        }
        float y = C_TITLE;
        if (!lv && !ms.isEmpty()) {
            Epl.Match m = ms.get(0);
            y += C_DATE;
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(m.kick);
            c.drawText(dayLabel(m.kick) + String.format(java.util.Locale.ROOT, ", %02d:%02d",
                    cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)), C_PAD, y - 10, day);
        }
        // nhiều trận cùng đá: mỗi dòng có phút thi đấu ở đầu, cụm trận dồn sang phải chừa chỗ
        boolean minuteCol = lv && ms.size() > 1;
        for (Epl.Match m : ms) {
            y += C_MATCH;
            matchLine(c, m, y, minuteCol);
        }
        List<Integer> rs = compactRanks();
        if (!rs.isEmpty()) {
            if (!ms.isEmpty()) { y += C_RULE / 2f; c.drawRect(C_PAD, y, right, y + 1, rule); y += C_RULE / 2f; }
            for (int n = 0; n < rs.size(); n++) {
                if (n == 2 && gapBefore(rs)) {
                    // dòng "…": giữa top 2 và đội yêu thích còn các hạng khác bị lược đi
                    sub.setTextAlign(Paint.Align.CENTER);
                    sub.setColor(0x66FFFFFF);
                    c.drawText("\u2026", CW / 2f, y + C_DOTS - 7, sub);   // ba ch\u1EA5m ngang, gi\u1EEFa th\u1EBB
                    y += C_DOTS;
                }
                rankRow(c, rs.get(n), y);
                y += C_RANK;
            }
        }
        c.restore();
        flushBadges(c);
    }

    private static String minuteOf(Epl.Match m) { return m.minutes > 0 ? m.minutes + "\u2032" : "Đang đá"; }

    /**
     * Một trận trên một dòng, tên viết tắt 3 chữ như trên truyền hình: "MUN [logo] 2 – 1 [logo] AVL".
     * Chưa đá thì giữa là "–". Thẻ gọn không làm nổi đội yêu thích: ít dòng, nhìn là thấy.
     */
    private void matchLine(Canvas c, Epl.Match m, float y, boolean minuteCol) {
        float center = y - C_MATCH / 2f + 2, base = center + 7;
        float left = C_PAD, cx = CW / 2f;
        if (minuteCol) {
            // cột phút rộng vừa "90′"; chưa có phút (dữ liệu lúc mới đá) thì chỉ chấm xanh: chữ "Đang đá" đã nằm ở
            // tiêu đề thẻ, in lại vào cột hẹp này thì lấn sang tên đội
            time.setTextAlign(Paint.Align.LEFT);
            time.setColor(0xFF7FE3A0);
            float col = time.measureText("90′") + 10;
            if (m.minutes > 0) c.drawText(m.minutes + "′", left, base, time);
            else c.drawCircle(left + 5, center + 1, 4, dot);
            cx = (left + col + CW - C_PAD) / 2f;
        }
        String midText = m.started && m.hs >= 0 && m.as >= 0 ? m.hs + " – " + m.as : "–";
        mid.setTextAlign(Paint.Align.CENTER);
        mid.setColor(live(m) ? 0xE6FFFFFF : m.finished ? 0x8CFFFFFF : 0x5CFFFFFF);
        c.drawText(midText, cx, base, mid);
        float half = Math.max(mid.measureText("0 – 0"), mid.measureText(midText)) / 2f;
        float gapName = minuteCol ? 38 : 44, gapBadge = minuteCol ? 20 : 24;   // có cột phút thì khít lại cho vừa thẻ
        Epl.Team h = data.teams.get(m.home), a = data.teams.get(m.away);
        team.setTextAlign(Paint.Align.RIGHT);
        team.setColor(TEAM);
        if (h != null) c.drawText(h.abbr, cx - half - gapName, base, team);
        team.setTextAlign(Paint.Align.LEFT);
        team.setColor(TEAM);
        if (a != null) c.drawText(a.abbr, cx + half + gapName, base, team);
        if (h != null && h.badge != null) badges.add(new Object[]{h.badge, (cx - half - gapBadge) * k, center * k});
        if (a != null && a.badge != null) badges.add(new Object[]{a.badge, (cx + half + gapBadge) * k, center * k});
    }

    /** Một dòng bảng xếp hạng gọn: hạng, logo, tên, lên/xuống, điểm. */
    private void rankRow(Canvas c, int i, float y) {
        Epl.Standing st = data.table.get(i);
        Epl.Team t = data.teams.get(st.team);
        float center = y + C_RANK / 2f, base = center + 6;
        sub.setTextAlign(Paint.Align.RIGHT);
        sub.setColor(0x73FFFFFF);
        c.drawText(String.valueOf(i + 1), C_PAD + 22, base, sub);
        if (t != null && t.small != null) badges.add(new Object[]{t.small, (C_PAD + 44) * k, center * k});
        team.setTextAlign(Paint.Align.LEFT);
        team.setColor(TEAM);
        team.setTextSize(18);
        if (t != null) c.drawText(t.name, C_PAD + 62, base, team);
        team.setTextSize(20);
        mv.setTextAlign(Paint.Align.RIGHT);
        float mvX = CW - C_PAD - 38;
        if (st.move > 0) { mv.setColor(0xCC7FD99C); c.drawText("\u25B2" + st.move, mvX, base - 1, mv); }
        else if (st.move < 0) { mv.setColor(0xCCF59A90); c.drawText("\u25BC" + (-st.move), mvX, base - 1, mv); }
        mv.setTextAlign(Paint.Align.LEFT);
        sub.setColor(0xD9FFFFFF);
        c.drawText(String.valueOf(st.pts), CW - C_PAD, base, sub);
    }

    // ---------- logo ----------

    private final List<Object[]> badges = new ArrayList<>();

    /** Logo đội (đã thu sẵn đúng cỡ) vẽ 1:1 ngoài phép phóng k, góc trên trái ở điểm ảnh nguyên: không bị lấy mẫu lại. */
    private void flushBadges(Canvas c) {
        for (Object[] o : badges) {
            Bitmap b = (Bitmap) o[0];
            c.drawBitmap(b, Math.round((float) o[1] - b.getWidth() / 2f), Math.round((float) o[2] - b.getHeight() / 2f), img);
        }
        badges.clear();
    }
}
