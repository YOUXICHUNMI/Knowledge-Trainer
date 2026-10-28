package morefish.knowledge.trainer.presentation.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import morefish.knowledge.trainer.domain.graph.DependencyKind
import morefish.knowledge.trainer.domain.graph.GraphRelation
import morefish.knowledge.trainer.domain.graph.QuestionRef
import morefish.knowledge.trainer.domain.model.Severity
import morefish.knowledge.trainer.presentation.AppController
import morefish.knowledge.trainer.presentation.graph.GraphPickSide
import morefish.knowledge.trainer.presentation.richtext.Markdown
import morefish.knowledge.trainer.presentation.richtext.RichText
import morefish.knowledge.trainer.presentation.theme.Palette
import morefish.knowledge.trainer.presentation.ui.GhostButton
import morefish.knowledge.trainer.presentation.ui.KtChip
import morefish.knowledge.trainer.presentation.ui.Panel
import morefish.knowledge.trainer.presentation.ui.Pill
import morefish.knowledge.trainer.presentation.ui.PrimaryButton
import morefish.knowledge.trainer.presentation.ui.ScreenScaffold
import morefish.knowledge.trainer.presentation.ui.SecondaryButton
import morefish.knowledge.trainer.presentation.ui.SectionTitle

/**
 * 关系编辑器。
 *
 * 两个设计点：
 * 1. **起点与目标分开选**，方向写在标题上。把"谁依赖谁"藏进一个下拉里，
 *    用户点完保存才发现连反了。
 * 2. **快照只在保存时记录**，编辑器里显示的是当前题库内容 ——
 *    编辑时看到旧摘录，会让人以为题库已经变了。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GraphEditorScreen(controller: AppController) {
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

    ScreenScaffold(
        title = if (controller.graph.editing) "编辑关系" else "建立关系",
        subtitle = "关系只存在你亲手连的地方",
        overline = "个人图谱",
        onBack = controller::closeGraphEditor,
    ) {
        Panel {
            SectionTitle("起点", "左边这道题")
            Spacer(Modifier.height(8.dp))
            EndpointPicker(
                controller = controller,
                side = GraphPickSide.SOURCE,
                ref = controller.graph.source,
                hint = "从哪道题出发",
                fieldShape = fieldShape,
                fieldColors = fieldColors,
            )

            Spacer(Modifier.height(16.dp))
            SectionTitle("关系", controller.graph.draftRelation.label)
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GraphRelation.entries.forEach { relation ->
                    KtChip(
                        label = "${relation.glyph} ${relation.label}",
                        selected = controller.graph.draftRelation == relation,
                        onClick = { controller.graph.selectRelation(relation) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                controller.graph.draftRelation.hint,
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Muted,
            )

            Spacer(Modifier.height(14.dp))
            SectionTitle("对判题的影响", controller.graph.draftDependency.label)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KtChip(
                    label = DependencyKind.NAVIGATION.label,
                    selected = controller.graph.draftDependency == DependencyKind.NAVIGATION,
                    onClick = { controller.graph.selectDependency(DependencyKind.NAVIGATION) },
                )
                KtChip(
                    label = DependencyKind.HARD.label,
                    selected = controller.graph.draftDependency == DependencyKind.HARD,
                    enabled = controller.graph.hardAllowed,
                    onClick = { controller.graph.selectDependency(DependencyKind.HARD) },
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (controller.graph.hardAllowed) {
                    "硬依赖缺失时，本题会明确标注「依据暂停」，不会拿快照凑合判题。"
                } else {
                    "「${controller.graph.draftRelation.label}」是对称关系，说不出谁依赖谁，只能做导航。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (controller.graph.hardAllowed) Palette.Muted else Palette.Pending,
            )

            Spacer(Modifier.height(16.dp))
            SectionTitle("目标", "右边这道题")
            Spacer(Modifier.height(8.dp))
            EndpointPicker(
                controller = controller,
                side = GraphPickSide.TARGET,
                ref = controller.graph.target,
                hint = when (controller.graph.draftRelation) {
                    GraphRelation.PREREQUISITE -> "前置知识：先学它"
                    GraphRelation.EXPLAINS -> "判题依据来自它"
                    else -> "要连过去的另一道题"
                },
                fieldShape = fieldShape,
                fieldColors = fieldColors,
            )
        }

        Spacer(Modifier.height(12.dp))
        Panel {
            SectionTitle("备注", "${controller.graph.draftNote.length} 字")
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = controller.graph.draftNote,
                onValueChange = controller.graph::updateNote,
                placeholder = { Text("为什么把这两道题连起来？（支持 Markdown 与 $...$ 公式）") },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
                shape = fieldShape,
                colors = fieldColors,
            )
            if (controller.graph.draftNote.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Panel(background = Palette.PanelMuted, padding = 12.dp) {
                    RichText(
                        text = controller.graph.draftNote,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.Ink,
                    )
                }
            }
        }

        if (controller.graph.issues.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Panel(background = Palette.WrongSoft, borderColor = Palette.Wrong) {
                controller.graph.issues.forEach { issue ->
                    Text(
                        (if (issue.severity == Severity.ERROR) "· " else "· 提醒：") + issue.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (issue.severity == Severity.ERROR) Palette.Wrong else Palette.Pending,
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(
                text = when {
                    controller.graph.busy -> "保存中…"
                    controller.graph.editing -> "保存修改"
                    else -> "保存关系"
                },
                onClick = controller::saveGraphEdge,
                enabled = !controller.graph.busy,
            )
            GhostButton("返回", onClick = controller::closeGraphEditor)
            if (controller.graph.editing) {
                val edgeId = controller.graph.editingEdgeId
                if (edgeId != null) {
                    GhostButton(
                        "删除",
                        onClick = { controller.deleteGraphEdge(edgeId) },
                        color = Palette.Wrong,
                    )
                }
            }
        }
    }
}

@Composable
private fun EndpointPicker(
    controller: AppController,
    side: GraphPickSide,
    ref: QuestionRef?,
    hint: String,
    fieldShape: RoundedCornerShape,
    fieldColors: androidx.compose.material3.TextFieldColors,
) {
    val picking = controller.graph.pickSide == side
    val card = controller.graph.cardOf(ref)

    if (picking) {
        OutlinedTextField(
            value = controller.graph.query,
            onValueChange = controller.graph::updateQuery,
            placeholder = { Text("输入题干关键词（跨全部题库搜索）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = fieldShape,
            colors = fieldColors,
        )
        Spacer(Modifier.height(8.dp))

        if (controller.graph.hits.isEmpty()) {
            Text(
                if (controller.graph.query.isBlank()) {
                    "搜到的是真实存在的题；软件不会按标题相似的题库猜一个。"
                } else {
                    "没有匹配的题目。换个关键词，或先导入对应题库。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Faint,
            )
        } else {
            controller.graph.hits.forEach { hit ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { controller.graph.pickHit(hit) }
                        .padding(vertical = 8.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            hit.bankTitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.Faint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            Markdown.plainExcerpt(hit.promptMarkdown, 60),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Palette.Ink,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Pill(hit.type.label, Palette.AccentInk, Palette.AccentSoft)
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        GhostButton("取消选择", onClick = controller.graph::cancelPick)
        return
    }

    if (ref == null) {
        Text(hint, style = MaterialTheme.typography.bodySmall, color = Palette.Faint)
        Spacer(Modifier.height(8.dp))
        SecondaryButton("搜索并选择", onClick = { controller.graph.beginPick(side) }, leading = "⌕")
        return
    }

    Panel(background = Palette.PanelMuted, padding = 12.dp) {
        Text(
            card?.bankTitle ?: ref.bankId,
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Faint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            Markdown.plainExcerpt(card?.promptMarkdown.orEmpty(), 110).ifBlank { "（这道题的内容暂时读不到）" },
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.Ink,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "${ref.questionId} · 内容版本 ${card?.contentRevision ?: "未知"}",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Faint,
        )
    }
    Spacer(Modifier.height(6.dp))
    GhostButton("换一道", onClick = { controller.graph.beginPick(side) })
}
