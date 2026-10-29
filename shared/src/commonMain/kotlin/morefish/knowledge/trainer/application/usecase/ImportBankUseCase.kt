package morefish.knowledge.trainer.application.usecase

import morefish.knowledge.trainer.application.port.BankRepository
import morefish.knowledge.trainer.application.port.ChoiceMapping
import morefish.knowledge.trainer.application.port.MediaStore
import morefish.knowledge.trainer.application.port.WallClock
import morefish.knowledge.trainer.domain.i18n.LocaleCoverage
import morefish.knowledge.trainer.domain.i18n.LocaleTag
import morefish.knowledge.trainer.domain.model.BankProvenance
import morefish.knowledge.trainer.domain.model.Fingerprint
import morefish.knowledge.trainer.domain.model.Ids
import morefish.knowledge.trainer.domain.model.QuestionBank
import morefish.knowledge.trainer.infrastructure.archive.DeflateCodec
import morefish.knowledge.trainer.infrastructure.archive.PureDeflateCodec
import morefish.knowledge.trainer.application.port.ImportParseResult
import morefish.knowledge.trainer.application.port.ImportPreview
import morefish.knowledge.trainer.infrastructure.importing.JsonQuestionImporter
import morefish.knowledge.trainer.infrastructure.importing.QbankImporter

sealed interface ImportOutcome {
    data class Imported(val bank: QuestionBank, val preview: ImportPreview) : ImportOutcome

    /** 同一份材料再次导入：不静默创建副本，交给用户确认。 */
    data class Duplicate(val existing: QuestionBank, val preview: ImportPreview) : ImportOutcome

    /**
     * 包声明的 bankId 在本地已经存在，但内容不同。
     *
     * 这**不是**错误，是"作者发了新版本"的正常情况 —— 但也不能直接覆盖：
     * 直接覆盖会把用户已经练过的题悄悄换成新内容，历史里那些记录还停在旧答案上，
     * 而用户完全不知道发生过什么。正确路径是走 `.qbpatch`，那里会把每一条改动列出来。
     */
    data class IdentityConflict(val existing: QuestionBank, val preview: ImportPreview) : ImportOutcome

    data class NeedsMapping(val preview: ImportPreview) : ImportOutcome
    data class Rejected(val preview: ImportPreview) : ImportOutcome
    data class Failed(val message: String) : ImportOutcome
}

/**
 * 导入编排：解析 → 预览 → 校验 → 指纹查重 → 落库。
 * 真正的"暂存区 + 原子提交"在接入 SQLite 后补齐，当前先保证不静默覆盖。
 *
 * 两种输入走两条严格分开的路径：
 * - `.qbank`（ZIP）：走 KT-QBF 1.0 严格校验，任何一条不合规就整体拒绝；
 * - 文本 JSON / NDJSON：走宽松导入器，允许"部分题目有问题、其余照常导入"。
 * 绝不把文本解析器用在包上，也不把包校验器用在文本上。
 */
class ImportBankUseCase(
    private val banks: BankRepository,
    private val media: MediaStore? = null,
    private val importer: JsonQuestionImporter = JsonQuestionImporter(),
    private val clock: WallClock = WallClock.System,
    private val deflate: DeflateCodec = PureDeflateCodec,
) {

    fun preview(raw: String, mapping: ChoiceMapping): ImportParseResult = importer.parse(raw, mapping)

    /** 是不是一个 .qbank 包（按 ZIP magic 判断，不看扩展名）。 */
    fun looksLikeQbank(bytes: ByteArray): Boolean = QbankImporter.isQbank(bytes)

    /**
     * 导入 .qbank。包内的题型、答案、判题策略都已由作者声明并通过 Schema 校验，
     * 因此**不需要**用户再做题型映射 —— 这一点与文本导入不同。
     */
    suspend fun importQbank(
        bytes: ByteArray,
        fallbackTitle: String,
        sourceLabel: String,
    ): ImportOutcome {
        val parsed = when (val result = QbankImporter.parse(bytes, deflate)) {
            is QbankImporter.Result.Failure -> return ImportOutcome.Failed(
                result.issues.joinToString("；") { it.message },
            )
            is QbankImporter.Result.Success -> result.bank
        }

        val preview = ImportPreview(
            accepted = parsed.questions,
            rejected = emptyList(),
            issues = emptyList(),
            notes = buildList {
                add("KT-QBF ${parsed.schemaVersion} 包 · 作者 ${parsed.authorName} · 源语言 ${parsed.sourceLocale}")
                parsed.locales.filter { it.locale != LocaleTag.parse(parsed.sourceLocale)?.value }.forEach {
                    add("包含语言 ${it.locale}（${it.covered}/${it.total}）")
                }
                parsed.warnings.forEach { add(it.message) }
            },
            needsMapping = false,
            ambiguousCount = 0,
        )

        if (!preview.hasContent) return ImportOutcome.Rejected(preview)

        val fingerprint = Fingerprint.ofQuestions(preview.accepted.map { it.contentFingerprint() })
        // 身份优先：同一个 bankId 就是同一个题库，哪怕内容变了。
        // 先按 id 找，再看内容是否一致 —— 顺序反过来会把"新版本"误判成"另一个库"。
        banks.find(parsed.bankId)?.let { existing ->
            // 内容指纹换过一次算法（排序拼接 → 可增量求和，见 Fingerprint.CURRENT_VERSION）。
            // 老库目录里存的是旧算法的值，直接比会得出"内容不同"——同一个包被报成"不同版本"。
            // 所以先比存储值；只有它比当前算法旧时才按内容重算一遍（代价是一次整库解析，
            // 且只在新版本之后第一次碰到老库时发生，那个库被写过一次之后就是新值）。
            return if (sameContentAs(existing, fingerprint)) {
                ImportOutcome.Duplicate(existing, preview)
            } else {
                ImportOutcome.IdentityConflict(existing, preview)
            }
        }
        // 内容完全相同但 id 不同：同一个库被改过 bankId 又发了一遍，仍然按重复处理
        banks.findByFingerprint(fingerprint)?.let { existing ->
            return ImportOutcome.Duplicate(existing, preview)
        }

        // 身份来自包声明，不来自本次导入：扩展包与更新补丁都按这个 UUID 找目标题库。
        // 1.0 之前的包没声明 bankId（字段是后来加的），这类包仍然要能导入 —— 生成一个，
        // 但代价是它拿不到"按身份更新"的待遇，只能靠内容指纹被补丁定位。
        val bankId = parsed.bankId.ifBlank { Ids.uuid() }
        // 媒体先落盘、题库后入库：任何一步失败，题库里都不会出现"题目引用了取不到的图片"的半成品。
        if (media != null && parsed.media.isNotEmpty()) {
            try {
                parsed.media.forEach { media.put(bankId, it.assetId, it.bytes) }
            } catch (e: Throwable) {
                media.removeAll(bankId)
                return ImportOutcome.Failed("媒体资源写入失败：${e.message ?: e::class.simpleName}")
            }
        }
        val bank = QuestionBank(
            bankId = bankId,
            title = parsed.title.ifBlank { fallbackTitle.ifBlank { "未命名题库" } },
            sourceLocale = parsed.sourceLocale,
            questions = preview.accepted,
            importedAtMillis = clock.nowMillis(),
            sourceLabel = sourceLabel,
            contentRevision = parsed.contentRevision,
            fingerprint = fingerprint,
            // 覆盖度来自包里实际解析成功的条数：练习时据此决定"哪些题有这一语言"
            localeCoverage = parsed.locales,
            provenance = BankProvenance(
                sources = parsed.sources,
                digest = parsed.digest,
                digestVerified = parsed.digestVerified,
                authorName = parsed.authorName,
                license = parsed.license,
            ),
        )
        banks.save(bank)
        return ImportOutcome.Imported(bank, preview)
    }

    suspend fun import(
        raw: String,
        mapping: ChoiceMapping,
        title: String,
        sourceLabel: String,
    ): ImportOutcome {
        val parsed = importer.parse(raw, mapping)
        val preview = when (parsed) {
            is ImportParseResult.Failed -> return ImportOutcome.Failed(parsed.message)
            is ImportParseResult.Ok -> parsed.preview
        }

        if (preview.needsMapping) return ImportOutcome.NeedsMapping(preview)
        if (!preview.hasContent) return ImportOutcome.Rejected(preview)

        val fingerprint = Fingerprint.ofQuestions(preview.accepted.map { it.contentFingerprint() })
        banks.findByFingerprint(fingerprint)?.let { existing ->
            return ImportOutcome.Duplicate(existing, preview)
        }

        // 文本导入没有语言信息：按界面语言声明为单一语言，而不是让它变成"没有语言的库"
        val singleLocale = "zh-Hans"
        val bank = QuestionBank(
            bankId = Ids.uuid(),
            title = title.ifBlank { "未命名题库" },
            sourceLocale = singleLocale,
            questions = preview.accepted,
            importedAtMillis = clock.nowMillis(),
            sourceLabel = sourceLabel,
            fingerprint = fingerprint,
            localeCoverage = listOf(LocaleCoverage(singleLocale, preview.accepted.size, preview.accepted.size)),
        )
        banks.save(bank)
        return ImportOutcome.Imported(bank, preview)
    }

    /**
     * 本地这个库的内容是不是就是 [incoming] 这份。
     *
     * 先比目录里存着的指纹（零成本）；只有它比当前算法旧、值又对不上时，才按**实际内容**
     * 用当前算法重算一遍 —— 老库的旧值不可能等于新算法的结果，不重算就只能报"内容不同"。
     */
    private fun sameContentAs(existing: QuestionBank, incoming: String): Boolean {
        if (existing.fingerprint.isNotBlank() && existing.fingerprint == incoming) return true
        if (existing.fingerprintVersion >= Fingerprint.CURRENT_VERSION) return false
        return Fingerprint.ofQuestions(existing.questions.map { it.contentFingerprint() }) == incoming
    }
}
