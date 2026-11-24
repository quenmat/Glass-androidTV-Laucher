package vn.glass.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

// Thẻ bản tin sáng (nằm trên lớp kính): dòng đầu "Sáng nay, Thứ Ba 24/11" + loa "Đang đọc bản tin", biểu tượng trời,
// nhiệt độ lớn, tình hình trời, gió / cảm giác như, AQI góc phải, vạch mảnh, rồi tối đa 3 lời khuyên (dòng đầu đậm).
final class MorningView extends View {
    static final int W = 820;
    private static final int PAD = 32, TIPS_Y = 206, TIP_STEP = 42;
    private static final int TXT = 0xEBFFFFFF, SUB = 0x99FFFFFF, HEAD = 0xA6FFFFFF, WARM = 0xE6FFC478, LINE = 0x1AFFFFFF;

    private final float s;
    private final CalView.Fonts fonts;
    private final Morning.Brief b;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final RectF r = new RectF();
    private final Path spk = new Path();
    private final WxIcon wxi = new WxIcon();
    private boolean speaking;

    MorningView(Context c, float scale, CalView.Fonts fonts, Morning.Brief b) {
        super(c);
        this.s = scale; this.fonts = fonts; this.b = b;
        setFocusable(false);
    }

    int designHeight() { return TIPS_Y + (b.tips.size() - 1) * TIP_STEP + 34; }

    void setSpeaking(boolean on) { if (speaking != on) { speaking = on; invalidate(); } }

    private float d(float v) { return v * s; }

    private void font(int w, float size, int color, Paint.Align a) {
        p.setTypeface(fonts.at(w, size));
        p.setTextSize(d(size));
        p.setColor(color);
        p.setTextAlign(a);
        p.setStyle(Paint.Style.FILL);
    }

    private void text(Canvas c, String t, float x, float y) { c.drawText(t, d(x), d(y), p); }

    @Override
    protected void onDraw(Canvas c) {
        font(550, 18, HEAD, Paint.Align.LEFT);
        text(c, b.head, PAD, 44);
        if (speaking) {
            font(450, 17, WARM, Paint.Align.RIGHT);
            text(c, "Đang đọc bản tin", W - PAD, 44);
            float x = W - PAD - p.measureText("Đang đọc bản tin") / s - 30;
            drawSpeaker(c, x, 27, 22);
        }
        wxi.draw(c, b.icon, d(26), d(62), d(76), 1f);
        font(350, 64, TXT, Paint.Align.LEFT);
        String t = b.temp + "°";
        text(c, t, 116, 128);
        float tx = 132 + p.measureText(t) / s;
        float aqiW = 0;
        if (b.aqi >= 0) {
            int col = Weather.aqiColor(b.aqi);
            font(500, 48, col, Paint.Align.RIGHT);
            text(c, String.valueOf(b.aqi), W - PAD, 112);
            aqiW = p.measureText(String.valueOf(b.aqi)) / s;
            font(600, 18, col, Paint.Align.RIGHT);
            text(c, "AQI, " + Weather.aqiName(b.aqi), W - PAD, 140);
            aqiW = Math.max(aqiW, p.measureText("AQI, " + Weather.aqiName(b.aqi)) / s);
        }
        float max = W - PAD - aqiW - 24 - tx;
        fit(c, b.desc, 500, 24, TXT, tx, 96, max);
        fit(c, b.sub, 400, 19, SUB, tx, 126, max);
        p.setColor(LINE);
        c.drawRect(d(PAD), d(166), d(W - PAD), d(166) + Math.max(1, d(1)), p);
        float y = TIPS_Y;
        for (Morning.Tip tip : b.tips) {
            p.setColor((0x34 << 24) | (tip.color & 0xFFFFFF));
            r.set(d(PAD), d(y - 22), d(PAD + 28), d(y + 6));
            c.drawRoundRect(r, d(14), d(14), p);
            p.setColor(tip.color);
            c.drawCircle(d(PAD + 14), d(y - 8), d(5), p);
            fit(c, tip.text, tip.strong ? 600 : 450, 22, tip.strong ? TXT : SUB, PAD + 44, y, W - PAD * 2 - 44);
            y += TIP_STEP;
        }
    }

    /** Một dòng vừa bề ngang: dài quá thì thu nhỏ chữ (tối thiểu 15), không cắt. */
    private void fit(Canvas c, String t, int w, float size, int color, float x, float y, float max) {
        float sz = size;
        font(w, sz, color, Paint.Align.LEFT);
        while (sz > 15 && p.measureText(t) / s > max) { sz -= 0.5f; font(w, sz, color, Paint.Align.LEFT); }
        text(c, t, x, y);
    }

    private void drawSpeaker(Canvas c, float x, float y, float S) {
        p.setColor(WARM);
        p.setStyle(Paint.Style.FILL);
        spk.reset();
        spk.moveTo(d(x + S * .1f), d(y + S * .38f)); spk.lineTo(d(x + S * .3f), d(y + S * .38f));
        spk.lineTo(d(x + S * .55f), d(y + S * .15f)); spk.lineTo(d(x + S * .55f), d(y + S * .85f));
        spk.lineTo(d(x + S * .3f), d(y + S * .62f)); spk.lineTo(d(x + S * .1f), d(y + S * .62f)); spk.close();
        c.drawPath(spk, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(d(S * .07f));
        p.setStrokeCap(Paint.Cap.ROUND);
        for (float rr : new float[]{.22f, .36f}) {
            r.set(d(x + S * .5f - S * rr), d(y + S * .5f - S * rr), d(x + S * .5f + S * rr), d(y + S * .5f + S * rr));
            c.drawArc(r, -45, 90, false, p);
        }
        p.setStyle(Paint.Style.FILL);
    }
}
