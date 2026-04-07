package com.goanalyzer.ui.chart

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goanalyzer.data.AnalysisResult
import com.goanalyzer.ui.analysis.calcWinrateLoss
import com.goanalyzer.ui.analysis.classifyMove
import com.goanalyzer.ui.theme.*
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 胜率曲线图 - 优化版：平滑贝塞尔曲线 + 渐变填充 + 移动平均线
 */
@Composable
fun WinrateChart(
    analysisResult: AnalysisResult?,
    currentMoveIndex: Int,
    onMoveClick: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (analysisResult == null || analysisResult.moves.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(150.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "暂无分析数据",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                fontSize = 14.sp
            )
        }
        return
    }

    val winratePoints = analysisResult.moves.mapIndexed { index, move ->
        WinrateChartPoint(index + 1, move.winrate * 100f)
    }

    // 计算移动平均线（5手平均）
    val movingAveragePoints = remember(winratePoints) {
        if (winratePoints.size < 3) emptyList() else {
            winratePoints.mapIndexed { index, point ->
                val start = maxOf(0, index - 2)
                val end = minOf(winratePoints.size - 1, index + 2)
                val avg = winratePoints.subList(start, end + 1).map { it.winrate }.average()
                WinrateChartPoint(point.turn, avg.toFloat())
            }
        }
    }

    val isDark = isSystemInDarkTheme()
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    // 失误标记动画
    val blunderPulseAnim = rememberInfiniteTransition(label = "blunderPulse")
    val blunderAlpha by blunderPulseAnim.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "blunderAlpha"
    )

    // 预计算失误点列表
    val blunderIndices = remember(analysisResult) {
        val indices = mutableListOf<Int>()
        for (i in 1 until analysisResult.moves.size) {
            val moveData = analysisResult.moves[i]
            if (moveData.move.isEmpty()) continue
            // 使用服务端计算的 isBestMove 字段
            val isBestMove = moveData.isBestMove
            val loss = calcWinrateLoss(moveData.moveInfos, moveData.move, moveData.isBlackMove) ?: continue
            val rating = classifyMove(loss, isBestMove)
            if (rating == com.goanalyzer.ui.analysis.MoveRating.BLUNDER || rating == com.goanalyzer.ui.analysis.MoveRating.MISTAKE) {
                indices.add(i + 1)
            }
        }
        indices
    }

    // 配色方案
    val chartBgColor = if (isDark) Color(0xFF1A1A1A) else Color(0xFFFAFAFA)
    val gridColor = if (isDark) Color(0xFF333333) else Color(0xFFEEEEEE)
    val textColor = if (isDark) Color(0xFF777777) else Color(0xFF999999)
    val lineColor = if (isDark) Color(0xFF66BB6A) else Color(0xFF2E7D32)
    val maLineColor = if (isDark) Color(0xFF90CAF9) else Color(0xFF1976D2)

    Column(modifier = modifier) {
        // 标题栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "胜率走势",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 图例
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(lineColor)
                )
                Text(
                    " 胜率  ",
                    fontSize = 9.sp,
                    color = textColor
                )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(maLineColor)
                )
                Text(
                    " 5手均线",
                    fontSize = 9.sp,
                    color = textColor
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .pointerInput(winratePoints.size) {
                        detectTapGestures { offset ->
                            val canvasWidth = size.width
                            val leftPadPx = with(density) { 36.dp.toPx() }
                            val rightPadPx = with(density) { 12.dp.toPx() }
                            val chartWidthPx = canvasWidth - leftPadPx - rightPadPx

                            if (offset.x < leftPadPx || offset.x > canvasWidth - rightPadPx) return@detectTapGestures

                            val ratio = (offset.x - leftPadPx) / chartWidthPx
                            val totalTurns = winratePoints.last().turn
                            val clickedTurn = (ratio * totalTurns).roundToInt().coerceIn(1, totalTurns.toInt())

                            onMoveClick(clickedTurn)
                        }
                    }
            ) {
                val leftPad = 36f
                val topPad = 8f
                val bottomPad = 24f
                val rightPad = 12f
                val chartWidth = size.width - leftPad - rightPad
                val chartHeight = size.height - topPad - bottomPad

                // 圆角背景
                drawRoundRect(
                    color = chartBgColor,
                    topLeft = Offset(leftPad, topPad),
                    size = Size(chartWidth, chartHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f)
                )

                // 参考线
                for (pct in listOf(0f, 25f, 50f, 75f, 100f)) {
                    val yLine = topPad + chartHeight * (1f - pct / 100f)
                    val lineW = if (pct == 50f) 1.2f else 0.5f
                    val lineColor2 = if (pct == 50f) textColor else gridColor
                    val isDashed = pct != 0f && pct != 100f && pct != 50f
                    if (isDashed) {
                        drawLine(
                            color = lineColor2,
                            start = Offset(leftPad, yLine),
                            end = Offset(leftPad + chartWidth, yLine),
                            strokeWidth = lineW,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)
                        )
                    } else {
                        drawLine(
                            color = lineColor2,
                            start = Offset(leftPad, yLine),
                            end = Offset(leftPad + chartWidth, yLine),
                            strokeWidth = lineW
                        )
                    }
                }

                // 标签
                val labelStyle = TextStyle(fontSize = 9.sp, color = textColor)
                drawTextHelper(this, textMeasurer, "50%", labelStyle, Offset(0f, topPad + chartHeight * 0.5f - 5f))
                drawTextHelper(this, textMeasurer, "100", labelStyle, Offset(2f, topPad - 2f))
                drawTextHelper(this, textMeasurer, "0", labelStyle, Offset(6f, topPad + chartHeight - 8f))

                // 失误区域高亮
                blunderIndices.forEach { turn ->
                    if (turn in 1..winratePoints.size) {
                        val x = leftPad + (turn.toFloat() / winratePoints.last().turn) * chartWidth
                        drawRect(
                            color = BadMoveColor.copy(alpha = 0.08f * blunderAlpha),
                            topLeft = Offset(x - 6f, topPad),
                            size = Size(12f, chartHeight)
                        )
                    }
                }

                if (winratePoints.size < 2) return@Canvas

                // 构建贝塞尔平滑曲线
                val path = createSmoothPath(winratePoints, leftPad, topPad, chartWidth, chartHeight, chartWidth / winratePoints.size)
                val fillPath = Path().apply {
                    addPath(path)
                    lineTo(leftPad + chartWidth, topPad + chartHeight)
                    lineTo(leftPad, topPad + chartHeight)
                    close()
                }

                // 渐变填充
                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            lineColor.copy(alpha = 0.35f),
                            lineColor.copy(alpha = 0.15f),
                            Color.Transparent
                        ),
                        startY = topPad,
                        endY = topPad + chartHeight
                    )
                )

                // 移动平均线
                if (movingAveragePoints.isNotEmpty()) {
                    val maPath = createSmoothPath(movingAveragePoints, leftPad, topPad, chartWidth, chartHeight, chartWidth / winratePoints.size)
                    drawPath(
                        path = maPath,
                        color = maLineColor.copy(alpha = 0.5f),
                        style = Stroke(width = 1.5f, cap = StrokeCap.Round)
                    )
                }

                // 主曲线
                drawPath(
                    path = path,
                    color = lineColor,
                    style = Stroke(width = 2.5f, cap = StrokeCap.Round)
                )

                // 失误标记点
                for (i in 1 until winratePoints.size) {
                    val curr = winratePoints[i].winrate
                    val moveData = analysisResult.moves.getOrNull(i)
                    if (moveData != null && moveData.move.isNotEmpty()) {
                        val bestMoveStr = moveData.moveInfos.firstOrNull()?.move ?: ""
                        val isBestMove = bestMoveStr.isNotEmpty() && moveData.move.equals(bestMoveStr, ignoreCase = true)
                        val loss = calcWinrateLoss(moveData.moveInfos, moveData.move, moveData.isBlackMove) ?: continue
                        val rating = classifyMove(loss, isBestMove)
                        if (rating == com.goanalyzer.ui.analysis.MoveRating.BLUNDER || rating == com.goanalyzer.ui.analysis.MoveRating.MISTAKE) {
                            val turn = winratePoints[i].turn
                            val x = leftPad + (turn.toFloat() / winratePoints.last().turn) * chartWidth
                            val y = topPad + (1f - curr / 100f) * chartHeight

                            // 脉冲外圈
                            drawCircle(
                                color = rating.color.copy(alpha = 0.25f * blunderAlpha),
                                radius = 8.dp.toPx(),
                                center = Offset(x, y)
                            )
                            // 内圈
                            drawCircle(
                                color = rating.color,
                                radius = 4.dp.toPx(),
                                center = Offset(x, y)
                            )
                        }
                    }
                }

                // 当前手标记
                if (currentMoveIndex > 0 && currentMoveIndex <= winratePoints.size) {
                    val point = winratePoints.getOrNull(currentMoveIndex - 1) ?: return@Canvas
                    val currentWinrate = point.winrate
                    val cx = leftPad + (point.turn.toFloat() / winratePoints.last().turn) * chartWidth
                    val cy = topPad + (1f - currentWinrate / 100f) * chartHeight

                    // 垂直指示线
                    drawLine(
                        color = if (isDark) Color(0x40FFFFFF) else Color(0x20000000),
                        start = Offset(cx, topPad),
                        end = Offset(cx, topPad + chartHeight),
                        strokeWidth = 1.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f), 0f)
                    )

                    // 中心点
                    drawCircle(
                        color = Color(0xFFFF5252),
                        radius = 5.dp.toPx(),
                        center = Offset(cx, cy)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 2.5.dp.toPx(),
                        center = Offset(cx, cy)
                    )

                    // 胜率标签
                    val labelText = "B ${"%.1f".format(currentWinrate)}%"
                    val labelStyle2 = TextStyle(fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    val labelResult = textMeasurer.measure(labelText, labelStyle2)
                    val labelW = labelResult.size.width + 10f
                    val labelH = labelResult.size.height + 6f
                    var labelX = cx + 8f
                    var labelY = cy - labelH - 4f

                    if (labelX + labelW > leftPad + chartWidth) {
                        labelX = cx - labelW - 8f
                    }
                    if (labelY < topPad) {
                        labelY = cy + 8f
                    }

                    drawRoundRect(
                        color = Color(0xDD333333),
                        topLeft = Offset(labelX, labelY),
                        size = Size(labelW, labelH),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f)
                    )
                    drawText(
                        textLayoutResult = labelResult,
                        topLeft = Offset(labelX + 5f, labelY + 3f)
                    )
                }
            }
        }

        // 底部提示
        Text(
            text = "${if (blunderIndices.isEmpty()) "暂无" else blunderIndices.size}处失误 · 点击曲线跳转",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}

/**
 * 创建平滑的贝塞尔曲线路径
 */
private fun createSmoothPath(
    points: List<WinrateChartPoint>,
    leftPad: Float,
    topPad: Float,
    chartWidth: Float,
    chartHeight: Float,
    segmentWidth: Float
): Path {
    val path = Path()
    if (points.size < 2) return path

    val firstPoint = points.first()
    val x0 = leftPad + (firstPoint.turn.toFloat() / points.last().turn) * chartWidth
    val y0 = topPad + (1f - firstPoint.winrate / 100f) * chartHeight
    path.moveTo(x0, y0)

    for (i in 1 until points.size) {
        val prev = points[i - 1]
        val curr = points[i]
        val next = points.getOrNull(i + 1)

        val x1 = leftPad + (curr.turn.toFloat() / points.last().turn) * chartWidth
        val y1 = topPad + (1f - curr.winrate / 100f) * chartHeight

        val xPrev = leftPad + (prev.turn.toFloat() / points.last().turn) * chartWidth
        val yPrev = topPad + (1f - prev.winrate / 100f) * chartHeight

        // 控制点偏移量
        val tension = 0.3f
        val dx = (x1 - xPrev) * tension
        val dy = (y1 - yPrev) * tension

        val cp1x = xPrev + dx
        val cp1y = yPrev + dy
        val cp2x = x1 - dx
        val cp2y = y1 - dy

        path.cubicTo(cp1x, cp1y, cp2x, cp2y, x1, y1)
    }

    return path
}

/** 胜率图数据点 */
private data class WinrateChartPoint(val turn: Int, val winrate: Float)

private fun drawTextHelper(
    drawScope: DrawScope,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    text: String,
    style: TextStyle,
    topLeft: Offset
) {
    val layout = textMeasurer.measure(text, style)
    drawScope.drawText(textLayoutResult = layout, topLeft = topLeft)
}
