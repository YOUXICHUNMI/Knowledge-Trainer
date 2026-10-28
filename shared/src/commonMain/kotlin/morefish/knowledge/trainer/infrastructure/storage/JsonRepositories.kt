package morefish.knowledge.trainer.infrastructure.storage

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import morefish.knowledge.trainer.application.port.AttemptRepository
import morefish.knowledge.trainer.application.port.BankRepository
import morefish.knowledge.trainer.application.port.GraphRepository
import morefish.knowledge.trainer.application.port.QuestionLocation
import morefish.knowledge.trainer.application.port.TextStorage
import morefish.knowledge.trainer.domain.graph.DependencyKind
import morefish.knowledge.trainer.domain.graph.GraphEdge
import morefish.knowledge.trainer.domain.graph.GraphRelation
import morefish.knowledge.trainer.domain.graph.GraphSnapshot
import morefish.knowledge.trainer.domain.graph.KnowledgeGraph
import morefish.knowledge.trainer.domain.graph.QuestionRef
import morefish.knowledge.trainer.domain.graph.validate
import morefish.knowledge.trainer.domain.i18n.LocaleCoverage
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
        return list().firstOrNull { it.fingerprint == fingerprint }
    }

    override suspend fun save(bank: QuestionBank) {
        mutex.withLock {
            val catalog = readCatalogLocked()
            val (count, typeCounts) = writeQuestionsLocked(bank.bankId, bank.questions)
            val meta = bank.toMeta(count, typeCounts)
            val next = catalog.filterNot { it.bankId == bank.bankId } + meta
            storage.write(BANKS_FILE, json.encodeToString(BankCatalogDto.serializer(), BankCatalogDto(banks = next)))
        }
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
        val next = catalog.filterNot { it.bankId == bankId }
        storage.write(BANKS_FILE, json.encodeToString(BankCatalogDto.serializer(), BankCatalogDto(banks = next)))
        storage.delete(questionFileOf(bankId))
        true
    }

    /**
     * 精确定位：每个库只读一次文件，逐行找目标 id。
     *
     * 关键在第二道过滤：解码之前先看这一行是否**真的**包含要找的 id。
     * 十万题的库有十万行，而一次图谱刷新通常只查两三道题 ——
     * 为了三行去解码十万行是纯浪费（整库解析要约 600ms，这里是一遍字符串扫描）。
     */
    override suspend fun locate(refs: List<QuestionRef>): Map<QuestionRef, QuestionLocation> {
        if (refs.isEmpty()) return emptyMap()
        return mutex.withLock {
            val titles = readCatalogLocked().associate { it.bankId to it.title }
            val found = mutableMapOf<QuestionRef, QuestionLocation>()

            refs.groupBy { it.bankId }.forEach bankLoop@{ (bankId, wanted) ->
                val title = titles[bankId] ?: return@bankLoop
                val raw = storage.read(questionFileOf(bankId)) ?: return@bankLoop
                val remaining = wanted.mapTo(mutableSetOf()) { it.questionId }

                raw.lineSequence().forEach lineLoop@{ line ->
                    if (remaining.isEmpty()) return@lineLoop
                    if (remaining.none { line.contains("\"$it\"") }) return@lineLoop
                    val dto = runCatching {
                        questionJson.decodeFromString(QuestionDto.serializer(), line)
                    }.getOrNull() ?: return@lineLoop
                    if (dto.questionId !in remaining) return@lineLoop
                    val question = dto.toDomain() ?: return@lineLoop
                    found[QuestionRef(bankId, question.questionId)] = QuestionLocation(
                        bankId = bankId,
                        bankTitle = title,
                        questionId = question.questionId,
                        promptMarkdown = question.promptMarkdown,
                        type = question.type,
                        contentRevision = question.contentRevision,
                    )
                    remaining -= question.questionId
                }
            }
            found
        }
    }

    /**
     * 搜题干。刻意不搜选项文字：四个选项里都可能出现同一个词，
     * 搜出来的每道题都"命中"，等于没搜。
     */
    override suspend fun searchQuestions(keyword: String, limit: Int): List<QuestionLocation> {
        val query = keyword.trim()
        if (query.isEmpty() || limit <= 0) return emptyList()
        return mutex.withLock {
            val hits = ArrayList<QuestionLocation>()
            outer@ for (meta in readCatalogLocked()) {
                val raw = storage.read(questionFileOf(meta.bankId)) ?: continue
                for (line in raw.lineSequence()) {
                    if (hits.size >= limit) break@outer
                    if (line.isBlank() || !line.contains(query, ignoreCase = true)) continue
                    val dto = runCatching {
                        questionJson.decodeFromString(QuestionDto.serializer(), line)
                    }.getOrNull() ?: continue
                    val question = dto.toDomain() ?: continue
                    if (!question.promptMarkdown.contains(query, ignoreCase = true)) continue
                    hits += QuestionLocation(
                        bankId = meta.bankId,
                        bankTitle = meta.title,
                        questionId = question.questionId,
                        promptMarkdown = question.promptMarkdown,
                        type = question.type,
                        contentRevision = question.contentRevision,
                    )
                }
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
            val (count, typeCounts) = writeQuestionsLocked(dto.bankId, questions)
            BankMetaDto(
                bankId = dto.bankId,
                title = dto.title,
                sourceLocale = dto.sourceLocale,
                importedAtMillis = dto.importedAtMillis,
                sourceLabel = dto.sourceLabel,
                contentRevision = dto.contentRevision,
                fingerprint = dto.fingerprint
                    .ifBlank { Fingerprint.ofQuestions(questions.map { it.contentFingerprint() }) },
                questionCount = count,
                typeCounts = typeCounts,
                // 旧布局没有出处信息（字段是 P8 第三块才加的），迁移时留空 —— 不伪造
            )
        }
        // 题目文件都已落地，才写目录 —— 目录就是这次迁移的提交点
        storage.write(BANKS_FILE, json.encodeToString(BankCatalogDto.serializer(), BankCatalogDto(banks = metas)))
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

    /** 返回实际写入的条数与题型分布；这两个值随后进目录。 */
    private fun writeQuestionsLocked(bankId: String, questions: List<Question>): Pair<Int, Map<String, Int>> {
        val builder = StringBuilder()
        val counts = mutableMapOf<String, Int>()
        var written = 0
        questions.forEach { question ->
            builder.append(questionJson.encodeToString(QuestionDto.serializer(), question.toDto())).append('\n')
            counts[question.type.wireName] = (counts[question.type.wireName] ?: 0) + 1
            written++
        }
        storage.write(questionFileOf(bankId), builder.toString())
        return written to counts
    }

    private companion object {
        const val CATALOG_SCHEMA_VERSION = 2
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

private fun QuestionBank.toMeta(questionCount: Int, typeCounts: Map<String, Int>): BankMetaDto = BankMetaDto(
    bankId = bankId,
    title = title,
    sourceLocale = sourceLocale,
    importedAtMillis = importedAtMillis,
    sourceLabel = sourceLabel,
    contentRevision = contentRevision,
    fingerprint = fingerprint,
    questionCount = questionCount,
    typeCounts = typeCounts,
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

/** 目录条目 → 领域对象。题目先不读，等真正要题面时再读。 */
private fun BankMetaDto.toDomain(loadQuestions: () -> List<Question>): QuestionBank = QuestionBank(
    bankId = bankId,
    title = title,
    sourceLocale = sourceLocale,
    questions = DeferredQuestions(declaredSize = questionCount, load = loadQuestions),
    importedAtMillis = importedAtMillis,
    sourceLabel = sourceLabel,
    contentRevision = contentRevision,
    fingerprint = fingerprint,
    typeCounts = typeCounts.mapNotNull { (wire, count) -> QuestionType.fromWire(wire)?.let { it to count } }
        .toMap()
        .takeIf { it.isNotEmpty() },
    // 旧目录没有 locales 段：返回 null 表示"只有源语言"，而不是"零种语言"
    localeCoverage = locales
        .map { LocaleCoverage(it.locale, it.covered, it.total) }
        .takeIf { it.isNotEmpty() },
    provenance = provenance.toDomain(),
)

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
