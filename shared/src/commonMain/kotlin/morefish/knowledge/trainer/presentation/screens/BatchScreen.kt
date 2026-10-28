package morefish.knowledge.trainer.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import morefish.knowledge.trainer.domain.ai.AiBatchItem
import morefish.knowledge.trainer.domain.ai.AiBatchItemState
import morefish.knowledge.trainer.domain.ai.AiBatchJob
import morefish.knowledge.trainer.domain.ai.AiBatchState
import morefish.knowledge.trainer.domain.ai.formatMicros
import morefish.knowledge.trainer.application.port.WallClock
import morefish.knowledge.trainer.presentation.AppController
import morefish.knowledge.trainer.presentation.theme.Palette
import morefish.knowledge.trainer.presentation.ui.BannerHost
import morefish.knowledge.trainer.presentation.ui.Divider
import morefish.knowledge.trainer.presentation.ui.GhostButton
import morefish.knowledge.trainer.presentation.ui.KtChip
import morefish.knowledge.trainer.presentation.ui.Panel
import morefish.knowledge.trainer.presentation.ui.Pill
import morefish.knowledge.trainer.presentation.ui.PrimaryButton
import morefish.knowledge.trainer.presentation.ui.ProgressLine
import morefish.knowledge.trainer.presentation.ui.ScreenScaffold
import morefish.knowledge.trainer.presentation.ui.SecondaryButton
import morefish.knowledge.trainer.presentation.ui.SectionTitle
import morefish.knowledge.trainer.presentation.ui.StatTile
import morefish.knowledge.trainer.presentation.ui.formatRelative

/**
 * 批量生成队列。
 *
 * 这一页要回答的问题只有三个，顺序不能反：
 * **这次要发几次请求、现在跑到哪、停下来还能不能接着跑。**
 *
 * 所以"排进队列"和"开始"是两个按钮 —— 排完先让你看清数量，点开始才真的花钱。
 * 合成一个"一键生成全部"，用户就没有机会在扣费前看一眼总数。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BatchScreen(controller: AppController) {
    ScreenScaffold(
        title = "批量生成",
        subtitle = "一次只发一个请求；随时可暂停，停下来不丢进度",
        overline = "队列",
        onBack = controller::closeBatch,
    ) {
        BannerHost(controller.banner, controller::dismissBanner)

        if (!controller.ai.available) {
            Panel(accent = Palette.Neutral) {
                SectionTitle("本平台不支持", null)
                Spacer(Modifier.height(8.dp))
                Text(
                    "联网 AI 目前只在桌面端接入。这里不会排任何队列，也不会偷偷发请求。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Muted,
                )
            }
            return@ScreenScaffold
        }

        NewJobPanel(controller)
        Spacer(Modifier.height(18.dp))
        controller.aiBatchSelected?.let { job ->
            JobPanel(controller, job)
            Spacer(Modifier.height(18.dp))
        }
        JobHistoryPanel(controller)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewJobPanel(controller: AppController) {
    val configured = controller.ai.config.configured && controller.ai.config.uploadConsent
    val banks = controller.banks
    var bankId by remember(banks) { mutableStateOf(banks.firstOrNull()?.bankId.orEmpty()) }
    var locale by remember(bankId) {
        mutableStateOf(banks.firstOrNull { it.bankId == bankId }?.sourceLocale.orEmpty())
    }
    // 默认 50 而不是"不限"：第一版默认不限，等于把"一次烧掉整个库"设成默认行为
    var maxCalls by remember { mutableStateOf("50") }
    val bank = banks.firstOrNull { it.bankId == bankId }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Palette.Accent,
        unfocusedBorderColor = Palette.PanelBorderStrong,
        focusedContainerColor = Palette.Panel,
        unfocusedContainerColor = Palette.Panel,
        cursorColor = Palette.Accent,
        focusedLabelColor = Palette.Accent,
        unfocusedLabelColor = Palette.Muted,
    )

    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("排新队列", "选范围 → 看数量 → 再开始")
            Spacer(Modifier.weight(1f))
            when {
                !configured && controller.ai.config.configured ->
                    Pill("未同意上传", Palette.Pending, Palette.PendingSoft)
                !controller.ai.config.configured -> Pill("未配置", Palette.Pending, Palette.PendingSoft)
                else -> Pill("可排", Palette.Correct, Palette.CorrectSoft)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (configured) {
                "只处理这个库里还没有讲解的题。已经有的会被排除，不重复请求。"
            } else {
                "还没有完成配置，排不了队列。先去「AI 讲解」把地址、模型和上传同意处理好。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (configured) Palette.Muted else Palette.Pending,
        )

        if (banks.isEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("还没有题库。先导入一个题库再回来。", style = MaterialTheme.typography.bodySmall, color = Palette.Faint)
            return@Panel
        }

        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            banks.forEach { candidate ->
                KtChip(
                    label = candidate.title,
                    selected = candidate.bankId == bankId,
                    onClick = {
                        bankId = candidate.bankId
                        locale = candidate.sourceLocale
                    },
                )
            }
        }

        if (bank != null && bank.availableLocales.size > 1) {
            Spacer(Modifier.height(10.dp))
            Text("按哪种语言生成", style = MaterialTheme.typography.labelSmall, color = Palette.Faint)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                bank.availableLocales.forEach { tag ->
                    KtChip(
                        label = tag,
                        selected = tag == locale,
                        onClick = { locale = tag },
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = maxCalls,
                onValueChange = { text -> maxCalls = text.filter { it.isDigit() }.take(5) },
                label = { Text("本次最多问几道（0 = 不限）") },
                singleLine = true,
                modifier = Modifier.width(260.dp),
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors,
            )
            Text(
                "只影响这一次任务；默认 50 次，每日次数 / token / 费用上限仍然生效",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Faint,
            )
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(
                text = "排进队列",
                onClick = { controller.createBatch(bankId, locale, maxCalls.toIntOrNull() ?: 0) },
                enabled = configured && bank != null,
            )
            GhostButton("返回 AI 讲解", onClick = controller::closeBatch)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JobPanel(controller: AppController, job: AiBatchJob) {
    val running = controller.aiBatchRunning && job.state == AiBatchState.RUNNING
    var showFailures by remember(job.jobId) { mutableStateOf(false) }

    Panel(accent = when (job.state) {
        AiBatchState.RUNNING -> Palette.Accent
        AiBatchState.PAUSED -> Palette.Pending
        AiBatchState.COMPLETED -> Palette.Correct
        else -> Palette.Neutral
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(job.bankTitle.ifBlank { "任务" }, "${job.locale} · ${job.model.ifBlank { "未指定模型" }}")
            Spacer(Modifier.weight(1f))
            Pill(job.state.label, stateTint(job.state), stateSoft(job.state))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            job.note.ifBlank { job.summaryText() },
            style = MaterialTheme.typography.bodySmall,
            color = if (job.state == AiBatchState.PAUSED) Palette.Pending else Palette.Muted,
        )

        Spacer(Modifier.height(14.dp))
        ProgressLine(progress = job.progress)
        Spacer(Modifier.height(6.dp))
        Text(
            "${job.settledCount} / ${job.total}　${(job.progress * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = Palette.Faint,
        )

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(label = "已生成", value = job.doneCount, modifier = Modifier.weight(1f), accent = Palette.Correct)
            StatTile(label = "跳过", value = job.skippedCount, modifier = Modifier.weight(1f))
            StatTile(
                label = "失败",
                value = job.failedCount,
                modifier = Modifier.weight(1f),
                accent = if (job.failedCount > 0) Palette.Wrong else Palette.Ink,
            )
            StatTile(
                label = "待处理",
                value = job.queuedCount,
                modifier = Modifier.weight(1f),
                // 跑完之后"待处理 0"不该还亮着靛蓝，那会让完成的任务看起来还有活没干
                accent = if (job.queuedCount > 0) Palette.Accent else Palette.Faint,
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(
            buildString {
                append("本次已发出 ").append(job.callsUsed).append(" 次请求")
                if (job.isOpen) job.callsLeft?.let { append("，还剩 ").append(it).append(" 次额度") }
                if (job.spentMicros > 0) append("；估算 ").append(formatMicros(job.spentMicros))
                if (job.spentTokens > 0) append(" · ").append(job.spentTokens).append(" tokens")
                if (job.failedCount > 0) append("。失败的那几道不会自动重试 —— 要重来请再排一次队列")
            },
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Muted,
        )

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                running -> {
                    SecondaryButton("暂停", onClick = controller::pauseBatch, leading = "❙❙")
                    GhostButton("取消", onClick = controller::cancelBatch, color = Palette.Wrong)
                    Text(
                        "当前这道题返回后才会停（打断请求也会扣费）",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.Faint,
                    )
                }

                job.canResume -> {
                    PrimaryButton(
                        text = if (job.state == AiBatchState.QUEUED) "开始" else "继续",
                        onClick = { controller.startBatch(job.jobId) },
                    )
                    if (job.isOpen) {
                        GhostButton("取消", onClick = controller::cancelBatch, color = Palette.Wrong)
                    }
                }

                job.hitCallLimit && job.queuedCount > 0 -> {
                    Text(
                        "已到本次上限 ${job.maxCalls} 次。再排一个新队列可以继续处理剩下的 ${job.queuedCount} 道。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.Pending,
                    )
                }

                else -> Text(
                    if (job.state == AiBatchState.CANCELLED) "任务已取消，未处理的项不会再发请求" else "这个任务已经跑完了",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Faint,
                )
            }
            Spacer(Modifier.weight(1f))
            if (!running) {
                GhostButton("删除任务", onClick = { controller.deleteBatch(job.jobId) })
            }
        }

        if (job.failedCount > 0) {
            Spacer(Modifier.height(10.dp))
            Divider()
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "失败项（${job.failedCount}）",
                    style = MaterialTheme.typography.labelMedium,
                    color = Palette.Wrong,
                )
                Spacer(Modifier.weight(1f))
                GhostButton(if (showFailures) "收起" else "展开", onClick = { showFailures = !showFailures })
            }
            if (showFailures) {
                Spacer(Modifier.height(8.dp))
                job.items.filter { it.state == AiBatchItemState.FAILED }.take(8).forEach { item ->
                    FailureRow(item)
                }
                val hidden = job.failedCount - 8
                if (hidden > 0) {
                    Text(
                        "还有 $hidden 条没有列出来",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.Faint,
                    )
                }
            }
        }
    }
}

@Composable
private fun FailureRow(item: AiBatchItem) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            item.questionId.take(12),
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Faint,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            item.note.ifBlank { "失败（没有留下原因）" },
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Wrong,
        )
    }
}

@Composable
private fun JobHistoryPanel(controller: AppController) {
    val jobs = controller.aiJobs
    if (jobs.size <= 1) return
    val now = WallClock.System.nowMillis()
    Panel {
        SectionTitle("任务记录", "${jobs.size} 个")
        Spacer(Modifier.height(10.dp))
        jobs.forEachIndexed { index, job ->
            if (index > 0) {
                Spacer(Modifier.height(8.dp))
                Divider()
                Spacer(Modifier.height(8.dp))
            }
            val selected = controller.aiBatchSelected?.jobId == job.jobId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) Palette.AccentSoft else Palette.Panel)
                    .clickable { controller.selectBatch(job.jobId) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Pill(job.state.label, stateTint(job.state), stateSoft(job.state))
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "${job.bankTitle.ifBlank { job.bankId.take(8) }} · ${job.locale}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.Ink,
                    )
                    Text(
                        "${job.doneCount}/${job.total} 已生成" +
                            if (job.failedCount > 0) " · ${job.failedCount} 失败" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.Faint,
                    )
                }
                Text(
                    formatRelative(now, job.createdAtMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Faint,
                )
            }
        }
    }
}

private fun stateTint(state: AiBatchState) = when (state) {
    AiBatchState.RUNNING -> Palette.Accent
    AiBatchState.PAUSED -> Palette.Pending
    AiBatchState.COMPLETED -> Palette.Correct
    AiBatchState.CANCELLED -> Palette.Wrong
    else -> Palette.Neutral
}

private fun stateSoft(state: AiBatchState) = when (state) {
    AiBatchState.RUNNING -> Palette.AccentSoft
    AiBatchState.PAUSED -> Palette.PendingSoft
    AiBatchState.COMPLETED -> Palette.CorrectSoft
    AiBatchState.CANCELLED -> Palette.WrongSoft
    else -> Palette.NeutralSoft
}
