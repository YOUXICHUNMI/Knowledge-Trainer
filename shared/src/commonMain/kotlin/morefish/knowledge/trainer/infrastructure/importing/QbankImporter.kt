package morefish.knowledge.trainer.infrastructure.importing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import morefish.knowledge.trainer.domain.i18n.LocaleCoverage
import morefish.knowledge.trainer.domain.i18n.LocaleTag
import morefish.knowledge.trainer.domain.model.GradingSpec
import morefish.knowledge.trainer.domain.model.PackageSource
import morefish.knowledge.trainer.domain.model.ProvenanceOrigin
import morefish.knowledge.trainer.domain.model.Question
import morefish.knowledge.trainer.domain.model.QuestionOption
import morefish.knowledge.trainer.domain.model.QuestionText
import morefish.knowledge.trainer.domain.model.QuestionType
import morefish.knowledge.trainer.infrastructure.GradingWire
import morefish.knowledge.trainer.infrastructure.archive.DeflateCodec
import morefish.knowledge.trainer.infrastructure.archive.Sha256
import morefish.knowledge.trainer.infrastructure.archive.ZipFormatException
import morefish.knowledge.trainer.infrastructure.archive.ZipReader

/**
 * KT-QBF 1.0 严格读取器。
 *
 * 与 tools/qbank 的 Python 校验器是**同一套规则的两份实现**：Python 端面向作者/CI，
 * Kotlin 端面向应用内导入。两边都必须拒绝同一批坏包 —— 因为用户完全可能绕过工具直接导入。
 *
 * 这里只做"能不能安全地读出来并转成题"，不做"要不要提示作者改"这类 UX 决策。
 */
object QbankImporter {

    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    private const val MANIFEST = "manifest.json"
    private const val FORMAT_ID = "knowledge-trainer-question-bank"
    private const val MEDIA_INDEX = "media/index.ndjson"
    private val QID = Regex("^[a-z0-9][a-z0-9_.-]{0,63}$")
    private val ASSET_ID = Regex("^[a-z0-9][a-z0-9_.-]{0,63}$")
    /** 整包摘要用 sha256（64 位）。注意与补丁锚点的 FNV-1a（16 位）不是一个东西。 */
    private val HEX64 = Regex("^[0-9a-f]{64}$")
    private val PART = Regex("^questions/part-[0-9]{4}\\.ndjson$")
    private val LOCALE_FILE = Regex("^locales/([^/]+)/questions\\.ndjson$")
    private val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private const val MAX_ASSET_BYTES = 20 * 1024 * 1024
    private const val MAX_TOTAL_MEDIA_BYTES = 200L * 1024 * 1024

    private data class MediaIndexEntry(val path: String, val declaredBytes: Int, val mimeType: String)

    sealed interface Result {
        data class Success(val bank: ParsedBank) : Result
        data class Failure(val issues: List<Issue>) : Result
    }

    data class Issue(val code: String, val message: String)

    /** 从包内提取出的媒体字节。只提取被题目引用的资源，未引用的不进内存。 */
    data class MediaFile(val assetId: String, val mimeType: String, val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is MediaFile && other.assetId == assetId && other.bytes.contentEquals(bytes)

        override fun hashCode(): Int = assetId.hashCode() * 31 + bytes.size
    }

    /** 解析结果：题目已经转成领域模型，另附作者/语言/资源等展示用信息。 */
    data class ParsedBank(
        val questions: List<Question>,
        val title: String,
        val authorName: String,
        val sourceLocale: String,
        /**
         * 包内声明的题库身份。**这是题库的稳定 ID**，导入时必须沿用它。
         *
         * 早期版本导入时重新生成了 UUID，后果是同一个作者的第二版包会变成"另一个题库"，
         * 按 UUID 绑定题库的扩展包永远等不到自己的库。身份来自包声明，不来自本次导入。
         */
        val bankId: String,
        /** 包声明的题库修订号。补丁的 baseRevision 与它比对。 */
        val contentRevision: Int,
        val locales: List<LocaleCoverage>,
        val warnings: List<Issue>,
        val assetCount: Int,
        val schemaVersion: String,
        val media: List<MediaFile> = emptyList(),
        /** 作者声明的出处清单。题目按 [Question.sourceId] 引用其中一条。 */
        val sources: List<PackageSource> = emptyList(),
        /** 包内声明的整包内容摘要。空串 = 老包没声明。 */
        val digest: String = "",
        /** 摘要重算一致。false 时**不能**当成"已核对"，界面上要写"未提供"。 */
        val digestVerified: Boolean = false,
        val license: String = "",
    )

    fun isQbank(bytes: ByteArray): Boolean = ZipReader.isZip(bytes)

    fun parse(bytes: ByteArray, codec: DeflateCodec): Result {
        val entries = try {
            ZipReader.entries(bytes)
        } catch (e: ZipFormatException) {
            return Result.Failure(listOf(Issue("ZIP_INVALID", e.message ?: "ZIP 结构损坏")))
        }

        val issues = mutableListOf<Issue>()
        val warnings = mutableListOf<Issue>()

        fun fail(code: String, message: String) {
            issues += Issue(code, message)
        }

        val byName = entries.filterNot { it.isDirectory }.associateBy { it.name }
        fun readEntry(name: String): ByteArray = ZipReader.read(bytes, byName.getValue(name), codec)

        // ---------- manifest ----------
        val manifestEntry = byName[MANIFEST] ?: run {
            return Result.Failure(listOf(Issue("MANIFEST_MISSING", "包内缺少 manifest.json，这不是有效的 .qbank")))
        }
        val manifestText = runCatching { readText(manifestEntry.name, ::readEntry) }.getOrElse { e ->
            return Result.Failure(
                listOf(Issue("ZIP_INVALID", "无法解压 manifest.json：${e.message ?: "解压失败"}")),
            )
        }
        val manifest = runCatching { json.parseToJsonElement(manifestText).jsonObject }.getOrElse { e ->
            return Result.Failure(
                listOf(Issue("MANIFEST_INVALID", "manifest.json 不是合法 JSON 对象：${e.message ?: ""}")),
            )
        }

        val schemaVersion = manifest.str("schemaVersion") ?: ""
        if (!Regex("^1\\.0\\.[0-9]+$").matches(schemaVersion)) {
            return Result.Failure(
                listOf(
                    Issue(
                        "SCHEMA_VERSION_UNSUPPORTED",
                        "题库格式版本 $schemaVersion 不受支持（本版本只支持 1.0.x），请升级应用或用规范工具转换",
                    ),
                ),
            )
        }
        if (manifest.str("format") != FORMAT_ID) {
            return Result.Failure(listOf(Issue("MANIFEST_INVALID", "manifest.format 不是 $FORMAT_ID")))
        }
        // bankId 是 1.0 的可选字段：早期包没有它，仍然要能导入（导入时生成一个）。
        // 但一旦写了就必须是合法 UUID —— 拼错的 ID 会让后续每一份补丁都找不到这个题库，
        // 这种错应该在导入时就暴露，而不是等到半年后用户点了补丁才发现。
        val bankId = manifest.str("bankId")?.trim() ?: ""
        if (bankId.isNotEmpty() && !UUID.matches(bankId)) {
            fail("MANIFEST_INVALID", "manifest.bankId 必须是 UUID，实际是 $bankId")
        }
        val declaredRevision = manifest.int("contentRevision") ?: 1
        if (declaredRevision < 1) {
            fail("MANIFEST_INVALID", "manifest.contentRevision 必须是正整数")
        }
        val supportedLocales = manifest.array("supportedLocales")?.mapNotNull { it.asString() } ?: emptyList()
        val sourceLocale = manifest.str("sourceLocale") ?: ""
        if (sourceLocale !in supportedLocales) {
            fail("MANIFEST_INVALID", "sourceLocale $sourceLocale 不在 supportedLocales 中")
        }
        val badTag = supportedLocales.firstOrNull { LocaleTag.parse(it) == null }
        if (badTag != null) {
            fail("LOCALE_TAG_INVALID", "supportedLocales 里的 $badTag 不是合法语言标签")
        }
        val normalized = supportedLocales.mapNotNull { LocaleTag.parse(it)?.value }
        if (normalized.size != normalized.distinct().size) {
            fail("LOCALE_TAG_INVALID", "supportedLocales 里有归一化后重复的语言标签")
        }

        // ---------- 清单 ↔ 条目 双向核对 ----------
        val listed = LinkedHashMap<String, String>()
        manifest.array("files")?.forEach { el ->
            val obj = el as? JsonObject ?: return@forEach
            val path = obj.str("path") ?: return@forEach
            val hash = obj.str("sha256") ?: return@forEach
            if (listed.put(path, hash) != null) fail("MANIFEST_INVALID", "files 中重复列出 $path")
        }
        for ((path, expected) in listed) {
            if (path !in byName) {
                fail("FILE_MISSING", "清单声明了 $path，但包内没有该文件")
                continue
            }
            val actual = Sha256.hex(readEntry(path))
            if (actual != expected) {
                fail("HASH_MISMATCH", "$path 的内容摘要与清单不符，文件可能被篡改或损坏")
            }
        }
        for (name in byName.keys) {
            if (name != MANIFEST && name !in listed) {
                fail("FILE_NOT_LISTED", "包内有未列入清单的文件：$name")
            }
        }

        // ---------- 整包内容摘要 ----------
        // 逐文件哈希证明"包内自洽"，这里证明"整包还是作者发出来的那一个"：
        // 两份内容相同、只是打包方式不同的包，逐文件哈希可能一样也可能不一样，
        // 但摘要（只依赖路径 + 内容哈希）一定一样。
        val authorName = (manifest["author"] as? JsonObject)?.str("displayName") ?: "未知作者"
        val license = manifest.str("license")?.trim() ?: ""
        val declaredDigest = manifest.str("contentDigest")?.trim() ?: ""
        var digestVerified = false
        if (declaredDigest.isEmpty()) {
            warnings += Issue(
                "CONTENT_DIGEST_MISSING",
                "这份包没有 contentDigest。可以照常导入，但无法判断" +
                    "「别人给你的包和你手里的是不是同一份内容」；用规范工具重新打包会自动补上",
            )
        } else if (!HEX64.matches(declaredDigest)) {
            fail("MANIFEST_INVALID", "manifest.contentDigest 必须是 64 位小写十六进制")
        } else {
            val actual = PackageDigest.of(
                PackageDigest.Identity(
                    format = manifest.str("format") ?: "",
                    schemaVersion = schemaVersion,
                    bankId = bankId,
                    contentRevision = declaredRevision,
                    sourceLocale = sourceLocale,
                    supportedLocales = supportedLocales,
                    title = manifest.str("title") ?: "未命名题库",
                    authorName = authorName,
                    license = license,
                ),
                byName.filterKeys { it != MANIFEST }.mapValues { (name, _) -> readEntry(name) },
            )
            if (actual != declaredDigest) {
                fail(
                    "CONTENT_DIGEST_MISMATCH",
                    "整包内容摘要与清单声明不符（清单 ${declaredDigest.take(12)}…，重算 ${actual.take(12)}…）。" +
                        "内容被改过，或者改完没有用规范工具重新打包",
                )
            } else {
                digestVerified = true
            }
        }

        // ---------- 出处声明 ----------
        val sources = mutableListOf<PackageSource>()
        manifest.array("sources")?.forEachIndexed { idx, el ->
            val obj = el as? JsonObject
            val sourceId = obj?.str("sourceId")
            if (obj == null || sourceId == null) {
                fail("SOURCE_INVALID", "sources 第 ${idx + 1} 条缺少 sourceId")
                return@forEachIndexed
            }
            if (!QID.matches(sourceId)) {
                fail("SOURCE_INVALID", "sources 第 ${idx + 1} 条的 sourceId 非法：$sourceId")
                return@forEachIndexed
            }
            if (sources.any { it.sourceId == sourceId }) {
                fail("SOURCE_DUPLICATE", "sources 里 sourceId $sourceId 重复")
                return@forEachIndexed
            }
            val sourceTitle = obj.str("title")
            if (sourceTitle.isNullOrBlank()) {
                fail("SOURCE_INVALID", "sources 第 ${idx + 1} 条缺少 title（读者要靠它去核对）")
                return@forEachIndexed
            }
            sources += PackageSource(
                sourceId = sourceId,
                title = sourceTitle,
                kind = obj.str("kind")?.trim() ?: "",
                author = obj.str("author")?.trim() ?: "",
                edition = obj.str("edition")?.trim() ?: "",
                url = obj.str("url")?.trim() ?: "",
                license = obj.str("license")?.trim() ?: "",
                note = obj.str("note")?.trim() ?: "",
            )
        }
        if (issues.isNotEmpty()) return Result.Failure(issues)
        if (sources.isEmpty() && manifest.array("sources") != null) {
            warnings += Issue("SOURCES_EMPTY", "manifest.sources 是空数组 —— 等同于没有声明任何出处")
        }

        // ---------- 媒体索引（可选） ----------
        // 与 Python 校验器同一套规则：assetId 唯一、path 指回包内文件、声明的 sha256 与清单一致。
        val mediaIndex = LinkedHashMap<String, MediaIndexEntry>()
        if (MEDIA_INDEX in byName) {
            lines(readText(MEDIA_INDEX, ::readEntry)).forEachIndexed { idx, line ->
                val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
                val id = obj?.str("assetId")
                val path = obj?.str("path")
                if (obj == null || id == null || !ASSET_ID.matches(id)) {
                    fail("MEDIA_INDEX_INVALID", "$MEDIA_INDEX 第 ${idx + 1} 行 assetId 非法")
                    return@forEachIndexed
                }
                if (path == null || !path.startsWith("media/") || path == MEDIA_INDEX) {
                    fail("MEDIA_INDEX_INVALID", "$MEDIA_INDEX 第 ${idx + 1} 行 path 非法：$path")
                    return@forEachIndexed
                }
                if (mediaIndex.containsKey(id)) {
                    fail("MEDIA_INDEX_INVALID", "assetId $id 在媒体索引中重复")
                    return@forEachIndexed
                }
                val declaredSha = obj.str("sha256") ?: ""
                val declaredBytes = obj.int("bytes") ?: -1
                val mimeType = obj.str("mimeType") ?: "application/octet-stream"
                val manifestSha = listed[path]
                if (manifestSha == null) {
                    fail("MEDIA_INDEX_INVALID", "$MEDIA_INDEX 声明的 $path 不在 manifest.files 中")
                    return@forEachIndexed
                }
                if (manifestSha != declaredSha) {
                    fail("MEDIA_INDEX_INVALID", "$MEDIA_INDEX 中 $path 的 sha256 与清单不符")
                    return@forEachIndexed
                }
                mediaIndex[id] = MediaIndexEntry(path, declaredBytes, mimeType)
            }
        }

        // ---------- 结构层 ----------
        val parts = byName.keys.filter { PART.matches(it) }.sorted()
        if (parts.isEmpty()) {
            return Result.Failure(listOf(Issue("SCHEMA_INVALID", "缺少 questions/part-0001.ndjson")))
        }

        val structure = LinkedHashMap<String, JsonObject>()
        parts.forEach { part ->
            lines(readText(part, ::readEntry)).forEachIndexed { idx, line ->
                val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
                if (obj == null) {
                    fail("NDJSON_INVALID", "$part 第 ${idx + 1} 行不是合法 JSON 对象")
                    return@forEachIndexed
                }
                val qid = obj.str("questionId")
                if (qid == null || !QID.matches(qid)) {
                    fail("SCHEMA_INVALID", "$part 第 ${idx + 1} 行 questionId 非法：$qid")
                    return@forEachIndexed
                }
                if (structure.containsKey(qid)) {
                    fail("QID_DUPLICATE", "questionId $qid 在包内重复")
                    return@forEachIndexed
                }
                structure[qid] = obj
            }
        }

        // ---------- 文案层：每种语言一份，源语言必须完整 ----------
        // 语言目录名就是语言标签，先归一化再比较 —— zh-hans 与 zh-Hans 是同一种语言，
        // 但同一个包里出现这两种写法（归一化后撞车）属于作者笔误，必须拒绝而不是取其一。
        val localeDirs = LinkedHashMap<String, String>() // 归一化标签 -> 包内原始目录名
        byName.keys.mapNotNull { name -> LOCALE_FILE.find(name)?.groupValues?.get(1)?.let { it to name } }
            .forEach { (rawLocale, path) ->
                val tag = LocaleTag.parse(rawLocale)
                if (tag == null) {
                    fail(
                        "LOCALE_TAG_INVALID",
                        "语言目录 $path 的标签 $rawLocale 不合法（只支持 language[-Script][-REGION]）",
                    )
                    return@forEach
                }
                if (localeDirs.put(tag.value, rawLocale) != null) {
                    fail("LOCALE_TAG_INVALID", "语言标签 $rawLocale 归一化后重复：${tag.value}")
                }
            }

        val localization = LinkedHashMap<String, LinkedHashMap<String, JsonObject>>()
        localeDirs.forEach { (tag, rawLocale) ->
            val path = "locales/$rawLocale/questions.ndjson"
            val entries = LinkedHashMap<String, JsonObject>()
            lines(readText(path, ::readEntry)).forEachIndexed { idx, line ->
                val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
                if (obj == null) {
                    fail("NDJSON_INVALID", "$path 第 ${idx + 1} 行不是合法 JSON 对象")
                    return@forEachIndexed
                }
                val qid = obj.str("questionId")
                if (qid == null) {
                    fail("SCHEMA_INVALID", "$path 第 ${idx + 1} 行缺少 questionId")
                    return@forEachIndexed
                }
                if (qid !in structure) {
                    fail("LOCALE_UNKNOWN_QID", "$path 第 ${idx + 1} 行引用了不存在的题 $qid")
                    return@forEachIndexed
                }
                if (entries.put(qid, obj) != null) {
                    fail("LOCALE_DUPLICATE_QID", "$path 中 $qid 出现了不止一次（翻译错位）")
                }
            }
            localization[tag] = entries
        }

        val sourceTag = LocaleTag.parse(sourceLocale)?.value ?: sourceLocale
        val sourceEntries = localization[sourceTag] ?: run {
            return Result.Failure(listOf(Issue("LOCALE_MISSING", "缺少源语言文案 locales/$sourceLocale/questions.ndjson")))
        }
        val missing = structure.keys - sourceEntries.keys
        if (missing.isNotEmpty()) {
            fail(
                "LOCALE_INCOMPLETE",
                "源语言 $sourceLocale 未覆盖全部题目，缺 ${missing.size} 题：${missing.take(5).joinToString(", ")}",
            )
        }
        // 清单里声明了却没有目录的语言：警告而不是拒绝 —— 声明是作者的意图，文件缺失是打包遗漏，
        // 前者不影响已有文案的可用性。
        supportedLocales.filter { declared ->
            LocaleTag.parse(declared)?.value !in localization.keys
        }.forEach { declared ->
            warnings += Issue("LOCALE_FILE_MISSING", "清单声明了 $declared，但包里没有它的文案文件")
        }
        localization.keys.filter { it !in supportedLocales.map { raw -> LocaleTag.parse(raw)?.value } }
            .forEach { extra ->
                warnings += Issue("LOCALE_UNDECLARED", "包里有 $extra 的文案，但 manifest 的 supportedLocales 没写它")
            }

        // ---------- 逐题转换 ----------
        val questions = mutableListOf<Question>()
        structure.forEach { (qid, s) ->
            val loc = sourceEntries[qid] ?: return@forEach
            val type = QuestionType.fromWire(s.str("type") ?: "") ?: return@forEach
            // 先按题型校验每个非源语言的译文；译错（选项对不上、简答没答案）必须整包拒绝，
            // 否则会留下"看起来有英文、判题却用中文答案"的半成品
            val texts = LinkedHashMap<String, QuestionText>()
            localization.forEach { (tag, entries) ->
                if (tag == sourceTag) return@forEach
                val obj = entries[qid] ?: return@forEach
                textOf(qid, type, s, obj, tag, ::fail)?.let { texts[tag] = it }
            }
            val converted = convert(qid, s, loc, texts, ::fail)
            if (converted != null) questions += converted
        }

        // ---------- 媒体引用核对与字节提取 ----------
        // 只提取被题目引用的资源；引用了索引里不存在的 assetId 属于包内错误，fail-closed。
        val referencedIds = questions.flatMap { it.assetIds }.distinct()
        var totalMediaBytes = 0L
        val media = mutableListOf<MediaFile>()
        for (assetId in referencedIds) {
            val entry = mediaIndex[assetId] ?: run {
                fail("ASSET_UNKNOWN", "题目引用了媒体索引中不存在的 assetId：$assetId")
                continue
            }
            val bytes = readEntry(entry.path)
            if (bytes.size > MAX_ASSET_BYTES) {
                fail("MEDIA_TOO_LARGE", "媒体资源 $assetId 超过单资源上限（${MAX_ASSET_BYTES / 1024 / 1024} MiB）")
                continue
            }
            if (entry.declaredBytes >= 0 && bytes.size != entry.declaredBytes) {
                fail("MEDIA_INDEX_INVALID", "媒体资源 $assetId 的字节数与索引声明不符")
                continue
            }
            totalMediaBytes += bytes.size
            if (totalMediaBytes > MAX_TOTAL_MEDIA_BYTES) {
                fail("MEDIA_TOO_LARGE", "被引用媒体资源合计超过 ${MAX_TOTAL_MEDIA_BYTES / 1024 / 1024} MiB 上限")
                break
            }
            media += MediaFile(assetId, entry.mimeType, bytes)
        }

        if (issues.isNotEmpty()) return Result.Failure(issues)

        // ---------- 出处引用的三处一致性 ----------
        val knownSources = sources.mapTo(mutableSetOf()) { it.sourceId }
        questions.forEach { question ->
            val sourceId = question.sourceId ?: return@forEach
            if (sourceId !in knownSources) {
                fail(
                    "SOURCE_UNKNOWN",
                    "${question.questionId} 引用了 sourceId $sourceId，但 manifest.sources 里没有这一条" +
                        if (sources.isEmpty()) "（整份 manifest 都没有 sources）" else "",
                )
            } else if (question.sourceLocator == null) {
                warnings += Issue(
                    "SOURCE_LOCATOR_MISSING",
                    "${question.questionId} 标了来源但没写 sourceLocator（章节 / 页码 / 题号），读者没法核对",
                )
            }
        }
        // 声称"抄来的"却不说是从哪抄的 —— 这比不写更糟：它让人以为出处已经交代过了
        questions.filter {
            (it.origin == ProvenanceOrigin.ADAPTED || it.origin == ProvenanceOrigin.IMPORTED) &&
                it.sourceId == null
        }.take(5).forEach { question ->
            warnings += Issue(
                "ORIGIN_WITHOUT_SOURCE",
                "${question.questionId} 声称 origin=${question.origin.wireName}，但没写 sourceId",
            )
        }
        // 悬空引用是硬错误：读者点不到那本书，而且它说明作者删了 sources 却漏了题目
        if (issues.isNotEmpty()) return Result.Failure(issues)

        val title = manifest.str("title") ?: "未命名题库"
        val assetCount = (manifest.array("files")?.count { (it as? JsonObject)?.str("path")?.startsWith("media/") == true }) ?: 0

        // 覆盖度按"真正解析进题目的条数"算，而不是数文件行数：
        // 一行被拒绝（比如坏题）时，数行数会虚高，界面上就会写着"完整"却抽不到题。
        val locales = localization.map { (tag, entries) ->
            val covered = entries.keys.count { it in structure }
            if (tag != sourceTag && covered < structure.size) {
                warnings += Issue(
                    "LOCALE_PARTIAL",
                    "$tag 只翻译了 $covered/${structure.size} 题；练习时默认跳过没翻译的题",
                )
            }
            LocaleCoverage(tag, covered, structure.size)
        }

        val enhancements = byName.keys.count { it.startsWith("enhancements/") && it != "enhancements/index.ndjson" }
        if (enhancements > 0) {
            warnings += Issue(
                "ENHANCEMENT_IGNORED",
                "包内含 $enhancements 份增强资料（拼写/重组等），当前版本尚未使用，题目本身可正常练习",
            )
        }
        if (assetCount > 0 && media.isEmpty()) {
            warnings += Issue(
                "MEDIA_NOT_REFERENCED",
                "包内含 $assetCount 个媒体资源，但没有题目引用它们（图片不会显示）",
            )
        }

        return Result.Success(
            ParsedBank(
                questions = questions,
                title = title,
                authorName = authorName,
                sourceLocale = sourceLocale,
                locales = locales,
                warnings = warnings,
                assetCount = assetCount,
                schemaVersion = schemaVersion,
                bankId = bankId,
                contentRevision = declaredRevision,
                media = media,
                sources = sources,
                digest = declaredDigest,
                digestVerified = digestVerified,
                license = license,
            ),
        )
    }

    /**
     * 校验并转换一条译文。
     *
     * 译文缺选项、选项 ID 与结构层对不上、简答题没有该语言的答案 —— 全部按"翻译错位"整包拒绝。
     * 允许部分题没有译文（覆盖度会记下来），但不允许"有译文却残缺"，
     * 那种半成品会让判题悄悄用回另一种语言的答案。
     */
    private fun textOf(
        qid: String,
        type: QuestionType,
        structure: JsonObject,
        obj: JsonObject,
        locale: String,
        fail: (String, String) -> Unit,
    ): QuestionText? {
        val prompt = obj.str("promptMarkdown")
        if (prompt.isNullOrBlank()) {
            fail("LOCALE_ENTRY_INVALID", "$qid 的 $locale 译文缺少 promptMarkdown")
            return null
        }
        val revision = obj.int("contentRevision") ?: 1
        if (revision < 1) {
            fail("REVISION_INVALID", "$qid 的 $locale 译文 contentRevision 必须 >= 1")
            return null
        }
        if (type == QuestionType.SHORT_ANSWER) {
            val answer = obj.str("answerText")
            if (answer.isNullOrEmpty()) {
                fail(
                    "LOCALE_ANSWER_MISSING",
                    "$qid 是简答题，$locale 译文缺少 answerText：不能拿另一种语言的答案判题",
                )
                return null
            }
            return QuestionText(locale, prompt, emptyMap(), answer, obj.str("explanationMarkdown"), revision)
        }

        val optionIds = structure.array("optionIds")?.mapNotNull { it.asString() } ?: emptyList()
        val texts = LinkedHashMap<String, String>()
        obj.array("options")?.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val id = o.str("optionId") ?: return@forEach
            val text = o.str("contentMarkdown") ?: return@forEach
            texts[id] = text
        }
        if (texts.keys != optionIds.toSet()) {
            fail("LOCALE_OPTION_MISMATCH", "$qid 的 $locale 译文选项与结构层 optionIds 不一致（翻译错位）")
            return null
        }
        if (texts.values.any { it.isBlank() }) {
            fail("LOCALE_OPTION_BLANK", "$qid 的 $locale 译文里有空选项")
            return null
        }
        return QuestionText(locale, prompt, texts, null, obj.str("explanationMarkdown"), revision)
    }

    private fun convert(
        qid: String,
        s: JsonObject,
        loc: JsonObject,
        translations: Map<String, QuestionText>,
        fail: (String, String) -> Unit,
    ): Question? {
        val type = QuestionType.fromWire(s.str("type") ?: "")
        if (type == null) {
            fail("SCHEMA_INVALID", "$qid 的题型 ${s.str("type")} 无法识别")
            return null
        }
        val provenance = provenanceOf(qid, s, fail)
        val prompt = loc.str("promptMarkdown")
        if (prompt.isNullOrBlank()) {
            fail("SCHEMA_INVALID", "$qid 缺少 promptMarkdown")
            return null
        }

        val optionIds = s.array("optionIds")?.mapNotNull { it.asString() } ?: emptyList()
        val correct = s.array("correctOptionIds")?.mapNotNull { it.asString() }?.toSet() ?: emptySet()
        // 资源引用：结构层优先，历史包允许写在文案层（两处都按 assetId 校验）
        val assetIds = (s.array("assets") ?: loc.array("assets"))
            ?.mapNotNull { it.asString() }
            ?.distinct()
            ?: emptyList()

        if (type == QuestionType.SHORT_ANSWER) {
            val answer = loc.str("answerText")
            if (answer.isNullOrEmpty()) {
                fail("ANSWER_MISSING", "$qid 是简答题但缺少 answerText")
                return null
            }
            // 判题策略：默认死记硬背；结构层 grading.strategy == "keywords" 时按默知识点
            val gradingObj = s["grading"] as? JsonObject
            val grading = when (gradingObj?.str("strategy")) {
                "keywords" -> GradingWire.keywordsFrom(gradingObj) ?: GradingSpec.Keywords()
                else -> GradingSpec.StrictText
            }
            return Question(
                questionId = qid,
                type = type,
                promptMarkdown = prompt,
                answerText = answer,
                explanationMarkdown = loc.str("explanationMarkdown"),
                assetIds = assetIds,
                grading = grading,
                tags = s.array("tags")?.mapNotNull { it.asString() } ?: emptyList(),
                contentRevision = loc.int("contentRevision") ?: 1,
                translations = translations,
                origin = provenance.origin,
                sourceId = provenance.sourceId,
                sourceLocator = provenance.sourceLocator,
            )
        }

        val options = loc.array("options")
            ?.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val id = o.str("optionId") ?: return@mapNotNull null
                val text = o.str("contentMarkdown") ?: return@mapNotNull null
                id to QuestionOption(id, text)
            } ?: emptyList()

        if (options.map { it.first }.toSet() != optionIds.toSet()) {
            fail("OPTION_MISMATCH", "$qid 的文案选项与结构层 optionIds 不一致")
            return null
        }
        if (!correct.all { it in optionIds }) {
            fail("OPTION_SUBSET", "$qid 的正确答案不在 optionIds 中")
            return null
        }
        if (type == QuestionType.SINGLE_CHOICE && correct.size != 1) {
            fail("ANSWER_COUNT", "$qid 是单选题但正确答案有 ${correct.size} 个")
            return null
        }
        if (type == QuestionType.MULTIPLE_CHOICE && correct.size < 2) {
            fail("ANSWER_COUNT", "$qid 是多选题但正确答案只有 ${correct.size} 个")
            return null
        }
        if (type == QuestionType.TRUE_FALSE && (optionIds.size != 2 || correct.size != 1)) {
            fail("OPTION_COUNT", "$qid 是判断题，应为 2 个选项且恰好 1 个正确答案")
            return null
        }
        if (type == QuestionType.ORDERING) {
            // 重组题：correctOptionIds 的书写顺序即标准次序
            val correctOrder = s.array("correctOptionIds")?.mapNotNull { it.asString() } ?: emptyList()
            if (correctOrder.size < 2) {
                fail("ANSWER_COUNT", "$qid 是重组题但正确片段只有 ${correctOrder.size} 个")
                return null
            }
            val orderedFragments = optionIds.mapNotNull { id -> options.firstOrNull { it.first == id }?.second }
            val gradingObj = s["grading"] as? JsonObject
            val declaredStrategy = gradingObj?.str("strategy")
            if (declaredStrategy != null && declaredStrategy != "ordering") {
                fail("GRADING_MISMATCH", "$qid 的题型与判题策略不匹配")
                return null
            }
            return Question(
                questionId = qid,
                type = type,
                promptMarkdown = prompt,
                options = orderedFragments,
                correctOptionIds = correct,
                explanationMarkdown = loc.str("explanationMarkdown"),
                assetIds = assetIds,
                shuffleAllowed = false,
                grading = GradingSpec.Ordering(
                    canonical = correctOrder,
                    alternatives = gradingObj?.let { GradingWire.alternativesFrom(it) } ?: emptyList(),
                ),
                tags = s.array("tags")?.mapNotNull { it.asString() } ?: emptyList(),
                contentRevision = loc.int("contentRevision") ?: 1,
                translations = translations,
                origin = provenance.origin,
                sourceId = provenance.sourceId,
                sourceLocator = provenance.sourceLocator,
            )
        }

        val declared = GradingSpec.fromWire((s["grading"] as? JsonObject)?.str("strategy"))
        val expected = if (type == QuestionType.SHORT_ANSWER) GradingSpec.StrictText else GradingSpec.ExactSet
        if (declared != null && declared != expected) {
            fail("GRADING_MISMATCH", "$qid 的题型与判题策略不匹配")
            return null
        }

        // 结构层 optionIds 决定默认显示顺序；库里没有这张图也不影响阅读
        val ordered = optionIds.mapNotNull { id -> options.firstOrNull { it.first == id }?.second }
        return Question(
            questionId = qid,
            type = type,
            promptMarkdown = prompt,
            options = ordered,
            correctOptionIds = correct,
            explanationMarkdown = loc.str("explanationMarkdown"),
            assetIds = assetIds,
            shuffleAllowed = s.bool("shuffleAllowed") ?: true,
            grading = expected,
            tags = s.array("tags")?.mapNotNull { it.asString() } ?: emptyList(),
            contentRevision = loc.int("contentRevision") ?: 1,
            translations = translations,
            origin = provenance.origin,
            sourceId = provenance.sourceId,
            sourceLocator = provenance.sourceLocator,
        )
    }

    // ---- 工具 ----

    /** 一条题目上的出处三件套。 */
    private data class Provenance(
        val origin: ProvenanceOrigin,
        val sourceId: String?,
        val sourceLocator: String?,
    )

    /**
     * 读题目自己的出处声明。
     *
     * `origin` 拼错（`copied`、`ai`）一律拒绝，不"猜一个最接近的"——猜错等于替作者
     * 声明了一件他没说过的事，而这类错误事后无法从数据里看出来。
     */
    private fun provenanceOf(qid: String, s: JsonObject, fail: (String, String) -> Unit): Provenance {
        val raw = s.str("origin")?.trim() ?: ""
        val origin = ProvenanceOrigin.fromWire(raw)
        if (raw.isNotEmpty() && origin == ProvenanceOrigin.UNSPECIFIED) {
            fail(
                "ORIGIN_INVALID",
                "$qid 的 origin=$raw 不在约定取值内（authored / adapted / imported / ai-assisted）",
            )
        }
        val sourceId = s.str("sourceId")?.trim()?.takeIf { it.isNotEmpty() }
        if (sourceId != null && !QID.matches(sourceId)) {
            fail("SCHEMA_INVALID", "$qid 的 sourceId 非法：$sourceId")
        }
        return Provenance(
            origin = origin,
            sourceId = sourceId,
            sourceLocator = s.str("sourceLocator")?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun readText(name: String, read: (String) -> ByteArray): String =
        read(name).decodeToString().removePrefix("\uFEFF")

    private fun lines(text: String): List<String> =
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.array(key: String): JsonArray? = (this[key] as? JsonArray)
    private fun kotlinx.serialization.json.JsonElement.asString(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content
}
