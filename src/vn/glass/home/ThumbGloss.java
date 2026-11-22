package vn.glass.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

// Lớp bóng đè lên mặt ô thumb khi được chọn (kiểu app Netflix): mặt kính sáng mờ phủ chéo từ góc trên trái và
// viền sáng mảnh; tĩnh, chỉ hiện dần khi chọn, không chạy vệt. Vẽ bằng drawRoundRect có khử răng cưa (không
// clipPath: mép bo của clipPath bị răng cưa). Không vẽ gì vào ảnh của ô.
final class ThumbGloss extends View {
    private final float radius, stroke;
    private final RectF rect = new RectF(), inner = new RectF();
    private final Paint gloss = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);

    ThumbGloss(Context c, float radius, float stroke) {
        super(c);
        this.radius = radius;
        this.stroke = stroke;
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(stroke);
        border.setColor(0x33FFFFFF);
        setAlpha(0f);
        setFocusable(false);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        rect.set(0, 0, w, h);
        inner.set(stroke / 2f, stroke / 2f, w - stroke / 2f, h - stroke / 2f);
        // mặt kính: sáng nhất ở góc trên trái, nhạt dần, hết hẳn trước giữa ô
        gloss.setShader(new LinearGradient(0, 0, w * 0.45f, h * 0.75f,
                new int[]{0x1FFFFFFF, 0x0AFFFFFF, 0x00FFFFFF}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));   // bóng nhẹ
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawRoundRect(rect, radius, radius, gloss);
        c.drawRoundRect(inner, radius - stroke / 2f, radius - stroke / 2f, border);
    }

    /** Ô được chọn: lớp bóng hiện dần (sau delayMs, cùng lúc ô phóng); bỏ chọn: tắt dần. */
    void focus(boolean on, long delayMs) {
        animate().cancel();
        animate().alpha(on ? 1f : 0f).setStartDelay(on ? delayMs : 0).setDuration(on ? 260 : 180).start();
    }
}
