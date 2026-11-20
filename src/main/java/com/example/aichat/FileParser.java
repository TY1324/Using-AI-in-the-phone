package com.example.aichat;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class FileParser {

    public static String parse(Context ctx, Uri uri, String name) throws Exception {
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) throw new Exception("无法打开文件");
        try {
            String lower = name.toLowerCase();
            if (lower.endsWith(".zip")) return parseZip(in);
            if (lower.endsWith(".epub")) return parseEpub(in);
            if (lower.endsWith(".docx")) return parseDocx(in);
            if (lower.endsWith(".xlsx")) return parseXlsx(in);
            if (lower.endsWith(".pdf")) return parsePdf(in);
            if (lower.endsWith(".doc") || lower.endsWith(".xls") || lower.endsWith(".ppt")) {
                return parseOfficeLegacy(in);
            }
            return readText(in);
        } finally {
            in.close();
        }
    }

    private static String readText(InputStream in) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append("\n");
        br.close();
        return sb.toString();
    }

    private static String parseZip(InputStream in) throws Exception {
        ZipInputStream zis = new ZipInputStream(in);
        ZipEntry entry;
        StringBuilder sb = new StringBuilder();
        while ((entry = zis.getNextEntry()) != null) {
            if (entry.isDirectory()) continue;
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = zis.read(buf)) != -1) baos.write(buf, 0, n);
            String nm = entry.getName().toLowerCase();
            if (nm.endsWith(".txt") || nm.endsWith(".md") || nm.endsWith(".json")
                    || nm.endsWith(".html") || nm.endsWith(".htm")) {
                try {
                    String content = new String(baos.toByteArray(), StandardCharsets.UTF_8);
                    sb.append("===== ").append(entry.getName()).append(" =====\n")
                            .append(content).append("\n\n");
                } catch (Exception ignored) {
                }
            }
        }
        zis.close();
        if (sb.length() == 0) throw new Exception("ZIP 未找到可读文本文件");
        return sb.toString();
    }

    private static String parseEpub(InputStream in) throws Exception {
        ZipInputStream zis = new ZipInputStream(in);
        ZipEntry entry;
        java.util.Map<String, byte[]> entries = new java.util.HashMap<>();
        List<String> htmlNames = new ArrayList<>();
        while ((entry = zis.getNextEntry()) != null) {
            if (entry.isDirectory()) continue;
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = zis.read(buf)) != -1) baos.write(buf, 0, n);
            entries.put(entry.getName(), baos.toByteArray());
            String nm = entry.getName().toLowerCase();
            if (nm.endsWith(".html") || nm.endsWith(".xhtml") || nm.endsWith(".htm")) {
                htmlNames.add(entry.getName());
            }
        }
        zis.close();
        java.util.Collections.sort(htmlNames);
        StringBuilder sb = new StringBuilder();
        for (String hn : htmlNames) {
            byte[] data = entries.get(hn);
            if (data == null) continue;
            String html = new String(data, StandardCharsets.UTF_8);
            sb.append(stripTags(html)).append("\n\n");
        }
        if (sb.length() == 0) throw new Exception("EPUB 未找到可读章节");
        return sb.toString();
    }

    private static String parseDocx(InputStream in) throws Exception {
        ZipInputStream zis = new ZipInputStream(in);
        ZipEntry entry;
        StringBuilder sb = new StringBuilder();
        while ((entry = zis.getNextEntry()) != null) {
            if (entry.getName().equals("word/document.xml")) {
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = zis.read(buf)) != -1) baos.write(buf, 0, n);
                String xml = new String(baos.toByteArray(), StandardCharsets.UTF_8);
                int idx = 0;
                while (true) {
                    int start = xml.indexOf("<w:t", idx);
                    if (start == -1) break;
                    int gt = xml.indexOf(">", start);
                    if (gt == -1) break;
                    int end = xml.indexOf("</w:t>", gt);
                    if (end == -1) break;
                    String text = xml.substring(gt + 1, end);
                    sb.append(text);
                    int pEnd = xml.indexOf("</w:p>", end);
                    if (pEnd != -1 && pEnd > end && pEnd < end + 50) {
                        sb.append("\n");
                    }
                    idx = end + 6;
                }
            }
        }
        zis.close();
        if (sb.length() == 0) throw new Exception("DOCX 未找到文本");
        return sb.toString();
    }

    private static String parseXlsx(InputStream in) throws Exception {
        ZipInputStream zis = new ZipInputStream(in);
        ZipEntry entry;
        java.util.Map<String, byte[]> entries = new java.util.HashMap<>();
        while ((entry = zis.getNextEntry()) != null) {
            if (entry.isDirectory()) continue;
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = zis.read(buf)) != -1) baos.write(buf, 0, n);
            entries.put(entry.getName(), baos.toByteArray());
        }
        zis.close();

        List<String> shared = new ArrayList<>();
        byte[] ssData = entries.get("xl/sharedStrings.xml");
        if (ssData != null) {
            String xml = new String(ssData, StandardCharsets.UTF_8);
            int idx = 0;
            while (true) {
                int start = xml.indexOf("<t", idx);
                if (start == -1) break;
                int gt = xml.indexOf(">", start);
                if (gt == -1) break;
                if (xml.charAt(gt - 1) == '/') { idx = gt + 1; continue; }
                int end = xml.indexOf("</t>", gt);
                if (end == -1) break;
                shared.add(xml.substring(gt + 1, end));
                idx = end + 4;
            }
        }

        byte[] sheetData = entries.get("xl/worksheets/sheet1.xml");
        if (sheetData == null) throw new Exception("XLSX 未找到 sheet1");
        String xml = new String(sheetData, StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        while (true) {
            int cStart = xml.indexOf("<c ", idx);
            if (cStart == -1) {
                cStart = xml.indexOf("<c>", idx);
                if (cStart == -1) break;
            }
            int cEnd = xml.indexOf("</c>", cStart);
            if (cEnd == -1) break;
            String cell = xml.substring(cStart, cEnd + 4);
            String r = "";
            int rIdx = cell.indexOf("r=\"");
            if (rIdx != -1) {
                int rEnd = cell.indexOf("\"", rIdx + 3);
                r = cell.substring(rIdx + 3, rEnd);
            }
            String t = "";
            int tIdx = cell.indexOf("t=\"");
            if (tIdx != -1) {
                int tEnd = cell.indexOf("\"", tIdx + 3);
                t = cell.substring(tIdx + 3, tEnd);
            }
            int vStart = cell.indexOf("<v>");
            int vEnd = cell.indexOf("</v>");
            String val = "";
            if (vStart != -1 && vEnd != -1) {
                val = cell.substring(vStart + 3, vEnd);
            }
            if (t.equals("s")) {
                int si = 0;
                try { si = Integer.parseInt(val); } catch (Exception ignored) {}
                if (si < shared.size()) val = shared.get(si);
            }
            sb.append(r).append(": ").append(val).append("  ");
            idx = cEnd + 4;
        }
        if (sb.length() == 0) throw new Exception("XLSX 未找到数据");
        return sb.toString();
    }

    private static String parsePdf(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) baos.write(buf, 0, n);
        String raw = new String(baos.toByteArray(), StandardCharsets.ISO_8859_1);
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        while (true) {
            int open = raw.indexOf("(", idx);
            if (open == -1) break;
            int depth = 0;
            int close = open;
            for (int i = open; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (c == '\\') { i++; continue; }
                if (c == '(') depth++;
                else if (c == ')') { depth--; if (depth == 0) { close = i; break; } }
            }
            if (close == open) break;
            String text = raw.substring(open + 1, close);
            int after = close + 1;
            while (after < raw.length()
                    && (raw.charAt(after) == ' ' || raw.charAt(after) == '\n'
                    || raw.charAt(after) == '\r')) {
                after++;
            }
            if (after + 2 <= raw.length()) {
                String op = raw.substring(after, Math.min(after + 2, raw.length()));
                if (op.startsWith("Tj") || op.startsWith("TJ")) {
                    text = text.replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t")
                            .replace("\\(", "(").replace("\\)", ")").replace("\\\\", "\\");
                    sb.append(text);
                }
            }
            idx = close + 1;
        }
        if (sb.length() == 0) throw new Exception("PDF 文本提取失败，请转换为文本文件");
        return sb.toString();
    }

    private static String parseOfficeLegacy(InputStream in) throws Exception {
        return readText(in);
    }

    private static String stripTags(String html) {
        StringBuilder sb = new StringBuilder();
        boolean inTag = false;
        for (int i = 0; i < html.length(); i++) {
            char c = html.charAt(i);
            if (c == '<') { inTag = true; continue; }
            if (c == '>') { inTag = false; sb.append(' '); continue; }
            if (!inTag) sb.append(c);
        }
        String s = sb.toString();
        s = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'");
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }
}
