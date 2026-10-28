package morefish.knowledge.trainer.presentation.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import morefish.knowledge.trainer.domain.tools.AngleMode
import morefish.knowledge.trainer.domain.tools.CalcOutcome
import morefish.knowledge.trainer.domain.tools.Conversion
import morefish.knowledge.trainer.domain.tools.Dimension
import morefish.knowledge.trainer.domain.tools.UnitDef
import morefish.knowledge.trainer.domain.tools.UnitPrecision
import morefish.knowledge.trainer.domain.tools.Units
import morefish.knowledge.trainer.domain.tools.formatNumber
import morefish.knowledge.trainer.presentation.AppController
import morefish.knowledge.trainer.presentation.CalcRecord
import morefish.knowledge.trainer.presentation.ConvPicker
import morefish.knowledge.trainer.presentation.ToolboxTab
import morefish.knowledge.trainer.presentation.theme.Palette
import morefish.knowledge.trainer.presentation.ui.Divider
import morefish.knowledge.trainer.presentation.ui.GhostButton
import morefish.knowledge.trainer.presentation.ui.KtChip
import morefish.knowledge.trainer.presentation.ui.Panel
import morefish.knowledge.trainer.presentation.ui.Pill
import morefish.knowledge.trainer.presentation.ui.ScreenScaffold
import morefish.knowledge.trainer.presentation.ui.SectionTitle
import morefish.knowledge.trainer.presentation.ui.pressScale

/**
 * 工具箱：离线科学计算器 + 单位换算。
 *
 * 为什么不做成一个"更多"里的杂项页：做题做到一半要算一个数、要换一个单位，
 * 这是答题流程的一部分，所以它必须能从练习页直接进去、并且原样退回来。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolboxScreen(controller: AppController) {
    ScreenScaffold(
        title = "工具箱",
        subtitle = "${controller.toolboxTab.hint} · 全部在本机完成",
        overline = "学习台",
        onBack = controller::closeToolbox,
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToolboxTab.entries.forEach { tab ->
                KtChip(
                    label = tab.label,
                    selected = controller.toolboxTab == tab,
                    onClick = { controller.selectToolboxTab(tab) },
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        when (controller.toolboxTab) {
            ToolboxTab.CALCULATOR -> CalculatorPane(controller)
            ToolboxTab.UNITS -> UnitPane(controller)
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ------------------------------------------------------------------ 计算器

@Composable
private fun CalculatorPane(controller: AppController) {
    Panel(padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("算式", style = MaterialTheme.typography.labelSmall, color = Palette.Faint)
            Spacer(Modifier.weight(1f))
            AngleMode.entries.forEach { mode ->
                KtChip(
                    label = mode.label,
                    selected = controller.calcAngleMode == mode,
                    onClick = { controller.selectCalcAngleMode(mode) },
                )
                Spacer(Modifier.width(6.dp))
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = controller.calcExpression,
            onValueChange = controller::updateCalcExpression,
            placeholder = { Text("例如 (3+4)×2^3、sqrt(2)·sin(30)、或 1/3", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
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

        Spacer(Modifier.height(14.dp))
        ResultBlock(controller.calcOutcome, controller.calcCommitted != null)

        Spacer(Modifier.height(8.dp))
        Text(
            "支持 + − × ÷ ^ ! %、π 与 e、sin/cos/tan/asin/acos/atan、ln/lg/log、sqrt/cbrt、abs/floor/ceil/round、" +
                "min/max/mod/pow/hypot。% 表示百分之一（50% = 0.5），log(x) 为常用对数，log(b, x) 以 b 为底。",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Faint,
        )
    }

    Spacer(Modifier.height(12.dp))
    Keypad(controller)

    if (controller.calcHistory.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        HistoryPanel(controller)
    }
}

/** 结果区：成功给数字，失败给原因和位置，什么都没写就别说空话。 */
@Composable
private fun ResultBlock(outcome: CalcOutcome?, committed: Boolean) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (outcome is CalcOutcome.Failure) Palette.WrongSoft else Palette.PanelMuted)
            .border(
                1.dp,
                if (outcome is CalcOutcome.Failure) Palette.Wrong.copy(alpha = 0.35f) else Palette.PanelBorder,
                shape,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        when (outcome) {
            null -> Text(
                "输入算式后这里显示结果；按「=」会存进下面的记录。",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Muted,
            )

            is CalcOutcome.Value -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("=", style = MaterialTheme.typography.headlineSmall, color = Palette.Faint)
                Spacer(Modifier.width(10.dp))
                Text(
                    outcome.display,
                    style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
                    color = if (committed) Palette.Accent else Palette.Ink,
                )
                Spacer(Modifier.width(10.dp))
                if (committed) Pill("已记录", Palette.Accent, Palette.AccentSoft)
            }

            is CalcOutcome.Failure -> Column {
                Text(outcome.error.message, style = MaterialTheme.typography.bodyMedium, color = Palette.Wrong)
                val position = outcome.error.position
                if (position >= 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "位置：第 ${position + 1} 个字符附近",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.Wrong.copy(alpha = 0.75f),
                    )
                }
            }
        }
    }
}

private sealed interface KeyAction {
    data class Insert(val text: String) : KeyAction
    data object Backspace : KeyAction
    data object Clear : KeyAction
    data object Equals : KeyAction
}

private enum class KeyTone { DIGIT, OPERATOR, FUNCTION, ACTION, EQUALS }

private data class KeySpec(val label: String, val action: KeyAction, val tone: KeyTone)

private fun digit(label: String) = KeySpec(label, KeyAction.Insert(label), KeyTone.DIGIT)
private fun op(label: String, insert: String = label) = KeySpec(label, KeyAction.Insert(insert), KeyTone.OPERATOR)
private fun fn(label: String, insert: String) = KeySpec(label, KeyAction.Insert(insert), KeyTone.FUNCTION)

/**
 * 键盘固定 5 列 × 7 行。
 *
 * 数字键放在右下三分之二、函数键在最上面 —— 和实体科学计算器一致，
 * 手指不用重新学一遍位置；「=」独占右下角，因为它是唯一需要被盲按的键。
 */
private val keypadRows: List<List<KeySpec>> = listOf(
    listOf(fn("sin", "sin("), fn("cos", "cos("), fn("tan", "tan("), op("(", "("), op(")", ")")),
    listOf(fn("ln", "ln("), fn("lg", "lg("), fn("√", "sqrt("), op("^"), op("!", "!")),
    listOf(digit("7"), digit("8"), digit("9"), op("÷", "/"), KeySpec("←", KeyAction.Backspace, KeyTone.ACTION)),
    listOf(digit("4"), digit("5"), digit("6"), op("×", "*"), KeySpec("C", KeyAction.Clear, KeyTone.ACTION)),
    listOf(digit("1"), digit("2"), digit("3"), op("−", "-"), op("π", "π")),
    listOf(digit("0"), digit("."), op("%", "%"), op("+", "+"), KeySpec("=", KeyAction.Equals, KeyTone.EQUALS)),
)

@Composable
private fun Keypad(controller: AppController) {
    Panel(padding = 12.dp, background = Palette.PanelMuted) {
        keypadRows.forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { key ->
                    KeyCap(key, Modifier.weight(1f)) {
                        when (val action = key.action) {
                            is KeyAction.Insert -> controller.appendCalc(action.text)
                            KeyAction.Backspace -> controller.backspaceCalc()
                            KeyAction.Clear -> controller.clearCalc()
                            KeyAction.Equals -> controller.commitCalc()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyCap(key: KeySpec, modifier: Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val background = when (key.tone) {
        KeyTone.DIGIT -> Palette.Panel
        KeyTone.OPERATOR, KeyTone.FUNCTION -> Palette.Panel
        KeyTone.ACTION -> Palette.AccentSoft
        KeyTone.EQUALS -> Palette.Accent
    }
    val foreground = when (key.tone) {
        KeyTone.EQUALS -> Palette.OnAccent
        KeyTone.ACTION -> Palette.Accent
        KeyTone.FUNCTION -> Palette.Muted
        else -> Palette.Ink
    }
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(background)
            .border(
                1.dp,
                if (key.tone == KeyTone.EQUALS) Palette.Accent else Palette.PanelBorder,
                RoundedCornerShape(11.dp),
            )
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pressScale(source, pressedScale = 0.94f),
        contentAlignment = Alignment.Center,
    ) {
        Text(key.label, style = MaterialTheme.typography.titleSmall, color = foreground)
    }
}

@Composable
private fun HistoryPanel(controller: AppController) {
    Panel(padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("计算记录", "本次打开应用期间")
            Spacer(Modifier.weight(1f))
            GhostButton("清空", onClick = controller::clearCalcHistory)
        }
        Spacer(Modifier.height(4.dp))
        controller.calcHistory.forEachIndexed { index, record ->
            if (index > 0) Divider(color = Palette.PanelBorder)
            RecordRow(record) { controller.useCalcRecord(record) }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "记录只存在内存里，关掉应用就清空 —— 计算器不该变成一本没人整理的错题本。",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Faint,
        )
    }
}

@Composable
private fun RecordRow(record: CalcRecord, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            record.expression,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = Palette.Muted,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "= ${record.result}",
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = Palette.Ink,
        )
    }
}

// ------------------------------------------------------------------ 单位换算

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UnitPane(controller: AppController) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Dimension.entries.forEach { dimension ->
            KtChip(
                label = dimension.label,
                selected = controller.convDimension == dimension,
                onClick = { controller.selectConvDimension(dimension) },
            )
        }
    }

    Spacer(Modifier.height(12.dp))

    Panel(padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = controller.convInput,
                onValueChange = controller::updateConvInput,
                label = { Text("数值") },
                singleLine = true,
                textStyle = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Palette.Accent,
                    unfocusedBorderColor = Palette.PanelBorderStrong,
                    focusedContainerColor = Palette.Panel,
                    unfocusedContainerColor = Palette.Panel,
                    cursorColor = Palette.Accent,
                ),
            )
            Spacer(Modifier.width(10.dp))
            GhostButton("⇅ 交换", onClick = controller::swapConvUnits)
        }

        Spacer(Modifier.height(12.dp))
        UnitSelector(
            label = "从",
            unit = controller.convFrom,
            expanded = controller.convPicker == ConvPicker.FROM,
            onToggle = { controller.toggleConvPicker(ConvPicker.FROM) },
        )
        Spacer(Modifier.height(8.dp))
        UnitSelector(
            label = "到",
            unit = controller.convTo,
            expanded = controller.convPicker == ConvPicker.TO,
            onToggle = { controller.toggleConvPicker(ConvPicker.TO) },
        )

        Spacer(Modifier.height(14.dp))
        ConversionResult(controller.convOutcome, controller.convInput, controller.convTo)
    }

    if (controller.convPicker != ConvPicker.NONE) {
        Spacer(Modifier.height(10.dp))
        UnitPickerList(controller)
    }

    Spacer(Modifier.height(12.dp))
    ReferenceTable(controller)
}

@Composable
private fun UnitSelector(label: String, unit: UnitDef, expanded: Boolean, onToggle: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (expanded) Palette.AccentSoft else Palette.PanelMuted)
            .border(1.dp, if (expanded) Palette.Accent else Palette.PanelBorder, shape)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onToggle)
            .pressScale(source)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Palette.Faint)
        Spacer(Modifier.width(12.dp))
        Text(
            unit.symbol,
            style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace),
            color = if (expanded) Palette.Accent else Palette.Ink,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            unit.name,
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.Muted,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(if (expanded) "收起" else "换一个 ▾", style = MaterialTheme.typography.labelMedium, color = Palette.Accent)
    }
}

@Composable
private fun ConversionResult(outcome: Conversion?, input: String, to: UnitDef) {
    val shape = RoundedCornerShape(14.dp)
    val bad = outcome is Conversion.DimensionMismatch || outcome is Conversion.Invalid
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (bad) Palette.WrongSoft else Palette.PanelMuted)
            .border(1.dp, if (bad) Palette.Wrong.copy(alpha = 0.35f) else Palette.PanelBorder, shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column {
            when (outcome) {
                null -> Text(
                    if (input.isBlank()) "先填一个数值" else "「$input」不是一个数，请只写数字（可用科学记数法，如 1.5e3）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.Muted,
                )

                is Conversion.Ok -> {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            outcome.display,
                            style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace),
                            color = Palette.Accent,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            to.symbol,
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                            color = Palette.Muted,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            to.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.Faint,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                    if (outcome.note != null) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.Top) {
                            Pill("精度", Palette.Pending, Palette.PendingSoft)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                outcome.note,
                                style = MaterialTheme.typography.bodySmall,
                                color = Palette.Pending,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                is Conversion.DimensionMismatch -> Text(
                    "「${outcome.from.label}」和「${outcome.to.label}」不是同一个维度，不能互转。" +
                        "这类换算没有物理意义，所以这里不给数字。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.Wrong,
                )

                is Conversion.Invalid -> Text(
                    outcome.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.Wrong,
                )
            }
        }
    }
}

@Composable
private fun UnitPickerList(controller: AppController) {
    val targetLabel = if (controller.convPicker == ConvPicker.FROM) "从" else "到"
    Panel(padding = 12.dp, background = Palette.PanelMuted) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("选择单位 · $targetLabel", controller.convDimension.label)
            Spacer(Modifier.weight(1f))
            GhostButton("取消", onClick = { controller.toggleConvPicker(controller.convPicker) })
        }
        Spacer(Modifier.height(8.dp))
        controller.convUnits.forEach { unit ->
            val selected = if (controller.convPicker == ConvPicker.FROM) {
                unit.id == controller.convFromId
            } else {
                unit.id == controller.convToId
            }
            UnitRow(unit, selected) { controller.selectConvUnit(unit) }
        }
    }
}

@Composable
private fun UnitRow(unit: UnitDef, selected: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Palette.AccentSoft else Palette.Panel)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            unit.symbol,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = if (selected) Palette.Accent else Palette.Ink,
            modifier = Modifier.width(78.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(unit.name, style = MaterialTheme.typography.bodyMedium, color = Palette.Ink, modifier = Modifier.width(104.dp))
        Text(
            unit.scaleHint,
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Faint,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (unit.precision != UnitPrecision.EXACT) {
            Spacer(Modifier.width(6.dp))
            Pill(
                if (unit.precision == UnitPrecision.CONVENTION) "约定" else "近似",
                Palette.Pending,
                Palette.PendingSoft,
            )
        }
    }
}

/** 常见对照：以 1 个「从」单位为准，把同维度其他单位列几行，省得反复切换。 */
@Composable
private fun ReferenceTable(controller: AppController) {
    val from = controller.convFrom
    val others = controller.convUnits
        .filter { it.id != from.id }
        .sortedBy { kotlin.math.abs(kotlin.math.ln(it.factor / from.factor)) }
        .take(5)

    Panel(padding = 16.dp) {
        SectionTitle("常见对照", "1 ${from.symbol}")
        Spacer(Modifier.height(6.dp))
        others.forEach { unit ->
            val outcome = Units.convert(1.0, from, unit)
            if (outcome is Conversion.Ok) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "1 ${from.symbol}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = Palette.Faint,
                        modifier = Modifier.width(96.dp),
                    )
                    Text("=", style = MaterialTheme.typography.bodySmall, color = Palette.Faint)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "${outcome.display} ${unit.symbol}",
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = Palette.Ink,
                        modifier = Modifier.weight(1f),
                    )
                    Text(unit.name, style = MaterialTheme.typography.bodySmall, color = Palette.Faint)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "温度带偏移，换算不是简单乘比例；市制单位（尺 / 斤 / 亩）与 BTU、年 等系数为约定值或近似值，" +
                "计算时会在结果下方标注。",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Faint,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "所有同维度换算都走「先落到基准单位、再换出去」这一步，任意两单位往返一次相对误差小于 10⁻¹²（测试逐对验证）。",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Faint,
        )
    }
}
