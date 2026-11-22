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
    private UiTheme.Palette theme;
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
            boolean sel = k.equals(key);
            tabLabels.get(k).setTextColor(sel ? theme.accent : theme.textSecondary);
            tabLabels.get(k).setTypeface(Typeface.DEFAULT, sel ? Typeface.BOLD : Typeface.NORMAL);
        }
        if (TAB_CHAT.equals(key)) {
            showChatPage();
        } else if (TAB_TOOLS.equals(key)) {
            showToolsPage();
        } else {
            showMePage();
        }
    }

    private void clearContent() {
        contentArea.removeAllViews();
    }

    /** 鍗＄墖瀹瑰櫒 */
    private LinearLayout cardContainer() {
        ScrollView sv = new ScrollView(this);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(12), dp(12), dp(16));
        sv.addView(box);
        contentArea.addView(sv);
        return box;
    }

    /** 鐢熸垚涓€寮犲崱鐗� */
    private LinearLayout card(String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(UiTheme.roundRect(theme.surface, dp(12)));
        c.setPadding(dp(14), dp(14), dp(14), dp(14));
        c.setElevation(dp(1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        c.setLayoutParams(lp);

        if (title != null && !title.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText(title);
            tv.setTextSize(dp(14));
            tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            tv.setTextColor(theme.text);
            tv.setPadding(0, 0, 0, dp(8));
            c.addView(tv);
        }
        return c;
    }

    /** 鍦嗚鎸夐挳 */
    private Button roundButton(String label, int bg, int fg) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(dp(13));
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setBackground(UiTheme.roundRect(bg, dp(10)));
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        return b;
    }

    /** 鍥炬爣鎸夐挳锛堝渾褰級 */
    private Button iconButton(String icon) {
        Button b = new Button(this);
        b.setText(icon);
        b.setTextSize(dp(18));
        b.setTextColor(theme.accent);
        b.setBackground(UiTheme.roundRect(theme.accentSoft, dp(20)));
        b.setPadding(dp(8), dp(4), dp(8), dp(4));
        b.setMinWidth(dp(36));
        b.setMinHeight(dp(36));
        return b;
    }

    /** 琛屽唴灏忔爣绛炬寜閽� */
    private Button pillButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(dp(12));
        b.setAllCaps(false);
        b.setTextColor(theme.accent);
        b.setBackground(UiTheme.stroke(theme.accentSoft, theme.accent, dp(16), dp(1)));
        b.setPadding(dp(14), dp(6), dp(14), dp(6));
        return b;
    }

    // ========================================================
    //   Tab 1锛氬璇濋〉
    // ========================================================

    private void showChatPage() {
        clearContent();

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        wrap.setBackgroundColor(theme.bg);

        scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(12), dp(12), dp(8));
        scroll.addView(list);
        wrap.addView(scroll);

        // 杈撳叆鏍�
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(theme.surface);
        bar.setPadding(dp(8), dp(8), dp(8), dp(8));
        bar.setElevation(dp(6));

        input = new EditText(this);
        input.setHint("杈撳叆娑堟伅鈥�");
        input.setTextSize(dp(15));
        input.setTextColor(theme.text);
        input.setHintTextColor(theme.textSecondary);
        input.setBackground(UiTheme.roundRect(theme.surfaceAlt, dp(20)));
        input.setPadding(dp(14), dp(10), dp(14), dp(10));
        input.setMaxLines(4);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(input, ip);

        Button plus = iconButton("+");
        plus.setOnClickListener(v -> showAttachSheet());
        bar.addView(plus, marginStart(dp(6)));

        sendBtn = roundButton("鍙戦€�", theme.accent, 0xFFFFFFFF);
        sendBtn.setOnClickListener(v -> {
            if (busy) doStop(); else doSend();
        });
        bar.addView(sendBtn, marginStart(dp(6)));

        wrap.addView(bar);
        contentArea.addView(wrap);

        reloadBubbles();
        updateSubtitle();
    }

    /** 闄勪欢闈㈡澘锛堝浘鐗� / 鏂囦欢锛� */
    private void showAttachSheet() {
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout row = new LinearLayout(box.getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);

        Button img = roundButton("鍥剧墖", theme.accentSoft, theme.accent);
        img.setOnClickListener(v -> {
            pickImage();
        });
        row.addView(img, marginEnd(dp(10)));

        Button file = roundButton("鏂囦欢", theme.accentSoft, theme.accent);
        file.setOnClickListener(v -> {
            FileHelper.pick(this);
        });
        row.addView(file);

        box.addView(row);

        new AlertDialog.Builder(this)
                .setTitle("娣诲姞闄勪欢")
                .setView(box)
                .setNegativeButton("鍙栨秷", null)
                .show();
    }

    private void reloadBubbles() {
        list.removeAllViews();
        history.clear();
        if (currentConvId <= 0) return;
        List<String[]> msgs = store.loadMessages(currentConvId);
        for (int i = 0; i < msgs.size(); i++) {
            String[] m = msgs.get(i);
            history.add(new String[]{m[0], m[1]});
            addBubble(m[0], m[1], i);
        }
    }

    private void loadLatestConversation() {
        currentConvId = store.getLatestConversationId();
        if (currentConvId <= 0) {
            currentConvId = store.newConversation();
        }
        if (currentTab.equals(TAB_CHAT)) {
            reloadBubbles();
            updateSubtitle();
        }
    }

    private void updateSubtitle() {
        if (subtitleRef == null) return;
        String title = store.getConversationTitle(currentConvId);
        if (title == null || title.isEmpty()) title = "鏂板璇�";
        String model = settings.globalModel();
        subtitleRef.setText(title + "  路  " + model);
    }

    // ========================================================
    //   Tab 2锛氬伐鍏烽〉
    // ========================================================

    private void showToolsPage() {
        clearContent();
        LinearLayout box = cardContainer();

        LinearLayout c1 = card("杈呭姪宸ュ叿");
        addRow(c1, "鑱旂綉鎼滅储", "瀹炴椂鑾峰彇浜掕仈缃戜俊鎭�", v -> doSend());
        addRow(c1, "瑙嗚鐞嗚В", "鍒嗘瀽鍥剧墖鍐呭", v -> analyzeImages());
        addRow(c1, "闀挎湡璁板繂", "璺ㄤ細璇濊浣忎綘鐨勫亸濂�", v -> showMemory());
        box.addView(c1);

        LinearLayout c2 = card("鎵╁睍");
        addRow(c2, "MCP 鏈嶅姟鍣�", "鎺ュ叆澶栭儴宸ュ叿", v -> showMcpConfig());
        addRow(c2, "鎶€鑳� Skill", "鍒囨崲 AI 瑙掕壊", v -> showSkills());
        addRow(c2, "瀛愪唬鐞�", "澶氳疆浠诲姟鎷嗚В", v -> showSubAgents());
        box.addView(c2);
    }

    /** 鍒楄〃琛岋細鏍囬 + 鎻忚堪 + 鐐瑰嚮 */
    private void addRow(LinearLayout card, String title, String desc, final View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setClickable(true);
        row.setOnClickListener(click);
        row.setPadding(dp(2), dp(10), dp(2), dp(10));
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(dp(15));
        t.setTextColor(theme.text);
        row.addView(t);

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextSize(dp(12));
        d.setTextColor(theme.textSecondary);
        d.setPadding(0, dp(2), 0, 0);
        row.addView(d);

        card.addView(row);
        addDivider(card);
    }

    private void addDivider(LinearLayout parent) {
        View v = new View(this);
        v.setBackgroundColor(theme.divider);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        v.setLayoutParams(lp);
        parent.addView(v);
    }

    // ========================================================
    //   Tab 3锛氭垜鐨勯〉
    // ========================================================

    private void showMePage() {
        clearContent();
        LinearLayout box = cardContainer();

        LinearLayout c1 = card("浼氳瘽");
        addRow(c1, "浼氳瘽鍒楄〃", "鏌ョ湅 / 閲嶅懡鍚� / 鍒犻櫎", v -> showSessions());
        addRow(c1, "浼氳瘽鍒嗙粍", "鎸変富棰樻暣鐞嗗璇�", v -> showGroups());
        addRow(c1, "瀵煎嚭瀵硅瘽", "淇濆瓨涓� Markdown", v -> exportConversation());
        box.addView(c1);

        LinearLayout c2 = card("鏁版嵁");
        addRow(c2, "API 璁板綍", "鍔犲瘑淇濆瓨澶氬瀵嗛挜", v -> showApiRecords());
        addRow(c2, "棰勮绠＄悊", "蹇€熷垏鎹㈡ā鍨�", v -> showPresets(settings.globalType(), settings.globalHost(), settings.globalKey(), settings.globalModel()));
        addRow(c2, "鐢ㄩ噺缁熻", "Token 涓庤垂鐢�", v -> showUsage());
        addRow(c2, "澶囦唤 / 鎭㈠", "瀵煎嚭瀵煎叆鍏ㄩ儴鏁版嵁", v -> showBackupSheet());
        box.addView(c2);

        LinearLayout c3 = card("鍏充簬");
        addRow(c3, "涓婚澶栬", "娴呰壊 / 娣辫壊", v -> toggleDarkMode());
        box.addView(c3);
    }

    private void showBackupSheet() {
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout row = new LinearLayout(box.getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);

        Button b1 = roundButton("澶囦唤", theme.accentSoft, theme.accent);
        b1.setOnClickListener(v -> { doBackup(); });
        row.addView(b1, marginEnd(dp(10)));

        Button b2 = roundButton("鎭㈠", theme.accentSoft, theme.accent);
        b2.setOnClickListener(v -> { doRestore(); });
        row.addView(b2);

        box.addView(row);
        new AlertDialog.Builder(this)
                .setTitle("澶囦唤 / 鎭㈠")
                .setView(box)
                .setNegativeButton("鍏抽棴", null)
                .show();
    }

    private void toggleDarkMode() {
        settings.saveDarkMode(!settings.darkMode());
        settings.saveThemeSettings(
                settings.roundedCorners(),
                settings.bubbleScale(),
                settings.themeFont());
        applyTheme();
        buildUi();
        status.setText(settings.darkMode() ? "宸插垏鎹㈡繁鑹�" : "宸插垏鎹㈡祬鑹�");
    }

    private LinearLayout.LayoutParams marginStart(int px) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(px);
        return lp;
    }

    private LinearLayout.LayoutParams marginEnd(int px) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(px);
        return lp;
    }

    private LinearLayout.LayoutParams marginAll(int px) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(px, px, px, px);
        return lp;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;

        if (requestCode == PICK_IMAGE) {
            handleImage(uri);
        } else if (requestCode == PICK_FILE_REQ) {
            FileHelper.handle(this, resultCode, data, new FileHelper.Callback() {
                @Override public void onText(String name, String text) {
                    pendingFileName = name;
                    pendingFileText = text;
                    status.setText("宸插姞杞芥枃浠讹細" + name);
                }
                @Override public void onError(String msg) {
                    status.setText(msg);
                }
            });
        }
    }

    private String getFileName(Uri uri) {
        if (uri == null) return "鏈煡鏂囦欢";
        String path = uri.getPath();
        if (path != null) {
            int last = path.lastIndexOf('/');
            if (last >= 0) return path.substring(last + 1);
        }
        String lastSeg = uri.getLastPathSegment();
        return lastSeg != null ? lastSeg : "鏈煡鏂囦欢";
    }

    private String readUriContent(Uri uri) throws Exception {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) return "";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) baos.write(buf, 0, n);
        in.close();
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
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
                status.setText("鍥剧墖瑙ｇ爜澶辫触");
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
            status.setText("宸叉坊鍔犲浘鐗� " + pendingImages.size() + " 寮�");
        } catch (Exception e) {
            status.setText("鍥剧墖璇诲彇澶辫触锛�" + e.getMessage());
        }
    }

    private void handleLargeFile(Uri uri, String name) {
        status.setText("姝ｅ湪瑙ｆ瀽澶ф枃浠垛€�");
        new Thread(() -> {
            try {
                android.content.res.AssetFileDescriptor afd = getContentResolver().openAssetFileDescriptor(uri, "r");
                long size = (afd != null) ? afd.getLength() : 0;
                if (afd != null) afd.close();

                LargeFileHandler.ParseResult res = LargeFileHandler.parse(this, uri, name, size);

                if (res.error != null && !res.error.isEmpty()) {
                    ui.post(() -> status.setText(res.error));
                    return;
                }

                if (res.chunks != null && !res.chunks.isEmpty()) {
                    String docId = name + "_" + System.currentTimeMillis();
                    DocumentChunker chunker = new DocumentChunker(this);
                    chunker.saveChunks(docId, res.chunks);

                    StringBuilder sb = new StringBuilder();
                    sb.append("[鏂囨。] ").append(name).append("\n");
                    sb.append("鏂囦欢澶у皬锛�").append(size / 1024).append(" KB\n");
                    sb.append("鍒嗗潡鏁伴噺锛�").append(res.totalChunks).append("\n\n");
                    sb.append("绔犺妭鎽樿锛歕n");
                    for (LargeFileHandler.Chunk c : res.chunks) {
                        sb.append("[").append(c.index).append("] ").append(c.summary).append("\n");
                    }
                    sb.append("\n锛堝畬鏁村唴瀹瑰凡绱㈠紩锛屽璇濇椂灏嗚嚜鍔ㄦ绱㈢浉鍏虫钀斤級");

                    ui.post(() -> {
                        pendingFileName = name;
                        pendingFileText = sb.toString();
                        status.setText("宸茶В鏋� " + res.totalChunks + " 鍧楋紝鍙彂閫�");
                    });
                } else if (res.fullText != null && !res.fullText.isEmpty()) {
                    ui.post(() -> {
                        pendingFileName = name;
                        pendingFileText = res.fullText;
                        status.setText("鏂囦欢宸插姞杞�");
                    });
                } else {
                    ui.post(() -> status.setText("鏂囦欢瑙ｆ瀽涓虹┖"));
                }
            } catch (Exception e) {
                ui.post(() -> status.setText("瑙ｆ瀽澶辫触锛�" + e.getMessage()));
            }
        }).start();
    }

    private void applyTheme() {
        int night = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        boolean dark = night == Configuration.UI_MODE_NIGHT_YES;
        if (dark) {
            theme = UiTheme.dark();
            bgColor = 0xFF121212;
            textColor = 0xFFE8E8E8;
            bubbleUser = 0xFF2C3E50;
            bubbleAi = 0xFF1E1E1E;
        } else {
            theme = UiTheme.light();
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
        for (int i = 0; i < msgs.size(); i++) {
            String[] m = msgs.get(i);
            history.add(new String[]{m[0], m[1]});
            addBubble(m[0], m[1], i);
        }
    }

    private TextView addBubble(String role, String text) {
        return addBubble(role, text, -1);
    }

    private TextView addBubble(String role, String text, final int historyIndex) {
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
        tv.setTag(historyIndex);
        tv.setOnLongClickListener(v -> {
            showMessageActions(tv, role, text, historyIndex);
            return true;
        });
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        return tv;
    }

    private void showMessageActions(TextView tv, String role, String text, final int historyIndex) {
        String[] items;
        if ("assistant".equals(role)) {
            items = new String[]{"澶嶅埗", "淇濆瓨涓烘枃浠�", "閲嶆柊鐢熸垚", "鍒犻櫎", "瀵煎嚭鏈細璇� Markdown"};
        } else {
            items = new String[]{"澶嶅埗", "淇濆瓨涓烘枃浠�", "鍒犻櫎"};
        }
        new AlertDialog.Builder(this)
                .setTitle("娑堟伅鎿嶄綔")
                .setItems(items, (d, which) -> {
                    String act = items[which];
                    if ("澶嶅埗".equals(act)) {
                        copyText(text);
                    } else if ("淇濆瓨涓烘枃浠�".equals(act)) {
                        saveBubbleToFile(tv);
                    } else if ("閲嶆柊鐢熸垚".equals(act)) {
                        regenerateFrom(historyIndex);
                    } else if ("鍒犻櫎".equals(act)) {
                        deleteMessage(tv, historyIndex);
                    } else if ("瀵煎嚭鏈細璇� Markdown".equals(act)) {
                        exportConversation();
                    }
                })
                .setNegativeButton("鍙栨秷", null)
                .show();
    }

    private void copyText(String text) {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("AI Chat", text));
            status.setText("宸插鍒�");
        } catch (Exception e) {
            status.setText("澶嶅埗澶辫触");
        }
    }

    private void deleteMessage(TextView tv, int historyIndex) {
        if (tv != null) {
            list.removeView(tv);
        }
        if (historyIndex >= 0 && historyIndex < history.size()) {
            history.remove(historyIndex);
        }
        status.setText("宸插垹闄わ紙閲嶅惎鍚庝粠鏁版嵁搴撴仮澶嶏級");
    }

    private void regenerateFrom(int historyIndex) {
        // 鎵惧埌璇� assistant 娑堟伅涔嬪墠鐨勬渶鍚庝竴涓� user 娑堟伅
        int target = -1;
        for (int i = historyIndex - 1; i >= 0; i--) {
            if ("user".equals(history.get(i)[0])) {
                target = i;
                break;
            }
        }
        if (target < 0) {
            status.setText("鎵句笉鍒板彲閲嶆柊鐢熸垚鐨勬彁闂�");
            return;
        }
        // 绉婚櫎璇� assistant 娑堟伅鍙婁箣鍚庣殑鍘嗗彶
        while (history.size() > target + 1) {
            history.remove(history.size() - 1);
        }
        list.removeAllViews();
        for (int i = 0; i < history.size(); i++) {
            addBubble(history.get(i)[0], history.get(i)[1], i);
        }
        input.setText(history.get(target)[1]);
        status.setText("宸插洖閫€锛岃閲嶆柊鍙戦€�");
    }

    private void exportConversation() {
        StringBuilder sb = new StringBuilder();
        List<String[]> msgs = store.loadMessages(currentConvId);
        String title = store.getConversationTitle(currentConvId);
        sb.append("# ").append(title == null ? "瀵硅瘽" : title).append("\n\n");
        for (String[] m : msgs) {
            if (m[0] == null || m[1] == null) continue;
            if ("user".equals(m[0])) {
                sb.append("**鎴�**\n\n").append(m[1]).append("\n\n");
            } else if ("assistant".equals(m[0])) {
                sb.append("**AI**\n\n").append(m[1]).append("\n\n");
            }
        }
        try {
            FileDelivery.SavedFile sf = FileDelivery.save(this, "瀵硅瘽瀵煎嚭", sb.toString(), FileDelivery.Kind.MD);
            status.setText("宸插鍑猴細" + sf.file.getName());
            showFileActions(sf, "瀵硅瘽宸插鍑�");
        } catch (Exception e) {
            status.setText("瀵煎嚭澶辫触锛�" + e.getMessage());
        }
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
        sendBtn.setText("鍋滄");

        final String fileName = pendingFileName;
        final String fileText = pendingFileText;
        final List<String> imgs = new ArrayList<>(pendingImages);

        StringBuilder shown = new StringBuilder();
        if (!text.isEmpty()) shown.append(text);
        if (!fileText.isEmpty()) {
            if (shown.length() > 0) shown.append("\n");
            shown.append("[鏂囦欢] ").append(fileName).append("\n").append(fileText);
        }
        if (!imgs.isEmpty()) {
            if (shown.length() > 0) shown.append("\n");
            shown.append("[鍥剧墖 x").append(imgs.size()).append("]");
        }
        final String fullText = shown.toString();

        int userIdx = history.size();
        history.add(new String[]{"user", fullText});
        addBubble("user", fullText, userIdx);
        store.saveMessage(currentConvId, "user", fullText);
        pendingImages.clear();
        pendingFileName = "";
        pendingFileText = "";

        if (store.countMessages(currentConvId) == 1) {
            String t = text.isEmpty() ? "闄勪欢瀵硅瘽" : (text.length() > 20 ? text.substring(0, 20) : text);
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
                            reply.setText(Md.parse(acc.toString() + "\n锛堝凡鍋滄锛�"));
                        }
                    });
                } else {
                    history.add(new String[]{"assistant", acc.toString()});
                    store.saveMessage(currentConvId, "assistant", acc.toString());
                    maybeExtractMemory(text, acc.toString());
                    maybeRunTools(acc.toString());
                }
            } catch (Exception e) {
                if (cancelled) return;
                final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                ui.post(() -> {
                    if (acc.length() == 0) {
                        removeBubble(reply);
                    } else {
                        reply.setText(Md.parse(acc.toString() + "\n锛堢敓鎴愪腑鏂級"));
                    }
                    status.setText("鍑洪敊锛�" + msg);
                });
            } finally {
                ui.post(() -> {
                    busy = false;
                    sendBtn.setText("鍙戦€�");
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
                return new String[]{p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[8]};
            }
        }
        return new String[]{
                settings.globalType(),
                settings.globalHost(),
                settings.globalKey(),
                settings.globalModel(),
                "4096",
                "",
                "",
                String.valueOf(settings.globalThinking())
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
        boolean thinking = conf[7].equals("true");
        while (host.endsWith("/")) host = host.substring(0, host.length() - 1);

        // 鏂囨。妫€绱細濡傛灉鏈� pendingFileName锛屾绱㈢浉鍏崇墖娈靛苟娉ㄥ叆
        String docContext = "";
        if (pendingFileName != null && !pendingFileName.isEmpty()) {
            DocumentChunker chunker = new DocumentChunker(this);
            List<DocumentChunker.SearchResult> hits = chunker.searchAll(userText, 3);
            for (DocumentChunker.SearchResult r : hits) {
                docContext += "\n[鐩稿叧鐗囨][" + r.chunk.index + "] " + r.chunk.summary + "\n";
            }
            if (!docContext.isEmpty()) {
                docContext = "浠ヤ笅鏄檮浠舵枃妗ｄ腑鐨勭浉鍏冲唴瀹癸紙璇蜂紭鍏堝弬鑰冿級锛�" + docContext;
            }
        }
        JSONArray msgs = new JSONArray();
        JSONObject memMsg = MemoryManager.toSystemMessage(settings.memory());
        if (memMsg != null) msgs.put(memMsg);
        if (skill != null && !skill.trim().isEmpty()) {
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", "璇烽伒寰互涓� Skill锛歕n" + skill);
            msgs.put(sys);
        }
        if (activeSkill != null && !activeSkill.trim().isEmpty()) {
            JSONObject sys2 = new JSONObject();
            sys2.put("role", "system");
            sys2.put("content", "褰撳墠浼氳瘽婵€娲荤殑 Skill锛歕n" + activeSkill);
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
            userMsg.put("content", (docContext == null || docContext.isEmpty()) ? userText : (userText + docContext));
        }
        msgs.put(userMsg);

        JSONObject body = new JSONObject();

        // 宸ュ叿璋冪敤鏀寔
        if (toolRegistry.hasTools()) {
            JSONArray tools = toolRegistry.getToolDefinitions();
            if (tools.length() > 0) {
                body.put("tools", tools);
                body.put("tool_choice", "auto");
            }
        }

        int budget = ContextManager.parseInt(maxTokens, 4096) * 3;
        msgs = ContextManager.applyStrategy(msgs, budget, settings.contextStrategy());

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
                ui.post(() -> status.setText("杩炴帴瓒呮椂锛岃妫€鏌ョ綉缁�"));
                throw new Exception("Timeout");
            }
        }
        br.close();
        c.disconnect();
        currentConn = null;
        try { recordUsage(conf, userText, acc.toString()); } catch (Exception ignored) {}
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
                .setTitle("浼氳瘽锛堝嬀閫夊彲鎵归噺鍒犻櫎锛�")
                .setMultiChoiceItems(items, null, (d, which, checked) -> {
                })
                .setPositiveButton("鍒犻櫎閫変腑", (d, w) -> {
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
                .setNeutralButton("鏂板缓", (d, w) -> {
                    currentConvId = store.newConversation();
                    loadHistory(currentConvId);
                })
                .setNegativeButton("鍗曚釜鎿嶄綔", (d, w) -> showSessionOps())
                .show();
    }

    private void showSessionOps() {
        new AlertDialog.Builder(this)
                .setTitle("浼氳瘽鎿嶄綔")
                .setItems(new String[]{"閲嶅懡鍚�", "璁句负妯″瀷", "绉诲叆椤圭洰缁�", "绉诲嚭椤圭洰缁�", "鍒犻櫎"}, (d, which) -> {
                    if (which == 0) renameConversation();
                    else if (which == 1) setConversationModel();
                    else if (which == 2) moveToGroup();
                    else if (which == 3) store.moveOutGroup(currentConvId);
                    else if (which == 4) deleteConversation();
                })
                .setNegativeButton("鍙栨秷", null)
                .show();
    }

    private void renameConversation() {
        final EditText et = new EditText(this);
        et.setText(store.getConversationTitle(currentConvId));
        new AlertDialog.Builder(this)
                .setTitle("閲嶅懡鍚�")
                .setView(et)
                .setPositiveButton("纭畾", (d, w) -> {
                    String t = et.getText().toString().trim();
                    if (!t.isEmpty()) store.updateTitle(currentConvId, t);
                })
                .setNegativeButton("鍙栨秷", null)
                .show();
    }

    private void setConversationModel() {
        List<String[]> presets = settings.listPresets();
        if (presets.isEmpty()) {
            status.setText("璇峰厛鍦ㄨ缃噷娣诲姞妯″瀷棰勮");
            return;
        }
        String[] names = new String[presets.size() + 1];
        names[0] = "璺熼殢鍏ㄥ眬";
        for (int i = 0; i < presets.size(); i++) names[i + 1] = presets.get(i)[0];
        new AlertDialog.Builder(this)
                .setTitle("閫夋嫨妯″瀷")
                .setItems(names, (d, which) -> {
                    if (which == 0) store.setConversationModel(currentConvId, "");
                    else store.setConversationModel(currentConvId, presets.get(which - 1)[0]);
                })
                .setNegativeButton("鍙栨秷", null)
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
                .setTitle("椤圭洰缁�")
                .setItems(items, (d, which) -> {
                    String[] g = groups.get(which);
                    showGroupDetail(Long.parseLong(g[0]), g[1]);
                })
                .setNeutralButton("鏂板缓缁�", (d, w) -> createGroup())
                .setNegativeButton("鍏抽棴", null)
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
                .setPositiveButton("鍒犻櫎缁�", (d, w) -> store.deleteGroup(groupId))
                .setNegativeButton("鍏抽棴", null)
                .show();
    }

    private void createGroup() {
        final EditText et = new EditText(this);
        et.setHint("椤圭洰缁勫悕绉�");
        new AlertDialog.Builder(this)
                .setTitle("鏂板缓椤圭洰缁�")
                .setView(et)
                .setPositiveButton("纭畾", (d, w) -> {
                    String n = et.getText().toString().trim();
                    if (!n.isEmpty()) store.newGroup(n);
                })
                .setNegativeButton("鍙栨秷", null)
                .show();
    }

    private void moveToGroup() {
        List<String[]> groups = store.listGroups();
        if (groups.isEmpty()) {
            status.setText("璇峰厛鍒涘缓椤圭洰缁�");
            return;
        }
        String[] names = new String[groups.size()];
        for (int i = 0; i < groups.size(); i++) names[i] = groups.get(i)[1];
        new AlertDialog.Builder(this)
                .setTitle("绉诲叆椤圭洰缁�")
                .setItems(names, (d, which) -> {
                    store.moveToGroup(currentConvId, Long.parseLong(groups.get(which)[0]));
                })
                .setNegativeButton("鍙栨秷", null)
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
        etHost.setHint("API 鍦板潃");
        etHost.setText(settings.globalHost());
        box.addView(etHost);

        final EditText etKey = new EditText(this);
        etKey.setHint("API Key");
        etKey.setText(settings.globalKey());
        box.addView(etKey);

        final EditText etModel = new EditText(this);
        etModel.setHint("妯″瀷 ID");
        etModel.setText(settings.globalModel());
        box.addView(etModel);

        final Button btnThinking = new Button(this);
        btnThinking.setText(settings.globalThinking() ? "娣卞害鎬濊€冿細寮€鍚�" : "娣卞害鎬濊€冿細鍏抽棴");
        btnThinking.setOnClickListener(v -> {
            boolean cur = settings.globalThinking();
            settings.put("g_thinking", cur ? "off" : "on");
            btnThinking.setText(cur ? "娣卞害鎬濊€冿細鍏抽棴" : "娣卞害鎬濊€冿細寮€鍚�");
        });
        box.addView(btnThinking);

        final Button btnContext = new Button(this);
        btnContext.setText("涓婁笅鏂囩瓥鐣ワ細" + ContextManager.strategyLabel(settings.contextStrategy()));
        btnContext.setOnClickListener(v -> {
            String cur = settings.contextStrategy();
            String next;
            if (ContextManager.STRATEGY_OFF.equals(cur)) {
                next = ContextManager.STRATEGY_SLIDING;
            } else if (ContextManager.STRATEGY_SLIDING.equals(cur)) {
                next = ContextManager.STRATEGY_SUMMARY;
            } else {
                next = ContextManager.STRATEGY_OFF;
            }
            settings.saveContextStrategy(next);
            btnContext.setText("涓婁笅鏂囩瓥鐣ワ細" + ContextManager.strategyLabel(next));
        });
        box.addView(btnContext);

        final Button btnMem = new Button(this);
        btnMem.setText(settings.memoryOn() ? "鑷姩璁板繂锛氬紑鍚�" : "鑷姩璁板繂锛氬叧闂�");
        btnMem.setOnClickListener(v -> {
            boolean cur = settings.memoryOn();
            settings.saveMemoryOn(!cur);
            btnMem.setText(!cur ? "鑷姩璁板繂锛氬紑鍚�" : "鑷姩璁板繂锛氬叧闂�");
        });
        box.addView(btnMem);

        Button btnVision = new Button(this);
        btnVision.setText("閰嶇疆瑙嗚妯″瀷");
        btnVision.setOnClickListener(v -> showVisionConfig());
        box.addView(btnVision);

        // 鎼滅储閰嶇疆
        final EditText etSearchEngine = new EditText(this);
        etSearchEngine.setHint("ddg/brave/serper");
        etSearchEngine.setText(settings.searchEngine());
        box.addView(etSearchEngine);

        final EditText etSearchKey = new EditText(this);
        etSearchKey.setHint("鎼滅储 API Key锛堝彲鐣欑┖锛�");
        etSearchKey.setText(settings.searchApiKey());
        box.addView(etSearchKey);

        new AlertDialog.Builder(this)
                .setTitle("鍏ㄥ眬璁剧疆")
                .setView(box)
                .setPositiveButton("淇濆瓨鍏ㄥ眬", (d, w) -> settings.saveGlobal(
                        etType.getText().toString().trim().toUpperCase(),
                        etHost.getText().toString().trim(),
                        etKey.getText().toString().trim(),
                        etModel.getText().toString().trim(),
                        settings.globalThinking()))
                .setNeutralButton("宸ュ叿閰嶇疆", (d, w) -> showToolConfig())
                .setNeutralButton("绠＄悊棰勮", (d, w) -> showPresets(
                        etType.getText().toString().trim().toUpperCase(),
                        etHost.getText().toString().trim(),
                        etKey.getText().toString().trim(),
                        etModel.getText().toString().trim()))
                .setNegativeButton("鍏抽棴", null)
                .show();
    }

    private void showPresets(String type, String host, String key, String model) {
        List<String[]> presets = settings.listPresets();
        String[] items = new String[presets.size()];
        for (int i = 0; i < presets.size(); i++) items[i] = presets.get(i)[0];
        new AlertDialog.Builder(this)
                .setTitle("妯″瀷棰勮锛堢偣鍑诲垹闄わ級")
                .setItems(items, (d, which) -> settings.deletePreset(presets.get(which)[0]))
                .setPositiveButton("鏂板棰勮", (d, w) -> addPreset(type, host, key, model))
                .setNegativeButton("鍏抽棴", null)
                .show();
    }

    private void addPreset(String type, String host, String key, String model) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));

        final EditText etName = new EditText(this);
        etName.setHint("棰勮鍚嶇О锛屽 Claude");
        box.addView(etName);

        final EditText etType = new EditText(this);
        etType.setHint("鍗忚");
        etType.setText(type);
        box.addView(etType);

        final EditText etHost = new EditText(this);
        etHost.setHint("API 鍦板潃");
        etHost.setText(host);
        box.addView(etHost);

        final EditText etKey = new EditText(this);
        etKey.setHint("API Key");
        etKey.setText(key);
        box.addView(etKey);

        final EditText etModel = new EditText(this);
        etModel.setHint("妯″瀷 ID");
        etModel.setText(model);
        box.addView(etModel);

        final EditText etMax = new EditText(this);
        etMax.setHint("maxTokens锛屽 4096");
        etMax.setText("4096");
        box.addView(etMax);

        final EditText etTemp = new EditText(this);
        etTemp.setHint("temperature锛屽彲鐣欑┖");
        box.addView(etTemp);

        final EditText etSkill = new EditText(this);
        etSkill.setHint("Skill 鍐呭锛屽彲澶氳");
        box.addView(etSkill);

        final EditText etNote = new EditText(this);
        etNote.setHint("澶囨敞");
        box.addView(etNote);

        final Button btnThinking = new Button(this);
        btnThinking.setText("娣卞害鎬濊€冿細鍏抽棴");
        btnThinking.setOnClickListener(v -> {
            boolean cur = btnThinking.getText().toString().contains("寮€鍚�");
            btnThinking.setText(cur ? "娣卞害鎬濊€冿細鍏抽棴" : "娣卞害鎬濊€冿細寮€鍚�");
        });
        box.addView(btnThinking);

        new AlertDialog.Builder(this)
                .setTitle("鏂板妯″瀷棰勮")
                .setView(box)
                .setPositiveButton("淇濆瓨", (d, w) -> {
                    String n = etName.getText().toString().trim();
                    if (n.isEmpty()) return;
                    boolean thinking = btnThinking.getText().toString().contains("寮€鍚�");
                    settings.savePreset(n,
                            etType.getText().toString().trim().toUpperCase(),
                            etHost.getText().toString().trim(),
                            etKey.getText().toString().trim(),
                            etModel.getText().toString().trim(),
                            etMax.getText().toString().trim(),
                            etTemp.getText().toString().trim(),
                            etSkill.getText().toString().trim(),
                            etNote.getText().toString().trim(),
                            thinking);
                })
                .setNegativeButton("鍙栨秷", null)
                .show();
    }

    private void showSkills() {
        List<Skill> skills = Skill.loadAll(this);
        if (skills.isEmpty()) {
            showAddSkill();
            return;
        }
        String[] items = new String[skills.size() + 1];
        items[0] = "锛� 鏂板缓 Skill";
        for (int i = 0; i < skills.size(); i++) {
            items[i + 1] = skills.get(i).name + (skills.get(i).name.equals(activeSkill) ? " 鉁�" : "");
        }
        new AlertDialog.Builder(this)
                .setTitle("Skill 绠＄悊锛堢偣鍑诲惎鐢�/鍒囨崲锛�")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        showAddSkill();
                    } else {
                        Skill s = skills.get(which - 1);
                        activeSkill = s.name;
                        status.setText("宸插惎鐢細" + s.name);
                    }
                })
                .setNegativeButton("鍏抽棴", null)
                .show();
    }

    private void showAddSkill() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));

        final EditText etName = new EditText(this);
        etName.setHint("Skill 鍚嶇О");
        box.addView(etName);

        final EditText etDesc = new EditText(this);
        etDesc.setHint("鎻忚堪");
        box.addView(etDesc);

        final EditText etContent = new EditText(this);
        etContent.setHint("Skill 鍐呭锛堜綔涓� system 鎻愮ず璇嶆敞鍏ワ級");
        etContent.setLines(6);
        box.addView(etContent);

        new AlertDialog.Builder(this)
                .setTitle("鏂板缓 Skill")
                .setView(box)
                .setPositiveButton("淇濆瓨", (d, w) -> {
                    String n = etName.getText().toString().trim();
                    String desc = etDesc.getText().toString().trim();
                    String content = etContent.getText().toString().trim();
                    if (n.isEmpty() || content.isEmpty()) return;
                    Skill.save(this, new Skill(n, content, desc));
                    status.setText("Skill 宸蹭繚瀛�");
                })
                .setNegativeButton("鍙栨秷", null)
                .show();
    }

    private void showSubAgents() {
        if (subAgentNames.isEmpty()) {
            status.setText("璇峰厛娣诲姞瀛愪唬鐞�");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("瀛愪唬鐞嗙鐞�")
                .setItems(subAgentNames.toArray(new String[0]), (d, which) -> {
                    String name = subAgentNames.get(which);
                    runSubAgent(name);
                })
                .setNegativeButton("娣诲姞瀛愪唬鐞�", (d, w) -> addSubAgent())
                .show();
    }

    private void addSubAgent() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));

        final EditText etName = new EditText(this);
        etName.setHint("瀛愪唬鐞嗗悕绉�");
        box.addView(etName);

        final EditText etSystem = new EditText(this);
        etSystem.setHint("绯荤粺鎸囦护");
        etSystem.setLines(4);
        box.addView(etSystem);

        final EditText etUser = new EditText(this);
        etUser.setHint("鐢ㄦ埛鎸囦护");
        etUser.setLines(2);
        box.addView(etUser);

        new AlertDialog.Builder(this)
                .setTitle("娣诲姞瀛愪唬鐞�")
                .setView(box)
                .setPositiveButton("娣诲姞", (d, w) -> {
                    String n = etName.getText().toString().trim();
                    if (!n.isEmpty()) subAgentNames.add(n);
                })
                .setNegativeButton("鍙栨秷", null)
                .show();
    }

    private void runSubAgent(String name) {
        status.setText("瀛愪唬鐞嗚繍琛屼腑鈥�");
        new Thread(() -> {
            try {
                String[] conf = resolveConfig();
                String system = "璇蜂弗鏍兼寜鐓т綘鐨勮鑹插拰鎸囦护瀹屾垚浠诲姟銆�";
                String user = "璇峰鐞嗕互涓嬩换鍔★細\n" + name;
                String result = SubAgent.run(conf[0], conf[1], conf[2], conf[3], system, user);
                subAgentResults.put(name, result);
                ui.post(() -> {
                    addBubble("user", "[瀛愪唬鐞� " + name + " 瀹屾垚]\n" + result);
                    store.saveMessage(currentConvId, "user", "[瀛愪唬鐞� " + name + " 瀹屾垚]\n" + result);
                    status.setText("瀛愪唬鐞嗗畬鎴�");
                });
            } catch (Exception e) {
                ui.post(() -> status.setText("瀛愪唬鐞嗗け璐ワ細" + e.getMessage()));
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

    private void showApiRecords() {
        if (!ApiKeyStore.hasPassword(this)) {
            final EditText et = new EditText(this);
            et.setHint("璁剧疆閿佸畾瀵嗙爜");
            new AlertDialog.Builder(this)
                    .setTitle("璁剧疆 API 璁板綍瀵嗙爜")
                    .setMessage("棣栨浣跨敤璇疯缃瘑鐮侊紝鐢ㄤ簬鍔犲瘑淇濆瓨 API Key銆�")
                    .setView(et)
                    .setPositiveButton("纭畾", (d, w) -> {
                        String p = et.getText().toString().trim();
                        if (p.isEmpty()) { status.setText("瀵嗙爜涓嶈兘涓虹┖"); return; }
                        ApiKeyStore.setPassword(this, p);
                        status.setText("瀵嗙爜宸茶缃�");
                        showApiRecords();
                    })
                    .setNegativeButton("鍙栨秷", null)
                    .show();
            return;
        }
        final EditText et = new EditText(this);
        et.setHint("杈撳叆閿佸畾瀵嗙爜");
        new AlertDialog.Builder(this)
                .setTitle("瑙ｉ攣 API 璁板綍")
                .setView(et)
                .setPositiveButton("瑙ｉ攣", (d, w) -> {
                    String p = et.getText().toString().trim();
                    if (!ApiKeyStore.verifyPassword(this, p)) {
                        status.setText("瀵嗙爜閿欒");
                        return;
                    }
                    showApiList(p);
                })
                .setNegativeButton("鍙栨秷", null)
                .show();
    }

    private void showApiList(final String pwd) {
        List<String[]> records;
        try {
            records = ApiKeyStore.list(this, pwd);
        } catch (Exception e) {
            status.setText("璇诲彇澶辫触锛�" + e.getMessage());
            return;
        }
        String[] items = new String[records.size() + 1];
        items[0] = "锛� 鏂板 API 璁板綍";
        for (int i = 0; i < records.size(); i++) {
            items[i + 1] = records.get(i)[1] + "  |  " + records.get(i)[2];
        }
        new AlertDialog.Builder(this)
                .setTitle("API 璁板綍锛堢偣鍑荤敤浜庡綋鍓嶄細璇濓級")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        showAddApi(pwd);
                    } else {
                        String[] r = records.get(which - 1);
                        applyApiRecord(r[2], r[3], r[4]);
                    }
                })
                .setNeutralButton("淇敼瀵嗙爜", (d, w) -> changeApiPassword())
                .setNegativeButton("鍏抽棴", null)
                .show();
    }

    private void applyApiRecord(String host, String key, String model) {
        settings.saveGlobal(settings.globalType(), host, key, model, settings.globalThinking());
        status.setText("宸插簲鐢細" + host + " / " + model);
    }

    private void showAddApi(final String pwd) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));

        final EditText etName = new EditText(this);
        etName.setHint("鍚嶇О锛屽 Claude 瀹樻柟");
        box.addView(etName);

        final EditText etHost = new EditText(this);
        etHost.setHint("API 涓绘満锛屽 https://api.anthropic.com");
        box.addView(etHost);

        final EditText etKey = new EditText(this);
        etKey.setHint("API Key");
        box.addView(etKey);

        final EditText etModel = new EditText(this);
        etModel.setHint("妯″瀷 ID锛屽彲鐣欑┖");
        box.addView(etModel);

        new AlertDialog.Builder(this)
                .setTitle("鏂板 API 璁板綍")
                .setView(box)
                .setPositiveButton("淇濆瓨", (d, w) -> {
                    String name = etName.getText().toString().trim();
                    String host = etHost.getText().toString().trim();
                    String key = etKey.getText().toString().trim();
                    String model = etModel.getText().toString().trim();
                    if (name.isEmpty() || host.isEmpty() || key.isEmpty()) {
                        status.setText("鍚嶇О/涓绘満/Key 蹇呭～");
                        return;
                    }
                    try {
                        ApiKeyStore.save(this, pwd, "", name, host, key, model);
                        status.setText("宸蹭繚瀛�");
                        showApiList(pwd);
                    } catch (Exception e) {
                        status.setText("淇濆瓨澶辫触锛�" + e.getMessage());
                    }
                })
                .setNegativeButton("鍙栨秷", null)
                .show();
    }

    private void changeApiPassword() {
        final EditText et = new EditText(this);
        et.setHint("杈撳叆鏂板瘑鐮�");
        new AlertDialog.Builder(this)
                .setTitle("淇敼瀵嗙爜")
                .setMessage("娉ㄦ剰锛氫慨鏀瑰瘑鐮佸悗鍘熸湁璁板綍灏嗘棤娉曡В瀵嗭紝璇峰厛澶囦唤銆�")
                .setView(et)
                .setPositiveButton("纭畾", (d, w) -> {
                    String p = et.getText().toString().trim();
                    if (p.isEmpty()) return;
                    ApiKeyStore.setPassword(this, p);
                    status.setText("瀵嗙爜宸蹭慨鏀�");
                })
                .setNegativeButton("鍙栨秷", null)
                .show();
    }

    private void maybeExtractMemory(String userText, String replyText) {
        if (!settings.memoryOn()) return;
        String[] conf = resolveConfig();
        String dialogue = "鐢ㄦ埛锛�" + userText + "\n鍔╂墜锛�" + replyText;
        MemoryManager.extract(conf[0], conf[1], conf[2], conf[3], dialogue,
                settings.memory(), new MemoryManager.Callback() {
                    @Override
                    public void onDone(final String mem) {
                        ui.post(() -> {
                            settings.saveMemory(mem);
                            status.setText("宸叉洿鏂拌蹇�");
                        });
                    }
                    @Override
                    public void onError(final String msg) {
                        // 闈欓粯澶辫触锛屼笉鎵撴柇鐢ㄦ埛
                    }
                });
    }

    private void showMemory() {
        String mem = settings.memory();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));

        final EditText et = new EditText(this);
        et.setHint("闀挎湡璁板繂鍐呭");
        et.setLines(8);
        et.setText(mem == null ? "" : mem);
        box.addView(et);

        new AlertDialog.Builder(this)
                .setTitle("闀挎湡璁板繂")
                .setView(box)
                .setPositiveButton("淇濆瓨", (d, w) -> {
                    settings.saveMemory(et.getText().toString().trim());
                    status.setText("璁板繂宸蹭繚瀛�");
                })
                .setNeutralButton("娓呯┖", (d, w) -> {
                    settings.clearMemory();
                    status.setText("璁板繂宸叉竻绌�");
                })
                .setNegativeButton("鍏抽棴", null)
                .show();
    }

    // ========== 瑙嗚鐞嗚В ==========

    /**
     * 鍒ゆ柇妯″瀷鏄惁鍘熺敓鏀寔瑙嗚
     * DeepSeek 绯诲垪锛坉eepseek-chat / deepseek-reasoner / deepseek-flash锛変笉鏀寔
     */
    private boolean modelSupportsVision(String type, String model) {
        String m = model == null ? "" : model.toLowerCase();
        if (m.contains("vl") || m.contains("vision") || m.contains("visual")) return true;
        if (m.startsWith("gpt-4") || m.equals("gpt-4o") || m.startsWith("gpt-4o")) return true;
        if (m.contains("claude") || m.contains("gemini")) return true;
        if (m.contains("qwen-vl") || m.contains("glm-4v") || m.contains("internvl")) return true;
        if (m.contains("deepseek-chat") || m.contains("deepseek-reasoner")
                || m.contains("deepseek-flash") || m.contains("deepseek-v3")) return false;
        // 鍏跺畠榛樿灏濊瘯
        return true;
    }

    private void loadVisionModel() {
        if (visionLoaded) return;
        String json = settings.visionJson();
        if (json == null || json.trim().isEmpty()) {
            visionModel = new VisionModel();
            visionLoaded = true;
            return;
        }
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            visionModel.name = o.optString("name", "");
            visionModel.type = o.optString("type", "OPENAI");
            visionModel.host = o.optString("host", "");
            visionModel.key = o.optString("key", "");
            visionModel.model = o.optString("model", "");
        } catch (Exception e) {
            visionModel = new VisionModel();
        }
        visionLoaded = true;
    }

    /**
     * 鍥剧墖鐞嗚В锛氫富妯″瀷鏀寔灏辩洿鎺ョ敤锛屽惁鍒欑敤瑙嗚妯″瀷
     */
    private void analyzeImages() {
        loadVisionModel();
        if (pendingImages.isEmpty()) {
            status.setText("璇峰厛閫夋嫨鍥剧墖");
            return;
        }
        String[] conf = resolveConfig();
        String type = conf[0];
        String model = conf[3];

        if (modelSupportsVision(type, model)) {
            // 涓绘ā鍨嬫敮鎸侊紝鐩存帴鎶婂浘鐗囦綔涓洪棶棰樺彂閫�
            input.setText("璇疯缁嗘弿杩拌繖寮犲浘鐗囩殑鍐呭");
            status.setText("涓绘ā鍨嬫敮鎸佽瑙夛紝璇风洿鎺ュ彂閫�");
            return;
        }

        if (!visionModel.isConfigured()) {
            status.setText("褰撳墠妯″瀷涓嶆敮鎸佽瑙夛紝璇峰厛鍦ㄨ缃噷閰嶇疆瑙嗚妯″瀷");
            return;
        }

        final String prompt = input.getText().toString().trim().isEmpty()
                ? "璇疯缁嗘弿杩拌繖寮犲浘鐗囩殑鍐呭"
                : input.getText().toString().trim();
        status.setText("瑙嗚妯″瀷鍒嗘瀽涓€�");

        new Thread(() -> {
            try {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < pendingImages.size(); i++) {
                    String result = visionModel.analyze(prompt, pendingImages.get(i));
                    sb.append("銆愬浘鐗� ").append(i + 1).append("銆�").append(result).append("\n");



                }
                final String out = sb.toString().trim();
                ui.post(() -> {
                    addBubble("assistant", "[瑙嗚鐞嗚В " + visionModel.name + "]" + out);

                    store.saveMessage(currentConvId, "assistant", "[瑙嗚鐞嗚В]" + out);

                    status.setText("鍥剧墖鍒嗘瀽瀹屾垚");
                });
            } catch (Exception e) {
                ui.post(() -> status.setText("鍒嗘瀽澶辫触锛�" + e.getMessage()));
            }
        }).start();
    }

    private void showVisionConfig() {
        loadVisionModel();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));

        final EditText etName = new EditText(this);
        etName.setHint("鍚嶇О锛屽 DeepSeek-VL");
        etName.setText(visionModel.name);
        box.addView(etName);

        final EditText etType = new EditText(this);
        etType.setHint("鍗忚 OPENAI / ANTHROPIC / GEMINI");
        etType.setText(visionModel.type);
        box.addView(etType);

        final EditText etHost = new EditText(this);
        etHost.setHint("API 涓绘満");
        etHost.setText(visionModel.host);
        box.addView(etHost);

        final EditText etKey = new EditText(this);
        etKey.setHint("API Key");
        etKey.setText(visionModel.key);
        box.addView(etKey);

        final EditText etModel = new EditText(this);
        etModel.setHint("妯″瀷 ID锛屽 deepseek-vl2");
        etModel.setText(visionModel.model);
        box.addView(etModel);

        new AlertDialog.Builder(this)
                .setTitle("瑙嗚妯″瀷閰嶇疆")
                .setMessage("涓绘ā鍨嬩笉鏀寔瑙嗚鏃讹紝鐢ㄥ畠鏉ュ垎鏋愬浘鐗囥€�")
                .setView(box)
                .setPositiveButton("淇濆瓨", (d, w) -> {
                    VisionModel v = new VisionModel(
                            etName.getText().toString().trim(),
                            etType.getText().toString().trim().toUpperCase(),
                            etHost.getText().toString().trim(),
                            etKey.getText().toString().trim(),
                            etModel.getText().toString().trim());
                    visionModel = v;
                    try {
                        org.json.JSONObject o = new org.json.JSONObject();
                        o.put("name", v.name);
                        o.put("type", v.type);
                        o.put("host", v.host);
                        o.put("key", v.key);
                        o.put("model", v.model);
                        settings.saveVisionJson(o.toString());
                        status.setText("瑙嗚妯″瀷宸蹭繚瀛�");
                    } catch (Exception e) {
                        status.setText("淇濆瓨澶辫触锛�" + e.getMessage());
                    }
                })
                .setNegativeButton("鍙栨秷", null)
                .show();
    }

    // ========== 宸ュ叿閰嶇疆 ==========

    private void showToolConfig() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));

        final Button btnAddMcp = new Button(this);
        btnAddMcp.setText("娣诲姞 MCP 鏈嶅姟鍣�");
        btnAddMcp.setOnClickListener(v -> showMcpConfig());
        box.addView(btnAddMcp);

        // 鍒楀嚭宸叉敞鍐屽伐鍏�
        StringBuilder sb = new StringBuilder();
        sb.append("鍐呯疆宸ュ叿锛歸eb_search, calculator\n");
        TextView tvInfo = new TextView(this);
        tvInfo.setText("宸ュ叿娉ㄥ唽琛ㄥ凡鍒濆鍖栵紝鍐呯疆锛歸eb_search, calculator\nMCP 鏈嶅姟鍣ㄩ渶鍗曠嫭娣诲姞");
        tvInfo.setTextSize(14);
        box.addView(tvInfo);

        new AlertDialog.Builder(this)
                .setTitle("宸ュ叿绠＄悊")
                .setView(box)
                .setPositiveButton("鍏抽棴", null)
                .show();
    }

    private void showMcpConfig() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));

        final EditText etName = new EditText(this);
        etName.setHint("鏈嶅姟鍣ㄥ悕绉帮紝濡� Brave Search");
        box.addView(etName);

        final EditText etUrl = new EditText(this);
        etUrl.setHint("MCP 鏈嶅姟鍣� HTTP 鍦板潃锛屽 https://mcp.example.com/rpc");
        box.addView(etUrl);

        new AlertDialog.Builder(this)
                .setTitle("娣诲姞 MCP 鏈嶅姟鍣�")
                .setView(box)
                .setPositiveButton("杩炴帴", (d, w) -> {
                    String name = etName.getText().toString().trim();
                    String url = etUrl.getText().toString().trim();
                    if (name.isEmpty() || url.isEmpty()) return;
                    new Thread(() -> {
                        try {
                            McpClient client = new McpClient(url, name);
                            client.connect();
                            toolRegistry.registerMcpServer(client);
                            ui.post(() -> status.setText("MCP 宸茶繛鎺ワ細" + name + " (" + client.tools.size() + " 宸ュ叿)"));
                        } catch (Exception e) {
                            ui.post(() -> status.setText("MCP 杩炴帴澶辫触锛�" + e.getMessage()));
                        }
                    }).start();
                })
                .setNegativeButton("鍙栨秷", null)
                .show();
    }


    private void maybeRunTools(String aiResponse) {
        if (!toolRegistry.hasTools()) return;
        // 妫€娴� AI 鍝嶅簲鏄惁鍖呭惈宸ュ叿璋冪敤璇锋眰锛堢畝鍖栵細妫€娴� JSON 鏍煎紡鐨� tool_calls锛�
        try {
            // OpenAI 鏍煎紡锛歿"tool_calls": [...]}
            JSONObject root = new JSONObject(aiResponse);
            if (root.has("tool_calls")) {
                JSONArray calls = root.getJSONArray("tool_calls");
                for (int i = 0; i < calls.length(); i++) {
                    JSONObject call = calls.getJSONObject(i);
                    String name = call.optString("name", "");
                    JSONObject args = call.optJSONObject("arguments");
                    if (args == null) args = new JSONObject();
                    String result = toolRegistry.execute(name, args);
                    // 娉ㄥ叆宸ュ叿缁撴灉浣滀负 system 娑堟伅锛岀户缁璇�
                    runToolResult(result);
                }
            }
        } catch (Exception ignored) {
            // 涓嶆槸绾� JSON锛屽彲鑳芥槸鏂囨湰鍖呭惈宸ュ叿璋冪敤锛岃烦杩�
        }
    }

    private void runToolResult(String toolResult) {
        // 灏嗗伐鍏风粨鏋滄敞鍏ュ璇�
        String toolContext = "[宸ュ叿缁撴灉]\n" + toolResult;
        store.saveMessage(currentConvId, "user", toolContext);
        history.add(new String[]{"user", toolContext});
        status.setText("宸ュ叿鎵ц瀹屾垚");
    }

    // ========== Token / 璐圭敤缁熻 ==========

    private void recordUsage(String[] conf, String userText, String replyText) {
        try {
            String model = conf[3];
            int inTokens = TokenCounter.estimate(userText) + currentHistoryTokens();
            int outTokens = TokenCounter.estimate(replyText);
            TokenCounter.Usage u = TokenCounter.calc(settings, model, inTokens, outTokens);
            settings.addUsage(inTokens + outTokens, u.cost, 1);
        } catch (Exception ignored) {
        }
    }

    private int currentHistoryTokens() {
        int total = 0;
        for (String[] h : history) {
            if (h[1] != null) total += TokenCounter.estimate(h[1]);
        }
        return total;
    }

    private void showUsage() {
        String json = settings.usageJson();
        int tokens = 0;
        double cost = 0;
        int turns = 0;
        try {
            JSONObject o = new JSONObject(json);
            tokens = o.optInt("tokens", 0);
            cost = o.optDouble("cost", 0);
            turns = o.optInt("turns", 0);
        } catch (Exception ignored) {
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));

        TextView tv = new TextView(this);
        tv.setText("绱 token锛�" + tokens + "\n"
                + "绱璐圭敤锛�" + TokenCounter.formatCost(cost) + "\n"
                + "瀵硅瘽杞锛�" + turns);
        tv.setTextSize(16);
        box.addView(tv);

        Button btnLive = new Button(this);
        btnLive.setText("鏇存柊瀹炴椂浠锋牸锛堝湪绾匡級");
        btnLive.setOnClickListener(v -> fetchLivePrices());
        box.addView(btnLive);

        Button btnPrice = new Button(this);
        btnPrice.setText("鏌ョ湅/缂栬緫浠锋牸琛�");
        btnPrice.setOnClickListener(v -> showPriceEditor());
        box.addView(btnPrice);

        new AlertDialog.Builder(this)
                .setTitle("鐢ㄩ噺缁熻")
                .setView(box)
                .setPositiveButton("娓呯┖缁熻", (d, w) -> {
                    settings.clearUsage();
                    status.setText("缁熻宸叉竻绌�");
                })
                .setNegativeButton("鍏抽棴", null)
                .show();
    }

    private void fetchLivePrices() {
        status.setText("姝ｅ湪鑾峰彇鍦ㄧ嚎浠锋牸鈥�");
        LivePriceProvider.fetchAndMerge(settings, new LivePriceProvider.Callback() {
            @Override
            public void onDone(final int count, final String source) {
                ui.post(() -> status.setText("宸叉洿鏂� " + count + " 涓ā鍨嬩环鏍硷紙" + source + "锛�"));
            }

            @Override
            public void onError(final String msg) {
                ui.post(() -> status.setText(msg));
            }
        });
    }

    private void showPriceEditor() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));

        final EditText et = new EditText(this);
        et.setHint("妯″瀷鍚�,杈撳叆浠�,杈撳嚭浠凤紙姣忕櫨涓噒oken鍏冿級\n姣忚涓€鏉�");
        et.setLines(8);

        StringBuilder sb = new StringBuilder();
        try {
            JSONArray arr = new JSONArray(settings.pricesJson());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                sb.append(o.optString("model", "")).append(",")
                  .append(o.optDouble("input", 0)).append(",")
                  .append(o.optDouble("output", 0)).append("\n");
            }
        } catch (Exception ignored) {
        }
        et.setText(sb.toString());
        box.addView(et);

        new AlertDialog.Builder(this)
                .setTitle("浠锋牸琛紙鍏�/鐧句竾 token锛�")
                .setView(box)
                .setPositiveButton("淇濆瓨", (d, w) -> {
                    savePriceText(et.getText().toString());
                })
                .setNeutralButton("鎭㈠榛樿", (d, w) -> {
                    settings.savePrices(TokenCounter.defaultPricesJson());
                    status.setText("宸叉仮澶嶉粯璁や环鏍艰〃");
                })
                .setNegativeButton("鍙栨秷", null)
                .show();
    }

    private void savePriceText(String text) {
        JSONArray arr = new JSONArray();
        String[] lines = text.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split(",");
            if (parts.length < 3) continue;
            try {
                JSONObject o = new JSONObject();
                o.put("model", parts[0].trim());
                o.put("input", Double.parseDouble(parts[1].trim()));
                o.put("output", Double.parseDouble(parts[2].trim()));
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        settings.savePrices(arr.toString());
        status.setText("浠锋牸琛ㄥ凡淇濆瓨");
    }

    // ========== 鏂囦欢浜や粯锛氫繚瀛� / 鎵撳紑 / 鍒嗕韩 ==========

    private void saveBubbleToFile(TextView tv) {
        CharSequence cs = tv.getText();
        String text = cs == null ? "" : cs.toString();
        if (text.trim().isEmpty()) {
            status.setText("娌℃湁鍙繚瀛樼殑鍐呭");
            return;
        }

        String code = extractCodeBlock(text);
        boolean hasCode = code != null && !code.isEmpty();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));

        final EditText etName = new EditText(this);
        etName.setHint("鏂囦欢鍚嶏紝濡� 鎶ュ憡");
        etName.setText(hasCode ? "output" : "note");
        box.addView(etName);

        final String[] kinds = hasCode
                ? new String[]{"HTML", "TXT", "MD", "CSV", "JSON"}
                : new String[]{"TXT", "MD", "HTML"};
        final java.util.List<Integer> sel = new java.util.ArrayList<Integer>();
        sel.add(0);

        new AlertDialog.Builder(this)
                .setTitle("淇濆瓨鍐呭")
                .setView(box)
                .setSingleChoiceItems(kinds, 0, (d, w) -> sel.set(0, w))
                .setPositiveButton("淇濆瓨", (d, w) -> {
                    String name = etName.getText().toString().trim();
                    int idx = sel.get(0);
                    String kindName = kinds[idx];
                    FileDelivery.Kind kind = kindOf(kindName);
                    String content = hasCode ? code : text;
                    try {
                        FileDelivery.SavedFile sf = FileDelivery.save(this, name, content, kind);
                        status.setText("宸蹭繚瀛橈細" + sf.file.getName());
                        showFileActions(sf, hasCode ? "浠ｇ爜宸蹭繚瀛�" : "鍐呭宸蹭繚瀛�");
                    } catch (Exception e) {
                        status.setText("淇濆瓨澶辫触锛�" + e.getMessage());
                    }
                })
                .setNegativeButton("鍙栨秷", null)
                .show();
    }

    private void showFileActions(final FileDelivery.SavedFile sf, String title) {
        new AlertDialog.Builder(this)
                .setTitle(title + "锛�" + sf.file.getName())
                .setItems(new String[]{"鎵撳紑", "鍒嗕韩", "浠呬繚瀛�"}, (d, which) -> {
                    if (which == 0) {
                        FileDelivery.open(this, sf);
                    } else if (which == 1) {
                        FileDelivery.share(this, sf, sf.file.getName());
                    } else {
                        status.setText("宸蹭繚瀛�");
                    }
                })
                .setNegativeButton("鍏抽棴", null)
                .show();
    }

    private static FileDelivery.Kind kindOf(String name) {
        if ("HTML".equals(name)) return FileDelivery.Kind.HTML;
        if ("CSV".equals(name)) return FileDelivery.Kind.CSV;
        if ("MD".equals(name)) return FileDelivery.Kind.MD;
        if ("JSON".equals(name)) return FileDelivery.Kind.JSON;
        return FileDelivery.Kind.TXT;
    }

    private static String extractCodeBlock(String text) {
        int start = text.indexOf("```");
        if (start < 0) return null;
        int langEnd = text.indexOf("\n", start);
        if (langEnd < 0) return null;
        int end = text.indexOf("```", langEnd + 1);
        if (end < 0) return null;
        return text.substring(langEnd + 1, end).trim();
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
