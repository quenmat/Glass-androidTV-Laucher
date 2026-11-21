package vn.glass.home;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;

/**
 * Phát thử một kênh IPTV ngay trong launcher (khung xem trước ở bảng kênh). Luồng udp://@nhóm:cổng của nhà mạng được
 * nhận bằng MulticastSocket vào một bộ đệm vòng, rồi đưa cho MediaPlayer của Android qua một cổng http nội bộ
 * (MediaPlayer không tự đọc được địa chỉ udp://). Giải mã bằng chip của box, hình ra Surface của khung xem trước.
 *
 * Bộ nhớ: mỗi lúc chỉ một phiên. stop() nhả hết: MediaPlayer, socket, cổng http nội bộ, các luồng, khoá multicast của Wi-Fi. Bộ đệm vòng
 * (4MB, ~4 giây luồng) dùng lại giữa các kênh khi bảng còn mở; releaseAll() (đóng bảng, rời launcher) thả luôn bộ đệm.
 */
final class TvPreview {
    interface Listener {
        void onFirstFrame();
        void onFailed();
    }

    private static final int RING = 4 << 20;
    private static byte[] ring;              // dùng lại giữa các phiên; null sau releaseAll()

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private long written;                    // tổng số byte đã nhận (vị trí luồng)
    private volatile boolean stopped;
    private boolean prepared, closing;       // closing: đang nhỏ tiếng dần để dừng, không được bật lại
    private MulticastSocket sock;
    private java.net.ServerSocket srv;
    private java.net.Socket client;
    private Thread rx;
    private MediaPlayer mp;
    private WifiManager.MulticastLock mlock;
    private float vol;
    private Runnable ramp;

    /** Bắt đầu phát url (udp://@225.1.2.3:30120) ra surface; trả về null nếu địa chỉ không phải udp multicast. */
    static TvPreview start(Context c, String url, Surface surface, Listener l) {
        if (url == null || !url.startsWith("udp://")) return null;
        TvPreview p = new TvPreview();
        try { p.open(c.getApplicationContext(), url, surface, l); return p; }
        catch (Exception e) { p.stop(); return null; }
    }

    private void open(Context c, String url, Surface surface, Listener l) throws Exception {
        String hp = url.substring("udp://".length());
        if (hp.startsWith("@")) hp = hp.substring(1);
        int k = hp.lastIndexOf(':');
        InetAddress group = InetAddress.getByName(hp.substring(0, k));
        int port = Integer.parseInt(hp.substring(k + 1).replaceAll("[^0-9].*", ""));
        WifiManager wm = (WifiManager) c.getSystemService(Context.WIFI_SERVICE);
        if (wm != null) { mlock = wm.createMulticastLock("glass-preview"); mlock.setReferenceCounted(false); mlock.acquire(); }
        this.surface = surface; this.listener = l;
        this.group = group; this.port = port;
        // Trình phát Amlogic (AmNuPlayer) tự đọc được udp multicast MPEG-TS, có xử lý luồng trực tiếp kiểu IPTV: thử trước.
        // Qua cổng http nội bộ thì nó coi như video mạng thường, đệm kiểu tải file, mấy giây đầu cạn đệm và tạm dừng.
        // Box khác không đọc được udp:// thì lỗi ngay lúc nạp: nhớ lại và đi đường chuyển tiếp http từ đó về sau.
        if (direct != Boolean.FALSE) {
            usingDirect = true;
            openPlayerNow(url);
            if (direct == null) ui.postDelayed(directTimeout, 4000);   // treo không báo lỗi: cũng coi là không đọc được
        }
        else startRelay();
    }

    private final Runnable directTimeout = () -> { if (!stopped && !this.firstFrame && this.usingDirect) fallbackToRelay(); };
    private static final long START_CUSHION_MS = 1000;
    private static Boolean direct;           // null: chưa biết; true/false: box đọc thẳng udp:// được hay không
    private boolean usingDirect, firstFrame;
    private InetAddress group;
    private int port;

    /** Đường dự phòng: tự nhận multicast vào bộ đệm vòng, đưa cho trình phát qua http 127.0.0.1. */
    private void startRelay() throws Exception {
        usingDirect = false;
        if (ring == null) ring = new byte[RING];
        sock = new MulticastSocket(null);
        sock.setReuseAddress(true);
        sock.setReceiveBufferSize(1 << 20);
        sock.bind(new InetSocketAddress(port));
        sock.joinGroup(new InetSocketAddress(group, port), (NetworkInterface) null);
        MulticastSocket s = sock;
        rx = new Thread(() -> receive(s), "glass-preview-rx");
        rx.start();

        // Chưa mở trình phát ngay: gom đệm trước (PREBUFFER, ~1,5 giây luồng) rồi mới mở. Mở ngay khi mới có ~1 giây thì
        // trình phát chạy sát đáy đệm, tới giây thứ 4-5 cạn đệm ("buffer low, pausing"), hình khựng mấy giây đầu.
        ui.postDelayed(prebufferTimeout, PREBUFFER_MAX_MS);
    }

    private static final int PREBUFFER = 1_400_000;          // byte (~1,5 giây ở ~7-8Mbps)
    private static final long PREBUFFER_MAX_MS = 3000;        // luồng nhẹ hơn: chờ tối đa chừng này rồi vẫn mở
    private Surface surface;
    private Listener listener;
    private boolean playerOpened;
    private final Runnable prebufferTimeout = this::openPlayer;

    private void openPlayer() {
        ui.removeCallbacks(prebufferTimeout);
        if (playerOpened || stopped) return;
        playerOpened = true;
        try {
            srv = new java.net.ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"));
            java.net.ServerSocket sv = srv;
            new Thread(() -> serve(sv), "glass-preview-http").start();
            openPlayerNow("http://127.0.0.1:" + srv.getLocalPort() + "/live.ts");
        } catch (Exception e) { if (listener != null && !stopped) listener.onFailed(); }
    }

    /** Đọc thẳng udp:// hỏng (lần đầu trên box này): nhả trình phát đó, chuyển sang đường chuyển tiếp http. */
    private void fallbackToRelay() {
        ui.removeCallbacks(directTimeout);
        direct = Boolean.FALSE;
        MediaPlayer m = mp; mp = null; prepared = false;
        if (m != null) {
            m.setOnInfoListener(null); m.setOnErrorListener(null); m.setOnPreparedListener(null);
            new Thread(() -> { try { m.reset(); } catch (Exception ignored) {} m.release(); }, "glass-preview-rel").start();
        }
        try { startRelay(); } catch (Exception e) { if (listener != null) listener.onFailed(); }
    }

    private void openPlayerNow(String source) throws Exception {
        Listener l = listener;
        mp = new MediaPlayer();
        mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
        mp.setDataSource(source);
        mp.setSurface(surface);
        mp.setVolume(0f, 0f);
        // vừa nạp xong mà phiên đang tắt dần (rời kênh ngay lúc đó): dừng luôn. Không thì fadeTo to dần sẽ thay mất
        // nhịp nhỏ dần kèm lệnh dừng, phiên mồ côi phát tiếng mãi (nghe hai tiếng chồng nhau)
        mp.setOnPreparedListener(m -> {
            prepared = true;
            if (closing) { stop(); return; }
            // luồng trực tiếp: nạp xong thì chờ thêm START_CUSHION_MS cho trình phát gom đệm rồi mới chạy. Chạy ngay thì nó
            // sát đáy đệm, mấy giây đầu cứ "buffer low, pausing" vài trăm ms một lần (hình khựng). Ảnh kênh vẫn che tới khung đầu.
            ui.postDelayed(() -> { if (!stopped && !closing && mp == m) { m.start(); fadeTo(1f, 1500, null); } }, START_CUSHION_MS);
        });
        mp.setOnInfoListener((m, what, extra) -> {
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && !stopped) {
                if (usingDirect && !firstFrame) { direct = Boolean.TRUE; ui.removeCallbacks(directTimeout); }
                firstFrame = true;
                if (l != null) l.onFirstFrame();
            }
            return false;
        });
        mp.setOnErrorListener((m, what, extra) -> {
            if (stopped) return true;
            if (usingDirect && !firstFrame && direct == null) fallbackToRelay();
            else if (l != null) l.onFailed();
            return true;
        });
        mp.prepareAsync();
    }

    private void receive(MulticastSocket s) {
        byte[] buf = new byte[65536];
        DatagramPacket pk = new DatagramPacket(buf, buf.length);
        try {
            while (!stopped) {
                s.receive(pk);
                int n = pk.getLength(), off = pk.getOffset();
                // gói RTP (byte đầu 0x80): bỏ 12 byte đầu; gói MPEG-TS thô bắt đầu bằng 0x47
                if (n > 12 && (buf[off] & 0xC0) == 0x80 && buf[off] != 0x47) { off += 12; n -= 12; }
                byte[] r = ring;
                if (r == null) break;
                synchronized (lock) {
                    int pos = (int) (written % RING);
                    int a = Math.min(n, RING - pos);
                    System.arraycopy(buf, off, r, pos, a);
                    if (a < n) System.arraycopy(buf, off + a, r, 0, n - a);
                    written += n;
                    lock.notifyAll();
                    if (written >= PREBUFFER && written - n < PREBUFFER) ui.post(this::openPlayer);
                }
            }
        } catch (Exception ignored) {}
    }

    /**
     * Cổng http nội bộ (127.0.0.1, cổng ngẫu nhiên) chuyển tiếp luồng từ bộ đệm vòng cho MediaPlayer. Không dùng
     * MediaDataSource: MediaPlayer đọc nó qua binder từng mẩu ~2KB, phần hình ~7Mbps không kịp, 95% khung hình bị bỏ vì tới
     * trễ (hình đứng như ảnh tĩnh, chỉ có tiếng). Qua http nó đọc từng khối lớn như luồng mạng thường.
     */
    private void serve(java.net.ServerSocket srv) {
        while (!stopped) {
            java.net.Socket c;
            try { c = srv.accept(); } catch (Exception e) { return; }
            synchronized (lock) { if (client != null) try { client.close(); } catch (Exception ignored) {} client = c; }
            new Thread(() -> stream(c), "glass-preview-out").start();
        }
    }

    private void stream(java.net.Socket c) {
        byte[] out = new byte[65536];
        try {
            java.io.InputStream in = c.getInputStream();
            // bỏ qua yêu cầu (tới dòng trống), luôn trả luồng trực tiếp từ đầu những gì đang giữ
            int st = 0, ch;
            while (st < 4 && (ch = in.read()) >= 0) st = (ch == 13 || ch == 10) ? st + 1 : 0;
            java.io.OutputStream os = c.getOutputStream();
            os.write("HTTP/1.0 200 OK\r\nContent-Type: video/mp2t\r\nConnection: close\r\n\r\n".getBytes());
            long pos;
            synchronized (lock) { pos = Math.max(0, written - RING + 65536); }
            while (!stopped) {
                int n;
                synchronized (lock) {
                    while (!stopped && written <= pos) lock.wait(1000);
                    byte[] r = ring;
                    if (stopped || r == null) break;
                    pos = Math.max(pos, written - RING + 65536);   // tụt quá xa: nhảy lên chỗ cũ nhất còn giữ
                    n = (int) Math.min(out.length, written - pos);
                    int i = (int) (pos % RING), a = Math.min(n, RING - i);
                    System.arraycopy(r, i, out, 0, a);
                    if (a < n) System.arraycopy(r, 0, out, a, n - a);
                }
                os.write(out, 0, n);
                pos += n;
            }
        } catch (Exception ignored) {
        } finally { try { c.close(); } catch (Exception ignored) {} }
    }

    /** Âm lượng đổi mềm (bình phương theo thời gian để tai nghe to dần đều). */
    private void fadeTo(float target, long ms, Runnable end) {
        if (closing && end == null) return;   // đang tắt dần: không nhận lệnh to lên nữa
        if (ramp != null) ui.removeCallbacks(ramp);
        float from = vol;
        long t0 = android.os.SystemClock.uptimeMillis();
        ramp = new Runnable() {
            @Override public void run() {
                if (mp == null) return;
                float t = Math.min(1f, (android.os.SystemClock.uptimeMillis() - t0) / (float) ms);
                float e = target > from ? t * t : 1f - (1f - t) * (1f - t);
                vol = from + (target - from) * e;
                try { mp.setVolume(vol, vol); } catch (Exception ignored) {}
                if (t < 1f) ui.postDelayed(this, 16);
                else if (end != null) end.run();
            }
        };
        ui.post(ramp);
    }

    /** Nhỏ tiếng dần (~0,2 giây) rồi dừng hẳn. */
    void fadeOutAndStop() {
        if (stopped || mp == null || !prepared) { stop(); return; }   // chưa phát tiếng: dừng ngay
        closing = true;
        try { mp.setSurface(null); } catch (Exception ignored) {}   // khung sắp ẩn: thả Surface ngay, chỉ còn tiếng nhỏ dần
        fadeTo(0f, 200, this::stop);
    }

    /** Dừng ngay, nhả hết (trừ bộ đệm vòng dùng lại). Gọi nhiều lần không sao. */
    void stop() {
        ui.removeCallbacks(prebufferTimeout);
        ui.removeCallbacks(directTimeout);
        if (ramp != null) { ui.removeCallbacks(ramp); ramp = null; }
        stopped = true;
        synchronized (lock) { lock.notifyAll(); }
        MediaPlayer m = mp; mp = null;
        if (m != null) {
            m.setOnInfoListener(null); m.setOnErrorListener(null); m.setOnPreparedListener(null);
            // release có thể chờ bộ giải mã vài chục ms: làm ở luồng riêng cho bảng kênh khỏi khựng
            new Thread(() -> { try { m.reset(); } catch (Exception ignored) {} m.release(); }, "glass-preview-rel").start();
        }
        java.net.ServerSocket sv = srv; srv = null;
        if (sv != null) try { sv.close(); } catch (Exception ignored) {}
        java.net.Socket cl;
        synchronized (lock) { cl = client; client = null; }
        if (cl != null) try { cl.close(); } catch (Exception ignored) {}
        MulticastSocket s = sock; sock = null;
        if (s != null) s.close();   // luồng nhận đang chờ receive() thoát ngay
        rx = null;
        if (mlock != null) { try { mlock.release(); } catch (Exception ignored) {} mlock = null; }
    }

    /** Đóng bảng kênh / rời launcher: thả cả bộ đệm vòng. */
    static void releaseAll() { ring = null; }
}
