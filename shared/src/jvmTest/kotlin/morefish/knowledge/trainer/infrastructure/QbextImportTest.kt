package morefish.knowledge.trainer.infrastructure.importing

import morefish.knowledge.trainer.domain.extension.ExtensionState
import morefish.knowledge.trainer.domain.extension.stateIn
import morefish.knowledge.trainer.domain.graph.DependencyKind
import morefish.knowledge.trainer.domain.graph.GraphRelation
import morefish.knowledge.trainer.infrastructure.archive.PureDeflateCodec
import morefish.knowledge.trainer.infrastructure.archive.Sha256
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `.qbext` 读取器。
 *
 * 正向用 tools/qbank 真打出来的包（跨语言链路一起测）；
 * 反向用 `java.util.zip` 现场造包 —— 坏包不该只存在于 Python 测试里，
 * 因为用户完全可能用手工工具造一个，Kotlin 这边必须同样拒绝。
 */
class QbextImportTest {

    private val examples: File by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "tools/qbank/examples")
            if (candidate.isDirectory) return@lazy candidate
            dir = dir.parentFile
        }
        fail("找不到 tools/qbank/examples，请先运行 python tools/qbank/qbext.py build")
    }

    private val bankA = "3f2b1d7a-9c4e-4a61-8b52-0d7e6c91a4f5"
    private val bankB = "7c1e5a92-4b3d-4f80-9a6e-2d5b8c1f0e37"

    private fun parse(bytes: ByteArray) = QbextImporter.parse(bytes, PureDeflateCodec)

    private fun issuesOf(bytes: ByteArray): List<QbextImporter.Issue> {
        val result = parse(bytes)
        return assertIs<QbextImporter.Result.Failure>(result, "应当被拒绝，实际解析成功").issues
    }

    private fun assertRejected(bytes: ByteArray, code: String) {
        val issues = issuesOf(bytes)
        assertTrue(
            issues.any { it.code == code },
            "应报 $code，实际是 ${issues.map { it.code }}：${issues.map { it.message }}",
        )
    }

    // ---- 造包 ----

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun qbext(
        bankId: String = bankA,
        packageId: String = "physiology-crossref",
        version: String = "1.0.0",
        relations: String? = ONE_RELATION,
        annotations: String? = null,
        format: String = QbextImporter.FORMAT_ID,
        schemaVersion: String = "1.0.0",
        corruptHash: Boolean = false,
        unlisted: String? = null,
    ): ByteArray {
        // 先算清单，再加"未列入清单"的条目 —— 顺序反了会把故意多出来的文件也算进清单里
        val entries = LinkedHashMap<String, ByteArray>()
        relations?.let { entries["relations/edges.ndjson"] = it.encodeToByteArray() }
        annotations?.let { entries["annotations/annotations.ndjson"] = it.encodeToByteArray() }

        val files = entries.entries.joinToString(",") { (path, bytes) ->
            val hash = if (corruptHash && path.startsWith("relations")) "0".repeat(64) else Sha256.hex(bytes)
            """{"path":"$path","sha256":"$hash"}"""
        }
        val manifest = """
            {"schemaVersion":"$schemaVersion","format":"$format","packageId":"$packageId",
             "name":"示例扩展","version":"$version","author":{"displayName":"测试作者"},
             "binds":{"bankId":"$bankId","bankTitle":"生理学"},
             "files":[$files]}
        """.trimIndent()
        unlisted?.let { entries["relations/extra.ndjson"] = it.encodeToByteArray() }
        return zip(linkedMapOf("manifest.json" to manifest.encodeToByteArray()) + entries)
    }

    // ---- 正向 ----

    @Test
    fun parsesTheShippedExamplePackage() {
        val file = File(examples, "relations.qbext")
        if (!file.isFile) fail("缺少示例扩展包 ${file.absolutePath}")

        val success = assertIs<QbextImporter.Result.Success>(parse(file.readBytes()))
        val pack = success.pack
        assertEquals("physiology-crossref", pack.packageId)
        assertEquals("1.0.0", pack.version)
        assertEquals(bankA, pack.binding.bankId)
        assertEquals(4, pack.relations.size)
        assertEquals(2, pack.annotations.size)

        // source 省略 bankId 时默认就是被绑定的题库
        assertTrue(pack.relations.all { it.source.bankId == bankA })
        assertTrue(pack.relations.all { it.target.bankId == bankB })

        val hard = pack.relations.single { it.dependency == DependencyKind.HARD }
        assertEquals(GraphRelation.PREREQUISITE, hard.relation)
        assertEquals("q-cardiac-output", hard.source.questionId)

        // 注释：一条挂题、一条针对整库
        assertTrue(pack.annotations.any { it.questionId == "q-cardiac-output" })
        assertTrue(pack.annotations.any { it.questionId == null })

        // 包本身不知道题库在不在；状态由调用方按已装题库判定
        assertEquals(ExtensionState.PENDING, pack.stateIn(emptySet()))
        assertEquals(ExtensionState.ACTIVE, pack.stateIn(setOf(bankA)))
    }

    @Test
    fun minimalPackageWithOneRelationIsEnough() {
        val success = assertIs<QbextImporter.Result.Success>(parse(qbext(relations = NAV_ONLY_RELATION)))
        assertEquals(1, success.pack.relations.size)
        assertEquals(0, success.pack.annotations.size)
        // 未声明 dependency 时按导航处理，不能默认成硬依赖
        assertEquals(DependencyKind.NAVIGATION, success.pack.relations.single().dependency)
    }

    @Test
    fun probeDistinguishesBankFromExtension() {
        val qbext = qbext()
        assertEquals(PackageFormat.QBEXT, PackageProbe.formatOf(qbext, PureDeflateCodec))

        val qbankFile = File(examples, "starter.qbank")
        if (qbankFile.isFile) {
            assertEquals(PackageFormat.QBANK, PackageProbe.formatOf(qbankFile.readBytes(), PureDeflateCodec))
        }
        // 不是 ZIP：既不是题库也不是扩展包，且能说清原因
        assertEquals(PackageFormat.UNKNOWN, PackageProbe.formatOf("{}".encodeToByteArray(), PureDeflateCodec))
        assertTrue(PackageProbe.describeUnknown("{}".encodeToByteArray(), PureDeflateCodec).contains("ZIP"))
    }

    // ---- 反向 ----

    @Test
    fun foreignSourceIsRejected() {
        // NDJSON 是一行一个对象：这里刻意不跨行，跨行会被当成"坏行"，测不到想测的规则
        val foreign = """{"relationId":"r1","source":{"bankId":"$bankB","questionId":"q1"},""" +
            """"target":{"bankId":"$bankB","questionId":"t1"},"relation":"related"}"""
        assertRejected(qbext(relations = foreign), "EXT_FOREIGN_SOURCE")
    }

    @Test
    fun hardDependencyOnSymmetricRelationIsRejected() {
        val symmetric = """{"relationId":"r1","source":{"questionId":"q1"},""" +
            """"target":{"bankId":"$bankB","questionId":"t1"},"relation":"related","dependency":"hard"}"""
        assertRejected(qbext(relations = symmetric), "EXT_RELATION_HARD_NEEDS_DIRECTION")
    }

    @Test
    fun selfReferenceIsRejected() {
        val self = """{"relationId":"r1","source":{"questionId":"q1"},""" +
            """"target":{"bankId":"$bankA","questionId":"q1"},"relation":"related"}"""
        assertRejected(qbext(relations = self), "EXT_RELATION_SELF")
    }

    @Test
    fun duplicateRelationIdIsRejected() {
        assertRejected(qbext(relations = "$ONE_RELATION\n$ONE_RELATION"), "EXT_RELATION_DUPLICATE")
    }

    @Test
    fun packageWithoutAnyContentIsRejected() {
        assertRejected(qbext(relations = null, annotations = null), "EXT_EMPTY")
    }

    @Test
    fun hashMismatchIsRejected() {
        assertRejected(qbext(corruptHash = true), "HASH_MISMATCH")
    }

    @Test
    fun unlistedEntryIsRejected() {
        assertRejected(qbext(unlisted = "{}"), "FILE_NOT_LISTED")
    }

    @Test
    fun badBindingIsRejected() {
        assertRejected(qbext(bankId = "not-a-uuid"), "BINDING_INVALID")
    }

    @Test
    fun bankPackageIsNotAcceptedAsExtension() {
        // 格式串写错（例如把 .qbank 改名成 .qbext）必须整包拒绝，不能"看起来差不多就收"
        assertRejected(qbext(format = PackageProbe.QBANK_FORMAT_ID), "MANIFEST_INVALID")
        assertRejected(qbext(schemaVersion = "2.0.0"), "SCHEMA_VERSION_UNSUPPORTED")
    }

    @Test
    fun malformedRelationLineIsRejectedWithLineNumber() {
        val broken = listOf(
            """{"relationId":"r1","source":{"questionId":"q1"},""" +
                """"target":{"bankId":"$bankB","questionId":"t1"},"relation":"related"}""",
            "",
            """{"relationId":"r2", not json""",
        ).joinToString("\n")
        val issues = issuesOf(qbext(relations = broken))
        val issue = issues.first { it.code == "NDJSON_INVALID" }
        // 空行占位，坏行是文件里的第 3 行 —— 报"过滤后第 2 条"会让人按行号找不到
        assertTrue(issue.message.contains("第 3 行"), "行号应按文件真实行号报告：${issue.message}")
    }

    @Test
    fun annotationWithEmptyBodyIsRejected() {
        val empty = """{"annotationId":"a1","title":"只有标题","bodyMarkdown":"   "}"""
        assertRejected(qbext(relations = null, annotations = empty), "SCHEMA_INVALID")
    }

    private companion object {
        /** 一条合法的最小关系：起点省略 bankId，默认取绑定的题库。 */
        const val ONE_RELATION =
            """{"relationId":"r1","source":{"questionId":"q1"},"target":{"bankId":"7c1e5a92-4b3d-4f80-9a6e-2d5b8c1f0e37","questionId":"t1"},"relation":"prerequisite","dependency":"hard"}"""

        /** 同一条关系但不写 dependency，用来验证默认值是"导航"而不是"硬依赖"。 */
        const val NAV_ONLY_RELATION =
            """{"relationId":"r1","source":{"questionId":"q1"},"target":{"bankId":"7c1e5a92-4b3d-4f80-9a6e-2d5b8c1f0e37","questionId":"t1"},"relation":"prerequisite"}"""
    }
}
