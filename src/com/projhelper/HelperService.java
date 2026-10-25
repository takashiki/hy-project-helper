package com.projhelper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ResolveInfo;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserManager;
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
    /** 开机后等桌面出现多久再打开投屏；一直等不到桌面就在 BOOT_MAX_WAIT_MS 后直接打开。 */
    private static final long HOME_SETTLE_MS = 1500;
    private static final long BOOT_MAX_WAIT_MS = 20000;
    private static final long BOOT_CHECK_MS = 15000;
    private static final int BOOT_MAX_RETRIES = 2;

    /** 当前前台界面，供 MainActivity 显示状态。 */
    static volatile String foreground = "";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private UsageStatsManager usm;
    private WindowManager wm;

    private String fgPkg = "", fgCls = "";
    private long fgSince, lastEventTs;
    private View overlay;
    private long overlayDismissedAt;

    private String homePkg = "";
    private boolean unlocked;
    private UnlockReceiver unlockReceiver;
    private boolean bootRequested, bootHandled;
    /** 用 elapsedRealtime，开机时系统时钟会跳。 */
    private long bootPendingSince;
    private long castLaunchedAt;
    private long castSeenAt;
    private int bootRetries;

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
            Diag.log(c, "launch miracast failed: " + e);
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

        // 开机时可能是被 LOCKED_BOOT_COMPLETED 拉起的，这时用户数据还没解锁：
        // 设置、使用记录、桌面都还不能用，先留在后台等解锁通知（只发给正在运行的程序，不用排队）
        if (getSystemService(UserManager.class).isUserUnlocked()) {
            onUnlocked();
        } else {
            Diag.log(this, "service created while locked, waiting for unlock");
            unlockReceiver = new UnlockReceiver(this);
            registerReceiver(unlockReceiver, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
            // 注册前那一刻刚好解锁的话，通知就错过了，再查一次
            if (getSystemService(UserManager.class).isUserUnlocked()) onUnlocked();
        }
    }

    private void onUnlocked() {
        if (unlocked) return;
        unlocked = true;
        if (unlockReceiver != null) {
            unregisterReceiver(unlockReceiver);
            unlockReceiver = null;
        }
        // 使用记录重启后还在，只看本次开机之后的，免得把关机前的投屏界面当成现在的前台
        long bootTime = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        lastEventTs = Math.max(System.currentTimeMillis() - 6 * 3600 * 1000L, bootTime);
        ResolveInfo home = getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
        if (home != null && home.activityInfo != null) homePkg = home.activityInfo.packageName;
        Diag.log(this, "unlocked, home=" + homePkg);
        if (bootRequested) handleBoot();
        handler.post(poll);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // LOCKED_BOOT_COMPLETED 和 BOOT_COMPLETED 都会走到这里，只处理第一次
        if (intent != null && ACTION_BOOT.equals(intent.getAction())) {
            Diag.log(this, "boot intent (unlocked=" + unlocked + ")");
            bootRequested = true;
            if (!unlocked && getSystemService(UserManager.class).isUserUnlocked()) onUnlocked();
            if (unlocked) handleBoot();
        }
        return START_STICKY;
    }

    private void handleBoot() {
        if (bootHandled) return;
        bootHandled = true;
        if (Weather.prefs(this).getBoolean("boot_cast", true)) {
            bootPendingSince = SystemClock.elapsedRealtime();
        }
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (unlockReceiver != null) unregisterReceiver(unlockReceiver);
        hideOverlay();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    private boolean isHome(String pkg) {
        return pkg.equals(homePkg) || pkg.contains("launcher");
    }

    /** 开机后等桌面出现（或等太久）再打开投屏；投屏已经在前台（比如用户自己点开了）就不管了。 */
    private void maybeBootLaunch() {
        if (bootPendingSince == 0) return;
        long waited = SystemClock.elapsedRealtime() - bootPendingSince;
        if (MIRACAST.equals(fgPkg)) {
            Diag.log(this, "miracast already in front, skip boot launch");
            bootPendingSince = 0;
            return;
        }
        boolean homeReady = isHome(fgPkg) && System.currentTimeMillis() - fgSince >= HOME_SETTLE_MS;
        if (homeReady || waited >= BOOT_MAX_WAIT_MS) {
            Diag.log(this, "boot launch after " + waited + "ms, fg=" + fgPkg);
            bootPendingSince = 0;
            bootLaunch();
        }
    }

    private void bootLaunch() {
        castLaunchedAt = System.currentTimeMillis();
        castSeenAt = 0;
        launchCast(this);
        handler.postDelayed(this::bootCheck, BOOT_CHECK_MS);
    }

    /** 开机时桌面可能晚于投屏加载、把它盖住；这种情况补开，最多两次。用户自己按返回退出的不管。 */
    private void bootCheck() {
        if (bootRetries >= BOOT_MAX_RETRIES || MIRACAST.equals(fgPkg)) return;
        boolean coveredQuickly = castSeenAt == 0 || fgSince - castSeenAt < 3000;
        if (coveredQuickly) {
            bootRetries++;
            Diag.log(this, "miracast covered after boot (seen=" + castSeenAt + ", fg=" + fgPkg
                    + "), relaunching #" + bootRetries);
            bootLaunch();
        }
    }

    private final Runnable poll = this::onPoll;

    private void onPoll() {
        try {
            updateForeground();
            maybeBootLaunch();
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
            if (SystemClock.elapsedRealtime() < 5 * 60 * 1000L) Diag.log(this, "fg " + foreground);
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

    /** 开机早期等用户数据解锁。 */
    static final class UnlockReceiver extends BroadcastReceiver {
        private final HelperService service;

        UnlockReceiver(HelperService service) {
            this.service = service;
        }

        @Override public void onReceive(Context c, Intent intent) {
            service.onUnlocked();
        }
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
