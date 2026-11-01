package vn.glass.home;

// Mã QR tối giản cho đường dẫn gửi ảnh: chế độ byte, mức sửa lỗi M, phiên bản 1..10 (tới ~210 ký tự).
// Theo đúng chuẩn ISO 18004: dữ liệu + mã Reed-Solomon chia khối, đặt mẫu cố định, chọn mặt nạ ít "phạt" nhất.
// Không phụ thuộc Android để thử được trên máy tính (tools/qr_test).
final class QrCode {
    final int size;
    private final boolean[][] dark, fixed;

    private static final int[] ECC_PER_BLOCK = {0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26};
    private static final int[] BLOCKS = {0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5};
    private static final int ECL_M = 0;   // bit định dạng của mức M

    boolean get(int x, int y) { return dark[y][x]; }

    static QrCode of(String text) {
        byte[] data;
        try { data = text.getBytes("UTF-8"); } catch (Exception e) { throw new IllegalStateException(e); }
        for (int v = 1; v <= 10; v++) {
            int cap = rawModules(v) / 8 - ECC_PER_BLOCK[v] * BLOCKS[v];
            int countBits = v <= 9 ? 8 : 16;
            if (4 + countBits + data.length * 8 <= cap * 8) return new QrCode(v, data, cap, countBits);
        }
        throw new IllegalArgumentException("chuoi qua dai cho ma QR");
    }

    private QrCode(int ver, byte[] text, int cap, int countBits) {
        size = ver * 4 + 17;
        dark = new boolean[size][size];
        fixed = new boolean[size][size];

        // chuỗi bit: chế độ byte (0100), độ dài, dữ liệu, kết thúc, đệm 0xEC 0x11
        BitBuf bb = new BitBuf();
        bb.put(4, 4);
        bb.put(text.length, countBits);
        for (byte b : text) bb.put(b & 0xFF, 8);
        bb.put(0, Math.min(4, cap * 8 - bb.n));
        bb.put(0, (8 - bb.n % 8) % 8);
        for (int pad = 0xEC; bb.n < cap * 8; pad ^= 0xEC ^ 0x11) bb.put(pad, 8);
        byte[] codewords = interleave(ver, bb.bytes());

        drawFixed(ver);
        drawCodewords(codewords);
        int best = 0;
        long bestScore = Long.MAX_VALUE;
        for (int m = 0; m < 8; m++) {
            mask(m); drawFormat(m);
            long s = penalty();
            if (s < bestScore) { bestScore = s; best = m; }
            mask(m);   // mặt nạ là phép XOR: áp lần nữa để gỡ
        }
        mask(best); drawFormat(best);
    }

    // ---------- mẫu cố định ----------

    private void set(int x, int y, boolean on) { dark[y][x] = on; fixed[y][x] = true; }

    private void drawFixed(int ver) {
        for (int i = 0; i < size; i++) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0); }
        finder(3, 3); finder(size - 4, 3); finder(3, size - 4);
        int[] al = alignPositions(ver);
        int n = al.length;
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                if (!(i == 0 && j == 0 || i == 0 && j == n - 1 || i == n - 1 && j == 0)) align(al[i], al[j]);
        drawFormat(0);   // giữ chỗ, vẽ lại sau khi chọn mặt nạ
        if (ver >= 7) {
            int rem = ver;
            for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
            int bits = ver << 12 | rem;
            for (int i = 0; i < 18; i++) {
                boolean b = ((bits >>> i) & 1) != 0;
                int a = size - 11 + i % 3, c = i / 3;
                set(a, c, b); set(c, a, b);
            }
        }
    }

    private void finder(int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++)
            for (int dx = -4; dx <= 4; dx++) {
                int x = cx + dx, y = cy + dy;
                if (x < 0 || y < 0 || x >= size || y >= size) continue;
                int d = Math.max(Math.abs(dx), Math.abs(dy));
                set(x, y, d != 2 && d != 4);
            }
    }

    private void align(int cx, int cy) {
        for (int dy = -2; dy <= 2; dy++)
            for (int dx = -2; dx <= 2; dx++) set(cx + dx, cy + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
    }

    private static int[] alignPositions(int ver) {
        if (ver == 1) return new int[0];
        int n = ver / 7 + 2;
        int step = (ver * 4 + n * 2 + 1) / (n * 2 - 2) * 2;
        int[] r = new int[n];
        r[0] = 6;
        for (int i = n - 1, pos = ver * 4 + 10; i >= 1; i--, pos -= step) r[i] = pos;
        return r;
    }

    private void drawFormat(int msk) {
        int data = ECL_M << 3 | msk, rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) set(8, i, bit(bits, i));
        set(8, 7, bit(bits, 6)); set(8, 8, bit(bits, 7)); set(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(8, size - 15 + i, bit(bits, i));
        set(8, size - 8, true);
    }

    private static boolean bit(int v, int i) { return ((v >>> i) & 1) != 0; }

    // ---------- dữ liệu ----------

    private static int rawModules(int ver) {
        int r = (16 * ver + 128) * ver + 64;
        if (ver >= 2) {
            int n = ver / 7 + 2;
            r -= (25 * n - 10) * n - 55;
            if (ver >= 7) r -= 36;
        }
        return r;
    }

    private static byte[] interleave(int ver, byte[] data) {
        int nb = BLOCKS[ver], ecc = ECC_PER_BLOCK[ver], raw = rawModules(ver) / 8;
        int shortN = nb - raw % nb, shortLen = raw / nb;
        byte[] div = rsDivisor(ecc);
        byte[][] blocks = new byte[nb][];
        for (int i = 0, k = 0; i < nb; i++) {
            int len = shortLen - ecc + (i < shortN ? 0 : 1);
            byte[] dat = java.util.Arrays.copyOfRange(data, k, k + len);
            k += len;
            byte[] e = rsRemainder(dat, div);
            byte[] blk = new byte[shortLen + 1];
            System.arraycopy(dat, 0, blk, 0, len);
            System.arraycopy(e, 0, blk, blk.length - ecc, ecc);   // khối ngắn: chừa một ô trống trước mã sửa lỗi
            blocks[i] = blk;
        }
        byte[] out = new byte[raw];
        int o = 0;
        for (int i = 0; i < shortLen + 1; i++)
            for (int j = 0; j < nb; j++)
                if (i != shortLen - ecc || j >= shortN) out[o++] = blocks[j][i];
        return out;
    }

    private static byte[] rsDivisor(int deg) {
        byte[] r = new byte[deg];
        r[deg - 1] = 1;
        int root = 1;
        for (int i = 0; i < deg; i++) {
            for (int j = 0; j < deg; j++) {
                r[j] = (byte) mul(r[j] & 0xFF, root);
                if (j + 1 < deg) r[j] ^= r[j + 1];
            }
            root = mul(root, 2);
        }
        return r;
    }

    private static byte[] rsRemainder(byte[] data, byte[] div) {
        byte[] r = new byte[div.length];
        for (byte b : data) {
            int f = (b ^ r[0]) & 0xFF;
            System.arraycopy(r, 1, r, 0, r.length - 1);
            r[r.length - 1] = 0;
            for (int i = 0; i < r.length; i++) r[i] ^= (byte) mul(div[i] & 0xFF, f);
        }
        return r;
    }

    private static int mul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z & 0xFF;
    }

    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean up = ((right + 1) & 2) == 0;
                    int y = up ? size - 1 - vert : vert;
                    if (!fixed[y][x] && i < data.length * 8) {
                        dark[y][x] = bit(data[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    private void mask(int m) {
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++) {
                if (fixed[y][x]) continue;
                boolean inv;
                switch (m) {
                    case 0: inv = (x + y) % 2 == 0; break;
                    case 1: inv = y % 2 == 0; break;
                    case 2: inv = x % 3 == 0; break;
                    case 3: inv = (x + y) % 3 == 0; break;
                    case 4: inv = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: inv = x * y % 2 + x * y % 3 == 0; break;
                    case 6: inv = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: inv = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                }
                if (inv) dark[y][x] = !dark[y][x];
            }
    }

    /** Điểm phạt rút gọn (luật 1, 2, 4 của chuẩn): chuỗi cùng màu dài, khối 2x2, lệch tỉ lệ đen trắng. */
    private long penalty() {
        long p = 0;
        for (int a = 0; a < size; a++) {
            int runR = 1, runC = 1;
            for (int b = 1; b < size; b++) {
                if (dark[a][b] == dark[a][b - 1]) { runR++; if (runR == 5) p += 3; else if (runR > 5) p++; } else runR = 1;
                if (dark[b][a] == dark[b - 1][a]) { runC++; if (runC == 5) p += 3; else if (runC > 5) p++; } else runC = 1;
            }
        }
        int black = 0;
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++) {
                if (dark[y][x]) black++;
                if (x > 0 && y > 0 && dark[y][x] == dark[y][x - 1] && dark[y][x] == dark[y - 1][x] && dark[y][x] == dark[y - 1][x - 1]) p += 3;
            }
        int total = size * size;
        p += (Math.abs(black * 20 - total * 10) + total - 1) / total * 10;
        return p;
    }

    private static final class BitBuf {
        int n;
        private final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        private int cur;

        void put(int v, int len) {
            for (int i = len - 1; i >= 0; i--) {
                cur = cur << 1 | ((v >>> i) & 1);
                n++;
                if (n % 8 == 0) { out.write(cur); cur = 0; }
            }
        }

        byte[] bytes() { return out.toByteArray(); }
    }
}
