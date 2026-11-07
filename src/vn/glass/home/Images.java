package vn.glass.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Tải ảnh (content://, file://, android.resource://, http/https, ảnh kèm trong APK) ở luồng nền,
// giải mã vừa đủ bề ngang cần dùng để ảnh nền 1080p không bị kéo giãn mà vẫn nhẹ RAM.
final class Images {
    interface Done { void on(Bitmap b); }

    private final Context ctx;
    // 2 luồng: đủ để tải trước mà không tranh CPU với hiệu ứng trên box yếu
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final Handler ui = new Handler(Looper.getMainLooper());
    // ~32MB: nền giờ chỉ một ảnh mỗi lúc (khoảng 5MB) cộng ảnh phim của vài app; box chỉ có 2GB RAM
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(32 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    Images(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    /** Bỏ ảnh tải sẵn (ảnh đang hiện vẫn do chính ô giữ, không mất) để nhường RAM cho app đang xem. */
    void trim() { cache.evictAll(); }

    void load(String uri, int reqW, Done done) {
        if (uri == null) { done.on(null); return; }
        String key = uri + "@" + reqW;
        Bitmap hit = cache.get(key);
        if (hit != null) { done.on(hit); return; }
        synchronized (inflight) {
            List<Done> w = inflight.get(key);
            if (w != null) { w.add(done); return; }      // đang tải rồi: chờ chung, không tải lại
            w = new ArrayList<>(); w.add(done); inflight.put(key, w);
        }
        io.execute(() -> {
            Bitmap b = null;
            try { b = decode(uri, reqW); } catch (Throwable e) {
                android.util.Log.w("GlassImg", "khong tai duoc " + uri + " : " + e);
            }
            if (b != null) cache.put(key, b);
            final Bitmap r = b;
            List<Done> w;
            synchronized (inflight) { w = inflight.remove(key); }
            ui.post(() -> { if (w != null) for (Done d : w) d.on(r); });
        });
    }

    private final Map<String, List<Done>> inflight = new HashMap<>();

    /** Nạp vào ImageView, bỏ qua nếu view đã được gán ảnh khác trong lúc chờ. */
    /** Nạp vào ImageView dưới dạng ảnh bo góc khử răng cưa (RoundImage). */
    void into(ImageView v, String uri, int reqW, float radius) {
        v.setTag(uri);
        boolean cached = uri != null && cache.get(uri + "@" + reqW) != null;
        if (!cached) { v.setImageDrawable(null); v.setAlpha(0f); }
        load(uri, reqW, b -> {
            if (uri == null || !uri.equals(v.getTag())) return;
            v.setImageDrawable(b == null ? null : new RoundImage(b, radius));
            // ảnh có sẵn thì hiện ngay; ảnh vừa tải xong thì mờ dần vào, không bật "bụp"
            if (cached) v.setAlpha(1f);
            else v.animate().alpha(1f).setDuration(260).start();
        });
    }

    /** Ảnh ô app cho hàng "Mở gần đây": "app:<package>" -> banner TV của app (không có thì biểu tượng trên nền tối). */
    static final String APP = "app:";

    private Bitmap appBanner(String pkg, int reqW) throws Exception {
        android.content.pm.PackageManager pm = ctx.getPackageManager();
        android.graphics.drawable.Drawable d = null;
        android.content.Intent lb = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (lb != null) { try { d = pm.getActivityBanner(lb); } catch (Exception ignored) {} }
        if (d == null) d = pm.getApplicationBanner(pkg);
        int w = reqW, h = reqW * 9 / 16;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        if (d != null) {
            d.setBounds(0, 0, w, h);
            d.draw(c);
            return b;
        }
        c.drawColor(0xFF1C1C1E);
        android.graphics.drawable.Drawable icon = pm.getApplicationIcon(pkg);
        int s = h / 2;
        icon.setBounds((w - s) / 2, (h - s) / 2, (w + s) / 2, (h + s) / 2);
        icon.draw(c);
        return b;
    }

    private Bitmap decode(String uri, int reqW) throws Exception {
        if (uri.startsWith(APP)) return appBanner(uri.substring(APP.length()), reqW);
        byte[] data = readAll(open(uri));
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        int s = 1;
        while (o.outWidth / (s * 2) >= reqW) s *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = s;
        d.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeByteArray(data, 0, data.length, d);
    }

    private InputStream open(String uri) throws Exception {
        // vài app (iQIYI) đưa ảnh qua http thường, bị chặn vì không mã hoá; máy chủ ảnh của họ có https nên đổi sang
        if (uri.startsWith("http://")) uri = "https://" + uri.substring(7);
        if (uri.startsWith("https://")) {
            HttpURLConnection c = (HttpURLConnection) new URL(uri).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(10000);
            c.setInstanceFollowRedirects(true);
            return c.getInputStream();
        }
        if (uri.startsWith(Wallpapers.ASSET)) return ctx.getAssets().open(uri.substring(Wallpapers.ASSET.length()));
        return ctx.getContentResolver().openInputStream(Uri.parse(uri));
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream i = in; ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = i.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }
}
