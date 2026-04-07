package com.goanalyzer.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.goanalyzer.data.*
import com.goanalyzer.ui.analysis.AnalysisDetailContent
import com.goanalyzer.ui.board.computeBoardStates
import com.goanalyzer.ui.screen.FoxwqImportDialog
import com.goanalyzer.ui.theme.*
import com.goanalyzer.ui.tsumego.TsumegoPanel

/**
 * 棋谱分析主页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisScreen(
    viewModel: AnalysisViewModel = viewModel(),
    container: GoAnalyzerContainer,
    navController: NavController,
    onSettingsClick: () -> Unit,
    onHistoryClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val gameState by viewModel.gameState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    var showFoxwqDialog by remember { mutableStateOf(false) }
    var showImportMenu by remember { mutableStateOf(false) }
    val foxwqClient = remember { FoxwqClient() }

    // 监听从历史页面返回的 record ID，自动加载棋谱和分析数据
    val backStackEntry = navController.currentBackStackEntryAsState().value
    val pendingRecordId = backStackEntry?.savedStateHandle?.get<String>("game_record_id")

    LaunchedEffect(pendingRecordId) {
        if (!pendingRecordId.isNullOrEmpty()) {
            viewModel.loadFromRecordId(pendingRecordId, container.gameRecordRepository)
            backStackEntry?.savedStateHandle?.remove<String>("game_record_id")
        }
    }

    val context = LocalContext.current
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.loadSgfFromUri(context, it) }
    }

    val game = gameState.game
    val analysisResult = gameState.analysisResult
    val currentMove = gameState.currentMoveIndex

    // 分析中的进度动画
    val analyzing = uiState is AnalysisUiState.Analyzing

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "AI.SISEN",
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        if (container.serverConnected.value) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(androidx.compose.foundation.shape.CircleShape)
                                            .background(Color(0xFF4CAF50))
                                    )
                                    Text(
                                        "已连接",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onHistoryClick) {
                        Icon(Icons.Default.History, "棋谱记录")
                    }
                },
                actions = {
                    // 导入菜单（本地 SGF + 野狐）
                    Box {
                        IconButton(onClick = { showImportMenu = true }) {
                            Icon(Icons.Default.FileOpen, "导入棋谱")
                        }
                        DropdownMenu(
                            expanded = showImportMenu,
                            onDismissRequest = { showImportMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("导入本地 SGF") },
                                leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
                                onClick = {
                                    showImportMenu = false
                                    filePicker.launch("application/octet-stream")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("野狐导入") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.CloudDownload, null,
                                        tint = Color(0xFFFF7043)
                                    )
                                },
                                onClick = {
                                    showImportMenu = false
                                    showFoxwqDialog = true
                                }
                            )
                        }
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.GridOn, "棋盘") },
                    label = { Text("棋盘", fontSize = 12.sp) }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.ShowChart, "胜率") },
                    label = { Text("胜率", fontSize = 12.sp) }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Psychology, "死活题") },
                    label = { Text("练习", fontSize = 12.sp) }
                )
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    when {
                        analyzing -> viewModel.cancelAnalysis()  // 分析中 → 点击取消
                        container.serverConnected.value -> {
                            viewModel.analyzeCurrentGame(container.apiClient, container.gameRecordRepository)
                        }
                        else -> onSettingsClick()
                    }
                },
                containerColor = when {
                    analyzing -> MaterialTheme.colorScheme.error
                    container.serverConnected.value -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.secondary
                },
                icon = {
                    if (analyzing) {
                        Icon(
                            Icons.Default.Stop,
                            null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onError
                        )
                    } else {
                        Icon(
                            if (container.serverConnected.value) Icons.Default.PlayArrow else Icons.Default.Lan,
                            null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                },
                text = {
                    Text(
                        when {
                            analyzing -> "取消分析"
                            container.serverConnected.value -> "分析"
                            else -> "连接服务器"
                        },
                        fontSize = 13.sp,
                        color = if (analyzing) MaterialTheme.colorScheme.onError
                                else MaterialTheme.colorScheme.onPrimary
                    )
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 未连接警告
            AnimatedVisibility(visible = !container.serverConnected.value) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSettingsClick() }
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.WarningAmber,
                            "未连接",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "未连接分析服务器，点击前往设置",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // 分析进度条（带文字说明）
            AnimatedVisibility(visible = analyzing) {
                val progress = gameState.analysisProgress
                val hasRealProgress = progress != null && progress.second > 0
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                        )
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    if (hasRealProgress) {
                        LinearProgressIndicator(
                            progress = { (progress!!.first.toFloat() / progress.second).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(3.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(3.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Psychology,
                            "AI 分析中",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        val msg = if (hasRealProgress)
                            "${progress!!.third}（${progress.first}/${progress.second}）"
                        else
                            (progress?.third ?: "AI 正在分析棋谱，请稍候...")
                        Text(
                            msg,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // 错误提示（可关闭，友好样式）
            val errorState = uiState as? AnalysisUiState.Error
            AnimatedVisibility(
                visible = errorState != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                errorState?.let {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                Icons.Default.ErrorOutline,
                                "错误",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp).padding(top = 1.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = it.message,
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            IconButton(
                                onClick = { viewModel.clearError() },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    "关闭",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }
            }

            // 棋局信息栏
            if (game != null) {
                CompactGameInfoBar(
                    game = game,
                    analysisResult = analysisResult,
                    gameStats = gameState.gameStats,
                    currentMoveIndex = currentMove
                )
            }

            when (selectedTab) {
                0 -> {
                    // 棋盘+详情 融合页（可滚动）
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        // 棋盘（固定宽度，基于屏幕宽度计算正方形）
                        val boardSizeDp = 360.dp // 合理的默认棋盘尺寸
                        com.goanalyzer.ui.board.GoBoard(
                            game = game,
                            analysisResult = analysisResult,
                            currentMoveIndex = currentMove,
                            showAnalysisOverlay = gameState.analysisOverlayMode != AnalysisOverlayMode.OFF,
                            analysisOverlayMode = gameState.analysisOverlayMode,
                            pvPreviewMoves = gameState.pvPreviewMoves,
                            pvPreviewStep = gameState.pvPreviewStep,
                            pvPreviewVisible = gameState.pvPreviewVisible,
                            onMoveClick = { viewModel.jumpToMove(it) },
                            modifier = Modifier
                                .fillMaxWidth()
                        )

                        // 分析控制区
                        AnalysisControlBar(
                            overlayMode = gameState.analysisOverlayMode,
                            hasAnalysis = analysisResult != null,
                            onCycleOverlay = { viewModel.cycleOverlayMode() }
                        )

                        // PV 预览控制
                        AnimatedVisibility(
                            visible = gameState.pvPreviewVisible && gameState.pvPreviewMoves.isNotEmpty(),
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            PvPreviewControl(
                                totalSteps = gameState.pvPreviewMoves.size,
                                currentStep = gameState.pvPreviewStep,
                                onStepChange = { viewModel.stepPvPreview(it) },
                                onClose = { viewModel.hidePvPreview() }
                            )
                        }

                        // 棋谱导航
                        com.goanalyzer.ui.board.GoBoardNavigation(
                            totalMoves = game?.moves?.count { !it.isSetup } ?: 0,
                            currentMoveIndex = currentMove,
                            onMoveChange = { viewModel.jumpToMove(it) },
                            showBlunderJump = analysisResult != null,
                            onNextBlunder = { viewModel.jumpToNextBlunder() },
                            onPrevBlunder = { viewModel.jumpToPrevBlunder() }
                        )

                        // 分隔线
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        )

                        // 详情内容（胜率变化 + AI推荐候选手）
                        AnalysisDetailContent(
                            game = game,
                            analysisResult = analysisResult,
                            currentMoveIndex = currentMove,
                            onPvPreviewClick = { candidate ->
                                viewModel.showPvPreview(candidate, game?.boardSize ?: 19)
                            },
                            onJumpToMove = { viewModel.jumpToMove(it) },
                            pvPreviewVisible = gameState.pvPreviewVisible
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
                1 -> {
                    // 胜率曲线 Tab
                    com.goanalyzer.ui.chart.WinrateChart(
                        analysisResult = analysisResult,
                        currentMoveIndex = currentMove,
                        onMoveClick = { viewModel.jumpToMove(it) },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    com.goanalyzer.ui.board.GoBoardNavigation(
                        totalMoves = game?.moves?.count { !it.isSetup } ?: 0,
                        currentMoveIndex = currentMove,
                        onMoveChange = { viewModel.jumpToMove(it) },
                        showBlunderJump = analysisResult != null,
                        onNextBlunder = { viewModel.jumpToNextBlunder() },
                        onPrevBlunder = { viewModel.jumpToPrevBlunder() }
                    )
                }
                2 -> {
                    // 死活题练习 Tab
                    if (analysisResult != null && game != null) {
                        val tsumegoProblems = remember(analysisResult) {
                            val boardStates = computeBoardStates(game)
                            generateTsumegoProblems(game, analysisResult, boardStates)
                        }
                        TsumegoPanel(
                            problems = tsumegoProblems,
                            onJumpToMove = { turnNumber ->
                                viewModel.jumpToMove(turnNumber)
                                selectedTab = 0  // 跳转到棋盘 Tab
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        // 未分析时
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.Lightbulb,
                                    null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    "请先分析棋谱",
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "分析完成后才能生成练习题",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 分析完成摘要弹窗
    val summary = gameState.analysisSummary
    if (summary != null) {
        AnalysisSummaryDialog(
            summary = summary,
            onDismiss = { viewModel.dismissAnalysisSummary() },
            onJumpToFirstBlunder = { viewModel.jumpToFirstBlunder() }
        )
    }

    // 欢迎界面（无棋谱时全屏覆盖）
    if (game == null) {
        WelcomeOverlay(
            onPickFile = { filePicker.launch("application/octet-stream") },
            onFoxwqImport = { showFoxwqDialog = true }
        )
    }

    // 野狐导入对话框
    if (showFoxwqDialog) {
        FoxwqImportDialog(
            foxwqClient = foxwqClient,
            followRepository = container.foxwqFollowRepository,
            onDismiss = { showFoxwqDialog = false },
            onGameImported = { sgfContent, gameName ->
                viewModel.loadSgfFromFoxwq(sgfContent, gameName)
            }
        )
    }
}

// ============ 对局详情信息栏 ============
@Composable
private fun CompactGameInfoBar(
    game: SgfGame,
    analysisResult: AnalysisResult?,
    gameStats: com.goanalyzer.data.GameStats?,
    currentMoveIndex: Int
) {
    val turnIndex = currentMoveIndex - 1
    val moveAnalysis = analysisResult?.moves?.getOrNull(turnIndex)
    val winratePct = moveAnalysis?.winrate?.times(100)
    val totalMoves = game.moves.count { !it.isSetup }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 第一行：棋手名对决 + 结果徽章
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 黑方
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(Color(0xFF1A1A1A))
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Column {
                        Text(
                            text = game.blackName.ifEmpty { "黑方" },
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (game.blackRank.isNotEmpty()) {
                            Text(
                                text = game.blackRank,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                }

                // vs 分隔
                Text(
                    "vs",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                // 白方
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.weight(1f)
                ) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = game.whiteName.ifEmpty { "白方" },
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (game.whiteRank.isNotEmpty()) {
                            Text(
                                text = game.whiteRank,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(5.dp))
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(Color(0xFFF5F5F5))
                        )
                    }
                }

                // 结果徽章
                if (game.result.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(10.dp))
                    val isBlackWin = game.result.contains("B", ignoreCase = true) ||
                            game.result.contains("黑", ignoreCase = true)
                    Surface(
                        color = if (isBlackWin)
                            Color(0xFF1A1A1A)
                        else
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = game.result,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isBlackWin) Color.White else MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            // 胜率进度条（仅有分析时显示）
            if (winratePct != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // 黑方胜率标签
                    Text(
                        "黑 ${"%.0f".format(winratePct)}%",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            winratePct > 55 -> GoodMoveColor
                            winratePct < 45 -> BadMoveColor
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    // 双色进度条
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth((winratePct / 100f).toFloat().coerceIn(0f, 1f))
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(Color(0xFF1A1A1A), Color(0xFF404040))
                                    )
                                )
                        )
                    }
                    Text(
                        "${"%.0f".format(100 - winratePct)}%",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            winratePct < 45 -> GoodMoveColor
                            winratePct > 55 -> BadMoveColor
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            // 第三行：统计标签行
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 提子信息
                if (gameStats != null) {
                    InfoChip(
                        label = "黑提 ${gameStats.blackCaptures}",
                        containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        textColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    InfoChip(
                        label = "白提 ${gameStats.whiteCaptures}",
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                        textColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // 手数
                InfoChip(
                    label = "$currentMoveIndex / ${totalMoves}手",
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                    textColor = MaterialTheme.colorScheme.primary
                )
                // 贴目
                InfoChip(
                    label = "贴${game.komi}",
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 棋盘
                InfoChip(
                    label = "${game.boardSize}路",
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun InfoChip(label: String, containerColor: Color, textColor: Color) {
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = textColor,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

// ============ 分析控制栏（紧凑） ============
@Composable
private fun AnalysisControlBar(
    overlayMode: AnalysisOverlayMode,
    hasAnalysis: Boolean,
    onCycleOverlay: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // AI 叠加模式切换 Chip
        val modeLabel = when (overlayMode) {
            AnalysisOverlayMode.OFF -> "AI 分析"
            AnalysisOverlayMode.BASIC -> "序号模式"
            AnalysisOverlayMode.HEATMAP -> "热度模式"
        }
        val modeIcon = when (overlayMode) {
            AnalysisOverlayMode.OFF -> Icons.Default.VisibilityOff
            AnalysisOverlayMode.BASIC -> Icons.Default.FormatListNumbered
            AnalysisOverlayMode.HEATMAP -> Icons.Default.Whatshot
        }
        val modeSelected = overlayMode != AnalysisOverlayMode.OFF

        FilterChip(
            selected = modeSelected,
            onClick = onCycleOverlay,
            label = { Text(modeLabel, fontSize = 12.sp) },
            leadingIcon = {
                Icon(modeIcon, null, modifier = Modifier.size(15.dp))
            },
            enabled = hasAnalysis,
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.primary,
                selectedLeadingIconColor = MaterialTheme.colorScheme.primary
            ),
            shape = RoundedCornerShape(10.dp)
        )

        // 未分析时的提示
        if (!hasAnalysis) {
            Text(
                "分析后可查看 AI 推荐手",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
    }
}

// ============ PV 预览控制 ============
@Composable
private fun PvPreviewControl(
    totalSteps: Int,
    currentStep: Int,
    onStepChange: (Int) -> Unit,
    onClose: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            IconButton(onClick = { onStepChange(0) }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.FirstPage, "起点", modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = { onStepChange(maxOf(0, currentStep - 1)) }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.KeyboardArrowLeft, "退一步", modifier = Modifier.size(16.dp))
            }
            Text(
                text = "PV $currentStep/$totalSteps",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
            IconButton(onClick = { onStepChange(minOf(totalSteps, currentStep + 1)) }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.KeyboardArrowRight, "进一步", modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = { onStepChange(totalSteps) }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.LastPage, "最后", modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, "关闭", modifier = Modifier.size(16.dp))
            }
        }
    }
}

// ============ 欢迎页（精美插画风格） ============
@Composable
private fun WelcomeOverlay(
    onPickFile: () -> Unit,
    onFoxwqImport: () -> Unit,
) {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = if (isDark) listOf(
                        Color(0xFF0D1B0E),
                        Color(0xFF121212),
                        Color(0xFF1A1F1A)
                    ) else listOf(
                        Color(0xFFE8F5E9),
                        Color(0xFFF8F9FA),
                        Color(0xFFF0F7F0)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // ——— Logo 区域 ———
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(
                        Brush.radialGradient(
                            colors = if (isDark) listOf(
                                Color(0xFF2E7D32),
                                Color(0xFF1B5E20),
                                Color(0xFF0D2E0E)
                            ) else listOf(
                                Color(0xFF66BB6A),
                                Color(0xFF2E7D32),
                                Color(0xFF1B5E20)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                // 精致棋盘图案
                Canvas(modifier = Modifier.size(88.dp)) {
                    val pad = 10f
                    val sz = size.width - pad * 2
                    val cell = sz / 5f

                    // 木纹底色
                    drawRect(
                        color = Color(0xFFDCB35C).copy(alpha = if (isDark) 0.15f else 0.25f),
                        topLeft = androidx.compose.ui.geometry.Offset(pad, pad),
                        size = androidx.compose.ui.geometry.Size(sz, sz)
                    )

                    // 网格线
                    val lineColor = Color.White.copy(alpha = 0.35f)
                    for (i in 0..5) {
                        drawLine(lineColor,
                            Offset(pad, pad + i * cell),
                            Offset(pad + sz, pad + i * cell), 1f)
                        drawLine(lineColor,
                            Offset(pad + i * cell, pad),
                            Offset(pad + i * cell, pad + sz), 1f)
                    }

                    // 棋子（白棋带光泽）
                    val stones = listOf(
                        Triple(1, 1, true), Triple(2, 1, false),
                        Triple(2, 2, true), Triple(3, 2, false),
                        Triple(1, 3, false), Triple(3, 3, true),
                        Triple(4, 1, true), Triple(4, 3, false)
                    )
                    stones.forEach { (col, row, isBlack) ->
                        val cx = pad + col * cell
                        val cy = pad + row * cell
                        val r = cell * 0.38f
                        if (isBlack) {
                            drawCircle(Color(0xFF0A0A0A), r + 1.5f, Offset(cx + 1.5f, cy + 1.5f)) // shadow
                            drawCircle(
                                Brush.radialGradient(
                                    listOf(Color(0xFF404040), Color(0xFF111111)),
                                    center = Offset(cx - r * 0.3f, cy - r * 0.3f),
                                    radius = r * 1.5f
                                ), r, Offset(cx, cy)
                            )
                        } else {
                            drawCircle(Color(0x40000000), r + 1.5f, Offset(cx + 1.5f, cy + 1.5f)) // shadow
                            drawCircle(
                                Brush.radialGradient(
                                    listOf(Color.White, Color(0xFFD8D8D8)),
                                    center = Offset(cx - r * 0.3f, cy - r * 0.35f),
                                    radius = r * 1.5f
                                ), r, Offset(cx, cy)
                            )
                            // 白棋高光
                            drawCircle(Color.White.copy(alpha = 0.6f), r * 0.35f,
                                Offset(cx - r * 0.28f, cy - r * 0.30f))
                        }
                    }

                    // AI 推荐标记（绿色圆圈）
                    val markCx = pad + 3 * cell
                    val markCy = pad + 1 * cell
                    drawCircle(Color(0xFF4CAF50).copy(alpha = 0.85f), cell * 0.38f, Offset(markCx, markCy), style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // ——— 标题 ———
            Text(
                "AI.SISEN",
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                "AI 驱动的围棋复盘分析",
                fontSize = 14.sp,
                letterSpacing = 0.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(36.dp))

            // ——— 主操作按钮 ———
            // SGF 文件导入（主要）
            Button(
                onClick = onPickFile,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 4.dp,
                    pressedElevation = 1.dp
                )
            ) {
                Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text("选择 SGF 棋谱", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 野狐导入（次要）
            OutlinedButton(
                onClick = onFoxwqImport,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(
                    1.5.dp,
                    Color(0xFFFF7043)
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color(0xFFFF7043)
                )
            ) {
                Icon(Icons.Default.CloudDownload, null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text("从野狐导入棋谱", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }

            Spacer(modifier = Modifier.height(28.dp))

            // ——— 功能特性标签 ———
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FeatureTag(Icons.Default.AutoGraph, "AI 复盘")
                FeatureTag(Icons.Default.ShowChart, "胜率曲线")
                FeatureTag(Icons.Default.Psychology, "死活练习")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "支持标准 SGF · 野狐 · 本地文件",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                letterSpacing = 0.3.sp
            )
        }
    }
}

@Composable
private fun FeatureTag(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f))
            Spacer(modifier = Modifier.width(5.dp))
            Text(text, fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f))
        }
    }
}

// ============ 分析完成摘要弹窗 ============
@Composable
private fun AnalysisSummaryDialog(
    summary: AnalysisSummary,
    onDismiss: () -> Unit,
    onJumpToFirstBlunder: () -> Unit
) {
    val totalErrors = summary.blunderCount + summary.mistakeCount
    val comment = when {
        totalErrors == 0 && summary.inaccuracyCount == 0 -> "🏆 非常出色！几乎无失误的对局。"
        totalErrors == 0 -> "😊 整体不错，仅有少量小偏差。"
        totalErrors <= 3 -> "💪 总体不错，有 $totalErrors 处值得复盘。"
        totalErrors <= 8 -> "📝 有 $totalErrors 处失误，建议重点复盘。"
        else -> "💡 有 $totalErrors 处失误，建议逐手仔细复盘。"
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()) {
                Text("🎯", fontSize = 32.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "分析完成",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    "共分析 ${summary.totalMoves} 手棋",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center
                )
            }
        },
        text = {
            Column {
                // 评级统计网格
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    SummaryStatItem(
                        emoji = "🤖", label = "AI之着",
                        count = summary.bestMoveCount, color = BestMoveColor
                    )
                    SummaryStatItem(
                        emoji = "✅", label = "好手",
                        count = summary.goodMoveCount, color = GoodMoveColor
                    )
                    SummaryStatItem(
                        emoji = "⚠️", label = "小失误",
                        count = summary.inaccuracyCount, color = ThirdMoveColor
                    )
                    SummaryStatItem(
                        emoji = "🔴", label = "失误",
                        count = summary.mistakeCount, color = BadMoveColor.copy(alpha = 0.85f)
                    )
                    SummaryStatItem(
                        emoji = "🚨", label = "严重",
                        count = summary.blunderCount, color = BadMoveColor
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 分隔线
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )

                Spacer(modifier = Modifier.height(14.dp))

                // 评语卡片
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Psychology,
                            null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = comment,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 4.dp, end = 4.dp)
            ) {
                if (summary.firstBlunderMove != null) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("关闭")
                    }
                    Button(
                        onClick = onJumpToFirstBlunder,
                        colors = ButtonDefaults.buttonColors(containerColor = BadMoveColor),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Navigation, null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("查看第一失误")
                    }
                } else {
                    Button(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("开始复盘")
                    }
                }
            }
        }
    )
}

@Composable
private fun SummaryStatItem(emoji: String, label: String, count: Int, color: Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(emoji, fontSize = 18.sp)
        Surface(
            color = color.copy(alpha = 0.12f),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                "$count",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = color,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
        Text(
            label,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            fontWeight = FontWeight.Medium
        )
    }
}
