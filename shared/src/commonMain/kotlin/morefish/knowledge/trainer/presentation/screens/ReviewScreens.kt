package morefish.knowledge.trainer.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import morefish.knowledge.trainer.application.port.WallClock
import morefish.knowledge.trainer.domain.model.AttemptRecord
import morefish.knowledge.trainer.domain.model.SessionItem
import morefish.knowledge.trainer.domain.model.Verdict
import morefish.knowledge.trainer.domain.model.WrongQuestionEntry
import morefish.knowledge.trainer.presentation.AppController
import morefish.knowledge.trainer.domain.i18n.localeLabel
import morefish.knowledge.trainer.presentation.HistoryRange
import morefish.knowledge.trainer.presentation.richtext.Markdown
import morefish.knowledge.trainer.presentation.richtext.RichText
import morefish.knowledge.trainer.presentation.theme.Palette
import morefish.knowledge.trainer.presentation.ui.EmptyState
import morefish.knowledge.trainer.presentation.ui.KtChip
import morefish.knowledge.trainer.presentation.ui.Panel
import morefish.knowledge.trainer.presentation.ui.Pill
import morefish.knowledge.trainer.presentation.ui.PrimaryButton
import morefish.knowledge.trainer.presentation.ui.QuestionFigure
import morefish.knowledge.trainer.presentation.ui.ScreenScaffold
import morefish.knowledge.trainer.presentation.ui.SectionTitle
import morefish.knowledge.trainer.presentation.ui.StaggeredAppear
import morefish.knowledge.trainer.presentation.ui.formatDuration
import morefish.knowledge.trainer.presentation.ui.formatRelative
import morefish.knowledge.trainer.presentation.ui.verdictColor
import morefish.knowledge.trainer.presentation.ui.verdictSoft

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryScreen(controller: AppController) {
    val now = WallClock.System.nowMillis()
    val attempts = controller.filteredHistory
    val shown = attempts.take(MAX_RENDERED_ATTEMPTS)

    ScreenScaffold(
        title = "历史记录",
        subtitle = "共 ${controller.history.size} 次提交 · 只追加，不覆盖",
        overline = "可追溯",
        onBack = controller::goHome,
    ) {
        Panel(padding = 16.dp) {
            FilterRow(
                label = "日期",
                values = HistoryRange.entries,
                selected = controller.historyRange,
                labelOf = { it.label },
                onSelect = controller::selectHistoryRange,
            )
            Spacer(Modifier.height(12.dp))
            FilterRow(
                label = "题库",
                values = listOf<String?>(null) + controller.banks.map { it.bankId },
                selected = controller.historyBankFilter,
                labelOf = { id -> if (id == null) "全部" else controller.bankTitle(id) },
                onSelect = controller::selectHistoryBank,
            )
            Spacer(Modifier.height(12.dp))
            FilterRow(
                label = "判定",
                values = listOf<Verdict?>(null) + Verdict.entries,
                selected = controller.historyVerdictFilter,
                labelOf = { it?.label ?: "全部" },
                onSelect = controller::selectHistoryVerdict,
            )
            // 只有真的存在多种语言的记录时才出现：单一语言时这一行是纯噪音
            val locales = controller.historyLocales
            if (locales.size > 1) {
                Spacer(Modifier.height(12.dp))
                FilterRow(
                    label = "语言",
                    values = listOf<String?>(null) + locales,
                    selected = controller.historyLocaleFilter,
                    labelOf = { if (it == null) "全部" else localeLabel(it) },
                    onSelect = controller::selectHistoryLocale,
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            "符合条件 ${attempts.size} 条" +
                if (shown.size < attempts.size) "（为保证流畅只渲染最近 $MAX_RENDERED_ATTEMPTS 条）" else "",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Faint,
        )
        Spacer(Modifier.height(10.dp))

        if (attempts.isEmpty()) {
            EmptyState(
                title = "当前筛选下没有记录",
                hint = "换个日期范围或题库试试；历史只会增加，不会因为筛选而丢失。",
                glyph = "○",
            )
            return@ScreenScaffold
        }

        shown.forEachIndexed { index, attempt ->
            StaggeredAppear(index) { AttemptCard(attempt, controller, now) }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private const val MAX_RENDERED_ATTEMPTS = 200

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> FilterRow(
    label: String,
    values: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = Palette.Faint,
            modifier = Modifier.width(40.dp).padding(top = 8.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            values.forEach { value ->
                KtChip(
                    label = labelOf(value),
                    selected = value == selected,
                    onClick = { onSelect(value) },
                )
            }
        }
    }
}

@Composable
private fun AttemptCard(attempt: AttemptRecord, controller: AppController, now: Long) {
    // 按那次作答的语言显示题面；译文缺失时退回原文（宁可换个语言也不给空白）
    val question = controller.questionFor(attempt)
    val color = verdictColor(attempt.verdict)

    Panel(padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .height(34.dp)
                    .background(color, CircleShape),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    question?.promptMarkdown?.let { Markdown.plainExcerpt(it, 70) }
                        ?: "（题库未安装：${attempt.questionId}）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.Ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${controller.bankTitle(attempt.bankId)} · ${attempt.questionType.label} · " +
                        (attempt.locale.takeIf { it.isNotBlank() }?.let { "${localeLabel(it)} · " } ?: "") +
                        "${attempt.strategyId} · 用时 ${formatDuration(attempt.elapsedMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Pill(attempt.verdict.label, color, verdictSoft(attempt.verdict))
                Spacer(Modifier.height(4.dp))
                Text(
                    formatRelative(now, attempt.submittedAtMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Faint,
                )
            }
        }
        // 复核痕迹紧跟在判定下面：历史页上一条判定如果与当时不一致，
        // 必须能一眼看出"这是后来改的、改之前是什么"，否则读者会以为记录被悄悄改了
        attempt.review?.let { mark ->
            Spacer(Modifier.height(6.dp))
            Text(
                if (mark.changed) {
                    "复核改判：当时判 ${mark.from.label}，你按新答案改成了 ${mark.to.label}"
                } else {
                    "复核：你确认维持当时的判定（${mark.from.label}）"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (mark.changed) Palette.Pending else Palette.Muted,
            )
        }
        if (attempt.selectedOptionIds.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "选择：" + labelsOf(attempt, attempt.selectedOptionIds).joinToString("、"),
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Muted,
            )
        }
        attempt.textAnswer?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(4.dp))
            Text("作答：$it", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
        }
    }
}

@Composable
fun WrongQuestionsScreen(controller: AppController) {
    val entries = controller.wrongEntries

    ScreenScaffold(
        title = "错题本",
        subtitle = "按题目聚合，不是错一次复制一份 · 共 ${entries.size} 题",
        overline = "待复习",
        onBack = controller::goHome,
    ) {
        if (entries.isEmpty()) {
            EmptyState(
                title = "目前没有错题",
                hint = "错题以该题最近一次提交为准：后来答对了就不再出现。做几道题再来看看。",
                glyph = "✓",
            )
            return@ScreenScaffold
        }

        PrimaryButton(
            text = "只练这 ${entries.size} 道错题",
            onClick = { controller.startWrongReview(entries) },
            leading = "→",
        )
        Spacer(Modifier.height(14.dp))

        entries.forEachIndexed { index, entry ->
            StaggeredAppear(index) { WrongCard(entry, controller) }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun WrongCard(entry: WrongQuestionEntry, controller: AppController) {
    val question = controller.questionOf(entry)
    val attempt = entry.latestAttempt
    val expected = question?.correctOptionIds ?: emptySet()
    val missing = expected - attempt.selectedOptionIds
    val extra = attempt.selectedOptionIds - expected
    val missingLabels = labelsOf(attempt, missing)
    val extraLabels = labelsOf(attempt, extra)

    Panel(accent = Palette.Wrong, padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill("错 ${entry.wrongCount} 次", Palette.Wrong, Palette.WrongSoft)
            Spacer(Modifier.width(8.dp))
            Text(
                attempt.questionType.label,
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Faint,
            )
            Spacer(Modifier.weight(1f))
            Text(
                attempt.verdict.label,
                style = MaterialTheme.typography.bodySmall,
                color = verdictColor(attempt.verdict),
            )
        }
        Spacer(Modifier.height(8.dp))
        RichText(
            text = question?.promptMarkdown ?: "（题目缺失：${entry.questionId}）",
            style = MaterialTheme.typography.bodyLarge,
            color = Palette.Ink,
        )
        if (question != null) {
            QuestionFigure(
                question = question,
                bankId = entry.bankId,
                loadMedia = controller::loadMedia,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        if (missing.isNotEmpty() || extra.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (missingLabels.isNotEmpty()) {
                    Pill("漏选 " + missingLabels.joinToString("、"), Palette.Wrong, Palette.WrongSoft)
                }
                if (extraLabels.isNotEmpty()) {
                    Pill("误选 " + extraLabels.joinToString("、"), Palette.Wrong, Palette.WrongSoft)
                }
            }
        }
        question?.explanationMarkdown?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(12.dp))
            SectionTitle("解析")
            Spacer(Modifier.height(6.dp))
            RichText(it, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
        }
    }
}

/**
 * 把 optionId 集合翻译成用户当时看到的 A/B/C。
 * 显示顺序在会话创建时冻结并随历史保存，所以这里永远用历史里的顺序，不查当前题库。
 */
private fun labelsOf(attempt: AttemptRecord, ids: Set<String>): List<String> =
    ids.map { id ->
        val index = attempt.displayOptionIds.indexOf(id)
        if (index < 0) id else SessionItem.optionLabel(index)
    }.sorted()
