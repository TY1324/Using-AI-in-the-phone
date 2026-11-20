package com.example.aichat;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

public class FileHelper {

    public static final int PICK_FILE = 2001;

    public interface Callback {
        void onText(String name, String text);
        void onError(String msg);
    }

    public static void pick(Activity a) {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        a.startActivityForResult(i, PICK_FILE);
    }

    public static void handle(Activity a, int resultCode, Intent data, Callback cb) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            cb.onError("未选择文件");
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            cb.onError("文件无效");
            return;
        }
        String name = "file";
        try {
            String n = uri.getLastPathSegment();
            if (n != null) name = n;
        } catch (Exception ignored) {
        }
        final String finalName = name;
        new Thread(() -> {
            try {
                String text = FileParser.parse(a, uri, finalName);
                cb.onText(finalName, text);
            } catch (Exception e) {
                cb.onError("解析失败：" + e.getMessage());
            }
        }).start();
    }
}
