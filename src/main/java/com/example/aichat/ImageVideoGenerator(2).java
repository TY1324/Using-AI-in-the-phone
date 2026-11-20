package com.example.aichat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class ImageVideoGenerator {

    public static String generateImage(String host, String key, String model,
                                       String prompt, String size) throws Exception {
        while (host.endsWith("/")) host = host.substring(0, host.length() - 1);
        String url = host + "/v1/images/generations";

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("prompt", prompt);
        body.put("n", 1);
        body.put("size", size);
        body.put("response_format", "b64_json");

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Authorization", "Bearer " + key);
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
            String detail = eb.toString();
            if (detail.length() > 200) detail = detail.substring(0, 200);
            throw new Exception("HTTP " + code + " " + detail);
        }

        BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        c.disconnect();

        JSONObject j = new JSONObject(sb.toString());
        JSONArray data = j.getJSONArray("data");
        if (data.length() == 0) throw new Exception("无图片返回");
        JSONObject item = data.getJSONObject(0);
        if (item.has("b64_json")) return item.getString("b64_json");
        if (item.has("url")) return item.getString("url");
        throw new Exception("无法解析图片数据");
    }

    /**
     * Sora 视频生成
     */
    public static String generateVideoSora(String host, String key, String model,
                                           String prompt, String duration) throws Exception {
        while (host.endsWith("/")) host = host.substring(0, host.length() - 1);
        String url = host + "/v1/videos/generations";

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("prompt", prompt);
        body.put("duration", duration);

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Authorization", "Bearer " + key);
        c.setDoOutput(true);
        c.setConnectTimeout(30000);
        c.setReadTimeout(300000);

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

        BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        c.disconnect();

        JSONObject j = new JSONObject(sb.toString());
        if (j.has("data")) {
            JSONArray data = j.getJSONArray("data");
            if (data.length() > 0) {
                JSONObject item = data.getJSONObject(0);
                if (item.has("url")) return item.getString("url");
                if (item.has("b64_json")) return item.getString("b64_json");
            }
        }
        if (j.has("url")) return j.getString("url");
        throw new Exception("无法解析视频数据");
    }

    /**
     * Claude 代码生成视频（通过 Claude 生成 HTML/JS 动画代码，返回可执行的 HTML 字符串）
     */
    public static String generateVideoClaudeCode(String claudeHost, String claudeKey, String claudeModel,
                                                 String prompt, String duration) throws Exception {
        while (claudeHost.endsWith("/")) claudeHost = claudeHost.substring(0, claudeHost.length() - 1);
        String url = claudeHost + "/v1/messages";

        JSONObject body = new JSONObject();
        body.put("model", claudeModel);
        body.put("max_tokens", 4096);
        body.put("stream", false);

        JSONObject system = new JSONObject();
        system.put("type", "text");
        system.put("text", "你是一个前端动画生成器。根据用户描述，生成一段自包含的 HTML+CSS+JS 动画代码，"
                + "用 Canvas 或 SVG 实现视觉动画。要求："
                + "1. 只输出完整 HTML 代码，不要任何解释；"
                + "2. 动画时长 " + duration + " 秒；"
                + "3. 使用 requestAnimationFrame 或 setInterval 驱动；"
                + "4. 视觉上要有明显的运动、变化或过渡效果；"
                + "5. 代码必须能直接在浏览器运行。");
        body.put("system", system);

        JSONArray messages = new JSONArray();
        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", prompt);
        messages.put(userMsg);
        body.put("messages", messages);

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("x-api-key", claudeKey);
        c.setRequestProperty("anthropic-version", "2023-06-01");
        c.setDoOutput(true);
        c.setConnectTimeout(30000);
        c.setReadTimeout(300000);

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

        BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        c.disconnect();

        JSONObject j = new JSONObject(sb.toString());
        JSONArray content = j.getJSONArray("content");
        if (content.length() == 0) throw new Exception("Claude 未返回内容");
        JSONObject item = content.getJSONObject(0);
        if (!item.has("text")) throw new Exception("Claude 未返回文本");
        return item.getString("text");
    }

    public static Bitmap base64ToBitmap(String b64) {
        try {
            byte[] decoded = Base64.decode(b64);
            return BitmapFactory.decodeByteArray(decoded, 0, decoded.length);
        } catch (Exception e) {
            return null;
        }
    }
}