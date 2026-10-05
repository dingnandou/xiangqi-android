package com.yijin.xiangqi.engineverify

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yijin.xiangqi.engine.EngineEvent
import com.yijin.xiangqi.engine.EngineOption
import com.yijin.xiangqi.engine.SearchBudget

/**
 * M0 引擎验证页面。
 *
 * 按《03-交给DeepSeek的开发任务书》§3，必须提供：
 *  - 引擎初始化状态、版本、可用选项
 *  - 「分析初始局面」「停止计算」「重新初始化」三个操作
 *
 * 页面上的每一个数字都必须来自真实引擎输出；没有数据时显示「未测试」，
 * 不允许出现占位值或伪造的成功提示。
 */
@Composable
fun EngineVerifyScreen(viewModel: EngineVerifyViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "M0 引擎验证",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "工程调试页面，不是最终产品首页。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        StatusCard(state)
        OptionsCard(state, viewModel)

        Text("操作", style = MaterialTheme.typography.titleMedium)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.analyze(budget = SearchBudget(movetimeMs = 1000)) },
                enabled = state.phase is EnginePhase.Ready && !state.isSearching,
            ) {
                Text("分析初始局面（1 秒）")
            }
            OutlinedButton(
                onClick = viewModel::stop,
                enabled = state.isSearching,
            ) {
                Text("停止计算")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { viewModel.analyze(budget = SearchBudget(infinite = true)) },
                enabled = state.phase is EnginePhase.Ready && !state.isSearching,
            ) {
                Text("不限时搜索")
            }
            OutlinedButton(
                onClick = { viewModel.analyze(budget = SearchBudget(depth = 10)) },
                enabled = state.phase is EnginePhase.Ready && !state.isSearching,
            ) {
                Text("固定深度 10")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { viewModel.runPerft(depth = 3) },
                enabled = state.phase is EnginePhase.Ready && !state.isBusy,
            ) {
                Text("perft 3")
            }
            OutlinedButton(
                onClick = { viewModel.initialize(verifyNetwork = true) },
                enabled = state.phase !is EnginePhase.Ready && !state.isBusy,
            ) {
                Text("重校验网络")
            }
            OutlinedButton(
                onClick = viewModel::reinitialize,
                enabled = !state.isBusy,
            ) {
                Text("重新初始化")
            }
        }

        state.lastSearchSummary?.let { SummaryCard(it, state.lastBestMove) }
        state.perftResult?.let { InfoCard("perft", it) }

        EventLogCard(state.events)
    }
}

@Composable
private fun StatusCard(state: EngineVerifyUiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("引擎状态", style = MaterialTheme.typography.titleMedium)

            when (val phase = state.phase) {
                EnginePhase.Idle -> InfoLine("初始化", "进行中")
                EnginePhase.Ready -> InfoLine("初始化", "就绪")
                is EnginePhase.Failed -> InfoLine("初始化", "失败：${phase.reason}", isError = true)
            }

            InfoLine("引擎版本", state.engineInfo.ifEmpty { "未测试" })
            InfoLine("网络", state.networkInfo.ifEmpty { "未测试" })
            InfoLine(
                "网络校验",
                when {
                    state.networkVerifiedNow -> "本次启动已重算 SHA-256，通过"
                    state.phase is EnginePhase.Ready -> "已通过（使用上次结果）"
                    else -> "未测试"
                },
            )
            InfoLine("二进制", state.buildInfo ?: "未测试")
            InfoLine("局面 FEN", state.positionFen.ifEmpty { "未测试" })
            state.optionError?.let { InfoLine("选项错误", it, isError = true) }

            if (state.isBusy) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.height(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.padding(start = 8.dp))
                    Text("处理中…", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun OptionsCard(state: EngineVerifyUiState, viewModel: EngineVerifyViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("可用选项（共 ${state.options.size} 项）", style = MaterialTheme.typography.titleMedium)
            if (state.options.isEmpty()) {
                Text("未测试", style = MaterialTheme.typography.bodySmall)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("线程：", style = MaterialTheme.typography.bodyMedium)
                listOf(1, 2, 4, 8).forEach { count ->
                    FilterChip(
                        selected = state.threads == count,
                        onClick = { viewModel.setThreads(count) },
                        enabled = state.phase is EnginePhase.Ready && !state.isBusy,
                        label = { Text("$count") },
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Hash：", style = MaterialTheme.typography.bodyMedium)
                listOf(16, 64, 256).forEach { mb ->
                    FilterChip(
                        selected = state.hashMb == mb,
                        onClick = { viewModel.setHashMb(mb) },
                        enabled = state.phase is EnginePhase.Ready && !state.isBusy,
                        label = { Text("${mb}M") },
                    )
                }
            }

            HorizontalDivider()
            state.options.forEach { option -> OptionRow(option) }
        }
    }
}

@Composable
private fun OptionRow(option: EngineOption) {
    val range = if (option.min != null && option.max != null) "${option.min}–${option.max}" else null
    Text(
        text = buildString {
            append(option.name)
            append("  [")
            append(option.type)
            append("]")
            option.defaultValue?.let { append("  默认 ").append(it) }
            range?.let { append("  范围 ").append(it) }
        },
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )
}

@Composable
private fun SummaryCard(summary: String, bestMove: String?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("本次结果", style = MaterialTheme.typography.titleMedium)
            Text(summary, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "最佳着：${bestMove ?: "未测试"}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun EventLogCard(events: List<EngineEvent>) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("事件流（${events.size}）", style = MaterialTheme.typography.titleMedium)
            if (events.isEmpty()) {
                Text("未测试", style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            events.takeLast(40).forEach { event ->
                Text(
                    text = describe(event),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun InfoCard(label: String, value: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String, isError: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

private fun describe(event: EngineEvent): String = when (event) {
    is EngineEvent.Started -> "START   #${event.requestId} seq=${event.seq}"
    is EngineEvent.Info ->
        "INFO    #${event.requestId} depth=${event.depth} score=${event.score.display()}" +
            (if (event.bound.isNotEmpty()) " ${event.bound}" else "") +
            " nodes=${event.nodes} time=${event.timeMs}ms pv=${event.pv}"

    is EngineEvent.BestMove ->
        "BESTMOVE #${event.requestId} ${event.bestMove.ifEmpty { "(none)" }}" +
            (if (event.ponder.isNotEmpty()) " ponder=${event.ponder}" else "")

    is EngineEvent.NetworkInfo -> "NETINFO ${event.text}"
    is EngineEvent.Unknown -> "UNKNOWN ${event.raw}"
}