package morefish.knowledge.trainer.domain.patch

import morefish.knowledge.trainer.domain.model.Question
import morefish.knowledge.trainer.domain.model.QuestionBank
import morefish.knowledge.trainer.domain.model.QuestionOption
import morefish.knowledge.trainer.domain.model.QuestionRules
import morefish.knowledge.trainer.domain.model.QuestionText
import morefish.knowledge.trainer.domain.model.QuestionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 补丁应用的核心判定。
 *
 * 这一组测试守的不是"能不能改"，而是**什么时候必须拒绝**：
 * 补丁一旦被错误地应用，用户手上那份练了很久的题库就变了内容，
 * 而且没有任何办法看出哪里被改过。所以"能应用"的分支只有一条，
 * "不能应用"的分支有好几条，每条都要单独验证。
 */
class BankPatchTest {

    private val bankId = "550e8400-e29b-41d4-a716-446655440000"

    // ---------------------------------------------------------------- 夹具

    private fun single(
        id: String = "q1",
        prompt: String = "TCP 三次握手的作用是什么？",
        correct: String = "o2",
        explanation: String? = null,
    ) = Question(
        questionId = id,
        type = QuestionType.SINGLE_CHOICE,
        promptMarkdown = prompt,
        options = listOf(
            QuestionOption("o1", "断开连接"),
            QuestionOption("o2", "建立连接"),
        ),
        correctOptionIds = setOf(correct),
        explanationMarkdown = explanation,
    )

    private fun multiple(id: String = "q2") = Question(
        questionId = id,
        type = QuestionType.MULTIPLE_CHOICE,
        promptMarkdown = "以下哪些属于实践的基本特征？",
        options = listOf(
            QuestionOption("o1", "客观物质性"),
            QuestionOption("o2", "自觉能动性"),
            QuestionOption("o3", "纯粹主观性"),
        ),
        correctOptionIds = setOf("o1", "o2"),
    )

    private fun shortAnswer(id: String = "q3") = Question(
        questionId = id,
        type = QuestionType.SHORT_ANSWER,
        promptMarkdown = "什么是实践？",
        answerText = "实践是人类能动地改造世界的客观物质活动。",
    )

    private fun bank(vararg questions: Question, revision: Int = 1) = QuestionBank(
        bankId = bankId,
        title = "示例题库",
        sourceLocale = "zh-Hans",
        questions = questions.toList(),
        importedAtMillis = 1_000,
        sourceLabel = "test",
        contentRevision = revision,
    )

    private fun patchOf(
        base: QuestionBank,
        vararg ops: BankPatchOp,
        target: Int = base.contentRevision + 1,
        title: String? = null,
        bankIdOverride: String = bankId,
    ) = BankPatch(
        patchId = "demo-v2",
        version = "2.0.0",
        name = "示例补丁",
        bankId = bankIdOverride,
        baseRevision = base.contentRevision,
        targetRevision = target,
        baseFingerprint = BankPatcher.fingerprintOf(base.questions),
        operations = ops.toList(),
        title = title,
    )

    // ---------------------------------------------------------------- 能应用

    @Test
    fun appliesWhenAnchorMatches() {
        val bank = bank(single(), multiple())
        val patch = patchOf(
            bank,
            BankPatchOp.Update(
                questionId = "q1",
                expect = null,
                changes = QuestionChanges(correctOptionIds = setOf("o1")),
            ),
        )
        val applied = assertIs<PatchApply.Ok>(BankPatcher.apply(bank, patch))

        assertEquals(2, applied.bank.contentRevision)
        assertEquals(setOf("o1"), applied.bank.questions.first { it.questionId == "q1" }.correctOptionIds)
        assertTrue(applied.report.entries.single().kind == PatchChangeKind.ANSWER)
        assertEquals(1, applied.report.answerChanges.size)
        // 指纹必须重算：不重算的话下一次更新会以为题库还停在旧版
        assertTrue(applied.bank.fingerprint != bank.fingerprint)
    }

    @Test
    fun contentOnlyChangeIsNotAnAnswerChange() {
        val bank = bank(single())
        val patch = patchOf(
            bank,
            BankPatchOp.Update("q1", null, QuestionChanges(promptMarkdown = "三次握手是为了什么？")),
        )
        val applied = assertIs<PatchApply.Ok>(BankPatcher.apply(bank, patch))

        val entry = applied.report.entries.single()
        assertEquals(PatchChangeKind.CONTENT, entry.kind)
        // 答案没变 → 历史不受影响，界面就不该吓唬人说"你的旧记录可能判错了"
        assertTrue(applied.report.answerChanges.isEmpty())
    }

    @Test
    fun addsQuestionAtTheEndAndKeepsOrder() {
        val bank = bank(single(), shortAnswer())
        val added = single(id = "q9", prompt = "新增的题")
        val patch = patchOf(bank, BankPatchOp.Add(added))
        val applied = assertIs<PatchApply.Ok>(BankPatcher.apply(bank, patch))

        assertEquals(listOf("q1", "q3", "q9"), applied.bank.questions.map { it.questionId })
        assertEquals(1, applied.bank.questions.last().contentRevision, "新题的内容修订从 1 起")
        assertEquals(PatchChangeKind.ADDED, applied.report.entries.single().kind)
    }

    @Test
    fun removesQuestion() {
        val bank = bank(single(), shortAnswer())
        val patch = patchOf(bank, BankPatchOp.Remove("q3", null))
        val applied = assertIs<PatchApply.Ok>(BankPatcher.apply(bank, patch))

        assertEquals(listOf("q1"), applied.bank.questions.map { it.questionId })
        assertEquals(PatchChangeKind.REMOVED, applied.report.entries.single().kind)
        assertEquals(1, applied.report.removed)
    }

    @Test
    fun titleChangeIsPartOfTheSamePatch() {
        val bank = bank(single())
        val patch = patchOf(bank, BankPatchOp.Remove("q1", null), title = "示例题库（第 2 版）")
        val applied = assertIs<PatchApply.Ok>(BankPatcher.apply(bank, patch))

        assertEquals("示例题库（第 2 版）", applied.bank.title)
        assertTrue(applied.report.titleChanged)
    }

    @Test
    fun updateBumpsQuestionRevisionOnlyWhenContentChanged() {
        val bank = bank(single())
        // 写入同一个值：作者可能是脚本生成的补丁，不该因此让所有缓存讲解失效。
        // 结果是"什么都不用做"，不是"补丁坏了" —— 这两件事在界面上的差别很大。
        val patch = patchOf(bank, BankPatchOp.Update("q1", null, QuestionChanges(promptMarkdown = single().promptMarkdown)))
        val noOp = assertIs<PatchApply.NoOp>(BankPatcher.apply(bank, patch))
        assertTrue(noOp.reason.contains("已经成立"))
        // 内容版本没动：讲解缓存不会因为一个空改动失效
        assertEquals(bank.questions.single().contentRevision, noOp.bank.questions.single().contentRevision)
    }

    // ---------------------------------------------------------------- 必须拒绝

    @Test
    fun noOpWhenAlreadyApplied() {
        val bank = bank(single())
        val patch = patchOf(
            bank,
            BankPatchOp.Update("q1", null, QuestionChanges(correctOptionIds = setOf("o1"))),
        )
        val first = assertIs<PatchApply.Ok>(BankPatcher.apply(bank, patch))
        // 用户又拿到同一个补丁（比如重复下载）：不该再改一次，也不该报错
        val again = assertIs<PatchApply.NoOp>(BankPatcher.apply(first.bank, patch))
        assertTrue(again.reason.contains("已经成立"))
    }

    @Test
    fun outdatedWhenLocalIsNewer() {
        val bank = bank(single(), revision = 5)
        val patch = BankPatch(
            patchId = "old",
            version = "1.0.1",
            name = "旧补丁",
            bankId = bankId,
            baseRevision = 3,
            targetRevision = 4,
            // 基于第 3 版的补丁，指纹必然与本地第 5 版不同 —— 这正是"链断了"的样子
            baseFingerprint = "0123456789abcdef",
            operations = listOf(BankPatchOp.Remove("q1", null)),
        )
        val outdated = assertIs<PatchApply.Outdated>(BankPatcher.apply(bank, patch))
        assertEquals(4, outdated.patchTarget)
    }

    @Test
    fun failsWhenExpectWasWrittenAgainstAnotherVersion() {
        val bank = bank(single())
        val patch = patchOf(
            bank,
            BankPatchOp.Update("q1", expect = "0000000000000000", changes = QuestionChanges(correctOptionIds = setOf("o1"))),
        )
        val failed = assertIs<PatchApply.Failed>(BankPatcher.apply(bank, patch))
        assertTrue(failed.conflicts.any { it.code == "EXPECT_MISMATCH" && it.questionId == "q1" })
    }

    @Test
    fun failsWhenSameRevisionButDifferentContent() {
        // 本地也是第 2 版，但内容与补丁声称的不一样：说明这份题库被第三方动过
        val bank = bank(single(prompt = "本地被改过的题干"), revision = 2)
        val patch = BankPatch(
            patchId = "p",
            version = "1.0.0",
            name = "补丁",
            bankId = bankId,
            baseRevision = 1,
            targetRevision = 2,
            baseFingerprint = "0123456789abcdef",
            operations = listOf(BankPatchOp.Remove("q1", null)),
        )
        val failed = assertIs<PatchApply.Failed>(BankPatcher.apply(bank, patch))
        assertTrue(failed.conflicts.any { it.code == "CONTENT_DIVERGED" })
    }

    @Test
    fun failsWhenAddCollides() {
        val bank = bank(single())
        val patch = patchOf(bank, BankPatchOp.Add(single(id = "q1")))
        val failed = assertIs<PatchApply.Failed>(BankPatcher.apply(bank, patch))
        assertTrue(failed.conflicts.any { it.code == "ADD_EXISTS" })
    }

    @Test
    fun failsWhenUpdateMakesQuestionInvalid() {
        // 多选题的正确答案被删到只剩一个：格式合法，语义上已经不是多选题了
        val bank = bank(multiple())
        val patch = patchOf(bank, BankPatchOp.Update("q2", null, QuestionChanges(correctOptionIds = setOf("o1"))))
        val failed = assertIs<PatchApply.Failed>(BankPatcher.apply(bank, patch))
        assertTrue(failed.conflicts.any { it.code == "QUESTION_INVALID" })
    }

    @Test
    fun failsWhenCorrectionPointsAtVanishedOption() {
        val bank = bank(single())
        val patch = patchOf(
            bank,
            BankPatchOp.Update(
                "q1",
                null,
                QuestionChanges(
                    options = listOf(QuestionOption("o1", "断开连接"), QuestionOption("o3", "建立连接")),
                    correctOptionIds = setOf("o2"),
                ),
            ),
        )
        val failed = assertIs<PatchApply.Failed>(BankPatcher.apply(bank, patch))
        assertTrue(failed.conflicts.any { it.code == "QUESTION_INVALID" })
    }

    @Test
    fun failsWhenTargetQuestionMissing() {
        val bank = bank(single())
        val patch = patchOf(bank, BankPatchOp.Update("q7", null, QuestionChanges(promptMarkdown = "x")))
        val failed = assertIs<PatchApply.Failed>(BankPatcher.apply(bank, patch))
        assertTrue(failed.conflicts.any { it.code == "UPDATE_MISSING" })
    }

    @Test
    fun failsWhenBankIdentityDoesNotMatchAndContentDiffers() {
        val bank = bank(single())
        val patch = patchOf(bank, BankPatchOp.Remove("q1", null), bankIdOverride = "11111111-1111-1111-1111-111111111111")
        // 指纹仍然对得上（内容一致），所以按内容匹配是允许的；
        // 真正要拒绝的是"ID 和内容都对不上"
        val other = BankPatch(
            patchId = "p",
            version = "1.0.0",
            name = "别人的补丁",
            bankId = "11111111-1111-1111-1111-111111111111",
            baseRevision = 1,
            targetRevision = 2,
            baseFingerprint = "ffffffffffffffff",
            operations = listOf(BankPatchOp.Remove("q1", null)),
        )
        val failed = assertIs<PatchApply.Failed>(BankPatcher.apply(bank, other))
        assertTrue(failed.conflicts.any { it.code == "BANK_MISMATCH" })
        // 内容匹配的那一个能正常应用 —— 老版本导入的题库 bankId 与作者声明的不一样
        assertIs<PatchApply.Ok>(BankPatcher.apply(bank, patch))
    }

    // ---------------------------------------------------------------- 覆盖度与答案文案

    @Test
    fun recomputesLocaleCoverageAfterAddingQuestions() {
        val translated = single(id = "q4").copy(
            translations = mapOf(
                "en" to QuestionText(
                    locale = "en",
                    promptMarkdown = "What does the TCP handshake do?",
                    optionTexts = mapOf("o1" to "Close", "o2" to "Open"),
                ),
            ),
        )
        val bank = bank(single()).copy(
            localeCoverage = listOf(
                morefish.knowledge.trainer.domain.i18n.LocaleCoverage("zh-Hans", 1, 1),
                morefish.knowledge.trainer.domain.i18n.LocaleCoverage("en", 1, 1),
            ),
        )
        val patch = patchOf(bank, BankPatchOp.Add(translated))
        val applied = assertIs<PatchApply.Ok>(BankPatcher.apply(bank, patch))

        val en = applied.bank.coverageOf("en")
        assertEquals(2, en.total, "题数变了，覆盖度的分母必须跟着变")
        assertEquals(1, en.covered)
        assertFalse(en.complete)
    }

    @Test
    fun answerLabelReadsLikeEnglishNotLikeIds() {
        val question = single()
        // 选项按当前显示顺序标 A/B：让人一眼看出改的是哪一个
        assertEquals("B. 建立连接", QuestionRules.answerLabel(question))
        assertEquals("实践是人类能动地改造世界的客观物质活动。", QuestionRules.answerLabel(shortAnswer()))
    }

    @Test
    fun questionRulesMatchTheToolchain() {
        assertTrue(QuestionRules.problemsOf(single()).isEmpty())
        assertTrue(QuestionRules.problemsOf(multiple()).isEmpty())
        assertTrue(QuestionRules.problemsOf(shortAnswer()).isEmpty())

        // 与 tools/qbank/ktqbf/qbpatch.py::question_problems 对应的几条
        assertTrue(QuestionRules.problemsOf(multiple().copy(correctOptionIds = setOf("o1"))).isNotEmpty())
        assertTrue(QuestionRules.problemsOf(single().copy(correctOptionIds = setOf("o1", "o2"))).isNotEmpty())
        assertTrue(QuestionRules.problemsOf(single().copy(correctOptionIds = emptySet())).isNotEmpty())
        assertTrue(QuestionRules.problemsOf(shortAnswer().copy(answerText = "  ")).isNotEmpty())
        assertTrue(QuestionRules.problemsOf(single().copy(promptMarkdown = "")).isNotEmpty())
        assertTrue(QuestionRules.problemsOf(single().copy(options = listOf(QuestionOption("o1", ""), QuestionOption("o2", "x")))).isNotEmpty())
    }
}
