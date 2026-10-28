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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import morefish.knowledge.trainer.application.port.WallClock
import morefish.knowledge.trainer.domain.ai.AiBudget
import morefish.knowledge.trainer.domain.ai.AiConfig
import morefish.knowledge.trainer.domain.ai.AiExplanation
import morefish.knowledge.trainer.domain.ai.AiRecordState
import morefish.knowledge.trainer.domain.ai.formatMicros
import morefish.knowledge.trainer.presentation.AppController
import morefish.knowledge.trainer.presentation.ui.BannerHost
import morefish.knowledge.trainer.presentation.ui.Divider
import morefish.knowledge.trainer.presentation.ui.EmptyState
import morefish.knowledge.trainer.presentation.ui.GhostButton
import morefish.knowledge.trainer.presentation.ui.KtChip
import morefish.knowledge.trainer.presentation.ui.Panel
import morefish.knowledge.trainer.presentation.ui.Pill
import morefish.knowledge.trainer.presentation.ui.PrimaryButton
import morefish.knowledge.trainer.presentation.ui.ScreenScaffold
import morefish.knowledge.trainer.presentation.ui.SecondaryButton
import morefish.knowledge.trainer.presentation.ui.SectionTitle
import morefish.knowledge.trainer.presentation.ui.StatTile
import morefish.knowledge.trainer.presentation.ui.formatRelative
import morefish.knowledge.trainer.presentation.theme.Palette

/**
 * AI 设置与台账。
 *
 * 这一页的写法有一条贯穿始终的原则：**把"会不会花钱"写在按钮前面**。
 * 未配置、未同意、预算不足时，页面上说的是"不会发请求"，而不是等用户点完再报错。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiScreen(controller: AppController) {
    ScreenScaffold(
        title = "AI 讲解",
        subtitle = "可选能力：不配置也能做完全部基础题型",
        overline = "按需生成",
        onBack = controller::closeAi,
    ) {
        BannerHost(controller.banner, controller::dismissBanner)

        StatusPanel(controller)
        Spacer(Modifier.height(14.dp))
        BatchEntry(controller)
        Spacer(Modifier.height(14.dp))
        ConfigPanel(controller)
        Spacer(Modifier.height(18.dp))
        RecordsPanel(controller)
    }
}

/**
 * 批量生成的入口。
 *
 * 单独一块，不与"单题讲解"混在一起：这两件事的成本量级差两个数量级，
 * 放在同一个面板里，用户会把"点一下看看"和"排一百道"当成同一种操作。
 */
@Composable
private fun BatchEntry(controller: AppController) {
    val pending = controller.batch.jobs.count { it.isOpen }
    val running = controller.batch.running
    Panel(background = Palette.PanelMuted, borderColor = Palette.AccentLine, padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("批量生成", style = MaterialTheme.typography.titleSmall, color = Palette.Ink)
            Spacer(Modifier.width(8.dp))
            when {
                running -> Pill("生成中", Palette.AccentInk, Palette.AccentSoft)
                pending > 0 -> Pill("$pending 个待续", Palette.Pending, Palette.PendingSoft)
                else -> Unit
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "把这个库里还没有讲解的题排成队列。排完先看到总数，点开始才发请求；" +
                "队列存在本机，可以暂停、继续、取消，每次只发一个请求。",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Muted,
        )
        Spacer(Modifier.height(12.dp))
        SecondaryButton(
            text = if (controller.batch.jobs.isEmpty()) "批量生成队列" else "批量生成队列 · ${controller.batch.jobs.size} 个任务",
            onClick = controller::openBatch,
            leading = "▤",
        )
    }
}

@Composable
private fun StatusPanel(controller: AppController) {
    val usage = controller.ai.usageToday
    val budget = controller.ai.config.budget
    Panel(accent = if (controller.ai.available) Palette.Accent else Palette.Neutral) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("当前状态", null)
            Spacer(Modifier.weight(1f))
            when {
                !controller.ai.available -> Pill("本平台不支持", Palette.Neutral, Palette.NeutralSoft)
                !controller.ai.config.configured -> Pill("未配置", Palette.Pending, Palette.PendingSoft)
                !controller.ai.config.uploadConsent -> Pill("未同意上传", Palette.Pending, Palette.PendingSoft)
                else -> Pill("可用", Palette.Correct, Palette.CorrectSoft)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            when {
                !controller.ai.available ->
                    "联网 AI 目前只在桌面端接入。Android / iOS / Web 端不会偷偷发请求，也不会显示点了没反应的按钮。"
                !controller.ai.config.configured ->
                    "还没有填接口地址与模型名。下面的输入框填完点保存，练习页才会出现「AI 讲解」入口。"
                !controller.ai.config.uploadConsent ->
                    "配置填好了，但还没有同意把题目内容发送给该服务 —— 没有这一步，一次请求都不会发。"
                else ->
                    "讲解由你配置的服务生成。默认只发当前这一道题（题干、选项、正确答案、你的答案），" +
                        "不发笔记、历史和其他题库。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Muted,
        )

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                label = "今日调用",
                value = usage.calls,
                modifier = Modifier.weight(1f),
                hint = if (budget.maxCallsPerDay > 0) "上限 ${budget.maxCallsPerDay}" else "不限",
            )
            StatTile(
                label = "今日 token",
                value = usage.tokens,
                modifier = Modifier.weight(1f),
                hint = if (budget.maxTokensPerDay > 0) "上限 ${budget.maxTokensPerDay}" else "不限",
            )
        }
        Spacer(Modifier.height(10.dp))
        val overBudget = budget.maxCostMicrosPerDay > 0 && usage.costMicros >= budget.maxCostMicrosPerDay
        Text(
            buildString {
                append("今日估算花费 ").append(formatMicros(usage.costMicros))
                if (budget.maxCostMicrosPerDay > 0) {
                    append(" / 上限 ").append(formatMicros(budget.maxCostMicrosPerDay))
                }
                if (budget.maxCallsPerDay > 0) {
                    append("　剩余次数 ").append((budget.maxCallsPerDay - usage.calls).coerceAtLeast(0))
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (overBudget) Palette.Wrong else Palette.Muted,
        )
        if (usage.failedCalls > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                "今天有 ${usage.failedCalls} 次调用失败（失败也会计入次数，避免无限重试把次数刷掉）",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Pending,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConfigPanel(controller: AppController) {
    val saved = controller.ai.config
    // 表单用本地草稿：每敲一个字就落盘既没必要，也会让"取消"变得不可能
    var baseUrl by remember(saved) { mutableStateOf(saved.baseUrl) }
    var model by remember(saved) { mutableStateOf(saved.model) }
    var apiKey by remember(saved) { mutableStateOf("") }
    var maxTokens by remember(saved) { mutableStateOf(saved.maxOutputTokens.toString()) }
    var maxCalls by remember(saved) { mutableStateOf(saved.budget.maxCallsPerDay.toString()) }
    var maxDayTokens by remember(saved) { mutableStateOf(saved.budget.maxTokensPerDay.toString()) }
    var maxCost by remember(saved) { mutableStateOf(yuanText(saved.budget.maxCostMicrosPerDay)) }
    var consent by remember(saved) { mutableStateOf(saved.uploadConsent) }
    var rememberKey by remember(saved) { mutableStateOf(saved.rememberKey) }

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

    Panel {
        SectionTitle("接入", "OpenAI 兼容接口")
        Spacer(Modifier.height(8.dp))
        Text(
            "支持任何提供 /chat/completions 的服务（云端 API、Ollama、LM Studio）。" +
                "公网地址必须 https；本机地址可以用 http。",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Muted,
        )
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                Triple("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
                Triple("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
                Triple("本地 Ollama", "http://localhost:11434/v1", "qwen2.5:7b"),
            ).forEach { (label, url, name) ->
                KtChip(
                    label = label,
                    selected = baseUrl == url && model == name,
                    onClick = {
                        baseUrl = url
                        model = name
                    },
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("接口地址 baseUrl") },
            placeholder = { Text("https://api.deepseek.com/v1") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = fieldShape,
            colors = fieldColors,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("模型名") },
            placeholder = { Text("deepseek-chat") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = fieldShape,
            colors = fieldColors,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text(if (controller.ai.hasKey) "API Key（已保存，留空则不修改）" else "API Key") },
            placeholder = { Text("本地模型可以留空") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            shape = fieldShape,
            colors = fieldColors,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            KtChip(label = if (rememberKey) "记住密钥：开" else "记住密钥：关", selected = rememberKey, onClick = {
                rememberKey = !rememberKey
            })
            Spacer(Modifier.width(8.dp))
            Text(
                "关掉后密钥只在这个会话有效，点保存会清掉本机已存的那份",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Faint,
            )
        }

        Spacer(Modifier.height(16.dp))
        Divider()
        Spacer(Modifier.height(14.dp))
        SectionTitle("成本闸门", "三道闸门，任一不过就不发请求")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NumberField("单次输出上限", maxTokens, { maxTokens = it }, fieldShape, fieldColors, Modifier.weight(1f))
            NumberField("每日调用次数", maxCalls, { maxCalls = it }, fieldShape, fieldColors, Modifier.weight(1f))
            NumberField("每日 token", maxDayTokens, { maxDayTokens = it }, fieldShape, fieldColors, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        NumberField(
            "每日费用上限（元，0 = 不限）",
            maxCost,
            { maxCost = it },
            fieldShape,
            fieldColors,
            Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "费用按你填的单价估算；单价留 0 表示不计费用，只按次数与 token 卡。",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Faint,
        )

        Spacer(Modifier.height(14.dp))
        Panel(background = Palette.PanelMuted, borderColor = Palette.PanelBorderStrong, padding = 14.dp) {
            Text(
                if (consent) {
                    "已同意：生成时会把当前题目的题干、选项、正确答案与你的作答发送给你配置的服务。"
                } else {
                    "未同意：现在不会发送任何题目内容。勾选下面的选项才会真正发出请求。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (consent) Palette.Muted else Palette.Pending,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                KtChip(
                    label = if (consent) "已同意上传本题内容" else "同意上传本题内容",
                    selected = consent,
                    onClick = { consent = !consent },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(
                text = "保存配置",
                onClick = {
                    controller.ai.saveConfig(
                        AiConfig(
                            baseUrl = baseUrl.trim(),
                            model = model.trim(),
                            maxOutputTokens = maxTokens.toIntOrNull() ?: saved.maxOutputTokens,
                            timeoutMillis = saved.timeoutMillis,
                            budget = AiBudget(
                                maxCallsPerDay = maxCalls.toIntOrNull() ?: saved.budget.maxCallsPerDay,
                                maxTokensPerDay = maxDayTokens.toIntOrNull() ?: saved.budget.maxTokensPerDay,
                                maxCostMicrosPerDay = microsOf(maxCost) ?: saved.budget.maxCostMicrosPerDay,
                            ),
                            pricing = saved.pricing,
                            uploadConsent = consent,
                            rememberKey = rememberKey,
                        ),
                        apiKeyDraft = apiKey,
                    )
                    apiKey = ""
                },
            )
            if (controller.ai.hasKey) {
                GhostButton("清除已存密钥", onClick = controller.ai::clearCredential)
            }
            if (saved.configured) {
                SecondaryButton(
                    text = "关闭 AI",
                    onClick = {
                        controller.ai.saveConfig(AiConfig(), apiKeyDraft = null)
                        baseUrl = ""
                        model = ""
                    },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "密钥存在本机 `ai-credential-v1.json`，不上传、不写日志、不随配置一起导出。" +
                "目前尚未加密 —— 加密存储与 .qbk 备份一起在后续阶段做。",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Faint,
        )
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    shape: RoundedCornerShape,
    colors: androidx.compose.material3.TextFieldColors,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
        shape = shape,
        colors = colors,
    )
}

@Composable
private fun RecordsPanel(controller: AppController) {
    val records = controller.ai.records
    SectionTitle("讲解记录", if (records.isEmpty()) null else "${records.size} 条")
    Spacer(Modifier.height(10.dp))
    if (records.isEmpty()) {
        EmptyState(
            title = "还没有生成过讲解",
            hint = "在练习页作答后，题目右上角会出现「AI 讲解」入口。生成过的讲解会缓存在本机，同样的题不会重复花钱。",
            glyph = "✦",
        )
        return
    }
    val now = WallClock.System.nowMillis()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        records.take(30).forEach { record ->
            RecordRow(record, now)
        }
    }
    if (records.size > 30) {
        Spacer(Modifier.height(8.dp))
        Text(
            "只显示最近 30 条；更早的记录仍在文件里（没有删除入口，避免手滑清掉花过钱的内容）",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Faint,
        )
    }
}

@Composable
private fun RecordRow(record: AiExplanation, now: Long) {
    var expanded by remember(record.key) { mutableStateOf(false) }
    Panel(padding = 14.dp, onClick = { expanded = !expanded }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(record.state.label, stateColor(record.state), stateSoft(record.state))
            Spacer(Modifier.width(8.dp))
            Text(
                record.model.ifBlank { "未知模型" },
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Muted,
            )
            Spacer(Modifier.weight(1f))
            Text(
                formatRelative(now, record.updatedAtMillis),
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Faint,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "题 ${record.questionId.take(10)}",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Faint,
            )
            if (record.locale.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(record.locale, style = MaterialTheme.typography.bodySmall, color = Palette.Faint)
            }
            Spacer(Modifier.weight(1f))
            if (record.charCount > 0) {
                Text("${record.charCount} 字", style = MaterialTheme.typography.bodySmall, color = Palette.Faint)
            }
            if (record.costMicros > 0) {
                Spacer(Modifier.width(8.dp))
                Text(formatMicros(record.costMicros), style = MaterialTheme.typography.bodySmall, color = Palette.Faint)
            }
        }
        if (record.error.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(record.error, style = MaterialTheme.typography.bodySmall, color = Palette.Wrong)
        }
        if (expanded && record.hasText) {
            Spacer(Modifier.height(10.dp))
            Divider()
            Spacer(Modifier.height(10.dp))
            Text(
                record.text.take(600) + if (record.text.length > 600) "…" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Ink,
            )
        }
    }
}

private fun stateColor(state: AiRecordState) = when (state) {
    AiRecordState.APPROVED -> Palette.Correct
    AiRecordState.REJECTED, AiRecordState.FAILED -> Palette.Wrong
    AiRecordState.NEEDS_REVIEW -> Palette.Pending
    else -> Palette.AccentInk
}

private fun stateSoft(state: AiRecordState) = when (state) {
    AiRecordState.APPROVED -> Palette.CorrectSoft
    AiRecordState.REJECTED, AiRecordState.FAILED -> Palette.WrongSoft
    AiRecordState.NEEDS_REVIEW -> Palette.PendingSoft
    else -> Palette.AccentSoft
}

private fun yuanText(micros: Long): String = if (micros <= 0) "0" else (micros / 1_000_000.0).toString()

private fun microsOf(text: String): Long? {
    val value = text.trim().toDoubleOrNull() ?: return null
    if (value < 0) return null
    return (value * 1_000_000).toLong()
}
