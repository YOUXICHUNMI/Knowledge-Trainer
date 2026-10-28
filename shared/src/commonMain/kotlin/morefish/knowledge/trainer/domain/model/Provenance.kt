package morefish.knowledge.trainer.domain.model

/**
 * 一道题是怎么来的。
 *
 * 这不是"可信度评分"——[AUTHORED] 不比 [IMPORTED] 更可信，一份认真改编自教材的题
 * 往往比作者顺手编的题更可靠。这个字段只回答"我该去哪里核对它"。
 *
 * [UNSPECIFIED] 的含义是**作者没说**，不是"作者原创"。界面上必须把两者分开写，
 * 把"没说"渲染成"原创"是本项目最不愿意犯的那类错误。
 */
enum class ProvenanceOrigin(val wireName: String, val label: String) {
    AUTHORED("authored", "作者原创"),
    ADAPTED("adapted", "改编自来源"),
    IMPORTED("imported", "直接引用"),
    AI_ASSISTED("ai-assisted", "AI 参与生成"),
    UNSPECIFIED("", "来源未标注");

    companion object {
        /**
         * 只认约定的四个值。拼错（`copied`、`ai`）一律当未声明处理并由校验器报错，
         * 不"猜一个最接近的"——猜错等于替作者声明了一件他没说过的事。
         */
        fun fromWire(value: String?): ProvenanceOrigin = when (value?.trim()?.lowercase()) {
            "authored" -> AUTHORED
            "adapted" -> ADAPTED
            "imported" -> IMPORTED
            "ai-assisted", "ai_assisted" -> AI_ASSISTED
            else -> UNSPECIFIED
        }
    }
}

/** 一条出处声明。`sourceId` 是题目里引用它的键，其余是给人读的。 */
data class PackageSource(
    val sourceId: String,
    val title: String,
    val kind: String = "",
    val author: String = "",
    val edition: String = "",
    val url: String = "",
    val license: String = "",
    val note: String = "",
) {
    /**
     * 一行能写下的出处：书名 + 版次。
     *
     * **不含作者**：作者属于整个题库（首页卡片上写着），每道题都重复一遍只是噪声。
     * 版次要留 —— 同一本书的两版可能订正过答案。
     */
    val shortLabel: String
        get() = buildList {
            add(title)
            if (edition.isNotBlank()) add(edition)
        }.joinToString(" · ")
}

/**
 * 一个题库的来源信息。
 *
 * [digest] 是整包内容摘要（见 `PackageDigest`）：回答"这份包还是作者发出来的那一份吗"。
 * [digestVerified] 为 false 表示包内没有声明摘要（老包），此时**不能**当成"已核对"，
 * 界面上要写成"未提供"，而不是打勾。
 */
data class BankProvenance(
    val sources: List<PackageSource> = emptyList(),
    val digest: String = "",
    val digestVerified: Boolean = false,
    val authorName: String = "",
    val license: String = "",
) {
    fun source(sourceId: String?): PackageSource? =
        if (sourceId.isNullOrBlank()) null else sources.firstOrNull { it.sourceId == sourceId }
}

/** 一个题库里"来源标注"的整体面貌。导入预览与题库详情用它，避免各页各算一套。 */
data class ProvenanceSummary(
    val total: Int,
    val withSource: Int,
    val undeclared: Int,
    val byOrigin: Map<ProvenanceOrigin, Int>,
) {
    /** 有明确出处的比例。分母是全部题目 —— 只报"有多少道有出处"会让人误以为其余的是原创。 */
    val sourcedPercent: Int get() = if (total == 0) 0 else withSource * 100 / total

    val aiAssisted: Int get() = byOrigin[ProvenanceOrigin.AI_ASSISTED] ?: 0

    companion object {
        fun of(questions: List<Question>): ProvenanceSummary {
            val byOrigin = mutableMapOf<ProvenanceOrigin, Int>()
            var withSource = 0
            for (question in questions) {
                val origin = question.origin
                byOrigin[origin] = (byOrigin[origin] ?: 0) + 1
                if (!question.sourceId.isNullOrBlank()) withSource++
            }
            return ProvenanceSummary(
                total = questions.size,
                withSource = withSource,
                undeclared = byOrigin[ProvenanceOrigin.UNSPECIFIED] ?: 0,
                byOrigin = byOrigin,
            )
        }
    }
}
