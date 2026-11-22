package com.example.aichat;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class Settings {

    private static final String PREFS = "aichat_settings";
    private static final String KEY_TYPE = "g_type";
    private static final String KEY_HOST = "g_host";
    private static final String KEY_KEY = "g_key";
    private static final String KEY_MODEL = "g_model";
    private static final String KEY_THINKING = "g_thinking";
    private static final String KEY_PRESETS = "g_presets";
    private static final String KEY_CONTEXT = "g_context_strategy";
    private static final String KEY_MEM_ON = "g_memory_on";
    private static final String KEY_MEM = "g_memory";
    private static final String KEY_VISION = "g_vision";
    private static final String KEY_SEARCH_ENGINE = "g_search_engine";
    private static final String KEY_SEARCH_KEY = "g_search_key";
    private static final String KEY_PRICES = "g_prices";
    private static final String KEY_USAGE = "g_usage";
    private static final String KEY_DARK = "g_dark_mode";
    private static final String KEY_KEEP_ALIVE = "g_keep_alive";
    private static final String KEY_RADIUS = "g_radius";
    private static final String KEY_BUBBLE_SCALE = "g_bubble_scale";
    private static final String KEY_FONT = "g_font";

    private final SharedPreferences sp;

    public Settings(Context ctx) {
        sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String globalType() {
        return sp.getString(KEY_TYPE, "OPENAI");
    }

    public String globalHost() {
        return sp.getString(KEY_HOST, "https://api.deepseek.com");
    }

    public String globalKey() {
        return sp.getString(KEY_KEY, "");
    }

    public String globalModel() {
        return sp.getString(KEY_MODEL, "deepseek-chat");
    }

    public String contextStrategy() {
        return sp.getString(KEY_CONTEXT, "sliding");
    }

    public void saveContextStrategy(String strategy) {
        sp.edit().putString(KEY_CONTEXT, strategy).apply();
    }

    public String searchEngine() {
        return sp.getString(KEY_SEARCH_ENGINE, "ddg");
    }

    public String searchApiKey() {
        return sp.getString(KEY_SEARCH_KEY, "");
    }

    public void saveSearchConfig(String engine, String key) {
        sp.edit().putString(KEY_SEARCH_ENGINE, engine).putString(KEY_SEARCH_KEY, key).apply();
    }

    public String visionJson() {
        return sp.getString(KEY_VISION, "");
    }

    public void saveVisionJson(String json) {
        sp.edit().putString(KEY_VISION, json).apply();
    }

    // ===== 价格 =====

    public String pricesJson() {
        return sp.getString(KEY_PRICES, "[]");
    }

    public void savePrices(String json) {
        sp.edit().putString(KEY_PRICES, json).apply();
    }

    // ===== 用量 =====

    public String usageJson() {
        return sp.getString(KEY_USAGE, "{}");
    }

    public void addUsage(int tokens, double cost, int turns) {
        String json = usageJson();
        try {
            JSONObject o = new JSONObject(json);
            o.put("tokens", o.optInt("tokens", 0) + tokens);
            o.put("cost", o.optDouble("cost", 0) + cost);
            o.put("turns", o.optInt("turns", 0) + turns);
            sp.edit().putString(KEY_USAGE, o.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public void clearUsage() {
        sp.edit().putString(KEY_USAGE, "{}").apply();
    }

    // ===== 主题 =====

    public boolean darkMode() {
        return sp.getString(KEY_DARK, "off").equals("on");
    }

    public void saveDarkMode(boolean dark) {
        sp.edit().putString(KEY_DARK, dark ? "on" : "off").apply();
    }

    public boolean keepAlive() {
        return sp.getString(KEY_KEEP_ALIVE, "off").equals("on");
    }

    public void saveKeepAlive(boolean on) {
        sp.edit().putString(KEY_KEEP_ALIVE, on ? "on" : "off").apply();
    }

    public boolean roundedCorners() {
        return sp.getString(KEY_RADIUS, "on").equals("on");
    }

    public float bubbleScale() {
        try {
            return Float.parseFloat(sp.getString(KEY_BUBBLE_SCALE, "1"));
        } catch (Exception e) {
            return 1f;
        }
    }

    public String themeFont() {
        return sp.getString(KEY_FONT, "default");
    }

    public void saveThemeSettings(boolean rounded, float bubbleScale, String font) {
        sp.edit()
                .putString(KEY_RADIUS, rounded ? "on" : "off")
                .putString(KEY_BUBBLE_SCALE, String.valueOf(bubbleScale))
                .putString(KEY_FONT, font)
                .apply();
    }

    public boolean memoryOn() {
        return sp.getString(KEY_MEM_ON, "off").equals("on");
    }

    public void saveMemoryOn(boolean on) {
        sp.edit().putString(KEY_MEM_ON, on ? "on" : "off").apply();
    }

    public String memory() {
        return sp.getString(KEY_MEM, "");
    }

    public void saveMemory(String mem) {
        sp.edit().putString(KEY_MEM, mem).apply();
    }

    public void clearMemory() {
        sp.edit().remove(KEY_MEM).apply();
    }

    public boolean globalThinking() {
        return sp.getString(KEY_THINKING, "off").equals("on");
    }

    public void saveGlobal(String type, String host, String key, String model, boolean thinking) {
        sp.edit()
                .putString(KEY_TYPE, type)
                .putString(KEY_HOST, host)
                .putString(KEY_KEY, key)
                .putString(KEY_MODEL, model)
                .putString(KEY_THINKING, thinking ? "on" : "off")
                .apply();
    }

    public void put(String key, String value) {
        sp.edit().putString(key, value).apply();
    }

    public List<String[]> listPresets() {
        List<String[]> list = new ArrayList<>();
        String json = sp.getString(KEY_PRESETS, "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new String[]{
                        o.optString("name", ""),
                        o.optString("type", "OPENAI"),
                        o.optString("host", ""),
                        o.optString("key", ""),
                        o.optString("model", ""),
                        o.optString("max", "4096"),
                        o.optString("temp", ""),
                        o.optString("skill", ""),
                        o.optString("note", ""),
                        o.optString("thinking", "off")
                });
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    public String[] getPreset(String name) {
        List<String[]> all = listPresets();
        for (String[] p : all) {
            if (p[0].equals(name)) return p;
        }
        return null;
    }

    public void savePreset(String name, String type, String host, String key,
                           String model, String max, String temp, String skill,
                           String note, boolean thinking) {
        List<String[]> all = listPresets();
        JSONArray arr = new JSONArray();
        boolean replaced = false;
        for (String[] p : all) {
            if (p[0].equals(name)) {
                arr.put(toJson(name, type, host, key, model, max, temp, skill, note, thinking));
                replaced = true;
            } else {
                arr.put(toJson(p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[8], p[9].equals("on")));
            }
        }
        if (!replaced) {
            arr.put(toJson(name, type, host, key, model, max, temp, skill, note, thinking));
        }
        sp.edit().putString(KEY_PRESETS, arr.toString()).apply();
    }

    public void deletePreset(String name) {
        List<String[]> all = listPresets();
        JSONArray arr = new JSONArray();
        for (String[] p : all) {
            if (!p[0].equals(name)) {
                arr.put(toJson(p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[8], p[9].equals("on")));
            }
        }
        sp.edit().putString(KEY_PRESETS, arr.toString()).apply();
    }

    private JSONObject toJson(String name, String type, String host, String key,
                              String model, String max, String temp, String skill,
                              String note, boolean thinking) {
        JSONObject o = new JSONObject();
        try {
            o.put("name", name);
            o.put("type", type);
            o.put("host", host);
            o.put("key", key);
            o.put("model", model);
            o.put("max", max);
            o.put("temp", temp);
            o.put("skill", skill);
            o.put("note", note);
            o.put("thinking", thinking ? "on" : "off");
        } catch (Exception ignored) {
        }
        return o;
    }
}
