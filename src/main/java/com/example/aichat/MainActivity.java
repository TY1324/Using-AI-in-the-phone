package com.example.aichat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private LinearLayout list;
    private ScrollView scroll;
    private EditText input;
    private TextView status;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<String[]> history = new ArrayList<>();
    private SharedPreferences sp;
    private boolean busy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sp = getSharedPreferences("chat", Context.MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(12), dp(12), dp(8), dp(8));
        TextView title = new TextView(this);
        title.setText("AI Chat");
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(title, tp);
        Button settings = new Button(this);
        settings.setText("设置");
        settings.setOnClickListener(v -> showSettings());
        bar.addView(settings);
        root.addView(bar);

        scroll = new ScrollView(this);
        LinearLayout.LayoutParams sp1 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, sp1);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(4), dp(12), dp(12));
        scroll.addView(list);

        status = new TextView(this);
        status.setTextColor(Color.RED);
        status.setPadding(dp(12), 0, dp(12), dp(4));
        root.addView(status);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setPadding(dp(12), dp(8), dp(12), dp(12));
        input = new EditText(this);
        input.setHint("输入内容…");
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bottom.addView(input, ip);
        Button send = new Button(this);
        send.setText("发送");
        send.setOnClickListener(v -> doSend());
        bottom.addView(send);
        root.addView(bottom);

        setContentView(root);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private TextView addBubble(String role, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(15);
        tv.setPadding(dp(12), dp(8), dp(12), dp(8));
        tv.setBackgroundColor("user".equals(role) ? 0xFFE3F2FD : 0xFFF2F2F2);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(6);
        list.addView(tv, p);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        return tv;
    }

    private void doSend() {
        final String text = input.getText().toString().trim();
        if (text.isEmpty() || busy) return;
        input.setText("");
        status.setText("");
        busy = true;
        addBubble("user", text);
        history.add(new String[]{"user", text});
        final TextView reply = addBubble("assistant", "");
        new Thread(() -> {
            StringBuilder acc = new StringBuilder();
            try {
                stream(acc, reply);
                history.add(new String[]{"assistant", acc.toString()});
            } catch (Exception e) {
                final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                ui.post(() -> status.setText("出错：" + msg));
            } finally {
                busy = false;
            }
        }).start();
    }

    private String cfg(String key, String def) {
        return sp.getString(key, def);
    }

    private void stream(StringBuilder acc, TextView reply) throws Exception {
        String type = cfg("type", "OPENAI").toUpperCase();
        String host = cfg("host", "https://api.deepseek.com").trim();
        String key = cfg("key", "");
        String model = cfg("model", "deepseek-chat");
        while (host.endsWith("/")) host = host.substring(0, host.length() - 1);

        JSONArray msgs = new JSONArray();
        for (String[] m : history) {
            if (m[1] == null || m[1].trim().isEmpty()) continue;
            JSONObject o = new JSONObject();
            if ("GEMINI".equals(type)) {
                o.put("role", "user".equals(m[0]) ? "user" : "model");
                JSONArray parts = new JSONArray();
                parts.put(new JSONObject().put("text", m[1]));
                o.put("parts", parts);
            } else {
                o.put("role", "user".equals(m[0]) ? "user" : "assistant");
                o.put("content", m[1]);
            }
            msgs.put(o);
        }

        JSONObject body = new JSONObject();
        String url;
        HttpURLConnection c;
        if ("ANTHROPIC".equals(type)) {
            url = host + "/v1/messages";
            body.put("model", model).put("max_tokens", 4096).put("stream", true).put("messages", msgs);
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestProperty("x-api-key", key);
            c.setRequestProperty("anthropic-version", "2023-06-01");
        } else if ("GEMINI".equals(type)) {
            url = host + "/v1beta/models/" + URLEncoder.encode(model, "UTF-8")
                    + ":streamGenerateContent?alt=sse";
            body.put("contents", msgs);
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestProperty("x-goog-api-key", key);
        } else {
            url = host + "/v1/chat/completions";
            body.put("model", model).put("stream", true).put("messages", msgs);
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
        String line;
        while ((line = br.readLine()) != null) {
            if (!line.startsWith("data:")) continue;
            String data = line.substring(5).trim();
            if ("[DONE]".equals(data)) break;
            String delta = extractDelta(type, data);
            if (delta != null && !delta.isEmpty()) {
                acc.append(delta);
                final String shown = acc.toString();
                ui.post(() -> reply.setText(shown));
            }
        }
        br.close();
        c.disconnect();
    }

    private String extractDelta(String type, String data) {
        try {
            JSONObject j = new JSONObject(data);
            if ("ANTHROPIC".equals(type)) {
                if ("content_block_delta".equals(j.optString("type"))) {
                    JSONObject d = j.optJSONObject("delta");
                    return d == null ? "" : d.optString("text", "");
                }
                return "";
            } else if ("GEMINI".equals(type)) {
                JSONArray cands = j.optJSONArray("candidates");
                if (cands == null) return "";
                JSONObject c0 = cands.optJSONObject(0);
                if (c0 == null) return "";
                JSONObject content = c0.optJSONObject("content");
                if (content == null) return "";
                JSONArray parts = content.optJSONArray("parts");
                if (parts == null) return "";
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < parts.length(); i++) {
                    JSONObject p = parts.optJSONObject(i);
                    if (p != null && p.has("text")) sb.append(p.optString("text", ""));
                }
                return sb.toString();
            } else {
                JSONArray ch = j.optJSONArray("choices");
                if (ch == null) return "";
                JSONObject c0 = ch.optJSONObject(0);
                if (c0 == null) return "";
                JSONObject d = c0.optJSONObject("delta");
                return d == null ? "" : d.optString("content", "");
            }
        } catch (Exception e) {
            return "";
        }
    }

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));

        final EditText etType = new EditText(this);
        etType.setHint("OPENAI / ANTHROPIC / GEMINI");
        etType.setText(cfg("type", "OPENAI"));
        box.addView(etType);

        final EditText etHost = new EditText(this);
        etHost.setHint("https://api.deepseek.com");
        etHost.setText(cfg("host", "https://api.deepseek.com"));
        box.addView(etHost);

        final EditText etKey = new EditText(this);
        etKey.setHint("API Key");
        etKey.setText(cfg("key", ""));
        box.addView(etKey);

        final EditText etModel = new EditText(this);
        etModel.setHint("模型 ID，如 deepseek-chat");
        etModel.setText(cfg("model", "deepseek-chat"));
        box.addView(etModel);

        new AlertDialog.Builder(this)
                .setTitle("设置")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> sp.edit()
                        .putString("type", etType.getText().toString().trim().toUpperCase())
                        .putString("host", etHost.getText().toString().trim())
                        .putString("key", etKey.getText().toString().trim())
                        .putString("model", etModel.getText().toString().trim())
                        .apply())
                .setNegativeButton("取消", null)
                .show();
    }
}
