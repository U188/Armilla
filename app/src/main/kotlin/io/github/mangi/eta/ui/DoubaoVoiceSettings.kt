package io.github.mangi.eta.ui

import android.media.MediaPlayer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig
import io.github.mangi.eta.agent.voice.doubao.DoubaoAsrProtocol
import io.github.mangi.eta.agent.voice.doubao.PersonalVoices
import kotlinx.coroutines.launch

@Composable
internal fun DoubaoVoiceSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config by DoubaoVoiceConfig.state.collectAsState()
    val voices by PersonalVoices.state.collectAsState()
    var asrKey by remember { mutableStateOf("") }
    var cloneKey by remember { mutableStateOf("") }
    var ak by remember { mutableStateOf("") }
    var sk by remember { mutableStateOf("") }
    var project by remember { mutableStateOf("") }
    var importBusy by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    LaunchedEffect(Unit) {
        DoubaoVoiceConfig.load(context); PersonalVoices.load(context)
        asrKey = DoubaoVoiceConfig.state.value.asrKey; cloneKey = DoubaoVoiceConfig.state.value.cloneKey
    }
    DisposableEffect(Unit) { onDispose { player?.release() } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !busy) scope.launch {
            busy = true
            try {
                PersonalVoices.create(context.applicationContext, uri, name, config.cloneKey)
                Toast.makeText(context, "已创建复刻任务，可在下方查询进度", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Toast.makeText(context, e.message ?: "音频导入失败", Toast.LENGTH_LONG).show()
            } finally { busy = false }
        }
    }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("豆包云端语音识别", style = MaterialTheme.typography.titleMedium)
        Text("识别音频会发送到豆包；与朗读、实时对话分别配置。")
        OutlinedTextField(asrKey, { asrKey = it }, label = { Text("ASR API Key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("识别资源：${config.resource}")
        DoubaoAsrProtocol.resources.forEach { resource ->
            Row {
                RadioButton(selected = resource == config.resource, onClick = { DoubaoVoiceConfig.save(context, config.copy(resource = resource)) })
                Text(resource, modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        Button(onClick = { DoubaoVoiceConfig.save(context, config.copy(asrKey = asrKey)) }) { Text("保存 ASR 配置") }
        Row {
            Switch(config.cloudAsr, { enabled -> DoubaoVoiceConfig.save(context, config.copy(cloudAsr = enabled)) }, enabled = config.asrKey.isNotBlank())
            Text(if (config.cloudAsr) "使用豆包 ASR" else "使用离线识别", Modifier.padding(12.dp))
        }
        HorizontalDivider()
        Text("个人音色与声音复刻", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(cloneKey, { cloneKey = it }, label = { Text("声音复刻 API Key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { DoubaoVoiceConfig.save(context, config.copy(cloneKey = cloneKey)) }) { Text("保存复刻配置") }
        OutlinedTextField(name, { name = it.take(80) }, label = { Text("音色名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("选择清晰的单人录音（WAV / MP3 / OGG / M4A / AAC，不超过 10 MiB）。自动创建后付费音色 ID；试听文本会收取合成费，首次正式使用会收取音色费用。未正式使用的音色 7 天后可能被服务端删除。")
        Row {
            Checkbox(consent, { consent = it })
            Text("我有权使用该声音样本，并了解上述费用", Modifier.padding(top = 12.dp))
        }
        Button(onClick = { picker.launch(arrayOf("audio/*")) }, enabled = consent && name.isNotBlank() && config.cloneKey.isNotBlank() && !busy) {
            Text(if (busy) "正在读取样本" else "选择录音并复刻")
        }
        Text("已购买音色：通过控制台 AK/SK 拉取，无需填写音色 ID。AK/SK 仅在本页临时使用，不保存。导入后仍用复刻 API Key 校验可用性。")
        OutlinedTextField(ak, { ak = it }, label = { Text("Access Key ID") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(sk, { sk = it }, label = { Text("Secret Access Key") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(project, { project = it }, label = { Text("火山项目名称") }, modifier = Modifier.fillMaxWidth())
        Button(enabled = !importBusy && ak.isNotBlank() && sk.isNotBlank() && project.isNotBlank() && config.cloneKey.isNotBlank(), onClick = {
            scope.launch {
                importBusy = true
                try {
                    val count = PersonalVoices.importPurchased(config.cloneKey, ak.trim(), sk.trim(), project.trim())
                    Toast.makeText(context, "已同步 $count 个音色", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Toast.makeText(context, e.message ?: "同步失败", Toast.LENGTH_LONG).show()
                } finally { importBusy = false }
            }
        }) { Text(if (importBusy) "正在同步" else "拉取已购买音色") }
        Text("下方列出本机复刻或已同步的音色；查询状态只读，不会重新训练。")
        voices.filter { it.account == PersonalVoices.account(config.cloneKey) }.forEach { voice ->
            HorizontalDivider()
            Text(voice.name, style = MaterialTheme.typography.titleSmall)
            Text(when (voice.status) { 0 -> "服务端未找到"; 1 -> "训练中"; 2 -> "训练成功"; 3 -> "训练失败"; 4 -> "已正式使用"; else -> "请求待确认" })
            if (voice.error.isNotBlank()) Text(voice.error, color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { PersonalVoices.refresh(voice, config.cloneKey) }) { Text("查询状态") }
                TextButton(enabled = voice.demo.startsWith("https://"), onClick = {
                    player?.release()
                    player = MediaPlayer().apply {
                        setOnPreparedListener { it.start() }
                        setOnCompletionListener { it.release(); if (player === it) player = null }
                        setOnErrorListener { mp, _, _ -> mp.release(); if (player === mp) player = null; Toast.makeText(context, "试听链接可能已过期，请查询状态后重试", Toast.LENGTH_LONG).show(); true }
                        try { setDataSource(voice.demo); prepareAsync() } catch (_: Exception) { release() }
                    }
                }) { Text("试听") }
            }
            if (voice.tts && !voice.accepted) TextButton(onClick = { PersonalVoices.accept(voice) }) { Text("确认音色，允许正式合成（可能产生音色费用）") }
            if (voice.accepted) Text("已允许正式使用；在朗读音色列表中选择。需使用同一个 API Key 账户。")
        }
    }
}
