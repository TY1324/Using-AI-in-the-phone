package com.example.aichat;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class FileDelivery {

    public enum Kind {
        TXT("txt", "text/plain"),
        HTML("html", "text/html"),
        CSV("csv", "text/csv"),
        MD("md", "text/markdown"),
        JSON("json", "application/json");

        public final String ext;
        public final String mime;

        Kind(String ext, String mime) {
            this.ext = ext;
            this.mime = mime;
        }
    }

    public static class SavedFile {
        public File file;
        public Kind kind;

        SavedFile(File f, Kind k) {
            file = f;
            kind = k;
        }
    }

    public static SavedFile save(Context ctx, String fileName, String content, Kind kind) throws Exception {
        File dir = new File(ctx.getFilesDir(), "exports");
        if (!dir.exists()) dir.mkdirs();

        String safeName = sanitize(fileName);
        if (safeName.isEmpty()) safeName = "export";
        if (!safeName.toLowerCase().endsWith("." + kind.ext)) {
            safeName = safeName + "." + kind.ext;
        }

        File out = new File(dir, safeName);
        OutputStream os = new FileOutputStream(out);
        os.write(content.getBytes(StandardCharsets.UTF_8));
        os.flush();
        os.close();
        return new SavedFile(out, kind);
    }

    /** 分享：把内容作为文本发送 */
    public static void share(Context ctx, SavedFile sf, String title) {
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, readContent(sf.file));
            i.putExtra(Intent.EXTRA_SUBJECT, title == null ? sf.file.getName() : title);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Intent chooser = Intent.createChooser(i, "分享内容");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(chooser);
        } catch (Exception ignored) {
        }
    }

    /** 打开：交给系统应用 */
    public static void open(Context ctx, SavedFile sf) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.fromFile(sf.file), sf.kind.mime);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception ignored) {
        }
    }

    public static String readContent(File f) {
        try {
            FileInputStream fis = new FileInputStream(f);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) baos.write(buf, 0, n);
            fis.close();
            return new String(baos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static String sanitize(String name) {
        if (name == null) return "";
        return name.replaceAll("[\\\\/:*?\"<>|\\s]+", "_").trim();
    }
}
