package com.example.aichat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class ContextManager {

    // 粗略估算：中文 1.5 字/token，英文 4 字/token
    private static final double CHARS_PER_TOKEN = 1.8;

    // 默认预算（可被模型 maxTokens 覆盖）
    private static final int DEFAULT_BUDGET = 12000;

    // 最近保留条数（滑动窗口）
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
            String content = m.optString("content", "");
            if (content.isEmpty()) {
                // OpenAI multimodal parts
                JSONArray parts = m.optJSONArray("parts");
                if (parts != null) {
                    for (int j = 0; j < parts.length(); j++) {
                        JSONObject p = parts.optJSONObject(j);
                        if (p != null && p.has("text")) {
                            total += estimateTokens(p.optString("text", ""));
                        }
                    }
                }
            } else {
                total += estimateTokens(content);
            }
        }
        return total;
    }

    /**
     * 裁剪到预算内：
     * 1. 保留 system + skill
     * 2. 保留最近 RECENT_KEEP 条
     * 3. 中间部分如果超预算，生成摘要替换
     */
    public static JSONArray trimToBudget(JSONArray original, int budgetTokens) {
        if (original == null || original.length() == 0) return original;
        if (estimateTokens(original) <= budgetTokens) return original;

        List<JSONObject> sys = new ArrayList<>();
        List<JSONObject> recent = new ArrayList<>();
        List<JSONObject> middle = new ArrayList<>();

        for (int i = 0; i < original.length(); i++) {
            JSONObject m = original.optJSONObject(i);
            if (m == null) continue;
            String role = m.optString("role", "");
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
            used += estimateTokens(m.optString("content", ""));
        }

        // 如果连 system + recent 都超预算，只保留 system + 最近几条
        int recentTokens = 0;
        for (JSONObject m : recent) recentTokens += estimateTokens(m.optString("content", ""));
        if (used + recentTokens > budgetTokens) {
            // 只保留最近 5 条
            for (int i = Math.max(0, recent.size() - 5); i < recent.size(); i++) {
                result.put(recent.get(i));
            }
            return result;
        }

        // 中间部分生成摘要（简单拼接前 200 字）
        if (!middle.isEmpty()) {
            StringBuilder summary = new StringBuilder("[上文摘要] ");
            for (JSONObject m : middle) {
                String c = m.optString("content", "");
                if (c.length() > 200) c = c.substring(0, 200);
                summary.append(m.optString("role", "")).append(": ").append(c).append("; ");
            }
            JSONObject sumMsg = new JSONObject();
            sumMsg.put("role", "system");
            sumMsg.put("content", summary.toString());
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
}
