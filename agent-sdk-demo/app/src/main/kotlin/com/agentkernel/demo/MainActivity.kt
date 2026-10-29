package com.agentkernel.demo

import ai.agentkernel.memory.MemoryEntry
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /**
     * 状态挂在 **ViewModel** 上，不是 activity 上 ——
     * 这样 activity 被重建时，对话历史与正在跑的 agent 循环都不会丢。
     * 详见 [DemoState.startChatAutorun] 的说明。
     */
    private val state: DemoState by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 支持从命令行触发自检（自动化 / 回归用，不用盯着屏幕）：
        //   adb shell am start -n com.agentkernel.demo/.MainActivity --ez autorun_selfcheck true
        //   adb shell am start -n com.agentkernel.demo/.MainActivity --ez autorun_torch true
        val autorunSelfCheck = intent?.getBooleanExtra("autorun_selfcheck", false) ?: false
        val autorunTorch = intent?.getBooleanExtra("autorun_torch", false) ?: false
        // 命令行触发的端到端对话（不盯屏幕）：
        //   adb shell am start -n com.agentkernel.demo/.MainActivity --es autorun_chat "用 bash 跑 pwd"
        val autorunChat = intent?.getStringExtra("autorun_chat")
        // 多轮对话（缓存命中率只有在多轮里才看得出来）：
        //   adb shell am start -n com.agentkernel.demo/.MainActivity --es autorun_chat_multi "问题一|问题二|问题三"
        val autorunChatMulti = intent?.getStringExtra("autorun_chat_multi")
        // 命令行触发拉档位清单（验清单接口，不碰屏幕）：
        //   adb shell am start -n com.agentkernel.demo/.MainActivity --ez autorun_efforts true
        val autorunEfforts = intent?.getBooleanExtra("autorun_efforts", false) ?: false
        // 命令行触发记忆子系统自检（归档 / 落盘 / 摘要块 / 压缩）：
        //   adb shell am start -S --activity-clear-task -n com.agentkernel.demo/.MainActivity --ez autorun_memory true
        val autorunMemory = intent?.getBooleanExtra("autorun_memory", false) ?: false
        // 命令行直接进「归档」页（验前端能翻历史，不碰屏幕）：
        //   adb shell am start -S --activity-clear-task -n com.agentkernel.demo/.MainActivity --ez autorun_archive true
        val autorunArchive = intent?.getBooleanExtra("autorun_archive", false) ?: false
        // 命令行跑归档分页自检（首屏只取最新一段 / 一次只多一段 / 链对得上）：
        //   adb shell am start -S --activity-clear-task -n com.agentkernel.demo/.MainActivity --ez autorun_archive_paging true
        val autorunArchivePaging = intent?.getBooleanExtra("autorun_archive_paging", false) ?: false
        // 命令行跑行级补丁自检（agent-memory patch / agent-text patch 真能改一行）：
        //   adb shell am start -S --activity-clear-task -n com.agentkernel.demo/.MainActivity --ez autorun_patch true
        val autorunPatch = intent?.getBooleanExtra("autorun_patch", false) ?: false
        // 命令行跑凭据自检（agent-cred 接线：实现可用性 / 写入 / 磁盘无明文 / 往返一致）：
        //   adb shell am start -S --activity-clear-task -n com.agentkernel.demo/.MainActivity --ez autorun_cred true
        //   adb logcat -d -s AgentSdkDemo | Select-String 'CRED\|'
        val autorunCred = intent?.getBooleanExtra("autorun_cred", false) ?: false
        // 网关配置也能从命令行注入（**运行时**的，token 不进 APK）：
        //   --es gateway_url ... --es gateway_token ... --es gateway_model ...
        // 思考强度用**强度编码**注入（2 的幂：1=关闭思考 2=low 4=medium 8=high 16=max）：
        //   --ei reasoning_strength 8
        state.applyGatewayConfig(
            intent?.getStringExtra("gateway_url"),
            intent?.getStringExtra("gateway_token"),
            intent?.getStringExtra("gateway_model"),
            intent?.getIntExtra("reasoning_strength", 0)?.takeIf { it > 0 },
        )
        // 压缩/归档的旋钮也能从命令行注入（回归用；不传就用 SDK 的推荐值）：
        //   --ei autorun_memory_max_chars 500   --ei autorun_memory_idle_min 1
        state.applyMemoryPolicy(
            intent?.getIntExtra("autorun_memory_max_chars", 0)?.takeIf { it > 0 },
            intent?.getIntExtra("autorun_memory_idle_min", 0)?.takeIf { it > 0 },
        )
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                DemoApp(
                    state,
                    autorunSelfCheck,
                    autorunTorch,
                    autorunChat,
                    autorunChatMulti,
                    autorunEfforts,
                    autorunMemory,
                    autorunArchive,
                    autorunArchivePaging,
                    autorunPatch,
                    autorunCred,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemoApp(
    state: DemoState,
    autorunSelfCheck: Boolean = false,
    autorunTorch: Boolean = false,
    autorunChat: String? = null,
    autorunChatMulti: String? = null,
    autorunEfforts: Boolean = false,
    autorunMemory: Boolean = false,
    autorunArchive: Boolean = false,
    autorunArchivePaging: Boolean = false,
    autorunPatch: Boolean = false,
    autorunCred: Boolean = false,
) {
    var tab by remember {
        mutableStateOf(
            when {
                autorunArchive -> 3
                !autorunChat.isNullOrBlank() || !autorunChatMulti.isNullOrBlank() -> 2
                autorunSelfCheck || autorunTorch || autorunMemory || autorunPatch || autorunCred -> 1
                else -> 0
            },
        )
    }
    val scope = rememberCoroutineScope()

    // 启动时兜底关一次手电筒。
    // 放在 **App 级**（不是能力台页级）：否则从「自检 / 对话」页进来就不会执行，
    // 上次退出时亮着的灯就留在用户手机上。
    // 手电筒自检自己会管基线，所以那一路跳过 —— 免得两路抢同一个灯。
    LaunchedEffect(Unit) {
        if (!autorunTorch) state.ensureTorchOff()
    }

    // 命令行触发的自检：跑完结果也在 logcat 里。
    // 两条路径**互斥**（一次只跑一条），免得并发抢同一个 torch。
    // ★ 一律走 state.start*() —— 它们在 **viewModelScope** 里跑，
    //   activity 重建不会把正在跑的 agent 循环掐断（详见 DemoState.startChatAutorun）。
    LaunchedEffect(autorunSelfCheck, autorunTorch, autorunChat, autorunChatMulti, autorunEfforts, autorunMemory, autorunPatch, autorunCred) {
        android.util.Log.i(
            "AgentSdkDemo",
            "[CHAT] [effect-enter] selfcheck=$autorunSelfCheck torch=$autorunTorch " +
                "chatLen=${autorunChat?.length} multiLen=${autorunChatMulti?.length} efforts=$autorunEfforts " +
                "memory=$autorunMemory patch=$autorunPatch cred=$autorunCred",
        )
        if (autorunTorch) {
            state.startTorchCheck()
        } else if (autorunSelfCheck) {
            state.startSelfCheck()
        } else if (autorunArchivePaging) {
            state.startArchivePagingCheck()
        } else if (autorunMemory) {
            state.startMemoryCheck()
        } else if (autorunPatch) {
            state.startPatchCheck()
        } else if (autorunCred) {
            state.startCredentialCheck()
        } else if (!autorunChatMulti.isNullOrBlank()) {
            // 多轮：用来观察缓存命中率随轮次的上升曲线。
            state.startChatMultiAutorun(
                autorunChatMulti.split("|").map { it.trim() }.filter { it.isNotEmpty() },
            )
        } else if (!autorunChat.isNullOrBlank()) {
            state.startChatAutorun(autorunChat)
        } else if (autorunEfforts) {
            // 拉档位清单（自动化用：验清单接口，不碰屏幕）。
            state.loadEffortCatalog()
        }
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Agent 能力台 · android-agent-sdk") }) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Bolt, null) },
                    label = { Text("能力台") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.FactCheck, null) },
                    label = { Text("自检") },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.Chat, null) },
                    label = { Text("对话") },
                )
                NavigationBarItem(
                    selected = tab == 3,
                    onClick = { tab = 3 },
                    icon = { Icon(Icons.Filled.History, null) },
                    label = { Text("归档") },
                )
                NavigationBarItem(
                    selected = tab == 4,
                    onClick = { tab = 4 },
                    icon = { Icon(Icons.Filled.ContentCopy, null) },
                    label = { Text("记忆") },
                )
            }
        },
    ) { padding ->
        when (tab) {
            0 -> CapabilitiesScreen(state, padding)
            1 -> SelfCheckScreen(state, padding)
            2 -> ChatScreen(state, padding)
            3 -> ArchiveScreen(state, padding)
            else -> MemoryScreen(state, padding)
        }
    }
}

// ---------------------------------------------------------------- 能力台

@Composable
fun CapabilitiesScreen(state: DemoState, padding: PaddingValues) {
    val scope = rememberCoroutineScope()
    var last by remember { mutableStateOf<CommandResult?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    // 注意：启动兜底关手电筒已经提到 **App 级**（DemoApp 的 LaunchedEffect），
    // 这里再放一份的话会关两次，camera service 日志里出现成对的 “turned off”，反而对不上账。

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InfoCard(
            title = "这是做什么的",
            body = "每个图标是一条**真实的设备能力命令**，点一下就通过 SDK 在这台手机上执行，\n" +
                "下方显示的是模型会看到的原始输出（JSON）。\n" +
                "命令名与 Termux:API 逐字对齐，模型按 Termux 的习惯写就认得。",
        )

        SectionTitle("设备能力")
        CapabilityCatalog.all.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { spec ->
                    CapabilityCard(
                        spec = spec,
                        modifier = Modifier.weight(1f),
                        // 手电筒是**开关**：再点一次就关。
                        badge = if (spec.key == "torch" && state.torchOn) "已开" else null,
                        onClick = {
                            scope.launch {
                                val r = if (spec.key == "torch") {
                                    state.toggleTorch()
                                } else {
                                    state.runCommand(spec.title, spec.command)
                                }
                                last = r
                                state.capabilityResults.add(r)
                                // 要权限的能力：执行后若被拒，弹一次申请。
                                spec.runtimePermission?.let { perm ->
                                    if (r.verdict != Verdict.PASS) {
                                        permissionLauncher.launch(arrayOf(perm))
                                    }
                                }
                            }
                        },
                    )
                }
                if (row.size == 1) Spacer(Modifier.width(0.dp))
            }
        }

        SectionTitle("文件基础操作（走普通 shell，不用 bootstrap）")
        CapabilityCatalog.fileOps.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { spec ->
                    CapabilityCard(
                        spec = spec,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            scope.launch {
                                val r = state.runCommand(spec.title, spec.command)
                                last = r
                                state.capabilityResults.add(r)
                            }
                        },
                    )
                }
                if (row.size == 1) Spacer(Modifier.width(0.dp))
            }
        }

        SectionTitle("SDK 自制命令（agent- 前缀，不在 Termux:API 里）")
        state.internalCommandSpecs.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { spec ->
                    CapabilityCard(
                        spec = spec,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            scope.launch {
                                // ★ `agent-text` 那张卡片上写的是**用法行**，不是一条可执行命令 ——
                                //   照着跑只会得到「不认识的子命令」。所以点它跑的是一条真例子。
                                val r = if (spec.key == "agent-text") {
                                    state.runAgentTextDemo()
                                } else {
                                    state.runCommand(spec.title, spec.command)
                                }
                                last = r
                                state.capabilityResults.add(r)
                            }
                        },
                    )
                }
                if (row.size == 1) Spacer(Modifier.width(0.dp))
            }
        }

        SectionTitle("环境探针（决定能不能预装 Python）")
        CapabilityCatalog.envProbes.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { spec ->
                    CapabilityCard(
                        spec = spec,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            scope.launch {
                                val r = state.runCommand(spec.title, spec.command)
                                last = r
                                state.capabilityResults.add(r)
                            }
                        },
                    )
                }
                if (row.size == 1) Spacer(Modifier.width(0.dp))
            }
        }

        SectionTitle("普通 shell 命令（证明 shell 本身也是通的）")
        CapabilityCatalog.shellSamples.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { spec ->
                    CapabilityCard(
                        spec = spec,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            scope.launch {
                                val r = state.runCommand(spec.title, spec.command)
                                last = r
                                state.capabilityResults.add(r)
                            }
                        },
                    )
                }
                if (row.size == 1) Spacer(Modifier.width(0.dp))
            }
        }

        last?.let { ResultCard(it) }

        Text(
            "已执行 ${state.capabilityResults.size} 条",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CapabilityCard(
    spec: CapabilitySpec,
    modifier: Modifier = Modifier,
    badge: String? = null,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(spec.icon, contentDescription = spec.title, modifier = Modifier.height(28.dp))
                Spacer(Modifier.width(8.dp))
                Text(spec.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (badge != null) {
                    Surface(color = Color(0xFF2E7D32), shape = RoundedCornerShape(6.dp)) {
                        Text(
                            badge,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            Text(
                spec.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                spec.command,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

// ---------------------------------------------------------------- 自检

@Composable
fun SelfCheckScreen(state: DemoState, padding: PaddingValues) {
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InfoCard(
            title = "合格标准是什么",
            body = "不是「没报错」就算过，而是行为符合契约：\n" +
                "✅ 合格 —— 退出码 0 且返回 {\"ok\":true,...}\n" +
                "⚠️ 受系统限制 —— 如实报错（ok:false + 非零退出码 + 说清原因），\n" +
                "        比如剪贴板读不到（Android 10+ 要前台焦点）\n" +
                "❌ 不合格 —— 返回了不该出现的错误，或者谎报成功",
        )

        Button(
            onClick = { scope.launch { state.runSelfCheck() } },
            enabled = !state.selfCheckRunning,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.PlayArrow, null)
            Spacer(Modifier.width(8.dp))
            Text(if (state.selfCheckRunning) "正在自检…" else "一键自检（自动监测）")
        }

        val pass = state.checks.count { it.verdict == Verdict.PASS }
        val warn = state.checks.count { it.verdict == Verdict.WARN }
        val fail = state.checks.count { it.verdict == Verdict.FAIL }
        if (state.checks.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Text(
                    "合计 ${state.checks.size} 项：✅ $pass · ⚠️ $warn · ❌ $fail",
                    modifier = Modifier.padding(14.dp),
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        state.checks.forEach { item ->
            CheckCard(item)
        }

        // 把「模型会看到的那份系统提示词」原样摆出来。
        // 它是模型唯一的「环境说明书」—— 摆出来才谈得上核对：
        // 路径对不对、命令清单是不是这台设备上真实存在的、能力边界写没写死。
        SectionTitle("模型看到的系统提示词（SDK 生成，原文）")
        Surface(
            color = Color.Black.copy(alpha = 0.25f),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                state.systemPrompt.ifBlank { "（还没生成）" },
                modifier = Modifier.padding(10.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun CheckCard(item: CheckItem) {
    val border = when (item.verdict) {
        Verdict.PASS -> Color(0xFF2E7D32)
        Verdict.WARN -> Color(0xFFF9A825)
        Verdict.FAIL -> Color(0xFFC62828)
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${item.verdict.emoji} ${item.title}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    "${item.elapsedMs}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                item.command,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                item.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (item.output.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Surface(
                    color = Color.Black.copy(alpha = 0.25f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        item.output.take(600),
                        modifier = Modifier.padding(8.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
            }
            Text(
                "判定：${item.verdict.label}",
                style = MaterialTheme.typography.labelSmall,
                color = border,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

// ---------------------------------------------------------------- 对话

@Composable
fun ChatScreen(state: DemoState, padding: PaddingValues) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("模型服务配置（运行时填写，不打包进 APK）", fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = state.gatewayUrl,
            onValueChange = { state.gatewayUrl = it },
            label = { Text("网关地址（OpenAI 兼容）") },
            placeholder = { Text("https://your-gateway/v1") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.gatewayToken,
            onValueChange = { state.gatewayToken = it },
            label = { Text("token") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { state.saveGateway() }) { Text("保存") }
            Text(
                "线格式仍是 OpenAI 那套；鉴权用 Bearer token",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---- 思考强度 ----
        // ★ **强度编码才是契约**：持久化 strength，不持久化 key / index
        //   （key 换模型就变，index 只是显示顺序）。清单**问服务端拿**，不自己抄。
        val catalog = state.effortCatalog
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { state.loadEffortCatalog() }) { Text("拉档位清单") }
            when {
                catalog == null -> Text(
                    state.effortCatalogError ?: "未拉清单（拉不到就如实报错，不编一份本地表）",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.effortCatalogError != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                else -> Text(
                    "${catalog.model} · 当前 " +
                        (state.reasoningStrength?.let { "强度 $it" } ?: "服务端默认"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (catalog != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = state.reasoningStrength == null,
                    onClick = { state.selectReasoningStrength(null) },
                    label = { Text("默认") },
                )
                catalog.levels.forEach { level ->
                    FilterChip(
                        selected = state.reasoningStrength == level.strength,
                        onClick = { state.selectReasoningStrength(level.strength) },
                        label = { Text(level.label) },
                    )
                }
            }
            val current = catalog.levels.firstOrNull { it.strength == state.reasoningStrength }
            Text(
                current?.let { "${it.hint}（强度 ${it.strength} / ${it.bits}）" }
                    ?: "不指定 = 服务端默认档位",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f),
        ) {
            Column(
                Modifier
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.chat.isEmpty()) {
                    Text(
                        "试试：「打开手电筒」「看下电量」「把剪贴板内容读出来」\n" +
                            "模型会下发 termux-* 命令，由这台手机真的执行。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.chat.forEach { line ->
                    when (line) {
                        is ChatLine.User -> ChatBubble("你", line.text, primary = true)
                        is ChatLine.Assistant -> ChatBubble("模型", line.text, primary = false)
                        is ChatLine.System -> ChatBubble("提示", line.text, primary = false, warn = true)
                        is ChatLine.ToolCall -> Surface(
                            color = Color(0xFF1B5E20).copy(alpha = 0.35f),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Text("执行命令", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text(
                                    line.command,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                )
                                Text(
                                    "退出码 ${line.exitCode}：${line.output.take(300)}",
                                    fontSize = 11.sp,
                                )
                            }
                        }
                        // 缓存与前缀状态：上游给的命中数 + SDK 的前缀体检。
                        is ChatLine.Metrics -> Surface(
                            color = Color(0xFF0D47A1).copy(alpha = 0.30f),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Text("缓存 / 前缀", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text(
                                    line.text,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }
            }
        }

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("跟模型说点什么") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                val text = input.trim()
                if (text.isNotEmpty() && !state.chatBusy) {
                    state.chat += ChatLine.User(text)
                    input = ""
                    scope.launch { state.sendToModel(text) }
                }
            },
            enabled = !state.chatBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.chatBusy) "模型思考中…" else "发送")
        }
    }
}

// ---------------------------------------------------------------- 记忆

/**
 * 记忆页 —— **两块**：今天的摘要 / 你自己记下的事。
 *
 * ★ 两块的口径与 SDK 的 `MemoryStore.digest()` **一致**：
 *   界面上看到的分法，跟**模型**在提示词里看到的分法是同一份
 *   （摘要 = 最新的每日槽，日常 = 追加式条目）。
 *   两边对不上，用户就没法拿界面核对模型到底看见了什么。
 *
 * 每条都标出**种类**（摘要 / 日常）和**日期** —— 记忆不标日期就只是一堆字。
 */
@Composable
fun MemoryScreen(state: DemoState, padding: PaddingValues) {
    LaunchedEffect(Unit) { state.loadMemory() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("长期记忆", fontWeight = FontWeight.Bold)
                Text(
                    "口径与提示词里那一块一致：摘要 = 今天的每日槽，日常 = 追加式条目",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { state.loadMemory() }) { Text("刷新") }
        }

        SectionTitle("今天的摘要（每天一条，新摘要替换当天那条）")
        val daily = state.memoryDaily
        if (daily == null) {
            Text(
                "今天还没有摘要。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            MemoryCard(daily, kindLabel = "摘要")
        }

        SectionTitle("你自己记下的事（${state.memoryNotes.size} 条）")
        if (state.memoryNotes.isEmpty()) {
            Text(
                "还没有日常条目。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.memoryNotes.forEach { entry -> MemoryCard(entry, kindLabel = "日常") }
        }
    }
}

/** 一条记忆：种类 + 日期 + 标题 + 正文。 */
@Composable
private fun MemoryCard(entry: MemoryEntry, kindLabel: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(
                "$kindLabel · ${formatMemoryTime(entry.createdAt)}",
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
            )
            if (entry.title.isNotBlank()) {
                Text(entry.title, style = MaterialTheme.typography.bodyMedium)
            }
            if (entry.summary.isNotBlank()) {
                Text(
                    entry.summary,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 记忆条目的日期（本地时区 —— 用户说的「今天」是本地日历上的今天）。 */
private fun formatMemoryTime(millis: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(millis))

// ---------------------------------------------------------------- 归档

/** 归档页的一行：要么是「一段的标题条」，要么是「一条原始消息」，要么是「加载更早」。 */
private sealed interface ArchiveRow {
    data class Header(val segment: DemoState.ArchiveView) : ArchiveRow
    data class Line(val line: DemoState.ArchiveLine) : ArchiveRow

    /** 列表最上头的「还有更早的」占位条。 */
    data object LoadMore : ArchiveRow
}

/**
 * 归档历史 —— **摘要不删原文，前端照样能翻**。
 *
 * ## 首屏只看最新一段
 *
 * 进页面只取**最新的一段**（[DemoState.loadLatestArchive]）——
 * 历史可能很长，整包拉进内存既慢又占地方。
 * 顶头显示的是**最新一段的日期**。
 *
 * ## 往上滚才加载更早的
 *
 * 列表是**反向**的（`reverseLayout = true`）：底部是最新的一段，往上滚是更早的。
 * 滚到已加载内容的最上边，就再取**一段**（不是一把全拉）。
 *
 * ★ **必须先滚过才加载**（`userScrolled`）：否则刚进页面就会把历史全拉下来 ——
 *   那正好违背「首屏只取最新一段」。
 */
@Composable
fun ArchiveScreen(state: DemoState, padding: PaddingValues) {
    val listState = rememberLazyListState()

    // 行序：**下标 0 = 列表最底下**。每段：先标题条，再把消息倒着排
    //（倒序是为了「越往下 = 越新」，和反向列表的视觉方向一致）。
    // 最上头再挂一条「还有更早的」占位条。
    val rows = remember(state.archiveSegments.size, state.archiveHasMore) {
        val base = state.archiveSegments.flatMap { seg ->
            listOf(ArchiveRow.Header(seg)) + seg.lines.asReversed().map { ArchiveRow.Line(it) }
        }
        if (state.archiveHasMore) base + ArchiveRow.LoadMore else base
    }

    LaunchedEffect(Unit) { state.loadLatestArchive() }

    /**
     * 往上滚 → 加载更早的一段。
     *
     * ## 两个条件缺一不可
     *
     * 1. **最老的一段已经露头**（`maxIdx >= total - 1`）—— 滚到头了；
     * 2. **滚动位置变过**（`pos != lastPos`）—— 用户真的滚了。
     *
     * ★ 第 2 条是**防「一把全拉」**的：
     *   只看第 1 条的话，内容一屏放得下就会连锁触发
     *   （加载一段 → 又露头 → 再加载……），最后**一次把历史全拉下来** ——
     *   那正好违背「小段小段地加载」。
     *   而位置不变（用户没动）就不触发 ⇒ 一次只前进一段。
     *
     * ⚠️ `isScrollInProgress` **不能**单独当「用户滚了」的判据 ——
     *   反向列表的初始布局也会让它短暂为真，于是**刚进页面就自动加载**了下一段。
     */
    var lastPos by remember { mutableStateOf(-1L) }
    LaunchedEffect(listState) {
        snapshotFlow {
            // 行数（含最上头那条「还有更早的」占位条）
            val total = state.archiveSegments.sumOf { 1 + it.lines.size } +
                (if (state.archiveHasMore) 1 else 0)
            val visible = listState.layoutInfo.visibleItemsInfo
            val maxIdx = visible.maxOfOrNull { it.index } ?: 0
            val pos = listState.firstVisibleItemIndex.toLong() * 1_000_000L +
                listState.firstVisibleItemScrollOffset
            // 四个数打包：内容条数 / 最老的露没露头 / 滚动位置 / 布局有没有行
            listOf(total.toLong(), maxIdx.toLong(), pos, visible.size.toLong())
        }.collect { snap ->
            val total = snap[0].toInt()
            val maxIdx = snap[1].toInt()
            val pos = snap[2]
            val visibleCount = snap[3].toInt()
            if (visibleCount == 0) return@collect          // 还没布局出来
            if (lastPos < 0) {
                lastPos = pos          // 第一次只做定位，不触发
            } else if (pos != lastPos && maxIdx >= total - 1) {
                lastPos = pos
                state.loadOlderArchive()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val latest = state.archiveSegments.firstOrNull()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("归档历史", fontWeight = FontWeight.Bold)
                Text(
                    if (latest == null) {
                        "还没有归档段"
                    } else {
                        "最新一段：${formatArchiveTime(latest.createdAt)}" +
                            " · 已加载 ${state.archiveSegments.size} 段"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { state.loadLatestArchive() }) { Text("刷新") }
        }

        Text(
            "对话被压缩后，原文会落成一段归档 —— 这里照常能翻。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (rows.isEmpty()) {
            Text(
                "暂无归档。跑一轮长对话触发压缩后，回这里就能看到被摘要掉的那一段。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(rows.size) { i ->
                when (val row = rows[i]) {
                    is ArchiveRow.Header -> ArchiveSegmentHeader(row.segment)
                    is ArchiveRow.Line -> ChatBubble(row.line.who, row.line.text, primary = row.line.who == "你")
                    is ArchiveRow.LoadMore -> ArchiveLoadMore(state)
                }
            }
        }

        Text(
            when {
                state.archiveLoading -> "正在加载更早的一段…"
                state.archiveHasMore -> "↑ 往上滚，加载更早的一段"
                state.archiveSegments.isEmpty() -> ""
                else -> "已经到最早的一段了"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 列表最上头的「还有更早的」占位条。
 *
 * ★ **为什么要点一下也能加载**：
 *   已加载的内容**一屏放得下**的时候，列表根本滚不动 ——
 *   只靠「滚到顶就自动加载」的话，更早的那几段**永远出不来**。
 *   所以这里给一个可点的入口：滚得动就自动加载，滚不动就点它。
 */
@Composable
private fun ArchiveLoadMore(state: DemoState) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !state.archiveLoading) { state.loadOlderArchive() },
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(
                if (state.archiveLoading) "正在加载更早的一段…" else "↑ 还有更早的（点一下加载）",
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
            )
            Text(
                "往上滚到这条就会自动加载；一次只取一段",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 一段归档的标题条：日期、类型、标题，以及段与段之间的链接。 */
@Composable
private fun ArchiveSegmentHeader(segment: DemoState.ArchiveView) {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(
                formatArchiveTime(segment.createdAt) +
                    " · " + if (segment.kind == "idle") "闲置归档" else "压缩归档",
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
            )
            if (segment.title.isNotBlank()) {
                Text(segment.title, style = MaterialTheme.typography.bodySmall)
            }
            // 段与段的链接：往前一段 / 后一段
            Text(
                buildString {
                    append("段 id ").append(segment.id)
                    append(" · 更早：").append(segment.prevId ?: "（无）")
                    append(" · 更晚：").append(segment.nextId ?: "（无）")
                },
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatArchiveTime(millis: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        .format(java.util.Date(millis))

@Composable
private fun ChatBubble(who: String, text: String, primary: Boolean, warn: Boolean = false) {
    Surface(
        color = when {
            warn -> Color(0xFFF9A825).copy(alpha = 0.25f)
            primary -> MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        },
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(who, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ---------------------------------------------------------------- 公共组件

@Composable
private fun InfoCard(title: String, body: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun ResultCard(r: CommandResult) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${r.verdict.emoji} ${r.title}",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text("退出码 ${r.exitCode} · ${r.elapsedMs}ms", style = MaterialTheme.typography.labelSmall)
            }
            Text(
                r.command,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Surface(color = Color.Black.copy(alpha = 0.3f), shape = RoundedCornerShape(8.dp)) {
                Text(
                    r.output.ifBlank { "(无输出)" },
                    modifier = Modifier.padding(8.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
        }
    }
}
