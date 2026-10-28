package morefish.knowledge.trainer.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import morefish.knowledge.trainer.domain.model.Note
import morefish.knowledge.trainer.domain.model.NoteScope
import morefish.knowledge.trainer.domain.model.Question
import morefish.knowledge.trainer.presentation.AppController
import morefish.knowledge.trainer.presentation.richtext.Markdown
import morefish.knowledge.trainer.presentation.richtext.RichText
import morefish.knowledge.trainer.presentation.theme.Palette
import morefish.knowledge.trainer.presentation.ui.GhostButton
import morefish.knowledge.trainer.presentation.ui.KtChip
import morefish.knowledge.trainer.presentation.ui.Panel
import morefish.knowledge.trainer.presentation.ui.Pill
import morefish.knowledge.trainer.presentation.ui.PrimaryButton
import morefish.knowledge.trainer.presentation.ui.ScreenScaffold
import morefish.knowledge.trainer.presentation.ui.SectionTitle

/**
 * 笔记编辑器。
 *
 * 表单状态在界面本地：打字不需要每次按键都穿过控制器，
 * 也自然不会出现"切走再回来发现写了半句被吞掉"。
 * 控制器只保留种子（编辑哪条 / 新建时预先挂在哪），保存时才一次性提交。
 */
@Composable
fun NoteEditorScreen(controller: AppController) {
    val seed = controller.notes.seed
    ScreenScaffold(
        title = if (controller.notes.editingExisting) "编辑笔记" else "新建笔记",
        subtitle = seed?.scope?.label ?: "",
        onBack = controller::closeNoteEditor,
    ) {
        if (seed == null) {
            Text("没有正在编辑的笔记，请从列表里选一条。", style = MaterialTheme.typography.bodyLarge, color = Palette.Muted)
            return@ScreenScaffold
        }
        // 种子换了（新建保存后拿到 noteId、或从练习页切到另一题）就重建表单
        key(seed.noteId, seed.scope.wireName, seed.questionId) {
            NoteForm(controller, seed)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoteForm(controller: AppController, seed: Note) {
    val banks = controller.banks
    var scope by remember { mutableStateOf(seed.scope) }
    var bankId by remember { mutableStateOf(seed.bankId) }
    var questionId by remember { mutableStateOf(seed.questionId) }
    var title by remember { mutableStateOf(seed.title) }
    var body by remember { mutableStateOf(seed.bodyMarkdown) }
    var dirty by remember { mutableStateOf(false) }
    var questionQuery by remember { mutableStateOf("") }
    var confirmingDelete by remember { mutableStateOf(false) }

    // 从练习页进来时题目关联是钉死的：这正是不让"题目笔记变成悬空记录"的保证。
    val lockedQuestion = seed.scope == NoteScope.QUESTION && !seed.questionId.isNullOrBlank()
    val bank = banks.firstOrNull { it.bankId == bankId }
    val selectedQuestion = bank?.questions?.firstOrNull { it.questionId == questionId }
    val scopeLocked = lockedQuestion
    val fieldShape = RoundedCornerShape(12.dp)
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Palette.Accent,
        unfocusedBorderColor = Palette.PanelBorderStrong,
        focusedContainerColor = Palette.Panel,
        unfocusedContainerColor = Palette.Panel,
        cursorColor = Palette.Accent,
        focusedLabelColor = Palette.AccentInk,
        unfocusedLabelColor = Palette.Muted,
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Panel {
            SectionTitle("归属")
            Spacer(Modifier.height(8.dp))
            if (scopeLocked) {
                Text(
                    "这条笔记是对当前这道题写的，题目关联固定不变。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Muted,
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NoteScope.entries.forEach { candidate ->
                        KtChip(
                            label = candidate.label,
                            selected = scope == candidate,
                            onClick = {
                                scope = candidate
                                if (candidate != NoteScope.QUESTION) questionId = null
                                if (candidate == NoteScope.DRAFT) bankId = null
                            },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(scope.hint, style = MaterialTheme.typography.bodySmall, color = Palette.Faint)
            }

            when (scope) {
                NoteScope.BANK -> {
                    Spacer(Modifier.height(12.dp))
                    BankPicker(banks, bankId) { bankId = it }
                }

                NoteScope.QUESTION -> {
                    Spacer(Modifier.height(12.dp))
                    if (lockedQuestion) {
                        Panel(background = Palette.PanelMuted, padding = 14.dp) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Pill("已关联", Palette.Correct, Palette.CorrectSoft)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    selectedQuestion?.promptMarkdown?.let { Markdown.plainExcerpt(it, 60) }
                                        ?: "（关联题目已不存在）",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Palette.Ink,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    } else {
                        BankPicker(banks, bankId) { picked ->
                            bankId = picked
                            questionId = null
                        }
                        Spacer(Modifier.height(10.dp))
                        if (bank == null) {
                            Text(
                                "先选择题库，再搜题目。题目笔记必须挂在具体某一题上。",
                                style = MaterialTheme.typography.bodySmall,
                                color = Palette.Muted,
                            )
                        } else if (selectedQuestion != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Pill("已选", Palette.Correct, Palette.CorrectSoft)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    Markdown.plainExcerpt(selectedQuestion.promptMarkdown, 60),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Palette.Ink,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                GhostButton("更换", onClick = { questionId = null })
                            }
                        } else {
                            OutlinedTextField(
                                value = questionQuery,
                                onValueChange = { questionQuery = it },
                                placeholder = { Text("输入关键词搜题目（至少 1 个字）") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = fieldShape,
                                colors = fieldColors,
                            )
                            val matches = remember(questionQuery, bank.bankId) {
                                if (questionQuery.isBlank()) emptyList()
                                else bank.questions
                                    .filter { it.promptMarkdown.contains(questionQuery, ignoreCase = true) }
                                    .take(6)
                            }
                            if (questionQuery.isNotBlank() && matches.isEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text("没有匹配的题目", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                            }
                            matches.forEach { candidate ->
                                Spacer(Modifier.height(4.dp))
                                QuestionRow(candidate) {
                                    questionId = candidate.questionId
                                    questionQuery = ""
                                }
                            }
                        }
                    }
                }

                NoteScope.DRAFT -> Unit
            }
        }

        Spacer(Modifier.height(12.dp))
        Panel {
            OutlinedTextField(
                value = title,
                onValueChange = {
                    title = it
                    dirty = true
                },
                label = { Text("标题（可留空，用正文首行代替）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = fieldShape,
                colors = fieldColors,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = body,
                onValueChange = {
                    body = it
                    dirty = true
                },
                label = { Text("正文（支持 Markdown 与 \$...\$ 公式）") },
                minLines = 10,
                modifier = Modifier.fillMaxWidth(),
                shape = fieldShape,
                colors = fieldColors,
            )
            if (body.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Panel(background = Palette.PanelMuted, padding = 14.dp) {
                    SectionTitle("预览")
                    Spacer(Modifier.height(6.dp))
                    RichText(text = body, style = MaterialTheme.typography.bodyLarge, color = Palette.Ink)
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    "公式写在本机渲染，不需要联网；不认识的命令会按源码显示，不会静默吞掉。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Muted,
                )
            }
        }

        val issues = controller.notes.issues
        if (issues.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Panel(accent = Palette.Wrong) {
                Text("还不能保存：", style = MaterialTheme.typography.titleSmall, color = Palette.Wrong)
                issues.forEach { issue ->
                    Spacer(Modifier.height(4.dp))
                    Text("· ${issue.message}", style = MaterialTheme.typography.bodyMedium, color = Palette.Ink)
                }
            }
        }

        if (controller.notes.saved && !dirty) {
            Spacer(Modifier.height(10.dp))
            Panel(accent = Palette.Correct) {
                Text("已保存到本机。", style = MaterialTheme.typography.bodyMedium, color = Palette.Correct)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("返回", onClick = controller::closeNoteEditor)
                    if (!lockedQuestion) {
                        GhostButton("再写一条", onClick = {
                            controller.openNewNote(scope = scope, bankId = bankId, questionId = questionId)
                        })
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PrimaryButton(
                if (controller.notes.busy) "保存中…" else "保存笔记",
                enabled = !controller.notes.busy,
                onClick = {
                    dirty = false
                    controller.notes.save(
                        seed.copy(
                            scope = scope,
                            bankId = if (scope == NoteScope.DRAFT) null else bankId,
                            questionId = if (scope == NoteScope.QUESTION) questionId else null,
                            title = title,
                            bodyMarkdown = body,
                        ),
                    )
                },
            )
            if (controller.notes.editingExisting) {
                if (confirmingDelete) {
                    GhostButton("确认删除（不可恢复）", color = Palette.Wrong, onClick = {
                        controller.deleteNote(seed.noteId)
                    })
                    GhostButton("取消", onClick = { confirmingDelete = false })
                } else {
                    GhostButton("删除这篇", color = Palette.Wrong, onClick = { confirmingDelete = true })
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BankPicker(
    banks: List<morefish.knowledge.trainer.domain.model.QuestionBank>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    if (banks.isEmpty()) {
        Text(
            "还没有题库。题库笔记需要先导入一份 .qbank 或示例题库。",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Muted,
        )
        return
    }
    SectionTitle("题库", if (banks.size == 1) null else "${banks.size} 个")
    Spacer(Modifier.height(6.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        banks.forEach { candidate ->
            KtChip(
                label = candidate.title,
                selected = selectedId == candidate.bankId,
                onClick = { onSelect(candidate.bankId) },
            )
        }
    }
}

@Composable
private fun QuestionRow(question: Question, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Palette.PanelMuted)
            .border(1.dp, Palette.PanelBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(question.type.label, Palette.AccentInk, Palette.AccentSoft)
            Spacer(Modifier.width(8.dp))
            Text(
                Markdown.plainExcerpt(question.promptMarkdown, 70),
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
