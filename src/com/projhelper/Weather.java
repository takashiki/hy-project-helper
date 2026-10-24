package com.projhelper;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;

/**
 * 天气：Open-Meteo（免 key）。位置优先级：手动经纬度 > 手动城市名（地理编码）> IP 定位。
 * 结果缓存在 SharedPreferences，30 分钟刷新一次，失败 5 分钟后重试。
 */
final class Weather {
    static final String PREFS = "cfg";
    private static final String TAG = "ProjHelper";
    private static final long REFRESH_MS = 30 * 60 * 1000L;
    private static final long RETRY_MS = 5 * 60 * 1000L;
    private static volatile boolean busy;

    private Weather() {}

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void refreshIfStale(Context c) {
        SharedPreferences p = prefs(c);
        long now = System.currentTimeMillis();
        if (busy) return;
        if (now - p.getLong("w_time", 0) < REFRESH_MS) return;
        if (now - p.getLong("w_try", 0) < RETRY_MS) return;
        busy = true;
        p.edit().putLong("w_try", now).apply();
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                fetch(app);
            } catch (Exception e) {
                Log.w(TAG, "weather failed", e);
            } finally {
                busy = false;
            }
        }, "weather").start();
    }

    /** 强制下次显示时重新获取（改了城市之后用）。 */
    static void invalidate(Context c) {
        prefs(c).edit().remove("w_time").remove("w_try").apply();
    }

    /** 时钟上显示的一行，没有数据时返回 null。 */
    static String line(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.contains("w_text")) return null;
        String city = p.getString("city", "");
        return String.format(Locale.US, "%s%s  %d°C   %d° ~ %d°",
                city.isEmpty() ? "" : city + "  ",
                p.getString("w_text", ""),
                p.getInt("w_temp", 0), p.getInt("w_min", 0), p.getInt("w_max", 0));
    }

    private static void fetch(Context c) throws Exception {
        SharedPreferences p = prefs(c);
        if (!p.contains("lat")) locate(p);
        if (!p.contains("lat")) return;

        String url = String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                        + "&current=temperature_2m,weather_code"
                        + "&daily=temperature_2m_max,temperature_2m_min"
                        + "&timezone=auto&forecast_days=1",
                p.getFloat("lat", 0), p.getFloat("lon", 0));
        JSONObject j = new JSONObject(get(url));
        JSONObject cur = j.getJSONObject("current");
        JSONObject daily = j.getJSONObject("daily");
        p.edit()
                .putString("w_text", describe(cur.getInt("weather_code")))
                .putInt("w_temp", (int) Math.round(cur.getDouble("temperature_2m")))
                .putInt("w_min", (int) Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)))
                .putInt("w_max", (int) Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)))
                .putLong("w_time", System.currentTimeMillis())
                .apply();
    }

    private static void locate(SharedPreferences p) throws Exception {
        String query = p.getString("city_query", "");
        if (!query.isEmpty()) {
            JSONObject j = new JSONObject(get(
                    "https://geocoding-api.open-meteo.com/v1/search?count=1&language=zh&name="
                            + URLEncoder.encode(query, "UTF-8")));
            JSONArray results = j.optJSONArray("results");
            if (results == null || results.length() == 0) {
                Log.w(TAG, "city not found: " + query);
                return;
            }
            JSONObject r = results.getJSONObject(0);
            p.edit()
                    .putFloat("lat", (float) r.getDouble("latitude"))
                    .putFloat("lon", (float) r.getDouble("longitude"))
                    .putString("city", r.optString("name", query))
                    .apply();
            return;
        }
        JSONObject j = new JSONObject(get("http://ip-api.com/json/?lang=zh-CN"));
        if (!"success".equals(j.optString("status"))) return;
        p.edit()
                .putFloat("lat", (float) j.getDouble("lat"))
                .putFloat("lon", (float) j.getDouble("lon"))
                .putString("city", j.optString("city", ""))
                .apply();
    }

    private static String get(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            conn.disconnect();
        }
    }

    /** WMO 天气代码 → 中文。 */
    static String describe(int code) {
        switch (code) {
            case 0: return "晴";
            case 1: return "晴间多云";
            case 2: return "多云";
            case 3: return "阴";
            case 45: case 48: return "雾";
            case 51: case 53: case 55: return "毛毛雨";
            case 56: case 57: return "冻毛毛雨";
            case 61: return "小雨";
            case 63: return "中雨";
            case 65: return "大雨";
            case 66: case 67: return "冻雨";
            case 71: return "小雪";
            case 73: return "中雪";
            case 75: return "大雪";
            case 77: return "雪粒";
            case 80: return "阵雨";
            case 81: return "中阵雨";
            case 82: return "强阵雨";
            case 85: case 86: return "阵雪";
            case 95: return "雷阵雨";
            case 96: case 99: return "雷阵雨伴冰雹";
            default: return "—";
        }
    }
}
