package morefish.knowledge.trainer.domain.model

/**
 * 一道**完整**题目是否自洽。
 *
 * 与 `.qbank` 导入器里那套检查不是同一件事，所以没有合并：
 *
 * - 导入器检查的是"结构层与文案层对不对得上"（`optionIds` 与文案里的选项是否同一批），
 *   那是两种文件之间的核对；
 * - 这里检查的是"这道题自己成不成立"（单选给了两个正确答案、判断题有五个选项），
 *   与包格式无关。
 *
 * 补丁会**改出新的题目**，改完必须重新过一遍这些规则 ——
 * 作者把一道多选的正确答案删到只剩一个，格式上完全合法，语义上已经不是多选题了。
 *
 * 规则与 `tools/qbank/ktqbf/qbpatch.py::question_problems` 一一对应：
 * 作者那边通过、用户那边被拒（或反过来）是最难查的一类问题。
 */
object QuestionRules {

    /** 题干、讲解、选项文案共用的字数上限。 */
    const val MAX_TEXT_CHARS: Int = 20_000

    fun problemsOf(question: Question): List<String> {
        val problems = mutableListOf<String>()

        if (question.questionId.isBlank()) {
            problems += "questionId 为空"
        }
        if (question.promptMarkdown.isBlank()) {
            problems += "题干为空"
        } else if (question.promptMarkdown.length > MAX_TEXT_CHARS) {
            problems += "题干超过 $MAX_TEXT_CHARS 字"
        }
        if ((question.explanationMarkdown?.length ?: 0) > MAX_TEXT_CHARS) {
            problems += "讲解超过 $MAX_TEXT_CHARS 字"
        }

        val optionIds = question.options.map { it.optionId }
        val duplicated = optionIds.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicated.isNotEmpty()) {
            problems += "选项 id 重复：${duplicated.joinToString("、")}"
        }
        if (question.options.any { it.optionId.isBlank() }) {
            problems += "有选项的 id 为空"
        }
        if (question.options.any { it.contentMarkdown.isBlank() }) {
            problems += "有选项的文案为空"
        }

        when (question.type) {
            QuestionType.SHORT_ANSWER -> {
                if (question.answerText.isNullOrBlank()) problems += "简答题没有标准答案"
                if (question.options.isNotEmpty()) problems += "简答题不应该有选项"
                if (question.correctOptionIds.isNotEmpty()) problems += "简答题不应该有 correctOptionIds"
                if (question.grading !is GradingSpec.StrictText && question.grading !is GradingSpec.Keywords) {
                    problems += "简答题只能用 strict-text 或 keywords 判题"
                }
            }

            QuestionType.ORDERING -> {
                if (question.options.size < 2) problems += "重组题至少需要两个片段"
                if (question.correctOptionIds.size != question.options.size ||
                    question.correctOptionIds.toSet() != optionIds.toSet()
                ) {
                    problems += "重组题的正确答案必须是全部片段的完整排列"
                }
                val grading = question.grading
                if (grading !is GradingSpec.Ordering) {
                    problems += "重组题必须用 ordering 判题"
                } else if (grading.canonical.isNotEmpty() && grading.canonical != question.correctOptionIds.toList()) {
                    // 两处都表达"标准次序"，不一致时以哪个为准就成了隐藏规则
                    problems += "重组题的 canonical 与 correctOptionIds 次序不一致"
                }
            }

            QuestionType.SINGLE_CHOICE, QuestionType.MULTIPLE_CHOICE, QuestionType.TRUE_FALSE -> {
                if (question.options.size < 2) problems += "选择题至少需要两个选项"
                if (question.correctOptionIds.isEmpty()) problems += "没有正确答案"
                if (!question.correctOptionIds.all { it in optionIds }) {
                    problems += "正确答案里有不在选项中的 id"
                }
                if (question.type == QuestionType.SINGLE_CHOICE && question.correctOptionIds.size != 1) {
                    problems += "单选题必须恰好一个正确答案"
                }
                if (question.type == QuestionType.MULTIPLE_CHOICE && question.correctOptionIds.size < 2) {
                    problems += "多选题至少两个正确答案"
                }
                if (question.type == QuestionType.TRUE_FALSE) {
                    if (question.options.size != 2) problems += "判断题必须恰好两个选项"
                    if (question.correctOptionIds.size != 1) problems += "判断题必须恰好一个正确答案"
                }
                if (question.grading !is GradingSpec.ExactSet) {
                    problems += "选择题必须用 exact-set 判题"
                }
            }
        }
        return problems
    }

    /**
     * 把答案写成人能读的一句话。
     *
     * 补丁预览要展示"原来是什么 → 现在是什么"，而 `{"o1","o2"}` 对人没有意义。
     * 选项按 A/B/C 的当前顺序标注，简答题取前 40 字。
     */
    fun answerLabel(question: Question): String = when (question.type) {
        QuestionType.SHORT_ANSWER -> {
            val text = question.answerText.orEmpty().replace('\n', ' ').trim()
            if (text.length <= 40) text else text.take(40) + "…"
        }

        QuestionType.ORDERING -> {
            val byId = question.options.associateBy { it.optionId }
            question.correctOptionIds.mapNotNull { byId[it]?.contentMarkdown?.take(12) }.joinToString(" → ")
                .ifBlank { "（次序为空）" }
        }

        else -> {
            val byId = question.options.associateBy { it.optionId }
            question.options.mapIndexedNotNull { index, option ->
                if (option.optionId !in question.correctOptionIds) {
                    null
                } else {
                    "${label(index)}. ${byId[option.optionId]?.contentMarkdown.orEmpty().take(18)}"
                }
            }.joinToString("；").ifBlank { "（没有正确答案）" }
        }
    }

    /** 0 → A、25 → Z、26 → AA。选项再多也不会出现空标签。 */
    fun label(index: Int): String {
        if (index < 0) return "?"
        var n = index
        val out = StringBuilder()
        while (true) {
            out.insert(0, ('A' + (n % 26)))
            n = n / 26 - 1
            if (n < 0) break
        }
        return out.toString()
    }
}
