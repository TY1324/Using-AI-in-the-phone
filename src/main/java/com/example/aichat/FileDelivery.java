package com.example.aichat;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.io.File;
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

    public static void open(Context ctx, SavedFile sf) {
        try {
            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    ctx, ctx.getPackageName() + ".fileprovider", sf.file);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, sf.kind.mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.fromFile(sf.file), sf.kind.mime);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        }
    }

    public static void share(Context ctx, SavedFile sf, String title) {
        try {
            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    ctx, ctx.getPackageName() + ".fileprovider", sf.file);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType(sf.kind.mime);
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.putExtra(Intent.EXTRA_SUBJECT, title == null ? sf.file.getName() : title);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(i, "分享文件");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(chooser);
        } catch (Exception e) {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType(sf.kind.mime);
            i.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(sf.file));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(Intent.createChooser(i, "分享文件"));
        }
    }

    private static String sanitize(String name) {
        if (name == null) return "";
        return name.replaceAll("[\\\\/:*?\"<>|\\s]+", "_").trim();
    }
}
