# 投屏助手（projector-helper）

给 ZUI 智投版 1.0 投影仪（型号 NL5H00X，海思平台，Android 9）用的小工具，包名 `com.projhelper`。

- 开机自动打开系统自带的「安卓投屏」（`com.hisilicon.miracast`），停在等待投屏界面
- 在等待界面停超过 1 分钟，上面叠一层时钟；手机连上投屏后自动收起，按遥控器任意键也会收起
- 替换系统屏保：时间、日期星期、农历（含节日和节气）、天气

## 为什么要这样做

- 安卓投屏必须停在前台才能被手机搜到，按 Home 键无效，按返回键退出后就搜不到了
- 安卓投屏开着时系统不会进入屏保
- 打开安卓投屏会占用 Wi-Fi，导致网络 adb 断开

## 构建

不用 Gradle，只依赖 JDK 17+ 和 Android SDK（`platforms;android-28`、`build-tools;34.0.0`）：

```bash
ANDROID_HOME=/path/to/android-sdk ./build.sh
# 产物：build/projector-helper.apk
```

签名文件 `release.jks` 不在仓库里，构建前把它放到本目录。密码通过环境变量 `KS_PASS` 传入，
默认值是 `projhelper`。如果没有 `release.jks`，脚本会生成一个新的签名文件；但用新签名打出来的包
没法覆盖安装旧版，要先卸载旧版才能装。

## 安装

```bash
adb connect <投影仪IP>:5555
export ANDROID_SERIAL=<投影仪IP>:5555
adb install -r build/projector-helper.apk
adb shell appops set com.projhelper GET_USAGE_STATS allow
adb shell appops set com.projhelper SYSTEM_ALERT_WINDOW allow
adb shell dumpsys deviceidle whitelist +com.projhelper
adb shell settings put secure screensaver_components com.projhelper/.ClockDream
adb shell am start -n com.projhelper/.MainActivity
```

## 配置

```bash
# 天气城市（拼音或中文），默认按 IP 定位
adb shell am broadcast -a com.projhelper.CONFIG -n com.projhelper/.BootReceiver --es city shanghai
# 或者直接给经纬度
adb shell am broadcast -a com.projhelper.CONFIG -n com.projhelper/.BootReceiver --ef lat 31.23 --ef lon 121.47 --es name 上海
# 等待界面停多少秒后显示时钟（默认 60）
adb shell am broadcast -a com.projhelper.CONFIG -n com.projhelper/.BootReceiver --ei idle 60
# 关闭开机自动打开投屏
adb shell am broadcast -a com.projhelper.CONFIG -n com.projhelper/.BootReceiver --ez boot_cast false
```

## 还原

```bash
adb shell settings put secure screensaver_components com.newlink.screensaver/com.newlink.screensaver.ScreenService
adb uninstall com.projhelper
```

## 说明

- 农历用 [6tail/lunar-java](https://github.com/6tail/lunar-java)（MIT），没用系统 ICU 的 `ChineseCalendar`：
  ICU 用的朔日近似算法在午夜附近会差一天，比如会把 2027 年春节算成 2 月 7 日（实际是 2 月 6 日）
- 天气用 [Open-Meteo](https://open-meteo.com/)，不需要 key
- build-tools 34 里的 d8 处理不了 JDK 21 javac 编出来的匿名内部类，代码里统一用 lambda 或具名类
