package vn.glass.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.List;

// Bảng Cài đặt của Glass Home: tấm kính tối trượt ra từ bên trái (cùng chỗ thẻ lịch bóng đá), 4 mục
// Ảnh nền Home / Màn hình chờ / Widget / Cài đặt hệ thống. Mục con trượt sang ngang như Apple TV.
// Gửi ảnh từ điện thoại là hộp giữa màn hình, có mã QR. Mọi chuyển động dùng chung một đường cong mềm.
final class SettingsPanel extends FrameLayout {
    interface Host {
        TextView text(int weight, float size, int color);
        int px(float v);
        Images images();
        Bitmap blur();
        /** Các đội của mùa hiện tại (tên + logo nhỏ), null nếu chưa tải được lịch. */
        List<Epl.Team> teams();
        /** what: "home", "lock", "saver", "widgets" */
        void changed(String what);
        void openSystemSettings();
        void closed();
    }

    private static final Interpolator EASE = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private static final int PX = 40, PY = 48, PW = 648, PH = 984, PR = 32;
    private static final int INK = 0xF2EEE6;
    private static int ink(int a) { return (a << 24) | INK; }

    private final Host h;
    private final View dim;
    private final FrameLayout panel, pages;
    private View page;          // trang đang hiện
    private String pageKey;     // null = trang chính
    private View drop;          // hộp gửi ảnh đang mở
    private PhotoDrop server;
    private boolean closing;

    SettingsPanel(Context c, Host host) {
        super(c);
        h = host;
        setClipChildren(false);
        dim = new View(c);
        dim.setBackgroundColor(0x7A000000);
        dim.setAlpha(0f);
        addView(dim, new LayoutParams(-1, -1));

        panel = new FrameLayout(c);
        int sw = getResources().getDisplayMetrics().widthPixels, sh = getResources().getDisplayMetrics().heightPixels;
        FrostView glass = new FrostView(c, px(PR), sw, sh, px(PX), px(PY), Math.max(1, px(1))).deep();
        if (h.blur() != null) glass.setImage(h.blur());
        panel.addView(glass, new LayoutParams(-1, -1));
        pages = new FrameLayout(c);
        panel.addView(pages, new LayoutParams(-1, -1));
        LayoutParams lp = new LayoutParams(px(PW), px(PH), Gravity.TOP | Gravity.START);
        lp.leftMargin = px(PX); lp.topMargin = px(PY);
        addView(panel, lp);

        // mở: kính trượt nhẹ từ trái vào và hiện dần, nền phía sau tối dần
        panel.setAlpha(0f);
        panel.setTranslationX(-px(56));
        dim.animate().alpha(1f).setDuration(320).setInterpolator(EASE).start();
        panel.animate().alpha(1f).translationX(0).setDuration(380).setInterpolator(EASE).withLayer().start();
        show(mainPage(), null, 0);
    }

    private int px(float v) { return h.px(v); }

    // ---------- điều hướng ----------

    /** Nút Quay lại: đóng hộp gửi ảnh, về trang chính, rồi đóng bảng. */
    void back() {
        if (drop != null) { closeDrop(); return; }
        if ("team".equals(pageKey)) { show(widgetPage(), "widgets", -1); return; }
        if (pageKey != null) { show(mainPage(), null, -1); return; }
        close(true);
    }

    void close(boolean animate) {
        if (closing) return;
        closing = true;
        if (server != null) { server.stop(); server = null; }
        Runnable gone = () -> { if (getParent() != null) ((ViewGroup) getParent()).removeView(this); };
        h.closed();
        if (!animate) { gone.run(); return; }
        dim.animate().alpha(0f).setDuration(240).setInterpolator(EASE).start();
        panel.animate().alpha(0f).translationX(-px(40)).setDuration(240).setInterpolator(EASE).withLayer().withEndAction(gone).start();
        if (drop != null) drop.animate().alpha(0f).setDuration(200).start();
    }

    /** Đổi trang: dir 1 = vào mục con (trang cũ lùi sang trái), -1 = quay lại, 0 = trang đầu tiên. */
    private void show(View next, String key, int dir) {
        View old = page;
        page = next;
        pageKey = key;
        pages.addView(next, new LayoutParams(-1, -1));
        if (old != null) {
            old.animate().alpha(0f).translationX(-dir * px(48)).setDuration(200).setInterpolator(EASE).withLayer()
                    .withEndAction(() -> pages.removeView(old)).start();
        }
        if (dir != 0) {
            next.setAlpha(0f);
            next.setTranslationX(dir * px(48));
            next.animate().alpha(1f).translationX(0).setStartDelay(60).setDuration(300).setInterpolator(EASE).withLayer().start();
        }
        View first = next.findViewWithTag(dir < 0 && old != null ? old.getTag() : "first");
        if (first == null) first = next.findViewWithTag("first");
        if (first != null) first.requestFocus();
    }

    // ---------- trang chính ----------

    private View mainPage() {
        LinearLayout col = column();
        col.addView(title("Cài đặt", null), lin(-1, -2, 0, 40));
        View home = row(Icon.IMAGE, "Ảnh nền Home", photoSummary(Wallpapers.HOME), "›", v -> show(photoPage(Wallpapers.HOME), Wallpapers.HOME, 1));
        home.setTag("first");
        col.addView(home);
        View lock = row(Icon.SAVER, "Màn hình chờ", saverSummary(), "›", v -> show(photoPage(Wallpapers.LOCK), Wallpapers.LOCK, 1));
        col.addView(lock, lin(-1, -2, 12, 0));
        View wid = row(Icon.WIDGET, "Widget", widgetSummary(), "›", v -> show(widgetPage(), "widgets", 1));
        col.addView(wid, lin(-1, -2, 12, 0));
        View line = new View(getContext());
        line.setBackgroundColor(0x1AFFFFFF);
        LinearLayout.LayoutParams ll = lin(-1, Math.max(1, px(2)), 18, 18);
        ll.leftMargin = ll.rightMargin = px(52);
        col.addView(line, ll);
        col.addView(row(Icon.SYSTEM, "Cài đặt hệ thống", "Mở Cài đặt của Google TV", "↗", v -> h.openSystemSettings()));
        // trang chính được dựng lại mỗi lần quay về: con trỏ về đúng mục vừa xem
        home.setTag(Wallpapers.HOME.equals(pageKey) || pageKey == null ? "first" : Wallpapers.HOME);
        lock.setTag(Wallpapers.LOCK);
        wid.setTag("widgets");
        FrameLayout f = new FrameLayout(getContext());
        f.addView(col, new LayoutParams(-1, -2));
        TextView hint = h.text(400, 19, ink(0x66));
        hint.setText("Bấm giữ nút bánh răng để mở thẳng Cài đặt hệ thống");
        LayoutParams lh = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        lh.leftMargin = px(52); lh.bottomMargin = px(36);
        f.addView(hint, lh);
        return f;
    }

    private String photoSummary(String set) {
        String src = Wallpapers.source(getContext(), set);
        String s = Wallpapers.BUILTIN.equals(src) ? "Ảnh kèm sẵn" : Wallpapers.MINE.equals(src) ? "Ảnh của bạn" : "Trộn hai bộ ảnh";
        return s + ", đổi mỗi " + minutes(Wallpapers.everyMin(getContext(), set));
    }

    private String saverSummary() {
        int after = Wallpapers.saverAfterMin(getContext());
        if (after == 0) return "Đang tắt";
        return "Bật sau " + after + " phút, đổi ảnh mỗi " + minutes(Wallpapers.everyMin(getContext(), Wallpapers.LOCK));
    }

    private String widgetSummary() {
        List<String> on = new java.util.ArrayList<>();
        if (Wallpapers.widget(getContext(), "btc")) on.add("BTC");
        if (Wallpapers.widget(getContext(), "eth")) on.add("ETH");
        if (Wallpapers.widget(getContext(), "gold")) on.add("vàng");
        boolean e = Wallpapers.widget(getContext(), "epl");
        if (on.isEmpty()) return e ? "Lịch Ngoại hạng Anh" : "Đang ẩn hết";
        String prices = "Giá " + String.join(", ", on);
        return e ? prices + " và lịch Ngoại hạng Anh" : prices;
    }

    private static String minutes(int m) { return m >= 60 ? (m / 60) + " giờ" : m + " phút"; }

    // ---------- trang Ảnh nền Home / Màn hình chờ ----------

    private View photoPage(String set) {
        boolean lock = Wallpapers.LOCK.equals(set);
        LinearLayout col = column();
        col.addView(title(lock ? "Màn hình chờ" : "Ảnh nền Home", "‹  Cài đặt"), lin(-1, -2, 0, 22));

        col.addView(section("NGUỒN ẢNH"));
        int nb = Wallpapers.builtin(getContext(), set).size(), nm = Wallpapers.user(getContext(), set).size();
        String[] keys = {Wallpapers.BUILTIN, Wallpapers.MINE, Wallpapers.MIX};
        String[] names = {"Ảnh kèm sẵn", "Ảnh của bạn", "Trộn cả hai"};
        String[] subs = {nb + " ảnh phong cảnh", nm == 0 ? "Chưa có ảnh nào" : nm + " ảnh đã gửi lên",
                (nb + nm) + " ảnh, xoay vòng lần lượt"};
        Radio[] radios = new Radio[3];
        for (int i = 0; i < 3; i++) {
            final String key = keys[i];
            radios[i] = new Radio(getContext());
            final Radio me = radios[i];
            View r = row(null, names[i], subs[i], null, v -> {
                Wallpapers.setSource(getContext(), set, key);
                for (Radio x : radios) x.set(x == me, true);
                h.changed(set);
            });
            ((ViewGroup) r).addView(me, rightCenter(px(30), px(30), 30));
            me.set(key.equals(Wallpapers.source(getContext(), set)), false);
            if (i == 0) r.setTag("first");
            col.addView(r, lin(-1, -2, i == 0 ? 0 : 6, 0));
        }

        if (lock) {
            col.addView(section("BẬT SAU"), lin(-1, -2, 26, 0));
            col.addView(segments(new int[]{3, 5, 10, 0}, new String[]{"3 phút", "5 phút", "10 phút", "Không bật"},
                    Wallpapers.saverAfterMin(getContext()), m -> { Wallpapers.setSaverAfterMin(getContext(), m); h.changed("saver"); }));
        }
        col.addView(section("ĐỔI ẢNH MỖI"), lin(-1, -2, 26, 0));
        int[] mins = lock ? new int[]{5, 15, 30, 60} : new int[]{5, 10, 30, 60};
        String[] labels = new String[mins.length];
        for (int i = 0; i < mins.length; i++) labels[i] = minutes(mins[i]);
        col.addView(segments(mins, labels, Wallpapers.everyMin(getContext(), set),
                m -> { Wallpapers.setEveryMin(getContext(), set, m); h.changed(set); }));

        View add = button("+  Thêm ảnh từ điện thoại", v -> openDrop(set));
        col.addView(add, lin(-1, px(64), 30, 0));

        List<File> mine = Wallpapers.user(getContext(), set);
        TextView sec = section("ẢNH CỦA BẠN" + (mine.isEmpty() ? "" : " (" + mine.size() + ")"));
        col.addView(sec, lin(-1, -2, 30, 0));
        if (mine.isEmpty()) {
            TextView none = h.text(400, 21, ink(0x73));
            none.setText("Gửi ảnh từ điện thoại để dùng ảnh của bạn.");
            col.addView(none, lin(-2, -2, 4, 0));
        } else {
            GridLayout grid = new GridLayout(getContext());
            grid.setColumnCount(3);
            grid.setClipChildren(false);
            for (File f : mine) grid.addView(thumb(f, set, grid, sec), thumbLp());
            LinearLayout.LayoutParams lg = lin(-2, -2, 4, 30);
            lg.leftMargin = px(52) - px(8);
            col.addView(grid, lg);
        }
        ScrollView sv = new ScrollView(getContext());
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipChildren(false);
        sv.setFillViewport(true);
        sv.setSmoothScrollingEnabled(true);
        sv.addView(col, new LayoutParams(-1, -2));
        sv.setTag(set);
        return sv;
    }

    private GridLayout.LayoutParams thumbLp() {
        GridLayout.LayoutParams g = new GridLayout.LayoutParams();
        g.width = px(168); g.height = px(95);
        g.setMargins(px(8), px(8), px(8), px(8));
        return g;
    }

    /** Ô ảnh của bạn: bấm OK một lần hiện "bấm lần nữa để xoá", bấm lần nữa thì xoá; đi chỗ khác là thôi. */
    private View thumb(File f, String set, GridLayout grid, TextView sec) {
        FrameLayout t = new FrameLayout(getContext());
        t.setFocusable(true);
        t.setClipChildren(false);
        ImageView img = new ImageView(getContext());
        img.setScaleType(ImageView.ScaleType.FIT_XY);
        GradientDrawable ph = new GradientDrawable();
        ph.setColor(0x14FFFFFF); ph.setCornerRadius(px(10));
        t.setBackground(ph);
        t.addView(img, new LayoutParams(-1, -1));
        h.images().into(img, android.net.Uri.fromFile(f).toString(), px(168), px(10));
        TextView ask = h.text(500, 17, ink(0xF2));
        ask.setText("Bấm lần nữa\nđể xoá");
        ask.setGravity(Gravity.CENTER);
        GradientDrawable ab = new GradientDrawable();
        ab.setColor(0xB8000000); ab.setCornerRadius(px(10));
        ask.setBackground(ab);
        ask.setAlpha(0f);
        t.addView(ask, new LayoutParams(-1, -1));
        View ring = new View(getContext());
        GradientDrawable rb = new GradientDrawable();
        rb.setCornerRadius(px(12)); rb.setStroke(Math.max(2, px(3)), 0xE6FFFFFF);
        ring.setBackground(rb);
        ring.setAlpha(0f);
        LayoutParams lr = new LayoutParams(-1, -1);
        lr.setMargins(-px(4), -px(4), -px(4), -px(4));
        t.addView(ring, lr);
        boolean[] armed = {false};
        t.setOnFocusChangeListener((v, has) -> {
            ring.animate().alpha(has ? 1f : 0f).setDuration(180).setInterpolator(EASE).start();
            t.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(220).setInterpolator(EASE).start();
            if (!has && armed[0]) { armed[0] = false; ask.animate().alpha(0f).setDuration(160).start(); }
        });
        t.setOnClickListener(v -> {
            if (!armed[0]) { armed[0] = true; ask.animate().alpha(1f).setDuration(180).setInterpolator(EASE).start(); return; }
            f.delete();
            int idx = grid.indexOfChild(t);
            View next = grid.getChildAt(idx + 1 < grid.getChildCount() ? idx + 1 : idx - 1);
            t.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(200).setInterpolator(EASE).withEndAction(() -> {
                grid.removeView(t);
                int n = grid.getChildCount();
                sec.setText("ẢNH CỦA BẠN" + (n == 0 ? "" : " (" + n + ")"));
            }).start();
            if (next != null && next != t) next.requestFocus();
            h.changed(set);
        });
        return t;
    }

    // ---------- trang Widget ----------

    private View widgetPage() {
        LinearLayout col = column();
        col.addView(title("Widget", "‹  Cài đặt"), lin(-1, -2, 0, 22));
        col.addView(section("GIÁ TÀI SẢN"));
        String[] keys = {"btc", "eth", "gold", "epl"};
        String[] names = {"BTC", "ETH", "Vàng", "Lịch thi đấu và bảng xếp hạng"};
        String[] subs = {"Bitcoin, giá theo USDT", "Ethereum, giá theo USDT", "Nhẫn trơn 999.9, giá bán ra",
                "Thẻ bên trái màn hình chính"};
        for (int i = 0; i < keys.length; i++) {
            final String key = keys[i];
            if (i == 3) col.addView(section("NGOẠI HẠNG ANH"), lin(-1, -2, 26, 0));
            Switch sw = new Switch(getContext());
            sw.set(Wallpapers.widget(getContext(), key), false);
            View r = row(null, names[i], subs[i], null, v -> {
                boolean on = !Wallpapers.widget(getContext(), key);
                Wallpapers.setWidget(getContext(), key, on);
                sw.set(on, true);
                h.changed("widgets");
            });
            ((ViewGroup) r).addView(sw, rightCenter(px(56), px(32), 28));
            if (i == 0) r.setTag("first");
            col.addView(r, lin(-1, -2, i == 0 || i == 3 ? 0 : 4, 0));
        }
        View fav = row(null, "Đội yêu thích", favName(), "›", v -> show(teamPage(), "team", 1));
        fav.setTag("team");
        col.addView(fav, lin(-1, -2, 4, 0));
        TextView note = h.text(400, 19, ink(0x66));
        note.setText("Trận đấu và thứ hạng của đội này được làm nổi bật.");
        note.setPadding(px(52), px(10), px(52), 0);
        col.addView(note);
        ScrollView sv = new ScrollView(getContext());
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipChildren(false);
        sv.addView(col, new LayoutParams(-1, -2));
        sv.setTag("widgets");
        return sv;
    }

    private String favName() {
        List<Epl.Team> ts = h.teams();
        int code = Wallpapers.favTeam(getContext());
        if (ts != null) for (Epl.Team t : ts) if (t.code == code) return t.name;
        return code == 1 ? "Man United" : "Chưa chọn";
    }

    /** Danh sách 20 đội theo tên, có logo; chọn đội nào thì chấm tròn nhảy sang đội đó. */
    private View teamPage() {
        LinearLayout col = column();
        col.addView(title("Đội yêu thích", "‹  Widget"), lin(-1, -2, 0, 22));
        List<Epl.Team> ts = h.teams();
        if (ts == null || ts.isEmpty()) {
            TextView none = h.text(400, 21, ink(0x80));
            none.setText("Chưa tải được danh sách đội, thử lại sau ít phút.");
            none.setPadding(px(52), 0, px(52), 0);
            col.addView(none);
            return col;
        }
        int cur = Wallpapers.favTeam(getContext());
        Radio[] radios = new Radio[ts.size()];
        View first = null;
        for (int i = 0; i < ts.size(); i++) {
            Epl.Team t = ts.get(i);
            radios[i] = new Radio(getContext());
            final Radio me = radios[i];
            View r = row(null, t.name, null, null, v -> {
                Wallpapers.setFavTeam(getContext(), t.code);
                for (Radio x : radios) x.set(x == me, true);
                h.changed("team");
            });
            if (t.badge != null) {
                ImageView b = new ImageView(getContext());
                b.setImageBitmap(t.badge);
                b.setScaleType(ImageView.ScaleType.CENTER);
                LayoutParams lb = new LayoutParams(px(40), px(40), Gravity.START | Gravity.CENTER_VERTICAL);
                lb.leftMargin = px(46);
                ((ViewGroup) r).addView(b, lb);
            }
            ((ViewGroup) r).addView(me, rightCenter(px(28), px(28), 30));
            me.set(t.code == cur, false);
            if (t.code == cur || first == null) { if (first != null) first.setTag(null); r.setTag("first"); first = r; }
            col.addView(r);
        }
        ScrollView sv = new ScrollView(getContext());
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipChildren(false);
        sv.addView(col, new LayoutParams(-1, -2));
        sv.setTag("team");
        return sv;
    }

    // ---------- gửi ảnh từ điện thoại ----------

    private void openDrop(String set) {
        if (drop != null) return;
        View back = getFocusedChild() == null ? null : findFocus();
        FrameLayout box = new FrameLayout(getContext());
        View shade = new View(getContext());
        shade.setBackgroundColor(0xA6000000);   // bảng Cài đặt phía sau lùi hẳn xuống
        box.addView(shade, new LayoutParams(-1, -1));

        int cw = 980, chh = 560;
        int cx = (1920 - cw) / 2, cy = (1080 - chh) / 2;
        FrameLayout card = new FrameLayout(getContext());
        int sw = getResources().getDisplayMetrics().widthPixels, sh = getResources().getDisplayMetrics().heightPixels;
        FrostView glass = new FrostView(getContext(), px(36), sw, sh, px(cx), px(cy), Math.max(1, px(1))).deep();
        if (h.blur() != null) glass.setImage(h.blur());
        card.addView(glass, new LayoutParams(-1, -1));
        LayoutParams lc = new LayoutParams(px(cw), px(chh), Gravity.TOP | Gravity.START);
        lc.leftMargin = px(cx); lc.topMargin = px(cy);
        box.addView(card, lc);

        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(64), px(56), px(64 + 340 + 48), 0);   // chữ dừng trước cột mã QR, dòng dài tự xuống dòng
        TextView t = h.text(600, 40, ink(0xF0));
        t.setText(Wallpapers.LOCK.equals(set) ? "Gửi ảnh màn hình chờ" : "Gửi ảnh nền Home");
        col.addView(t);
        String[] steps = {"1.  Điện thoại bắt cùng Wi-Fi với tivi", "2.  Mở camera, quét mã bên phải", "3.  Chọn ảnh trong máy, ảnh tự gửi lên"};
        for (int i = 0; i < steps.length; i++) {
            TextView s = h.text(500, 27, ink(0xD0));
            s.setText(steps[i]);
            col.addView(s, lin(-2, -2, i == 0 ? 34 : 20, 0));
        }
        TextView or = h.text(400, 21, ink(0x80));
        or.setText("Hoặc mở trình duyệt:");
        col.addView(or, lin(-2, -2, 40, 0));
        TextView url = h.text(600, 24, ink(0xDC));
        col.addView(url, lin(-2, -2, 8, 0));
        TextView status = h.text(400, 19, ink(0x80));
        status.setText("Chưa nhận ảnh nào");
        col.addView(status, lin(-2, -2, 40, 0));
        card.addView(col, new LayoutParams(-1, -1));

        ImageView qr = new ImageView(getContext());
        LayoutParams lq = new LayoutParams(px(340), px(340), Gravity.END | Gravity.TOP);
        lq.rightMargin = px(64); lq.topMargin = px(70);
        card.addView(qr, lq);

        View done = button("Xong", v -> closeDrop());
        LayoutParams ld = new LayoutParams(px(340), px(64), Gravity.END | Gravity.BOTTOM);
        ld.rightMargin = px(64); ld.bottomMargin = px(48);
        card.addView(done, ld);

        server = new PhotoDrop(Wallpapers.userDir(getContext(), set), n -> {
            status.setText("Đã nhận " + n + " ảnh, bấm Xong khi gửi hết");
            status.setTextColor(0xD97FD99C);
            h.changed(set);
        });
        String u = server.start(getContext());
        if (u == null) {
            server = null;
            url.setText("Tivi chưa kết nối mạng");
            status.setText("Kết nối Wi-Fi hoặc dây mạng cho tivi rồi thử lại");
        } else {
            url.setText(u);
            qr.setImageBitmap(qrBitmap(u, px(340)));
            TextView tip = h.text(400, 19, ink(0x66));
            tip.setText("Không mở được trang? Điện thoại có thể đang dùng 4G hoặc Wi-Fi khách.");
            col.addView(tip, lin(-2, -2, 8, 0));
        }

        box.setAlpha(0f);
        card.setScaleX(0.96f); card.setScaleY(0.96f);
        addView(box, new LayoutParams(-1, -1));
        drop = box;
        dropReturn = back;
        panel.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        box.animate().alpha(1f).setDuration(260).setInterpolator(EASE).start();
        card.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(EASE).withLayer().start();
        done.requestFocus();
    }

    private View dropReturn;

    private void closeDrop() {
        if (drop == null) return;
        if (server != null) { server.stop(); server = null; }
        View d = drop;
        drop = null;
        d.animate().alpha(0f).setDuration(220).setInterpolator(EASE).withEndAction(() -> removeView(d)).start();
        panel.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        // dựng lại trang để thấy ảnh vừa gửi; con trỏ về nút "Thêm ảnh"
        if (pageKey != null && !"widgets".equals(pageKey)) {
            View p = photoPage(pageKey);
            View old = page;
            page = p;
            pages.addView(p, new LayoutParams(-1, -1));
            pages.removeView(old);
            View add = p.findViewWithTag("add");
            if (add != null) add.requestFocus();
        } else if (dropReturn != null) dropReturn.requestFocus();
    }

    /** Mã QR trên nền kem bo góc, mỗi ô vuông đúng số điểm ảnh nguyên để không nhoè. */
    private static Bitmap qrBitmap(String text, int size) {
        QrCode q = QrCode.of(text);
        int quiet = 3, cells = q.size + quiet * 2, cell = Math.max(1, size / cells);
        int off = (size - cell * cells) / 2 + cell * quiet;
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFF6F4EE);
        c.drawRoundRect(new RectF(0, 0, size, size), size * 0.06f, size * 0.06f, p);
        p.setAntiAlias(false);
        p.setColor(0xFF141418);
        for (int y = 0; y < q.size; y++)
            for (int x = 0; x < q.size; x++)
                if (q.get(x, y)) c.drawRect(off + x * cell, off + y * cell, off + (x + 1) * cell, off + (y + 1) * cell, p);
        return b;
    }

    // ---------- khối dựng chung ----------

    private LinearLayout column() {
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, px(48), 0, px(40));
        col.setClipChildren(false);
        col.setClipToPadding(false);
        return col;
    }

    private LinearLayout.LayoutParams lin(int w, int hh, int top, int bottom) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(w, hh);
        l.topMargin = px(top); l.bottomMargin = px(bottom);
        return l;
    }

    private LayoutParams rightCenter(int w, int hh, int right) {
        LayoutParams l = new LayoutParams(w, hh, Gravity.END | Gravity.CENTER_VERTICAL);
        l.rightMargin = px(right);
        return l;
    }

    private View title(String t, String backLabel) {
        LinearLayout l = new LinearLayout(getContext());
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(px(52), 0, px(52), 0);
        if (backLabel != null) {
            TextView b = h.text(500, 19, ink(0x70));
            b.setText(backLabel);
            l.addView(b, lin(-2, -2, 0, 10));
        }
        TextView tv = h.text(600, 40, ink(0xF0));
        tv.setText(t);
        l.addView(tv);
        return l;
    }

    private TextView section(String s) {
        TextView t = h.text(500, 18, ink(0x6B));
        t.setText(s);
        t.setLetterSpacing(0.06f);
        t.setIncludeFontPadding(true);   // chữ hoa có hai dấu (Ỗ, Ổ) cao hơn khung chữ: chừa chỗ để dấu không bị xén
        t.setPadding(px(52), px(4), px(52), px(10));
        return t;
    }

    /** Một dòng chọn được: nền sáng mờ hiện dần khi chọn, chữ sáng lên; có biểu tượng thì đặt trong ô bo góc bên trái. */
    private View row(Icon icon, String title, String sub, String arrow, OnClickListener click) {
        FrameLayout r = new FrameLayout(getContext());
        r.setFocusable(true);
        r.setClickable(true);
        r.setOnClickListener(click);
        // nền sáng khi chọn là nền của chính dòng (không phải view con kéo giãn: dòng cao đúng theo chữ)
        GradientDrawable hb = new GradientDrawable();
        hb.setColor(0x24FFFFFF); hb.setCornerRadius(px(20));
        hb.setAlpha(0);
        r.setBackground(new android.graphics.drawable.InsetDrawable(hb, px(24), 0, px(24), 0));
        IconView iv = null;
        int textLeft = 52;
        if (icon != null) {
            iv = new IconView(getContext(), icon);
            LayoutParams li = new LayoutParams(px(60), px(60), Gravity.START | Gravity.CENTER_VERTICAL);
            li.leftMargin = px(50);
            r.addView(iv, li);
            textLeft = 130;
        }
        if (sub == null) textLeft = 102;   // dòng một tầng chữ (danh sách đội): chừa chỗ logo bên trái
        LinearLayout tx = new LinearLayout(getContext());
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding(px(textLeft), px(sub == null ? 16 : 20), px(84), px(sub == null ? 16 : 22));
        TextView t1 = h.text(500, 28, ink(0xD0));
        t1.setText(title);
        t1.setSingleLine(true);
        tx.addView(t1);
        TextView t2 = h.text(400, 21, ink(0x80));
        t2.setText(sub);
        t2.setSingleLine(true);
        t2.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (sub != null) tx.addView(t2, lin(-2, -2, 8, 0));
        r.addView(tx, new LayoutParams(-1, -2, Gravity.CENTER_VERTICAL));
        TextView ar = null;
        if (arrow != null) {
            ar = h.text(500, 28, ink(0x5C));
            ar.setText(arrow);
            r.addView(ar, rightCenter(-2, -2, 52));
        }
        final IconView fi = iv;
        final TextView fa = ar;
        r.setOnFocusChangeListener((v, has) -> {
            ValueAnimator ha = ValueAnimator.ofInt(hb.getAlpha(), has ? 255 : 0);
            ha.setDuration(200);
            ha.setInterpolator(EASE);
            ha.addUpdateListener(an -> hb.setAlpha((int) an.getAnimatedValue()));
            ha.start();
            fade(t1, has ? 0xF5 : 0xD0);
            fade(t2, has ? 0x99 : 0x80);
            if (fa != null) fade(fa, has ? 0xB3 : 0x5C);
            if (fi != null) fi.lit(has);
        });
        return r;
    }

    /** Đổi độ đậm chữ mượt (không nhảy màu). */
    private static void fade(TextView t, int alpha) {
        int from = t.getCurrentTextColor() >>> 24;
        if (from == alpha) return;
        ValueAnimator a = ValueAnimator.ofInt(from, alpha);
        a.setDuration(200);
        a.setInterpolator(EASE);
        a.addUpdateListener(an -> t.setTextColor(ink((int) an.getAnimatedValue())));
        a.start();
    }

    private View button(String s, OnClickListener click) {
        TextView b = h.text(500, 22, ink(0xD9));
        b.setText(s);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnClickListener(click);
        b.setTag("add");
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(16));
        bg.setColor(0x1AFFFFFF);
        bg.setStroke(Math.max(1, px(1.5f)), 0x29FFFFFF);
        b.setBackground(bg);
        FrameLayout wrap = new FrameLayout(getContext());
        LayoutParams lb = new LayoutParams(-1, -1);
        lb.leftMargin = lb.rightMargin = px(52);
        wrap.addView(b, lb);
        b.setOnFocusChangeListener((v, has) -> {
            ValueAnimator a = ValueAnimator.ofArgb(has ? 0x1AFFFFFF : 0xEBF2EEE6, has ? 0xEBF2EEE6 : 0x1AFFFFFF);
            a.setDuration(220);
            a.setInterpolator(EASE);
            a.addUpdateListener(an -> bg.setColor((int) an.getAnimatedValue()));
            a.start();
            b.setTextColor(has ? 0xFF1C1C1E : ink(0xD9));
            b.animate().scaleX(has ? 1.03f : 1f).scaleY(has ? 1.03f : 1f).setDuration(220).setInterpolator(EASE).start();
        });
        return wrap;
    }

    interface IntPick { void on(int v); }

    /** Thanh chọn nhiều mức (5 phút / 10 phút...): ô đang dùng có nền sáng, ô đang trỏ tới có nền trắng. */
    private View segments(int[] values, String[] labels, int current, IntPick pick) {
        LinearLayout bar = new LinearLayout(getContext());
        bar.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable track = new GradientDrawable();
        track.setColor(0x14FFFFFF); track.setCornerRadius(px(16));
        bar.setBackground(track);
        bar.setPadding(px(5), px(5), px(5), px(5));
        TextView[] segs = new TextView[values.length];
        GradientDrawable[] fills = new GradientDrawable[values.length];
        int[] sel = {current};
        for (int i = 0; i < values.length; i++) {
            final int v = values[i];
            TextView s = h.text(500, 21, ink(values[i] == current ? 0xEB : 0x8C));
            s.setText(labels[i]);
            s.setGravity(Gravity.CENTER);
            s.setFocusable(true);
            s.setClickable(true);
            GradientDrawable f = new GradientDrawable();
            f.setCornerRadius(px(12));
            f.setColor(values[i] == current ? 0x2EFFFFFF : 0x00FFFFFF);
            s.setBackground(f);
            segs[i] = s; fills[i] = f;
            s.setOnClickListener(x -> {
                sel[0] = v;
                pick.on(v);
                for (int j = 0; j < segs.length; j++) paintSeg(segs[j], fills[j], values[j] == sel[0], segs[j].hasFocus());
            });
            s.setOnFocusChangeListener((x, has) -> {
                for (int j = 0; j < segs.length; j++) paintSeg(segs[j], fills[j], values[j] == sel[0], segs[j] == x && has);
            });
            bar.addView(s, new LinearLayout.LayoutParams(0, px(52), 1f));
        }
        FrameLayout wrap = new FrameLayout(getContext());
        LayoutParams lb = new LayoutParams(-1, -2);
        lb.leftMargin = lb.rightMargin = px(52);
        wrap.addView(bar, lb);
        return wrap;
    }

    private void paintSeg(TextView s, GradientDrawable f, boolean selected, boolean focused) {
        int to = focused ? 0xEBF2EEE6 : selected ? 0x2EFFFFFF : 0x00FFFFFF;
        Object prev = s.getTag();
        int from = prev instanceof Integer ? (Integer) prev : (selected ? 0x2EFFFFFF : 0x00FFFFFF);
        s.setTag(to);
        if (from != to) {
            ValueAnimator a = ValueAnimator.ofArgb(from, to);
            a.setDuration(200);
            a.setInterpolator(EASE);
            a.addUpdateListener(an -> f.setColor((int) an.getAnimatedValue()));
            a.start();
        }
        s.setTextColor(focused ? 0xFF1C1C1E : ink(selected ? 0xEB : 0x8C));
    }

    // ---------- biểu tượng, nút tròn, công tắc ----------

    enum Icon { IMAGE, SAVER, WIDGET, SYSTEM }

    /** Biểu tượng nét mảnh trong ô bo góc; khi dòng được chọn thì ô và nét sáng lên. */
    private final class IconView extends View {
        private final Icon kind;
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
                tile = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private float lit;

        IconView(Context c, Icon k) {
            super(c);
            kind = k;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
        }

        void lit(boolean on) {
            ValueAnimator a = ValueAnimator.ofFloat(lit, on ? 1f : 0f);
            a.setDuration(200);
            a.setInterpolator(EASE);
            a.addUpdateListener(an -> { lit = (float) an.getAnimatedValue(); invalidate(); });
            a.start();
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), s = w / 60f;
            tile.setColor(((int) (0x16 + 0x10 * lit) << 24) | 0xFFFFFF);
            c.drawRoundRect(0, 0, w, getHeight(), 16 * s, 16 * s, tile);
            int col = ((int) (0xB0 + 0x45 * lit) << 24) | INK;
            stroke.setColor(col);
            stroke.setStrokeWidth(2.6f * s);
            fill.setColor(col);
            float cx = w / 2f, cy = getHeight() / 2f;
            switch (kind) {
                case IMAGE: {
                    c.drawRoundRect(cx - 15 * s, cy - 12 * s, cx + 15 * s, cy + 12 * s, 4 * s, 4 * s, stroke);
                    path.reset();
                    path.moveTo(cx - 13 * s, cy + 9 * s); path.lineTo(cx - 4 * s, cy - 1 * s); path.lineTo(cx + 2 * s, cy + 5 * s);
                    path.lineTo(cx + 6 * s, cy + 1 * s); path.lineTo(cx + 13 * s, cy + 8 * s);
                    c.drawPath(path, stroke);
                    c.drawCircle(cx + 6.5f * s, cy - 5.5f * s, 2.6f * s, fill);
                    break;
                }
                case SAVER: {
                    // trăng khuyết: hình tròn trừ đi hình tròn lệch lên phải
                    path.reset();
                    path.addCircle(cx - 1 * s, cy + 1 * s, 13 * s, Path.Direction.CW);
                    Path cut = new Path();
                    cut.addCircle(cx + 6 * s, cy - 5 * s, 12 * s, Path.Direction.CW);
                    path.op(cut, Path.Op.DIFFERENCE);
                    c.drawPath(path, fill);
                    c.drawCircle(cx + 11 * s, cy - 12 * s, 2 * s, fill);
                    break;
                }
                case WIDGET: {
                    for (float gx : new float[]{-13, 2})
                        for (float gy : new float[]{-13, 2})
                            c.drawRoundRect(cx + gx * s, cy + gy * s, cx + (gx + 11) * s, cy + (gy + 11) * s, 3 * s, 3 * s, stroke);
                    break;
                }
                case SYSTEM: {
                    Paint teeth = new Paint(stroke);
                    teeth.setStrokeWidth(4.5f * s);
                    teeth.setStrokeCap(Paint.Cap.BUTT);
                    for (int i = 0; i < 8; i++) {
                        double t = i * Math.PI / 4;
                        c.drawLine(cx + (float) (10 * Math.cos(t)) * s, cy + (float) (10 * Math.sin(t)) * s,
                                cx + (float) (15 * Math.cos(t)) * s, cy + (float) (15 * Math.sin(t)) * s, teeth);
                    }
                    c.drawCircle(cx, cy, 11 * s, stroke);
                    c.drawCircle(cx, cy, 4.5f * s, stroke);
                    break;
                }
            }
        }
    }

    /** Nút chọn tròn: chấm giữa phóng ra/thu lại khi đổi. */
    private static final class Radio extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float on;

        Radio(Context c) { super(c); }

        void set(boolean v, boolean animate) {
            if (!animate) { on = v ? 1f : 0f; invalidate(); return; }
            ValueAnimator a = ValueAnimator.ofFloat(on, v ? 1f : 0f);
            a.setDuration(240);
            a.setInterpolator(EASE);
            a.addUpdateListener(an -> { on = (float) an.getAnimatedValue(); invalidate(); });
            a.start();
        }

        @Override protected void onDraw(Canvas c) {
            float r = getWidth() / 2f, sw = getWidth() / 15f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(sw);
            p.setColor(((int) (0x5C + 0x8F * on) << 24) | INK);
            c.drawCircle(r, r, r - sw, p);
            if (on > 0.01f) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(((int) (0xF0 * on) << 24) | INK);
                c.drawCircle(r, r, (r * 0.5f) * on, p);
            }
        }
    }

    /** Công tắc bật/tắt: núm trượt và nền đổi màu cùng lúc. */
    private static final class Switch extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float on;

        Switch(Context c) { super(c); }

        void set(boolean v, boolean animate) {
            if (!animate) { on = v ? 1f : 0f; invalidate(); return; }
            ValueAnimator a = ValueAnimator.ofFloat(on, v ? 1f : 0f);
            a.setDuration(260);
            a.setInterpolator(EASE);
            a.addUpdateListener(an -> { on = (float) an.getAnimatedValue(); invalidate(); });
            a.start();
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), hh = getHeight(), r = hh / 2f;
            int off = 0x33FFFFFF, onC = 0xD97FD99C;
            p.setColor((Integer) new android.animation.ArgbEvaluator().evaluate(on, off, onC));
            c.drawRoundRect(0, 0, w, hh, r, r, p);
            p.setColor(0xFFF6F4EE);
            float knob = r - hh * 0.1f;
            c.drawCircle(r + (w - 2 * r) * on, r, knob, p);
        }
    }
}
