package com.example.aichat;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private LinearLayout logContainer;
    private EditText filterInput;
    private final List<String> logs = new ArrayList<>();
    private boolean isMonitoring = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 请求权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.READ_LOGS}, 1);
            }
        }

        // 布局
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#FAFAFA"));

        // 顶部工具栏
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setPadding(24, 24, 24, 8);

        TextView title = new TextView(this);
        title.setText("崩溃捕手 CrashCatcher");
        title.setTextSize(20);
        title.setTextColor(Color.parseColor("#1A1A1A"));
        title.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        toolbar.addView(title, titleParams);

        Button clearBtn = new Button(this);
        clearBtn.setText("清空");
        clearBtn.setTextColor(Color.WHITE);
        clearBtn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#EF4444")));
        clearBtn.setOnClickListener(v -> clearLogs());
        toolbar.addView(clearBtn);

        root.addView(toolbar);

        // 过滤输入框
        filterInput = new EditText(this);
        filterInput.setHint("输入过滤关键词...");
        filterInput.setTextColor(Color.parseColor("#1A1A1A"));
        filterInput.setHintTextColor(Color.parseColor("#9E9E9E"));
        filterInput.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#E5E7EB")));
        LinearLayout.LayoutParams filterParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        filterParams.setMargins(24, 8, 24, 8);
        root.addView(filterInput, filterParams);

        // 日志滚动区域
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(Color.WHITE);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        logContainer = new LinearLayout(this);
        logContainer.setOrientation(LinearLayout.VERTICAL);
        logContainer.setPadding(16, 16, 16, 16);
        scrollView.addView(logContainer);

        root.addView(scrollView);

        // 底部控制栏
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setPadding(24, 16, 24, 24);

        Button startBtn = new Button(this);
        startBtn.setText("开始监控");
        startBtn.setTextColor(Color.WHITE);
        startBtn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#10B981")));
        LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        startBtn.setOnClickListener(v -> toggleMonitoring());
        controls.addView(startBtn, startParams);

        Button exportBtn = new Button(this);
        exportBtn.setText("导出");
        exportBtn.setTextColor(Color.WHITE);
        exportBtn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#3B82F6")));
        LinearLayout.LayoutParams exportParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        exportParams.setMargins(16, 0, 0, 0);
        exportBtn.setOnClickListener(v -> exportLogs());
        controls.addView(exportBtn, exportParams);

        root.addView(controls);

        setContentView(root);
    }

    private void toggleMonitoring() {
        isMonitoring = !isMonitoring;
        if (isMonitoring) {
            startMonitoring();
            Toast.makeText(this, "开始监控...", Toast.LENGTH_SHORT).show();
        } else {
            stopMonitoring();
            Toast.makeText(this, "停止监控", Toast.LENGTH_SHORT).show();
        }
    }

    private void startMonitoring() {
        // 这里启动 logcat 实时读取
        new Thread(() -> {
            try {
                Process process = Runtime.getRuntime().exec("logcat -v time");
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

                String line;
                while ((line = reader.readLine()) != null) {
                    final String logLine = line;
                    runOnUiThread(() -> {
                        if (isMonitoring) {
                            logs.add(logLine);
                            if (logs.size() > 500) logs.remove(0);
                            updateLogView();
                        }
                    });
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void stopMonitoring() {
        // 停止逻辑
    }

    private void clearLogs() {
        logs.clear();
        logContainer.removeAllViews();
        Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show();
    }

    private void exportLogs() {
        StringBuilder sb = new StringBuilder();
        for (String log : logs) sb.append(log).append("\n");

        File exportDir = new File(getExternalFilesDir(null), "exports");
        if (!exportDir.exists()) exportDir.mkdirs();
        File exportFile = new File(exportDir, "crash_log.txt");

        try (java.io.FileWriter writer = new java.io.FileWriter(exportFile)) {
            writer.write(sb.toString());
            Toast.makeText(this, "导出到: " + exportFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void updateLogView() {
        logContainer.removeAllViews();
        String filter = filterInput.getText().toString().trim();

        for (String log : logs) {
            if (!filter.isEmpty() && !log.contains(filter)) continue;
            TextView tv = new TextView(this);
            tv.setText(log);
            tv.setTextSize(12);
            tv.setTextColor(Color.parseColor("#1A1A1A"));
            tv.setPadding(8, 4, 8, 4);
            logContainer.addView(tv);
        }
    }
}
