package com.example.aichat;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;

public class Md {

    public static CharSequence parse(String src) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        String[] lines = src.split("\n", -1);
        boolean inCode = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            if (line.trim().startsWith("```")) {
                inCode = !inCode;
                continue;
            }

            if (inCode) {
                int start = sb.length();
                sb.append(line);
                sb.setSpan(new TypefaceSpan("monospace"), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new BackgroundColorSpan(0xFFEEEEEE), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (i < lines.length - 1) sb.append("\n");
                continue;
            }

            String t = line;
            int size = 1;
            boolean bullet = false;

            if (t.startsWith("### ")) { size = 3; t = t.substring(4); }
            else if (t.startsWith("## ")) { size = 2; t = t.substring(3); }
            else if (t.startsWith("# ")) { size = 1; t = t.substring(2); }
            else if (t.startsWith("- ") || t.startsWith("* ")) { bullet = true; t = t.substring(2); }

            int lineStart = sb.length();
            if (bullet) sb.append("• ");
            applyInline(sb, t);

            if (size > 1) {
                sb.setSpan(new RelativeSizeSpan(1.25f), lineStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), lineStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (bullet) {
                sb.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), lineStart, lineStart + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (i < lines.length - 1) sb.append("\n");
        }
        return sb;
    }

    private static void applyInline(SpannableStringBuilder sb, String text) {
        int i = 0;
        while (i < text.length()) {
            if (text.startsWith("**", i)) {
                int end = text.indexOf("**", i + 2);
                if (end > 0) {
                    int start = sb.length();
                    sb.append(text, i + 2, end);
                    sb.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 2;
                    continue;
                }
            }
            if (text.charAt(i) == '`') {
                int end = text.indexOf('`', i + 1);
                if (end > 0) {
                    int start = sb.length();
                    sb.append(text, i + 1, end);
                    sb.setSpan(new TypefaceSpan("monospace"), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new BackgroundColorSpan(0xFFE3F2FD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }
            sb.append(text.charAt(i));
            i++;
        }
    }
}
