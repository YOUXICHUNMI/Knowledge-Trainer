package morefish.knowledge.trainer.infrastructure.importing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import morefish.knowledge.trainer.application.port.ChoiceMapping
import morefish.knowledge.trainer.application.port.ImportParseResult
import morefish.knowledge.trainer.application.port.ImportPreview
import morefish.knowledge.trainer.application.port.RejectedQuestion
import morefish.knowledge.trainer.domain.model.GradingSpec
import morefish.knowledge.trainer.domain.model.Question
import morefish.knowledge.trainer.domain.model.QuestionOption
import morefish.knowledge.trainer.domain.model.QuestionType
import morefish.knowledge.trainer.domain.model.Severity
import morefish.knowledge.trainer.domain.model.ValidationIssue
import morefish.knowledge.trainer.domain.model.validate
import morefish.knowledge.trainer.infrastructure.GradingWire

/**
 * 把用户粘贴 / 导入的 JSON 转成题目草稿。
 *
 * 原则：
 * 1. 不猜题型 —— 含糊时报告并要求用户映射；
 * 2. 不静默丢题 —— 无法导入的题目逐条给出原因；
 * 3. 只做结构校验，不做内容"纠正"。
 */
class JsonQuestionImporter {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(raw: String, mapping: ChoiceMapping = ChoiceMapping.AUTO): ImportParseResult {
        val text = raw.removePrefix("\uFEFF").trim()
        if (text.isEmpty()) return ImportParseResult.Failed("内容为空，没有可导入的题目")

        val notes = mutableListOf<String>()
        val stream = openStream(text, notes)
        stream.error?.let { return ImportParseResult.Failed(it) }
        if (stream.count == 0) return ImportParseResult.Failed("题目数组为空")

        val accepted = mutableListOf<Question>()
        val rejected = mutableListOf<RejectedQuestion>()
        val issues = mutableListOf<ValidationIssue>()
        val seenIds = mutableSetOf<String>()
        var ambiguous = 0
        var index = 0
        var wellFormed = 0

        stream.elements.forEach { element ->
            index++
            val ordinal = index

            // 坏行不连坐：一行写错不该让另外九万九千九百九十九道题都进不来
            if (element == null) {
                rejected += RejectedQuestion(
                    ordinal = ordinal,
                    promptExcerpt = "",
                    issues = listOf(
                        ValidationIssue(Severity.ERROR, "line-not-json", "第 $ordinal 项不是合法 JSON，已跳过"),
                    ),
                )
                return@forEach
            }

            wellFormed++
            val obj = element as? JsonObject
            if (obj == null) {
                rejected += RejectedQuestion(
                    ordinal = ordinal,
                    promptExcerpt = element.toString().take(40),
                    issues = listOf(
                        ValidationIssue(Severity.ERROR, "not-object", "第 $ordinal 项不是 JSON 对象"),
                    ),
                )
                return@forEach
            }

            val parsed = runCatching { parseOne(obj, ordinal, mapping) }.getOrElse { t ->
                ParsedItem(
                    question = null,
                    issues = listOf(
                        ValidationIssue(Severity.ERROR, "parse-exception", "第 $ordinal 项解析失败：${t.message}"),
                    ),
                    ambiguous = false,
                    promptExcerpt = "",
                )
            }

            if (parsed.ambiguous) ambiguous++
            issues += parsed.issues

            val question = parsed.question
            val blocking = parsed.issues.filter { it.severity == Severity.ERROR } +
                (question?.validate()?.filter { it.severity == Severity.ERROR } ?: emptyList())

            when {
                question == null || blocking.isNotEmpty() -> rejected += RejectedQuestion(
                    ordinal = ordinal,
                    promptExcerpt = parsed.promptExcerpt.ifBlank { question?.promptMarkdown?.take(40) ?: "" },
                    issues = blocking.ifEmpty { parsed.issues },
                )

                !seenIds.add(question.questionId) -> rejected += RejectedQuestion(
                    ordinal = ordinal,
                    promptExcerpt = question.promptMarkdown.take(40),
                    issues = listOf(
                        ValidationIssue(
                            Severity.ERROR, "duplicate-question-id",
                            "题目 ID ${question.questionId} 在同一份导入中重复出现", question.questionId,
                        ),
                    ),
                )

                else -> accepted += question
            }
        }

        // 一行都解析不出来 = 这根本不是 JSON，别假装"有 N 项被拒绝"
        if (wellFormed == 0) {
            return ImportParseResult.Failed(
                "无法解析为 JSON。支持：JSON 数组、带 questions 字段的对象、NDJSON（每行一个 JSON 对象）。",
            )
        }

        notes += "共解析 $index 项：可导入 ${accepted.size} 题，拒绝 ${rejected.size} 项"
        if (ambiguous > 0) {
            notes += "有 $ambiguous 道题的题型含糊（答案为多个选项但未声明类型），需要你指定映射"
        }

        return ImportParseResult.Ok(
            ImportPreview(
                accepted = accepted,
                rejected = rejected,
                issues = issues,
                notes = notes,
                needsMapping = ambiguous > 0,
                ambiguousCount = ambiguous,
            ),
        )
    }

    // ---- 单题解析 ----

    private data class ParsedItem(
        val question: Question?,
        val issues: List<ValidationIssue>,
        val ambiguous: Boolean,
        val promptExcerpt: String,
    )

    private fun parseOne(obj: JsonObject, ordinal: Int, mapping: ChoiceMapping): ParsedItem {
        val prompt = text(obj, "question", "prompt", "stem", "题干", "title").orEmpty()
        if (prompt.isBlank()) {
            return ParsedItem(
                null,
                listOf(ValidationIssue(Severity.ERROR, "prompt-blank", "第 $ordinal 项缺少题干（question / prompt / 题干）")),
                false,
                "",
            )
        }

        val issues = mutableListOf<ValidationIssue>()
        var options = parseOptions(obj)
        val declaredType = parseDeclaredType(obj)
        val answerElement = obj["answer"] ?: obj["答案"] ?: obj["correct"] ?: obj["correctAnswer"]

        // 判断题允许省略选项：自动补"正确 / 错误"两个稳定选项
        if (options.isEmpty() && declaredType == QuestionType.TRUE_FALSE) {
            options = listOf(
                QuestionOption("T", "正确"),
                QuestionOption("F", "错误"),
            )
        }

        val questionId = questionIdOf(obj, ordinal)
        val explanation = explanationOf(obj)
        val revision = revisionOf(obj)

        if (options.isEmpty()) {
            val answerText = answerElement?.asText() ?: text(obj, "answerText")
            if (declaredType != null && declaredType != QuestionType.SHORT_ANSWER) {
                issues += ValidationIssue(
                    Severity.ERROR, "options-missing",
                    "声明为${declaredType.label}但没有任何选项",
                )
            }
            if (answerText.isNullOrBlank()) {
                issues += ValidationIssue(Severity.ERROR, "answer-missing", "简答题缺少答案（answer）")
            }
            return ParsedItem(
                question = Question(
                    questionId = questionId,
                    type = QuestionType.SHORT_ANSWER,
                    promptMarkdown = prompt,
                    options = emptyList(),
                    answerText = answerText,
                    explanationMarkdown = explanation,
                    shuffleAllowed = false,
                    grading = parseTextGrading(obj),
                    contentRevision = revision,
                ),
                issues = issues,
                ambiguous = false,
                promptExcerpt = prompt.take(40),
            )
        }

        val resolved = resolveAnswer(
            answerElement = answerElement,
            correctFromIds = (obj["correctOptionIds"] as? JsonArray)
                ?.mapNotNull { it.asText()?.trim() }
                ?.filter { it.isNotEmpty() },
            options = options,
        )
        val correctIds = resolved.ids.toSet()
        if (resolved.unresolved.isNotEmpty()) {
            issues += ValidationIssue(
                Severity.ERROR, "answer-unresolved",
                "答案中的 ${resolved.unresolved.joinToString()} 无法对应到任何选项",
            )
        }

        var type = declaredType
        var ambiguous = false

        if (type == null) {
            when {
                correctIds.size > 1 -> when (mapping) {
                    ChoiceMapping.AUTO -> {
                        ambiguous = true
                        issues += ValidationIssue(
                            Severity.ERROR, "ambiguous-type",
                            "答案为多个选项（${correctIds.joinToString()}）但未声明题型，需要你选择按单选还是多选导入",
                        )
                        type = QuestionType.MULTIPLE_CHOICE
                    }

                    ChoiceMapping.SINGLE -> {
                        issues += ValidationIssue(
                            Severity.ERROR, "ambiguous-type",
                            "你选择了按单选导入，但答案包含 ${correctIds.size} 个选项",
                        )
                        type = QuestionType.SINGLE_CHOICE
                    }

                    ChoiceMapping.MULTIPLE -> type = QuestionType.MULTIPLE_CHOICE
                }

                correctIds.size == 1 -> {
                    issues += ValidationIssue(
                        Severity.WARNING, "type-inferred",
                        "未声明题型，按单选导入（答案唯一）",
                    )
                    type = QuestionType.SINGLE_CHOICE
                }

                else -> {
                    issues += ValidationIssue(Severity.ERROR, "answer-missing", "选择题缺少答案，无法判题")
                    type = QuestionType.SINGLE_CHOICE
                }
            }
        } else if (type == QuestionType.SINGLE_CHOICE && correctIds.size > 1) {
            issues += ValidationIssue(
                Severity.ERROR, "single-with-multiple-answers",
                "声明为单选但答案包含 ${correctIds.size} 个选项",
            )
        }

        if (type == QuestionType.TRUE_FALSE && options.size != 2) {
            issues += ValidationIssue(Severity.ERROR, "true-false-options", "判断题必须恰好 2 个选项")
        }

        val finalType: QuestionType = type

        // 重组题：答案次序即标准次序；等价次序从 grading 对象读取
        if (finalType == QuestionType.ORDERING) {
            if (correctIds.size < 2) {
                issues += ValidationIssue(
                    Severity.ERROR, "ordering-fragments",
                    "重组题至少需要 2 个正确片段，当前 ${correctIds.size} 个",
                )
            }
            val alternatives = (obj["grading"] as? JsonObject)?.let { GradingWire.alternativesFrom(it) } ?: emptyList()
            return ParsedItem(
                question = Question(
                    questionId = questionId,
                    type = QuestionType.ORDERING,
                    promptMarkdown = prompt,
                    options = options,
                    correctOptionIds = correctIds,
                    explanationMarkdown = explanation,
                    shuffleAllowed = boolean(obj, "shuffleAllowed") ?: false,
                    grading = GradingSpec.Ordering(canonical = resolved.ids.distinct(), alternatives = alternatives),
                    tags = (obj["tags"] as? JsonArray)?.mapNotNull { it.asText() } ?: emptyList(),
                    contentRevision = revision,
                ),
                issues = issues,
                ambiguous = ambiguous,
                promptExcerpt = prompt.take(40),
            )
        }

        return ParsedItem(
            question = Question(
                questionId = questionId,
                type = finalType,
                promptMarkdown = prompt,
                options = options,
                correctOptionIds = correctIds,
                explanationMarkdown = explanation,
                shuffleAllowed = boolean(obj, "shuffleAllowed") ?: true,
                grading = GradingSpec.fromWire(text(obj, "grading", "strategy"))
                    ?: Question.defaultGradingFor(finalType),
                tags = (obj["tags"] as? JsonArray)?.mapNotNull { it.asText() } ?: emptyList(),
                contentRevision = revision,
            ),
            issues = issues,
            ambiguous = ambiguous,
            promptExcerpt = prompt.take(40),
        )
    }

    private fun parseDeclaredType(obj: JsonObject): QuestionType? {
        val raw = text(obj, "type", "questionType", "题型") ?: return null
        QuestionType.fromWire(raw)?.let { return it }
        return when (raw.trim()) {
            "单选", "单选题", "单项选择" -> QuestionType.SINGLE_CHOICE
            "多选", "多选题", "多项选择" -> QuestionType.MULTIPLE_CHOICE
            "判断", "判断题" -> QuestionType.TRUE_FALSE
            "简答", "简答题", "问答" -> QuestionType.SHORT_ANSWER
            "重组", "重组题", "语义重组", "排序", "排序题", "排列" -> QuestionType.ORDERING
            else -> null
        }
    }

    /** 简答题的判题策略：默认死记硬背；grading 为对象且含关键词字段时按默知识点。 */
    private fun parseTextGrading(obj: JsonObject): GradingSpec {
        val gradingObj = obj["grading"] as? JsonObject
        if (gradingObj != null) {
            val strategy = (gradingObj["strategy"] as? JsonPrimitive)?.content?.trim()?.lowercase()
            if (strategy == "keywords") {
                GradingWire.keywordsFrom(gradingObj)?.let { return it }
            }
            return GradingSpec.StrictText
        }
        // 顶层便捷写法：requiredKeywords / forbiddenKeywords
        GradingWire.keywordsFrom(obj)?.let { return it }
        return GradingSpec.StrictText
    }

    private fun parseOptions(obj: JsonObject): List<QuestionOption> {
        when (val element = obj["options"] ?: obj["选项"] ?: obj["choices"]) {
            is JsonArray -> {
                if (element.isEmpty()) return emptyList()
                return element.mapIndexed { i, item ->
                    when (item) {
                        is JsonObject -> {
                            val id = text(item, "optionId", "id", "key", "label") ?: "o${i + 1}"
                            val content = text(item, "contentMarkdown", "text", "content", "value")
                                ?: item["label"]?.asText()
                                ?: ""
                            QuestionOption(id.trim(), content)
                        }

                        else -> QuestionOption("o${i + 1}", item.asText().orEmpty())
                    }
                }
            }

            is JsonObject -> {
                if (element.isEmpty()) return emptyList()
                return element.entries.map { (key, value) ->
                    QuestionOption(key.trim(), value.asText().orEmpty())
                }
            }

            else -> Unit
        }

        // 兼容 {"A": "...", "B": "...", "C": "...", "D": "..."} 扁平写法
        val letters = listOf("A", "B", "C", "D", "E", "F", "G", "H")
        val present = letters.filter { obj.containsKey(it) }
        if (present.size >= 2) {
            return present.map { letter -> QuestionOption(letter, obj[letter]?.asText().orEmpty()) }
        }
        return emptyList()
    }

    private data class ResolvedAnswer(val ids: List<String>, val unresolved: List<String>)

    private fun resolveAnswer(
        answerElement: JsonElement?,
        correctFromIds: List<String>?,
        options: List<QuestionOption>,
    ): ResolvedAnswer {
        val tokens = if (!correctFromIds.isNullOrEmpty()) correctFromIds else tokensOf(answerElement)
        val ids = mutableListOf<String>()
        val unresolved = mutableListOf<String>()
        tokens.forEach { token ->
            val id = mapToken(token, options)
            if (id == null) unresolved += token else ids += id
        }
        return ResolvedAnswer(ids.distinct(), unresolved)
    }

    private fun tokensOf(element: JsonElement?): List<String> {
        if (element == null) return emptyList()
        return when (element) {
            is JsonArray -> element.flatMap { tokensOf(it) }
            is JsonObject -> emptyList()
            is JsonPrimitive -> {
                if (!element.isString) {
                    val b = element.booleanOrNull
                    if (b != null) return listOf(if (b) "true" else "false")
                }
                val raw = element.content.trim()
                if (raw.isEmpty()) emptyList() else splitAnswer(raw)
            }
        }
    }

    /** "A,C" / "A、C" / "AC" / "1,3" 都要能拆开。 */
    private fun splitAnswer(raw: String): List<String> {
        val cleaned = raw.replace(Regex("^[\\s\\[\\]()（）]+|[\\s\\[\\]()（）]+$"), "")
        if (cleaned.isEmpty()) return emptyList()

        val parts = cleaned.split(Regex("[,，、;；/\\s]+")).filter { it.isNotBlank() }
        if (parts.size > 1) return parts

        val single = parts.firstOrNull() ?: return emptyList()
        if (single.length in 2..6 && single.all { it in 'A'..'Z' }) {
            return single.map { it.toString() }
        }
        return listOf(single)
    }

    private fun mapToken(token: String, options: List<QuestionOption>): String? {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return null

        options.firstOrNull { it.optionId == trimmed }?.let { return it.optionId }
        options.firstOrNull { it.optionId.equals(trimmed, ignoreCase = true) }?.let { return it.optionId }

        trimmed.toIntOrNull()?.let { number ->
            return if (number in 1..options.size) options[number - 1].optionId else null
        }

        if (trimmed.length == 1) {
            val letter = trimmed[0].uppercaseChar()
            if (letter in 'A'..'Z') {
                val idx = letter - 'A'
                if (idx in options.indices) return options[idx].optionId
            }
        }

        when (trimmed.lowercase()) {
            "true", "t", "正确", "对", "√" -> return options.getOrNull(0)?.optionId
            "false", "f", "错误", "错", "×" -> return options.getOrNull(1)?.optionId
        }
        return null
    }

    /**
     * 打开一条"题目流"。
     *
     * 整份 JSON 仍然走 DOM（这个格式本来就要整体解析才能确认合法）；
     * 但 NDJSON 是逐行的 —— 十万题时把每行再拼成一个 JsonArray 等于凭空多复制一份语法树，
     * 这里用惰性序列，解析一行、判定一行、丢一行。
     *
     * 行内容不合法时给出 `null` 而不是整份失败：一条脏数据不该拖死整次导入。
     */
    private fun openStream(text: String, notes: MutableList<String>): ElementStream {
        runCatching { json.parseToJsonElement(text) }.getOrNull()?.let { root ->
            when (root) {
                is JsonArray -> {
                    notes += "识别为 JSON 数组"
                    return ElementStream(root.asSequence(), root.size)
                }

                is JsonObject -> {
                    val nested = root["questions"] ?: root["items"] ?: root["data"]
                    if (nested is JsonArray) {
                        notes += "识别为 JSON 对象"
                        return ElementStream(nested.asSequence(), nested.size)
                    }
                    return ElementStream(emptySequence(), 0, "根对象里没有 questions / items 数组")
                }

                else -> return ElementStream(emptySequence(), 0, "根节点必须是数组或对象")
            }
        }

        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) {
            return ElementStream(
                emptySequence(),
                0,
                "无法解析为 JSON。支持：JSON 数组、带 questions 字段的对象、NDJSON（每行一个 JSON 对象）。",
            )
        }
        notes += "识别为 NDJSON（每行一个 JSON 对象），共 ${lines.size} 行"
        return ElementStream(
            elements = lines.asSequence().map { line -> runCatching { json.parseToJsonElement(line) }.getOrNull() },
            count = lines.size,
        )
    }

    /** 元素为 null 表示这一行不是合法 JSON（只有 NDJSON 会出现）。 */
    private class ElementStream(
        val elements: Sequence<JsonElement?>,
        val count: Int,
        val error: String? = null,
    )

    // ---- 字段小工具 ----

    private fun questionIdOf(obj: JsonObject, ordinal: Int): String {
        val explicit = text(obj, "questionId", "id")
        if (!explicit.isNullOrBlank()) return explicit.trim()
        return "q-${ordinal.toString().padStart(4, '0')}"
    }

    private fun explanationOf(obj: JsonObject): String? =
        text(obj, "explanation", "explanationMarkdown", "解析")

    private fun revisionOf(obj: JsonObject): Int =
        (obj["contentRevision"] as? JsonPrimitive)?.content?.toIntOrNull()?.coerceAtLeast(1) ?: 1

    private fun boolean(obj: JsonObject, key: String): Boolean? =
        (obj[key] as? JsonPrimitive)?.booleanOrNull

    private fun text(obj: JsonObject, vararg keys: String): String? {
        keys.forEach { key ->
            val element = obj[key] ?: return@forEach
            element.asText()?.let { if (it.isNotBlank()) return it }
        }
        return null
    }

    private fun JsonElement.asText(): String? = when (this) {
        is JsonPrimitive -> content
        is JsonArray -> joinToString(", ") { it.asText().orEmpty() }
        is JsonObject -> null
    }
}
