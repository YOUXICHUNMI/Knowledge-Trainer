package morefish.knowledge.trainer.presentation.toolbox

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import morefish.knowledge.trainer.domain.tools.AngleMode
import morefish.knowledge.trainer.domain.tools.CalcOutcome
import morefish.knowledge.trainer.domain.tools.Calculator
import morefish.knowledge.trainer.domain.tools.Conversion
import morefish.knowledge.trainer.domain.tools.Dimension
import morefish.knowledge.trainer.domain.tools.UnitDef
import morefish.knowledge.trainer.domain.tools.Units
import morefish.knowledge.trainer.domain.tools.parseNumber

enum class ToolboxTab(val label: String, val hint: String) {
    CALCULATOR("计算器", "表达式的每一步都在本机算，不联网"),
    UNITS("单位换算", "按维度检查，跨维度不换算"),
}

/** 一次计算的留档。只存在内存里：计算器不是笔记本，关掉就该清空。 */
data class CalcRecord(val expression: String, val result: String)

/** 单位选择面板当前在给哪一侧选单位。 */
enum class ConvPicker { NONE, FROM, TO }

/**
 * 工具箱的状态与行为：计算器 + 单位换算。
 *
 * 单独成类的原因不是它大，而是它**跟题目、练习、题库全无关系**：
 * 换一个单位不需要知道当前在做哪道题，也不写任何持久化。
 * 把它留在 `AppController` 里会让组合根多背一份与业务无关的状态，
 * 也让"工具箱能不能离线用"这件事看不出边界。
 *
 * 这里只做本机计算：[Calculator] 与 [Units] 都是 domain 里的纯函数，
 * 没有网络、没有磁盘、没有时钟依赖，因此整个类可以直接单测。
 *
 * 导航（进/出工具箱、从哪进来的）留在 `AppController`：那是路由，不是工具箱的状态。
 */
class ToolboxController {

    // ---------------------------------------------------------------- 计算器

    var tab by mutableStateOf(ToolboxTab.CALCULATOR)
        private set

    var expression by mutableStateOf("")
        private set

    var angleMode by mutableStateOf(AngleMode.DEG)
        private set

    /** 按下「=」之后的结论。继续编辑就清空，避免旧结果看起来像当前算式的答案。 */
    var committed by mutableStateOf<CalcOutcome?>(null)
        private set

    /** 一次计算的留档，只存在内存里：计算器不是笔记本，关掉就该清空。 */
    var history by mutableStateOf<List<CalcRecord>>(emptyList())
        private set

    /** 编辑过程中的实时结果（只取成功的部分来显示，不拿半截算式报错吓人）。 */
    val live: CalcOutcome?
        get() = if (expression.isBlank()) null else Calculator.evaluate(expression, angleMode)

    /** 结果区显示什么：按过「=」就显示那次结论，否则显示实时预览。 */
    val outcome: CalcOutcome?
        get() = committed ?: live

    fun selectTab(next: ToolboxTab) {
        tab = next
    }

    fun updateExpression(value: String) {
        expression = value
        committed = null
    }

    /** 键盘输入的字符直接追加到末尾：不做光标插入，行为可预期。 */
    fun append(text: String) {
        expression += text
        committed = null
    }

    fun backspace() {
        if (expression.isEmpty()) return
        expression = expression.dropLast(1)
        committed = null
    }

    fun clear() {
        expression = ""
        committed = null
    }

    fun selectAngleMode(mode: AngleMode) {
        angleMode = mode
        committed = null
    }

    /**
     * 按下「=」：算一次并留档。失败时保留算式、只报错 ——
     * 清空输入等于把用户刚写的式子扔掉。
     */
    fun commit() {
        val trimmed = expression.trim()
        if (trimmed.isEmpty()) return
        val result = Calculator.evaluate(trimmed, angleMode)
        committed = result
        if (result is CalcOutcome.Value) {
            history = (listOf(CalcRecord(trimmed, result.display)) + history).take(MAX_HISTORY)
        }
    }

    fun useRecord(record: CalcRecord) {
        expression = record.expression
        committed = null
    }

    fun clearHistory() {
        history = emptyList()
    }

    // ---------------------------------------------------------------- 单位换算

    var dimension by mutableStateOf(Dimension.LENGTH)
        private set

    var fromId by mutableStateOf("m")
        private set

    var toId by mutableStateOf("cm")
        private set

    var input by mutableStateOf("1")
        private set

    var picker by mutableStateOf(ConvPicker.NONE)
        private set

    val units: List<UnitDef> get() = Units.of(dimension)

    val from: UnitDef get() = Units.byId(fromId) ?: units.first()

    val to: UnitDef get() = Units.byId(toId) ?: units.last()

    val conversion: Conversion?
        get() {
            val value = parseNumber(input) ?: return null
            return Units.convert(value, from, to)
        }

    fun selectDimension(next: Dimension) {
        dimension = next
        val available = Units.of(next)
        fromId = available.first().id
        toId = (available.getOrNull(1) ?: available.first()).id
        picker = ConvPicker.NONE
    }

    fun togglePicker(target: ConvPicker) {
        picker = if (picker == target) ConvPicker.NONE else target
    }

    /** 选择单位时顺带关掉选择面板；选中对面那个单位没有意义，直接忽略。 */
    fun selectUnit(unit: UnitDef) {
        when (picker) {
            ConvPicker.FROM -> if (unit.id != toId) fromId = unit.id
            ConvPicker.TO -> if (unit.id != fromId) toId = unit.id
            ConvPicker.NONE -> Unit
        }
        picker = ConvPicker.NONE
    }

    fun swap() {
        val previous = fromId
        fromId = toId
        toId = previous
    }

    fun updateInput(value: String) {
        input = value
    }

    private companion object {
        const val MAX_HISTORY = 20
    }
}
