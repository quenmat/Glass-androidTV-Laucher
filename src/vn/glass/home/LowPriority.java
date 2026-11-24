package vn.glass.home;

import java.util.concurrent.ThreadFactory;

// Luồng nền ưu tiên thấp (tải mạng, giải mã ảnh, làm nhoè): lúc vừa bật tivi launcher làm nhiều việc nền cùng lúc,
// để luồng giao diện và luồng vẽ được CPU trước thì hiệu ứng chọn ô / chọn giờ không bị giật.
final class LowPriority implements ThreadFactory {
    private final String name;
    private int n;

    LowPriority(String name) { this.name = name; }

    @Override
    public synchronized Thread newThread(Runnable r) {
        return new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, name + "-" + (++n));
    }
}
