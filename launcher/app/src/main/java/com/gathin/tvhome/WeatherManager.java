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
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WeatherManager {

    private static final String TAG = "WeatherManager";
    private static final String PREF_NAME = "weather_prefs";
    private static final String KEY_TEXT = "weather_text";
    private static final String KEY_TIME = "weather_time";
    private static final long CACHE_DURATION_MS = 45 * 60 * 1000L; // 45 minutes

    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 10; TV) AppleWebKit/537.36";

    public interface Callback {
        void onWeatherUpdated(String weatherText);
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Callback callback;
    private boolean isFetching = false;

    public WeatherManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
        String cached = getCachedWeather();
        if (cached != null && callback != null) {
            callback.onWeatherUpdated(cached);
        }
    }

    public String getCachedWeather() {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_TEXT, null);
    }

    public String getLastCity() {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getString("last_city", "Bandung");
    }

    public void refreshIfStale() {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        long lastTime = prefs.getLong(KEY_TIME, 0);
        long now = System.currentTimeMillis();
        if (now - lastTime > CACHE_DURATION_MS || !prefs.contains(KEY_TEXT)) {
            fetchWeather();
        }
    }

    public synchronized void fetchWeather() {
        if (isFetching) return;
        isFetching = true;

        executor.execute(() -> {
            try {
                double[] latLon = getCoordinatesFromIp();
                if (latLon == null) {
                    latLon = getCoordinatesFallback();
                }
                if (latLon == null) {
                    // Default fallback coordinates (Jakarta) so Open-Meteo can still fetch
                    latLon = new double[]{-6.2088, 106.8456};
                }

                double lat = latLon[0];
                double lon = latLon[1];
                Log.d(TAG, "Fetching weather for coordinates: " + lat + ", " + lon);
                String weatherText = fetchOpenMeteo(lat, lon);
                if (weatherText != null && !weatherText.isEmpty()) {
                    Log.d(TAG, "Weather fetched successfully: " + weatherText);
                    SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
                    prefs.edit()
                            .putString(KEY_TEXT, weatherText)
                            .putLong(KEY_TIME, System.currentTimeMillis())
                            .apply();

                    mainHandler.post(() -> {
                        if (callback != null) {
                            callback.onWeatherUpdated(weatherText);
                        }
                    });
                }
            } catch (Exception e) {
                Log.w(TAG, "Error fetching weather: " + e.getMessage());
            } finally {
                isFetching = false;
            }
        });
    }

    private double[] getCoordinatesFromIp() {
        try {
            URL url = new URL("https://ipwho.is/");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code == 200) {
                String resp = readStream(conn);
                JSONObject json = new JSONObject(resp);
                if (json.optBoolean("success", false)) {
                    double lat = json.getDouble("latitude");
                    double lon = json.getDouble("longitude");
                    String city = json.optString("city", "Bandung");
                    context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                            .edit().putString("last_city", city).apply();
                    return new double[]{lat, lon};
                }
            } else {
                Log.w(TAG, "ipwho.is returned HTTP " + code);
            }
        } catch (Exception e) {
            Log.w(TAG, "ipwho.is failed: " + e.getMessage());
        }
        return null;
    }

    private double[] getCoordinatesFallback() {
        try {
            URL url = new URL("http://ip-api.com/json/");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            int code = conn.getResponseCode();
            if (code == 200) {
                String resp = readStream(conn);
                JSONObject json = new JSONObject(resp);
                if ("success".equalsIgnoreCase(json.optString("status"))) {
                    double lat = json.getDouble("lat");
                    double lon = json.getDouble("lon");
                    return new double[]{lat, lon};
                }
            } else {
                Log.w(TAG, "ip-api returned HTTP " + code);
            }
        } catch (Exception e) {
            Log.w(TAG, "ip-api fallback failed: " + e.getMessage());
        }
        return null;
    }

    private String fetchOpenMeteo(double lat, double lon) {
        try {
            String urlStr = String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min&timezone=auto",
                    lat, lon);
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            int code = conn.getResponseCode();
            if (code == 200) {
                String resp = readStream(conn);
                JSONObject json = new JSONObject(resp);
                JSONObject current = json.getJSONObject("current");
                JSONObject daily = json.getJSONObject("daily");

                double currentTemp = current.getDouble("temperature_2m");
                int weatherCode = current.getInt("weather_code");

                JSONArray maxArr = daily.getJSONArray("temperature_2m_max");
                JSONArray minArr = daily.getJSONArray("temperature_2m_min");
                double maxTemp = maxArr.getDouble(0);
                double minTemp = minArr.getDouble(0);

                String condition = getWeatherDescription(weatherCode);

                return String.format(Locale.US, "%d° %s  H:%d° L:%d°",
                        Math.round(currentTemp),
                        condition,
                        Math.round(maxTemp),
                        Math.round(minTemp));
            } else {
                Log.w(TAG, "Open-Meteo returned HTTP " + code);
            }
        } catch (Exception e) {
            Log.w(TAG, "Open-Meteo fetch failed: " + e.getMessage());
        }
        return null;
    }

    private String getWeatherDescription(int code) {
        switch (code) {
            case 0:
                return "Clear";
            case 1:
            case 2:
                return "Partly Cloudy";
            case 3:
                return "Overcast";
            case 45:
            case 48:
                return "Fog";
            case 51:
            case 53:
            case 55:
                return "Drizzle";
            case 56:
            case 57:
                return "Freezing Drizzle";
            case 61:
            case 63:
            case 65:
                return "Rain";
            case 66:
            case 67:
                return "Freezing Rain";
            case 71:
            case 73:
            case 75:
                return "Snow";
            case 77:
                return "Snow Grains";
            case 80:
            case 81:
            case 82:
                return "Showers";
            case 85:
            case 86:
                return "Snow Showers";
            case 95:
                return "Thunderstorm";
            case 96:
            case 99:
                return "Thunderstorm / Hail";
            default:
                return "Partly Cloudy";
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

