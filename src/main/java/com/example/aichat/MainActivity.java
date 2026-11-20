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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    private String activeSkill = "";
    private final List<String> subAgentNames = new ArrayList<>();
    private final Map<String, String> subAgentResults = new HashMap<>();

    private static final int PICK_IMAGE = 1001;
    private static final int REQUEST_SAVE = 1002;
    private static final int REQUEST_OPEN = 1003;

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
        Button skillBtn = new Button(this);
        skillBtn.setText("Skill");
        skillBtn.setOnClickListener(v -> showSkills());
        bar.addView(skillBtn);
        Button subAgentBtn = new Button(this);
        subAgentBtn.setText("子代理");
        subAgentBtn.setOnClickListener(v -> showSubAgents());
        bar.addView(subAgentBtn);
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

        Button backupBtn = new Button(this);
        backupBtn.setText("备份");
        backupBtn.setOnClickListener(v -> doBackup());
        bottom.addView(backupBtn);

        Button restoreBtn = new Button(this);
        restoreBtn.setText("恢复");
        restoreBtn.setOnClickListener(v -> doRestore());
        bottom.addView(restoreBtn);

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

        // ===== 生成区（独立于文本区） =====
        LinearLayout genBar = new LinearLayout(this);
        genBar.setOrientation(LinearLayout.HORIZONTAL);
        genBar.setPadding(dp(12), dp(4), dp(12), dp(8));
        genBar.setBackgroundColor(bgColor);

        final EditText genPrompt = new EditText(this);
        genPrompt.setHint("生成提示词…");
        genPrompt.setTextColor(textColor);
        genPrompt.setHintTextColor(0xFF9E9E9E);
        LinearLayout.LayoutParams gip =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        genBar.addView(genPrompt, gip);

        Button genImgBtn = new Button(this);
        genImgBtn.setText("生成图");
        genImgBtn.setOnClickListener(v -> {
            String p = genPrompt.getText().toString().trim();
            if (p.isEmpty()) { status.setText("请输入提示词"); return; }
            status.setText("图片生成中…");
            new Thread(() -> {
                try {
                    String[] conf = resolveConfig();
                    String result = ImageVideoGenerator.generateImage(conf[1], conf[2], "dall-e-3", p, "1024x1024");
                    ui.post(() -> {
                        pendingImages.add(result);
                        status.setText("图片已生成，可发送");
                        addBubble("assistant", "[已生成图片]");
                    });
                } catch (Exception e) {
                    ui.post(() -> status.setText("生成失败：" + e.getMessage()));
                }
            }).start();
        });
        genBar.addView(genImgBtn);

        Button genVideoBtn = new Button(this);
        genVideoBtn.setText("生成视频");
        genVideoBtn.setOnClickListener(v -> {
            String p = genPrompt.getText().toString().trim();
            if (p.isEmpty()) { status.setText("请输入提示词"); return; }
            status.setText("视频生成中…");
            new Thread(() -> {
                try {
                    String[] conf = resolveConfig();
                    String[] modes = {"Sora 生成", "Claude 代码生成"};
                    // 默认用 Sora，可在此改为弹窗选择
                    String result = ImageVideoGenerator.generateVideoSora(conf[1], conf[2], "sora", p, "5");
                    ui.post(() -> {
                        status.setText("视频已生成");
                        addBubble("assistant", "[已生成视频]\n" + result);
                    });
                } catch (Exception e) {
                    ui.post(() -> status.setText("生成失败：" + e.getMessage()));
                }
            }).start();
        });
        genBar.addView(genVideoBtn);

        root.addView(genBar);

        setContentView(root);

        currentConvId = store.getLatestConversationId();
        if (currentConvId == -1) {
            currentConvId = store.newConversation();
        }
        loadHistory(currentConvId);
        startForeService();
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
        } else if (requestCode == REQUEST_SAVE) {
            if (resultCode == RESULT_OK && data != null) {
                Uri u    private void stream(StringBuilder acc, TextView reply, String userText, List<String> imgs) throws Exception {
        String[] conf = resolveConfig();
        String type = conf[0].toUpperCase();
        String host = conf[1].trim();
        String key = conf[2];
        String model = conf[3];
        String maxTokens = conf[4];
        String temperature = conf[5];
        String skill = conf[6];
        boolean thinking = conf[7].equals("true");
        while (host.endsWith("/")) host = host.substring(0, host.length() - 1);

        JSONArray msgs = new JSONArray();
        if (skill != null && !skill.trim().isEmpty()) {
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", "请遵循以下 Skill：\n" + skill);
            msgs.put(sys);
        }
        if (activeSkill != null && !activeSkill.trim().isEmpty()) {
            JSONObject sys2 = new JSONObject();
            sys2.put("role", "system");
            sys2.put("content", "当前会话激活的 Skill：\n" + activeSkill);
            msgs.put(sys2);
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
            if (thinking) {
                JSONObject think = new JSONObject();
                think.put("type", "enabled");
                think.put("budget_tokens", 2000);
                body.put("thinking", think);
            }
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
        long start = System.currentTimeMillis();
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
            if (System.currentTimeMillis() - start > 30000) {
                ui.post(() -> status.setText("连接超时，请检查网络"));
                throw new Exception("Timeout");
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
        et.setText(s    private void showSkills() {
        List<Skill> skills = Skill.loadAll(this);
        if (skills.isEmpty()) {
            showAddSkill();
            return;
        }
        String[] items = new String[skills.size() + 1];
        items[0] = "＋ 新建 Skill";
        for (int i = 0; i < skills.size(); i++) {
            items[i + 1] = skills.get(i).name + (skills.get(i).name.equals(activeSkill) ? " ✓" : "");
        }
        new AlertDialog.Builder(this)
                .setTitle("Skill 管理（点击启用/切换）")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        showAddSkill();
                    } else {
                        Skill s = skills.get(which - 1);
                        activeSkill = s.name;
                        status.setText("已启用：" + s.name);
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showAddSkill() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));

        final EditText etName = new EditText(this);
        etName.setHint("Skill 名称");
        box.addView(etName);

        final EditText etDesc = new EditText(this);
        etDesc.setHint("描述");
        box.addView(etDesc);

        final EditText etContent = new EditText(this);
        etContent.setHint("Skill 内容（作为 system 提示词注入）");
        etContent.setLines(6);
        box.addView(etContent);

        new AlertDialog.Builder(this)
                .setTitle("新建 Skill")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    String n = etName.getText().toString().trim();
                    String desc = etDesc.getText().toString().trim();
                    String content = etContent.getText().toString().trim();
                    if (n.isEmpty() || content.isEmpty()) return;
                    Skill.save(this, new Skill(n, content, desc));
                    status.setText("Skill 已保存");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showSubAgents() {
        if (subAgentNames.isEmpty()) {
            status.setText("请先添加子代理");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("子代理管理")
                .setItems(subAgentNames.toArray(new String[0]), (d, which) -> {
                    String name = subAgentNames.get(which);
                    runSubAgent(name);
                })
                .setNegativeButton("添加子代理", (d, w) -> addSubAgent())
                .show();
    }

    private void addSubAgent() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));

        final EditText etName = new EditText(this);
        etName.setHint("子代理名称");
        box.addView(etName);

        final EditText etSystem = new EditText(this);
        etSystem.setHint("系统指令");
        etSystem.setLines(4);
        box.addView(etSystem);

        final EditText etUser = new EditText(this);
        etUser.setHint("用户指令");
        etUser.setLines(2);
        box.addView(etUser);

        new AlertDialog.Builder(this)
                .setTitle("添加子代理")
                .setView(box)
                .setPositiveButton("添加", (d, w) -> {
                    String n = etName.getText().toString().trim();
                    if (!n.isEmpty()) subAgentNames.add(n);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void runSubAgent(String name) {
        status.setText("子代理运行中…");
        new Thread(() -> {
            try {
                String[] conf = resolveConfig();
                String system = "请严格按照你的角色和指令完成任务。";
                String user = "请处理以下任务：\n" + name;
                String result = SubAgent.run(conf[0], conf[1], conf[2], conf[3], system, user);
                subAgentResults.put(name, result);
                ui.post(() -> {
                    addBubble("user", "[子代理 " + name + " 完成]\n" + result);
                    store.saveMessage(currentConvId, "user", "[子代理 " + name + " 完成]\n" + result);
                    status.setText("子代理完成");
                });
            } catch (Exception e) {
                ui.post(() -> status.setText("子代理失败：" + e.getMessage()));
            }
        }).start();
    }

    private void doBackup() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "aichat_backup.json");
        startActivityForResult(intent, REQUEST_SAVE);
    }

    private void doRestore() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        startActivityForResult(intent, REQUEST_OPEN);
    }

    private void startForeService() {
        Intent i = new Intent(this, ForeService.class);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }
}
