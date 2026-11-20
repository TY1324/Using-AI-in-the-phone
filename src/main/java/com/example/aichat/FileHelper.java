package com.example.aichat;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
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

    public static void pick(Context context) {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        ((Activity) context).startActivityForResult(i, PICK_FILE);
    }

    public static void handle(final Context context, int resultCode, Intent data, final Callback cb) {
        if (resultCode != Activity.RESULT_OK || data == null) return;
        final Uri uri = data.getData();
        if (uri == null) {
            cb.onError("无法读取文件");
            return;
        }
        final String name = resolveName(context, uri);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String text = readText(context, uri);
                    if (text.length() > 4000) text = text.substring(0, 4000);
                    cb.onText(name, text);
                } catch (Exception e) {
                    cb.onError("读取失败：" + e.getMessage());
                }
            }
        }).start();
    }

    private static String resolveName(Context context, Uri uri) {
        Cursor c = null;
        try {
            ContentResolver cr = context.getContentResolver();
            c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToNext()) {
                return c.getString(0);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return "文件";
    }

    private static String readText(Context context, Uri uri) throws Exception {
        ContentResolver cr = context.getContentResolver();
        InputStream in = cr.openInputStream(uri);
        if (in == null) throw new Exception("无法打开");
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
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
