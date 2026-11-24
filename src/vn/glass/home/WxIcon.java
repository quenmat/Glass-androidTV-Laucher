package vn.glass.home;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

// Biểu tượng thời tiết vẽ vector (mép sắc ở mọi cỡ): nắng, trăng, ít mây, mây, sương mù, mưa phùn, mưa, dông, tuyết.
// Dùng chung cho bảng lịch và thẻ bản tin sáng.
final class WxIcon {
    private final Paint ic = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path(), path2 = new Path();

    private static int fade(int c, float f) { return (Math.round((c >>> 24) * f) << 24) | (c & 0xFFFFFF); }

    static final int SUN = 0, MOON = 1, PART_DAY = 2, PART_NIGHT = 3, CLOUD = 4, FOG = 5, DRIZZLE = 6,
            RAIN = 7, STORM = 8, SNOW = 9;

    static int kind(int code, boolean day) {
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

    /** Vẽ biểu tượng loại k trong ô vuông cạnh S (điểm ảnh thật) tại (X, Y); f: độ hiện (0..1). */
    void draw(Canvas c, int k, float X, float Y, float S, float f) {
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
            path.setFillType(Path.FillType.WINDING);
        path.setFillType(Path.FillType.WINDING);   // phép gộp lần trước đổi kiểu tô: vẽ lại không được để lỗ
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
        path.setFillType(Path.FillType.WINDING);   // phép gộp lần trước đổi kiểu tô: vẽ lại không được để lỗ
        path.addCircle(X + S * .29f, Y + S * .56f, S * .19f, Path.Direction.CW);
        path.addCircle(X + S * .50f, Y + S * .40f, S * .22f, Path.Direction.CW);
        path.addCircle(X + S * .71f, Y + S * .53f, S * .19f, Path.Direction.CW);
        path2.reset();
        path2.setFillType(Path.FillType.WINDING);
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
        path.setFillType(Path.FillType.WINDING);   // phép gộp lần trước đổi kiểu tô: vẽ lại không được để lỗ
        path.addCircle(cx, cy, rad, Path.Direction.CW);
        path2.reset();
        path2.setFillType(Path.FillType.WINDING);
        path2.addCircle(cx + rad * .55f, cy - rad * .35f, rad * .85f, Path.Direction.CW);
        path.op(path2, Path.Op.DIFFERENCE);
        c.drawPath(path, ic);
    }
}
