package com.example.aichat;

import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.style.BackgroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.RelativeSizeSpan;
import android.graphics.Typeface;
import android.text.style.ForegroundColorSpan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Md {

    public static CharSequence parse(String text) {
        if (text == null) return "";
        if (text.isEmpty()) return "";

        SpannableStringBuilder builder = new SpannableStringBuilder();
        String[] lines = text.split("\\n", -1);
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];

            // 代码块
            if (line.trim().startsWith("```")) {
                StringBuilder code = new StringBuilder();
                i++;
                while (i < lines.length && !lines[i].trim().startsWith("```")) {
                    code.append(lines[i]).append('\n');
                    i++;
                }
                i++;
                SpannableString ss = new SpannableString(code.toString());
                ss.setSpan(new TypefaceSpan("monospace"), 0, ss.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                ss.setSpan(new BackgroundColorSpan(0xFFE8E8E8), 0, ss.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                builder.append(ss);
                continue;
            }

            // 标题
            if (line.trim().startsWith("#")) {
                String content = line.trim().replaceAll("^#+\\s*", "");
                SpannableString ss = new SpannableString(content + "\n");
                ss.setSpan(new StyleSpan(Typeface.BOLD), 0, content.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                ss.setSpan(new RelativeSizeSpan(1.3f), 0, content.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                builder.append(ss);
                i++;
                continue;
            }

            // 列表
            if (line.trim().startsWith("- ") || line.trim().startsWith("* ")) {
                String content = line.trim().substring(2);
                builder.append("• ").append(parseInline(content)).append("\n");
                i++;
                continue;
            }

            // 普通行
            builder.append(parseInline(line)).append("\n");
            i++;
        }
        return builder;
    }

    /** 纯文本（去掉 Markdown 标记），用于摘要/列表 */
    public static String parsePlain(String text) {
        if (text == null) return "";
        String s = text;
        s = s.replaceAll("```[\\s\\S]*?```", " [代码] ");
        s = s.replaceAll("`([^`]*)`", "$1");
        s = s.replaceAll("\\*\\*(.+?)\\*\\*", "$1");
        s = s.replaceAll("^#+\\s*", "");
        s = s.replaceAll("^[\\-\\*]\\s+", "· ");
        s = s.replaceAll("\\n{2,}", "\n");
        return s.trim();
    }

    private static CharSequence parseInline(String text) {
        if (text == null) return "";
        SpannableStringBuilder builder = new SpannableStringBuilder();

        // 加粗 **text**
        Pattern bold = Pattern.compile("\\*\\*(.+?)\\*\\*");
        Matcher m = bold.matcher(text);
        int last = 0;
        while (m.find()) {
            builder.append(text.substring(last, m.start()));
            SpannableString ss = new SpannableString(m.group(1));
            ss.setSpan(new StyleSpan(Typeface.BOLD), 0, ss.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            builder.append(ss);
            last = m.end();
        }
        builder.append(text.substring(last));

        // 行内代码 `text`
        String result = builder.toString();
        builder = new SpannableStringBuilder();
        Pattern code = Pattern.compile("`(.+?)`");
        Matcher m2 = code.matcher(result);
        last = 0;
        while (m2.find()) {
            builder.append(result.substring(last, m2.start()));
            SpannableString ss = new SpannableString(m2.group(1));
            ss.setSpan(new TypefaceSpan("monospace"), 0, ss.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            ss.setSpan(new BackgroundColorSpan(0xFFF0F0F0), 0, ss.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            builder.append(ss);
            last = m2.end();
        }
        builder.append(result.substring(last));

        return builder;
    }
}
