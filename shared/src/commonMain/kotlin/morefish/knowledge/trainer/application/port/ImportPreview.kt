package morefish.knowledge.trainer.application.port

import morefish.knowledge.trainer.domain.model.Question
import morefish.knowledge.trainer.domain.model.QuestionType
import morefish.knowledge.trainer.domain.model.ValidationIssue

/**
 * 导入预览：导入器的输出、界面的输入。
 *
 * 这些类型放在 application/port 而不是某个具体导入器旁边，是为了让"预览"这件事
 * 成为用例契约的一部分：文本导入器、`.qbank` 导入器、界面、测试都引用同一份定义，
 * 而表现层不需要认识 `infrastructure.importing` 这个包。
 */

/** 被拒绝的单条题目。ordinal 是它在原始材料里的序号，方便用户回去改。 */
data class RejectedQuestion(
    val ordinal: Int,
    val promptExcerpt: String,
    val issues: List<ValidationIssue>,
)

data class ImportPreview(
    val accepted: List<Question>,
    val rejected: List<RejectedQuestion>,
    val issues: List<ValidationIssue>,
    val notes: List<String>,
    /** 存在"题型含糊、需要用户指定"的题目；此时不应直接导入。 */
    val needsMapping: Boolean,
    val ambiguousCount: Int,
) {
    val detectedTypes: Map<QuestionType, Int>
        get() = accepted.groupingBy { it.type }.eachCount().toList()
            .sortedBy { it.first.ordinal }
            .toMap()

    val hasContent: Boolean get() = accepted.isNotEmpty()
}

sealed interface ImportParseResult {
    data class Ok(val preview: ImportPreview) : ImportParseResult
    data class Failed(val message: String) : ImportParseResult
}
