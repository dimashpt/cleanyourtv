package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PrayerManager {

    private static final String TAG = "PrayerManager";
    private static final String PREF_NAME = "prayer_prefs";
    private static final String KEY_CITY_ID = "prayer_city_id";
    private static final String KEY_CITY_NAME = "prayer_city_name";
    private static final String KEY_DATE = "prayer_date";
    private static final String KEY_SUBUH = "prayer_subuh";
    private static final String KEY_DZUHUR = "prayer_dzuhur";
    private static final String KEY_ASHAR = "prayer_ashar";
    private static final String KEY_MAGHRIB = "prayer_maghrib";
    private static final String KEY_ISYA = "prayer_isya";

    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 10; TV) AppleWebKit/537.36";

    public interface Callback {
        void onPrayerUpdated(String prayerText);
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Callback callback;
    private boolean isFetching = false;

    public PrayerManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
        updateDisplay();
    }

    public void refreshIfStale(String cityName) {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String cachedDate = prefs.getString(KEY_DATE, "");
        if (!today.equals(cachedDate)) {
            fetchPrayerSchedule(cityName);
        } else {
            updateDisplay();
        }
    }

    public void updateDisplay() {
        String displayText = calculateNextPrayerText();
        if (displayText != null && callback != null) {
            mainHandler.post(() -> callback.onPrayerUpdated(displayText));
        }
    }

    public synchronized void fetchPrayerSchedule(String cityName) {
        if (isFetching) return;
        isFetching = true;

        executor.execute(() -> {
            try {
                String city = cityName;
                if (city == null || city.isEmpty()) {
                    city = "Bandung";
                }

                SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
                String cachedCityName = prefs.getString(KEY_CITY_NAME, "");
                String cityId = prefs.getString(KEY_CITY_ID, "");

                if (cityId.isEmpty() || !city.equalsIgnoreCase(cachedCityName)) {
                    cityId = findCityId(city);
                    if (cityId == null) {
                        cityId = "1219"; // Default Kota Bandung
                    }
                    prefs.edit().putString(KEY_CITY_ID, cityId).putString(KEY_CITY_NAME, city).apply();
                }

                Calendar cal = Calendar.getInstance();
                int year = cal.get(Calendar.YEAR);
                int month = cal.get(Calendar.MONTH) + 1;
                int day = cal.get(Calendar.DAY_OF_MONTH);

                String scheduleUrl = String.format(Locale.US,
                        "https://api.myquran.com/v2/sholat/jadwal/%s/%04d/%02d/%02d",
                        cityId, year, month, day);

                Log.d(TAG, "Fetching prayer schedule: " + scheduleUrl);
                URL url = new URL(scheduleUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", USER_AGENT);

                if (conn.getResponseCode() == 200) {
                    String resp = readStream(conn);
                    JSONObject json = new JSONObject(resp);
                    if (json.optBoolean("status", false)) {
                        JSONObject data = json.getJSONObject("data");
                        JSONObject jadwal = data.getJSONObject("jadwal");

                        String todayStr = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
                        prefs.edit()
                                .putString(KEY_DATE, todayStr)
                                .putString(KEY_SUBUH, jadwal.getString("subuh"))
                                .putString(KEY_DZUHUR, jadwal.getString("dzuhur"))
                                .putString(KEY_ASHAR, jadwal.getString("ashar"))
                                .putString(KEY_MAGHRIB, jadwal.getString("maghrib"))
                                .putString(KEY_ISYA, jadwal.getString("isya"))
                                .apply();

                        Log.d(TAG, "Prayer schedule saved for " + todayStr);
                        updateDisplay();
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to fetch prayer schedule: " + e.getMessage());
            } finally {
                isFetching = false;
            }
        });
    }

    private String findCityId(String cityName) {
        try {
            String encoded = URLEncoder.encode(cityName, "UTF-8");
            URL url = new URL("https://api.myquran.com/v2/sholat/kota/cari/" + encoded);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", USER_AGENT);

            if (conn.getResponseCode() == 200) {
                String resp = readStream(conn);
                JSONObject json = new JSONObject(resp);
                if (json.optBoolean("status", false)) {
                    JSONArray data = json.getJSONArray("data");
                    if (data.length() > 0) {
                        for (int i = 0; i < data.length(); i++) {
                            JSONObject item = data.getJSONObject(i);
                            String loc = item.optString("lokasi", "");
                            if (loc.contains("KOTA")) {
                                return item.getString("id");
                            }
                        }
                        return data.getJSONObject(0).getString("id");
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Find city ID failed: " + e.getMessage());
        }
        return null;
    }

    private String calculateNextPrayerText() {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String subuh = prefs.getString(KEY_SUBUH, "04:08");
        String dzuhur = prefs.getString(KEY_DZUHUR, "11:37");
        String ashar = prefs.getString(KEY_ASHAR, "14:47");
        String maghrib = prefs.getString(KEY_MAGHRIB, "17:50");
        String isya = prefs.getString(KEY_ISYA, "18:56");

        Calendar now = Calendar.getInstance();
        int currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);

        int subuhMin = parseTimeToMinutes(subuh);
        int dzuhurMin = parseTimeToMinutes(dzuhur);
        int asharMin = parseTimeToMinutes(ashar);
        int maghribMin = parseTimeToMinutes(maghrib);
        int isyaMin = parseTimeToMinutes(isya);

        String nextName;
        String nextTime;
        int targetMinutes;

        if (currentMinutes < subuhMin) {
            nextName = "Subuh";
            nextTime = subuh;
            targetMinutes = subuhMin;
        } else if (currentMinutes < dzuhurMin) {
            nextName = "Dzuhur";
            nextTime = dzuhur;
            targetMinutes = dzuhurMin;
        } else if (currentMinutes < asharMin) {
            nextName = "Ashar";
            nextTime = ashar;
            targetMinutes = asharMin;
        } else if (currentMinutes < maghribMin) {
            nextName = "Maghrib";
            nextTime = maghrib;
            targetMinutes = maghribMin;
        } else if (currentMinutes < isyaMin) {
            nextName = "Isya";
            nextTime = isya;
            targetMinutes = isyaMin;
        } else {
            nextName = "Subuh";
            nextTime = subuh;
            targetMinutes = subuhMin + 24 * 60; // Tomorrow's Subuh
        }

        int diff = targetMinutes - currentMinutes;
        int hours = diff / 60;
        int mins = diff % 60;

        String remaining;
        if (hours > 0) {
            remaining = String.format(Locale.US, "in %d h %d min", hours, mins);
        } else {
            remaining = String.format(Locale.US, "in %d min", mins);
        }

        return String.format(Locale.US, "%s %s · %s", nextName, nextTime, remaining);
    }

    private int parseTimeToMinutes(String timeStr) {
        try {
            String[] parts = timeStr.split(":");
            return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
        } catch (Exception e) {
            return 0;
        }
    }

    private String readStream(HttpURLConnection conn) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        return sb.toString();
    }
}

