package com.goanalyzer.ui.tsumego

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.goanalyzer.data.TsumegoAnswer
import com.goanalyzer.data.TsumegoProblem
import com.goanalyzer.data.TsumegoProgress

/**
 * 死活题练习 ViewModel
 * 管理题目列表、答题状态、进度
 */
class TsumegoViewModel(
    problems: List<TsumegoProblem>
) {
    /** 所有题目 */
    val allProblems: List<TsumegoProblem> = problems

    /** 当前题目索引 */
    var currentIndex by mutableStateOf(0)
        private set

    /** 练习模式激活 */
    var isPracticeMode by mutableStateOf(false)
        private set

    /** 各题答题状态 */
    private val _answers = mutableMapOf<String, TsumegoAnswer>()
    val answers: Map<String, TsumegoAnswer> = _answers

    /** 用户在当前题目的落子位置（用于练习模式棋盘显示） */
    var userMove by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    /** 是否显示正确答案 */
    var showAnswer by mutableStateOf(false)
        private set

    /** 各题的胜率提升（正确后） */
    private val _winrateGains = mutableMapOf<String, Float>()
    val winrateGains: Map<String, Float> = _winrateGains

    /** 是否全部完成 */
    val isAllDone: Boolean
        get() = allProblems.all { _answers[it.id] != TsumegoAnswer.PENDING }

    /** 总体进度 */
    val progress: TsumegoProgress
        get() {
            var correct = 0
            var wrong = 0
            var skipped = 0
            for (p in allProblems) {
                when (_answers[p.id]) {
                    TsumegoAnswer.CORRECT -> correct++
                    TsumegoAnswer.WRONG -> wrong++
                    TsumegoAnswer.SKIPPED -> skipped++
                    else -> {}
                }
            }
            return TsumegoProgress(allProblems.size, correct, wrong, skipped)
        }

    /** 当前题目 */
    val currentProblem: TsumegoProblem?
        get() = allProblems.getOrNull(currentIndex)

    /** 开始练习模式 */
    fun startPractice() {
        isPracticeMode = true
        currentIndex = 0
        userMove = null
        showAnswer = false
        // 重置状态
        _answers.clear()
        _winrateGains.clear()
        for (p in allProblems) {
            _answers[p.id] = TsumegoAnswer.PENDING
        }
    }

    /** 退出练习模式 */
    fun exitPractice() {
        isPracticeMode = false
        userMove = null
        showAnswer = false
    }

    /** 用户在棋盘上落子 */
    fun placeMove(x: Int, y: Int) {
        if (!isPracticeMode) return
        val problem = currentProblem ?: return
        if (_answers[problem.id] != TsumegoAnswer.PENDING) return
        userMove = Pair(x, y)
    }

    /** 确认当前落子（判定对错） */
    fun confirmMove() {
        val problem = currentProblem ?: return
        val move = userMove ?: return
        if (_answers[problem.id] != TsumegoAnswer.PENDING) return

        val isCorrect = move == problem.correctMove
        _answers[problem.id] = if (isCorrect) TsumegoAnswer.CORRECT else TsumegoAnswer.WRONG
        showAnswer = true

        // 计算胜率提升（答对后相当于从坏手胜率恢复到正确着法胜率）
        if (isCorrect) {
            _winrateGains[problem.id] = problem.winrateDrop
        }
    }

    /** 跳过当前题目 */
    fun skipCurrent() {
        val problem = currentProblem ?: return
        if (_answers[problem.id] != TsumegoAnswer.PENDING) return
        _answers[problem.id] = TsumegoAnswer.SKIPPED
        showAnswer = true
    }

    /** 显示答案（仅查看，不答题） */
    fun revealAnswer() {
        val problem = currentProblem ?: return
        _answers[problem.id] = TsumegoAnswer.REVEALED
        showAnswer = true
        userMove = problem.correctMove
    }

    /** 下一题 */
    fun nextProblem() {
        if (currentIndex < allProblems.size - 1) {
            currentIndex++
            userMove = null
            showAnswer = false
        }
    }

    /** 上一题 */
    fun prevProblem() {
        if (currentIndex > 0) {
            currentIndex--
            userMove = null
            showAnswer = false
        }
    }

    /** 跳转到指定题 */
    fun jumpToProblem(index: Int) {
        if (index in allProblems.indices) {
            currentIndex = index
            userMove = null
            showAnswer = false
        }
    }

    /** 获取指定题目的答题状态 */
    fun getAnswerState(problemId: String): TsumegoAnswer = _answers[problemId] ?: TsumegoAnswer.PENDING

    /** 重置所有答题状态 */
    fun reset() {
        _answers.clear()
        _winrateGains.clear()
        for (p in allProblems) {
            _answers[p.id] = TsumegoAnswer.PENDING
        }
        currentIndex = 0
        userMove = null
        showAnswer = false
    }
}
