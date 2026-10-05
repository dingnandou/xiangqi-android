package com.yijin.xiangqi.engineverify

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yijin.xiangqi.engine.EngineEvent
import com.yijin.xiangqi.engine.EngineOpenResult
import com.yijin.xiangqi.engine.EngineOption
import com.yijin.xiangqi.engine.EngineScore
import com.yijin.xiangqi.engine.EngineSession
import com.yijin.xiangqi.engine.SearchBudget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * M0 工程验证页面的状态持有者。
 *
 * 这个 ViewModel **只服务于 M0 的引擎可行性验证**，
 * 不是最终产品首页（《03》总任务说明第 3 段已明确）。
 * M1 起会用真实的棋局状态替换掉这里的 positionFen / eventLog。
 */
class EngineVerifyViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(EngineVerifyUiState())
    val state: StateFlow<EngineVerifyUiState> = _state.asStateFlow()

    private var session: EngineSession? = null
    private var pollJob: Job? = null

    /** 本次搜索使用的 requestId；事件按它过滤，丢弃上一次搜索的迟到结果。 */
    private var activeRequestId: Long = -1L

    init {
        _state.update { it.copy(buildInfo = runCatching { EngineSession.buildInfo() }.getOrNull()) }
        initialize()
    }

    fun initialize(verifyNetwork: Boolean = false) {
        if (session != null) return
        setBusy(true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                EngineSession.open(
                    context = getApplication(),
                    threads = _state.value.threads,
                    hashMb = _state.value.hashMb,
                    verifyNetwork = verifyNetwork,
                )
            }
            when (result) {
                is EngineOpenResult.Failed -> {
                    session = null
                    _state.update {
                        it.copy(
                            phase = EnginePhase.Failed(result.reason),
                            isBusy = false,
                            options = emptyList(),
                            networkVerifiedNow = false,
                        )
                    }
                }

                is EngineOpenResult.Ready -> {
                    val opened = result.session
                    session = opened
                    val options = withContext(Dispatchers.IO) { opened.options() }
                    val info = withContext(Dispatchers.IO) { opened.engineInfo }
                    val net = withContext(Dispatchers.IO) { opened.networkInfo }
                    val fen = withContext(Dispatchers.IO) {
                        opened.setPosition(START_FEN).ifEmpty { opened.currentFen() }
                    }
                    _state.update {
                        it.copy(
                            phase = EnginePhase.Ready,
                            engineInfo = info,
                            networkInfo = net,
                            networkVerifiedNow = result.networkVerifiedNow,
                            options = options,
                            positionFen = fen,
                            isBusy = false,
                        )
                    }
                }
            }
        }
    }

    /** 重新初始化：彻底销毁会话再重建，用来验证重复启动是否稳定。 */
    fun reinitialize() {
        if (_state.value.isBusy) return
        setBusy(true)
        viewModelScope.launch {
            pollJob?.cancel()
            withContext(Dispatchers.IO) {
                runCatching { session?.close() }
                session = null
            }
            _state.update {
                it.copy(
                    phase = EnginePhase.Idle,
                    engineInfo = "",
                    networkInfo = "",
                    options = emptyList(),
                    events = emptyList(),
                    lastBestMove = null,
                    lastSearchSummary = null,
                    positionFen = "",
                    networkVerifiedNow = false,
                )
            }
            initialize()
        }
    }

    fun setThreads(threads: Int) {
        _state.update { it.copy(threads = threads) }
        applyEngineOption("Threads", threads.toString())
    }

    fun setHashMb(hashMb: Int) {
        _state.update { it.copy(hashMb = hashMb) }
        applyEngineOption("Hash", hashMb.toString())
    }

    private fun applyEngineOption(name: String, value: String) {
        val current = session ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { current.setOption(name, value) }
            if (!ok) {
                _state.update { it.copy(optionError = "引擎不接受选项 $name = $value") }
            } else {
                _state.update { it.copy(optionError = null) }
            }
        }
    }

    /** 「分析初始局面」：按当前预算搜索一次，并持续收集事件。 */
    fun analyze(positionFen: String = _state.value.positionFen, budget: SearchBudget) {
        val current = session ?: return
        if (_state.value.isSearching) return

        setBusy(true)
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) { current.setPosition(positionFen) }
            if (error.isNotEmpty()) {
                _state.update { it.copy(phase = EnginePhase.Failed("设置局面失败：$error"), isBusy = false) }
                return@launch
            }
            val requestId = withContext(Dispatchers.IO) { current.go(budget) }
            activeRequestId = requestId
            _state.update {
                it.copy(
                    isSearching = true,
                    searchStartedAt = System.nanoTime(),
                    events = emptyList(),
                    lastBestMove = null,
                    lastSearchSummary = "搜索中…",
                )
            }
            pollJob?.cancel()
            pollJob = launch { pollEvents(current, requestId, budget) }
        }
    }

    /** 「停止计算」：发出 stop，并继续等待 bestmove 事件以便确认真的停了。 */
    fun stop() {
        val current = session ?: return
        viewModelScope.launch { withContext(Dispatchers.IO) { current.stop() } }
    }

    private suspend fun pollEvents(
        current: EngineSession,
        requestId: Long,
        budget: SearchBudget,
    ) {
        var latest: EngineEvent.Info? = null
        while (viewModelScope.isActive) {
            val events = withContext(Dispatchers.IO) { current.drainEvents(requestId) }
            events.forEach { event ->
                if (event is EngineEvent.Info) latest = event
                if (event is EngineEvent.BestMove) {
                    val elapsedMs = (System.nanoTime() - _state.value.searchStartedAt) / 1_000_000
                    _state.update {
                        it.copy(
                            isSearching = false,
                            isBusy = false,
                            lastBestMove = event.bestMove.ifEmpty { "（无着法）" },
                            lastSearchSummary = buildString {
                                append("请求 #")
                                append(requestId)
                                append(" · 预算 ")
                                append(describe(budget))
                                append(" · 实际 ")
                                append(elapsedMs)
                                append(" ms")
                                val info = latest
                                if (info != null) {
                                    append(" · 深度 ")
                                    append(info.depth)
                                    append(" · 评分 ")
                                    append(info.score.display())
                                    append(" · 节点 ")
                                    append(info.nodes)
                                }
                            },
                            events = (it.events + events.takeLast(MAX_LOG)).takeLast(MAX_LOG),
                        )
                    }
                    return
                }
            }
            if (events.isNotEmpty()) {
                _state.update {
                    it.copy(events = (it.events + events.takeLast(MAX_LOG)).takeLast(MAX_LOG))
                }
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    /** perft：验证引擎走法生成的自洽性。M1 规则内核的交叉校验基线。 */
    fun runPerft(fen: String = _state.value.positionFen, depth: Int) {
        val current = session ?: return
        viewModelScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { current.perft(fen, depth) } }
            _state.update {
                it.copy(
                    perftResult = result.fold(
                        onSuccess = { nodes -> "perft($depth) = $nodes 节点" },
                        onFailure = { e -> "perft 失败：${e.message ?: e::class.java.simpleName}" },
                    )
                )
            }
        }
    }

    private fun describe(budget: SearchBudget): String = when {
        budget.infinite -> "不限时"
        budget.movetimeMs > 0 -> "${budget.movetimeMs} ms"
        budget.depth > 0 -> "深度 ${budget.depth}"
        budget.nodes > 0L -> "${budget.nodes} 节点"
        else -> "未指定"
    }

    private fun setBusy(busy: Boolean) = _state.update { it.copy(isBusy = busy) }

    override fun onCleared() {
        pollJob?.cancel()
        session?.close()
        session = null
        super.onCleared()
    }

    companion object {
        /** 中国象棋标准初始局面。规则内核在 M1 落地前，先由引擎 FEN 作为唯一事实来源。 */
        const val START_FEN = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1"

        private const val MAX_LOG = 200
        private const val POLL_INTERVAL_MS = 60L
        private const val TAG = "EngineVerify"
    }
}

sealed interface EnginePhase {
    data object Idle : EnginePhase
    data object Ready : EnginePhase
    data class Failed(val reason: String) : EnginePhase
}

data class EngineVerifyUiState(
    val phase: EnginePhase = EnginePhase.Idle,
    val buildInfo: String? = null,
    val engineInfo: String = "",
    val networkInfo: String = "",
    val networkVerifiedNow: Boolean = false,
    val options: List<EngineOption> = emptyList(),
    val positionFen: String = "",
    val threads: Int = 1,
    val hashMb: Int = 16,
    val isBusy: Boolean = false,
    val isSearching: Boolean = false,
    val lastBestMove: String? = null,
    val lastSearchSummary: String? = null,
    val perftResult: String? = null,
    val optionError: String? = null,
    val events: List<EngineEvent> = emptyList(),
    val searchStartedAt: Long = 0L,
)