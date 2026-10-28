package morefish.knowledge.trainer.presentation

import morefish.knowledge.trainer.presentation.toolbox.ConvPicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import morefish.knowledge.trainer.application.port.InMemoryTextStorage
import morefish.knowledge.trainer.application.port.WallClock
import morefish.knowledge.trainer.application.usecase.ImportBankUseCase
import morefish.knowledge.trainer.domain.graph.DependencyKind
import morefish.knowledge.trainer.domain.graph.GraphEdge
import morefish.knowledge.trainer.domain.graph.GraphRelation
import morefish.knowledge.trainer.domain.graph.GraphSnapshot
import morefish.knowledge.trainer.domain.graph.KnowledgeGraph
import morefish.knowledge.trainer.domain.graph.QuestionRef
import morefish.knowledge.trainer.domain.model.NoteScope
import morefish.knowledge.trainer.domain.model.QuestionType
import morefish.knowledge.trainer.domain.model.isPracticable
import morefish.knowledge.trainer.domain.practice.PracticeScope
import morefish.knowledge.trainer.domain.tools.CalcOutcome
import morefish.knowledge.trainer.domain.tools.Conversion
import morefish.knowledge.trainer.domain.tools.Dimension
import morefish.knowledge.trainer.domain.tools.Units
import morefish.knowledge.trainer.application.port.ChoiceMapping
import morefish.knowledge.trainer.infrastructure.storage.JsonGraphRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 控制器级端到端：暂停 → 换一个控制器（模拟重启）→ 恢复。
 * 这是 P2 的验收用例，而不是只测仓储能否读写。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppControllerTest {

    private var now = 1_800_000_000_000L
    private val clock = WallClock { now }

    private fun controller(storage: InMemoryTextStorage, scheduler: TestCoroutineScheduler): AppController =
        AppController(
            deps = AppDependencies(storage, clock),
            scope = CoroutineScope(UnconfinedTestDispatcher(scheduler)),
        )

    private suspend fun seedBank(storage: InMemoryTextStorage) {
        ImportBankUseCase(AppDependencies(storage, clock).bankRepository, clock = clock)
            .import(morefish.knowledge.trainer.presentation.SampleBank.json, ChoiceMapping.AUTO, "示例", "test")
    }

    private fun answerCurrent(controller: AppController) {
        val question = assertNotNull(controller.currentQuestion)
        when (question.type) {
            QuestionType.SHORT_ANSWER -> controller.updateTextAnswer(question.answerText.orEmpty())
            else -> question.correctOptionIds.forEach { controller.toggleOption(it) }
        }
        controller.submit()
    }

    @Test
    fun pauseAndRestartRestoresCursorDraftAndAnsweredCount() = runTest {
        val storage = InMemoryTextStorage()
        seedBank(storage)

        val first = controller(storage, testScheduler)
        first.refresh()
        val bank = first.banks.single()
        first.startPractice(bank, questionCount = 3)
        assertEquals(Screen.PRACTICE, first.screen)
        assertEquals(3, first.session!!.items.size)

        answerCurrent(first)
        testScheduler.advanceUntilIdle()
        assertEquals(1, first.session!!.answeredCount)

        first.next()
        testScheduler.advanceUntilIdle()
        assertEquals(1, first.session!!.cursor)

        // 第 2 题留一个未提交的草稿
        val secondQuestion = assertNotNull(first.session!!.current?.question)
        val draftOption = secondQuestion.options.firstOrNull()?.optionId
        if (secondQuestion.type == QuestionType.SHORT_ANSWER) {
            first.updateTextAnswer("写到一半")
        } else if (draftOption != null) {
            first.toggleOption(draftOption)
        }
        testScheduler.advanceUntilIdle()

        first.pauseAndExit()
        testScheduler.advanceUntilIdle()
        assertEquals(Screen.HOME, first.screen)

        // 换一个控制器实例，模拟进程重启：只有磁盘上的数据能带过来。
        val restarted = controller(storage, testScheduler)
        restarted.refresh()
        testScheduler.advanceUntilIdle()

        val paused = assertNotNull(restarted.resumable)
        assertEquals(1, paused.answeredCount)
        assertEquals(1, paused.firstUnansweredIndex)

        restarted.resumeSession()
        testScheduler.advanceUntilIdle()

        assertEquals(Screen.PRACTICE, restarted.screen)
        val resumed = assertNotNull(restarted.session)
        assertEquals(1, resumed.cursor)
        assertEquals(1, resumed.answeredCount)
        if (secondQuestion.type == QuestionType.SHORT_ANSWER) {
            assertEquals("写到一半", restarted.textAnswer)
        } else {
            assertEquals(setOfNotNull(draftOption), restarted.selectedOptionIds)
        }
    }

    @Test
    fun discardingResumableRemovesStoredSessionButKeepsHistory() = runTest {
        val storage = InMemoryTextStorage()
        seedBank(storage)

        val first = controller(storage, testScheduler)
        first.refresh()
        first.startPractice(first.banks.single(), questionCount = 3)
        answerCurrent(first)
        testScheduler.advanceUntilIdle()
        first.pauseAndExit()
        testScheduler.advanceUntilIdle()

        val second = controller(storage, testScheduler)
        second.refresh()
        assertNotNull(second.resumable)
        second.discardResumable()
        testScheduler.advanceUntilIdle()

        val third = controller(storage, testScheduler)
        third.refresh()
        testScheduler.advanceUntilIdle()

        assertNull(third.resumable)
        assertEquals(1, third.history.size, "已提交的记录必须保留")
    }

    @Test
    fun unpracticedScopeShrinksAfterAnswering() = runTest {
        val storage = InMemoryTextStorage()
        seedBank(storage)

        val controller = controller(storage, testScheduler)
        controller.refresh()
        val bank = controller.banks.single()
        assertEquals(6, bank.questionCount)

        controller.openSetup(bank.bankId)
        controller.setup.selectScope(PracticeScope.UNPRACTICED)
        assertEquals(6, controller.setup.candidateCount)

        controller.startPlannedPractice()
        testScheduler.advanceUntilIdle()
        answerCurrent(controller)
        testScheduler.advanceUntilIdle()

        controller.goHome()
        testScheduler.advanceUntilIdle()

        controller.openSetup(bank.bankId)
        controller.setup.selectScope(PracticeScope.UNPRACTICED)
        // 答过一题（该题若被判错仍会算"已练"，因为未练习只看有没有有效作答）
        assertTrue(
            controller.setup.candidateCount == 5,
            "答过一题后未练习应为 5，实际 ${controller.setup.candidateCount}",
        )
    }

    @Test
    fun planLabelIsStoredOnSession() = runTest {
        val storage = InMemoryTextStorage()
        seedBank(storage)

        val controller = controller(storage, testScheduler)
        controller.refresh()
        controller.openSetup(controller.banks.single().bankId)
        controller.setup.selectCount(20)
        controller.setup.toggleMixedTypes()
        controller.startPlannedPractice()
        testScheduler.advanceUntilIdle()

        val session = assertNotNull(controller.session)
        assertTrue(session.planLabel.contains("全部"))
        assertTrue(session.planLabel.contains("混合题型"))
        assertEquals(6, session.items.size)
    }

    // ---- P4：离线手工编辑 ----

    @Test
    fun manualQuestionIsSavedAndPracticable() = runTest {
        val storage = InMemoryTextStorage()
        seedBank(storage)

        val controller = controller(storage, testScheduler)
        controller.refresh()
        val bankId = controller.banks.single().bankId
        val before = controller.banks.single().questions.size
        val revisionBefore = controller.banks.single().contentRevision

        controller.openQuestionEditor(bankId)
        assertEquals(Screen.QUESTION_EDITOR, controller.screen)

        controller.questionEditor.add(
            bankId,
            morefish.knowledge.trainer.domain.model.Question(
                questionId = "manual-test-1",
                type = QuestionType.SHORT_ANSWER,
                promptMarkdown = "默写：测试题",
                answerText = "标准答案",
                tags = listOf("manual"),
            ),
        )
        testScheduler.advanceUntilIdle()

        assertTrue(controller.questionEditor.saved, "合法题目应保存成功")
        assertTrue(controller.questionEditor.issues.isEmpty())
        val bank = controller.banks.single()
        assertEquals(before + 1, bank.questions.size)
        assertEquals("manual-test-1", bank.questions.last().questionId)
        assertEquals(revisionBefore + 1, bank.contentRevision, "加了题就是新一版内容，版本号必须跟着走")

        // 新题立即可练（进入可练集合）
        val bankAfter = assertNotNull(controller.banks.single())
        assertTrue(
            bankAfter.questions.any { it.questionId == "manual-test-1" && it.isPracticable() },
            "手工题应可离线判题",
        )
    }

    @Test
    fun invalidManualQuestionIsRejectedFailClosed() = runTest {
        val storage = InMemoryTextStorage()
        seedBank(storage)

        val controller = controller(storage, testScheduler)
        controller.refresh()
        val bankId = controller.banks.single().bankId
        val before = controller.banks.single().questions.size

        // 单选没有正确答案：ERROR 级校验必须拦截，不得落盘
        controller.questionEditor.add(
            bankId,
            morefish.knowledge.trainer.domain.model.Question(
                questionId = "manual-bad",
                type = QuestionType.SINGLE_CHOICE,
                promptMarkdown = "缺答案的单选",
                options = listOf(
                    morefish.knowledge.trainer.domain.model.QuestionOption("o1", "甲"),
                    morefish.knowledge.trainer.domain.model.QuestionOption("o2", "乙"),
                ),
            ),
        )
        testScheduler.advanceUntilIdle()

        assertFalse(controller.questionEditor.saved)
        assertTrue(controller.questionEditor.issues.isNotEmpty())
        assertEquals(before, controller.banks.single().questions.size, "非法题目不得写入题库")
    }
    @Test
    fun manualQuestionWithAnExistingIdIsRejected() = runTest {
        val storage = InMemoryTextStorage()
        seedBank(storage)

        val controller = controller(storage, testScheduler)
        controller.refresh()
        val bankId = controller.banks.single().bankId
        val existing = controller.banks.single().questions.first()
        val before = controller.banks.single().questions.size

        controller.questionEditor.add(
            bankId,
            morefish.knowledge.trainer.domain.model.Question(
                questionId = existing.questionId,
                type = QuestionType.SHORT_ANSWER,
                promptMarkdown = "顶着旧 ID 的新题",
                answerText = "答案",
            ),
        )
        testScheduler.advanceUntilIdle()

        // ID 是进度、复核、错题本匹配题目的唯一依据：撞了 ID 会让"这道题的作答记录"
        // 被两套题面共享，事后分不清哪条历史属于谁。所以宁可拒收，也不覆盖。
        assertFalse(controller.questionEditor.saved)
        assertTrue(
            controller.questionEditor.issues.any { it.code == "duplicate-id" },
            "应指出是 ID 重复，而不是笼统说保存失败",
        )
        assertEquals(before, controller.banks.single().questions.size)
        assertEquals(
            existing.promptMarkdown,
            controller.banks.single().questions.first { it.questionId == existing.questionId }.promptMarkdown,
            "原题必须留在原处，不能被同名 ID 顶掉",
        )
    }

    // ---- 个人笔记 ----

    @Test
    fun questionNoteFromPracticeIsSavedAndSurvivesRestart() = runTest {
        val storage = InMemoryTextStorage()
        seedBank(storage)

        val first = controller(storage, testScheduler)
        first.refresh()
        first.startPractice(first.banks.single(), questionCount = 2)
        val question = assertNotNull(first.currentQuestion)

        first.openNoteForCurrentQuestion()
        assertEquals(Screen.NOTE_EDITOR, first.screen)
        val seed = assertNotNull(first.noteSeed)
        assertEquals(NoteScope.QUESTION, seed.scope)
        assertEquals(question.questionId, seed.questionId)
        assertEquals(first.session!!.bankId, seed.bankId, "题目笔记必须自动挂上当前题库")

        first.saveNote(seed.copy(bodyMarkdown = " 先判断量纲，再代入数值 "))
        testScheduler.advanceUntilIdle()

        assertTrue(first.noteSaved)
        val saved = first.notes.single()
        assertEquals("先判断量纲，再代入数值", saved.bodyMarkdown)
        assertTrue(saved.updatedAtMillis > 0)

        // 换一个控制器 = 重启：笔记必须还在，且关联不断
        val restarted = controller(storage, testScheduler)
        restarted.refresh()
        testScheduler.advanceUntilIdle()
        val reloaded = restarted.notes.single()
        assertEquals(NoteScope.QUESTION, reloaded.scope)
        assertEquals(question.questionId, reloaded.questionId)
        assertEquals(1, restarted.noteCountFor(reloaded.bankId!!))
    }

    @Test
    fun editingAnExistingNoteKeepsItsIdentityAndCreationTime() = runTest {
        val storage = InMemoryTextStorage()
        val c = controller(storage, testScheduler)
        c.openNewNote()
        c.saveNote(assertNotNull(c.noteSeed).copy(bodyMarkdown = "第一版"))
        testScheduler.advanceUntilIdle()
        val created = c.notes.single()

        now += 5_000
        c.saveNote(created.copy(bodyMarkdown = "第二版"))
        testScheduler.advanceUntilIdle()

        val edited = c.notes.single()
        assertEquals(created.noteId, edited.noteId, "编辑不能变成新增一条")
        assertEquals(created.createdAtMillis, edited.createdAtMillis)
        assertTrue(edited.updatedAtMillis > created.updatedAtMillis)
        assertEquals("第二版", edited.bodyMarkdown)
    }

    @Test
    fun emptyNoteIsRejectedFailClosed() = runTest {
        val storage = InMemoryTextStorage()
        val c = controller(storage, testScheduler)
        c.openNewNote()
        c.saveNote(assertNotNull(c.noteSeed))
        testScheduler.advanceUntilIdle()

        assertFalse(c.noteSaved)
        assertTrue(c.notes.isEmpty(), "空白笔记不得落盘")
        assertTrue(c.noteIssues.any { it.code == "note-empty" })
        assertEquals(Screen.NOTE_EDITOR, c.screen, "校验失败应停在编辑器，不能吞掉用户输入")
    }

    @Test
    fun questionNoteWithoutQuestionIsRejected() = runTest {
        val storage = InMemoryTextStorage()
        val c = controller(storage, testScheduler)
        c.openNewNote(NoteScope.QUESTION)
        c.saveNote(assertNotNull(c.noteSeed).copy(bodyMarkdown = "没挂题的题目笔记"))
        testScheduler.advanceUntilIdle()

        assertTrue(c.notes.isEmpty())
        assertTrue(c.noteIssues.any { it.code == "note-question-missing" })
    }

    @Test
    fun pinAndDeleteFollowUserIntent() = runTest {
        val storage = InMemoryTextStorage()
        val c = controller(storage, testScheduler)
        c.openNewNote()
        c.saveNote(assertNotNull(c.noteSeed).copy(bodyMarkdown = "可置顶可删除"))
        testScheduler.advanceUntilIdle()
        val note = c.notes.single()

        c.toggleNotePin(note.noteId)
        testScheduler.advanceUntilIdle()
        assertTrue(c.notes.single().pinned)
        assertEquals(note.updatedAtMillis, c.notes.single().updatedAtMillis, "置顶不应改变最后编辑时间")

        c.deleteNote(note.noteId)
        testScheduler.advanceUntilIdle()
        assertTrue(c.notes.isEmpty())
        assertEquals(Screen.NOTES, c.screen, "删除后回到列表，而不是留在已不存在的编辑器里")
    }

    @Test
    fun noteEditorReturnsToWhereItWasOpenedFrom() = runTest {
        val storage = InMemoryTextStorage()
        seedBank(storage)
        val c = controller(storage, testScheduler)
        c.refresh()

        // 从练习页写笔记：回来必须还是那道题，而不是被丢进笔记列表
        c.startPractice(c.banks.single(), questionCount = 1)
        c.openNoteForCurrentQuestion()
        assertEquals(Screen.NOTE_EDITOR, c.screen)
        c.saveNote(assertNotNull(c.noteSeed).copy(bodyMarkdown = "做题时的想法"))
        testScheduler.advanceUntilIdle()
        c.closeNoteEditor()
        assertEquals(Screen.PRACTICE, c.screen)
        assertNotNull(c.currentQuestion)

        // 从笔记列表进入：回来仍是列表
        c.openNotes()
        c.openNoteEditor(c.notes.single().noteId)
        c.closeNoteEditor()
        assertEquals(Screen.NOTES, c.screen)
    }

    @Test
    fun openingNotesFilteredByBankKeepsFilterUntilCleared() = runTest {
        val storage = InMemoryTextStorage()
        seedBank(storage)
        val c = controller(storage, testScheduler)
        c.refresh()
        val bankId = c.banks.single().bankId

        c.openNotes(bankId)
        assertEquals(bankId, c.noteBankFilter)
        c.filterNotesByBank(null)
        assertNull(c.noteBankFilter)
        c.closeNotes()
        assertNull(c.noteBankFilter, "离开笔记页要清掉筛选，避免下次进来只剩一个题库")
        assertEquals(Screen.HOME, c.screen)
    }


    // ---- 工具箱 ----

    @Test
    fun calculatorKeepsOnlySuccessfulCalculationsInHistory() = runTest {
        val c = controller(InMemoryTextStorage(), testScheduler)

        c.toolbox.append("1+2*3")
        assertEquals("1+2*3", c.toolbox.expression)
        c.toolbox.commit()
        assertEquals(1, c.toolbox.history.size)
        assertEquals("1+2*3", c.toolbox.history.single().expression)
        assertEquals("7", c.toolbox.history.single().result)

        // 算不出来时保留算式、只报错，并且不往记录里塞一条失败的
        c.toolbox.append("/0")
        c.toolbox.commit()
        assertEquals(1, c.toolbox.history.size)
        assertTrue(c.toolbox.committed is CalcOutcome.Failure)

        // 继续编辑就该清掉上一次的结论，否则旧结果看起来像新算式的答案
        c.toolbox.backspace()
        assertNull(c.toolbox.committed)
        assertEquals("1+2*3/", c.toolbox.expression)

        c.toolbox.clear()
        assertEquals("", c.toolbox.expression)
        assertNull(c.toolbox.live, "空算式不该有实时结果")
    }

    @Test
    fun calculatorHistoryIsReusableAndBounded() = runTest {
        val c = controller(InMemoryTextStorage(), testScheduler)
        repeat(25) { index ->
            c.toolbox.clear()
            c.toolbox.append("$index+1")
            c.toolbox.commit()
        }
        assertEquals(20, c.toolbox.history.size, "记录上限 20 条，超出丢最旧的")
        assertEquals("24+1", c.toolbox.history.first().expression)
        assertEquals("25", c.toolbox.history.first().result)

        c.toolbox.useRecord(c.toolbox.history.first())
        assertEquals("24+1", c.toolbox.expression)
        assertNull(c.toolbox.committed)

        c.toolbox.clearHistory()
        assertTrue(c.toolbox.history.isEmpty())
    }

    @Test
    fun toolboxRemembersWhereItWasOpenedFrom() = runTest {
        val c = controller(InMemoryTextStorage(), testScheduler)
        c.openToolbox()
        assertEquals(Screen.TOOLBOX, c.screen)
        assertFalse(c.usedToolsForCurrentQuestion, "不在做题时用工具，不该记到某道题头上")
        c.closeToolbox()
        assertEquals(Screen.HOME, c.screen)
    }

    @Test
    fun usingToolsWhilePracticingIsRecordedOnTheAttemptAndResetsOnNextQuestion() = runTest {
        val storage = InMemoryTextStorage()
        seedBank(storage)
        val c = controller(storage, testScheduler)
        c.refresh()
        c.startPractice(c.banks.single(), questionCount = 2)

        // 先写一半，再去看工具：回来输入不能丢
        val question = assertNotNull(c.currentQuestion)
        if (question.type == QuestionType.SHORT_ANSWER) {
            c.updateTextAnswer("写到一半")
        } else {
            c.toggleOption(question.options.first().optionId)
        }
        val textBefore = c.textAnswer
        val selectedBefore = c.selectedOptionIds
        val orderedBefore = c.orderedOptionIds

        c.openToolbox()
        assertEquals(Screen.TOOLBOX, c.screen)
        assertTrue(c.usedToolsForCurrentQuestion, "从练习页打开工具即算用过")

        c.closeToolbox()
        assertEquals(Screen.PRACTICE, c.screen)
        assertEquals(textBefore, c.textAnswer)
        assertEquals(selectedBefore, c.selectedOptionIds)
        assertEquals(orderedBefore, c.orderedOptionIds)

        answerCurrent(c)
        testScheduler.advanceUntilIdle()
        val attempt = assertNotNull(c.feedback).attempt
        assertTrue(attempt.usedTools, "用了工具就要在历史里留下痕迹")

        c.next()
        assertFalse(c.usedToolsForCurrentQuestion, "换一题必须重新计")
    }

    @Test
    fun unitConversionStateFollowsDimensionAndSwap() = runTest {
        val c = controller(InMemoryTextStorage(), testScheduler)
        assertEquals(Dimension.LENGTH, c.toolbox.dimension)
        assertEquals("m", c.toolbox.fromId)

        c.toolbox.updateInput("2.5")
        val outcome = c.toolbox.conversion
        assertTrue(outcome is Conversion.Ok)
        assertEquals("250", outcome.display)

        c.toolbox.swap()
        assertEquals("cm", c.toolbox.fromId)
        assertEquals("m", c.toolbox.toId)

        c.toolbox.selectDimension(Dimension.MASS)
        assertEquals("kg", c.toolbox.fromId)
        assertEquals("g", c.toolbox.toId)

        // 把「到」设成和「从」同一个单位没有意义，应当被忽略
        c.toolbox.togglePicker(ConvPicker.TO)
        c.toolbox.selectUnit(assertNotNull(Units.resolve("kg")))
        assertEquals("kg", c.toolbox.fromId)
        assertEquals("g", c.toolbox.toId)
        assertEquals(ConvPicker.NONE, c.toolbox.picker)

        c.toolbox.updateInput("不是数字")
        assertNull(c.toolbox.conversion)
    }

    // ---- 知识图谱 ----

    @Test
    fun graphOpensFromHomeAndFromPracticeAndReturnsWhereItCameFrom() = runTest {
        val storage = InMemoryTextStorage()
        seedBank(storage)
        val c = controller(storage, testScheduler)
        c.refresh()
        testScheduler.advanceUntilIdle()

        c.openGraph()
        testScheduler.advanceUntilIdle()
        assertEquals(Screen.GRAPH, c.screen)
        assertNull(c.graphQuestionFilter, "从首页进来是看全部关系")
        c.closeGraph()
        assertEquals(Screen.HOME, c.screen)

        c.startPractice(c.banks.single(), questionCount = 1)
        testScheduler.advanceUntilIdle()
        val ref = QuestionRef(c.session!!.bankId, assertNotNull(c.currentQuestion).questionId)

        c.openGraphForCurrentQuestion()
        testScheduler.advanceUntilIdle()
        assertEquals(Screen.GRAPH, c.screen)
        assertEquals(ref, c.graphQuestionFilter, "从练习页进来只看这道题的关系")

        c.closeGraph()
        assertEquals(Screen.PRACTICE, c.screen)
        assertNotNull(c.currentQuestion, "看图谱不该弄丢正在做的那道题")
    }

    @Test
    fun creatingAnEdgeSearchesAcrossBanksAndCapturesSnapshots() = runTest {
        val storage = InMemoryTextStorage()
        seedBank(storage)
        val c = controller(storage, testScheduler)
        c.refresh()
        testScheduler.advanceUntilIdle()

        val bank = c.banks.single()
        val source = bank.questions[0]
        val target = bank.questions[1]

        c.openGraph()
        testScheduler.advanceUntilIdle()
        c.openGraphEditor(source = QuestionRef(bank.bankId, source.questionId))
        assertEquals(Screen.GRAPH_EDITOR, c.screen)

        c.beginGraphPick(GraphPickSide.TARGET)
        c.updateGraphQuery(target.promptMarkdown.take(4))
        testScheduler.advanceUntilIdle()
        assertTrue(c.graphHits.isNotEmpty(), "跨库搜题应当能搜到题干")
        c.pickGraphHit(c.graphHits.first())
        assertEquals(GraphPickSide.NONE, c.graphPickSide, "选完就收起搜索框")

        c.updateGraphNote("这两道题一起看更容易记住")
        c.saveGraphEdge()
        testScheduler.advanceUntilIdle()

        assertEquals(Screen.GRAPH, c.screen, "保存后回图谱列表，而不是停在编辑器里")
        val edge = c.graph.edges.single()
        assertEquals(source.questionId, edge.source.questionId)
        assertEquals("这两道题一起看更容易记住", edge.noteMarkdown)
        assertNotNull(c.graph.snapshotOf(edge.source), "建立关系时要留下两端快照")
        assertNotNull(c.graph.snapshotOf(edge.target))
        assertEquals(1, c.graphView.relationCount)
        assertEquals(0, c.graphView.problemRefCount)

        // 落盘了才算真的保存
        assertEquals(1, JsonGraphRepository(storage).load().edges.size)
        assertEquals(1, c.graphEdgeCountFor(edge.source), "练习页的「关联 N」直接读这张图")
    }

    @Test
    fun selfReferenceIsRejectedWithoutLosingTheDraft() = runTest {
        val storage = InMemoryTextStorage()
        seedBank(storage)
        val c = controller(storage, testScheduler)
        c.refresh()
        testScheduler.advanceUntilIdle()

        val bank = c.banks.single()
        val question = bank.questions.first()
        val ref = QuestionRef(bank.bankId, question.questionId)

        c.openGraph()
        testScheduler.advanceUntilIdle()
        c.openGraphEditor(source = ref)
        c.beginGraphPick(GraphPickSide.TARGET)
        c.updateGraphQuery(question.promptMarkdown.take(4))
        testScheduler.advanceUntilIdle()
        c.pickGraphHit(c.graphHits.first())
        c.updateGraphNote("写了一半的备注")
        c.saveGraphEdge()
        testScheduler.advanceUntilIdle()

        assertEquals(Screen.GRAPH_EDITOR, c.screen, "校验失败不该把人踢回列表")
        assertTrue(c.graphIssues.any { it.code == "graph-self-reference" })
        assertEquals("写了一半的备注", c.graphDraftNote, "失败后草稿必须还在")
        assertNotNull(c.graphSource)
        assertTrue(JsonGraphRepository(storage).load().edges.isEmpty(), "没通过校验就不能落盘")
    }

    @Test
    fun hardDependencyOnUninstalledBankIsReportedWhilePractising() = runTest {
        val storage = InMemoryTextStorage()
        seedBank(storage)

        // 先开一次练习拿到题目，再给整个库的每道题都挂一条指向"未安装题库"的硬依赖
        val probe = controller(storage, testScheduler)
        probe.refresh()
        testScheduler.advanceUntilIdle()
        val bank = probe.banks.single()
        val now = clock.nowMillis()
        val edges = bank.questions.mapIndexed { index, question ->
            GraphEdge(
                edgeId = "edge-hard-$index",
                source = QuestionRef(bank.bankId, question.questionId),
                target = QuestionRef("bank-not-here", "q1"),
                relation = GraphRelation.EXPLAINS,
                dependency = DependencyKind.HARD,
                createdAtMillis = now,
                updatedAtMillis = now,
            )
        }
        JsonGraphRepository(storage).save(KnowledgeGraph(edges = edges))

        val c = controller(storage, testScheduler)
        c.refresh()
        testScheduler.advanceUntilIdle()
        c.startPractice(c.banks.single(), questionCount = 1)
        testScheduler.advanceUntilIdle()

        assertEquals(1, c.blockedHardDependencies, "目标题库不在本机，判题依据必须标成暂停")

        answerCurrent(c)
        testScheduler.advanceUntilIdle()
        assertEquals(1, c.blockedHardDependencies, "提交后仍然如实标注，不因为答完了就消失")
    }

    @Test
    fun navigationEdgesDoNotPauseAnythingWhenTargetsAreMissing() = runTest {
        val storage = InMemoryTextStorage()
        seedBank(storage)
        val probe = controller(storage, testScheduler)
        probe.refresh()
        testScheduler.advanceUntilIdle()
        val bank = probe.banks.single()
        val now = clock.nowMillis()
        JsonGraphRepository(storage).save(
            KnowledgeGraph(
                edges = bank.questions.mapIndexed { index, question ->
                    GraphEdge(
                        edgeId = "edge-nav-$index",
                        source = QuestionRef(bank.bankId, question.questionId),
                        target = QuestionRef("bank-not-here", "q1"),
                        relation = GraphRelation.RELATED,
                        dependency = DependencyKind.NAVIGATION,
                        createdAtMillis = now,
                        updatedAtMillis = now,
                    )
                },
            ),
        )

        val c = controller(storage, testScheduler)
        c.refresh()
        testScheduler.advanceUntilIdle()
        c.startPractice(c.banks.single(), questionCount = 1)
        testScheduler.advanceUntilIdle()

        assertEquals(0, c.blockedHardDependencies, "导航关系缺了只是少条线索，不该被说成判题暂停")
        c.openGraph()
        testScheduler.advanceUntilIdle()
        assertTrue(c.graphView.problemRefCount > 0, "但在图谱页仍然要如实标出缺失")
        assertEquals(0, c.graphView.blockedCount)
    }

    @Test
    fun bankUpdateMarksEdgeForRevalidationUntilConfirmed() = runTest {
        val storage = InMemoryTextStorage()
        seedBank(storage)
        val c = controller(storage, testScheduler)
        c.refresh()
        testScheduler.advanceUntilIdle()
        val bank = c.banks.single()
        val source = bank.questions[0]
        val target = bank.questions[1]
        val now = clock.nowMillis()

        val sourceRef = QuestionRef(bank.bankId, source.questionId)
        val targetRef = QuestionRef(bank.bankId, target.questionId)
        JsonGraphRepository(storage).save(
            KnowledgeGraph(
                snapshots = listOf(
                    GraphSnapshot(sourceRef, bank.title, source.promptMarkdown, source.contentRevision, now),
                    GraphSnapshot(targetRef, bank.title, target.promptMarkdown, target.contentRevision, now),
                ),
                edges = listOf(
                    GraphEdge(
                        edgeId = "edge-rev",
                        source = sourceRef,
                        target = targetRef,
                        relation = GraphRelation.EXPLAINS,
                        dependency = DependencyKind.NAVIGATION,
                        createdAtMillis = now,
                        updatedAtMillis = now,
                    ),
                ),
            ),
        )
        c.openGraph()
        testScheduler.advanceUntilIdle()
        assertEquals(0, c.graphView.problemRefCount)

        // 作者更新了目标题：内容版本从 1 变成 2
        val updated = bank.copy(
            questions = bank.questions.map {
                if (it.questionId == target.questionId) {
                    it.copy(contentRevision = 2, promptMarkdown = it.promptMarkdown + "（补充说明）")
                } else {
                    it
                }
            },
        )
        AppDependencies(storage, clock).bankRepository.save(updated)

        c.openGraph()
        testScheduler.advanceUntilIdle()
        assertTrue(c.graphView.edges.single().target.status == morefish.knowledge.trainer.domain.graph.RefStatus.NEEDS_REVALIDATION)

        c.revalidateGraphEdge("edge-rev")
        testScheduler.advanceUntilIdle()
        assertEquals(0, c.graphView.problemRefCount, "人工确认后按当前版本重记快照")
        assertEquals(2, c.graph.snapshotOf(QuestionRef(bank.bankId, target.questionId))?.contentRevision)
    }

    @Test
    fun deletingAnEdgeRemovesItFromDiskAndDropsItsSnapshots() = runTest {
        val storage = InMemoryTextStorage()
        seedBank(storage)
        val c = controller(storage, testScheduler)
        c.refresh()
        testScheduler.advanceUntilIdle()
        val bank = c.banks.single()
        val now = clock.nowMillis()
        JsonGraphRepository(storage).save(
            KnowledgeGraph(
                edges = listOf(
                    GraphEdge(
                        edgeId = "edge-del",
                        source = QuestionRef(bank.bankId, bank.questions[0].questionId),
                        target = QuestionRef(bank.bankId, bank.questions[1].questionId),
                        relation = GraphRelation.CONTRASTS,
                        createdAtMillis = now,
                        updatedAtMillis = now,
                    ),
                ),
            ),
        )
        c.openGraph()
        testScheduler.advanceUntilIdle()
        c.revalidateGraphEdge("edge-del")
        testScheduler.advanceUntilIdle()
        assertTrue(c.graph.snapshots.isNotEmpty())

        c.deleteGraphEdge("edge-del")
        testScheduler.advanceUntilIdle()

        assertTrue(c.graph.edges.isEmpty())
        assertTrue(c.graph.snapshots.isEmpty(), "关系没了，快照也没有存在理由")
        val onDisk = JsonGraphRepository(storage).load()
        assertTrue(onDisk.edges.isEmpty())
        assertTrue(onDisk.snapshots.isEmpty())
    }

}
