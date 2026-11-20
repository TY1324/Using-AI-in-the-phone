package com.example.aichat

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private var seq = 1L

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _config = MutableStateFlow(prefs.load())
    val config: StateFlow<ProviderConfig> = _config.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun saveConfig(c: ProviderConfig) {
        prefs.save(c)
        _config.value = c
    }

    fun send(text: String) {
        val content = text.trim()
        if (content.isEmpty() || _busy.value) return
        val cfg = _config.value
        val userMsg = ChatMessage(seq++, Role.USER, content)
        val replyId = seq++
        val history = _messages.value + userMsg
        _messages.value = history + ChatMessage(replyId, Role.ASSISTANT, "")
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                providerFor(cfg.type).stream(cfg, history) { delta ->
                    _messages.value = _messages.value.map { m ->
                        if (m.id == replyId) m.copy(text = m.text + delta) else m
                    }
                }
            } catch (e: Exception) {
                _error.value = e.message ?: e.javaClass.simpleName
            } finally {
                _busy.value = false
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                ChatScreen(vm)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel) {
    val messages by vm.messages.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val config by vm.config.collectAsState()
    var input by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("AI Chat") },
            actions = { TextButton(onClick = { showSettings = true }) { Text("设置") } }
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { m -> MessageRow(m) }
            }
            if (error != null) {
                Text("出错：" + error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入内容…") }
                )
                Button(
                    onClick = { val t = input; input = ""; vm.send(t) },
                    enabled = !busy && input.isNotBlank()
                ) { Text(if (busy) "生成中" else "发送") }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(config, { showSettings = false }, { vm.saveConfig(it); showSettings = false })
    }
}

@Composable
private fun MessageRow(m: ChatMessage) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(if (m.role == Role.USER) "我" else "AI", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(if (m.text.isEmpty()) "…" else m.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SettingsDialog(current: ProviderConfig, onDismiss: () -> Unit, onSave: (ProviderConfig) -> Unit) {
    var type by remember { mutableStateOf(current.type) }
    var host by remember { mutableStateOf(current.host) }
    var key by remember { mutableStateOf(current.apiKey) }
    var model by remember { mutableStateOf(current.model) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ProviderType.values().forEach { t ->
                        TextButton(onClick = {
                            type = t
                            host = defaultHost(t)
                            model = defaultModel(t)
                        }) { Text(t.name, style = MaterialTheme.typography.labelSmall) }
                    }
                }
                OutlinedTextField(host, { host = it }, label = { Text("API 主机") }, singleLine = true)
                OutlinedTextField(key, { key = it }, label = { Text("API Key") }, singleLine = true)
                OutlinedTextField(model, { model = it }, label = { Text("模型 ID") }, singleLine = true)
                Text("端点自动补全：OpenAI /v1/chat/completions，Anthropic /v1/messages，Gemini /v1beta/models/{model}:streamGenerateContent", style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(ProviderConfig(type, host.trim(), key.trim(), model.trim())) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
