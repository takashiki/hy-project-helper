package com.projhelper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * 开机 / 更新后启动服务；另外接收 adb 发来的配置：
 *   adb shell am broadcast -a com.projhelper.CONFIG -n com.projhelper/.BootReceiver \
 *       [--es city shanghai] [--ef lat 31.23 --ef lon 121.47] [--ei idle 60] [--ez boot_cast true]
 */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            HelperService.start(c, HelperService.ACTION_BOOT);
        } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            HelperService.start(c, null);
        } else if ("com.projhelper.CONFIG".equals(action)) {
            SharedPreferences.Editor e = Weather.prefs(c).edit();
            boolean locationChanged = false;
            if (intent.hasExtra("city")) {
                e.putString("city_query", intent.getStringExtra("city"))
                        .remove("lat").remove("lon").remove("city");
                locationChanged = true;
            }
            if (intent.hasExtra("lat") && intent.hasExtra("lon")) {
                e.putFloat("lat", intent.getFloatExtra("lat", 0))
                        .putFloat("lon", intent.getFloatExtra("lon", 0));
                if (intent.hasExtra("name")) e.putString("city", intent.getStringExtra("name"));
                locationChanged = true;
            }
            if (intent.hasExtra("idle")) e.putInt("idle_sec", intent.getIntExtra("idle", 60));
            if (intent.hasExtra("boot_cast")) {
                e.putBoolean("boot_cast", intent.getBooleanExtra("boot_cast", true));
            }
            e.commit();
            if (locationChanged) {
                Weather.invalidate(c);
                Weather.refreshIfStale(c);
            }
        }
    }
}
