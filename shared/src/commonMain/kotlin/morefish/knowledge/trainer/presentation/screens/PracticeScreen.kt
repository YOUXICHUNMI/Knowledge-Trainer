package morefish.knowledge.trainer.presentation.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import morefish.knowledge.trainer.domain.ai.AiRecordState
import morefish.knowledge.trainer.domain.graph.QuestionRef
import morefish.knowledge.trainer.domain.model.DiffSegment
import morefish.knowledge.trainer.domain.model.EvaluationDetail
import morefish.knowledge.trainer.domain.model.GradingSpec
import morefish.knowledge.trainer.domain.model.QuestionOption
import morefish.knowledge.trainer.domain.model.QuestionType
import morefish.knowledge.trainer.domain.model.SegmentKind
import morefish.knowledge.trainer.domain.model.SessionItem
import morefish.knowledge.trainer.domain.model.Verdict
import morefish.knowledge.trainer.domain.model.forQuestion
import morefish.knowledge.trainer.presentation.AppController
import morefish.knowledge.trainer.presentation.theme.Palette
import morefish.knowledge.trainer.presentation.richtext.RichText
import morefish.knowledge.trainer.presentation.ui.BannerHost
import morefish.knowledge.trainer.presentation.ui.Divider
import morefish.knowledge.trainer.presentation.ui.GhostButton
import morefish.knowledge.trainer.presentation.ui.Motion
import morefish.knowledge.trainer.presentation.ui.Panel
import morefish.knowledge.trainer.presentation.ui.Pill
import morefish.knowledge.trainer.presentation.ui.PrimaryButton
import morefish.knowledge.trainer.presentation.ui.ProvenanceLine
import morefish.knowledge.trainer.presentation.ui.QuestionFigure
import morefish.knowledge.trainer.presentation.ui.ScreenScaffold
import morefish.knowledge.trainer.presentation.ui.SecondaryButton
import morefish.knowledge.trainer.presentation.ui.SectionTitle
import morefish.knowledge.trainer.presentation.ui.animatedTint
import morefish.knowledge.trainer.presentation.ui.formatDuration
import morefish.knowledge.trainer.presentation.ui.pressScale
import morefish.knowledge.trainer.presentation.ui.verdictColor
import morefish.knowledge.trainer.presentation.ui.verdictSoft

@Composable
fun PracticeScreen(controller: AppController) {
    val session = controller.practice.session
    val item = session?.current

    if (session == null || item == null) {
        ScreenScaffold(title = "练习", onBack = controller::goHome) {
            Text("没有进行中的会话。", color = Palette.Muted)
        }
        return
    }

    val feedback = controller.practice.feedback
    val total = session.items.size
    val done = session.cursor + if (feedback != null) 1 else 0

    ScreenScaffold(
        title = controller.bankTitle(session.bankId),
        subtitle = buildString {
            append(session.planLabel.ifBlank { "练习" })
            append(" · ").append(item.question.type.label)
            // 多语言题库要说清这一场是哪种语言，否则"题变了"会被当成题库出错
            controller.practice.sessionLocaleLabel?.let { append(" · ").append(it) }
            append(" · 已答 ").append(session.answeredCount).append(" / ").append(total)
        },
        overline = "第 ${session.cursor + 1} 题 / 共 $total 题",
        onBack = controller::goHome,
        progress = if (total == 0) 0f else done.toFloat() / total,
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FavoriteToggle(
                    active = controller.isFavorite(item.question.questionId),
                    onClick = { controller.toggleFavorite(item.question) },
                )
                SecondaryButton("暂停退出", onClick = controller::pauseAndExit)
            }
        },
    ) {
        BannerHost(controller.banner, controller::dismissBanner)

        // 换题时整块内容横向推入，让"翻页"有方向感；同题内作答不触发。
        AnimatedContent(
            targetState = item,
            transitionSpec = {
                (
                    fadeIn(tween(Motion.StandardMillis)) +
                        slideInHorizontally(tween(Motion.EmphasisMillis)) { it / 8 }
                    ).togetherWith(
                    fadeOut(tween(Motion.QuickMillis)) +
                        slideOutHorizontally(tween(Motion.StandardMillis)) { -it / 16 },
                ).using(SizeTransform(clip = false))
            },
            label = "question",
        ) { current ->
            Column {
                QuestionCard(current, index = session.items.indexOf(current), total = total, controller = controller)
                Spacer(Modifier.height(14.dp))
                AnswerArea(current, controller, feedback != null)
                // 讲解放在作答之后：没作答就展开，等于把答案提前给出来
                if (feedback != null) {
                    Spacer(Modifier.height(14.dp))
                    AiExplanationPanel(controller)
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        // 提交区与反馈区互斥，用展开/收起过渡而不是瞬间替换，
        // 否则提交那一刻整页会跳一下。
        AnimatedVisibility(
            visible = feedback == null,
            enter = fadeIn(tween(Motion.StandardMillis)) + expandVertically(tween(Motion.EmphasisMillis)),
            exit = fadeOut(tween(Motion.QuickMillis)) + shrinkVertically(tween(Motion.StandardMillis)),
        ) {
            SubmitBar(controller, item)
        }

        AnimatedVisibility(
            visible = feedback != null,
            enter = fadeIn(tween(Motion.StandardMillis)) +
                expandVertically(tween(Motion.EmphasisMillis)) +
                slideInVertically(tween(Motion.EmphasisMillis)) { it / 10 },
            exit = fadeOut(tween(Motion.QuickMillis)) + shrinkVertically(tween(Motion.StandardMillis)),
        ) {
            Column {
                FeedbackPanel(controller)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(
                        text = if (session.isLastQuestion) "查看总结" else "下一题",
                        onClick = controller::next,
                        leading = null,
                    )
                    GhostButton("结束本次", onClick = controller::finish)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 题干

@Composable
private fun QuestionCard(item: SessionItem, index: Int, total: Int, controller: AppController) {
    val question = item.question
    Panel(padding = 20.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(question.type.label, Palette.Accent, Palette.AccentSoft)
            // 本场选了某语言、这题却没有译文：必须标出来，不能让人以为看到的是译文
            if (controller.practice.currentQuestionShowsOriginal) {
                Spacer(Modifier.width(6.dp))
                Pill("原文", Palette.Pending, Palette.PendingSoft)
            }
            Spacer(Modifier.width(8.dp))
            if (question.tags.isNotEmpty()) {
                Text(
                    question.tags.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Faint,
                )
            }
            Spacer(Modifier.weight(1f))
            GraphLink(controller)
            AiLink(controller)
            Spacer(Modifier.width(6.dp))
            ToolLink(controller)
            Spacer(Modifier.width(6.dp))
            NoteLink(controller, question.questionId)
            Spacer(Modifier.width(10.dp))
            Text(
                "${index + 1} / $total",
                style = MaterialTheme.typography.labelMedium,
                color = Palette.Faint,
            )
        }
        Spacer(Modifier.height(12.dp))
        RichText(
            text = question.promptMarkdown,
            style = MaterialTheme.typography.titleLarge,
            color = Palette.Ink,
        )
        QuestionFigure(
            question = question,
            bankId = controller.practice.session?.bankId,
            loadMedia = controller::loadMedia,
            modifier = Modifier.padding(top = 12.dp),
        )
        // 出处放在题面底下：作答之前就该知道这道题哪来的
        controller.practice.currentBank?.provenance?.let { provenance ->
            ProvenanceLine(
                question = question,
                provenance = provenance,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/**
 * 题目笔记入口：已有就显示"笔记"并直接打开那一条，没有就显示"写笔记"。
 * 入口放在题号旁边而不是页尾 —— 想法总是发生在读题的那一刻。
 */
@Composable
private fun NoteLink(controller: AppController, questionId: String) {
    val bankId = controller.practice.session?.bankId
    val existing = bankId?.let { controller.notes.forQuestion(it, questionId) }
    val source = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .hoverable(source)
            .clickable(
                interactionSource = source,
                indication = null,
                onClick = {
                    if (existing != null) controller.openNoteEditor(existing.noteId)
                    else controller.openNoteForCurrentQuestion()
                },
            )
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("✎", style = MaterialTheme.typography.labelLarge, color = Palette.Accent)
        Spacer(Modifier.width(4.dp))
        Text(
            if (existing != null) "笔记" else "写笔记",
            style = MaterialTheme.typography.labelMedium,
            color = Palette.Accent,
        )
    }
}

/**
 * 图谱入口：看这道题连出去的关系，也在这里新连一条。
 *
 * 只在**这道题真的有关系**时才显示计数；0 条时写"关联"，
 * 不写"关联 0" —— 那看起来像个坏掉的计数器。
 */
@Composable
private fun GraphLink(controller: AppController) {
    val session = controller.practice.session
    val question = session?.current?.question
    val ref = if (session != null && question != null) QuestionRef(session.bankId, question.questionId) else null
    val count = ref?.let { controller.graph.edgeCountFor(it) } ?: 0
    val tint = when {
        controller.practice.blockedHardDependencies > 0 -> Palette.Wrong
        count > 0 -> Palette.Accent
        else -> Palette.Muted
    }
    val source = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .hoverable(source)
            .clickable(interactionSource = source, indication = null) {
                controller.openGraphForCurrentQuestion()
            }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⇄", style = MaterialTheme.typography.labelLarge, color = tint)
        Spacer(Modifier.width(4.dp))
        Text(
            if (count > 0) "关联 $count" else "关联",
            style = MaterialTheme.typography.labelMedium,
            color = tint,
        )
    }
}

/**
 * 工具入口。做题时算一个数、换一个单位是正常需求，藏着不给才是奇怪的事。
 *
 * 打开即记下"本题用过辅助工具"，随作答写进历史 —— 记录是真的，不做事后猜测。
 */
@Composable
private fun ToolLink(controller: AppController) {
    val used = controller.practice.usedToolsForCurrentQuestion
    val source = remember { MutableInteractionSource() }
    val tint = if (used) Palette.Pending else Palette.Muted
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .hoverable(source)
            .clickable(
                interactionSource = source,
                indication = null,
                onClick = { controller.openToolbox() },
            )
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("∑", style = MaterialTheme.typography.labelLarge, color = tint)
        Spacer(Modifier.width(4.dp))
        Text(
            if (used) "工具·已用" else "工具",
            style = MaterialTheme.typography.labelMedium,
            color = tint,
        )
    }
}

/**
 * AI 讲解入口。
 *
 * 平台没有接入联网能力时**不显示** —— 一个点了永远弹"不支持"的按钮，不如没有。
 * 没配置时点它直接跳到配置页，而不是先报一句"还没有配置"再让用户自己找。
 */
@Composable
private fun AiLink(controller: AppController) {
    if (!controller.ai.available) return
    val record = controller.ai.currentExplanation
    val configured = controller.ai.config.configured
    val tint = when {
        controller.ai.busy -> Palette.Pending
        record?.state == AiRecordState.APPROVED -> Palette.Correct
        record?.hasText == true -> Palette.Accent
        configured -> Palette.Muted
        else -> Palette.Faint
    }
    val source = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .hoverable(source)
            .clickable(interactionSource = source, indication = null) {
                when {
                    !configured -> controller.openAi()
                    record?.hasText == true -> controller.ai.togglePanel()
                    else -> controller.ai.generate()
                }
            }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("✦", style = MaterialTheme.typography.labelLarge, color = tint)
        Spacer(Modifier.width(4.dp))
        Text(
            when {
                controller.ai.busy -> "生成中"
                record?.hasText == true -> "讲解"
                else -> "AI 讲解"
            },
            style = MaterialTheme.typography.labelMedium,
            color = tint,
        )
    }
}

/**
 * 讲解面板。
 *
 * 三条信息必须同时在场：这是 AI 写的、它是什么状态、这次花了多少。
 * 只写"AI 讲解"而不写状态，等于让人把"模型觉得"当成"书上有"。
 */
@Composable
private fun AiExplanationPanel(controller: AppController) {
    if (!controller.ai.panelOpen) return
    val record = controller.ai.currentExplanation
    Panel(background = Palette.PanelMuted, borderColor = Palette.AccentLine, padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("✦", style = MaterialTheme.typography.labelLarge, color = Palette.Accent)
            Spacer(Modifier.width(6.dp))
            Text("AI 讲解", style = MaterialTheme.typography.titleSmall, color = Palette.Ink)
            if (record != null) {
                Spacer(Modifier.width(8.dp))
                Pill(record.state.label, aiTint(record.state), aiSoft(record.state))
            }
            Spacer(Modifier.weight(1f))
            GhostButton("收起", onClick = controller.ai::togglePanel)
        }
        Spacer(Modifier.height(10.dp))

        when {
            controller.ai.busy -> Text(
                "正在请求 ${controller.ai.config.model.ifBlank { "你配置的服务" }}… 这一次会计入今天的用量。",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Muted,
            )

            record == null -> Column {
                Text(
                    "这道题还没有讲解。生成时会发送题干、选项、正确答案与你的作答。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.Muted,
                )
                Spacer(Modifier.height(10.dp))
                PrimaryButton("生成讲解", onClick = { controller.ai.generate() })
            }

            else -> Column {
                if (record.state == AiRecordState.FAILED && record.error.isNotBlank()) {
                    Text(record.error, style = MaterialTheme.typography.bodyMedium, color = Palette.Wrong)
                    if (record.hasText) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "下面是上一次生成的内容，没有被这次失败删掉。",
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.Faint,
                        )
                    }
                }
                if (record.hasText) {
                    RichText(
                        text = record.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.Ink,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        buildString {
                            append("由 ").append(record.model.ifBlank { "模型" }).append(" 生成 · ")
                            append(record.state.label)
                            if (record.promptTokens + record.completionTokens > 0) {
                                append(" · ").append(record.promptTokens + record.completionTokens).append(" tokens")
                            }
                            append("。结构校验只保证文本能正常展示，不保证讲得对 —— 请对照教材判断。")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.Faint,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (record.hasText) {
                        SecondaryButton(
                            text = if (record.state == AiRecordState.APPROVED) "已标记有用" else "有用",
                            onClick = { controller.ai.reviewCurrent(AiRecordState.APPROVED) },
                            enabled = record.state != AiRecordState.APPROVED,
                        )
                        GhostButton(
                            text = if (record.state == AiRecordState.REJECTED) "已弃用" else "没用",
                            onClick = { controller.ai.reviewCurrent(AiRecordState.REJECTED) },
                        )
                        GhostButton("重新生成", onClick = { controller.ai.generate(force = true) })
                    } else {
                        PrimaryButton("重新生成", onClick = { controller.ai.generate(force = true) })
                    }
                }
            }
        }
    }
}

private fun aiTint(state: AiRecordState): Color = when (state) {
    AiRecordState.APPROVED -> Palette.Correct
    AiRecordState.REJECTED, AiRecordState.FAILED -> Palette.Wrong
    AiRecordState.NEEDS_REVIEW -> Palette.Pending
    else -> Palette.Accent
}

private fun aiSoft(state: AiRecordState): Color = when (state) {
    AiRecordState.APPROVED -> Palette.CorrectSoft
    AiRecordState.REJECTED, AiRecordState.FAILED -> Palette.WrongSoft
    AiRecordState.NEEDS_REVIEW -> Palette.PendingSoft
    else -> Palette.AccentSoft
}

// ------------------------------------------------------------------ 作答区

@Composable
private fun AnswerArea(item: SessionItem, controller: AppController, revealed: Boolean) {
    val question = item.question
    val detail = controller.practice.feedback?.evaluation?.detail
    val choiceDetail = detail as? EvaluationDetail.Choice

    if (question.type == QuestionType.SHORT_ANSWER) {
        val isKeywords = question.grading is GradingSpec.Keywords
        OutlinedTextField(
            value = controller.practice.textAnswer,
            onValueChange = controller.practice::updateTextAnswer,
            enabled = !revealed,
            label = { Text(if (isKeywords) "写出你的回答" else "默写标准答案") },
            placeholder = {
                Text(
                    if (isKeywords) "按知识点作答，系统按关键词规则判定" 
                    else "逐字符比较：标点、空格、换行都参与判定",
                )
            },
            modifier = Modifier.fillMaxWidth().height(150.dp),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Palette.Accent,
                unfocusedBorderColor = Palette.PanelBorderStrong,
                focusedContainerColor = Palette.Panel,
                unfocusedContainerColor = Palette.Panel,
                disabledContainerColor = Palette.PanelMuted,
                disabledBorderColor = Palette.PanelBorder,
                cursorColor = Palette.Accent,
                focusedLabelColor = Palette.Accent,
                unfocusedLabelColor = Palette.Muted,
            ),
        )
        return
    }

    if (question.type == QuestionType.ORDERING) {
        OrderingArea(item, controller, revealed)
        return
    }

    Column {
        item.orderedOptions().forEach { option ->
            OptionRow(
                option = option,
                label = item.labelOf(option.optionId),
                multiple = question.type == QuestionType.MULTIPLE_CHOICE,
                selected = controller.practice.selectedOptionIds.contains(option.optionId),
                expected = choiceDetail?.expected?.contains(option.optionId) == true,
                revealed = revealed,
                enabled = !revealed,
                onClick = { controller.practice.toggleOption(option.optionId) },
            )
            Spacer(Modifier.height(9.dp))
        }
    }
}

/** 语义重组作答：点击下方片段按次序组入答案，点击答案里的片段移除。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OrderingArea(item: SessionItem, controller: AppController, revealed: Boolean) {
    val ordered = controller.practice.orderedOptionIds
    val fragments = item.orderedOptions()

    SectionTitle("你的排列（点击片段移除）")
    Spacer(Modifier.height(8.dp))
    val answerShape = RoundedCornerShape(14.dp)
    if (ordered.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(answerShape)
                .background(Palette.PanelMuted)
                .border(1.dp, Palette.PanelBorder, answerShape)
                .padding(horizontal = 14.dp, vertical = 16.dp),
        ) {
            Text(
                "依次点击下方片段，组成你认为正确的次序",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Faint,
            )
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            ordered.forEachIndexed { index, optionId ->
                val fragment = fragments.firstOrNull { it.optionId == optionId } ?: return@forEachIndexed
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(answerShape)
                        .background(Palette.AccentSoft)
                        .border(1.dp, Palette.Accent, answerShape)
                        .toggleable(
                            value = true,
                            enabled = !revealed,
                            role = Role.Button,
                            onValueChange = { controller.practice.removeFragmentAt(index) },
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LetterBadge("${index + 1}", selected = true, multiple = true, revealed = revealed)
                    Spacer(Modifier.width(12.dp))
                    RichText(
                        text = fragment.contentMarkdown,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Palette.Ink,
                        modifier = Modifier.weight(1f),
                    )
                    if (!revealed) {
                        Text("移除", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(14.dp))
    SectionTitle("片段")
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        fragments.forEach { fragment ->
            val used = fragment.optionId in ordered
            val bg = animatedTint(if (used) Palette.PanelMuted else Palette.Panel)
            val border = animatedTint(if (used) Palette.PanelBorder else Palette.Accent)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(bg)
                    .border(1.dp, border, RoundedCornerShape(10.dp))
                    .toggleable(
                        value = used,
                        enabled = !revealed && !used,
                        role = Role.Button,
                        onValueChange = { controller.practice.appendFragment(fragment.optionId) },
                    )
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                RichText(
                    text = fragment.contentMarkdown,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (used) Palette.Faint else Palette.Ink,
                )
            }
        }
    }
}

/**
 * 一个选项。
 *
 * 选择题的身份是 optionId，A/B/C/D 只是当前显示顺序 —— 所以字母徽标只是"位置标签"，
 * 选中态用填充色表达，判题后用左右两侧的 ✓ / ✕ 表达对错，三者互不混用。
 */
@Composable
private fun OptionRow(
    option: QuestionOption,
    label: String,
    multiple: Boolean,
    selected: Boolean,
    expected: Boolean,
    revealed: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val isCorrect = revealed && expected
    val isWrong = revealed && selected && !expected

    val targetBackground = when {
        isCorrect -> Palette.CorrectSoft
        isWrong -> Palette.WrongSoft
        selected -> Palette.AccentSoft
        else -> Palette.Panel
    }
    val targetBorder = when {
        isCorrect -> Palette.Correct
        isWrong -> Palette.Wrong
        selected -> Palette.Accent
        else -> Palette.PanelBorder
    }
    val borderWidth by animateDpAsState(
        targetValue = if (isCorrect || isWrong || selected) 1.5.dp else 1.dp,
        animationSpec = tween(Motion.StandardMillis),
        label = "option-border",
    )

    val background = animatedTint(targetBackground)
    val borderColor = animatedTint(targetBorder)
    val shape = RoundedCornerShape(14.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background)
            .border(borderWidth, borderColor, shape)
            .hoverable(source, enabled = enabled)
            .toggleable(
                value = selected,
                enabled = enabled,
                role = Role.Checkbox,
                interactionSource = source,
                indication = null,
                onValueChange = { onClick() },
            )
            .pressScale(source, enabled)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LetterBadge(label, selected = selected || isCorrect, multiple = multiple, revealed = revealed)
        Spacer(Modifier.width(12.dp))
        RichText(
            text = option.contentMarkdown,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isWrong) Palette.Wrong else Palette.Ink,
            modifier = Modifier.weight(1f),
        )
        if (revealed) {
            Spacer(Modifier.width(10.dp))
            when {
                isCorrect -> MarkSymbol(text = "✓", color = Palette.Correct)
                isWrong -> MarkSymbol(text = "✕", color = Palette.Wrong)
                else -> Unit
            }
        }
    }
}

@Composable
private fun LetterBadge(label: String, selected: Boolean, multiple: Boolean, revealed: Boolean) {
    val bg = animatedTint(if (selected) Palette.Accent else Palette.NeutralSoft)
    val fg = animatedTint(if (selected) Palette.OnAccent else Palette.Muted)
    val shape = if (multiple) RoundedCornerShape(8.dp) else CircleShape
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(shape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = fg,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 判题标记弹出：从 0.4 放大到 1，避免"突然出现一个符号"的生硬感。 */
@Composable
private fun MarkSymbol(text: String, color: Color) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, Motion.PopSpring)
    }
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = color,
        modifier = Modifier.graphicsLayer {
            scaleX = 0.4f + 0.6f * progress.value
            scaleY = 0.4f + 0.6f * progress.value
            alpha = progress.value
        },
    )
}

// ------------------------------------------------------------------ 提交栏

@Composable
private fun SubmitBar(controller: AppController, item: SessionItem) {
    val question = item.question
    val hasAnswer = if (question.type == QuestionType.SHORT_ANSWER) {
        controller.practice.textAnswer.isNotBlank()
    } else {
        controller.practice.selectedOptionIds.isNotEmpty()
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    when (question.type) {
                        QuestionType.MULTIPLE_CHOICE ->
                            if (controller.practice.selectedOptionIds.isEmpty()) "已选 0 项 · 可多选，选完手动提交"
                            else "已选 ${controller.practice.selectedOptionIds.size} 项 · 完全匹配才算正确"

                        QuestionType.SHORT_ANSWER -> "逐字符比较，标点、空格、换行都参与判定"
                        else -> if (controller.practice.selectedOptionIds.isEmpty()) "选择一个选项后提交" else "已选择，可以提交"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasAnswer) Palette.Muted else Palette.Faint,
                )
            }
            Spacer(Modifier.width(12.dp))
            PrimaryButton("提交作答", onClick = controller.practice::submit, enabled = hasAnswer)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            GhostButton("放弃本题", onClick = controller.practice::abandon)
            GhostButton("结束本次", onClick = controller::finish)
        }
    }
}

// ------------------------------------------------------------------ 反馈

@Composable
private fun FavoriteToggle(active: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val bg = animatedTint(if (active) Palette.PendingSoft else Palette.Panel)
    val borderColor = animatedTint(if (active) Palette.Pending else Palette.PanelBorderStrong)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .hoverable(source)
            .toggleable(
                value = active,
                role = Role.Button,
                interactionSource = source,
                indication = null,
                onValueChange = { onClick() },
            )
            .pressScale(source)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (active) "★" else "☆", style = MaterialTheme.typography.labelLarge, color = Palette.Pending)
        Spacer(Modifier.width(5.dp))
        Text(
            if (active) "已收藏" else "收藏",
            style = MaterialTheme.typography.labelLarge,
            color = if (active) Palette.Pending else Palette.Muted,
        )
    }
}

@Composable
private fun FeedbackPanel(controller: AppController) {
    val feedback = controller.practice.feedback ?: return
    val evaluation = feedback.evaluation
    val item = feedback.session.current ?: return
    val question = item.question
    val tint = verdictColor(evaluation.verdict)

    Panel(accent = tint) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            VerdictBadge(evaluation.verdict)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    verdictHeadline(evaluation.verdict),
                    style = MaterialTheme.typography.titleMedium,
                    color = tint,
                )
                Text(
                    "判题策略：${evaluation.strategyId} · 用时 ${formatDuration(feedback.attempt.elapsedMillis)}" +
                        if (feedback.attempt.usedTools) " · 本题用过辅助工具" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Muted,
                )
                if (controller.practice.blockedHardDependencies > 0) {
                    Text(
                        "本题有 ${controller.practice.blockedHardDependencies} 条判题硬依赖缺失，相关依据已暂停",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.Wrong,
                    )
                }
            }
        }

        when (val detail = evaluation.detail) {
            is EvaluationDetail.Choice -> {
                Spacer(Modifier.height(12.dp))
                // 显示顺序（A/B/C）才是用户读题时看到的身份，排序也按它来，
                // 直接按 optionId 排序会得到 "C、B、A" 这种看起来错乱的结果。
                val missingLabels = detail.missing.map(item::labelOf).sorted()
                val extraLabels = detail.extra.map(item::labelOf).sorted()
                val expectedLabels = detail.expected.map(item::labelOf).sorted()
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (missingLabels.isNotEmpty()) {
                        Pill("漏选 " + missingLabels.joinToString("、"), Palette.Wrong, Palette.WrongSoft)
                    }
                    if (extraLabels.isNotEmpty()) {
                        Pill("误选 " + extraLabels.joinToString("、"), Palette.Wrong, Palette.WrongSoft)
                    }
                    Pill("正确 " + expectedLabels.joinToString("、"), Palette.Correct, Palette.CorrectSoft)
                }
            }

            is EvaluationDetail.TextDiff -> {
                Spacer(Modifier.height(14.dp))
                SectionTitle("标准答案")
                Spacer(Modifier.height(6.dp))
                DiffText(detail.segments, showDeletions = true)
                Spacer(Modifier.height(10.dp))
                SectionTitle("你的作答")
                Spacer(Modifier.height(6.dp))
                DiffText(detail.segments, showDeletions = false)
                if (detail.notes.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "差异定位：" + detail.notes.joinToString("；"),
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.Muted,
                    )
                }
            }

            is EvaluationDetail.Keywords -> {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (detail.present.isNotEmpty()) {
                        Pill("已涉及 " + detail.present.joinToString("、"), Palette.Correct, Palette.CorrectSoft)
                    }
                    if (detail.missing.isNotEmpty()) {
                        Pill("未涉及 " + detail.missing.joinToString("、"), Palette.Wrong, Palette.WrongSoft)
                    }
                    if (detail.forbiddenHit.isNotEmpty()) {
                        Pill("不应出现 " + detail.forbiddenHit.joinToString("、"), Palette.Wrong, Palette.WrongSoft)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "默知识点按关键词规则判定：答出全部要点为正确，答出部分为部分正确；出现禁用内容直接判错。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Muted,
                )
            }

            is EvaluationDetail.Ordering -> {
                Spacer(Modifier.height(12.dp))
                SectionTitle("标准次序")
                Spacer(Modifier.height(6.dp))
                detail.expected.forEachIndexed { i, id ->
                    val content = item.question.option(id)?.contentMarkdown ?: return@forEachIndexed
                    Row {
                        Text(
                            "${i + 1}. ${item.labelOf(id)}.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Palette.Muted,
                            modifier = Modifier.width(44.dp),
                        )
                        RichText(
                            text = content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Palette.Ink,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (detail.sameSet) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "片段都选对了，但次序不符合任何认可的等价次序。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.Wrong,
                    )
                } else {
                    val missing = detail.missing.map(item::labelOf).sorted()
                    val extra = detail.extra.map(item::labelOf).sorted()
                    if (missing.isNotEmpty() || extra.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (missing.isNotEmpty()) Pill("缺少 " + missing.joinToString("、"), Palette.Wrong, Palette.WrongSoft)
                            if (extra.isNotEmpty()) Pill("混入 " + extra.joinToString("、"), Palette.Wrong, Palette.WrongSoft)
                        }
                    }
                }
            }

            is EvaluationDetail.Pending -> {
                Spacer(Modifier.height(10.dp))
                Text(detail.reason, style = MaterialTheme.typography.bodyMedium, color = Palette.Pending)
            }
        }

        if (!question.explanationMarkdown.isNullOrBlank()) {
            Spacer(Modifier.height(14.dp))
            Divider()
            Spacer(Modifier.height(12.dp))
            SectionTitle("解析")
            Spacer(Modifier.height(6.dp))
            RichText(question.explanationMarkdown, style = MaterialTheme.typography.bodyLarge, color = Palette.Ink)
        }

        if (evaluation.verdict == Verdict.ABANDONED) {
            Spacer(Modifier.height(8.dp))
            Text(
                "本题记为放弃：不计入正确率分母，也不进入错题本。",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Muted,
            )
        }
    }
}

@Composable
private fun DiffText(segments: List<DiffSegment>, showDeletions: Boolean) {
    Text(
        buildAnnotatedString {
            segments.forEach { segment ->
                when (segment.kind) {
                    SegmentKind.SAME -> append(segment.text)
                    SegmentKind.DELETED ->
                        if (showDeletions) {
                            withStyle(SpanStyle(color = Palette.Wrong, background = Palette.WrongSoft)) {
                                append(segment.text)
                            }
                        }

                    SegmentKind.INSERTED ->
                        if (!showDeletions) {
                            withStyle(SpanStyle(color = Palette.Correct, background = Palette.CorrectSoft)) {
                                append(segment.text)
                            }
                        }
                }
            }
        },
        style = MaterialTheme.typography.bodyLarge,
        fontFamily = FontFamily.Monospace,
    )
}

@Composable
private fun VerdictBadge(verdict: Verdict) {
    val scale = remember { Animatable(0.6f) }
    LaunchedEffect(Unit) {
        scale.animateTo(1f, Motion.PopSpring)
    }
    val glyph = when (verdict) {
        Verdict.CORRECT -> "✓"
        Verdict.PARTIALLY_CORRECT -> "±"
        Verdict.INCORRECT -> "✕"
        Verdict.PENDING -> "?"
        Verdict.ABANDONED -> "—"
    }
    Box(
        modifier = Modifier
            .size(44.dp)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .clip(CircleShape)
            .background(verdictSoft(verdict)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            style = MaterialTheme.typography.titleLarge,
            color = verdictColor(verdict),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun verdictHeadline(verdict: Verdict): String = when (verdict) {
    Verdict.CORRECT -> "回答正确"
    Verdict.PARTIALLY_CORRECT -> "部分正确"
    Verdict.INCORRECT -> "回答错误"
    Verdict.PENDING -> "无法可靠判定"
    Verdict.ABANDONED -> "本题已放弃"
}
