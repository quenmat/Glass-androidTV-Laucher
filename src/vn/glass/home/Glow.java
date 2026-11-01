package vn.glass.home;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

// Quầng sáng mềm quanh ô đang chọn, màu lấy từ màu chủ đạo của logo.
final class Glow {

    /** Màu chủ đạo: gom các điểm ảnh có độ bão hoà cao theo nhóm sắc độ, lấy nhóm đông nhất. */
    static int dominant(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight(), step = Math.max(1, Math.min(w, h) / 60);
        long[] r = new long[12], g = new long[12], bl = new long[12], n = new long[12];
        float[] hsv = new float[3];
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                int c = b.getPixel(x, y);
                if (Color.alpha(c) < 200) continue;
                Color.colorToHSV(c, hsv);
                if (hsv[1] < 0.35f || hsv[2] < 0.25f) continue;   // bỏ trắng, đen, xám
                int k = (int) (hsv[0] / 30f) % 12;
                r[k] += Color.red(c); g[k] += Color.green(c); bl[k] += Color.blue(c); n[k]++;
            }
        }
        int best = -1;
        for (int k = 0; k < 12; k++) if (n[k] > 0 && (best < 0 || n[k] > n[best])) best = k;
        if (best < 0 || n[best] < 8) return Color.WHITE;
        return Color.rgb((int) (r[best] / n[best]), (int) (g[best] / n[best]), (int) (bl[best] / n[best]));
    }

    /** Ảnh quầng sáng: hình chữ nhật bo góc làm mờ, rộng hơn ô một khoảng `spread` mỗi bên. */
    static Bitmap make(int cardW, int cardH, float radius, int spread, int color) {
        int w = cardW + spread * 2, h = cardH + spread * 2;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        // nâng độ sáng màu để quầng vẫn rõ quanh ô nền tối (Netflix, VieON) trên ảnh nền tối
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] = Math.max(hsv[2], 0.95f);
        int col = Color.HSVToColor(hsv);
        // 2 lớp: ngoài toả rộng và nhạt, trong ôm sát mép ô và rực -> sát logo sáng mạnh, xa thì tan dần.
        // `spread` là lề của ảnh; mỗi lớp chừa lề > bán kính nhoè để ánh sáng tan về 0 trước mép ảnh (không bị cắt vuông).
        Paint outer = new Paint(Paint.ANTI_ALIAS_FLAG);
        outer.setColor(col);
        // cardW/cardH/radius là ô LÚC ĐANG CHỌN (đã phóng): quầng ôm đúng hình ô nổi lên, không bị ô lấn vào.
        // gọn và rõ: lớp ngoài đậm, toả ~4,5% lề (~6-7px) quanh ô, nhoè hẹp (vẫn nhỏ hơn lề để không bị cắt vuông).
        // Chủ nhà đã thử hẹp hơn (~3-4px) và thấy mức này đẹp hơn: đừng thu tiếp.
        outer.setAlpha(180);
        outer.setMaskFilter(new BlurMaskFilter(spread * 0.10f, BlurMaskFilter.Blur.NORMAL));
        // cách đều 4 cạnh: toả ra cùng một khoảng ngang lẫn dọc. Góc lồi khi nhoè bị loãng (đo trên TV: yếu hơn
        // cạnh ~10-15%), nên góc quầng bo vuông hơn hình bao lý thuyết để dồn thêm sáng vào góc, cho đều với cạnh.
        float io = spread * 0.955f;
        float ro = radius + (spread - io) * 0.35f;
        c.drawRoundRect(new RectF(io, io, w - io, h - io), ro, ro, outer);

        Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);
        inner.setColor(col);
        inner.setAlpha(225);
        inner.setMaskFilter(new BlurMaskFilter(spread * 0.08f, BlurMaskFilter.Blur.NORMAL));
        float ii = spread * 0.98f;
        float ri = radius * 0.85f;
        c.drawRoundRect(new RectF(ii, ii, w - ii, h - ii), ri, ri, inner);

        // khoét đúng hình ô ở giữa: quầng nằm TRÊN dock (để toả đều 4 phía, không bị ô bên cạnh che mất
        // hai bên) mà không phủ màu lên chính ô đang chọn
        Paint hole = new Paint(Paint.ANTI_ALIAS_FLAG);
        hole.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR));
        // lỗ trùng khít ô (cùng cỡ, cùng độ bo, toạ độ nguyên): mép khử răng cưa của quầng và của ảnh ô nằm đúng
        // chồng lên nhau nên viền ô sắc như ảnh gốc, kể cả góc bo
        c.drawRoundRect(new RectF(spread, spread, w - spread, h - spread), radius, radius, hole);
        return out;
    }

    private Glow() {}
}
