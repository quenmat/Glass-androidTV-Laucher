package vn.glass.home;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

// Biểu tượng Cài đặt kiểu Apple (bánh răng nét): vẽ bằng hình vector ngay lúc vẽ, nên mép sắc ở mọi cỡ và lúc nút
// phóng to khi chọn (ảnh bitmap thu nhỏ thì mép răng mềm, nhoè). 10 răng ngắn bo tròn quanh thân tròn, khoét một lỗ
// lớn, giữa lỗ là vành tròn mảnh. Màu theo bộ lọc màu của ImageView (trắng lúc thường, tối lúc nút sáng trắng).
final class GearIcon extends Drawable {
    private static final int TEETH = 10;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path body = new Path();

    GearIcon() {
        fill.setColor(0xFFFFFFFF);
        fill.setStyle(Paint.Style.FILL);
        ring.setColor(0xFFFFFFFF);
        ring.setStyle(Paint.Style.STROKE);
    }

    @Override
    protected void onBoundsChange(Rect b) {
        float s = Math.min(b.width(), b.height());
        float cx = b.exactCenterX(), cy = b.exactCenterY();
        float tip = s * 0.48f, base = s * 0.38f;
        double step = 2 * Math.PI / TEETH, half = step * 0.42 / 2;
        body.reset();
        // viền ngoài: thân tròn xen răng hình thang (đỉnh hẹp, chân rộng), bo góc bằng CornerPathEffect
        for (int i = 0; i < TEETH; i++) {
            double a = i * step - Math.PI / 2;
            double[][] pts = {{a - half * 1.25, base}, {a - half * 0.8, tip}, {a + half * 0.8, tip}, {a + half * 1.25, base}};
            for (int k = 0; k < pts.length; k++) {
                float x = cx + (float) (pts[k][1] * Math.cos(pts[k][0])), y = cy + (float) (pts[k][1] * Math.sin(pts[k][0]));
                if (i == 0 && k == 0) body.moveTo(x, y); else body.lineTo(x, y);
            }
            // cung thân tròn tới chân răng kế tiếp
            double from = a + half * 1.25, to = a + step - half * 1.25;
            for (int j = 1; j <= 4; j++) {
                double t = from + (to - from) * j / 5;
                body.lineTo(cx + (float) (base * Math.cos(t)), cy + (float) (base * Math.sin(t)));
            }
        }
        body.close();
        // lỗ lớn giữa (thành bánh răng "nét")
        body.addCircle(cx, cy, s * 0.29f, Path.Direction.CCW);
        body.setFillType(Path.FillType.EVEN_ODD);
        // bo góc răng một lần ở đây (áp CornerPathEffect ra hình mới) thay vì để bút vẽ áp lại ở mỗi khung: lần đầu
        // nút hiện ra không phải dựng hiệu ứng nét, hiệu ứng nở nút mượt ngay từ lần chọn đầu tiên
        Paint round = new Paint(Paint.ANTI_ALIAS_FLAG);
        round.setStyle(Paint.Style.FILL);
        round.setPathEffect(new CornerPathEffect(s * 0.035f));
        Path baked = new Path();
        round.getFillPath(body, baked);
        baked.setFillType(Path.FillType.EVEN_ODD);
        body.set(baked);
        ring.setStrokeWidth(s * 0.06f);
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float s = Math.min(b.width(), b.height());
        c.drawPath(body, fill);
        c.drawCircle(b.exactCenterX(), b.exactCenterY(), s * 0.14f, ring);   // vành tròn mảnh giữa lỗ
    }

    @Override public void setAlpha(int a) { fill.setAlpha(a); ring.setAlpha(a); invalidateSelf(); }

    @Override public void setColorFilter(ColorFilter cf) { fill.setColorFilter(cf); ring.setColorFilter(cf); invalidateSelf(); }

    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
