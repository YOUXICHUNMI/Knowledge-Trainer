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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import morefish.knowledge.trainer.application.usecase.PatchIntake
import morefish.knowledge.trainer.domain.patch.PatchApply
import morefish.knowledge.trainer.domain.patch.PatchChangeKind
import morefish.knowledge.trainer.domain.patch.PatchConflict
import morefish.knowledge.trainer.domain.patch.PatchEntry
import morefish.knowledge.trainer.domain.patch.PatchReport
import morefish.knowledge.trainer.presentation.AppController
import morefish.knowledge.trainer.presentation.theme.Palette
import morefish.knowledge.trainer.presentation.ui.BannerHost
import morefish.knowledge.trainer.presentation.ui.Divider
import morefish.knowledge.trainer.presentation.ui.GhostButton
import morefish.knowledge.trainer.presentation.ui.Panel
import morefish.knowledge.trainer.presentation.ui.Pill
import morefish.knowledge.trainer.presentation.ui.PrimaryButton
import morefish.knowledge.trainer.presentation.ui.ScreenScaffold
import morefish.knowledge.trainer.presentation.ui.SectionTitle
import morefish.knowledge.trainer.presentation.ui.StatTile

/**
 * 题库更新补丁的预览页。
 *
 * 这一页只做一件事：**在改任何东西之前，把会变的东西摆出来。**
 *
 * 顺序是刻意的 —— 先"哪几道题的答案变了"（这会影响你对过去判断的理解），
 * 再"哪几道题面变了"（这只会影响以后），最后才是总数。
 * 反过来排，用户会先看到一个"共 5 处改动"的漂亮数字，然后才在长列表底部
 * 发现有一道他练过三次的题答案被改了。
 */
@Composable
fun PatchScreen(controller: AppController) {
    ScreenScaffold(
        title = "题库更新",
        subtitle = "先看清会改什么，再决定要不要改",
        overline = "补丁",
        onBack = controller::closePatch,
    ) {
        BannerHost(controller.banner, controller::dismissBanner)
        when (val intake = controller.patchIntake) {
            null -> Text(
                "没有正在查看的补丁。从「导入」页选择 .qbpatch 文件即可。",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Muted,
            )

            is PatchIntake.Rejected -> RejectedPanel(intake.issues.map { it.code to it.message })
            is PatchIntake.BankMissing -> MissingPanel(intake)
            is PatchIntake.Ready -> ReadyPanel(controller, intake)
        }
    }
}

// ------------------------------------------------------------------ 状态分支

@Composable
private fun RejectedPanel(problems: List<Pair<String, String>>) {
    Panel(borderColor = Palette.WrongSoft, background = Palette.WrongSoft) {
        Text("补丁未通过校验", style = MaterialTheme.typography.titleSmall, color = Palette.Wrong)
        Spacer(Modifier.height(6.dp))
        Text(
            "下面这些地方不合法。什么都没有改 —— 补丁是整包校验的，错一条就整包拒绝。",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Ink,
        )
        Spacer(Modifier.height(10.dp))
        problems.take(8).forEach { (code, message) ->
            Row(modifier = Modifier.padding(vertical = 3.dp)) {
                Text(code, style = MaterialTheme.typography.labelSmall, color = Palette.Wrong)
                Spacer(Modifier.width(8.dp))
                Text(message, style = MaterialTheme.typography.bodySmall, color = Palette.Ink)
            }
        }
        if (problems.size > 8) {
            Spacer(Modifier.height(6.dp))
            Text("还有 ${problems.size - 8} 条没有列出来", style = MaterialTheme.typography.bodySmall, color = Palette.Faint)
        }
    }
}

@Composable
private fun MissingPanel(intake: PatchIntake.BankMissing) {
    val patch = intake.patch
    Panel(borderColor = Palette.PendingSoft, background = Palette.PendingSoft) {
        Text("找不到要更新的题库", style = MaterialTheme.typography.titleSmall, color = Palette.Pending)
        Spacer(Modifier.height(6.dp))
        Text(
            "这个补丁要求题库里有第 ${patch.baseRevision} 版的内容，但这台机器上没有对得上的题库。" +
                "补丁只做增量修改，没法单独安装 —— 先用完整包把那个题库装上，再来应用它。",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Ink,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "补丁 ${patch.patchId} · 目标题库 ${patch.bankId.take(8)}…",
            style = MaterialTheme.typography.labelSmall,
            color = Palette.Faint,
        )
    }
}

@Composable
private fun ReadyPanel(controller: AppController, intake: PatchIntake.Ready) {
    PatchHeader(intake)
    Spacer(Modifier.height(14.dp))

    when (val plan = intake.plan) {
        is PatchApply.Ok -> {
            ChangeStats(plan.report)
            Spacer(Modifier.height(14.dp))
            AnswerSection(plan.report, intake.affectedAttempts)
            Spacer(Modifier.height(14.dp))
            EntryList(plan.report.entries)
            Spacer(Modifier.height(14.dp))
            ApplyFooter(controller, intake)
        }

        is PatchApply.NoOp -> NoticePanel(
            title = "不需要更新",
            body = plan.reason,
            tint = Palette.Muted,
            soft = Palette.PanelMuted,
        )

        is PatchApply.Outdated -> NoticePanel(
            title = "这个补丁比本地题库旧",
            body = "本地已经是第 ${plan.bank.contentRevision} 版，这个补丁只更新到第 ${plan.patchTarget} 版。" +
                "应用它等于把内容往回退，所以这里不提供「应用」按钮。",
            tint = Palette.Pending,
            soft = Palette.PendingSoft,
        )

        is PatchApply.Failed -> ConflictPanel(plan.conflicts)
    }
}

// ------------------------------------------------------------------ 头部

@Composable
private fun PatchHeader(intake: PatchIntake.Ready) {
    val patch = intake.patch
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(patch.name, style = MaterialTheme.typography.titleMedium, color = Palette.Ink)
            Spacer(Modifier.width(8.dp))
            Pill("v${patch.version}", Palette.Accent, Palette.AccentSoft)
            Spacer(Modifier.weight(1f))
            Pill(
                "第 ${patch.baseRevision} 版 → 第 ${patch.targetRevision} 版",
                Palette.Ink,
                Palette.PanelMuted,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "目标题库《${intake.bank.title}》 · 本地 ${intake.bank.questionCount} 题",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Muted,
        )
        if (patch.notes.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(patch.notes, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "补丁 ${patch.patchId} · 基于内容 ${patch.baseFingerprint.take(8)}…" +
                " · 这份补丁只改上面这个题库第 ${patch.baseRevision} 版的内容",
            style = MaterialTheme.typography.labelSmall,
            color = Palette.Faint,
        )
    }
}

@Composable
private fun ChangeStats(report: PatchReport) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile(
            label = "新增",
            value = report.added,
            modifier = Modifier.weight(1f),
            accent = if (report.added > 0) Palette.Correct else Palette.Faint,
        )
        StatTile(
            label = "修改",
            value = report.updated,
            modifier = Modifier.weight(1f),
            accent = if (report.updated > 0) Palette.Accent else Palette.Faint,
        )
        StatTile(
            label = "删除",
            value = report.removed,
            modifier = Modifier.weight(1f),
            accent = if (report.removed > 0) Palette.Wrong else Palette.Faint,
        )
        StatTile(
            label = "答案变更",
            value = report.answerChanges.size,
            modifier = Modifier.weight(1f),
            accent = if (report.answerChanges.isNotEmpty()) Palette.Pending else Palette.Faint,
        )
    }
}

// ------------------------------------------------------------------ 答案变更

@Composable
private fun AnswerSection(report: PatchReport, affectedAttempts: Int) {
    val answers = report.answerChanges
    if (answers.isEmpty()) {
        Panel(background = Palette.PanelMuted, borderColor = Palette.PanelBorder) {
            Text(
                "这次更新没有改动任何一道题的正确答案 —— 你的练习历史与现在的判题口径仍然一致。",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Muted,
            )
        }
        return
    }

    Panel(background = Palette.PendingSoft, borderColor = Palette.Pending) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("答案变更", style = MaterialTheme.typography.titleSmall, color = Palette.Pending)
            Spacer(Modifier.width(8.dp))
            Pill("${answers.size} 道", Palette.Pending, Palette.NeutralSoft)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (affectedAttempts > 0) {
                "这些题「什么算答对」变了。你在其中练过 $affectedAttempts 次 —— " +
                    "那些记录不会因为这次更新而改变，它们仍然是当时的判定结果。"
            } else {
                "这些题「什么算答对」变了，但你还一次都没练过，所以没有历史受影响。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Ink,
        )
        Spacer(Modifier.height(10.dp))
        answers.forEach { entry ->
            AnswerRow(entry)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "历史不会被改写：作者改答案不等于你当时答错了。要不要按新答案复核旧记录，是你自己的决定。",
            style = MaterialTheme.typography.labelSmall,
            color = Palette.Muted,
        )
    }
}

@Composable
private fun AnswerRow(entry: PatchEntry) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(entry.questionId, style = MaterialTheme.typography.labelSmall, color = Palette.Faint)
            Spacer(Modifier.width(8.dp))
            Text(
                entry.title,
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("旧", style = MaterialTheme.typography.labelSmall, color = Palette.Faint)
            Spacer(Modifier.width(6.dp))
            Text(entry.before, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("新", style = MaterialTheme.typography.labelSmall, color = Palette.Pending)
            Spacer(Modifier.width(6.dp))
            Text(entry.after, style = MaterialTheme.typography.bodySmall, color = Palette.Ink)
        }
    }
}

// ------------------------------------------------------------------ 改动清单

@Composable
private fun EntryList(entries: List<PatchEntry>) {
    SectionTitle("这次会改什么", "${entries.size} 处")
    Spacer(Modifier.height(8.dp))
    Panel(padding = 0.dp) {
        entries.forEachIndexed { index, entry ->
            if (index > 0) Divider()
            EntryRow(entry)
        }
    }
}

@Composable
private fun EntryRow(entry: PatchEntry) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(kindSoft(entry.kind)),
            contentAlignment = Alignment.Center,
        ) {
            Text(kindSign(entry.kind), style = MaterialTheme.typography.labelSmall, color = kindTint(entry.kind))
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.kind.label, style = MaterialTheme.typography.labelSmall, color = kindTint(entry.kind))
                Spacer(Modifier.width(8.dp))
                Text(entry.questionId, style = MaterialTheme.typography.labelSmall, color = Palette.Faint)
            }
            Spacer(Modifier.height(3.dp))
            Text(entry.title, style = MaterialTheme.typography.bodyMedium, color = Palette.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (entry.kind == PatchChangeKind.ANSWER ||
                entry.kind == PatchChangeKind.REMOVED ||
                entry.kind == PatchChangeKind.PROVENANCE
            ) {
                Spacer(Modifier.height(3.dp))
                Text(
                    when (entry.kind) {
                        PatchChangeKind.REMOVED -> "原答案：${entry.before}"
                        PatchChangeKind.PROVENANCE -> "${entry.before} → ${entry.after}"
                        else -> "${entry.before} → ${entry.after}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Muted,
                )
            }
        }
    }
}

private fun kindSign(kind: PatchChangeKind): String = when (kind) {
    PatchChangeKind.ADDED -> "＋"
    PatchChangeKind.REMOVED -> "－"
    PatchChangeKind.ANSWER -> "!"
    PatchChangeKind.CONTENT -> "~"
    // 出处用"注"的意思：它不是内容改动，是给这道题补了一句说明
    PatchChangeKind.PROVENANCE -> "◎"
}

private fun kindTint(kind: PatchChangeKind): Color = when (kind) {
    PatchChangeKind.ADDED -> Palette.Correct
    PatchChangeKind.REMOVED -> Palette.Wrong
    PatchChangeKind.ANSWER -> Palette.Pending
    PatchChangeKind.CONTENT -> Palette.Accent
    PatchChangeKind.PROVENANCE -> Palette.Muted
}

private fun kindSoft(kind: PatchChangeKind): Color = when (kind) {
    PatchChangeKind.ADDED -> Palette.CorrectSoft
    PatchChangeKind.REMOVED -> Palette.WrongSoft
    PatchChangeKind.ANSWER -> Palette.PendingSoft
    PatchChangeKind.CONTENT -> Palette.AccentSoft
    PatchChangeKind.PROVENANCE -> Palette.NeutralSoft
}

// ------------------------------------------------------------------ 底部与拒绝

@Composable
private fun ApplyFooter(controller: AppController, intake: PatchIntake.Ready) {
    val patch = intake.patch
    Panel(background = Palette.PanelMuted, borderColor = Palette.PanelBorder) {
        Text(
            "应用之后：题库变为第 ${patch.targetRevision} 版，题库卡片上会显示版本号；" +
                "改动过的题会重新生成 AI 讲解（缓存按内容版本分开，旧讲解不会套在新题上）。" +
                "练习历史一条都不动。",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Muted,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(
                text = if (controller.busy) "应用中…" else "应用更新",
                onClick = controller::applyPickedPatch,
                enabled = !controller.busy,
            )
            GhostButton("取消", onClick = controller::closePatch)
        }
    }
}

@Composable
private fun NoticePanel(title: String, body: String, tint: Color, soft: Color) {
    Panel(background = soft, borderColor = tint) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = tint)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodySmall, color = Palette.Ink)
    }
}

@Composable
private fun ConflictPanel(conflicts: List<PatchConflict>) {
    Panel(borderColor = Palette.Wrong, background = Palette.WrongSoft) {
        Text("补丁和本地题库对不上", style = MaterialTheme.typography.titleSmall, color = Palette.Wrong)
        Spacer(Modifier.height(6.dp))
        Text(
            "什么都没有改。下面每一条都是拒绝的理由 —— 补丁不提供「尽力应用」模式：" +
                "胡乱改一半的结果比不改更糟，而且事后没法分辨哪里被改过。",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Ink,
        )
        Spacer(Modifier.height(10.dp))
        conflicts.take(10).forEach { conflict ->
            Row(modifier = Modifier.padding(vertical = 3.dp)) {
                Text(conflict.code, style = MaterialTheme.typography.labelSmall, color = Palette.Wrong)
                Spacer(Modifier.width(8.dp))
                Text(conflict.message, style = MaterialTheme.typography.bodySmall, color = Palette.Ink)
            }
        }
        if (conflicts.size > 10) {
            Spacer(Modifier.height(6.dp))
            Text("还有 ${conflicts.size - 10} 条没有列出来", style = MaterialTheme.typography.bodySmall, color = Palette.Faint)
        }
    }
}
