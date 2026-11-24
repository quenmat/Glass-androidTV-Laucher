package vn.glass.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.util.Calendar;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Bản tin thời tiết khi bật tivi, sống theo cả tiến trình chứ không theo màn hình launcher: đang ở YouTube lâu thì
// hệ thống dọn màn hình launcher để nhường RAM, nhưng tiến trình vẫn còn (dịch vụ HomeGuard / giữ loa giữ nó). Nghe
// tắt / bật màn hình ở đây, nên sáng tivi bật thẳng vào YouTube vẫn đọc bản tin; thẻ hiện khi về màn hình chính.
// Buổi sáng ngày thường đọc to (lần bật đầu tiên trong ngày), lúc khác chỉ hiện thẻ. Số cũ hơn 45 phút thì lấy lại
// rồi mới dùng, không lấy được thì thôi (không báo sai).
final class Briefing {
    /** Màn hình launcher đang sống (có thể null). */
    interface Host {
        boolean canShowCard();
        void showBrief(Morning.Brief b);
        void speakingChanged(boolean on);
        void briefSkipped();          // không hiện được thẻ ngay: launcher tự xét thẻ nhắc
        void screenChanged(boolean on);
    }

    private static Briefing inst;

    static synchronized Briefing get(Context c) {
        if (inst == null) inst = new Briefing(c.getApplicationContext());
        return inst;
    }

    private static final long FRESH_MS = 45 * 60_000L;

    private final Context app;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor(new LowPriority("glass-bantin"));

    Host host;
    boolean done, busy;        // lần bật này đã chạy bản tin / đang lấy số
    boolean remindWake;        // lần bật này chưa xét thẻ nhắc (giỗ, rằm...)
    Morning.Brief pending;     // bản tin chưa kịp hiện thẻ (đang ở app khác)

    private Briefing(Context app) {
        this.app = app;
        // box vừa khởi động là một lần "bật tivi"; tiến trình bị dọn rồi mở lại trong cùng lần khởi động thì không
        long boot = (System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()) / 60_000L;
        SharedPreferences p = prefs();
        remindWake = Math.abs(p.getLong("nhac_boot", -10) - boot) > 1;
        if (remindWake) p.edit().putLong("nhac_boot", boot).apply();
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        app.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                boolean on = Intent.ACTION_SCREEN_ON.equals(i.getAction());
                if (!on) { remindWake = true; done = false; pending = null; stopSpeech(); }
                else onWake();   // bản tin ngay khi màn hình sáng (sáng ngày thường thì đọc to)
                if (host != null) host.screenChanged(on);
            }
        }, f);
    }

    private SharedPreferences prefs() { return app.getSharedPreferences("glass", Context.MODE_PRIVATE); }

    /** Lần bật tivi này chưa có bản tin thì chạy: lấy số (còn mới thì dùng luôn), đọc nếu là sáng ngày thường. */
    void onWake() {
        if (done || busy) return;
        done = true;
        busy = true;
        Calendar c = Calendar.getInstance();
        String today = c.get(Calendar.YEAR) + "-" + c.get(Calendar.DAY_OF_YEAR);
        boolean speak = Morning.window(c) && !today.equals(prefs().getString("bantin_doc", ""));
        if (speak) ttsPrepare();   // khởi động giọng đọc (~2 giây) song song với lấy số
        fresh(n -> ready(n, speak, today));
    }

    /** Xem thử từ máy tính: lấy số mới, hiện thẻ và đọc, bất kể giờ. */
    void preview() {
        busy = true;
        fresh(n -> {
            busy = false;
            if (n == null) return;
            Morning.Brief b = Morning.build(n);
            say(b.speech);
            if (host != null && host.canShowCard()) host.showBrief(b);
        });
    }

    private interface Got { void got(Weather.Now n); }

    /** Số thời tiết dưới 45 phút tuổi; cũ hơn thì lấy lại (thử 5 lần, cách 2 giây); không có thì null. */
    private void fresh(Got cb) {
        Weather.Now cached = Weather.cached(app);
        if (cached != null && cached.hasWx() && System.currentTimeMillis() - cached.wxAt < FRESH_MS) { cb.got(cached); return; }
        net.execute(() -> {
            Weather.Now n = null;
            for (int k = 0; k < 5; k++) {
                n = Weather.refreshNow(app);
                if (n != null && System.currentTimeMillis() - n.wxAt < FRESH_MS) break;
                n = null;
                try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
            }
            Weather.Now got = n;
            ui.post(() -> cb.got(got));
        });
    }

    private void ready(Weather.Now n, boolean speak, String today) {
        busy = false;
        if (n == null || !n.hasWx()) { stopSpeech(); if (host != null) host.briefSkipped(); return; }
        Morning.Brief b = Morning.build(n);
        if (speak) { prefs().edit().putString("bantin_doc", today).apply(); say(b.speech); }
        if (host != null && host.canShowCard()) host.showBrief(b);
        else { pending = b; if (host != null) host.briefSkipped(); }
    }

    // ---------- giọng đọc Google tiếng Việt ----------
    // Đọc tới hết kể cả khi bé đã mở YouTube: trong lúc đọc xin ưu tiên âm thanh tạm thời, app đang phát tự dừng,
    // đọc xong trả lại thì phát tiếp. Máy chưa có giọng Việt thì im. Đọc xong nhả ngay (không giữ RAM).

    private android.speech.tts.TextToSpeech tts;
    private boolean ttsReady;
    boolean talking;
    private String ttsPending;
    private android.media.AudioFocusRequest focus;
    private final Runnable giveUp = () -> { if (!talking) stopSpeech(); };

    private void ttsPrepare() {
        stopSpeech();
        final android.speech.tts.TextToSpeech[] h = new android.speech.tts.TextToSpeech[1];
        android.speech.tts.TextToSpeech.OnInitListener init = st -> ui.post(() -> {
            if (tts == null || tts != h[0]) return;
            if (st != android.speech.tts.TextToSpeech.SUCCESS) { android.util.Log.i("GlassTts", "khoi dong loi " + st); stopSpeech(); return; }
            int r = tts.setLanguage(new Locale("vi", "VN"));
            android.util.Log.i("GlassTts", "tieng Viet " + r);
            if (r < 0) { stopSpeech(); return; }
            tts.setSpeechRate(0.95f);
            tts.setAudioAttributes(attrs());
            tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                @Override public void onStart(String id) { ui.post(() -> { talking = true; if (host != null) host.speakingChanged(true); }); }
                @Override public void onDone(String id) { ui.post(Briefing.this::stopSpeech); }
                @Override public void onError(String id) { ui.post(Briefing.this::stopSpeech); }
            });
            ttsReady = true;
            if (ttsPending != null) speakNow();
        });
        try {
            h[0] = new android.speech.tts.TextToSpeech(app, init, "com.google.android.tts");
            tts = h[0];
            ui.removeCallbacks(giveUp);
            ui.postDelayed(giveUp, 60_000);   // không có gì để đọc thì nhả
        } catch (Exception e) { android.util.Log.i("GlassTts", "khong tao duoc " + e); }
    }

    private static android.media.AudioAttributes attrs() {
        return new android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build();
    }

    void say(String text) {
        if (tts == null) ttsPrepare();   // khởi động trước (bước này dọn hàng chờ), rồi mới xếp câu vào
        ttsPending = text;
        if (ttsReady) speakNow();
    }

    private void speakNow() {
        String text = ttsPending;
        ttsPending = null;
        if (tts == null || text == null) return;
        android.media.AudioManager am = app.getSystemService(android.media.AudioManager.class);
        if (am != null) {
            focus = new android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attrs()).setOnAudioFocusChangeListener(fc -> {}).build();
            am.requestAudioFocus(focus);
        }
        tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "bantin");
    }

    void stopSpeech() {
        android.speech.tts.TextToSpeech t = tts;
        tts = null;
        ttsReady = false; talking = false; ttsPending = null;
        ui.removeCallbacks(giveUp);
        if (t != null) { try { t.stop(); t.shutdown(); } catch (Exception ignored) {} }
        if (focus != null) {
            android.media.AudioManager am = app.getSystemService(android.media.AudioManager.class);
            if (am != null) am.abandonAudioFocusRequest(focus);   // trả lại: YouTube phát tiếp
            focus = null;
        }
        if (host != null) host.speakingChanged(false);
    }
}
