package com.example.aichat;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ToolRegistry {

    public interface ToolExecutor {
        String execute(JSONObject args) throws Exception;
    }

    private final Context ctx;
    private final Map<String, ToolExecutor> executors = new HashMap<>();
    private final Map<String, JSONObject> definitions = new HashMap<>();
    private final List<McpClient> mcpClients = new ArrayList<>();

    public ToolRegistry(Context ctx) {
        this.ctx = ctx;
    }

    public void register(String name, String description, JSONObject parameters, ToolExecutor exec) {
        JSONObject def = new JSONObject();
        try {
            def.put("name", name);
            def.put("description", description);
            def.put("parameters", parameters);
        } catch (Exception ignored) {
        }
        definitions.put(name, def);
        executors.put(name, exec);
    }

    public void registerBuiltins() {
        JSONObject searchParams = new JSONObject();
        try {
            searchParams.put("type", "object");
            JSONObject props = new JSONObject();
            JSONObject q = new JSONObject();
            q.put("type", "string");
            q.put("description", "搜索关键词");
            props.put("query", q);
            JSONObject n = new JSONObject();
            n.put("type", "integer");
            n.put("description", "返回条数（默认5）");
            props.put("num", n);
            searchParams.put("properties", props);
        } catch (Exception ignored) {}

        register("web_search", "联网搜索互联网信息，返回相关结果", searchParams, args -> {
            String query = args.optString("query", "");
            int num = args.optInt("num", 5);
            if (query.isEmpty()) return "查询为空";
            String[] conf = resolveSearchConfig();
            WebSearchService svc = new WebSearchService(ctx);
            List<WebSearchService.SearchResult> results = svc.search(conf[0], conf[1], query, num);
            return WebSearchService.formatResults(results);
        });

        JSONObject calcParams = new JSONObject();
        try {
            calcParams.put("type", "object");
            JSONObject props = new JSONObject();
            JSONObject expr = new JSONObject();
            expr.put("type", "string");
            expr.put("description", "数学表达式，如 2+3*4");
            props.put("expression", expr);
            calcParams.put("properties", props);
        } catch (Exception ignored) {}

        register("calculator", "计算数学表达式的值", calcParams, args -> {
            String expr = args.optString("expression", "");
            return "计算：" + expr + " = " + simpleEval(expr);
        });
    }

    public void registerMcpServer(McpClient client) {
        mcpClients.add(client);
        for (McpClient.Tool t : client.tools) {
            String fullName = "mcp:" + client.serverName + ":" + t.name;
            register(fullName, "[MCP " + client.serverName + "] " + t.description,
                    t.inputSchema != null ? t.inputSchema : new JSONObject(),
                    args -> {
                        McpClient.ToolResult r = client.callTool(t.name, args);
                        return r.isError ? "错误：" + r.content : r.content;
                    });
        }
    }

    public JSONArray getToolDefinitions() {
        JSONArray arr = new JSONArray();
        for (JSONObject def : definitions.values()) {
            try {
                JSONObject tool = new JSONObject();
                tool.put("type", "function");
                tool.put("function", def);
                arr.put(tool);
            } catch (Exception ignored) {
            }
        }
        return arr;
    }

    public JSONArray getMcpToolDefinitions() {
        JSONArray all = new JSONArray();
        for (McpClient c : mcpClients) {
            JSONArray mcpTools = c.toOpenAiTools();
            for (int i = 0; i < mcpTools.length(); i++) {
                all.put(mcpTools.opt(i));
            }
        }
        return all;
    }

    public String execute(String name, JSONObject args) {
        ToolExecutor exec = executors.get(name);
        if (exec == null) return "工具不存在：" + name;
        try {
            return exec.execute(args);
        } catch (Exception e) {
            return "工具执行失败：" + e.getMessage();
        }
    }

    public boolean hasTools() {
        return !definitions.isEmpty() || !mcpClients.isEmpty();
    }

    public String buildSystemPrompt() {
        if (!hasTools()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("你可以使用以下工具来回答问题：\n");
        for (JSONObject def : definitions.values()) {
            String name = def.optString("name", "");
            String desc = def.optString("description", "");
            sb.append("- ").append(name).append("：").append(desc).append("\n");
        }
        for (McpClient c : mcpClients) {
            for (McpClient.Tool t : c.tools) {
                sb.append("- mcp:").append(c.serverName).append(":").append(t.name)
                  .append("：").append(t.description).append("\n");
            }
        }
        sb.append("\n当用户问题需要实时信息时，优先调用 web_search。");
        return sb.toString();
    }

    private String[] resolveSearchConfig() {
        android.content.SharedPreferences sp = ctx.getSharedPreferences("aichat_search", Context.MODE_PRIVATE);
        String engine = sp.getString("engine", WebSearchService.ENGINE_DDG);
        String apiKey = sp.getString("api_key", "");
        return new String[]{engine, apiKey};
    }

    private static double simpleEval(String expr) {
        try {
            return Double.parseDouble(expr);
        } catch (Exception e) {
            return 0;
        }
    }
}
