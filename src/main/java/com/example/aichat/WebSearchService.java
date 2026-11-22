package com.example.aichat;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 联网搜索服务
 * 支持多种搜索引擎 API（Brave、Serper、Google Custom Search、DuckDuckGo等）
 * 返回结构化搜索结果，可注入到对话作为参考资料
 */
public class WebSearchService {

    public static class SearchResult {
        public String title;
        public String url;
        public String snippet;
        public String source; // 搜索引擎来源

        public SearchResult(String title, String url, String snippet, String source) {
            this.title = title;
            this.url = url;
            this.snippet = snippet;
            this.source = source;
        }
    }

    // 搜索引擎类型
    public static final String ENGINE_BRAVE = "brave";
    public static final String ENGINE_SERPER = "serper";
    public static final String ENGINE_DDG = "ddg"; // DuckDuckGo HTML
    public static final String ENGINE_GOOGLE = "google";

    private final Context ctx;

    public WebSearchService(Context ctx) {
        this.ctx = ctx;
    }

    /**
     * 执行搜索
     * @param engine 搜索引擎类型
     * @param apiKey API 密钥（可选）
     * @param query 搜索关键词
     * @param num 返回条数
     * @return 搜索结果列表
     * @throws Exception 搜索失败
     */
    public List<SearchResult> search(String engine, String apiKey, String query, int num) throws Exception {
        switch (engine) {
            case ENGINE_BRAVE:
                return braveSearch(apiKey, query, num);
            case ENGINE_SERPER:
                return serperSearch(apiKey, query, num);
            case ENGINE_GOOGLE:
                return googleSearch(apiKey, query, num);
            case ENGINE_DDG:
            default:
                return duckDuckGoSearch(query, num);
        }
    }

    // ========== Brave Search API ==========
    private List<SearchResult> braveSearch(String apiKey, String query, int num) throws Exception {
        String urlStr = "https://api.search.brave.com/res/v1/web/search?q="
                + URLEncoder.encode(query, "UTF-8")
                + "&count=" + num;

        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setRequestMethod("GET");
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("X-Subscription-Token", apiKey);
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);

        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new Exception("Brave 搜索失败 HTTP " + code);
        }

        String json = readStream(c.getInputStream());
        c.disconnect();

        return parseBraveJson(json, num);
    }

    private List<SearchResult> parseBraveJson(String json, int num) {
        List<SearchResult> list = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(json);
            JSONArray web = root.optJSONArray("web");
            if (web == null) {
                // 新版 API 结构
                JSONArray results = root.optJSONArray("results");
                if (results != null) {
                    for (int i = 0; i < Math.min(results.length(), num); i++) {
                        JSONObject item = results.getJSONObject(i);
                        list.add(new SearchResult(
                                item.optString("title", ""),
                                item.optString("url", ""),
                                item.optString("description", ""),
                                "Brave"
                        ));
                    }
                }
                return list;
            }
            for (int i = 0; i < Math.min(web.length(), num); i++) {
                JSONObject item = web.getJSONObject(i);
                list.add(new SearchResult(
                        item.optString("title", ""),
                        item.optString("url", ""),
                        item.optString("description", ""),
                        "Brave"
                ));
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    // ========== Serper.dev API ==========
    private List<SearchResult> serperSearch(String apiKey, String query, int num) throws Exception {
        String urlStr = "https://google.serper.dev/search";

        JSONObject body = new JSONObject();
        body.put("q", query);
        body.put("num", num);

        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("X-API-KEY", apiKey);
        c.setDoOutput(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);

        OutputStream os = c.getOutputStream();
        os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        os.flush();
        os.close();

        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new Exception("Serper 搜索失败 HTTP " + code);
        }

        String json = readStream(c.getInputStream());
        c.disconnect();

        return parseSerperJson(json, num);
    }

    private List<SearchResult> parseSerperJson(String json, int num) {
        List<SearchResult> list = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(json);
            JSONArray organic = root.optJSONArray("organic");
            if (organic == null) return list;
            for (int i = 0; i < Math.min(organic.length(), num); i++) {
                JSONObject item = organic.getJSONObject(i);
                list.add(new SearchResult(
                        item.optString("title", ""),
                        item.optString("link", ""),
                        item.optString("snippet", ""),
                        "Serper"
                ));
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    // ========== Google Custom Search API ==========
    private List<SearchResult> googleSearch(String apiKey, String query, int num) throws Exception {
        // 需要 apiKey 和 cx（搜索引擎 ID），这里简化只使用 key，cx 需由用户额外配置
        // 实际使用中建议用 Serper 或 Brave 替代
        throw new Exception("Google Custom Search 需额外配置 CX 参数，建议使用 Brave 或 Serper");
    }

    // ========== DuckDuckGo HTML（无需 API Key，作兜底） ==========
    private List<SearchResult> duckDuckGoSearch(String query, int num) throws Exception {
        String urlStr = "https://duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8");

        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setRequestMethod("GET");
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36");
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);

        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new Exception("DuckDuckGo 搜索失败 HTTP " + code);
        }

        String html = readStream(c.getInputStream());
        c.disconnect();

        return parseDuckDuckGoHtml(html, num);
    }

    private List<SearchResult> parseDuckDuckGoHtml(String html, int num) {
        List<SearchResult> list = new ArrayList<>();
        try {
            // 简单正则提取结果（DuckDuckGo HTML 结构）
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                    "<a rel=\"nofollow\" class=\"result__a\" href=\"([^\"]+)\">(.*?)</a>.*?<a class=\"result__snippet\".*?>(.*?)</a>",
                    java.util.regex.Pattern.DOTALL);
            java.util.regex.Matcher m = p.matcher(html);
            while (m.find() && list.size() < num) {
                String url = m.group(1);
                String title = stripTags(m.group(2));
                String snippet = stripTags(m.group(3));
                list.add(new SearchResult(title, url, snippet, "DuckDuckGo"));
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    private static String stripTags(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]+>", "").replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<").replaceAll("&gt;", ">")
                .replaceAll("&quot;", "\"").replaceAll("&#39;", "'")
                .trim();
    }

    // ========== 工具 ==========

    private static String readStream(InputStream in) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append('\n');
        br.close();
        return sb.toString();
    }

    /**
     * 将搜索结果格式化为可注入对话的文本
     */
    public static String formatResults(List<SearchResult> results) {
        if (results == null || results.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("[联网搜索结果]\\n");
        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            sb.append((i + 1)).append(". ").append(r.title).append("\\n");
            sb.append("   链接：").append(r.url).append("\\n");
            sb.append("   摘要：").append(r.snippet).append("\\n");
            sb.append("   来源：").append(r.source).append("\\n\\n");
        }
        return sb.toString();
    }
}
