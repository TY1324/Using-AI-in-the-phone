package com.example.aichat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {

    // ===== 状态 =====
    private LinearLayout root;
    private LinearLayout contentArea;
    private LinearLayout tabBar;
    private ScrollView scroll;
    private LinearLayout list;
    private EditText input;
    private TextView status;

    private Button sendBtn;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // ===== 数据 =====
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

    // ===== 主题 =====
    private UiTheme.Palette theme;
    private int bgColor, textColor, bubbleUser, bubbleAi;

    private static final int PICK_IMAGE = 1001;
    private static final int REQUEST_SAVE = 1002;
    private static final int REQUEST_OPEN = 1003;
    private static final int PICK_FILE_REQ = 2001;

    // ===== 标签页 =====
    private static final String TAB_CHAT = "chat";
    private static final String TAB_TOOLS = "tools";
    private static final String TAB_ME = "me";
    private String currentTab = TAB_CHAT;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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
    }

    // ========================================================
    //   UI 构建
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
        switchTab(TAB_CHAT);
    }

    /** 顶部：标题 + 对话信息 */
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

        Button menu = iconButton("☰");
        menu.setOnClickListener(v -> showSettings());
        bar.addView(menu);

        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ((TextView) subtitle).setTag("subtitle");
        ((TextView) subtitle).setId(View.generateViewId());
        // 保存引用供后续更新
        subtitleRef = subtitle;
    }

    private TextView subtitleRef;

    /** 底部：标签导航（三个 Tab） */
    private void buildTabBar() {
        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setBackgroundColor(theme.surface);
        tabBar.setElevation(dp(8));

        addTab(TAB_CHAT, "对话", () -> switchTab(TAB_CHAT));
        addTab(TAB_TOOLS, "工具", () -> switchTab(TAB_TOOLS));
        addTab(TAB_ME, "我的", () -> switchTab(TAB_ME));

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

    /** 卡片容器 */
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

    /** 生成一张卡片 */
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

    /** 圆角按钮 */
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

    /** 图标按钮（圆形） */
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

    /** 行内小标签按钮 */
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
    //   Tab 1：对话页
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

        // 输入栏
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(theme.surface);
        bar.setPadding(dp(8), dp(8), dp(8), dp(8));
        bar.setElevation(dp(6));

        input = new EditText(this);
        input.setHint("输入消息…");
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

        sendBtn = roundButton("发送", theme.accent, 0xFFFFFFFF);
        sendBtn.setOnClickListener(v -> {
            if (busy) doStop(); else doSend();
        });
        bar.addView(sendBtn, marginStart(dp(6)));

        wrap.addView(bar);
        contentArea.addView(wrap);

        reloadBubbles();
        updateSubtitle();
    }

    /** 附件面板（图片 / 文件） */
    private void showAttachSheet() {
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout row = new LinearLayout(box.getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);

        Button img = roundButton("图片", theme.accentSoft, theme.accent);
        img.setOnClickListener(v -> {
            new AlertDialog.Builder(this).dismiss();
            pickImage();
        });
        row.addView(img, marginEnd(dp(10)));

        Button file = roundButton("文件", theme.accentSoft, theme.accent);
        file.setOnClickListener(v -> {
            new AlertDialog.Builder(this).dismiss();
            FileHelper.pick(this);
        });
        row.addView(file);

        box.addView(row);

        new AlertDialog.Builder(this)
                .setTitle("添加附件")
                .setView(box)
                .setNegativeButton("取消", null)
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
        if (title == null || title.isEmpty()) title = "新对话";
        String model = settings.globalModel();
        subtitleRef.setText(title + "  ·  " + model);
    }

    // ========================================================
    //   Tab 2：工具页
    // ========================================================

    private void showToolsPage() {
        clearContent();
        LinearLayout box = cardContainer();

        LinearLayout c1 = card("辅助工具");
        addRow(c1, "联网搜索", "实时获取互联网信息", v -> doSend());
        addRow(c1, "视觉理解", "分析图片内容", v -> analyzeImages());
        addRow(c1, "长期记忆", "跨会话记住你的偏好", v -> showMemory());
        box.addView(c1);

        LinearLayout c2 = card("扩展");
        addRow(c2, "MCP 服务器", "接入外部工具", v -> showMcpConfig());
        addRow(c2, "技能 Skill", "切换 AI 角色", v -> showSkills());
        addRow(c2, "子代理", "多轮任务拆解", v -> showSubAgents());
        box.addView(c2);
    }

    /** 列表行：标题 + 描述 + 点击 */
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
    //   Tab 3：我的页
    // ========================================================

    private void showMePage() {
        clearContent();
        LinearLayout box = cardContainer();

        LinearLayout c1 = card("会话");
        addRow(c1, "会话列表", "查看 / 重命名 / 删除", v -> showSessions());
        addRow(c1, "会话分组", "按主题整理对话", v -> showGroups());
        addRow(c1, "导出对话", "保存为 Markdown", v -> exportConversation());
        box.addView(c1);

        LinearLayout c2 = card("数据");
        addRow(c2, "API 记录", "加密保存多套密钥", v -> showApiRecords());
        addRow(c2, "预设管理", "快速切换模型", v -> showPresets(settings.globalType(), settings.globalHost(), settings.globalKey(), settings.globalModel()));
        addRow(c2, "用量统计", "Token 与费用", v -> showUsage());
        addRow(c2, "备份 / 恢复", "导出导入全部数据", v -> showBackupSheet());
        box.addView(c2);

        LinearLayout c3 = card("关于");
        addRow(c3, "主题外观", "浅色 / 深色", v -> toggleDarkMode());
        box.addView(c3);
    }

    private void showBackupSheet() {
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout row = new LinearLayout(box.getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);

        Button b1 = roundButton("备份", theme.accentSoft, theme.accent);
        b1.setOnClickListener(v -> { new AlertDialog.Builder(this).dismiss(); doBackup(); });
        row.addView(b1, marginEnd(dp(10)));

        Button b2 = roundButton("恢复", theme.accentSoft, theme.accent);
        b2.setOnClickListener(v -> { new AlertDialog.Builder(this).dismiss(); doRestore(); });
        row.addView(b2);

        box.addView(row);
        new AlertDialog.Builder(this)
                .setTitle("备份 / 恢复")
                .setView(box)
                .setNegativeButton("关闭", null)
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
        status.setText(settings.darkMode() ? "已切换深色" : "已切换浅色");
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

    private void handleLargeFile(Uri uri, String name) {
        status.setText("正在解析大文件…");
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
                    sb.append("[文档] ").append(name).append("\n");
                    sb.append("文件大小：").append(size / 1024).append(" KB\n");
                    sb.append("分块数量：").append(res.totalChunks).append("\n\n");
                    sb.append("章节摘要：\n");
                    for (LargeFileHandler.Chunk c : res.chunks) {
                        sb.append("[").append(c.index).append("] ").append(c.summary).append("\n");
                    }
                    sb.append("\n（完整内容已索引，对话时将自动检索相关段落）");

                    ui.post(() -> {
                        pendingFileName = name;
                        pendingFileText = sb.toString();
                        status.setText("已解析 " + res.totalChunks + " 块，可发送");
                    });
                } else if (res.fullText != null && !res.fullText.isEmpty()) {
                    ui.post(() -> {
                        pendingFileName = name;
                        pendingFileText = res.fullText;
                        status.setText("文件已加载");
                    });
                } else {
                    ui.post(() -> status.setText("文件解析为空"));
                }
            } catch (Exception e) {
                ui.post(() -> status.setText("解析失败：" + e.getMessage()));
            }
        }).start();
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
            items = new String[]{"复制", "保存为文件", "重新生成", "删除", "导出本会话 Markdown"};
        } else {
            items = new String[]{"复制", "保存为文件", "删除"};
        }
        new AlertDialog.Builder(this)
                .setTitle("消息操作")
                .setItems(items, (d, which) -> {
                    String act = items[which];
                    if ("复制".equals(act)) {
                        copyText(text);
                    } else if ("保存为文件".equals(act)) {
                        saveBubbleToFile(tv);
                    } else if ("重新生成".equals(act)) {
                        regenerateFrom(historyIndex);
                    } else if ("删除".equals(act)) {
                        deleteMessage(tv, historyIndex);
                    } else if ("导出本会话 Markdown".equals(act)) {
                        exportConversation();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void copyText(String text) {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("AI Chat", text));
            status.setText("已复制");
        } catch (Exception e) {
            status.setText("复制失败");
        }
    }

    private void deleteMessage(TextView tv, int historyIndex) {
        if (tv != null) {
            list.removeView(tv);
        }
        if (historyIndex >= 0 && historyIndex < history.size()) {
            history.remove(historyIndex);
        }
        status.setText("已删除（重启后从数据库恢复）");
    }

    private void regenerateFrom(int historyIndex) {
        // 找到该 assistant 消息之前的最后一个 user 消息
        int target = -1;
        for (int i = historyIndex - 1; i >= 0; i--) {
            if ("user".equals(history.get(i)[0])) {
                target = i;
                break;
            }
        }
        if (target < 0) {
            status.setText("找不到可重新生成的提问");
            return;
        }
        // 移除该 assistant 消息及之后的历史
        while (history.size() > target + 1) {
            history.remove(history.size() - 1);
        }
        list.removeAllViews();
        for (int i = 0; i < history.size(); i++) {
            addBubble(history.get(i)[0], history.get(i)[1], i);
        }
        input.setText(history.get(target)[1]);
        status.setText("已回退，请重新发送");
    }

    private void exportConversation() {
        StringBuilder sb = new StringBuilder();
        List<String[]> msgs = store.loadMessages(currentConvId);
        String title = store.getConversationTitle(currentConvId);
        sb.append("# ").append(title == null ? "对话" : title).append("\n\n");
        for (String[] m : msgs) {
            if (m[0] == null || m[1] == null) continue;
            if ("user".equals(m[0])) {
                sb.append("**我**\n\n").append(m[1]).append("\n\n");
            } else if ("assistant".equals(m[0])) {
                sb.append("**AI**\n\n").append(m[1]).append("\n\n");
            }
        }
        try {
            FileDelivery.SavedFile sf = FileDelivery.save(this, "对话导出", sb.toString(), FileDelivery.Kind.MD);
            status.setText("已导出：" + sf.file.getName());
            showFileActions(sf, "对话已导出");
        } catch (Exception e) {
            status.setText("导出失败：" + e.getMessage());
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

        int userIdx = history.size();
        history.add(new String[]{"user", fullText});
        addBubble("user", fullText, userIdx);
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
                    maybeExtractMemory(text, acc.toString());
                    recordUsage(conf, text, acc.toString());
                    maybeRunTools(acc.toString());
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

        // 文档检索：如果有 pendingFileName，检索相关片段并注入
        String docContext = "";
        if (pendingFileName != null && !pendingFileName.isEmpty()) {
            DocumentChunker chunker = new DocumentChunker(this);
            List<DocumentChunker.SearchResult> hits = chunker.searchAll(userText, 3);
            for (DocumentChunker.SearchResult r : hits) {
                docContext += "\n[相关片段][" + r.chunk.index + "] " + r.chunk.summary + "\n";
            }
            if (!docContext.isEmpty()) {
                docContext = "以下是附件文档中的相关内容（请优先参考）：" + docContext;
            }
        }
        JSONArray msgs = new JSONArray();
        JSONObject memMsg = MemoryManager.toSystemMessage(settings.memory());
        if (memMsg != null) msgs.put(memMsg);
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
            userMsg.put("content", (docContext == null || docContext.isEmpty()) ? userText : (userText + docContext));
        }
        msgs.put(userMsg);

        JSONObject body = new JSONObject();

        // 工具调用支持
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

        final Button btnThinking = new Button(this);
        btnThinking.setText(settings.globalThinking() ? "深度思考：开启" : "深度思考：关闭");
        btnThinking.setOnClickListener(v -> {
            boolean cur = settings.globalThinking();
            settings.put("g_thinking", cur ? "off" : "on");
            btnThinking.setText(cur ? "深度思考：关闭" : "深度思考：开启");
        });
        box.addView(btnThinking);

        final Button btnContext = new Button(this);
        btnContext.setText("上下文策略：" + ContextManager.strategyLabel(settings.contextStrategy()));
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
            btnContext.setText("上下文策略：" + ContextManager.strategyLabel(next));
        });
        box.addView(btnContext);

        final Button btnMem = new Button(this);
        btnMem.setText(settings.memoryOn() ? "自动记忆：开启" : "自动记忆：关闭");
        btnMem.setOnClickListener(v -> {
            boolean cur = settings.memoryOn();
            settings.saveMemoryOn(!cur);
            btnMem.setText(!cur ? "自动记忆：开启" : "自动记忆：关闭");
        });
        box.addView(btnMem);

        Button btnVision = new Button(this);
        btnVision.setText("配置视觉模型");
        btnVision.setOnClickListener(v -> showVisionConfig());
        box.addView(btnVision);

        // 搜索配置
        final EditText etSearchEngine = new EditText(this);
        etSearchEngine.setHint("ddg/brave/serper");
        etSearchEngine.setText(settings.searchEngine());
        box.addView(etSearchEngine);

        final EditText etSearchKey = new EditText(this);
        etSearchKey.setHint("搜索 API Key（可留空）");
        etSearchKey.setText(settings.searchApiKey());
        box.addView(etSearchKey);

        new AlertDialog.Builder(this)
                .setTitle("全局设置")
                .setView(box)
                .setPositiveButton("保存全局", (d, w) -> settings.saveGlobal(
                        etType.getText().toString().trim().toUpperCase(),
                        etHost.getText().toString().trim(),
                        etKey.getText().toString().trim(),
                        etModel.getText().toString().trim(),
                        settings.globalThinking()))
                .setNeutralButton("工具配置", (d, w) -> showToolConfig())
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

        final Button btnThinking = new Button(this);
        btnThinking.setText("深度思考：关闭");
        btnThinking.setOnClickListener(v -> {
            boolean cur = btnThinking.getText().toString().contains("开启");
            btnThinking.setText(cur ? "深度思考：关闭" : "深度思考：开启");
        });
        box.addView(btnThinking);

        new AlertDialog.Builder(this)
                .setTitle("新增模型预设")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    String n = etName.getText().toString().trim();
                    if (n.isEmpty()) return;
                    boolean thinking = btnThinking.getText().toString().contains("开启");
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
                .setNegativeButton("取消", null)
                .show();
    }

    private void showSkills() {
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

    private void showApiRecords() {
        if (!ApiKeyStore.hasPassword(this)) {
            final EditText et = new EditText(this);
            et.setHint("设置锁定密码");
            new AlertDialog.Builder(this)
                    .setTitle("设置 API 记录密码")
                    .setMessage("首次使用请设置密码，用于加密保存 API Key。")
                    .setView(et)
                    .setPositiveButton("确定", (d, w) -> {
                        String p = et.getText().toString().trim();
                        if (p.isEmpty()) { status.setText("密码不能为空"); return; }
                        ApiKeyStore.setPassword(this, p);
                        status.setText("密码已设置");
                        showApiRecords();
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        final EditText et = new EditText(this);
        et.setHint("输入锁定密码");
        new AlertDialog.Builder(this)
                .setTitle("解锁 API 记录")
                .setView(et)
                .setPositiveButton("解锁", (d, w) -> {
                    String p = et.getText().toString().trim();
                    if (!ApiKeyStore.verifyPassword(this, p)) {
                        status.setText("密码错误");
                        return;
                    }
                    showApiList(p);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showApiList(final String pwd) {
        List<String[]> records;
        try {
            records = ApiKeyStore.list(this, pwd);
        } catch (Exception e) {
            status.setText("读取失败：" + e.getMessage());
            return;
        }
        String[] items = new String[records.size() + 1];
        items[0] = "＋ 新增 API 记录";
        for (int i = 0; i < records.size(); i++) {
            items[i + 1] = records.get(i)[1] + "  |  " + records.get(i)[2];
        }
        new AlertDialog.Builder(this)
                .setTitle("API 记录（点击用于当前会话）")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        showAddApi(pwd);
                    } else {
                        String[] r = records.get(which - 1);
                        applyApiRecord(r[2], r[3], r[4]);
                    }
                })
                .setNeutralButton("修改密码", (d, w) -> changeApiPassword())
                .setNegativeButton("关闭", null)
                .show();
    }

    private void applyApiRecord(String host, String key, String model) {
        settings.saveGlobal(settings.globalType(), host, key, model, settings.globalThinking());
        status.setText("已应用：" + host + " / " + model);
    }

    private void showAddApi(final String pwd) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));

        final EditText etName = new EditText(this);
        etName.setHint("名称，如 Claude 官方");
        box.addView(etName);

        final EditText etHost = new EditText(this);
        etHost.setHint("API 主机，如 https://api.anthropic.com");
        box.addView(etHost);

        final EditText etKey = new EditText(this);
        etKey.setHint("API Key");
        box.addView(etKey);

        final EditText etModel = new EditText(this);
        etModel.setHint("模型 ID，可留空");
        box.addView(etModel);

        new AlertDialog.Builder(this)
                .setTitle("新增 API 记录")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    String name = etName.getText().toString().trim();
                    String host = etHost.getText().toString().trim();
                    String key = etKey.getText().toString().trim();
                    String model = etModel.getText().toString().trim();
                    if (name.isEmpty() || host.isEmpty() || key.isEmpty()) {
                        status.setText("名称/主机/Key 必填");
                        return;
                    }
                    try {
                        ApiKeyStore.save(this, pwd, "", name, host, key, model);
                        status.setText("已保存");
                        showApiList(pwd);
                    } catch (Exception e) {
                        status.setText("保存失败：" + e.getMessage());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void changeApiPassword() {
        final EditText et = new EditText(this);
        et.setHint("输入新密码");
        new AlertDialog.Builder(this)
                .setTitle("修改密码")
                .setMessage("注意：修改密码后原有记录将无法解密，请先备份。")
                .setView(et)
                .setPositiveButton("确定", (d, w) -> {
                    String p = et.getText().toString().trim();
                    if (p.isEmpty()) return;
                    ApiKeyStore.setPassword(this, p);
                    status.setText("密码已修改");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void maybeExtractMemory(String userText, String replyText) {
        if (!settings.memoryOn()) return;
        String[] conf = resolveConfig();
        String dialogue = "用户：" + userText + "\n助手：" + replyText;
        MemoryManager.extract(conf[0], conf[1], conf[2], conf[3], dialogue,
                settings.memory(), new MemoryManager.Callback() {
                    @Override
                    public void onDone(final String mem) {
                        ui.post(() -> {
                            settings.saveMemory(mem);
                            status.setText("已更新记忆");
                        });
                    }
                    @Override
                    public void onError(final String msg) {
                        // 静默失败，不打断用户
                    }
                });
    }

    private void showMemory() {
        String mem = settings.memory();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));

        final EditText et = new EditText(this);
        et.setHint("长期记忆内容");
        et.setLines(8);
        et.setText(mem == null ? "" : mem);
        box.addView(et);

        new AlertDialog.Builder(this)
                .setTitle("长期记忆")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    settings.saveMemory(et.getText().toString().trim());
                    status.setText("记忆已保存");
                })
                .setNeutralButton("清空", (d, w) -> {
                    settings.clearMemory();
                    status.setText("记忆已清空");
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    // ========== 视觉理解 ==========

    /**
     * 判断模型是否原生支持视觉
     * DeepSeek 系列（deepseek-chat / deepseek-reasoner / deepseek-flash）不支持
     */
    private boolean modelSupportsVision(String type, String model) {
        String m = model == null ? "" : model.toLowerCase();
        if (m.contains("vl") || m.contains("vision") || m.contains("visual")) return true;
        if (m.startsWith("gpt-4") || m.equals("gpt-4o") || m.startsWith("gpt-4o")) return true;
        if (m.contains("claude") || m.contains("gemini")) return true;
        if (m.contains("qwen-vl") || m.contains("glm-4v") || m.contains("internvl")) return true;
        if (m.contains("deepseek-chat") || m.contains("deepseek-reasoner")
                || m.contains("deepseek-flash") || m.contains("deepseek-v3")) return false;
        // 其它默认尝试
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
     * 图片理解：主模型支持就直接用，否则用视觉模型
     */
    private void analyzeImages() {
        loadVisionModel();
        if (pendingImages.isEmpty()) {
            status.setText("请先选择图片");
            return;
        }
        String[] conf = resolveConfig();
        String type = conf[0];
        String model = conf[3];

        if (modelSupportsVision(type, model)) {
            // 主模型支持，直接把图片作为问题发送
            input.setText("请详细描述这张图片的内容");
            status.setText("主模型支持视觉，请直接发送");
            return;
        }

        if (!visionModel.isConfigured()) {
            status.setText("当前模型不支持视觉，请先在设置里配置视觉模型");
            return;
        }

        final String prompt = input.getText().toString().trim().isEmpty()
                ? "请详细描述这张图片的内容"
                : input.getText().toString().trim();
        status.setText("视觉模型分析中…");

        new Thread(() -> {
            try {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < pendingImages.size(); i++) {
                    String result = visionModel.analyze(prompt, pendingImages.get(i));
                    sb.append("【图片 ").append(i + 1).append("】").append(result).append("\n");



                }
                final String out = sb.toString().trim();
                ui.post(() -> {
                    addBubble("assistant", "[视觉理解 " + visionModel.name + "]" + out);

                    store.saveMessage(currentConvId, "assistant", "[视觉理解]" + out);

                    status.setText("图片分析完成");
                });
            } catch (Exception e) {
                ui.post(() -> status.setText("分析失败：" + e.getMessage()));
            }
        }).start();
    }

    private void showVisionConfig() {
        loadVisionModel();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));

        final EditText etName = new EditText(this);
        etName.setHint("名称，如 DeepSeek-VL");
        etName.setText(visionModel.name);
        box.addView(etName);

        final EditText etType = new EditText(this);
        etType.setHint("协议 OPENAI / ANTHROPIC / GEMINI");
        etType.setText(visionModel.type);
        box.addView(etType);

        final EditText etHost = new EditText(this);
        etHost.setHint("API 主机");
        etHost.setText(visionModel.host);
        box.addView(etHost);

        final EditText etKey = new EditText(this);
        etKey.setHint("API Key");
        etKey.setText(visionModel.key);
        box.addView(etKey);

        final EditText etModel = new EditText(this);
        etModel.setHint("模型 ID，如 deepseek-vl2");
        etModel.setText(visionModel.model);
        box.addView(etModel);

        new AlertDialog.Builder(this)
                .setTitle("视觉模型配置")
                .setMessage("主模型不支持视觉时，用它来分析图片。")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
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
                        status.setText("视觉模型已保存");
                    } catch (Exception e) {
                        status.setText("保存失败：" + e.getMessage());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ========== 工具配置 ==========

    private void showToolConfig() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));

        final Button btnAddMcp = new Button(this);
        btnAddMcp.setText("添加 MCP 服务器");
        btnAddMcp.setOnClickListener(v -> showMcpConfig());
        box.addView(btnAddMcp);

        // 列出已注册工具
        StringBuilder sb = new StringBuilder();
        sb.append("内置工具：web_search, calculator\n");
        TextView tvInfo = new TextView(this);
        tvInfo.setText("工具注册表已初始化，内置：web_search, calculator\nMCP 服务器需单独添加");
        tvInfo.setTextSize(14);
        box.addView(tvInfo);

        new AlertDialog.Builder(this)
                .setTitle("工具管理")
                .setView(box)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void showMcpConfig() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));

        final EditText etName = new EditText(this);
        etName.setHint("服务器名称，如 Brave Search");
        box.addView(etName);

        final EditText etUrl = new EditText(this);
        etUrl.setHint("MCP 服务器 HTTP 地址，如 https://mcp.example.com/rpc");
        box.addView(etUrl);

        new AlertDialog.Builder(this)
                .setTitle("添加 MCP 服务器")
                .setView(box)
                .setPositiveButton("连接", (d, w) -> {
                    String name = etName.getText().toString().trim();
                    String url = etUrl.getText().toString().trim();
                    if (name.isEmpty() || url.isEmpty()) return;
                    new Thread(() -> {
                        try {
                            McpClient client = new McpClient(url, name);
                            client.connect();
                            toolRegistry.registerMcpServer(client);
                            ui.post(() -> status.setText("MCP 已连接：" + name + " (" + client.tools.size() + " 工具)"));
                        } catch (Exception e) {
                            ui.post(() -> status.setText("MCP 连接失败：" + e.getMessage()));
                        }
                    }).start();
                })
                .setNegativeButton("取消", null)
                .show();
    }


    private void maybeRunTools(String aiResponse) {
        if (!toolRegistry.hasTools()) return;
        // 检测 AI 响应是否包含工具调用请求（简化：检测 JSON 格式的 tool_calls）
        try {
            // OpenAI 格式：{"tool_calls": [...]}
            JSONObject root = new JSONObject(aiResponse);
            if (root.has("tool_calls")) {
                JSONArray calls = root.getJSONArray("tool_calls");
                for (int i = 0; i < calls.length(); i++) {
                    JSONObject call = calls.getJSONObject(i);
                    String name = call.optString("name", "");
                    JSONObject args = call.optJSONObject("arguments");
                    if (args == null) args = new JSONObject();
                    String result = toolRegistry.execute(name, args);
                    // 注入工具结果作为 system 消息，继续对话
                    runToolResult(result);
                }
            }
        } catch (Exception ignored) {
            // 不是纯 JSON，可能是文本包含工具调用，跳过
        }
    }

    private void runToolResult(String toolResult) {
        // 将工具结果注入对话
        String toolContext = "[工具结果]\n" + toolResult;
        store.saveMessage(currentConvId, "user", toolContext);
        history.add(new String[]{"user", toolContext});
        status.setText("工具执行完成");
    }

    // ========== Token / 费用统计 ==========

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
        tv.setText("累计 token：" + tokens + "\n"
                + "累计费用：" + TokenCounter.formatCost(cost) + "\n"
                + "对话轮次：" + turns);
        tv.setTextSize(16);
        box.addView(tv);

        Button btnLive = new Button(this);
        btnLive.setText("更新实时价格（在线）");
        btnLive.setOnClickListener(v -> fetchLivePrices());
        box.addView(btnLive);

        Button btnPrice = new Button(this);
        btnPrice.setText("查看/编辑价格表");
        btnPrice.setOnClickListener(v -> showPriceEditor());
        box.addView(btnPrice);

        new AlertDialog.Builder(this)
                .setTitle("用量统计")
                .setView(box)
                .setPositiveButton("清空统计", (d, w) -> {
                    settings.clearUsage();
                    status.setText("统计已清空");
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void fetchLivePrices() {
        status.setText("正在获取在线价格…");
        LivePriceProvider.fetchAndMerge(settings, new LivePriceProvider.Callback() {
            @Override
            public void onDone(final int count, final String source) {
                ui.post(() -> status.setText("已更新 " + count + " 个模型价格（" + source + "）"));
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
        et.setHint("模型名,输入价,输出价（每百万token元）\n每行一条");
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
                .setTitle("价格表（元/百万 token）")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    savePriceText(et.getText().toString());
                })
                .setNeutralButton("恢复默认", (d, w) -> {
                    settings.savePrices(TokenCounter.defaultPricesJson());
                    status.setText("已恢复默认价格表");
                })
                .setNegativeButton("取消", null)
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
        status.setText("价格表已保存");
    }

    // ========== 文件交付：保存 / 打开 / 分享 ==========

    private void saveBubbleToFile(TextView tv) {
        CharSequence cs = tv.getText();
        String text = cs == null ? "" : cs.toString();
        if (text.trim().isEmpty()) {
            status.setText("没有可保存的内容");
            return;
        }

        String code = extractCodeBlock(text);
        boolean hasCode = code != null && !code.isEmpty();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));

        final EditText etName = new EditText(this);
        etName.setHint("文件名，如 报告");
        etName.setText(hasCode ? "output" : "note");
        box.addView(etName);

        final String[] kinds = hasCode
                ? new String[]{"HTML", "TXT", "MD", "CSV", "JSON"}
                : new String[]{"TXT", "MD", "HTML"};
        final java.util.List<Integer> sel = new java.util.ArrayList<Integer>();
        sel.add(0);

        new AlertDialog.Builder(this)
                .setTitle("保存内容")
                .setView(box)
                .setSingleChoiceItems(kinds, 0, (d, w) -> sel.set(0, w))
                .setPositiveButton("保存", (d, w) -> {
                    String name = etName.getText().toString().trim();
                    int idx = sel.get(0);
                    String kindName = kinds[idx];
                    FileDelivery.Kind kind = kindOf(kindName);
                    String content = hasCode ? code : text;
                    try {
                        FileDelivery.SavedFile sf = FileDelivery.save(this, name, content, kind);
                        status.setText("已保存：" + sf.file.getName());
                        showFileActions(sf, hasCode ? "代码已保存" : "内容已保存");
                    } catch (Exception e) {
                        status.setText("保存失败：" + e.getMessage());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showFileActions(final FileDelivery.SavedFile sf, String title) {
        new AlertDialog.Builder(this)
                .setTitle(title + "：" + sf.file.getName())
                .setItems(new String[]{"打开", "分享", "仅保存"}, (d, which) -> {
                    if (which == 0) {
                        FileDelivery.open(this, sf);
                    } else if (which == 1) {
                        FileDelivery.share(this, sf, sf.file.getName());
                    } else {
                        status.setText("已保存");
                    }
                })
                .setNegativeButton("关闭", null)
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
