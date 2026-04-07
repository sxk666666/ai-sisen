package com.goanalyzer.ui.screen

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goanalyzer.data.*
import com.goanalyzer.data.AnalysisEvent
import com.goanalyzer.ui.board.StoneState
import com.goanalyzer.ui.analysis.MoveRating
import com.goanalyzer.ui.analysis.calcWinrateLoss
import com.goanalyzer.ui.analysis.classifyMove
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class GameState(
    val game: SgfGame? = null,
    val analysisResult: AnalysisResult? = null,
    val currentMoveIndex: Int = 0,
    val sgfContent: String = "",
    val recordId: String = "",
    val fileName: String = "",
    val analysisOverlayMode: AnalysisOverlayMode = AnalysisOverlayMode.BASIC,
    val pvPreviewMoves: List<PvPreviewMove> = emptyList(),
    val pvPreviewStep: Int = 0,
    val pvPreviewVisible: Boolean = false,
    /** 分析摘要：完成后显示一次 */
    val analysisSummary: AnalysisSummary? = null,
    /** 实时分析进度：(current, total, message)，仅分析中有效 */
    val analysisProgress: Triple<Int, Int, String>? = null,
    /** 失误手数列表（缓存，避免重复遍历） */
    val blunderMoveIndices: List<Int> = emptyList(),
    /** 对局统计（黑子数、白子数、提子数等） */
    val gameStats: GameStats? = null,
)

/** 分析完成后的一次性摘要数据 */
data class AnalysisSummary(
    val totalMoves: Int,
    val bestMoveCount: Int,
    val goodMoveCount: Int,
    val inaccuracyCount: Int,
    val mistakeCount: Int,
    val blunderCount: Int,
    val firstBlunderMove: Int? = null  // 第一个失误的手数
)

sealed interface AnalysisUiState {
    data object Idle : AnalysisUiState
    data object Loading : AnalysisUiState
    data object Analyzing : AnalysisUiState
    data class Error(val message: String) : AnalysisUiState
    data class Complete(val result: AnalysisResult) : AnalysisUiState
}

class AnalysisViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<AnalysisUiState>(AnalysisUiState.Idle)
    val uiState: StateFlow<AnalysisUiState> = _uiState.asStateFlow()

    private val _gameState = MutableStateFlow(GameState())
    val gameState: StateFlow<GameState> = _gameState.asStateFlow()

    /** 当前分析任务的 Job，用于取消 */
    private var analyzeJob: Job? = null

    /**
     * 从 URI 加载 SGF 文件
     */
    fun loadSgfFromUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            _uiState.value = AnalysisUiState.Loading
            try {
                val content = context.contentResolver.openInputStream(uri)?.use {
                    it.bufferedReader().readText()
                } ?: throw Exception("无法读取文件")

                val fileName = uri.lastPathSegment ?: "unknown.sgf"
                loadSgfContent(content, fileName)
            } catch (e: Exception) {
                _uiState.value = AnalysisUiState.Error("加载失败: ${e.message}")
            }
        }
    }

    /**
     * 从野狐导入 SGF 棋谱
     * @param sgfContent SGF 内容文本
     * @param gameName 对局名称（如 "柯洁 vs 申真谞 (白胜)"）
     */
    fun loadSgfFromFoxwq(sgfContent: String, gameName: String) {
        val fileName = "$gameName.sgf"
        loadSgfContent(sgfContent, fileName)
    }

    /**
     * 从 GameRecord 加载棋谱（含已保存的分析结果）
     */
    fun loadFromRecord(record: GameRecord, repository: GameRecordRepository? = null) {
        loadSgfContent(record.sgfContent, record.fileName, record.id)

        // 恢复已保存的分析数据
        if (record.analyzed && repository != null) {
            val analysisResult = repository.loadAnalysisResult(record.id)
            if (analysisResult != null) {
                _gameState.update { it.copy(analysisResult = analysisResult) }
            }
        }
    }

    /**
     * 从历史记录 ID 加载棋谱（复盘用）
     * @return true 如果成功加载，false 如果记录未找到
     */
    fun loadFromRecordId(recordId: String, repository: GameRecordRepository): Boolean {
        val record = repository.getRecord(recordId) ?: return false
        loadFromRecord(record, repository)
        return true
    }

    private fun loadSgfContent(content: String, fileName: String, recordId: String = "") {
        try {
            val parser = SgfParser()
            val game = parser.parse(content)

            _gameState.value = GameState(
                game = game,
                currentMoveIndex = 0,
                sgfContent = content,
                recordId = recordId,
                fileName = fileName,
                analysisSummary = null
            )
            updateGameStats()  // 计算初始对局统计
            _uiState.value = AnalysisUiState.Idle
        } catch (e: Exception) {
            _uiState.value = AnalysisUiState.Error("解析失败: ${e.message}")
        }
    }

    /**
     * 调用服务器分析当前棋谱（WebSocket 流式，支持真实进度）
     */
    fun analyzeCurrentGame(apiClient: ApiClient, repository: GameRecordRepository? = null) {
        analyzeJob = viewModelScope.launch {
            val sgfContent = _gameState.value.sgfContent
            if (sgfContent.isEmpty()) {
                _uiState.value = AnalysisUiState.Error("请先加载棋谱")
                return@launch
            }

            _uiState.value = AnalysisUiState.Analyzing
            _gameState.update { it.copy(analysisProgress = Triple(0, 0, "正在连接分析服务...")) }

            try {
                var finalResult: AnalysisResult? = null

                apiClient.analyzeWithProgress(sgfContent).collect { event ->
                    when (event) {
                        is AnalysisEvent.Progress -> {
                            _gameState.update {
                                it.copy(analysisProgress = Triple(event.current, event.total, event.message))
                            }
                        }
                        is AnalysisEvent.Complete -> {
                            finalResult = event.result
                        }
                        is AnalysisEvent.Error -> {
                            throw RuntimeException(event.message)
                        }
                    }
                }

                val result = finalResult ?: throw RuntimeException("未收到分析结果")

                // 生成分析摘要 + 缓存失误列表（一次性计算，避免重复遍历）
                val summary = generateAnalysisSummary(result)
                val blunders = computeBlunderIndices(result)

                _gameState.update {
                    it.copy(
                        analysisResult = result,
                        currentMoveIndex = 1,
                        analysisProgress = null,
                        analysisSummary = summary,
                        blunderMoveIndices = blunders,
                    )
                }

                _uiState.value = AnalysisUiState.Complete(result)

                // 保存到历史记录
                if (repository != null) {
                    val state = _gameState.value
                    val game = state.game ?: return@launch
                    val record = GameRecord(
                        id = state.recordId,
                        blackName = game.blackName,
                        whiteName = game.whiteName,
                        boardSize = game.boardSize,
                        komi = game.komi,
                        rules = game.rules,
                        gameDate = game.gameDate,
                        result = game.result,
                        totalMoves = game.moves.count { !it.isSetup },
                        analyzed = true,
                        sgfContent = sgfContent,
                        fileName = state.fileName
                    )
                    val id = repository.saveRecord(record)
                    repository.updateAnalysis(id, result)
                    _gameState.update { it.copy(recordId = id) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _gameState.update { it.copy(analysisProgress = null) }
                _uiState.value = AnalysisUiState.Idle
            } catch (e: Exception) {
                _gameState.update { it.copy(analysisProgress = null) }
                _uiState.value = AnalysisUiState.Error(toHumanReadableError(e))
            }
        }
    }

    /**
     * 取消正在进行的分析
     */
    fun cancelAnalysis() {
        analyzeJob?.cancel()
        analyzeJob = null
        _gameState.update { it.copy(analysisProgress = null) }
        _uiState.value = AnalysisUiState.Idle
    }

    /**
     * 清除错误状态（关闭错误提示条）
     */
    fun clearError() {
        if (_uiState.value is AnalysisUiState.Error) {
            _uiState.value = AnalysisUiState.Idle
        }
    }

    /**
     * 跳转到指定手数
     */
    fun jumpToMove(moveIndex: Int) {
        _gameState.update {
            it.copy(
                currentMoveIndex = moveIndex,
                pvPreviewVisible = false,
                pvPreviewMoves = emptyList(),
                pvPreviewStep = 0
            )
        }
        updateGameStats()  // 更新对局统计
    }

    /**
     * 切换分析覆盖模式
     */
    fun cycleOverlayMode() {
        _gameState.update { state ->
            val nextMode = when (state.analysisOverlayMode) {
                AnalysisOverlayMode.OFF -> AnalysisOverlayMode.BASIC
                AnalysisOverlayMode.BASIC -> AnalysisOverlayMode.HEATMAP
                AnalysisOverlayMode.HEATMAP -> AnalysisOverlayMode.OFF
            }
            state.copy(analysisOverlayMode = nextMode)
        }
    }

    /**
     * 获取所有失误点索引列表（直接读缓存，O(1)）
     * 失误定义：小失误、失误、严重失误
     */
    fun getBlunderMoves(): List<Int> = _gameState.value.blunderMoveIndices

    /**
     * 计算失误手数列表（分析完成后调用一次，结果存入 GameState 缓存）
     */
    private fun computeBlunderIndices(result: AnalysisResult): List<Int> {
        val blunders = mutableListOf<Int>()
        for (i in result.moves.indices) {
            if (i == 0) continue
            val move = result.moves[i]
            if (move.move.isEmpty()) continue
            // 使用服务端计算的 isBestMove 字段
            val isBestMove = move.isBestMove
            val loss = calcWinrateLoss(move.moveInfos, move.move, move.isBlackMove) ?: continue
            val rating = classifyMove(loss, isBestMove)
            if (rating == MoveRating.BLUNDER || rating == MoveRating.MISTAKE || rating == MoveRating.INACCURACY) {
                blunders.add(i + 1) // turnNumber 从 1 开始
            }
        }
        return blunders
    }

    /**
     * 跳转到下一个失误点
     */
    fun jumpToNextBlunder() {
        val blunders = getBlunderMoves()
        if (blunders.isEmpty()) return
        val current = _gameState.value.currentMoveIndex
        val next = blunders.firstOrNull { it > current }
        if (next != null) {
            jumpToMove(next)
        } else {
            // 循环到第一个失误
            jumpToMove(blunders.first())
        }
    }

    /**
     * 跳转到上一个失误点
     */
    fun jumpToPrevBlunder() {
        val blunders = getBlunderMoves()
        if (blunders.isEmpty()) return
        val current = _gameState.value.currentMoveIndex
        val prev = blunders.lastOrNull { it < current }
        if (prev != null) {
            jumpToMove(prev)
        } else {
            // 循环到最后一个失误
            jumpToMove(blunders.last())
        }
    }

    /**
     * 显示指定候选手的 PV 变化图预览
     */
    fun showPvPreview(candidateMove: CandidateMove, boardSize: Int) {
        val pvMoves = mutableListOf<PvPreviewMove>()
        val baseBoardState = getBoardStateAtCurrentMove() ?: return

        var isBlack = true
        val currentGame = _gameState.value.game ?: return
        val currentTurn = _gameState.value.currentMoveIndex

        // 确定当前该谁下
        val normalMoves = currentGame.moves.filter { !it.isSetup }
        isBlack = if (currentTurn > 0 && currentTurn <= normalMoves.size) {
            normalMoves[currentTurn - 1].isBlack
        } else {
            true
        }
        // 下一手颜色取反
        isBlack = !isBlack

        // 从当前棋盘状态开始，模拟 PV
        val simBoard = baseBoardState.copy()

        candidateMove.pv.forEachIndexed { index, moveStr ->
            val coord = SgfParser.stringToCoordinate(moveStr, boardSize) ?: return
            simBoard.placeStone(coord.first, coord.second, isBlack)
            pvMoves.add(PvPreviewMove(
                x = coord.first,
                y = coord.second,
                isBlack = isBlack,
                label = index + 1
            ))
            isBlack = !isBlack
        }

        _gameState.update {
            it.copy(
                pvPreviewMoves = pvMoves,
                pvPreviewStep = pvMoves.size,
                pvPreviewVisible = true
            )
        }
    }

    /**
     * 步进 PV 预览
     */
    fun stepPvPreview(step: Int) {
        _gameState.update {
            it.copy(pvPreviewStep = step.coerceIn(0, it.pvPreviewMoves.size))
        }
    }

    /**
     * 隐藏 PV 预览
     */
    fun hidePvPreview() {
        _gameState.update {
            it.copy(pvPreviewVisible = false, pvPreviewMoves = emptyList(), pvPreviewStep = 0)
        }
    }

    /**
     * 生成分析摘要
     */
    private fun generateAnalysisSummary(result: AnalysisResult): AnalysisSummary {
        var bestCount = 0
        var goodCount = 0
        var inaccuracyCount = 0
        var mistakeCount = 0
        var blunderCount = 0
        var firstBlunderMove: Int? = null

        for (i in 1 until result.moves.size) {
            val move = result.moves[i]
            if (move.move.isEmpty()) continue
            // 使用服务端计算的 isBestMove 字段
            val isBestMove = move.isBestMove
            val loss = calcWinrateLoss(move.moveInfos, move.move, move.isBlackMove) ?: 0f
            val rating = classifyMove(loss, isBestMove)
            when (rating) {
                MoveRating.BEST_MOVE -> bestCount++
                MoveRating.GOOD_MOVE -> goodCount++
                MoveRating.INACCURACY -> inaccuracyCount++
                MoveRating.MISTAKE -> { mistakeCount++; if (firstBlunderMove == null) firstBlunderMove = i + 1 }
                MoveRating.BLUNDER -> { blunderCount++; if (firstBlunderMove == null) firstBlunderMove = i + 1 }
            }
        }

        return AnalysisSummary(
            totalMoves = result.moves.count { it.move.isNotEmpty() },
            bestMoveCount = bestCount,
            goodMoveCount = goodCount,
            inaccuracyCount = inaccuracyCount,
            mistakeCount = mistakeCount,
            blunderCount = blunderCount,
            firstBlunderMove = firstBlunderMove
        )
    }

    /**
     * 关闭分析摘要弹窗
     */
    fun dismissAnalysisSummary() {
        _gameState.update { it.copy(analysisSummary = null) }
    }

    /**
     * 从摘要跳转到第一个失误
     */
    fun jumpToFirstBlunder() {
        val first = _gameState.value.analysisSummary?.firstBlunderMove ?: return
        dismissAnalysisSummary()
        jumpToMove(first)
    }

    private fun getBoardStateAtCurrentMove(): com.goanalyzer.ui.board.GoBoardState? {
        val game = _gameState.value.game ?: return null
        val states = com.goanalyzer.ui.board.computeBoardStates(game)
        val stateIndex = (_gameState.value.currentMoveIndex).coerceIn(0, states.size - 1)
        return states[stateIndex]
    }

    /**
     * 计算当前对局统计
     */
    private fun computeCurrentGameStats(): GameStats? {
        val game = _gameState.value.game ?: return null
        val boardState = getBoardStateAtCurrentMove() ?: return null

        // 计算让子棋初始棋子数
        var setupBlackCount = 0
        var setupWhiteCount = 0
        for (move in game.moves) {
            if (move.isSetup) {
                if (move.isBlack) setupBlackCount++ else setupWhiteCount++
            } else break // 只计算初始布局
        }

        // 计算当前盘面棋子数
        var blackOnBoard = 0
        var whiteOnBoard = 0
        for (x in 0 until boardState.boardSize) {
            for (y in 0 until boardState.boardSize) {
                when (boardState.get(x, y)) {
                    StoneState.BLACK -> blackOnBoard++
                    StoneState.WHITE -> whiteOnBoard++
                    StoneState.EMPTY -> {}
                }
            }
        }

        // 计算当前手索引对应的非 setup 走法数量
        // currentMoveIndex 是 game.moves 中的位置（0=初始盘面），states[i] = 第 i 手之后的状态
        val currentMoveInGame = _gameState.value.currentMoveIndex.coerceAtMost(game.moves.size)
        var blackMovesPlaced = 0  // 黑方已下（提子）的手数
        var whiteMovesPlaced = 0  // 白方已下（提子）的手数
        for (i in 0 until currentMoveInGame) {
            val move = game.moves[i]
            if (!move.isSetup && !move.isPass) {
                if (move.isBlack) blackMovesPlaced++ else whiteMovesPlaced++
            }
        }

        // 计算提子数：已下的手 - 盘面剩余棋子数
        val blackCaptures = maxOf(0, whiteMovesPlaced - whiteOnBoard)
        val whiteCaptures = maxOf(0, blackMovesPlaced - blackOnBoard)

        val totalMoves = game.moves.count { !it.isSetup }

        return GameStats(
            blackStonesOnBoard = blackOnBoard,
            whiteStonesOnBoard = whiteOnBoard,
            blackCaptures = blackCaptures,
            whiteCaptures = whiteCaptures,
            totalMoves = totalMoves,
            currentTurn = _gameState.value.currentMoveIndex
        )
    }

    /**
     * 更新当前对局统计
     */
    private fun updateGameStats() {
        val stats = computeCurrentGameStats()
        _gameState.update { it.copy(gameStats = stats) }
    }

    /**
     * 将异常转为人类可读的中文错误提示（P1-5）
     */
    private fun toHumanReadableError(e: Exception): String {
        val msg = e.message ?: ""
        return when {
            // 网络连接类
            e is java.net.ConnectException ||
            msg.contains("Connection refused", ignoreCase = true) ||
            msg.contains("ECONNREFUSED", ignoreCase = true) ->
                "无法连接到分析服务器，请确认服务已启动并检查 IP 地址"

            e is java.net.SocketTimeoutException ||
            msg.contains("timeout", ignoreCase = true) ||
            msg.contains("timed out", ignoreCase = true) ->
                "分析超时，请检查 KataGo 是否正常运行，或减少棋谱手数后重试"

            e is java.net.UnknownHostException ||
            msg.contains("UnknownHost", ignoreCase = true) ->
                "找不到服务器，请检查 IP 地址是否正确"

            // SGF / 棋谱解析
            msg.contains("SGF", ignoreCase = true) ||
            msg.contains("parse", ignoreCase = true) ->
                "棋谱格式错误，请确认是标准 SGF 文件"

            // KataGo 相关
            msg.contains("KataGo", ignoreCase = true) ||
            msg.contains("katago", ignoreCase = true) ->
                "AI 引擎出错：$msg\n请重启服务后再试"

            // HTTP 错误
            msg.contains("500") || msg.contains("Internal Server Error", ignoreCase = true) ->
                "服务器内部错误，请查看服务端日志"

            msg.contains("404") ->
                "接口未找到，请确认服务版本正确"

            msg.contains("401") || msg.contains("403") ->
                "权限不足，请检查服务器配置"

            // 通用兜底
            else -> "分析失败：${msg.take(80)}"
        }
    }
}
