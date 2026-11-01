package vn.glass.home;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

// Ảnh bo góc vẽ bằng bút khử răng cưa (BitmapShader + drawRoundRect), cắt kiểu center-crop.
// Không dùng clipToOutline vì trên nhiều máy mép bo của kiểu cắt đó bị răng cưa.
final class RoundImage extends Drawable {
    private final Bitmap bmp;
    private final float radius;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF rect = new RectF();
    private final Matrix m = new Matrix();

    RoundImage(Bitmap bmp, float radius) {
        this.bmp = bmp;
        this.radius = radius;
        paint.setShader(new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onBoundsChange(Rect b) {
        rect.set(b);
        float s = Math.max(b.width() / (float) bmp.getWidth(), b.height() / (float) bmp.getHeight());
        m.setScale(s, s);
        m.postTranslate(b.left + (b.width() - bmp.getWidth() * s) / 2f, b.top + (b.height() - bmp.getHeight() * s) / 2f);
        paint.getShader().setLocalMatrix(m);
    }

    @Override public void draw(Canvas c) { c.drawRoundRect(rect, radius, radius, paint); }
    @Override public void setAlpha(int a) { paint.setAlpha(a); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter f) { paint.setColorFilter(f); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
