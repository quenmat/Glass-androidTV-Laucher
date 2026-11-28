package vn.glass.home;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Trình phát kênh IPTV toàn màn hình của Glass Home, thay VLC. Dùng chính trình phát của chip Amlogic (MediaPlayer,
 * qua TvPreview) đọc thẳng luồng udp:// của modem: đường IPTV của hãng, khử xen kẽ 1080i bằng phần cứng. Xin hệ thống
 * chuyển màn hình sang 50Hz cho khớp nhịp truyền hình (1080i50) trong lúc xem, thoát ra thì hệ thống tự trả về.
 * Lên / xuống: đổi kênh. Back: về launcher. Không phát được thì mở trình phát ngoài (VLC) như cũ.
 */
public final class TvPlayer extends Activity implements SurfaceHolder.Callback {
    static final String EXTRA_NAME = "kenh";

    private SurfaceView view;
    private Surface surface;
    private TvPreview player;
    private TextView label;
    private List<Tv.Channel> channels;
    private int index = -1;
    private final Runnable hideLabel = () -> { if (label != null) label.animate().alpha(0f).setDuration(600).start(); };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        view = new SurfaceView(this);
        view.getHolder().addCallback(this);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));
        label = new TextView(this);
        label.setTextColor(Color.WHITE);
        label.setTextSize(28);
        label.setShadowLayer(12, 0, 2, 0xCC000000);
        label.setAlpha(0f);
        FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        ll.setMargins(72, 56, 0, 0);
        root.addView(label, ll);
        setContentView(root);

        channels = Tv.load(this);
        String name = getIntent().getStringExtra(EXTRA_NAME);
        for (int i = 0; i < channels.size(); i++) if (channels.get(i).name.equals(name)) { index = i; break; }
        if (index < 0) { finish(); return; }
    }

    @Override public void surfaceCreated(SurfaceHolder h) {
        surface = h.getSurface();
        // khớp nhịp truyền hình: hình 50 trường mỗi giây, xin màn hình 50Hz (hệ thống đang để "khớp tốc độ khung hình: luôn luôn")
        try { surface.setFrameRate(50f, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, Surface.CHANGE_FRAME_RATE_ALWAYS); }
        catch (Exception e) { android.util.Log.i("GlassTvPlayer", "khong xin duoc 50Hz " + e); }
        play();
    }

    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {}

    @Override public void surfaceDestroyed(SurfaceHolder h) {
        stopPlayer();
        surface = null;
    }

    private void play() {
        stopPlayer();
        if (surface == null || index < 0) return;
        Tv.Channel ch = channels.get(index);
        Tv.remember(this, ch.name);
        showLabel(ch.name);
        final Tv.Channel want = ch;
        player = TvPreview.start(this, ch.url, surface, new TvPreview.Listener() {
            @Override public void onFirstFrame() { android.util.Log.i("GlassTvPlayer", "vao hinh " + want.name); }
            @Override public void onFailed() {
                android.util.Log.i("GlassTvPlayer", "khong phat duoc " + want.name + ", mo trinh phat ngoai");
                if (isFinishing() || channels.get(index) != want) return;
                stopPlayer();
                Tv.playExternal(TvPlayer.this, want, null);
                finish();
            }
        });
        if (player == null) { Tv.playExternal(this, ch, null); finish(); }
    }

    private void showLabel(String s) {
        label.removeCallbacks(hideLabel);
        label.setText(s);
        label.animate().cancel();
        label.animate().alpha(1f).setDuration(250).start();
        label.postDelayed(hideLabel, 3000);
    }

    private void stopPlayer() {
        TvPreview p = player;
        player = null;
        if (p != null) p.stop();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (channels != null && !channels.isEmpty()) {
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_CHANNEL_UP) { step(-1); return true; }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_CHANNEL_DOWN) { step(1); return true; }
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) { showLabel(channels.get(index).name); return true; }
        }
        return super.onKeyDown(code, e);
    }

    private void step(int d) {
        int n = channels.size();
        index = ((index + d) % n + n) % n;
        play();
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopPlayer();
        TvPreview.releaseAll();   // thả bộ đệm vòng 4MB
        finish();                 // không giữ màn phát chạy ngầm: về launcher là đóng hẳn
    }
}
