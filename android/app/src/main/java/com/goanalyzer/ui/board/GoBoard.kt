package com.goanalyzer.ui.board

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material.icons.filled.LastPage
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.filled.KeyboardDoubleArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goanalyzer.data.AnalysisOverlayMode
import com.goanalyzer.data.AnalysisResult
import com.goanalyzer.data.CandidateMove
import com.goanalyzer.data.PvPreviewMove
import com.goanalyzer.data.SgfGame
import com.goanalyzer.data.SgfParser
import com.goanalyzer.ui.analysis.MoveRating
import com.goanalyzer.ui.analysis.calcWinrateLoss
import com.goanalyzer.ui.analysis.classifyMove
import com.goanalyzer.ui.theme.*
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ============ 围棋规则引擎 ============

enum class StoneState { EMPTY, BLACK, WHITE }

class GoBoardState(val boardSize: Int = 19) {
    val board: Array<Array<StoneState>> = Array(boardSize) { Array(boardSize) { StoneState.EMPTY } }
    var koPoint: Pair<Int, Int>? = null
    var lastMove: Pair<Int, Int>? = null

    fun get(x: Int, y: Int): StoneState {
        if (x !in 0 until boardSize || y !in 0 until boardSize) return StoneState.EMPTY
        return board[x][y]
    }

    fun set(x: Int, y: Int, state: StoneState) {
        if (x in 0 until boardSize && y in 0 until boardSize) {
            board[x][y] = state
        }
    }

    private fun neighbors(x: Int, y: Int): List<Pair<Int, Int>> {
        return listOf(
            Pair(x - 1, y), Pair(x + 1, y),
            Pair(x, y - 1), Pair(x, y + 1)
        ).filter { it.first in 0 until boardSize && it.second in 0 until boardSize }
    }

    fun getGroup(x: Int, y: Int): Pair<List<Pair<Int, Int>>, Int> {
        val color = get(x, y)
        if (color == StoneState.EMPTY) return emptyList<Pair<Int, Int>>() to 0

        val visited = mutableSetOf<Pair<Int, Int>>()
        val stones = mutableListOf<Pair<Int, Int>>()
        val liberties = mutableSetOf<Pair<Int, Int>>()

        val stack = mutableListOf(Pair(x, y))
        while (stack.isNotEmpty()) {
            val pos = stack.removeAt(stack.size - 1)
            if (pos in visited) continue
            visited.add(pos)
            stones.add(pos)

            for (n in neighbors(pos.first, pos.second)) {
                val nState = get(n.first, n.second)
                when (nState) {
                    StoneState.EMPTY -> liberties.add(n)
                    color -> if (n !in visited) stack.add(n)
                    else -> {}
                }
            }
        }

        return stones to liberties.size
    }

    fun placeStone(x: Int, y: Int, isBlack: Boolean): Boolean {
        if (x !in 0 until boardSize || y !in 0 until boardSize) return false
        if (get(x, y) != StoneState.EMPTY) return false
        if (koPoint == Pair(x, y)) return false

        val myColor = if (isBlack) StoneState.BLACK else StoneState.WHITE
        val oppColor = if (isBlack) StoneState.WHITE else StoneState.BLACK

        set(x, y, myColor)

        var capturedStones = mutableListOf<Pair<Int, Int>>()
        for (n in neighbors(x, y)) {
            if (get(n.first, n.second) == oppColor) {
                val (group, liberties) = getGroup(n.first, n.second)
                if (liberties == 0) {
                    capturedStones.addAll(group)
                }
            }
        }

        for (stone in capturedStones) {
            set(stone.first, stone.second, StoneState.EMPTY)
        }

        val (myGroup, myLiberties) = getGroup(x, y)
        if (myLiberties == 0) {
            set(x, y, StoneState.EMPTY)
            return false
        }

        koPoint = if (capturedStones.size == 1 && myGroup.size == 1 && myLiberties == 1) {
            capturedStones[0]
        } else {
            null
        }

        lastMove = Pair(x, y)
        return true
    }

    fun pass() {
        koPoint = null
    }

    fun copy(): GoBoardState {
        val newBoard = GoBoardState(boardSize)
        for (x in 0 until boardSize) {
            for (y in 0 until boardSize) {
                newBoard.board[x][y] = this.board[x][y]
            }
        }
        newBoard.koPoint = this.koPoint
        newBoard.lastMove = this.lastMove
        return newBoard
    }
}

fun computeBoardStates(game: SgfGame): List<GoBoardState> {
    val states = mutableListOf<GoBoardState>()
    val current = GoBoardState(game.boardSize)
    states.add(current.copy())

    for (move in game.moves) {
        if (move.isSetup) {
            current.placeStone(move.x, move.y, move.isBlack)
        } else if (move.isPass) {
            current.pass()
        } else {
            current.placeStone(move.x, move.y, move.isBlack)
        }
        states.add(current.copy())
    }

    return states
}

// ============ 棋盘绘制组件 ============

@Composable
fun GoBoard(
    game: SgfGame? = null,
    analysisResult: AnalysisResult? = null,
    currentMoveIndex: Int = 0,
    showAnalysisOverlay: Boolean = false,
    analysisOverlayMode: AnalysisOverlayMode = AnalysisOverlayMode.BASIC,
    pvPreviewMoves: List<PvPreviewMove> = emptyList(),
    pvPreviewStep: Int = 0,
    pvPreviewVisible: Boolean = false,
    onMoveClick: (Int) -> Unit = {},
    boardStates: List<GoBoardState>? = null,
    modifier: Modifier = Modifier
) {
    val boardSize = game?.boardSize ?: 19
    val textMeasurer = rememberTextMeasurer()
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()

    val states = boardStates ?: remember(game) {
        if (game != null) computeBoardStates(game) else emptyList()
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth()
            .aspectRatio(1f)
    ) {
        val density = LocalDensity.current
        val maxWidthPx = with(density) { maxWidth.toPx() }

        // 预计算坐标标签的文字 layout（依赖 boardSize + canvasSize，不需要每帧重建）
        val pad = remember(maxWidthPx) { computePadding(maxWidthPx) }
        val boardArea = remember(maxWidthPx, pad) { maxWidthPx - pad * 2 }
        val cellSize = remember(boardArea, boardSize) { boardArea / (boardSize - 1) }

        // 预测量坐标文字（每次 boardSize 或 cellSize 变化才重建，不在 drawScope 里 measure）
        val coordTextStyle = remember(cellSize) {
            TextStyle(fontSize = (cellSize * 0.28f).sp)
        }
        // 列标签（A-T 跳过 I）
        val colLayouts = remember(boardSize, coordTextStyle) {
            (0 until boardSize).map { i ->
                val ch = if (i >= 8) ('A' + i + 1) else ('A' + i)
                textMeasurer.measure(ch.toString(), coordTextStyle)
            }
        }
        // 行标签（19..1）
        val rowLayouts = remember(boardSize, coordTextStyle) {
            (0 until boardSize).map { i ->
                textMeasurer.measure((boardSize - i).toString(), coordTextStyle)
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(boardSize, currentMoveIndex) {
                    detectTapGestures { offset ->
                        val padLocal = computePadding(maxWidthPx)
                        val boardAreaLocal = maxWidthPx - padLocal * 2
                        val cellSizeLocal = boardAreaLocal / (boardSize - 1)

                        val x = ((offset.x - padLocal) / cellSizeLocal).roundToInt()
                        val y = ((offset.y - padLocal) / cellSizeLocal).roundToInt()

                        if (x in 0 until boardSize && y in 0 until boardSize) {
                            val normalMoves = game?.moves?.filter { !it.isSetup } ?: emptyList()
                            val clickMove = normalMoves.indexOfFirst {
                                it.x == x && it.y == y && !it.isPass && it.turnNumber <= currentMoveIndex
                            }
                            if (clickMove != null && clickMove >= 0) {
                                onMoveClick(clickMove)
                            }
                        }
                    }
                }
                .pointerInput(boardSize) {
                    var startX = 0f
                    var startMoveIdx = 0

                    detectDragGestures(
                        onDragStart = { offset ->
                            startX = offset.x
                            startMoveIdx = currentMoveIndex
                        },
                        onDragEnd = {},
                        onDragCancel = {},
                        onDrag = { change, _ ->
                            val totalMoves = game?.moves?.count { !it.isSetup } ?: 0
                            val threshold = maxWidthPx / (boardSize + 2)
                            val delta = change.position.x - startX
                            val moveDelta = (delta / threshold).roundToInt()
                            val newMove = (startMoveIdx + moveDelta).coerceIn(0, totalMoves)
                            if (newMove != currentMoveIndex) {
                                onMoveClick(newMove)
                            }
                        }
                    )
                }
        ) {
            val canvasSize = size.minDimension
            val padC = computePadding(canvasSize)
            val boardAreaC = canvasSize - padC * 2
            val cellSizeC = boardAreaC / (boardSize - 1)

            // 1. 棋盘背景（木纹渐变 + 圆角）
            drawBoardBackground(padC, boardAreaC, isDark)

            // 2. 网格线
            drawGrid(padC, boardAreaC, boardSize, cellSizeC, isDark)

            // 3. 星位
            drawStarPoints(padC, cellSizeC, boardSize, isDark)

            // 4. 坐标（使用预计算的 layout，不再每帧 measure）
            drawCoordinatesCached(padC, boardAreaC, cellSizeC, boardSize, colLayouts, rowLayouts, isDark)

            // 5. 棋子
            if (states.isNotEmpty()) {
                // 安全获取 stateIndex，防止边界情况崩溃
                val stateIndex = (currentMoveIndex).coerceIn(0, maxOf(0, states.size - 1))
                val boardState = states[stateIndex]
                for (bx in 0 until boardSize) {
                    for (by in 0 until boardSize) {
                        val stone = boardState.get(bx, by)
                        if (stone != StoneState.EMPTY) {
                            val centerX = padC + bx * cellSizeC
                            val centerY = padC + by * cellSizeC
                            val stoneRadius = cellSizeC * 0.46f
                            drawStone(centerX, centerY, stoneRadius, stone == StoneState.BLACK, isDark)
                        }
                    }
                }

                // 最后一手标记（小红点 / 小蓝点）
                if (currentMoveIndex > 0 && currentMoveIndex <= (game?.moves?.size ?: 0)) {
                    val lastMove = game?.moves?.getOrNull(currentMoveIndex - 1)
                    if (lastMove != null && !lastMove.isPass) {
                        val centerX = padC + lastMove.x * cellSizeC
                        val centerY = padC + lastMove.y * cellSizeC
                        drawLastMoveMarker(centerX, centerY, cellSizeC * 0.46f, lastMove.isBlack)
                    }
                }
            }

            // 6. PV 变化图预览
            if (pvPreviewVisible && pvPreviewMoves.isNotEmpty()) {
                val visibleMoves = pvPreviewMoves.take(pvPreviewStep)
                visibleMoves.forEach { pvMove ->
                    val centerX = padC + pvMove.x * cellSizeC
                    val centerY = padC + pvMove.y * cellSizeC
                    val stoneRadius = cellSizeC * 0.42f
                    drawPvStone(centerX, centerY, stoneRadius, pvMove.isBlack, pvMove.label, textMeasurer)
                }
            }

            // 7. 分析覆盖层
            if (showAnalysisOverlay && analysisResult != null && game != null && !pvPreviewVisible) {
                drawAnalysisOverlay(
                    padC, cellSizeC, boardSize,
                    currentMoveIndex, analysisResult,
                    overlayMode = analysisOverlayMode,
                    textMeasurer = textMeasurer
                )
            }
        }
    }
}

// 计算内边距（留空间给坐标）
private fun computePadding(canvasSize: Float): Float = canvasSize * 0.055f

// ============ 棋盘背景（优化：增强木纹质感 + 立体边框） ============
private fun DrawScope.drawBoardBackground(padding: Float, boardArea: Float, isDark: Boolean) {
    val boardCol = boardColor(isDark)
    val boardColLight = boardColorLight(isDark)
    val borderColor = gridColor(isDark)

    // 外部阴影（卡片效果）
    drawRoundRect(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.Black.copy(alpha = if (isDark) 0.4f else 0.15f),
                Color.Transparent
            ),
            center = Offset(padding + boardArea * 0.5f, padding + boardArea * 0.5f),
            radius = boardArea * 0.8f
        ),
        topLeft = Offset(padding - 6f, padding - 6f),
        size = Size(boardArea + 12f, boardArea + 12f),
        cornerRadius = CornerRadius(8.dp.toPx())
    )

    // 木纹渐变背景（对角线渐变更自然）
    drawRoundRect(
        brush = Brush.linearGradient(
            colors = listOf(
                boardColLight,
                boardCol,
                boardColLight,
                boardCol,
                boardColLight
            ),
            start = Offset(padding, padding),
            end = Offset(padding + boardArea, padding + boardArea * 0.7f)
        ),
        topLeft = Offset(padding, padding),
        size = Size(boardArea, boardArea),
        cornerRadius = CornerRadius(4.dp.toPx())
    )

    // 细微木纹线条（暗色模式下更淡，避免干扰白棋显示）
    val lineColor = if (isDark) Color(0xFF000000).copy(alpha = 0.03f) else Color(0xFF000000).copy(alpha = 0.05f)
    for (i in 0 until 15) {
        val yOffset = (boardArea * 0.015f * kotlin.math.sin(i * 0.8)).toFloat()
        val y = padding + (boardArea / 15f) * i + yOffset
        val startPt = Offset(padding, y)
        val endPt = Offset(padding + boardArea, y)
        drawLine(
            color = lineColor,
            start = startPt,
            end = endPt,
            strokeWidth = 0.8f
        )
    }

    // 内边框高光
    drawRoundRect(
        color = if (isDark) Color(0xFF5D4E37) else Color(0xFFA08050),
        topLeft = Offset(padding, padding),
        size = Size(boardArea, boardArea),
        cornerRadius = CornerRadius(4.dp.toPx()),
        style = Stroke(width = 1.5f)
    )

    // 外框深色描边
    drawRoundRect(
        color = borderColor.copy(alpha = 0.6f),
        topLeft = Offset(padding - 1.5f, padding - 1.5f),
        size = Size(boardArea + 3f, boardArea + 3f),
        cornerRadius = CornerRadius(5.dp.toPx()),
        style = Stroke(width = 1f)
    )
}

// ============ 网格线 ============
private fun DrawScope.drawGrid(
    padding: Float, boardArea: Float, boardSize: Int, cellSize: Float, isDark: Boolean
) {
    val lineColor = gridColor(isDark)

    for (i in 0 until boardSize) {
        val pos = padding + i * cellSize
        // 边线加粗
        val strokeWidth = if (i == 0 || i == boardSize - 1) 1.5f else 0.8f
        drawLine(color = lineColor, start = Offset(padding, pos), end = Offset(padding + boardArea, pos), strokeWidth = strokeWidth)
        drawLine(color = lineColor, start = Offset(pos, padding), end = Offset(pos, padding + boardArea), strokeWidth = strokeWidth)
    }
}

// ============ 星位（优化：增加晕影效果） ============
private fun DrawScope.drawStarPoints(
    padding: Float, cellSize: Float, boardSize: Int, isDark: Boolean
) {
    val starPositions = when (boardSize) {
        19 -> listOf(3, 9, 15)
        13 -> listOf(3, 6, 9)
        9 -> listOf(2, 4, 6)
        else -> return
    }
    val baseRadius = cellSize * 0.10f
    val starCol = if (isDark) StarPointColorDark else StarPointColor
    val glowCol = if (isDark) StarPointColorDark.copy(alpha = 0.3f) else StarPointColor.copy(alpha = 0.25f)

    for (sx in starPositions) {
        for (sy in starPositions) {
            val cx = padding + sx * cellSize
            val cy = padding + sy * cellSize

            // 星位光晕
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(glowCol, Color.Transparent),
                    center = Offset(cx, cy),
                    radius = baseRadius * 2f
                ),
                radius = baseRadius * 2f,
                center = Offset(cx, cy)
            )

            // 星位本体
            drawCircle(
                color = starCol,
                radius = baseRadius,
                center = Offset(cx, cy)
            )
        }
    }
}

// ============ 坐标（原版：每帧 measure，保留备用） ============
private fun DrawScope.drawCoordinates(
    padding: Float, boardArea: Float, cellSize: Float, boardSize: Int,
    textMeasurer: androidx.compose.ui.text.TextMeasurer, isDark: Boolean
) {
    val textStyle = TextStyle(
        fontSize = (cellSize * 0.28f).sp,
        color = coordColor(isDark)
    )

    for (i in 0 until boardSize) {
        val colChar = if (i >= 8) ('A' + i + 1) else ('A' + i)
        val rowNum = boardSize - i

        val colLayout = textMeasurer.measure(colChar.toString(), textStyle)
        drawText(
            textLayoutResult = colLayout,
            topLeft = Offset(padding + i * cellSize - colLayout.size.width * 0.5f, padding + boardArea + 5f)
        )

        val rowLayout = textMeasurer.measure(rowNum.toString(), textStyle)
        drawText(
            textLayoutResult = rowLayout,
            topLeft = Offset(padding - rowLayout.size.width - 5f, padding + i * cellSize - rowLayout.size.height * 0.5f)
        )
    }
}

// ============ 坐标（优化版：使用预计算 layout，每帧 0 measure 调用） ============
private fun DrawScope.drawCoordinatesCached(
    padding: Float, boardArea: Float, cellSize: Float, boardSize: Int,
    colLayouts: List<androidx.compose.ui.text.TextLayoutResult>,
    rowLayouts: List<androidx.compose.ui.text.TextLayoutResult>,
    isDark: Boolean
) {
    for (i in 0 until boardSize) {
        val colLayout = colLayouts.getOrNull(i) ?: continue
        drawText(
            textLayoutResult = colLayout,
            topLeft = Offset(padding + i * cellSize - colLayout.size.width * 0.5f, padding + boardArea + 5f)
        )

        val rowLayout = rowLayouts.getOrNull(i) ?: continue
        drawText(
            textLayoutResult = rowLayout,
            topLeft = Offset(padding - rowLayout.size.width - 5f, padding + i * cellSize - rowLayout.size.height * 0.5f)
        )
    }
}

// ============ 棋子（高品质3D光影 - 优化白棋显示） ============
private fun DrawScope.drawStone(centerX: Float, centerY: Float, radius: Float, isBlack: Boolean, isDark: Boolean = false) {
    // 外阴影（软阴影效果）
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.Black.copy(alpha = 0.35f),
                Color.Black.copy(alpha = 0.15f),
                Color.Transparent
            ),
            center = Offset(centerX + radius * 0.4f, centerY + radius * 0.4f),
            radius = radius * 1.5f
        ),
        radius = radius * 1.2f,
        center = Offset(centerX, centerY)
    )

    // 紧邻阴影
    drawCircle(
        color = StoneShadowColor,
        radius = radius * 1.06f,
        center = Offset(centerX + 1f, centerY + 1.5f)
    )

    if (isBlack) {
        // 黑子：多层渐变营造金属质感
        // 底色层
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF505050),
                    Color(0xFF2A2A2A),
                    Color(0xFF0F0F0F)
                ),
                center = Offset(centerX - radius * 0.35f, centerY - radius * 0.35f),
                radius = radius * 1.4f
            ),
            radius = radius,
            center = Offset(centerX, centerY)
        )

        // 顶部高光弧
        drawArc(
            color = Color(0xFF404040),
            startAngle = -135f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(centerX - radius * 0.85f, centerY - radius * 0.85f),
            size = androidx.compose.ui.geometry.Size(radius * 1.7f, radius * 1.7f),
            style = Stroke(width = radius * 0.15f)
        )

        // 主高光
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF505050),
                    Color.Transparent
                ),
                center = Offset(centerX - radius * 0.35f, centerY - radius * 0.35f),
                radius = radius * 0.45f
            ),
            radius = radius * 0.45f,
            center = Offset(centerX - radius * 0.35f, centerY - radius * 0.35f)
        )

        // 边缘光泽
        drawCircle(
            color = BlackStoneEdge,
            radius = radius,
            center = Offset(centerX, centerY),
            style = Stroke(width = 0.8f)
        )
    } else {
        // 白子：珍珠质感多层渐变（优化暗色模式可见性）
        // 暗色模式下边缘更明显
        val edgeColor = if (isDark) WhiteStoneDarkEdge else WhiteStoneEdge
        val baseShadow = if (isDark) Color(0xFF606060) else Color(0xFFD8D8D8)

        // 基础层（带底部阴影的立体感）
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    WhiteStoneColor,
                    WhiteStonePearl,
                    baseShadow
                ),
                center = Offset(centerX - radius * 0.3f, centerY - radius * 0.3f),
                radius = radius * 1.4f
            ),
            radius = radius,
            center = Offset(centerX, centerY)
        )

        // 主高光（强烈一点更明显）
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White,
                    Color.White.copy(alpha = 0.7f),
                    Color.Transparent
                ),
                center = Offset(centerX - radius * 0.3f, centerY - radius * 0.35f),
                radius = radius * 0.45f
            ),
            radius = radius * 0.45f,
            center = Offset(centerX - radius * 0.3f, centerY - radius * 0.35f)
        )

        // 次高光（增加层次）
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.5f),
                    Color.Transparent
                ),
                center = Offset(centerX - radius * 0.15f, centerY - radius * 0.15f),
                radius = radius * 0.25f
            ),
            radius = radius * 0.25f,
            center = Offset(centerX - radius * 0.15f, centerY - radius * 0.15f)
        )

        // 边缘轮廓（暗色模式下加粗更明显）
        val edgeWidth = if (isDark) 1.0f else 0.6f
        drawCircle(
            color = edgeColor,
            radius = radius,
            center = Offset(centerX, centerY),
            style = Stroke(width = edgeWidth)
        )

        // 底部微阴影（暗色模式下反向处理，增加立体感）
        if (isDark) {
            // 暗色模式下：底部添加浅色反光，增加立体感
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.15f),
                        Color.Transparent
                    ),
                    center = Offset(centerX + radius * 0.25f, centerY + radius * 0.25f),
                    radius = radius * 0.4f
                ),
                radius = radius * 0.4f,
                center = Offset(centerX + radius * 0.25f, centerY + radius * 0.25f)
            )
        } else {
            // 亮色模式：底部阴影
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color(0x20000000)
                    ),
                    center = Offset(centerX + radius * 0.3f, centerY + radius * 0.3f),
                    radius = radius * 0.5f
                ),
                radius = radius * 0.5f,
                center = Offset(centerX + radius * 0.3f, centerY + radius * 0.3f)
            )
        }
    }
}

// ============ 最后一手标记（小红点，不写手数） ============
private fun DrawScope.drawLastMoveMarker(
    centerX: Float, centerY: Float, radius: Float, isBlack: Boolean
) {
    val markerColor = if (isBlack) Color(0xFFFF5252) else Color(0xFF1565C0)
    val markerRadius = radius * 0.28f
    drawCircle(
        color = markerColor,
        radius = markerRadius,
        center = Offset(centerX, centerY)
    )
}

// ============ PV 预览棋子（优化：半透明玻璃态效果） ============
private fun DrawScope.drawPvStone(
    centerX: Float, centerY: Float, radius: Float,
    isBlack: Boolean, label: Int,
    textMeasurer: androidx.compose.ui.text.TextMeasurer
) {
    if (isBlack) {
        // 黑子：半透明玻璃态
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF303030).copy(alpha = 0.85f),
                    Color(0xFF1A1A1A).copy(alpha = 0.75f)
                ),
                center = Offset(centerX - radius * 0.2f, centerY - radius * 0.2f),
                radius = radius * 1.2f
            ),
            radius = radius,
            center = Offset(centerX, centerY)
        )
        // 边缘发光
        drawCircle(
            color = Color(0xFF404040).copy(alpha = 0.5f),
            radius = radius,
            center = Offset(centerX, centerY),
            style = Stroke(width = 1f)
        )
    } else {
        // 白子：珍珠半透明
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.95f),
                    Color(0xFFE0E0E0).copy(alpha = 0.85f)
                ),
                center = Offset(centerX - radius * 0.2f, centerY - radius * 0.2f),
                radius = radius * 1.2f
            ),
            radius = radius,
            center = Offset(centerX, centerY)
        )
        // 边缘描边
        drawCircle(
            color = Color(0xFFC0C0C0).copy(alpha = 0.6f),
            radius = radius,
            center = Offset(centerX, centerY),
            style = Stroke(width = 0.8f)
        )
    }

    // 序号
    val labelColor = if (isBlack) Color.White.copy(alpha = 0.95f) else Color.Black.copy(alpha = 0.9f)
    val textStyle = TextStyle(
        fontSize = (radius * 0.85f).sp,
        color = labelColor,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
    )
    val layout = textMeasurer.measure(label.toString(), textStyle)
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(centerX - layout.size.width * 0.5f, centerY - layout.size.height * 0.5f)
    )
}

// ============ 分析覆盖层 ============
private fun DrawScope.drawAnalysisOverlay(
    padding: Float, cellSize: Float, boardSize: Int,
    currentMoveIndex: Int, analysisResult: AnalysisResult,
    overlayMode: AnalysisOverlayMode,
    textMeasurer: androidx.compose.ui.text.TextMeasurer
) {
    if (currentMoveIndex <= 0) return
    val turnIndex = currentMoveIndex - 1
    val moveAnalysis = analysisResult.moves.getOrNull(turnIndex) ?: return
    if (moveAnalysis.move.isEmpty()) return

    // 使用服务端计算的 isBestMove 字段
    val isBestMove = moveAnalysis.isBestMove
    val loss = calcWinrateLoss(moveAnalysis.moveInfos, moveAnalysis.move, moveAnalysis.isBlackMove) ?: 0f
    val rating = classifyMove(loss, isBestMove)

    // 在棋子上标记质量
    if (moveAnalysis.move.isNotEmpty()) {
        val coord = SgfParser.stringToCoordinate(moveAnalysis.move, boardSize)
        if (coord != null) {
            val cx = padding + coord.first * cellSize
            val cy = padding + coord.second * cellSize
            when (rating) {
                MoveRating.BEST_MOVE -> {
                    // AI 之着：绿色小圆点
                    drawCircle(color = BestMoveColor, radius = cellSize * 0.10f, center = Offset(cx, cy))
                }
                MoveRating.GOOD_MOVE -> {
                    // 好手：薄荷绿小圆点
                    drawCircle(color = GoodMoveColor, radius = cellSize * 0.08f, center = Offset(cx, cy))
                }
                MoveRating.INACCURACY, MoveRating.MISTAKE, MoveRating.BLUNDER -> {
                    drawMoveQualityMarker(cx, cy, cellSize, rating)
                }
            }
        }
    }

    // 推荐手
    when (overlayMode) {
        AnalysisOverlayMode.BASIC -> drawBasicMoveSuggestions(padding, cellSize, boardSize, moveAnalysis.moveInfos, textMeasurer)
        AnalysisOverlayMode.HEATMAP -> drawHeatmapOverlay(padding, cellSize, boardSize, moveAnalysis.moveInfos, textMeasurer)
        AnalysisOverlayMode.OFF -> {}
    }
}

// ============ 失误程度标记（优化：增强视觉效果） ============
private fun DrawScope.drawMoveQualityMarker(cx: Float, cy: Float, cellSize: Float, rating: MoveRating) {
    when (rating) {
        MoveRating.BLUNDER -> {
            // 严重失误：红色圆环 + 填充
            val r = cellSize * 0.28f
            // 外圈光晕
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(BadMoveColor.copy(alpha = 0.3f), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = r * 1.6f
                ),
                radius = r * 1.6f,
                center = Offset(cx, cy)
            )
            // 主体圆
            drawCircle(color = BadMoveColor, radius = r, center = Offset(cx, cy))
            // 内部高光
            drawCircle(
                color = Color.White.copy(alpha = 0.25f),
                radius = r * 0.5f,
                center = Offset(cx - r * 0.2f, cy - r * 0.2f)
            )
        }
        MoveRating.MISTAKE -> {
            // 失误：圆角方块
            val s = cellSize * 0.14f
            drawRoundRect(
                color = BadMoveColor.copy(alpha = 0.85f),
                topLeft = Offset(cx - s, cy - s),
                size = Size(s * 2, s * 2),
                cornerRadius = CornerRadius(s * 0.4f)
            )
            // 内部高光
            drawCircle(
                color = Color.White.copy(alpha = 0.2f),
                radius = s * 0.6f,
                center = Offset(cx, cy)
            )
        }
        MoveRating.INACCURACY -> {
            // 小失误：细线方框
            val s = cellSize * 0.12f
            drawRoundRect(
                color = ThirdMoveColor.copy(alpha = 0.8f),
                topLeft = Offset(cx - s, cy - s),
                size = Size(s * 2, s * 2),
                cornerRadius = CornerRadius(s * 0.3f),
                style = Stroke(width = 2f)
            )
        }
        else -> {}
    }
}

// ============ 基础模式：序号圆 + 胜率数字（优化：玻璃态效果） ============
private fun DrawScope.drawBasicMoveSuggestions(
    padding: Float, cellSize: Float, boardSize: Int,
    moveInfos: List<CandidateMove>,
    textMeasurer: androidx.compose.ui.text.TextMeasurer
) {
    val colors = listOf(BestMoveColor, SecondMoveColor, ThirdMoveColor, FourthMoveColor, FifthMoveColor)

    moveInfos.take(5).forEachIndexed { index, candidate ->
        val coord = SgfParser.stringToCoordinate(candidate.move, boardSize) ?: return@forEachIndexed
        val x = padding + coord.first * cellSize
        val y = padding + coord.second * cellSize
        val bgColor = colors.getOrElse(index) { FifthMoveColor }

        val circleRadius = cellSize * 0.36f

        // 外发光
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(bgColor.copy(alpha = 0.4f), Color.Transparent),
                center = Offset(x, y),
                radius = circleRadius * 1.8f
            ),
            radius = circleRadius * 1.8f,
            center = Offset(x, y)
        )

        // 主体圆（玻璃态）
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    bgColor.copy(alpha = 0.95f),
                    bgColor.copy(alpha = 0.85f)
                ),
                center = Offset(x - circleRadius * 0.2f, y - circleRadius * 0.2f),
                radius = circleRadius * 1.5f
            ),
            radius = circleRadius,
            center = Offset(x, y)
        )

        // 高光
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.35f), Color.Transparent),
                center = Offset(x - circleRadius * 0.3f, y - circleRadius * 0.35f),
                radius = circleRadius * 0.5f
            ),
            radius = circleRadius * 0.5f,
            center = Offset(x - circleRadius * 0.3f, y - circleRadius * 0.35f)
        )

        // 序号（居中）
        val numStyle = TextStyle(
            fontSize = (cellSize * 0.26f).sp,
            color = Color.White,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
        )
        val numLayout = textMeasurer.measure((index + 1).toString(), numStyle)
        drawText(
            textLayoutResult = numLayout,
            topLeft = Offset(x - numLayout.size.width * 0.5f, y - numLayout.size.height * 0.55f)
        )

        // 胜率百分比（居中偏下）
        val wrText = "%.1f".format(candidate.winrate * 100)
        val wrStyle = TextStyle(
            fontSize = (cellSize * 0.18f).sp,
            color = Color.White.copy(alpha = 0.9f),
            fontWeight = androidx.compose.ui.text.font.FontWeight.Normal
        )
        val wrLayout = textMeasurer.measure(wrText, wrStyle)
        drawText(
            textLayoutResult = wrLayout,
            topLeft = Offset(x - wrLayout.size.width * 0.5f, y + cellSize * 0.08f)
        )
    }
}

// ============ 热度图模式 ============
private fun DrawScope.drawHeatmapOverlay(
    padding: Float, cellSize: Float, boardSize: Int,
    moveInfos: List<CandidateMove>,
    textMeasurer: androidx.compose.ui.text.TextMeasurer
) {
    if (moveInfos.isEmpty()) return
    val maxVisits = moveInfos.maxOf { it.visits.toFloat() }.coerceAtLeast(1f)

    moveInfos.take(10).forEachIndexed { index, candidate ->
        val coord = SgfParser.stringToCoordinate(candidate.move, boardSize) ?: return@forEachIndexed
        val x = padding + coord.first * cellSize
        val y = padding + coord.second * cellSize
        val intensity = candidate.visits.toFloat() / maxVisits
        val radius = cellSize * (0.18f + intensity * 0.28f)
        val alpha = 0.25f + intensity * 0.55f

        // 颜色映射：高胜率→绿/蓝，低胜率→红/橙
        val hue = (1f - candidate.winrate) * 120f
        val heatmapColor = Color.hsl(hue, 0.75f, 0.50f, alpha)

        drawCircle(color = heatmapColor, radius = radius, center = Offset(x, y))

        // 前 5 显示序号
        if (index < 5) {
            val labelStyle = TextStyle(fontSize = (cellSize * 0.22f).sp, color = Color.White.copy(alpha = 0.9f))
            val labelLayout = textMeasurer.measure((index + 1).toString(), labelStyle)
            drawText(
                textLayoutResult = labelLayout,
                topLeft = Offset(x - labelLayout.size.width * 0.5f, y - labelLayout.size.height * 0.5f)
            )
        }
    }
}

// ============ 导航控制栏 ============
@Composable
fun GoBoardNavigation(
    totalMoves: Int,
    currentMoveIndex: Int,
    onMoveChange: (Int) -> Unit,
    showBlunderJump: Boolean = false,
    onNextBlunder: (() -> Unit)? = null,
    onPrevBlunder: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // 合并为一行：起点 | 上手 | 手数 | 下手 | 终点 | 分隔 | 失误跳转
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 基础导航
        IconButton(onClick = { onMoveChange(0) }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.FirstPage, "起点", modifier = Modifier.size(20.dp))
        }
        IconButton(onClick = { onMoveChange(maxOf(0, currentMoveIndex - 1)) }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.KeyboardArrowLeft, "上一手", modifier = Modifier.size(26.dp))
        }

        // 手数显示
        Text(
            text = "$currentMoveIndex / $totalMoves",
            modifier = Modifier.padding(horizontal = 8.dp),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        IconButton(onClick = { onMoveChange(minOf(totalMoves, currentMoveIndex + 1)) }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.KeyboardArrowRight, "下一手", modifier = Modifier.size(26.dp))
        }
        IconButton(onClick = { onMoveChange(totalMoves) }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.LastPage, "终点", modifier = Modifier.size(20.dp))
        }

        // 失误跳转
        if (showBlunderJump && onNextBlunder != null && onPrevBlunder != null) {
            // 竖线分隔
            Box(
                modifier = Modifier
                    .height(20.dp)
                    .padding(horizontal = 4.dp)
            ) {
                androidx.compose.material3.VerticalDivider()
            }

            IconButton(onClick = onPrevBlunder, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.KeyboardDoubleArrowLeft,
                    "上一个失误",
                    tint = BadMoveColor,
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                text = "失误",
                fontSize = 11.sp,
                color = BadMoveColor,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
            IconButton(onClick = onNextBlunder, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.KeyboardDoubleArrowRight,
                    "下一个失误",
                    tint = BadMoveColor,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
