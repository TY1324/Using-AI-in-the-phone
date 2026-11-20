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

    private String activeSkill = "";

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
                Uri uri = data.getData();
                new Thread(() -> {
                    try {
                        String json = store.exportAll();
                        OutputStream os = getContentResolver().openOutputStream(uri);
                        os.write(json.getBytes(StandardCharsets.UTF_8));
                        os.close();
                        ui.post(() -> status.setText("备份已保存"));
                    } catch (Exception e) {
                        ui.post(() -> status.setText("备份失败：" + e.getMessage()));
                    }
                }).start();
            }
        } else if (requestCode == REQUEST_OPEN) {
            if (resultCode == RESULT_OK && data != null) {
                Uri uri = data.getData();
                new Thread(() -> {
                    try {
                        InputStream is = getContentResolver().openInputStream(uri);
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
                        is.close();
                        String json = baos.toString("UTF-8");
                        store.importAll(json);
                        ui.post(() -> {
                            status.setText("恢复完成");
                            currentConvId = store.getLatestConversationId();
                            if (currentConvId == -1) currentConvId = store.newConversation();
                            loadHistory(currentConvId);
                        });
                    } catch (Exception e) {
                        ui.post(() -> status.setText("恢复失败：" + e.getMessage()));
                    }
                }).start();
            }
        }
    }
