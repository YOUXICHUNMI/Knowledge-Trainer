package morefish.knowledge.trainer.presentation.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import morefish.knowledge.trainer.domain.practice.LocalDates
import morefish.knowledge.trainer.domain.practice.StudyCalendar
import morefish.knowledge.trainer.domain.practice.StudyDay
import morefish.knowledge.trainer.presentation.theme.Palette

/**
 * 学习日历热力图。按周分列（周一在上、周日在下），
 * 空格子表示没有提交，不是"未打开应用"。
 *
 * 交互：鼠标悬停某一格时，底部那行从"整体统计"切换成"这一天的明细"，
 * 被指的格子会放大 —— 没有悬停反馈的话，用户根本不知道格子可以读数。
 */
@Composable
fun StudyCalendarPanel(calendar: StudyCalendar, modifier: Modifier = Modifier) {
    var hovered by remember { mutableStateOf<StudyDay?>(null) }

    Panel(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("学习日历", style = MaterialTheme.typography.titleMedium, color = Palette.Ink)
            Spacer(Modifier.width(8.dp))
            Pill(
                text = "连续 ${calendar.currentStreak} 天",
                color = if (calendar.currentStreak > 0) Palette.Accent else Palette.Muted,
                background = if (calendar.currentStreak > 0) Palette.AccentSoft else Palette.NeutralSoft,
            )
        }
        Spacer(Modifier.height(12.dp))

        val days = calendar.days
        if (days.isEmpty()) {
            Text("还没有记录。", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
            return@Panel
        }

        // 首日若不是周一，前面补空格，保证每一列都是同一周。
        val leading = LocalDates.weekdayIndex(days.first().epochDay)
        val cells: List<StudyDay?> = List(leading) { null } + days
        val weeks = cells.chunked(7)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("一", "", "三", "", "五", "", "日").forEach { label ->
                    Box(modifier = Modifier.height(15.dp), contentAlignment = Alignment.Center) {
                        Text(label, style = MaterialTheme.typography.labelSmall, color = Palette.Faint)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                weeks.forEach { week ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        week.forEach { day ->
                            if (day == null) {
                                Spacer(Modifier.size(15.dp))
                            } else {
                                DayCell(
                                    day = day,
                                    level = calendar.levelOf(day),
                                    isToday = day.epochDay == calendar.todayEpochDay,
                                    onHover = { hovered = if (it) day else if (hovered == day) null else hovered },
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        val active = hovered
        if (active != null) {
            Text(
                "${LocalDates.yearMonthDay(active.epochDay)} · ${active.attempts} 次提交" +
                    " · 正确 ${active.correct} · 错误 ${active.wrong}" +
                    if (active.pending > 0) " · 待确认 ${active.pending}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Ink,
                fontWeight = FontWeight.Medium,
            )
        } else {
            Text(
                "${LocalDates.monthDayLabel(days.first().epochDay)} — ${LocalDates.monthDayLabel(days.last().epochDay)}" +
                    " · 活跃 ${calendar.activeDays}/${days.size} 天 · 共 ${calendar.totalAttempts} 次提交",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Muted,
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("少", style = MaterialTheme.typography.labelSmall, color = Palette.Faint)
            Spacer(Modifier.width(6.dp))
            (0..4).forEach { level ->
                Box(
                    modifier = Modifier
                        .size(11.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(levelColor(level)),
                )
                Spacer(Modifier.width(3.dp))
            }
            Text("多", style = MaterialTheme.typography.labelSmall, color = Palette.Faint)
            Spacer(Modifier.width(12.dp))
            Text(
                "每格 = 一次提交，不是打开应用",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Faint,
            )
        }
    }
}

@Composable
private fun DayCell(day: StudyDay, level: Int, isToday: Boolean, onHover: (Boolean) -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    LaunchedEffect(hovered) { onHover(hovered) }

    val scale by animateFloatAsState(
        targetValue = if (hovered) 1.35f else 1f,
        animationSpec = Motion.PressSpring,
        label = "cell-scale",
    )
    val tint = animatedTint(levelColor(level))

    Box(
        modifier = Modifier
            .size(15.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(4.dp))
            .background(tint)
            .hoverable(source)
            .then(
                if (isToday) {
                    Modifier.border(1.5.dp, Palette.Accent, RoundedCornerShape(4.dp))
                } else {
                    Modifier
                },
            ),
    )
}

private fun levelColor(level: Int): Color = when (level) {
    0 -> Color(0xFFEDEBE1)
    1 -> Color(0xFFDCD6F8)
    2 -> Color(0xFFBAB0EF)
    3 -> Color(0xFF8B7BE4)
    else -> Palette.Accent
}
