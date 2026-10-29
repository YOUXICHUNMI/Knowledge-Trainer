package morefish.knowledge.trainer.infrastructure

import kotlinx.coroutines.test.runTest
import morefish.knowledge.trainer.application.port.AddQuestionResult
import morefish.knowledge.trainer.application.port.InMemoryTextStorage
import morefish.knowledge.trainer.application.usecase.SubmitAnswerUseCase
import morefish.knowledge.trainer.domain.i18n.LocaleCoverage
import morefish.knowledge.trainer.domain.model.AnswerInput
import morefish.knowledge.trainer.domain.model.Fingerprint
import morefish.knowledge.trainer.domain.model.PracticeSession
import morefish.knowledge.trainer.domain.model.Question
import morefish.knowledge.trainer.domain.model.QuestionBank
import morefish.knowledge.trainer.domain.model.QuestionOption
import morefish.knowledge.trainer.domain.model.QuestionText
import morefish.knowledge.trainer.domain.model.QuestionType
import morefish.knowledge.trainer.domain.model.SessionItem
import morefish.knowledge.trainer.domain.model.Verdict
import morefish.knowledge.trainer.infrastructure.storage.ATTEMPTS_FILE
import morefish.knowledge.trainer.infrastructure.storage.BANKS_FILE
import morefish.knowledge.trainer.infrastructure.storage.BankCatalogDto
import morefish.knowledge.trainer.infrastructure.storage.BankMetaDto
import morefish.knowledge.trainer.infrastructure.storage.DeferredQuestions
import morefish.knowledge.trainer.infrastructure.storage.JsonAttemptRepository
import morefish.knowledge.trainer.infrastructure.storage.JsonBankRepository
import morefish.knowledge.trainer.infrastructure.storage.questionFileOf
import morefish.knowledge.trainer.infrastructure.storage.storageJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersistenceTest {

    private val question = Question(
        questionId = "q1",
        type = QuestionType.SINGLE_CHOICE,
        promptMarkdown = "题干",
        options = listOf(QuestionOption("o1", "甲"), QuestionOption("o2", "乙")),
        correctOptionIds = setOf("o1"),
        explanationMarkdown = "解析",
    )

    private fun bank() = QuestionBank(
        bankId = "b1",
        title = "测试题库",
        sourceLocale = "zh-Hans",
        questions = listOf(question),
        importedAtMillis = 100,
        sourceLabel = "test",
    )

    @Test
    fun translationsAndLocaleCoverageSurviveARestart() = runTest {
        val storage = InMemoryTextStorage()
        val bilingual = bank().copy(
            questions = listOf(
                question.copy(
                    translations = mapOf(
                        "en" to QuestionText(
                            locale = "en",
                            promptMarkdown = "Prompt",
                            optionTexts = mapOf("o1" to "first", "o2" to "second"),
                            explanationMarkdown = "Because.",
                            contentRevision = 4,
                        ),
                    ),
                ),
            ),
            localeCoverage = listOf(LocaleCoverage("zh-Hans", 1, 1), LocaleCoverage("en", 1, 1)),
        )
        JsonBankRepository(storage).save(bilingual)

        val loaded = assertNotNull(JsonBankRepository(storage).find("b1"))
        // 覆盖度来自目录：不解析题目就能列语言
        assertEquals(2, loaded.availableLocales.size)
        assertEquals(1, loaded.coverageOf("en").covered)
        // 译文版本按语言各记一份：英文第 4 版不影响中文仍是第 1 版
        assertEquals(4, loaded.questions.single().translations.getValue("en").contentRevision)
        val english = assertNotNull(loaded.questions.single().localized("en", "zh-Hans"))
        assertEquals("Prompt", english.promptMarkdown)
        assertEquals("second", english.option("o2")?.contentMarkdown)
    }

    @Test
    fun bankRoundTripsThroughStorage() = runTest {
        val storage = InMemoryTextStorage()
        JsonBankRepository(storage).save(bank())

        // 新建一个仓储实例，模拟"下次启动"
        val reopened = JsonBankRepository(storage)
        val loaded = reopened.find("b1")

        assertNotNull(loaded)
        assertEquals("测试题库", loaded.title)
        assertEquals(1, loaded.questions.size)
        assertEquals(setOf("o1"), loaded.questions.single().correctOptionIds)
        assertEquals("解析", loaded.questions.single().explanationMarkdown)
    }

    @Test
    fun attemptsAreAppendedNotOverwritten() = runTest {
        val storage = InMemoryTextStorage()
        val repository = JsonAttemptRepository(storage)

        val useCase = SubmitAnswerUseCase(repository)
        val session = PracticeSession.create(
            sessionId = "s1",
            bankId = "b1",
            items = listOf(SessionItem(question, listOf("o1", "o2"))),
            mode = morefish.knowledge.trainer.domain.model.PracticeMode.NATIVE,
            nowMillis = 0,
        )

        useCase.submit(session, session.items.first(), AnswerInput.Choices(setOf("o1")), 1200)
        useCase.submit(session, session.items.first(), AnswerInput.Choices(setOf("o2")), 800)

        val raw = assertNotNull(storage.read(ATTEMPTS_FILE))
        assertEquals(2, raw.lines().count { it.isNotBlank() })

        val all = JsonAttemptRepository(storage).all()
        assertEquals(2, all.size)
        assertEquals(Verdict.CORRECT, all.first().verdict)
        assertEquals(Verdict.INCORRECT, all.last().verdict)
    }

    @Test
    fun corruptLinesAreCountedAndReported() = runTest {
        val storage = InMemoryTextStorage()
        JsonAttemptRepository(storage).append(
            morefish.knowledge.trainer.domain.model.AttemptRecord(
                attemptId = "a1",
                sessionId = "s1",
                bankId = "b1",
                questionId = "q1",
                questionType = QuestionType.SINGLE_CHOICE,
                mode = morefish.knowledge.trainer.domain.model.PracticeMode.NATIVE,
                displayOptionIds = listOf("o1"),
                selectedOptionIds = setOf("o1"),
                textAnswer = null,
                verdict = Verdict.CORRECT,
                strategyId = "exact-set",
                gradingSpec = "exact-set",
                answerRevision = 1,
                questionFingerprint = "fp",
                submittedAtMillis = 1,
                elapsedMillis = 10,
                explanationSeen = false,
            ),
        )
        storage.appendLine(ATTEMPTS_FILE, "{ 这不是合法记录")

        val repository = JsonAttemptRepository(storage)
        assertEquals(1, repository.all().size)
        assertTrue(repository.loadWarnings.single().contains("无法解析"))
    }

    @Test
    fun missingBankReturnsNull() = runTest {
        assertNull(JsonBankRepository(InMemoryTextStorage()).find("nope"))
    }

    @Test
    fun keywordsAndOrderingGradingRoundTrip() = runTest {
        val storage = InMemoryTextStorage()
        val bank = QuestionBank(
            bankId = "b2",
            title = "P4 题库",
            sourceLocale = "zh-Hans",
            questions = listOf(
                Question(
                    questionId = "kw",
                    type = QuestionType.SHORT_ANSWER,
                    promptMarkdown = "默知识点",
                    answerText = "参考答案",
                    grading = morefish.knowledge.trainer.domain.model.GradingSpec.Keywords(
                        required = listOf("实践", "能动"),
                        forbidden = listOf("迷信"),
                    ),
                ),
                Question(
                    questionId = "ord",
                    type = QuestionType.ORDERING,
                    promptMarkdown = "重组",
                    options = listOf(
                        QuestionOption("f1", "主体"),
                        QuestionOption("f2", "谓语"),
                        QuestionOption("f3", "宾语"),
                    ),
                    correctOptionIds = setOf("f1", "f2", "f3"),
                    grading = morefish.knowledge.trainer.domain.model.GradingSpec.Ordering(
                        canonical = listOf("f1", "f2", "f3"),
                        alternatives = listOf(listOf("f2", "f1", "f3")),
                    ),
                ),
            ),
            importedAtMillis = 200,
            sourceLabel = "test",
        )
        JsonBankRepository(storage).save(bank)

        val loaded = assertNotNull(JsonBankRepository(storage).find("b2"))
        val kw = loaded.questions.first { it.questionId == "kw" }
        val kwSpec = assertIs<morefish.knowledge.trainer.domain.model.GradingSpec.Keywords>(kw.grading)
        assertEquals(listOf("实践", "能动"), kwSpec.required)
        assertEquals(listOf("迷信"), kwSpec.forbidden)

        val ord = loaded.questions.first { it.questionId == "ord" }
        val ordSpec = assertIs<morefish.knowledge.trainer.domain.model.GradingSpec.Ordering>(ord.grading)
        assertEquals(listOf("f1", "f2", "f3"), ordSpec.canonical)
        assertEquals(listOf(listOf("f2", "f1", "f3")), ordSpec.alternatives)
    }

    @Test
    fun orderingAttemptPreservesSequenceAcrossStorage() = runTest {
        val storage = InMemoryTextStorage()
        val repository = JsonAttemptRepository(storage)
        repository.append(
            morefish.knowledge.trainer.domain.model.AttemptRecord(
                attemptId = "a-ord",
                sessionId = "s1",
                bankId = "b1",
                questionId = "ord",
                questionType = QuestionType.ORDERING,
                mode = morefish.knowledge.trainer.domain.model.PracticeMode.NATIVE,
                displayOptionIds = listOf("f1", "f2", "f3"),
                selectedOptionIds = setOf("f2", "f1", "f3"),
                orderedOptionIds = listOf("f2", "f1", "f3"),
                textAnswer = null,
                verdict = Verdict.CORRECT,
                strategyId = "ordering",
                gradingSpec = "ordering",
                answerRevision = 1,
                questionFingerprint = "fp",
                submittedAtMillis = 5,
                elapsedMillis = 8,
                explanationSeen = false,
            ),
        )
        val loaded = JsonAttemptRepository(storage).all().single()
        assertEquals(listOf("f2", "f1", "f3"), loaded.orderedOptionIds)
    }

    @Test
    fun toolUsageSurvivesStorageAndDefaultsToFalseForOldRecords() = runTest {
        val storage = InMemoryTextStorage()
        val repository = JsonAttemptRepository(storage)
        repository.append(
            morefish.knowledge.trainer.domain.model.AttemptRecord(
                attemptId = "a-tools",
                sessionId = "s1",
                bankId = "b1",
                questionId = "q1",
                questionType = QuestionType.SINGLE_CHOICE,
                mode = morefish.knowledge.trainer.domain.model.PracticeMode.NATIVE,
                displayOptionIds = listOf("o1"),
                selectedOptionIds = setOf("o1"),
                textAnswer = null,
                verdict = Verdict.CORRECT,
                strategyId = "exact-set",
                gradingSpec = "exact-set",
                answerRevision = 1,
                questionFingerprint = "fp",
                submittedAtMillis = 1,
                elapsedMillis = 10,
                explanationSeen = false,
                usedTools = true,
            ),
        )
        assertTrue(JsonAttemptRepository(storage).all().single().usedTools)

        // 旧版本写下的记录没有这个字段：读回来必须是 false，而不是解析失败
        storage.appendLine(
            ATTEMPTS_FILE,
            """{"attemptId":"a-old","sessionId":"s0","bankId":"b1","questionId":"q0",""" +
                """"questionType":"SINGLE_CHOICE","mode":"native","verdict":"CORRECT",""" +
                """"strategyId":"exact-set","gradingSpec":"exact-set","submittedAtMillis":1}""",
        )
        val reloaded = JsonAttemptRepository(storage).all()
        assertEquals(2, reloaded.size)
        assertFalse(reloaded.first { it.attemptId == "a-old" }.usedTools)
    }

    // ---- 大题库的存储布局 ----

    @Test
    fun catalogKeepsQuestionsOutOfTheMetadataFile() = runTest {
        val storage = InMemoryTextStorage()
        JsonBankRepository(storage).save(bank())

        val catalog = assertNotNull(storage.read(BANKS_FILE))
        assertFalse(catalog.contains("题干"), "目录里不该出现题目正文：$catalog")
        assertTrue(catalog.contains("\"questionCount\":1"), "目录要带题数，首页靠它：$catalog")

        val lines = assertNotNull(storage.read(questionFileOf("b1")))
            .lineSequence().count { it.isNotBlank() }
        assertEquals(1, lines, "题目在自己的文件里，一行一题")
    }

    @Test
    fun listingBanksDoesNotParseQuestionFiles() = runTest {
        val storage = InMemoryTextStorage()
        JsonBankRepository(storage).save(bank())

        val listed = JsonBankRepository(storage).list().single()

        assertEquals(1, listed.questionCount, "题数来自目录")
        assertEquals(mapOf(QuestionType.SINGLE_CHOICE to 1), listed.typeBreakdown(), "分布来自目录")
        val questions = assertIs<DeferredQuestions>(listed.questions)
        assertFalse(questions.isLoaded, "只是列个题库，不该把题目解析进来")
        assertEquals("题干", listed.questions.single().promptMarkdown, "真读时才解析，内容仍要对")
        assertTrue(questions.isLoaded)
    }

    @Test
    fun savingOneBankLeavesOtherBanksFileUntouched() = runTest {
        val storage = InMemoryTextStorage()
        val repository = JsonBankRepository(storage)

        val other = bank().copy(bankId = "b2", title = "另一个库", questions = listOf(question, question.copy(questionId = "q9")))
        repository.save(other)
        val otherFileBefore = assertNotNull(storage.read(questionFileOf("b2")))

        repository.save(bank())

        assertEquals(
            otherFileBefore,
            storage.read(questionFileOf("b2")),
            "写 b1 不该重写 b2 的题目文件 —— 十万题的库不该被别人的一次保存拖下水",
        )
        assertEquals(2, repository.find("b2")?.questionCount)
        assertEquals(1, repository.find("b1")?.questionCount)
    }

    @Test
    fun addingAQuestionUpdatesCatalogCounts() = runTest {
        val storage = InMemoryTextStorage()
        val repository = JsonBankRepository(storage)
        repository.save(bank())

        val loaded = assertNotNull(repository.find("b1"))
        val extra = Question(
            questionId = "q2",
            type = QuestionType.SHORT_ANSWER,
            promptMarkdown = "新加的简答题",
            answerText = "答案",
        )
        repository.save(loaded.copy(questions = loaded.questions + extra))

        val reopened = JsonBankRepository(storage).find("b1")
        assertNotNull(reopened)
        assertEquals(2, reopened.questionCount, "加了一题，目录里的题数要跟着变")
        assertEquals(
            mapOf(QuestionType.SINGLE_CHOICE to 1, QuestionType.SHORT_ANSWER to 1),
            reopened.typeBreakdown(),
            "目录里的题型分布也要跟着变，不能留着旧值",
        )
        assertEquals(listOf("q1", "q2"), reopened.questions.map { it.questionId })

        // 指纹同理：`copy(questions = …)` 会原样保留旧 fingerprint，
        // 照抄它等于告诉后来的人"这个库还是原来那份内容"。而导入就是按指纹判重复的。
        assertNotEquals(loaded.fingerprint, reopened.fingerprint, "内容变了，目录里的指纹必须跟着变")
        assertEquals(
            Fingerprint.ofQuestions(reopened.questions.map { it.contentFingerprint() }),
            reopened.fingerprint,
            "目录里的指纹必须等于实际内容的指纹",
        )
    }

    /**
     * 移除题库：目录条目与题目文件一起消失，别的库一个字节不动。
     *
     * 第二刀钉的是"本来就不在"：一次什么都没删的调用必须返回 false ——
     * 界面靠它决定要不要说"已移除"。
     */
    @Test
    fun removingABankDropsItsCatalogEntryAndItsQuestionFile() = runTest {
        val storage = InMemoryTextStorage()
        val repository = JsonBankRepository(storage)
        repository.save(bank())
        repository.save(bank().copy(bankId = "b2", title = "另一个题库"))
        assertNotNull(storage.read(questionFileOf("b1")))

        assertTrue(repository.delete("b1"))
        assertNull(storage.read(questionFileOf("b1")), "题目文件不能留在磁盘上变成无人认领的字节")
        assertEquals(listOf("b2"), JsonBankRepository(storage).list().map { it.bankId })

        val survivor = assertNotNull(JsonBankRepository(storage).find("b2"))
        assertEquals(1, survivor.questionCount, "删一个库不该动到另一个库的题数")
        assertEquals("题干", survivor.questions.single().promptMarkdown)

        assertFalse(repository.delete("b1"), "第二次删除什么都没删，不能报告成功")
    }


    // ---- addQuestion：代价可以小，但结果必须与"读出来 copy 一份再 save"逐字段一致 ----

    private fun extra(id: String) = Question(
        questionId = id,
        type = QuestionType.SHORT_ANSWER,
        promptMarkdown = "第 $id 题",
        answerText = "答案",
    )

    private fun catalogFingerprintOf(bank: QuestionBank): String =
        Fingerprint.ofQuestions(bank.questions.map { it.contentFingerprint() })

    @Test
    fun addQuestionLeavesCatalogEqualToContent() = runTest {
        val storage = InMemoryTextStorage()
        JsonBankRepository(storage).save(bank().copy(localeCoverage = listOf(LocaleCoverage("zh-Hans", 1, 1))))

        assertEquals(AddQuestionResult.Added, JsonBankRepository(storage).addQuestion("b1", extra("q2")))

        val reloaded = assertNotNull(JsonBankRepository(storage).find("b1"))
        assertEquals(2, reloaded.questionCount, "目录里的题数要跟着变")
        assertEquals(
            mapOf(QuestionType.SINGLE_CHOICE to 1, QuestionType.SHORT_ANSWER to 1),
            reloaded.typeBreakdown(),
            "题型分布也要跟着变",
        )
        assertEquals(2, reloaded.contentRevision, "内容变了，内容版本加一")
        assertEquals(
            catalogFingerprintOf(reloaded),
            reloaded.fingerprint,
            "目录里的指纹必须等于实际内容 —— 增量累加那一步不能算错",
        )
        assertEquals(Fingerprint.CURRENT_VERSION, reloaded.fingerprintVersion)
        assertEquals(listOf("q1", "q2"), reloaded.questions.map { it.questionId })
        assertEquals(2, reloaded.coverageOf("zh-Hans").covered)
        assertEquals(2, reloaded.coverageOf("zh-Hans").total)
    }

    @Test
    fun addQuestionWithAnExistingIdWritesNothing() = runTest {
        val storage = InMemoryTextStorage()
        JsonBankRepository(storage).save(bank())
        val catalogBefore = storage.read(BANKS_FILE)

        assertEquals(AddQuestionResult.DuplicateId, JsonBankRepository(storage).addQuestion("b1", extra("q1")))

        assertEquals(catalogBefore, storage.read(BANKS_FILE), "被拒绝的追加不该留下半个目录更新")
        assertEquals(1, assertNotNull(JsonBankRepository(storage).find("b1")).questionCount)
    }

    @Test
    fun addQuestionOnlyRejectsAnExactIdMatch() = runTest {
        val storage = InMemoryTextStorage()
        JsonBankRepository(storage).save(bank())

        // "q1x" 与已有 id "q1" 前缀相同：子串预筛要是直接当判据，就会把新题误报成重复
        assertEquals(AddQuestionResult.Added, JsonBankRepository(storage).addQuestion("b1", extra("q1x")))
        assertEquals(2, assertNotNull(JsonBankRepository(storage).find("b1")).questionCount)
    }

    @Test
    fun addQuestionToAMissingBankDoesNotCreateOne() = runTest {
        val storage = InMemoryTextStorage()

        assertEquals(AddQuestionResult.BankMissing, JsonBankRepository(storage).addQuestion("nope", extra("q2")))

        assertTrue(JsonBankRepository(storage).list().isEmpty(), "加题不能凭空造出一个题库")
        assertNull(storage.read(questionFileOf("nope")))
    }

    @Test
    fun addQuestionDoesNotTouchOtherBanks() = runTest {
        val storage = InMemoryTextStorage()
        val repository = JsonBankRepository(storage)
        repository.save(bank())
        repository.save(bank().copy(bankId = "b2", title = "另一个题库"))
        val otherBefore = storage.read(questionFileOf("b2"))

        repository.addQuestion("b1", extra("q2"))

        assertEquals(otherBefore, storage.read(questionFileOf("b2")), "另一个库的题目文件一个字节都不该动")
        val other = assertNotNull(JsonBankRepository(storage).find("b2"))
        assertEquals(1, other.questionCount)
        assertEquals(1, other.contentRevision, "别的库的内容版本也不该变")
    }

    @Test
    fun addQuestionOnALegacyCatalogUpgradesTheFingerprintOnce() = runTest {
        val storage = InMemoryTextStorage()
        JsonBankRepository(storage).save(bank())

        // 把目录改回升级前的样子：首版算法算出的值，且没有 fingerprintVersion 字段
        val legacyValue = Fingerprint.legacyOfQuestions(listOf(question.contentFingerprint()))
        val legacyMeta = BankMetaDto(
            bankId = "b1",
            title = "测试题库",
            sourceLocale = "zh-Hans",
            importedAtMillis = 100,
            sourceLabel = "test",
            fingerprint = legacyValue,
            questionCount = 1,
            typeCounts = mapOf(QuestionType.SINGLE_CHOICE.wireName to 1),
        )
        storage.write(
            BANKS_FILE,
            storageJson.encodeToString(BankCatalogDto.serializer(), BankCatalogDto(banks = listOf(legacyMeta))),
        )
        assertEquals(
            Fingerprint.LEGACY_VERSION,
            assertNotNull(JsonBankRepository(storage).find("b1")).fingerprintVersion,
            "老目录里没有这个字段，读回来就该是首版",
        )

        assertEquals(AddQuestionResult.Added, JsonBankRepository(storage).addQuestion("b1", extra("q2")))

        val upgraded = assertNotNull(JsonBankRepository(storage).find("b1"))
        assertEquals(Fingerprint.CURRENT_VERSION, upgraded.fingerprintVersion, "写过一次之后目录里就是新算法的值")
        assertEquals(catalogFingerprintOf(upgraded), upgraded.fingerprint)
        assertEquals(2, upgraded.questionCount)
    }

    @Test
    fun addQuestionGrowsLocaleCoverageTheSameWayAFullRewriteWould() = runTest {
        val storage = InMemoryTextStorage()
        val english = QuestionText(locale = "en", promptMarkdown = "Prompt", explanationMarkdown = "Because.")
        JsonBankRepository(storage).save(
            bank().copy(
                questions = listOf(question.copy(translations = mapOf("en" to english))),
                localeCoverage = listOf(LocaleCoverage("zh-Hans", 1, 1), LocaleCoverage("en", 1, 1)),
            ),
        )

        JsonBankRepository(storage).addQuestion("b1", extra("q2")) // 只有中文

        val reloaded = assertNotNull(JsonBankRepository(storage).find("b1"))
        assertEquals(2, reloaded.coverageOf("zh-Hans").covered)
        assertEquals(2, reloaded.coverageOf("zh-Hans").total)
        assertEquals(1, reloaded.coverageOf("en").covered, "新题没有英文，英文的已覆盖数不该加")
        assertEquals(2, reloaded.coverageOf("en").total, "但总数要跟着涨，否则覆盖率会算成 1/1")
    }

    @Test
    fun addQuestionIntroducesANewLocaleIntoTheCatalog() = runTest {
        val storage = InMemoryTextStorage()
        JsonBankRepository(storage).save(
            bank().copy(localeCoverage = listOf(LocaleCoverage("zh-Hans", 1, 1))),
        )
        val english = QuestionText(locale = "en", promptMarkdown = "Second", answerText = "answer")

        JsonBankRepository(storage).addQuestion("b1", extra("q2").copy(translations = mapOf("en" to english)))

        val reloaded = assertNotNull(JsonBankRepository(storage).find("b1"))
        assertTrue("en" in reloaded.availableLocales, "新题带来了目录里没记过的语言，它该出现在可选语言里")
        assertEquals(1, reloaded.coverageOf("en").covered)
        assertEquals(2, reloaded.coverageOf("en").total)
        assertEquals(2, reloaded.coverageOf("zh-Hans").covered)
    }

    @Test
    fun incrementalFingerprintEqualsTheWholeTableOne() {
        val parts = listOf("a", "b", "c")

        val whole = Fingerprint.ofQuestions(parts)
        val incremental = parts.fold("") { acc, part -> Fingerprint.plus(acc, part) }

        assertEquals(whole, incremental, "逐项累加必须与整表算法逐位相同")
        assertEquals(whole, Fingerprint.ofQuestions(parts.reversed()), "指纹与题目顺序无关")
        assertNotEquals(whole, Fingerprint.ofQuestions(parts + "d"))
        // 首版算法的公式必须原样保留：用户设备上还躺着那样算出来的值，改了就再也认不出来
        assertEquals(Fingerprint.of(parts.sorted().joinToString("\u0000")), Fingerprint.legacyOfQuestions(parts))
    }
}
