package com.example.aichat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
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

    // ===== 鐘舵€� =====
    private LinearLayout root;
    private LinearLayout contentArea;
    private LinearLayout tabBar;
    private ScrollView scroll;
    private LinearLayout list;
    private EditText input;
    private TextView status;

    private Button sendBtn;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // ===== 鏁版嵁 =====
    private final List<String[]> history = new ArrayList<>();
    private final List<String> pendingImages = new ArrayList<>();
    private String pendingFileName = "";
    private String pendingFileText = "";
    private Settings settings;
    private Store store;
    private long currentConvId = -1;
    private boolean busy = false;

    private volatile boolean cancelled = false;
    private HttpURLConnection currentConn = null;
    private Thread workThread = null;

    private String activeSkill = "";
    private VisionModel visionModel = new VisionModel();
    private boolean visionLoaded = false;
    private ToolRegistry toolRegistry;
    private final List<String> subAgentNames = new ArrayList<>();
    private final Map<String, String> subAgentResults = new HashMap<>();

    // ===== 涓婚 =====
    private UiTheme.Palette theme = UiTheme.light();
    private int bgColor, textColor, bubbleUser, bubbleAi;

    private static final int PICK_IMAGE = 1001;
    private static final int REQUEST_SAVE = 1002;
    private static final int REQUEST_OPEN = 1003;
    private static final int PICK_FILE_REQ = 2001;

    // ===== 鏍囩椤� =====
    private static final String TAB_CHAT = "chat";
    private static final String TAB_TOOLS = "tools";
    private static final String TAB_ME = "me";
    private String currentTab = TAB_CHAT;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            settings = new Settings(this);
            store = new Store(this);
            toolRegistry = new ToolRegistry(this);
            toolRegistry.registerBuiltins();
            Skill.ensureDefaults(this);
            if (settings.pricesJson().isEmpty()) {
                settings.savePrices(TokenCounter.defaultPricesJson());
            }

            applyTheme();
            buildUi();

            if (settings.keepAlive()) {
                startForeService();
            }
            loadLatestConversation();
        } catch (Throwable e) {
            showCrash(e);
        }
    }

    private void showCrash(Throwable e) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("鍚姩閿欒锛歕n\n");
            sb.append(e.toString()).append("\n\n");
            StackTraceElement[] st = e.getStackTrace();
            int n = Math.min(12, st.length);
            for (int i = 0; i < n; i++) sb.append(st[i].toString()).append('\n');
            Throwable cause = e.getCause();
            if (cause != null) {
                sb.append("\n鍘熷洜锛�").append(cause.toString()).append('\n');
                StackTraceElement[] cs = cause.getStackTrace();
                int cn = Math.min(10, cs.length);
                for (int i = 0; i < cn; i++) sb.append(cs[i].toString()).append('\n');
            }

            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(20), dp(20), dp(20), dp(20));
            TextView tv = new TextView(this);
            tv.setText(sb.toString());
            tv.setTextSize(dp(11));
            tv.setTextColor(0xFFCC0000);
            box.addView(tv);
            ScrollView sv = new ScrollView(this);
            sv.addView(box);
            setContentView(sv);
        } catch (Exception ignored) {
        }
    }

    private void showCrash(String msg) {
        try {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(20), dp(20), dp(20), dp(20));
            TextView tv = new TextView(this);
            tv.setText(msg);
            tv.setTextSize(dp(12));
            tv.setTextColor(0xFF000000);
            box.addView(tv);
            ScrollView sv = new ScrollView(this);
            sv.addView(box);
            setContentView(sv);
        } catch (Exception ignored) {
        }
    }

    // ========================================================
    //   UI 鏋勫缓
    // ========================================================

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.bg);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        buildTopBar();
        contentArea = new LinearLayout(this);
        contentArea.setOrientation(LinearLayout.VERTICAL);
        contentArea.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(contentArea);
        buildTabBar();

        status = new TextView(this);
        status.setTextColor(theme.danger);
        status.setTextSize(dp(12));
        status.setPadding(dp(16), dp(4), dp(16), dp(4));
        status.setGravity(Gravity.CENTER);
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);

        setContentView(root);
        switchTab(TAB_CHAT);
    }

    /** 椤堕儴锛氭爣棰� + 瀵硅瘽淇℃伅 */
    private void buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(theme.surface);
        bar.setPadding(dp(16), dp(14), dp(16), dp(14));
        bar.setElevation(dp(2));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(left, lp);

        TextView title = new TextView(this);
        title.setText("AI Chat");
        title.setTextSize(dp(19));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(theme.text);
        left.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setTextSize(dp(12));
        subtitle.setTextColor(theme.textSecondary);
        left.addView(subtitle);

        Button menu = iconButton("鈽�");
        menu.setOnClickListener(v -> showSettings());
        bar.addView(menu);

        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ((TextView) subtitle).setTag("subtitle");
        ((TextView) subtitle).setId(View.generateViewId());
        // 淇濆瓨寮曠敤渚涘悗缁洿鏂�
        subtitleRef = subtitle;
    }

    private TextView subtitleRef;

    /** 搴曢儴锛氭爣绛惧鑸紙涓変釜 Tab锛� */
    private void buildTabBar() {
        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setBackgroundColor(theme.surface);
        tabBar.setElevation(dp(8));

        addTab(TAB_CHAT, "瀵硅瘽", () -> switchTab(TAB_CHAT));
        addTab(TAB_TOOLS, "宸ュ叿", () -> switchTab(TAB_TOOLS));
        addTab(TAB_ME, "鎴戠殑", () -> switchTab(TAB_ME));

        root.addView(tabBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
    }

    private final Map<String, LinearLayout> tabContainers = new HashMap<>();
    private final Map<String, TextView> tabLabels = new HashMap<>();

    private void addTab(String key, String label, final Runnable action) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        item.setClickable(true);
        item.setOnClickListener(v -> action.run());

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(dp(13));
        tv.setTextColor(theme.textSecondary);
        tv.setGravity(Gravity.CENTER);
        item.addView(tv);

        tabBar.addView(item);
        tabLabels.put(key, tv);
    }

    private void switchTab(String key) {
        currentTab = key;
        for (String k : tabLabels.keySet()) {
            boolean sel = k.equals(key
