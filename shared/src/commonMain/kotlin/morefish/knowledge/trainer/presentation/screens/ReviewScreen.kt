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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import morefish.knowledge.trainer.application.port.WallClock
import morefish.knowledge.trainer.domain.practice.ReviewCandidate
import morefish.knowledge.trainer.domain.practice.ReviewPlan
import morefish.knowledge.trainer.presentation.AppController
import morefish.knowledge.trainer.presentation.theme.Palette
import morefish.knowledge.trainer.presentation.ui.BannerHost
import morefish.knowledge.trainer.presentation.ui.Divider
import morefish.knowledge.trainer.presentation.ui.EmptyState
import morefish.knowledge.trainer.presentation.ui.GhostButton
import morefish.knowledge.trainer.presentation.ui.Panel
import morefish.knowledge.trainer.presentation.ui.Pill
import morefish.knowledge.trainer.presentation.ui.ScreenScaffold
import morefish.knowledge.trainer.presentation.ui.SecondaryButton
import morefish.knowledge.trainer.presentation.ui.SectionTitle
import morefish.knowledge.trainer.presentation.ui.formatRelative
import morefish.knowledge.trainer.presentation.ui.verdictColor
import morefish.knowledge.trainer.presentation.ui.verdictSoft

/**
 * 历史复核页。
 *
 * 这一页存在的理由只有一句话：**答案改了，但软件没有资格替你改写过去。**
 *
 * 题库更新只改题库当前的内容，历史那一行永远是"当时按那时的标准判成了什么"。
 * 作者改答案不等于用户当时答错了 —— 所以这里不做事后重判，只把差异摆出来，
 * 一条一条问。
 *
 * 两个刻意的取舍：
 *
 * 1. **没有主按钮。** 整页一个 [morefish.knowledge.trainer.presentation.ui.PrimaryButton]
 *    都没有，两个选项在视觉上完全等重。这里不存在"推荐的答案"：
 *    「按新答案算」会动统计，「维持当时」会让你以后再做这道题时标准不一致，
 *    哪一个更对取决于用户怎么看那次作答，界面不该抢答。
 * 2. **批量也走弱按钮。** 「全部按新答案」是一个一下改几十条数字的动作，
 *    做得太顺手反而是问题。
 */
@Composable
fun ReviewScreen(controller: AppController) {
    val now = WallClock.System.nowMillis()
    ScreenScaffold(
        title = "历史复核",
        subtitle = "答案改了之后，旧记录怎么算由你决定",
        overline = "复核",
        onBack = controller::closeReview,
    ) {
        BannerHost(controller.banner, controller::dismissBanner)

        val plan = controller.review.plan
        when {
            plan == null && controller.review.busy -> BusyPanel()
            plan == null -> EmptyState(
                title = "还没有扫描",
                hint = "复核要逐题重算一遍历史，所以不在进首页时做。点一下右上角刷新或重新进入本页即可。",
                glyph = "↻",
            )

            plan.isEmpty -> PlanEmpty(plan)

            else -> {
                SummaryPanel(controller, plan)
                Spacer(Modifier.height(14.dp))
                SectionTitle("逐条确认", "${plan.count} 条")
                Spacer(Modifier.height(8.dp))
                Panel(padding = 0.dp) {
                    plan.candidates.forEachIndexed { index, candidate ->
                        if (index > 0) Divider()
                        CandidateRow(controller, candidate, now)
                    }
                }
                if (plan.unreviewable > 0) {
                    Spacer(Modifier.height(12.dp))
                    UnreviewableNote(plan.unreviewable)
                }
                Spacer(Modifier.height(14.dp))
                Footnote()
            }
        }
    }
}

// ------------------------------------------------------------------ 顶部

@Composable
private fun BusyPanel() {
    Panel(background = Palette.PanelMuted) {
        Text(
            "正在按现在的题目内容重算历史…",
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.Muted,
        )
    }
}

@Composable
private fun PlanEmpty(plan: ReviewPlan) {
    EmptyState(
        title = "没有需要复核的记录",
        hint = "历史里每一条记录的判定，都和现在的题目内容一致 —— 说明你练过的题没有被改过答案，" +
            "或者改过之后你已经表态过了。",
        glyph = "✓",
    )
    if (plan.unreviewable > 0) {
        Spacer(Modifier.height(12.dp))
        UnreviewableNote(plan.unreviewable)
    }
}

@Composable
private fun SummaryPanel(controller: AppController, plan: ReviewPlan) {
    Panel(background = Palette.PendingSoft, borderColor = Palette.Pending) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("判定对不上", style = MaterialTheme.typography.titleSmall, color = Palette.Pending)
            Spacer(Modifier.width(8.dp))
            Pill("${plan.count} 条", Palette.Pending, Palette.NeutralSoft)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "这些记录当时判的结果，跟按现在的题目内容重算的结果不一样 —— 多半是作者改过正确答案。" +
                "下面列出「你当时答的」和「现在的标准答案」，由你决定那次作答算不算数。",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Ink,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "「按新答案复核」会改写这条记录的生效判定，统计、正确率、错题本随之变化；" +
                "「维持当时判定」什么都不改，只是记下你看过了，以后不再拿它问你。",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Muted,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton(
                "全部按新答案",
                onClick = { controller.review.decideAll(true) },
                enabled = !controller.review.busy,
            )
            GhostButton(
                "全部维持当时判定",
                onClick = { controller.review.decideAll(false) },
                enabled = !controller.review.busy,
            )
            if (controller.review.busy) {
                Spacer(Modifier.width(4.dp))
                Text("处理中…", style = MaterialTheme.typography.labelSmall, color = Palette.Faint)
            }
        }
    }
}

// ------------------------------------------------------------------ 单条

@Composable
private fun CandidateRow(controller: AppController, candidate: ReviewCandidate, now: Long) {
    val attempt = candidate.attempt
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(candidate.typeLabel, Palette.Accent, Palette.AccentSoft)
            Spacer(Modifier.width(8.dp))
            Text(
                candidate.bankTitle,
                style = MaterialTheme.typography.labelSmall,
                color = Palette.Faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                formatRelative(now, attempt.submittedAtMillis),
                style = MaterialTheme.typography.labelSmall,
                color = Palette.Faint,
            )
            Spacer(Modifier.weight(1f))
            Text(
                attempt.questionId,
                style = MaterialTheme.typography.labelSmall,
                color = Palette.Faint,
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            candidate.prompt,
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.Ink,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(10.dp))
        VerdictLine(
            label = "当时判的",
            verdictLabel = candidate.previousVerdict.label,
            color = verdictColor(candidate.previousVerdict),
            soft = verdictSoft(candidate.previousVerdict),
            answer = candidate.previousAnswer,
        )
        Spacer(Modifier.height(6.dp))
        VerdictLine(
            label = "按现在重算",
            verdictLabel = candidate.suggestedVerdict.label,
            color = verdictColor(candidate.suggestedVerdict),
            soft = verdictSoft(candidate.suggestedVerdict),
            answer = "现在的标准答案：${candidate.currentAnswer}",
        )

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton(
                "按新答案：${candidate.suggestedVerdict.label}",
                onClick = { controller.review.decide(attempt.attemptId, accept = true) },
                enabled = !controller.review.busy,
            )
            GhostButton(
                "维持：${candidate.previousVerdict.label}",
                onClick = { controller.review.decide(attempt.attemptId, accept = false) },
                enabled = !controller.review.busy,
            )
        }
    }
}

@Composable
private fun VerdictLine(label: String, verdictLabel: String, color: androidx.compose.ui.graphics.Color, soft: androidx.compose.ui.graphics.Color, answer: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(soft)
                .padding(horizontal = 7.dp, vertical = 2.dp),
        ) {
            Text(
                "$label · $verdictLabel",
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            answer,
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ------------------------------------------------------------------ 底部

@Composable
private fun UnreviewableNote(count: Int) {
    Panel(background = Palette.PanelMuted, borderColor = Palette.PanelBorder) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Palette.NeutralSoft),
                contentAlignment = Alignment.Center,
            ) {
                Text("?", style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                "还有 $count 条记录对应的题目已经不在题库里了（作者删了题，或整个题库被移除）。" +
                    "它们没法重算，所以不在这里 —— 但历史里一条都没少，在历史记录页仍然看得到。",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Muted,
            )
        }
    }
}

@Composable
private fun Footnote() {
    Panel(background = Palette.PanelMuted, borderColor = Palette.PanelBorder) {
        Text(
            "复核不会改写历史记录本身：每条记录存的仍是当时提交的答案和当时的判定，" +
                "你的决定作为一条单独的痕迹挂在它上面。所以就算哪天想反悔，把决定再改一次就行 —— " +
                "原始那一行永远还在。",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Muted,
        )
    }
}
