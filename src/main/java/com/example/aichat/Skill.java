package com.example.aichat;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class Skill {
    public String name;
    public String content;
    public String description;

    public Skill(String name, String content, String description) {
        this.name = name;
        this.content = content;
        this.description = description;
    }

    private static final String PREFS_NAME = "skills_prefs";
    private static final String KEY_LIST = "skills_list";

    public static void save(Context ctx, Skill s) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        try {
            JSONArray arr = loadArray(sp);
            JSONArray out = new JSONArray();
            boolean found = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (o.optString("name", "").equals(s.name)) {
                    out.put(toJson(s));
                    found = true;
                } else {
                    out.put(o);
                }
            }
            if (!found) out.put(toJson(s));
            sp.edit().putString(KEY_LIST, out.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public static List<Skill> loadAll(Context ctx) {
        List<Skill> list = new ArrayList<>();
        SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        try {
            JSONArray arr = loadArray(sp);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new Skill(
                        o.optString("name", ""),
                        o.optString("content", ""),
                        o.optString("description", "")
                ));
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    public static void delete(Context ctx, String name) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        try {
            JSONArray arr = loadArray(sp);
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (!o.optString("name", "").equals(name)) out.put(o);
            }
            sp.edit().putString(KEY_LIST, out.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private static JSONArray loadArray(SharedPreferences sp) throws Exception {
        String s = sp.getString(KEY_LIST, "[]");
        return new JSONArray(s);
    }

    private static JSONObject toJson(Skill s) throws Exception {
        JSONObject o = new JSONObject();
        o.put("name", s.name);
        o.put("content", s.content);
        o.put("description", s.description);
        return o;
    }
}
