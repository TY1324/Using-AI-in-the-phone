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

public class SubAgent {

    public static String run(String type, String host, String key, String model,
                            String system, String user) throws Exception {
        while (host.endsWith("/")) host = host.substring(0, host.length() - 1);

        JSONArray messages = new JSONArray();
        if (system != null && !system.trim().isEmpty()) {
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", system);
            messages.put(sys);
        }
        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", user);
        messages.put(userMsg);

        JSONObject body = new JSONObject();
        String url;
        HttpURLConnection c;

        if ("ANTHROPIC".equals(type.toUpperCase())) {
            url = host + "/v1/messages";
            body.put("model", model).put("max_tokens", 4096)
                    .put("stream", false).put("messages", messages);
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

        JSONObject j = new JSONObject(sb.toString());
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
                        StringBuilder sb2 = new StringBuilder();
                        for (int i = 0; i < parts.length(); i++) {
                            JSONObject p = parts.getJSONObject(i);
                            if (p.has("text")) sb2.append(p.getString("text"));
                        }
                        return sb2.toString();
                    }
                }
            }
        } else {
            JSONArray choices = j.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                JSONObject c0 = choices.getJSONObject(0);
                JSONObject msg = c0.optJSONObject("message");
                if (msg != null && msg.has("content")) {
                    return msg.getString("content");
                }
            }
        }
        return sb.toString();
    }
}
