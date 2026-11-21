package com.example.aichat;

import android.content.Context;
import android.net.Uri;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

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

public class LargeFileHandler {

    public static final int DEFAULT_CHUNK_SIZE = 1024 * 1024;
    public static final int MAX_FILE_SIZE = 600 * 1024 * 1024;

    public static class Chunk {
        public int index;
        public long startOffset;
        public int length;
        public String text;
        public String summary;
    }

    public static class ParseResult {
        public String fileName;
        public long fileSize;
        public String mimeType;
        public List<Chunk> chunks = new ArrayList<>();
        public String fullText;
        public int totalChunks;
        public String error;
    }

    public static boolean isLargeFile(long size) {
        return size > 1024 * 1024;
    }

    public static ParseResult parse(Context ctx, Uri uri, String fileName, long fileSize) {
        ParseResult result = new ParseResult();
        result.fileName = fileName;
        result.fileSize = fileSize;

        if (fileSize > MAX_FILE_SIZE) {
            result.error = "文件过大（>600MB），暂不支持";
            return result;
        }

        try {
            String mime = ctx.getContentResolver().getType(uri);
            result.mimeType = mime;

            String ext = getExtension(fileName).toLowerCase();
            String path = uri.toString();

            if (isTextBased(ext)) {
                parseLargeText(ctx, path, result);
            } else if ("pdf".equals(ext)) {
                parsePdf(ctx, path, result);
            } else if ("docx".equals(ext)) {
                parseDocx(ctx, path, result);
            } else if ("xlsx".equals(ext)) {
                parseXlsx(ctx, path, result);
            } else {
                String text = readAllText(ctx, path);
                result.fullText = text;
                result.chunks = chunkText(text, 0);
            }
            result.totalChunks = result.chunks.size();
        } catch (Exception e) {
            result.error = "解析失败：" + e.getMessage();
        }
        return result;
    }

    // ========== 各类解析（自实现，不依赖 FileParser） ==========

    private static void parseLargeText(Context ctx, String path, ParseResult result) throws Exception {
        InputStream in = openStream(ctx, path);
        if (in == null) return;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int read;
        while ((read = in.read(buf)) != -1) {
            baos.write(buf, 0, read);
        }
        in.close();
        byte[] bytes = baos.toByteArray();

        String text = new String(bytes, StandardCharsets.UTF_8);
        if (isGarbled(text)) {
            text = new String(bytes, Charset.forName("GB18030"));
        }
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }

        result.fullText = text;
        result.chunks = chunkText(text, 0);
    }

    private static void parseDocx(Context ctx, String path, ParseResult result) throws Exception {
        InputStream in = openStream(ctx, path);
        if (in == null) return;
        ZipInputStream zis = new ZipInputStream(in);
        ZipEntry entry;
        StringBuilder text = new StringBuilder();
        while ((entry = zis.getNextEntry()) != null) {
            if ("word/document.xml".equals(entry.getName())) {
                text.append(extractXmlText(zis));
            }
        }
        zis.close();
        result.fullText = text.toString();
        result.chunks = chunkText(text.toString(), 0);
    }

    private static void parseXlsx(Context ctx, String path, ParseResult result) throws Exception {
        InputStream in = openStream(ctx, path);
        if (in == null) return;
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
        result.fullText = text.toString();
        result.chunks = chunkText(text.toString(), 0);
    }

    private static void parsePdf(Context ctx, String path, ParseResult result) throws Exception {
        InputStream in = openStream(ctx, path);
        if (in == null) return;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) baos.write(buf, 0, n);
        in.close();
        byte[] bytes = baos.toByteArray();

        String raw = new String(bytes, StandardCharsets.ISO_8859_1);
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        while ((idx = raw.indexOf("BT", idx)) != -1) {
            int end = raw.indexOf("ET", idx);
            if (end == -1) break;
            String block = raw.substring(idx, end);
            java.util.regex.Pattern p1 = java.util.regex.Pattern.compile("\\((.*?)\\)\\s*Tj");
            java.util.regex.Matcher m1 = p1.matcher(block);
            while (m1.find()) sb.append(m1.group(1));
            java.util.regex.Pattern p2 = java.util.regex.Pattern.compile("\\[(.*?)\\]\\s*TJ");
            java.util.regex.Matcher m2 = p2.matcher(block);
            while (m2.find()) {
                java.util.regex.Pattern p3 = java.util.regex.Pattern.compile("\\((.*?)\\)");
                java.util.regex.Matcher m3 = p3.matcher(m2.group(1));
                while (m3.find()) sb.append(m3.group(1));
            }
            idx = end + 2;
        }
        String text = sb.toString().trim();
        if (text.isEmpty()) text = "（PDF 为扫描件或无嵌入文本，需 OCR）";
        result.fullText = text;
        result.chunks = chunkText(text, 0);
    }

    // ========== 分块 ==========

    public static List<Chunk> chunkText(String text, int startIndex) {
        List<Chunk> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) return chunks;

        String[] paragraphs = text.split("\\n\\s*\\n");
        StringBuilder current = new StringBuilder();
        int idx = startIndex;

        for (String p : paragraphs) {
            p = p.trim();
            if (p.isEmpty()) continue;

            if (current.length() + p.length() > DEFAULT_CHUNK_SIZE && current.length() > 0) {
                saveChunk(chunks, idx++, current.toString());
                current.setLength(0);
            }
            current.append(p).append("\n\n");
        }

        if (current.length() > 0) {
            saveChunk(chunks, idx, current.toString());
        }
        return chunks;
    }

    private static void saveChunk(List<Chunk> chunks, int index, String text) {
        Chunk c = new Chunk();
        c.index = index;
        c.startOffset = (long) index * DEFAULT_CHUNK_SIZE;
        c.length = text.length();
        c.text = text;
        c.summary = summarize(text);
        chunks.add(c);
    }

    private static String summarize(String text) {
        if (text == null) return "";
        String t = text.replaceAll("\\s+", " ").trim();
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }

    // ========== 检索 ==========

    public static List<Chunk> search(List<Chunk> chunks, String query, int topK) {
        if (chunks == null || chunks.isEmpty() || query == null || query.trim().isEmpty())
            return new ArrayList<>();

        String q = query.toLowerCase().trim();
        String[] keywords = q.split("\\s+");
        List<Scored> scored = new ArrayList<>();

        for (Chunk c : chunks) {
            String hay = (c.text + " " + c.summary).toLowerCase();
            int score = 0;
            for (String kw : keywords) {
                if (kw.isEmpty()) continue;
                int idx = 0;
                while ((idx = hay.indexOf(kw, idx)) != -1) {
                    score++;
                    idx += kw.length();
                }
            }
            if (score > 0) scored.add(new Scored(c, score));
        }

        scored.sort((a, b) -> b.score - a.score);
        List<Chunk> result = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, scored.size()); i++) result.add(scored.get(i).chunk);
        return result;
    }

    private static class Scored {
        Chunk chunk;
        int score;
        Scored(Chunk c, int s) { chunk = c; score = s; }
    }

    // ========== 工具 ==========

    private static String readAllText(Context ctx, String path) throws Exception {
        InputStream in = openStream(ctx, path);
        if (in == null) return "";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) baos.write(buf, 0, n);
        in.close();
        byte[] bytes = baos.toByteArray();
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (isGarbled(text)) {
            text = new String(bytes, Charset.forName("GB18030"));
        }
        return text;
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

    private static InputStream openStream(Context ctx, String path) throws Exception {
        if (path == null) return null;
        if (path.startsWith("/")) {
            return new FileInputStream(new File(path));
        }
        try {
            return ctx.getContentResolver().openInputStream(Uri.parse(path));
        } catch (Exception e) {
            return null;
        }
    }

    private static String getExtension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot + 1) : "";
    }

    private static boolean isTextBased(String ext) {
        return "txt".equals(ext) || "md".equals(ext) || "markdown".equals(ext)
                || "log".equals(ext) || "csv".equals(ext) || "json".equals(ext)
                || "xml".equals(ext) || "html".equals(ext) || "htm".equals(ext)
                || "java".equals(ext) || "kt".equals(ext) || "py".equals(ext)
                || "js".equals(ext) || "ts".equals(ext) || "c".equals(ext)
                || "cpp".equals(ext) || "h".equals(ext) || "css".equals(ext)
                || "sh".equals(ext) || "yml".equals(ext) || "yaml".equals(ext);
    }
}
