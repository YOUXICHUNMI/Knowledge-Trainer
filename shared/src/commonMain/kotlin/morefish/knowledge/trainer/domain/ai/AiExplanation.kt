package morefish.knowledge.trainer.domain.ai

import morefish.knowledge.trainer.domain.model.Fingerprint

/**
 * 一条 AI 讲解记录的状态。
 *
 * 刻意把「生成出来了」和「内容靠得住」分成不同状态：
 * 模型返回的文本默认是 [GENERATED_UNVERIFIED]，本地只做**结构**校验，
 * 校验通过也只是 [SCHEMA_VALIDATED] —— 结构合法不代表讲得对。
 * 只有用户自己点过「有用」才是 [APPROVED]，那才是这条内容被人类认可的唯一证据。
 */
enum class AiRecordState(val wireName: String, val label: String) {
    /** 请求失败（网络 / 鉴权 / 限流 / 解析）。保留失败记录是为了让"花了没花、花在哪"可查。 */
    FAILED("failed", "调用失败"),
    GENERATED_UNVERIFIED("generated_unverified", "未验证"),
    SCHEMA_VALIDATED("schema_validated", "结构已校验"),
    NEEDS_REVIEW("needs_review", "需要复核"),
    APPROVED("approved", "已采纳"),
    REJECTED("rejected", "已弃用"),
    ;

    /** 可以拿来当缓存直接展示的状态（失败与被弃用的不算）。 */
    val isReusable: Boolean
        get() = this == GENERATED_UNVERIFIED || this == SCHEMA_VALIDATED ||
            this == NEEDS_REVIEW || this == APPROVED

    companion object {
        fun fromWire(value: String?): AiRecordState? = entries.firstOrNull { it.wireName == value }
    }
}

/**
 * 一条讲解记录，同时充当"缓存"与"审核留档"。
 *
 * 缓存键（[key]）把「哪道题的哪一版、哪种语言、哪个模型、哪版提示词」都算进去：
 * 题目内容改了、换了模型、改了提示词，都不该命中旧讲解 —— 拿旧解释配新题目是最难发现的那种错。
 */
data class AiExplanation(
    val key: String,
    val bankId: String,
    val questionId: String,
    val locale: String,
    val questionRevision: Int,
    val model: String,
    val state: AiRecordState,
    val text: String = "",
    val error: String = "",
    val createdAtMillis: Long = 0,
    val updatedAtMillis: Long = createdAtMillis,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val costMicros: Long = 0,
) {
    val hasText: Boolean get() = text.isNotBlank()

    val charCount: Int get() = text.length

    /**
     * 用户对这条讲解的处置。只允许"人"能做的三种动作：
     * 采纳、弃用、打回待复核。生成类状态由系统写入，不允许从这里伪造。
     */
    fun reviewed(next: AiRecordState, nowMillis: Long): AiExplanation {
        require(next == AiRecordState.APPROVED || next == AiRecordState.REJECTED || next == AiRecordState.NEEDS_REVIEW) {
            "不是人工审核状态：$next"
        }
        require(hasText || next == AiRecordState.NEEDS_REVIEW) { "没有内容的记录不能被采纳或弃用" }
        return copy(state = next, updatedAtMillis = nowMillis)
    }

    companion object {
        /**
         * 提示词版本。改了系统提示词就必须改这里，否则旧讲解会被当成"同一份"复用。
         */
        const val PROMPT_VERSION = "explain-v1"

        fun keyFor(
            bankId: String,
            questionId: String,
            locale: String,
            questionRevision: Int,
            contentFingerprint: String,
            model: String,
        ): String = Fingerprint.of(
            listOf(
                "ai-explanation",
                PROMPT_VERSION,
                bankId,
                questionId,
                locale,
                questionRevision.toString(),
                contentFingerprint,
                model,
            ).joinToString("\u0001"),
        )
    }
}

/** 讲解正文的长度上限。超过这个长度基本可以断定是模型跑题，而不是"讲得更细"。 */
const val MAX_EXPLANATION_CHARS = 20_000

/** 结构校验结果。注意它只判断"这段文本能不能当讲解展示"，不判断内容对不对。 */
sealed interface AiTextCheck {
    data object Ok : AiTextCheck

    data class Problem(val reason: String) : AiTextCheck
}

/**
 * 本地结构校验。
 *
 * 只做三件确定的事：非空、长度合理、没有不可见控制字符。
 * 刻意不做"关键词匹配答案"这类猜测式校验 —— 那会把正确但表述不同的讲解判成坏内容，
 * 而真正的错误（讲错了）它也拦不住。
 */
fun checkExplanationText(raw: String): AiTextCheck {
    val text = raw.trim()
    if (text.isEmpty()) return AiTextCheck.Problem("模型返回了空内容")
    val control = text.firstOrNull { it.code < 0x20 && it != '\n' && it != '\t' }
    if (control != null) {
        // common 里没有 String.format，手写 padding 才不会在 iOS/JS 上炸
        val hex = control.code.toString(16).uppercase().padStart(4, '0')
        return AiTextCheck.Problem("内容里混进了不可见控制字符（U+$hex），已拦下")
    }
    if (text.length > MAX_EXPLANATION_CHARS) {
        return AiTextCheck.Problem("内容 ${text.length} 字，超过 $MAX_EXPLANATION_CHARS 字上限，可能是模型跑题")
    }
    return AiTextCheck.Ok
}

/** 存盘前的文本整理：去掉 \r（Windows 换行会让 Markdown 渲染出多余空行）并裁掉首尾空白。 */
fun normalizeExplanation(raw: String): String = raw.replace("\r\n", "\n").replace('\r', '\n').trim()
