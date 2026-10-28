package morefish.knowledge.trainer.presentation.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import morefish.knowledge.trainer.application.port.WallClock
import morefish.knowledge.trainer.domain.model.AttemptRecord
import morefish.knowledge.trainer.domain.model.BankProvenance
import morefish.knowledge.trainer.domain.model.PracticeSession
import morefish.knowledge.trainer.domain.model.QuestionBank
import morefish.knowledge.trainer.domain.model.Verdict
import morefish.knowledge.trainer.getPlatform
import morefish.knowledge.trainer.presentation.AppController
import morefish.knowledge.trainer.presentation.SampleBank
import morefish.knowledge.trainer.presentation.theme.HeroGradient
import morefish.knowledge.trainer.presentation.richtext.Markdown
import morefish.knowledge.trainer.presentation.theme.Palette
import morefish.knowledge.trainer.presentation.ui.AnimatedCount
import morefish.knowledge.trainer.presentation.ui.BannerHost
import morefish.knowledge.trainer.presentation.ui.EmptyState
import morefish.knowledge.trainer.presentation.ui.GhostButton
import morefish.knowledge.trainer.presentation.ui.Panel
import morefish.knowledge.trainer.presentation.ui.Pill
import morefish.knowledge.trainer.presentation.ui.PrimaryButton
import morefish.knowledge.trainer.presentation.ui.ProgressLine
import morefish.knowledge.trainer.presentation.ui.RingMeter
import morefish.knowledge.trainer.presentation.ui.ScreenScaffold
import morefish.knowledge.trainer.presentation.ui.SecondaryButton
import morefish.knowledge.trainer.presentation.ui.SectionTitle
import morefish.knowledge.trainer.presentation.ui.StaggeredAppear
import morefish.knowledge.trainer.presentation.ui.StatTile
import morefish.knowledge.trainer.presentation.ui.StudyCalendarPanel
import morefish.knowledge.trainer.presentation.ui.formatRelative
import morefish.knowledge.trainer.presentation.ui.pressScale
import morefish.knowledge.trainer.presentation.ui.verdictColor
import morefish.knowledge.trainer.presentation.ui.verdictSoft

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(controller: AppController) {
    val now = WallClock.System.nowMillis()
    val attempts = controller.history
    val graded = attempts.count { it.verdict != Verdict.ABANDONED }
    val correct = attempts.count { it.verdict == Verdict.CORRECT }
    val accuracy = if (graded == 0) null else correct.toFloat() / graded
    val questionTotal = controller.banks.sumOf { it.questionCount }
    val paused = controller.resumable?.takeIf { it.current != null }

    ScreenScaffold(
        title = "Knowledge Trainer",
        subtitle = "离线优先的知识训练器 · ${getPlatform().name}",
        overline = "学习台",
    ) {
        BannerHost(controller.banner, controller::dismissBanner)

        controller.storageWarning?.let { warning ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Pill("提示", Palette.Pending, Palette.PendingSoft)
                Spacer(Modifier.width(8.dp))
                Text(warning, style = MaterialTheme.typography.bodySmall, color = Palette.Pending)
            }
        }

        StaggeredAppear(0) {
            HeroCard(
                streak = controller.historyView.calendar.currentStreak,
                accuracy = accuracy,
                attemptCount = attempts.size,
                hasBank = controller.banks.isNotEmpty(),
                paused = paused,
                onContinue = controller::resumeSession,
                onStart = {
                    val bank = controller.banks.firstOrNull()
                    if (bank == null) {
                        controller.openImport(SampleBank.json)
                    } else {
                        controller.startPractice(bank, questionCount = 10)
                    }
                },
                onImport = { controller.openImport(SampleBank.json) },
            )
        }

        if (paused != null) {
            Spacer(Modifier.height(14.dp))
            StaggeredAppear(1) { ResumeCard(paused, now, controller) }
        }

        Spacer(Modifier.height(18.dp))
        StaggeredAppear(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    label = "题库",
                    value = controller.banks.size,
                    modifier = Modifier.weight(1f),
                    hint = "本地",
                )
                StatTile(
                    label = "题目",
                    value = questionTotal,
                    modifier = Modifier.weight(1f),
                    hint = "可练",
                )
                StatTile(
                    label = "提交",
                    value = attempts.size,
                    modifier = Modifier.weight(1f),
                    hint = "累计",
                )
                StatTile(
                    label = "错题",
                    value = controller.historyView.wrongEntries.size,
                    modifier = Modifier.weight(1f),
                    hint = "待复习",
                    accent = if (controller.historyView.wrongEntries.isEmpty()) Palette.Ink else Palette.Wrong,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        StaggeredAppear(3) {
            // 入口在窄屏会挤成两行，用 FlowRow 让它自己换行而不是被裁掉
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SecondaryButton("导入题库", onClick = { controller.openImport(SampleBank.json) }, leading = "＋")
                // 只在真的有待复核记录时出现：一个永远显示"0"的入口只是噪声，
                // 而"有几条"本身就是它该不该被看见的信息
                if (controller.reviewPending > 0) {
                    SecondaryButton(
                        "复核 ${controller.reviewPending}",
                        onClick = controller::openReview,
                        leading = "↻",
                    )
                }
                SecondaryButton("错题本", onClick = controller::openWrong, leading = "✕")
                SecondaryButton("历史记录", onClick = controller::openHistory, leading = "≡")
                SecondaryButton(
                    if (controller.notes.isEmpty()) "笔记" else "笔记 ${controller.notes.size}",
                    onClick = { controller.openNotes() },
                    leading = "✎",
                )
                SecondaryButton("工具", onClick = { controller.openToolbox() }, leading = "∑")
                SecondaryButton(
                    if (controller.extensions.isEmpty()) "扩展包" else "扩展包 ${controller.extensions.size}",
                    onClick = controller::openExtensions,
                    leading = "⊞",
                )
                SecondaryButton(
                    if (controller.graph.edges.isEmpty()) "图谱" else "图谱 ${controller.graph.edges.size}",
                    onClick = { controller.openGraph() },
                    leading = "⇄",
                )
                SecondaryButton(
                    if (controller.ai.records.isEmpty()) "AI 讲解" else "AI 讲解 ${controller.ai.records.size}",
                    onClick = controller::openAi,
                    leading = "✦",
                )
                // 只在数据真的落在磁盘上的平台出现：Web / iOS 关掉应用就没了，
                // 那里给一个"备份"按钮等于承诺一件做不到的事。
                if (controller.backupSupported) {
                    SecondaryButton("备份", onClick = controller::openBackup, leading = "⇩")
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        StaggeredAppear(4) { StudyCalendarPanel(controller.historyView.calendar) }

        Spacer(Modifier.height(24.dp))
        StaggeredAppear(5) {
            SectionTitle("我的题库", if (controller.banks.isEmpty()) null else "${controller.banks.size} 个")
        }
        Spacer(Modifier.height(10.dp))

        if (controller.banks.isEmpty()) {
            EmptyState(
                title = "还没有题库",
                hint = "导入 .qbank 包或粘贴 JSON 就能开始。也可以先载入内置示例，完整走一遍做题闭环。",
                glyph = "＋",
                action = {
                    PrimaryButton("载入示例题库", onClick = { controller.openImport(SampleBank.json) })
                },
            )
        } else {
            controller.banks.forEachIndexed { index, bank ->
                StaggeredAppear(6 + index) {
                    BankCard(
                        bank = bank,
                        controller = controller,
                        favoriteCount = controller.favorites.count { it.bankId == bank.bankId },
                        noteCount = controller.noteCountFor(bank.bankId),
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
        }

        Spacer(Modifier.height(24.dp))
        StaggeredAppear(7 + controller.banks.size) {
            SectionTitle(
                "最近提交",
                if (attempts.isEmpty()) "暂无" else "最近 ${minOf(5, attempts.size)} 条",
            )
        }
        Spacer(Modifier.height(10.dp))

        if (attempts.isEmpty()) {
            Panel(background = Palette.PanelMuted) {
                Text(
                    "完成一次练习后，这里会按时间倒序列出每次提交的判定结果。历史记录只追加、不覆盖。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.Muted,
                )
            }
        } else {
            Panel(padding = 8.dp) {
                val recent = attempts.sortedByDescending { it.submittedAtMillis }.take(5)
                recent.forEachIndexed { index, attempt ->
                    AttemptRow(attempt, now, controller)
                    if (index != recent.lastIndex) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp)
                                .height(1.dp)
                                .background(Palette.PanelBorder),
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Hero

/**
 * 首页的"仪表盘"：连续天数 + 正确率 + 一个明确的下一步。
 *
 * 深色渐变卡是整个应用唯一的高饱和面，作用是把视线从纸白背景里拉过来 ——
 * 只有一个，所以一眼就知道该看哪。
 */
@Composable
private fun HeroCard(
    streak: Int,
    accuracy: Float?,
    attemptCount: Int,
    hasBank: Boolean,
    paused: PracticeSession?,
    onContinue: () -> Unit,
    onStart: () -> Unit,
    onImport: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(12.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(HeroGradient),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = Color.White.copy(alpha = 0.06f),
                radius = size.minDimension * 0.55f,
                center = Offset(size.width * 0.92f, size.height * 0.12f),
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.05f),
                radius = size.minDimension * 0.35f,
                center = Offset(size.width * 0.75f, size.height * 1.05f),
            )
        }

        Column(modifier = Modifier.padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "连续学习",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                    Row(verticalAlignment = Alignment.Bottom) {
                        AnimatedCount(
                            value = streak,
                            style = MaterialTheme.typography.displayMedium,
                            color = Color.White,
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            "天",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (attemptCount == 0) {
                            "还没有提交记录，做完第一题就会亮起来"
                        } else {
                            "共 $attemptCount 次提交 · 正确率 " +
                                (if (accuracy == null) "—" else "${(accuracy * 100).toInt()}%")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.78f),
                    )
                }
                RingMeter(
                    progress = accuracy,
                    size = 86.dp,
                    stroke = 8.dp,
                    color = Color(0xFFCFC6FF),
                    track = Color.White.copy(alpha = 0.16f),
                    contentColor = Color.White,
                    caption = "正确率",
                )
            }

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HeroAction(
                    text = when {
                        paused != null -> "继续上次练习"
                        hasBank -> "练 10 题"
                        else -> "导入题库"
                    },
                    primary = true,
                    onClick = when {
                        paused != null -> onContinue
                        hasBank -> onStart
                        else -> onImport
                    },
                )
                if (hasBank) {
                    HeroAction(text = "导入题库", primary = false, onClick = onImport)
                }
            }
        }
    }
}

@Composable
private fun HeroAction(text: String, primary: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(11.dp))
            .background(if (primary) Color.White else Color.White.copy(alpha = 0.14f))
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pressScale(source)
            .padding(horizontal = 18.dp, vertical = 11.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (primary) Palette.AccentDeep else Color.White,
        )
    }
}

// ------------------------------------------------------------------ 会话卡片

/** 未完成的会话不是"丢了"，而是停在这里等用户决定继续还是放弃。 */
@Composable
private fun ResumeCard(paused: PracticeSession, now: Long, controller: AppController) {
    Panel(accent = Palette.Accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill("未完成", Palette.Accent, Palette.AccentSoft)
            Spacer(Modifier.width(8.dp))
            Text(
                controller.bankTitle(paused.bankId),
                style = MaterialTheme.typography.titleSmall,
                color = Palette.Ink,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${paused.answeredCount} / ${paused.items.size}",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Muted,
            )
        }
        Spacer(Modifier.height(10.dp))
        ProgressLine(
            progress = if (paused.items.isEmpty()) 0f else paused.answeredCount.toFloat() / paused.items.size,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "${paused.planLabel.ifBlank { "自定义" }} · 停在第 ${paused.firstUnansweredIndex + 1} 题 · " +
                formatRelative(now, paused.updatedAtMillis),
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Muted,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("继续练习", onClick = controller::resumeSession, leading = "→")
            GhostButton("放弃这次", onClick = controller::discardResumable)
        }
    }
}

// ------------------------------------------------------------------ 题库卡片

private val avatarColors = listOf(
    Color(0xFF4A3ECB) to Color(0xFF8B7BE4),
    Color(0xFF0B7B58) to Color(0xFF4CB79A),
    Color(0xFFB4581F) to Color(0xFFE0A063),
    Color(0xFF8B2C6B) to Color(0xFFC973AC),
)

@Composable
private fun BankCard(bank: QuestionBank, controller: AppController, favoriteCount: Int, noteCount: Int) {
    val seed = bank.bankId.hashCode().let { if (it == Int.MIN_VALUE) 0 else it } and 0x7FFFFFFF
    val pair = avatarColors[seed % avatarColors.size]

    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(listOf(pair.first, pair.second))),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    bank.title.take(1),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    bank.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Palette.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                val breakdown = bank.typeBreakdown().entries.joinToString(" · ") { "${it.key.label} ${it.value}" }
                Text(breakdown, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                val caption = provenanceCaption(bank.provenance)
                if (caption != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        caption,
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.Faint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // 只有被更新过的题库才显示版本号：绝大多数库一辈子停在第 1 版，
            // 给它们挂一个"第 1 版"只是噪声。而一旦应用过补丁，这个数字就是
            // 用户核对"我收到的是哪一版、这个补丁要不要再装一次"的唯一依据。
            if (bank.contentRevision > 1) {
                Pill("第 ${bank.contentRevision} 版", Palette.Muted, Palette.PanelMuted)
                Spacer(Modifier.width(6.dp))
            }
            Pill("${bank.questionCount} 题", Palette.Accent, Palette.AccentSoft)
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton("练 10 题", onClick = { controller.startPractice(bank, questionCount = 10) })
            SecondaryButton("练习设置", onClick = { controller.openSetup(bank.bankId) })
            GhostButton(
                "全部 ${bank.questionCount} 题",
                onClick = { controller.startPractice(bank, questionCount = null) },
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            GhostButton("＋ 新建题目", onClick = { controller.openQuestionEditor(bank.bankId) })
            GhostButton(
                if (noteCount == 0) "写笔记" else "笔记 $noteCount",
                onClick = { controller.openNotes(bank.bankId) },
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "手工添加的题与导入题同库练习，标记为 manual",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Faint,
        )

        if (favoriteCount > 0) {
            Spacer(Modifier.height(2.dp))
            Text(
                "本地已收藏 $favoriteCount 题，可在练习设置里只练收藏",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Faint,
            )
        }
    }
}

// ------------------------------------------------------------------ 最近提交

@Composable
private fun AttemptRow(attempt: AttemptRecord, now: Long, controller: AppController) {
    val question = controller.banks.firstOrNull { it.bankId == attempt.bankId }
        ?.questions?.firstOrNull { it.questionId == attempt.questionId }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(verdictColor(attempt.verdict), CircleShape),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            question?.promptMarkdown?.let { Markdown.plainExcerpt(it, 40) } ?: attempt.questionId,
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.Ink,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(10.dp))
        // 复核留下的痕迹。改判与"看过但维持"必须能分开 ——
        // 否则历史里会出现一条判定与当时不一致、却看不出为什么的怪记录
        attempt.review?.let { mark ->
            if (mark.changed) {
                Pill("复核改判", Palette.Pending, Palette.PendingSoft)
            } else {
                Pill("复核保留", Palette.Muted, Palette.NeutralSoft)
            }
            Spacer(Modifier.width(8.dp))
        }
        Pill(
            attempt.verdict.label,
            verdictColor(attempt.verdict),
            verdictSoft(attempt.verdict),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            formatRelative(now, attempt.submittedAtMillis),
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Faint,
        )
    }
}

/**
 * 题库卡第三行：谁做的、有几处出处。
 *
 * 以前这个信息完全没在界面上出现过 —— 用户只能看到一个标题和一串题型分布，
 * 无从知道"这是谁整理的、里面引了什么书"。没有内容就返回 null，不写"未知作者"：
 * 旧包确实没声明作者，那就不显示，而不是把"没说"渲染成一个体面的默认值。
 */
private fun provenanceCaption(provenance: BankProvenance): String? {
    val parts = buildList {
        if (provenance.authorName.isNotBlank() && provenance.authorName != "未知作者") {
            add(provenance.authorName)
        }
        if (provenance.sources.isNotEmpty()) add("出处 ${provenance.sources.size} 处")
        if (provenance.license.isNotBlank()) add(provenance.license)
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
