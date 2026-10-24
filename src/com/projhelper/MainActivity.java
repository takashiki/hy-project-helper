package com.projhelper;

import android.app.Activity;
import android.app.AppOpsManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 状态页：看权限、当前屏保、天气，顺便能预览时钟。 */
public class MainActivity extends Activity {
    private TextView status;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        HelperService.start(this, null);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(80, 60, 80, 60);

        status = new TextView(this);
        status.setTextSize(20);
        status.setLineSpacing(0, 1.3f);
        root.addView(status);

        root.addView(button("预览时钟（按任意键退出）",
                () -> startActivity(new Intent(this, ClockActivity.class))));
        root.addView(button("打开安卓投屏", () -> HelperService.launchCast(this)));
        root.addView(button("刷新天气", () -> {
            Weather.invalidate(this);
            Weather.refreshIfStale(this);
            status.postDelayed(this::refresh, 3000);
        }));
        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private Button button(String text, Runnable action) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(20);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 24;
        b.setLayoutParams(lp);
        return b;
    }

    private void refresh() {
        AppOpsManager ops = getSystemService(AppOpsManager.class);
        boolean usage = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), getPackageName()) == AppOpsManager.MODE_ALLOWED;
        boolean overlay = Settings.canDrawOverlays(this);
        String dream = Settings.Secure.getString(getContentResolver(), "screensaver_components");
        SharedPreferences p = Weather.prefs(this);
        String weather = Weather.line(this);

        status.setText("投屏助手\n\n"
                + "使用情况访问权限：" + (usage ? "已开启" : "未开启（检测不到投屏状态）") + "\n"
                + "悬浮窗权限：" + (overlay ? "已开启" : "未开启（投屏等待时无法显示时钟）") + "\n"
                + "系统屏保：" + dream + "\n"
                + "开机自动打开投屏：" + (p.getBoolean("boot_cast", true) ? "是" : "否")
                + "    等待 " + p.getInt("idle_sec", 60) + " 秒后显示时钟\n"
                + "天气：" + (weather == null ? "暂无" : weather) + "\n"
                + "当前前台：" + HelperService.foreground);
    }
}
