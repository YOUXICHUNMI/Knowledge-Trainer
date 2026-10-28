package morefish.knowledge.trainer.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import morefish.knowledge.trainer.application.port.WallClock
import morefish.knowledge.trainer.domain.model.Note
import morefish.knowledge.trainer.domain.model.NoteScope
import morefish.knowledge.trainer.domain.model.Question
import morefish.knowledge.trainer.domain.model.sortedForDisplay
import morefish.knowledge.trainer.presentation.AppController
import morefish.knowledge.trainer.presentation.richtext.Markdown
import morefish.knowledge.trainer.presentation.theme.Palette
import morefish.knowledge.trainer.presentation.ui.BannerHost
import morefish.knowledge.trainer.presentation.ui.EmptyState
import morefish.knowledge.trainer.presentation.ui.GhostButton
import morefish.knowledge.trainer.presentation.ui.KtChip
import morefish.knowledge.trainer.presentation.ui.Panel
import morefish.knowledge.trainer.presentation.ui.Pill
import morefish.knowledge.trainer.presentation.ui.PrimaryButton
import morefish.knowledge.trainer.presentation.ui.ScreenScaffold
import morefish.knowledge.trainer.presentation.ui.formatRelative

/**
 * 个人笔记列表。
 *
 * 三种归属共用一套列表，靠筛选胶囊区分 —— 分成三个 Tab 会让人在
 * "我是不是找错地方了"上浪费时间，而笔记总量通常不大。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NotesScreen(controller: AppController) {
    val now = WallClock.System.nowMillis()
    var query by remember { mutableStateOf("") }
    var scopeFilter by remember { mutableStateOf<NoteScope?>(null) }
    val bankFilter = controller.noteBankFilter
    val bankFilterTitle = bankFilter?.let { id -> controller.banks.firstOrNull { it.bankId == id }?.title }

    val all = controller.notes
    val visible = all
        .filter { note ->
            (scopeFilter == null || note.scope == scopeFilter) &&
                (bankFilter == null || note.bankId == bankFilter) &&
                note.matches(query)
        }
        .sortedForDisplay()

    ScreenScaffold(
        title = "笔记",
        subtitle = buildString {
            append("本地保存")
            if (all.isNotEmpty()) append(" · 共 ${all.size} 条")
        },
        overline = "个人笔记",
        onBack = controller::closeNotes,
        trailing = {
            PrimaryButton("新建", onClick = { controller.openNewNote() }, leading = "＋")
        },
    ) {
        BannerHost(controller.banner, controller::dismissBanner)

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            KtChip("全部 ${all.size}", selected = scopeFilter == null, onClick = { scopeFilter = null })
            NoteScope.entries.forEach { scope ->
                val count = all.count { it.scope == scope }
                KtChip(
                    label = "${scope.label} $count",
                    selected = scopeFilter == scope,
                    onClick = { scopeFilter = if (scopeFilter == scope) null else scope },
                )
            }
        }

        if (bankFilter != null) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(
                    "只看：${bankFilterTitle ?: "已删除的题库"}",
                    Palette.Accent,
                    Palette.AccentSoft,
                )
                Spacer(Modifier.width(4.dp))
                GhostButton("清除筛选", onClick = { controller.filterNotesByBank(null) })
            }
        }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("搜索标题或正文") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Palette.Accent,
                unfocusedBorderColor = Palette.PanelBorderStrong,
                focusedContainerColor = Palette.Panel,
                unfocusedContainerColor = Palette.Panel,
                cursorColor = Palette.Accent,
            ),
        )

        Spacer(Modifier.height(16.dp))

        when {
            all.isEmpty() -> EmptyState(
                title = "还没有笔记",
                hint = "题库笔记记资料来源与使用心得，题目笔记记思路与易错点，草稿随手写。全部只存在本机，不需要联网。",
                glyph = "✎",
                action = { PrimaryButton("写第一条", onClick = { controller.openNewNote() }) },
            )

            visible.isEmpty() -> EmptyState(
                title = "没有匹配的笔记",
                hint = "换个关键词，或者清除筛选条件看看全部 ${all.size} 条。",
                glyph = "○",
            )

            else -> visible.forEach { note ->
                NoteCard(note, now, controller)
                Spacer(Modifier.height(10.dp))
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun NoteCard(note: Note, now: Long, controller: AppController) {
    val bank = note.bankId?.let { id -> controller.banks.firstOrNull { it.bankId == id } }
    val question = questionOf(controller, note)
    val context = when (note.scope) {
        NoteScope.BANK -> "题库 · ${bank?.title ?: "已删除的题库"}"
        NoteScope.QUESTION -> "题目 · " +
            (question?.promptMarkdown?.let { Markdown.plainExcerpt(it, 46) } ?: "关联题目已不存在")
        NoteScope.DRAFT -> "不关联题库，随时可以清掉"
    }

    Panel(onClick = { controller.openNoteEditor(note.noteId) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(note.scope.label, scopeColor(note.scope), scopeSoft(note.scope))
            if (note.pinned) {
                Spacer(Modifier.width(6.dp))
                Pill("置顶", Palette.Pending, Palette.PendingSoft)
            }
            Spacer(Modifier.weight(1f))
            Text(
                formatRelative(now, note.updatedAtMillis),
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Faint,
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            titleOf(note),
            style = MaterialTheme.typography.titleSmall,
            color = Palette.Ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (note.title.isNotBlank() && note.bodyMarkdown.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                Markdown.plainExcerpt(note.bodyMarkdown, 90),
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            context,
            style = MaterialTheme.typography.bodySmall,
            color = if (note.scope == NoteScope.QUESTION && question == null) Palette.Wrong else Palette.Faint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(6.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GhostButton(
                if (note.pinned) "取消置顶" else "置顶",
                onClick = { controller.toggleNotePin(note.noteId) },
            )
            GhostButton("编辑", onClick = { controller.openNoteEditor(note.noteId) })
        }
    }
}

/**
 * 列表标题：优先用户写的标题；没写就取正文首行并**剥掉 Markdown 标记**。
 * 用 presentation 层的 plainExcerpt 而不是 domain 的 displayTitle，
 * 否则列表里会露出 `**易错点**` 这种源码痕迹。
 */
private fun titleOf(note: Note): String {
    val explicit = note.title.trim()
    if (explicit.isNotEmpty()) return explicit
    // 只取第一段：把行内公式换成空格，避免标题里冒出 \dfrac 这种源码
    val firstLine = note.bodyMarkdown.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .map { it.replace(Regex("\\$[^$]*\\$"), " ") }
        .firstOrNull { it.isNotBlank() }
        .orEmpty()
    val excerpt = Markdown.plainExcerpt(firstLine, Note.EXCERPT_LENGTH).trim()
    return excerpt.ifBlank { "（无标题）" }
}

private fun questionOf(controller: AppController, note: Note): Question? {
    if (note.scope != NoteScope.QUESTION) return null
    val bankId = note.bankId ?: return null
    val questionId = note.questionId ?: return null
    return controller.banks.firstOrNull { it.bankId == bankId }
        ?.questions?.firstOrNull { it.questionId == questionId }
}

private fun scopeColor(scope: NoteScope) = when (scope) {
    NoteScope.BANK -> Palette.Accent
    NoteScope.QUESTION -> Palette.Correct
    NoteScope.DRAFT -> Palette.Pending
}

private fun scopeSoft(scope: NoteScope) = when (scope) {
    NoteScope.BANK -> Palette.AccentSoft
    NoteScope.QUESTION -> Palette.CorrectSoft
    NoteScope.DRAFT -> Palette.PendingSoft
}
