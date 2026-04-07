package com.goanalyzer.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.goanalyzer.data.ApiClient
import com.goanalyzer.data.GameRecordRepository
import com.goanalyzer.data.GoAnalyzerContainer
import com.goanalyzer.data.ThemeManager
import com.goanalyzer.data.ThemeMode
import kotlinx.coroutines.launch

/**
 * 设置页面 - 连接服务器 + 主题切换
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: GoAnalyzerContainer,
    themeManager: ThemeManager,
    viewModel: SettingsViewModel = viewModel(),
    onBack: () -> Unit
) {
    val savedHost by themeManager.serverHost.collectAsState(initial = "")
    val savedPort by themeManager.serverPort.collectAsState(initial = ThemeManager.DEFAULT_PORT)

    var serverHost by remember(savedHost) {
        mutableStateOf(if (savedHost.isNotBlank()) savedHost else container.serverAddress.value)
    }
    var serverPort by remember(savedPort) {
        mutableIntStateOf(savedPort)
    }
    val scope = rememberCoroutineScope()
    var connecting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    val currentTheme by themeManager.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    val isConnected = container.serverConnected.value

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "设置",
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.3.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ——— 连接状态大卡 ———
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isConnected)
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                    else
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                ),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 状态图标（圆形背景）
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                if (isConnected)
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                else
                                    MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (isConnected) Icons.Default.CheckCircle else Icons.Default.WifiOff,
                            null,
                            modifier = Modifier.size(26.dp),
                            tint = if (isConnected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isConnected) "已连接分析服务" else "未连接",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = if (isConnected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                        if (isConnected) {
                            Text(
                                text = container.serverAddress.value,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        } else {
                            Text(
                                text = "配置服务器地址后点击连接",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }

                    // 连接状态指示点
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                if (isConnected) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                            )
                    )
                }
            }

            // ——— 服务器配置卡 ———
            SettingsSection(title = "分析服务器", icon = Icons.Default.Lan) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = serverHost,
                        onValueChange = {
                            serverHost = it
                            testResult = null
                        },
                        label = { Text("服务器 IP 地址") },
                        placeholder = { Text("例如: 192.168.1.100") },
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = {
                            Icon(Icons.Default.Computer, null,
                                modifier = Modifier.size(20.dp))
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )

                    OutlinedTextField(
                        value = serverPort.toString(),
                        onValueChange = { serverPort = it.toIntOrNull() ?: 8088 },
                        label = { Text("端口") },
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = {
                            Icon(Icons.Default.Numbers, null,
                                modifier = Modifier.size(20.dp))
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )

                    Button(
                        onClick = {
                            scope.launch {
                                connecting = true
                                testResult = null
                                try {
                                    container.apiClient.connect(serverHost, serverPort)
                                    val success = container.apiClient.testConnection()
                                    container.setServerConnected(success, "$serverHost:$serverPort")
                                    if (success) {
                                        themeManager.saveServerSettings(serverHost, serverPort)
                                        testResult = "✓ 连接成功，设置已保存"
                                    } else {
                                        testResult = "✗ 连接失败，请检查地址和网络"
                                    }
                                } catch (e: Exception) {
                                    container.setServerConnected(false)
                                    testResult = "✗ 连接失败: ${e.message}"
                                }
                                connecting = false
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        enabled = serverHost.isNotBlank() && !connecting,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        if (connecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("连接中…")
                        } else {
                            Icon(Icons.Default.Wifi, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("测试连接", fontWeight = FontWeight.SemiBold)
                        }
                    }

                    testResult?.let { result ->
                        val isSuccess = result.startsWith("✓")
                        Surface(
                            color = if (isSuccess)
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            else
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                                    null,
                                    modifier = Modifier.size(16.dp),
                                    tint = if (isSuccess) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = result,
                                    fontSize = 13.sp,
                                    color = if (isSuccess) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            // ——— 外观设置卡 ———
            SettingsSection(title = "外观", icon = Icons.Default.Palette) {
                val themes = listOf(
                    Triple(ThemeMode.SYSTEM, "跟随系统", Icons.Default.BrightnessAuto),
                    Triple(ThemeMode.LIGHT, "浅色", Icons.Default.LightMode),
                    Triple(ThemeMode.DARK, "深色", Icons.Default.DarkMode)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    themes.forEach { (mode, label, icon) ->
                        val selected = currentTheme == mode
                        FilterChip(
                            selected = selected,
                            onClick = {
                                scope.launch { themeManager.setThemeMode(mode) }
                            },
                            label = {
                                Text(
                                    label,
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                )
                            },
                            leadingIcon = {
                                Icon(icon, null, modifier = Modifier.size(16.dp))
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.primary,
                                selectedLeadingIconColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }
            }

            // ——— 使用说明卡 ———
            SettingsSection(title = "使用说明", icon = Icons.Default.HelpOutline) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val steps = listOf(
                        "在电脑上运行 server.py",
                        "确保手机和电脑在同一局域网",
                        "输入电脑 IP 和端口",
                        "点击「测试连接」确认连通",
                        "返回主页面选择 SGF 进行分析"
                    )
                    steps.forEachIndexed { i, step ->
                        Row(verticalAlignment = Alignment.Top) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "${i + 1}",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                step,
                                fontSize = 13.sp,
                                lineHeight = 19.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 1.dp)
                            )
                        }
                    }
                }
            }

            // ——— 内网穿透卡 ———
            SettingsSection(title = "内网穿透方案", icon = Icons.Default.Language) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val tools = listOf(
                        Triple("Tailscale", "免费 · 推荐", Icons.Default.Star),
                        Triple("ZeroTier", "免费 · 虚拟局域网", Icons.Default.Hub),
                        Triple("frp", "自建服务器穿透", Icons.Default.Router),
                        Triple("ngrok", "快速临时暴露", Icons.Default.FlashOn)
                    )
                    tools.forEach { (name, desc, icon) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    icon, null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text(desc, fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

// ——— 通用分组卡片组件 ———
@Composable
private fun SettingsSection(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 分组标题
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 14.dp)
            ) {
                Icon(
                    icon, null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    letterSpacing = 0.2.sp
                )
            }
            content()
        }
    }
}
