package morefish.knowledge.trainer.infrastructure.storage

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import morefish.knowledge.trainer.application.port.AddQuestionResult
import morefish.knowledge.trainer.application.port.AttemptRepository
import morefish.knowledge.trainer.application.port.BankRepository
import morefish.knowledge.trainer.application.port.GraphRepository
import morefish.knowledge.trainer.application.port.QuestionLocation
import morefish.knowledge.trainer.application.port.RandomAccessText
import morefish.knowledge.trainer.application.port.TextStorage
import morefish.knowledge.trainer.domain.graph.DependencyKind
import morefish.knowledge.trainer.domain.graph.GraphEdge
import morefish.knowledge.trainer.domain.graph.GraphRelation
import morefish.knowledge.trainer.domain.graph.GraphSnapshot
import morefish.knowledge.trainer.domain.graph.KnowledgeGraph
import morefish.knowledge.trainer.domain.graph.QuestionRef
import morefish.knowledge.trainer.domain.graph.validate
import morefish.knowledge.trainer.domain.i18n.LocaleCoverage
import morefish.knowledge.trainer.domain.i18n.LocaleTag
import morefish.knowledge.trainer.domain.model.AttemptRecord
import morefish.knowledge.trainer.domain.model.Fingerprint
import morefish.knowledge.trainer.domain.model.GradingSpec
import morefish.knowledge.trainer.domain.model.PracticeMode
import morefish.knowledge.trainer.domain.model.Question
import morefish.knowledge.trainer.domain.model.QuestionBank
import morefish.knowledge.trainer.domain.model.QuestionOption
import morefish.knowledge.trainer.domain.model.BankProvenance
import morefish.knowledge.trainer.domain.model.PackageSource
import morefish.knowledge.trainer.domain.model.ProvenanceOrigin
import morefish.knowledge.trainer.domain.model.QuestionText
import morefish.knowledge.trainer.domain.model.QuestionType
import morefish.knowledge.trainer.domain.model.Severity
import morefish.knowledge.trainer.domain.model.Verdict
import morefish.knowledge.trainer.infrastructure.GradingWire

internal val storageJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/**
 * 题目行的编码器：**不写默认值**。
 *
 * 每行都带上 `"shuffleAllowed":true,"assetIds":[],"tags":[]…` 会让十万题的库白白胖三倍，
 * 磁盘和解析时间都跟着涨。DTO 的默认值与领域默认值一一对应，省略掉不丢信息
 * （`grading` 是例外：凡不是 exact-set 的策略都会显式写出）。
 */
internal val questionJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
}

internal const val BANKS_FILE = "banks-v1.json"
internal const val ATTEMPTS_FILE = "attempts-v1.ndjson"

/**
 * 单个题库的题目文件路径。
 *
 * bankId 由本应用生成，理论上不会带路径分隔符；但仍然过一遍字符白名单 ——
 * 一份被手工改坏的目录文件不该有机会读写到题库目录之外的路径。
 */
internal fun questionFileOf(bankId: String): String {
    val safe = bankId.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
        .joinToString("")
        .ifBlank { "bank" }
    return "banks/$safe/questions-v1.ndjson"
}

/**
 * 题目文件的旁注索引：每行「题号 \t 起始字节 \t 长度」。
 *
 * 它和题目文件放在同一个目录里，是**派生**的 —— 删了不影响数据正确性，
 * 只影响下一次「取一道题」要不要把整份文件读一遍。没跟着一起生成的后果也一样：
 * 校验不过就重建。刻意不用 JSON：一行三列、十万行，解析器越薄越好。
 */
internal fun indexFileOf(bankId: String): String =
    questionFileOf(bankId).substringBeforeLast('/') + "/$INDEX_FILE_NAME"

/**
 * 索引的文件名。备份要按名字把它剔掉：它是**派生数据**，重建只要一次扫描，
 * 打进备份只会在每个包里多塞一成体积，还让"恢复出来的索引"多一层没必要的信任。
 */
internal const val INDEX_FILE_NAME = "offsets-v1.tsv"

internal fun searchIndexFileOf(bankId: String): String =
    questionFileOf(bankId).substringBeforeLast('/') + "/$SEARCH_FILE_NAME"

internal fun searchTailFileOf(bankId: String): String =
    questionFileOf(bankId).substringBeforeLast('/') + "/$SEARCH_TAIL_FILE_NAME"

/**
 * 倒排索引与它的尾巴，都是派生数据，备份一个都不带（见 `DirectoryVault.isIgnoredName`）。
 * 尾巴是"整库写完又追加进来的那几行"的临时记账 —— 主索引不重排，查询时补上它。
 */
internal const val SEARCH_FILE_NAME = "search-v1.tsv"
internal const val SEARCH_TAIL_FILE_NAME = "search-tail-v1.tsv"

/**
 * 题库仓储：目录 + 每库一个 NDJSON。
 *
 * 三个关键设计：
 * 1. **目录只放元数据**。首页、题库列表、查重都只看目录，题目一道都不用解析。
 * 2. **写库不碰别的库**。旧实现每次 save 都要把所有库读回内存再整体重写；
 *    十万题的库做一次这件事就是几百 MB 的瞬时占用。
 * 3. **题目按需解析**。`questions` 是一个 [DeferredQuestions]，`size` 直接来自目录，
 *    只有真正要读题面时才把该库的 NDJSON 解析进来。
 *
 * 提交顺序：先写题目文件（整文件原子替换），后写目录。目录是提交点，
 * 崩在中间最多留下一个"目录还指向旧内容"的状态，不会出现目录说有 10 万题、
 * 实际题目文件是空的。
 */
class JsonBankRepository(
    private val storage: TextStorage,
) : BankRepository {

    private val json = storageJson
    private val mutex = Mutex()
    private var warnings: List<String> = emptyList()

    override val loadWarnings: List<String> get() = warnings

    override suspend fun list(): List<QuestionBank> = mutex.withLock { readLocked() }

    override suspend fun find(bankId: String): QuestionBank? =
        list().firstOrNull { it.bankId == bankId }

    override suspend fun findByFingerprint(fingerprint: String): QuestionBank? {
        if (fingerprint.isBlank()) return null
        val metas = list()
        metas.firstOrNull { it.fingerprint == fingerprint }?.let { return it }
        // 没命中：可能是"升级前按首版算法写下的值"在找自己（老补丁的 baseFingerprint 就是那种值）。
        // 旧值只有重新算一遍才比得上 —— 这条只在未命中时走，不会拖慢首页与题库列表。
        return metas.firstOrNull { bank ->
            if (bank.fingerprintVersion >= Fingerprint.CURRENT_VERSION) return@firstOrNull false
            val parts = bank.questions.map { it.contentFingerprint() }
            Fingerprint.legacyOfQuestions(parts) == fingerprint || Fingerprint.ofQuestions(parts) == fingerprint
        }
    }

    override suspend fun save(bank: QuestionBank) {
        mutex.withLock {
            val catalog = readCatalogLocked()
            val written = writeQuestionsLocked(bank.bankId, bank.questions)
            writeCatalogLocked(catalog.filterNot { it.bankId == bank.bankId } + bank.toMeta(written))
        }
    }

    /**
     * 追加一道题：**只写一行 + 更新目录**，不重写整库。
     *
     * 与 [save] 的差别全在代价上。十万题的库上，`save(bank.copy(questions = +1))` 要
     * 先把整库读回来（解析 49.9MB、峰值 106MB）、再整份写回去；而这道题本身只有 512 字节。
     * 这条路径只在"库里已有、id 不重复"时生效，两条前置检查都不碰题目文件：
     *
     * - 库在不在：只看目录；
     * - id 重不重：查旁注索引（见 [QuestionIndex]），十万题是一张 4MB 表的哈希查找；
     *   没有索引能力（Web / 内存后端）或索引校验不过时才退回"读整份 + 子串预筛"。
     *
     * 剩下那一次整库解析只出现在"目录里的指纹还是首版算法"的老库上：新指纹要靠累加得到，
     * 而老值不能累加 —— 所以那种库第一次追加走一次整库重写（顺便把目录升到新版），之后就是增量。
     */
    override suspend fun addQuestion(bankId: String, question: Question): AddQuestionResult = mutex.withLock {
        val catalog = readCatalogLocked()
        val meta = catalog.firstOrNull { it.bankId == bankId } ?: return@withLock AddQuestionResult.BankMissing
        val file = questionFileOf(bankId)

        // 查重只看索引：十万题的库上，"这个 id 在不在"变成查一张 4MB 的旁注表，
        // 而不是把 50MB 题目文件读回来扫一遍。索引不可用（Web/内存后端、或校验不过）时才退回扫描。
        val index = indexForLocked(meta)
        val duplicate = index?.contains(question.questionId)
            ?: questionIdExistsLocked(file, question.questionId)
        if (duplicate) return@withLock AddQuestionResult.DuplicateId

        val rest = catalog.filterNot { it.bankId == bankId }
        val line = questionJson.encodeToString(QuestionDto.serializer(), question.toDto())

        if (meta.fingerprintVersion < Fingerprint.CURRENT_VERSION) {
            // 老库：必须看见全部题目才能把指纹换成新版。这一次整库重写是升级成本，不是日常成本。
            val questions = readQuestions(bankId) + question
            val written = writeQuestionsLocked(bankId, questions)
            writeCatalogLocked(rest + meta.copy(
                contentRevision = meta.contentRevision + 1,
                fingerprint = written.fingerprint,
                fingerprintVersion = written.fingerprintVersion,
                questionCount = written.count,
                typeCounts = written.typeCounts,
                locales = grownLocales(meta.locales, question, meta.sourceLocale, written.count),
            ))
            return@withLock AddQuestionResult.Added
        }

        // 先写题目行，再写索引行：崩在两步之间只会让索引**落后**（结构锁能发现并重建），
        // 而不是让索引**超前**（那会指向一个还没写进来的位置）。
        // 尾巴攒太长了：趁这次追加先把主索引重排一遍。
        // 必须赶在"把这一行写进文件"之前 —— 重排是照着文件扫的，
        // 文件已经多了一行、偏移表还没记，两边就对不上，重排出来的表会当场作废。
        val tailBytes = (storage as? RandomAccessText)?.byteLength(searchTailFileOf(bankId))
        if (index != null && tailBytes != null && tailBytes > SEARCH_TAIL_LIMIT_BYTES) {
            rebuildSearchIndexLocked(meta, index)
        }

        val length = lineByteLength(line)
        val offset = index?.endOffset
        // 这一行走索引里的哪一格，要在 add 之前取（add 之后 count 就变了）
        val row = index?.count ?: -1
        storage.appendLine(file, line)
        if (index != null && offset != null) {
            storage.appendLine(indexFileOf(bankId), "${question.questionId}\t$offset\t$length")
            index.add(question.questionId, length)
            // 倒排表不重排，把这一行的词记进尾巴；查询时会补上。
            appendSearchTailLocked(bankId, row, question.promptMarkdown)
        }
        val nextCount = meta.questionCount + 1
        writeCatalogLocked(rest + meta.copy(
            contentRevision = meta.contentRevision + 1,
            fingerprint = Fingerprint.plus(meta.fingerprint, question.contentFingerprint()),
            questionCount = nextCount,
            typeCounts = meta.typeCounts + (question.type.wireName to (meta.typeCounts[question.type.wireName] ?: 0) + 1),
            locales = grownLocales(meta.locales, question, meta.sourceLocale, nextCount),
        ))
        AddQuestionResult.Added
    }

    /**
     * 移除题库：**先改目录，再删题目文件**。
     *
     * 顺序有讲究：目录是提交点。反过来的话，进程在两步之间被杀，目录里还挂着一个
     * 题目文件已经没了的库 —— 首页写着"N 题"，点进去一道题都没有。现在的顺序
     * 最坏只留下一个孤儿文件（列表不读它），不会让列表说谎。
     *
     * 返回 false 表示目录里本来就没有这个库：不写文件、不留痕。
     */
    override suspend fun delete(bankId: String): Boolean = mutex.withLock {
        val catalog = readCatalogLocked()
        if (catalog.none { it.bankId == bankId }) return@withLock false
        writeCatalogLocked(catalog.filterNot { it.bankId == bankId })
        // 索引先删：题目文件删掉后父目录空了会被顺手收走，留着索引会让那次收尾落空。
        storage.delete(indexFileOf(bankId))
        storage.delete(searchIndexFileOf(bankId))
        storage.delete(searchTailFileOf(bankId))
        storage.delete(questionFileOf(bankId))
        forgetIndexLocked(bankId)
        forgetSearchLocked(bankId)
        true
    }

    /**
     * 精确定位。
     *
     * 有旁注索引时，这里读的是**那一道题的那一行**（几百字节），不是整份 50MB；
     * 索引只有在通过 [QuestionIndex] 的三道锁之后才会被信任，否则这次查询老实扫一遍，
     * 顺手把索引重建掉 —— 结果是"这次慢，下次不慢"，不是"快但可能错"。
     *
     * 没有随机读能力（Web / 内存后端）时，退化成"读整份 + 扫一遍"的老路径，行为不变。
     */
    override suspend fun locate(refs: List<QuestionRef>): Map<QuestionRef, QuestionLocation> {
        if (refs.isEmpty()) return emptyMap()
        return mutex.withLock {
            val metas = readCatalogLocked().associateBy { it.bankId }
            val found = mutableMapOf<QuestionRef, QuestionLocation>()
            val random = storage as? RandomAccessText

            refs.groupBy { it.bankId }.forEach bankLoop@{ (bankId, wanted) ->
                val meta = metas[bankId] ?: return@bankLoop
                val remaining = wanted.mapTo(mutableSetOf()) { it.questionId }

                val index = if (random != null) indexForLocked(meta) else null
                if (index != null && random != null) {
                    val hits = mutableMapOf<QuestionRef, QuestionLocation>()
                    var lied = false
                    // 遍历副本：命中要从 remaining 里摘掉
                    for (id in remaining.toList()) {
                        when (val outcome = readAtLocked(random, meta, index, id)) {
                            is IndexRead.Hit -> {
                                hits[QuestionRef(bankId, id)] = outcome.location
                                remaining -= id
                            }
                            // 索引过了结构锁与条数锁 = 它覆盖了文件的每一行；查不到就是真没有
                            IndexRead.Absent -> remaining -= id
                            IndexRead.Lie -> lied = true
                        }
                    }
                    if (!lied) {
                        found += hits
                    } else {
                        // 索引指错了行：这一库的结果全不作数，重建之后交给下面的扫描
                        rebuildIndexLocked(meta)
                    }
                }

                if (remaining.isNotEmpty()) {
                    scanLocateLocked(meta, remaining, found)
                }
            }
            found
        }
    }

    /** 按索引读一行，并把"读到的到底是不是这一道题"核对掉。 */
    private fun readAtLocked(
        random: RandomAccessText,
        meta: BankMetaDto,
        index: QuestionIndex,
        questionId: String,
    ): IndexRead {
        val entry = index.entry(questionId) ?: return IndexRead.Absent
        val raw = random.readRange(questionFileOf(meta.bankId), entry.offset, entry.length)
            ?: return IndexRead.Lie
        val dto = runCatching {
            questionJson.decodeFromString(QuestionDto.serializer(), raw.trim())
        }.getOrNull() ?: return IndexRead.Lie
        // 内容锁：偏移算错了会在这里露馅，而不是把别人的题当成这一道交出去
        if (dto.questionId != questionId) return IndexRead.Lie
        // 题号对得上、内容却不成形（缺选项之类）：这一道确实用不了，但不是索引的错 ——
        // 说成"撒谎"会导致每次定位都重建一遍，那才是真的惩罚
        val question = dto.toDomain() ?: return IndexRead.Absent
        return IndexRead.Hit(
            QuestionLocation(
                bankId = meta.bankId,
                bankTitle = meta.title,
                questionId = question.questionId,
                promptMarkdown = question.promptMarkdown,
                type = question.type,
                contentRevision = question.contentRevision,
            ),
        )
    }

    /** 老实路径：读整份、逐行扫。解码之前先看这一行是否真的包含要找的 id。 */
    private fun scanLocateLocked(
        meta: BankMetaDto,
        wanted: Set<String>,
        found: MutableMap<QuestionRef, QuestionLocation>,
    ) {
        val remaining = wanted.toMutableSet()
        val raw = storage.read(questionFileOf(meta.bankId)) ?: return
        raw.lineSequence().forEach lineLoop@{ line ->
            if (remaining.isEmpty()) return@lineLoop
            if (remaining.none { line.contains("\"$it\"") }) return@lineLoop
            val dto = runCatching {
                questionJson.decodeFromString(QuestionDto.serializer(), line)
            }.getOrNull() ?: return@lineLoop
            if (dto.questionId !in remaining) return@lineLoop
            val question = dto.toDomain() ?: return@lineLoop
            found[QuestionRef(meta.bankId, question.questionId)] = QuestionLocation(
                bankId = meta.bankId,
                bankTitle = meta.title,
                questionId = question.questionId,
                promptMarkdown = question.promptMarkdown,
                type = question.type,
                contentRevision = question.contentRevision,
            )
            remaining -= question.questionId
        }
    }

    /**
     * 搜题干。刻意不搜选项文字：四个选项里都可能出现同一个词，
     * 搜出来的每道题都"命中"，等于没搜。
     *
     * 走倒排表（见 [SearchIndex]）：中文按二元组切，取交集得到候选，再逐行读回来
     * 用完整子串复核。十万题的库上这是 1.26s → 几十毫秒的那一刀。
     *
     * 索引不可用时（Web / 内存后端、校验不过）退回全表扫描 —— 结果一样，只是慢。
     * 两条路径用的是同一个 [matchesQuery] 判据，所以"快的那条搜到了、慢的那条搜不到"
     * 这种事不会发生。
     */
    override suspend fun searchQuestions(keyword: String, limit: Int): List<QuestionLocation> {
        val query = keyword.trim()
        if (query.isEmpty() || limit <= 0) return emptyList()
        val segments = searchQuerySegments(query)
        if (segments.isEmpty()) return emptyList()
        val terms = searchTermsOf(query)

        return mutex.withLock {
            val hits = ArrayList<QuestionLocation>()
            for (meta in readCatalogLocked()) {
                if (hits.size >= limit) break
                searchInBankLocked(meta, terms, segments, limit - hits.size, hits)
            }
            hits
        }
    }

    // ---- 读 ----

    private fun readLocked(): List<QuestionBank> =
        readCatalogLocked().map { meta -> meta.toDomain { readQuestions(meta.bankId) } }

    private fun readCatalogLocked(): List<BankMetaDto> {
        val raw = storage.read(BANKS_FILE) ?: return emptyList()
        if (raw.isBlank()) return emptyList()

        val version = runCatching {
            (json.parseToJsonElement(raw) as? JsonObject)
                ?.get("schemaVersion")
                ?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() }
        }.getOrNull()

        if (version != null && version >= CATALOG_SCHEMA_VERSION) {
            val catalog = runCatching {
                json.decodeFromString(BankCatalogDto.serializer(), raw)
            }.getOrNull() ?: return emptyList()
            return catalog.banks
        }
        return migrateFromLegacyLocked(raw)
    }

    /** 旧布局（题目内嵌在 banks-v1.json 里）→ 新布局。读一次就迁完，旧文件被目录覆盖。 */
    private fun migrateFromLegacyLocked(raw: String): List<BankMetaDto> {
        val legacy = runCatching { json.decodeFromString(BankFileDto.serializer(), raw) }.getOrNull()
            ?: return emptyList()

        val metas = legacy.banks.map { dto ->
            val questions = dto.questions.mapNotNull { it.toDomain() }
            val written = writeQuestionsLocked(dto.bankId, questions)
            BankMetaDto(
                bankId = dto.bankId,
                title = dto.title,
                sourceLocale = dto.sourceLocale,
                importedAtMillis = dto.importedAtMillis,
                sourceLabel = dto.sourceLabel,
                contentRevision = dto.contentRevision,
                // 用实际内容的指纹，不抄旧文件里那个：旧布局下它同样可能是"copy 出来的旧值"，
                // 而迁移是唯一一次"看见了全部题目"的机会，没有理由继续传一个可能说谎的数。
                fingerprint = written.fingerprint,
                questionCount = written.count,
                typeCounts = written.typeCounts,
                // 旧布局没有出处信息（字段是 P8 第三块才加的），迁移时留空 —— 不伪造
            )
        }
        // 题目文件都已落地，才写目录 —— 目录就是这次迁移的提交点
        writeCatalogLocked(metas)
        warnings = listOf("题库存储已升级为「目录 + 每库独立文件」（共 ${metas.size} 个库），旧文件已转换")
        return metas
    }

    private fun readQuestions(bankId: String): List<Question> {
        val raw = storage.read(questionFileOf(bankId)) ?: return emptyList()
        if (raw.isBlank()) return emptyList()

        val out = ArrayList<Question>()
        var corrupt = 0
        raw.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            val dto = runCatching { questionJson.decodeFromString(QuestionDto.serializer(), line) }.getOrNull()
            if (dto == null) corrupt++ else dto.toDomain()?.let(out::add)
        }
        if (corrupt > 0) {
            warnings = warnings + "题库 $bankId 有 $corrupt 行无法解析，已跳过（原文件未修改）"
        }
        return out
    }

    // ---- 写 ----

    private fun writeCatalogLocked(banks: List<BankMetaDto>) {
        storage.write(BANKS_FILE, json.encodeToString(BankCatalogDto.serializer(), BankCatalogDto(banks = banks)))
    }

    /**
     * 这个 id 在题目文件里出现过吗。
     *
     * 分两段：先对整个文本做**一次**原生子串扫描（50MB 十几毫秒），没命中就收工；
     * 命中了才逐行去看，且只解码命中的那几行 —— 整库解析十万题是 800ms 级，
     * 而逐行 `contains` 也有一百毫秒级，都远贵于"扫一遍字节"。
     *
     * 子串预筛用带引号的 id，与 [locate] 是同一套判断：不带引号的话，`q1` 会命中 `q1x`。
     */
    private fun questionIdExistsLocked(file: String, questionId: String): Boolean {
        val raw = storage.read(file) ?: return false
        if (raw.isEmpty()) return false
        val needle = "\"$questionId\""
        if (!raw.contains(needle)) return false
        return raw.lineSequence().any { line ->
            line.contains(needle) && runCatching {
                questionJson.decodeFromString(QuestionDto.serializer(), line).questionId == questionId
            }.getOrDefault(false)
        }
    }

    // ---- 索引 ----

    /**
     * 解析好的索引缓存。只留两个：真正同时在用的通常只有一个大库，
     * 留两个是给"练习里跳到另一科的题"这种跨库操作一个余地，再多就只是占内存。
     *
     * 缓存不改变正确性：每次用之前都要过一遍结构锁（字节数）与条数锁，
     * 所以别处（另一个进程、备份恢复）动过文件，这里不会拿着旧表照用。
     */
    private val indexCache = LinkedHashMap<String, QuestionIndex>()

    private fun forgetIndexLocked(bankId: String) {
        indexCache.remove(bankId)
    }

    private fun rememberIndexLocked(bankId: String, index: QuestionIndex) {
        indexCache.remove(bankId)
        indexCache[bankId] = index
        while (indexCache.size > MAX_CACHED_INDEXES) {
            val eldest = indexCache.keys.first()
            indexCache.remove(eldest)
        }
    }

    /**
     * 取这个库的索引：内存里有一份就用它，文件里有一份就解析它，都没有（或都对不上）就重建。
     *
     * 三道锁在 [QuestionIndex] 里解释过。这里落实的是其中两道 ——
     * **结构锁**（最后一条的结束位置 = 题目文件字节长度）与**条数锁**（条数 = 目录题数）。
     * 第三道内容锁在真正读题时逐条核对，因为它要等读回来才看得见。
     */
    private fun indexForLocked(meta: BankMetaDto): QuestionIndex? {
        val random = storage as? RandomAccessText ?: return null
        val fileBytes = random.byteLength(questionFileOf(meta.bankId)) ?: return null

        indexCache[meta.bankId]?.let { cached ->
            if (cached.covers(fileBytes, meta.questionCount)) return cached
            indexCache.remove(meta.bankId)
        }

        val parsed = storage.read(indexFileOf(meta.bankId))?.let { QuestionIndex.parse(it) }
        if (parsed != null && parsed.covers(fileBytes, meta.questionCount)) {
            rememberIndexLocked(meta.bankId, parsed)
            return parsed
        }
        return rebuildIndexLocked(meta)
    }

    /**
     * 从题目文件重新扫一遍，重建索引并落盘。
     *
     * 只在三种情况下发生：第一次用新版本打开老库、索引文件丢了、索引与文件对不上。
     * 都是**一次性**成本（十万题约一次整份读），重建之后回到 O(1)。
     *
     * 题号用"行首的 questionId 字段"直接取，取不到才退回整行解码：
     * 我们是按 DTO 顺序写出去的，正常情况下第一个字段就是它；
     * 但外面手工改过的文件字段顺序可能不同，那时不能猜，老实解析。
     */
    private fun rebuildIndexLocked(meta: BankMetaDto): QuestionIndex? {
        val raw = storage.read(questionFileOf(meta.bankId)) ?: return null
        val index = QuestionIndex.empty()

        var start = 0
        while (start < raw.length) {
            val newline = raw.indexOf('\n', start)
            val end = if (newline < 0) raw.length else newline
            val segment = raw.substring(start, end)
            // 长度按**实际字节**算：汉字三字节，按字符算会让后面每一个偏移都错位
            val length = segment.encodeToByteArray().size + if (newline < 0) 0 else 1
            val id = if (segment.isBlank()) null else questionIdOfLine(segment)
            if (id != null) index.add(id, length) else index.skip(length)
            start = if (newline < 0) raw.length else newline + 1
        }

        // 比目录多出条目说明文件里真有目录不知道的题（崩在两次写之间）。这时把表写下去，
        // 下一次还会因为"比目录多"而重建：那不是一张可以信任的表。
        if (index.count <= meta.questionCount) {
            storage.write(indexFileOf(meta.bankId), index.render())
            rememberIndexLocked(meta.bankId, index)
        }
        return index
    }

    /** 从一行题目里取题号：优先走"行首字段"这条快路，取不到才解析整行。 */
    private fun questionIdOfLine(line: String): String? {
        // 行必须看起来是一个**写完的**对象：崩在写一半的残行（`{"questionId":"q-1","type":"sing`）
        // 也能从"行首字段"里抠出一个像样的 id，但它根本不是一道题 —— 索引里带上它，
        // 结果是每查一次都要重建一次。宁可跳过，让它留给"损坏行"那条告警。
        val marker = "{\"questionId\":\""
        if (line.endsWith("}") && line.startsWith(marker)) {
            val close = line.indexOf('"', marker.length)
            if (close > marker.length) {
                val raw = line.substring(marker.length, close)
                // 带转义的值不能直接当题号用（`\"` 之后才是值的结尾），交给解码器
                if (raw.none { it == '\\' }) return raw
            }
        }
        return runCatching {
            questionJson.decodeFromString(QuestionDto.serializer(), line).questionId
        }.getOrNull()
    }

    // ---- 倒排索引 ----

    /**
     * 解析好的倒排表缓存。**只留一份**：一份十万题的倒排表约 6MB（一百多万个 postings），
     * 留两份就是十二三 MB —— 手机上这已经是"能不能开着别的应用"的区别。
     * 跨库搜索时会有缓存抖动，但每次抖动只是重新解析一次（几百毫秒），不是重新扫描（一秒多）。
     */
    private var searchCache: Pair<String, SearchIndex>? = null

    private fun forgetSearchLocked(bankId: String) {
        if (searchCache?.first == bankId) searchCache = null
    }

    /**
     * 取这个库的倒排表：缓存 → 文件 → 重建。
     *
     * 与偏移表不同，这张表**没有增量路径**：追加一行时主索引不重排（那要改十几处位置），
     * 而是把这一行的词记进 [searchTailFileOf]，查询时补上。尾巴长到阈值时，
     * 下一次追加顺手重建一次 —— 把一次整份扫描摊到几百次追加上。
     */
    private fun searchIndexForLocked(meta: BankMetaDto): SearchIndex? {
        val random = storage as? RandomAccessText ?: return null
        val qIndex = indexForLocked(meta) ?: return null
        val fileBytes = random.byteLength(questionFileOf(meta.bankId)) ?: return null

        searchCache?.let { (cachedBank, cached) ->
            if (cachedBank == meta.bankId && usableLocked(meta, cached, fileBytes, qIndex)) return cached
            if (cachedBank == meta.bankId) searchCache = null
        }

        val parsed = storage.read(searchIndexFileOf(meta.bankId))?.let { SearchIndex.parse(it) }
        if (parsed != null && usableLocked(meta, parsed, fileBytes, qIndex)) {
            searchCache = meta.bankId to parsed
            return parsed
        }
        return rebuildSearchIndexLocked(meta, qIndex)
    }

    /**
     * 这张倒排表还能不能用来回答查询。
     *
     * 比偏移表的结构锁宽一点，因为倒排表**不随追加而重排**：文件在后头长长是正常的，
     * 那些新行由尾巴（[searchTailFileOf]）补。所以判据分三种情况：
     *
     * - 文件比索引生成时**短**：被改写过了，行号随时可能错位 —— 重建；
     * - 偏移表与索引一样长（没有新行）：要求字节数完全一致，否则重建；
     * - 偏移表比索引多 N 行：尾巴里得至少有 N 行记账，否则说明有行是绕过仓储写进来的，
     *   主索引根本不知道它 —— 重建。
     *
     * 最后一条是这个设计的关键：没有它，"另一个进程往文件里加了一行"会让那一行
     * 永远搜不到，而界面上看不出任何异常。
     */
    private fun usableLocked(
        meta: BankMetaDto,
        search: SearchIndex,
        fileBytes: Long,
        qIndex: QuestionIndex,
    ): Boolean {
        if (fileBytes < search.fileBytes) return false
        if (qIndex.count < search.slots) return false
        val missing = qIndex.count - search.slots
        if (missing == 0) return fileBytes == search.fileBytes
        return tailRowCountLocked(meta) >= missing
    }

    /** 尾巴里记了几行。 */
    private fun tailRowCountLocked(meta: BankMetaDto): Int {
        val raw = storage.read(searchTailFileOf(meta.bankId)) ?: return 0
        if (raw.isBlank()) return 0
        var count = 0
        for (line in raw.lineSequence()) if (line.isNotBlank()) count++
        return count
    }

    /**
     * 从题目文件重排倒排表。
     *
     * **行号必须与偏移表同源**：跳过哪些行、按什么顺序编号，两边用的是同一个
     * [questionIdOfLine]。不同源的后果不是慢，是把别人的题当成命中交出去。
     */
    private fun rebuildSearchIndexLocked(meta: BankMetaDto, qIndex: QuestionIndex): SearchIndex? {
        val raw = storage.read(questionFileOf(meta.bankId)) ?: return null
        val builder = SearchIndexBuilder()
        var row = 0
        for (line in raw.lineSequence()) {
            if (line.isBlank()) continue
            // 与偏移表同一把尺子：题号读不出来就不占槽
            questionIdOfLine(line) ?: continue
            val prompt = runCatching {
                questionJson.decodeFromString(QuestionDto.serializer(), line).promptMarkdown
            }.getOrNull().orEmpty()
            builder.add(row, prompt)
            row++
        }

        // 行号上界与偏移表对不上，说明这次扫描和那份表看到的不是同一个文件。
        // 内存里这份仍可用于本次查询，但不落盘、不进缓存 —— 写下去只会让下一次也错。
        val search = builder.build(qIndex.endOffset, builder.rowCount)
        if (builder.rowCount == qIndex.count) {
            storage.write(searchIndexFileOf(meta.bankId), builder.render(qIndex.endOffset, builder.rowCount))
            // 主索引已覆盖全部行，尾巴作废
            storage.delete(searchTailFileOf(meta.bankId))
            searchCache = meta.bankId to search
        }
        return search
    }

    /**
     * 把一行的词记进尾巴。
     *
     * 只记词、不记题干：一行约 60 字节而不是 500 —— 尾巴是"每次都整份读"的东西，
     * 它的体积直接就是搜索的固定开销。
     */
    private fun appendSearchTailLocked(bankId: String, row: Int, prompt: String) {
        val terms = StringBuilder()
        val seen = HashSet<String>()
        forEachSearchTerm(prompt) { term ->
            if (seen.add(term)) {
                if (terms.isNotEmpty()) terms.append(',')
                terms.append(term)
            }
        }
        storage.appendLine(searchTailFileOf(bankId), "$row\t$terms")
    }

    /**
     * 尾巴里命中查询的行号（升序）。
     *
     * 用 `,词,` 两侧加逗号的子串判断代替切分：尾巴每查一次都要整份读，
     * 切成一堆 List 只为了看某个词在不在，纯属白花钱。
     */
    private fun tailRowsLocked(meta: BankMetaDto, terms: List<String>): List<Int> {
        val raw = storage.read(searchTailFileOf(meta.bankId)) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        val rows = ArrayList<Int>()
        for (line in raw.lineSequence()) {
            if (line.isBlank()) continue
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val row = line.substring(0, tab).toIntOrNull() ?: continue
            val padded = "," + line.substring(tab + 1) + ","
            if (terms.all { padded.contains(",$it,") }) {
                // 尾巴里的行号天然比主索引大；写了两次的只认第一次
                if (rows.isEmpty() || rows.last() != row) rows += row
            }
        }
        return rows
    }

    /** 读回某一行的题目并核对内容；对不上就是"这一行不是答案"，不是"索引坏了"。 */
    private fun searchRowLocked(
        random: RandomAccessText,
        meta: BankMetaDto,
        qIndex: QuestionIndex,
        fileBytes: Long,
        row: Int,
        segments: List<String>,
    ): QuestionLocation? {
        val entry = qIndex.at(row) ?: return null
        // 就按索引里记的长度读：它和行号是同一次扫描算出来的，本来就是精确值。
        // 早先版本多读 2KB 兜底，实测多花的字节数占搜索总读取量的四分之三 ——
        // 而"兜底"能挡的那种情况（长度偏小）会在这里解码失败、跳过这一行，
        // 结果是少一条命中，不是给出错答案。
        val window = minOf(entry.length.toLong(), SEARCH_READ_MAX.toLong(), fileBytes - entry.offset).toInt()
        if (window <= 0) return null
        val raw = random.readRange(questionFileOf(meta.bankId), entry.offset, window) ?: return null
        val line = raw.substringBefore('\n')
        val dto = runCatching {
            questionJson.decodeFromString(QuestionDto.serializer(), line)
        }.getOrNull() ?: return null
        if (!matchesQuery(dto.promptMarkdown, segments)) return null
        val question = dto.toDomain() ?: return null
        return QuestionLocation(
            bankId = meta.bankId,
            bankTitle = meta.title,
            questionId = question.questionId,
            promptMarkdown = question.promptMarkdown,
            type = question.type,
            contentRevision = question.contentRevision,
        )
    }

    /** 老实路径：读整份、逐行扫。索引不可用时走这条，结果与索引路径一致。 */
    private fun scanSearchLocked(
        meta: BankMetaDto,
        segments: List<String>,
        room: Int,
        hits: MutableList<QuestionLocation>,
    ) {
        val raw = storage.read(questionFileOf(meta.bankId)) ?: return
        var added = 0
        for (line in raw.lineSequence()) {
            if (added >= room) break
            if (line.isBlank()) continue
            val dto = runCatching {
                questionJson.decodeFromString(QuestionDto.serializer(), line)
            }.getOrNull() ?: continue
            if (!matchesQuery(dto.promptMarkdown, segments)) continue
            val question = dto.toDomain() ?: continue
            hits += QuestionLocation(
                bankId = meta.bankId,
                bankTitle = meta.title,
                questionId = question.questionId,
                promptMarkdown = question.promptMarkdown,
                type = question.type,
                contentRevision = question.contentRevision,
            )
            added++
        }
    }

    /** 在一个库里搜索：有索引走索引，没有就走扫描。 */
    private fun searchInBankLocked(
        meta: BankMetaDto,
        terms: List<String>,
        segments: List<String>,
        room: Int,
        hits: MutableList<QuestionLocation>,
    ) {
        val random = storage as? RandomAccessText
        val qIndex = if (random != null) indexForLocked(meta) else null
        val search = if (random != null && qIndex != null) searchIndexForLocked(meta) else null
        // terms 为空 = 查询全是单字，没有可用来筛候选的二元组：老实扫一遍。
        // 这条不是"索引失效"，是"这个查询本身没有可索引的东西"。
        if (random == null || qIndex == null || search == null || terms.isEmpty()) {
            scanSearchLocked(meta, segments, room, hits)
            return
        }

        val fileBytes = random.byteLength(questionFileOf(meta.bankId)) ?: return

        var added = 0
        // 主索引的候选是升序的；尾巴的行号天然更大，接着往后走就是全序，不必排序
        for (row in search.candidates(terms)) {
            if (added >= room) return
            val location = searchRowLocked(random, meta, qIndex, fileBytes, row, segments) ?: continue
            hits += location
            added++
        }
        var lastRow = -1
        for (row in tailRowsLocked(meta, terms)) {
            if (added >= room) return
            if (row <= lastRow) continue
            lastRow = row
            val location = searchRowLocked(random, meta, qIndex, fileBytes, row, segments) ?: continue
            hits += location
            added++
        }
    }

    /**
     * 返回实际写入的条数、题型分布与**内容指纹**；这三个值随后进目录。
     *
     * 指纹必须在这里算，不能由调用方传：`QuestionBank.copy(questions = …)` 会
     * 原样保留旧的 `fingerprint`，于是"加了一道题，目录里还写着老内容的指纹"。
     * 后果不是界面显示错误 —— 而是 `ImportBankUseCase` 拿它判断"这个库是不是导入过"，
     * 从这一刻起就会答错（把改过的库当成原包）。
     *
     * 边序列化边累加（[Fingerprint.Accumulator]），不再先攒一个十万条指纹的列表再排序：
     * 那样既能省掉一次排序，也让"追加一道题"有办法只更新这个累加值（见 [addQuestion]）。
     */
    private fun writeQuestionsLocked(bankId: String, questions: List<Question>): WrittenQuestions {
        val builder = StringBuilder()
        val index = QuestionIndexBuilder()
        val search = SearchIndexBuilder()
        val counts = mutableMapOf<String, Int>()
        val fingerprint = Fingerprint.Accumulator()
        var written = 0
        questions.forEach { question ->
            val line = questionJson.encodeToString(QuestionDto.serializer(), question.toDto())
            index.append(question.questionId, lineByteLength(line))
            search.add(written, question.promptMarkdown)
            builder.append(line).append('\n')
            fingerprint.add(question.contentFingerprint())
            counts[question.type.wireName] = (counts[question.type.wireName] ?: 0) + 1
            written++
        }
        // 题目文件先落盘，索引后写：反过来的话，崩在中间会留下"索引比文件长"，
        // 结构锁同样能发现，但先写内容更符合"文件是本体、索引是旁注"的次序。
        storage.write(questionFileOf(bankId), builder.toString())
        storage.write(indexFileOf(bankId), index.build())
        // 倒排表顺手排出来（这一步是纯内存的），尾巴随之作废：主索引已经覆盖全部行。
        storage.write(searchIndexFileOf(bankId), search.render(index.endOffset, search.rowCount))
        storage.delete(searchTailFileOf(bankId))
        forgetIndexLocked(bankId)
        forgetSearchLocked(bankId)
        return WrittenQuestions(written, counts, fingerprint.value(), Fingerprint.CURRENT_VERSION, index.endOffset)
    }

    private companion object {
        const val CATALOG_SCHEMA_VERSION = 2

        /** 内存里最多留几份解析好的偏移表（一份十万题的索引约 6MB）。 */
        const val MAX_CACHED_INDEXES = 2

        /** 尾巴长到这个字节数，下一次追加顺手重建主索引 —— 别让它无限长下去。 */
        const val SEARCH_TAIL_LIMIT_BYTES = 64L * 1024

        /** 单行最多读这么远 —— 再长就不是题库，是别的东西混进来了。 */
        const val SEARCH_READ_MAX = 256 * 1024
    }
}

/**
 * 按需解析的题目列表：`size` 直接来自目录，`get` 才去读文件。
 *
 * 这不是"偷懒"——首页要显示"N 题"和题型分布，如果为了两个数字把十万道题
 * 从磁盘解析成对象，启动就要几秒和几百 MB。真正的题库列表在练习时必然会读，
 * 那时再读一次就好。
 *
 * 读一次即缓存：同一场练习里翻题不该反复解析同一个文件。
 */
internal class DeferredQuestions(
    private val declaredSize: Int,
    private val load: () -> List<Question>,
) : AbstractList<Question>(), RandomAccess {

    private var cached: List<Question>? = null

    override val size: Int get() = cached?.size ?: declaredSize

    override fun get(index: Int): Question {
        val list = cached ?: load().also { cached = it }
        return list[index]
    }

    /** 是否已经真正读过文件（诊断与测试用，不参与业务判断）。 */
    val isLoaded: Boolean get() = cached != null
}

/**
 * 历史仓储的 NDJSON 实现：一次提交追加一行，永不改写已有行。
 * 损坏行不会被静默丢弃 —— 会被计数并通过 [loadWarnings] 暴露。
 */
class JsonAttemptRepository(
    private val storage: TextStorage,
) : AttemptRepository {

    private val json = storageJson
    private val mutex = Mutex()
    private var warnings: List<String> = emptyList()

    override val loadWarnings: List<String> get() = warnings

    override suspend fun append(record: AttemptRecord) {
        mutex.withLock {
            storage.appendLine(
                ATTEMPTS_FILE,
                json.encodeToString(AttemptDto.serializer(), record.toDto()),
            )
        }
    }

    override suspend fun all(): List<AttemptRecord> = mutex.withLock { readLocked() }

    override suspend fun forSession(sessionId: String): List<AttemptRecord> =
        all().filter { it.sessionId == sessionId }

    private fun readLocked(): List<AttemptRecord> {
        val raw = storage.read(ATTEMPTS_FILE) ?: return emptyList()
        if (raw.isBlank()) return emptyList()

        var corrupt = 0
        val records = buildList {
            raw.lineSequence().forEach { line ->
                if (line.isBlank()) return@forEach
                val dto = runCatching {
                    json.decodeFromString(AttemptDto.serializer(), line)
                }.getOrNull()
                if (dto == null) corrupt++ else add(dto.toDomain())
            }
        }
        warnings = if (corrupt == 0) emptyList() else listOf("历史记录中有 $corrupt 行无法解析，已跳过（原始文件未修改）")
        return records
    }
}

/**
 * 按索引读一道题的结果。
 *
 * 分「没有」「读到了」「索引在撒谎」三种，是因为它们该有完全不同的后续动作：
 * 没有 = 这道题真的不在了（正常）；读到了 = 收工；撒谎 = 这张表不能再用，
 * 要重建并退回扫描 —— 而不是把它当成"没有"悄悄咽下去。
 */
private sealed interface IndexRead {
    class Hit(val location: QuestionLocation) : IndexRead
    data object Absent : IndexRead
    data object Lie : IndexRead
}

/** [JsonBankRepository.writeQuestionsLocked] 的产物：目录里那三个"必须等于实际内容"的字段。 */
internal class WrittenQuestions(
    val count: Int,
    val typeCounts: Map<String, Int>,
    val fingerprint: String,
    /** 这串指纹是按哪一版算法算的，一起写进目录。 */
    val fingerprintVersion: Int,
    /** 题目文件写完有多少字节 —— 索引的结构锁要拿它对，测试也用它核对布局。 */
    val fileBytes: Long,
)

private fun QuestionBank.toMeta(written: WrittenQuestions): BankMetaDto = BankMetaDto(
    bankId = bankId,
    title = title,
    sourceLocale = sourceLocale,
    importedAtMillis = importedAtMillis,
    sourceLabel = sourceLabel,
    contentRevision = contentRevision,
    fingerprint = written.fingerprint,
    fingerprintVersion = written.fingerprintVersion,
    questionCount = written.count,
    typeCounts = written.typeCounts,
    provenance = provenance.toDto(),
    locales = localeCoverage.orEmpty().map { LocaleCoverageDto(it.locale, it.covered, it.total) },
)

internal fun Question.toDto(): QuestionDto = QuestionDto(
    questionId = questionId,
    type = type.wireName,
    promptMarkdown = promptMarkdown,
    options = options.map { OptionDto(it.optionId, it.contentMarkdown) },
    correctOptionIds = correctOptionIds.sorted(),
    answerText = answerText,
    explanationMarkdown = explanationMarkdown,
    assetIds = assetIds,
    shuffleAllowed = shuffleAllowed,
    grading = grading.wireName,
    gradingParams = GradingWire.paramsOf(grading),
    tags = tags,
    contentRevision = contentRevision,
    locale = locale,
    translations = translations.map { (locale, text) ->
        QuestionTextDto(
            locale = locale,
            promptMarkdown = text.promptMarkdown,
            optionTexts = text.optionTexts,
            answerText = text.answerText,
            explanationMarkdown = text.explanationMarkdown,
            contentRevision = text.contentRevision,
        )
    }.sortedBy { it.locale },
    origin = origin.wireName,
    sourceId = sourceId,
    sourceLocator = sourceLocator,
)

/** 目录条目 → 领域对象。题目先不读，等真正要题面时再读。 */private fun BankMetaDto.toDomain(loadQuestions: () -> List<Question>): QuestionBank = QuestionBank(
    bankId = bankId,
    title = title,
    sourceLocale = sourceLocale,
    questions = DeferredQuestions(declaredSize = questionCount, load = loadQuestions),
    importedAtMillis = importedAtMillis,
    sourceLabel = sourceLabel,
    contentRevision = contentRevision,
    fingerprint = fingerprint,
    fingerprintVersion = fingerprintVersion,
    typeCounts = typeCounts.mapNotNull { (wire, count) -> QuestionType.fromWire(wire)?.let { it to count } }
        .toMap()
        .takeIf { it.isNotEmpty() },
    // 旧目录没有 locales 段：返回 null 表示"只有源语言"，而不是"零种语言"
    localeCoverage = locales
        .map { LocaleCoverage(it.locale, it.covered, it.total) }
        .takeIf { it.isNotEmpty() },
    provenance = provenance.toDomain(),
)

/**
 * 追加一道题之后的语言覆盖度。
 *
 * 必须与"整库重算一遍"等价：每个已知语言的 total 都 +1，只有那一道题真有这个语言的
 * 译文时 covered 才 +1；这题若带来了目录里没记过的新语言，补一条。
 *
 * 空列表原样返回 —— 老目录没有这一段，那代表"只有源语言"，不能凭空造出一排 0。
 */
private fun grownLocales(
    current: List<LocaleCoverageDto>,
    question: Question,
    sourceLocale: String,
    newTotal: Int,
): List<LocaleCoverageDto> {
    if (current.isEmpty()) return current
    val inline = question.locale?.takeIf { it.isNotBlank() } ?: sourceLocale
    val source = normalizeLocale(inline)
    val has = (question.translations.keys.map(::normalizeLocale) + source).toSet()
    val known = current.mapTo(mutableSetOf()) { normalizeLocale(it.locale) }

    val grown = current.map { dto ->
        val locale = normalizeLocale(dto.locale)
        dto.copy(covered = if (locale in has) dto.covered + 1 else dto.covered, total = newTotal)
    }
    val added = has.filterNot { it in known }.sorted().map { locale ->
        // 源语言是原文本身，新加一道源语言的题它就一定覆盖
        LocaleCoverageDto(locale, if (locale == source) newTotal else 1, newTotal)
    }
    return grown + added
}

private fun normalizeLocale(tag: String): String = LocaleTag.parse(tag)?.value ?: tag

private fun ProvenanceDto.toDomain(): BankProvenance = BankProvenance(
    sources = sources.map {
        PackageSource(
            sourceId = it.sourceId,
            title = it.title,
            kind = it.kind,
            author = it.author,
            edition = it.edition,
            url = it.url,
            license = it.license,
            note = it.note,
        )
    },
    digest = digest,
    digestVerified = digestVerified,
    authorName = authorName,
    license = license,
)

private fun BankProvenance.toDto(): ProvenanceDto = ProvenanceDto(
    sources = sources.map {
        PackageSourceDto(
            sourceId = it.sourceId,
            title = it.title,
            kind = it.kind,
            author = it.author,
            edition = it.edition,
            url = it.url,
            license = it.license,
            note = it.note,
        )
    },
    digest = digest,
    digestVerified = digestVerified,
    authorName = authorName,
    license = license,
)

internal fun QuestionDto.toDomain(): Question? {
    val type = QuestionType.fromWire(this.type) ?: return null
    val grading = GradingWire.parse(this.grading, this.gradingParams) ?: Question.defaultGradingFor(type)
    return Question(
        questionId = questionId,
        type = type,
        promptMarkdown = promptMarkdown,
        options = options.map { QuestionOption(it.optionId, it.contentMarkdown) },
        correctOptionIds = correctOptionIds.toSet(),
        answerText = answerText,
        explanationMarkdown = explanationMarkdown,
        assetIds = assetIds,
        shuffleAllowed = shuffleAllowed,
        grading = grading,
        tags = tags,
        contentRevision = contentRevision,
        locale = locale,
        origin = ProvenanceOrigin.fromWire(origin),
        sourceId = sourceId,
        sourceLocator = sourceLocator,
        translations = translations.associate { dto ->
            dto.locale to QuestionText(
                locale = dto.locale,
                promptMarkdown = dto.promptMarkdown,
                optionTexts = dto.optionTexts,
                answerText = dto.answerText,
                explanationMarkdown = dto.explanationMarkdown,
                contentRevision = dto.contentRevision,
            )
        },
    )
}

private fun AttemptRecord.toDto(): AttemptDto = AttemptDto(
    attemptId = attemptId,
    sessionId = sessionId,
    bankId = bankId,
    questionId = questionId,
    questionType = questionType.wireName,
    mode = mode.wireName,
    displayOptionIds = displayOptionIds,
    selectedOptionIds = selectedOptionIds.toList(),
    orderedOptionIds = orderedOptionIds,
    textAnswer = textAnswer,
    verdict = verdict.name,
    strategyId = strategyId,
    gradingSpec = gradingSpec,
    answerRevision = answerRevision,
    questionFingerprint = questionFingerprint,
    submittedAtMillis = submittedAtMillis,
    elapsedMillis = elapsedMillis,
    explanationSeen = explanationSeen,
    usedTools = usedTools,
    locale = locale,
)

private fun AttemptDto.toDomain(): AttemptRecord = AttemptRecord(
    attemptId = attemptId,
    sessionId = sessionId,
    bankId = bankId,
    questionId = questionId,
    questionType = QuestionType.fromWire(questionType) ?: QuestionType.SHORT_ANSWER,
    mode = PracticeMode.entries.firstOrNull { it.wireName == mode } ?: PracticeMode.NATIVE,
    displayOptionIds = displayOptionIds,
    selectedOptionIds = selectedOptionIds.toSet(),
    orderedOptionIds = orderedOptionIds,
    textAnswer = textAnswer,
    verdict = Verdict.entries.firstOrNull { it.name == verdict } ?: Verdict.PENDING,
    strategyId = strategyId,
    gradingSpec = gradingSpec,
    answerRevision = answerRevision,
    questionFingerprint = questionFingerprint,
    submittedAtMillis = submittedAtMillis,
    elapsedMillis = elapsedMillis,
    explanationSeen = explanationSeen,
    usedTools = usedTools,
    locale = locale,
)


internal const val GRAPH_FILE = "graph-v1.json"

/**
 * 个人知识图谱的 JSON 实现。
 *
 * 整文件重写：图谱是手工积累的，条数量级远小于题库；
 * 代价是每改一条关系都要重写整张图，收益是"不会有半条关系留在磁盘上"。
 *
 * 单条关系损坏只跳过那一条并计数 —— 一条脏数据不该让人丢掉整张图谱。
 */
class JsonGraphRepository(
    private val storage: TextStorage,
) : GraphRepository {

    private val json = storageJson
    private val mutex = Mutex()
    private var warnings: List<String> = emptyList()

    override val loadWarnings: List<String> get() = warnings

    override suspend fun load(): KnowledgeGraph = mutex.withLock { readLocked() }

    override suspend fun save(graph: KnowledgeGraph) {
        mutex.withLock {
            storage.write(GRAPH_FILE, json.encodeToString(GraphFileDto.serializer(), graph.pruned().toDto()))
        }
    }

    private fun readLocked(): KnowledgeGraph {
        val raw = storage.read(GRAPH_FILE) ?: return KnowledgeGraph.Empty
        if (raw.isBlank()) return KnowledgeGraph.Empty

        val dto = runCatching { json.decodeFromString(GraphFileDto.serializer(), raw) }.getOrNull()
        if (dto == null) {
            // 整份坏掉时不覆盖原文件：那是一张用户看不见、也重建不出来的图
            warnings = listOf("知识图谱文件无法解析，已按空图谱打开（原文件未被覆盖）")
            return KnowledgeGraph.Empty
        }

        var skipped = 0
        val edges = dto.edges.mapNotNull { edgeDto ->
            val edge = edgeDto.toDomain()
            val broken = edge == null || edge.validate().any { it.severity == Severity.ERROR }
            if (broken) {
                skipped++
                null
            } else {
                edge
            }
        }
        if (skipped > 0) {
            warnings = listOf("知识图谱有 $skipped 条关系不合法，已跳过（原文件未修改）")
        }
        return KnowledgeGraph(
            snapshots = dto.snapshots.mapNotNull { it.toDomain() },
            edges = edges,
        )
    }
}

private fun KnowledgeGraph.toDto(): GraphFileDto = GraphFileDto(
    snapshots = snapshots.map { snapshot ->
        GraphSnapshotDto(
            bankId = snapshot.ref.bankId,
            questionId = snapshot.ref.questionId,
            bankTitle = snapshot.bankTitle,
            promptExcerpt = snapshot.promptExcerpt,
            contentRevision = snapshot.contentRevision,
            capturedAtMillis = snapshot.capturedAtMillis,
        )
    },
    edges = edges.map { edge ->
        GraphEdgeDto(
            edgeId = edge.edgeId,
            sourceBankId = edge.source.bankId,
            sourceQuestionId = edge.source.questionId,
            targetBankId = edge.target.bankId,
            targetQuestionId = edge.target.questionId,
            relation = edge.relation.wireName,
            dependency = edge.dependency.wireName,
            noteMarkdown = edge.noteMarkdown,
            createdAtMillis = edge.createdAtMillis,
            updatedAtMillis = edge.updatedAtMillis,
        )
    },
)

private fun GraphSnapshotDto.toDomain(): GraphSnapshot? {
    val ref = QuestionRef(bankId, questionId)
    if (!ref.isComplete()) return null
    return GraphSnapshot(
        ref = ref,
        bankTitle = bankTitle,
        promptExcerpt = promptExcerpt,
        contentRevision = contentRevision.coerceAtLeast(1),
        capturedAtMillis = capturedAtMillis,
    )
}

private fun GraphEdgeDto.toDomain(): GraphEdge? {
    val relation = GraphRelation.fromWire(relation) ?: return null
    val source = QuestionRef(sourceBankId, sourceQuestionId)
    val target = QuestionRef(targetBankId, targetQuestionId)
    if (!source.isComplete() || !target.isComplete()) return null
    return GraphEdge(
        edgeId = edgeId,
        source = source,
        target = target,
        relation = relation,
        dependency = DependencyKind.fromWire(dependency),
        noteMarkdown = noteMarkdown,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
    )
}
