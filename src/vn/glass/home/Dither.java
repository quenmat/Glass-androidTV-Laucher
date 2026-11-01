package vn.glass.home;

import android.graphics.Bitmap;

// Dải chuyển màu có rải nhiễu (dithering): giao diện Android vẽ 8-bit nên dải màu tối dễ lộ từng bậc;
// cộng nhiễu tam giác ±1 bậc vào từng điểm ảnh thì mắt thấy chuyển mượt, không còn vòng/sọc.
final class Dither {

    /** Nền tròn toả từ (cx, cy): màu c0 ở tâm, tan dần về c1 ở bán kính r. Ảnh đặc, đúng cỡ màn hình. */
    static Bitmap radial(int w, int h, float cx, float cy, float r, int c0, int c1) {
        int[] px = new int[w * h];
        int seed = 0x9E3779B9;
        float r0 = (c0 >> 16) & 255, g0 = (c0 >> 8) & 255, b0 = c0 & 255;
        float r1 = (c1 >> 16) & 255, g1 = (c1 >> 8) & 255, b1 = c1 & 255;
        for (int y = 0; y < h; y++) {
            float dy = y - cy;
            for (int x = 0; x < w; x++) {
                float dx = x - cx;
                float t = Math.min(1f, (float) Math.sqrt(dx * dx + dy * dy) / r);
                seed ^= seed << 13; seed ^= seed >>> 17; seed ^= seed << 5;
                float n = ((seed & 255) - ((seed >>> 8) & 255)) / 255f;   // nhiễu tam giác trong (-1, 1)
                px[y * w + x] = 0xFF000000
                        | ch(r0 + (r1 - r0) * t + n) << 16
                        | ch(g0 + (g1 - g0) * t + n) << 8
                        | ch(b0 + (b1 - b0) * t + n);
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    /** Lớp đen trong suốt dần từ dưới lên: alpha nội suy tuyến tính qua các mốc đều nhau (stops[0] ở đáy). */
    static Bitmap fadeUp(int w, int h, int[] stops) {
        int[] px = new int[w * h];
        int seed = 0x2545F491;
        int seg = stops.length - 1;
        for (int y = 0; y < h; y++) {
            float t = (h - 1 - y) / (float) Math.max(1, h - 1) * seg;
            int i = Math.min(seg - 1, (int) t);
            float a = stops[i] + (stops[i + 1] - stops[i]) * (t - i);
            for (int x = 0; x < w; x++) {
                seed ^= seed << 13; seed ^= seed >>> 17; seed ^= seed << 5;
                float n = ((seed & 255) - ((seed >>> 8) & 255)) / 255f;
                px[y * w + x] = ch(a + n) << 24;
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    /** Lớp đen hai mép trái phải: đậm `edge` ở sát mép, tan mượt (smoothstep) về 0 sau `band` bề rộng mỗi bên. */
    static Bitmap fadeSides(int w, int h, int edge, float band) {
        float[] col = new float[w];
        float bw = w * band;
        for (int x = 0; x < w; x++) {
            float d = Math.min(x, w - 1 - x) / bw;
            float t = Math.max(0f, 1f - d);
            col[x] = edge * t * t * (3 - 2 * t);
        }
        int[] px = new int[w * h];
        int seed = 0x6C8E9CF5;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                seed ^= seed << 13; seed ^= seed >>> 17; seed ^= seed << 5;
                float n = ((seed & 255) - ((seed >>> 8) & 255)) / 255f;
                px[y * w + x] = col[x] > 0 ? ch(col[x] + n) << 24 : 0;
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    /**
     * Quầng tối mềm hình elip (đặt sau cụm chữ): đậm `center` ở tâm, tan theo smoothstep về 0 ở mép ảnh,
     * không viền, có nhiễu để không phân tầng.
     */
    static Bitmap halo(int w, int h, int center) {
        int[] px = new int[w * h];
        int seed = 0x1B873593;
        float cx = w / 2f, cy = h / 2f;
        for (int y = 0; y < h; y++) {
            float dy = (y - cy) / cy;
            for (int x = 0; x < w; x++) {
                float dx = (x - cx) / cx;
                float t = Math.max(0f, 1f - (float) Math.sqrt(dx * dx + dy * dy));
                float a = center * t * t * (3 - 2 * t);
                seed ^= seed << 13; seed ^= seed >>> 17; seed ^= seed << 5;
                float n = ((seed & 255) - ((seed >>> 8) & 255)) / 255f;
                px[y * w + x] = a > 0 ? ch(a + n) << 24 : 0;
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    private static int ch(float v) { return v <= 0 ? 0 : v >= 255 ? 255 : (int) (v + 0.5f); }

    private Dither() {}
}
