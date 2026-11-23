package vn.glass.home;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

// Màn hình chính kiểu Apple TV:
// - ảnh nền tràn màn hình, đổi theo app đang chọn (ảnh nội dung app đẩy lên, ảnh riêng, hoặc màu)
// - khay kính đứng yên, ô app nổi lên + quầng sáng theo màu logo khi chọn
// - bấm lên: kệ nội dung của app (tối đa 5 mục), bấm xuống quay lại dock
public class MainActivity extends Activity {

    // bố cục thiết kế cho 1920x1080, nhân hệ số k nếu màn hình khác
    private float k;
    private int px(float v) { return Math.round(v * k); }

    // dock gọn: ô 220x138 (tỉ lệ ~1,6 như ảnh ô), khay 176 cao; 7 ô: 7 x 220 + 6 x 24 + 44 = 1728 rộng, căn giữa màn
    private static final int CARD_W = 220, CARD_H = 138, CARD_GAP = 24, TRAY_PAD_X = 22, TRAY_PAD_Y = 19;
    private static final float CARD_R = 16;
    // Ô đang chọn phóng tới đúng 243x152 điểm ảnh (~110,3% cả hai chiều): ảnh logo dựng sẵn đúng cỡ này hiển thị 1:1
    // với điểm ảnh màn hình nên sắc nét, mép ô và lỗ khoét của quầng nằm trên toạ độ nguyên nên khít nhau từng điểm
    // ảnh, kể cả góc bo (cùng độ bo 12% chiều cao như ảnh ô).
    private static final int SEL_W = 243, SEL_H = 152;
    private static final float SCALE_X = SEL_W / (float) CARD_W, SCALE_Y = SEL_H / (float) CARD_H;
    private static final float SEL_R = SEL_H * 0.12f;
    // dock + hàng nội dung dồn xuống dưới để ảnh nền hở rộng
    private static final int TRAY_TOP = 614;
    // bố cục (đồng hồ, ô giá, hàng nội dung) theo khay 6 ô; cài thêm app thì khay dài ra về bên phải, tràn khỏi màn hình
    private static final int BASE_N = 6;
    private static int trayW(int n) { return n * CARD_W + (n - 1) * CARD_GAP + TRAY_PAD_X * 2; }
    // mép trái khay: dock vừa trong lưới thì căn giữa theo đúng số app; dài hơn lưới thì neo mép trái lưới, trượt ngang
    private int trayLeft() {
        int w = trayW(apps.size());
        return w <= GRID_W ? (1920 - w) / 2 : (1920 - GRID_W) / 2;
    }
    // lưới chung của màn hình (đồng hồ, nút Cài đặt, ô giá, hàng nội dung): rộng 1842, lề 30 (khung của dock cũ), không
    // đổi theo cỡ dock
    private static final int GRID_W = 1842, GRID_PAD = 30;
    // hàng nội dung: 5 ô 16:9 (332x187), bề ngang tính theo dock (xem buildShelf). Hàng nằm TRÊN dock, cách mép trên khay
    // ROW_GAP: đủ chỗ cho ô phim đang chọn phóng to (lấn xuống ~23px) mà không chạm ô app bên dưới
    // SHELF_N: số ô vừa một màn (định cỡ ô); ROW_N: số ô thường của một hàng (dư SHELF_N thì hàng cuộn ngang theo ô
    // đang chọn); ROW_MAX: số ô tối đa (FPT, TV360: đủ mọi trận của vòng)
    private static final int SHELF_N = 5, ROW_N = 10, ROW_MAX = 12, THUMB_GAP = 30, ROW_GAP = 40, ROW_TOP = 614 - ROW_GAP - 187;
    private final int[] thumbSize = new int[2];
    private static final float ROW_IDLE_ALPHA = 0.80f, THUMB_R = 12;   // hàng lúc đang ở dock: trong hơn để nền và dock nổi

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Images images;
    private Typeface regular, semibold;
    private List<Apps.App> apps;
    private Map<String, List<Shelf.Item>> shelf = Collections.emptyMap();

    private FrameLayout root;
    private ImageView bgFront, bgBack;
    private String bgShown;
    private View scrim;
    private TextView clock, rowHead;
    private List<Shelf.Item> rowItems = Collections.emptyList();
    private String rowKey;
    private String rowApp;   // app của hàng đang hiện (để biết lần vẽ lại chỉ là hàng tải lại hay đã đổi app)
    private int shelfVersion;
    private LinearLayout tray, shelfRow;
    private final List<FrameLayout> cards = new ArrayList<>();
    private final List<FrameLayout> thumbs = new ArrayList<>();
    private FrameLayout allApps;
    private int focused;
    private boolean inShelf;

    /**
     * Chỉ một bản launcher được sống. Mở bằng lệnh thường (không phải nút Home: lệnh từ app khác, từ máy tính) thì
     * Android dựng thêm một bản trong task riêng, giữ thêm cả giao diện lẫn ảnh, box 2GB tốn gấp đôi RAM.
     * Bản mới không phải từ nút Home mà đã có bản đang sống: chuyển lệnh sang bản đó (qua nút Home) rồi tự đóng.
     * Bản mới từ nút Home: đóng bản lạc cũ, giữ bản này.
     */
    private static java.lang.ref.WeakReference<MainActivity> live;
    private static final String FWD_ACTION = "glass_act";

    private boolean singleInstance() {
        MainActivity old = live == null ? null : live.get();
        boolean home = getIntent() != null && getIntent().hasCategory(Intent.CATEGORY_HOME);
        if (old != null && old != this && !old.isFinishing() && old.getTaskId() != getTaskId()) {
            if (!home) {
                Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(getPackageName())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (getIntent() != null && getIntent().getAction() != null) i.putExtra(FWD_ACTION, getIntent().getAction());
                if (getIntent() != null && getIntent().getExtras() != null) i.putExtras(getIntent().getExtras());
                startActivity(i);
                finishAndRemoveTask();
                return false;
            }
            old.finishAndRemoveTask();
        }
        live = new java.lang.ref.WeakReference<>(this);
        return true;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!singleInstance()) return;
        k = getResources().getDisplayMetrics().widthPixels / 1920f;
        images = new Images(this);
        // font cho tiêu đề hàng (2 phần cùng cỡ 30): đúng opsz của cỡ đó
        regular = fontFor(400, 30); semibold = fontFor(600, 30);
        apps = Apps.dock(this);
        focused = prefs().getInt("focused", 0);
        if (focused >= apps.size()) focused = 0;
        restoreFeeds();   // hàng YouTube / FPT Play / TV360 đã lưu: có ô ngay, khỏi đợi tải mạng
        buildUi();
        setContentView(root);
        watchTvProvider(true);
        watchNetwork(true);
        watchScreen();
        // box vừa khởi động là một lần "bật tivi"; launcher bị hệ thống dọn rồi mở lại (cùng lần khởi động) thì không
        long boot = (System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()) / 60_000L;
        wakePending = Math.abs(prefs().getLong("nhac_boot", -10) - boot) > 1;
        if (wakePending) prefs().edit().putLong("nhac_boot", boot).apply();
        // mở bằng nút APPS khi launcher chưa chạy: dựng xong giao diện rồi sang ảnh kế tiếp
        String act = getIntent() == null ? null : getIntent().getAction();
        if (isAppsButton(act)) root.post(this::nextBackground);
        hideSystemBars();
    }

    // một luồng nền dùng chung (đọc nội dung, làm nhoè kính); không tạo luồng mới mỗi lần
    private final java.util.concurrent.ExecutorService bg = Executors.newSingleThreadExecutor();

    /**
     * Rời màn hình chính (đang xem phim...): nhả ảnh tải sẵn và các nền màu vẽ sẵn để app đang chạy đủ RAM.
     * Box chỉ 2GB, khi thiếu RAM hệ thống phải đẩy ra swap liên tục và video dễ khựng. Quay về thì tự tải lại.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            images.trim();
            colorBgs.evictAll();
        }
    }

    @Override
    protected void onDestroy() {
        if (root == null) { super.onDestroy(); return; }   // bản thừa tự đóng ngay trong onCreate: chưa dựng gì
        if (live != null && live.get() == this) live = null;
        watchTvProvider(false);
        watchNetwork(false);
        if (screenRx != null) try { unregisterReceiver(screenRx); } catch (Exception ignored) {}
        bg.shutdownNow();
        net.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // vừa cài/gỡ app (ví dụ từ Play Store quay về): dựng lại dock để ô mới hiện ngay trước Play Store
        List<Apps.App> now = Apps.dock(this);
        boolean same = now.size() == apps.size();
        for (int i = 0; same && i < now.size(); i++) same = java.util.Objects.equals(now.get(i).pkg, apps.get(i).pkg);
        if (!same) { recreate(); return; }
        hideSystemBars();
        // xin quyền đọc nội dung một lần; bị từ chối thì thôi, không hỏi lại mỗi lần quay về
        if (checkSelfPermission("android.permission.READ_TV_LISTINGS") != PackageManager.PERMISSION_GRANTED
                && !prefs().getBoolean("asked_tv", false)) {
            prefs().edit().putBoolean("asked_tv", true).apply();
            requestPermissions(new String[]{"android.permission.READ_TV_LISTINGS"}, 1);
        }
        resumedNow = true;
        // quay về từ nội dung mở ở hàng thumb (Home / Back trong app kia): giữ nguyên ô đang chọn để chuyển nội dung khác
        // (trận khác, video khác) cho nhanh; hàng chỉ đổ lại khi về dock (shelfState)
        refreshShelf();
        if (allApps != null) {
            // quay về từ app khác (trình phát) với bảng đang mở: ô đang chọn về đúng cỡ chọn + quầng sáng
            View f = allApps.findFocus();
            if (f != null && f.getOnFocusChangeListener() != null) f.getOnFocusChangeListener().onFocusChange(f, true);
        }
        int ti = inShelf ? thumbs.indexOf(getCurrentFocus()) : -1;
        if (allApps == null && ti >= 0) {
            scheduleThumbPreview(ti);   // ô kênh truyền hình: xem trước lại như lúc rời đi
        } else if (allApps == null) {
            if (inShelf) shelfState(false);
            FrameLayout c = cards.get(focused);
            if (c.hasFocus()) { if (glowCur >= 0) startBreathing(glowViews[glowCur]); }
            else c.requestFocus();
        }
        ui.removeCallbacks(clockTick);
        ui.post(clockTick);
        // giá chỉ cập nhật khi màn hình chính đang hiện
        applyWidgets();
        ui.removeCallbacks(eplCycle);
        if (eplView.mode() != EplView.COMPACT)
            ui.postDelayed(eplCycle, eplView.mode() == EplView.TABLE ? EPL_TABLE_MS : EPL_FIXTURES_MS);
        ui.removeCallbacks(bgRotate); ui.postDelayed(bgRotate, bgRotateMs());
        ui.removeCallbacks(ytTick); ui.post(ytTick);   // quay về màn hình chính: đủ 10 phút từ lượt trước thì xem lại ngay
        redrawIfStale();   // ô trận đã hết giờ trong lúc xem app khác: bỏ ngay, không đợi lượt tải
        refreshWeather();  // số thời tiết sẵn trước khi mở bảng lịch (chỉ khi đã cũ)
        ui.removeCallbacks(remindLater); ui.postDelayed(remindLater, 1500);
        armSaver();   // đứng yên ở màn hình chính 5 phút thì hiện màn hình chờ
        // lịch phát sóng sẵn trong bộ nhớ trước khi mở bảng kênh; cũ thì tải lại ở nền
        for (Apps.App a : apps) if (a.tv) { bg.execute(() -> { Epg.load(this); Epg.refreshIfStale(this); }); break; }
        try { startForegroundService(new Intent(this, KeepAudioService.class)); } catch (Exception ignored) {}   // giữ soundbar thức
        ui.removeCallbacks(showHomePrompt);
        if (needsHomePrompt()) ui.postDelayed(showHomePrompt, 1200);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumedNow = false;
        // không chạy hiệu ứng lặp và đồng hồ khi đang xem app khác
        if (breathe != null) { breathe.cancel(); breathe = null; }
        ui.removeCallbacks(clockTick);
        ui.removeCallbacks(pendingBg);
        ui.removeCallbacks(btcTick);   // rời launcher: ngừng gọi mạng lấy giá
        ui.removeCallbacks(goldTick);
        ui.removeCallbacks(ytTick);
        ui.removeCallbacks(feedBoundary);
        ui.removeCallbacks(eplTick);
        ui.removeCallbacks(eplCycle);
        ui.removeCallbacks(bgRotate);
        ui.removeCallbacks(showSaverLater);
        hideSaver(false);
        if (chCard != null) collapsePreview(false);   // rời launcher (mở trình phát, nút Home...): tắt xem trước ngay, nhả bộ giải mã
        stopThumbPreview(false);
        if (chCard == null) TvPreview.releaseAll();
        if (settings != null) settings.close(false);   // rời màn hình chính: đóng bảng, tắt máy chủ nhận ảnh
        closeCal(false);
        ui.removeCallbacks(remindLater);
        hideRemind(false);
        ui.removeCallbacks(showHomePrompt);
        if (homePrompt != null) { root.removeView(homePrompt); homePrompt = null;
            tray.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            shelfRow.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            gear.setFocusable(true); clock.setFocusable(true); }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) { refreshShelf(); }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // nút Home khi đang ở launcher: đóng mọi lớp phủ, về dock
        // lệnh chuyển từ một bản launcher thừa (xem singleInstance): dùng đúng lệnh gốc
        String act = intent.getStringExtra(FWD_ACTION) != null ? intent.getStringExtra(FWD_ACTION) : intent.getAction();
        // xem trước màn hình chờ từ máy tính (am start -a vn.glass.home.SAVER -n vn.glass.home/.MainActivity)
        if ("vn.glass.home.SAVER".equals(act)) { ui.post(this::showSaver); return; }
        // xem thử lịch thi đấu khi có trận đang đá / đã xong (am start -a vn.glass.home.EPL_DEMO ...), 1 phút rồi về thật
        if ("vn.glass.home.EPL_DEMO".equals(act)) { eplDemo(); return; }
        hideSaver(false);   // nút Home, APPS...: luôn đánh thức khỏi màn hình chờ
        armSaver();
        // nút APPS trên điều khiển (qua app phụ "Tất cả ứng dụng" hoặc Button Mapper): sang ảnh nền kế tiếp
        // lệnh từ máy tính: nhảy tới đúng một ảnh theo tên (am start -a vn.glass.home.NEXT_BG --es file <tên>)
        if ("vn.glass.home.NEXT_BG".equals(act) && intent.getStringExtra("file") != null) { showWallpaper(intent.getStringExtra("file")); return; }
        if (isAppsButton(act) || "vn.glass.home.NEXT_BG".equals(act)) { nextBackground(); return; }
        closeAllApps();
        closeCal(true);
        if (settings != null) settings.close(true);
        // bấm Home khi đang ở chính launcher: về dock. Quay về từ app khác (launcher chưa resume) thì giữ ô thumb đang chọn
        if (inShelf && resumedNow) exitShelf();
    }

    private boolean resumedNow;

    // ---------- giao diện ----------

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setClipChildren(false);

        bgBack = fullImage(); bgFront = fullImage();
        root.addView(bgBack); root.addView(bgFront);
        tint = new View(this);
        tint.setBackgroundColor(tintColor);
        root.addView(tint, new FrameLayout.LayoutParams(-1, -1));

        // làm tối dưới đáy và bên trái để dock, chữ luôn đọc được, phần trên giữ trọn ảnh
        scrim = new View(this);
        // vẽ sẵn có rải nhiễu: lớp tối dần vẽ 8-bit trơn sẽ lộ từng bậc trên ảnh tối
        scrim.setBackground(new android.graphics.drawable.BitmapDrawable(getResources(),
                Dither.fadeUp(getResources().getDisplayMetrics().widthPixels, px(620), new int[]{0xE6, 0x99, 0x00})));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, px(620), Gravity.BOTTOM));
        // tối dần ở hai mép trái phải: ảnh nền gom về giữa, mép màn hình không chói
        // hai dải mép trái/phải (không phủ cả màn hình: phần giữa trong suốt mà vẫn tốn công vẽ mỗi khung)
        int dsw = getResources().getDisplayMetrics().widthPixels, dsh = getResources().getDisplayMetrics().heightPixels;
        Bitmap sideBmp = Dither.fadeSides(dsw, dsh, 0x99, 0.22f);
        int band = Math.round(dsw * 0.22f) + 2;
        for (int side = 0; side < 2; side++) {
            View v = new View(this);
            Bitmap part = Bitmap.createBitmap(sideBmp, side == 0 ? 0 : dsw - band, 0, band, dsh);
            v.setBackground(new android.graphics.drawable.BitmapDrawable(getResources(), part));
            root.addView(v, new FrameLayout.LayoutParams(band, -1, side == 0 ? Gravity.START : Gravity.END));
        }
        sideBmp.recycle();

        // lớp tối rất nhẹ ở mép trên (đậm nhất sát mép, tan hết trước giữa thẻ): giờ chữ trần không bị lẫn vào ảnh nền
        // sáng mà vẫn không thành dải tối rõ. Vẽ sẵn có nhiễu chống sọc (Dither), lật ngược lớp "tối dần từ dưới lên"
        View topScrim = new View(this);
        topScrim.setBackground(new android.graphics.drawable.BitmapDrawable(getResources(),
                Dither.fadeUp(dsw, px(240), new int[]{0x52, 0x24, 0x08, 0x00})));
        topScrim.setScaleY(-1f);
        root.addView(topScrim, new FrameLayout.LayoutParams(-1, px(240), Gravity.TOP));

        // đồng hồ + nút Cài đặt thành một cụm chữ trần ở giữa phía trên (hai góc trên nhường cho thẻ lịch, thẻ giá):
        // giờ chữ tròn Quicksand như màn hình khoá, bánh răng nét kiểu Apple đứng ngay sau giờ
        clock = text(500, CLOCK_SIZE, 0xB3FAF6EE);   // kem ấm ~70%: dịu như chữ màn hình khoá, không trắng gắt
        clock.setTypeface(roundFont(500));
        clock.setLetterSpacing(0f);
        clock.setGravity(Gravity.CENTER);
        clock.setShadowLayer(px(8), 0, px(1), 0x2E000000);   // chữ trần trên ảnh: bóng rất nhạt, nhoè rộng, chỉ đủ tách khỏi nền sáng
        // bề rộng cố định theo chuỗi rộng nhất: chữ số Quicksand không đều nhau, khung tự co giãn thì giờ xê dịch theo phút
        int clockW = (int) Math.ceil(clock.getPaint().measureText("00:00") * 1.06f);
        // mặc định chỉ có giờ, đứng chính giữa; nút Cài đặt ẩn ngay sau giờ. Chọn nút (từ thẻ lịch bấm phải) thì giờ
        // dạt trái nửa bề rộng nút để cả cụm giờ + nút vẫn ở chính giữa, nút hiện dần ra
        clockShift = (px(CLOCK_GAP) + px(GEAR)) / 2f;
        int clockLeft = (px(1920) - clockW) / 2;
        // đồng hồ là bản gọn của bảng lịch: chọn được (từ thẻ lịch thi đấu bấm phải), lúc chọn có viên sáng mờ phía sau
        // như ô đang chọn, nút Cài đặt hiện mờ bên cạnh; bấm OK mở bảng lịch
        clockPill = new View(this);
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(px(GEAR / 2f));
        pill.setColor(0xF2FFFFFF);   // chọn đồng hồ: viên trắng đặc, giờ chữ tối (cùng kiểu nút đang chọn)
        clockPill.setBackground(pill);
        clockPill.setAlpha(0f);
        FrameLayout.LayoutParams lpl = new FrameLayout.LayoutParams(clockW + px(PILL_PAD * 2), px(GEAR), Gravity.TOP | Gravity.START);
        lpl.setMargins(clockLeft - px(PILL_PAD), px(TOP_Y), 0, 0);
        root.addView(clockPill, lpl);
        FrameLayout.LayoutParams lc = new FrameLayout.LayoutParams(clockW, px(GEAR), Gravity.TOP | Gravity.START);
        lc.setMargins(clockLeft, px(TOP_Y), 0, 0);
        root.addView(clock, lc);
        clock.setFocusable(true);
        clock.setFocusableInTouchMode(true);
        clock.setDefaultFocusHighlightEnabled(false);
        clock.setOnFocusChangeListener((v, has) -> { ui.removeCallbacks(syncTopLater); ui.post(syncTopLater); });
        clock.setOnClickListener(v -> openCal());
        buildGear(Math.round(clockLeft - clockShift) + clockW + px(PILL_PAD + CLOCK_GAP));

        buildTicker();       // cột giá BTC, ETH, vàng dưới đồng hồ, cùng chất kính với dock
        buildEpl();          // thẻ lịch Ngoại hạng Anh bên trái, kính đậm
        buildShelf();
        buildFrost();        // kính mờ: ảnh nền nhoè, cắt theo khay
        buildTray();
        buildGlowLayer();    // quầng sáng nằm trên dock (đã khoét hình ô): toả đều 4 phía, không bị ô bên cạnh che
        setLift(px(DOCK_DROP), false);   // mặc định đứng ở dock: cả cụm hạ thấp
    }

    // ---------- nút Cài đặt: bánh răng nét kiểu Apple, đứng ngay sau đồng hồ ở giữa phía trên ----------

    private static final int GEAR = 60;
    private static final int TOP_Y = 36;          // mép trên của hàng trên cùng (cụm giờ, thẻ lịch, thẻ giá)
    private static final int CLOCK_SIZE = 42, CLOCK_GAP = 12, PILL_PAD = 22;
    private FrameLayout gear;
    private View clockPill;
    private GradientDrawable gearDot;
    private ImageView gearIcon;
    private float clockShift;   // giờ dạt trái chừng này khi nút Cài đặt hiện ra

    /** left: toạ độ điểm ảnh thật (đã nhân tỉ lệ màn hình), chỗ của nút khi đã hiện (giờ đã dạt trái). */
    private void buildGear(int left) {
        gear = new FrameLayout(this);
        gear.setFocusable(true); clock.setFocusable(true);
        gear.setFocusableInTouchMode(true);
        // nút ẩn hẳn khi không được chọn (vẫn nhận được lựa chọn); chọn thì hiện thành nút tròn trắng đặc, biểu tượng
        // tối, như nút Apple TV
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(0xF2FFFFFF);
        gear.setBackground(dot);
        gear.setDefaultFocusHighlightEnabled(false);
        gearDot = dot;
        ImageView ic = new ImageView(this);
        ic.setImageDrawable(new GearIcon());   // vẽ vector: mép sắc ở mọi cỡ, kể cả lúc nút phóng to
        ic.setColorFilter(0xFF1C1C1E);
        gearIcon = ic;
        FrameLayout.LayoutParams li = new FrameLayout.LayoutParams(px(GEAR * 0.56f), px(GEAR * 0.56f), Gravity.CENTER);
        gear.addView(ic, li);
        gear.setAlpha(0f);
        gear.setScaleX(0.8f); gear.setScaleY(0.8f);
        // trạng thái cụm giờ + nút tính sau khi lựa chọn đã chuyển xong (đồng hồ <-> nút là hai sự kiện liền nhau:
        // xử lý từng sự kiện thì giờ giật về giữa rồi lại dạt ra)
        gear.setOnFocusChangeListener((v, has) -> { ui.removeCallbacks(syncTopLater); ui.post(syncTopLater); });
        gear.setOnClickListener(v -> openSettings());
        gear.setOnLongClickListener(v -> { openSystemSettings(); return true; });
        FrameLayout.LayoutParams lg = new FrameLayout.LayoutParams(px(GEAR), px(GEAR), Gravity.TOP | Gravity.START);
        lg.setMargins(left, px(TOP_Y), 0, 0);   // cùng hàng, cùng chiều cao với đồng hồ
        root.addView(gear, lg);
    }

    private final Runnable syncTopLater = this::syncTop;
    private int topState = -1;   // 0: không chọn gì, 1: chọn đồng hồ (hoặc bảng lịch đang mở), 2: chọn nút Cài đặt
    private ValueAnimator gearTone;

    /**
     * Cụm giờ + Cài đặt theo lựa chọn hiện tại. Không chọn: chỉ có giờ ở chính giữa. Chọn đồng hồ: giờ dạt trái, nằm
     * trong viên trắng đặc, chữ tối; nút Cài đặt bên cạnh chỉ là biểu tượng nhỏ mờ, không nền. Chọn nút: viên sau giờ
     * tắt, giờ về chữ sáng, nút nở ra thành nút trắng đặc, biểu tượng tối. Thứ đang chọn luôn là thứ duy nhất trắng đặc.
     */
    private void syncTop() {
        int st = clock.hasFocus() || cal != null ? 1 : gear.hasFocus() ? 2 : 0;
        if (st == topState) return;
        boolean wasOpen = topState > 0;
        topState = st;
        boolean open = st > 0;
        if (open != wasOpen) dimGlow(open || inShelf);
        if ((st == 1) != wasClock) toneClock(st == 1);
        wasClock = st == 1;   // rời xuống hàng thumb: quầng ô app vẫn tắt như lúc vào hàng từ dock
        float tx = open ? -clockShift : 0f;
        // không cancel(): chỉ thay đích dời ngang, nhịp lún của pressClock (độ co) chạy tiếp
        clock.animate().translationX(tx).setInterpolator(EASE).setDuration(560).start();
        clockPill.animate().translationX(tx).alpha(st == 1 ? 1f : 0f).setInterpolator(EASE).setDuration(st == 1 ? 520 : 420).start();
        float sc = st == 2 ? 1.1f : 0.8f;
        gear.animate().cancel();
        gear.animate().alpha(open ? 1f : 0f).scaleX(sc).scaleY(sc)
                .setStartDelay(open && !wasOpen ? 90 : 0).setInterpolator(EASE).setDuration(open ? 520 : 380).start();
        // nền nút: trắng đặc lúc chọn, kính mờ lúc chỉ đứng cạnh đồng hồ; biểu tượng đổi màu theo
        int dotTo = st == 2 ? 0xF2FFFFFF : 0x00FFFFFF, icTo = st == 2 ? 0xFF1C1C1E : 0x8CFFFFFF;
        if (!open) return;   // đang tắt dần: giữ màu cũ tới hết
        if (gearTone != null) gearTone.cancel();
        int dotFrom = gearDot.getColor() != null ? gearDot.getColor().getDefaultColor() : dotTo;
        int icFrom = gearIconColor;
        gearTone = ValueAnimator.ofFloat(0f, 1f);
        gearTone.setDuration(wasOpen ? 420 : 0);
        gearTone.setInterpolator(EASE);
        android.animation.ArgbEvaluator ev = new android.animation.ArgbEvaluator();
        gearTone.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            gearDot.setColor((int) ev.evaluate(f, dotFrom, dotTo));
            gearIconColor = (int) ev.evaluate(f, icFrom, icTo);
            gearIcon.setColorFilter(gearIconColor);
        });
        gearTone.start();
    }

    private int gearIconColor = 0xFF1C1C1E;
    private boolean wasClock;
    private int clockRest = 0xB3FAF6EE, clockShadowA = 0x2E;   // màu giờ theo nền (tuneClock), dùng lúc không chọn

    /** Giờ chữ tối trong viên trắng lúc chọn, về màu theo nền lúc thôi chọn. */
    private void toneClock(boolean selected) {
        int to = selected ? 0xFF1C1C1E : clockRest;
        if (clockTone != null) clockTone.cancel();
        clockTone = ValueAnimator.ofArgb(clock.getCurrentTextColor(), to);
        clockTone.setDuration(420);
        clockTone.setInterpolator(EASE);
        clockTone.addUpdateListener(a -> clock.setTextColor((int) a.getAnimatedValue()));
        clockTone.start();
        if (selected) clock.setShadowLayer(0, 0, 0, 0);
        else clock.setShadowLayer(px(8), 0, px(1), clockShadowA << 24);
    }

    // ---------- bảng lịch: mở từ đồng hồ ----------

    private static final int CAL_TOP = TOP_Y + GEAR + 16;
    private FrameLayout cal;
    private CalView calView;
    private FrostView calFrost;
    private final java.util.concurrent.atomic.AtomicBoolean wxBusy = new java.util.concurrent.atomic.AtomicBoolean(),
            daysBusy = new java.util.concurrent.atomic.AtomicBoolean();

    private void openCal() {
        if (cal != null || settings != null || allApps != null || homePrompt != null) return;
        hideRemind(true);
        int sw = getResources().getDisplayMetrics().widthPixels, sh = getResources().getDisplayMetrics().heightPixels;
        calView = new CalView(this, px(1000) / 1000f, this::fontFor, Weather.cached(this));
        int w = px(CalView.W), h = px(calView.designHeight());
        int left = (px(1920) - w) / 2, top = px(CAL_TOP);
        cal = new FrameLayout(this);
        cal.setFocusable(false);
        calFrost = new FrostView(this, px(26), sw, sh, left, top, Math.max(1, px(1))).deep();
        if (lastBlur != null) calFrost.setImage(lastBlur);
        cal.addView(calFrost, new FrameLayout.LayoutParams(-1, -1));
        cal.addView(calView, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, Gravity.TOP | Gravity.START);
        lp.leftMargin = left; lp.topMargin = top;
        root.addView(cal, lp);
        // thả xuống từ đồng hồ: hiện dần, nở nhẹ từ mép trên
        cal.setPivotX(w / 2f); cal.setPivotY(0f);
        cal.setAlpha(0f); cal.setScaleX(0.96f); cal.setScaleY(0.92f); cal.setTranslationY(-px(14));
        cal.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setInterpolator(EASE).setDuration(560).withLayer().start();
        syncRowWithEpl();   // hàng banner nhường chỗ cho bảng
        syncTop();
        pressClock();
        refreshWeather();
    }

    /** Bấm OK ở đồng hồ: giờ và viên sáng lún nhẹ rồi bật lại, như ô app lúc bấm. */
    private void pressClock() {
        for (View v : new View[]{clock, clockPill}) {
            v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(150).setInterpolator(EASE)
                    .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(EASE).start()).start();
        }
    }

    /** Đóng bảng lịch; lựa chọn ở lại đồng hồ. */
    private void closeCal(boolean animate) {
        FrameLayout c = cal;
        if (c == null) return;
        cal = null; calView = null; calFrost = null;
        c.animate().cancel();
        if (animate) {
            c.animate().alpha(0f).scaleX(0.97f).scaleY(0.94f).translationY(-px(10)).setInterpolator(EASE).setDuration(380)
                    .withLayer().withEndAction(() -> root.removeView(c)).start();
        } else {
            root.removeView(c);
        }
        syncRowWithEpl();
        syncTop();
        if (animate) pressClock();
    }

    // ---------- thẻ nhắc ngày đáng nhớ: thả xuống dưới giờ, mỗi ngày nhắc hiện một lần ----------

    private FrameLayout remindCard;
    private int remindCheckedDay = -1;

    // tivi vừa bật (box khởi động hoặc màn hình vừa sáng lại sau khi tắt): lần đầu tới màn hình chính sau đó thì nhắc
    private boolean wakePending;
    private android.content.BroadcastReceiver screenRx;

    /** Theo dõi tắt / bật màn hình (tắt tivi bằng điều khiển là màn hình tắt, box vẫn chạy). */
    private void watchScreen() {
        screenRx = new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context c, Intent i) {
                if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) { wakePending = true; hideRemind(false); }
                else if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) { ui.removeCallbacks(remindLater); ui.postDelayed(remindLater, 1500); }
            }
        };
        android.content.IntentFilter f = new android.content.IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenRx, f);
    }

    /**
     * Thẻ nhắc khi màn hình chính đang trống, không che gì: đúng ngày nhắc thì mỗi lần bật tivi nhắc một lần; lần
     * nhắc bị lỡ (box tắt hôm đó...) thì nhắc bù một lần. Quay về từ app khác không nhắc lại.
     */
    private void maybeRemind() {
        if (!resumedNow || saverOn || remindCard != null || cal != null || settings != null || allApps != null
                || homePrompt != null) return;
        boolean wake = wakePending;
        wakePending = false;
        java.util.Set<String> shown = new java.util.HashSet<>(prefs().getStringSet("nhac_da_hien_v3", java.util.Collections.emptySet()));
        Days.Due u = Days.reminder(this, shown, wake);
        if (u == null) return;
        if (shown.size() > 40) shown.clear();   // khoá cũ (sự kiện đã qua) không cần giữ mãi
        shown.add(u.key);
        prefs().edit().putStringSet("nhac_da_hien_v3", shown).apply();
        showRemind(u);
    }

    private static final String[] THU_NGAN = {"Chủ Nhật", "Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy"};

    private void showRemind(Days.Due u) {
        int sw = getResources().getDisplayMetrics().widthPixels, sh = getResources().getDisplayMetrics().heightPixels;
        // thẻ dài theo lời nhắc (hiện đủ, không cắt "..."): tối thiểu 560, tối đa 1240 (giữa thẻ bóng đá và thẻ giá);
        // dài hơn nữa thì xuống hai dòng
        TextView t1 = text(500, 22, 0xEBFFFFFF);
        t1.setText(u.text);
        String sub = u.when();
        TextView t2 = text(400, 18, 0x99FFFFFF);
        t2.setText(sub);
        float need = Math.max(t1.getPaint().measureText(u.text), t2.getPaint().measureText(sub)) + px(88 + 32);
        boolean two = need > px(1240);
        int w = Math.max(px(560), Math.min(px(1240), (int) Math.ceil(need)));
        int h = px(two ? 126 : 96), left = (px(1920) - w) / 2, top = px(CAL_TOP);
        FrameLayout box = new FrameLayout(this);
        box.setFocusable(false);
        FrostView f = new FrostView(this, px(24), sw, sh, left, top, Math.max(1, px(1))).deep();
        if (lastBlur != null) f.setImage(lastBlur);
        box.addView(f, new FrameLayout.LayoutParams(-1, -1));
        // tờ lịch nhỏ: dải cam ghi tháng, số ngày to ở giữa
        String month = "TH" + u.mm;
        String day = String.valueOf(u.dd);
        View page = new View(this) {
            final Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
            final android.graphics.RectF r = new android.graphics.RectF();
            final android.graphics.Path clip = new android.graphics.Path();
            @Override protected void onDraw(Canvas c) {
                float W = getWidth(), H = getHeight(), rad = px(12);
                r.set(0, 0, W, H);
                pt.setStyle(Paint.Style.FILL);
                pt.setColor(0xF0FAF6EE);
                c.drawRoundRect(r, rad, rad, pt);
                clip.reset();
                clip.addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW);
                c.save();
                c.clipPath(clip);
                pt.setColor(0xFFE8964A);
                c.drawRect(0, 0, W, px(16), pt);
                c.restore();
                pt.setTextAlign(Paint.Align.CENTER);
                pt.setTypeface(fontFor(700, 10));
                pt.setTextSize(px(10));
                pt.setColor(0xF2FFFFFF);
                c.drawText(month, W / 2, px(12), pt);
                pt.setTypeface(fontFor(600, 26));
                pt.setTextSize(px(26));
                pt.setColor(0xFF1C1C20);
                c.drawText(day, W / 2, px(45), pt);
            }
        };
        FrameLayout.LayoutParams lpg = new FrameLayout.LayoutParams(px(50), px(54), Gravity.START | Gravity.CENTER_VERTICAL);
        lpg.leftMargin = px(21);
        box.addView(page, lpg);
        t1.setMaxLines(2);
        t1.setLineSpacing(px(4), 1f);
        FrameLayout.LayoutParams l1 = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP | Gravity.START);
        l1.setMargins(px(88), px(22), px(24), 0);
        box.addView(t1, l1);
        t2.setSingleLine(true);
        FrameLayout.LayoutParams l2 = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP | Gravity.START);
        l2.setMargins(px(88), px(two ? 86 : 56), px(24), 0);
        box.addView(t2, l2);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, Gravity.TOP | Gravity.START);
        lp.leftMargin = left; lp.topMargin = top;
        root.addView(box, lp);
        remindCard = box;
        // thả xuống từ dưới giờ, chậm và êm; 30 giây sau (hoặc bấm phím bất kỳ) thu lên tắt dần
        box.setAlpha(0f); box.setTranslationY(-px(14));
        box.animate().alpha(1f).translationY(0f).setInterpolator(EASE).setDuration(620).withLayer().start();
        ui.removeCallbacks(hideRemindLater);
        ui.postDelayed(hideRemindLater, 30_000);
    }

    private final Runnable remindLater = this::maybeRemind;
    private final Runnable hideRemindLater = () -> hideRemind(true);

    private void hideRemind(boolean animate) {
        FrameLayout b = remindCard;
        if (b == null) return;
        remindCard = null;
        ui.removeCallbacks(hideRemindLater);
        b.animate().cancel();
        if (animate) b.animate().alpha(0f).translationY(-px(10)).setInterpolator(EASE).setDuration(460).withLayer()
                .withEndAction(() -> root.removeView(b)).start();
        else root.removeView(b);
    }

    /** Thời tiết + AQI cũ quá thì đọc lại ở nền; bảng lịch đang mở thì số mới hoà dần vào. */
    private void refreshWeather() {
        // ngày đáng nhớ từ Google Sheet (3 tiếng một lần): bảng đang mở thì chấm và dòng cuối theo bản mới
        if (Days.stale(this) && daysBusy.compareAndSet(false, true)) {
            net.execute(() -> {
                boolean ch = Days.refresh(this);
                daysBusy.set(false);
                ui.post(() -> { if (ch && calView != null) calView.daysChanged(); maybeRemind(); });
            });
        }
        if (!Weather.stale(this) || !wxBusy.compareAndSet(false, true)) return;
        net.execute(() -> {
            Weather.Now n = Weather.refresh(this);
            wxBusy.set(false);
            ui.post(() -> { if (calView != null) calView.setWeather(n); });
        });
    }

    private void buildTray() {
        int n = apps.size();
        int trayW = trayW(n);
        int trayH = CARD_H + TRAY_PAD_Y * 2;

        tray = new LinearLayout(this);
        tray.setOrientation(LinearLayout.HORIZONTAL);
        tray.setGravity(Gravity.CENTER_VERTICAL);
        tray.setPadding(px(TRAY_PAD_X), 0, px(TRAY_PAD_X), 0);
        tray.setClipChildren(false); tray.setClipToPadding(false);
        // lớp kính phía trên lớp nhoè: chỉ một viền rất mảnh và nhạt
        // kính, viền và bo góc do FrostView vẽ (khử răng cưa); khay chỉ còn xếp các ô
        // neo mép trái (không căn giữa): thêm app thì khay chỉ dài ra về bên phải
        FrameLayout.LayoutParams lt = new FrameLayout.LayoutParams(px(trayW), px(trayH), Gravity.TOP | Gravity.START);
        lt.topMargin = px(TRAY_TOP);
        lt.leftMargin = px(trayLeft());
        root.addView(tray, lt);

        for (int i = 0; i < n; i++) {
            Apps.App a = apps.get(i);
            FrameLayout cell = card(a, i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(CARD_W), px(CARD_H));
            if (i > 0) lp.leftMargin = px(CARD_GAP);
            tray.addView(cell, lp);
            cards.add(cell);
        }
    }

    private FrameLayout card(Apps.App a, int index) {
        FrameLayout cell = new FrameLayout(this);
        cell.setClipChildren(false);
        cell.setFocusable(true);
        cell.setFocusableInTouchMode(true);

        // thu về đúng cỡ ô lúc được chọn để khi phóng to, ảnh khớp 1:1 với điểm ảnh màn hình
        Bitmap own = a.cardFile == null ? null : BitmapFactory.decodeFile(a.cardFile);
        Bitmap art = own != null ? scaleHQ(own, px(SEL_W), px(SEL_H))
                : a.key != null ? scaleHQ(asset("cards/" + a.key + ".png"), px(SEL_W), px(SEL_H))
                : appCard(a.pkg, px(SEL_W), px(SEL_H));
        art = creamIfWhite(art);
        art = rimIfDark(art);
        int glowColor = a.isAllApps() ? Color.WHITE : Glow.dominant(art);
        // quầng theo đúng ô lúc đang chọn (300x188), kể cả độ bo góc
        glowBitmaps.add(Glow.make(px(SEL_W), px(SEL_H), px(SEL_R), px(GLOW_SPREAD), glowColor));

        // góc bo đã vẽ mịn sẵn trong ảnh ô. Không đổ bóng: ô nổi lên đã có quầng sáng, còn bóng thì
        // trên kính dock sáng lộ thành vệt xám đen ở hai góc dưới. translationZ vẫn giữ để ô chọn nằm trên ô bên.
        ImageView img = new ImageView(this);
        img.setImageBitmap(art);
        img.setScaleType(ImageView.ScaleType.FIT_XY);
        img.setOutlineProvider(null);
        cell.addView(img, new FrameLayout.LayoutParams(-1, -1));

        // ánh sáng nhẹ trên mặt ô khi chọn (GradientDrawable bo góc tự khử răng cưa)
        View sheen = new View(this);
        GradientDrawable sh = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0x40FFFFFF, 0x0DFFFFFF, 0x00FFFFFF});
        sh.setCornerRadius(px(CARD_R));
        sheen.setBackground(sh);
        sheen.setOutlineProvider(null);
        sheen.setAlpha(0f);
        cell.addView(sheen, new FrameLayout.LayoutParams(-1, -1));

        cell.setOnFocusChangeListener((v, has) -> {
            lift(img, sheen, has);
            if (has) { shelfState(false); onAppFocused(index); shiftDock(index); moveGlow(index); }
        });
        // nhấn OK: lún xuống ngay (chưa cần thả tay) để biết lệnh đã nhận
        cell.setOnKeyListener((v, kc, ev) -> {
            if (isOk(kc) && ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) press(img, sheen);
            return false;
        });
        // thả tay: bật lại + quầng loé, mở app ngay, cửa sổ app phóng ra từ chính ô này.
        // âm thanh bấm/di chuyển: Android tự phát âm hệ thống (như menu Google TV), không đóng gói file nào
        cell.setOnClickListener(v -> { release(img, sheen); flashGlow(); launch(a, cell); });
        return cell;
    }

    /**
     * Ô app nền tối (Netflix, iQIYI, Truyền hình, Apps) hoà vào kính dock tối, chỉ còn logo trôi: vẽ sẵn vào ảnh ô một
     * mép kính bắt sáng kiểu Apple TV, sáng ở góc trên trái rồi tắt dần (không viền đều quanh ô), theo đúng góc bo
     * của ảnh. Vẽ vào ảnh nên phóng/thu cùng ô, không thêm lớp vẽ nào. Ô nền sáng giữ nguyên.
     */
    private Bitmap rimIfDark(Bitmap art) {
        if (art == null) return null;
        Bitmap s = Bitmap.createScaledBitmap(art, 24, 15, true);
        double sum = 0; int n = 0;
        for (int y = 0; y < s.getHeight(); y++)
            for (int x = 0; x < s.getWidth(); x++) {
                int c = s.getPixel(x, y);
                if ((c >>> 24) < 128) continue;   // góc bo trong suốt
                sum += FrostView.glare(c); n++;
            }
        if (s != art) s.recycle();
        if (n == 0 || sum / n / 255.0 > 0.30) return art;
        Bitmap out = art.isMutable() ? art : art.copy(Bitmap.Config.ARGB_8888, true);
        android.graphics.Canvas c = new android.graphics.Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        float w = Math.max(1.5f, out.getWidth() / 180f), h = w / 2f;
        p.setStrokeWidth(w);
        p.setShader(new android.graphics.LinearGradient(0, 0, out.getWidth() * 0.7f, out.getHeight(),
                new int[]{0x2EFFFFFF, 0x0AFFFFFF, 0x03FFFFFF}, new float[]{0f, 0.45f, 1f},
                android.graphics.Shader.TileMode.CLAMP));
        float r = px(SEL_R);
        c.drawRoundRect(h, h, out.getWidth() - h, out.getHeight() - h, r - h, r - h, p);
        return out;
    }

    /**
     * Ô app nền trắng (YouTube, FPT Play, TV360...) trắng gắt trên kính tối: thay nền trắng bằng trắng ngà như mặt sứ,
     * dịu hơn trắng tinh (sáng nhất ~93%), chuyển rất nhẹ từ trên xuống, góc trên trái hơi bắt sáng. Không hoa văn.
     * Logo giữ nguyên màu (điểm ảnh càng trắng càng lấy nền sứ, mép chữ khử răng cưa tự hoà), góc bo giữ đúng kênh
     * trong suốt của ảnh gốc. Ô nền tối không đổi.
     */
    private Bitmap creamIfWhite(Bitmap art) {
        if (art == null) return null;
        int w = art.getWidth(), h = art.getHeight();
        // chỉ ô có viền ngoài gần như trắng tinh
        int white = 0, n = 0;
        for (int i = 0; i < 24; i++) {
            int[] xy = {w * (i + 1) / 26, h / 12, w * (i + 1) / 26, h - 1 - h / 12};
            for (int k = 0; k < 4; k += 2) {
                int c = art.getPixel(xy[k], xy[k + 1]);
                if ((c >>> 24) < 200) continue;
                n++;
                if (Math.min(Math.min((c >> 16) & 0xFF, (c >> 8) & 0xFF), c & 0xFF) > 240) white++;
            }
        }
        if (n == 0 || white < n * 0.85f) return art;
        Bitmap bg = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bg);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setShader(new android.graphics.LinearGradient(0, 0, 0, h, 0xFFEEEAE2, 0xFFDED8CD, android.graphics.Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h, fill);
        fill.setShader(new android.graphics.RadialGradient(w * 0.3f, h * 0.1f, w * 0.62f,
                new int[]{0x26FFFFFF, 0x0AFFFFFF, 0x00FFFFFF}, new float[]{0f, 0.5f, 1f}, android.graphics.Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h, fill);
        int[] src = new int[w * h], back = new int[w * h];
        art.getPixels(src, 0, w, 0, 0, w, h);
        bg.getPixels(back, 0, w, 0, 0, w, h);
        for (int i = 0; i < src.length; i++) {
            int c = src[i], b = back[i];
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, bl = c & 0xFF;
            float k = Math.max(0f, Math.min(1f, (250 - Math.min(Math.min(r, g), bl)) / 70f));   // 0: trắng -> nền kem
            int rr = Math.round(r * k + ((b >> 16) & 0xFF) * (1 - k));
            int gg = Math.round(g * k + ((b >> 8) & 0xFF) * (1 - k));
            int bb = Math.round(bl * k + (b & 0xFF) * (1 - k));
            src[i] = (c & 0xFF000000) | (rr << 16) | (gg << 8) | bb;
        }
        bg.setPixels(src, 0, w, 0, 0, w, h);
        // giữ trong suốt ở góc bo của ảnh gốc
        Paint keep = new Paint();
        keep.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
        new Canvas(bg).drawBitmap(art, 0, 0, keep);
        return bg;
    }

    private static boolean isOk(int kc) {
        return kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER || kc == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    /** Lún xuống như phím thật: thu từ 116% về ~107%, bóng ngắn lại. */
    private void press(View img, View sheen) {
        for (View v : new View[]{img, sheen}) {
            v.animate().cancel();
            v.animate().scaleX(1.07f).scaleY(1.07f).translationZ(px(10)).setInterpolator(EASE).setDuration(150).start();
        }
    }

    /** Bật nảy trở lại cỡ đang chọn. */
    private void release(View img, View sheen) {
        for (View v : new View[]{img, sheen}) {
            v.animate().cancel();
            v.animate().scaleX(SCALE_X).scaleY(SCALE_Y).translationZ(px(30))
                    .setInterpolator(EASE).setDuration(420).start();
        }
    }

    /** Quầng sáng loé lên một nhịp rồi trở lại "thở". */
    private void flashGlow() {
        if (glowCur < 0) return;
        if (breathe != null) { breathe.cancel(); breathe = null; }
        ImageView g = glowViews[glowCur];
        g.animate().cancel();
        // loé sáng bằng độ sáng (không phình to: lỗ khoét phải khít mép ô)
        g.animate().alpha(1f).setDuration(200).setInterpolator(EASE)
                .withEndAction(() -> g.animate().alpha(GLOW_MOVING).setDuration(600).setInterpolator(EASE)
                        .withEndAction(() -> startBreathing(g)).start()).start();
    }

    /** Cửa sổ app phóng ra từ vị trí ô (Android hiện ngay màn hình chờ của app trong lúc app khởi động). */
    private android.os.Bundle zoomFrom(View src) {
        return src == null ? null : android.app.ActivityOptions
                .makeScaleUpAnimation(src, 0, 0, src.getWidth(), src.getHeight()).toBundle();
    }

    // đường cong "chậm dần mềm" dùng chung cho mọi chuyển động để cả bộ đồng nhất
    private static final android.view.animation.Interpolator EASE =
            new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f);

    /** Chọn: ô nổi lên đều từ tâm, nảy rất nhẹ, bóng sâu dần. Bỏ chọn: chìm dần về khay, không nảy. */
    private void lift(View img, View sheen, boolean on) {
        float sx = on ? SCALE_X : 1f, sy = on ? SCALE_Y : 1f;
        img.animate().cancel(); sheen.animate().cancel();
        android.view.animation.Interpolator in = EASE;
        // độ mờ đi cùng nhịp phóng/thu: cancel() ở trên cắt ngang lần mờ/sáng của shelfState, phải đặt lại đích ở đây
        // (ô đang chọn luôn sáng; ô thôi chọn mờ 0,6 nếu đang ở hàng thumb), không thì ô kẹt ở độ mờ dở dang
        img.animate().scaleX(sx).scaleY(sy).translationZ(on ? px(30) : 0).alpha(on || !inShelf ? 1f : 0.6f)
                .setInterpolator(in).setDuration(on ? 500 : 520).start();
        sheen.animate().scaleX(sx).scaleY(sy).translationZ(on ? px(31) : 0).alpha(on ? 1f : 0f)
                .setInterpolator(in).setDuration(on ? 500 : 480).start();
    }

    // ---------- kính mờ: ảnh nền nhoè mạnh, cắt đúng hình khay, bo góc khử răng cưa ----------

    private static final float TRAY_RADIUS = CARD_R + TRAY_PAD_Y * 0.9f;
    private FrostView frost;

    private void buildFrost() {
        int trayW = px(trayW(apps.size())), trayH = px(CARD_H + TRAY_PAD_Y * 2);
        int sw = getResources().getDisplayMetrics().widthPixels, sh = getResources().getDisplayMetrics().heightPixels;
        // cùng chất kính tối với ô giá và thẻ lịch (đen mờ, đậm dần trên nền sáng), không trắng sữa
        frost = new FrostView(this, px(TRAY_RADIUS), sw, sh, px(trayLeft()), px(TRAY_TOP), Math.max(1, px(1))).smoky();
        FrameLayout.LayoutParams lf = new FrameLayout.LayoutParams(trayW, trayH, Gravity.TOP | Gravity.START);
        lf.topMargin = px(TRAY_TOP);
        lf.leftMargin = px(trayLeft());
        root.addView(frost, lf);
    }

    /** Kính mờ đổi theo ảnh nền (làm nhoè ở luồng nền), hoà dần cùng lúc với nền. */
    private void updateFrost(Bitmap bmp, int color) {
        if (bmp == null) {
            Bitmap c = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888);
            c.eraseColor(color);
            lastBlur = c;
            frost.setImage(c);
            for (FrostView f : tickFrost) if (f != null) f.setImage(c);
            if (eplFrost != null) eplFrost.setImage(c);
            if (calFrost != null) calFrost.setImage(c);
            tuneClock(c);
            return;
        }
        bg.execute(() -> {
            Bitmap b = FrostView.blur(bmp);
            ui.post(() -> {
                lastBlur = b;
                frost.setImage(b);
                for (FrostView f : tickFrost) if (f != null) f.setImage(b);
                if (eplFrost != null) eplFrost.setImage(b);
                if (calFrost != null) calFrost.setImage(b);
                tuneClock(b);
            });
        });
    }

    private ValueAnimator clockTone;

    /**
     * Tương phản động cho giờ: đo độ sáng vùng ảnh nền ngay sau cụm giờ (giữa, sát mép trên). Nền tối thì giờ dịu hẳn
     * (~50%, sáng quá trên nền tối là chói), nền sáng thì giờ đậm dần (~84%) và bóng chữ rõ hơn để vẫn đọc được.
     * Đổi màu cùng nhịp hoà ảnh nền.
     */
    private void tuneClock(Bitmap b) {
        if (clock == null || b == null) return;
        int x0 = Math.round(b.getWidth() * 0.38f), x1 = Math.round(b.getWidth() * 0.62f);
        int y1 = Math.max(1, Math.round(b.getHeight() * 0.14f));
        double sum = 0; int n = 0;
        for (int y = 0; y < y1; y++)
            for (int x = x0; x < x1; x++) { sum += FrostView.glare(b.getPixel(x, y)); n++; }
        float lum = n == 0 ? 0f : (float) (sum / n / 255.0);
        float t = Math.max(0f, Math.min(1f, (lum - 0.12f) / 0.5f));   // tối hơn 12%: dịu nhất, sáng hơn 62%: đậm nhất
        int alpha = Math.round(0x80 + (0xD6 - 0x80) * t);
        int shadow = Math.round(0x1F + (0x52 - 0x1F) * t);
        int to = (alpha << 24) | 0xFAF6EE;
        clockRest = to; clockShadowA = shadow;
        if (topState == 1) return;   // đang chọn đồng hồ: chữ tối trong viên trắng, đổi nền thì để lúc thôi chọn
        int from = clock.getCurrentTextColor();
        if (clockTone != null) clockTone.cancel();
        clockTone = ValueAnimator.ofArgb(from, to);
        clockTone.setDuration(700);
        clockTone.setInterpolator(EASE);
        clockTone.addUpdateListener(a -> clock.setTextColor((int) a.getAnimatedValue()));
        clockTone.start();
        clock.setShadowLayer(px(8), 0, px(1), shadow << 24);
    }

    // ---------- ô giá BTC + vàng (chỉ để theo dõi) ----------

    // một thẻ kính chung (cùng kiểu thẻ lịch bên trái): 3 dòng BTC, ETH, vàng cao TICK_H, ngăn bằng vạch mảnh
    private static final int TICK_W = 236, TICK_H = 88, TICK_GAP = 0, TICK_PAD = 20, TICK_TOP = 40, TICK_CARD_PAD = 4;
    private FrameLayout tickCard;
    private final View[] tickLine = new View[3];
    private ValueAnimator tickCardAnim;
    // bản sáng của xanh/đỏ hệ thống, đủ tương phản trên kính tối
    // dịu mắt: xanh/đỏ ngả pastel, không đặc 100%; trắng ~78% (xanh/đỏ/trắng bão hoà nhìn lâu bị chói)
    private static final int GREEN = 0xD97FD99C, RED = 0xD9F59A90, WHITE = 0xC7FFFFFF;
    private static final long BTC_EVERY = 3000, GOLD_EVERY = 10 * 60 * 1000;
    // cột giá từ trên xuống: BTC, ETH (cùng thứ tự Prices.COINS), rồi Vàng
    private static final int GOLD = 2, TICKS = 3;
    private final FrostView[] tickFrost = new FrostView[TICKS];
    private final View[] tickTile = new View[TICKS];
    private final TextView[] tickExtra = new TextView[TICKS], tickPrice = new TextView[TICKS];
    private long btcOkAt, goldOkAt;
    private final double[] lastCoin = {-1, -1};

    // ---------- lịch Ngoại hạng Anh: thẻ kính đậm bên trái, dưới nút Cài đặt ----------

    private static final int EPL_X = 40, EPL_TOP = 40;   // sát mép trên: giờ + Cài đặt đã ra giữa, hai góc trên trống
    private FrameLayout eplBox;
    private FrostView eplFrost;
    private EplView eplView;
    private final java.util.concurrent.atomic.AtomicBoolean eplBusy = new java.util.concurrent.atomic.AtomicBoolean();
    private long eplFailMs;   // lỗi mạng liên tiếp: giãn nhịp gọi (1 -> 2 -> 4... tối đa 15 phút)

    private void buildEpl() {
        int sw = getResources().getDisplayMetrics().widthPixels, sh = getResources().getDisplayMetrics().heightPixels;
        eplBox = new FrameLayout(this);
        eplBox.setFocusable(false);
        eplBox.setVisibility(View.GONE);   // hiện khi có dữ liệu
        eplFrost = new FrostView(this, px(20), sw, sh, px(EPL_X), px(EPL_TOP), Math.max(1, px(1))).deep();
        eplBox.addView(eplFrost, new FrameLayout.LayoutParams(-1, -1));
        Epl.badgePx = px(28);
        eplView = new EplView(this, px(1000) / 1000f, fontFor(600, 17), fontFor(500, 20));
        eplView.setFavorite(Wallpapers.favTeam(this));
        // lượt đầy đủ chỉ chạy một lần mỗi lần box khởi động; về sau (kể cả khi hệ thống dọn launcher để nhường RAM
        // cho app đang xem rồi mở lại) thì vào thẳng thẻ gọn
        long boot = (System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()) / 60_000L;
        if (Math.abs(prefs().getLong("epl_full_boot", -10) - boot) <= 1) eplView.setMode(EplView.COMPACT);
        else prefs().edit().putLong("epl_full_boot", boot).apply();
        eplBox.addView(eplView, new FrameLayout.LayoutParams(-2, -2));
        View ring = new View(this);
        eplRing = ring;
        GradientDrawable rb = new GradientDrawable();
        rb.setCornerRadius(px(20));
        rb.setStroke(Math.max(2, px(3)), 0xD9FFFFFF);
        ring.setBackground(rb);
        ring.setAlpha(0f);
        eplBox.addView(ring, new FrameLayout.LayoutParams(-1, -1));
        // chỉ cho chọn khi đi từ nút bánh răng xuống (xem dispatchKeyEvent): bảng Cài đặt, hộp nhắc không lỡ trỏ vào
        eplBox.setOnFocusChangeListener((v, has) -> {
            if (!has) {
                ring.animate().alpha(0f).setDuration(300).setInterpolator(EASE).start();
                eplView.setSelected(EplView.SEL_NONE);
                eplBox.setFocusable(false);
            }
            // rời thẻ xuống hàng thumb (đã vào hàng, quầng đã tắt): đừng bật lại quầng ô app, ô app đang lùi chứ không được chọn
            dimGlow(has || inShelf);
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        lp.leftMargin = px(EPL_X); lp.topMargin = px(EPL_TOP);
        root.addView(eplBox, lp);
    }

    /**
     * Lấy lịch/tỉ số. Nhịp: có trận đang đá (hoặc vừa tới giờ đá) -> mỗi phút; không thì chờ tới đúng giờ bóng lăn
     * của trận kế tiếp, tối đa 3 tiếng. Chỉ chạy khi đang ở màn hình chính (dừng ở onPause). Lỗi mạng thì giãn nhịp.
     */
    private final Runnable eplTick = new Runnable() {
        @Override public void run() {
            long demoLeft = eplDemoUntil - android.os.SystemClock.uptimeMillis();
            if (demoLeft > 1000) { ui.removeCallbacks(this); ui.postDelayed(this, demoLeft); return; }
            if (!eplBusy.compareAndSet(false, true)) return;
            net.execute(() -> {
                Epl.Data d = Epl.load(MainActivity.this);
                eplBusy.set(false);
                ui.post(() -> {
                    long now = System.currentTimeMillis(), wait;
                    if (d == null || d.matches.isEmpty()) {
                        eplFailMs = Math.min(15 * 60_000L, eplFailMs == 0 ? 60_000L : eplFailMs * 2);
                        wait = eplFailMs;
                    } else {
                        eplFailMs = 0;
                        eplView.setData(d);
                        // khung kính đúng cỡ thẻ lịch (kính tự đo thì chiếm cả màn hình)
                        ViewGroup.LayoutParams lb = eplBox.getLayoutParams();
                        lb.width = px(eplView.designWidth());
                        lb.height = px(eplView.designHeight());
                        eplBox.setLayoutParams(lb);
                        if (eplBox.getVisibility() != View.VISIBLE && Wallpapers.widget(MainActivity.this, "epl")) {
                            eplBox.setAlpha(0f);
                            eplBox.setVisibility(View.VISIBLE);
                            eplBox.animate().alpha(eplShelfAlpha()).setDuration(500).start();
                        }
                        syncRowWithEpl();
                        long nk = d.nextKick(now);
                        wait = d.inPlayWindow(now) ? 60_000L
                                : Math.max(60_000L, Math.min(3 * 3600_000L, nk > 0 ? nk - now + 60_000L : 3 * 3600_000L));
                        if (!eplView.hasTable()) wait = 60_000L;
                    }
                    ui.removeCallbacks(eplTick);
                    ui.postDelayed(eplTick, wait);
                });
            });
        }
    };

    /** Giả lập: 3 trận đầu đã xong, 2 trận kế đang đá (phút 67 và 23), còn lại chưa đá. Sau 1 phút lấy lại dữ liệu thật. */
    private void eplDemo() {
        Epl.Data real = eplView.data();
        if (real == null) return;
        Epl.Data d = new Epl.Data();
        d.round = real.round;
        d.teams = real.teams;
        d.table = real.table;
        int[][] score = {{2, 1}, {0, 0}, {1, 3}, {1, 0}, {0, 2}};
        int[] minute = {0, 0, 0, 67, 23};
        for (int i = 0; i < real.matches.size(); i++) {
            Epl.Match o = real.matches.get(i), m = new Epl.Match();
            m.kick = o.kick; m.home = o.home; m.away = o.away;
            if (i < 5) {
                m.started = true; m.hs = score[i][0]; m.as = score[i][1];
                m.finished = i < 3; m.minutes = minute[i];
            }
            d.matches.add(m);
        }
        eplView.setData(d);
        eplDemoUntil = android.os.SystemClock.uptimeMillis() + 60_000L;
        ui.removeCallbacks(eplTick);
        ui.postDelayed(eplTick, 60_000L);
    }

    private long eplDemoUntil;   // đang xem thử: lượt lấy dữ liệu thật chen ngang (onResume) không được ghi đè

    // Thẻ bóng đá: một lượt đầy đủ (lịch 1 phút -> bảng xếp hạng 30 giây) rồi thu gọn lại và ở yên đó.
    // Muốn xem lại: từ nút bánh răng bấm xuống chọn thẻ, bấm OK là chạy lại đúng một lượt đầy đủ.
    private static final long EPL_FIXTURES_MS = 60_000L, EPL_TABLE_MS = 30_000L;
    private ValueAnimator eplResize;
    private View eplRing;   // viền sáng cả thẻ khi chọn thẻ đầy đủ

    private final Runnable eplCycle = new Runnable() {
        @Override public void run() {
            if (eplBox.getVisibility() != View.VISIBLE || inShelf || settings != null) {
                ui.postDelayed(this, EPL_FIXTURES_MS);   // đang bị che: chưa đi tiếp, hẹn lần sau
                return;
            }
            int m = eplView.mode();
            if (m == EplView.FIXTURES && eplView.hasTable()) {
                swapEpl(EplView.TABLE);
                ui.postDelayed(this, EPL_TABLE_MS);
            } else if (m != EplView.COMPACT) {
                swapEpl(EplView.COMPACT);
            }
        }
    };

    private final Runnable eplCollapse = new Runnable() {
        @Override public void run() {
            if (inShelf || settings != null) { ui.postDelayed(this, EPL_FIXTURES_MS); return; }   // đang che: để sau
            swapEpl(EplView.COMPACT);
        }
    };

    /**
     * Bấm OK trên thẻ. Thẻ gọn: khu lịch đang chọn thì mở lịch đầy đủ (1 phút), khu bảng xếp hạng thì mở bảng
     * đầy đủ (30 giây), xong tự thu gọn lại. Thẻ đang đầy đủ: thu gọn ngay.
     */
    private void stepEpl() {
        ui.removeCallbacks(eplCycle);
        ui.removeCallbacks(eplCollapse);
        if (eplView.mode() == EplView.COMPACT) {
            boolean table = eplView.selected() == EplView.SEL_TABLE;
            swapEpl(table ? EplView.TABLE : EplView.FIXTURES);
            ui.postDelayed(eplCollapse, table ? EPL_TABLE_MS : EPL_FIXTURES_MS);
        } else {
            swapEpl(EplView.COMPACT);
        }
    }

    /** Khu của thẻ gọn ứng với bản đầy đủ vừa thu về (khu đó không còn thì theo thứ tự thường). */
    private int backPart(int fromMode) {
        if (fromMode == EplView.TABLE && eplView.hasTablePart()) return EplView.SEL_TABLE;
        if (fromMode == EplView.FIXTURES && eplView.hasMatchPart()) return EplView.SEL_MATCH;
        return eplPart(false);
    }

    /** Thẻ gọn có khu nào để chọn: từ dưới lên ưu tiên bảng xếp hạng, từ trên xuống ưu tiên lịch. */
    private int eplPart(boolean fromBelow) {
        boolean m = eplView.hasMatchPart(), t = eplView.hasTablePart();
        if (fromBelow) return t ? EplView.SEL_TABLE : m ? EplView.SEL_MATCH : EplView.SEL_NONE;
        return m ? EplView.SEL_MATCH : t ? EplView.SEL_TABLE : EplView.SEL_NONE;
    }

    /** Chọn thẻ bằng điều khiển: thẻ gọn thì chọn đúng một khu, thẻ đầy đủ thì viền sáng cả thẻ. */
    private void focusEpl(boolean fromBelow) {
        eplBox.setFocusable(true);
        eplBox.requestFocus();
        if (eplView.mode() == EplView.COMPACT) eplView.setSelected(eplPart(fromBelow));
        eplRing.animate().alpha(eplView.mode() == EplView.COMPACT ? 0f : 1f).setDuration(300).setInterpolator(EASE).start();
    }

    /** Đổi chế độ thẻ: chữ mờ đi, khung kính co giãn cả ngang lẫn dọc tới cỡ mới, chữ mới hiện dần. */
    private void swapEpl(int to) {
        if (eplView.mode() == to) return;
        // thu về bản gọn: chọn lại đúng khu vừa mở (bảng xếp hạng đầy đủ -> khu bảng, lịch đầy đủ -> khu lịch),
        // không nhảy về khu lịch theo thứ tự mặc định
        int from = eplView.mode();
        eplView.animate().cancel();
        eplView.animate().alpha(0f).setDuration(340).setInterpolator(EASE).withEndAction(() -> {
            ViewGroup.LayoutParams cur = eplBox.getLayoutParams();
            int w0 = cur.width > 0 ? cur.width : eplBox.getWidth(), h0 = cur.height > 0 ? cur.height : eplBox.getHeight();
            eplView.setMode(to);
            syncRowWithEpl();
            if (eplBox.hasFocus()) {
                eplRing.animate().alpha(to == EplView.COMPACT ? 0f : 1f).setDuration(300).start();
                eplView.setSelected(to == EplView.COMPACT ? backPart(from) : EplView.SEL_NONE);
            }
            int w1 = px(eplView.designWidth()), h1 = px(eplView.designHeight());
            if (eplResize != null) eplResize.cancel();
            eplResize = ValueAnimator.ofFloat(0f, 1f);
            eplResize.setDuration(560);
            eplResize.setInterpolator(EASE);
            eplResize.addUpdateListener(a -> {
                float f = (float) a.getAnimatedValue();
                ViewGroup.LayoutParams lb = eplBox.getLayoutParams();
                lb.width = Math.round(w0 + (w1 - w0) * f);
                lb.height = Math.round(h0 + (h1 - h0) * f);
                eplBox.setLayoutParams(lb);
            });
            eplResize.start();
            eplView.animate().alpha(1f).setStartDelay(180).setDuration(480).setInterpolator(EASE).start();
        }).start();
    }

    /**
     * Thẻ bóng đá mở lịch / bảng xếp hạng đầy đủ dài xuống chạm hàng thumb: đứng ở dock thì hàng thumb mờ hẳn nhường chỗ
     * cho thẻ; vào hàng thumb (bấm lên) thì ngược lại, thẻ mờ đi (shelfState). Thẻ gọn không chạm nên hàng hiện như thường.
     */
    private boolean eplCoversRow() {
        if (eplBox == null || eplBox.getVisibility() != View.VISIBLE || settings != null
                || !Wallpapers.widget(this, "epl")) return false;
        if (eplView.mode() == EplView.COMPACT) return false;
        int rowTop = ROW_TOP + DOCK_DROP - THUMB_GROW_H / 2;
        return EPL_TOP + eplView.designHeight() > rowTop - 12;
    }

    private void syncRowWithEpl() {
        // cả hàng chỉ 1 hoặc 0 (nhường thẻ lịch); mờ nhẹ lúc đứng ở dock làm ở từng ô (ô có lớp đệm), không làm cả hàng:
        // đổi alpha cả hàng bắt GPU vẽ cả hàng ra lớp tạm mỗi khung, lúc từ dock bấm lên ô nở bị khựng
        float a = inShelf || (!eplCoversRow() && cal == null) ? 1f : 0f;
        if (shelfRow.getAlpha() != a) shelfRow.animate().alpha(a).setDuration(360).start();
        // thẻ bóng đá: ở hàng thumb chỉ ẩn khi đang mở dạng đầy đủ (dài xuống chạm hàng); dạng gọn không che gì nên giữ nguyên
        // (đang bị tắt hay đang mờ dần để ẩn hẳn thì thôi, kẻo cắt ngang lần ẩn)
        if (eplBox != null && settings == null && eplBox.getVisibility() == View.VISIBLE && Wallpapers.widget(this, "epl")) {
            float ea = eplShelfAlpha();
            if (eplBox.getAlpha() != ea) eplBox.animate().alpha(ea).setDuration(360).withLayer().start();
        }
    }

    private float eplShelfAlpha() { return inShelf && eplView.mode() != EplView.COMPACT ? 0f : 1f; }

    /** Chọn được thẻ bằng điều khiển khi đang hiện trên màn hình chính. */
    private boolean eplSelectable() {
        return eplBox != null && eplBox.getVisibility() == View.VISIBLE && eplBox.getAlpha() > 0.5f
                && !inShelf && settings == null && allApps == null && homePrompt == null;
    }

    private void buildTicker() {
        int sw = getResources().getDisplayMetrics().widthPixels, sh = getResources().getDisplayMetrics().heightPixels;
        int right = (1920 + GRID_W) / 2;                    // thẳng mép phải lưới (cùng mép đồng hồ)
        int x0 = right - TICK_W;
        String[] names = {"BTC", "ETH", "Vàng"};
        // một thẻ kính duy nhất, bo góc và độ đậm như thẻ lịch; cao co giãn theo số dòng đang bật (applyWidgets)
        tickCard = new FrameLayout(this);
        tickCard.setFocusable(false);
        tickFrost[0] = new FrostView(this, px(20), sw, sh, px(x0), px(TICK_TOP), Math.max(1, px(1))).deep();
        tickCard.addView(tickFrost[0], new FrameLayout.LayoutParams(-1, -1));
        for (int i = 0; i < TICKS; i++) {
            FrameLayout row = new FrameLayout(this);
            row.setFocusable(false);
            // vạch ngăn mảnh ở đầu dòng (ẩn ở dòng đầu tiên đang hiện)
            View line = new View(this);
            line.setBackgroundColor(0x1AFFFFFF);
            FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(-1, Math.max(1, px(1)), Gravity.TOP);
            ll.setMargins(px(TICK_PAD), 0, px(TICK_PAD), 0);
            row.addView(line, ll);
            tickLine[i] = line;
            TextView name = text(500, 18, 0x80FFFFFF);
            name.setText(names[i]);
            FrameLayout.LayoutParams ln = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
            ln.setMargins(px(TICK_PAD), px(14), 0, 0);
            row.addView(name, ln);
            tickExtra[i] = text(500, 18, 0x73FFFFFF);
            tickExtra[i].setText("");
            FrameLayout.LayoutParams le = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            le.setMargins(0, px(14), px(TICK_PAD), 0);
            row.addView(tickExtra[i], le);
            tickPrice[i] = text(600, 30, WHITE);
            tickPrice[i].setFontFeatureSettings("tnum");     // số đều chiều rộng, nhảy số không xô chữ
            tickPrice[i].setText("···");
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
            lp.setMargins(px(TICK_PAD), px(38), 0, 0);
            row.addView(tickPrice[i], lp);
            FrameLayout.LayoutParams lr = new FrameLayout.LayoutParams(-1, px(TICK_H), Gravity.TOP);
            lr.topMargin = px(TICK_CARD_PAD + i * TICK_H);
            tickCard.addView(row, lr);
            tickTile[i] = row;
        }
        FrameLayout.LayoutParams lc = new FrameLayout.LayoutParams(px(TICK_W), px(tickCardH(TICKS)), Gravity.TOP | Gravity.START);
        lc.leftMargin = px(x0); lc.topMargin = px(TICK_TOP);
        root.addView(tickCard, lc);
    }

    private static int tickCardH(int rows) { return rows * TICK_H + TICK_CARD_PAD * 2; }

    /** Thẻ giá co/giãn tới đủ chỗ cho n dòng (đổi cỡ vài lần một đời, chỉ khi bật/tắt ô trong Cài đặt). */
    private void sizeTickCard(int n, boolean animate) {
        if (tickCardAnim != null) tickCardAnim.cancel();
        ViewGroup.LayoutParams lp = tickCard.getLayoutParams();
        int to = px(tickCardH(n));
        if (!animate || lp.height == to) { lp.height = to; tickCard.setLayoutParams(lp); return; }
        tickCardAnim = ValueAnimator.ofInt(lp.height, to);
        tickCardAnim.setDuration(420);
        tickCardAnim.setInterpolator(EASE);
        tickCardAnim.addUpdateListener(an -> {
            ViewGroup.LayoutParams l = tickCard.getLayoutParams();
            l.height = (int) an.getAnimatedValue();
            tickCard.setLayoutParams(l);
        });
        tickCardAnim.start();
    }

    private static java.text.DecimalFormat viFormat(String pattern) {
        java.text.DecimalFormatSymbols s = new java.text.DecimalFormatSymbols(new Locale("vi", "VN"));
        s.setGroupingSeparator('.'); s.setDecimalSeparator(',');
        return new java.text.DecimalFormat(pattern, s);
    }

    // luồng riêng cho mạng (không làm chậm việc làm nhoè kính/đọc nội dung); lần gọi trước chưa xong thì bỏ qua
    private final java.util.concurrent.ExecutorService net = Executors.newFixedThreadPool(2);
    private final java.util.concurrent.atomic.AtomicBoolean btcBusy = new java.util.concurrent.atomic.AtomicBoolean(),
            goldBusy = new java.util.concurrent.atomic.AtomicBoolean();

    private final Runnable btcTick = new Runnable() {
        @Override public void run() {
            // ô thumb đang phát kênh: khỏi gọi mạng và khỏi vẽ lại ô giá (số chạy làm cả màn vẽ lại, hình xem trước khựng)
            if (thumbPrev == null && btcBusy.compareAndSet(false, true)) {
                net.execute(() -> {
                    Prices.Btc[] cs = Prices.crypto();
                    btcBusy.set(false);
                    ui.post(() -> {
                        if (cs != null) for (int i = 0; i < cs.length; i++) if (cs[i] != null) showCoin(i, cs[i]);
                        markStale();
                    });
                });
            }
            ui.postDelayed(this, BTC_EVERY);
        }
    };

    private final Runnable goldTick = new Runnable() {
        @Override public void run() {
            if (goldBusy.compareAndSet(false, true)) {
                net.execute(() -> {
                    Prices.Gold g = Prices.gold();
                    goldBusy.set(false);
                    ui.post(() -> { if (g != null) showGold(g); markStale(); });
                });
            }
            ui.postDelayed(this, GOLD_EVERY);
        }
    };

    /** BTC/ETH (ô i): xanh khi tăng, đỏ khi giảm so với 24 giờ trước, trắng khi đứng; nhảy giá thì chớp nhẹ. */
    private void showCoin(int i, Prices.Btc b) {
        btcOkAt = System.currentTimeMillis();
        int col = b.changePct > 0.005 ? GREEN : b.changePct < -0.005 ? RED : WHITE;
        String arrow = col == GREEN ? "▲ " : col == RED ? "▼ " : "";
        tickExtra[i].setText(arrow + viFormat("0.00").format(Math.abs(b.changePct)) + "%");
        tickExtra[i].setTextColor(col == WHITE ? 0x73FFFFFF : col);
        tickPrice[i].setText(viFormat("#,##0.00").format(b.price));
        tickPrice[i].setTextColor(col);
        if (lastCoin[i] >= 0 && b.price != lastCoin[i]) {
            tickPrice[i].animate().cancel();
            tickPrice[i].setAlpha(0.45f);
            tickPrice[i].animate().alpha(1f).setDuration(560).setInterpolator(EASE).start();
        }
        lastCoin[i] = b.price;
        lastChg[i] = b.changePct;
    }

    /** Vàng: so với mức giá trước đó (tự nhớ): xanh nếu tăng, đỏ nếu giảm, trắng nếu chưa đổi. */
    private void showGold(Prices.Gold g) {
        goldOkAt = System.currentTimeMillis();
        long last = prefs().getLong("gold_last", 0), prev = prefs().getLong("gold_prev", 0);
        if (g.sell != last) {
            prev = last;
            prefs().edit().putLong("gold_prev", prev).putLong("gold_last", g.sell).apply();
        }
        int col = prev <= 0 || g.sell == prev ? WHITE : g.sell > prev ? GREEN : RED;
        tickPrice[GOLD].setText(viFormat("#,##0").format(g.sell));
        tickPrice[GOLD].setTextColor(col);
    }

    /** Mất mạng/nguồn lỗi: giữ giá cuối và làm mờ để biết là giá cũ. */
    private void markStale() {
        long now = System.currentTimeMillis();
        float coin = btcOkAt > 0 && now - btcOkAt < 30_000 ? 1f : 0.5f;
        for (int i = 0; i < GOLD; i++) tickTile[i].animate().alpha(coin).setDuration(420).start();
        tickTile[GOLD].animate().alpha(goldOkAt > 0 && now - goldOkAt < 2 * 3600_000 ? 1f : 0.5f).setDuration(420).start();
    }

    // ---------- quầng sáng: một lớp riêng nằm dưới khay kính, trượt theo ô đang chọn ----------

    private static final int GLOW_SPREAD = 145;   // lề ảnh quầng sáng; độ toả thật ~60px quanh ô + phần nhoè
    private final List<Bitmap> glowBitmaps = new ArrayList<>();
    private final ImageView[] glowViews = new ImageView[2];
    private int glowCur = -1;
    private AnimatorSet breathe;

    private void buildGlowLayer() {
        int w = px(SEL_W) + px(GLOW_SPREAD) * 2, h = px(SEL_H) + px(GLOW_SPREAD) * 2;
        for (int i = 0; i < 2; i++) {
            ImageView g = new ImageView(this);
            g.setAlpha(0f);
            root.addView(g, new FrameLayout.LayoutParams(w, h));   // thêm sau khay -> nằm trên khay
            glowViews[i] = g;
        }
    }

    // ---------- dock dài hơn màn hình: trượt ngang để ô đang chọn luôn nằm trong khung ----------

    // ---------- dock hạ thấp khi đứng ở dock (hàng nội dung ló nửa dưới mép), nâng lên khi vào hàng ----------

    // cả cụm (hàng nội dung ở trên + dock ở dưới) hạ thấp sát đáy: khay từ y=849, đáy khay 1025, ô app đang chọn
    // chạm ~1021. Vào hàng nội dung không xê dịch gì nữa (hàng đã nằm trọn trên dock).
    private static final int DOCK_DROP = 235;
    private float dockLift;
    private ValueAnimator liftAnim;

    /** Đặt độ hạ của cả cụm dưới (khay, kính mờ, hàng nội dung); quầng sáng tự theo ở lần moveGlow kế tiếp. */
    private void setLift(float target, boolean animate) {
        if (liftAnim != null) liftAnim.cancel();
        dockLift = target;
        if (!animate) { applyLift(target); return; }
        liftAnim = ValueAnimator.ofFloat(tray.getTranslationY(), target);
        liftAnim.setDuration(560);
        liftAnim.setInterpolator(EASE);
        liftAnim.addUpdateListener(an -> applyLift((float) an.getAnimatedValue()));
        liftAnim.start();
    }

    private void applyLift(float y) {
        tray.setTranslationY(y);
        frost.setShiftY(y);
        shelfRow.setTranslationY(y);
        rowHead.setTranslationY(y);
    }

    private float dockShift;
    private ValueAnimator dockAnim;

    /** Ô đang chọn lọt ra ngoài khung 6 ô thì cả khay (ô + kính mờ) trượt vừa đủ để thấy trọn ô đó. */
    private void shiftDock(int i) {
        int tl = trayLeft(), gl = (1920 - GRID_W) / 2;
        float left = px(tl + TRAY_PAD_X + i * (CARD_W + CARD_GAP)), right = left + px(CARD_W);
        float min = px(gl + TRAY_PAD_X), max = px(1920 - gl - TRAY_PAD_X);
        float target = dockShift;
        if (right + target > max) target = max - right;
        if (left + target < min) target = min - left;
        target = Math.min(0f, target);
        if (target == dockShift) return;
        if (dockAnim != null) dockAnim.cancel();
        dockAnim = ValueAnimator.ofFloat(tray.getTranslationX(), target);
        dockAnim.setDuration(520);
        dockAnim.setInterpolator(EASE);
        dockAnim.addUpdateListener(an -> {
            float s = (float) an.getAnimatedValue();
            tray.setTranslationX(s);
            frost.setShift(s);
        });
        dockAnim.start();
        dockShift = target;
    }

    /** Quầng cũ trượt sang ô mới và mờ đi, quầng mới (màu logo mới) trượt theo và hiện lên. */
    private void moveGlow(int i) {
        FrameLayout cell = cards.get(i);
        cell.post(() -> {
            // tính theo vị trí đích của khay (đang trượt thì quầng tới thẳng chỗ ô sẽ dừng)
            // ô phóng đều từ tâm: quầng (cỡ ô đã phóng) đặt sao cho tâm trùng tâm ô
            // toạ độ nguyên: lỗ khoét trùng mép ô đang chọn từng điểm ảnh (không lệch nửa điểm ảnh gây mép gai)
            float x = Math.round(tray.getLeft() + dockShift + cell.getLeft() - (px(SEL_W) - cell.getWidth()) / 2f) - px(GLOW_SPREAD);
            float y = Math.round(tray.getTop() + dockLift + cell.getTop() - (px(SEL_H) - cell.getHeight()) / 2f) - px(GLOW_SPREAD);
            if (breathe != null) { breathe.cancel(); breathe = null; }
            int nextIdx = glowCur < 0 ? 0 : 1 - glowCur;
            ImageView next = glowViews[nextIdx];
            ImageView cur = glowCur < 0 ? null : glowViews[glowCur];
            next.animate().cancel();
            next.setImageBitmap(glowBitmaps.get(i));
            if (cur != null && cur.getAlpha() > 0.05f) { next.setX(cur.getX()); next.setY(cur.getY()); }
            else { next.setX(x); next.setY(y); }
            next.setScaleX(1f); next.setScaleY(1f);   // không co giãn: lỗ khoét luôn khít mép ô
            next.animate().x(x).y(y).alpha(GLOW_MOVING).setDuration(520)
                    .setInterpolator(EASE)
                    .withEndAction(() -> startBreathing(next)).start();
            if (cur != null) {
                cur.animate().cancel();
                cur.animate().x(x).y(y).alpha(0f).setDuration(520).setInterpolator(EASE).start();
            }
            glowCur = nextIdx;
        });
    }

    // đang lướt / vừa sang ô: quầng dịu; đứng yên ~1 s mới sáng lên tối đa rồi thở
    private static final float GLOW_MOVING = 0.6f;

    /**
     * Đứng yên ở một ô: chờ ~1 s, quầng sáng dần từ mức dịu lên tối đa (0,8 s), rồi "thở": dịu còn ~55%
     * và sáng lại đầy, mỗi nhịp ~3,2 s. Di chuyển sang ô khác thì quầng về mức dịu (moveGlow).
     * Một bộ đếm thời gian duy nhất tự tính độ sáng theo thời gian (bộ hiệu ứng lồng nhau không chạy ổn trên box).
     * Chỉ đổi độ sáng, không phình to: quầng có lỗ khoét đúng hình ô, phình ra là hở khe giữa ô và quầng.
     */
    private void startBreathing(View g) {
        if (breathe != null) breathe.cancel();
        final float from = g.getAlpha();
        ValueAnimator clock = ValueAnimator.ofFloat(0f, 1f);
        clock.setDuration(60_000);
        clock.setRepeatCount(ValueAnimator.INFINITE);
        clock.setInterpolator(new android.view.animation.LinearInterpolator());
        final long t0 = android.os.SystemClock.uptimeMillis();
        clock.addUpdateListener(an -> {
            long t = android.os.SystemClock.uptimeMillis() - t0 - 1000;   // chờ 1 s đứng yên
            if (t < 0) { g.setAlpha(from); return; }
            if (t < 800) { g.setAlpha(from + (1f - from) * EASE.getInterpolation(t / 800f)); return; }
            double ph = (t - 800) / 3200.0 * 2 * Math.PI;                  // nhịp 3,2 s: 1 -> 0,55 -> 1
            g.setAlpha((float) (0.775 + 0.225 * Math.cos(ph)));
        });
        breathe = new AnimatorSet();
        breathe.play(clock);
        breathe.start();
    }

    /** Rời dock (vào hàng nội dung / mở bảng ứng dụng): quầng sáng dịu đi. */
    private void dimGlow(boolean dim) {
        if (glowCur < 0) return;
        if (breathe != null) { breathe.cancel(); breathe = null; }
        ImageView g = glowViews[glowCur];
        g.animate().cancel();
        g.animate().alpha(dim ? 0f : GLOW_MOVING).scaleX(1f).scaleY(1f).setDuration(360)
                .withEndAction(() -> { if (!dim) startBreathing(g); }).start();
    }

    // Khu nội dung dưới dock: luôn hiện nội dung của app đang chọn (đang xem dở trước, rồi gợi ý).
    private void buildShelf() {
        rowHead = text(600, 30, 0xFFFFFFFF);
        FrameLayout.LayoutParams lh = new FrameLayout.LayoutParams(-2, -2);
        root.addView(rowHead, lh);
        rowHead.setVisibility(View.GONE);   // không hiện tiêu đề hàng: ảnh nội dung tự nói lên nó là gì

        // lưới chung kiểu Apple: tiêu đề + hàng ảnh thẳng mép với ô app đầu và cuối trong dock
        int trayW = GRID_W;
        int innerW = trayW - GRID_PAD * 2;
        int left = (1920 - trayW) / 2 + GRID_PAD;
        int thumbW = (innerW - (SHELF_N - 1) * THUMB_GAP) / SHELF_N;
        int thumbH = Math.round(thumbW * 9f / 16f);
        thumbSize[0] = thumbW; thumbSize[1] = thumbH;

        shelfRow = new LinearLayout(this);
        shelfRow.setOrientation(LinearLayout.HORIZONTAL);
        shelfRow.setClipChildren(false); shelfRow.setClipToPadding(false);
        int rowW = ROW_MAX * thumbW + (ROW_MAX - 1) * THUMB_GAP;   // đủ chỗ cho mọi ô; phần quá mép phải nằm ngoài màn
        FrameLayout.LayoutParams lr = new FrameLayout.LayoutParams(px(rowW), px(thumbH), Gravity.TOP | Gravity.START);
        lr.topMargin = px(ROW_TOP);
        lr.leftMargin = px(left);
        root.addView(shelfRow, lr);
        for (int i = 0; i < ROW_MAX; i++) {
            FrameLayout t = thumb(i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(thumbW), px(thumbH));
            if (i > 0) lp.leftMargin = px(THUMB_GAP);
            shelfRow.addView(t, lp);
            thumbs.add(t);
        }
        rowHead.setX(px(left));
        rowHead.setY(px(ROW_TOP - 54));
        for (FrameLayout t : thumbs) t.setAlpha(ROW_IDLE_ALPHA);
    }

    // ô phim đang chọn to thêm đúng 80x45 điểm ảnh (332x187 -> 412x232, ~124%, vẫn 16:9): mép rơi đúng điểm ảnh.
    // Mỗi bên lấn 40 > khe 30, nên các ô hai bên dạt ra 40 (spreadThumbs) để vẫn hở đúng khe 30, không ô nào đè ô nào.
    // Cả hai số phải chẵn: ô nở đều từ tâm nên mỗi mép nhích ra nửa số này. 45 làm mép trên rơi vào nửa điểm ảnh
    // (lệch 22,5): lúc phóng còn vẽ qua lớp đệm thì mép mịn, phóng xong vẽ thẳng thì hàng điểm ảnh dở dang ở mép trên
    // lộ nền xám của ô thành một mảnh sáng tự hiện ra sau khi phóng. 46: lệch đúng 23, mép rơi đúng điểm ảnh.
    private static final int THUMB_GROW_W = 80, THUMB_GROW_H = 46;
    private float thumbScaleX() { return (px(thumbSize[0]) + px(THUMB_GROW_W)) / (float) px(thumbSize[0]); }
    private float thumbScaleY() { return (px(thumbSize[1]) + px(THUMB_GROW_H)) / (float) px(thumbSize[1]); }

    /** Ô sel phóng to: ô bên trái dạt trái, ô bên phải dạt phải đúng phần lấn; sel = -1 thì cả hàng về chỗ cũ. */
    private void spreadThumbs(int sel) {
        float d = px(THUMB_GROW_W) / 2f;
        for (int j = 0; j < thumbs.size(); j++) {
            float x = sel < 0 || j == sel ? 0 : j < sel ? -d : d;
            FrameLayout t = thumbs.get(j);
            if (t.getTranslationX() == x) continue;
            t.animate().translationX(x).setStartDelay(0).setInterpolator(EASE).setDuration(440).withLayer().start();
        }
    }

    // ---------- cuộn hàng ----------
    // Hàng nhiều hơn SHELF_N ô: ô đầu đang thấy (rowFirst) dời theo ô chọn sao cho ô chọn luôn nằm trong lưới, cả hàng trượt
    // ngang một bước đúng một ô + khe. Ô kế tiếp ngoài lưới ló một mẩu ở mép màn, gợi ý còn nội dung.
    private int rowFirst;

    private void scrollRowTo(int i) {
        int first = rowFirst;
        if (i < first) first = i;
        else if (i > first + SHELF_N - 1) first = i - (SHELF_N - 1);
        if (first == rowFirst) return;
        rowFirst = first;
        float x = -first * px(thumbSize[0] + THUMB_GAP);
        shelfRow.animate().translationX(x).setInterpolator(EASE).setDuration(440).start();
    }

    private void resetRowScroll() {
        rowFirst = 0;
        shelfRow.animate().translationX(0).setDuration(0).start();
        shelfRow.setTranslationX(0);
    }

    private static final float THUMB_DIM = 0.68f;   // ô phim không được chọn khi đang ở hàng nội dung (trong quá thì ảnh nền xuyên qua, nhìn loá)

    // ---------- xem trước kênh ngay trên ô thumb (hàng của ô Truyền hình) ----------
    // Đứng yên trên ô kênh 0,3 giây (ô đã phóng to xong) thì một khung đúng cỡ ô đã phóng đè lên: dưới cùng là SurfaceView
    // phát kênh, trên là ảnh kênh (trông y như ô) mờ dần khi có khung hình đầu, tiếng to dần (TvPreview). Khung nằm thẳng
    // trong root, không nằm trong ô (ô có scale, SurfaceView không phóng/thu theo), rời ô là gỡ hẳn khung, Surface tự huỷ.
    private static final long THUMB_DWELL_MS = 300;
    private FrameLayout thumbPrevBox;
    private ImageView thumbPrevArt;
    private TvPreview thumbPrev;
    private int thumbPrevIdx = -1;
    private Runnable thumbDwell;

    private void scheduleThumbPreview(int i) {
        if (thumbDwell != null) ui.removeCallbacks(thumbDwell);
        thumbDwell = null;
        if (i >= rowItems.size() || rowItems.get(i).tvChannel == null) return;
        if (thumbPrevIdx == i && thumbPrevBox != null) return;
        thumbDwell = () -> { thumbDwell = null; startThumbPreview(i); };
        ui.postDelayed(thumbDwell, THUMB_DWELL_MS);
    }

    private void startThumbPreview(int i) {
        stopThumbPreview(false);
        if (!inShelf || allApps != null || i >= rowItems.size() || !thumbs.get(i).hasFocus()) return;
        Tv.Channel ch = Tv.find(this, rowItems.get(i).tvChannel);
        if (ch == null || ch.url == null || !ch.url.startsWith("udp://")) return;
        FrameLayout t = thumbs.get(i);
        int w = px(thumbSize[0]), h = px(thumbSize[1]);
        int sw = w + px(THUMB_GROW_W), sh = h + px(THUMB_GROW_H);
        int x = Math.round(shelfRow.getLeft() + shelfRow.getTranslationX()) + t.getLeft() - (sw - w) / 2;
        int y = Math.round(shelfRow.getTop() + dockLift) + t.getTop() - (sh - h) / 2;
        thumbPrevIdx = i;
        FrameLayout box = new FrameLayout(this);
        roundClip(box, px(THUMB_R) * thumbScaleY());
        android.view.SurfaceView sv = new android.view.SurfaceView(this);
        sv.getHolder().addCallback(new android.view.SurfaceHolder.Callback() {
            @Override public void surfaceCreated(android.view.SurfaceHolder hd) {
                if (thumbPrevBox != box || thumbPrev != null) return;
                thumbPrev = TvPreview.start(MainActivity.this, ch.url, hd.getSurface(), new TvPreview.Listener() {
                    @Override public void onFirstFrame() {
                        if (thumbPrevBox != box) return;
                        thumbPrevArt.animate().alpha(0f).setDuration(400).setInterpolator(EASE).start();
                    }
                    @Override public void onFailed() { if (thumbPrevBox == box) stopThumbPreview(false); }
                });
            }
            @Override public void surfaceChanged(android.view.SurfaceHolder hd, int f, int ww, int hh) {}
            @Override public void surfaceDestroyed(android.view.SurfaceHolder hd) {
                if (thumbPrevBox == box || thumbPrevBox == null) { TvPreview p = thumbPrev; thumbPrev = null; if (p != null) p.stop(); }
            }
        });
        box.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        // ảnh kênh che hình tới khi có khung đầu (ô trông không đổi gì), rồi mờ đi
        ImageView art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.FIT_XY);
        art.setImageDrawable(thumbImage(t).getDrawable());
        box.addView(art, new FrameLayout.LayoutParams(-1, -1));
        thumbPrevArt = art;
        FrameLayout.LayoutParams lb = new FrameLayout.LayoutParams(sw, sh, Gravity.TOP | Gravity.START);
        lb.leftMargin = x; lb.topMargin = y;
        thumbPrevBox = box;
        root.addView(box, root.indexOfChild(shelfRow) + 1, lb);
        // ẩn mặt ô bên dưới: ô phóng 124% có mép trên rơi vào nửa điểm ảnh (y ,5), khung đặt trên toạ độ nguyên nên một
        // vệt mảnh của ô ló ra mép trên và hai góc bo (ảnh kênh VTV5 thành vạch xanh). Khung đã có ảnh kênh y hệt ô.
        t.getChildAt(0).setAlpha(0f);
    }

    /** Tắt xem trước trên ô: tiếng nhỏ dần (fade) hoặc dừng ngay, gỡ khung (Surface huỷ theo). */
    private void stopThumbPreview(boolean fade) {
        if (thumbDwell != null) { ui.removeCallbacks(thumbDwell); thumbDwell = null; }
        thumbPrevIdx = -1;
        TvPreview p = thumbPrev;
        thumbPrev = null;
        if (p != null) { if (fade) p.fadeOutAndStop(); else p.stop(); }
        FrameLayout box = thumbPrevBox;
        thumbPrevBox = null;
        thumbPrevArt = null;
        if (box != null) root.removeView(box);
        for (FrameLayout t : thumbs) t.getChildAt(0).setAlpha(1f);   // hiện lại mặt ô đã ẩn lúc xem trước
    }


    private FrameLayout thumb(int i) {
        FrameLayout t = new FrameLayout(this);
        t.setClipChildren(false);
        t.setFocusable(true);
        // mọi phần bo góc đều vẽ bằng drawable khử răng cưa, không cắt theo outline
        float tr = px(THUMB_R);
        FrameLayout face = new FrameLayout(this);
        GradientDrawable ph = new GradientDrawable();
        ph.setColor(0x26FFFFFF);
        ph.setCornerRadius(tr);
        face.setBackground(ph);
        shadowShape(face, tr);
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_XY);   // RoundImage tự cắt kiểu center-crop
        face.addView(img, new FrameLayout.LayoutParams(-1, -1));
        // dải tối mờ ở đáy để tên nội dung đọc được mà không cần khối chữ riêng
        View shade = new View(this);
        GradientDrawable sd = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xD9000000, 0x66000000, 0x00000000});
        sd.setCornerRadii(new float[]{0, 0, 0, 0, tr, tr, tr, tr});
        shade.setBackground(sd);
        face.addView(shade, new FrameLayout.LayoutParams(-1, px(thumbSize[1] * 0.55f), Gravity.BOTTOM));
        TextView title = text(600, 21, 0xFFFFFFFF);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams lt = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        lt.setMargins(px(14), 0, px(14), px(16));
        face.addView(title, lt);
        // thanh tiến độ dạng viên thuốc, nằm gọn trong ảnh (không chạm góc bo)
        View bar = new View(this);
        GradientDrawable bd = new GradientDrawable();
        bd.setColor(0xF2FFFFFF);
        bd.setCornerRadius(px(2));
        bar.setBackground(bd);
        FrameLayout.LayoutParams lbar = new FrameLayout.LayoutParams(0, px(4), Gravity.BOTTOM | Gravity.START);
        lbar.setMargins(px(12), 0, 0, px(8));
        face.addView(bar, lbar);
        // logo app nhỏ ở góc trên trái, chỉ hiện khi hàng trộn nội dung nhiều app
        ImageView badge = new ImageView(this);
        badge.setScaleType(ImageView.ScaleType.FIT_XY);
        badge.setElevation(px(4));
        shadowShape(badge, px(BADGE_H * 0.12f));
        FrameLayout.LayoutParams lbd = new FrameLayout.LayoutParams(px(BADGE_W), px(BADGE_H), Gravity.TOP | Gravity.START);
        lbd.setMargins(px(10), px(10), 0, 0);
        face.addView(badge, lbd);
        // lớp bóng khi chọn (mặt kính tĩnh, không viền): con cuối của mặt ô, đè lên mọi thứ
        face.addView(new ThumbGloss(this, tr), new FrameLayout.LayoutParams(-1, -1));
        t.addView(face, new FrameLayout.LayoutParams(-1, -1));
        t.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        // phóng đều từ tâm ra 4 phía tới đúng cỡ nguyên (thêm 32x18 điểm ảnh, cùng một tỉ lệ ~109,6%):
        // không nảy quá đà, không bóng đổ (bóng chỉ đổ xuống dưới làm ô trông phình lệch, và tốn sức vẽ mỗi khung)
        face.setOutlineProvider(null);
        t.setOnFocusChangeListener((v, has) -> {
            if (has) shelfState(true);   // ô phim nhận lựa chọn bằng đường nào thì cũng là đang ở hàng nội dung
            if (!has && thumbPrevIdx == i) stopThumbPreview(true);
            // ô chọn: to hẳn + sáng đầy; ô khác (khi đang ở hàng): tối bớt để ô chọn nổi lên
            v.setPivotX(v.getWidth() / 2f); v.setPivotY(v.getHeight() / 2f);
            v.animate().cancel();
            // ô không chọn (mờ 55%) giữ sẵn một lớp đệm: mờ cả ô mà không có lớp thì mỗi khung GPU phải vẽ ô ra lớp tạm
            // rồi ghép lại (4 ô = ~15ms/khung, ô nở khựng). Ô đang chọn vẽ thẳng (phóng 124% từ lớp đệm 1x thì nhoè).
            v.setLayerType(has ? View.LAYER_TYPE_NONE : View.LAYER_TYPE_HARDWARE, null);
            // vừa từ dock xuống: cả hàng đang trượt lên; phóng cùng lúc thì mép dưới bị kéo lên, trông như ô chỉ nở
            // lên trên. Đợi hàng trượt gần xong rồi mới phóng, để mắt thấy ô nở đều 4 phía.
            long wait = has && liftAnim != null && liftAnim.isRunning()
                    ? Math.max(0, liftAnim.getDuration() - liftAnim.getCurrentPlayTime() - 80) : 0;
            v.animate().scaleX(has ? thumbScaleX() : 1f).scaleY(has ? thumbScaleY() : 1f)
                    .alpha(has ? 1f : inShelf ? THUMB_DIM : ROW_IDLE_ALPHA)
                    .translationZ(has ? px(2) : 0)   // chỉ để ô chọn vẽ đè lên ô bên cạnh, không đổ bóng
                    .setStartDelay(has ? wait : 0)
                    // vẽ ô thành một lớp đệm suốt lúc phóng/thu: mỗi khung chỉ ghép lại tấm ảnh có sẵn, không vẽ lại ảnh
                    // lớn, dải tối, chữ (GPU box đã ~15ms/khung, vẽ lại ô là trễ khung, ô nở trông khựng)
                    .withLayer()
                    .setInterpolator(EASE).setDuration(has ? 440 : 380).start();
            thumbGloss((FrameLayout) v).focus(has, wait, has ? 440 : 380);   // cùng nhịp, cùng đường cong với ô phóng/thu
            if (has) { scrollRowTo(i); spreadThumbs(i); scheduleThumbPreview(i); }
        });
        // nhấn: lún nhẹ ngay; thả: phồng lại đúng cỡ chọn rồi mở nội dung, cửa sổ phóng ra từ ô này
        t.setOnKeyListener((v, kc, ev) -> {
            if (isOk(kc) && ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) {
                v.animate().cancel();
                v.animate().scaleX(1.03f).scaleY(1.03f).setInterpolator(EASE).setDuration(150).start();
            }
            return false;
        });
        t.setOnClickListener(v -> {
            stopThumbPreview(false);
            v.animate().cancel();
            v.animate().scaleX(thumbScaleX()).scaleY(thumbScaleY()).setInterpolator(EASE).setDuration(300).start();
            openItem(i, v);
        });
        return t;
    }

    private static ThumbGloss thumbGloss(FrameLayout t) {
        FrameLayout face = (FrameLayout) t.getChildAt(0);
        return (ThumbGloss) face.getChildAt(face.getChildCount() - 1);
    }

    private static ImageView thumbImage(FrameLayout t) { return (ImageView) ((FrameLayout) t.getChildAt(0)).getChildAt(0); }
    private static TextView thumbTitle(FrameLayout t) { return (TextView) ((FrameLayout) t.getChildAt(0)).getChildAt(2); }
    private static View thumbBar(FrameLayout t) { return ((FrameLayout) t.getChildAt(0)).getChildAt(3); }
    private static ImageView thumbBadge(FrameLayout t) { return (ImageView) ((FrameLayout) t.getChildAt(0)).getChildAt(4); }

    private static final int BADGE_W = 64, BADGE_H = 40;
    private final Map<String, Bitmap> badgeCache = new java.util.HashMap<>();

    /**
     * Ô cho app cài thêm (không có ảnh vẽ sẵn): banner TV của app phủ kín ô; app không có banner thì
     * biểu tượng đặt giữa nền màu chủ đạo của chính nó. Bo góc như ô vẽ sẵn (12% chiều cao), khử răng cưa.
     */
    private Bitmap appCard(String pkg, int w, int h) {
        Bitmap face = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(face);
        try {
            PackageManager pm = getPackageManager();
            Intent li = pm.getLeanbackLaunchIntentForPackage(pkg);
            Drawable banner = li != null ? pm.getActivityBanner(li) : null;
            if (banner == null) banner = pm.getApplicationBanner(pkg);
            if (banner != null) {
                // banner 16:9 -> ô 16:10: phủ kín, cắt bớt hai bên
                int bw = Math.round(h * 16f / 9f);
                c.drawBitmap(toBitmap(banner, bw, h), (w - bw) / 2f, 0, new Paint(Paint.FILTER_BITMAP_FLAG));
            } else {
                Bitmap icon = toBitmap(pm.getApplicationIcon(pkg), Math.round(h * 0.56f), Math.round(h * 0.56f));
                float[] hsv = new float[3];
                Color.colorToHSV(Glow.dominant(icon), hsv);
                hsv[2] = Math.min(hsv[2], 0.35f);
                c.drawColor(Color.HSVToColor(hsv));
                c.drawBitmap(icon, (w - icon.getWidth()) / 2f, (h - icon.getHeight()) / 2f, new Paint(Paint.FILTER_BITMAP_FLAG));
            }
        } catch (Exception e) {
            c.drawColor(0xFF2C2C30);
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setShader(new android.graphics.BitmapShader(face, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP));
        new Canvas(out).drawRoundRect(0, 0, w, h, h * 0.12f, h * 0.12f, p);
        return out;
    }

    /** Logo nhỏ của app (ảnh ô trên dock nếu có, không thì banner/biểu tượng của app). */
    // Logo nhỏ không khung cho ảnh phim (kiểu chữ N của Netflix): chỉ logo trong suốt, cao ~26px, bóng mềm phía sau
    // để không chìm trên ảnh sáng. App có trong đây dùng logo này thay cho ô dock thu nhỏ (to và nặng).
    private static final Map<String, String> MARK = new java.util.HashMap<>();
    static { MARK.put("com.iqiyi.i18n.tv", "cards/iqiyi_badge.png"); }
    private static final int MARK_H = 26, MARK_PAD = 4;
    private final Map<String, Bitmap> markCache = new java.util.HashMap<>();

    private Bitmap markFor(String pkg) {
        String path = MARK.get(pkg);
        if (path == null) return null;
        Bitmap m = markCache.get(pkg);
        if (m != null) return m;
        Bitmap src = asset(path);
        int h = px(MARK_H), w = Math.round(h * src.getWidth() / (float) src.getHeight());
        Bitmap logo = scaleHQ(src, w, h);
        int pad = px(MARK_PAD);
        m = Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(m);
        Paint sh = new Paint(Paint.ANTI_ALIAS_FLAG);
        sh.setMaskFilter(new android.graphics.BlurMaskFilter(px(3), android.graphics.BlurMaskFilter.Blur.NORMAL));
        sh.setColor(0x8C000000);
        c.drawBitmap(logo.extractAlpha(), pad, pad + px(1), sh);
        c.drawBitmap(logo, pad, pad, new Paint(Paint.FILTER_BITMAP_FLAG));
        markCache.put(pkg, m);
        return m;
    }

    private Bitmap badgeFor(String pkg) {
        Bitmap b = badgeCache.get(pkg);
        if (b != null) return b;
        int w = px(BADGE_W) * 2, h = px(BADGE_H) * 2;
        for (Apps.App a : apps) {
            if (pkg.equals(a.pkg) && a.key != null) { b = scaleHQ(asset("cards/" + a.key + ".png"), w, h); break; }
        }
        if (b == null) {
            try {
                PackageManager pm = getPackageManager();
                Drawable d = pm.getApplicationBanner(pkg);
                if (d == null) d = pm.getApplicationIcon(pkg);
                b = toBitmap(d, w, h);
            } catch (Exception e) { return null; }
        }
        badgeCache.put(pkg, b);
        return b;
    }

    /**
     * Tiêu đề hàng kiểu Apple TV: phần chính trắng semibold, phần phụ cùng dòng xám nhạt nét thường.
     * Thứ bậc bằng độ đậm và độ sáng, không dùng màu.
     */
    private CharSequence heading(String main, String sub) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(main);
        sb.setSpan(new android.text.style.TypefaceSpan(semibold), 0, main.length(), 0);
        sb.setSpan(new android.text.style.ForegroundColorSpan(0xFFFFFFFF), 0, main.length(), 0);
        if (sub != null) {
            int s = sb.length();
            sb.append("  –  ").append(sub);
            sb.setSpan(new android.text.style.TypefaceSpan(regular), s, sb.length(), 0);
            sb.setSpan(new android.text.style.ForegroundColorSpan(0x8CFFFFFF), s, sb.length(), 0);
        }
        return sb;
    }

    /**
     * Đổ nội dung vào hàng dưới dock: nội dung của app đang chọn đứng đầu; còn chỗ thì lấp bằng
     * nội dung đang xem dở của app khác (mới xem nhất trước, không trùng). Hàng trộn nhiều app thì
     * tiêu đề là "Xem tiếp" và mỗi ô có logo app nhỏ ở góc.
     */
    private void fillRow(Apps.App a) {
        String key = a.key + "|" + shelfVersion;
        if (key.equals(rowKey)) return;
        rowKey = key;
        // cùng app, chỉ là hàng vừa tải lại: giữ banner cũ tới khi banner mới sẵn sàng rồi hoà dần vào, tiêu đề đứng yên
        boolean sameApp = a.key.equals(rowApp);
        rowApp = a.key;

        List<Shelf.Item> own = itemsOf(a);
        List<Shelf.Item> items = new ArrayList<>();
        // FPT Play: đủ mọi trận của vòng (hàng cuộn); app khác tối đa ROW_N ô
        int cap = FptFeed.PKG.equals(a.pkg) || Tv360Feed.PKG.equals(a.pkg) ? ROW_MAX : ROW_N;
        for (Shelf.Item it : own) { if (items.size() < cap) items.add(it); }
        // hàng chỉ có nội dung của chính app đang chọn; app không có nội dung thì hàng để trống (không mượn app khác)
        boolean mixed = false;
        for (Shelf.Item it : items) if (!it.pkg.equals(items.get(0).pkg)) { mixed = true; break; }
        CharSequence head;
        if (items.isEmpty()) head = "";
        else if (a.isAllApps()) head = heading("Mở gần đây", null);
        else if (a.launchUri != null || a.tv) head = heading(a.label, null);
        else if (mixed || own.isEmpty()) head = heading("Xem tiếp", null);
        else head = heading(own.get(0).continueWatching ? "Đang xem dở" : "Gợi ý", a.label);
        rowItems = items;
        if (!sameApp || !head.toString().equals(rowHead.getText().toString())) {
            rowHead.animate().cancel();
            rowHead.setAlpha(0f);
            rowHead.setText(head);
            rowHead.animate().alpha(1f).setDuration(450).start();
        }
        resetRowScroll();
        for (int i = 0; i < thumbs.size(); i++) {
            FrameLayout t = thumbs.get(i);
            t.animate().cancel();
            boolean has = i < items.size();
            t.setVisibility(has ? View.VISIBLE : View.INVISIBLE);
            t.setFocusable(has);
            if (!has) continue;
            Shelf.Item it = items.get(i);
            images.into(thumbImage(t), it.art, px(thumbSize[0]) * 2, px(THUMB_R), sameApp);
            if (it.live && it.title != null && !it.title.isEmpty()) {
                // đang phát trực tiếp: nhãn đỏ ngắn trước tên video
                android.text.SpannableStringBuilder lt = new android.text.SpannableStringBuilder("● TRỰC TIẾP  ");
                lt.setSpan(new android.text.style.ForegroundColorSpan(0xFFFF4D4D), 0, lt.length(), 0);
                lt.setSpan(new android.text.style.TypefaceSpan(semibold), 0, lt.length(), 0);
                lt.append(it.title == null ? "" : it.title);
                thumbTitle(t).setText(lt);
            } else thumbTitle(t).setText(it.title == null ? "" : it.title);
            // dải tối đáy ô chỉ để đọc tên nội dung: ô không có chữ (kênh truyền hình, ảnh game) thì bỏ, ảnh sáng đều.
            // Ô kênh còn vì thế mà khớp với khung xem trước đè lên (ảnh kênh không dải tối), không loé sáng nửa dưới.
            ((FrameLayout) t.getChildAt(0)).getChildAt(1).setVisibility(
                    it.title == null || it.title.isEmpty() ? View.GONE : View.VISIBLE);
            View bar = thumbBar(t);
            ViewGroup.LayoutParams lb = bar.getLayoutParams();
            lb.width = Math.round((px(thumbSize[0]) - px(24)) * it.progress);
            bar.setLayoutParams(lb);
            ImageView badge = thumbBadge(t);
            // ảnh của Netflix đã in sẵn chữ N đỏ ở góc: không dán logo app đè lên, giữ chữ N gốc
            boolean branded = "com.netflix.ninja".equals(it.pkg);
            // iQIYI: ảnh không có logo của app, nên luôn gắn logo nhỏ ở góc trên trái (như chữ N trên ảnh Netflix)
            // ô live không chữ (banner trận FPT Play): viên đỏ "● TRỰC TIẾP" ở góc trên trái
            boolean pill = it.live && (it.title == null || it.title.isEmpty());
            Bitmap mark = pill ? livePill() : a.isAllApps() || a.launchUri != null || a.tv ? null : markFor(it.pkg);   // logo nhỏ không khung (iQIYI): luôn gắn, kể cả hàng của chính app
            // hàng "Mở gần đây": bản thân ô đã là logo app, không gắn thêm
            Bitmap bb = mark != null ? mark : (mixed || own.isEmpty()) && !branded && !a.isAllApps() && a.launchUri == null && !a.tv ? badgeFor(it.pkg) : null;
            badge.setVisibility(bb == null ? View.GONE : View.VISIBLE);
            if (bb != null) {
                badge.setImageBitmap(bb);
                FrameLayout.LayoutParams lbd = (FrameLayout.LayoutParams) badge.getLayoutParams();
                if (mark != null) {
                    // đúng cỡ ảnh, không khung, không bóng hộp; lề bù phần đệm bóng để logo cách mép ~10px
                    lbd.width = mark.getWidth(); lbd.height = mark.getHeight();
                    lbd.setMargins(px(pill ? 10 : 10 - MARK_PAD), px(pill ? 10 : 10 - MARK_PAD), 0, 0);
                    badge.setElevation(0);
                    badge.setOutlineProvider(null);
                } else {
                    lbd.width = px(BADGE_W); lbd.height = px(BADGE_H);
                    lbd.setMargins(px(10), px(10), 0, 0);
                    badge.setElevation(px(4));
                    shadowShape(badge, px(BADGE_H * 0.12f));
                }
                badge.setLayoutParams(lbd);
            }
            // đổi app: các ô mờ ra rồi trượt nhẹ vào lần lượt
            t.setAlpha(0f);
            t.setTranslationX(px(24));
            t.animate().alpha(inShelf ? THUMB_DIM : ROW_IDLE_ALPHA).translationX(0).setStartDelay(i * 50L).setDuration(440)
                    .setInterpolator(new DecelerateInterpolator(1.8f)).start();
        }
    }

    private Bitmap livePill;

    /** Viên đỏ "● TRỰC TIẾP" (vẽ một lần, dùng chung mọi ô). */
    private Bitmap livePill() {
        if (livePill != null) return livePill;
        android.graphics.Paint tp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        tp.setTypeface(fontFor(700, 14));
        tp.setTextSize(px(16));
        tp.setColor(0xFFFFFFFF);
        String txt = "TRỰC TIẾP";
        int h = px(26), pad = px(11), dot = px(8), gap = px(7);
        int w = Math.round(pad + dot + gap + tp.measureText(txt) + pad);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        android.graphics.Paint bg = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        bg.setColor(0xFFE5202E);
        c.drawRoundRect(0, 0, w, h, h / 2f, h / 2f, bg);
        c.drawCircle(pad + dot / 2f, h / 2f, dot / 2f, tp);
        android.graphics.Paint.FontMetrics fm = tp.getFontMetrics();
        c.drawText(txt, pad + dot + gap, h / 2f - (fm.ascent + fm.descent) / 2f, tp);
        livePill = b;
        return b;
    }

    // ---------- trạng thái chọn ----------

    private void onAppFocused(int i) {
        focused = i;
        prefs().edit().putInt("focused", i).apply();
        // không hiện tên app: nhìn logo là biết. ô phản hồi ngay; nền chỉ đổi khi người dùng dừng lại, lướt nhanh không tải ảnh thừa
        ui.removeCallbacks(pendingBg);
        ui.postDelayed(pendingBg, BG_SETTLE_MS);
    }

    private static final long BG_SETTLE_MS = 180;
    // đổi app chỉ đổi hàng nội dung; nền giữ nguyên, tự sang ảnh khác theo nhịp chọn trong Cài đặt
    // ô game có ảnh nền riêng: nền cả màn hình hoà sang ảnh của game (như PS4); rời ô game thì về ảnh nền thường
    private final Runnable pendingBg = () -> {
        Apps.App a = apps.get(focused);
        fillRow(a);
        if (a.bgFile != null) showGameBackground(a.bgFile);
        else if (bgShown != null && bgShown.startsWith("game:")) showBackground();
    };

    private void showGameBackground(String path) {
        String key = "game:" + path;
        if (key.equals(bgShown)) return;
        bgShown = key;
        images.load(android.net.Uri.fromFile(new File(path)).toString(), bgWidth(), bmp -> {
            if (key.equals(bgShown)) crossfade(bmp, BG_FALLBACK);
        });
    }

    private static final int BG_FALLBACK = 0xFF1E2A44;   // chưa có ảnh nào: nền màu xanh đêm

    /** Nền: ảnh tới lượt trong bộ ảnh đang chọn ở Cài đặt; cùng ảnh thì không tải lại. */
    private void showBackground() {
        String uri = sharedBackground();
        String key = "wall:" + uri;
        if (key.equals(bgShown)) return;
        bgShown = key;
        if (uri == null) { crossfade(null, BG_FALLBACK); return; }
        images.load(uri, bgWidth(), bmp -> {
            if (!key.equals(bgShown)) return;
            crossfade(bmp, BG_FALLBACK);
        });
    }

    /**
     * Bộ ảnh nền theo Cài đặt (ảnh kèm sẵn, ảnh của bạn hoặc trộn, xem Wallpapers); lần lượt từng ảnh,
     * đổi theo nhịp đã chọn (mặc định 10 phút).
     */
    private String sharedBackground() {
        List<String> fs = Wallpapers.active(this, Wallpapers.HOME);
        if (fs.isEmpty()) return null;
        long slot = System.currentTimeMillis() / bgRotateMs() + prefs().getInt("bg_skip", 0);
        return fs.get((int) (slot % fs.size()));
    }

    /** Hiện ngay ảnh có tên bắt đầu bằng `name`; vòng xoay đi tiếp từ ảnh đó. */
    private void showWallpaper(String name) {
        List<String> fs = Wallpapers.active(this, Wallpapers.HOME);
        for (int i = 0; i < fs.size(); i++) {
            String n = fs.get(i).substring(fs.get(i).lastIndexOf('/') + 1);
            if (!n.startsWith(name)) continue;
            long slot = System.currentTimeMillis() / bgRotateMs();
            int skip = (int) (((i - slot) % fs.size() + fs.size()) % fs.size());
            prefs().edit().putInt("bg_skip", skip).apply();
            if (inShelf) exitShelf();
            showBackground();
            return;
        }
    }

    private static boolean inside(View v, ViewGroup g) {
        for (android.view.ViewParent p = v == null ? null : v.getParent(); p != null; p = p.getParent()) if (p == g) return true;
        return false;
    }

    private static boolean isAppsButton(String act) {
        return "android.intent.action.ALL_APPS".equals(act) || "vn.glass.home.ALL_APPS".equals(act);
    }

    /** Sang ngay ảnh kế tiếp trong vòng (am start -a vn.glass.home.NEXT_BG -n vn.glass.home/.MainActivity). */
    private void nextBackground() {
        prefs().edit().putInt("bg_skip", prefs().getInt("bg_skip", 0) + 1).apply();
        if (inShelf) exitShelf();
        showBackground();
    }

    private long bgRotateMs() { return Wallpapers.everyMin(this, Wallpapers.HOME) * 60_000L; }
    // tới lượt ảnh chung kế tiếp: chỉ đổi khi đang đứng ở dock (đang soi hàng nội dung thì giữ nền của ô)
    private final Runnable bgRotate = new Runnable() {
        @Override public void run() {
            // đang chọn ô game (nền là ảnh game) thì không đổi sang ảnh nền khác
            if (!inShelf && allApps == null && apps.get(focused).bgFile == null) showBackground();
            ui.postDelayed(this, bgRotateMs());
        }
    };

    private int bgWidth() { return Math.min(1600, getResources().getDisplayMetrics().widthPixels); }

    /** Bấm lên từ dock: vào hàng nội dung phía trên (hàng sáng lên, dock lùi nhẹ). */
    private void enterShelf() {
        // đang lướt nhanh mà hàng chưa kịp đổi: đổ ngay theo app đang chọn
        ui.removeCallbacks(pendingBg);
        pendingBg.run();
        if (rowItems.isEmpty()) return;
        shelfState(true);
        thumbs.get(0).requestFocus();
    }

    /** Từ thẻ lịch / nút Cài đặt bấm xuống: vào hàng thumb (nằm ngay dưới) nếu có nội dung, không thì về dock. */
    private void focusBelowTop() {
        enterShelf();
        if (!inShelf) cards.get(focused).requestFocus();
    }

    /** Bấm xuống từ hàng nội dung: về đúng ô app. */
    private void exitShelf() {
        shelfState(false);
        cards.get(focused).requestFocus();
    }

    /**
     * Trạng thái dock / hàng nội dung. Gọi cả từ sự kiện chọn ô (ô dock hay ô phim nhận lựa chọn bằng
     * đường nào cũng vậy) nên trạng thái luôn khớp với ô đang chọn, không bao giờ lệch.
     * Ảnh nền đứng yên khi chuyển qua lại (chỉ đổi theo nhịp hoặc nút APPS).
     */
    private void shelfState(boolean on) {
        if (inShelf == on) return;
        inShelf = on;
        dimGlow(on);
        if (!on) { stopThumbPreview(true); spreadThumbs(-1); fillRow(apps.get(focused)); }   // có bản mới (kênh vừa live) lúc đứng trong hàng: giờ mới đổ
        syncRowWithEpl();
        // vào hàng: ô chưa chọn tối bớt (ô chọn tự sáng lại trong sự kiện chọn); về dock: cả hàng sáng đều
        for (FrameLayout t : thumbs) if (!t.hasFocus()) t.animate().alpha(on ? THUMB_DIM : ROW_IDLE_ALPHA).setDuration(360).start();
        // dock lùi: mờ từng ảnh ô (ImageView vẽ thẳng với độ mờ, không cần lớp tạm). Mờ cả khay thì mỗi khung lúc ô app
        // đang phóng/thu GPU phải vẽ lại cả khay ra lớp tạm (~15ms), bấm xuống về dock bị khựng.
        for (FrameLayout c : cards) c.getChildAt(0).animate().alpha(on ? 0.6f : 1f).setDuration(360).start();
    }

    // ---------- nền ----------

    private AnimatorSet bgAnim;

    /** Ảnh mới mờ dần vào và thu nhẹ 106% -> 100% (Ken Burns); ảnh cũ tối dần phía sau. */
    private void crossfade(Bitmap bmp, int color) {
        if (bgAnim != null) bgAnim.cancel();
        bgBack.setImageDrawable(bgFront.getDrawable());
        bgBack.setScaleX(bgFront.getScaleX()); bgBack.setScaleY(bgFront.getScaleY());
        bgBack.setAlpha(bgFront.getAlpha());
        bgBack.setVisibility(View.VISIBLE);
        if (bmp != null) bgFront.setImageBitmap(bmp);
        else bgFront.setImageDrawable(colorBackground(color));
        updateFrost(bmp, color);
        bgFront.setAlpha(0f);
        bgFront.setScaleX(1.06f); bgFront.setScaleY(1.06f);

        ObjectAnimator fadeIn = ObjectAnimator.ofFloat(bgFront, View.ALPHA, 0f, 1f);
        fadeIn.setDuration(550);
        fadeIn.setInterpolator(new DecelerateInterpolator(1.6f));
        ObjectAnimator sx = ObjectAnimator.ofFloat(bgFront, View.SCALE_X, 1.06f, 1f);
        ObjectAnimator sy = ObjectAnimator.ofFloat(bgFront, View.SCALE_Y, 1.06f, 1f);
        sx.setDuration(1200); sy.setDuration(1200);
        sx.setInterpolator(new DecelerateInterpolator(2f)); sy.setInterpolator(new DecelerateInterpolator(2f));
        ObjectAnimator dim = ObjectAnimator.ofFloat(bgBack, View.ALPHA, bgBack.getAlpha(), 0.35f);
        dim.setDuration(550);

        // lớp phủ mỏng theo màu chủ đạo của ảnh mới, chuyển màu cùng lúc với ảnh
        int target = overlayFor(bmp, color);
        ValueAnimator tc = ValueAnimator.ofArgb(tintColor, target);
        tc.setDuration(700);
        tc.addUpdateListener(an -> { tintColor = (int) an.getAnimatedValue(); tint.setBackgroundColor(tintColor); });

        bgAnim = new AnimatorSet();
        bgAnim.playTogether(fadeIn, sx, sy, dim, tc);
        // ảnh mới đã phủ kín: ẩn ảnh cũ phía sau, đỡ vẽ thêm một lớp toàn màn hình ở mọi khung
        fadeIn.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean canceled;
            @Override public void onAnimationCancel(android.animation.Animator a) { canceled = true; }
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (!canceled) { bgBack.setVisibility(View.INVISIBLE); bgBack.setImageDrawable(null); }
            }
        });
        bgAnim.start();
    }

    // nền màu đã rải nhiễu, giữ vài cái gần nhất (mỗi cái ~8MB ở 1080p)
    private final android.util.LruCache<Integer, Bitmap> colorBgs = new android.util.LruCache<>(3);

    private Drawable colorBackground(int c) {
        Bitmap b = colorBgs.get(c);
        if (b == null) {
            int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
            b = Dither.radial(w, h, w * 0.7f, h * 0.3f, px(1300), c, 0xFF050507);
            colorBgs.put(c, b);
        }
        return new android.graphics.drawable.BitmapDrawable(getResources(), b);
    }


    // ---------- lớp phủ nền: mỏng, theo màu chủ đạo của ảnh ----------

    private View tint;
    private int tintColor = 0x2E000000;
    private static final int TINT_ALPHA = 0x38;   // ~22%, phủ nhẹ

    /**
     * Màu chủ đạo của ảnh (trung bình có trọng số độ bão hoà), làm sẫm lại rồi phủ lên.
     * Độ phủ theo độ sáng của ảnh: ảnh tối phủ nhẹ (~22%), ảnh càng sáng phủ càng đậm (tới ~55%),
     * để ảnh nào nhìn cũng tối ngang nhau, chữ và dock luôn nổi.
     */
    private static int overlayFor(Bitmap b, int fallback) {
        int base = fallback;
        float lum = 0f;
        if (b != null) {
            int w = b.getWidth(), h = b.getHeight(), step = Math.max(1, Math.min(w, h) / 48);
            double r = 0, g = 0, bl = 0, wsum = 0, l = 0;
            int n = 0;
            float[] hsv = new float[3];
            for (int y = 0; y < h; y += step) {
                for (int x = 0; x < w; x += step) {
                    int c = b.getPixel(x, y);
                    Color.colorToHSV(c, hsv);
                    double wt = 0.15 + hsv[1] * hsv[2];      // ưu tiên vùng có màu, bớt trọng số vùng xám
                    r += Color.red(c) * wt; g += Color.green(c) * wt; bl += Color.blue(c) * wt; wsum += wt;
                    l += FrostView.glare(c);   // độ chói cảm nhận: ảnh đỏ/xanh rực cũng được phủ đủ
                    n++;
                }
            }
            if (wsum > 0) base = Color.rgb((int) (r / wsum), (int) (g / wsum), (int) (bl / wsum));
            if (n > 0) lum = (float) (l / n / 255.0);
        }
        float[] hsv = new float[3];
        Color.colorToHSV(base, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.15f);
        hsv[2] = Math.min(hsv[2], 0.32f);                  // sẫm lại để không làm nhạt ảnh
        // phủ vừa đủ kéo độ sáng trung bình về mức mục tiêu: sau phủ ~ lum * (1 - a) = BG_TARGET
        float a = lum <= 0f ? 0f : 1f - BG_TARGET / lum;
        a = Math.max(TINT_ALPHA / 255f, Math.min(TINT_MAX / 255f, a));
        return (Math.round(a * 255) << 24) | (Color.HSVToColor(hsv) & 0xFFFFFF);
    }

    private static final float BG_TARGET = 0.20f;   // độ sáng trung bình mong muốn của nền sau khi phủ
    private static final int TINT_MAX = 0xA3;        // ~64%, trần cho ảnh rất sáng

    // ---------- dữ liệu ----------

    // App (Netflix...) cập nhật hàng "Xem tiếp" vài giây SAU khi người xem thoát phim, lúc launcher đã đọc xong.
    // Nghe thay đổi của TvProvider suốt vòng đời launcher (kể cả lúc đang ở app khác) để không sót phim nào
    // và kịp ghi nhớ trước khi app tự đẩy phim cũ ra. Gom các thay đổi dồn dập lại, đọc một lần sau 1,5 s.
    private final Runnable shelfChanged = this::refreshShelf;
    private final android.database.ContentObserver tvObserver = new android.database.ContentObserver(ui) {
        @Override public void onChange(boolean self) {
            ui.removeCallbacks(shelfChanged);
            ui.postDelayed(shelfChanged, 1500);
        }
    };

    private void watchTvProvider(boolean on) {
        try {
            if (on) {
                getContentResolver().registerContentObserver(
                        android.net.Uri.parse("content://android.media.tv/watch_next_program"), true, tvObserver);
            } else {
                getContentResolver().unregisterContentObserver(tvObserver);
                ui.removeCallbacks(shelfChanged);
            }
        } catch (Exception ignored) {}
    }

    private void refreshShelf() {
        bg.execute(() -> {
            Map<String, List<Shelf.Item>> m = Shelf.load(this);
            ui.post(() -> {
                shelf = m;
                shelfVersion++;
                if (!inShelf) fillRow(apps.get(focused));   // đang đứng trong hàng: giữ nguyên, về dock mới đổ bản mới
                // nền chỉ đổi nếu tới lượt ảnh khác, không nháy khi quay về
                if (!inShelf) showBackground();
            });
        });
    }

    private List<Shelf.Item> itemsOf(Apps.App a) {
        if (a.isAllApps()) return recentApps();   // ô Apps: hàng là các app mở gần nhất
        if (a.tv) {
            // ô Truyền hình: kênh xem gần đây trước rồi các kênh đầu danh sách; ảnh kênh đã có tên kênh nên không ghi thêm chữ
            List<Shelf.Item> out = new ArrayList<>();
            for (Tv.Channel ch : Tv.recentFirst(this, Tv.load(this), ROW_N)) {
                Shelf.Item it = new Shelf.Item();
                it.pkg = a.pkg;
                it.art = ch.art == null ? null : android.net.Uri.fromFile(new File(ch.art)).toString();
                it.tvChannel = ch.name;
                out.add(it);
            }
            return out;
        }
        if (a.launchUri != null) {
            // ô game: ảnh bìa và ảnh lúc đang chơi; bấm ảnh nào cũng vào thẳng game
            List<Shelf.Item> out = new ArrayList<>();
            for (String path : a.thumbs) {
                Shelf.Item it = new Shelf.Item();
                it.pkg = a.pkg;
                it.art = android.net.Uri.fromFile(new File(path)).toString();
                it.gameUri = a.launchUri;
                it.gameActivity = a.launchActivity;
                out.add(it);
            }
            return out;
        }
        List<Shelf.Item> l = shelf.get(a.pkg);
        if (FptFeed.PKG.equals(a.pkg) && !FptFeed.items().isEmpty()) {
            // FPT Play: trận Ngoại hạng Anh đang đá / sắp đá (banner trận) trước nội dung app tự đăng
            List<Shelf.Item> out = timely(FptFeed.items());
            if (l != null) out.addAll(l);
            return out;
        }
        if (Tv360Feed.PKG.equals(a.pkg) && !Tv360Feed.items().isEmpty()) {
            // TV360: trận Cúp C1 đang đá / sắp đá, rồi các chương trình đang phát trực tiếp, trước nội dung app tự đăng
            List<Shelf.Item> out = timely(Tv360Feed.items());
            if (l != null) out.addAll(l);
            return out;
        }
        if (YtFeed.PKG.equals(a.pkg) && !YtFeed.items().isEmpty()) {
            // YouTube: video của các kênh chọn sẵn (đang live trước) rồi mới tới gợi ý của app
            List<Shelf.Item> out = new ArrayList<>(YtFeed.items());
            java.util.Set<String> titles = new java.util.HashSet<>();
            for (Shelf.Item it : out) titles.add(it.title);
            if (l != null) for (Shelf.Item it : l) if (!titles.contains(it.title)) out.add(it);
            return out;
        }
        return l == null ? Collections.emptyList() : l;
    }

    // ---------- kênh YouTube chọn sẵn ----------
    // 10 phút một lượt, kể cả khi vừa quay về màn hình chính (chưa đủ 10 phút từ lượt trước thì đợi nốt).
    // Chỉ chạy khi màn hình chính đang hiện; đang xem trước kênh truyền hình thì lùi lượt (khỏi tranh mạng, khỏi vẽ lại).
    private static final long YT_EVERY = 10 * 60_000;
    private long ytLastAt;
    private final java.util.concurrent.atomic.AtomicBoolean ytBusy = new java.util.concurrent.atomic.AtomicBoolean();
    private final Runnable ytTick = new Runnable() {
        @Override public void run() {
            long wait = ytLastAt + (anyLive() ? LIVE_EVERY : YT_EVERY) - android.os.SystemClock.elapsedRealtime();
            if (ytLastAt != 0 && wait > 0) { ui.postDelayed(this, wait); return; }
            if (thumbPrev != null) { ui.postDelayed(this, 30_000); return; }
            if (ytBusy.compareAndSet(false, true)) {
                ytLastAt = android.os.SystemClock.elapsedRealtime();
                String fav = favAbbr(), favName = favName();
                net.execute(() -> {
                    // hàng trận / trực tiếp trước (vài lượt gọi), xong là vẽ ngay; YouTube sau; lọc phim (nhiều trang) sau cùng
                    if (FptFeed.refresh(fav, ROW_MAX) | Tv360Feed.refresh(getApplicationContext(), favName, ROW_MAX)) feedsChanged();
                    if (YtFeed.refresh(getApplicationContext(), ROW_N)) feedsChanged();
                    if (FptFeed.refreshFilms()) feedsChanged();
                    ytBusy.set(false);
                });
            }
            // đang có trận / chương trình trực tiếp: xem lại dày hơn (trận xong, đổi trận) cho hàng khỏi lệch
            ui.postDelayed(this, anyLive() ? LIVE_EVERY : YT_EVERY);
        }
    };

    private static final long LIVE_EVERY = 3 * 60_000;
    private static final long FEED_KEEP_MS = 12 * 3600_000L;   // hàng đã lưu cũ hơn chừng này thì bỏ, đợi tải mới

    private File feedFile(String name) { return new File(getFilesDir(), "hang_" + name + ".json"); }

    private void restoreFeeds() {
        YtFeed.restore(Shelf.loadItems(feedFile("youtube"), FEED_KEEP_MS));
        FptFeed.restore(Shelf.loadItems(feedFile("fpt"), FEED_KEEP_MS));
        Tv360Feed.restore(Shelf.loadItems(feedFile("tv360"), FEED_KEEP_MS));
    }

    /** Một hàng vừa đổi (luồng nền): lưu cả ba hàng rồi vẽ lại. */
    private void feedsChanged() {
        Shelf.saveItems(feedFile("youtube"), YtFeed.items());
        Shelf.saveItems(feedFile("fpt"), FptFeed.items());
        Shelf.saveItems(feedFile("tv360"), Tv360Feed.items());
        ui.post(() -> {
            shelfVersion++;
            // đang đứng trong hàng thì không đổ lại dưới tay người dùng; lần ra dock / đổi app sẽ vẽ bản mới
            if (!inShelf) fillRow(apps.get(focused));
            scheduleFeedBoundary();
        });
    }

    /** Ô trận / chương trình theo giờ thật: quá giờ thì bỏ, tới giờ thì thành "trực tiếp" (khỏi đợi lượt tải). */
    private static List<Shelf.Item> timely(List<Shelf.Item> l) {
        long now = System.currentTimeMillis();
        List<Shelf.Item> out = new ArrayList<>(l.size());
        for (Shelf.Item it : l) {
            if (it.until > 0 && now > it.until) continue;
            if (it.startAt > 0 && now >= it.startAt) it.live = true;
            out.add(it);
        }
        return out;
    }

    private boolean anyLive() {
        long now = System.currentTimeMillis();
        for (List<Shelf.Item> l : java.util.Arrays.asList(FptFeed.items(), Tv360Feed.items(), YtFeed.items()))
            for (Shelf.Item it : l) if ((it.live || (it.startAt > 0 && now >= it.startAt)) && (it.until == 0 || now <= it.until)) return true;
        return false;
    }

    /** Mốc gần nhất một ô tới giờ hoặc hết giờ; tới mốc thì vẽ lại hàng (đang ở màn hình chính). */
    private final Runnable feedBoundary = this::redrawIfStale;

    private void scheduleFeedBoundary() {
        ui.removeCallbacks(feedBoundary);
        long now = System.currentTimeMillis(), next = Long.MAX_VALUE;
        for (List<Shelf.Item> l : java.util.Arrays.asList(FptFeed.items(), Tv360Feed.items()))
            for (Shelf.Item it : l) {
                if (it.startAt > now && !it.live) next = Math.min(next, it.startAt);
                if (it.until > now) next = Math.min(next, it.until);
            }
        if (next != Long.MAX_VALUE && resumedNow) ui.postDelayed(feedBoundary, next - now + 1000);
    }

    private void redrawIfStale() {
        long now = System.currentTimeMillis();
        boolean stale = false;
        for (Shelf.Item it : rowItems)
            if ((it.until > 0 && now > it.until) || (it.startAt > 0 && now >= it.startAt && !it.live)) { stale = true; break; }
        // chỉ vẽ lại khi hàng đang hiện thật sự lệch giờ (khỏi nháy hàng mỗi lần quay về)
        if (stale && !inShelf && apps != null && focused < apps.size()) { shelfVersion++; fillRow(apps.get(focused)); }
        scheduleFeedBoundary();
    }

    /** Mã 3 chữ của đội yêu thích (vd MUN) theo dữ liệu thẻ EPL; chưa có dữ liệu thì null. */
    private String favAbbr() {
        Epl.Data d = eplView == null ? null : eplView.data();
        if (d == null) return null;
        int code = Wallpapers.favTeam(this);
        for (Epl.Team t : d.teams.values()) if (t.code == code) return t.abbr;
        return null;
    }

    /** Tên ngắn của đội yêu thích (vd Man City) theo dữ liệu thẻ EPL; chưa có dữ liệu thì null. */
    private String favName() {
        Epl.Data d = eplView == null ? null : eplView.data();
        if (d == null) return null;
        int code = Wallpapers.favTeam(this);
        for (Epl.Team t : d.teams.values()) if (t.code == code) return t.name;
        return null;
    }

    // ---------- mở app ----------

    private void launch(Apps.App a, View src) {
        try {
            if (a.isAllApps()) { openAllApps(); return; }   // ô Apps: bảng tất cả ứng dụng (có Cửa hàng Play)
            if (a.tv) { openPanel(true); return; }           // ô Truyền hình: bảng kênh, cùng kiểu bảng Apps
            if (a.launchUri != null) { launchGame(a.pkg, a.launchUri, a.launchActivity, src); return; }
            launchPkg(a.pkg, src);
        } catch (Exception ignored) {}
    }

    /**
     * Ô game: mở thẳng file game bằng app giả lập (như lối tắt PPSSPP tự tạo). Đường dẫn file:// sang app khác bị
     * Android chặn mặc định (chống lộ file của chính mình); file game nằm trong thư mục của app giả lập, không phải
     * của Glass Home, nên tạm bỏ chặn đúng lúc gửi lệnh rồi đặt lại.
     */
    private void launchGame(String pkg, String uri, String activity, View src) {
        if (RETROARCH.equals(pkg) && launchRetroArch(uri, src)) return;
        // kiểu file bắt buộc: bộ lọc mở file của PPSSPP chỉ nhận lệnh có kèm kiểu (bất kỳ)
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(android.net.Uri.parse(uri), "application/octet-stream")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (activity != null) i.setClassName(pkg, activity); else i.setPackage(pkg);
        android.os.StrictMode.VmPolicy old = android.os.StrictMode.getVmPolicy();
        android.os.StrictMode.setVmPolicy(new android.os.StrictMode.VmPolicy.Builder().build());
        try { startActivity(i, zoomFrom(src)); }
        catch (Exception e) { launchPkg(pkg, src); }   // lệnh mở thẳng không được: mở app giả lập như thường
        finally { android.os.StrictMode.setVmPolicy(old); }
    }

    private static final String RETROARCH = "com.retroarch";

    /**
     * RetroArch (bản Cửa hàng Play) không nhận lệnh mở file: phải gọi thẳng màn chơi kèm đường dẫn game, core và file
     * cấu hình. Core đóng sẵn trong app (libsnes9x.so...), thư mục chứa đổi mỗi lần app cập nhật nên hỏi lại lúc mở.
     * Core chọn theo đuôi file game. Game phải nằm trong thư mục của RetroArch (Android/media/com.retroarch/...).
     */
    private boolean launchRetroArch(String uri, View src) {
        String path = android.net.Uri.parse(uri).getPath();
        if (path == null) return false;
        String ext = path.substring(path.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT);
        String core;
        switch (ext) {
            case "sfc": case "smc": core = "snes9x"; break;
            case "nes": core = "fceumm"; break;
            case "gba": core = "mgba"; break;
            case "gb": case "gbc": core = "gambatte"; break;
            case "md": case "gen": case "smd": core = "genesis_plus_gx"; break;
            default: return false;
        }
        try {
            String lib = getPackageManager().getApplicationInfo(RETROARCH, 0).nativeLibraryDir + "/lib" + core + ".so";
            Intent i = new Intent().setClassName(RETROARCH, "com.retroarch.browser.retroactivity.RetroActivityFuture")
                    .putExtra("ROM", path).putExtra("LIBRETRO", lib)
                    .putExtra("CONFIGFILE", "/storage/emulated/0/Android/data/com.retroarch/files/retroarch.cfg")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i, zoomFrom(src));
            return true;
        } catch (Exception e) { return false; }
    }

    private void launchPkg(String pkg, View src) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i != null) { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), zoomFrom(src)); rememberOpened(pkg); }
    }

    // ---------- app mở gần đây (hàng nội dung dưới ô Apps) ----------
    // Chỉ biết những app mở từ Glass Home (dock, bảng Apps, hàng nội dung); app mở bằng giọng nói hay nút trên
    // điều khiển thì không thấy (đọc lịch sử dùng app của cả máy cần quyền đặc biệt mà Google TV không cho bật).

    private void rememberGame(Apps.App g) { rememberOpened(g.key); }

    private void rememberOpened(String pkg) {
        if (pkg == null || pkg.equals(getPackageName())) return;
        List<String> l = new ArrayList<>(java.util.Arrays.asList(prefs().getString("recent_apps", "").split(",")));
        l.remove("");
        l.remove(pkg);
        l.add(0, pkg);
        while (l.size() > 12) l.remove(l.size() - 1);
        prefs().edit().putString("recent_apps", String.join(",", l)).apply();
        shelfVersion++;   // hàng của ô Apps đổi theo
    }

    /** Tối đa ROW_N app mở gần nhất còn cài trên máy: ảnh là ô vẽ sẵn nếu có, không thì banner TV của app. */
    private boolean onDock(String pkg) {
        if (apps != null) for (Apps.App a : apps) if (pkg.equals(a.pkg)) return true;
        return false;
    }

    private List<Shelf.Item> recentApps() {
        List<Shelf.Item> out = new ArrayList<>();
        PackageManager pm = getPackageManager();
        List<Apps.App> games = Apps.games(this);
        for (String pkg : prefs().getString("recent_apps", "").split(",")) {
            if (pkg.isEmpty() || out.size() >= ROW_N) continue;
            if (pkg.startsWith("game:")) {
                for (Apps.App g : games) if (pkg.equals(g.key)) {
                    Shelf.Item it = new Shelf.Item();
                    it.pkg = g.pkg;
                    it.title = g.label;
                    it.art = g.cardFile != null ? android.net.Uri.fromFile(new File(g.cardFile)).toString() : null;
                    it.gameUri = g.launchUri;
                    it.gameActivity = g.launchActivity;
                    if (it.art != null) out.add(it);
                }
                continue;
            }
            if (pm.getLeanbackLaunchIntentForPackage(pkg) == null && pm.getLaunchIntentForPackage(pkg) == null) continue;
            if (onDock(pkg)) continue;   // app đã có ô trên dock ngay bên trên: hiện lại ở hàng này thành trùng
            Shelf.Item it = new Shelf.Item();
            it.pkg = pkg;
            try { it.title = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); } catch (Exception e) { it.title = pkg; }
            String card = Apps.cardKey(pkg);
            it.art = card != null ? Wallpapers.ASSET + "cards/" + card + ".png" : Images.APP + pkg;
            out.add(it);
        }
        return out;
    }

    /** Mở đúng nội dung; không mở được thì mở app của nội dung đó (hàng có thể trộn nhiều app). */
    private void openItem(int i, View src) {
        List<Shelf.Item> items = rowItems;
        if (i >= items.size()) return;
        Shelf.Item it = items.get(i);
        if (it.tvChannel != null) { playChannel(Tv.find(this, it.tvChannel), src); return; }
        if (it.gameUri != null) {
            launchGame(it.pkg, it.gameUri, it.gameActivity, src);
            for (Apps.App g : Apps.games(this)) if (it.gameUri.equals(g.launchUri)) rememberGame(g);
            return;
        }
        try {
            if (it.intent != null) {
                startActivity(Intent.parseUri(it.intent, Intent.URI_INTENT_SCHEME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), zoomFrom(src));
                rememberOpened(it.pkg);
                return;
            }
        } catch (Exception ignored) {}
        launchPkg(it.pkg, src);
    }

    // ---------- có mạng trở lại ----------
    // Lúc box vừa khởi động, launcher chạy trước khi có mạng: lần tải đầu hỏng, rồi phải đợi lượt sau (giãn dần,
    // có khi rất lâu). Hệ thống báo có mạng thì tải lại ngay những gì còn thiếu.

    private android.net.ConnectivityManager.NetworkCallback netCb;

    private void watchNetwork(boolean on) {
        android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
        if (cm == null) return;
        try {
            if (on && netCb == null) {
                netCb = new android.net.ConnectivityManager.NetworkCallback() {
                    @Override public void onAvailable(android.net.Network n) { ui.postDelayed(MainActivity.this::onNetworkBack, 1500); }
                };
                cm.registerDefaultNetworkCallback(netCb);
            } else if (!on && netCb != null) {
                cm.unregisterNetworkCallback(netCb);
                netCb = null;
            }
        } catch (Exception ignored) {}
    }

    private void onNetworkBack() {
        if (isFinishing() || !shown()) return;   // đang xem app khác: lúc quay về màn hình chính sẽ tự tải
        if (Wallpapers.widget(this, "epl") && (eplView.data() == null || !eplView.hasTable() || eplFailMs > 0)) {
            eplFailMs = 0;
            ui.removeCallbacks(eplTick);
            ui.post(eplTick);
        }
        if (Wallpapers.widget(this, "gold") && goldOkAt == 0) { ui.removeCallbacks(goldTick); ui.post(goldTick); }
    }

    // ---------- nhắc đặt Glass Home làm màn hình chính ----------
    // Google TV không có chỗ chọn launcher. Máy chưa đặt bằng máy tính (adb) thì nhắc bật dịch vụ HomeGuard trong
    // Hỗ trợ tiếp cận: bật tivi và bấm Home sẽ vào Glass Home. "Để sau" thì thôi nhắc tới lần khởi động lại app.

    private FrameLayout homePrompt;
    private static boolean homePromptLater;
    private final Runnable showHomePrompt = this::showHomePrompt;

    private boolean needsHomePrompt() {
        return !homePromptLater && homePrompt == null && settings == null && allApps == null
                && !HomeGuard.isSystemHome(this) && !HomeGuard.enabled(this) && !justUpdated();
    }

    /**
     * Vừa cài bản mới (adb install -r): hệ thống gỡ rồi nối lại dịch vụ trợ năng của app, có lúc launcher mở lên đúng
     * khoảng giữa và tưởng dịch vụ bị tắt. Trong 5 phút sau khi cập nhật thì không hỏi; tắt thật thì lần sau mới hỏi.
     */
    private boolean justUpdated() {
        try {
            long t = getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime;
            return System.currentTimeMillis() - t < 5 * 60_000;
        } catch (Exception e) { return false; }
    }

    private void showHomePrompt() {
        if (!needsHomePrompt() || saverOn || !shown()) return;
        int sw = getResources().getDisplayMetrics().widthPixels, sh = getResources().getDisplayMetrics().heightPixels;
        FrameLayout box = new FrameLayout(this);
        View shade = new View(this);
        shade.setBackgroundColor(0xA6000000);
        box.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        int cw = 920, ch = 470, cx = (1920 - cw) / 2, cy = (1080 - ch) / 2;
        FrameLayout card = new FrameLayout(this);
        FrostView glass = new FrostView(this, px(36), sw, sh, px(cx), px(cy), Math.max(1, px(1))).deep();
        if (lastBlur != null) glass.setImage(lastBlur);
        card.addView(glass, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(64), px(56), px(64), px(48));
        TextView t = text(600, 38, 0xF0F2EEE6);
        t.setText("Đặt Glass Home làm màn hình chính");
        col.addView(t);
        TextView b = text(400, 24, 0xB3F2EEE6);
        b.setText("Bật một lần trong Cài đặt → Hỗ trợ tiếp cận → Glass Home: màn hình chính. "
                + "Từ đó bật tivi hay bấm nút Home đều vào thẳng Glass Home.");
        b.setLineSpacing(px(8), 1f);
        LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(-1, -2);
        lb.topMargin = px(22);
        col.addView(b, lb);
        TextView n = text(400, 20, 0x80F2EEE6);
        n.setText("Android sẽ cảnh báo dịch vụ có thể theo dõi thao tác. Glass Home chỉ nghe nút Home và biết màn hình gốc có đang hiện không.");
        n.setLineSpacing(px(6), 1f);
        LinearLayout.LayoutParams ln = new LinearLayout.LayoutParams(-1, -2);
        ln.topMargin = px(18);
        col.addView(n, ln);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView open = promptButton("Mở cài đặt", v -> {
            closeHomePrompt(false);
            try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); } catch (Exception ignored) {}
        });
        TextView later = promptButton("Để sau", v -> closeHomePrompt(true));
        LinearLayout.LayoutParams lo = new LinearLayout.LayoutParams(px(300), px(64));
        row.addView(open, lo);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(px(220), px(64));
        ll.leftMargin = px(20);
        row.addView(later, ll);
        LinearLayout.LayoutParams lr = new LinearLayout.LayoutParams(-2, -2);
        lr.topMargin = px(34);
        col.addView(row, lr);
        card.addView(col, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams lc = new FrameLayout.LayoutParams(px(cw), px(ch), Gravity.TOP | Gravity.START);
        lc.leftMargin = px(cx); lc.topMargin = px(cy);
        box.addView(card, lc);

        tray.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        shelfRow.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        gear.setFocusable(false); clock.setFocusable(false);
        dimGlow(true);
        box.setAlpha(0f);
        card.setScaleX(0.96f); card.setScaleY(0.96f);
        root.addView(box, new FrameLayout.LayoutParams(-1, -1));
        homePrompt = box;
        box.animate().alpha(1f).setDuration(400).setInterpolator(EASE).start();
        card.animate().scaleX(1f).scaleY(1f).setDuration(480).setInterpolator(EASE).withLayer().start();
        open.requestFocus();
    }

    /** Nút trong hộp nhắc: nền kính mờ, được chọn thì nền trắng chữ tối như nút Apple TV. */
    private TextView promptButton(String s, View.OnClickListener click) {
        TextView b = text(500, 24, 0xE6F2EEE6);
        b.setText(s);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnClickListener(click);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(16));
        bg.setColor(0x1FFFFFFF);
        b.setBackground(bg);
        b.setOnFocusChangeListener((v, has) -> {
            ValueAnimator a = ValueAnimator.ofArgb(has ? 0x1FFFFFFF : 0xEBF2EEE6, has ? 0xEBF2EEE6 : 0x1FFFFFFF);
            a.setDuration(320);
            a.setInterpolator(EASE);
            a.addUpdateListener(an -> bg.setColor((int) an.getAnimatedValue()));
            a.start();
            b.setTextColor(has ? 0xFF1C1C1E : 0xE6F2EEE6);
            b.animate().scaleX(has ? 1.04f : 1f).scaleY(has ? 1.04f : 1f).setDuration(320).setInterpolator(EASE).start();
        });
        return b;
    }

    private void closeHomePrompt(boolean later) {
        if (homePrompt == null) return;
        if (later) homePromptLater = true;
        View v = homePrompt;
        homePrompt = null;
        v.animate().alpha(0f).setDuration(320).setInterpolator(EASE).withEndAction(() -> root.removeView(v)).start();
        tray.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        shelfRow.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        gear.setFocusable(true); clock.setFocusable(true);
        cards.get(focused).requestFocus();
    }

    // ---------- bảng Cài đặt ----------

    private SettingsPanel settings;
    private Bitmap lastBlur;   // ảnh nền đã làm nhoè: kính của bảng Cài đặt dùng chung

    private void openSettings() {
        if (settings != null) return;
        tray.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        shelfRow.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        gear.setFocusable(false); clock.setFocusable(false);
        dimGlow(true);
        if (eplBox != null) eplBox.animate().alpha(0f).setDuration(320).start();   // bảng nằm đúng chỗ thẻ lịch
        settings = new SettingsPanel(this, new SettingsPanel.Host() {
            @Override public TextView text(int weight, float size, int color) { return MainActivity.this.text(weight, size, color); }
            @Override public int px(float v) { return MainActivity.this.px(v); }
            @Override public Images images() { return images; }
            @Override public Bitmap blur() { return lastBlur; }
            @Override public List<Epl.Team> teams() {
                Epl.Data d = eplView.data();
                if (d == null) return null;
                List<Epl.Team> ts = new ArrayList<>(d.teams.values());
                Collections.sort(ts, (x, y) -> x.name.compareToIgnoreCase(y.name));
                return ts;
            }
            @Override public void changed(String what) { settingsChanged(what); }
            @Override public void openSystemSettings() { MainActivity.this.openSystemSettings(); }
            @Override public void closed() { onSettingsClosed(); }
        });
        root.addView(settings, new FrameLayout.LayoutParams(-1, -1));
    }

    private void onSettingsClosed() {
        settings = null;
        tray.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        shelfRow.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        gear.setFocusable(true); clock.setFocusable(true);
        if (eplBox != null) eplBox.animate().alpha(eplShelfAlpha()).setDuration(420).start();
        syncRowWithEpl();
        gear.requestFocus();   // đóng bảng: con trỏ về nút bánh răng như lúc mở
    }

    private void openSystemSettings() {
        if (settings != null) settings.close(false);
        startActivity(new Intent(Settings.ACTION_SETTINGS), zoomFrom(gear));
    }

    private void settingsChanged(String what) {
        switch (what) {
            case Wallpapers.HOME:
                bgShown = null;
                showBackground();
                ui.removeCallbacks(bgRotate); ui.postDelayed(bgRotate, bgRotateMs());
                break;
            case "saver": armSaver(); break;
            case "widgets": applyWidgets(); break;
            case "team": eplView.setFavorite(Wallpapers.favTeam(this)); break;
            default: break;
        }
    }

    private static final String[] TICK_KEYS = {"btc", "eth", "gold"};   // cùng thứ tự với cột giá
    private final float[] tickShift = new float[TICKS];
    private final ValueAnimator[] tickMove = new ValueAnimator[TICKS];

    /**
     * Ẩn/hiện từng ô giá và thẻ lịch theo Cài đặt; ô nào ẩn thì các ô dưới trượt lên lấp chỗ (giữ thứ tự
     * BTC, ETH, vàng). Không còn ô nào cần thì không gọi mạng.
     */
    private void applyWidgets() {
        boolean epl = Wallpapers.widget(this, "epl");
        int slot = 0;
        for (int i = 0; i < TICKS; i++) {
            boolean on = Wallpapers.widget(this, TICK_KEYS[i]);
            boolean wasHidden = tickTile[i].getVisibility() != View.VISIBLE;
            float dy = px((slot - i) * (TICK_H + TICK_GAP));
            if (on) slot++;
            // ô đang ẩn hiện ra: đặt sẵn ở chỗ mới rồi mới hiện dần, không trượt từ chỗ cũ
            if (on) moveTick(i, dy, !wasHidden);
            fadeVisible(tickTile[i], on, 1f);
            if (on) tickLine[i].setVisibility(slot == 1 ? View.INVISIBLE : View.VISIBLE);   // không vạch ở dòng đầu
        }
        boolean firstLayout = tickCard.getVisibility() != View.VISIBLE;
        if (slot > 0) sizeTickCard(slot, !firstLayout);
        fadeVisible(tickCard, slot > 0, 1f);
        boolean coins = Wallpapers.widget(this, "btc") || Wallpapers.widget(this, "eth");
        ui.removeCallbacks(btcTick); ui.removeCallbacks(goldTick);
        if (coins) ui.post(btcTick);
        if (Wallpapers.widget(this, "gold")) ui.post(goldTick);
        ui.removeCallbacks(eplTick);
        if (epl) {
            if (eplView.data() != null) fadeVisible(eplBox, true, settings != null ? 0f : eplShelfAlpha());
            ui.post(eplTick);
        } else fadeVisible(eplBox, false, 0f);
        syncRowWithEpl();
    }

    /**
     * Dời dòng giá i lên/xuống dy trong thẻ kính chung (lấp chỗ dòng bị tắt); thẻ tự co theo số dòng.
     */
    private void moveTick(int i, float dy, boolean animate) {
        if (tickMove[i] != null) tickMove[i].cancel();
        if (!animate || tickShift[i] == dy) { setTickShift(i, dy); return; }
        tickMove[i] = ValueAnimator.ofFloat(tickShift[i], dy);
        tickMove[i].setDuration(420);
        tickMove[i].setInterpolator(EASE);
        tickMove[i].addUpdateListener(a -> setTickShift(i, (float) a.getAnimatedValue()));
        tickMove[i].start();
    }

    private void setTickShift(int i, float dy) {
        tickShift[i] = dy;
        tickTile[i].setTranslationY(dy);   // dòng nằm trong thẻ kính chung: chỉ dời chữ, kính không đổi
    }

    private static void fadeVisible(View v, boolean on, float alpha) {
        if (v == null) return;
        v.animate().cancel();
        if (on) {
            if (v.getVisibility() != View.VISIBLE) { v.setAlpha(0f); v.setVisibility(View.VISIBLE); }
            v.animate().alpha(alpha).setDuration(420).setInterpolator(EASE).start();
        } else if (v.getVisibility() == View.VISIBLE) {
            v.animate().alpha(0f).setDuration(380).setInterpolator(EASE).withEndAction(() -> v.setVisibility(View.GONE)).start();
        }
    }

    // ---------- tất cả ứng dụng ----------

    private void openAllApps() { openPanel(false); }

    /** Bảng phủ cả màn hình: tv = bảng kênh truyền hình (nhóm theo group-title), không thì bảng Trò chơi + Ứng dụng. */
    private void openPanel(boolean tv) {
        if (allApps != null) return;
        stopThumbPreview(false);   // một phiên phát mỗi lúc: bảng kênh có khung xem trước riêng
        allApps = new FrameLayout(this);
        allApps.setBackgroundColor(0xF0050507);
        appsArtFor = null;
        allApps.setAlpha(0f);
        // ScrollView không tự cuộn khi đổi ô (nó cuộn giật, tức thì); bảng tự cuộn bằng hiệu ứng mềm ở dưới
        ScrollView sv = new ScrollView(this) {
            @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) { return 0; }
        };
        sv.setClipChildren(false);
        sv.setClipToPadding(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(110), px(70), px(110), px(70));
        col.setClipChildren(false);
        col.setClipToPadding(false);   // quầng sáng của ô cột trái tràn vào lề trái, không bị cắt
        View first = null;
        if (tv) first = fillChannels(col);
        List<Apps.App> games = tv ? Collections.emptyList() : Apps.games(this);
        if (!games.isEmpty()) {
            TextView gh = text(600, 40, 0xFFFFFFFF);
            gh.setText("Trò chơi");
            col.addView(gh);
            GridLayout gg = new GridLayout(this);
            gg.setColumnCount(5);
            gg.setClipChildren(false);
            LinearLayout.LayoutParams lgg = new LinearLayout.LayoutParams(-2, -2);
            lgg.topMargin = px(40);
            lgg.bottomMargin = px(30);
            col.addView(gg, lgg);
            for (Apps.App g : games) {
                FrameLayout tile = gameTile(g);
                GridLayout.LayoutParams gl = new GridLayout.LayoutParams();
                gl.width = px(300); gl.height = px(220 + GAME_TILE_H - APP_TILE_H);
                gl.setMargins(px(10), px(10), px(26), px(26));
                gg.addView(tile, gl);
                if (first == null) first = tile;
            }
        }
        if (!tv) first = fillAppsGrid(col, first);
        sv.addView(col);
        finishPanel(sv, col, first);
    }

    /** Phần "Ứng dụng" của bảng Apps: mọi app có giao diện TV, xếp theo tên. */
    private View fillAppsGrid(LinearLayout col, View first) {
        TextView h = text(600, 40, 0xFFFFFFFF);
        h.setText("Ứng dụng");
        col.addView(h);
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(5);
        grid.setClipChildren(false);
        LinearLayout.LayoutParams lgp = new LinearLayout.LayoutParams(-2, -2);
        lgp.topMargin = px(40);
        col.addView(grid, lgp);

        PackageManager pm = getPackageManager();
        List<ResolveInfo> ris = pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.LEANBACK_LAUNCHER"), 0);
        Collections.sort(ris, (x, y) -> String.valueOf(x.loadLabel(pm)).compareToIgnoreCase(String.valueOf(y.loadLabel(pm))));
        for (ResolveInfo ri : ris) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || pkg.startsWith("vn.glass.")) continue;
            FrameLayout tile = appTile(pm, ri);
            GridLayout.LayoutParams gl = new GridLayout.LayoutParams();
            gl.width = px(300); gl.height = px(220);
            gl.setMargins(px(10), px(10), px(26), px(26));
            grid.addView(tile, gl);
            if (first == null) first = tile;
        }
        return first;
    }

    private void finishPanel(ScrollView sv, LinearLayout col, View first) {
        // cuộn theo ô đang chọn: lên tới hàng đầu của một nhóm thì kéo hết lên để thấy cả tiêu đề nhóm (Trò chơi,
        // Ứng dụng); ô khác thì luôn chừa khoảng thoáng phía trên, không để ô dính sát mép màn hình
        sv.setSmoothScrollingEnabled(true);
        // nghe ở cửa sổ (root) và gỡ ra khi đóng bảng: để lại thì cửa sổ giữ cả bảng cũ (mọi ảnh ô, quầng sáng)
        // sau mỗi lần mở, launcher phình thêm ~18MB một lần
        appsFocusWatch = (o, n) -> {
            if (n == null || !(n.getParent() instanceof GridLayout) || n.getParent().getParent() != col) return;
            View g = (View) n.getParent();
            int idx = col.indexOfChild(g);
            View head = idx > 0 ? col.getChildAt(idx - 1) : null;
            int tileTop = g.getTop() + n.getTop();
            int target;
            // hàng đầu của nhóm: kéo lên thấy cả tiêu đề; nhóm đầu tiên thì về đúng như lúc mới mở bảng
            if (n.getTop() < px(20) && head != null) target = idx <= 1 ? 0 : head.getTop() - px(70);
            else {
                // ô lọt ra ngoài phía trên hoặc phía dưới: cuộn vừa đủ, chừa khoảng thoáng ~150px; còn trong khung thì đứng yên
                int bottom = tileTop + n.getHeight();
                target = sv.getScrollY();
                if (tileTop - target < px(150)) target = tileTop - px(150);
                else if (bottom - target > sv.getHeight() - px(120)) target = bottom - sv.getHeight() + px(120);
            }
            int t = Math.max(0, target);
            sv.post(() -> scrollAppsTo(sv, t));
        };
        root.getViewTreeObserver().addOnGlobalFocusChangeListener(appsFocusWatch);
        // nền sau bảng: chọn ô game thì hiện ảnh lớn của game (như PS4), ô khác thì tối như cũ
        appsArt = fullImage();
        appsArt.setAlpha(0f);
        allApps.addView(appsArt);
        View shade = new View(this);
        shade.setBackgroundColor(0x8C050507);
        allApps.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        allApps.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        if (chCard != null) {   // bảng kênh: ô thông tin đứng yên bên phải, không cuộn theo lưới
            FrameLayout.LayoutParams lc = new FrameLayout.LayoutParams(px(CH_CARD_W), -1, Gravity.TOP | Gravity.END);
            lc.topMargin = px(60);
            lc.bottomMargin = px(60);
            lc.rightMargin = px(60);
            allApps.addView(chCard, lc);
            ui.removeCallbacks(chTick);
            ui.postDelayed(chTick, 30_000);
        }
        root.addView(allApps, new FrameLayout.LayoutParams(-1, -1));
        // khoá dock + hàng nội dung phía sau để con trỏ không lọt ra ngoài bảng; tắt quầng "thở"
        tray.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        shelfRow.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        gear.setFocusable(false); clock.setFocusable(false);
        dimGlow(true);
        // bảng đã che kín: tạm ẩn màn Home bên dưới (ảnh nền, kính, dock) để mỗi khung khỏi vẽ thừa cả màn phía sau
        View panel = allApps;
        allApps.animate().alpha(1f).setDuration(340).withEndAction(() -> {
            if (allApps != panel) return;
            for (int i = 0; i < root.getChildCount(); i++) {
                View c = root.getChildAt(i);
                if (c != panel && c.getVisibility() == View.VISIBLE) { c.setVisibility(View.INVISIBLE); hiddenUnderPanel.add(c); }
            }
        }).start();
        if (first != null) {
            first.requestFocus();
            // ô chọn sẵn (kênh vừa xem) có thể nằm sâu bên dưới: khi lưới đã đo xong vị trí thì nhảy thẳng tới đó
            View f0 = first;
            sv.post(() -> {
                if (appsFocusWatch == null || !f0.hasFocus()) return;
                appsFocusWatch.onGlobalFocusChanged(null, f0);
                if (appsScroll != null) appsScroll.end();
            });
        }
    }

    private ValueAnimator appsScroll;
    private final List<View> hiddenUnderPanel = new ArrayList<>();
    private android.view.ViewTreeObserver.OnGlobalFocusChangeListener appsFocusWatch;

    /** Cuộn bảng Apps mềm (cùng đường cong EASE với mọi chuyển động của launcher), cuộn tiếp từ vị trí đang dở. */
    private void scrollAppsTo(ScrollView sv, int y) {
        int max = Math.max(0, sv.getChildAt(0).getHeight() - sv.getHeight());
        int to = Math.max(0, Math.min(max, y)), from = sv.getScrollY();
        if (appsScroll != null) appsScroll.cancel();
        if (to == from) return;
        appsScroll = ValueAnimator.ofInt(from, to);
        appsScroll.setDuration(Math.min(520, 300 + Math.abs(to - from) / 4));
        appsScroll.setInterpolator(EASE);
        appsScroll.addUpdateListener(a -> sv.scrollTo(0, (int) a.getAnimatedValue()));
        appsScroll.start();
    }

    private ImageView appsArt;
    private String appsArtFor;

    /** Nền sau bảng Apps theo ô đang chọn: ảnh game hiện dần, rời ô game thì mờ đi. */
    private void appsBackdrop(String bgFile) {
        if (appsArt == null) return;
        if (bgFile == null) { appsArtFor = null; appsArt.animate().alpha(0f).setDuration(420).start(); return; }
        if (bgFile.equals(appsArtFor)) return;
        appsArtFor = bgFile;
        ImageView target = appsArt;
        images.load(android.net.Uri.fromFile(new File(bgFile)).toString(), bgWidth(), bmp -> {
            if (!bgFile.equals(appsArtFor) || bmp == null || target != appsArt) return;
            target.setImageBitmap(bmp);
            target.animate().alpha(1f).setDuration(560).setInterpolator(EASE).start();
        });
    }

    /** Ô game trong bảng Apps: ảnh ô của game (logo), tên bên dưới; bấm là vào thẳng game. */
    private FrameLayout gameTile(Apps.App g) {
        FrameLayout t = new FrameLayout(this);
        t.setFocusable(true);
        t.setClipChildren(false);
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_XY);
        // ảnh ô game cùng tỉ lệ 1,6 với ô dock (464x290): giữ nguyên tỉ lệ thì góc bo trong ảnh (12% chiều cao)
        // không bị kéo méo, quầng sáng vẽ đúng độ bo đó nên ôm khít cả 4 góc
        Bitmap card = g.cardFile == null ? null : BitmapFactory.decodeFile(g.cardFile);
        if (card != null) img.setImageBitmap(scaleHQ(card, px(APP_TILE_W), px(GAME_TILE_H)));
        shadowShape(img, px(GAME_TILE_H * 0.12f));
        t.addView(img, new FrameLayout.LayoutParams(-1, px(GAME_TILE_H)));
        TextView name = text(500, 22, 0xD9FFFFFF);
        name.setText(g.label);
        name.setSingleLine(true);
        t.addView(name, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        appsTileFocus(t, img, APP_TILE_W, GAME_TILE_H, GAME_TILE_H * 0.12f, () -> appsBackdrop(g.bgFile));
        t.setOnClickListener(v -> { launchGame(g.pkg, g.launchUri, g.launchActivity, v); rememberGame(g); closeAllApps(); });
        return t;
    }

    // ô kênh nhỏ hơn ô app, không ghi tên dưới ô (logo đã là tên kênh); 4 cột bên trái, bên phải là ô thông tin kênh
    private static final int CH_TILE_W = 250, CH_TILE_H = 141, CH_TILE_R = 12, CH_COLS = 4, CH_CARD_W = 592;

    /** Nhóm trên bảng kênh: đúng group-title trong kenh.m3u (Quốc gia, HTV, Địa phương, Quốc tế...), thiếu thì "Kênh". */
    private static String panelGroup(String g) { return g == null || g.isEmpty() ? "Kênh" : g; }

    /** Bảng kênh: mỗi nhóm một tiêu đề + lưới 6 cột ô kênh 16:9, nhóm theo thứ tự trong danh sách; trả về ô cần chọn sẵn. */
    private View fillChannels(LinearLayout col) {
        View first = null, last = null;
        String lastName = Tv.lastWatched(this);
        Map<String, GridLayout> groups = new java.util.LinkedHashMap<>();
        for (Tv.Channel ch : Tv.load(this)) {
            String g = panelGroup(ch.group);
            GridLayout grid = groups.get(g);
            if (grid == null) {
                TextView h = text(600, 40, 0xFFFFFFFF);
                h.setText(g);
                col.addView(h);
                grid = new GridLayout(this);
                grid.setColumnCount(CH_COLS);
                grid.setClipChildren(false);
                LinearLayout.LayoutParams lg = new LinearLayout.LayoutParams(-2, -2);
                lg.topMargin = px(30);
                lg.bottomMargin = px(24);
                col.addView(grid, lg);
                groups.put(g, grid);
            }
            FrameLayout tile = channelTile(ch);
            GridLayout.LayoutParams gl = new GridLayout.LayoutParams();
            gl.width = px(CH_TILE_W); gl.height = px(CH_TILE_H);
            gl.setMargins(px(10), px(10), px(18), px(22));
            grid.addView(tile, gl);
            if (first == null) first = tile;
            if (ch.name.equals(lastName)) last = tile;
        }
        buildChannelCard();
        return last != null ? last : first;   // chọn sẵn kênh vừa xem
    }

    // ---------- cột thông tin kênh (bên phải bảng kênh) ----------
    // Cột kính cao gần hết màn: đầu cột là logo nhỏ + tên kênh; rồi chương trình đang chiếu (chữ to nhất),
    // giờ chiếu, "còn bao lâu", thanh tiến độ màu kênh; dòng thời gian các chương trình kế; mô tả kênh ở đáy.
    // Đứng yên trên một kênh 0,7 giây thì logo nở thành khung xem trước (TvPreview), tiếng to dần; rời kênh thì tiếng nhỏ
    // dần, khung thu về logo. Mọi chuyển động chỉ đổi scale/translation/alpha (không dựng lại bố cục): mượt trên box yếu.

    private static final int CH_PAD = 44, CH_LOGO_W = 136, CH_LOGO_H = 77, CH_HEAD_GAP = 52, CH_NAME_ROW = 64;
    private static final long CH_DWELL_MS = 700;

    private FrameLayout chCard, chFrame, chBodyWrap;
    private LinearLayout chHead, chBody, chList;
    private ImageView chArt, chFrameArt;
    private android.view.SurfaceView chVideo;
    private android.widget.ProgressBar chSpin;
    private View chFrameOverlay, chKnob;
    private VolBars chVol;
    private TextView chName, chGroup, chName2, chGroup2, chNowLabel, chNowTitle, chNowTime, chRemain, chNextHead, chDesc, chHint;
    private android.graphics.drawable.ClipDrawable chBarClip;
    private FrameLayout chBar;
    private Tv.Channel chShown, chPreviewing;
    private TvPreview chPrev;
    private android.view.Surface chSurface;
    private boolean chExpanded;
    private float chBarFrac = -1f;
    private ValueAnimator chBarAnim;

    private final Runnable chTick = new Runnable() {
        @Override public void run() {
            if (chCard == null) return;
            if (chShown != null) fillChannelCard(chShown, false);
            ui.postDelayed(this, 30_000);
        }
    };
    private final Runnable chDwell = () -> { if (chShown != null) startPreview(chShown); };

    private int chInner() { return CH_CARD_W - CH_PAD * 2; }
    private int chFrameH() { return Math.round(chInner() * 9f / 16f); }
    private float chCompactY() { return px(CH_LOGO_H + CH_HEAD_GAP - CH_NAME_ROW); }
    private float chExpandedY() { return px(chFrameH() + 28); }

    private void buildChannelCard() {
        chCard = new FrameLayout(this);
        chCard.setClipChildren(true);
        GradientDrawable bgd = new GradientDrawable();
        bgd.setColor(0x9E0C1210);
        bgd.setCornerRadius(px(28));
        bgd.setStroke(px(2), 0x22FFFFFF);
        chCard.setBackground(bgd);
        int inner = chInner();

        // đầu cột (lúc chưa xem trước): logo nhỏ, tên + nhóm
        chHead = new LinearLayout(this);
        chHead.setOrientation(LinearLayout.HORIZONTAL);
        chHead.setGravity(Gravity.CENTER_VERTICAL);
        chArt = new ImageView(this);
        chArt.setScaleType(ImageView.ScaleType.FIT_XY);
        roundClip(chArt, px(12));
        chHead.addView(chArt, new LinearLayout.LayoutParams(px(CH_LOGO_W), px(CH_LOGO_H)));
        LinearLayout nameCol = new LinearLayout(this);
        nameCol.setOrientation(LinearLayout.VERTICAL);
        chName = text(700, 40, 0xFFFFFFFF);
        chName.setSingleLine(true);
        chName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nameCol.addView(chName);
        chGroup = text(450, 21, 0x96FFFFFF);
        chGroup.setSingleLine(true);
        nameCol.addView(chGroup, lp(-2, -2, 8));
        LinearLayout.LayoutParams ln = new LinearLayout.LayoutParams(0, -2, 1f);
        ln.leftMargin = px(24);
        chHead.addView(nameCol, ln);
        FrameLayout.LayoutParams lh = new FrameLayout.LayoutParams(inner, px(CH_LOGO_H), Gravity.TOP | Gravity.START);
        lh.leftMargin = px(CH_PAD); lh.topMargin = px(CH_PAD);
        chCard.addView(chHead, lh);

        // thân cột: hàng tên (chỉ hiện khi đang xem trước) + đang chiếu + tiến độ + tiếp theo; trượt xuống khi khung nở
        chBodyWrap = new FrameLayout(this);
        chBody = new LinearLayout(this);
        chBody.setOrientation(LinearLayout.VERTICAL);
        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setBaselineAligned(true);
        chName2 = text(700, 36, 0xFFFFFFFF);
        chName2.setSingleLine(true);
        nameRow.addView(chName2);
        chGroup2 = text(450, 21, 0x96FFFFFF);
        chGroup2.setSingleLine(true);
        LinearLayout.LayoutParams lg2 = new LinearLayout.LayoutParams(-2, -2);
        lg2.leftMargin = px(16);
        nameRow.addView(chGroup2, lg2);
        nameRow.setAlpha(0f);
        chBody.addView(nameRow, new LinearLayout.LayoutParams(-1, px(CH_NAME_ROW)));
        chNowLabel = label("ĐANG CHIẾU", 0xFF5FD6A0);
        chBody.addView(chNowLabel);
        chNowTitle = text(700, 40, 0xFFFFFFFF);
        chNowTitle.setMaxLines(2);
        chNowTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        chNowTitle.setLineSpacing(px(6), 1f);
        chBody.addView(chNowTitle, lp(-1, -2, 12));
        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        chNowTime = text(500, 24, 0x96FFFFFF);
        timeRow.addView(chNowTime, new LinearLayout.LayoutParams(0, -2, 1f));
        chRemain = text(600, 24, 0xFFFFFFFF);
        timeRow.addView(chRemain);
        chBody.addView(timeRow, lp(-1, -2, 16));
        // thanh tiến độ: nền mờ + phần đã chiếu (ClipDrawable, đổi level không phải dựng lại bố cục) + chấm sáng đầu thanh
        chBar = new FrameLayout(this);
        View track = new View(this);
        GradientDrawable tr = new GradientDrawable();
        tr.setColor(0x28FFFFFF);
        tr.setCornerRadius(px(4));
        track.setBackground(tr);
        chBar.addView(track, new FrameLayout.LayoutParams(-1, px(8), Gravity.CENTER_VERTICAL));
        View fill = new View(this);
        chBarClip = new android.graphics.drawable.ClipDrawable(barFill(0xFF5FD6A0), Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL);
        fill.setBackground(chBarClip);
        chBar.addView(fill, new FrameLayout.LayoutParams(-1, px(8), Gravity.CENTER_VERTICAL));
        chKnob = new View(this);
        GradientDrawable kn = new GradientDrawable();
        kn.setShape(GradientDrawable.OVAL);
        kn.setColor(0xFFFFFFFF);
        chKnob.setBackground(kn);
        chBar.addView(chKnob, new FrameLayout.LayoutParams(px(18), px(18), Gravity.CENTER_VERTICAL));
        chBody.addView(chBar, lp(-1, px(18), 18));
        chNextHead = label("TIẾP THEO", 0x69FFFFFF);
        chBody.addView(chNextHead, lp(-2, -2, 44));
        chList = new LinearLayout(this);
        chList.setOrientation(LinearLayout.VERTICAL);
        chBody.addView(chList, lp(-1, -2, 18));
        chBodyWrap.addView(chBody, new FrameLayout.LayoutParams(-1, -2));
        FrameLayout.LayoutParams lb = new FrameLayout.LayoutParams(inner, -2, Gravity.TOP | Gravity.START);
        lb.leftMargin = px(CH_PAD); lb.topMargin = px(CH_PAD);
        chBodyWrap.setTranslationY(chCompactY());
        chCard.addView(chBodyWrap, lb);

        // mô tả kênh ở đáy cột (ẩn khi đang xem trước) + gợi ý phím
        chDesc = text(420, 21, 0x69FFFFFF);
        chDesc.setMaxLines(3);
        chDesc.setEllipsize(android.text.TextUtils.TruncateAt.END);
        chDesc.setLineSpacing(px(8), 1f);
        View line = new View(this);
        line.setBackgroundColor(0x1CFFFFFF);
        LinearLayout descBox = new LinearLayout(this);
        descBox.setOrientation(LinearLayout.VERTICAL);
        descBox.addView(line, new LinearLayout.LayoutParams(-1, px(1)));
        descBox.addView(chDesc, lp(-1, -2, 22));
        descBox.setTag("desc");
        FrameLayout.LayoutParams ld = new FrameLayout.LayoutParams(inner, -2, Gravity.BOTTOM | Gravity.START);
        ld.leftMargin = px(CH_PAD); ld.bottomMargin = px(104);
        chCard.addView(descBox, ld);
        LinearLayout hint = new LinearLayout(this);
        hint.setOrientation(LinearLayout.HORIZONTAL);
        hint.setGravity(Gravity.CENTER_VERTICAL);
        TextView ok = text(700, 17, 0xFFFFFFFF);
        ok.setText("OK");
        ok.setGravity(Gravity.CENTER);
        GradientDrawable okb = new GradientDrawable();
        okb.setCornerRadius(px(8));
        okb.setStroke(px(2), 0x5AFFFFFF);
        ok.setBackground(okb);
        hint.addView(ok, new LinearLayout.LayoutParams(px(52), px(34)));
        chHint = text(500, 20, 0x96FFFFFF);
        chHint.setText("Xem kênh");
        LinearLayout.LayoutParams lhh = new LinearLayout.LayoutParams(-2, -2);
        lhh.leftMargin = px(14);
        hint.addView(chHint, lhh);
        FrameLayout.LayoutParams lk = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        lk.leftMargin = px(CH_PAD); lk.bottomMargin = px(42);
        chCard.addView(hint, lk);

        // khung xem trước: cỡ đầy đủ 16:9, lúc nghỉ thu nhỏ đúng bằng ô logo (gốc phóng ở góc trên trái) và ẩn đi
        chFrame = new FrameLayout(this);
        roundClip(chFrame, px(18));
        chFrame.setBackgroundColor(0xFF000000);
        // SurfaceView (không phải TextureView): hình đi thẳng ra lớp video của box, giao diện không phải vẽ lại cả bảng
        // mỗi khung hình (TextureView làm hình xem trước giật như ảnh tĩnh). Khung bo góc của chFrame cắt luôn lỗ nhìn xuống
        // lớp video nên góc vẫn bo. Chỉ hiện SurfaceView khi khung đã nở hết (không phóng to/thu nhỏ nó).
        chVideo = new android.view.SurfaceView(this);
        chVideo.getHolder().addCallback(new android.view.SurfaceHolder.Callback() {
            @Override public void surfaceCreated(android.view.SurfaceHolder h) {
                chSurface = h.getSurface();
                if (chPreviewing != null && chPrev == null) beginPlayback(chPreviewing);
            }
            @Override public void surfaceChanged(android.view.SurfaceHolder h, int f, int w, int hh) {}
            @Override public void surfaceDestroyed(android.view.SurfaceHolder h) {
                chSurface = null;
                stopPreviewPlayer(false);
            }
        });
        chVideo.setVisibility(View.GONE);
        chFrame.addView(chVideo, new FrameLayout.LayoutParams(-1, -1));
        chFrameArt = new ImageView(this);
        chFrameArt.setScaleType(ImageView.ScaleType.FIT_XY);
        chFrame.addView(chFrameArt, new FrameLayout.LayoutParams(-1, -1));
        chSpin = new android.widget.ProgressBar(this);
        chSpin.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(0xE6FFFFFF));
        chFrame.addView(chSpin, new FrameLayout.LayoutParams(px(52), px(52), Gravity.CENTER));
        // lớp trên hình khi đã phát: dải tối đáy khung, loa + vạch âm lượng dâng dần
        FrameLayout ov = new FrameLayout(this);
        View shadeB = new View(this);
        shadeB.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0x99000000}));
        ov.addView(shadeB, new FrameLayout.LayoutParams(-1, px(90), Gravity.BOTTOM));
        chVol = new VolBars(this);
        FrameLayout.LayoutParams lv = new FrameLayout.LayoutParams(px(100), px(34), Gravity.BOTTOM | Gravity.END);
        lv.rightMargin = px(20); lv.bottomMargin = px(20);
        ov.addView(chVol, lv);
        ov.setAlpha(0f);
        chFrameOverlay = ov;
        chFrame.addView(ov, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams lf = new FrameLayout.LayoutParams(inner, chFrameH(), Gravity.TOP | Gravity.START);
        lf.leftMargin = px(CH_PAD); lf.topMargin = px(CH_PAD);
        chFrame.setPivotX(0); chFrame.setPivotY(0);
        chFrame.setScaleX(CH_LOGO_W / (float) inner);
        chFrame.setScaleY(CH_LOGO_H / (float) chFrameH());
        chFrame.setVisibility(View.INVISIBLE);
        chCard.addView(chFrame, lf);

        // lịch chưa nạp vào bộ nhớ: nạp ở nền rồi vẽ lại; lịch cũ thì tải lại ở nền
        bg.execute(() -> {
            if (Epg.cached() == null) { Epg.load(this); ui.post(this::redrawChannelCard); }
            if (Epg.refreshIfStale(this)) ui.post(this::redrawChannelCard);
        });
    }

    private void roundClip(View v, float r) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) { o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r); }
        });
        v.setClipToOutline(true);
    }

    private TextView label(String s, int color) {
        TextView t = text(650, 17, color);
        t.setText(s);
        t.setLetterSpacing(0.06f);
        t.setIncludeFontPadding(true);   // Ế, Ể in hoa: giữ dấu trên cùng
        return t;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int top) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(w, h);
        l.topMargin = px(top);
        return l;
    }

    // nền sau bảng kênh ánh theo màu kênh đang chọn: ảnh ô kênh thu nhỏ, nhoè (FrostView.blur) phủ cả màn, chuyển màu mềm
    private final android.util.LruCache<String, Bitmap> chGlow = new android.util.LruCache<>(24);

    private void channelBackdrop(Tv.Channel ch) {
        if (appsArt == null || ch.art == null) return;
        String key = "ch:" + ch.art;
        if (key.equals(appsArtFor)) return;
        appsArtFor = key;
        Bitmap b = chGlow.get(ch.art);
        if (b == null) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 4;   // ảnh 640x360 -> 160x90 rồi nhoè còn vài chục điểm ảnh: rẻ, làm ngay được
            Bitmap small = BitmapFactory.decodeFile(ch.art, o);
            if (small == null) return;
            b = FrostView.blur(small);
            chGlow.put(ch.art, b);
        }
        android.graphics.drawable.Drawable now = new android.graphics.drawable.BitmapDrawable(getResources(), b);
        android.graphics.drawable.Drawable prev = appsArt.getDrawable();
        if (prev == null || appsArt.getAlpha() < 0.05f) {
            appsArt.setImageDrawable(now);
        } else {
            android.graphics.drawable.TransitionDrawable td = new android.graphics.drawable.TransitionDrawable(
                    new android.graphics.drawable.Drawable[]{prev, now});
            td.setCrossFadeEnabled(true);
            appsArt.setImageDrawable(td);
            td.startTransition(260);
        }
        appsArt.animate().cancel();
        appsArt.animate().alpha(0.6f).setDuration(440).setInterpolator(EASE).start();
    }

    private void redrawChannelCard() { if (chShown != null && chCard != null) fillChannelCard(chShown, false); }

    /** Thanh tiến độ chuyển sắc theo màu kênh: đậm bên trái, sáng dần về đầu thanh. */
    private GradientDrawable barFill(int c) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        float[] a = {hsv[0], Math.min(1f, hsv[1] * 1.1f), Math.max(0.75f, hsv[2])};
        float[] b = {hsv[0], hsv[1] * 0.55f, 1f};
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.HSVToColor(a), Color.HSVToColor(b)});
        g.setCornerRadius(px(4));
        return g;
    }

    // màu nhấn của kênh (lấy từ ảnh nhoè đã có trong chGlow); kênh nền trắng/xám thì dùng xanh ngọc cho khỏi nhạt
    private final Map<String, Integer> chAccent = new java.util.HashMap<>();

    private int channelAccent(Tv.Channel ch) {
        Integer c = chAccent.get(ch.art);
        if (c != null) return c;
        Bitmap b = chGlow.get(ch.art);
        int col = b == null ? 0xFF4DD0E1 : Glow.dominant(b);
        float[] hsv = new float[3];
        Color.colorToHSV(col, hsv);
        if (hsv[1] < 0.28f) col = 0xFF4DD0E1;
        else { hsv[2] = Math.max(hsv[2], 0.82f); hsv[1] = Math.min(hsv[1], 0.62f); col = Color.HSVToColor(hsv); }   // đủ sáng để đọc trên nền tối
        chAccent.put(ch.art, col);
        return col;
    }

    /** Chọn một ô kênh: đang xem trước kênh khác thì thu khung lại; đổi nội dung cột mềm; hẹn 0,7 giây để xem trước. */
    private void showChannelInfo(Tv.Channel ch) {
        if (chCard == null) return;
        boolean changed = chShown != ch;
        chShown = ch;
        ui.removeCallbacks(chDwell);
        if (changed) {
            if (chPreviewing != null && chPreviewing != ch) collapsePreview(true);
            if (ch.art != null) {
                String u = android.net.Uri.fromFile(new File(ch.art)).toString();
                images.into(chArt, u, px(CH_TILE_W), px(12));   // cùng cỡ với ô kênh: lấy thẳng từ bộ đệm, không giải mã thêm
                images.into(chFrameArt, u, px(CH_TILE_W), 0);
            }
            int acc = channelAccent(ch);
            chNowLabel.setTextColor(acc);
            chBarClip.setDrawable(barFill(acc));
            // nội dung cũ mờ đi, nội dung mới trượt lên ~12px rồi hiện ra
            chBody.animate().cancel();
            chBody.animate().alpha(0f).setDuration(140).setInterpolator(EASE).withEndAction(() -> {
                if (chCard == null || chShown != ch) return;
                fillChannelCard(ch, true);
                chBody.setTranslationY(px(12));
                chBody.animate().alpha(1f).translationY(0).setDuration(320).setInterpolator(EASE).start();
            }).start();
            chHead.setAlpha(chExpanded ? 0f : 1f);
            fillHead(ch);
        }
        if (chPreviewing == null) ui.postDelayed(chDwell, CH_DWELL_MS);
    }

    private void fillHead(Tv.Channel ch) {
        String g = panelGroup(ch.group);
        chName.setText(ch.name); chGroup.setText(g);
        chName2.setText(ch.name); chGroup2.setText(g);
        chDesc.setText(ch.desc == null ? "" : ch.desc);
        ((View) chDesc.getParent()).setVisibility(ch.desc == null ? View.INVISIBLE : View.VISIBLE);
    }

    private void fillChannelCard(Tv.Channel ch, boolean animateBar) {
        long now = System.currentTimeMillis();
        List<Epg.Prog> up = upcoming(ch.tvgId, now, 5);
        Epg.Prog cur = !up.isEmpty() && up.get(0).start <= now ? up.remove(0) : null;
        if (cur != null) {
            chNowLabel.setVisibility(View.VISIBLE);
            chNowTitle.setText(cur.title);
            chNowTitle.setTextColor(0xFFFFFFFF);
            chNowTime.setText(hhmm(cur.start) + " – " + hhmm(cur.stop));
            chRemain.setText(remain(cur.stop - now));
            float f = Math.max(0f, Math.min(1f, (now - cur.start) / (float) (cur.stop - cur.start)));
            setBar(f, animateBar);
        } else {
            chNowLabel.setVisibility(View.GONE);
            chNowTitle.setText(ch.tvgId == null ? "Chưa có lịch phát sóng" : Epg.cached() == null ? "Đang tải lịch phát sóng" : "Chưa có lịch giờ này");
            chNowTitle.setTextColor(0x99FFFFFF);
        }
        int cv = cur != null ? View.VISIBLE : View.GONE;
        ((View) chNowTime.getParent()).setVisibility(cv);
        chBar.setVisibility(cv);
        chNextHead.setVisibility(up.isEmpty() ? View.GONE : View.VISIBLE);
        chList.removeAllViews();
        for (int i = 0; i < Math.min(4, up.size()); i++) chList.addView(timelineRow(up.get(i), i == 0, i == Math.min(4, up.size()) - 1));
        chBody.post(this::fitRows);
    }

    /** Chương trình đang chiếu (nếu có) rồi các chương trình kế, tối đa n. */
    private static List<Epg.Prog> upcoming(String id, long now, int n) {
        List<Epg.Prog> out = new ArrayList<>();
        Map<String, List<Epg.Prog>> m = Epg.cached();
        List<Epg.Prog> l = m == null || id == null ? null : m.get(id);
        if (l == null) return out;
        for (Epg.Prog p : l) {
            if (p.stop <= now) continue;
            out.add(p);
            if (out.size() >= n) break;
        }
        return out;
    }

    private static String remain(long ms) {
        long m = Math.max(1, (ms + 59_999) / 60_000);
        return m < 60 ? "còn " + m + " phút" : "còn " + (m / 60) + " giờ" + (m % 60 > 0 ? " " + (m % 60) + " phút" : "");
    }

    /** Một dòng trên dòng thời gian: chấm + đường ray dọc, giờ, tên chương trình (dòng đầu sáng và đậm hơn). */
    private View timelineRow(Epg.Prog p, boolean first, boolean last) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout rail = new FrameLayout(this);
        if (!last) {
            View ln = new View(this);
            ln.setBackgroundColor(0x28FFFFFF);
            FrameLayout.LayoutParams l = new FrameLayout.LayoutParams(px(2), -1, Gravity.CENTER_HORIZONTAL);
            l.topMargin = px(18);
            rail.addView(ln, l);
        }
        View dot = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(first ? chNowLabel.getCurrentTextColor() : 0x6EFFFFFF);
        dot.setBackground(d);
        int ds = px(first ? 14 : 10);
        FrameLayout.LayoutParams lpd = new FrameLayout.LayoutParams(ds, ds, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        lpd.topMargin = px(first ? 9 : 11);
        rail.addView(dot, lpd);
        row.addView(rail, new LinearLayout.LayoutParams(px(14), -1));
        TextView t = text(first ? 600 : 500, 22, first ? 0xFFFFFFFF : 0x69FFFFFF);
        t.setText(hhmm(p.start));
        LinearLayout.LayoutParams lt = new LinearLayout.LayoutParams(px(90), -2);
        lt.leftMargin = px(16); lt.topMargin = px(4);
        row.addView(t, lt);
        TextView s = text(first ? 600 : 450, first ? 26 : 24, first ? 0xFFFFFFFF : 0x96FFFFFF);
        s.setText(p.title);
        s.setMaxLines(first ? 2 : 1);
        s.setEllipsize(android.text.TextUtils.TruncateAt.END);
        s.setLineSpacing(px(4), 1f);
        row.addView(s, new LinearLayout.LayoutParams(0, -2, 1f));
        row.setPadding(0, 0, 0, px(first ? 26 : 20));
        return row;
    }

    /** Dòng lịch nào lấn xuống mô tả (hoặc xuống gợi ý phím khi đang xem trước) thì ẩn mềm. */
    private void fitRows() {
        if (chCard == null || chList == null) return;
        float ty = chExpanded ? chExpandedY() : chCompactY();
        View desc = (View) chDesc.getParent();
        float limit = chExpanded || desc.getVisibility() != View.VISIBLE
                ? chCard.getHeight() - px(104) : desc.getTop() - px(20);
        for (int i = 0; i < chList.getChildCount(); i++) {
            View r = chList.getChildAt(i);
            float bottom = px(CH_PAD) + ty + chList.getTop() + r.getTop() + r.getHeight() - r.getPaddingBottom();
            r.animate().alpha(bottom > limit ? 0f : 1f).setDuration(180).start();
        }
    }

    /** Thanh tiến độ: đổi kênh thì chạy từ đầu tới đúng chỗ; cập nhật định kỳ thì đặt thẳng. */
    private void setBar(float f, boolean animate) {
        if (chBarAnim != null) chBarAnim.cancel();
        if (!animate) { applyBar(f); return; }
        chBarAnim = ValueAnimator.ofFloat(0f, f);
        chBarAnim.setDuration(520);
        chBarAnim.setInterpolator(EASE);
        chBarAnim.addUpdateListener(a -> applyBar((float) a.getAnimatedValue()));
        chBarAnim.start();
    }

    private void applyBar(float f) {
        chBarFrac = f;
        chBarClip.setLevel(Math.round(f * 10000));
        int w = chBar.getWidth() > 0 ? chBar.getWidth() : px(chInner());
        chKnob.setTranslationX(Math.max(0, Math.min(w - px(18), w * f - px(9))));
    }

    // ---------- xem trước kênh ----------

    /** Đứng yên 0,7 giây: logo nở thành khung, thân cột trượt xuống, bắt đầu nhận luồng. */
    private void startPreview(Tv.Channel ch) {
        if (chCard == null || ch == null || ch.url == null || chPreviewing == ch) return;
        chPreviewing = ch;
        chExpanded = true;
        // quầng "thở" quanh ô kênh bắt cả bảng vẽ lại ~10 lần/giây: dừng lúc đang xem trước cho box dồn sức giải mã hình
        if (breathe != null) { breathe.cancel(); breathe = null; }
        chFrameArt.setAlpha(1f);
        chFrameArt.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(px(10), px(10), android.graphics.Shader.TileMode.CLAMP));
        chSpin.setVisibility(View.VISIBLE);
        chSpin.setAlpha(0f);
        chSpin.animate().alpha(1f).setStartDelay(250).setDuration(200).start();
        chFrameOverlay.setAlpha(0f);
        chVol.setLevel(0f);
        chFrame.setVisibility(View.VISIBLE);
        chFrame.animate().cancel();
        chFrame.animate().scaleX(1f).scaleY(1f).setDuration(460).setInterpolator(EASE).withEndAction(() -> {
            if (chVideo != null && chPreviewing == ch) chVideo.setVisibility(View.VISIBLE);   // tạo Surface -> beginPlayback
        }).start();
        chHead.animate().cancel();
        chHead.animate().alpha(0f).setDuration(160).start();
        chBodyWrap.animate().cancel();
        chBodyWrap.animate().translationY(chExpandedY()).setDuration(460).setInterpolator(EASE).start();
        ((ViewGroup) chBody).getChildAt(0).animate().alpha(1f).setDuration(360).setStartDelay(120).start();
        ((View) chDesc.getParent()).animate().alpha(0f).setDuration(160).start();
        chHint.setText("Xem toàn màn hình");
        ui.postDelayed(this::fitRows, 10);
    }

    private void beginPlayback(Tv.Channel ch) {
        stopPreviewPlayer(false);
        chPrev = TvPreview.start(this, ch.url, chSurface, new TvPreview.Listener() {
            @Override public void onFirstFrame() {
                if (chPreviewing != ch || chFrameArt == null) return;
                chSpin.animate().cancel();
                // vòng xoay ẩn hẳn (GONE): để alpha 0 thì nó vẫn quay ngầm, giao diện vẽ lại liên tục, tốn CPU
                chSpin.animate().alpha(0f).setStartDelay(0).setDuration(150).withEndAction(() -> { if (chSpin != null) chSpin.setVisibility(View.GONE); }).start();
                chFrameArt.animate().alpha(0f).setDuration(280).setInterpolator(EASE)
                        .withEndAction(() -> { if (chFrameArt != null) chFrameArt.setRenderEffect(null); }).start();
                chFrameOverlay.animate().alpha(1f).setDuration(280).start();
                chVol.rampTo(1f, 1500);
            }
            @Override public void onFailed() {
                if (chPreviewing != ch) return;
                stopPreviewPlayer(false);
                chSpin.animate().alpha(0f).setDuration(150).withEndAction(() -> { if (chSpin != null) chSpin.setVisibility(View.GONE); }).start();
            }
        });
    }

    private void stopPreviewPlayer(boolean fade) {
        TvPreview p = chPrev;
        chPrev = null;
        if (p == null) return;
        if (fade) p.fadeOutAndStop(); else p.stop();
    }

    /** Rời kênh đang xem trước: tiếng nhỏ dần, khung thu về logo, thân cột về chỗ cũ. */
    private void collapsePreview(boolean animate) {
        ui.removeCallbacks(chDwell);
        stopPreviewPlayer(animate);   // nhả Surface ngay, tiếng nhỏ dần rồi dừng
        chPreviewing = null;
        if (chVideo != null) chVideo.setVisibility(View.GONE);
        if (chFrame == null || !chExpanded) return;
        chExpanded = false;
        float sx = CH_LOGO_W / (float) chInner(), sy = CH_LOGO_H / (float) chFrameH();
        long d = animate ? 280 : 0;
        chFrameArt.animate().cancel();
        chFrameArt.setRenderEffect(null);
        chFrameArt.setAlpha(1f);   // che hình cũ ngay, khung thu về đúng logo
        chFrameOverlay.animate().cancel();
        chFrameOverlay.setAlpha(0f);
        chSpin.animate().cancel();
        chSpin.setVisibility(View.GONE);
        chVol.setLevel(0f);
        chFrame.animate().cancel();
        chFrame.animate().scaleX(sx).scaleY(sy).setDuration(d).setInterpolator(EASE)
                .withEndAction(() -> { if (chFrame != null && !chExpanded) chFrame.setVisibility(View.INVISIBLE); }).start();
        chHead.animate().cancel();
        chHead.animate().alpha(1f).setStartDelay(animate ? 120 : 0).setDuration(d).start();
        chBodyWrap.animate().cancel();
        chBodyWrap.animate().translationY(chCompactY()).setDuration(d).setInterpolator(EASE).start();
        ((ViewGroup) chBody).getChildAt(0).animate().alpha(0f).setStartDelay(0).setDuration(animate ? 120 : 0).start();
        ((View) chDesc.getParent()).animate().alpha(1f).setStartDelay(animate ? 140 : 0).setDuration(d).start();
        chHint.setText("Xem kênh");
        ui.postDelayed(this::fitRows, 10);
    }

    /** Đóng bảng / rời launcher: dừng phát, thả Surface và bộ đệm. */
    private void releaseChannelCard() {
        ui.removeCallbacks(chTick);
        ui.removeCallbacks(chDwell);
        stopPreviewPlayer(false);
        chPreviewing = null;
        chExpanded = false;
        if (chBarAnim != null) { chBarAnim.cancel(); chBarAnim = null; }
        if (chVol != null) chVol.stopRamp();
        chSurface = null;   // Surface của SurfaceView do hệ thống giữ, gỡ view là tự huỷ
        TvPreview.releaseAll();
        chCard = null; chFrame = null; chBodyWrap = null; chHead = null; chBody = null; chList = null;
        chArt = null; chFrameArt = null; chVideo = null; chSpin = null; chFrameOverlay = null; chKnob = null; chVol = null;
        chBarClip = null; chBar = null; chShown = null;
    }

    /** Loa + 5 vạch âm lượng; vạch sáng dần theo mức (to dần cùng tiếng). */
    private static final class VolBars extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path spk = new android.graphics.Path();
        private float level;
        private ValueAnimator anim;
        VolBars(android.content.Context c) { super(c); }
        void setLevel(float l) { stopRamp(); level = l; invalidate(); }
        void stopRamp() { if (anim != null) { anim.cancel(); anim = null; } }
        void rampTo(float to, long ms) {
            stopRamp();
            anim = ValueAnimator.ofFloat(level, to);
            anim.setDuration(ms);
            anim.addUpdateListener(a -> { level = (float) a.getAnimatedValue(); invalidate(); });
            anim.start();
        }
        @Override protected void onDraw(Canvas c) {
            float h = getHeight(), u = h / 34f, cy = h / 2f;
            p.setColor(0xFFFFFFFF);
            spk.reset();
            spk.moveTo(0, cy - 7 * u); spk.lineTo(8 * u, cy - 7 * u); spk.lineTo(18 * u, cy - 15 * u);
            spk.lineTo(18 * u, cy + 15 * u); spk.lineTo(8 * u, cy + 7 * u); spk.lineTo(0, cy + 7 * u); spk.close();
            c.drawPath(spk, p);
            for (int i = 0; i < 5; i++) {
                float bh = (8 + i * 6) * u, x = (32 + i * 13) * u;
                float on = Math.max(0f, Math.min(1f, level * 5 - i));
                p.setColor(Color.argb(Math.round(70 + 185 * on), 255, 255, 255));
                c.drawRoundRect(x, cy - bh / 2, x + 7 * u, cy + bh / 2, 3 * u, 3 * u, p);
            }
        }
    }

    private static String hhmm(long t) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(t);
        return String.format(java.util.Locale.ROOT, "%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE));
    }

    /** Ô kênh trong bảng kênh: ảnh logo kênh 16:9, không ghi tên; bấm là trình phát mở kênh. */
    private FrameLayout channelTile(Tv.Channel ch) {
        FrameLayout t = new FrameLayout(this);
        t.setFocusable(true);
        t.setClipChildren(false);
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_XY);
        GradientDrawable ph = new GradientDrawable();
        ph.setColor(0x33FFFFFF);
        ph.setCornerRadius(px(CH_TILE_R));
        img.setBackground(ph);
        img.setContentDescription(ch.name);
        // ảnh kênh nạp ở nền qua bộ đệm ảnh: bảng hiện ngay (giải mã 106 ảnh trên luồng giao diện làm khựng hơn 1 giây),
        // lần mở sau lấy thẳng từ bộ đệm. Gán thẳng, không mờ dần, để khỏi giẫm hiệu ứng phóng to khi ô được chọn.
        if (ch.art != null) images.load(android.net.Uri.fromFile(new File(ch.art)).toString(), px(CH_TILE_W), b -> {
            if (b == null) return;
            img.setImageDrawable(new RoundImage(b, px(CH_TILE_R)));
            // ô đã được chọn từ trước khi ảnh tới (ô chọn sẵn lúc mở bảng): dựng quầng sáng theo màu logo ngay bây giờ
            if (t.hasFocus() && t.getOnFocusChangeListener() != null) t.getOnFocusChangeListener().onFocusChange(t, true);
        });
        // không đổ bóng: ô đang chọn nổi lên (translationZ) thì Android vẽ bóng đậm sát mép, lộ thành viền đen dưới quầng sáng
        img.setOutlineProvider(null);
        t.addView(img, new FrameLayout.LayoutParams(-1, px(CH_TILE_H)));
        appsTileFocus(t, img, CH_TILE_W, CH_TILE_H, CH_TILE_R, () -> { channelBackdrop(ch); showChannelInfo(ch); });
        t.setOnClickListener(v -> playChannel(ch, v));
        return t;
    }

    /**
     * Trình phát mở kênh. Bảng kênh để nguyên phía sau: bấm Back là về đúng bảng, đúng ô vừa chọn
     * (ô đó cũng được chọn sẵn lần mở bảng sau).
     */
    private void playChannel(Tv.Channel ch, View src) {
        if (chCard != null) collapsePreview(false);
        stopThumbPreview(false);   // nhả bộ giải mã cho trình phát toàn màn hình
        if (!Tv.play(this, ch, zoomFrom(src)))
            android.widget.Toast.makeText(this, "Chưa mở được " + (ch == null ? "kênh" : ch.name), android.widget.Toast.LENGTH_SHORT).show();
    }

    private static final int APP_TILE_W = 300, APP_TILE_H = 169, APP_TILE_R = 14, GAME_TILE_H = 188;
    // ô đang chọn phóng cỡ như dock (300 -> 330, ~110%)
    private static final float APP_TILE_SCALE = 1.1f;

    /**
     * Hiệu ứng chọn của ô trong bảng Apps, y như ô dock: phóng ~110% có nảy nhẹ, ánh sáng trên mặt ô, quầng sáng
     * theo màu logo (khoét lỗ đúng hình ô đã phóng, kể cả góc bo) hiện ở mức dịu, đứng yên ~1 s thì sáng hẳn rồi
     * "thở"; nhấn OK thì ô lún xuống. Ảnh quầng chỉ dựng lúc ô được chọn lần đầu (bảng nhiều ô, đỡ tốn bộ nhớ).
     */
    private void appsTileFocus(FrameLayout t, ImageView img, int w, int h, float r, Runnable onFocus) {
        float sc = APP_TILE_SCALE;
        View sheen = new View(this);
        GradientDrawable sh = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0x40FFFFFF, 0x0DFFFFFF, 0x00FFFFFF});
        sh.setCornerRadius(px(r));
        sheen.setBackground(sh);
        sheen.setOutlineProvider(null);
        sheen.setAlpha(0f);
        t.addView(sheen, new FrameLayout.LayoutParams(-1, px(h)));

        int cw = Math.round(px(w) * sc), ch = Math.round(px(h) * sc), sp = px(GLOW_SPREAD);
        ImageView g = new ImageView(this);
        g.setAlpha(0f);
        g.setTranslationZ(px(32));   // trên ảnh ô đang nổi (translationZ 30), chỉ lộ phần quanh mép nhờ lỗ khoét
        FrameLayout.LayoutParams lg = new FrameLayout.LayoutParams(cw + sp * 2, ch + sp * 2);
        lg.leftMargin = Math.round(px(w) / 2f - (cw + sp * 2) / 2f);
        lg.topMargin = Math.round(px(h) / 2f - (ch + sp * 2) / 2f);
        t.addView(g, lg);

        t.setOnFocusChangeListener((v, has) -> {
            if (has && g.getDrawable() == null && img.getDrawable() != null) {
                Bitmap art = toBitmap(img.getDrawable(), px(w) / 4, px(h) / 4);
                g.setImageBitmap(Glow.make(cw, ch, px(r) * sc, sp, Glow.dominant(art)));
            }
            float s = has ? sc : 1f;
            android.view.animation.Interpolator in = EASE;
            img.animate().cancel(); sheen.animate().cancel(); g.animate().cancel();
            img.animate().scaleX(s).scaleY(s).translationZ(has ? px(30) : 0).setInterpolator(in).setDuration(has ? 500 : 520).start();
            sheen.animate().scaleX(s).scaleY(s).translationZ(has ? px(31) : 0).alpha(has ? 1f : 0f)
                    .setInterpolator(in).setDuration(has ? 500 : 480).start();
            if (has) {
                g.animate().alpha(GLOW_MOVING).setDuration(520).setInterpolator(EASE).withEndAction(() -> startBreathing(g)).start();
                onFocus.run();
            } else {
                if (breathe != null) { breathe.cancel(); breathe = null; }
                g.animate().alpha(0f).setDuration(300).setInterpolator(EASE).start();
            }
        });
        t.setOnKeyListener((v, kc, ev) -> {
            if (isOk(kc) && ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) press(img, sheen);
            // nhả OK: bật lại đúng cỡ đang chọn (110%). Không bật lại thì ô kẹt ở cỡ lún 107% trong khi quầng sáng dựng cho
            // 110%, lộ khe tối thành viền đen quanh ô (rõ nhất khi bấm Back từ trình phát về)
            if (isOk(kc) && ev.getAction() == KeyEvent.ACTION_UP) {
                for (View x : new View[]{img, sheen}) {
                    x.animate().cancel();
                    x.animate().scaleX(sc).scaleY(sc).translationZ(px(x == img ? 30 : 31))
                            .setInterpolator(EASE).setDuration(420).start();
                }
            }
            return false;
        });
    }

    private FrameLayout appTile(PackageManager pm, ResolveInfo ri) {
        FrameLayout t = new FrameLayout(this);
        t.setFocusable(true);
        t.setClipChildren(false);
        ImageView img = new ImageView(this);
        GradientDrawable ph = new GradientDrawable();
        ph.setColor(0x33FFFFFF);
        ph.setCornerRadius(px(14));
        img.setBackground(ph);
        shadowShape(img, px(14));
        Drawable d = ri.activityInfo.loadBanner(pm);
        if (d == null) d = ri.activityInfo.applicationInfo.loadBanner(pm);
        if (d == null) {
            d = ri.loadIcon(pm);
            img.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            img.setPadding(px(40), px(30), px(40), px(30));
            img.setImageDrawable(d);
        } else {
            // banner vẽ thành ảnh rồi bo góc bằng bút khử răng cưa
            img.setScaleType(ImageView.ScaleType.FIT_XY);
            img.setImageDrawable(new RoundImage(toBitmap(d, px(300), px(169)), px(14)));
        }
        t.addView(img, new FrameLayout.LayoutParams(-1, px(169)));
        TextView name = text(500, 22, 0xD9FFFFFF);
        name.setText(ri.loadLabel(pm));
        name.setSingleLine(true);
        FrameLayout.LayoutParams ln = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        t.addView(name, ln);
        String pkg = ri.activityInfo.packageName;
        appsTileFocus(t, img, APP_TILE_W, APP_TILE_H, APP_TILE_R, () -> appsBackdrop(null));
        t.setOnClickListener(v -> { launchPkg(pkg, v); closeAllApps(); });
        return t;
    }

    private void closeAllApps() {
        if (allApps == null) return;
        View a = allApps;
        allApps = null;
        for (View c : hiddenUnderPanel) c.setVisibility(View.VISIBLE);   // hiện lại màn Home trước khi bảng mờ đi
        hiddenUnderPanel.clear();
        if (appsFocusWatch != null) { root.getViewTreeObserver().removeOnGlobalFocusChangeListener(appsFocusWatch); appsFocusWatch = null; }
        if (appsScroll != null) { appsScroll.cancel(); appsScroll = null; }
        appsArt = null; appsArtFor = null;
        releaseChannelCard();   // dừng xem trước, thả Surface + bộ đệm luồng
        a.animate().alpha(0f).setDuration(260).withEndAction(() -> root.removeView(a)).start();
        tray.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        shelfRow.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        gear.setFocusable(true); clock.setFocusable(true);
        cards.get(focused).requestFocus();   // focus về ô -> quầng sáng hiện lại và "thở"
    }

    // ---------- phím ----------

    // ---------- màn hình chờ: đứng yên ở màn hình chính 5 phút ----------
    // Nền là ảnh đang hiện, tối thêm, trôi rất chậm; giờ + ngày dương + âm lịch nằm giữa, trong 1/3 phía trên.
    // Chữ rõ mà không gắt: trắng ngà, bóng mỏng nhoè rộng, quầng tối mềm phía sau đậm theo độ sáng vùng ảnh.
    // Cụm chữ dịch nhẹ vài phút một lần để tấm nền không lưu ảnh. Bấm nút bất kỳ: mờ đi, nút đó không bị tính.

    private static final String[] THU = {"Chủ Nhật", "Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy"};
    private FrameLayout saver;
    private View saverHalo;
    private LinearLayout saverText;
    private TextView saverTime, saverSolar, saverLunar, saverTet;
    private ObjectAnimator saverDrift;
    private ImageView saverBg;
    private long saverImageMs() { return Wallpapers.everyMin(this, Wallpapers.LOCK) * 60_000L; }   // mặc định 15 phút
    // Màn hình chờ của Google TV (Ambient/Backdrop) tự bật sau 10 phút không dùng và đè lên màn hình chờ này.
    // Khi màn hình chờ đang hiện thì giữ màn hình sáng để hệ thống không chen vào, nhưng chỉ tối đa 2 tiếng:
    // quá giờ thì trả lại cho hệ thống để box tự chờ rồi tắt như cài đặt sẵn, không bắt TV sáng mãi.
    private static final long SAVER_HOLD_MS = 2 * 60 * 60_000L;
    private final Runnable releaseScreen = () ->
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

    /** Ảnh kế tiếp trong bộ ảnh màn hình chờ (xoay vòng theo tên); fade = hoà dần từ ảnh cũ. */
    private void nextSaverImage(boolean fade) {
        List<String> fs = Wallpapers.active(this, Wallpapers.LOCK);
        if (fs.isEmpty()) return;                           // không có ảnh nào: giữ ảnh nền đang hiện
        int i = (prefs().getInt("saver_img", -1) + 1) % fs.size();
        prefs().edit().putInt("saver_img", i).apply();
        ImageView target = saverBg;
        images.load(fs.get(i), bgWidth(), bmp -> {
            if (!saverOn || saverBg != target || bmp == null) return;
            if (!fade) { target.setImageBitmap(bmp); tuneHalo(target.getDrawable()); return; }
            target.animate().alpha(0f).setDuration(900).withEndAction(() -> {
                target.setImageBitmap(bmp);
                tuneHalo(target.getDrawable());
                target.animate().alpha(1f).setDuration(1200).start();
            }).start();
        });
    }

    private final Runnable saverImageTick = new Runnable() {
        @Override public void run() {
            if (!saverOn) return;
            nextSaverImage(true);
            ui.postDelayed(this, saverImageMs());
        }
    };

    /** Quầng sau cụm giờ đậm theo độ sáng vùng ảnh ở góc dưới trái: ảnh tối gần như không thấy, ảnh sáng đậm tới ~45%. */
    private void tuneHalo(Drawable d) {
        if (saverHalo == null) return;
        float a = 0.33f;
        if (d instanceof android.graphics.drawable.BitmapDrawable) {
            float lum = regionGlare(((android.graphics.drawable.BitmapDrawable) d).getBitmap(), 0.02f, 0.62f, 0.4f, 0.95f);
            a = 0.33f + 0.67f * Math.max(0f, Math.min(1f, (lum - 0.2f) / 0.45f));
        }
        saverHalo.animate().alpha(a).setDuration(600).start();
        if (d instanceof android.graphics.drawable.BitmapDrawable) glassFrom(((android.graphics.drawable.BitmapDrawable) d).getBitmap());
    }

    // ---------- chữ kính mờ của màn hình chờ ----------
    // Lòng chữ là chính ảnh nền làm nhoè, sáng lên và pha trắng sữa: thấy màu ảnh xuyên qua, hơi trong. Kính phẳng:
    // không viền sáng, không bóng lệch; chỉ quầng tối rất mềm đều quanh chữ. Ảnh nhoè chỉ dựng lại khi đổi ảnh,
    // vị trí chỉ tính lại khi cụm chữ dời chỗ (3 phút một lần), không vẽ lại từng khung hình.

    private Bitmap glassSrc;

    /** Ảnh nhoè đã pha màu kính: tối như nền màn hình chờ, sáng lên ~1,9 lần rồi phủ ~33% trắng ngà. */
    private void glassFrom(Bitmap bg) {
        if (bg == null) return;
        Bitmap b = FrostView.blur(bg);
        Bitmap t = Bitmap.createBitmap(b.getWidth(), b.getHeight(), Bitmap.Config.ARGB_8888);
        float k = 0.456f;   // 0,55 (lớp tối) x 0,65 (đáy tối dần) x 1,9 (làm sáng) x 0,67 (phần còn lại sau lớp trắng)
        android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix(new float[]{
                k, 0, 0, 0, 107,
                0, k, 0, 0, 105,
                0, 0, k, 0, 103,
                0, 0, 0, 1, 0});
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
        new Canvas(t).drawBitmap(b, 0, 0, p);
        glassSrc = t;
        if (saverText != null) saverText.post(this::placeGlass);
    }

    /** Gắn ảnh kính vào nét chữ, khớp đúng chỗ ảnh nền phía sau (ảnh nền phóng 108%, cắt giữa như ImageView). */
    private void placeGlass() {
        if (!saverOn || glassSrc == null || saverBg == null) return;
        int sw = getResources().getDisplayMetrics().widthPixels, sh = getResources().getDisplayMetrics().heightPixels;
        float k = Math.max(sw / (float) glassSrc.getWidth(), sh / (float) glassSrc.getHeight()) * saverBg.getScaleX();
        float ox = (sw - glassSrc.getWidth() * k) / 2f + saverBg.getTranslationX(), oy = (sh - glassSrc.getHeight() * k) / 2f;
        int[] at = new int[2];
        for (TextView tv : new TextView[]{saverTime, saverSolar, saverLunar}) {
            if (tv == null) continue;
            tv.getLocationOnScreen(at);
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setScale(k, k);
            m.postTranslate(ox - at[0] - tv.getTotalPaddingLeft(), oy - at[1] - tv.getExtendedPaddingTop());
            android.graphics.BitmapShader shader = new android.graphics.BitmapShader(glassSrc,
                    android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
            shader.setLocalMatrix(m);
            tv.getPaint().setFilterBitmap(true);
            tv.getPaint().setShader(shader);
            tv.invalidate();
        }
    }
    private boolean saverOn, swallowUp;

    private final Runnable showSaverLater = this::showSaver;
    private final Runnable saverMove = new Runnable() {
        @Override public void run() {
            if (!saverOn) return;
            // dịch cụm chữ tới một chỗ ngẫu nhiên gần đó (vẫn ở góc dưới trái), thật chậm
            java.util.Random r = new java.util.Random();
            float dx = px(-40 + r.nextInt(81)), dy = px(-40 + r.nextInt(41));
            saverText.animate().translationX(dx).translationY(dy).setDuration(4000).setInterpolator(EASE)
                    .withEndAction(() -> {
                        placeGlass();   // dời xong: kính khớp lại với nền
                        // chốt lại độ cao dòng thông báo theo cụm chữ, phòng khi đường dịch của nó bị cắt ngang
                        if (saverTet != null && saverText != null) saverTet.setTranslationY(noticeShift + saverText.getTranslationY());
                    }).start();
            saverHalo.animate().translationX(dx).translationY(dy).setDuration(4000).setInterpolator(EASE).start();
            // thông báo dịch cùng độ cao với cụm bên trái: chân chữ luôn thẳng hàng với dòng âm lịch
            saverTet.animate().translationX(-Math.abs(dx) / 3).translationY(noticeShift + dy).setDuration(4000).setInterpolator(EASE).start();
            ui.postDelayed(this, 3 * 60_000L);
        }
    };

    /** Đếm lại thời gian chờ (mặc định 5 phút) từ lần bấm cuối; Cài đặt chọn "Không bật" thì thôi. */
    private void armSaver() {
        ui.removeCallbacks(showSaverLater);
        int m = Wallpapers.saverAfterMin(this);
        if (m > 0) ui.postDelayed(showSaverLater, m * 60_000L);
    }

    private void showSaver() {
        if (saverOn || isFinishing()) return;
        saverOn = true;
        if (breathe != null) { breathe.cancel(); breathe = null; }   // dock bị che: khỏi vẽ nhịp thở
        ui.removeCallbacks(btcTick);                                  // giá cũng bị che: khỏi gọi mạng
        int sw = getResources().getDisplayMetrics().widthPixels, sh = getResources().getDisplayMetrics().heightPixels;
        saver = new FrameLayout(this);
        saver.setBackgroundColor(Color.BLACK);
        // nền: bộ ảnh riêng của màn hình chờ (bg/khoa, đồng bộ từ D:\lock screen), mỗi lần hiện lấy ảnh kế tiếp;
        // bộ ảnh trống thì dùng ảnh nền đang hiện. Phóng nhẹ và trôi qua lại rất chậm (90 s một chiều)
        ImageView bgv = fullImage();
        bgv.setImageDrawable(bgFront.getDrawable());
        bgv.setScaleX(1.08f); bgv.setScaleY(1.08f);
        saver.addView(bgv);
        saverBg = bgv;
        nextSaverImage(false);
        saverDrift = ObjectAnimator.ofFloat(bgv, View.TRANSLATION_X, -px(45), px(45));
        saverDrift.setDuration(90_000);
        saverDrift.setRepeatCount(ValueAnimator.INFINITE);
        saverDrift.setRepeatMode(ValueAnimator.REVERSE);
        saverDrift.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        saverDrift.start();
        // tối hơn thường lệ ~45% để đỡ hại tấm nền, thêm loang tối hai bên như màn hình chính
        View dim = new View(this);
        dim.setBackgroundColor(0x73000000);
        saver.addView(dim, new FrameLayout.LayoutParams(-1, -1));
        View sides = new View(this);
        sides.setBackground(new android.graphics.drawable.BitmapDrawable(getResources(), Dither.fadeSides(sw, sh, 0x66, 0.25f)));
        saver.addView(sides, new FrameLayout.LayoutParams(-1, -1));
        // đáy tối dần: hai cụm chữ ở hai góc dưới luôn nằm trên nền đủ tối
        View floor = new View(this);
        floor.setBackground(new android.graphics.drawable.BitmapDrawable(getResources(), Dither.fadeUp(sw, px(480), new int[]{0x8C, 0x40, 0x00})));
        saver.addView(floor, new FrameLayout.LayoutParams(-1, px(480), Gravity.BOTTOM));
        // quầng tối mềm sau cụm giờ: dựng đậm tối đa (~45%), độ đậm thật chỉnh bằng alpha theo độ sáng vùng ảnh
        saverHalo = new View(this);
        saverHalo.setBackground(new android.graphics.drawable.BitmapDrawable(getResources(), Dither.halo(px(1200), px(620), 0x73)));
        tuneHalo(saverBg.getDrawable());
        FrameLayout.LayoutParams lh = new FrameLayout.LayoutParams(px(1200), px(620), Gravity.BOTTOM | Gravity.START);
        lh.leftMargin = px(-250); lh.bottomMargin = px(-150);
        saver.addView(saverHalo, lh);
        // cụm chữ: góc dưới trái, căn lề trái, không che nhân vật ở giữa ảnh
        saverText = new LinearLayout(this);
        saverText.setOrientation(LinearLayout.VERTICAL);
        saverText.setGravity(Gravity.START);
        // nét 320 (thanh nhưng không như sợi tóc): giao diện vẽ ở 1080p rồi TV phóng lên 4K, nét quá mảnh sẽ răng cưa
        // nhỏ và dịu: kem ấm (không trắng lạnh) ~60% (giờ) > ~55% (lịch dương) > ~44% (âm lịch);
        // trên nền tối, trắng lạnh và độ sáng cao trông gắt
        // chữ kính: màu lấy từ ảnh nền (placeGlass), ở đây chỉ còn độ trong; giờ nét 460 để lòng kính đủ rộng mà thấy
        saverTime = text(460, 140, 0xD7FFFFFF);
        saverTime.setLetterSpacing(0f);   // không giãn chữ âm: bề rộng đo được khớp nét vẽ, chữ số cuối không bị xén
        saverTime.setFontFeatureSettings("tnum");
        saverTime.setSingleLine(true);
        saverTime.setEllipsize(null);
        saverTime.setPadding(0, px(8), px(24), px(8));
        saverSolar = text(500, 36, 0xD2FFFFFF);
        // âm lịch cùng kiểu chữ với dòng thông báo góc phải (đếm ngược Tết / giá coin) và cùng đường chân chữ
        saverLunar = text(500, 26, 0xB4FFFFFF);
        saverLunar.setLetterSpacing(0.02f);
        saverLunar.setFontFeatureSettings("tnum");
        // ô thông báo góc dưới phải (đếm ngược Tết / giá coin, xoay vòng): chữ nhỏ, màu từng phần do span quyết định
        saverTet = text(500, 26, 0xD1FAF6EE);
        saverTet.setFontFeatureSettings("tnum");
        saverTet.setLetterSpacing(0.02f);
        saverTet.setShadowLayer(px(14), 0, px(2), 0x52000000);   // bóng mỏng nhoè rộng: tách chữ khỏi ảnh, không thành viền
        // chữ tròn (Quicksand) cho cả cụm màn hình khoá: mềm như mặt đồng hồ Apple Watch, không cứng nhọn như Inter.
        // Quicksand thanh hơn Inter cùng số wght: chữ nhỏ (lịch, âm lịch, thông báo) đậm thêm để vẫn rõ trên ảnh sáng
        saverTime.setTypeface(roundFont(500));
        saverSolar.setTypeface(roundFont(600));
        saverLunar.setTypeface(roundFont(600));
        saverTet.setTypeface(roundFont(600));
        saverTime.setLetterSpacing(0f);
        saverSolar.setLetterSpacing(0f);
        // chữ kính: quầng tối rất mềm, đều quanh chữ (không lệch xuống, không thành viền nổi khối)
        for (TextView t : new TextView[]{saverTime, saverSolar, saverLunar}) t.setShadowLayer(px(14), 0, 0, 0x2E000000);
        // bề rộng cố định theo chuỗi rộng nhất (+15% dư): khung tự đo của TextView hụt với Inter có độ đậm tuỳ biến
        // ở cỡ lớn, làm chữ số cuối bị xén một nửa
        int timeW = (int) Math.ceil(saverTime.getPaint().measureText("00:00") * 1.15f) + saverTime.getPaddingRight();
        saverText.addView(saverTime, new LinearLayout.LayoutParams(timeW, -2));
        LinearLayout.LayoutParams ls = new LinearLayout.LayoutParams(-2, -2);
        ls.topMargin = px(6);
        saverText.addView(saverSolar, ls);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-2, -2);
        ll.topMargin = px(12);
        saverText.addView(saverLunar, ll);
        FrameLayout.LayoutParams lt = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        lt.leftMargin = px(110); lt.bottomMargin = px(72);
        saver.addView(saverText, lt);
        saverTime.setTranslationX(-px(8));   // mép chữ số to có khoảng trắng riêng: kéo nhẹ cho thẳng lề với hai dòng dưới
        // đếm ngược Tết tách riêng ở góc dưới phải: cụm giữa đỡ nhiều chữ
        FrameLayout.LayoutParams lte = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        lte.rightMargin = px(96); lte.bottomMargin = px(72);
        saver.addView(saverTet, lte);
        updateSaverText();
        saver.setAlpha(0f);
        root.addView(saver, new FrameLayout.LayoutParams(-1, -1));
        saver.animate().alpha(1f).setDuration(1200).setInterpolator(EASE).start();
        ui.removeCallbacks(saverMove);
        ui.postDelayed(saverMove, 3 * 60_000L);
        ui.removeCallbacks(saverImageTick);
        ui.postDelayed(saverImageTick, saverImageMs());
        noticeIdx = 0;
        noticeShift = 0;
        showNotice(false);
        saver.post(this::alignNotice);
        saver.post(this::placeGlass);   // bố cục xong mới biết chữ nằm đâu để khớp kính với nền
        ui.removeCallbacks(noticeTick);
        ui.postDelayed(noticeTick, NOTICE_MS);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ui.removeCallbacks(releaseScreen);
        ui.postDelayed(releaseScreen, SAVER_HOLD_MS);
    }

    /** Thoát màn hình chờ: mờ đi rồi gỡ hẳn (nhả bộ nhớ các lớp vẽ sẵn), dock trở lại như cũ. */
    private void hideSaver(boolean animate) {
        if (!saverOn) return;
        saverOn = false;
        ui.removeCallbacks(saverMove);
        ui.removeCallbacks(saverImageTick);
        ui.removeCallbacks(releaseScreen);
        ui.removeCallbacks(noticeTick);
        releaseScreen.run();
        View v = saver;
        saver = null;
        saverBg = null;
        saverHalo = null;
        if (saverDrift != null) { saverDrift.cancel(); saverDrift = null; }
        Runnable gone = () -> root.removeView(v);
        if (animate) v.animate().alpha(0f).setDuration(350).setInterpolator(EASE).withEndAction(gone).start();
        else gone.run();
        if (shown()) {
            if (Wallpapers.widget(this, "btc") || Wallpapers.widget(this, "eth")) { ui.removeCallbacks(btcTick); ui.post(btcTick); }
            if (glowCur >= 0 && !inShelf && allApps == null) startBreathing(glowViews[glowCur]);
        }
    }

    private boolean shown() { return getWindow() != null && hasWindowFocus(); }

    private void updateSaverText() {
        if (!saverOn) return;
        java.util.Calendar c = java.util.Calendar.getInstance();
        int d = c.get(java.util.Calendar.DAY_OF_MONTH), m = c.get(java.util.Calendar.MONTH) + 1, y = c.get(java.util.Calendar.YEAR);
        saverTime.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(c.getTime()));
        saverSolar.setText(THU[c.get(java.util.Calendar.DAY_OF_WEEK) - 1] + ", " + d + " tháng " + m + ", " + y);
        saverLunar.setText(Lunar.describe(d, m, y));
    }

    // ---------- ô thông báo góc dưới phải của màn hình chờ: xoay vòng mỗi phút ----------
    // Đếm ngược Tết (chỉ trong mùa chờ Tết) -> BTCUSDT -> ETHUSDT -> lại từ đầu. Giá coin lấy mới đúng lúc tới lượt.

    private static final long NOTICE_MS = 60_000L;
    private int noticeIdx;
    private float noticeShift;   // độ dời để chân chữ thông báo trùng chân chữ dòng âm lịch (khác cỡ chữ)

    /** Đo sau khi bố cục xong: dời thông báo cho đường chân chữ trùng đúng dòng âm lịch bên trái. */
    private void alignNotice() {
        if (saverLunar == null || saverTet == null) return;
        int[] a = new int[2], b = new int[2];
        saverLunar.getLocationOnScreen(a);
        saverTet.getLocationOnScreen(b);
        float lunarBase = a[1] - saverLunar.getTranslationY() - saverText.getTranslationY() + saverLunar.getBaseline();
        float noticeBase = b[1] - saverTet.getTranslationY() + saverTet.getBaseline();
        noticeShift = Math.round(lunarBase - noticeBase);
        saverTet.setTranslationY(noticeShift + saverText.getTranslationY());
    }
    private final double[] lastChg = new double[2];
    private final Runnable noticeTick = new Runnable() {
        @Override public void run() {
            if (!saverOn) return;
            noticeIdx++;
            showNotice(true);
            ui.postDelayed(this, NOTICE_MS);
        }
    };

    private void showNotice(boolean fade) {
        String tet = Lunar.tetCountdown(java.util.Calendar.getInstance());
        // ngày của nhà đánh dấu "Màn hình khoá" = Yes (trong khoảng nhắc): thêm một lượt trong vòng xoay
        String mine = Days.lockLine(this);
        List<Integer> kinds = new ArrayList<>();
        if (!mine.isEmpty()) kinds.add(-2);
        if (!tet.isEmpty()) kinds.add(-1);
        kinds.add(0); kinds.add(1);
        int k = kinds.get(noticeIdx % kinds.size());
        if (k < 0) {
            android.text.SpannableString s = new android.text.SpannableString(k == -2 ? mine : tet);
            s.setSpan(new android.text.style.ForegroundColorSpan(0xA6FFE2B0), 0, s.length(), 0);   // vàng ấm dịu
            setNotice(s, fade);
            return;
        }
        // tới lượt coin: lấy giá mới rồi mới hiện (lỗi mạng thì dùng giá cuối đã biết)
        net.execute(() -> {
            Prices.Btc[] cs = Prices.crypto();
            ui.post(() -> {
                if (cs != null) for (int i = 0; i < cs.length; i++) if (cs[i] != null) {
                    lastCoin[i] = cs[i].price; lastChg[i] = cs[i].changePct;
                }
                if (saverOn) setNotice(coinNotice(k), fade);
            });
        });
    }

    /** "BTCUSDT  80.790,10  ▲ 0,65%": tên nhạt, giá trắng ngà, % xanh/đỏ dịu. */
    private CharSequence coinNotice(int i) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        String name = i == 0 ? "BTCUSDT" : "ETHUSDT";
        sb.append(name);
        sb.setSpan(new android.text.style.ForegroundColorSpan(0x8CFAF6EE), 0, sb.length(), 0);
        if (lastCoin[i] < 0) return sb;
        int p0 = sb.length();
        sb.append("   ").append(viFormat("#,##0.00").format(lastCoin[i]));
        sb.setSpan(new android.text.style.ForegroundColorSpan(0xD1FAF6EE), p0, sb.length(), 0);
        double ch = lastChg[i];
        String arrow = ch > 0.005 ? "▲ " : ch < -0.005 ? "▼ " : "";
        int col = ch > 0.005 ? 0xC78FE0AE : ch < -0.005 ? 0xC7FF9F94 : 0x8CFAF6EE;
        int c0 = sb.length();
        sb.append("   ").append(arrow).append(viFormat("0.00").format(Math.abs(ch))).append("%");
        sb.setSpan(new android.text.style.ForegroundColorSpan(col), c0, sb.length(), 0);
        return sb;
    }

    /** Đổi thông báo: mờ đi, thay chữ, hiện lại (chậm, nhẹ). */
    private void setNotice(CharSequence s, boolean fade) {
        if (saverTet == null) return;
        if (noticeFade != null) noticeFade.cancel();
        if (!fade) { saverTet.setText(s); saverTet.setAlpha(1f); return; }
        // mờ/hiện bằng bộ chạy riêng chỉ cho độ trong: KHÔNG gọi saverTet.animate().cancel(), vì lượt dịch chống lưu
        // ảnh (saverMove, 3 phút) trùng đúng lượt đổi thông báo (mỗi phút, cùng mốc bắt đầu): huỷ ở đây là cắt ngang
        // đường dịch, dòng thông báo đứng lại giữa chừng, lệch khỏi chân chữ dòng âm lịch
        TextView t = saverTet;
        android.animation.ObjectAnimator out = android.animation.ObjectAnimator.ofFloat(t, View.ALPHA, t.getAlpha(), 0f);
        out.setDuration(500);
        out.setInterpolator(EASE);
        out.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean cancelled;
            @Override public void onAnimationCancel(android.animation.Animator a) { cancelled = true; }
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (cancelled || saverTet != t) return;
                t.setText(s);
                noticeFade = android.animation.ObjectAnimator.ofFloat(t, View.ALPHA, 0f, 1f);
                noticeFade.setDuration(700);
                noticeFade.setInterpolator(EASE);
                noticeFade.start();
            }
        });
        noticeFade = out;
        out.start();
    }

    private android.animation.ObjectAnimator noticeFade;

    /** Độ chói cảm nhận trung bình (0..1) của một vùng ảnh, toạ độ tính theo tỉ lệ khung hình. */
    private static float regionGlare(Bitmap b, float x0, float y0, float x1, float y1) {
        int w = b.getWidth(), h = b.getHeight(), step = Math.max(1, Math.min(w, h) / 60);
        double sum = 0; int n = 0;
        for (int y = (int) (h * y0); y < (int) (h * y1); y += step) {
            for (int x = (int) (w * x0); x < (int) (w * x1); x += step) { sum += FrostView.glare(b.getPixel(x, y)); n++; }
        }
        return n == 0 ? 0f : (float) (sum / n / 255.0);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // màn hình chờ đang hiện: nút bấm đầu tiên chỉ để đánh thức, không tính là thao tác
        if (saverOn) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) { hideSaver(true); swallowUp = true; armSaver(); }
            return true;
        }
        if (swallowUp) {
            if (e.getAction() == KeyEvent.ACTION_UP) swallowUp = false;
            return true;
        }
        armSaver();
        if (remindCard != null && e.getAction() == KeyEvent.ACTION_DOWN) hideRemind(true);
        if (homePrompt != null) {
            // hộp nhắc đặt làm màn hình chính: Quay lại = Để sau; phím hướng chuyển giữa hai nút
            int kc = e.getKeyCode();
            if (kc == KeyEvent.KEYCODE_BACK || kc == KeyEvent.KEYCODE_ESCAPE) {
                if (e.getAction() == KeyEvent.ACTION_DOWN) closeHomePrompt(true);
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        if (settings != null) {
            // bảng Cài đặt: Quay lại đi lùi từng bước; các phím hướng để hệ thống tự chuyển trong bảng
            int kc = e.getKeyCode();
            if (kc == KeyEvent.KEYCODE_BACK || kc == KeyEvent.KEYCODE_ESCAPE) {
                if (e.getAction() == KeyEvent.ACTION_DOWN) settings.back();
                return true;
            }
            if (kc == KeyEvent.KEYCODE_ALL_APPS || kc == KeyEvent.KEYCODE_MENU) return true;
            return super.dispatchKeyEvent(e);
        }
        if (cal != null) {
            // bảng lịch: trái / phải xem tháng trước, tháng sau; OK, Quay lại, lên: đóng bảng (về đồng hồ);
            // xuống: đóng bảng và xuống hàng banner / dock như khi đứng ở đồng hồ
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                int kc = e.getKeyCode();
                if (kc == KeyEvent.KEYCODE_DPAD_LEFT || kc == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    boolean right = kc == KeyEvent.KEYCODE_DPAD_RIGHT;
                    if (calView.shift(right ? 1 : -1)) root.playSoundEffect(right
                            ? android.view.SoundEffectConstants.NAVIGATION_RIGHT : android.view.SoundEffectConstants.NAVIGATION_LEFT);
                } else if (kc == KeyEvent.KEYCODE_DPAD_DOWN) {
                    root.playSoundEffect(android.view.SoundEffectConstants.NAVIGATION_DOWN);
                    closeCal(true);
                    focusBelowTop();
                } else if (e.getRepeatCount() == 0 && (kc == KeyEvent.KEYCODE_BACK || kc == KeyEvent.KEYCODE_ESCAPE
                        || kc == KeyEvent.KEYCODE_DPAD_UP || isOk(kc))) {
                    root.playSoundEffect(kc == KeyEvent.KEYCODE_DPAD_UP
                            ? android.view.SoundEffectConstants.NAVIGATION_UP : android.view.SoundEffectConstants.CLICK);
                    closeCal(true);
                    if (!clock.hasFocus()) clock.requestFocus();
                }
            }
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            int kc = e.getKeyCode();
            if (kc == KeyEvent.KEYCODE_ALL_APPS) {   // nút APPS: sang ảnh nền kế tiếp
                if (allApps != null) closeAllApps();
                nextBackground();
                return true;
            }
            if (kc == KeyEvent.KEYCODE_MENU) {        // bảng Tất cả ứng dụng vẫn mở được bằng phím Menu
                if (allApps == null) openAllApps(); else closeAllApps();
                return true;
            }
            if (kc == KeyEvent.KEYCODE_BACK || kc == KeyEvent.KEYCODE_ESCAPE) {
                if (allApps != null) closeAllApps();
                else if (inShelf) exitShelf();
                return true;   // launcher không thoát khi bấm Back
            }
            if (allApps == null && eplBox.hasFocus()) {
                // đang chọn thẻ bóng đá
                boolean compact = eplView.mode() == EplView.COMPACT;
                if (kc == KeyEvent.KEYCODE_DPAD_UP) {
                    root.playSoundEffect(android.view.SoundEffectConstants.NAVIGATION_UP);
                    // phía trên thẻ không còn gì (Cài đặt đã ra giữa, sang bằng phím phải): ở khu trên cùng thì đứng yên
                    if (compact && eplView.selected() == EplView.SEL_TABLE && eplView.hasMatchPart()) eplView.setSelected(EplView.SEL_MATCH);
                    return true;
                }
                if (kc == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    // sang đồng hồ (giữa phía trên; nút Cài đặt hiện ra cạnh giờ), thẻ gọn hay đầy đủ đều vậy
                    if (clock.isFocusable()) {
                        root.playSoundEffect(android.view.SoundEffectConstants.NAVIGATION_RIGHT);
                        clock.requestFocus();
                    }
                    return true;
                }
                if (kc == KeyEvent.KEYCODE_DPAD_DOWN) {
                    root.playSoundEffect(android.view.SoundEffectConstants.NAVIGATION_DOWN);
                    if (compact && eplView.selected() == EplView.SEL_MATCH && eplView.hasTablePart()) eplView.setSelected(EplView.SEL_TABLE);
                    else focusBelowTop();
                    return true;
                }
                if (kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER) {
                    root.playSoundEffect(android.view.SoundEffectConstants.CLICK);
                    stepEpl(); return true;
                }
                return true;   // trái: đứng yên (mép màn hình)
            }
            if (allApps == null && clock.hasFocus()) {
                // ở đồng hồ (giữa phía trên):
                //   OK: mở bảng lịch; phải: sang nút Cài đặt; trái: về thẻ lịch thi đấu (nếu thẻ đang hiện)
                //   xuống: vào hàng banner nếu đang có, không thì về đúng ô app đang chọn trên dock; lên: đứng yên
                if (kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER || kc == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    if (e.getRepeatCount() == 0) {
                        root.playSoundEffect(android.view.SoundEffectConstants.CLICK);
                        openCal();
                    }
                    return true;
                }
                if (kc == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (gear.isFocusable()) {
                        root.playSoundEffect(android.view.SoundEffectConstants.NAVIGATION_RIGHT);
                        gear.requestFocus();
                    }
                    return true;
                }
                if (kc == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (eplSelectable()) {
                        root.playSoundEffect(android.view.SoundEffectConstants.NAVIGATION_LEFT);
                        focusEpl(false);
                    }
                    return true;
                }
                if (kc == KeyEvent.KEYCODE_DPAD_DOWN) {
                    root.playSoundEffect(android.view.SoundEffectConstants.NAVIGATION_DOWN);
                    focusBelowTop();
                    return true;
                }
                if (kc == KeyEvent.KEYCODE_DPAD_UP) return true;
            }
            if (allApps == null && gear.hasFocus()) {
                // ở nút Cài đặt (ngay sau đồng hồ):
                //   xuống: vào hàng banner nếu đang có, không thì về đúng ô app đang chọn trên dock
                //   trái: về đồng hồ; phải / lên: đứng yên
                if (kc == KeyEvent.KEYCODE_DPAD_DOWN) {
                    root.playSoundEffect(android.view.SoundEffectConstants.NAVIGATION_DOWN);
                    focusBelowTop();
                    return true;
                }
                if (kc == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (clock.isFocusable()) {
                        root.playSoundEffect(android.view.SoundEffectConstants.NAVIGATION_LEFT);
                        clock.requestFocus();
                    }
                    return true;
                }
                if (kc == KeyEvent.KEYCODE_DPAD_UP || kc == KeyEvent.KEYCODE_DPAD_RIGHT) return true;
            }
            if (allApps == null && inShelf && (kc == KeyEvent.KEYCODE_DPAD_LEFT || kc == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                // trong hàng thumb: sang đúng ô kề theo thứ tự (ô ngoài màn vẫn tới được, hàng tự cuộn); tới mép thì đứng yên
                int i = thumbs.indexOf(getCurrentFocus());
                if (i >= 0) {
                    int j = i + (kc == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                    if (j >= 0 && j < thumbs.size() && j < rowItems.size() && thumbs.get(j).isFocusable()) {
                        root.playSoundEffect(kc == KeyEvent.KEYCODE_DPAD_RIGHT
                                ? android.view.SoundEffectConstants.NAVIGATION_RIGHT : android.view.SoundEffectConstants.NAVIGATION_LEFT);
                        thumbs.get(j).requestFocus();
                    }
                    return true;
                }
            }
            if (allApps == null && (kc == KeyEvent.KEYCODE_DPAD_LEFT || kc == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                // trái/phải chỉ đi trong cùng một hàng: tới mép thì đứng yên, không nhảy sang dock/hàng nội dung
                // hay nút Cài đặt (Cài đặt chỉ lên được bằng phím lên)
                View cur = getCurrentFocus();
                View next = cur == null ? null
                        : cur.focusSearch(kc == KeyEvent.KEYCODE_DPAD_LEFT ? View.FOCUS_LEFT : View.FOCUS_RIGHT);
                ViewGroup row = inside(cur, shelfRow) ? shelfRow : inside(cur, tray) ? tray : null;
                if (next == null || next == gear || next == clock || (row != null && !inside(next, row))) return true;
            }
            if (allApps == null) {
                // hàng nội dung nằm trên dock: từ dock bấm lên vào hàng (hàng trống thì lên thẳng thẻ lịch / Cài đặt),
                // từ hàng bấm lên nữa thì lên thẻ lịch / Cài đặt, bấm xuống về dock.
                // các phím này launcher tự xử lý nên hệ thống không phát tiếng: gọi âm điều hướng hệ thống bằng tay
                if (kc == KeyEvent.KEYCODE_DPAD_UP) {
                    root.playSoundEffect(android.view.SoundEffectConstants.NAVIGATION_UP);
                    if (!inShelf) { enterShelf(); if (inShelf) return true; }
                    boolean fromRow = inShelf;
                    shelfState(false);
                    // từ hàng thumb lên: thẻ lịch đang mờ (vừa bắt đầu hiện lại) nhưng vẫn là đích đúng
                    boolean epl = fromRow ? eplBox.getVisibility() == View.VISIBLE && settings == null && homePrompt == null
                            : eplSelectable();
                    if (epl && (eplView.mode() != EplView.COMPACT || eplPart(true) != EplView.SEL_NONE)) focusEpl(true);
                    else clock.requestFocus();
                    return true;
                }
                if (kc == KeyEvent.KEYCODE_DPAD_DOWN && inShelf) {
                    root.playSoundEffect(android.view.SoundEffectConstants.NAVIGATION_DOWN);
                    exitShelf(); return true;
                }
                if (kc == KeyEvent.KEYCODE_DPAD_DOWN) return true;
                if (kc == KeyEvent.KEYCODE_DPAD_UP) return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    // ---------- tiện ích ----------

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            clock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
            int today = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR);
            if (today != remindCheckedDay) { remindCheckedDay = today; ui.post(remindLater); }
            updateSaverText();
            ui.postDelayed(this, 15000);
        }
    };

    private ImageView fullImage() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        return v;
    }

    /**
     * Chữ mịn kiểu Apple: font Inter đúng kích cỡ quang học (opsz) theo cỡ chữ, khử răng cưa + vị trí
     * dưới điểm ảnh, chữ to khít nhẹ; không bóng chữ (bóng nhoè làm viền chữ lem, "gai").
     */
    private TextView text(int weight, float sizePx, int color) {
        TextView t = new TextView(this);
        t.setTypeface(fontFor(weight, sizePx));
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(sizePx));
        t.setTextColor(color);
        t.setPaintFlags(t.getPaintFlags() | android.graphics.Paint.ANTI_ALIAS_FLAG
                | android.graphics.Paint.SUBPIXEL_TEXT_FLAG);
        t.setLetterSpacing(sizePx >= 28 ? -0.01f : 0f);
        t.setIncludeFontPadding(false);
        return t;
    }

    private final Map<String, Typeface> fonts = new java.util.HashMap<>();

    /** opsz của Inter chạy 14..32: chữ nhỏ nét thoáng dễ đọc, chữ to nét thanh gọn. */
    private Typeface fontFor(int weight, float sizePx) {
        int opsz = Math.max(14, Math.min(32, Math.round(sizePx)));
        String key = weight + "/" + opsz;
        Typeface tf = fonts.get(key);
        if (tf != null) return tf;
        try {
            tf = new Typeface.Builder(getAssets(), "fonts/Inter.ttf")
                    .setFontVariationSettings("'wght' " + weight + ", 'opsz' " + opsz).build();
        } catch (Exception e) {
            tf = Typeface.create("sans-serif", weight >= 600 ? Typeface.BOLD : Typeface.NORMAL);
        }
        fonts.put(key, tf);
        return tf;
    }

    /** Chữ tròn cho màn hình khoá (Quicksand, đầu nét bo tròn kiểu đồng hồ Apple Watch); wght chạy 300..700. */
    private Typeface roundFont(int weight) {
        String key = "round/" + weight;
        Typeface tf = fonts.get(key);
        if (tf != null) return tf;
        try {
            tf = new Typeface.Builder(getAssets(), "fonts/Quicksand.ttf")
                    .setFontVariationSettings("'wght' " + Math.max(300, Math.min(700, weight))).build();
        } catch (Exception e) {
            tf = fontFor(weight, 30);
        }
        fonts.put(key, tf);
        return tf;
    }

    private static Bitmap toBitmap(Drawable d, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        return b;
    }

    /** Hình bo góc chỉ dùng cho bóng đổ, không cắt nội dung (cắt theo outline dễ răng cưa). */
    private void shadowShape(View v, float r) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        v.setClipToOutline(false);
    }

    private Bitmap asset(String path) {
        try (InputStream in = getAssets().open(path)) {
            return BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            Bitmap b = Bitmap.createBitmap(480, 300, Bitmap.Config.ARGB_8888);
            b.eraseColor(0xFF2C2C30);
            return b;
        }
    }

    /** Thu nhỏ từng nửa (lọc song tuyến ở tỉ lệ 2:1 = trung bình 4 điểm, không răng cưa) rồi về đúng cỡ. */
    private static Bitmap scaleHQ(Bitmap b, int w, int h) {
        while (b.getWidth() / 2 >= w && b.getHeight() / 2 >= h) {
            b = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
        }
        if (b.getWidth() != w || b.getHeight() != h) b = Bitmap.createScaledBitmap(b, w, h, true);
        b.setHasMipMap(true);
        return b;
    }

    private SharedPreferences prefs() { return getSharedPreferences("glass", MODE_PRIVATE); }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }
}
