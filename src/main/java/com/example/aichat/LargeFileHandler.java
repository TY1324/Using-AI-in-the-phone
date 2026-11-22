package com.example.aichat;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 大文件处理器：支持 600MB+ 文件的分片读取、文本提取、分块索引
 * 仅使用标准 Java/Android API，无第三方依赖
 */
public class LargeFileHandler {

    public static final int DEFAULT_CHUNK_SIZE = 1024 * 1024; // 1MB 文本分块
    public static final int MAX_FILE_SIZE = 600 * 1024 * 1024; // 600MB

    public static class Chunk {
        public int index;
        public long startOffset;
        public int length;
        public String text;
        public String summary; // 可选：块摘要
    }

    public static class ParseResult {
        public String fileName;
        public long fileSize;
        public String mimeType;
        public List<Chunk> chunks = new ArrayList<>();
        public String fullText; // 小文件时完整文本
        public int totalChunks;
        public String error;
    }

    /**
     * 判断是否为大文件（需分块处理）
     */
    public static boolean isLargeFile(long size) {
        return size > 1024 * 1024; // >1MB 走分块
    }

    /**
     * 主入口：解析文件（自动决定是否分块）
     */
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

            if (isLargeFile(fileSize)) {
                parseLarge(ctx, uri, result);
            } else {
                parseSmall(ctx, uri, result);
            }
        } catch (Exception e) {
            result.error = "解析失败：" + e.getMessage();
        }
        return result;
    }

    // 小文件：一次性读完
    private static void parseSmall(Context ctx, Uri uri, ParseResult result) throws Exception {
        String text = readAllText(ctx, uri);
        result.fullText = text;
        result.chunks = chunkText(text, 0);
        result.totalChunks = result.chunks.size();
    }

    // 大文件：分片读取 + 流式分块
    private static void parseLarge(Context ctx, Uri uri, ParseResult result) throws Exception {
        String ext = getExtension(result.fileName).toLowerCase();

        if (isTextBased(ext)) {
            parseLargeText(ctx, uri, result);
        } else if ("pdf".equals(ext)) {
            parseLargePdf(ctx, uri, result);
        } else if ("docx".equals(ext)) {
            parseLargeDocx(ctx, uri, result);
        } else {
            // 其它二进制：尝试提取文本
            String text = readAllText(ctx, uri);
            result.fullText = text;
            result.chunks = chunkText(text, 0);
        }
        result.totalChunks = result.chunks.size();
    }

    // 大文本文件：按行/定长分块
    private static void parseLargeText(Context ctx, Uri uri, ParseResult result) throws Exception {
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) return;

        byte[] buffer = new byte[8192];
        StringBuilder sb = new StringBuilder();
        int chunkIndex = 0;
        int read;
        List<Chunk> chunks = new ArrayList<>();

        while ((read = in.read(buffer)) != -1) {
            // 尝试 UTF-8 解码
            String part = new String(buffer, 0, read, StandardCharsets.UTF_8);
            sb.append(part);

            // 累积到 1MB 切一块
            if (sb.length() >= DEFAULT_CHUNK_SIZE) {
                Chunk c = new Chunk();
                c.index = chunkIndex++;
                c.startOffset = (long) c.index * DEFAULT_CHUNK_SIZE;
                c.length = sb.length();
                c.text = sb.toString();
                c.summary = summarize(c.text);
                chunks.add(c);
                sb.setLength(0); // 清空
            }
        }
        in.close();

        // 尾部
        if (sb.length() > 0) {
            Chunk c = new Chunk();
            c.index = chunkIndex;
            c.startOffset = (long) c.index * DEFAULT_CHUNK_SIZE;
            c.length = sb.length();
            c.text = sb.toString();
            c.summary = summarize(c.text);
            chunks.add(c);
        }

        result.chunks = chunks;
    }

    // 大 PDF：分页提取（简化版，实际需用 PDFBox）
    private static void parseLargePdf(Context ctx, Uri uri, ParseResult result) throws Exception {
        // 复用 FileParser 的 PDF 解析
        String text = FileParser.parsePdfLite(ctx, uri.toString());
        result.fullText = text;
        result.chunks = chunkText(text, 0);
    }

    // 大 DOCX：解压读取 word/document.xml
    private static void parseLargeDocx(Context ctx, Uri uri, ParseResult result) throws Exception {
        String text = FileParser.parseDocx(ctx, uri.toString());
        result.fullText = text;
        result.chunks = chunkText(text, 0);
    }

    // ========== 工具方法 ==========

    private static String readAllText(Context ctx, Uri uri) throws Exception {
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) return "";
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) baos.write(buf, 0, n);
        in.close();
        byte[] bytes = baos.toByteArray();

        // 编码自动检测
        String text = new String(bytes, StandardCharsets.UTF_8);
        int bad = 0;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\uFFFD') bad++;
        if (bad > text.length() / 50) {
            try {
                in = ctx.getContentResolver().openInputStream(uri);
                text = readStream(in, java.nio.charset.Charset.forName("GBK"));
            } catch (Exception ignored) {}
        }
        return text;
    }

    private static String readStream(InputStream in, java.nio.charset.Charset cs) throws Exception {
        java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(in, cs));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append('\n');
        br.close();
        return sb.toString();
    }

    /**
     * 文本分块：按段落优先，超长段落再按定长切
     */
    public static List<Chunk> chunkText(String text, int startIndex) {
        List<Chunk> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) return chunks;

        // 先按双换行分段
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

    /** 简单摘要：取前 200 字 */
    private static String summarize(String text) {
        if (text == null) return "";
        String t = text.replaceAll("\\s+", " ").trim();
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }

    private static String getExtension(String fileName) {
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

    /**
     * 检索：关键词匹配 + 简单评分
     */
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
}