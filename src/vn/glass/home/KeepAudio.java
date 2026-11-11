package vn.glass.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import java.util.List;

/**
 * Giữ dàn âm thanh (soundbar cắm cổng ARC của tivi) không tự ngủ. Soundbar thấy im lặng tuyệt đối một lúc
 * (khoảng 15-20 phút) là tự chuyển chờ, tắt chế độ System Audio, tivi quay về loa trong.
 *
 * Chạy nền trong HomeGuard, nhìn cả hệ thống chứ không riêng launcher: không app nào phát tiếng suốt 5 phút
 * (ở Home, màn hình chờ, hay phim đang tạm dừng) thì phát lặp một dải nhiễu cực nhỏ (vài bước trên 16 bit,
 * khoảng -80 dBFS), tai không nghe được nhưng luồng tiếng không còn là số 0 tuyệt đối. App nào phát tiếng lại
 * là dừng ngay. Không xin audio focus nên không làm app khác dừng; box tắt màn hình thì dừng hẳn để box ngủ.
 */
final class KeepAudio {
    private static final int RATE = 48000, AMP = 4;
    private static final long QUIET_MS = 5 * 60_000L;

    private final Context ctx;
    private AudioManager am;
    private PowerManager pm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private AudioTrack track;
    private boolean armed;

    KeepAudio(Context c) {
        ctx = c.getApplicationContext();
        am = ctx.getSystemService(AudioManager.class);
        pm = ctx.getSystemService(PowerManager.class);
    }

    private final AudioManager.AudioPlaybackCallback watch = new AudioManager.AudioPlaybackCallback() {
        @Override public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> cs) { check(cs); }
    };
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { check(am.getActivePlaybackConfigurations()); }
    };
    private final Runnable quietLongEnough = () -> {
        armed = false;
        if (pm.isInteractive() && othersPlaying(am.getActivePlaybackConfigurations()) == 0) play();
    };

    void start() {
        am.registerAudioPlaybackCallback(watch, ui);
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        ctx.registerReceiver(screen, f);
        check(am.getActivePlaybackConfigurations());
    }

    void stop() {
        try { am.unregisterAudioPlaybackCallback(watch); } catch (Exception ignored) {}
        try { ctx.unregisterReceiver(screen); } catch (Exception ignored) {}
        ui.removeCallbacks(quietLongEnough); armed = false;
        silence();
    }

    private void check(List<AudioPlaybackConfiguration> cs) {
        if (!pm.isInteractive() || othersPlaying(cs) > 0) {   // có tiếng khác, hoặc box đã tắt màn hình
            ui.removeCallbacks(quietLongEnough); armed = false;
            silence();
        } else if (track == null && !armed) {                  // vừa im: đếm 5 phút
            armed = true;
            ui.postDelayed(quietLongEnough, QUIET_MS);
        }
    }

    /**
     * Số trình phát đang phát (trừ chính nhiễu của mình). App thường chỉ nhận bản đã ẩn danh của danh sách: không có
     * tên app hay thiết bị, nhưng còn trạng thái trong toString ("state:started"); trình phát tạm dừng thì không tính.
     */
    private int othersPlaying(List<AudioPlaybackConfiguration> cs) {
        int n = 0;
        for (AudioPlaybackConfiguration c : cs) if (String.valueOf(c).contains("state:started")) n++;
        if (track != null && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) n--;
        return Math.max(n, 0);
    }

    private void play() {
        if (track != null) return;
        try {
            short[] buf = new short[RATE];   // 1 giây, phát lặp
            java.util.Random r = new java.util.Random(7);
            for (int i = 0; i < buf.length; i++) buf[i] = (short) (r.nextInt(2 * AMP + 1) - AMP);
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(buf.length * 2).build();
            t.write(buf, 0, buf.length);
            t.setLoopPoints(0, buf.length, -1);
            track = t;
            t.play();
        } catch (Exception ignored) {}
    }

    private void silence() {
        if (track == null) return;
        AudioTrack t = track;
        track = null;
        try { t.stop(); } catch (Exception ignored) {}
        t.release();
    }
}
