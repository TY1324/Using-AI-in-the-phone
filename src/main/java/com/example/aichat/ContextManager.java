package com.example.aichat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class ContextManager {

    // 策略常量
    public static final String STRATEGY_OFF = "off";
    public static final String STRATEGY_SLIDING = "sliding";
    public static final String STRATEGY_SUMMARY = "summary";

    private static final double CHARS_PER_TOKEN = 1.8;
    private static final int RECENT_KEEP = 10;

    public static String strategyLabel(String s) {
        if (STRATEGY_SLIDING.equals(s)) return "滑动窗口";
        if (STRATEGY_SUMMARY.equals(s)) return "摘要压缩";
        return "关闭";
    }

    public static int estimateTokens(String text) {
        if (text == null) return 0;
        return (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
    }

    public static int estimateTokens(JSONArray msgs) {
        int total = 0;
        if (msgs == null) return 0;
        for (int i = 0; i < msgs.length(); i++) {
            JSONObject m = msgs.optJSONObject(i);
            if (m == null) continue;
            String content = m.optString("content", "");
            if (content.isEmpty()) {
                JSONArray parts = m.optJSONArray("parts");
                if (parts != null) {
                    for (int j = 0; j < parts.length(); j++) {
                        JSONObject p = parts.optJSONObject(j);
                        if (p != null) total += estimateTokens(p.optString("text", ""));
                    }
                }
            } else {
                total += estimateTokens(content);
            }
        }
        return total;
    }

    /**
     * 按策略裁剪消息
     * @param msgs 原始消息
     * @param budgetTokens 预算（token）
     * @param strategy off / sliding / summary
     */
    public static JSONArray applyStrategy(JSONArray msgs, int budgetTokens, String strategy) {
        if (msgs == null || msgs.length() == 0) return msgs;
        if (STRATEGY_OFF.equals(strategy)) return msgs;
        if (estimateTokens(msgs) <= budgetTokens) return msgs;

        List<JSONObject> sys = new ArrayList<>();
        List<JSONObject> recent = new ArrayList<>();
        List<JSONObject> middle = new ArrayList<>();

        for (int i = 0; i < msgs.length(); i++) {
            JSONObject m = msgs.optJSONObject(i);
            if (m == null) continue;
            String role = m.optString("role", "");
            if ("system".equals(role)) {
                sys.add(m);
            } else if (i >= msgs.length() - RECENT_KEEP) {
                recent.add(m);
            } else {
                middle.add(m);
            }
        }

        JSONArray result = new JSONArray();
        int used = 0;
        for (JSONObject m : sys) {
            result.put(m);
            used += estimateTokens(m.optString("content", ""));
        }

        int recentTokens = 0;
        for (JSONObject m : recent) {
            recentTokens += estimateTokens(m.optString("content", ""));
        }

        if (used + recentTokens > budgetTokens) {
            // 连 system + 最近都不够，只保留最近 5 条
            for (int i = Math.max(0, recent.size() - 5); i < recent.size(); i++) {
                result.put(recent.get(i));
            }
            return result;
        }

        if (STRATEGY_SUMMARY.equals(strategy) && !middle.isEmpty()) {
            StringBuilder summary = new StringBuilder("[上文摘要] ");
            for (JSONObject m : middle) {
                String c = m.optString("content", "");
                if (c.length() > 200) c = c.substring(0, 200);
                summary.append(m.optString("role", "")).append(": ").append(c).append("; ");
            }
            int sumTokens = estimateTokens(summary.toString());
            if (used + sumTokens + recentTokens <= budgetTokens) {
                try {
                    JSONObject sumMsg = new JSONObject();
                    sumMsg.put("role", "system");
                    sumMsg.put("content", summary.toString());
                    result.put(sumMsg);
                } catch (Exception ignored) {
                }
            }
        }
        // sliding 策略：直接丢弃 middle

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
