package vn.glass.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Nhận ảnh từ điện thoại trong mạng nhà: máy chủ web nhỏ chỉ chạy khi màn "Gửi ảnh từ điện thoại" đang mở.
// Đường dẫn có mã ngẫu nhiên (đổi mỗi lần mở) nên chỉ ai nhìn thấy mã QR trên tivi mới gửi được.
// Trang web tự thu ảnh về 1920x1080 ngay trên điện thoại rồi mới gửi (nhẹ mạng); tivi vẫn cắt/thu lại cho chắc.
final class PhotoDrop {
    interface Listener { void onPhoto(int total); }

    private static final int MAX_BODY = 40 * 1024 * 1024;
    private final File dir;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    // mỗi kết nối một luồng: trình duyệt điện thoại mở sẵn vài kết nối "để dành" không gửi gì; nếu chỉ có vài luồng,
    // các kết nối đó chiếm hết và yêu cầu thật phải xếp hàng tới khi điện thoại báo không truy cập được
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private ServerSocket server;
    private volatile boolean running;
    private int received;
    final String token;
    String url;

    PhotoDrop(File dir, Listener l) {
        this.dir = dir;
        this.listener = l;
        String abc = "abcdefghjkmnpqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) sb.append(abc.charAt(r.nextInt(abc.length())));
        token = sb.toString();
    }

    /** Mở cổng (8080, bận thì thử tiếp tới 8099). Trả về đường dẫn, null nếu tivi chưa vào mạng. */
    String start(Context ctx) {
        String ip = lanAddress(ctx);
        if (ip == null) return null;
        for (int port = 8080; port < 8100 && server == null; port++) {
            try { server = new ServerSocket(port); } catch (Exception ignored) {}
        }
        if (server == null) return null;
        running = true;
        url = "http://" + ip + ":" + server.getLocalPort() + "/" + token;
        pool.execute(this::acceptLoop);
        return url;
    }

    void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        pool.shutdownNow();
    }

    /** Địa chỉ IPv4 của tivi trong mạng nhà (Wi-Fi hay dây mạng đều được). */
    static String lanAddress(Context ctx) {
        try {
            ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
            LinkProperties lp = cm.getLinkProperties(cm.getActiveNetwork());
            if (lp == null) return null;
            for (LinkAddress a : lp.getLinkAddresses())
                if (a.getAddress() instanceof Inet4Address && !a.getAddress().isLoopbackAddress())
                    return a.getAddress().getHostAddress();
        } catch (Exception ignored) {}
        return null;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                pool.execute(() -> handle(s));
            } catch (Exception e) {
                if (!running) return;
            }
        }
    }

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(15_000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            OutputStream out = sock.getOutputStream();
            String who = String.valueOf(sock.getInetAddress());
            // trình duyệt tự nâng lên https (bắt tay TLS mở đầu bằng byte 0x16): đóng ngay để nó quay về http,
            // thay vì treo chờ dòng lệnh HTTP rồi báo "không truy cập được"
            in.mark(1);
            int first = in.read();
            if (first == 0x16) { android.util.Log.i("GlassDrop", who + " https -> dong"); return; }
            in.reset();
            String line = readLine(in);
            android.util.Log.i("GlassDrop", who + " " + line);   // adb logcat -s GlassDrop: điện thoại có tới được tivi không
            if (line == null) return;
            String[] req = line.split(" ");
            if (req.length < 2) return;
            int len = 0;
            for (String h; (h = readLine(in)) != null && !h.isEmpty(); ) {
                int c = h.indexOf(':');
                if (c > 0 && h.substring(0, c).trim().equalsIgnoreCase("Content-Length"))
                    len = Integer.parseInt(h.substring(c + 1).trim());
            }
            String path = req[1];
            if (req[0].equals("GET") && path.equals("/" + token)) {
                reply(out, 200, "text/html; charset=utf-8", PAGE.getBytes("UTF-8"));
            } else if (req[0].equals("POST") && path.equals("/" + token + "/up")) {
                if (len <= 0 || len > MAX_BODY) { reply(out, 413, "text/plain", "too big".getBytes()); return; }
                byte[] body = new byte[len];
                for (int o = 0; o < len; ) {
                    int n = in.read(body, o, len - o);
                    if (n < 0) throw new java.io.EOFException();
                    o += n;
                }
                boolean ok = save(body);
                reply(out, ok ? 200 : 415, "text/plain", (ok ? "ok" : "bad image").getBytes());
            } else {
                // sai đường dẫn (thiếu mã, hoặc mã của lần mở trước): trang báo rõ thay vì chữ "not found" trơn
                reply(out, req[0].equals("GET") ? 200 : 404, "text/html; charset=utf-8", WRONG.getBytes("UTF-8"));
            }
        } catch (Exception ignored) {}
    }

    /** Giải mã, cắt giữa về 16:9, thu về tối đa 1920x1080, lưu JPEG. Tên bắt đầu "dt-" để script đồng bộ không xoá. */
    private boolean save(byte[] data) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth <= 0) return false;
        int s = 1;
        while (o.outWidth / (s * 2) >= 1920 && o.outHeight / (s * 2) >= 1080) s *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = s;
        Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, d);
        if (b == null) return false;
        int w = b.getWidth(), h = b.getHeight();
        int cw = w, ch = w * 9 / 16;
        if (ch > h) { ch = h; cw = h * 16 / 9; }
        Bitmap crop = Bitmap.createBitmap(b, (w - cw) / 2, (h - ch) / 2, cw, ch);
        Bitmap fin = cw > 1920 ? Bitmap.createScaledBitmap(crop, 1920, 1080, true) : crop;
        dir.mkdirs();
        String name = String.format(Locale.ROOT, "dt-%d-%03d.jpg", System.currentTimeMillis(), (int) (Math.random() * 1000));
        File tmp = new File(dir, name + ".part");
        try (FileOutputStream f = new FileOutputStream(tmp)) {
            fin.compress(Bitmap.CompressFormat.JPEG, 90, f);
        } catch (Exception e) {
            tmp.delete();
            return false;
        }
        if (!tmp.renameTo(new File(dir, name))) return false;
        int total;
        synchronized (this) { total = ++received; }
        ui.post(() -> listener.onPhoto(total));
        return true;
    }

    private static String readLine(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int c; (c = in.read()) != -1; ) {
            if (c == '\n') return b.toString("UTF-8").replace("\r", "");
            if (b.size() > 8192) return null;
            b.write(c);
        }
        return b.size() == 0 ? null : b.toString("UTF-8");
    }

    private static void reply(OutputStream out, int code, String type, byte[] body) throws java.io.IOException {
        String head = "HTTP/1.1 " + code + " OK\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        out.write(body);
        out.flush();
    }

    private static final String WRONG = "<!doctype html><html lang=vi><head><meta charset=utf-8>"
            + "<meta name=viewport content='width=device-width,initial-scale=1'><title>Glass Home</title><style>"
            + ":root{color-scheme:dark}body{margin:0;background:#111214;color:#ece8e1;font:17px/1.55 system-ui,sans-serif;padding:32px 22px}"
            + "h1{font-size:23px;margin:0 0 10px}p{color:#9a968f;margin:0}</style></head><body>"
            + "<h1>Đường dẫn chưa đúng</h1><p>Hãy quét lại mã QR đang hiện trên tivi. Mỗi lần mở hộp gửi ảnh, mã đổi một lần, "
            + "nên đường dẫn cũ hoặc thiếu phần cuối sẽ không dùng được.</p></body></html>";

    // trang trên điện thoại: chọn nhiều ảnh, thu về 1920x1080 (cắt giữa 16:9) bằng canvas rồi gửi lần lượt
    private static final String PAGE = "<!doctype html><html lang=vi><head><meta charset=utf-8>"
            + "<meta name=viewport content='width=device-width,initial-scale=1'><title>Gửi ảnh lên tivi</title><style>"
            + ":root{color-scheme:dark}body{margin:0;background:#111214;color:#ece8e1;font:16px/1.5 system-ui,sans-serif;padding:28px 20px}"
            + "h1{font-size:24px;margin:0 0 6px}p{color:#9a968f;margin:0 0 22px}"
            + "label{display:block;text-align:center;padding:18px;border-radius:16px;background:#ece8e1;color:#111214;font-weight:600;font-size:18px}"
            + "input{display:none}#st{margin-top:22px;font-size:15px;color:#c9c5be}.bar{height:6px;border-radius:3px;background:#2a2b2f;margin-top:10px;overflow:hidden}"
            + ".bar i{display:block;height:100%;width:0;background:#7fd99c;transition:width .3s}</style></head><body>"
            + "<h1>Gửi ảnh lên tivi</h1><p>Ảnh ngang đẹp nhất. Ảnh sẽ được cắt giữa về khung 16:9.</p>"
            + "<label>Chọn ảnh<input id=f type=file accept='image/*' multiple></label><div id=st></div><div class=bar><i id=b></i></div>"
            + "<script>"
            + "const st=document.getElementById('st'),bar=document.getElementById('b');"
            + "function shrink(file){return new Promise(res=>{const u=URL.createObjectURL(file),im=new Image();"
            + "im.onload=()=>{let w=im.naturalWidth,h=im.naturalHeight,cw=w,ch=Math.round(w*9/16);if(ch>h){ch=h;cw=Math.round(h*16/9)}"
            + "const W=Math.min(1920,cw),H=Math.round(W*9/16),c=document.createElement('canvas');c.width=W;c.height=H;"
            + "c.getContext('2d').drawImage(im,(w-cw)/2,(h-ch)/2,cw,ch,0,0,W,H);URL.revokeObjectURL(u);c.toBlob(b=>res(b||file),'image/jpeg',0.9)};"
            + "im.onerror=()=>{URL.revokeObjectURL(u);res(file)};im.src=u})}"
            + "document.getElementById('f').onchange=async e=>{const fs=[...e.target.files];let ok=0,bad=0;"
            + "for(let i=0;i<fs.length;i++){st.textContent='Đang gửi '+(i+1)+'/'+fs.length+'...';"
            + "try{const b=await shrink(fs[i]);const r=await fetch(location.pathname+'/up',{method:'POST',body:b});r.ok?ok++:bad++}catch(_){bad++}"
            + "bar.style.width=((i+1)/fs.length*100)+'%'}"
            + "st.textContent='Đã gửi '+ok+' ảnh'+(bad?', '+bad+' ảnh lỗi':'')+'. Bạn có thể chọn thêm.';e.target.value=''};"
            + "</script></body></html>";
}
