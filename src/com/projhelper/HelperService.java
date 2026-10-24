package com.projhelper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * 常驻服务：
 * 1. 开机后自动打开「安卓投屏」(com.hisilicon.miracast) 的等待界面；
 * 2. 投屏停在等待界面（非 DisplayActivity）超过设定时间，就在上面叠加时钟，
 *    叠加窗口不会让投屏 App 退到后台，手机连上（进入 DisplayActivity）时自动收起。
 */
public class HelperService extends Service {
    static final String ACTION_BOOT = "com.projhelper.BOOT";
    static final String MIRACAST = "com.hisilicon.miracast";
    static final ComponentName CAST =
            new ComponentName(MIRACAST, MIRACAST + ".activity.WelcomeActivity");
    private static final String TAG = "ProjHelper";
    private static final long BOOT_DELAY_MS = 8000;
    private static final long BOOT_CHECK_MS = 15000;

    /** 当前前台界面，供 MainActivity 显示状态。 */
    static volatile String foreground = "";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private UsageStatsManager usm;
    private WindowManager wm;

    private String fgPkg = "", fgCls = "";
    private long fgSince, lastEventTs;
    private View overlay;
    private long overlayDismissedAt;

    private long castLaunchedAt;
    private long castSeenAt;
    private boolean bootRetried;

    static void start(Context c, String action) {
        Intent i = new Intent(c, HelperService.class);
        if (action != null) i.setAction(action);
        c.startForegroundService(i);
    }

    static void launchCast(Context c) {
        Intent i = new Intent(Intent.ACTION_MAIN).setComponent(CAST)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "launch miracast failed", e);
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                "helper", "投屏助手", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "helper")
                .setContentTitle("投屏助手运行中")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build();
        startForeground(1, n);

        usm = getSystemService(UsageStatsManager.class);
        wm = getSystemService(WindowManager.class);
        lastEventTs = System.currentTimeMillis() - 6 * 3600 * 1000L;
        handler.post(poll);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_BOOT.equals(intent.getAction())
                && Weather.prefs(this).getBoolean("boot_cast", true)) {
            handler.postDelayed(this::bootLaunch, BOOT_DELAY_MS);
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        hideOverlay();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    private void bootLaunch() {
        castLaunchedAt = System.currentTimeMillis();
        castSeenAt = 0;
        launchCast(this);
        handler.postDelayed(this::bootCheck, BOOT_CHECK_MS);
    }

    /** 开机时桌面可能晚于投屏加载、把它盖住；这种情况补开一次。用户自己按返回退出的不管。 */
    private void bootCheck() {
        if (bootRetried || MIRACAST.equals(fgPkg)) return;
        boolean coveredQuickly = castSeenAt == 0 || fgSince - castSeenAt < 3000;
        if (coveredQuickly) {
            bootRetried = true;
            Log.i(TAG, "miracast covered after boot, relaunching");
            bootLaunch();
        }
    }

    private final Runnable poll = this::onPoll;

    private void onPoll() {
        try {
            updateForeground();
            evaluate();
        } catch (Exception e) {
            Log.w(TAG, "poll failed", e);
        }
        handler.postDelayed(poll, 1000);
    }

    private void updateForeground() {
        long now = System.currentTimeMillis();
        UsageEvents events = usm.queryEvents(Math.max(lastEventTs - 5000, now - 6 * 3600 * 1000L), now);
        if (events == null) return;
        UsageEvents.Event e = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            if (e.getEventType() != UsageEvents.Event.MOVE_TO_FOREGROUND) continue;
            long ts = e.getTimeStamp();
            if (ts <= lastEventTs) continue;
            lastEventTs = ts;
            fgPkg = e.getPackageName();
            fgCls = e.getClassName() == null ? "" : e.getClassName();
            fgSince = ts;
            foreground = fgPkg + "/" + fgCls;
            if (MIRACAST.equals(fgPkg) && castLaunchedAt > 0 && castSeenAt == 0
                    && ts >= castLaunchedAt) {
                castSeenAt = ts;
            }
        }
    }

    private void evaluate() {
        boolean waiting = MIRACAST.equals(fgPkg) && !fgCls.endsWith("DisplayActivity");
        if (!waiting) {
            hideOverlay();
            return;
        }
        long idleMs = Weather.prefs(this).getInt("idle_sec", 60) * 1000L;
        long since = Math.max(fgSince, overlayDismissedAt);
        if (overlay == null && System.currentTimeMillis() - since >= idleMs) {
            showOverlay();
        }
    }

    private void showOverlay() {
        if (!Settings.canDrawOverlays(this)) return;
        OverlayRoot root = new OverlayRoot(this, () -> {
            overlayDismissedAt = System.currentTimeMillis();
            handler.post(this::hideOverlay);
        });
        root.addView(new ClockView(this));
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.OPAQUE);
        try {
            wm.addView(root, lp);
            root.requestFocus();
            overlay = root;
        } catch (Exception e) {
            Log.w(TAG, "show overlay failed", e);
        }
    }

    private void hideOverlay() {
        if (overlay == null) return;
        try {
            wm.removeView(overlay);
        } catch (Exception ignored) {
        }
        overlay = null;
    }

    /** 叠加时钟的根布局：按下和抬起都吃掉，抬起时收起，避免按键漏给下面的投屏界面。 */
    static final class OverlayRoot extends FrameLayout {
        private final Runnable onDismiss;

        OverlayRoot(Context c, Runnable onDismiss) {
            super(c);
            this.onDismiss = onDismiss;
        }

        @Override public boolean dispatchKeyEvent(KeyEvent event) {
            if (event.getAction() == KeyEvent.ACTION_UP) onDismiss.run();
            return true;
        }
    }
}
