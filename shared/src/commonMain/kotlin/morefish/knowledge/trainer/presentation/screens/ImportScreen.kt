package morefish.knowledge.trainer.presentation.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import morefish.knowledge.trainer.application.port.ChoiceMapping
import morefish.knowledge.trainer.application.port.ImportPreview
import morefish.knowledge.trainer.presentation.AppController
import morefish.knowledge.trainer.presentation.Screen
import morefish.knowledge.trainer.presentation.theme.Palette
import morefish.knowledge.trainer.presentation.ui.BannerHost
import morefish.knowledge.trainer.presentation.ui.Divider
import morefish.knowledge.trainer.presentation.ui.GhostButton
import morefish.knowledge.trainer.presentation.ui.Motion
import morefish.knowledge.trainer.presentation.ui.Panel
import morefish.knowledge.trainer.presentation.ui.Pill
import morefish.knowledge.trainer.presentation.ui.PrimaryButton
import morefish.knowledge.trainer.presentation.ui.ScreenScaffold
import morefish.knowledge.trainer.presentation.ui.SecondaryButton
import morefish.knowledge.trainer.presentation.ui.SectionTitle
import morefish.knowledge.trainer.presentation.ui.StaggeredAppear

@Composable
fun ImportScreen(controller: AppController, sample: String) {
    ScreenScaffold(
        title = "导入题库",
        subtitle = ".qbank 包严格校验 · 粘贴 JSON 走宽松导入",
        overline = "新题库",
        onBack = controller::goHome,
    ) {
        BannerHost(controller.banner, controller::dismissBanner)

        StaggeredAppear(0) { QbankFilePanel(controller) }

        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Divider(modifier = Modifier.weight(1f))
            Text(
                "  或粘贴文本  ",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.Faint,
            )
            Divider(modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(18.dp))

        StaggeredAppear(1) {
            Column {
                OutlinedTextField(
                    value = controller.importTitle,
                    onValueChange = controller::updateImportTitle,
                    label = { Text("题库名称（可留空）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = fieldColors(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = controller.importText,
                    onValueChange = controller::updateImportText,
                    label = { Text("题库 JSON / NDJSON") },
                    modifier = Modifier.fillMaxWidth().height(240.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = fieldColors(),
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("解析预览", onClick = { controller.previewImport(ChoiceMapping.AUTO) })
                    SecondaryButton("载入示例", onClick = { controller.updateImportText(sample) })
                    GhostButton("跳过预览直接导入", onClick = { controller.confirmImport(ChoiceMapping.AUTO) })
                }
            }
        }

        controller.importError?.let { error ->
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill("失败", Palette.Wrong, Palette.WrongSoft)
                Spacer(Modifier.width(8.dp))
                Text(error, color = Palette.Wrong, style = MaterialTheme.typography.bodyMedium)
            }
        }

        AnimatedVisibility(
            visible = controller.importPreview != null,
            enter = fadeIn(tween(Motion.StandardMillis)) + expandVertically(tween(Motion.EmphasisMillis)),
            exit = fadeOut(tween(Motion.QuickMillis)) + shrinkVertically(tween(Motion.StandardMillis)),
        ) {
            Column {
                Spacer(Modifier.height(16.dp))
                controller.importPreview?.let { preview -> PreviewPanel(preview, controller) }
            }
        }

        Spacer(Modifier.height(26.dp))
        StaggeredAppear(2) { StandardEntryPanel(controller) }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Palette.Accent,
    unfocusedBorderColor = Palette.PanelBorderStrong,
    focusedContainerColor = Palette.Panel,
    unfocusedContainerColor = Palette.Panel,
    cursorColor = Palette.Accent,
    focusedLabelColor = Palette.Accent,
    unfocusedLabelColor = Palette.Muted,
)

/**
 * .qbank 入口。
 *
 * 用虚线框是刻意的：虚线在这个产品里只出现在"往里面放东西"的地方，
 * 其它卡片一律实线。当前没有实现拖拽，所以明确写"选择文件"，不给假承诺。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QbankFilePanel(controller: AppController) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Palette.Panel)
            .drawBehind {
                drawRoundRect(
                    color = Palette.PanelBorderStrong,
                    cornerRadius = CornerRadius(18.dp.toPx()),
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 7f)),
                    ),
                )
            }
            .padding(22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Palette.AccentSoft)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text("QBF", style = MaterialTheme.typography.labelLarge, color = Palette.Accent)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    ".qbank 题库包",
                    style = MaterialTheme.typography.titleMedium,
                    color = Palette.Ink,
                )
                Text(
                    if (controller.filePickerAvailable) {
                        "选择文件或直接把包拖进窗口；作者已在包内声明题型与答案"
                    } else {
                        "作者已声明题型与答案，推荐优先用包导入"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Muted,
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CheckLine("清单与逐文件 sha256")
            CheckLine("Schema 与语义校验")
            CheckLine("失败整包拒绝，不写半份")
        }

        Spacer(Modifier.height(16.dp))
        if (controller.filePickerAvailable) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton(
                    text = if (controller.busy) "校验中…" else "选择 .qbank / .qbext 文件",
                    onClick = controller::pickPackageFile,
                    enabled = !controller.busy,
                    leading = "⌘",
                )
                controller.pickedFileName?.let { name ->
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "最近：$name",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.Muted,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "选到 .qbext 会自动按扩展包处理（只加关系与注释，不改题库）。两者都按包内清单判定，不看扩展名。",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Faint,
            )
        } else {
            Text(
                controller.filePickerUnavailableReason ?: "当前平台不能选择文件",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Pending,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "可以改用桌面端导入，或把包内的 JSON 内容粘贴到下面 —— " +
                    "那会走宽松导入，失去哈希与跨语言校验。",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Muted,
            )
        }
    }
}

@Composable
private fun CheckLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.width(14.dp), contentAlignment = Alignment.Center) {
            Text("✓", style = MaterialTheme.typography.labelMedium, color = Palette.Correct)
        }
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
    }
}

@Composable
private fun PreviewPanel(preview: ImportPreview, controller: AppController) {
    Panel(padding = 20.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("导入预览")
            Spacer(Modifier.weight(1f))
            if (preview.accepted.isNotEmpty()) {
                Pill("可导入 ${preview.accepted.size} 题", Palette.Correct, Palette.CorrectSoft)
            }
        }

        if (preview.notes.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            preview.notes.forEach { note ->
                Row(modifier = Modifier.padding(vertical = 1.dp)) {
                    Text("· ", style = MaterialTheme.typography.bodySmall, color = Palette.Faint)
                    Text(note, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                }
            }
        }

        if (preview.detectedTypes.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                preview.detectedTypes.entries.forEach { entry ->
                    Pill(
                        "${entry.key.label} ${entry.value}",
                        Palette.Accent,
                        Palette.AccentSoft,
                    )
                }
            }
        }

        if (preview.needsMapping) {
            Spacer(Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Palette.PendingSoft)
                    .padding(14.dp),
            ) {
                Column {
                    Text(
                        "有 ${preview.ambiguousCount} 道题的答案包含多个选项，但没有声明题型。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.Pending,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "系统不会替你猜。请选择按单选还是多选导入 —— 猜错的题型会永久写进题库身份。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.Pending,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton("按多选导入", onClick = { controller.confirmImport(ChoiceMapping.MULTIPLE) })
                        SecondaryButton("按单选导入", onClick = { controller.confirmImport(ChoiceMapping.SINGLE) })
                    }
                }
            }
        }

        if (preview.rejected.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Divider()
            Spacer(Modifier.height(12.dp))
            Text(
                "被拒绝的题目（${preview.rejected.size}）",
                style = MaterialTheme.typography.titleSmall,
                color = Palette.Wrong,
            )
            Spacer(Modifier.height(6.dp))
            preview.rejected.take(8).forEach { rejected ->
                Text(
                    "第 ${rejected.ordinal} 项 · ${rejected.promptExcerpt.ifBlank { "（无题干）" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Ink,
                )
                rejected.issues.take(2).forEach { issue ->
                    Text(
                        "    ${issue.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.Muted,
                    )
                }
            }
            if (preview.rejected.size > 8) {
                Text(
                    "…还有 ${preview.rejected.size - 8} 项未展开",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Faint,
                )
            }
        }

        if (preview.hasContent && !preview.needsMapping) {
            Spacer(Modifier.height(16.dp))
            PrimaryButton(
                text = "确认导入 ${preview.accepted.size} 题",
                onClick = { controller.confirmImport(ChoiceMapping.AUTO) },
                enabled = !controller.busy,
            )
        }
    }
}

@Composable
private fun StandardEntryPanel(controller: AppController) {
    Panel(background = Palette.PanelMuted) {
        SectionTitle("题库标准与制作工具", hint = "全部本地内容，不需要联网或账号")
        Spacer(Modifier.height(8.dp))
        Text(
            "手上这个 .qbank 是否可信？怎么让 AI 按标准做包？三块说明都随应用离线内置。",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Muted,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("题库标准说明", onClick = { controller.openStandard(Screen.STANDARD) })
            SecondaryButton("AI 制包 Skill", onClick = { controller.openStandard(Screen.SKILL) })
            SecondaryButton("示例包与工具", onClick = { controller.openStandard(Screen.TOOLS) })
        }
    }
}
