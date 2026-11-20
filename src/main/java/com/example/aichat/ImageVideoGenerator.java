package com.example.aichat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
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

    public static String generateVideo(String host, String key, String model,
                                      String prompt, String duration) throws Exception {
        while (host.endsWith("/")) host = host.substring(0, host.length() - 1);
        // 兼容 OpenAI video generations endpoint
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

    public static Bitmap base64ToBitmap(String b64) {
        try {
            byte[] decoded = Base64.decode(b64, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(decoded, 0, decoded.length);
        } catch (Exception e) {
            return null;
        }
    }
}
