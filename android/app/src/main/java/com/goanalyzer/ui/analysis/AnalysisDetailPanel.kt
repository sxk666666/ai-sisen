package com.goanalyzer.ui.analysis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import com.goanalyzer.data.*
import com.goanalyzer.ui.theme.*

// ============ 统一着手评级系统（对齐 AI Sensei 5 级）============

/** 开局忽略阈值：前 N 手不计胜率损失（开局胜率波动大，无参考意义） */
private const val OPENING_IGNORE_THRESHOLD = 10

/**
 * 着手质量评级（基于胜率损失，阈值单位为百分比）
 * 胜率以黑方视角（0=白赢，1=黑赢）
 */
enum class MoveRating(val label: String, val emoji: String, val color: Color, val bgColor: Color) {
    BEST_MOVE("AI之着", "\u2728", BestMoveColor, BestMoveColor.copy(alpha = 0.15f)),       // 胜率损失 ≈ 0%，且等于 AI 首选
    GOOD_MOVE("好手", "\u2705", GoodMoveColor, GoodMoveColor.copy(alpha = 0.15f)),          // 胜率损失 0% ~ 2%
    INACCURACY("小失误", "\u26A0\uFE0F", ThirdMoveColor, ThirdMoveColor.copy(alpha = 0.15f)),       // 胜率损失 2% ~ 5%（非首选）
    MISTAKE("失误", "\uD83D\uDCDD", BadMoveColor.copy(alpha = 0.9f), BadMoveColor.copy(alpha = 0.15f)),  // 胜率损失 5% ~ 10%
    BLUNDER("严重失误", "\uD83D\uDEA8", BadMoveColor, BadMoveColor.copy(alpha = 0.15f))    // 胜率损失 > 10%
}

/**
 * 从 moveInfos 中查找实际着法的胜率
 * KataGo 返回的 moveInfos 已按胜率降序排列，包含所有候选着法及其胜率。
 * @param moveInfos KataGo 返回的候选着法列表
 * @param actualMove 实际落子坐标（如 "D4"）
 * @return 实际着法的胜率（黑视角，0-1），找不到则返回 null
 */
private fun findActualMoveWinrate(moveInfos: List<CandidateMove>, actualMove: String): Float? {
    if (actualMove.isEmpty() || moveInfos.isEmpty()) return null
    // KataGo 坐标格式与 SGF 一致（字母+数字），直接比较
    return moveInfos.find { it.move.equals(actualMove, ignoreCase = true) }?.winrate
}

/**
 * 计算胜率损失（黑方视角，百分比）
 * 在 KataGo 的 moveInfos 中，最佳着法是 moveInfos[0]（胜率最高）。
 * 胜率损失 = |bestWinrate - actualMoveWinrate|。
 * 若实际着法不在 moveInfos 中（pass 等），返回 null。
 *
 * @param moveInfos KataGo 候选着法列表
 * @param actualMove 实际落子坐标（如 "D4"）
 * @param isBlackMove 是否为黑方走棋（仅用于兼容性保留）
 * @return 胜率损失百分比（如 8.5 表示下降 8.5%），若无法计算则返回 null
 */
fun calcWinrateLoss(moveInfos: List<CandidateMove>, actualMove: String, isBlackMove: Boolean): Float? {
    if (moveInfos.isEmpty() || actualMove.isEmpty()) return null
    val bestMove = moveInfos[0]
    val bestWr = bestMove.winrate
    val actualWr = findActualMoveWinrate(moveInfos, actualMove)
        ?: return null  // 无法找到实际着法（pass 等情况）
    // 损失 = |bestWinrate - actualWinrate|（始终取绝对值，损失就是损失）
    return kotlin.math.abs(bestWr - actualWr) * 100f
}

/**
 * 判定着手等级
 * @param winrateDrop 胜率损失百分比（黑视角，正数=黑方损失，负数=黑方获益）
 * @param isBestMove 实际着法是否就是 AI 首选
 * @param moveNumber 当前是第几手，开局前 N 手忽略胜率损失
 */
fun classifyMove(winrateDrop: Float, isBestMove: Boolean, moveNumber: Int = 0): MoveRating {
    return when {
        // AI 之着：等于首选
        isBestMove -> MoveRating.BEST_MOVE
        // 开局忽略：前 N 手无论损失多少都算好手
        moveNumber > 0 && moveNumber <= OPENING_IGNORE_THRESHOLD -> MoveRating.GOOD_MOVE
        // 严重失误：损失 > 10%
        winrateDrop > 10f -> MoveRating.BLUNDER
        // 失误：损失 > 5%
        winrateDrop > 5f -> MoveRating.MISTAKE
        // 小失误：损失 2% ~ 5%
        winrateDrop > 2f -> MoveRating.INACCURACY
        // 好手：损失 0% ~ 2%
        else -> MoveRating.GOOD_MOVE
    }
}

/**
 * 着手评级标签组件（带彩色背景，类似 AI Sensei）
 */
@Composable
fun MoveRatingBadge(
    rating: MoveRating,
    modifier: Modifier = Modifier,
    showEmoji: Boolean = true,
    showLabel: Boolean = true
) {
    Surface(
        color = rating.bgColor,
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (showEmoji) {
                Text(rating.emoji, fontSize = 12.sp)
            }
            if (showLabel) {
                Text(
                    text = rating.label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = rating.color
                )
            }
        }
    }
}

/** 兼容旧调用方式（内部使用 calcWinrateLoss） */
fun classifyMove(
    playerChange: Float,
    bestWinrate: Float? = null,
    currentWinrate: Float? = null,
    actualMove: String = "",
    bestMove: String = ""
): MoveRating {
    // 已废弃：改用 calcWinrateLoss + classifyMove(winrateDrop, isBestMove)
    val isBest = actualMove.isNotEmpty() && bestMove.isNotEmpty() && actualMove.equals(bestMove, ignoreCase = true)
    return classifyMove(kotlin.math.abs(playerChange), isBest)
}

/**
 * 分析结果详情面板
 */
@Composable
fun AnalysisDetailPanel(
    game: SgfGame?,
    analysisResult: AnalysisResult?,
    currentMoveIndex: Int,
    onPvPreviewClick: (CandidateMove) -> Unit = {},
    onJumpToMove: (Int) -> Unit = {},
    pvPreviewVisible: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (game == null || analysisResult == null) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.VisibilityOff,
                    "暂无分析",
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text("请先分析棋谱", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "点击右下角按钮开始 AI 分析",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    fontSize = 12.sp
                )
            }
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        AnalysisDetailContent(
            game = game,
            analysisResult = analysisResult,
            currentMoveIndex = currentMoveIndex,
            onPvPreviewClick = onPvPreviewClick,
            onJumpToMove = onJumpToMove,
            pvPreviewVisible = pvPreviewVisible
        )
    }
}

/**
 * 详情内容（不含外层滚动和空状态，可嵌入其他页面）
 */
@Composable
fun AnalysisDetailContent(
    game: SgfGame?,
    analysisResult: AnalysisResult?,
    currentMoveIndex: Int,
    onPvPreviewClick: (CandidateMove) -> Unit = {},
    onJumpToMove: (Int) -> Unit = {},
    pvPreviewVisible: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (game == null || analysisResult == null) return

    // 计算所有失误点
    val blunders = remember(analysisResult) {
        val list = mutableListOf<BlunderInfo>()
        for (i in 1 until analysisResult.moves.size) {
            val moveData = analysisResult.moves[i]
            if (moveData.move.isEmpty()) continue
            // 使用服务端计算的 isBestMove 字段
            val isBestMove = moveData.isBestMove
            val bestMoveStr = moveData.moveInfos.firstOrNull()?.move ?: ""
            val loss = calcWinrateLoss(moveData.moveInfos, moveData.move, moveData.isBlackMove) ?: continue
            val moveNumber = i + 1
            val rating = classifyMove(loss, isBestMove, moveNumber)
            // 复盘只显示失误和严重失误（去掉小失误）
            if (rating == MoveRating.BLUNDER || rating == MoveRating.MISTAKE) {
                list.add(BlunderInfo(
                    turnNumber = i + 1,
                    actualMove = moveData.move,
                    bestMove = bestMoveStr,
                    winrateChange = -loss / 100f,  // 负数表示损失
                    severity = rating.label,
                    rating = rating
                ))
            }
        }
        list
    }

    var blundersExpanded by remember { mutableStateOf(false) }

    val turnIndex = currentMoveIndex - 1
    val moveAnalysis = analysisResult.moves.getOrNull(turnIndex)
    val currentMove = game.moves.getOrNull(turnIndex)

    Column(modifier = modifier.padding(horizontal = 12.dp)) {
        if (moveAnalysis != null && currentMove != null) {
            // 顶部手数信息卡
            MoveInfoCard(currentMove, moveAnalysis, game.boardSize, turnIndex > 0, analysisResult)

            Spacer(modifier = Modifier.height(8.dp))

            // 胜率变化条
            if (turnIndex > 0 && turnIndex < analysisResult.moves.size) {
                val prevMove = analysisResult.moves.getOrNull(turnIndex - 1)
                if (prevMove != null) {
                    WinrateChangeBar(moveAnalysis, prevMove, currentMove)
                }

                // AI 简评（当此手为失误或小失误时）
                // 使用服务端计算的 isBestMove 字段
                val isBestMove = moveAnalysis.isBestMove
                val mLoss = calcWinrateLoss(moveAnalysis.moveInfos, moveAnalysis.move, currentMove.isBlack)
                if (mLoss != null) {
                    val mRating = classifyMove(mLoss, isBestMove, currentMoveIndex)
                    // 当前手显示失误/严重失误提示（不再包含小失误）
                    if (moveAnalysis.moveInfos.isNotEmpty() && (mRating == MoveRating.BLUNDER || mRating == MoveRating.MISTAKE)) {
                        Spacer(modifier = Modifier.height(6.dp))
                        val bestMove = moveAnalysis.moveInfos.firstOrNull()
                        if (bestMove != null) {
                            AiInsightCard(
                                actualMove = currentMove,
                                bestMove = bestMove,
                                winrateChange = -mLoss,  // 负数表示损失
                                rating = mRating,
                                boardSize = game.boardSize
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            }

            // 棋谱注释
            if (currentMove.comment.isNotEmpty()) {
                CommentCard(currentMove.comment)
                Spacer(modifier = Modifier.height(8.dp))
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // 失误总览卡片
            if (blunders.isNotEmpty()) {
                BlunderSummaryCard(
                    blunders = blunders,
                    expanded = blundersExpanded,
                    currentMoveIndex = currentMoveIndex,
                    boardSize = game.boardSize,
                    onToggle = { blundersExpanded = !blundersExpanded },
                    onJumpToMove = onJumpToMove
                )

                Spacer(modifier = Modifier.height(8.dp))

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
            }

            // AI 推荐标题
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "AI 推荐",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(6.dp))
                if (pvPreviewVisible) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Text("预览中", fontSize = 10.sp, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                    }
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "点击预览变化图",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 候选手列表
            if (moveAnalysis.moveInfos.isNotEmpty()) {
                moveAnalysis.moveInfos.take(5).forEachIndexed { index, candidate ->
                    // 安全获取第一项的 visits，避免空列表崩溃
                    val firstVisits = moveAnalysis.moveInfos.firstOrNull()?.visits ?: 1
                    CandidateMoveRow(
                        rank = index + 1,
                        move = candidate.move,
                        winrate = candidate.winrate,
                        visits = candidate.visits,
                        visitsRatio = if (firstVisits > 0) {
                            candidate.visits.toFloat() / firstVisits
                        } else 0f,
                        isBest = index == 0,
                        boardSize = game.boardSize,
                        onClick = { onPvPreviewClick(candidate) }
                    )
                }
            }

            // AI 推荐变化图
            if (moveAnalysis.pv.size >= 4) {
                Spacer(modifier = Modifier.height(4.dp))
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                PvTextPreview(moveAnalysis.pv, game.boardSize)
            }
        } else {
            // 当前手数为0，提示翻棋
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "← → 浏览棋谱查看分析",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ============ 手数信息卡 ============
@Composable
private fun MoveInfoCard(
    currentMove: SgfMove,
    moveAnalysis: MoveAnalysis,
    boardSize: Int,
    hasPrev: Boolean,
    analysisResult: AnalysisResult
) {
    val coord = if (currentMove.isPass) "Pass"
                else SgfParser.coordinateToString(currentMove.x, currentMove.y, boardSize)

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 棋子颜色
                Surface(
                    color = if (currentMove.isBlack) Color(0xFF1A1A1A) else Color(0xFFF5F5F5),
                    shape = MaterialTheme.shapes.extraSmall
                ) {
                    Box(modifier = Modifier.size(20.dp))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text("第 ${moveAnalysis.turnNumber} 手", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(coord, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.primary)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 数据行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatItem("搜索", "${moveAnalysis.visits}")
                StatItem("目数差", "${"%.1f".format(moveAnalysis.lead)}")
                val wr = moveAnalysis.winrate * 100
                StatItem("黑胜率", "${"%.1f".format(wr)}%", color = when {
                    wr > 60 -> GoodMoveColor
                    wr < 40 -> BadMoveColor
                    else -> MaterialTheme.colorScheme.onSurface
                })
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = color)
    }
}

// ============ 胜率变化条 ============
@Composable
private fun WinrateChangeBar(
    moveAnalysis: MoveAnalysis,
    prevMove: MoveAnalysis,
    currentMove: SgfMove
) {
    // 使用服务端计算的 isBestMove 字段
    val isBestMove = moveAnalysis.isBestMove
    val loss = calcWinrateLoss(moveAnalysis.moveInfos, moveAnalysis.move, currentMove.isBlack) ?: 0f
    val rating = classifyMove(loss, isBestMove)

    // 显示：负数表示损失（如 "-8.50%"）
    val changeText = "${"%.2f".format(-loss)}%"
    val changeColor = rating.color

    // 胜率变化可视化条（损失越大条越长）
    val barWidth = (loss.coerceIn(0f, 15f) / 15f * 50).coerceAtLeast(0f)

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = MaterialTheme.shapes.small
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("此手评价", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.weight(1f))
                // 评级标签
                MoveRatingBadge(rating = rating)
                Text(changeText, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = changeColor)
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 可视化条
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                // 背景
                Surface(
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    shape = MaterialTheme.shapes.extraSmall
                ) {}
                // 损失条（从右往左填充）
                val lossWidth = ((loss.coerceIn(0f, 15f) / 15f))
                if (lossWidth > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(lossWidth)
                            .height(6.dp)
                            .align(Alignment.CenterEnd),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = changeColor,
                            shape = MaterialTheme.shapes.extraSmall
                        ) {}
                    }
                }
            }
        }
    }
}

// ============ 注释卡片 ============
@Composable
private fun CommentCard(comment: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = MaterialTheme.shapes.small
    ) {
        Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text("\uD83D\uDCAC ", fontSize = 12.sp)
            Text(text = comment, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp)
        }
    }
}

// ============ 候选手行 ============
@Composable
private fun CandidateMoveRow(
    rank: Int,
    move: String,
    winrate: Float,
    visits: Int,
    visitsRatio: Float,
    isBest: Boolean,
    boardSize: Int = 19,
    onClick: () -> Unit = {}
) {
    val coord = SgfParser.stringToCoordinate(move, boardSize)
    val rankColors = listOf(BestMoveColor, SecondMoveColor, ThirdMoveColor, FourthMoveColor, FifthMoveColor)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isBest) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(if (isBest) 2.dp else 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 排名徽章
            val rankColor = rankColors.getOrElse(rank - 1) { FifthMoveColor }
            Surface(
                color = rankColor,
                shape = MaterialTheme.shapes.extraSmall
            ) {
                Text(
                    "$rank",
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 坐标
            Text(
                text = coord?.let { SgfParser.coordinateToString(it.first, it.second, boardSize) } ?: move,
                fontWeight = if (isBest) FontWeight.Bold else FontWeight.Normal,
                fontSize = 14.sp,
                modifier = Modifier.width(36.dp)
            )

            // 访问比例进度条
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.extraSmall
                ) {}
                Surface(
                    modifier = Modifier.fillMaxWidth(visitsRatio.coerceIn(0f, 1f)).height(4.dp),
                    color = rankColor,
                    shape = MaterialTheme.shapes.extraSmall
                ) {}
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 胜率
            Text(
                text = "${"%.1f".format(winrate * 100)}%",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.width(2.dp))

            // 访问数
            Text(
                text = "$visits",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.width(4.dp))

            // 播放按钮
            Icon(
                Icons.Default.PlayArrow,
                "预览变化图",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
            )
        }
    }
}

// ============ PV 文字预览 ============
@Composable
private fun PvTextPreview(pv: List<String>, boardSize: Int) {
    Column {
        Text("推荐变化图", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(4.dp))
        val pvMoves = pv.take(12).mapIndexed { idx, moveStr ->
            val coord = SgfParser.stringToCoordinate(moveStr, boardSize)
            val coordText = coord?.let { SgfParser.coordinateToString(it.first, it.second, boardSize) } ?: moveStr
            val prefix = if (idx % 2 == 0) "\u26AB" else "\u26AA"
            "$prefix$coordText"
        }
        Text(
            text = pvMoves.joinToString(" "),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 20.sp
        )
    }
}

// ============ 失误数据 ============
private data class BlunderInfo(
    val turnNumber: Int,
    val actualMove: String,
    val bestMove: String,
    val winrateChange: Float,  // 负值（百分比）
    val severity: String,
    val rating: MoveRating
)

// ============ 失误总览卡片 ============
@Composable
private fun BlunderSummaryCard(
    blunders: List<BlunderInfo>,
    expanded: Boolean,
    currentMoveIndex: Int,
    boardSize: Int,
    onToggle: () -> Unit,
    onJumpToMove: (Int) -> Unit
) {
    val severeCount = blunders.count { it.rating == MoveRating.BLUNDER }
    val mistakeCount = blunders.count { it.rating == MoveRating.MISTAKE }
    val inaccuracyCount = blunders.count { it.rating == MoveRating.INACCURACY }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 标题行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.TrendingDown,
                    "失误总览",
                    tint = BadMoveColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "失误总览",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = BadMoveColor
                )
                Spacer(modifier = Modifier.weight(1f))

                // 统计标签
                if (severeCount > 0) {
                    Surface(
                        color = BadMoveColor.copy(alpha = 0.15f),
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text("\uD83D\uDEA8", fontSize = 10.sp)
                            Text("$severeCount", fontSize = 10.sp, color = BadMoveColor, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }
                if (mistakeCount > 0) {
                    Surface(
                        color = BadMoveColor.copy(alpha = 0.1f),
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text("\uD83D\uDCDD", fontSize = 10.sp)
                            Text("$mistakeCount", fontSize = 10.sp, color = BadMoveColor.copy(alpha = 0.9f), fontWeight = FontWeight.Bold)
                        }
                    }
                }
                if (inaccuracyCount > 0) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Surface(
                        color = ThirdMoveColor.copy(alpha = 0.1f),
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text("\u26A0\uFE0F", fontSize = 10.sp)
                            Text("$inaccuracyCount", fontSize = 10.sp, color = ThirdMoveColor, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 展开的失误列表（最多显示前10个）
            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))

                val displayBlunders = blunders.take(10)
                displayBlunders.forEach { blunder ->
                    val isCurrentMove = currentMoveIndex == blunder.turnNumber
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 1.dp)
                            .clickable { onJumpToMove(blunder.turnNumber) },
                        color = if (isCurrentMove) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        } else {
                            Color.Transparent
                        },
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 严重程度指示器
                            val indicatorColor = blunder.rating.color
                            Surface(
                                color = indicatorColor,
                                shape = MaterialTheme.shapes.extraSmall
                            ) {
                                Text(
                                    "#${blunder.turnNumber}",
                                    fontSize = 9.sp,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            // 实际落子 vs 推荐落子
                            val actualCoord = SgfParser.stringToCoordinate(blunder.actualMove, boardSize)
                            val bestCoord = SgfParser.stringToCoordinate(blunder.bestMove, boardSize)
                            val actualText = actualCoord?.let {
                                SgfParser.coordinateToString(it.first, it.second, boardSize)
                            } ?: blunder.actualMove
                            val bestText = bestCoord?.let {
                                SgfParser.coordinateToString(it.first, it.second, boardSize)
                            } ?: blunder.bestMove

                            Text(
                                text = actualText,
                                fontSize = 12.sp,
                                color = BadMoveColor,
                                fontWeight = FontWeight.Medium
                            )
                            if (bestText.isNotEmpty()) {
                                Text(
                                    text = " → ",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = bestText,
                                    fontSize = 12.sp,
                                    color = GoodMoveColor,
                                    fontWeight = FontWeight.Medium
                                )
                            }

                            Spacer(modifier = Modifier.weight(1f))

                            // 胜率变化
                            Text(
                                text = "${blunder.winrateChange.times(100).let { "%.1f".format(it) }}%",
                                fontSize = 11.sp,
                                color = indicatorColor,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                if (blunders.size > 10) {
                    Text(
                        text = "还有 ${blunders.size - 10} 处...",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(start = 6.dp, top = 4.dp)
                    )
                }
            }
        }
    }
}

// ============ AI 简评卡片 ============
@Composable
private fun AiInsightCard(
    actualMove: SgfMove,
    bestMove: CandidateMove,
    winrateChange: Float,   // 百分比，如 -3.5f
    rating: MoveRating,
    boardSize: Int
) {
    val actualCoord = if (actualMove.isPass) "Pass"
        else SgfParser.coordinateToString(actualMove.x, actualMove.y, boardSize)
    val bestCoord = SgfParser.stringToCoordinate(bestMove.move, boardSize)
    val bestText = bestCoord?.let {
        SgfParser.coordinateToString(it.first, it.second, boardSize)
    } ?: bestMove.move

    val changePct = abs(winrateChange)
    val severityText = when (rating) {
        MoveRating.BLUNDER -> "严重失误！这手棋损失了约 ${changePct.toInt()}% 的胜率"
        MoveRating.MISTAKE -> "明显失误，胜率下降了 ${"%.1f".format(changePct)}%"
        else -> "略有偏差，胜率变化 ${"%.1f".format(winrateChange)}%"
    }
    val suggestion = when (rating) {
        MoveRating.BLUNDER -> "建议重点关注这步棋，AI 推荐 ${bestText} 是更优选择"
        MoveRating.MISTAKE -> "AI 更推荐 ${bestText}，可以点击上方「预览」查看变化图"
        else -> "AI 推荐 ${bestText} 稍优，差距不大"
    }

    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f),
        shape = MaterialTheme.shapes.small
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("\uD83E\uDD14 ", fontSize = 12.sp)
                MoveRatingBadge(rating = rating)
                Text(
                    text = severityText,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = suggestion,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                lineHeight = 16.sp
            )
        }
    }
}
