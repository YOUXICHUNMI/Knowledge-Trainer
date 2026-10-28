package morefish.knowledge.trainer.infrastructure.storage

import kotlinx.serialization.Serializable

/**
 * 持久化 DTO。与领域模型分离，目的是让磁盘格式拥有独立的版本与迁移空间，
 * 领域模型调整时不必被迫改变已写入用户设备的数据格式。
 */
@Serializable
internal data class BankFileDto(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val banks: List<BankDto> = emptyList(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

/**
 * 题库目录（schema 2）：只存元数据，题目在 `banks/<bankId>/questions-v1.ndjson`。
 *
 * 拆开的理由很实在：十万题的题库塞进一个 JSON 时，**每次打开应用都要解析十万道题**
 * 才画得出首页的题库卡；而写一个库时还得把其他库全部读回内存再整体重写。
 * 拆开之后，首页只看目录（几十字节一库），写库只写自己那一份。
 */
@Serializable
internal data class BankCatalogDto(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val banks: List<BankMetaDto> = emptyList(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
    }
}

@Serializable
internal data class BankMetaDto(
    val bankId: String,
    val title: String,
    val sourceLocale: String,
    val importedAtMillis: Long,
    val sourceLabel: String,
    val contentRevision: Int = 1,
    val fingerprint: String = "",
    /** 题目条数。首页与列表用它，不必解析题目文件。 */
    val questionCount: Int = 0,
    /** 题型分布，wireName → 条数。 */
    val typeCounts: Map<String, Int> = emptyMap(),
    /**
     * 各语言覆盖度。旧目录没有这一段，读回来是空列表 → 视为"只有源语言"。
     * 放在目录里是为了不解析题目就能在首页与设置页显示"英文只翻了 2/6 题"。
     */
    val locales: List<LocaleCoverageDto> = emptyList(),
    /**
     * 包级出处信息。放在目录里（而不是题目文件里）是因为它属于"这个包"，
     * 而且导入预览、题库详情要不解析题目就能显示"作者 / 许可 / 内容摘要"。
     */
    val provenance: ProvenanceDto = ProvenanceDto(),
)

@Serializable
internal data class LocaleCoverageDto(
    val locale: String,
    val covered: Int = 0,
    val total: Int = 0,
)

@Serializable
internal data class BankDto(
    val bankId: String,
    val title: String,
    val sourceLocale: String,
    val importedAtMillis: Long,
    val sourceLabel: String,
    val contentRevision: Int = 1,
    val fingerprint: String = "",
    val questions: List<QuestionDto> = emptyList(),
)

@Serializable
internal data class QuestionDto(
    val questionId: String,
    val type: String,
    val promptMarkdown: String,
    val options: List<OptionDto> = emptyList(),
    val correctOptionIds: List<String> = emptyList(),
    val answerText: String? = null,
    val explanationMarkdown: String? = null,
    val assetIds: List<String> = emptyList(),
    val shuffleAllowed: Boolean = true,
    val grading: String = "exact-set",
    /** 带参数判题策略（keywords/ordering）的参数 JSON；无参数策略为 null。 */
    val gradingParams: String? = null,
    val tags: List<String> = emptyList(),
    val contentRevision: Int = 1,
    /** 内联文案的语言；null = 旧数据未声明。 */
    val locale: String? = null,
    /** 其他语言的译文。源语言永远在内联字段里，这里只放"另外几份"。 */
    val translations: List<QuestionTextDto> = emptyList(),
    /** 出处：wireName（authored / adapted / imported / ai-assisted）；空串 = 未声明。 */
    val origin: String = "",
    val sourceId: String? = null,
    val sourceLocator: String? = null,
)

@Serializable
internal data class QuestionTextDto(
    val locale: String,
    val promptMarkdown: String,
    val optionTexts: Map<String, String> = emptyMap(),
    val answerText: String? = null,
    val explanationMarkdown: String? = null,
    val contentRevision: Int = 1,
)

@Serializable
internal data class OptionDto(
    val optionId: String,
    val contentMarkdown: String,
)

@Serializable
internal data class AttemptDto(
    val attemptId: String,
    val sessionId: String,
    val bankId: String,
    val questionId: String,
    val questionType: String,
    val mode: String,
    val displayOptionIds: List<String> = emptyList(),
    val selectedOptionIds: List<String> = emptyList(),
    /** 重组题作答的片段次序；非重组题为空。 */
    val orderedOptionIds: List<String> = emptyList(),
    val textAnswer: String? = null,
    val verdict: String,
    val strategyId: String,
    val gradingSpec: String,
    val answerRevision: Int = 1,
    val questionFingerprint: String = "",
    val submittedAtMillis: Long,
    val elapsedMillis: Long = 0,
    val explanationSeen: Boolean = false,
    /** 作答时是否用过辅助工具（计算器 / 单位换算）。旧记录没有这个字段，读作 false。 */
    val usedTools: Boolean = false,
    /** 作答所用语言。旧记录没有这个字段，读作空串（未声明）。 */
    val locale: String = "",
)

/**
 * 未完成会话的快照。每题都带完整题目副本 —— 恢复时不需要回头查题库，
 * 也因此不会因为题库更新而在途中换答案。
 */
@Serializable
internal data class SessionFileDto(
    val schemaVersion: Int = 1,
    val session: SessionDto? = null,
)

@Serializable
internal data class SessionDto(
    val sessionId: String,
    val bankId: String,
    val bankTitle: String = "",
    val planLabel: String = "",
    val mode: String = "native",
    val state: String = "IN_PROGRESS",
    val cursor: Int = 0,
    val createdAtMillis: Long,
    val updatedAtMillis: Long = createdAtMillis,
    val items: List<SessionItemDto> = emptyList(),
    val draft: DraftDto = DraftDto(),
    /** 本场练习的语言。旧会话没有这个字段，读回来是空串（按源语言处理）。 */
    val locale: String = "",
)

@Serializable
internal data class SessionItemDto(
    val question: QuestionDto,
    val displayOptionIds: List<String> = emptyList(),
)

@Serializable
internal data class DraftDto(
    val selectedOptionIds: List<String> = emptyList(),
    val textAnswer: String = "",
    val orderedOptionIds: List<String> = emptyList(),
)

@Serializable
internal data class FavoritesFileDto(
    val schemaVersion: Int = 1,
    val items: List<String> = emptyList(),
)

/** 笔记文件。整文件重写：笔记总量远小于题库，换来的是"删得干净、改得利落"。 */
@Serializable
internal data class NotesFileDto(
    val schemaVersion: Int = 1,
    val notes: List<NoteDto> = emptyList(),
)

@Serializable
internal data class NoteDto(
    val noteId: String,
    val scope: String,
    val bankId: String? = null,
    val questionId: String? = null,
    val title: String = "",
    val bodyMarkdown: String = "",
    val pinned: Boolean = false,
    val createdAtMillis: Long = 0,
    val updatedAtMillis: Long = 0,
)

/**
 * 个人知识图谱文件。整文件重写：图谱是用户手工积累的，条数量级远小于题库。
 *
 * 关系只存引用，快照单独一张表 —— 同一条题可能被多条关系引用，
 * 快照跟着引用存一份会立刻出现"同一道题有两个不一致的版本号"。
 */
@Serializable
internal data class GraphFileDto(
    val schemaVersion: Int = 1,
    val snapshots: List<GraphSnapshotDto> = emptyList(),
    val edges: List<GraphEdgeDto> = emptyList(),
)

@Serializable
internal data class GraphSnapshotDto(
    val bankId: String,
    val questionId: String,
    val bankTitle: String = "",
    val promptExcerpt: String = "",
    val contentRevision: Int = 1,
    val capturedAtMillis: Long = 0,
)

@Serializable
internal data class GraphEdgeDto(
    val edgeId: String,
    val sourceBankId: String,
    val sourceQuestionId: String,
    val targetBankId: String,
    val targetQuestionId: String,
    val relation: String,
    val dependency: String = "navigation",
    val noteMarkdown: String = "",
    val createdAtMillis: Long = 0,
    val updatedAtMillis: Long = 0,
)

/**
 * 已安装的扩展包。**不存"是否生效"**：那取决于题库在不在，
 * 存下来只会在用户卸载题库之后变成一句过期的谎话。
 */
@Serializable
internal data class ExtensionsFileDto(
    val schemaVersion: Int = 1,
    val packs: List<ExtensionPackDto> = emptyList(),
)

@Serializable
internal data class ExtensionPackDto(
    val packageId: String,
    val name: String,
    val version: String,
    val author: String = "",
    val license: String = "",
    val bankId: String,
    val bankTitleHint: String = "",
    val importedAtMillis: Long = 0,
    val sourceLabel: String = "",
    val relations: List<ExtensionRelationDto> = emptyList(),
    val annotations: List<ExtensionAnnotationDto> = emptyList(),
)

@Serializable
internal data class ExtensionRelationDto(
    val relationId: String,
    val sourceQuestionId: String,
    val targetBankId: String,
    val targetQuestionId: String,
    val relation: String,
    val dependency: String = "navigation",
    val noteMarkdown: String = "",
)

@Serializable
internal data class ExtensionAnnotationDto(
    val annotationId: String,
    val questionId: String? = null,
    val title: String = "",
    val bodyMarkdown: String = "",
)

// ---------------------------------------------------------------- AI（P7）

/**
 * AI 接入配置。**不含密钥** —— 密钥在 `ai-credential-v1.json`，
 * 分开存是为了"导出配置"或"贴到聊天里问人"时不会顺手泄漏密钥。
 */
@Serializable
internal data class AiConfigFileDto(
    val schemaVersion: Int = 1,
    val baseUrl: String = "",
    val model: String = "",
    val maxOutputTokens: Int = 700,
    val temperature: Double = 0.2,
    val timeoutMillis: Long = 30_000,
    val maxCallsPerDay: Int = 40,
    val maxTokensPerDay: Int = 60_000,
    val maxCostMicrosPerDay: Long = 0,
    val promptMicrosPerMillion: Long = 0,
    val completionMicrosPerMillion: Long = 0,
    val uploadConsent: Boolean = false,
    val rememberKey: Boolean = true,
)

@Serializable
internal data class AiCredentialFileDto(
    val schemaVersion: Int = 1,
    val apiKey: String = "",
)

@Serializable
internal data class AiExplanationsFileDto(
    val schemaVersion: Int = 1,
    val records: List<AiExplanationDto> = emptyList(),
)

@Serializable
internal data class AiExplanationDto(
    val key: String,
    val bankId: String,
    val questionId: String,
    val locale: String = "",
    val questionRevision: Int = 1,
    val model: String = "",
    val state: String,
    val text: String = "",
    val error: String = "",
    val createdAtMillis: Long = 0,
    val updatedAtMillis: Long = 0,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val costMicros: Long = 0,
)

/** 账本一行。NDJSON 追加写：花过的钱不该因为"保存时重写文件"而丢失。 */
@Serializable
internal data class AiUsageEntryDto(
    val day: Long,
    val at: Long,
    val key: String = "",
    val model: String = "",
    val ok: Boolean = true,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val costMicros: Long = 0,
)

/** 批量任务清单。**不含讲解正文** —— 正文在 `ai-explanations-v1.json`，一份内容只有一处。 */
@Serializable
internal data class AiBatchesFileDto(
    val schemaVersion: Int = 1,
    val jobs: List<AiBatchJobDto> = emptyList(),
)

@Serializable
internal data class AiBatchJobDto(
    val jobId: String,
    val bankId: String,
    val bankTitle: String = "",
    val model: String = "",
    val locale: String = "",
    val state: String,
    val cursor: Int = 0,
    val maxCalls: Int = 0,
    val note: String = "",
    val createdAtMillis: Long = 0,
    val updatedAtMillis: Long = 0,
    val items: List<AiBatchItemDto> = emptyList(),
)

@Serializable
internal data class AiBatchItemDto(
    val bankId: String = "",
    val questionId: String,
    val locale: String = "",
    val questionRevision: Int = 1,
    val contentFingerprint: String = "",
    val state: String,
    val attempts: Int = 0,
    val note: String = "",
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val costMicros: Long = 0,
)

// ---------------------------------------------------------------- 修订日志（P8）

@Serializable
internal data class RevisionsFileDto(
    val schemaVersion: Int = 1,
    val banks: List<BankRevisionLogDto> = emptyList(),
)

@Serializable
internal data class BankRevisionLogDto(
    val bankId: String,
    val entries: List<RevisionEntryDto> = emptyList(),
)

/** 一处改动摘要。旧题目全文不存 —— 修订日志要说明"发生了什么"，不是用来回滚的。 */
@Serializable
internal data class RevisionEntryDto(
    val revision: Int,
    val patchId: String = "",
    val name: String = "",
    val atMillis: Long = 0,
    val added: Int = 0,
    val updated: Int = 0,
    val removed: Int = 0,
    val answerChangedIds: List<String> = emptyList(),
    val affectedAttempts: Int = 0,
    val note: String = "",
)

/**
 * 一条历史复核决定（`attempt-reviews-v1.ndjson`，一行一条，只追加）。
 *
 * 存 `previousVerdict` 与 `effectiveVerdict` 两个判定而不是只存"结论"：
 * 决定写入之后题目可能又被改过，那时要能回答"当初是从什么改到什么"。
 */
@Serializable
internal data class AttemptReviewDto(
    val attemptId: String,
    val bankId: String = "",
    val questionId: String = "",
    /** `accept` 或 `restore`。 */
    val decision: String,
    val previousVerdict: String,
    val effectiveVerdict: String,
    /** 复核所依据的题目内容指纹；题目再改一次时，旧决定要重新问。 */
    val basisFingerprint: String = "",
    val decidedAtMillis: Long = 0,
)

/**
 * 包级出处。旧目录没有这一段，读回来是空对象 → 视为"没有声明任何出处"。
 *
 * [digestVerified] 与 [digest] 分开存：老包没有摘要，界面要写"未提供"而不是打勾。
 */
@Serializable
internal data class ProvenanceDto(
    val sources: List<PackageSourceDto> = emptyList(),
    val digest: String = "",
    val digestVerified: Boolean = false,
    val authorName: String = "",
    val license: String = "",
)

@Serializable
internal data class PackageSourceDto(
    val sourceId: String,
    val title: String,
    val kind: String = "",
    val author: String = "",
    val edition: String = "",
    val url: String = "",
    val license: String = "",
    val note: String = "",
)
