package com.example.aichat;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class Skill {

    public String name;
    public String content;
    public String description;

    public Skill() {}

    public Skill(String name, String content, String description) {
        this.name = name;
        this.content = content;
        this.description = description;
    }

    private static final String PREFS = "aichat_skills";
    private static final String KEY_SKILLS = "skills";

    public static void save(Context ctx, Skill skill) {
        List<Skill> all = loadAll(ctx);
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).name.equals(skill.name)) {
                all.set(i, skill);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            all.add(skill);
        }
        writeAll(ctx, all);
    }

    public static List<Skill> loadAll(Context ctx) {
        List<Skill> list = new ArrayList<>();
        android.content.SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String json = sp.getString(KEY_SKILLS, "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Skill s = new Skill();
                s.name = o.optString("name", "");
                s.content = o.optString("content", "");
                s.description = o.optString("description", "");
                list.add(s);
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    private static final String KEY_INITIALIZED = "initialized";

    /**
     * 首次启动时写入默认 Skill
     */
    public static void ensureDefaults(Context ctx) {
        android.content.SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (sp.getBoolean(KEY_INITIALIZED, false)) return;

        List<Skill> all = loadAll(ctx);

        if (findByName(all, "文档") == null) {
            all.add(new Skill("文档",
                "你是文档处理专家。任务：读取用户提供的 Word 文档（.docx）文字内容，并据此创建文本或 HTML 报告。\n\n"
                + "工作流程：\n"
                + "1. 若用户已附加 .docx 文件，先解析其文字内容；\n"
                + "2. 若用户要求 HTML 报告，输出完整可运行的 HTML（含内联 CSS，风格简洁专业）；\n"
                + "3. 若用户要求文本报告，输出结构化纯文本（标题、分级、要点）；\n"
                + "4. 报告需包含：概述、关键信息提取、结论或建议；\n"
                + "5. 只输出最终成果本身，不要解释过程。",
                "读取 Word 文档文字并创建文本或 HTML 报告"));
        }

        if (findByName(all, "电子表格") == null) {
            all.add(new Skill("电子表格",
                "你是数据处理专家。任务：分析用户提供的 Excel（.xlsx）数据，并生成电子表格或 HTML 报告。\n\n"
                + "工作流程：\n"
                + "1. 解析 .xlsx 的单元格数据，识别表头与数据行；\n"
                + "2. 做基础统计：求和、平均值、最大值、最小值、计数；\n"
                + "3. 若要求 HTML 报告，输出带表格和样式的完整 HTML；\n"
                + "4. 若要求电子表格，输出 CSV 格式（可直接用 Excel 打开）；\n"
                + "5. 指出数据中的异常值、趋势与值得关注的结论；\n"
                + "6. 只输出最终成果本身。",
                "分析 Excel 数据并创建电子表格或 HTML 报告"));
        }

        if (findByName(all, "演示文稿") == null) {
            all.add(new Skill("演示文稿",
                "你是演示设计专家。任务：使用文字、图片、表格和形状创建 PowerPoint 演示文稿。\n\n"
                + "工作流程：\n"
                + "1. 先输出演示大纲（每页标题 + 要点）；\n"
                + "2. 生成完整 HTML 版演示（每页一个 section，可用浏览器全屏翻页预览）；\n"
                + "3. 若用户需要 .pptx，输出结构化大纲文本，并说明可导入 PowerPoint 的步骤；\n"
                + "4. 每页包含：标题、3-5 个要点、可选图表占位或表格；\n"
                + "5. 风格统一、留白合理、字号层级清晰；\n"
                + "6. 只输出最终成果本身。",
                "使用文字、图片、表格和形状创建 PowerPoint 演示文稿"));
        }

        if (findByName(all, "网站") == null) {
            all.add(new Skill("网站",
                "你是前端工程师。任务：创建静态网站，并输出可离线预览的完整 HTML。\n\n"
                + "工作流程：\n"
                + "1. 输出单个自包含的 .html 文件（内联 CSS 与 JS，无外部依赖）；\n"
                + "2. 响应式布局，适配手机与电脑；\n"
                + "3. 包含导航、内容区、页脚；需要多页时用单页锚点或 JS 切换；\n"
                + "4. 图片用占位或 CSS 渐变，不引用外链资源；\n"
                + "5. 代码需能直接保存为 .html 并在浏览器打开预览；\n"
                + "6. 只输出完整 HTML 代码本身。",
                "创建静态网站，并离线预览 HTML"));
        }

        if (findByName(all, "文件") == null) {
            all.add(new Skill("文件",
                "你是文件处理助手。任务：生成并交付多种格式文件（文件夹说明、zip 清单、txt、HTML 等）。\n\n"
                + "工作流程：\n"
                + "1. 按用户要求生成对应格式内容；\n"
                + "2. txt / html / csv / md 直接输出完整内容；\n"
                + "3. 需要 zip 时，输出文件清单与每个文件的内容，并说明打包方式；\n"
                + "4. 生成后提示用户使用「打开」或「分享」按钮处理文件；\n"
                + "5. 只输出最终成果本身。",
                "生成并给出文件夹、zip、txt 等多种文件，支持快速打开与分享"));
        }

        writeAll(ctx, all);
        sp.edit().putBoolean(KEY_INITIALIZED, true).apply();
    }

    private static Skill findByName(List<Skill> list, String name) {
        for (Skill s : list) {
            if (name.equals(s.name)) return s;
        }
        return null;
    }

    public static void delete(Context ctx, String name) {
        List<Skill> all = loadAll(ctx);
        List<Skill> filtered = new ArrayList<>();
        for (Skill s : all) {
            if (!s.name.equals(name)) filtered.add(s);
        }
        writeAll(ctx, filtered);
    }

    private static void writeAll(Context ctx, List<Skill> list) {
        JSONArray arr = new JSONArray();
        for (Skill s : list) {
            JSONObject o = new JSONObject();
            try {
                o.put("name", s.name);
                o.put("content", s.content);
                o.put("description", s.description);
            } catch (Exception ignored) {
            }
            arr.put(o);
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_SKILLS, arr.toString()).apply();
    }
}
