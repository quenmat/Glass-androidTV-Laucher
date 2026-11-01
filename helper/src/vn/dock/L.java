package vn.dock;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;

// App phụ cho nút APPS (gán bằng Button Mapper): mở bảng "Tất cả ứng dụng" của launcher đang dùng.
// Thử lần lượt các action ghi trong meta-data "act" (cách nhau bởi dấu phẩy): Glass Home trước,
// không có thì lùi về Projectivy, để nút APPS không bao giờ "câm".
public class L extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            PackageManager pm = getPackageManager();
            Bundle m = pm.getApplicationInfo(getPackageName(), PackageManager.GET_META_DATA).metaData;
            for (String act : m.getString("act", "").split(",")) {
                Intent i = new Intent(act.trim()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (i.resolveActivity(pm) != null) { startActivity(i); break; }
            }
        } catch (Exception ignored) {
        }
        finish();
    }
}
