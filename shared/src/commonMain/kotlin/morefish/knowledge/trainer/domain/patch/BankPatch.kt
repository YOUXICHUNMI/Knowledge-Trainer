package morefish.knowledge.trainer.domain.patch

import morefish.knowledge.trainer.domain.i18n.LocaleCoverage
import morefish.knowledge.trainer.domain.i18n.LocaleTag
import morefish.knowledge.trainer.domain.model.Fingerprint
import morefish.knowledge.trainer.domain.model.ProvenanceOrigin
import morefish.knowledge.trainer.domain.model.Question
import morefish.knowledge.trainer.domain.model.QuestionBank
import morefish.knowledge.trainer.domain.model.QuestionOption
import morefish.knowledge.trainer.domain.model.QuestionRules

/**
 * 一条补丁操作。
 *
 * 没有"改题型"这种操作：题型一变，判题策略、历史口径、"什么算答对"会同时变化，
 * 那是换了一道题而不是改了这道题。作者要换题型就 remove + add，让人一眼看见。
 */
sealed interface BankPatchOp {
    data class Add(val question: Question) : BankPatchOp

    /**
     * 字段级修改。[changes] 里为 null 的字段表示"不动"。
     *
     * [expect] 是"作者以为这道题现在长这样"的指纹声明。作者不用手写 ——
     * 制包工具从旧包里算出来填上。它的用处是把"补丁基于的不是这一版"从
     * "结果看起来有点怪"变成一条明确的拒绝理由。
     */
    data class Update(
        val questionId: String,
        val expect: String?,
        val changes: QuestionChanges,
    ) : BankPatchOp

    data class Remove(val questionId: String, val expect: String?) : BankPatchOp
}

/**
 * `update` 能改的字段。null = 保持原值。
 *
 * 出处的三个字段用**空串表示清空**（`sourceId = ""` 会把"引自某书"变回"没有出处"）。
 * 这里不能用 null 表示清空 —— null 已经被"不改"占用了。
 */
data class QuestionChanges(
    val promptMarkdown: String? = null,
    val options: List<QuestionOption>? = null,
    val correctOptionIds: Set<String>? = null,
    val answerText: String? = null,
    val explanationMarkdown: String? = null,
    val assetIds: List<String>? = null,
    val shuffleAllowed: Boolean? = null,
    val tags: List<String>? = null,
    val sourceId: String? = null,
    val sourceLocator: String? = null,
    val origin: ProvenanceOrigin? = null,
) {
    val isEmpty: Boolean
        get() = promptMarkdown == null && options == null && correctOptionIds == null &&
            answerText == null && explanationMarkdown == null && assetIds == null &&
            shuffleAllowed == null && tags == null && sourceId == null &&
            sourceLocator == null && origin == null

    /** 只改了出处、没动题目内容。预览里单列一类，免得看起来像"题面又变了"。 */
    val touchesOnlyProvenance: Boolean
        get() = !isEmpty && promptMarkdown == null && options == null && correctOptionIds == null &&
            answerText == null && explanationMarkdown == null && assetIds == null &&
            shuffleAllowed == null && tags == null

    fun applyTo(question: Question): Question = question.copy(
        promptMarkdown = promptMarkdown ?: question.promptMarkdown,
        options = options ?: question.options,
        correctOptionIds = correctOptionIds ?: question.correctOptionIds,
        answerText = answerText ?: question.answerText,
        explanationMarkdown = explanationMarkdown ?: question.explanationMarkdown,
        assetIds = assetIds ?: question.assetIds,
        shuffleAllowed = shuffleAllowed ?: question.shuffleAllowed,
        tags = tags ?: question.tags,
        // 空串 = 清空，所以这里不能写 `?:` 把空串当没值
        sourceId = (sourceId ?: question.sourceId)?.takeIf { it.isNotBlank() },
        sourceLocator = (sourceLocator ?: question.sourceLocator)?.takeIf { it.isNotBlank() },
        origin = origin ?: question.origin,
    )
}

/**
 * 一份已通过格式校验的更新补丁。
 *
 * [baseFingerprint] 是整个机制的地基：它回答"这份补丁改的是哪一版"。
 * 应用端拿本地题库重算后比对，对不上就整包拒绝 —— 补丁没有"尽力而为"模式，
 * 因为盲改的结果是内容乱掉且无法察觉。
 */
data class BankPatch(
    val patchId: String,
    val version: String,
    val name: String,
    val bankId: String,
    val baseRevision: Int,
    val targetRevision: Int,
    val baseFingerprint: String,
    val operations: List<BankPatchOp>,
    /** 非 null 表示这次更新同时改了题库标题。 */
    val title: String? = null,
    val notes: String = "",
    val createdAt: String = "",
) {
    val addedCount: Int get() = operations.count { it is BankPatchOp.Add }
    val updatedCount: Int get() = operations.count { it is BankPatchOp.Update }
    val removedCount: Int get() = operations.count { it is BankPatchOp.Remove }
}

/** 一道题在这次更新里发生了什么。 */
enum class PatchChangeKind(val label: String) {
    /** 正确答案 / 标准答案 / 判题策略变了 —— **历史判定可能与现在不一致**。 */
    ANSWER("答案变更"),

    /** 题干或选项文案变了，但"什么算答对"没变。 */
    CONTENT("题面变更"),

    /**
     * 只有出处变了（补了一个漏写的来源、改成"AI 参与生成"）。
     *
     * 单列一类是有意的：它不是内容变更，不换修订号、不影响讲解缓存，
     * 混进"题面变更"会让人以为这道题被改过。
     */
    PROVENANCE("出处变更"),

    ADDED("新增"),
    REMOVED("删除"),
}

/**
 * 报告里的一行。补丁预览就是这份清单 —— 让人在点"应用"之前逐条看见会改什么。
 *
 * [before] / [after] 对人类可读：选择题写"A. 建立 TCP 连接"而不是 `{o2}`。
 */
data class PatchEntry(
    val questionId: String,
    val kind: PatchChangeKind,
    val title: String,
    val before: String,
    val after: String,
)

/** 应用结果的结构性说明，界面与修订记录都用它。 */
data class PatchReport(
    val entries: List<PatchEntry>,
    val titleBefore: String,
    val titleAfter: String,
    val beforeRevision: Int,
    val afterRevision: Int,
) {
    val added: Int get() = entries.count { it.kind == PatchChangeKind.ADDED }
    val updated: Int get() = entries.count {
        it.kind == PatchChangeKind.CONTENT || it.kind == PatchChangeKind.ANSWER ||
            it.kind == PatchChangeKind.PROVENANCE
    }
    val removed: Int get() = entries.count { it.kind == PatchChangeKind.REMOVED }
    val answerChanges: List<PatchEntry> get() = entries.filter { it.kind == PatchChangeKind.ANSWER }
    val titleChanged: Boolean get() = titleBefore != titleAfter
}

/** 一条拒绝理由。[questionId] 非空时界面可以定位到具体哪道题。 */
data class PatchConflict(
    val code: String,
    val message: String,
    val questionId: String? = null,
)

sealed interface PatchApply {
    data class Ok(val bank: QuestionBank, val report: PatchReport) : PatchApply

    /** 补丁描述的状态在本地已经成立：不用改任何东西，也不算错误。 */
    data class NoOp(val bank: QuestionBank, val reason: String) : PatchApply

    /** 本地版本比补丁还新：这个补丁应用上去等于把内容往回退。 */
    data class Outdated(val bank: QuestionBank, val patchTarget: Int) : PatchApply

    data class Failed(val conflicts: List<PatchConflict>) : PatchApply
}

/**
 * 把补丁应用到题库上。**纯函数**：不写盘、不碰历史、不做任何 IO。
 *
 * 判定顺序是有讲究的，换一下就会给出误导性的结论：
 *
 * 1. 锚点对得上 → 严格应用（连每条 `expect` 一起校验）
 * 2. 锚点不对 → 先假设"已经应用过"：忽略 expect 重算一遍，结果与本地一致就是 [PatchApply.NoOp]
 * 3. 仍然不同 → 按版本号给出"你比补丁新"或"版本链断了"
 *
 * 第 2 步必须排在"版本太旧"之前：用户装过更新后的完整包时，锚点同样对不上，
 * 但那不是错误，报"版本不符"会让他去重装一个本来就有的东西。
 */
object BankPatcher {

    fun fingerprintOf(questions: List<Question>): String =
        Fingerprint.ofQuestions(questions.map { it.contentFingerprint() })

    fun apply(bank: QuestionBank, patch: BankPatch): PatchApply {
        val currentFingerprint = fingerprintOf(bank.questions)

        // 身份与内容都对不上：这不是"我的题库"，连诊断都不该给得太具体，
        // 说清楚"不是这个库"就够了，免得用户照着去改自己的题库
        if (bank.bankId != patch.bankId && currentFingerprint != patch.baseFingerprint) {
            return PatchApply.Failed(
                listOf(
                    PatchConflict(
                        "BANK_MISMATCH",
                        "这个补丁是给另一个题库的（补丁 ${patch.bankId.take(8)}…，" +
                            "本地 ${bank.bankId.take(8)}…），内容也对不上，所以不会被应用。",
                    ),
                ),
            )
        }

        if (currentFingerprint == patch.baseFingerprint) {
            return when (val computed = compute(bank, patch, checkExpect = true)) {
                is Computed.Failed -> {
                    // 严格模式（锚点对得上）下，"每条改动在本地都已是现状"同样是"装过了"，
                    // 不是失败。只改出处的补丁最容易走到这里：它不改变内容指纹，
                    // 所以第二次安装时锚点依然匹配，改动却已经落过盘了。
                    if (computed.conflicts.all { it.code == CODE_NO_EFFECT }) {
                        PatchApply.NoOp(
                            bank = bank,
                            reason = "这个补丁的每条改动本地都已经成立，不需要再装一次",
                        )
                    } else {
                        PatchApply.Failed(computed.conflicts)
                    }
                }

                is Computed.Done -> PatchApply.Ok(buildBank(bank, patch, computed), computed.report(bank, patch))
            }
        }

        // 不是"基于这一版"，先看是不是"已经改过了"
        val relaxed = compute(bank, patch, checkExpect = false)
        if (relaxed is Computed.Done && fingerprintOf(relaxed.questions) == currentFingerprint) {
            return PatchApply.NoOp(
                bank = bank,
                reason = "这个补丁描述的状态本地已经成立（可能之前应用过，也可能装的是更新后的完整包）",
            )
        }
        if (relaxed is Computed.Failed) {
            // PATCH_NO_EFFECT 是好消息：补丁的每条改动在本地都已经是现状
            if (relaxed.conflicts.all { it.code == CODE_EXPECT || it.code == CODE_NO_EFFECT }) {
                return PatchApply.NoOp(
                    bank = bank,
                    reason = "这个补丁描述的状态本地已经成立（可能之前应用过，也可能装的是更新后的完整包）",
                )
            }
            // 要改的题不在了 / 要加的题已经有了 —— 这是真的对不上，不是"已经改过"
            return PatchApply.Failed(relaxed.conflicts)
        }
        if (bank.contentRevision > patch.targetRevision) {
            return PatchApply.Outdated(bank, patch.targetRevision)
        }
        if (bank.contentRevision == patch.targetRevision) {
            return PatchApply.Failed(
                listOf(
                    PatchConflict(
                        "CONTENT_DIVERGED",
                        "本地题库也是第 ${bank.contentRevision} 版，但内容和补丁说的不一样。" +
                            "这说明手上这份题库被改过，或者补丁基于的不是这一份 —— 两种都不该盲目覆盖。",
                    ),
                ),
            )
        }
        return PatchApply.Failed(
            listOf(
                PatchConflict(
                    "BASE_MISMATCH",
                    "补丁要改的是第 ${patch.baseRevision} 版，本地是第 ${bank.contentRevision} 版。" +
                        "多半是中间漏了一次更新：先装上中间版本的补丁，这一个才接得上。",
                ),
            ) + ((relaxed as? Computed.Failed)?.conflicts.orEmpty().filter { it.code == CODE_EXPECT }),
        )
    }

    private const val CODE_EXPECT = "EXPECT_MISMATCH"
    private const val CODE_NO_EFFECT = "PATCH_NO_EFFECT"

    private sealed interface Computed {
        data class Done(val questions: List<Question>, val entries: List<PatchEntry>, val title: String) : Computed
        data class Failed(val conflicts: List<PatchConflict>) : Computed
    }

    private fun Computed.Done.report(bank: QuestionBank, patch: BankPatch) = PatchReport(
        entries = entries,
        titleBefore = bank.title,
        titleAfter = title,
        beforeRevision = bank.contentRevision,
        afterRevision = patch.targetRevision,
    )

    /**
     * 在内存里算出应用后的题目列表。
     *
     * [checkExpect] 为 false 时只校验"题目在不在"，不校验"内容对不对" ——
     * 这是"已经应用过"的探测路径需要的宽松模式。
     */
    private fun compute(bank: QuestionBank, patch: BankPatch, checkExpect: Boolean): Computed {
        val conflicts = mutableListOf<PatchConflict>()
        val byId = bank.questions.associateBy { it.questionId }.toMutableMap()
        val order = bank.questions.map { it.questionId }.toMutableList()
        val entries = mutableListOf<PatchEntry>()

        fun expectMatches(target: Question, expect: String?, opName: String): Boolean {
            if (!checkExpect || expect == null) return true
            val actual = target.contentFingerprint()
            if (actual == expect) return true
            conflicts += PatchConflict(
                CODE_EXPECT,
                "$opName 说这道题应该是 ${expect.take(8)}…，本地实际是 ${actual.take(8)}…；" +
                    "这道题已经被别的改动动过了",
                target.questionId,
            )
            return false
        }

        for (op in patch.operations) {
            when (op) {
                is BankPatchOp.Add -> {
                    val qid = op.question.questionId
                    val existing = byId[qid]
                    if (existing != null) {
                        // 宽松模式下，同 id 且内容一字不差 → 这道题早就加过了，跳过即可。
                        // 这是"已经应用过"最常见的形态：新增的题还在，其余改动早已落地。
                        val sameContent = existing.contentFingerprint() == op.question.contentFingerprint()
                        if (checkExpect || !sameContent) {
                            conflicts += PatchConflict(
                                "ADD_EXISTS",
                                "补丁要新增 $qid，但本地已经有这道题了（作者应该用 update）",
                                qid,
                            )
                        }
                        continue
                    }
                    val problems = QuestionRules.problemsOf(op.question)
                    if (problems.isNotEmpty()) {
                        conflicts += PatchConflict("QUESTION_INVALID", "$qid 改完之后不成立：${problems.joinToString("；")}", qid)
                        continue
                    }
                    // 新题的内容修订从 1 起：它是这个库里的一道新题，不是"改过的老题"
                    val fresh = op.question.copy(contentRevision = 1)
                    byId[qid] = fresh
                    order += qid
                    entries += PatchEntry(
                        questionId = qid,
                        kind = PatchChangeKind.ADDED,
                        title = summaryOf(fresh),
                        before = "",
                        after = QuestionRules.answerLabel(fresh),
                    )
                }

                is BankPatchOp.Update -> {
                    val current = byId[op.questionId]
                    if (current == null) {
                        conflicts += PatchConflict(
                            "UPDATE_MISSING",
                            "补丁要改 ${op.questionId}，但本地没有这道题",
                            op.questionId,
                        )
                        continue
                    }
                    if (!expectMatches(current, op.expect, "update")) continue

                    val merged = op.changes.applyTo(current)
                    val problems = QuestionRules.problemsOf(merged)
                    if (problems.isNotEmpty()) {
                        conflicts += PatchConflict(
                            "QUESTION_INVALID",
                            "${op.questionId} 改完之后不成立：${problems.joinToString("；")}",
                            op.questionId,
                        )
                        continue
                    }
                    // 内容没变就不动修订号：否则"改了一个空格"也会让所有缓存讲解失效。
                    // 出处单独看：它不进内容指纹，所以"只补了一个来源"不会让修订号跳一格，
                    // 但那也是一次真实的改动，必须写进去 —— 否则作者的修复会被静默丢弃。
                    val contentChanged = merged.contentFingerprint() != current.contentFingerprint()
                    val provenanceChanged = merged.origin != current.origin ||
                        merged.sourceId != current.sourceId ||
                        merged.sourceLocator != current.sourceLocator
                    val changed = contentChanged || provenanceChanged
                    val next = if (contentChanged) {
                        merged.copy(contentRevision = current.contentRevision + 1)
                    } else {
                        merged
                    }
                    byId[op.questionId] = next
                    if (changed) {
                        val kind = when {
                            answerChanged(current, next) -> PatchChangeKind.ANSWER
                            !contentChanged -> PatchChangeKind.PROVENANCE
                            else -> PatchChangeKind.CONTENT
                        }
                        entries += PatchEntry(
                            questionId = op.questionId,
                            kind = kind,
                            title = summaryOf(next),
                            before = when (kind) {
                                PatchChangeKind.ANSWER -> QuestionRules.answerLabel(current)
                                // 出处变更要说"从什么变成什么"，否则预览里只看到题干摘要，
                                // 用户没法判断这次改动是不是自己想要的
                                PatchChangeKind.PROVENANCE -> provenanceLabel(current, bank)
                                else -> summaryOf(current)
                            },
                            after = when (kind) {
                                PatchChangeKind.ANSWER -> QuestionRules.answerLabel(next)
                                PatchChangeKind.PROVENANCE -> provenanceLabel(next, bank)
                                else -> summaryOf(next)
                            },
                        )
                    }
                }

                is BankPatchOp.Remove -> {
                    val current = byId[op.questionId]
                    if (current == null) {
                        conflicts += PatchConflict(
                            "REMOVE_MISSING",
                            "补丁要删 ${op.questionId}，但本地没有这道题",
                            op.questionId,
                        )
                        continue
                    }
                    if (!expectMatches(current, op.expect, "remove")) continue
                    byId.remove(op.questionId)
                    order.remove(op.questionId)
                    entries += PatchEntry(
                        questionId = op.questionId,
                        kind = PatchChangeKind.REMOVED,
                        title = summaryOf(current),
                        before = QuestionRules.answerLabel(current),
                        after = "",
                    )
                }
            }
        }

        if (conflicts.isNotEmpty()) return Computed.Failed(conflicts)
        if (entries.isEmpty()) return Computed.Failed(listOf(PatchConflict(CODE_NO_EFFECT, "补丁没有产生任何改动")))
        // 保持原有题目顺序，新增的排在最后：顺序是作者编排的，不该被更新打乱
        val questions = order.mapNotNull { byId[it] }
        return Computed.Done(questions = questions, entries = entries, title = patch.title ?: bank.title)
    }

    /**
     * "什么算答对"变了吗。
     *
     * 只看答案本身，不看讲解与标签：讲解写错不影响判题，把它算成答案变更会让
     * 历史复核的提示变成噪音，用户就会开始忽略它。
     */
    /** 出处的一行人话。找不到 sources 条目时照实写 id —— 不编一个好看的名字。 */
    private fun provenanceLabel(question: Question, bank: QuestionBank): String {
        val origin = question.origin.label
        val source = bank.provenance.source(question.sourceId)
        val locator = question.sourceLocator
        val where = when {
            source == null && question.sourceId == null -> ""
            source == null -> " · ${question.sourceId}"
            locator == null -> " · ${source.shortLabel}"
            else -> " · ${source.shortLabel} · $locator"
        }
        return origin + where
    }

    private fun answerChanged(before: Question, after: Question): Boolean =
        before.correctOptionIds != after.correctOptionIds ||
            before.answerText != after.answerText

    private fun summaryOf(question: Question): String {
        val text = question.promptMarkdown.replace('\n', ' ').trim()
        return if (text.length <= 32) text else text.take(32) + "…"
    }

    private fun buildBank(bank: QuestionBank, patch: BankPatch, computed: Computed.Done): QuestionBank {
        val questions = computed.questions
        val source = LocaleTag.parse(bank.sourceLocale)?.value ?: bank.sourceLocale
        // 题数变了，覆盖度必须重算：不然界面上会写着"英文 6/6"，实际只剩 5 道题
        val coverage = bank.availableLocales.map { locale ->
            val want = LocaleTag.parse(locale)?.value ?: locale
            val covered = if (want == source) questions.size else questions.count { it.hasTextIn(locale) }
            LocaleCoverage(locale = locale, covered = covered, total = questions.size)
        }
        return bank.copy(
            title = computed.title,
            questions = questions,
            contentRevision = patch.targetRevision,
            fingerprint = fingerprintOf(questions),
            typeCounts = null,
            localeCoverage = coverage,
        )
    }
}
