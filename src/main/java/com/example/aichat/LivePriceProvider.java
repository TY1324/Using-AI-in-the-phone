package com.example.aichat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 在线价格提供器
 *
 * 数据源（按优先级尝试，失败自动回退）：
 * 1. OpenRouter /api/v1/models —— 覆盖 300+ 模型，字段 input_cost_per_token（美元/Token）
 * 2. LiteLLM 价格表（GitHub / jsDelivr CDN）
 *
 * 汇率：open.er-api.com 获取 USD/CNY，失败用固定 7.2
 * 所有价格统一为「元 / 百万 token」
 */
public class LivePriceProvider {

    public interface Callback {
        void onDone(int count, String source);
        void onError(String msg);
    }

    private static final String OPENROUTER_URL = "https://openrouter.ai/api/v1/models";
    private static final String LITELLM_URL =
            "https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json";
    private static final String LITELLM_CDN_URL =
            "https://cdn.jsdelivr.net/gh/BerriAI/litellm@main/model_prices_and_context_window.json";
    private static final String EXCHANGE_URL = "https://open.er-api.com/v6/latest/USD";

    private static double cachedRate = 0;

    public static double usdToCnyRate() {
        if (cachedRate > 0) return cachedRate;
        try {
            String json = httpGet(EXCHANGE_URL, 10000);
            JSONObject o = new JSONObject(json);
            JSONObject rates = o.optJSONObject("rates");
            if (rates != null && rates.has("CNY")) {
                cachedRate = rates.optDouble("CNY", 0);
            }
        } catch (Exception ignored) {
        }
        if (cachedRate <= 0) cachedRate = 7.2;
        return cachedRate;
    }

    /**
     * 拉取在线价格并合并进 Settings
     */
    public static void fetchAndMerge(final Settings settings, final Callback cb) {
        new Thread(() -> {
            try {
                String json = httpGet(OPENROUTER_URL, 15000);
                int n = mergeOpenRouter(settings, json);
                if (n > 0) {
                    cb.onDone(n, "OpenRouter");
                    return;
                }
            } catch (Exception ignored) {
            }

            try {
                String json;
                try {
                    json = httpGet(LITELLM_URL, 20000);
                } catch (Exception e) {
                    json = httpGet(LITELLM_CDN_URL, 20000);
                }
                int n = mergeLiteLLM(settings, json);
                if (n > 0) {
                    cb.onDone(n, "LiteLLM");
                    return;
                }
            } catch (Exception ignored) {
            }

            cb.onError("在线价格获取失败，已使用本地价格表");
        }).start();
    }

    // ========== OpenRouter ==========

    private static int mergeOpenRouter(Settings settings, String json) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONArray data = root.optJSONArray("data");
        if (data == null) return 0;

        Map<String, double[]> merged = loadExisting(settings);
        double rate = usdToCnyRate();

        for (int i = 0; i < data.length(); i++) {
            JSONObject m = data.getJSONObject(i);
            String id = m.optString("id", "");
            if (id.isEmpty()) continue;

            double inUsd = m.optDouble("input_cost_per_token", 0);
            double outUsd = m.optDouble("output_cost_per_token", 0);
            if (inUsd <= 0 && outUsd <= 0) continue;

            double inCny = inUsd * rate * 1000000.0;
            double outCny = outUsd * rate * 1000000.0;
            merged.put(id, new double[]{inCny, outCny});
        }

        saveMerged(settings, merged);
        return merged.size();
    }

    // ========== LiteLLM ==========

    private static int mergeLiteLLM(Settings settings, String json) throws Exception {
        JSONObject root = new JSONObject(json);
        Map<String, double[]> merged = loadExisting(settings);
        double rate = usdToCnyRate();

        Iterator<String> it = root.keys();
        while (it.hasNext()) {
            String key = it.next();
            JSONObject m = root.optJSONObject(key);
            if (m == null) continue;

            double inUsd = m.optDouble("input_cost_per_token", 0);
            double outUsd = m.optDouble("output_cost_per_token", 0);
            if (inUsd <= 0 && outUsd <= 0) continue;

            double inCny = inUsd * rate * 1000000.0;
            double outCny = outUsd * rate * 1000000.0;
            merged.put(key, new double[]{inCny, outCny});
        }

        saveMerged(settings, merged);
        return merged.size();
    }

    // ========== 与本地表合并 ==========

    private static Map<String, double[]> loadExisting(Settings settings) {
        Map<String, double[]> map = new HashMap<>();
        try {
            JSONArray arr = new JSONArray(settings.pricesJson());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String model = o.optString("model", "");
                if (model.isEmpty()) continue;
                map.put(model, new double[]{
                        o.optDouble("input", 0),
                        o.optDouble("output", 0)
                });
            }
        } catch (Exception ignored) {
        }
        return map;
    }

    private static void saveMerged(Settings settings, Map<String, double[]> map) {
        JSONArray arr = new JSONArray();
        for (Map.Entry<String, double[]> e : map.entrySet()) {
            try {
                JSONObject o = new JSONObject();
                o.put("model", e.getKey());
                o.put("input", round(e.getValue()[0]));
                o.put("output", round(e.getValue()[1]));
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        settings.savePrices(arr.toString());
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    /**
     * 在已保存的价格表中查询某个模型
     * @return {input, output} 元/百万token；找不到返回 null
     */
    public static double[] lookup(Settings settings, String model) {
        if (settings == null || model == null || model.isEmpty()) return null;
        try {
            JSONArray arr = new JSONArray(settings.pricesJson());
            String target = model.toLowerCase();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String m = o.optString("model", "").toLowerCase();
                if (m.equals(target) || target.startsWith(m) || m.startsWith(target)) {
                    return new double[]{o.optDouble("input", 0), o.optDouble("output", 0)};
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // ========== HTTP ==========

    private static String httpGet(String urlStr, int timeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setRequestMethod("GET");
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "AiChat-Android/1.0");
        c.setConnectTimeout(timeout);
        c.setReadTimeout(timeout);

        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code);
        }

        InputStream in = c.getInputStream();
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        c.disconnect();
        return sb.toString();
    }
}
