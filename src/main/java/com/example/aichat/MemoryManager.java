package com.example.aichat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class MemoryManager {

    public interface Callback {
        void onDone(String memory);
        void onError(String msg);
    }

    public static void extract(String type, String host, String key, String model,
                               String recentDialogue, final String prevMemory,
                               final Callback cb) {
        new Thread(() -> {
            try {
                String result = doExtract(type, host, key, model, recentDialogue, prevMemory);
                cb.onDone(result);
            } catch (Exception e) {
                cb.onError(e.getMessage());
            }
        }).start();
    }

    private static String doExtract(String type, String host, String key, String model,
                                    String recentDialogue, String prevMemory) throws Exception {
        while (host.endsWith("/")) host = host.substring(0, host.length() - 1);

        JSONArray messages = new JSONArray();

        JSONObject sys = new JSONObject();
        sys.put("role", "system");
        sys.put("content", "你是记忆抽取器。阅读对话，提取值得长期记住的关键信息。\n"
                + "要求：\n"
                + "1. 只输出 3-5 条要点，每条一行，用「- 」开头\n"
                + "2. 保留：用户偏好、重要事实、未完成事项、纠正过的错误\n"
                + "3. 不要输出寒暄、重复、无关内容\n"
                + "4. 如无值得记住的内容，输出「（无）」\n"
                + "5. 只输出要点本身，不要任何解释或标题");
        messages.put(sys);

        if (prevMemory != null && !prevMemory.trim().isEmpty()) {
            JSONObject old = new JSONObject();
            old.put("role", "system");
            old.put("content", "已有记忆：\n" + prevMemory);
            messages.put(old);
        }

        JSONObject user = new JSONObject();
        user.put("role", "user");
        user.put("content", "请从以下对话中抽取记忆：\n\n" + recentDialogue);
        messages.put(user);

        JSONObject body = new JSONObject();
        String url;
        HttpURLConnection c;

        if ("ANTHROPIC".equals(type.toUpperCase())) {
            url = host + "/v1/messages";
            body.put("model", model).put("max_tokens", 1024).put("stream", false)
                    .put("messages", messages);
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestProperty("x-api-key", key);
            c.setRequestProperty("anthropic-version", "2023-06-01");
        } else if ("GEMINI".equals(type.toUpperCase())) {
            url = host + "/v1beta/models/" + java.net.URLEncoder.encode(model, "UTF-8")
                    + ":generateContent";
            body.put("contents", messages);
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestProperty("x-goog-api-key", key);
        } else {
            url = host + "/v1/chat/completions";
            body.put("model", model).put("stream", false).put("messages", messages);
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestProperty("Authorization", "Bearer " + key);
        }

        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setDoOutput(true);
        c.setConnectTimeout(30000);
        c.setReadTimeout(120000);

        OutputStream os = c.getOutputStream();
        os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        os.flush();
        os.close();

        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            InputStream es = c.getErrorStream();
            StringBuilder eb = new StringBuilder();
            if (es != null) {
                BufferedReader er = new BufferedReader(new InputStreamReader(es, StandardCharsets.UTF_8));
                String l;
                while ((l = er.readLine()) != null) eb.append(l);
                er.close();
            }
            throw new Exception("HTTP " + code + " " + eb.toString());
        }

        BufferedReader br = new BufferedReader(
                new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        c.disconnect();

        return extractText(type, sb.toString());
    }

    public static JSONObject toSystemMessage(String memory) {
        if (memory == null || memory.trim().isEmpty()) return null;
        if (memory.trim().equals("（无）")) return null;
        try {
            JSONObject o = new JSONObject();
            o.put("role", "system");
            o.put("content", "关于用户的长期记忆：\n" + memory);
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractText(String type, String raw) {
        try {
            JSONObject j = new JSONObject(raw);
            if ("ANTHROPIC".equals(type.toUpperCase())) {
                JSONArray content = j.optJSONArray("content");
                if (content != null && content.length() > 0) {
                    JSONObject item = content.getJSONObject(0);
                    if (item.has("text")) return item.getString("text");
                }
            } else if ("GEMINI".equals(type.toUpperCase())) {
                JSONArray cands = j.optJSONArray("candidates");
                if (cands != null && cands.length() > 0) {
                    JSONObject c0 = cands.getJSONObject(0);
                    JSONObject content = c0.optJSONObject("content");
                    if (content != null) {
                        JSONArray parts = content.optJSONArray("parts");
                        if (parts != null) {
                            StringBuilder sb = new StringBuilder();
                            for (int i = 0; i < parts.length(); i++) {
                                JSONObject p = parts.getJSONObject(i);
                                if (p.has("text")) sb.append(p.getString("text"));
                            }
                            return sb.toString();
                        }
                    }
                }
            } else {
                JSONArray choices = j.optJSONArray("choices");
                if (choices != null && choices.length() > 0) {
                    JSONObject c0 = choices.getJSONObject(0);
                    JSONObject msg = c0.optJSONObject("message");
                    if (msg != null && msg.has("content")) return msg.getString("content");
                }
            }
        } catch (Exception ignored) {
        }
        return raw;
    }
}
