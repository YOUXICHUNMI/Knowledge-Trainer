package morefish.knowledge.trainer.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import morefish.knowledge.trainer.application.port.WallClock
import morefish.knowledge.trainer.domain.extension.ExtensionPack
import morefish.knowledge.trainer.domain.extension.ExtensionState
import morefish.knowledge.trainer.presentation.AppController
import morefish.knowledge.trainer.presentation.richtext.RichText
import morefish.knowledge.trainer.presentation.theme.Palette
import morefish.knowledge.trainer.presentation.ui.BannerHost
import morefish.knowledge.trainer.presentation.ui.EmptyState
import morefish.knowledge.trainer.presentation.ui.GhostButton
import morefish.knowledge.trainer.presentation.ui.Panel
import morefish.knowledge.trainer.presentation.ui.Pill
import morefish.knowledge.trainer.presentation.ui.PrimaryButton
import morefish.knowledge.trainer.presentation.ui.ScreenScaffold
import morefish.knowledge.trainer.presentation.ui.SectionTitle
import morefish.knowledge.trainer.presentation.ui.StatTile
import morefish.knowledge.trainer.presentation.ui.formatRelative

/**
 * 扩展包（`.qbext`）。
 *
 * 这一页要说清三件事：
 * 1. 包装在**哪个题库**上 —— 按 UUID 认，标题只是提示；
 * 2. 它现在**生效没有** —— 题库没装就是"等待题库"，关系不会提前显示；
 * 3. 它带来了什么 —— 关系进图谱，注释在这里读（只读，改包才是改它）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExtensionsScreen(controller: AppController) {
    val now = WallClock.System.nowMillis()
    val packs = controller.extensions

    ScreenScaffold(
        title = "扩展包",
        subtitle = "绑定某个题库的跨库关系与讲解 · 原题库只读",
        overline = "个人补充",
        onBack = controller::closeExtensions,
    ) {
        BannerHost(controller.banner, controller::dismissBanner)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                label = "已安装",
                value = packs.size,
                modifier = Modifier.weight(1f),
                hint = "个包",
            )
            StatTile(
                label = "生效中",
                value = controller.activeExtensions.size,
                modifier = Modifier.weight(1f),
                hint = "题库在",
                accent = if (controller.activeExtensions.isEmpty()) Palette.Ink else Palette.Correct,
            )
            StatTile(
                label = "等待题库",
                value = controller.pendingExtensions.size,
                modifier = Modifier.weight(1f),
                hint = "先到一步",
                accent = if (controller.pendingExtensions.isEmpty()) Palette.Ink else Palette.Pending,
            )
            StatTile(
                label = "关系",
                value = controller.extensionRelationCount,
                modifier = Modifier.weight(1f),
                hint = "跨库",
            )
        }

        Spacer(Modifier.height(14.dp))
        Panel(background = Palette.PanelMuted) {
            Text(
                "扩展包只往你本机添加两样东西：跨库关系与讲解注释。题目、答案、历史一条都不会被改。" +
                    "绑定认的是题库 UUID —— 标题改了也还是同一个库，同名但 UUID 不同的库则永远是另一个库。",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Muted,
            )
        }

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (controller.filePickerAvailable) {
                PrimaryButton(
                    text = if (controller.busy) "校验中…" else "选择 .qbext 文件",
                    onClick = controller::pickPackageFile,
                    enabled = !controller.busy,
                    leading = "＋",
                )
            } else {
                Text(
                    controller.filePickerUnavailableReason ?: "当前平台不能选择文件",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Pending,
                )
            }
            controller.pickedFileName?.let { name ->
                Spacer(Modifier.width(12.dp))
                Text("最近：$name", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "也可以直接把 .qbext 拖进窗口。包内清单会与逐文件哈希核对，错一条就整包拒绝。",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Faint,
        )

        Spacer(Modifier.height(18.dp))
        SectionTitle("已安装", if (packs.isEmpty()) "暂无" else "${packs.size} 个")

        Spacer(Modifier.height(8.dp))
        if (packs.isEmpty()) {
            EmptyState(
                title = "还没有装扩展包",
                hint = "扩展包由题库作者或社区提供，通常和题库一起发布。装上之后，它带的关系会直接出现在图谱里，" +
                    "注释在这里阅读。",
                glyph = "⊞",
            )
        } else {
            packs.forEach { pack ->
                ExtensionCard(pack, controller, now)
                Spacer(Modifier.height(10.dp))
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "移除一个包只会删掉它带来的关系与注释。想留下某条关系，先在图谱里手工重建一条再移除。",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Faint,
        )
    }
}

@Composable
private fun ExtensionCard(pack: ExtensionPack, controller: AppController, now: Long) {
    val state = controller.extensionStateOf(pack)
    val active = state == ExtensionState.ACTIVE
    val expanded = controller.expandedExtensionId == pack.packageId

    Panel(accent = if (active) null else Palette.Pending) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                pack.name,
                style = MaterialTheme.typography.titleMedium,
                color = Palette.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Pill("v${pack.version}", Palette.Muted, Palette.NeutralSoft)
            Spacer(Modifier.width(6.dp))
            Pill(
                state.label,
                if (active) Palette.Correct else Palette.Pending,
                if (active) Palette.CorrectSoft else Palette.PendingSoft,
            )
        }

        Spacer(Modifier.height(4.dp))
        Text(
            buildString {
                append(pack.author.ifBlank { "未知作者" })
                if (pack.license.isNotBlank()) append(" · ").append(pack.license)
                append(" · ").append(formatRelative(now, pack.importedAtMillis)).append("安装")
            },
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Faint,
        )

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill("绑定题库", Palette.Muted, Palette.NeutralSoft)
            Spacer(Modifier.width(8.dp))
            Text(
                controller.extensionBoundaryLabel(pack),
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            pack.binding.bankId,
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Faint,
        )

        if (!active) {
            Spacer(Modifier.height(8.dp))
            Text(
                "这个题库现在不在本机。包已经存好，题库装上就会自动生效 —— 在此之前，" +
                    "它带的关系不会出现在图谱里，也不会被当成依据。",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Pending,
            )
        }

        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("${pack.relationCount} 条关系", Palette.Accent, Palette.AccentSoft)
            Pill("覆盖 ${pack.coveredQuestionCount} 题", Palette.Accent, Palette.AccentSoft)
            Pill("${pack.annotationCount} 条注释", Palette.Muted, Palette.NeutralSoft)
        }

        if (pack.annotations.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                GhostButton(
                    if (expanded) "收起注释" else "阅读注释 ${pack.annotationCount}",
                    onClick = { controller.toggleExtensionExpanded(pack.packageId) },
                )
                Spacer(Modifier.weight(1f))
                GhostButton(
                    "移除扩展包",
                    onClick = { controller.removeExtension(pack.packageId) },
                    enabled = !controller.extensionBusy,
                    color = Palette.Wrong,
                )
            }
        } else {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "只带关系，没有注释",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Faint,
                    modifier = Modifier.weight(1f),
                )
                GhostButton(
                    "移除扩展包",
                    onClick = { controller.removeExtension(pack.packageId) },
                    enabled = !controller.extensionBusy,
                    color = Palette.Wrong,
                )
            }
        }

        if (expanded) {
            Spacer(Modifier.height(10.dp))
            pack.annotations.forEach { annotation ->
                Column(
                    modifier = Modifier
                        .padding(bottom = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            annotation.title.ifBlank { "（无标题）" },
                            style = MaterialTheme.typography.labelLarge,
                            color = Palette.Ink,
                        )
                        Spacer(Modifier.width(6.dp))
                        Pill(
                            annotation.questionId?.let { "题目 $it" } ?: "整库",
                            Palette.Muted,
                            Palette.NeutralSoft,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    RichText(
                        text = annotation.bodyMarkdown,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.Muted,
                    )
                }
            }
            Text(
                "扩展包里的内容只读：要改请在制包源目录改，再以新版本重新安装。",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Faint,
            )
        }
    }
}
