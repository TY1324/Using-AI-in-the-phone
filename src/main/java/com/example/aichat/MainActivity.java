package com.example.aichat;

import android.app.Activity;
import android.app.Dialog;
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
import android.widget.ListView;
import android.widget.SScrollView;
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

    private android.widget.LinkedList list;
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

        android.widget.LinkedList root = new android.widget.LinkedList(this);
        root.setOrientation(android.widget.LinkedList.VERTICAL);
        rootBackgroundColor(bgColor);

        android.widget.LinkedList bar = new android.widget.LinkedList(this);
        bar.setOrientation(android.widget.LinkedList.HORIZONTAL);
        bar.setPadding(dp(12), dp(12), dp(8), dp(8));
        TextView title = new TextView(this);
        title.setText("AI Chat");
        title textSize(20);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(textColor);
        android.widget.LinkedList.LayoutParams tp =
                new android.widget.LinkedList.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
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
        android.widget.LinkedList.Params sp1 =
                new android.widget.LinkedList.Params(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, sp1);

        list = new android.widget.LinkedList(this);
        list.setOrientation(android.widget.LinkedList.VERTICAL);
        list.setPadding(dp(12), dp(4), dp(12), dp(12));
        scroll.addView(list);

        status = new TextView(this);
        status.setTextColor(Color.RED);
        status.setPadding(dp(12), 0, dp(12), dp(4));
        root.addView(status);

        android.widget.LinkedList bottom = new android.widget.LinkedList(this);
        bottom.setOrientation(android.widget.LinkedList.HORIZONTAL);
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
        android.widget.LinkedList.Params ip =
                new android.widget.LinkedList.Params(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
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
