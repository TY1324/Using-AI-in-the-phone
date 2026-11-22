package com.example.aichat;

import android.content.Context;
import android.graphics.BitmapFactory;

import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class FileParser {

    public static class Result {
        public String text = "";
        public int pages = 0;
        public int words = 0;
        public String type = "";
    }

    /**
     * 文本扩展名集合（含 Markdown）
     */
    private static final String[] TEXT_EXTS = {
            "txt", "md", "markdown", "log", "csv", "json", "xml",
            "html", "htm", "java", "kt", "py", "js", "ts", "c", "cpp", "h",
            "css", "sh", "yml", "yaml", "toml", "ini", "cfg"
    };

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

    /**
     * 主入口：根据文件名判断类型并解析。
     * content 可能为：文件路径字符串（旧接口）或 Uri（新接口）。
     */
    public static Result parse(Context ctx, String content, String fileName) {
        Result r = new Result();
        try {
            if (content == null || content.isEmpty()) {
                r.type = "empty";
                return r;
            }

            if (fileName == null) fileName = "";

            // 1. 图片：按 base64 / 文件路径 判断
            if (fileName.toLowerCase().endsWith(".jpg") || fileName.toLowerCase().endsWith(".jpeg")
                    || fileName.toLowerCase().endsWith(".png") || fileName.toLowerCase().endsWith(".gif")
                    || fileName.toLowerCase().endsWith(".bmp") || fileName.toLowerCase().endsWith(".webp")) {
                r.type = "image";
                r.text = "图片文件：" + fileName;
                return r;
            }

            // 2. PDF
            if (fileName.toLowerCase().endsWith(".pdf")) {
                r.type = "pdf";
                r.text = parsePdf(ctx, content);
                r.words = countWords(r.text);
                return r;
            }

            // 3. Office 现代格式
            if (fileName.toLowerCase().endsWith(".docx")) {
                r.type = "docx";
                r.text = parseDocx(ctx, content);
                r.words = countWords(r.text);
                return r;
            }

            if (fileName.toLowerCase().endsWith(".xlsx")) {
                r.type = "xlsx";
                r.text = parseXlsx(ctx, content);
                r.words = countWords(r.text);
                return r;
            }

            if (fileName.toLowerCase().endsWith(".pptx")) {
                r.type = "pptx";
                r.text = parsePptx(ctx, content);
                r.pages = countPages(r.text);
                r.words = countWords(r.text);
                return r;
            }

            // 4. 文本类（含 .txt / .md）
            if (isTextLike(fileName) || fileName.isEmpty()) {
                r.type = "text";
                r.text = parseText(ctx, content);
                r.words = countWords(r.text);
                return r;
            }

            // 5. EPUB
            if (fileName.toLowerCase().endsWith(".epub")) {
                r.type = "epub";
                r.text = parseEpub(ctx, content);
                r.words = countWords(r.text);
                return r;
            }

            // 默认按文本
            r.type = "text";
            r.text = parseText(ctx, content);
            r.words = countWords(r.text);
            return r;
        } catch (Exception e) {
            r.type = "error";
            r.text = "解析失败：" + e.getMessage();
            return r;
        }
    }

    // ========== 文本解析（UTF-8 + GBK 自动检测） ==========

    private static String parseText(Context ctx, String content) throws Exception {
        java.io.InputStream in = null;
        if (content.startsWith("/")) {
            in = new java.io.FileInputStream(new File(content));
        } else {
            try {
                in = ctx.getContentResolver().openInputStream(android.net.Uri.parse(content));
            } catch (Exception e) {
                // 可能是 base64 或纯文本
                return content;
            }
        }
        if (in == null) return "";

        // 1. 先尝试 UTF-8
        String text = readStream(in, StandardCharsets.UTF_8);

        // 2. 检测是否包含大量替换字符（乱码标志）
        int bad = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\uFFFD') bad++;
        }
        if (bad > text.length() / 50) {
            // 乱码较多，改用 GBK 重读
            try {
                in = ctx.getContentResolver().openInputStream(android.net.Uri.parse(content));
                if (in != null) {
                    text = readStream(in, java.nio.charset.Charset.forName("GBK"));
                }
            } catch (Exception ignored) {
            }
        }

        // 3. 清理 BOM
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }

        return text;
    }

    private static String readStream(InputStream in, java.nio.charset.Charset cs) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(in, cs));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line).append('\n');
        }
        br.close();
        return sb.toString();
    }

    // ========== PDF 解析（自实现，兼容老版 PdfBox-Android） ==========

    private static String parsePdf(Context ctx, String content) {
        try {
            return parsePdfLite(ctx, content);
        } catch (Exception e) {
            return "PDF 解析失败：" + e.getMessage();
        }
    }

    /**
     * 轻量 PDF 文本提取（仅用标准库，无需第三方）
     */
    public static String parsePdfLite(Context ctx, String content) throws Exception {
        InputStream in = openStream(ctx, content);
        if (in == null) return "";

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) baos.write(buf, 0, n);
        in.close();
        byte[] bytes = baos.toByteArray();

        // 简单 PDF 解析：提取 (text) Tj 和 [(text)] TJ
        StringBuilder result = new StringBuilder();
        String raw = new String(bytes, StandardCharsets.ISO_8859_1);

        // 提取 BT ... ET 块
        int idx = 0;
        while ((idx = raw.indexOf("BT", idx)) != -1) {
            int end = raw.indexOf("ET", idx);
            if (end == -1) break;
            String block = raw.substring(idx, end);
            // 匹配 (text) Tj 或 [(text)] TJ
            java.util.regex.Pattern p1 = java.util.regex.Pattern.compile("\\((.*?)\\)\\s*Tj");
            java.util.regex.Matcher m1 = p1.matcher(block);
            while (m1.find()) {
                result.append(m1.group(1));
            }
            java.util.regex.Pattern p2 = java.util.regex.Pattern.compile("\\[(.*?)\\]\\s*TJ");
            java.util.regex.Matcher m2 = p2.matcher(block);
            while (m2.find()) {
                String part = m2.group(1);
                java.util.regex.Pattern p3 = java.util.regex.Pattern.compile("\\((.*?)\\)");
                java.util.regex.Matcher m3 = p3.matcher(part);
                while (m3.find()) {
                    result.append(m3.group(1));
                }
            }
            idx = end + 2;
        }

        String text = result.toString();
        if (text.trim().isEmpty()) {
            return "（PDF 为扫描件或无嵌入文本，需 OCR）";
        }
        return text;
    }

    // ========== DOCX ==========

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

    // ========== XLSX ==========

    public static String parseXlsx(Context ctx, String content) throws Exception {
        InputStream in = openStream(ctx, content);
        if (in == null) return "";
        ZipInputStream zis = new ZipInputStream(in);
        ZipEntry entry;
        StringBuilder text = new StringBuilder();
        while ((entry = zis.getNextEntry()) != null) {
            if (entry.getName().startsWith("xl/sharedStrings.xml")) {
                text.append(extractXmlText(zis)).append('\n');
            } else if (entry.getName().matches("xl/worksheets/sheet\\d+\\.xml")) {
                text.append(extractXmlText(zis)).append('\n');
            }
        }
        zis.close();
        return text.toString();
    }

    // ========== PPTX ==========

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

    // ========== EPUB ==========

    private static String parseEpub(Context ctx, String content) throws Exception {
        InputStream in = openStream(ctx, content);
        if (in == null) return "";
        ZipInputStream zis = new ZipInputStream(in);
        ZipEntry entry;
        StringBuilder text = new StringBuilder();
        while ((entry = zis.getNextEntry()) != null) {
            String name = entry.getName();
            if (name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".htm")) {
                text.append(extractXmlText(zis)).append('\n');
            }
        }
        zis.close();
        return stripHtml(text.toString());
    }

    private static String stripHtml(String html) {
        return html.replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    // ========== 工具 ==========

    private static InputStream openStream(Context ctx, String content) throws Exception {
        if (content == null) return null;
        if (content.startsWith("/")) {
            return new java.io.FileInputStream(new File(content));
        }
        try {
            return ctx.getContentResolver().openInputStream(android.net.Uri.parse(content));
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

    private static int countPages(String text) {
        if (text == null) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf("--- 幻灯片", idx)) != -1) {
            count++;
            idx += 6;
        }
        return count;
    }
}