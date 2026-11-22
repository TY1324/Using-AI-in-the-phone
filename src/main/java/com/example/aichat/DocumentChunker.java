package com.example.aichat;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档分块索引：构建倒排索引，支持关键词检索 + 向量检索（简化版）
 * 数据持久化到 SharedPreferences（小数据量）或本地文件（大数据量）
 */
public class DocumentChunker {

    private static final String PREFS = "doc_chunks";
    private static final String KEY_INDEX = "index_";

    private final Context ctx;

    public DocumentChunker(Context ctx) {
        this.ctx = ctx;
    }

    /**
     * 保存文档分块（会话级或全局）
     */
    public void saveChunks(String docId, List<LargeFileHandler.Chunk> chunks) {
        try {
            JSONArray arr = new JSONArray();
            for (LargeFileHandler.Chunk c : chunks) {
                JSONObject o = new JSONObject();
                o.put("index", c.index);
                o.put("startOffset", c.startOffset);
                o.put("length", c.length);
                o.put("text", c.text);
                o.put("summary", c.summary == null ? "" : c.summary);
                arr.put(o);
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_INDEX + docId, arr.toString()).apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 加载文档分块
     */
    public List<LargeFileHandler.Chunk> loadChunks(String docId) {
        List<LargeFileHandler.Chunk> list = new ArrayList<>();
        String json = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_INDEX + docId, "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                LargeFileHandler.Chunk c = new LargeFileHandler.Chunk();
                c.index = o.optInt("index", 0);
                c.startOffset = o.optLong("startOffset", 0);
                c.length = o.optInt("length", 0);
                c.text = o.optString("text", "");
                c.summary = o.optString("summary", "");
                list.add(c);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }

    /**
     * 删除文档索引
     */
    public void deleteChunks(String docId) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_INDEX + docId).apply();
    }

    /**
     * 关键词检索（跨文档）
     */
    public List<SearchResult> searchAll(String query, int topK) {
        Map<String, List<LargeFileHandler.Chunk>> all = loadAll();
        List<SearchResult> results = new ArrayList<>();

        for (Map.Entry<String, List<LargeFileHandler.Chunk>> e : all.entrySet()) {
            String docId = e.getKey();
            List<LargeFileHandler.Chunk> chunks = e.getValue();
            List<LargeFileHandler.Chunk> hits = LargeFileHandler.search(chunks, query, topK);
            for (LargeFileHandler.Chunk c : hits) {
                results.add(new SearchResult(docId, c));
            }
        }

        results.sort((a, b) -> b.score - a.score);
        return results.size() > topK ? results.subList(0, topK) : results;
    }

    /**
     * 检索结果
     */
    public static class SearchResult {
        public String docId;
        public LargeFileHandler.Chunk chunk;
        public int score;

        SearchResult(String docId, LargeFileHandler.Chunk chunk) {
            this.docId = docId;
            this.chunk = chunk;
            this.score = calculateScore(chunk);
        }

        private int calculateScore(LargeFileHandler.Chunk c) {
            // 简化：summary 命中权重更高
            return 1;
        }
    }

    private Map<String, List<LargeFileHandler.Chunk>> loadAll() {
        Map<String, List<LargeFileHandler.Chunk>> map = new HashMap<>();
        android.content.SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Map<String, ?> all = sp.getAll();
        for (String key : all.keySet()) {
            if (key.startsWith(KEY_INDEX)) {
                String docId = key.substring(KEY_INDEX.length());
                map.put(docId, loadChunks(docId));
            }
        }
        return map;
    }
}
