package com.example.aichat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * MCP (Model Context Protocol) 客户端
 * 支持 HTTP/SSE 传输方式，与远程 MCP 服务器通信
 * 可调用 MCP 服务器提供的工具（tool calling）
 */
public class McpClient {

    public static class Tool {
        public String name;
        public String description;
        public JSONObject inputSchema; // JSON Schema

        public Tool(String name, String description, JSONObject inputSchema) {
            this.name = name;
            this.description = description;
            this.inputSchema = inputSchema;
        }
    }

    public static class ToolResult {
        public String content;
        public boolean isError;
        public ToolResult(String content, boolean isError) {
            this.content = content;
            this.isError = isError;
        }
    }

    public String serverUrl;
    public String serverName;
    public List<Tool> tools = new ArrayList<>();
    public boolean connected = false;

    public McpClient(String serverUrl, String serverName) {
        this.serverUrl = serverUrl;
        this.serverName = serverName;
    }

    /**
     * 连接 MCP 服务器并获取工具列表
     * @throws Exception 连接失败
     */
    public void connect() throws Exception {
        // 1. 初始化连接
        JSONObject initMsg = new JSONObject();
        initMsg.put("jsonrpc", "2.0");
        initMsg.put("id", 1);
        initMsg.put("method", "initialize");
        JSONObject params = new JSONObject();
        params.put("protocolVersion", "2024-11-05");
        params.put("capabilities", new JSONObject());
        JSONObject clientInfo = new JSONObject();
        clientInfo.put("name", "AI Chat Android");
        clientInfo.put("version", "1.0.0");
        params.put("clientInfo", clientInfo);
        initMsg.put("params", params);

        sendJsonRpc(initMsg);

        // 2. 获取工具列表
        JSONObject listMsg = new JSONObject();
        listMsg.put("jsonrpc", "2.0");
        listMsg.put("id", 2);
        listMsg.put("method", "tools/list");

        JSONObject resp = sendJsonRpc(listMsg);
        parseTools(resp);
        connected = true;
    }

    /**
     * 调用 MCP 工具
     * @param toolName 工具名称
     * @param arguments 工具参数（JSON 对象）
     * @return 工具调用结果
     * @throws Exception 调用失败
     */
    public ToolResult callTool(String toolName, JSONObject arguments) throws Exception {
        JSONObject msg = new JSONObject();
        msg.put("jsonrpc", "2.0");
        msg.put("id", 3);
        msg.put("method", "tools/call");
        JSONObject params = new JSONObject();
        params.put("name", toolName);
        params.put("arguments", arguments);
        msg.put("params", params);

        JSONObject resp = sendJsonRpc(msg);

        // 解析结果
        JSONObject result = resp.optJSONObject("result");
        if (result == null) {
            JSONObject error = resp.optJSONObject("error");
            String msgText = error != null ? error.optString("message", "未知错误") : "无结果";
            return new ToolResult(msgText, true);
        }

        JSONArray content = result.optJSONArray("content");
        StringBuilder sb = new StringBuilder();
        if (content != null) {
            for (int i = 0; i < content.length(); i++) {
                JSONObject item = content.getJSONObject(i);
                String type = item.optString("type", "");
                if ("text".equals(type)) {
                    sb.append(item.optString("text", "")).append("\n");
                } else {
                    sb.append(item.toString()).append("\n");
                }
            }
        } else {
            sb.append(result.toString());
        }
        boolean isError = result.optBoolean("isError", false);
        return new ToolResult(sb.toString().trim(), isError);
    }

    /**
     * 发送 JSON-RPC 请求
     */
    private JSONObject sendJsonRpc(JSONObject msg) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(serverUrl).openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json");
        c.setDoOutput(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);

        OutputStream os = c.getOutputStream();
        os.write(msg.toString().getBytes(StandardCharsets.UTF_8));
        os.flush();
        os.close();

        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            InputStream es = c.getErrorStream();
            StringBuilder eb = new StringBuilder();
            if (es != null) {
                BufferedReader er = new BufferedReader(new InputStreamReader(es, StandardCharsets.UTF_8));
                String l;
                while ((l = er.readLine()) != null) eb.append(l);
                er.close();
            }
            throw new Exception("HTTP " + code + " " + eb.toString());
        }

        BufferedReader br = new BufferedReader(
                new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        c.disconnect();

        return new JSONObject(sb.toString());
    }

    /**
     * 解析工具列表
     */
    private void parseTools(JSONObject resp) {
        tools.clear();
        try {
            JSONObject result = resp.optJSONObject("result");
            if (result == null) return;
            JSONArray toolsArr = result.optJSONArray("tools");
            if (toolsArr == null) return;
            for (int i = 0; i < toolsArr.length(); i++) {
                JSONObject t = toolsArr.getJSONObject(i);
                String name = t.optString("name", "");
                String desc = t.optString("description", "");
                JSONObject schema = t.optJSONObject("inputSchema");
                tools.add(new Tool(name, desc, schema));
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 将 MCP 工具转为 OpenAI function calling 格式
     */
    public JSONArray toOpenAiTools() {
        JSONArray arr = new JSONArray();
        for (Tool t : tools) {
            try {
                JSONObject fn = new JSONObject();
                fn.put("name", t.name);
                fn.put("description", t.description);
                fn.put("parameters", t.inputSchema != null ? t.inputSchema : new JSONObject());

                JSONObject tool = new JSONObject();
                tool.put("type", "function");
                tool.put("function", fn);
                arr.put(tool);
            } catch (Exception ignored) {
            }
        }
        return arr;
    }
}
