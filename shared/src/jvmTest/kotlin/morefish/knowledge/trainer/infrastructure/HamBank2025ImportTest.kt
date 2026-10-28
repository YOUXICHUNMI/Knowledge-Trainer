package morefish.knowledge.trainer.infrastructure

import kotlinx.coroutines.test.runTest
import morefish.knowledge.trainer.application.port.InMemoryTextStorage
import morefish.knowledge.trainer.application.usecase.ImportBankUseCase
import morefish.knowledge.trainer.application.usecase.ImportOutcome
import morefish.knowledge.trainer.infrastructure.archive.PureDeflateCodec
import morefish.knowledge.trainer.infrastructure.importing.QbankImporter
import morefish.knowledge.trainer.domain.model.QuestionType
import morefish.knowledge.trainer.infrastructure.storage.FileMediaStore
import morefish.knowledge.trainer.infrastructure.storage.JsonBankRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 导入真实规模的题库：CRAC 业余无线电 B 类操作证书考试题库（2025 版，1143 题 + 24 官方附图）。
 * 包文件在 banks/ham-b-2025.qbank（由 tools/qbank 打包器产出）；不存在时整体跳过。
 */
class HamBank2025ImportTest {

    private val bankFile: File by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "banks/ham-b-2025.qbank")
            if (candidate.isFile) return@lazy candidate
            dir = dir.parentFile
        }
        fail("找不到 banks/ham-b-2025.qbank；请先用 tools/qbank/build_qbank.py 打包 banks/ham-b-2025-authoring")
    }

    private fun bytes(): ByteArray = bankFile.readBytes()

    @Test
    fun parsesRealBankWithFigures() {
        val result = QbankImporter.parse(bytes(), PureDeflateCodec)
        val parsed = assertIs<QbankImporter.Result.Success>(result).bank
        assertEquals(1143, parsed.questions.size, "官方 B 类题库共 1143 题")
        // manifest 的 media/ 条目 = 24 张附图 + 1 个 media/index.ndjson
        assertEquals(25, parsed.assetCount, "官方附图 24 张 + 媒体索引")
        assertEquals(1, parsed.locales.size)
        // 24 道附图题按 assetId 引用包内资源，字节随解析结果一并提取
        val withAssets = parsed.questions.filter { it.assetIds.isNotEmpty() }
        assertEquals(24, withAssets.size, "官方附图题 24 道")
        assertEquals(24, parsed.media.size, "被引用的媒体应全部提取")
        parsed.media.forEach { asset ->
            assertTrue(
                asset.bytes.size > 3 && asset.bytes[0] == 0xFF.toByte() && asset.bytes[1] == 0xD8.toByte(),
                "${asset.assetId} 应是 JPEG（FF D8 开头）",
            )
        }
    }

    @Test
    fun singleAndMultipleChoiceMatchSource() {
        val result = QbankImporter.parse(bytes(), PureDeflateCodec)
        val parsed = assertIs<QbankImporter.Result.Success>(result).bank
        // 官方编号 MCn 前缀的 n = 正确选项个数；MC1-0014/0016 两条源数据笔误按实际答案数归多选
        assertEquals(947, parsed.questions.count { it.type == QuestionType.SINGLE_CHOICE })
        assertEquals(196, parsed.questions.count { it.type == QuestionType.MULTIPLE_CHOICE })
    }

    @Test
    fun importsIntoRepository() = runTest {
        // 媒体落盘走真实的文件实现，验证"导入成功 = 图片可读"这条链路
        val mediaRoot = java.nio.file.Files.createTempDirectory("kt-media")
        val mediaStore = FileMediaStore(mediaRoot.toFile())
        val importer = ImportBankUseCase(JsonBankRepository(InMemoryTextStorage()), media = mediaStore)
        val outcome = importer.importQbank(bytes(), "B类题库", "ham-b-2025")
        val imported = assertIs<ImportOutcome.Imported>(outcome)
        assertEquals(1143, imported.bank.questionCount)
        assertEquals(24, imported.bank.questions.count { it.assetIds.isNotEmpty() })
        val firstAssetId = imported.bank.questions.first { it.assetIds.isNotEmpty() }.assetIds.first()
        val stored = mediaStore.get(imported.bank.bankId, firstAssetId)
        assertTrue(stored != null && stored.size > 100, "图片字节应已写入媒体仓库")

        // 重复导入必须被内容指纹识别
        val again = importer.importQbank(bytes(), "B类题库", "ham-b-2025")
        assertIs<ImportOutcome.Duplicate>(again)
        mediaRoot.toFile().deleteRecursively()
    }
}
