package com.example.aichat;

import android.content.Context;
import android.net.Uri;

import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class FileParser {

    private static final String[] TEXT_EXTS = {
            "txt", "md", "markdown", "log", "csv", "json", "xml",
            "html", "htm", "java", "kt", "py", "js", "ts", "c", "cpp", "h",
            "css", "sh", "yml", "yaml", "toml", "ini", "cfg"
    };

    public static class Result {
        public String text = "";
        public int pages = 0;
        public int words = 0;
        public String type = "";
        public String encoding = "";
    }

    public static boolean isTextLike(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        int dot = lower.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = lower.substring(dot + 1);
        for (String t : TEXT_EXTS) {
            if (t.equals(ext)) return true;
        }
        return false;
    }

    public static Result parse(Context ctx, String content, String fileName) {
        Result r = new Result();
        try {
            if (content == null || content.isEmpty()) {
                r.type = "empty";
                return r;
            }
            if (fileName == null) fileName = "";
            String lower = fileName.toLowerCase();

            if (lower.endsWith(".pdf")) {
                r.type = "pdf";
                r.text = parsePdf(ctx, content);
                r.words = countWords(r.text);
                return r;
            }
            if (lower.endsWith(".docx")) {
                r.type = "docx";
                r.text = parseDocx(ctx, content);
                r.words = countWords(r.text);
                return r;
            }
            if (lower.endsWith(".xlsx")) {
                r.type = "xlsx";
                r.text = parseXlsx(ctx, content);
                r.words = countWords(r.text);
                return r;
            }
            if (lower.endsWith(".pptx")) {
                r.type = "pptx";
                r.text = parsePptx(ctx, content);
                r.words = countWords(r.text);
                return r;
            }
            if (lower.endsWith(".epub")) {
                r.type = "epub";
                r.text = parseEpub(ctx, content);
                r.words = countWords(r.text);
                return r;
            }
            if (lower.endsWith(".doc") || lower.endsWith(".xls") || lower.endsWith(".ppt")) {
                r.type = "legacy";
                r.text = parseText(ctx, content);
                r.words = countWords(r.text);
                return r;
            }

            r.type = "text";
            r.text = parseText(ctx, content);
            r.encoding = detectEncoding(ctx, content);
            r.words = countWords(r.text);
            return r;
        } catch (Exception e) {
            r.type = "error";
            r.text = "解析失败：" + e.getMessage();
            return r;
        }
    }

    // ========== 公开方法（供 LargeFileHandler 调用） ==========

    public static String parseText(Context ctx, String content) throws Exception {
        byte[] bytes = readAllBytes(ctx, content);
        if (bytes == null || bytes.length == 0) return "";

        String text = decode(bytes, StandardCharsets.UTF_8);
        if (isGarbled(text)) {
            text = decode(bytes, Charset.forName("GB18030"));
        }
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        return text;
    }

    public static String parseDocx(Context ctx, String content) throws Exception {
        InputStream in = openStream(ctx, content);
        if (in == null) return "";
        ZipInputStream zis = new ZipInputStream(in);
        ZipEntry entry;
        StringBuilder text = new StringBuilder();
        while ((entry = zis.getNextEntry()) != null) {
            if ("word/document.xml".equals(entry.getName())) {
                text.append(extractXmlText(zis));
            }
        }
        zis.close();
        return text.toString();
    }

    public static String parseXlsx(Context ctx, String content) throws Exception {
        InputStream in = openStream(ctx, content);
        if (in == null) return "";
        ZipInputStream zis = new ZipInputStream(in);
        ZipEntry entry;
        StringBuilder text = new StringBuilder();
        while ((entry = zis.getNextEntry()) != null) {
            String n = entry.getName();
            if (n.startsWith("xl/sharedStrings.xml") || n.matches("xl/worksheets/sheet\\d+\\.xml")) {
                text.append(extractXmlText(zis)).append('\n');
            }
        }
        zis.close();
        return text.toString();
    }

    public static String parsePptx(Context ctx, String content) throws Exception {
        InputStream in = openStream(ctx, content);
        if (in == null) return "";
        ZipInputStream zis = new ZipInputStream(in);
        ZipEntry entry;
        StringBuilder text = new StringBuilder();
        int slide = 0;
        while ((entry = zis.getNextEntry()) != null) {
            if (entry.getName().matches("ppt/slides/slide\\d+\\.xml")) {
                slide++;
                text.append("--- 幻灯片 ").append(slide).append(" ---\n");
                text.append(extractXmlText(zis)).append('\n');
            }
        }
        zis.close();
        return text.toString();
    }

    public static String parseEpub(Context ctx, String content) throws Exception {
        InputStream in = openStream(ctx, content);
        if (in == null) return "";
        ZipInputStream zis = new ZipInputStream(in);
        ZipEntry entry;
        StringBuilder text = new StringBuilder();
        while ((entry = zis.getNextEntry()) != null) {
            String n = entry.getName();
            if (n.endsWith(".xhtml") || n.endsWith(".html") || n.endsWith(".htm")) {
                text.append(extractXmlText(zis)).append('\n');
            }
        }
        zis.close();
        return stripHtml(text.toString());
    }

    public static String parsePdf(Context ctx, String content) throws Exception {
        byte[] bytes = readAllBytes(ctx, content);
        if (bytes == null) return "";
        String raw = new String(bytes, StandardCharsets.ISO_8859_1);
        StringBuilder result = new StringBuilder();
        int idx = 0;
        while ((idx = raw.indexOf("BT", idx)) != -1) {
            int end = raw.indexOf("ET", idx);
            if (end == -1) break;
            String block = raw.substring(idx, end);
            java.util.regex.Pattern p1 = java.util.regex.Pattern.compile("\\((.*?)\\)\\s*Tj");
            java.util.regex.Matcher m1 = p1.matcher(block);
            while (m1.find()) result.append(m1.group(1));
            java.util.regex.Pattern p2 = java.util.regex.Pattern.compile("\\[(.*?)\\]\\s*TJ");
            java.util.regex.Matcher m2 = p2.matcher(block);
            while (m2.find()) {
                java.util.regex.Pattern p3 = java.util.regex.Pattern.compile("\\((.*?)\\)");
                java.util.regex.Matcher m3 = p3.matcher(m2.group(1));
                while (m3.find()) result.append(m3.group(1));
            }
            idx = end + 2;
        }
        if (result.toString().trim().isEmpty()) {
            return "（PDF 为扫描件或无嵌入文本，需 OCR）";
        }
        return result.toString();
    }

    public static String parsePdfLite(Context ctx, String content) throws Exception {
        return parsePdf(ctx, content);
    }

    // ========== 内部工具 ==========

    private static String detectEncoding(Context ctx, String content) {
        try {
            byte[] bytes = readAllBytes(ctx, content);
            if (bytes == null) return "unknown";
            String utf8 = decode(bytes, StandardCharsets.UTF_8);
            if (!isGarbled(utf8)) return "UTF-8";
            return "GB18030(GBK/GB2312)";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static byte[] readAllBytes(Context ctx, String content) throws Exception {
        InputStream in = openStream(ctx, content);
        if (in == null) return null;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) baos.write(buf, 0, n);
        in.close();
        return baos.toByteArray();
    }

    private static String decode(byte[] bytes, Charset cs) {
        return new String(bytes, cs);
    }

    private static boolean isGarbled(String text) {
        if (text == null || text.isEmpty()) return false;
        int bad = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\uFFFD') bad++;
        }
        return bad > text.length() / 50;
    }

    private static String extractXmlText(InputStream in) throws Exception {
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        factory.setNamespaceAware(true);
        XmlPullParser parser = factory.newPullParser();
        parser.setInput(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        int eventType = parser.getEventType();
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && "p".equals(parser.getName())) {
                sb.append('\n');
            } else if (eventType == XmlPullParser.TEXT) {
                sb.append(parser.getText());
            }
            eventType = parser.next();
        }
        return sb.toString();
    }

    private static String stripHtml(String html) {
        return html.replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static InputStream openStream(Context ctx, String content) throws Exception {
        if (content == null) return null;
        if (content.startsWith("/")) {
            return new FileInputStream(new File(content));
        }
        try {
            return ctx.getContentResolver().openInputStream(Uri.parse(content));
        } catch (Exception e) {
            return null;
        }
    }

    public static int countWords(String text) {
        if (text == null || text.isEmpty()) return 0;
        int count = 0;
        boolean inWord = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\n' || c == '\t' || c == '\r') {
                if (inWord) { count++; inWord = false; }
            } else {
                inWord = true;
            }
        }
        if (inWord) count++;
        return count;
    }
}
