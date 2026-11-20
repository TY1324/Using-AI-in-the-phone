package com.example.aichat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class ContextManager {

    private static final double CHARS_PER_TOKEN = 1.8;
    private static final int RECENT_KEEP = 10;

    public static int estimateTokens(String text) {
        if (text == null) return 0;
        return (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
    }

    public static int estimateTokens(JSONArray msgs) {
        int total = 0;
        for (int i = 0; i < msgs.length(); i++) {
            JSONObject m = msgs.optJSONObject(i);
            if (m == null) continue;
            String content = m.getString("content");
            if (content.isEmpty()) {
                JSONArray parts = m.optJSONArray("parts");
                if (parts != null) {
                    for (int j = 0; j < parts.length(); j++) {
                        JSONObject p = parts.optJSONObject(j);
                        if (p != null && p.has("text")) {
                            total += estimateTokens(p.getString("text"));
                        }
                    }
                }
            } else {
                total += estimateTokens(content);
            }
        }
        return total;
    }

    public static JSONArray trimToBudget(JSONArray original, int budgetTokens) {
        if (original == null || original.length() == 0) return original;
        if (estimateTokens(original) <= budgetTokens) return original;

        List<JSONObject> sys = new ArrayList<>();
        List<JSONObject> recent = new ArrayList<>();
        List<JSONObject> middle = new ArrayList<>();

        for (int i = 0; i < original.length(); i++) {
            JSONObject m = original.optJSONObject(i);
            if (m == null) continue;
            String role = m.getString("role");
            if ("system".equals(role)) {
                sys.add(m);
            } else if (i >= original.length() - RECENT_KEEP) {
                recent.add(m);
            } else {
                middle.add(m);
            }
        }

        JSONArray result = new JSONArray();
        int used = 0;
        for (JSONObject m : sys) {
            result.put(m);
            used += estimateTokens(m.getString("content"));
        }

        int recentTokens = 0;
        for (JSONObject m : recent) recentTokens += estimateTokens(m.getString("content"));
        if (used + recentTokens > budgetTokens) {
            for (int i = Math.max(0, recent.size() - 5); i < recent.size(); i++) {
                result.put(recent.get(i));
            }
            return result;
        }

        if (!middle.isEmpty()) {
            StringBuilder summary = new StringBuilder("[上文摘要] ");
            for (JSONObject m : middle) {
                String c = m.getString("content");
                if (c.length() > 200) c = c.substring(0, 200);
                summary.append(m.getString("role")).append(": ").append(c).append("; ");
            }
            JSONObject sumMsg = new JSONObject();
            try {
                sumMsg.put("role", "system");
                sumMsg.put("content", summary.toString());
            } catch (org.json.JSONException e) {
            }
            int sumTokens = estimateTokens(summary.toString());
            if (used + sumTokens + recentTokens <= budgetTokens) {
                result.put(sumMsg);
                used += sumTokens;
            }
        }

        for (JSONObject m : recent) {
            result.put(m);
        }
        return result;
    }

    public static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }
}
