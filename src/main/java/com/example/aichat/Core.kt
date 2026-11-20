package com.example.aichat

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

enum class Role { USER, ASSISTANT }

enum class ProviderType { OPENAI, ANTHROPIC, GEMINI }

data class ChatMessage(val id: Long, val role: Role, val text: String)

data class ProviderConfig(
    val type: ProviderType = ProviderType.OPENAI,
    val host: String = "https://api.deepseek.com",
    val apiKey: String = "",
    val model: String = "deepseek-chat"
)

fun defaultHost(t: ProviderType) = when (t) {
    ProviderType.OPENAI -> "https://api.deepseek.com"
    ProviderType.ANTHROPIC -> "https://api.anthropic.com"
    ProviderType.GEMINI -> "https://generativelanguage.googleapis.com"
}

fun defaultModel(t: ProviderType) = when (t) {
    ProviderType.OPENAI -> "deepseek-chat"
    ProviderType.ANTHROPIC -> "claude-sonnet-4-5"
    ProviderType.GEMINI -> "gemini-2.5-flash"
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("chat", Context.MODE_PRIVATE)

    fun load(): ProviderConfig {
        val type = runCatching {
            ProviderType.valueOf(sp.getString("type", ProviderType.OPENAI.name)!!)
        }.getOrDefault(ProviderType.OPENAI)
        return ProviderConfig(
            type = type,
            host = sp.getString("host", defaultHost(type)) ?: defaultHost(type),
            apiKey = sp.getString("key", "") ?: "",
            model = sp.getString("model", defaultModel(type)) ?: defaultModel(type)
        )
    }

    fun save(c: ProviderConfig) {
        sp.edit()
            .putString("type", c.type.name)
            .putString("host", c.host)
            .putString("key", c.apiKey)
            .putString("model", c.model)
            .apply()
    }
}

private val client = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(180, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .build()

private fun joinUrl(host: String, path: String): String {
    val h = host.trim().trimEnd('/')
    if (h.endsWith(path)) return h
    if (h.endsWith("/v1") && path.startsWith("/v1")) return h + path.substring(3)
    return h + path
}

private fun roleName(r: Role) = if (r == Role.USER) "user" else "assistant"

interface ChatProvider {
    suspend fun stream(c: ProviderConfig, history: List<ChatMessage>, onDelta: (String) -> Unit)
}

class OpenAiProvider : ChatProvider {
    override suspend fun stream(c: ProviderConfig, history: List<ChatMessage>, onDelta: (String) -> Unit) =
        withContext(Dispatchers.IO) {
            val arr = JSONArray()
            history.forEach { m ->
                if (m.text.isNotBlank()) {
                    arr.put(JSONObject().put("role", roleName(m.role)).put("content", m.text))
                }
            }
            val body = JSONObject().put("model", c.model).put("stream", true).put("messages", arr)
            val req = Request.Builder()
                .url(joinUrl(c.host, "/v1/chat/completions"))
                .addHeader("Authorization", "Bearer " + c.apiKey)
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP " + resp.code + " " + (resp.body?.string()?.take(200) ?: ""))
                val src = resp.body?.source() ?: throw IOException("empty body")
                while (!src.exhausted()) {
                    val line = src.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val json = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    val delta = json.optJSONArray("choices")?.optJSONObject(0)
                        ?.optJSONObject("delta")?.optString("content").orEmpty()
                    if (delta.isNotEmpty()) onDelta(delta)
                }
            }
        }
}

class AnthropicProvider : ChatProvider {
    override suspend fun stream(c: ProviderConfig, history: List<ChatMessage>, onDelta: (String) -> Unit) =
        withContext(Dispatchers.IO) {
            val arr = JSONArray()
            history.forEach { m ->
                if (m.text.isNotBlank()) {
                    arr.put(JSONObject().put("role", roleName(m.role)).put("content", m.text))
                }
            }
            val body = JSONObject()
                .put("model", c.model)
                .put("max_tokens", 4096)
                .put("stream", true)
                .put("messages", arr)
            val req = Request.Builder()
                .url(joinUrl(c.host, "/v1/messages"))
                .addHeader("x-api-key", c.apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP " + resp.code + " " + (resp.body?.string()?.take(200) ?: ""))
                val src = resp.body?.source() ?: throw IOException("empty body")
                while (!src.exhausted()) {
                    val line = src.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty()) continue
                    val json = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    if (json.optString("type") == "content_block_delta") {
                        val t = json.optJSONObject("delta")?.optString("text").orEmpty()
                        if (t.isNotEmpty()) onDelta(t)
                    }
                }
            }
        }
}

class GeminiProvider : ChatProvider {
    override suspend fun stream(c: ProviderConfig, history: List<ChatMessage>, onDelta: (String) -> Unit) =
        withContext(Dispatchers.IO) {
            val arr = JSONArray()
            history.forEach { m ->
                if (m.text.isNotBlank()) {
                    val role = if (m.role == Role.USER) "user" else "model"
                    arr.put(
                        JSONObject().put("role", role)
                            .put("parts", JSONArray().put(JSONObject().put("text", m.text)))
                    )
                }
            }
            val body = JSONObject().put("contents", arr)
            val model = URLEncoder.encode(c.model, "UTF-8")
            val url = joinUrl(c.host, "/v1beta/models/" + model + ":streamGenerateContent?alt=sse")
            val req = Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", c.apiKey)
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP " + resp.code + " " + (resp.body?.string()?.take(200) ?: ""))
                val src = resp.body?.source() ?: throw IOException("empty body")
                while (!src.exhausted()) {
                    val line = src.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty()) continue
                    val json = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    val parts = json.optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts") ?: continue
                    for (i in 0 until parts.length()) {
                        val t = parts.optJSONObject(i)?.optString("text").orEmpty()
                        if (t.isNotEmpty()) onDelta(t)
                    }
                }
            }
        }
}

fun providerFor(t: ProviderType): ChatProvider = when (t) {
    ProviderType.OPENAI -> OpenAiProvider()
    ProviderType.ANTHROPIC -> AnthropicProvider()
    ProviderType.GEMINI -> GeminiProvider()
}
