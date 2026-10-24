package com.projhelper;

import android.service.dreams.DreamService;

/** 系统屏保：替换掉原来的 com.newlink.screensaver。 */
public class ClockDream extends DreamService {
    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false); // 按任意键退出屏保
        setFullscreen(true);
        setScreenBright(true);
        setContentView(new ClockView(this));
    }
}
