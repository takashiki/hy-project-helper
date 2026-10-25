package com.projhelper;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 诊断日志。这台机器的 logcat 会过滤掉第三方 App 的日志，所以另外写一份到
 *   /sdcard/Android/data/com.projhelper/files/log.txt
 * 每行开头是开机后的秒数，不受开机时系统时钟跳变影响。
 * 开机早期（用户数据还没解锁）外部存储不可用，先攒在内存里，下次能写时一起写。
 */
final class Diag {
    private static final String TAG = "ProjHelper";
    private static final long MAX_BYTES = 64 * 1024;
    private static final List<String> pending = new ArrayList<>();

    private Diag() {}

    static synchronized void log(Context c, String msg) {
        Log.i(TAG, msg);
        pending.add(String.format(java.util.Locale.US, "[%7.1fs] %tF %<tT %s\n",
                SystemClock.elapsedRealtime() / 1000.0, System.currentTimeMillis(), msg));
        try {
            File dir = c.getApplicationContext().getExternalFilesDir(null);
            if (dir == null) return;
            File f = new File(dir, "log.txt");
            boolean append = f.length() < MAX_BYTES;
            try (FileOutputStream out = new FileOutputStream(f, append)) {
                for (String line : pending) out.write(line.getBytes(StandardCharsets.UTF_8));
            }
            pending.clear();
        } catch (Exception e) {
            if (pending.size() > 200) pending.remove(0);
        }
    }
}
