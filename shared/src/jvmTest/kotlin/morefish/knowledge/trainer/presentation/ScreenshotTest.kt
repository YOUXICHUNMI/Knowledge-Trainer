package morefish.knowledge.trainer.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import morefish.knowledge.trainer.application.port.FilePicker
import morefish.knowledge.trainer.application.port.PickedFile
import morefish.knowledge.trainer.infrastructure.storage.DirectoryVault
import morefish.knowledge.trainer.infrastructure.storage.FileMediaStore
import morefish.knowledge.trainer.infrastructure.storage.FileTextStorage
import morefish.knowledge.trainer.application.port.AiHttpClient
import morefish.knowledge.trainer.application.port.AiHttpRequest
import morefish.knowledge.trainer.application.port.AiHttpResponse
import morefish.knowledge.trainer.application.port.InMemoryTextStorage
import morefish.knowledge.trainer.application.usecase.ImportBankUseCase
import morefish.knowledge.trainer.application.usecase.ImportOutcome
import morefish.knowledge.trainer.domain.ai.AiConfig
import morefish.knowledge.trainer.domain.model.AttemptRecord
import morefish.knowledge.trainer.domain.model.NoteScope
import morefish.knowledge.trainer.domain.model.PracticeMode
import morefish.knowledge.trainer.domain.model.ProvenanceOrigin
import morefish.knowledge.trainer.domain.model.Question
import morefish.knowledge.trainer.domain.model.QuestionBank
import morefish.knowledge.trainer.domain.model.QuestionOption
import morefish.knowledge.trainer.domain.model.QuestionType
import morefish.knowledge.trainer.domain.model.Verdict
import morefish.knowledge.trainer.domain.graph.DependencyKind
import morefish.knowledge.trainer.domain.graph.GraphEdge
import morefish.knowledge.trainer.domain.graph.GraphRelation
import morefish.knowledge.trainer.domain.graph.GraphSnapshot
import morefish.knowledge.trainer.domain.graph.KnowledgeGraph
import morefish.knowledge.trainer.domain.graph.QuestionRef
import morefish.knowledge.trainer.domain.tools.Dimension
import morefish.knowledge.trainer.domain.tools.Units
import morefish.knowledge.trainer.infrastructure.archive.Sha256
import morefish.knowledge.trainer.application.port.ChoiceMapping
import morefish.knowledge.trainer.infrastructure.storage.JsonGraphRepository
import morefish.knowledge.trainer.presentation.toolbox.ToolboxTab
import morefish.knowledge.trainer.presentation.toolbox.ConvPicker
import morefish.knowledge.trainer.presentation.toolbox.CalcRecord
import morefish.knowledge.trainer.presentation.theme.KtTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 离屏渲染各页面为 PNG，用来人工核对排版与配色。
 *
 * 默认不执行：它需要 Skia 图形栈，且在无显示环境里没有意义。
 * 需要看图时：`KT_SHOTS=1 ./gradlew :shared:jvmTest --tests "*ScreenshotTest*"`。
 */
class ScreenshotTest {

    private val outDir = File("build/ui-shots").apply { mkdirs() }

    @Test
    fun renderAllScreens() {
        if (System.getenv("KT_SHOTS") != "1") {
            println("跳过 ScreenshotTest：设置 KT_SHOTS=1 才会渲染")
            return
        }

        val storage = InMemoryTextStorage()
        val deps = AppDependencies(storage)
        val controller = AppController(deps, CoroutineScope(Dispatchers.Unconfined))

        val outcome = runBlocking {
            ImportBankUseCase(deps.bankRepository, clock = deps.clock)
                .import(SampleBank.json, ChoiceMapping.AUTO, "示例题库", "screenshot")
        }
        val imported = (outcome as? ImportOutcome.Imported)?.bank
            ?: error("示例题库导入失败：$outcome")

        // 造几天的历史，让日历与"最近提交"有内容可看
        runBlocking {
            fakeHistory(imported).forEach { deps.attemptRepository.append(it) }
        }
        controller.refresh()

        shot("01-home") { AppContent(controller) }
        shot("02-practice-setup") {
            controller.openSetup(imported.bankId)
            AppContent(controller)
        }
        shot("03-practice") {
            controller.startPractice(imported, questionCount = 3)
            AppContent(controller)
        }
        shot("04-practice-feedback") {
            // 故意选错，看错误态的配色与差异标注
            val first = controller.session?.current ?: error("没有会话")
            val wrongOption = first.orderedOptions().firstOrNull { it.optionId !in first.question.correctOptionIds }
            if (wrongOption != null) {
                controller.toggleOption(wrongOption.optionId)
            } else {
                controller.updateTextAnswer("一个明显不对的答案")
            }
            controller.submit()
            AppContent(controller)
        }
        shot("05-summary") {
            controller.finish()
            AppContent(controller)
        }
        shot("06-history") {
            controller.openHistory()
            AppContent(controller)
        }
        shot("07-wrong") {
            controller.openWrong()
            AppContent(controller)
        }
        shot("08-import") {
            controller.openImport(SampleBank.json)
            AppContent(controller)
        }
        shot("09-standard") {
            controller.openStandard(Screen.STANDARD, Screen.IMPORT)
            AppContent(controller)
        }
        shot("12-question-editor") {
            controller.openQuestionEditor(controller.banks.single().bankId)
            AppContent(controller)
        }

        val files = outDir.listFiles { f -> f.extension == "png" }.orEmpty()
        assertTrue(files.size >= 9, "应生成 9 张截图，实际 ${files.size}")
        println("截图目录：${outDir.absolutePath}")
    }

    /**
     * 媒体渲染核对：导入真实 B 类题库（1143 题 + 24 官方附图），
     * 取一道附图题走"错题 → 错题本 → 重练"路径，验证练习页与错题卡都能显示图片。
     */
    @Test
    fun renderPracticeWithFigure() {
        if (System.getenv("KT_SHOTS") != "1") {
            println("跳过 ScreenshotTest：设置 KT_SHOTS=1 才会渲染")
            return
        }
        val bankFile = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .take(6)
            .map { File(it, "banks/ham-b-2025.qbank") }
            .firstOrNull { it.isFile }
            ?: return println("跳过：找不到 banks/ham-b-2025.qbank")

        val storage = InMemoryTextStorage()
        val deps = AppDependencies(storage) // 默认 InMemoryMediaStore，验证内存路径也能出图
        val controller = AppController(deps, CoroutineScope(Dispatchers.Unconfined))

        val outcome = runBlocking {
            ImportBankUseCase(deps.bankRepository, media = deps.mediaStore, clock = deps.clock)
                .importQbank(bankFile.readBytes(), "B类题库", "screenshot")
        }
        val bank = (outcome as? ImportOutcome.Imported)?.bank
            ?: error("B 类题库导入失败：$outcome")
        controller.refresh()

        // 造一条该附图题的错误历史，让错题本聚合出这张卡
        val figQuestion = bank.questions.first { it.assetIds.isNotEmpty() }
        runBlocking {
            deps.attemptRepository.append(
                AttemptRecord(
                    attemptId = "shot-figure",
                    sessionId = "shot-figure-session",
                    bankId = bank.bankId,
                    questionId = figQuestion.questionId,
                    questionType = figQuestion.type,
                    mode = PracticeMode.NATIVE,
                    displayOptionIds = figQuestion.options.map { it.optionId },
                    selectedOptionIds = setOf(figQuestion.options.first().optionId),
                    textAnswer = null,
                    verdict = Verdict.INCORRECT,
                    strategyId = "exact-set",
                    gradingSpec = "{}",
                    answerRevision = figQuestion.contentRevision,
                    questionFingerprint = figQuestion.contentFingerprint(),
                    submittedAtMillis = depsNow(),
                    elapsedMillis = 20_000L,
                    explanationSeen = false,
                ),
            )
        }
        controller.refresh()

        shot("10-wrong-figure") {
            controller.openWrong()
            AppContent(controller)
        }
        shot("11-practice-figure") {
            controller.startWrongReview(controller.historyView.wrongEntries)
            AppContent(controller)
        }
        println("附图题 ${figQuestion.questionId} assets=${figQuestion.assetIds}")
    }

    /**
     * 富文本核对：题干里的 Markdown 强调、行内公式、独立公式块、分式/根号/求和，
     * 以及解析里的列表。用真实布局（不是 mock）离屏渲染，人眼核对基线有没有错位。
     */
    @Test
    fun renderRichText() {
        if (System.getenv("KT_SHOTS") != "1") {
            println("跳过 ScreenshotTest：设置 KT_SHOTS=1 才会渲染")
            return
        }
        val storage = InMemoryTextStorage()
        val deps = AppDependencies(storage)
        val controller = AppController(deps, CoroutineScope(Dispatchers.Unconfined))

        val bank = QuestionBank(
            bankId = "rich-shot",
            title = "富文本渲染核对",
            sourceLocale = "zh-Hans",
            questions = listOf(
                Question(
                    questionId = "r1",
                    type = QuestionType.SINGLE_CHOICE,
                    promptMarkdown = "某线圈的电感 \$L = 100\\,\\mathrm{mH}\$，当通过它的电流在 " +
                        "\$0.2\\,\\mathrm{s}\$ 内由 0 均匀增大到 \$2\\,\\mathrm{A}\$ 时，线圈两端的自感电动势为：",
                    options = listOf(
                        QuestionOption("r1-a", "\$1\\,\\mathrm{V}\$"),
                        QuestionOption("r1-b", "\$0.5\\,\\mathrm{V}\$"),
                        QuestionOption("r1-c", "\$2\\,\\mathrm{V}\$"),
                        QuestionOption("r1-d", "\$10\\,\\mathrm{V}\$"),
                    ),
                    correctOptionIds = setOf("r1-a"),
                    explanationMarkdown = "**法拉第电磁感应定律**给出 \$e = -L\\dfrac{\\Delta I}{\\Delta t}\$。\n\n" +
                        "代入 \$\\Delta I = 2\\,\\mathrm{A}\$、\$\\Delta t = 0.2\\,\\mathrm{s}\$：\n\n" +
                        "\$\$e = 0.1 \\times \\frac{2}{0.2} = 1\\,\\mathrm{V}\$\$\n\n所以选 A。",
                ),
                Question(
                    questionId = "r2",
                    type = QuestionType.SINGLE_CHOICE,
                    promptMarkdown = "谐振频率 \$f_0\$ 与电感 \$L\$、电容 \$C\$ 的关系是：" +
                        "\$\$f_0 = \\frac{1}{2\\pi\\sqrt{LC}}\$\$\n\n" +
                        "其中 \$\\pi \\approx 3.1416\$，\$\\sqrt{2} \\approx 1.414\$。",
                    options = listOf(
                        QuestionOption("r2-a", "\$f_0 = \\frac{1}{2\\pi\\sqrt{LC}}\$"),
                        QuestionOption("r2-b", "\$f_0 = 2\\pi\\sqrt{LC}\$"),
                        QuestionOption("r2-c", "\$f_0 = \\frac{1}{2\\pi LC}\$"),
                        QuestionOption("r2-d", "\$f_0 = \\sqrt{\\frac{L}{C}}\$"),
                    ),
                    correctOptionIds = setOf("r2-a"),
                    explanationMarkdown = "串联谐振时 \$X_L = X_C\$，即 \$2\\pi f L = \\frac{1}{2\\pi f C}\$，" +
                        "解得 \$f_0 = \\frac{1}{2\\pi\\sqrt{LC}}\$。",
                ),
                Question(
                    questionId = "r3",
                    type = QuestionType.SHORT_ANSWER,
                    promptMarkdown = "写出**自由空间**中电磁波的波速公式，并代入 " +
                        "\$c = 3 \\times 10^8\\,\\mathrm{m/s}\$ 说明单位。注意这里的 \$c\$ 是真空光速，" +
                        "与介质中的相速度 \$v = \\frac{1}{\\sqrt{\\mu\\varepsilon}}\$ 不是一回事，" +
                        "介质中的速度一定小于真空光速，这个结论在考试里经常被用来排除干扰项。",
                    answerText = "\$c = \\frac{1}{\\sqrt{\\mu_0 \\varepsilon_0}}\$",
                    explanationMarkdown = "- 真空磁导率 \$\\mu_0 = 4\\pi \\times 10^{-7}\\,\\mathrm{H/m}\$\n" +
                        "- 真空介电常数 \$\\varepsilon_0 \\approx 8.854 \\times 10^{-12}\\,\\mathrm{F/m}\$\n" +
                        "- 相乘开方取倒数即得 \$c \\approx 3 \\times 10^8\\,\\mathrm{m/s}\$",
                ),
            ),
            importedAtMillis = depsNow(),
            sourceLabel = "screenshot",
        )
        runBlocking { deps.bankRepository.save(bank) }
        controller.refresh()

        shot("13-richtext-practice") {
            controller.startPractice(bank, questionCount = 3)
            AppContent(controller)
        }
        shot("14-richtext-feedback") {
            val first = controller.session?.current ?: error("没有会话")
            val wrong = first.orderedOptions().firstOrNull { it.optionId !in first.question.correctOptionIds }
            if (wrong != null) controller.toggleOption(wrong.optionId) else controller.updateTextAnswer("错的")
            controller.submit()
            AppContent(controller)
        }
        shot("15-richtext-block-math") {
            // 抽题顺序会打乱，按题号走到简答题再截图
            var guard = 0
            while (controller.session?.current?.question?.questionId != "r3" && guard++ < 8) {
                controller.next()
            }
            AppContent(controller)
        }
        shot("16-richtext-list") {
            controller.updateTextAnswer("c = 1 / sqrt(mu0 eps0)")
            controller.submit()
            AppContent(controller)
        }
    }


    /** 笔记：列表三种归属 + 编辑器（含 Markdown/公式预览）。 */
    @Test
    fun renderNotes() {
        if (System.getenv("KT_SHOTS") != "1") {
            println("跳过 ScreenshotTest：设置 KT_SHOTS=1 才会渲染")
            return
        }

        val storage = InMemoryTextStorage()
        val deps = AppDependencies(storage)
        val controller = AppController(deps, CoroutineScope(Dispatchers.Unconfined))

        val imported = runBlocking {
            (ImportBankUseCase(deps.bankRepository, clock = deps.clock)
                .import(SampleBank.json, ChoiceMapping.AUTO, "示例题库", "screenshot") as ImportOutcome.Imported).bank
        }
        controller.refresh()
        val firstQuestion = imported.questions.first()

        controller.openNewNote(NoteScope.BANK, imported.bankId)
        controller.saveNote(
            controller.noteSeed!!.copy(
                title = "这份题库的边界",
                bodyMarkdown = "只覆盖**第一章到第三章**，第四章的线路计算没有收进来。\n\n" +
                    "- 选择题占七成\n- 计算题偏少\n\n复习时配合真题使用。",
            ),
        )
        controller.openNewNote(NoteScope.QUESTION, imported.bankId, firstQuestion.questionId)
        controller.saveNote(
            controller.noteSeed!!.copy(
                bodyMarkdown = "**易错点**：先把单位统一成国际单位再代入。\n\n" +
                    "自感电动势 \$e = -L\\dfrac{\\Delta I}{\\Delta t}\$，负号只表示方向。",
            ),
        )
        controller.openNewNote(NoteScope.DRAFT)
        controller.saveNote(
            controller.noteSeed!!.copy(
                title = "周末计划",
                bodyMarkdown = "1. 复盘错题本里 30 天内的错题\n2. 把谐振频率那组公式默写一遍\n3. 做一套完整卷",
            ),
        )

        shot("17-notes-list") {
            controller.openNotes()
            AppContent(controller)
        }
        shot("18-note-editor") {
            controller.openNoteEditor(controller.notes.first { it.scope == NoteScope.DRAFT }.noteId)
            AppContent(controller)
        }
        shot("19-note-editor-formula") {
            controller.openNoteEditor(controller.notes.first { it.scope == NoteScope.QUESTION }.noteId)
            AppContent(controller)
        }
    }


    /** 工具箱：计算器（含记录）与单位换算（含精度提示、单位选择列表）。 */
    @Test
    fun renderToolbox() {
        if (System.getenv("KT_SHOTS") != "1") {
            println("跳过 ScreenshotTest：设置 KT_SHOTS=1 才会渲染")
            return
        }

        val deps = AppDependencies(InMemoryTextStorage())
        val controller = AppController(deps, CoroutineScope(Dispatchers.Unconfined))
        controller.openToolbox()

        shot("20-toolbox-calculator") {
            controller.toolbox.updateExpression("(3+4)×2^3-sqrt(2)")
            AppContent(controller)
        }
        // 记录在键盘下方，需要一张更高的图才能看清
        shot("21-toolbox-calculator-history", height = 1240) {
            controller.toolbox.clear()
            listOf("1/3", "sin(30)", "2^10", "1.5e3*2").forEach { expression ->
                controller.toolbox.updateExpression(expression)
                controller.toolbox.commit()
            }
            controller.toolbox.updateExpression("(3+4)×2^3-sqrt(2)")
            controller.toolbox.commit()
            AppContent(controller)
        }
        shot("22-toolbox-units") {
            controller.toolbox.selectTab(ToolboxTab.UNITS)
            controller.toolbox.selectDimension(Dimension.TEMPERATURE)
            controller.toolbox.updateInput("36.6")
            controller.toolbox.togglePicker(ConvPicker.TO)
            controller.toolbox.selectUnit(requireNotNull(Units.resolve("°F")))
            AppContent(controller)
        }
        shot("23-toolbox-unit-picker") {
            controller.toolbox.togglePicker(ConvPicker.FROM)
            AppContent(controller)
        }
        shot("24-toolbox-calculator-error") {
            controller.toolbox.togglePicker(ConvPicker.FROM)
            controller.toolbox.selectTab(ToolboxTab.CALCULATOR)
            controller.toolbox.updateExpression("1/(3-3)")
            controller.toolbox.commit()
            AppContent(controller)
        }
    }

    private fun fakeHistory(bank: QuestionBank): List<AttemptRecord> {
        val now = depsNow()
        val picks = bank.questions.take(9)
        val plan = listOf(
            Verdict.CORRECT to 0L,
            Verdict.INCORRECT to 3L,
            Verdict.CORRECT to 3L,
            Verdict.CORRECT to 4L,
            Verdict.INCORRECT to 4L,
            Verdict.PARTIALLY_CORRECT to 6L,
            Verdict.CORRECT to 6L,
            Verdict.CORRECT to 9L,
            Verdict.CORRECT to 12L,
        )
        return picks.mapIndexed { index, question ->
            val (verdict, daysAgo) = plan[index]
            val submitted = now - daysAgo * 86_400_000L - index * 3_600_000L
            AttemptRecord(
                attemptId = "shot-attempt-$index",
                sessionId = "shot-session-${index / 3}",
                bankId = bank.bankId,
                questionId = question.questionId,
                questionType = question.type,
                mode = PracticeMode.NATIVE,
                displayOptionIds = question.options.map { it.optionId },
                selectedOptionIds = if (verdict == Verdict.CORRECT) {
                    question.correctOptionIds
                } else {
                    setOfNotNull(question.options.firstOrNull()?.optionId)
                },
                textAnswer = if (question.type == QuestionType.SHORT_ANSWER) "（示例作答）" else null,
                verdict = verdict,
                strategyId = "exact-set",
                gradingSpec = "{}",
                answerRevision = question.contentRevision,
                questionFingerprint = question.contentFingerprint(),
                submittedAtMillis = submitted,
                elapsedMillis = 12_000L + index * 1500L,
                explanationSeen = false,
            )
        }
    }

    private fun depsNow(): Long = morefish.knowledge.trainer.application.port.WallClock.System.nowMillis()


    /**
     * 知识图谱：列表（含未安装题库 / 判题硬依赖暂停）、编辑器、练习页入口。
     *
     * 缺失引用只能靠"题库不在本机"造出来，所以这里直接写一份带快照的图谱，
     * 而不是走导入流程 —— 截图核对的是界面怎么表达缺失，不是导入逻辑。
     */
    @Test
    fun renderGraph() {
        if (System.getenv("KT_SHOTS") != "1") {
            println("跳过 ScreenshotTest：设置 KT_SHOTS=1 才会渲染")
            return
        }

        val storage = InMemoryTextStorage()
        val deps = AppDependencies(storage)
        val controller = AppController(deps, CoroutineScope(Dispatchers.Unconfined))

        val imported = runBlocking {
            (ImportBankUseCase(deps.bankRepository, clock = deps.clock)
                .import(SampleBank.json, ChoiceMapping.AUTO, "示例题库", "screenshot") as ImportOutcome.Imported).bank
        }
        controller.refresh()

        val q1 = imported.questions[0]
        val q2 = imported.questions[1]
        val q3 = imported.questions[2]
        val first = QuestionRef(imported.bankId, q1.questionId)
        val second = QuestionRef(imported.bankId, q2.questionId)
        val third = QuestionRef(imported.bankId, q3.questionId)
        val missing = QuestionRef("bank-thermal-handbook", "q7")
        val now = deps.clock.nowMillis()

        runBlocking {
            JsonGraphRepository(storage).save(
                KnowledgeGraph(
                    snapshots = listOf(
                        GraphSnapshot(first, imported.title, q1.promptMarkdown, 1, now),
                        GraphSnapshot(second, imported.title, q2.promptMarkdown, 1, now),
                        GraphSnapshot(third, imported.title, q3.promptMarkdown, 1, now),
                        GraphSnapshot(
                            missing,
                            "热力学公式手册（未安装）",
                            "理想气体等温过程的做功对照表 \$W = nRT\\ln\\dfrac{V_2}{V_1}\$",
                            3,
                            now,
                        ),
                        GraphSnapshot(
                            QuestionRef("bank-thermal-handbook", "q8"),
                            "热力学公式手册（未安装）",
                            "常见物质的比热容表：水 4.18、冰 2.09、铝 0.88 \$\\mathrm{kJ/(kg \\cdot K)}\$",
                            3,
                            now,
                        ),
                    ),
                    edges = listOf(
                        GraphEdge(
                            edgeId = "edge-shot-1",
                            source = first,
                            target = second,
                            relation = GraphRelation.EXPLAINS,
                            dependency = DependencyKind.NAVIGATION,
                            noteMarkdown = "这两题共用同一套**谐振**结论，右边那道推导更完整。",
                            createdAtMillis = now - 90_000,
                            updatedAtMillis = now - 30_000,
                        ),
                        GraphEdge(
                            edgeId = "edge-shot-2",
                            source = second,
                            target = missing,
                            relation = GraphRelation.PREREQUISITE,
                            dependency = DependencyKind.NAVIGATION,
                            noteMarkdown = "先看公式手册里的等温过程，再回来做这道。",
                            createdAtMillis = now - 80_000,
                            updatedAtMillis = now - 20_000,
                        ),
                        GraphEdge(
                            edgeId = "edge-shot-3",
                            source = third,
                            target = QuestionRef("bank-thermal-handbook", "q8"),
                            relation = GraphRelation.EXPLAINS,
                            dependency = DependencyKind.HARD,
                            noteMarkdown = "判题要对照手册里的比热表，表不在就不下结论。",
                            createdAtMillis = now - 70_000,
                            updatedAtMillis = now - 10_000,
                        ),
                    ),
                ),
            )
        }

        shot("25-graph-list", height = 1180) {
            controller.openGraph()
            AppContent(controller)
        }
        shot("26-graph-editor", height = 1180) {
            controller.openGraphEditor(edgeId = "edge-shot-3")
            AppContent(controller)
        }

        // 练习页的「关联」入口：给当前这道题挂两条关系后再回来看
        controller.startPractice(imported, questionCount = 1)
        val current = controller.session?.current?.question
        if (current != null) {
            val currentRef = QuestionRef(imported.bankId, current.questionId)
            runBlocking {
                val graph = JsonGraphRepository(storage).load()
                JsonGraphRepository(storage).save(
                    graph.withEdge(
                        GraphEdge(
                            edgeId = "edge-shot-4",
                            source = currentRef,
                            target = second,
                            relation = GraphRelation.RELATED,
                            createdAtMillis = now,
                            updatedAtMillis = now,
                        ),
                    ),
                )
            }
            controller.openGraph()
            controller.closeGraph()
        }
        shot("27-practice-with-graph-link") {
            AppContent(controller)
        }
    }

    /**
     * 扩展包页：等待题库（包先到）与生效中（题库已到）两种状态。
     *
     * 例包绑的是固定 UUID；为了让第二种状态可见，这里把清单里的 bankId 换成
     * 刚导入的示例题库 —— 测的是界面表达，不是绑定校验。
     */
    @Test
    fun renderExtensions() {
        if (System.getenv("KT_SHOTS") != "1") {
            println("跳过 ScreenshotTest：设置 KT_SHOTS=1 才会渲染")
            return
        }

        val storage = InMemoryTextStorage()
        val deps = AppDependencies(storage)
        val controller = AppController(deps, CoroutineScope(Dispatchers.Unconfined))
        val imported = runBlocking {
            (ImportBankUseCase(deps.bankRepository, clock = deps.clock)
                .import(SampleBank.json, ChoiceMapping.AUTO, "示例题库", "screenshot") as ImportOutcome.Imported).bank
        }
        controller.refresh()

        val example = extensionExampleBytes()
        controller.importDroppedFile("relations.qbext", example)
        shot("28-extensions-pending", height = 1180) { AppContent(controller) }

        controller.importDroppedFile("relations.qbext", rebind(example, imported.bankId, "示例题库"))
        controller.openExtensions()
        controller.toggleExtensionExpanded("physiology-crossref")
        shot("29-extensions-active", height = 1180) { AppContent(controller) }
    }

    private fun extensionExampleBytes(): ByteArray {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "tools/qbank/examples/relations.qbext")
            if (candidate.isFile) return candidate.readBytes()
            dir = dir.parentFile
        }
        error("找不到 examples/relations.qbext")
    }

    /**
     * 换绑并升版：清单按改写后的字节重新算哈希。
     *
     * 这一步必须重算 —— 否则截图里看到的是"哈希不符"的失败提示，而不是生效状态。
     */
    private fun rebind(bytes: ByteArray, bankId: String, title: String): ByteArray {
        val oldBank = "3f2b1d7a-9c4e-4a61-8b52-0d7e6c91a4f5"
        val entries = LinkedHashMap<String, ByteArray>()
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val data = zis.readBytes()
                // 有的关系显式写了 source.bankId，换绑时要一起换
                entries[entry.name] = data.decodeToString().replace(oldBank, bankId).encodeToByteArray()
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        val files = entries.filterKeys { it != "manifest.json" }
            .map { (path, data) -> """{"path":"$path","sha256":"${Sha256.hex(data)}"}""" }
            .joinToString(",")
        val manifest = """
            {"schemaVersion":"1.0.0","format":"knowledge-trainer-bank-extension",
             "packageId":"physiology-crossref","name":"生理学 · 跨库关系补丁","version":"1.1.0",
             "author":{"displayName":"Knowledge Trainer 示例"},"license":"CC-BY-SA-4.0",
             "binds":{"bankId":"$bankId","bankTitle":"$title"},"files":[$files]}
        """.trimIndent()
        entries["manifest.json"] = manifest.encodeToByteArray()

        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zos ->
            (listOf("manifest.json") + entries.keys.filter { it != "manifest.json" }).forEach { name ->
                zos.putNextEntry(java.util.zip.ZipEntry(name))
                zos.write(entries.getValue(name))
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /**
     * 多语言：设置页（中文/英文两种，英文只翻 2/6）与"缺译文看原文"的练习页。
     *
     * 用仓库里的综合样例包（真实 zh-Hans + en 双语），不是现造的假数据 ——
     * 要核对的正是"覆盖度写在界面上、没译文的题带「原文」徽章"这两件事。
     */
    @Test
    fun renderLocales() {
        if (System.getenv("KT_SHOTS") != "1") {
            println("跳过 ScreenshotTest：设置 KT_SHOTS=1 才会渲染")
            return
        }
        val packageFile = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .take(6)
            .map { File(it, "tools/qbank/examples/advanced.qbank") }
            .firstOrNull { it.isFile }
            ?: return println("跳过：找不到 examples/advanced.qbank")

        val storage = InMemoryTextStorage()
        val deps = AppDependencies(storage)
        val controller = AppController(deps, CoroutineScope(Dispatchers.Unconfined))
        val outcome = runBlocking {
            ImportBankUseCase(deps.bankRepository, media = deps.mediaStore, clock = deps.clock)
                .importQbank(packageFile.readBytes(), "综合样例", "screenshot")
        }
        val bank = (outcome as? ImportOutcome.Imported)?.bank ?: error("综合样例导入失败：$outcome")
        controller.refresh()

        controller.openSetup(bank.bankId)
        controller.selectSetupLocale("en")
        shot("30-locale-setup", height = 1180) { AppContent(controller) }

        controller.toggleSetupFallback()
        controller.selectSetupCount(null)
        controller.startPlannedPractice()
        // 走到第一道"本场选了英文、但这题没译文"的题，把「原文」徽章拍进去
        var guard = 0
        while (!controller.currentQuestionShowsOriginal && guard++ < 12) controller.next()
        shot("31-locale-fallback") { AppContent(controller) }
        println("练习语言=${controller.session?.locale} 原文标记=${controller.currentQuestionShowsOriginal}")
    }

    /**
     * AI 讲解：未配置时的设置页，以及作答后展开的讲解面板。
     *
     * 传输层换成假实现，返回一段带行内公式的固定讲解 ——
     * 要核对的是"未配置时界面说什么"和"生成之后状态/成本怎么写"，不是模型会不会讲课。
     */
    @Test
    fun renderAi() {
        if (System.getenv("KT_SHOTS") != "1") {
            println("跳过 ScreenshotTest：设置 KT_SHOTS=1 才会渲染")
            return
        }
        val storage = InMemoryTextStorage()
        val deps = AppDependencies(storage, aiHttpClient = ScreenshotAiClient())
        val controller = AppController(deps, CoroutineScope(Dispatchers.Unconfined))
        // 用固定的一道多选题，而不是随机抽题：截图要能稳定对上下面那段讲解
        val bank = QuestionBank(
            bankId = "bank-ai-shot",
            title = "协程基础",
            sourceLocale = "zh-Hans",
            questions = listOf(
                Question(
                    questionId = "q-coroutine",
                    type = QuestionType.MULTIPLE_CHOICE,
                    promptMarkdown = "下列哪些属于 Kotlin 协程的挂起函数调用规则？",
                    options = listOf(
                        QuestionOption("o1", "挂起函数只能在协程或其他挂起函数中调用"),
                        QuestionOption("o2", "挂起函数可以被取消"),
                        QuestionOption("o3", "挂起函数会阻塞当前线程"),
                    ),
                    correctOptionIds = setOf("o1", "o2"),
                ),
            ),
            importedAtMillis = 1_700_000_000_000,
            sourceLabel = "screenshot",
        )
        runBlocking { deps.bankRepository.save(bank) }
        controller.refresh()

        controller.openAi()
        shot("32-ai-unconfigured", height = 1320) { AppContent(controller) }

        controller.saveAiConfig(
            AiConfig(
                baseUrl = "https://api.deepseek.com/v1",
                model = "deepseek-chat",
                uploadConsent = true,
            ),
            "sk-screenshot-demo",
        )
        controller.startPractice(bank, questionCount = 1)
        controller.session?.current?.question?.correctOptionIds?.forEach { controller.toggleOption(it) }
        controller.submit()
        controller.generateAiExplanation()
        shot("33-ai-explanation", height = 1320) { AppContent(controller) }
        println("AI 记录数=${controller.aiRecords.size} 状态=${controller.aiCurrentExplanation?.state}")
    }

    private class ScreenshotAiClient : AiHttpClient {
        override suspend fun post(request: AiHttpRequest): AiHttpResponse = AiHttpResponse(
            status = 200,
            body = """
                {"model":"deepseek-chat",
                 "choices":[{"message":{"content":"**结论：挂起函数可以在协程或其他挂起函数里调用，但它不会阻塞线程。**\n\n1. `suspend` 只是编译期标记，调用点被编译成状态机，线程在等待期间被让出去。\n2. 所以「挂起函数会阻塞当前线程」是错的 —— 阻塞的是逻辑流程，不是线程。\n3. 挂起函数之间会传递取消：父协程取消，子调用一起结束，所以「可以被取消」成立。\n\n注意：挂起 ≠ 切线程。它只是把「等」交还给调度器，在哪个线程恢复由调度器决定。"}}],
                 "usage":{"prompt_tokens":860,"completion_tokens":210}}
            """.trimIndent(),
        )
    }

    /**
     * 批量队列：刚排好（一次请求都没发）与跑完（进度 100%）两个画面。
     *
     * 这一页要证明的只有一件事：**在花钱之前，用户能看清"这次是几次请求"。**
     */
    @Test
    fun renderBatch() {
        if (System.getenv("KT_SHOTS") != "1") {
            println("跳过 ScreenshotTest：设置 KT_SHOTS=1 才会渲染")
            return
        }
        val storage = InMemoryTextStorage()
        val deps = AppDependencies(storage, aiHttpClient = ScreenshotAiClient())
        val controller = AppController(deps, CoroutineScope(Dispatchers.Unconfined))
        val bank = QuestionBank(
            bankId = "bank-batch-shot",
            title = "协程与并发",
            sourceLocale = "zh-Hans",
            questions = listOf(
                "suspend 函数在等待时会让出线程",
                "结构化并发要求父作用域等待子协程结束",
                "withContext 会切换调度器",
                "launch 会返回一个 Job",
                "async 的异常在 await 时才抛出",
            ).mapIndexed { index, prompt ->
                Question(
                    questionId = "q-batch-$index",
                    type = QuestionType.SINGLE_CHOICE,
                    promptMarkdown = "第 ${index + 1} 题：$prompt",
                    options = listOf(
                        QuestionOption("o${index}a", "正确"),
                        QuestionOption("o${index}b", "错误"),
                    ),
                    correctOptionIds = setOf("o${index}a"),
                )
            },
            importedAtMillis = 1_700_000_000_000,
            sourceLabel = "screenshot",
        )
        runBlocking { deps.bankRepository.save(bank) }
        controller.refresh()
        controller.saveAiConfig(
            AiConfig(
                baseUrl = "https://api.deepseek.com/v1",
                model = "deepseek-chat",
                uploadConsent = true,
            ),
            "sk-screenshot-demo",
        )
        controller.openBatch()
        controller.createBatch(bank.bankId, "zh-Hans", 50)
        shot("34-batch-queued", height = 880) { AppContent(controller) }

        controller.startBatch()
        shot("35-batch-done", height = 880) { AppContent(controller) }
        val job = controller.aiBatchSelected
        println("批量任务状态=${job?.state} 成功=${job?.doneCount} 请求=${job?.callsUsed}")
    }

    /**
     * 题库更新补丁：预览页（含答案变更提示）与应用之后。
     *
     * 要证明的两件事：
     * 1. **「哪道题答案变了」排在「共几处改动」前面** —— 用户先看到的是会影响
     *    "我过去练的对不对"的那一条，而不是一个漂亮的统计数字；
     * 2. 应用之后回到首页，题库卡片上出现版本号，说明"这一份确实换了代"。
     */
    @Test
    fun renderPatch() {
        if (System.getenv("KT_SHOTS") != "1") {
            println("跳过 ScreenshotTest：设置 KT_SHOTS=1 才会渲染")
            return
        }
        val storage = InMemoryTextStorage()
        val deps = AppDependencies(storage)
        val controller = AppController(deps, CoroutineScope(Dispatchers.Unconfined))
        runBlocking {
            ImportBankUseCase(deps.bankRepository, clock = deps.clock)
                .importQbank(exampleFile("starter.qbank"), "示例题库", "screenshot")
            // q002 是答案会被改掉的那道题：练过两次，"你在其中练过 2 次"才有实际含义
            repeat(2) { index ->
                deps.attemptRepository.append(
                    AttemptRecord(
                        attemptId = "shot-patch-$index",
                        sessionId = "shot-patch",
                        bankId = "550e8400-e29b-41d4-a716-446655440000",
                        questionId = "q002",
                        questionType = QuestionType.SINGLE_CHOICE,
                        mode = PracticeMode.NATIVE,
                        displayOptionIds = listOf("o1", "o2", "o3"),
                        selectedOptionIds = setOf("o2"),
                        textAnswer = null,
                        verdict = Verdict.CORRECT,
                        strategyId = "exact-set",
                        gradingSpec = "exact-set",
                        answerRevision = 1,
                        questionFingerprint = "2cf5290769b52058",
                        submittedAtMillis = 1_800_000_000_000 + index * 1_000L,
                        elapsedMillis = 6_000,
                        explanationSeen = false,
                    ),
                )
            }
        }
        controller.refresh()
        controller.importDroppedFile("starter-v2.qbpatch", exampleFile("starter-v2.qbpatch"))
        shot("36-patch-preview", height = 1180) { AppContent(controller) }

        controller.applyPickedPatch()
        // 加高是为了把题库卡片一起拍进来 —— 要核对的"第 2 版"徽章在卡片右上角
        shot("37-patch-applied", height = 1500) { AppContent(controller) }
        println("修订日志=${controller.latestRevisions.values.map { "${it.revision}/${it.name}" }}")
    }

    /**
     * 历史复核三态：有待复核的首页、逐条确认页、表过态之后的历史。
     *
     * 这一块最需要看的就是"改判之后历史长什么样" —— 判定换了、但能看出是后来改的，
     * 这个信息只在图上成立，单元测试断言不出"读起来会不会误解"。
     */
    @Test
    fun renderReview() {
        if (System.getenv("KT_SHOTS") != "1") {
            println("跳过 ScreenshotTest：设置 KT_SHOTS=1 才会渲染")
            return
        }
        val storage = InMemoryTextStorage()
        val deps = AppDependencies(storage)
        val controller = AppController(deps, CoroutineScope(Dispatchers.Unconfined))
        // 历史时间落在过去两天：截图里"1 天前 / 2 天前"比一排"刚刚"更能看出是哪一次
        val base = deps.clock.nowMillis() - 2 * 86_400_000L
        runBlocking {
            ImportBankUseCase(deps.bankRepository, clock = deps.clock)
                .importQbank(exampleFile("starter.qbank"), "示例题库", "screenshot")
            // 两次"当时答对"的 q002（第 1 版正确集合是 o1+o2+o3），补丁加了 o5 之后都会变成漏选
            repeat(2) { index ->
                deps.attemptRepository.append(
                    AttemptRecord(
                        attemptId = "shot-review-$index",
                        sessionId = "shot-review",
                        bankId = "550e8400-e29b-41d4-a716-446655440000",
                        questionId = "q002",
                        questionType = QuestionType.MULTIPLE_CHOICE,
                        mode = PracticeMode.NATIVE,
                        displayOptionIds = listOf("o1", "o2", "o3", "o4"),
                        selectedOptionIds = setOf("o1", "o2", "o3"),
                        textAnswer = null,
                        verdict = Verdict.CORRECT,
                        strategyId = "exact-set",
                        gradingSpec = "exact-set",
                        answerRevision = 1,
                        questionFingerprint = "2cf5290769b52058",
                        submittedAtMillis = base + index * 86_400_000L,
                        elapsedMillis = 9_000,
                        explanationSeen = false,
                    ),
                )
            }
            // q001 也练过，但补丁只改了它的题面 → 不该出现在复核列表里
            deps.attemptRepository.append(
                AttemptRecord(
                    attemptId = "shot-review-q001",
                    sessionId = "shot-review",
                    bankId = "550e8400-e29b-41d4-a716-446655440000",
                    questionId = "q001",
                    questionType = QuestionType.SINGLE_CHOICE,
                    mode = PracticeMode.NATIVE,
                    displayOptionIds = listOf("o1", "o2", "o3"),
                    selectedOptionIds = setOf("o2"),
                    textAnswer = null,
                    verdict = Verdict.CORRECT,
                    strategyId = "exact-set",
                    gradingSpec = "exact-set",
                    answerRevision = 1,
                    questionFingerprint = "7a946af22e6b29e0",
                    submittedAtMillis = base + 3_600_000,
                    elapsedMillis = 4_000,
                    explanationSeen = false,
                ),
            )
        }
        controller.refresh()
        controller.importDroppedFile("starter-v2.qbpatch", exampleFile("starter-v2.qbpatch"))
        controller.applyPickedPatch()
        println("待复核=${controller.reviewPending}")
        shot("38-review-badge", height = 780) { AppContent(controller) }

        controller.openReview()
        println("候选=${controller.reviewPlan?.count} 不可复核=${controller.reviewPlan?.unreviewable}")
        shot("39-review-list", height = 880) { AppContent(controller) }

        controller.decideAllReviews(accept = true)
        // 表完态回到首页：要看的是"最近提交"里那两条判定换了、并且标着是复核改的
        controller.closeReview()
        println("改判后判定=${controller.history.map { it.verdict }} 待复核=${controller.reviewPending}")
        shot("40-review-decided", height = 1560) { AppContent(controller) }
    }

    /** 读仓库里的示例包：截图必须用真实产物，不是测试里现拼的理想数据。 */
    private fun exampleFile(name: String): ByteArray {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "tools/qbank/examples/$name")
            if (candidate.isFile) return candidate.readBytes()
            dir = dir.parentFile
        }
        error("找不到 examples/$name")
    }

    /**
     * 来源真实性：题面下的出处行 + 首页题库卡的作者与出处。
     *
     * 四张图对应四种状态，因为它们**必须**长得不一样：
     * 有出处（改编自某书 第几章）、未标注、AI 参与生成、以及首页那一行概览。
     * 把它们渲染成同一个样子，等于把"作者没说"和"作者说了"混为一谈。
     */
    @Test
    fun renderProvenance() {
        if (System.getenv("KT_SHOTS") != "1") {
            println("跳过 ScreenshotTest：设置 KT_SHOTS=1 才会渲染")
            return
        }
        val storage = InMemoryTextStorage()
        val deps = AppDependencies(storage)
        val controller = AppController(deps, CoroutineScope(Dispatchers.Unconfined))
        val starter = exampleFile("starter.qbank")
        val imported = runBlocking {
            val outcome = ImportBankUseCase(deps.bankRepository, clock = deps.clock)
                .importQbank(starter, "示例题库", "screenshot")
            val bank = (outcome as? ImportOutcome.Imported)?.bank ?: error("示例包导入失败：$outcome")
            bank
        }
        controller.refresh()
        println("来源=${imported.provenance.sources.size} 处 摘要已核对=${imported.provenance.digestVerified}")
        println("作者=${imported.provenance.authorName} 许可=${imported.provenance.license}")
        println("origin=" + imported.questions.joinToString(" ") { "${it.questionId}:${it.origin.wireName.ifBlank { "未标注" }}" })

        shot("41-provenance-home", height = 1240) { AppContent(controller) }

        // 每张图都从**新会话**开始，只往前走。会话里的抽题顺序是随机的：
        // 第一版是"接着上一张再 next()"，结果 q003 恰好排在 q002 前面，
        // 从 q002 再 next() 就直接跳到了总结页 —— 文件名写着"未标注"，拍出来却是成绩单。
        shotAt(controller, imported, "q002", "42-provenance-adapted")
        shotAt(controller, imported, "q003", "43-provenance-unspecified")

        // AI 参与生成：最该被看见的一种状态。把它标在 q003 上再拍一次。
        val marked = imported.copy(
            questions = imported.questions.map {
                if (it.questionId == "q003") {
                    it.copy(origin = ProvenanceOrigin.AI_ASSISTED, sourceId = "marx-textbook", sourceLocator = "第二章")
                } else {
                    it
                }
            },
        )
        runBlocking { deps.bankRepository.save(marked) }
        controller.refresh()
        shotAt(controller, marked, "q003", "44-provenance-ai")
    }

    /** 开一个新会话，走到指定题号再拍。走不到就抛异常 —— 静默出一张错图比崩掉更贵。 */
    private fun shotAt(
        controller: AppController,
        bank: morefish.knowledge.trainer.domain.model.QuestionBank,
        questionId: String,
        name: String,
    ) {
        controller.startPractice(bank, questionCount = bank.questions.size)
        val session = controller.session ?: error("$name：会话没有建起来")
        val index = session.items.indexOfFirst { it.question.questionId == questionId }
        check(index >= 0) { "$name：会话里没有 $questionId" }
        // 翻页必须在 shot 之外：content lambda 会被渲染二十多次，放进去等于连翻二十多次
        repeat(index - session.cursor) { controller.next() }
        check(controller.session?.current?.question?.questionId == questionId) {
            "$name：翻到的是 ${controller.session?.current?.question?.questionId}，不是 $questionId"
        }
        shot(name) { AppContent(controller) }
    }

    /**
     * 上一次恢复被打断：启动时说一句，备份页上留一张写清"现在生效的是哪一份"的卡片。
     *
     * 这里只拍卡片，不拍首页横幅 —— 横幅是一次性的，卡片才是能回头再看的那一份。
     * 横幅的文案由 [BackupFlowTest] 断言，不靠这张图。
     */
    @Test
    fun renderBackupRecovery() {
        if (System.getenv("KT_SHOTS") != "1") {
            println("跳过 ScreenshotTest：设置 KT_SHOTS=1 才会渲染")
            return
        }
        val root = Files.createTempDirectory("kt-shot-recovery").toFile()
        val previous = "kt-shot-recovery.pre-restore-1792000000000"
        val deps = AppDependencies(
            FileTextStorage(root),
            filePicker = ShotPicker(),
            mediaStore = FileMediaStore(root),
            vault = DirectoryVault(root, deviceLabel = "Windows 11 · 桌面端"),
            restoreRecovery = morefish.knowledge.trainer.application.port.RestoreRecovery(
                kind = morefish.knowledge.trainer.application.port.RestoreRecoveryKind.ROLLED_FORWARD,
                dataLocation = root.absolutePath,
                previousLocation = File(root.parentFile, previous).absolutePath,
                filesInPlace = 3,
            ),
        )
        val controller = AppController(deps, CoroutineScope(Dispatchers.Unconfined))
        runBlocking {
            ImportBankUseCase(deps.bankRepository, clock = deps.clock)
                .importQbank(exampleFile("starter.qbank"), "示例题库", "screenshot")
        }
        controller.refresh()
        controller.openBackup()
        // 横幅与卡片是同一件事的两次陈述，同框会显得啰嗦；这里拍的是卡片
        controller.dismissBanner()
        shot("53-backup-recovery", height = 1070) { AppContent(controller) }
        root.deleteRecursively()
    }

    /**
     * 引用自检**发现缺口**时的那一屏。
     *
     * 刻意造出三种缺口，因为"全部接上"的样子看不出这个面板有没有用：
     * 指向没装的题库（图谱）、指向已被删掉的题（图谱 + 笔记）、题目里写了但文件不在的图。
     * 用的都是真实落盘 + 真实 `DirectoryVault.listPaths()`，不是伪造的清单 ——
     * 图片那一项要是靠假路径，截图就失去意义。
     */
    @Test
    fun renderBackupAudit() {
        if (System.getenv("KT_SHOTS") != "1") {
            println("跳过 ScreenshotTest：设置 KT_SHOTS=1 才会渲染")
            return
        }
        val root = Files.createTempDirectory("kt-shot-audit").toFile()
        val deps = AppDependencies(
            FileTextStorage(root),
            filePicker = ShotPicker(),
            mediaStore = FileMediaStore(root),
            vault = DirectoryVault(root, deviceLabel = "Windows 11 · 桌面端"),
        )
        val controller = AppController(deps, CoroutineScope(Dispatchers.Unconfined))
        runBlocking {
            ImportBankUseCase(deps.bankRepository, clock = deps.clock)
                .importQbank(exampleFile("advanced.qbank"), "综合示例（含图）", "screenshot")
            val bank = deps.bankRepository.list().first()
            val first = bank.questions.first().questionId
            val second = bank.questions[1].questionId

            deps.graphRepository.save(
                KnowledgeGraph(
                    edges = listOf(
                        // 同一题库里的一道已被删掉的题
                        GraphEdge(
                            edgeId = "edge-audit-1",
                            source = QuestionRef(bank.bankId, first),
                            target = QuestionRef(bank.bankId, "q-deleted-004"),
                            relation = GraphRelation.EXPLAINS,
                            dependency = DependencyKind.NAVIGATION,
                            createdAtMillis = deps.clock.nowMillis() - 60_000,
                            updatedAtMillis = deps.clock.nowMillis() - 30_000,
                        ),
                        // 一个从没装过的题库
                        GraphEdge(
                            edgeId = "edge-audit-2",
                            source = QuestionRef(bank.bankId, second),
                            target = QuestionRef("bank-thermal-handbook", "q8"),
                            relation = GraphRelation.PREREQUISITE,
                            dependency = DependencyKind.NAVIGATION,
                            createdAtMillis = deps.clock.nowMillis() - 50_000,
                            updatedAtMillis = deps.clock.nowMillis() - 20_000,
                        ),
                    ),
                ),
            )
            deps.noteRepository.upsert(
                morefish.knowledge.trainer.domain.model.Note(
                    noteId = "n-audit-1",
                    scope = NoteScope.QUESTION,
                    bankId = bank.bankId,
                    questionId = "q-deleted-004",
                    title = "这道题的易错点",
                    bodyMarkdown = "等温过程那一支要单独记一遍。",
                    createdAtMillis = deps.clock.nowMillis() - 40_000,
                    updatedAtMillis = deps.clock.nowMillis() - 10_000,
                ),
            )
        }
        // 图片文件被删掉（换机器时漏带、或者手工清过目录）：题目里还写着它
        File(root, "media").deleteRecursively()

        controller.refresh()
        controller.openBackup()
        controller.dismissBanner()
        shot("54-backup-audit", height = 1120) { AppContent(controller) }
        root.deleteRecursively()
    }

    /**
     * 备份与迁移页的三张图：总览 / 刚导出 / 恢复预览（外加一张被拒绝的）。
     *
     * 用真实临时目录 + [DirectoryVault]，因为这一页的文案全都挂在真实数字上
     * （几个题库、几条历史、多大、多少文件）—— 假数据截出来的图没有核对价值。
     */
    @Test
    fun renderBackup() {
        if (System.getenv("KT_SHOTS") != "1") {
            println("跳过 ScreenshotTest：设置 KT_SHOTS=1 才会渲染")
            return
        }
        val root = Files.createTempDirectory("kt-shot-backup").toFile()
        val picker = ShotPicker()
        val deps = AppDependencies(
            FileTextStorage(root),
            filePicker = picker,
            mediaStore = FileMediaStore(root),
            vault = DirectoryVault(root, deviceLabel = "Windows 11 · 桌面端"),
        )
        val controller = AppController(deps, CoroutineScope(Dispatchers.Unconfined))
        runBlocking {
            ImportBankUseCase(deps.bankRepository, clock = deps.clock)
                .importQbank(exampleFile("starter.qbank"), "示例题库", "screenshot")
            deps.attemptRepository.append(
                AttemptRecord(
                    attemptId = "a-shot",
                    sessionId = "s-shot",
                    bankId = "550e8400-e29b-41d4-a716-446655440000",
                    questionId = "q002",
                    questionType = QuestionType.MULTIPLE_CHOICE,
                    mode = PracticeMode.NATIVE,
                    displayOptionIds = listOf("o1", "o2", "o3", "o4"),
                    selectedOptionIds = setOf("o1", "o2", "o3"),
                    textAnswer = null,
                    verdict = Verdict.CORRECT,
                    strategyId = "exact-set",
                    gradingSpec = "exact-set",
                    answerRevision = 1,
                    questionFingerprint = "2cf5290769b52058",
                    submittedAtMillis = deps.clock.nowMillis() - 86_400_000L,
                    elapsedMillis = 8_000,
                    explanationSeen = false,
                ),
            )
        }
        controller.refresh()
        controller.openBackup()
        shot("45-backup-overview", height = 884) { AppContent(controller) }

        // ---- 加密：开关打开、输两次口令、勾确认之后按钮才是可点的
        controller.toggleBackupEncrypt()
        controller.updateBackupExportPassphrase("备份口令-海豚毛线九月")
        controller.updateBackupExportConfirm("备份口令-海豚毛线九月")
        controller.updateBackupExportAck(true)
        shot("49-backup-encrypt-export", height = 1140) { AppContent(controller) }

        controller.exportBackup()
        val encryptedSaved = picker.saved ?: error("加密导出没有落盘")
        controller.intakeBackupBytes(encryptedSaved.name, encryptedSaved.readBytes())
        shot("50-backup-locked", height = 1590) { AppContent(controller) }

        controller.unlockBackup("记错的口令-海豚毛线九月")
        shot("51-backup-wrong-passphrase", height = 1614) { AppContent(controller) }

        controller.unlockBackup("备份口令-海豚毛线九月")
        shot("52-backup-unlocked", height = 1572) { AppContent(controller) }

        // ---- 不加密：同一页的另一半（关掉开关会顺带清掉口令）
        controller.cancelRestore()
        controller.dismissBanner()
        controller.toggleBackupEncrypt()
        controller.exportBackup()
        shot("46-backup-exported", height = 1082) { AppContent(controller) }

        val saved = picker.saved ?: error("导出没有落盘")
        val bytes = saved.readBytes()
        controller.intakeBackupBytes(saved.name, bytes)
        shot("47-backup-restore", height = 1286) { AppContent(controller) }

        // 截断最后 64 字节：EOCD 没了，读出来的就是"这不是一个完整备份"。
        controller.intakeBackupBytes("坏掉的备份.qbk", bytes.copyOf(bytes.size - 64))
        shot("48-backup-rejected", height = 1166) { AppContent(controller) }
        root.deleteRecursively()
    }

    /** 截图用的选择器：不做对话框，只把字节按建议的文件名写到临时目录。 */
    private class ShotPicker : FilePicker {
        override val isAvailable: Boolean = true
        override val unavailableReason: String? = null
        override val canSave: Boolean = true
        var saved: File? = null

        override suspend fun pickPackage(): PickedFile? = null

        override suspend fun saveFile(suggestedName: String, bytes: ByteArray): Boolean {
            val dir = Files.createTempDirectory("kt-shot-save").toFile()
            val target = File(dir, suggestedName)
            target.writeBytes(bytes)
            saved = target
            return true
        }
    }

    private fun shot(name: String, height: Int = 960, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(
            width = 1180,
            height = height,
            density = Density(1f),
            content = { KtTheme { content() } },
        )
        try {
            var nanos = 0L
            repeat(26) {
                scene.render(nanos)
                nanos += 33_000_000L
                Thread.sleep(12)
            }
            val image = scene.render(nanos)
            val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("PNG 编码失败：$name")
            File(outDir, "$name.png").writeBytes(data.bytes)
        } finally {
            scene.close()
        }
    }
}
