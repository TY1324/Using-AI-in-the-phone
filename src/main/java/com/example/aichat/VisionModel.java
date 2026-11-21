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

/**
 * 视觉理解模型：独立于主模型配置，用于图片理解。
 *
 * 当主模型不支持视觉时，可临时切换到视觉模型。
 */
public class VisionModel {

    public String name = "";
    public String type = "OPENAI";
    public String host = "";
    public String key = "";
    public String model = "";

    public VisionModel() {}

    public VisionModel(String name, String type, String host, String key, String model) {
        this.name = name;
        this.type = type;
        this.host = host;
        this.key = key;
        this.model = model;
    }

    /**
     * 判断当前配置是否可用（四个字段都非空）
     */
    public boolean isConfigured() {
        return !isEmpty(host) && !isEmpty(key) && !isEmpty(model);
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * 调用视觉模型分析图片，返回文字描述
     */
    public String analyze(String prompt, String base64Image) throws Exception {
        String t = type.toUpperCase();
        while (host.endsWith("/")) host = host.substring(0, host.length() - 1);

        JSONArray msgs = new JSONArray();
        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");

        if ("ANTHROPIC".equals(t)) {
            JSONArray parts = new JSONArray();
            JSONObject textPart = new JSONObject();
            textPart.put("type", "text");
            textPart.put("text", prompt);
            parts.put(textPart);
            JSONObject imgPart = new JSONObject();
            imgPart.put("type", "image");
            JSONObject src = new JSONObject();
            src.put("type", "base64");
            src.put("media_type", "image/jpeg");
            src.put("data", base64Image);
            imgPart.put("source", src);
            parts.put(imgPart);
            userMsg.put("content", parts);
        } else if ("GEMINI".equals(t)) {
            JSONArray parts = new JSONArray();
            JSONObject textPart = new JSONObject();
            textPart.put("text", prompt);
            parts.put(textPart);
            JSONObject imgPart = new JSONObject();
            JSONObject inline = new JSONObject();
            inline.put("mime_type", "image/jpeg");
            inline.put("data", base64Image);
            imgPart.put("inline_data", inline);
            parts.put(imgPart);
            userMsg.put("content", parts);
        } else {
            JSONArray parts = new JSONArray();
            JSONObject textPart = new JSONObject();
            textPart.put("type", "text");
            textPart.put("text", prompt);
            parts.put(textPart);
            JSONObject imgPart = new JSONObject();
            imgPart.put("type", "image_url");
            JSONObject urlObj = new JSONObject();
            urlObj.put("url", "data:image/jpeg;base64," + base64Image);
            imgPart.put("image_url", urlObj);
            parts.put(imgPart);
            userMsg.put("content", parts);
        }
        msgs.put(userMsg);

        JSONObject body = new JSONObject();
        String url;
        HttpURLConnection c;

        if ("ANTHROPIC".equals(t)) {
            url = host + "/v1/messages";
            body.put("model", model).put("max_tokens", 2048).put("stream", false)
                    .put("messages", msgs);
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestProperty("x-api-key", key);
            c.setRequestProperty("anthropic-version", "2023-06-01");
        } else if ("GEMINI".equals(t)) {
            url = host + "/v1beta/models/" + java.net.URLEncoder.encode(model, "UTF-8")
                    + ":generateContent";
            body.put("contents", msgs);
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestProperty("x-goog-api-key", key);
        } else {
            url = host + "/v1/chat/completions";
            body.put("model", model).put("stream", false).put("messages", msgs);
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestProperty("Authorization", "Bearer " + key);
        }

        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setDoOutput(true);
        c.setConnectTimeout(30000);
        c.setReadTimeout(180000);

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
            String detail = eb.toString();
            if (detail.length() > 200) detail = detail.substring(0, 200);
            throw new Exception("HTTP " + code + " " + detail);
        }

        BufferedReader br = new BufferedReader(
                new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        c.disconnect();

        return extractText(t, sb.toString());
    }

    private static String extractText(String type, String raw) {
        try {
            JSONObject j = new JSONObject(raw);
            if ("ANTHROPIC".equals(type)) {
                JSONArray content = j.optJSONArray("content");
                if (content != null && content.length() > 0) {
                    JSONObject item = content.getJSONObject(0);
                    if (item.has("text")) return item.getString("text");
                }
            } else if ("GEMINI".equals(type)) {
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
