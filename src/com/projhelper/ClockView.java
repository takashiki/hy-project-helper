package com.projhelper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

/** 全屏时钟：大字时间、日期星期、农历、天气。整体每分钟轻微漂移一次位置。 */
public class ClockView extends View {
    private static final String WEEK = "日一二三四五六";

    private final Paint timePaint = paint("sans-serif-light", 0xFFE8E8E8);
    private final Paint datePaint = paint("sans-serif", 0xFFB8B8B8);
    private final Paint subPaint = paint("sans-serif", 0xFF8A8A8A);
    private final Random random = new Random();
    private float driftX, driftY;
    private int lastMinute = -1;
    private String lunarText = "";
    private int lunarDay = -1;

    private final Runnable tick = this::onTick;

    public ClockView(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);
        timePaint.setFontFeatureSettings("tnum");
    }

    private static Paint paint(String family, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(Typeface.create(family, Typeface.NORMAL));
        p.setColor(color);
        p.setTextAlign(Paint.Align.CENTER);
        return p;
    }

    private void onTick() {
        invalidate();
        postDelayed(tick, 1000);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(tick);
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;

        Calendar now = Calendar.getInstance();
        int minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        if (minute != lastMinute) {
            lastMinute = minute;
            driftX = (random.nextFloat() - 0.5f) * w * 0.08f;
            driftY = (random.nextFloat() - 0.5f) * h * 0.08f;
            Weather.refreshIfStale(getContext());
        }
        int dayOfYear = now.get(Calendar.YEAR) * 1000 + now.get(Calendar.DAY_OF_YEAR);
        if (dayOfYear != lunarDay) {
            lunarDay = dayOfYear;
            try {
                lunarText = Lunar.describe(new Date(now.getTimeInMillis()));
            } catch (Throwable t) {
                lunarText = "";
            }
        }

        String time = String.format(Locale.US, "%02d:%02d",
                now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE));
        String date = String.format(Locale.US, "%d月%d日  星期%c",
                now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH),
                WEEK.charAt(now.get(Calendar.DAY_OF_WEEK) - 1));
        String weather = Weather.line(getContext());

        timePaint.setTextSize(h * 0.26f);
        datePaint.setTextSize(h * 0.058f);
        subPaint.setTextSize(h * 0.045f);

        float gap = h * 0.035f;
        float timeH = h * 0.26f * 0.75f; // 数字的大致可见高度
        float dateH = h * 0.058f;
        float subH = h * 0.045f;
        float total = timeH + gap * 1.6f + dateH;
        if (!lunarText.isEmpty()) total += gap + subH;
        if (weather != null) total += gap + subH;

        float cx = w / 2f + driftX;
        float y = (h - total) / 2f + driftY + timeH;
        canvas.drawText(time, cx, y, timePaint);
        y += gap * 1.6f + dateH;
        canvas.drawText(date, cx, y, datePaint);
        if (!lunarText.isEmpty()) {
            y += gap + subH;
            canvas.drawText(lunarText, cx, y, subPaint);
        }
        if (weather != null) {
            y += gap + subH;
            canvas.drawText(weather, cx, y, subPaint);
        }
    }
}
