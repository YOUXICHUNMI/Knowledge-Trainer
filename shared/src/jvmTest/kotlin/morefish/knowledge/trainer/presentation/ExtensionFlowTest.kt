package morefish.knowledge.trainer.presentation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import morefish.knowledge.trainer.application.port.InMemoryTextStorage
import morefish.knowledge.trainer.application.port.WallClock
import morefish.knowledge.trainer.domain.extension.ExtensionState
import morefish.knowledge.trainer.domain.graph.QuestionRef
import morefish.knowledge.trainer.domain.model.Question
import morefish.knowledge.trainer.domain.model.QuestionBank
import morefish.knowledge.trainer.domain.model.QuestionOption
import morefish.knowledge.trainer.domain.model.QuestionType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 扩展包在控制器层的完整流程：装包（题库还没到）→ 题库到位自动生效 → 版本规则 → 移除。
 *
 * 这条链路正是 P6 验收里那句"跨库关系缺失可留待恢复"：
 * 扩展包先到、题库后到，关系照样生效，不需要重装。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExtensionFlowTest {

    private val bankA = "3f2b1d7a-9c4e-4a61-8b52-0d7e6c91a4f5"
    private val bankB = "7c1e5a92-4b3d-4f80-9a6e-2d5b8c1f0e37"

    private var now = 1_800_000_000_000L
    private val clock = WallClock { now }

    private fun controller(storage: InMemoryTextStorage, scheduler: TestCoroutineScheduler): AppController =
        AppController(
            deps = AppDependencies(storage, clock),
            scope = CoroutineScope(UnconfinedTestDispatcher(scheduler)),
        )

    private val example: ByteArray by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "tools/qbank/examples/relations.qbext")
            if (candidate.isFile) return@lazy candidate.readBytes()
            dir = dir.parentFile
        }
        fail("找不到 tools/qbank/examples/relations.qbext；请先运行 python tools/qbank/qbext.py build")
    }

    /** 把包里的版本号换掉，其余字节不动（清单只登记别的文件，改 manifest 不破坏哈希）。 */
    private fun withVersion(bytes: ByteArray, version: String): ByteArray {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                entries[entry.name] = zis.readBytes()
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        val manifest = entries.getValue("manifest.json").decodeToString()
            .replace("\"version\": \"1.0.0\"", "\"version\": \"$version\"")
        entries["manifest.json"] = manifest.encodeToByteArray()

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            (listOf("manifest.json") + entries.keys.filter { it != "manifest.json" }).forEach { name ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(entries.getValue(name))
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun sampleBank(bankId: String, title: String, questionIds: List<String>) = QuestionBank(
        bankId = bankId,
        title = title,
        sourceLocale = "zh-Hans",
        questions = questionIds.map { qid ->
            Question(
                questionId = qid,
                type = QuestionType.SINGLE_CHOICE,
                promptMarkdown = "题目 $qid 的题干",
                options = listOf(QuestionOption("o1", "甲"), QuestionOption("o2", "乙")),
                correctOptionIds = setOf("o1"),
            )
        },
        importedAtMillis = now,
        sourceLabel = "test",
    )

    private suspend fun seedBank(storage: InMemoryTextStorage, bank: QuestionBank) {
        AppDependencies(storage, clock).bankRepository.save(bank)
    }

    // ---- 用例 ----

    @Test
    fun packInstalledBeforeItsBankStaysPendingWithoutLeakingEdges() = runTest {
        val storage = InMemoryTextStorage()
        val c = controller(storage, testScheduler)
        c.importDroppedFile("relations.qbext", example)
        testScheduler.advanceUntilIdle()

        assertEquals(Screen.EXTENSIONS, c.screen, "装完扩展包应直接停在扩展包页")
        val pack = c.extensions.single()
        assertEquals(ExtensionState.PENDING, c.extensionStateOf(pack))
        assertEquals(1, c.pendingExtensions.size)
        assertEquals(0, c.graph.edges.size, "题库没到，关系不能提前出现在图谱里")
        assertEquals(4, c.extensionRelationCount, "关系已经装进来了，只是还没生效")

        // 横幅说明原因，而不是让人以为包坏了
        assertTrue(c.banner!!.text.contains("题库还没装"))
    }

    @Test
    fun installingTheBoundBankActivatesPendingPackAutomatically() = runTest {
        val storage = InMemoryTextStorage()
        val c = controller(storage, testScheduler)
        c.importDroppedFile("relations.qbext", example)
        testScheduler.advanceUntilIdle()
        assertEquals(ExtensionState.PENDING, c.extensionStateOf(c.extensions.single()))

        // 题库到位（模拟用户后导入同一个 UUID 的题库）
        seedBank(storage, sampleBank(bankA, "生理学", listOf("q-cardiac-output", "q-arterial-pressure")))
        c.refresh()
        testScheduler.advanceUntilIdle()

        assertEquals(ExtensionState.ACTIVE, c.extensionStateOf(c.extensions.single()))
        assertEquals(0, c.pendingExtensions.size)
        assertEquals(4, c.graph.edges.size)
        c.openGraph()
        testScheduler.advanceUntilIdle()
        assertTrue(c.graph.edges.all { it.edgeId.startsWith("ext:") })

        // 目标题库（循环系统）仍然没装：跨库端点如实报"题库未安装"
        val blocked = c.graphView.edges.count { it.blocked }
        assertEquals(1, blocked, "只有那条判题硬依赖应该被标暂停")
        assertEquals(4, c.graphView.edges.count { it.target.status.label == "题库未安装" })
    }

    @Test
    fun extensionEdgesCannotBeEditedOrDeletedInPlace() = runTest {
        val storage = InMemoryTextStorage()
        val c = controller(storage, testScheduler)
        c.importDroppedFile("relations.qbext", example)
        seedBank(storage, sampleBank(bankA, "生理学", listOf("q-cardiac-output")))
        c.refresh()
        testScheduler.advanceUntilIdle()

        val edgeId = c.graph.edges.first().edgeId
        c.openGraphEditor(edgeId)
        assertEquals(Screen.EXTENSIONS, c.screen, "扩展包关系不能在这里进编辑器")
        assertTrue(c.banner!!.isError)

        c.deleteGraphEdge(edgeId)
        assertTrue(c.banner!!.isError)
        assertEquals(4, c.graph.edges.size, "删不掉")
        assertEquals(1, c.extensions.size)
    }

    @Test
    fun sameVersionIsRejectedAsDuplicate() = runTest {
        val storage = InMemoryTextStorage()
        val c = controller(storage, testScheduler)
        c.importDroppedFile("relations.qbext", example)
        testScheduler.advanceUntilIdle()

        c.importDroppedFile("relations.qbext", example)
        testScheduler.advanceUntilIdle()

        assertEquals(1, c.extensions.size)
        assertTrue(c.banner!!.text.contains("已经装过"))
        assertTrue(c.banner!!.isError)
    }

    @Test
    fun olderVersionIsRejectedAndNewerOneReplaces() = runTest {
        val storage = InMemoryTextStorage()
        val c = controller(storage, testScheduler)
        c.importDroppedFile("relations.qbext", example)
        testScheduler.advanceUntilIdle()

        c.importDroppedFile("relations.qbext", withVersion(example, "0.9.0"))
        testScheduler.advanceUntilIdle()
        assertEquals("1.0.0", c.extensions.single().version, "降级必须被拒绝")
        assertTrue(c.banner!!.isError)

        c.importDroppedFile("relations.qbext", withVersion(example, "1.1.0"))
        testScheduler.advanceUntilIdle()
        assertEquals("1.1.0", c.extensions.single().version)
        assertEquals(1, c.extensions.size, "升级是替换，不是并存")
        assertEquals(4, c.extensions.single().relations.size)
    }

    @Test
    fun removingPackDropsItsEdgesAndKeepsHandBuiltOnes() = runTest {
        val storage = InMemoryTextStorage()
        val c = controller(storage, testScheduler)
        seedBank(storage, sampleBank(bankA, "生理学", listOf("q-cardiac-output", "q-arterial-pressure")))

        // 先手工建一条关系
        c.openGraphEditor(source = QuestionRef(bankA, "q-cardiac-output"))
        c.beginGraphPick(GraphPickSide.TARGET)
        c.pickGraphHit(
            morefish.knowledge.trainer.application.port.QuestionLocation(
                bankId = bankA,
                bankTitle = "生理学",
                questionId = "q-arterial-pressure",
                promptMarkdown = "题目 q-arterial-pressure 的题干",
                type = QuestionType.SINGLE_CHOICE,
                contentRevision = 1,
            ),
        )
        c.saveGraphEdge()
        testScheduler.advanceUntilIdle()
        assertEquals(1, c.graph.edges.size)

        c.importDroppedFile("relations.qbext", example)
        testScheduler.advanceUntilIdle()
        assertEquals(5, c.graph.edges.size)

        c.removeExtension("physiology-crossref")
        testScheduler.advanceUntilIdle()

        assertTrue(c.extensions.isEmpty())
        assertEquals(1, c.graph.edges.size, "只留手建的那条")
        assertTrue(c.graph.edges.single().edgeId.startsWith("edge-"))
        // 手建的那条仍在磁盘上，删包没动图谱文件
        assertEquals(1, AppDependencies(storage, clock).graphRepository.load().edges.size)
        assertNull(c.graph.snapshotOf(QuestionRef(bankB, "q-frank-starling")))
    }

    @Test
    fun aQbankDroppedWithTheWrongExtensionIsRejectedByFormat() = runTest {
        val storage = InMemoryTextStorage()
        val c = controller(storage, testScheduler)
        // 随便一个 ZIP，没有扩展包清单：必须说清"认不出来"，而不是静默装个空包
        c.importDroppedFile("fake.qbext", "PK\u0005\u0006".encodeToByteArray() + ByteArray(18))
        testScheduler.advanceUntilIdle()

        assertTrue(c.extensions.isEmpty())
        assertTrue(c.banner!!.isError)
        assertTrue(c.banner!!.text.contains("不是可识别的包"))
    }
}
