package com.yijin.xiangqi.engine

/**
 * 引擎事件。
 *
 * 对应《02-技术方案与验收标准》§6.1「保留评分原始类型」：
 * 引擎返回什么就存什么，不把 cp / mate / 上下界揉成一个浮点数，
 * 也不在这里换算红黑视角——那是 M3 复盘阶段的职责。
 */
sealed interface EngineEvent {

    /** 归属的请求编号。与发起搜索时的 requestId 不一致的事件必须丢弃。 */
    val requestId: Long

    /** 引擎内部为本次搜索分配的序号，用于区分「新搜索」与「上一次搜索的迟到结果」。 */
    val seq: Long

    /** 搜索刚开始。 */
    data class Started(override val requestId: Long, override val seq: Long) : EngineEvent

    /** 搜索进展。[score] 保留原始类型，不做折算。 */
    data class Info(
        override val requestId: Long,
        override val seq: Long,
        val depth: Int,
        val selDepth: Int,
        val multiPv: Int,
        val score: EngineScore,
        val bound: String,
        val nodes: Long,
        val nps: Long,
        val timeMs: Long,
        val hashfullPermille: Int,
        val wdl: String,
        val pv: String,
    ) : EngineEvent

    /** 搜索结束。`bestmove` 为空表示引擎没有可用着法（如终局或被强制停止）。 */
    data class BestMove(
        override val requestId: Long,
        override val seq: Long,
        val bestMove: String,
        val ponder: String,
    ) : EngineEvent

    /** 引擎启动期输出，例如网络文件描述。 */
    data class NetworkInfo(val text: String) : EngineEvent

    /** 解析不了的原始事件，保留原文以便排查，不静默丢弃。 */
    data class Unknown(override val requestId: Long, override val seq: Long, val raw: String) : EngineEvent
}

/**
 * 引擎评分，保留原始类型。
 *
 * - [Cp]：以厘兵(centipawn)为单位的内部数值，**不是胜率**，也不是"差几个兵"。
 * - [Mate]：距离将死的着数，符号表示对哪一方有利；0 表示正在被将死。
 * - [None]：引擎没有给出可用评分。
 * - [Bound]：只有上界/下界（alpha-beta 未收敛），不可直接与完整评分比较。
 */
sealed interface EngineScore {

    data class Cp(val value: Int) : EngineScore

    data class Mate(val plies: Int) : EngineScore

    data object None : EngineScore

    /** 边界类型，保留引擎给出的 upper/lower 标记。 */
    data class Bound(val value: Int, val bound: String) : EngineScore

    /** 供界面显示的短文本。刻意不显示百分胜率。 */
    fun display(): String = when (this) {
        is Cp -> if (value > 0) "+$value" else value.toString()
        is Mate -> when {
            plies > 0 -> "M${(plies + 1) / 2}"
            plies < 0 -> "-M${(-plies + 1) / 2}"
            else -> "M0"
        }
        is Bound -> if (value > 0) "+$value${boundSuffix()}" else "${value}${boundSuffix()}"
        None -> "—"
    }

    private fun boundSuffix(): String = when (bound.lowercase()) {
        "lower" -> "…"
        "upper" -> "^"
        else -> ""
    }
}

/**
 * 把 C++ 侧拼好的字符串事件解析成结构化对象。
 *
 * 单独成类是为了能在 JVM 单元测试里直接喂字符串验证解析，
 * 不需要真机（见 engine/src/test/.../EngineEventParserTest.kt）。
 */
object EngineEventParser {

    private const val NO_REQUEST = -1L

    fun parseAll(raw: Array<String>): List<EngineEvent> = raw.map(::parse)

    fun parse(line: String): EngineEvent {
        val parts = line.split('|')
        return when (parts.firstOrNull()) {
            "START" -> EngineEvent.Started(parts.longAt(1), parts.longAt(2))

            "INFO" -> EngineEvent.Info(
                requestId = parts.longAt(1),
                seq = parts.longAt(2),
                depth = parts.intAt(3),
                selDepth = parts.intAt(4),
                multiPv = parts.intAt(5),
                score = parseScore(parts.stringAt(6), parts.stringAt(7), parts.stringAt(8)),
                bound = parts.stringAt(8),
                nodes = parts.longAt(9),
                nps = parts.longAt(10),
                timeMs = parts.longAt(11),
                hashfullPermille = parts.intAt(12),
                wdl = parts.stringAt(13),
                pv = parts.stringAt(14),
            )

            "BESTMOVE" -> EngineEvent.BestMove(
                requestId = parts.longAt(1),
                seq = parts.longAt(2),
                bestMove = parts.stringAt(3),
                ponder = parts.stringAt(4),
            )

            "NETINFO" -> EngineEvent.NetworkInfo(parts.stringAt(1))

            else -> EngineEvent.Unknown(NO_REQUEST, 0L, line)
        }
    }

    private fun parseScore(type: String, value: String, bound: String): EngineScore {
        val intValue = value.toIntOrNull() ?: return EngineScore.None
        return when (type) {
            "cp" -> if (bound.isEmpty()) EngineScore.Cp(intValue) else EngineScore.Bound(intValue, bound)
            "mate" -> EngineScore.Mate(intValue)
            else -> EngineScore.None
        }
    }

    private fun List<String>.stringAt(index: Int): String = getOrNull(index).orEmpty()

    private fun List<String>.intAt(index: Int): Int = stringAt(index).toIntOrNull() ?: 0

    private fun List<String>.longAt(index: Int): Long = stringAt(index).toLongOrNull() ?: NO_REQUEST
}