package com.example.aichat;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class Settings {

    private final SharedPreferences sp;

    public Settings(Context context) {
        sp = context.getSharedPreferences("chat", Context.MODE_PRIVATE);
    }

    public String get(String key, String def) {
        return sp.getString(key, def);
    }

    public void put(String key, String value) {
        sp.edit().putString(key, value).apply();
    }

    // ===== 全局默认配置 =====

    public String globalType() { return get("g_type", "OPENAI").toUpperCase(); }
    public String globalHost() { return get("g_host", "https://api.deepseek.com"); }
    public String globalKey() { return get("g_key", ""); }
    public String globalModel() { return get("g_model", "deepseek-chat"); }

    public void saveGlobal(String type, String host, String key, String model) {
        sp.edit()
                .putString("g_type", type)
                .putString("g_host", host)
                .putString("g_key", key)
                .putString("g_model", model)
                .apply();
    }

    // ===== 模型预设 =====

    public void savePreset(String name, String type, String host, String key, String model) {
        try {
            JSONArray arr = getPresetsArray();
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("type", type);
            o.put("host", host);
            o.put("key", key);
            o.put("model", model);
            arr.put(o);
            sp.edit().putString("presets", arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public List<String[]> listPresets() {
        List<String[]> list = new ArrayList<>();
        try {
            JSONArray arr = getPresetsArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new String[]{
                        o.optString("name", ""),
                        o.optString("type", "OPENAI"),
                        o.optString("host", ""),
                        o.optString("key", ""),
                        o.optString("model", "")
                });
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    public String[] getPreset(String name) {
        for (String[] p : listPresets()) {
            if (p[0].equals(name)) return p;
        }
        return null;
    }

    public void deletePreset(String name) {
        try {
            JSONArray arr = getPresetsArray();
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (!o.optString("name", "").equals(name)) out.put(o);
            }
            sp.edit().putString("presets", out.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private JSONArray getPresetsArray() throws Exception {
        String s = sp.getString("presets", "[]");
        return new JSONArray(s);
    }
}
