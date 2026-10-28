package morefish.knowledge.trainer.domain.backup

import morefish.knowledge.trainer.domain.extension.BankBinding
import morefish.knowledge.trainer.domain.extension.ExtensionAnnotation
import morefish.knowledge.trainer.domain.extension.ExtensionPack
import morefish.knowledge.trainer.domain.extension.ExtensionRelation
import morefish.knowledge.trainer.domain.graph.DependencyKind
import morefish.knowledge.trainer.domain.graph.GraphEdge
import morefish.knowledge.trainer.domain.graph.GraphRelation
import morefish.knowledge.trainer.domain.graph.KnowledgeGraph
import morefish.knowledge.trainer.domain.graph.QuestionRef
import morefish.knowledge.trainer.domain.model.AttemptRecord
import morefish.knowledge.trainer.domain.model.Note
import morefish.knowledge.trainer.domain.model.NoteScope
import morefish.knowledge.trainer.domain.model.PracticeMode
import morefish.knowledge.trainer.domain.model.Question
import morefish.knowledge.trainer.domain.model.QuestionBank
import morefish.knowledge.trainer.domain.model.QuestionType
import morefish.knowledge.trainer.domain.model.Verdict
import morefish.knowledge.trainer.domain.model.mediaRelativePath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 引用闭包自检的纯函数矩阵。
 *
 * 这些用例守的是一句话：**自检既不能漏报，也不能把"正常的卸载"说成"数据损坏"。**
 * 前者让用户带着一个自己不知道的窟窿搬机器，后者让这个功能第一次打开就被关掉。
 */
class ReferenceAuditTest {

    // ---- 构造工具 ----

    private fun question(id: String, assets: List<String> = emptyList()) = Question(
        questionId = id,
        type = QuestionType.MULTIPLE_CHOICE,
        promptMarkdown = "题 $id",
        options = listOf(),
        correctOptionIds = emptySet(),
        assetIds = assets,
    )

    private fun bank(id: String, title: String, questions: List<Question>) = QuestionBank(
        bankId = id,
        title = title,
        sourceLocale = "zh-Hans",
        questions = questions,
        importedAtMillis = 1_700_000_000_000L,
        sourceLabel = "test",
    )

    private fun edge(id: String, from: Pair<String, String>, to: Pair<String, String>) = GraphEdge(
        edgeId = id,
        source = QuestionRef(from.first, from.second),
        target = QuestionRef(to.first, to.second),
        relation = GraphRelation.RELATED,
        dependency = DependencyKind.NAVIGATION,
        createdAtMillis = 1_700_000_000_000L,
        updatedAtMillis = 1_700_000_000_000L,
    )

    private fun attempt(bankId: String, questionId: String) = AttemptRecord(
        attemptId = "a-$bankId-$questionId",
        sessionId = "s1",
        bankId = bankId,
        questionId = questionId,
        questionType = QuestionType.MULTIPLE_CHOICE,
        mode = PracticeMode.NATIVE,
        displayOptionIds = emptyList(),
        selectedOptionIds = emptySet(),
        textAnswer = null,
        verdict = Verdict.CORRECT,
        strategyId = "exact-set",
        gradingSpec = "exact-set",
        answerRevision = 1,
        questionFingerprint = "ffff",
        submittedAtMillis = 1_700_000_000_000L,
        elapsedMillis = 1_000,
        explanationSeen = false,
    )

    private fun pack(
        bankId: String,
        relations: List<ExtensionRelation> = emptyList(),
        annotations: List<ExtensionAnnotation> = emptyList(),
    ) = ExtensionPack(
        packageId = "p-$bankId",
        name = "包",
        version = "1",
        author = "test",
        binding = BankBinding(bankId, "提示"),
        relations = relations,
        annotations = annotations,
    )

    private val cleanBank = bank("b1", "化学", listOf(question("q1"), question("q2")))

    // ---- 全接上 ----

    @Test
    fun everythingResolvesIsClean() {
        val graph = KnowledgeGraph(
            edges = listOf(edge("e1", "b1" to "q1", "b1" to "q2")),
        )
        val audit = auditReferences(
            banks = listOf(cleanBank),
            graph = graph,
            notes = listOf(Note(noteId = "n1", scope = NoteScope.QUESTION, bankId = "b1", questionId = "q2")),
            extensions = listOf(pack("b1", annotations = listOf(ExtensionAnnotation("an1", "q1", "讲", "正文")))),
            history = listOf(attempt("b1", "q1")),
        )

        assertTrue(audit.isClean)
        assertEquals(0, audit.gapCount)
        assertEquals(1, audit.banks)
        assertEquals(2, audit.questions)
        assertEquals(1, audit.graphEdges)
        assertEquals(1, audit.notes)
        assertEquals(1, audit.extensionPacks)
        assertEquals(1, audit.historyBanks)
        // 图谱 1 + 笔记 1 + 扩展包 1 + 历史 1
        assertEquals(4, audit.refCount)
        assertTrue(audit.summary.contains("全部接上"), audit.summary)
        // 没传文件清单：必须自称"没核对"，而不是"一张图都不缺"
        assertFalse(audit.mediaChecked)
        assertTrue(audit.summary.contains("图片没有核对"), audit.summary)
    }

    // ---- 题库整个不在：合并 ----

    @Test
    fun missingBankMergesEveryReferenceIntoOneGap() {
        // 三条图谱关系 + 一条笔记 + 一个扩展包绑定，全都指着同一个没装的库
        val graph = KnowledgeGraph(
            edges = listOf(
                edge("e1", "b1" to "q1", "gone" to "x1"),
                edge("e2", "b1" to "q1", "gone" to "x2"),
                edge("e3", "b1" to "q2", "gone" to "x1"),
            ),
        )
        val audit = auditReferences(
            banks = listOf(cleanBank),
            graph = graph,
            notes = listOf(Note(noteId = "n1", scope = NoteScope.BANK, bankId = "gone")),
            extensions = listOf(pack("gone")),
            history = listOf(attempt("b1", "q1")),
        )

        assertEquals(1, audit.gapCount, "同一个没装的库必须只有一条，否则十条关系看起来像十件事")
        val gap = audit.gaps.single()
        assertEquals(RefGapKind.BANK_MISSING, gap.kind)
        assertEquals("gone", gap.bankId)
        assertEquals(null, gap.questionId, "整库不在时说'哪道题'没有意义")
        assertEquals(null, gap.bankTitle, "库都不在，标题无从得知")
        assertEquals(1, audit.bankGapCount)
        assertEquals(0, audit.questionGapCount)
        // 图谱 3 处 + 笔记 1 处 + 扩展包 1 处
        assertEquals(5, gap.occurrences)
        assertEquals(listOf(RefOwner.GRAPH, RefOwner.NOTE, RefOwner.EXTENSION), gap.owners)
        assertTrue(gap.target.contains("gone".take(8)), gap.target)
    }

    // ---- 题目级缺失：逐题保留 ----

    @Test
    fun deletedQuestionsStaySeparate() {
        val graph = KnowledgeGraph(
            edges = listOf(
                edge("e1", "b1" to "q1", "b1" to "q404"),
                edge("e2", "b1" to "q2", "b1" to "q405"),
                edge("e3", "b1" to "q1", "b1" to "q404"),
            ),
        )
        val audit = auditReferences(
            banks = listOf(cleanBank),
            graph = graph,
            notes = emptyList(),
            extensions = emptyList(),
            history = emptyList(),
        )

        assertEquals(2, audit.gapCount, "删了两道题就是两条，合并会把两道说成一道")
        assertEquals(2, audit.questionGapCount)
        assertEquals("q404", audit.gaps[0].questionId, "同一个目标的两条关系要合并，所以多的排前面")
        assertEquals(2, audit.gaps[0].occurrences)
        assertEquals("q405", audit.gaps[1].questionId)
        assertEquals(1, audit.gaps[1].occurrences)
        // 库在，所以有标题
        assertEquals("化学", audit.gaps[0].bankTitle)
        assertTrue(audit.gaps[0].target.contains("化学"), audit.gaps[0].target)
    }

    // ---- 笔记的三种 scope ----

    @Test
    fun draftNotesAreNotReferences() {
        val audit = auditReferences(
            banks = listOf(cleanBank),
            graph = KnowledgeGraph(),
            notes = listOf(
                Note(noteId = "n1", scope = NoteScope.DRAFT, bodyMarkdown = "随手写"),
                Note(noteId = "n2", scope = NoteScope.BANK, bankId = "b1"),
                Note(noteId = "n3", scope = NoteScope.QUESTION, bankId = "b1", questionId = "q1"),
            ),
            extensions = emptyList(),
            history = emptyList(),
        )

        assertEquals(2, audit.notes, "草稿按定义就没有归属，算进来会让自检永远不干净")
        assertTrue(audit.isClean)
    }

    @Test
    fun bankNoteReportsAtBankLevelAndQuestionNoteAtQuestionLevel() {
        val audit = auditReferences(
            banks = listOf(cleanBank),
            graph = KnowledgeGraph(),
            notes = listOf(
                Note(noteId = "n1", scope = NoteScope.BANK, bankId = "gone"),
                Note(noteId = "n2", scope = NoteScope.QUESTION, bankId = "b1", questionId = "q404"),
            ),
            extensions = emptyList(),
            history = emptyList(),
        )

        assertEquals(2, audit.gapCount)
        val bankGap = audit.gaps.single { it.kind == RefGapKind.BANK_MISSING }
        val questionGap = audit.gaps.single { it.kind == RefGapKind.QUESTION_MISSING }
        assertEquals(null, bankGap.questionId)
        assertEquals("q404", questionGap.questionId)
        assertEquals(listOf(RefOwner.NOTE), questionGap.owners)
    }

    // ---- 扩展包 ----

    @Test
    fun extensionBindingAndAnnotationAreBothChecked() {
        val audit = auditReferences(
            banks = listOf(cleanBank),
            // 关系本身已经在合并后的图谱里，这里不再重复计
            graph = KnowledgeGraph(),
            notes = emptyList(),
            extensions = listOf(
                pack("b1", annotations = listOf(ExtensionAnnotation("an1", "q404", "讲", "正文"))),
                pack("gone"),
            ),
            history = emptyList(),
        )

        assertEquals(2, audit.gapCount)
        val annotation = audit.gaps.single { it.questionId == "q404" }
        assertEquals(RefGapKind.QUESTION_MISSING, annotation.kind)
        assertEquals(listOf(RefOwner.EXTENSION), annotation.owners)
        val binding = audit.gaps.single { it.bankId == "gone" }
        assertEquals(RefGapKind.BANK_MISSING, binding.kind)
    }

    // ---- 历史只查库级 ----

    @Test
    fun historyOnlyChecksTheBank() {
        // 补丁删题是正常操作：历史指向一道已被删的题，是"确实做过"的证据，不是悬空
        val audit = auditReferences(
            banks = listOf(cleanBank),
            graph = KnowledgeGraph(),
            notes = emptyList(),
            extensions = emptyList(),
            history = listOf(attempt("b1", "q404"), attempt("b1", "q405")),
        )

        assertTrue(audit.isClean, "历史指向已删的题不该被报成缺口")
        assertEquals(1, audit.historyBanks)
        assertEquals(1, audit.refCount, "三千条历史只涉及一个库，算一处引用")
    }

    @Test
    fun historyForAMissingBankIsReported() {
        val audit = auditReferences(
            banks = listOf(cleanBank),
            graph = KnowledgeGraph(),
            notes = emptyList(),
            extensions = emptyList(),
            history = listOf(attempt("b1", "q1"), attempt("gone", "x1"), attempt("gone", "x2")),
        )

        assertEquals(1, audit.gapCount)
        val gap = audit.gaps.single()
        assertEquals("gone", gap.bankId)
        assertEquals(RefGapKind.BANK_MISSING, gap.kind)
        assertEquals(listOf(RefOwner.HISTORY), gap.owners)
        assertEquals(1, gap.occurrences, "按库去重，不是按记录数")
        assertEquals(2, audit.historyBanks)
    }

    // ---- 媒体 ----

    @Test
    fun nullPathSetSkipsMediaEntirely() {
        val bank = bank("b1", "带图", listOf(question("q1", listOf("img-a"))))
        val audit = auditReferences(
            banks = listOf(bank),
            graph = KnowledgeGraph(),
            notes = emptyList(),
            extensions = emptyList(),
            history = emptyList(),
            availablePaths = null,
        )

        assertFalse(audit.mediaChecked)
        assertTrue(audit.mediaGaps.isEmpty(), "不知道有哪些文件时，不能把每张图都报成缺失")
        assertEquals(1, audit.mediaReferenced)
        assertTrue(audit.isClean)
    }

    @Test
    fun presentMediaIsNotReported() {
        val bank = bank("b1", "带图", listOf(question("q1", listOf("img-a", "img-b"))))
        val audit = auditReferences(
            banks = listOf(bank),
            graph = KnowledgeGraph(),
            notes = emptyList(),
            extensions = emptyList(),
            history = emptyList(),
            availablePaths = setOf(
                mediaRelativePath("b1", "img-a"),
                mediaRelativePath("b1", "img-b"),
                "banks-v1.json",
            ),
        )

        assertTrue(audit.mediaChecked)
        assertEquals(2, audit.mediaReferenced)
        assertTrue(audit.mediaGaps.isEmpty())
        assertTrue(audit.isClean)
    }

    @Test
    fun missingMediaCountsReferencingQuestions() {
        // 两道题引用同一张缺失的图：那是一张图的缺口，不是两处
        val bank = bank(
            "b1", "带图",
            listOf(
                question("q1", listOf("img-a")),
                question("q2", listOf("img-a", "img-b")),
            ),
        )
        val audit = auditReferences(
            banks = listOf(bank),
            graph = KnowledgeGraph(),
            notes = emptyList(),
            extensions = emptyList(),
            history = emptyList(),
            availablePaths = emptySet(),
        )

        assertEquals(2, audit.mediaReferenced)
        assertEquals(2, audit.mediaGaps.size)
        val a = audit.mediaGaps.single { it.assetId == "img-a" }
        assertEquals(2, a.questionCount)
        assertEquals("带图", a.bankTitle)
        assertFalse(audit.isClean, "缺图会让一道题看起来坏了，不能算干净")
    }

    @Test
    fun mediaPathUsesTheSharedRuleNotTheRawName() {
        // assetId 里有斜杠与空格：落盘时会被压成下划线，自检必须按同一条规则算
        val bank = bank("b1", "怪名字", listOf(question("q1", listOf("a/b c.png"))))
        val audit = auditReferences(
            banks = listOf(bank),
            graph = KnowledgeGraph(),
            notes = emptyList(),
            extensions = emptyList(),
            history = emptyList(),
            availablePaths = setOf(mediaRelativePath("b1", "a/b c.png")),
        )

        assertTrue(audit.mediaGaps.isEmpty(), "自检与写入方必须用同一个路径函数")
    }

    // ---- 汇总文案与排序 ----

    @Test
    fun summaryStatesBothHalves() {
        val bank = bank("b1", "带图", listOf(question("q1", listOf("missing-img"))))
        val audit = auditReferences(
            banks = listOf(bank),
            graph = KnowledgeGraph(edges = listOf(edge("e1", "b1" to "q1", "b1" to "q404"))),
            notes = emptyList(),
            extensions = emptyList(),
            history = emptyList(),
            availablePaths = emptySet(),
        )

        assertTrue(audit.summary.contains("1 个目标接不上"), audit.summary)
        assertTrue(audit.summary.contains("缺 1 张"), audit.summary)
    }

    @Test
    fun emptyDataSaysNothingToCheckInsteadOfAllGood() {
        val audit = auditReferences(
            banks = emptyList(),
            graph = KnowledgeGraph(),
            notes = emptyList(),
            extensions = emptyList(),
            history = emptyList(),
            availablePaths = emptySet(),
        )

        assertTrue(audit.summary.contains("没有需要核对的引用"), audit.summary)
        assertTrue(audit.summary.contains("没有引用的图片"), audit.summary)
        assertTrue(audit.isClean)
    }

    @Test
    fun bankGapsComeFirstThenByOccurrences() {
        val graph = KnowledgeGraph(
            edges = listOf(
                // 题级缺失，两处
                edge("e1", "b1" to "q1", "b1" to "q404"),
                edge("e2", "b1" to "q1", "b1" to "q404"),
                // 库级缺失，一处
                edge("e3", "b1" to "q1", "gone" to "x1"),
            ),
        )
        val audit = auditReferences(
            banks = listOf(cleanBank),
            graph = graph,
            notes = emptyList(),
            extensions = emptyList(),
            history = emptyList(),
        )

        // "那个库还在吗"比"哪道题被删了"更要紧，所以库级缺失排前面
        assertEquals(RefGapKind.BANK_MISSING, audit.gaps[0].kind)
        assertEquals(RefGapKind.QUESTION_MISSING, audit.gaps[1].kind)
    }
}
