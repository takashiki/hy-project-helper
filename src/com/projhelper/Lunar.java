package com.projhelper;

import java.util.Date;
import java.util.List;

/**
 * 农历文字，用 6tail/lunar-java（寿星万年历算法）。
 * 没用系统自带的 ICU ChineseCalendar：它的朔日近似在午夜附近会差一天（如 2027 春节算成 2 月 7 日）。
 */
final class Lunar {
    private Lunar() {}

    /** 例如 "农历九月十六 · 丙午马年"，逢节日 / 节气在末尾加上。 */
    static String describe(Date date) {
        com.nlf.calendar.Lunar l = com.nlf.calendar.Lunar.fromDate(date);
        StringBuilder sb = new StringBuilder("农历")
                .append(l.getMonthInChinese()).append('月').append(l.getDayInChinese())
                .append(" · ").append(l.getYearInGanZhi()).append(l.getYearShengXiao()).append('年');
        List<String> festivals = l.getFestivals();
        if (!festivals.isEmpty()) sb.append(" · ").append(festivals.get(0));
        String jieQi = l.getJieQi();
        if (jieQi != null && !jieQi.isEmpty()) sb.append(" · ").append(jieQi);
        return sb.toString();
    }
}
