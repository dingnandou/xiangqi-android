package com.yijin.xiangqi.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * 引擎会话的 Kotlin 门面。
 *
 * 职责边界（对应《02-技术方案与验收标准》§5.1/§5.2）：
 *  - 本类**不是**引擎的替代品，它只是把 JNI 的同步接口包装成可安全使用的会话。
 *  - 搜索全部由 Pikafish 自己的线程池执行；本类任何方法都不会在调用线程上跑搜索。
 *  - 每次 [go] 分配一个新的 requestId。事件带 requestId 回流，调用方比对后丢弃过期响应。
 *    这就是 §5.2 要求的「核对 requestId / sessionId / revision」在本阶段的最小实现。
 */
class EngineSession private constructor(
    private val handle: Long,
    private val networkDirectory: File,
) : AutoCloseable {

    private val nextRequestId = AtomicLong(0L)
    private var closed = false

    /** 引擎自报版本，例如 `Pikafish 2026-09-06`。 */
    val engineInfo: String get() = requireHandle { NativeEngine.nativeEngineInfo(handle) }

    /** 网络文件描述，例如 `NNUE evaluation using pikafish.nnue (64MiB, ...)`。 */
    val networkInfo: String get() = requireHandle { NativeEngine.nativeNetworkInfo(handle) }

    /** 设置局面。[moves] 使用 UCI 坐标记谱（如 `h2e2`）。失败返回错误文本，成功返回空串。 */
    fun setPosition(fen: String, moves: List<String> = emptyList()): String =
        requireHandle { NativeEngine.nativeSetPosition(handle, fen, moves.toTypedArray()) }

    /** 引擎当前局面。 */
    fun currentFen(): String = requireHandle { NativeEngine.nativeFEN(handle) }

    /** 全部 UCI 选项，格式见 [EngineOption]。 */
    fun options(): List<EngineOption> = requireHandle {
        NativeEngine.nativeOptions(handle).map(EngineOption.Companion::parse)
    }

    /** 设置选项，返回 false 表示引擎没有这个选项名。 */
    fun setOption(name: String, value: String): Boolean =
        requireHandle { NativeEngine.nativeSetOption(handle, name, value) }

    /**
     * 启动搜索，立即返回。
     *
     * @param budget 搜索预算。三个维度至少给一个非零值。
     * @return 本次请求编号，用于过滤事件。
     */
    fun go(
        budget: SearchBudget,
        ponder: Boolean = false,
    ): Long {
        val requestId = nextRequestId.incrementAndGet()
        val started = requireHandle {
            NativeEngine.nativeGo(
                handle = handle,
                requestId = requestId,
                movetimeMs = budget.movetimeMs,
                depth = budget.depth,
                nodes = budget.nodes,
                infinite = budget.infinite,
                ponder = ponder,
            )
        }
        if (!started) {
            Log.w(TAG, "go() failed to start, requestId=$requestId")
        }
        return requestId
    }

    /** 请求停止。非阻塞，具体是否停下看 [drainEvents] 里的 [EngineEvent.BestMove]。 */
    fun stop() = requireHandle { NativeEngine.nativeStop(handle) }

    /** 清空置换表与搜索状态。对应 UCI 的 `ucinewgame`。 */
    fun clearSearch() = requireHandle { NativeEngine.nativeClearSearch(handle) }

    /** 取走当前积压的事件并清空队列。不会阻塞。 */
    fun drainEvents(): List<EngineEvent> =
        requireHandle { EngineEventParser.parseAll(NativeEngine.nativePollEvents(handle)) }

    /** 只保留属于 [requestId] 的事件。 */
    fun drainEvents(requestId: Long): List<EngineEvent> =
        drainEvents().filter { it.requestId == requestId || it is EngineEvent.NetworkInfo }

    /** 置换表占用（千分比）。 */
    fun hashfullPermille(): Int = requireHandle { NativeEngine.nativeHashfull(handle) }

    /** perft 节点数。失败抛异常，由调用方决定是否展示。 */
    fun perft(fen: String, depth: Int): Long =
        requireHandle { NativeEngine.nativePerft(handle, fen, depth) }

    override fun close() {
        if (closed) return
        closed = true
        NativeEngine.nativeDestroy(handle)
    }

    private inline fun <T> requireHandle(block: () -> T): T {
        check(!closed) { "引擎会话已关闭" }
        return block()
    }

    companion object {
        private const val TAG = "EngineSession"

        /** 编译期信息：架构、SIMD 与编译器。用于确认跑的是哪份二进制。 */
        fun buildInfo(): String = NativeEngine.nativeBuildInfo()

        /**
         * 创建会话。**必须在后台线程调用**：Pikafish 的 `Eval::NNUE::Network` 约 65 MB，
         * 且内部搜索线程对栈有要求，重初始化不是瞬时操作。
         *
         * 失败时返回 [EngineOpenResult.Failed]，绝不抛出到调用方之上，
         * 因为引擎不可用时上层必须显示真实原因，而不是崩溃。
         */
        fun open(
            context: Context,
            threads: Int,
            hashMb: Int,
            verifyNetwork: Boolean = false,
        ): EngineOpenResult =
            when (val asset = NetworkAsset.prepare(context, verifyNetwork)) {
                is NetworkAsset.Result.Failed -> EngineOpenResult.Failed(asset.reason)

                is NetworkAsset.Result.Ready -> try {
                    EngineOpenResult.Ready(
                        EngineSession(
                            handle = NativeEngine.nativeCreate(
                                netDir = asset.directory.absolutePath,
                                threads = threads,
                                hashMb = hashMb,
                            ),
                            networkDirectory = asset.directory,
                        ),
                        asset.verifiedNow,
                    )
                } catch (e: Throwable) {
                    // UnsatisfiedLinkError / RuntimeException 都归到这里：
                    // 引擎没起来就是没起来，不能伪装成可用。
                    EngineOpenResult.Failed("引擎初始化失败：${e.message ?: e::class.java.simpleName}")
                }
            }
    }
}

/** 搜索预算。三个维度至少一个非零。 */
data class SearchBudget(
    val movetimeMs: Int = 0,
    val depth: Int = 0,
    val nodes: Long = 0L,
    val infinite: Boolean = false,
) {
    init {
        require(movetimeMs >= 0 && depth >= 0 && nodes >= 0L) { "搜索预算不能为负" }
    }
}

sealed interface EngineOpenResult {
    data class Ready(val session: EngineSession, val networkVerifiedNow: Boolean) : EngineOpenResult
    data class Failed(val reason: String) : EngineOpenResult
}

/**
 * 一个 UCI 选项。字段与引擎实际给出的一致，不做归一化，
 * 这样界面上显示的就是引擎真正支持的东西。
 */
data class EngineOption(
    val name: String,
    val type: String,
    val defaultValue: String?,
    val min: Int?,
    val max: Int?,
) {
    companion object {
        /**
         * 解析 `option name Threads type spin default 1 min 1 max 1024` 这类文本。
         * 解析失败返回 name 为空的选项，由调用方决定是否展示。
         */
        fun parse(line: String): EngineOption {
            val tokens = line.trim().split(Regex("\\s+"))
            fun valueAfter(key: String): String? {
                val index = tokens.indexOf(key)
                return if (index in 0 until tokens.lastIndex) tokens[index + 1] else null
            }
            return EngineOption(
                name = valueAfter("name").orEmpty(),
                type = valueAfter("type").orEmpty(),
                defaultValue = valueAfter("default"),
                min = valueAfter("min")?.toIntOrNull(),
                max = valueAfter("max")?.toIntOrNull(),
            )
        }
    }
}