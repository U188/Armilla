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
internal fun DoubaoVoiceSettings(page: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val diagnostics by io.github.mangi.eta.agent.voice.doubao.DoubaoDiagnostics.state.collectAsState()
    var advanced by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf<String?>(null) }
    var step by remember { mutableIntStateOf(0) }
    var audio by remember { mutableStateOf<android.net.Uri?>(null) }
    var fileName by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    val back = { if (mode != null) { if (step > 0) step-- else mode = null } else onBack() }
    var showDiagnostics by remember { mutableStateOf(false) }
    val config by DoubaoVoiceConfig.state.collectAsState()
    val voices by PersonalVoices.state.collectAsState()
    var asrKey by remember { mutableStateOf("") }
    var cloneKey by remember { mutableStateOf("") }
    var ak by remember { mutableStateOf("") }
    var sk by remember { mutableStateOf("") }
    var project by remember { mutableStateOf("default") }
    var postpaid by remember { mutableStateOf(false) }
    var slotId by remember { mutableStateOf("") }
    var importBusy by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler { if (!busy) back() }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    LaunchedEffect(Unit) {
        DoubaoVoiceConfig.load(context); PersonalVoices.load(context)
        asrKey = DoubaoVoiceConfig.state.value.asrKey; cloneKey = DoubaoVoiceConfig.state.value.cloneKey
    }
    DisposableEffect(Unit) { onDispose { player?.release() } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            audio = uri; fileName = "已选择录音"; consent = false
            scope.launch {
                val display = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }.getOrNull()
                }
                if (audio == uri) fileName = display ?: "已选择录音"
            }
        }
    }
    key(mode, step) {
    io.github.mangi.eta.ui.components.MiuixScaffoldPage(
    title = when { mode == "create" -> "制作声音 · 第 ${step + 1}/3 步"; mode == "import" -> "导入控制台声音"; page == "asr" -> "识别方式"; page == "voices" -> "我的声音"; else -> "帮助与诊断" },
    onBack = { if (!busy) back() },
    ) { item {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (page == "diagnostics") {
                    Text("语音输入：打开总开关，返回聊天页点击语音按钮。文字出现才表示识别成功。")
                    Text("已有控制台声音用“导入”；有录音要制作声音用“制作”。遇到错误可以复制诊断给开发者。")
                    Row {
                        TextButton(onClick = { showDiagnostics = !showDiagnostics }) { Text(if (showDiagnostics) "收起诊断" else "查看语音诊断") }
                        TextButton(onClick = {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("豆包语音诊断", diagnostics.joinToString("\n")))
                            Toast.makeText(context, "已复制脱敏诊断", Toast.LENGTH_SHORT).show()
                        }, enabled = diagnostics.isNotEmpty()) { Text("复制诊断") }
                    }
                    if (showDiagnostics) {
                        Text("记录本次进程内最近 100 条；应用日志也会保留诊断。不记录密钥、音频或识别正文。")
                        Text(diagnostics.takeLast(25).joinToString("\n").ifBlank { "暂无记录，请执行识别或查询音色状态后查看。" }, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { io.github.mangi.eta.agent.voice.doubao.DoubaoDiagnostics.clear() }) { Text("清空面板") }
                    }
                }
                if (page == "asr") {
                    Row {
                        RadioButton(!config.cloudAsr, { DoubaoVoiceConfig.save(context, config.copy(cloudAsr = false)) })
                        Text("本机识别", Modifier.padding(top = 12.dp))
                        RadioButton(config.cloudAsr, { DoubaoVoiceConfig.save(context, config.copy(cloudAsr = true)) })
                        Text("豆包识别", Modifier.padding(top = 12.dp))
                    }
                    if (!config.cloudAsr) Text("无需账户。上一页下载语音包后，即可离线识别。")
                    if (config.cloudAsr) {
                        Text("连接豆包账户", style = MaterialTheme.typography.titleMedium)
                        Text("识别音频会发送到豆包；与朗读、实时对话分别配置。")
                        OutlinedTextField(asrKey, { asrKey = it }, label = { Text("ASR API Key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级设置" else "高级：更换识别服务") }
                        if (advanced) {
                            Text("仅在控制台开通了不同服务时更改。")
                            DoubaoAsrProtocol.resources.forEach { resource ->
                                Row {
                                    RadioButton(selected = resource == config.resource, onClick = { DoubaoVoiceConfig.save(context, config.copy(resource = resource)) })
                                    Text(when (resource) {
                                        "volc.seedasr.sauc.duration" -> "识别 2.0 · 按时长"
                                        "volc.seedasr.sauc.concurrent" -> "识别 2.0 · 按并发"
                                        "volc.bigasr.sauc.duration" -> "识别 1.0 · 按时长"
                                        else -> "识别 1.0 · 按并发"
                                    }, modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        Button(enabled = asrKey.isNotBlank(), onClick = { DoubaoVoiceConfig.save(context, config.copy(asrKey = asrKey)); notice = "已保存。返回聊天页说一句话，文字出现即识别成功。" }) { Text("保存连接设置") }
                        VoiceConsoleHelp()
                        Text("API Key 是账户连接凭证。保存成功不代表已验证识别权限。", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (page == "voices") {
                    if (mode == null) {
                        Text("管理回复朗读的声音，与语音输入分开。")
                        if (config.cloneKey.isBlank() || advanced) {
                            OutlinedTextField(cloneKey, { cloneKey = it }, label = { Text("声音复刻 API Key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                            Button(onClick = { DoubaoVoiceConfig.save(context, config.copy(cloneKey = cloneKey)) }) { Text("保存账户") }
                            VoiceConsoleHelp()
                        } else Text("账户已配置")
                        TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起账户设置" else "更换账户 / 高级设置") }
                        Button(enabled = config.cloneKey.isNotBlank(), onClick = { mode = "import"; step = 0; postpaid = false; advanced = false; notice = "" }) { Text("导入控制台已有声音") }
                        OutlinedButton(enabled = config.cloneKey.isNotBlank(), onClick = { mode = "create"; step = 0; postpaid = false; advanced = false; audio = null; fileName = ""; consent = false; notice = "" }) { Text("用录音制作声音") }
                        if (config.cloneKey.isBlank()) Text("先保存 API Key，才能添加声音。")
                    }
                    if (mode != null && step == 0) {
                        Text(if (mode == "import") "复制已有声音的音色 ID，只查询导入，不会重新训练。" else "先选择你已有的音色名额。免费赠送的也可以用。")
                        OutlinedTextField(name, { name = it.take(80) }, label = { Text(if (mode == "import") "备注名称（可不填）" else "声音名称，例如：我的声音") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        if (mode == "create") {
                            TextButton(onClick = { advanced = !advanced }) { Text("高级：新建后付费音色") }
                            if (advanced || postpaid) Row {
                                RadioButton(!postpaid, { postpaid = false; consent = false })
                                Text("已有/免费槽位", Modifier.padding(top = 12.dp))
                                RadioButton(postpaid, { postpaid = true; consent = false })
                                Text("新建后付费", Modifier.padding(top = 12.dp))
                            }
                        }
                        if (!postpaid || mode == "import") {
                            OutlinedTextField(slotId, { slotId = it.trim(); consent = false }, label = { Text("控制台音色 ID（S_…）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Text("从豆包控制台 → 音色库复制 ID；免费赠送的名额也在这里。需要与已配置 Key 属于同一项目。")
                            VoiceConsoleHelp()
                            voices.filter { it.account == PersonalVoices.account(config.cloneKey) && it.id.startsWith("S_") }.forEach { slot ->
                                TextButton(onClick = { slotId = slot.id; consent = false }) { Text("使用 ${slot.name}（${slot.id}）") }
                            }
                        } else Text("需额外开通同项目的后付费音色服务；仅开通声音复刻 2.0 不足以创建自定义 ID。首次正式合成可能收取音色费用；未正式使用的音色 7 天后可能删除。")
                        if (mode == "import") {
                            Button(enabled = !busy && slotId.matches(Regex("S_[A-Za-z0-9_-]+")), onClick = {
                                busy = true
                                scope.launch {
                                    try { PersonalVoices.importExisting(config.cloneKey, slotId, name); mode = null; notice = "已导入，可在列表中试听或查看状态。" }
                                    catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; notice = e.message ?: "导入失败" }
                                    finally { busy = false }
                                }
                            }) { Text(if (busy) "正在查询…" else "导入声音") }
                        } else Button(enabled = name.isNotBlank() && (postpaid || slotId.matches(Regex("S_[A-Za-z0-9_-]+"))), onClick = { step = 1; notice = "" }) { Text("下一步：选择录音") }
                        if (slotId.isBlank() && !postpaid) Text("请从控制台的音色库复制 S_ 开头的 ID。", style = MaterialTheme.typography.bodySmall)
                    }
                    if (mode == "create" && step == 1) {
                        Text("选择清晰的单人录音，尽量没有音乐和噪声。支持 WAV、MP3、OGG、M4A、AAC，不超过 10 MiB。")
                        OutlinedButton(onClick = { picker.launch(arrayOf("audio/*")) }) { Text(if (audio == null) "选择录音" else "重新选择录音") }
                        if (fileName.isNotBlank()) Text(fileName)
                        Text("选择文件不会立即上传，下一步确认后才制作。")
                        Button(enabled = audio != null, onClick = { step = 2; consent = false }) { Text("下一步：确认制作") }
                    }
                    if (mode == "create" && step == 2) {
                        Text("声音名称：$name")
                        Text(if (postpaid) "新建后付费音色" else "使用音色：$slotId")
                        Text(if (postpaid) "需单独开通后付费音色服务；试听按账户规则计费，首次正式合成可能收取音色费。" else "录音会上传豆包，消耗此音色的训练次数，并可能覆盖原来的声音。试听按账户额度或计费规则结算。")
                        Row { Checkbox(consent, { consent = it }, enabled = !busy); Text("我有权使用该录音，并确认以上操作", Modifier.weight(1f).padding(top = 12.dp)) }
                        Button(enabled = consent && !busy, onClick = {
                            busy = true
                            val trainingKey = config.cloneKey
                            scope.launch {
                                try { PersonalVoices.create(context.applicationContext, requireNotNull(audio), name, trainingKey, if (postpaid) null else slotId.trim()); mode = null; step = 0; notice = "已提交制作任务，在下方刷新状态。请先试听，再允许正式使用。" }
                                catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; notice = e.message ?: "提交失败" }
                                finally { busy = false }
                            }
                        }) { Text(if (busy) "正在提交…" else "确认上传并制作") }
                    }
                    if (mode == null && advanced) {
                        Text("已有音色（含未训练槽位）：通过控制台 AK/SK 拉取，或直接填写控制台 ID。AK/SK 仅在本页临时使用，不保存。导入后仍用复刻 API Key 校验可用性。")
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
                        }) { Text(if (importBusy) "正在同步" else "同步已有音色/槽位") }
                    }
                    if (mode == null) {
                        Text("下方列出本机复刻或已同步的音色；查询状态只读，不会重新训练。")
                        voices.filter { it.account == PersonalVoices.account(config.cloneKey) }.forEach { voice ->
                            HorizontalDivider()
                            Text(voice.name, style = MaterialTheme.typography.titleSmall)
                            Text(when (voice.status) { -2 -> "请求被拒绝"; 0 -> "服务端未找到"; 1 -> "训练中"; 2 -> "训练成功"; 3 -> "训练失败"; 4 -> "已正式使用"; else -> "请求待确认" })
                            if (voice.error.isNotBlank()) {
                                var expanded by remember(voice.id, voice.error) { mutableStateOf(false) }
                                Text(if (voice.error.contains("45000030")) "豆包未授权此次操作，请核对音色与 Key 的项目及服务权限。" else "操作未完成，请查看详情。", color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起详情" else "错误详情") }
                                if (expanded) Text(voice.error, style = MaterialTheme.typography.bodySmall)
                            }
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
                if (notice.isNotBlank()) Text(notice)
            }
        }

    } }
    }

@Composable
private fun VoiceConsoleHelp() {
    val context = LocalContext.current
    TextButton(onClick = {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://console.volcengine.com/speech/new/overview?projectName=default")))
    }) { Text("打开豆包控制台") }
    Text("获取 Key：控制台 → API Key。获取音色 ID：控制台 → 音色库。二者需属于同一项目。", style = MaterialTheme.typography.bodySmall)
}
