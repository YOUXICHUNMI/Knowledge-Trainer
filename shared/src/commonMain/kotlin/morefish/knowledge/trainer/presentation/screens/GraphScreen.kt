package morefish.knowledge.trainer.presentation.screens

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import morefish.knowledge.trainer.domain.graph.DependencyKind
import morefish.knowledge.trainer.domain.graph.GraphEdgeView
import morefish.knowledge.trainer.domain.graph.GraphRelation
import morefish.knowledge.trainer.domain.graph.RefResolution
import morefish.knowledge.trainer.domain.graph.RefStatus
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
import morefish.knowledge.trainer.presentation.ui.SectionTitle
import morefish.knowledge.trainer.presentation.ui.StatTile

/**
 * 个人知识图谱。
 *
 * 这一页的中心不是"炫一张网络图"，而是**处理断线**：
 * 哪条关系指向的题库已经不在本机、哪条关系的依据内容已经变了。
 * 所以列表按"有问题优先"排，缺失端点的快照一律标注来源。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GraphScreen(controller: AppController) {
    val view = controller.graph.view
    val edges = controller.graph.filteredEdges
    val focus = controller.graph.questionFilter

    ScreenScaffold(
        title = "知识图谱",
        subtitle = "手工建立的跨库关系 · 不推断、不自动连线",
        overline = "个人图谱",
        onBack = controller::closeGraph,
    ) {
        BannerHost(controller.banner, controller::dismissBanner)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                label = "关系",
                value = view.relationCount,
                modifier = Modifier.weight(1f),
                hint = "手工",
            )
            StatTile(
                label = "涉及题目",
                value = view.refs.size,
                modifier = Modifier.weight(1f),
                hint = "跨库",
            )
            StatTile(
                label = "缺失",
                value = view.problemRefCount,
                modifier = Modifier.weight(1f),
                hint = "待恢复",
                accent = if (view.problemRefCount == 0) Palette.Ink else Palette.Wrong,
            )
            StatTile(
                label = "依据暂停",
                value = view.blockedCount,
                modifier = Modifier.weight(1f),
                hint = "硬依赖",
                accent = if (view.blockedCount == 0) Palette.Ink else Palette.Pending,
            )
        }

        Spacer(Modifier.height(14.dp))

        Panel(background = Palette.PanelMuted) {
            Text(
                "图谱只存引用，不复制题目。题库暂时不在本机时，关系与建立时的摘录都会保留；" +
                    "但快照只用来认出「这是哪道题」，不会被当成判题依据。",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Muted,
            )
        }

        if (focus != null) {
            val card = controller.graph.cardOf(focus)
            Spacer(Modifier.height(12.dp))
            Panel(accent = Palette.Accent) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill("只看这道题", Palette.AccentInk, Palette.AccentSoft)
                    Spacer(Modifier.weight(1f))
                    GhostButton("看全部关系", onClick = controller.graph::clearFocus)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    card?.bankTitle ?: focus.bankId,
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Faint,
                )
                Text(
                    Markdown.plainExcerpt(card?.promptMarkdown.orEmpty(), 120).ifBlank { "（这道题的内容暂时读不到）" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.Ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionTitle("筛选", "可叠加")

        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            KtChip(
                label = "全部 ${view.relationCount}",
                selected = controller.graph.relationFilter == null,
                onClick = { controller.graph.filterRelation(null) },
            )
            GraphRelation.entries.forEach { relation ->
                KtChip(
                    label = "${relation.label} ${view.relationCount(relation)}",
                    selected = controller.graph.relationFilter == relation,
                    onClick = { controller.graph.filterRelation(relation) },
                )
            }
            KtChip(
                label = "只看问题 ${view.problemRefCount}",
                selected = controller.graph.problemsOnly,
                onClick = controller.graph::toggleProblemsOnly,
            )
        }

        Spacer(Modifier.height(16.dp))
        SectionTitle("关系", if (edges.isEmpty()) "暂无" else "${edges.size} 条")

        Spacer(Modifier.height(8.dp))
        if (edges.isEmpty()) {
            EmptyState(
                title = if (view.edges.isEmpty()) "还没有建立任何关系" else "当前筛选下没有关系",
                hint = if (view.edges.isEmpty()) {
                    "关系要自己连：在练习页点「关联」，或在下面新建一条。软件不会因为标题像就替你连。"
                } else {
                    "换一个关系类型，或关掉「只看问题」。"
                },
                glyph = "⇄",
                action = if (view.edges.isEmpty()) {
                    { PrimaryButton("新建关系", onClick = { controller.openGraphEditor() }) }
                } else {
                    null
                },
            )
        } else {
            edges.forEach { edgeView ->
                EdgeCard(edgeView, controller)
                Spacer(Modifier.height(10.dp))
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(
                "新建关系",
                onClick = { controller.openGraphEditor(source = focus) },
                leading = "＋",
            )
            GhostButton("返回", onClick = controller::closeGraph)
        }
    }
}

@Composable
private fun EdgeCard(view: GraphEdgeView, controller: AppController) {
    val edge = view.edge
    val relationColor = relationColor(edge.relation)
    val relationSoft = relationSoft(edge.relation)
    // 扩展包带来的关系在视图里与手建关系同权，只是不能在这里改：改包才是改它
    val packName = controller.extensionNameOf(edge.edgeId)

    Panel(accent = if (view.hasProblem) Palette.Pending else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill("${edge.relation.glyph} ${edge.relation.label}", relationColor, relationSoft)
            if (edge.dependency == DependencyKind.HARD) {
                Spacer(Modifier.width(8.dp))
                Pill("判题硬依赖", Palette.Pending, Palette.PendingSoft)
            }
            if (packName != null) {
                Spacer(Modifier.width(8.dp))
                Pill("扩展包", Palette.AccentInk, Palette.AccentSoft)
            }
            Spacer(Modifier.weight(1f))
            if (view.blocked) {
                Pill("依据暂停", Palette.Wrong, Palette.WrongSoft)
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Top) {
            EndpointColumn(view.source, modifier = Modifier.weight(1f))
            Text(
                edge.relation.glyph,
                style = MaterialTheme.typography.titleLarge,
                color = relationColor,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
            EndpointColumn(view.target, modifier = Modifier.weight(1f))
        }

        if (edge.noteMarkdown.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Panel(background = Palette.PanelMuted, padding = 12.dp) {
                Text(
                    Markdown.plainExcerpt(edge.noteMarkdown, 160),
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Muted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (view.blocked) {
            Spacer(Modifier.height(10.dp))
            Text(
                "右边这道题现在拿不到，所以这条依据不能用；本题不会假装它还成立。",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Wrong,
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (packName != null) "来自扩展包《$packName》· 要改请改包后重装" else edge.relation.hint,
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Faint,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (packName == null) {
                if (view.source.status == RefStatus.NEEDS_REVALIDATION ||
                    view.target.status == RefStatus.NEEDS_REVALIDATION
                ) {
                    GhostButton("复核确认", onClick = { controller.revalidateGraphEdge(edge.edgeId) })
                }
                GhostButton("编辑", onClick = { controller.openGraphEditor(edge.edgeId) })
                GhostButton("删除", onClick = { controller.deleteGraphEdge(edge.edgeId) }, color = Palette.Wrong)
            } else {
                // 扩展包的关系没有"复核"这回事：包自带版本号，重装新版就是复核
                GhostButton("看这个包", onClick = controller::openExtensions, color = Palette.AccentInk)
            }
        }
    }
}

@Composable
private fun EndpointColumn(resolution: RefResolution, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            resolution.displayBankTitle,
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Faint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            Markdown.plainExcerpt(resolution.displayPrompt, 90),
            style = MaterialTheme.typography.bodyMedium,
            color = if (resolution.status == RefStatus.PRESENT) Palette.Ink else Palette.Muted,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (resolution.status != RefStatus.PRESENT) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(
                    resolution.status.label,
                    statusColor(resolution.status),
                    statusSoft(resolution.status),
                )
                if (resolution.fromSnapshotOnly) {
                    Spacer(Modifier.width(6.dp))
                    Pill("快照", Palette.Muted, Palette.NeutralSoft)
                }
            }
        }
    }
}

/** 关系配色借用已有的四组语义色：不额外发明一套"图谱色"，界面才像同一套东西。 */
internal fun relationColor(relation: GraphRelation): Color = when (relation) {
    GraphRelation.RELATED -> Palette.AccentInk
    GraphRelation.PREREQUISITE -> Palette.Pending
    GraphRelation.EXPLAINS -> Palette.Correct
    GraphRelation.CONTRASTS -> Palette.Wrong
}

internal fun relationSoft(relation: GraphRelation): Color = when (relation) {
    GraphRelation.RELATED -> Palette.AccentSoft
    GraphRelation.PREREQUISITE -> Palette.PendingSoft
    GraphRelation.EXPLAINS -> Palette.CorrectSoft
    GraphRelation.CONTRASTS -> Palette.WrongSoft
}

internal fun statusColor(status: RefStatus): Color = when (status) {
    RefStatus.PRESENT -> Palette.Correct
    RefStatus.MISSING_BANK, RefStatus.MISSING_QUESTION -> Palette.Wrong
    RefStatus.NEEDS_REVALIDATION -> Palette.Pending
}

internal fun statusSoft(status: RefStatus): Color = when (status) {
    RefStatus.PRESENT -> Palette.CorrectSoft
    RefStatus.MISSING_BANK, RefStatus.MISSING_QUESTION -> Palette.WrongSoft
    RefStatus.NEEDS_REVALIDATION -> Palette.PendingSoft
}
