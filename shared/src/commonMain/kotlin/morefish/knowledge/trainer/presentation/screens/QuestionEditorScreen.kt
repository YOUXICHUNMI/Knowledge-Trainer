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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import morefish.knowledge.trainer.domain.model.GradingSpec
import morefish.knowledge.trainer.domain.model.Question
import morefish.knowledge.trainer.domain.model.QuestionOption
import morefish.knowledge.trainer.domain.model.QuestionType
import morefish.knowledge.trainer.presentation.AppController
import morefish.knowledge.trainer.presentation.richtext.RichText
import morefish.knowledge.trainer.presentation.theme.Palette
import morefish.knowledge.trainer.presentation.ui.GhostButton
import morefish.knowledge.trainer.presentation.ui.Panel
import morefish.knowledge.trainer.presentation.ui.PrimaryButton
import morefish.knowledge.trainer.presentation.ui.ScreenScaffold
import morefish.knowledge.trainer.presentation.ui.SectionTitle
import morefish.knowledge.trainer.presentation.ui.SecondaryButton

/**
 * 离线手工编辑：在既有题库内新建题目（个人扩展）。
 *
 * 支持的题型：单选 / 多选 / 判断 / 简答（死记硬背或关键词）/ 重组。
 * 重组的"等价次序"、逐选项解释等进阶能力留给 .qbank 导入路径 ——
 * 表单保证"填的每一项都能被校验器看懂"，不制造界面能填、导入器拒收的落差。
 */
@Composable
fun QuestionEditorScreen(controller: AppController) {
    val bank = controller.questionEditor.bank
    ScreenScaffold(title = "新建题目", subtitle = bank?.title ?: "", onBack = controller::closeQuestionEditor) {
        if (bank == null) {
            Text("题库不存在或已被删除", style = MaterialTheme.typography.bodyLarge, color = Palette.Wrong)
            return@ScreenScaffold
        }
        EditorForm(controller)
    }
}

/** 一个选项/片段的草稿。optionId 随增删实时重排，保存时以最终序号为准。 */
private class FragmentDraft(var content: String = "", var correct: Boolean = false)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorForm(controller: AppController) {
    val bankId = controller.questionEditor.bankId ?: return
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

    var type by remember { mutableStateOf(QuestionType.SINGLE_CHOICE) }
    var keywordsMode by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf("") }
    var answerText by remember { mutableStateOf("") }
    var required by remember { mutableStateOf("") }
    var forbidden by remember { mutableStateOf("") }
    var explanation by remember { mutableStateOf("") }
    val fragments = remember {
        mutableStateListOf(FragmentDraft(correct = true), FragmentDraft())
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Panel {
            SectionTitle("题型")
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    QuestionType.SINGLE_CHOICE,
                    QuestionType.MULTIPLE_CHOICE,
                    QuestionType.TRUE_FALSE,
                    QuestionType.SHORT_ANSWER,
                    QuestionType.ORDERING,
                ).forEach { candidate ->
                    TypeChip(candidate.label, active = type == candidate) {
                        type = candidate
                        if (candidate != QuestionType.SHORT_ANSWER) keywordsMode = false
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            LabeledField("题干（支持 Markdown 与 \$...\$ 公式）", prompt, fieldColors, fieldShape, minLines = 2) {
                prompt = it
            }
            if (prompt.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Panel(padding = 14.dp) {
                    SectionTitle("预览")
                    Spacer(Modifier.height(6.dp))
                    RichText(text = prompt, style = MaterialTheme.typography.bodyLarge, color = Palette.Ink)
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    "行内公式写在 \$...\$ 里，独立公式用 \$\$...\$\$；强调用 **粗体**。渲染在本机完成，不需要联网。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Muted,
                )
            }

            when (type) {
                QuestionType.SHORT_ANSWER -> {
                    Spacer(Modifier.height(8.dp))
                    GhostButton(if (keywordsMode) "当前：关键词默知识点（点击切回逐字默写）" else "当前：逐字默写（点击切换关键词模式）", onClick = {
                        keywordsMode = !keywordsMode
                    })
                    Spacer(Modifier.height(8.dp))
                    if (keywordsMode) {
                        LabeledField("必含关键词（顿号或逗号分隔）", required, fieldColors, fieldShape) { required = it }
                        Spacer(Modifier.height(8.dp))
                        LabeledField("禁用词（出现即判错，可留空）", forbidden, fieldColors, fieldShape) { forbidden = it }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "关键词全命中 = 正确，部分命中 = 部分正确，命中禁用词 = 错误；不填任何规则时判为待确认。",
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.Muted,
                        )
                    } else {
                        LabeledField("标准答案（逐字符默写）", answerText, fieldColors, fieldShape, minLines = 2) {
                            answerText = it
                        }
                    }
                }

                QuestionType.TRUE_FALSE -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "判断题使用固定选项「正确 / 错误」，在下方勾选正确答案。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.Muted,
                    )
                }

                else -> {
                    Spacer(Modifier.height(12.dp))
                    SectionTitle(
                        if (type == QuestionType.ORDERING) {
                            "片段（勾选 = 正确片段，勾选顺序 = 标准次序）"
                        } else {
                            "选项（勾选 = 正确答案）"
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                    fragments.forEachIndexed { index, fragment ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "o${index + 1}",
                                style = MaterialTheme.typography.labelLarge,
                                color = Palette.Muted,
                            )
                            OutlinedTextField(
                                value = fragment.content,
                                onValueChange = { fragment.content = it },
                                enabled = type != QuestionType.TRUE_FALSE,
                                placeholder = {
                                    Text(if (type == QuestionType.ORDERING) "片段内容" else "选项内容")
                                },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                shape = fieldShape,
                                colors = fieldColors,
                            )
                            CorrectToggle(active = fragment.correct) {
                                if (type == QuestionType.SINGLE_CHOICE || type == QuestionType.TRUE_FALSE) {
                                    fragments.forEachIndexed { i, f -> f.correct = i == index }
                                } else {
                                    fragment.correct = !fragment.correct
                                }
                            }
                            if (type != QuestionType.TRUE_FALSE && fragments.size > 2) {
                                Text(
                                    "删除",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Palette.Wrong,
                                    modifier = Modifier.clickable {
                                        fragments.removeAt(index)
                                    },
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    if (type != QuestionType.TRUE_FALSE && fragments.size < 8) {
                        GhostButton("加一个${if (type == QuestionType.ORDERING) "片段" else "选项"}", onClick = {
                            fragments.add(FragmentDraft())
                        })
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            LabeledField("解析（可选）", explanation, fieldColors, fieldShape, minLines = 2) { explanation = it }
        }

        val issues = controller.questionEditor.issues
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
        if (controller.questionEditor.saved) {
            Spacer(Modifier.height(10.dp))
            Panel(accent = Palette.Correct) {
                Text("已保存到本题库，可继续写下一题或返回首页。", style = MaterialTheme.typography.bodyMedium, color = Palette.Correct)
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(
                if (controller.questionEditor.busy) "保存中…" else "保存题目",
                enabled = !controller.questionEditor.busy && !controller.questionEditor.saved,
                onClick = {
                    val question = buildQuestion(
                        type, keywordsMode, prompt, answerText, required, forbidden, explanation, fragments,
                        idSeed = morefish.knowledge.trainer.domain.model.Ids.uuid().take(8).lowercase(),
                    )
                    controller.questionEditor.add(bankId, question)
                },
            )
            SecondaryButton("再写一题", enabled = controller.questionEditor.saved, onClick = {
                prompt = ""
                answerText = ""
                required = ""
                forbidden = ""
                explanation = ""
                fragments.clear()
                fragments.add(FragmentDraft(correct = true))
                fragments.add(FragmentDraft())
                type = QuestionType.SINGLE_CHOICE
                keywordsMode = false
                controller.questionEditor.beginNext()
            })
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TypeChip(label: String, active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (active) Palette.AccentSoft else Palette.PanelMuted)
            .border(1.dp, if (active) Palette.Accent else Palette.PanelBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (active) Palette.Accent else Palette.Muted)
    }
}

@Composable
private fun CorrectToggle(active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(shape)
            .background(if (active) Palette.CorrectSoft else Palette.PanelMuted)
            // 这个方块里面没有字，整块唯一的形状就是这条边，所以不能用分隔线的边：
            // 分隔线（#332E24）压在自己那块底上只有 1.26:1，深色下等于没有方块 ——
            // 而它是"标出正确答案"的唯一入口。用 Muted（浅 5.2 / 深 6.9），
            // 与 Material 自己的未选中勾选框一致。
            .border(1.dp, if (active) Palette.Correct else Palette.Muted, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (active) {
            Text("✓", style = MaterialTheme.typography.labelLarge, color = Palette.Correct)
        }
    }
}

private fun buildQuestion(
    type: QuestionType,
    keywordsMode: Boolean,
    prompt: String,
    answerText: String,
    required: String,
    forbidden: String,
    explanation: String,
    fragments: List<FragmentDraft>,
    idSeed: String,
): Question {
    // 判断题固定两个稳定选项（与 JSON 导入的自动补全一致）
    val options: List<QuestionOption> = when (type) {
        QuestionType.SHORT_ANSWER -> emptyList()
        QuestionType.TRUE_FALSE -> listOf(QuestionOption("T", "正确"), QuestionOption("F", "错误"))
        else -> fragments.mapIndexed { i, f -> QuestionOption("o${i + 1}", f.content.trim()) }
    }
    val correctIds: List<String> = when (type) {
        QuestionType.SHORT_ANSWER -> emptyList()
        QuestionType.TRUE_FALSE -> listOf(if (fragments.firstOrNull()?.correct != false) "T" else "F")
        else -> fragments.mapIndexedNotNull { i, f -> if (f.correct) "o${i + 1}" else null }
    }
    val grading: GradingSpec = when (type) {
        QuestionType.SHORT_ANSWER ->
            if (keywordsMode) {
                GradingSpec.Keywords(required = splitKeywords(required), forbidden = splitKeywords(forbidden))
            } else {
                GradingSpec.StrictText
            }

        QuestionType.ORDERING -> GradingSpec.Ordering(canonical = correctIds)
        else -> GradingSpec.ExactSet
    }
    return Question(
        questionId = "manual-$idSeed",
        type = type,
        promptMarkdown = prompt.trim(),
        options = options,
        correctOptionIds = correctIds.toSet(),
        answerText = if (type == QuestionType.SHORT_ANSWER) answerText.trim() else null,
        explanationMarkdown = explanation.trim().ifEmpty { null },
        shuffleAllowed = type != QuestionType.ORDERING,
        grading = grading,
        tags = listOf("manual"),
    )
}

private fun splitKeywords(raw: String): List<String> =
    raw.split("、", ",", "，", ";", "；")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

@Composable
private fun LabeledField(
    label: String,
    value: String,
    colors: androidx.compose.material3.TextFieldColors,
    shape: androidx.compose.ui.graphics.Shape,
    minLines: Int = 1,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        minLines = minLines,
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        colors = colors,
    )
}
