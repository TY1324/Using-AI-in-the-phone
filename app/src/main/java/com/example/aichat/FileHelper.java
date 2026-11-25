package com.example.aichat;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public class FileHelper {

    public static final int PICK_FILE = 2001;

    public static void pick(Activity activity) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        String[] mimeTypes = {
                "text/*", "application/json", "application/pdf",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "application/epub+zip"
        };
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        activity.startActivityForResult(intent, PICK_FILE);
    }

    public static void handle(Activity activity, int resultCode, Intent data, Callback callback) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            callback.onError("未选择文件");
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            callback.onError("无效的文件 URI");
            return;
        }
        new Thread(() -> {
            try {
                String text = readText(activity, uri);
                String name = getFileName(activity, uri);
                activity.runOnUiThread(() -> callback.onText(name, text));
            } catch (Exception e) {
                activity.runOnUiThread(() -> callback.onError("读取失败：" + e.getMessage()));
            }
        }).start();
    }

    private static String readText(Activity activity, Uri uri) throws Exception {
        InputStream in = activity.getContentResolver().openInputStream(uri);
        if (in == null) return "";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        in.close();
        byte[] bytes = baos.toByteArray();

        // 尝试 UTF-8
        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        int bad = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\uFFFD') bad++;
        }
        if (bad > text.length() / 50) {
            try {
                in = activity.getContentResolver().openInputStream(uri);
                text = readStream(in, java.nio.charset.Charset.forName("GBK"));
            } catch (Exception ignored) {
            }
        }
        return text;
    }

    private static String readStream(InputStream in, java.nio.charset.Charset cs) throws Exception {
        java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(in, cs));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line).append('\n');
        }
        br.close();
        return sb.toString();
    }

    private static String getFileName(Activity activity, Uri uri) {
        String name = "文件";
        try {
            android.database.Cursor cursor = activity.getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = cursor.getString(idx);
                cursor.close();
            }
        } catch (Exception ignored) {
        }
        return name;
    }

    public interface Callback {
        void onText(String name, String text);
        void onError(String msg);
    }
}
