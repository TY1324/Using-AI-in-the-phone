package com.example.aichat;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

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
        final Uri uri = data.getData();
        if (uri == null) {
            cb.onError("无效的文件");
            return;
        }
        final String name = resolveName(a, uri);

        new Thread(() -> {
            try {
                FileParser.Result r = FileParser.parse(a, uri.toString(), name);
                if ("error".equals(r.type)) {
                    a.runOnUiThread(() -> cb.onError(r.text));
                    return;
                }
                String text = r.text == null ? "" : r.text;
                a.runOnUiThread(() -> cb.onText(name, text));
            } catch (Exception e) {
                a.runOnUiThread(() -> cb.onError("解析失败：" + e.getMessage()));
            }
        }).start();
    }

    private static String resolveName(Activity a, Uri uri) {
        String name = "文件";
        try {
            android.database.Cursor c = a.getContentResolver().query(
                    uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                name = c.getString(0);
                c.close();
            }
        } catch (Exception ignored) {
        }
        if (name == null || name.isEmpty()) {
            String last = uri.getLastPathSegment();
            if (last != null) name = last;
        }
        return name;
    }
}
