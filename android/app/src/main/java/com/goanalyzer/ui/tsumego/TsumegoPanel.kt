package com.goanalyzer.ui.tsumego

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goanalyzer.data.TsumegoAnswer
import com.goanalyzer.data.TsumegoProblem
import com.goanalyzer.data.TsumegoProgress
import com.goanalyzer.ui.analysis.MoveRating
import com.goanalyzer.ui.board.GoBoardState
import com.goanalyzer.ui.board.StoneState
import com.goanalyzer.ui.theme.*


// ============ 死活题过滤模式 ============
enum class TsumegoFilterMode {
    ALL_BAD_MOVES,   // 全部坏手（失误+小失误）
    KEY_BOTTLENECKS  // 只练关键胜负手（严重失误）
}

private val MoveRating.filterModeColor: Color
    get() = when (this) {
        MoveRating.BLUNDER -> BadMoveColor
        MoveRating.MISTAKE -> BadMoveColor.copy(alpha = 0.7f)
        MoveRating.INACCURACY -> ThirdMoveColor
        else -> GoodMoveColor
    }

private val MoveRating.filterModeLabel: String
    get() = when (this) {
        MoveRating.BLUNDER -> "关键胜负手"
        MoveRating.MISTAKE -> "失误"
        MoveRating.INACCURACY -> "小失误"
        else -> ""
    }

// ============ 主入口 ============
@Composable
fun TsumegoPanel(
    problems: List<TsumegoProblem>,
    onJumpToMove: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // 题目为空时
    if (problems.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.CheckCircle,
                    null,
                    modifier = Modifier.size(48.dp),
                    tint = BestMoveColor.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "本局没有明显失误",
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "继续加油！",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
        return
    }

    var vm by remember { mutableStateOf<TsumegoViewModel?>(null) }
    var showPractice by remember { mutableStateOf(false) }
    var filterMode by remember { mutableStateOf(TsumegoFilterMode.KEY_BOTTLENECKS) }

    // 根据过滤模式计算当前显示的题目
    val filteredProblems = remember(problems, filterMode) {
        when (filterMode) {
            TsumegoFilterMode.KEY_BOTTLENECKS -> problems.filter { it.rating == MoveRating.BLUNDER }
            TsumegoFilterMode.ALL_BAD_MOVES -> problems
        }
    }

    // 初始化 VM（使用当前过滤后的题目）
    LaunchedEffect(filteredProblems) {
        if (vm == null || vm!!.allProblems != filteredProblems) {
            vm = TsumegoViewModel(filteredProblems)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (!showPractice) {
            // ===== 题目列表视图（带过滤模式切换）=====
            TsumegoListContent(
                allProblems = problems,
                filteredProblems = filteredProblems,
                filterMode = filterMode,
                onFilterChange = { filterMode = it },
                onStartPractice = {
                    vm?.startPractice()
                    showPractice = true
                },
                onJumpToMove = onJumpToMove,
                modifier = Modifier.weight(1f)
            )
        } else {
            // ===== 练习模式视图 =====
            vm?.let { viewModel ->
                if (viewModel.isAllDone) {
                    TsumegoSummaryContent(
                        progress = viewModel.progress,
                        problems = filteredProblems,
                        onReview = { viewModel.reset() },
                        onExit = {
                            viewModel.exitPractice()
                            showPractice = false
                        }
                    )
                } else {
                    TsumegoPracticeContent(
                        viewModel = viewModel,
                        onExit = {
                            viewModel.exitPractice()
                            showPractice = false
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

// ============ 题目列表内容 ============
@Composable
private fun TsumegoListContent(
    allProblems: List<TsumegoProblem>,
    filteredProblems: List<TsumegoProblem>,
    filterMode: TsumegoFilterMode,
    onFilterChange: (TsumegoFilterMode) -> Unit,
    onStartPractice: () -> Unit,
    onJumpToMove: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val blunderCount = allProblems.count { it.rating == MoveRating.BLUNDER }
    val mistakeCount = allProblems.count { it.rating == MoveRating.MISTAKE }
    val inaccCount = allProblems.count { it.rating == MoveRating.INACCURACY }
    val avgLoss = if (filteredProblems.isNotEmpty()) filteredProblems.map { it.winrateDrop }.average().toFloat() else 0f

    Column(modifier = modifier) {
        // 统计卡片
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = BadMoveColor.copy(alpha = 0.08f)
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // 第一行：标题 + 过滤切换 + 开始按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "共 ${filteredProblems.size} 道练习题",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = BadMoveColor
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "平均损失 ${"%.1f".format(avgLoss)}% 胜率",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = onStartPractice,
                        enabled = filteredProblems.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = BadMoveColor)
                    ) {
                        Icon(Icons.Default.Psychology, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("开始练习")
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 第二行：过滤切换标签
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 全部坏手
                    val isAllSelected = filterMode == TsumegoFilterMode.ALL_BAD_MOVES
                    val allCount = allProblems.size
                    FilterChip(
                        selected = isAllSelected,
                        onClick = { onFilterChange(TsumegoFilterMode.ALL_BAD_MOVES) },
                        label = {
                            Text(
                                "全部 $allCount",
                                fontSize = 12.sp,
                                fontWeight = if (isAllSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        leadingIcon = if (isAllSelected) {
                            { Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp)) }
                        } else null,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = BadMoveColor.copy(alpha = 0.15f),
                            selectedLabelColor = BadMoveColor
                        )
                    )

                    // 关键胜负手（BLUNDER）
                    val isKeySelected = filterMode == TsumegoFilterMode.KEY_BOTTLENECKS
                    FilterChip(
                        selected = isKeySelected,
                        onClick = { onFilterChange(TsumegoFilterMode.KEY_BOTTLENECKS) },
                        label = {
                            Text(
                                "关键 ${blunderCount}",
                                fontSize = 12.sp,
                                fontWeight = if (isKeySelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        leadingIcon = if (isKeySelected) {
                            { Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp)) }
                        } else null,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = BadMoveColor.copy(alpha = 0.2f),
                            selectedLabelColor = BadMoveColor
                        )
                    )

                    // 辅助标签（分布）
                    Spacer(modifier = Modifier.width(4.dp))
                    if (mistakeCount > 0) {
                        TsumegoBadge("${mistakeCount}失误", BadMoveColor.copy(alpha = 0.7f))
                    }
                    if (inaccCount > 0) {
                        TsumegoBadge("${inaccCount}小失误", ThirdMoveColor)
                    }
                }

                // 提示文字
                if (filterMode == TsumegoFilterMode.KEY_BOTTLENECKS && blunderCount == 0) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "本局无严重失误，无需针对性练习",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }

        // 题目列表
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(filteredProblems) { index, problem ->
                TsumegoProblemCard(
                    problem = problem,
                    index = index,
                    onJumpToMove = { onJumpToMove(problem.turnNumber) }
                )
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun TsumegoBadge(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text,
            fontSize = 11.sp,
            color = color,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun TsumegoProblemCard(
    problem: TsumegoProblem,
    index: Int,
    onJumpToMove: () -> Unit
) {
    val ratingColor = problem.rating.color
    val playerText = if (problem.problemPlayerIsBlack) "黑" else "白"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onJumpToMove),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 序号
            Surface(
                color = ratingColor.copy(alpha = 0.15f),
                shape = CircleShape
            ) {
                Text(
                    "#${index + 1}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = ratingColor,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            // 信息
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "第 ${problem.turnNumber} 手",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "($playerText)",
                        fontSize = 12.sp,
                        color = if (problem.problemPlayerIsBlack) Color.Black else Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(
                                if (problem.problemPlayerIsBlack) Color.Black else Color.White,
                                RoundedCornerShape(3.dp)
                            )
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Row {
                    Text(
                        "实际: ${problem.actualMoveStr}",
                        fontSize = 11.sp,
                        color = ratingColor
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "→ 正确: ${problem.correctMoveStr}",
                        fontSize = 11.sp,
                        color = BestMoveColor,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // 损失
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "-${"%.1f".format(problem.winrateDrop)}%",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = ratingColor
                )
                Text(
                    problem.rating.emoji,
                    fontSize = 12.sp
                )
            }
        }
    }
}

// ============ 练习模式内容 ============
@Composable
private fun TsumegoPracticeContent(
    viewModel: TsumegoViewModel,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val problem = viewModel.currentProblem ?: return

    Column(modifier = modifier.fillMaxSize()) {
        // 顶部栏
        TsumegoPracticeTopBar(
            progress = viewModel.progress,
            onExit = onExit
        )

        // 棋盘区域（可点击）
        TsumegoBoard(
            problem = problem,
            userMove = viewModel.userMove,
            showAnswer = viewModel.showAnswer,
            onCellClick = { x, y -> viewModel.placeMove(x, y) },
            onConfirm = { viewModel.confirmMove() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .aspectRatio(1f)
        )

        // 答题反馈区
        AnimatedVisibility(
            visible = viewModel.showAnswer,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut()
        ) {
            TsumegoFeedback(
                problem = problem,
                onNext = { viewModel.nextProblem() },
                onPrev = { viewModel.prevProblem() },
                hasPrev = viewModel.currentIndex > 0,
                hasNext = viewModel.currentIndex < viewModel.allProblems.size - 1,
                answerState = viewModel.getAnswerState(problem.id)
            )
        }

        // 底部操作栏（答题前）
        AnimatedVisibility(
            visible = !viewModel.showAnswer,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                // 题目说明
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${problem.rating.emoji} ${problem.rating.label}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = problem.rating.color
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "轮到 ${if (problem.problemPlayerIsBlack) "黑方" else "白方"} 走",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                "第 ${problem.turnNumber} 手",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "点击棋盘 ${problem.correctMoveStr} 位置落子，然后点击「确认」",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.skipCurrent() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.SkipNext, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("跳过")
                    }
                    Button(
                        onClick = { viewModel.confirmMove() },
                        enabled = viewModel.userMove != null,
                        modifier = Modifier.weight(2f),
                        colors = ButtonDefaults.buttonColors(containerColor = BestMoveColor)
                    ) {
                        Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("确认落子")
                    }
                }
            }
        }
    }
}

@Composable
private fun TsumegoPracticeTopBar(
    progress: TsumegoProgress,
    onExit: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onExit) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "退出练习")
        }
        Text(
            "死活题练习",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.weight(1f))
        // 进度
        Text(
            "${progress.correctCount}/${progress.totalProblems} 正确",
            fontSize = 13.sp,
            color = BestMoveColor,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.width(8.dp))
        LinearProgressIndicator(
            progress = progress.completedCount.toFloat() / progress.totalProblems.coerceAtLeast(1),
            modifier = Modifier
                .width(80.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = BestMoveColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
    }
}

// ============ 可交互棋盘（死活题练习用）============
@Composable
private fun TsumegoBoard(
    problem: TsumegoProblem,
    userMove: Pair<Int, Int>?,
    showAnswer: Boolean,
    onCellClick: (Int, Int) -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier
) {
    val boardState = problem.boardStateBeforeMove
    val boardSize = boardState.boardSize

    BoxWithConstraints(modifier = modifier) {
        val boardPx = kotlin.math.min(
            with(LocalDensity.current) { maxWidth.toPx() },
            with(LocalDensity.current) { maxHeight.toPx() }
        )
        val cellSize = boardPx / boardSize

        Box(Modifier.aspectRatio(1f)) {
            // 底板（棋盘木色）
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { /* 点击由下面处理 */ }
            ) {
                drawBoard(boardSize, cellSize, isDark = false)
            }

            // 落子 + 交互层（可点击）
            TsumegoClickableBoard(
                boardState = boardState,
                boardSize = boardSize,
                userMove = userMove,
                problemPlayerIsBlack = problem.problemPlayerIsBlack,
                showAnswer = showAnswer,
                correctMove = if (showAnswer) problem.correctMove else null,
                onCellClick = onCellClick,
                modifier = Modifier.fillMaxSize()
            )

            // 最后一手的十字标记（如果board state里有）
            boardState.lastMove?.let { last ->
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawLastMoveMarker(last, boardSize, cellSize)
                }
            }
        }
    }
}

private fun DrawScope.drawBoard(boardSize: Int, cellSize: Float, isDark: Boolean) {
    val boardColor = if (isDark) BoardColorDarkMode else BoardColor
    val gridColor = if (isDark) GridColorDark else GridColor

    // 底色
    drawRect(boardColor, size = size)

    // 网格
    val padding = cellSize / 2
    for (i in 0 until boardSize) {
        // 横线
        drawLine(
            gridColor, Offset(padding, padding + i * cellSize),
            Offset(padding + (boardSize - 1) * cellSize, padding + i * cellSize),
            strokeWidth = 1f
        )
        // 竖线
        drawLine(
            gridColor, Offset(padding + i * cellSize, padding),
            Offset(padding + i * cellSize, padding + (boardSize - 1) * cellSize),
            strokeWidth = 1f
        )
    }

    // 星位
    val starPoints = getStarPoints(boardSize)
    for ((sx, sy) in starPoints) {
        drawCircle(
            GridColor, radius = cellSize * 0.12f,
            center = Offset(padding + sx * cellSize, padding + sy * cellSize)
        )
    }
}

private fun getStarPoints(boardSize: Int): List<Pair<Int, Int>> {
    return when (boardSize) {
        19 -> listOf(3 to 3, 9 to 3, 15 to 3, 3 to 9, 9 to 9, 15 to 9, 3 to 15, 9 to 15, 15 to 15)
        13 -> listOf(3 to 3, 6 to 3, 9 to 3, 3 to 6, 6 to 6, 9 to 6, 3 to 9, 6 to 9, 9 to 9)
        9 -> listOf(2 to 2, 4 to 2, 6 to 2, 2 to 4, 4 to 4, 6 to 4, 2 to 6, 4 to 6, 6 to 6)
        else -> emptyList()
    }
}

private fun DrawScope.drawBoardStones(
    boardState: GoBoardState, boardSize: Int, cellSize: Float
) {
    val padding = cellSize / 2
    for (x in 0 until boardSize) {
        for (y in 0 until boardSize) {
            val stone = boardState.get(x, y)
            if (stone == StoneState.EMPTY) continue
            val cx = padding + x * cellSize
            val cy = padding + y * cellSize
            val r = cellSize * 0.46f

            if (stone == StoneState.BLACK) {
                // 黑子：深色填充 + 高光
                drawCircle(BlackStoneColor, r, Offset(cx, cy))
                drawCircle(
                    BlackStoneHighlight.copy(alpha = 0.4f), r * 0.5f,
                    Offset(cx - r * 0.2f, cy - r * 0.25f)
                )
            } else {
                // 白子：白填充 + 阴影
                drawCircle(WhiteStoneColor, r, Offset(cx, cy))
                drawCircle(WhiteStoneShadow, r, Offset(cx, cy))
                drawCircle(WhiteStoneColor, r, Offset(cx, cy))
            }
        }
    }
}

private fun DrawScope.drawUserMove(
    move: Pair<Int, Int>?, playerIsBlack: Boolean, boardSize: Int, cellSize: Float
) {
    if (move == null) return
    val (x, y) = move
    val cx = cellSize / 2 + x * cellSize
    val cy = cellSize / 2 + y * cellSize
    val r = cellSize * 0.42f
    val color = if (playerIsBlack) BlackStoneColor else WhiteStoneColor

    drawCircle(color, r, Offset(cx, cy))
    if (!playerIsBlack) {
        drawCircle(WhiteStoneShadow, r, Offset(cx, cy))
        drawCircle(WhiteStoneColor, r, Offset(cx, cy))
    }
    // 高光
    drawCircle(
        if (playerIsBlack) BlackStoneHighlight.copy(alpha = 0.4f) else BlackStoneColor.copy(alpha = 0.15f),
        r * 0.5f, Offset(cx - r * 0.2f, cy - r * 0.25f)
    )
}

private fun DrawScope.drawCorrectMove(
    correctMove: Pair<Int, Int>, boardSize: Int, cellSize: Float
) {
    val (x, y) = correctMove
    val cx = cellSize / 2 + x * cellSize
    val cy = cellSize / 2 + y * cellSize
    val r = cellSize * 0.35f

    // 绿色圆圈 + 勾
    drawCircle(BestMoveColor, r, Offset(cx, cy), style = Stroke(width = cellSize * 0.08f))
    // 中心点
    drawCircle(BestMoveColor, cellSize * 0.1f, Offset(cx, cy))
}

private fun DrawScope.drawLastMoveMarker(
    last: Pair<Int, Int>, boardSize: Int, cellSize: Float
) {
    val (x, y) = last
    val cx = cellSize / 2 + x * cellSize
    val cy = cellSize / 2 + y * cellSize
    val r = cellSize * 0.08f
    drawCircle(Color.White.copy(alpha = 0.7f), r, Offset(cx, cy))
}

// ============ 答题反馈 ============
@Composable
private fun TsumegoFeedback(
    problem: TsumegoProblem,
    answerState: TsumegoAnswer,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    hasPrev: Boolean,
    hasNext: Boolean
) {
    val isCorrect = answerState == TsumegoAnswer.CORRECT
    val feedbackColor = if (isCorrect) BestMoveColor else BadMoveColor
    val feedbackIcon = if (isCorrect) Icons.Default.CheckCircle else Icons.Default.Cancel
    val feedbackTitle = if (isCorrect) "回答正确！" else "回答错误"
    val feedbackDesc = if (isCorrect) {
        "你找到了正确的着法 ${problem.correctMoveStr}，胜率提升 +${"%.1f".format(problem.winrateDrop)}%"
    } else {
        "正确着法是 ${problem.correctMoveStr}（你选的是 ${problem.actualMoveStr}）"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = feedbackColor.copy(alpha = 0.1f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(feedbackIcon, null, tint = feedbackColor, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    feedbackTitle,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = feedbackColor
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    problem.rating.emoji,
                    fontSize = 20.sp
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                feedbackDesc,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface
            )

            // 显示 PV
            if (problem.pv.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                val pvText = buildPvText(problem.pv, problem.boardSize)
                Text(
                    "后续变化: $pvText",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onPrev,
                    enabled = hasPrev,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.KeyboardArrowLeft, null, modifier = Modifier.size(16.dp))
                    Text("上一题")
                }
                Button(
                    onClick = onNext,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = feedbackColor)
                ) {
                    Text(if (hasNext) "下一题" else "完成")
                    Icon(Icons.Default.KeyboardArrowRight, null, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

private fun buildPvText(pv: List<String>, boardSize: Int): String {
    val limit = kotlin.math.min(pv.size, 8)
    return pv.take(limit).mapIndexed { idx, moveStr ->
        val coord = com.goanalyzer.data.SgfParser.stringToCoordinate(moveStr, boardSize)
        val text = coord?.let {
            com.goanalyzer.data.SgfParser.coordinateToString(it.first, it.second, boardSize)
        } ?: moveStr
        "${if (idx % 2 == 0) "黑" else "白"} $text"
    }.joinToString(" ")
}

// ============ 练习完成总结 ============
@Composable
private fun TsumegoSummaryContent(
    progress: TsumegoProgress,
    problems: List<TsumegoProblem>,
    onReview: () -> Unit,
    onExit: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val accuracy = progress.accuracy
        val accuracyColor = when {
            accuracy >= 0.8f -> BestMoveColor
            accuracy >= 0.5f -> ThirdMoveColor
            else -> BadMoveColor
        }
        val accuracyText = when {
            accuracy >= 0.9f -> "完美！"
            accuracy >= 0.7f -> "很棒！"
            accuracy >= 0.5f -> "还需努力"
            else -> "继续加油"
        }

        // 大圆环
        Box(
            modifier = Modifier.size(140.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                progress = 1f,
                modifier = Modifier.fillMaxSize(),
                strokeWidth = 10.dp,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            )
            CircularProgressIndicator(
                progress = accuracy,
                modifier = Modifier.fillMaxSize(),
                strokeWidth = 10.dp,
                color = accuracyColor
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "${(accuracy * 100).toInt()}%",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = accuracyColor
                )
                Text(
                    accuracyText,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            "练习完成！",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            StatChip("${progress.correctCount}", "正确", BestMoveColor)
            StatChip("${progress.wrongCount}", "错误", BadMoveColor)
            StatChip("${progress.skippedCount}", "跳过", ThirdMoveColor)
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            "共 ${progress.totalProblems} 道题",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onReview,
            colors = ButtonDefaults.buttonColors(containerColor = BestMoveColor)
        ) {
            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("重新练习")
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(onClick = onExit) {
            Text("返回题目列表")
        }
    }
}

@Composable
private fun StatChip(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ============ 可点击棋盘（独立组件，避免 pointerInput 作用域冲突）============
@Composable
private fun TsumegoClickableBoard(
    boardState: GoBoardState,
    boardSize: Int,
    userMove: Pair<Int, Int>?,
    problemPlayerIsBlack: Boolean,
    showAnswer: Boolean,
    correctMove: Pair<Int, Int>?,
    onCellClick: (Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        val boardPx = kotlin.math.min(
            with(LocalDensity.current) { maxWidth.toPx() },
            with(LocalDensity.current) { maxHeight.toPx() }
        )
        val cellSize = boardPx / boardSize

        // 底板
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawBoard(boardSize, cellSize, isDark = false)
        }

        // 落子
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawBoardStones(boardState, boardSize, cellSize)
            drawUserMove(userMove, problemPlayerIsBlack, boardSize, cellSize)
            if (showAnswer && correctMove != null) {
                drawCorrectMove(correctMove, boardSize, cellSize)
            }
        }

        // 最后一手标记
        boardState.lastMove?.let { last ->
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawLastMoveMarker(last, boardSize, cellSize)
            }
        }

        // 点击层（透明，拦截点击事件）
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(boardSize) {
                    detectTapGestures { tapOffset ->
                        val bx = ((tapOffset.x / cellSize).toInt())
                            .coerceIn(0, boardSize - 1)
                        val by = ((tapOffset.y / cellSize).toInt())
                            .coerceIn(0, boardSize - 1)
                        onCellClick(bx, by)
                    }
                }
        ) { }
    }
}
