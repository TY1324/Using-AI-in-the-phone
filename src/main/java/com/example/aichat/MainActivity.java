package com.example.aichat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
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
    private final List<String> pendingImages = new ArrayList<>();
    private String pendingFileName = "";
    private String pendingFileText = "";
    private Settings settings;
    private Store store;
    private long currentConvId = -1;
    private boolean busy = false;

    private Button sendBtn;
    private volatile boolean cancelled = false;
    private HttpURLConnection currentConn = null;
    private Thread workThread = null;

    private int bgColor;
    private int textColor;
    private int bubbleUser;
    private int bubbleAi;

    private static final int PICK_IMAGE = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = new Settings(this);
        store = new Store(this);
        applyTheme();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bgColor);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(12), dp(12), dp(8), dp(8));
        TextView title = new TextView(this);
        title.setText("AI Chat");
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(textColor);
        LinearLayout.LayoutParams tp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(title, tp);
        Button sessions = new Button(this);
        sessions.setText("会话");
        sessions.setOnClickListener(v -> showSessions());
        bar.addView(sessions);
        Button groups = new Button(this);
        groups.setText("组");
        groups.setOnClickListener(v -> showGroups());
        bar.addView(groups);
        Button settingsBtn = new Button(this);
        settingsBtn.setText("设置");
        settingsBtn.setOnClickListener(v -> showSettings());
        bar.addView(settingsBtn);
        root.addView(bar);

        scroll = new ScrollView(this);
        LinearLayout.LayoutParams sp1 =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
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
        bottom.setPadding(dp(12), dp(8), dp(12), dp(8));

        Button imgBtn = new Button(this);
        imgBtn.setText("图");
        imgBtn.setOnClickListener(v -> pickImage());
        bottom.addView(imgBtn);

        Button fileBtn = new Button(this);
        fileBtn.setText("文");
        fileBtn.setOnClickListener(v -> FileHelper.pick(this));
        bottom.addView(fileBtn);

        input = new EditText(this);
        input.setHint("输入内容…");
        input.setTextColor(textColor);
        input.setHintTextColor(0xFF9E9E9E);
        LinearLayout.LayoutParams ip =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bottom.addView(input, ip);

        sendBtn = new Button(this);
        sendBtn.setText("发送");
        sendBtn.setOnClickListener(v -> {
            if (busy) {
                doStop();
            } else {
                doSend();
            }
        });
        bottom.addView(sendBtn);
        root.addView(bottom);

        setContentView(root);

        currentConvId = store.getLatestConversationId();
        if (currentConvId == -1) {
            currentConvId = store.newConversation();
        }
        loadHistory(currentConvId);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_IMAGE) {
            if (resultCode == RESULT_OK && data != null) {
                handleImage(data.getData());
            }
        } else if (requestCode == FileHelper.PICK_FILE) {
            FileHelper.handle(this, resultCode, data, new FileHelper.Callback() {
                @Override
                public void onText(final String name, final String text) {
                    ui.post(() -> {
                        pendingFileName = name;
                        pendingFileText = text;
                        status.setText("已附加文件：" + name + "（" + text.length() + " 字）");
                    });
                }

                @Override
                public void onError(final String msg) {
                    ui.post(() -> status.setText(msg));
                }
            });
        }
    }

    private void pickImage() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(i, PICK_IMAGE);
    }

    private void handleImage(Uri uri) {
        if (uri == null) return;
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            Bitmap bmp = BitmapFactory.decodeStream(in);
            if (in != null) in.close();
            if (bmp == null) {
                status.setText("图片解码失败");
                return;
            }
            int max = 1024;
            int w = bmp.getWidth();
            int h = bmp.getHeight();
            if (w > max || h > max) {
                float scale = Math.min((float) max / w, (float) max / h);
                bmp = Bitmap.createScaledBitmap(bmp, (int) (w * scale), (int) (h * scale), true);
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, baos);
            String b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
            pendingImages.add(b64);
            status.setText("已添加图片 " + pendingImages.size() + " 张");
        } catch (Exception e) {
            status.setText("图片读取失败：" + e.getMessage());
        }
    }

    private void applyTheme() {
        int night = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        boolean dark = night == Configuration.UI_MODE_NIGHT_YES;
        if (dark) {
            bgColor = 0xFF121212;
            textColor = 0xFFE8E8E8;
            bubbleUser = 0xFF2C3E50;
            bubbleAi = 0xFF1E1E1E;
        } else {
            bgColor = 0xFFFFFFFF;
            textColor = 0xFF1A1A1A;
            bubbleUser = 0xFFE3F2FD;
            bubbleAi = 0xFFF2F2F2;
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void loadHistory(long convId) {
        history.clear();
        list.removeAllViews();
        List<String[]> msgs = store.loadMessages(convId);
        for (String[] m : msgs) {
            history.add(new String[]{m[0], m[1]});
            addBubble(m[0], m[1]);
        }
    }

    private TextView addBubble(String role, String text) {
        TextView tv = new TextView(this);
        if ("assistant".equals(role)) {
            tv.setText(Md.parse(text));
        } else {
            tv.setText(text);
        }
        tv.setTextSize(15);
        tv.setTextColor(textColor);
        tv.setPadding(dp(12), dp(8), dp(12), dp(8));
        tv.setBackgroundColor("user".equals(role) ? bubbleUser : bubbleAi);
        LinearLayout.LayoutParams p =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(6);
        list.addView(tv, p);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        return tv;
    }

    private void removeBubble(TextView tv) {
        if (tv == null) return;
        list.removeView(tv);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void doSend() {
        final String text = input.getText().toString().trim();
        if (text.isEmpty() && pendingImages.isEmpty() && pendingFileText.isEmpty()) return;
        if (busy) return;
        input.setText("");
        status.setText("");
        busy = true;
        cancelled = false;
        sendBtn.setText("停止");

        final String fileName = pendingFileName;
        final String fileText = pendingFileText;
        final List<String> imgs = new ArrayList<>(pendingImages);

        StringBuilder shown = new StringBuilder();
        if (!text.isEmpty()) shown.append(text);
        if (!fileText.isEmpty()) {
            if (shown.length() > 0) shown.append("\n");
            shown.append("[文件] ").append(fileName).append("\n").append(fileText);
        }
        if (!imgs.isEmpty()) {
            if (shown.length() > 0) shown.append("\n");
            shown.append("[图片 x").append(imgs.size()).append("]");
        }
        final String fullText = shown.toString();

        addBubble("user", fullText);
        store.saveMessage(currentConvId, "user", fullText);
        pendingImages.clear();
        pendingFileName = "";
        pendingFileText = "";

        if (store.countMessages(currentConvId) == 1) {
            String t = text.isEmpty() ? "附件对话" : (text.length() > 20 ? text.substring(0, 20) : text);
            store.updateTitle(currentConvId, t);
        }

        final TextView reply = addBubble("assistant", "");
        workThread = new Thread(() -> {
            StringBuilder acc = new StringBuilder();
            try {
                stream(acc, reply, fullText, imgs);
                if (cancelled) {
                    ui.post(() -> {
                        if (acc.length() == 0) {
                            removeBubble(reply);
                        } else {
                            reply.setText(Md.parse(acc.toString() + "\n（已停止）"));
                        }
                    });
                } else {
                    history.add(new String[]{"assistant", acc.toString()});
                    store.saveMessage(currentConvId, "assistant", acc.toString());
                }
            } catch (Exception e) {
                if (cancelled) return;
                final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                ui.post(() -> {
                    if (acc.length() == 0) {
                        removeBubble(reply);
                    } else {
                        reply.setText(Md.parse(acc.toString() + "\n（生成中断）"));
                    }
                    status.setText("出错：" + msg);
                });
            } finally {
                ui.post(() -> {
                    busy = false;
                    sendBtn.setText("发送");
                });
            }
        });
        workThread.start();
    }

    private void doStop() {
        cancelled = true;
        if (currentConn != null) {
            try {
                currentConn.disconnect();
            } catch (Exception ignored) {
            }
        }
        if (workThread != null) {
            workThread.interrupt();
        }
    }

    private String[] resolveConfig() {
        String convModel = store.getConversationModel(currentConvId);
        if (convModel != null && !convModel.isEmpty()) {
            String[] p = settings.getPreset(convModel);
            if (p != null) {
                return new String[]{p[1], p[2], p[3], p[4], p[5], p[6], p[7]};
            }
        }
        return new String[]{
                settings.globalType(),
                settings.globalHost(),
                settings.globalKey(),
                settings.globalModel(),
                "4096",
                "",
                ""
        };
    }

    private void stream(StringBuilder acc, TextView reply, String userText, List<String> imgs) throws Exception {
        String[] conf = resolveConfig();
        String type = conf[0].toUpperCase();
        String host = conf[1].trim();
        String key = conf[2];
        String model = conf[3];
        String maxTokens = conf[4];
        String temperature = conf[5];
        String skill = conf[6];
        while (host.endsWith("/")) host = host.substring(0, host.length() - 1);

        JSONArray msgs = new JSONArray();
        if (skill != null && !skill.trim().isEmpty()) {
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", "请遵循以下 Skill：\n" + skill);
            msgs.put(sys);
        }
        for (String[] m : history) {
            if (m[1] == null || m[1].trim().isEmpty()) continue;
            JSONObject o = new JSONObject();
            o.put("role", "user".equals(m[0]) ? "user" : "assistant");
            o.put("content", m[1]);
            msgs.put(o);
        }

        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        if (type.equals("OPENAI") && !imgs.isEmpty()) {
            JSONArray parts = new JSONArray();
            if (!userText.isEmpty()) {
                JSONObject t = new JSONObject();
                t.put("type", "text");
                t.put("text", userText);
                parts.put(t);
            }
            for (String b64 : imgs) {
                JSONObject img = new JSONObject();
                img.put("type", "image_url");
                JSONObject urlObj = new JSONObject();
                urlObj.put("url", "data:image/jpeg;base64," + b64);
                img.put("image_url", urlObj);
                parts.put(img);
            }
            userMsg.put("content", parts);
        } else {
            userMsg.put("content", userText);
        }
        msgs.put(userMsg);

        int budget = ContextManager.parseInt(maxTokens, 4096) * 3;
        msgs = ContextManager.trimToBudget(msgs, budget);

        JSONObject body = new JSONObject();
        String url;
        HttpURLConnection c;
        if ("ANTHROPIC".equals(type)) {
            url = host + "/v1/messages";
            body.put("model", model).put("max_tokens", ContextManager.parseInt(maxTokens, 4096))
                    .put("stream", true).put("messages", msgs);
            if (!temperature.isEmpty()) body.put("temperature", Double.parseDouble(temperature));
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestProperty("x-api-key", key);
            c.setRequestProperty("anthropic-version", "2023-06-01");
        } else if ("GEMINI".equals(type)) {
            url = host + "/v1beta/models/" + URLEncoder.encode(model, "UTF-8")
                    + ":streamGenerateContent?alt=sse";
            body.put("contents", msgs);
            if (!temperature.isEmpty()) {
                JSONObject gen = new JSONObject();
                gen.put("temperature", Double.parseDouble(temperature));
                body.put("generationConfig", gen);
            }
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestProperty("x-goog-api-key", key);
        } else {
            url = host + "/v1/chat/completions";
            body.put("model", model).put("stream", true).put("messages", msgs);
            body.put("max_tokens", ContextManager.parseInt(maxTokens, 4096));
            if (!temperature.isEmpty()) body.put("temperature", Double.parseDouble(temperature));
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestProperty("Authorization", "Bearer " + key);
        }

        currentConn = c;
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
            if (cancelled) break;
            if (!line.startsWith("data:")) continue;
            String data = line.substring(5).trim();
            if ("[DONE]".equals(data)) break;
            String delta = extractDelta(type, data);
            if (delta != null && !delta.isEmpty()) {
                acc.append(delta);
                final String shownNow = acc.toString();
                ui.post(() -> reply.setText(Md.parse(shownNow)));
            }
        }
        br.close();
        c.disconnect();
        currentConn = null;
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

    private void showSessions() {
        List<String[]> convs = store.listConversations();
        String[] items = new String[convs.size()];
        for (int i = 0; i < convs.size(); i++) {
            items[i] = convs.get(i)[1];
        }
        new AlertDialog.Builder(this)
                .setTitle("会话（勾选可批量删除）")
                .setMultiChoiceItems(items, null, (d, which, checked) -> {
                })
                .setPositiveButton("删除选中", (d, w) -> {
                    AlertDialog ad = (AlertDialog) d;
                    ListView lv = ad.getListView();
                    for (int i = 0; i < convs.size(); i++) {
                        if (lv.isItemChecked(i)) {
                            store.deleteConversation(Long.parseLong(convs.get(i)[0]));
                        }
                    }
                    currentConvId = store.getLatestConversationId();
                    if (currentConvId == -1) currentConvId = store.newConversation();
                    loadHistory(currentConvId);
                })
                .setNeutralButton("新建", (d, w) -> {
                    currentConvId = store.newConversation();
                    loadHistory(currentConvId);
                })
                .setNegativeButton("单个操作", (d, w) -> showSessionOps())
                .show();
    }

    private void showSessionOps() {
        new AlertDialog.Builder(this)
                .setTitle("会话操作")
                .setItems(new String[]{"重命名", "设为模型", "移入项目组", "移出项目组", "删除"}, (d, which) -> {
                    if (which == 0) renameConversation();
                    else if (which == 1) setConversationModel();
                    else if (which == 2) moveToGroup();
                    else if (which == 3) store.moveOutGroup(currentConvId);
                    else if (which == 4) deleteConversation();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void renameConversation() {
        final EditText et = new EditText(this);
        et.setText(store.getConversationTitle(currentConvId));
        new AlertDialog.Builder(this)
                .setTitle("重命名")
                .setView(et)
                .setPositiveButton("确定", (d, w) -> {
                    String t = et.getText().toString().trim();
                    if (!t.isEmpty()) store.updateTitle(currentConvId, t);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void setConversationModel() {
        List<String[]> presets = settings.listPresets();
        if (presets.isEmpty()) {
            status.setText("请先在设置里添加模型预设");
            return;
        }
        String[] names = new String[presets.size() + 1];
        names[0] = "跟随全局";
        for (int i = 0; i < presets.size(); i++) names[i + 1] = presets.get(i)[0];
        new AlertDialog.Builder(this)
                .setTitle("选择模型")
                .setItems(names, (d, which) -> {
                    if (which == 0) store.setConversationModel(currentConvId, "");
                    else store.setConversationModel(currentConvId, presets.get(which - 1)[0]);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteConversation() {
        store.deleteConversation(currentConvId);
        currentConvId = store.getLatestConversationId();
        if (currentConvId == -1) currentConvId = store.newConversation();
        loadHistory(currentConvId);
    }

    private void showGroups() {
        List<String[]> groups = store.listGroups();
        String[] items = new String[groups.size()];
        for (int i = 0; i < groups.size(); i++) items[i] = groups.get(i)[1];
        new AlertDialog.Builder(this)
                .setTitle("项目组")
                .setItems(items, (d, which) -> {
                    String[] g = groups.get(which);
                    showGroupDetail(Long.parseLong(g[0]), g[1]);
                })
                .setNeutralButton("新建组", (d, w) -> createGroup())
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showGroupDetail(long groupId, String name) {
        List<String[]> convs = store.listConversationsInGroup(groupId);
        String[] items = new String[convs.size()];
        for (int i = 0; i < convs.size(); i++) items[i] = convs.get(i)[1];
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setItems(items, (d, which) -> {
                    currentConvId = Long.parseLong(convs.get(which)[0]);
                    loadHistory(currentConvId);
                })
                .setPositiveButton("删除组", (d, w) -> store.deleteGroup(groupId))
                .setNegativeButton("关闭", null)
                .show();
    }

    private void createGroup() {
        final EditText et = new EditText(this);
        et.setHint("项目组名称");
        new AlertDialog.Builder(this)
                .setTitle("新建项目组")
                .setView(et)
                .setPositiveButton("确定", (d, w) -> {
                    String n = et.getText().toString().trim();
                    if (!n.isEmpty()) store.newGroup(n);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void moveToGroup() {
        List<String[]> groups = store.listGroups();
        if (groups.isEmpty()) {
            status.setText("请先创建项目组");
            return;
        }
        String[] names = new String[groups.size()];
        for (int i = 0; i < groups.size(); i++) names[i] = groups.get(i)[1];
        new AlertDialog.Builder(this)
                .setTitle("移入项目组")
                .setItems(names, (d, which) -> {
                    store.moveToGroup(currentConvId, Long.parseLong(groups.get(which)[0]));
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));

        final EditText etType = new EditText(this);
        etType.setHint("OPENAI / ANTHROPIC / GEMINI");
        etType.setText(settings.globalType());
        box.addView(etType);

        final EditText etHost = new EditText(this);
        etHost.setHint("API 地址");
        etHost.setText(settings.globalHost());
        box.addView(etHost);

        final EditText etKey = new EditText(this);
        etKey.setHint("API Key");
        etKey.setText(settings.globalKey());
        box.addView(etKey);

        final EditText etModel = new EditText(this);
        etModel.setHint("模型 ID");
        etModel.setText(settings.globalModel());
        box.addView(etModel);

        new AlertDialog.Builder(this)
                .setTitle("全局设置")
                .setView(box)
                .setPositiveButton("保存全局", (d, w) -> settings.saveGlobal(
                        etType.getText().toString().trim().toUpperCase(),
                        etHost.getText().toString().trim(),
                        etKey.getText().toString().trim(),
                        etModel.getText().toString().trim()))
                .setNeutralButton("管理预设", (d, w) -> showPresets(
                        etType.getText().toString().trim().toUpperCase(),
                        etHost.getText().toString().trim(),
                        etKey.getText().toString().trim(),
                        etModel.getText().toString().trim()))
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showPresets(String type, String host, String key, String model) {
        List<String[]> presets = settings.listPresets();
        String[] items = new String[presets.size()];
        for (int i = 0; i < presets.size(); i++) items[i] = presets.get(i)[0];
        new AlertDialog.Builder(this)
                .setTitle("模型预设（点击删除）")
                .setItems(items, (d, which) -> settings.deletePreset(presets.get(which)[0]))
                .setPositiveButton("新增预设", (d, w) -> addPreset(type, host, key, model))
                .setNegativeButton("关闭", null)
                .show();
    }

    private void addPreset(String type, String host, String key, String model) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));

        final EditText etName = new EditText(this);
        etName.setHint("预设名称，如 Claude");
        box.addView(etName);

        final EditText etType = new EditText(this);
        etType.setHint("协议");
        etType.setText(type);
        box.addView(etType);

        final EditText etHost = new EditText(this);
        etHost.setHint("API 地址");
        etHost.setText(host);
        box.addView(etHost);

        final EditText etKey = new EditText(this);
        etKey.setHint("API Key");
        etKey.setText(key);
        box.addView(etKey);

        final EditText etModel = new EditText(this);
        etModel.setHint("模型 ID");
        etModel.setText(model);
        box.addView(etModel);

        final EditText etMax = new EditText(this);
        etMax.setHint("maxTokens，如 4096");
        etMax.setText("4096");
        box.addView(etMax);

        final EditText etTemp = new EditText(this);
        etTemp.setHint("temperature，可留空");
        box.addView(etTemp);

        final EditText etSkill = new EditText(this);
        etSkill.setHint("Skill 内容，可多行");
        box.addView(etSkill);

        final EditText etNote = new EditText(this);
        etNote.setHint("备注");
        box.addView(etNote);

        new AlertDialog.Builder(this)
                .setTitle("新增模型预设")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    String n = etName.getText().toString().trim();
                    if (n.isEmpty()) return;
                    settings.savePreset(n,
                            etType.getText().toString().trim().toUpperCase(),
                            etHost.getText().toString().trim(),
                            etKey.getText().toString().trim(),
                            etModel.getText().toString().trim(),
                            etMax.getText().toString().trim(),
                            etTemp.getText().toString().trim(),
                            etSkill.getText().toString().trim(),
                            etNote.getText().toString().trim());
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
