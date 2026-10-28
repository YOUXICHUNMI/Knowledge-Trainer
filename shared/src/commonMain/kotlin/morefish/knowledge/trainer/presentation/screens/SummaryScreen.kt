package morefish.knowledge.trainer.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import morefish.knowledge.trainer.presentation.AppController
import morefish.knowledge.trainer.presentation.theme.Palette
import morefish.knowledge.trainer.presentation.ui.AnimatedCount
import morefish.knowledge.trainer.presentation.ui.BarSegment
import morefish.knowledge.trainer.presentation.ui.GhostButton
import morefish.knowledge.trainer.presentation.ui.Panel
import morefish.knowledge.trainer.presentation.ui.Pill
import morefish.knowledge.trainer.presentation.ui.PrimaryButton
import morefish.knowledge.trainer.presentation.ui.RingMeter
import morefish.knowledge.trainer.presentation.ui.ScreenScaffold
import morefish.knowledge.trainer.presentation.ui.SecondaryButton
import morefish.knowledge.trainer.presentation.ui.SectionTitle
import morefish.knowledge.trainer.presentation.ui.SegmentedBar
import morefish.knowledge.trainer.presentation.ui.StaggeredAppear
import morefish.knowledge.trainer.presentation.ui.formatDuration

@Composable
fun SummaryScreen(controller: AppController) {
    val summary = controller.practice.summary

    ScreenScaffold(
        title = "练习总结",
        subtitle = "统计口径写在这里，避免模糊的「正确率」",
        overline = "本次结果",
        onBack = controller::goHome,
    ) {
        if (summary == null) {
            Text("还没有可总结的会话。", color = Palette.Muted)
            return@ScreenScaffold
        }

        StaggeredAppear(0) {
            Panel(padding = 22.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RingMeter(
                        progress = summary.accuracy?.toFloat(),
                        size = 124.dp,
                        stroke = 11.dp,
                        color = if ((summary.accuracy ?: 1.0) >= 0.6) Palette.Correct else Palette.Wrong,
                        caption = "正确率",
                    )
                    Spacer(Modifier.width(22.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "正确 / 已判定",
                            style = MaterialTheme.typography.labelSmall,
                            color = Palette.Faint,
                        )
                        Row(verticalAlignment = Alignment.Bottom) {
                            AnimatedCount(
                                value = summary.correct,
                                style = MaterialTheme.typography.displaySmall,
                                color = Palette.Correct,
                            )
                            Text(
                                " ÷ ${summary.graded}",
                                style = MaterialTheme.typography.titleMedium,
                                color = Palette.Muted,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "待确认 ${summary.pending} 题与放弃 ${summary.abandoned} 题不计入分母。" +
                                "部分正确按 0.5 计入的加权值仅供辅助参考。",
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.Muted,
                        )
                        summary.weightedAccuracy?.let { weighted ->
                            Spacer(Modifier.height(6.dp))
                            Pill(
                                "加权正确率 ${(weighted * 100).toInt()}%",
                                Palette.Accent,
                                Palette.AccentSoft,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))
                SegmentedBar(
                    segments = listOf(
                        BarSegment(summary.correct.toFloat(), Palette.Correct),
                        BarSegment(summary.partiallyCorrect.toFloat(), Palette.Pending),
                        BarSegment(summary.incorrect.toFloat(), Palette.Wrong),
                        BarSegment(summary.pending.toFloat(), Palette.PendingSoft),
                        BarSegment(summary.abandoned.toFloat(), Palette.NeutralSoft),
                    ),
                    height = 12.dp,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Legend("正确", summary.correct, Palette.Correct)
                    Legend("部分正确", summary.partiallyCorrect, Palette.Pending)
                    Legend("错误", summary.incorrect, Palette.Wrong)
                    Legend("待确认", summary.pending, Palette.Pending.copy(alpha = 0.45f))
                    Legend("放弃", summary.abandoned, Palette.Faint)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        StaggeredAppear(1) {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCell("题目总数", summary.totalQuestions, Modifier.weight(1f))
                    MetricCell("已提交", summary.answered, Modifier.weight(1f))
                    MetricCell(
                        "用时",
                        null,
                        Modifier.weight(1f),
                        text = formatDuration(summary.totalElapsedMillis),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCell("正确", summary.correct, Modifier.weight(1f), color = Palette.Correct)
                    MetricCell("错误", summary.incorrect, Modifier.weight(1f), color = Palette.Wrong)
                    MetricCell("放弃", summary.abandoned, Modifier.weight(1f), color = Palette.Muted)
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        StaggeredAppear(2) {
            Column {
                SectionTitle("接下来")
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("返回首页", onClick = controller::goHome)
                    SecondaryButton("查看错题本", onClick = controller::openWrong)
                    GhostButton("查看历史", onClick = controller::openHistory)
                }
            }
        }
    }
}

@Composable
private fun Legend(label: String, count: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(
            "$label $count",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Muted,
        )
    }
}

@Composable
private fun MetricCell(
    label: String,
    value: Int?,
    modifier: Modifier = Modifier,
    text: String? = null,
    color: Color = Palette.Ink,
) {
    Column(
        modifier = modifier
            .background(Palette.PanelMuted, androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Palette.Faint)
        Spacer(Modifier.height(4.dp))
        if (value != null) {
            AnimatedCount(
                value = value,
                style = MaterialTheme.typography.titleLarge,
                color = color,
                suffix = " 题",
            )
        } else {
            Text(text ?: "—", style = MaterialTheme.typography.titleLarge, color = color)
        }
    }
}
