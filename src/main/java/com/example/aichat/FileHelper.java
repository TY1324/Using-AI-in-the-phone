package com.example.aichat;

import android.content.Context;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class FileHelper {

    public static final int PICK_FILE = 1002;

    public interface Callback {
        void onText(String name, String text);
        void onError(String msg);
    }

    public static void pickFile(Context context) {
        android.content.Intent i = new android.content.Intent(
                android.content.Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        ((android.app.Activity) context).startActivityForResult(i, PICK_FILE);
    }

    public static void handleResult(Context context, int resultCode,
                                    android.content.Intent data, Callback cb) {
        if (resultCode != android.app.Activity.RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) {
            cb.onError("无法读取文件");
            return;
        }
        String name = resolveName(context, uri);
        new Thread(() -> {
            try {
                String text = readText(context, uri);
                if (text.length() > 4000) text = text.substring(0, 4000);
                ((android.os.Handler) null);
                cb.onText(name, text);
            } catch (Exception e) {
                cb.onError("读取失败：" + e.getMessage());
            }
        }).start();
    }

    private static String resolveName(Context context, Uri uri) {
        try {
            android.content.ContentResolver cr = context.getContentResolver();
            android.database.Cursor c = cr.query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                c.close();
                return n;
            }
            if (c != null) c.close();
        } catch (Exception ignored) {
        }
        return "文件";
    }

    private static String readText(Context context, Uri uri) throws Exception {
        android.content.ContentResolver cr = context.getContentResolver();
        InputStream in = cr.openInputStream(uri);
        if (in == null) throw new Exception("无法打开");
        BufferedReader br = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line).append("\n");
        }
        br.close();
        in.close();
        return sb.toString();
    }
}
