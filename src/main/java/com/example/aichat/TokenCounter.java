package com.example.aichat;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Token 与费用统计
 * 按字符估算 token（中文 1.8 字/token），按模型价格表计算费用
 */
public class TokenCounter {

    public static class Price {
        public String model;
        public double input;   // 元 / 百万 token
        public double output;  // 元 / 百万 token

        public Price(String model, double input, double output) {
            this.model = model;
            this.input = input;
            this.output = output;
        }
    }

    public static class Usage {
        public int inputTokens;
        public int outputTokens;
        public double cost;
    }

    /**
     * 内置常见模型价格表（元/百万 token，可按需修改）
     */
    public static String defaultPricesJson() {
        JSONArray arr = new JSONArray();
        try {
            arr.put(price("deepseek-chat", 2.0, 8.0));
            arr.put(price("deepseek-reasoner", 4.0, 16.0));
            arr.put(price("deepseek-flash", 1.0, 2.0));
            arr.put(price("gpt-4o", 18.0, 72.0));
            arr.put(price("gpt-4o-mini", 1.08, 4.32));
            arr.put(price("claude-3-5-sonnet", 21.6, 108.0));
            arr.put(price("claude-3-5-haiku", 7.2, 36.0));
            arr.put(price("gemini-2.0-flash", 0.72, 2.88));
            arr.put(price("qwen-plus", 0.8, 2.0));
            arr.put(price("kimi-k2", 4.0, 16.0));
        } catch (Exception ignored) {
        }
        return arr.toString();
    }

    private static JSONObject price(String model, double input, double output) throws Exception {
        JSONObject o = new JSONObject();
        o.put("model", model);
        o.put("input", input);
        o.put("output", output);
        return o;
    }

    /**
     * 估算 token 数
     */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        return (int) Math.ceil(text.length() / 1.8);
    }

    /**
     * 计算一次对话的费用
     */
    public static Usage calc(Settings settings, String model, int inTokens, int outTokens) {
        Usage u = new Usage();
        u.inputTokens = inTokens;
        u.outputTokens = outTokens;

        double inPrice = 0;
        double outPrice = 0;
        try {
            JSONArray arr = new JSONArray(settings.pricesJson());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String m = o.optString("model", "");
                if (matchModel(m, model)) {
                    inPrice = o.optDouble("input", 0);
                    outPrice = o.optDouble("output", 0);
                    break;
                }
            }
        } catch (Exception ignored) {
        }

        u.cost = (inTokens / 1000000.0) * inPrice + (outTokens / 1000000.0) * outPrice;
        return u;
    }

    private static boolean matchModel(String priceModel, String actualModel) {
        if (priceModel == null || actualModel == null) return false;
        String p = priceModel.toLowerCase();
        String a = actualModel.toLowerCase();
        if (p.equals(a)) return true;
        // 前缀匹配，如 deepseek-chat 匹配 deepseek-chat-2501
        if (a.startsWith(p)) return true;
        if (p.startsWith("claude") && a.startsWith("claude")) return true;
        if (p.startsWith("gpt-4o") && a.startsWith("gpt-4o")) return true;
        if (p.startsWith("gemini") && a.startsWith("gemini")) return true;
        if (p.startsWith("deepseek") && a.startsWith("deepseek")) return true;
        return false;
    }

    /**
     * 从 API 返回的 usage 字段提取真实 token
     */
    public static int[] extractUsage(String type, String raw) {
        int[] result = {0, 0};
        try {
            JSONObject j = new JSONObject(raw);
            if ("ANTHROPIC".equals(type)) {
                JSONObject u = j.optJSONObject("usage");
                if (u != null) {
                    result[0] = u.optInt("input_tokens", 0);
                    result[1] = u.optInt("output_tokens", 0);
                }
            } else if ("GEMINI".equals(type)) {
                JSONObject u = j.optJSONObject("usageMetadata");
                if (u != null) {
                    result[0] = u.optInt("promptTokenCount", 0);
                    result[1] = u.optInt("candidatesTokenCount", 0);
                }
            } else {
                JSONObject u = j.optJSONObject("usage");
                if (u != null) {
                    result[0] = u.optInt("prompt_tokens", 0);
                    result[1] = u.optInt("completion_tokens", 0);
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    public static String formatCost(double cost) {
        if (cost < 0.0001) return "¥0";
        if (cost < 0.01) return String.format("¥%.5f", cost);
        if (cost < 1) return String.format("¥%.4f", cost);
        return String.format("¥%.3f", cost);
    }
}
