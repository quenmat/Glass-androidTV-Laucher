package vn.glass.home;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

// Lấy giá để hiển thị (chỉ để theo dõi): BTC/USDT từ cổng dữ liệu công khai của Binance,
// vàng nhẫn 999.9 từ PNJ, dự phòng Bảo Tín Minh Châu. Chạy ở luồng nền; lỗi thì trả null.
final class Prices {
    static final class Btc { double price, changePct; }
    static final class Gold { long sell, buy; String source; }

    // một lần gọi lấy cả BTC lẫn ETH (thứ tự trả về theo đúng danh sách symbols)
    static final String[] COINS = {"BTCUSDT", "ETHUSDT"};
    private static final String SYMBOLS = "?symbols=%5B%22BTCUSDT%22,%22ETHUSDT%22%5D";
    private static final String[] BTC_URLS = {
            "https://data-api.binance.vision/api/v3/ticker/24hr" + SYMBOLS,
            "https://api.binance.com/api/v3/ticker/24hr" + SYMBOLS,
    };
    private static final String PNJ = "https://edge-api.pnj.io/ecom-frontend/v1/get-gold-price?zone=00";
    private static final String BTMC = "http://api.btmc.vn/api/BTMCAPI/getpricebtmc?key=3kd8ub1llcg9t45hnoh8hmn7t5kc2v";

    /** Giá các đồng trong COINS, cùng thứ tự; lỗi thì null. */
    static Btc[] crypto() {
        for (String u : BTC_URLS) {
            try {
                JSONArray a = new JSONArray(get(u));
                Btc[] out = new Btc[COINS.length];
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    int k = java.util.Arrays.asList(COINS).indexOf(o.getString("symbol"));
                    if (k < 0) continue;
                    Btc b = new Btc();
                    b.price = Double.parseDouble(o.getString("lastPrice"));
                    b.changePct = Double.parseDouble(o.getString("priceChangePercent"));
                    out[k] = b;
                }
                return out;
            } catch (Exception ignored) {}
        }
        return null;
    }

    static Gold gold() {
        // PNJ: giá theo nghìn đồng / chỉ, mã N24K = Nhẫn trơn PNJ 999.9
        try {
            JSONArray a = new JSONObject(get(PNJ)).getJSONArray("data");
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if ("N24K".equals(o.optString("masp"))) {
                    Gold g = new Gold();
                    g.sell = o.getLong("giaban") * 1000L;
                    g.buy = o.getLong("giamua") * 1000L;
                    g.source = "PNJ";
                    if (g.sell > 0) return g;
                }
            }
        } catch (Exception ignored) {}
        // BTMC: các dòng có khoá @n_i (tên), @ps_i (bán), @pb_i (mua), đơn vị đồng / chỉ
        try {
            JSONArray a = new JSONObject(get(BTMC)).getJSONObject("DataList").getJSONArray("Data");
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String row = o.optString("@row");
                String name = o.optString("@n_" + row).toUpperCase();
                if (name.contains("NHẪN") && o.optString("@h_" + row).contains("999.9")) {
                    Gold g = new Gold();
                    g.sell = Long.parseLong(o.optString("@ps_" + row, "0"));
                    g.buy = Long.parseLong(o.optString("@pb_" + row, "0"));
                    g.source = "BTMC";
                    if (g.sell > 0) return g;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String get(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(7000);
        c.setRequestProperty("User-Agent", "GlassHome/0.1");
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private Prices() {}
}
