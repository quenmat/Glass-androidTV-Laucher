package vn.glass.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.PathInterpolator;

// Khay kính mờ: ảnh nền đã làm nhoè, tô vào hình chữ nhật bo góc bằng bút khử răng cưa,
// thêm lớp trắng mỏng và viền rất mảnh. Đổi ảnh thì hoà dần ảnh cũ sang ảnh mới.
final class FrostView extends View {
    private final float radius;
    private final float screenW, screenH, offX, offY;   // vị trí khay trên màn hình, để vùng nhoè khớp nền
    private final Paint img = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint old = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint milk = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float mix = 1f;
    private ValueAnimator anim;
    private Bitmap cur, prev;
    private boolean adaptive;                 // kính tối: lớp đen đậm dần theo độ sáng nền phía sau
    private int milkFrom = 0x66, milkTo = 0x66;
    private boolean tame;                     // kính dock: thêm lớp hãm sáng theo độ sáng nền
    private int shadeFrom = 0x30, shadeTo = 0x30;
    private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float shift, shiftY;   // khay đang trượt bao nhiêu: vùng nhoè dời ngược lại để vẫn khớp nền

    FrostView(Context c, float radius, float screenW, float screenH, float offX, float offY, float stroke) {
        super(c);
        this.radius = radius; this.screenW = screenW; this.screenH = screenH; this.offX = offX; this.offY = offY;
        milk.setColor(0x2EFFFFFF);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(stroke);
        edge.setColor(0x10FFFFFF);   // viền gần như vô hình, chỉ đủ tách khay khỏi nền
    }

    /** Kính tối (cho ô chứa chữ màu): lớp phủ đen mờ thay cho trắng sữa để chữ màu đủ tương phản. */
    FrostView dark() {
        milk.setColor(darkMin << 24);
        // không viền: trên kính tối viền trắng lộ thành vệt sáng ở góc bo; khối tách khỏi nền nhờ độ tối
        edge.setColor(0x00000000);
        adaptive = true;
        milkFrom = milkTo = darkMin;
        return this;
    }

    /**
     * Kính sáng của dock, có hãm sáng: lớp trắng sữa giữ nguyên, thêm lớp đen đậm dần khi nền phía sau sáng,
     * để trên ảnh nền sáng (hoàng hôn, trời trắng) khay không thành mảng chói.
     */
    FrostView tame() {
        tame = true;
        milk.setColor(0x1FFFFFFF);   // trắng sữa mỏng hơn kính thường: dock là kính khói, logo nổi hơn
        return this;
    }

    // kính tối (dock, ô giá): ~40% trên nền tối, tới ~70% trên nền sáng
    private int darkMin = 0x66, darkMax = 0xB3;

    /** Kính tối đậm (thẻ nhiều chữ nhỏ như lịch thi đấu): ~64% trên nền tối, tới ~78% trên nền sáng. */
    FrostView deep() {
        darkMin = 0xA3; darkMax = 0xC7;
        return dark();
    }

    /** Độ đậm lớp đen cho kính tối: nền tối giữ mức thấp (ô giá ~28%), nền sáng đậm dần để chữ đủ tương phản. */
    private int darkAlpha(Bitmap b) {
        float t = Math.max(0f, Math.min(1f, (regionLum(b) - 0.25f) / 0.5f));   // tối hơn 25% giữ nguyên, sáng hơn 75% đậm hết mức
        return Math.round(darkMin + (darkMax - darkMin) * t);
    }

    /** Lớp tối cho kính dock: luôn có ~19% (kính khói), nền sáng dần từ 30% tới 70% thì đậm tới ~55%. */
    private int shadeAlpha(Bitmap b) {
        float t = Math.max(0f, Math.min(1f, (regionLum(b) - 0.30f) / 0.40f));
        return Math.round(0x30 + (0x8C - 0x30) * t);
    }

    /** Độ sáng trung bình (0..1) của vùng ảnh nhoè nằm ngay sau khối này (ảnh được phóng center-crop ra cỡ màn hình). */
    private float regionLum(Bitmap b) {
        float k = Math.max(screenW / b.getWidth(), screenH / b.getHeight());
        float ox = (screenW - b.getWidth() * k) / 2f, oy = (screenH - b.getHeight() * k) / 2f;
        float left = offX + shift, top = offY + shiftY;
        int x0 = clamp((int) ((left - ox) / k), b.getWidth()), x1 = clamp((int) Math.ceil((Math.min(screenW, left + getWidth()) - ox) / k), b.getWidth());
        int y0 = clamp((int) ((top - oy) / k), b.getHeight()), y1 = clamp((int) Math.ceil((top + getHeight() - oy) / k), b.getHeight());
        double sum = 0; int n = 0;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                int c = b.getPixel(x, y);
                sum += glare(c);
                n++;
            }
        }
        return n == 0 ? 0f : (float) (sum / n / 255.0);
    }

    /**
     * Độ "chói" cảm nhận của một điểm ảnh (0..255): nửa độ chói chuẩn, nửa kênh màu mạnh nhất.
     * Độ chói chuẩn coi đỏ/xanh dương bão hoà là tối, nhưng mắt thấy chúng rực; kênh mạnh nhất bù lại chỗ đó.
     */
    static double glare(int c) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, bl = c & 255;
        return 0.5 * (0.2126 * r + 0.7152 * g + 0.0722 * bl) + 0.5 * Math.max(r, Math.max(g, bl));
    }

    private static int clamp(int v, int size) { return Math.max(0, Math.min(size - 1, v)); }

    /** Nhoè rẻ: thu nhỏ còn ~1/24 rồi để bộ vẽ phóng lại bằng lọc song tuyến. */
    static Bitmap blur(Bitmap src) {
        Bitmap b = src;
        int w = Math.max(8, src.getWidth() / 24), h = Math.max(8, src.getHeight() / 24);
        while (b.getWidth() / 2 >= w) b = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
        return Bitmap.createScaledBitmap(b, w, h, true);
    }

    /** Trượt khay theo chiều ngang (dock dài hơn màn hình); ảnh nhoè đứng yên theo nền. */
    void setShift(float s) { setShift(s, shiftY); }

    void setShiftY(float sy) { setShift(shift, sy); }

    /** Trượt khay theo cả hai chiều (dock hạ thấp / nâng lên khi vào hàng nội dung). */
    void setShift(float sx, float sy) {
        shift = sx; shiftY = sy;
        setTranslationX(sx);
        setTranslationY(sy);
        // gọi mỗi khung khi khay trượt: chỉ dời toạ độ của bút vẽ sẵn có, không tạo bút mới (đỡ việc cho luồng vẽ)
        place(img.getShader(), cur);
        place(old.getShader(), prev);
        invalidate();
    }

    private void place(Shader s, Bitmap b) {
        if (s == null || b == null) return;
        s.setLocalMatrix(matrixFor(b));
    }

    private Matrix matrixFor(Bitmap b) {
        // phóng ảnh nhỏ ra cỡ màn hình (center-crop) rồi dời ngược về vị trí khay
        float k = Math.max(screenW / b.getWidth(), screenH / b.getHeight());
        Matrix m = new Matrix();
        m.setScale(k, k);
        m.postTranslate((screenW - b.getWidth() * k) / 2f - offX - shift, (screenH - b.getHeight() * k) / 2f - offY - shiftY);
        return m;
    }

    void setImage(Bitmap blurred) {
        prev = cur;
        cur = blurred;
        if (adaptive) { milkFrom = currentMilk(); milkTo = darkAlpha(blurred); }
        if (tame) { shadeFrom = currentShade(); shadeTo = shadeAlpha(blurred); }
        old.setShader(prev == null ? null : shaderFor(prev));
        img.setShader(shaderFor(blurred));
        if (anim != null) anim.cancel();
        mix = 0f;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(550);
        anim.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        anim.addUpdateListener(a -> { mix = (float) a.getAnimatedValue(); invalidate(); });
        anim.start();
    }

    private int currentMilk() { return Math.round(milkFrom + (milkTo - milkFrom) * mix); }

    private int currentShade() { return Math.round(shadeFrom + (shadeTo - shadeFrom) * mix); }

    private Shader shaderFor(Bitmap b) {
        BitmapShader s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        s.setLocalMatrix(matrixFor(b));
        return s;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) { rect.set(0, 0, w, h); }

    @Override
    protected void onDraw(Canvas c) {
        if (old.getShader() != null && mix < 1f) {
            old.setAlpha(255);
            c.drawRoundRect(rect, radius, radius, old);
        }
        if (img.getShader() != null) {
            img.setAlpha(Math.round(255 * mix));
            c.drawRoundRect(rect, radius, radius, img);
        }
        if (adaptive) milk.setColor(currentMilk() << 24);   // đổi độ đậm cùng nhịp hoà ảnh
        c.drawRoundRect(rect, radius, radius, milk);
        if (tame && currentShade() > 0) {
            shade.setColor(currentShade() << 24);
            c.drawRoundRect(rect, radius, radius, shade);
        }
        float h = edge.getStrokeWidth() / 2f;
        c.drawRoundRect(h, h, rect.right - h, rect.bottom - h, radius - h, radius - h, edge);
    }
}
