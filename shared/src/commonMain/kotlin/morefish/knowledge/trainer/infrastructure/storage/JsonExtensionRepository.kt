package morefish.knowledge.trainer.infrastructure.storage

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import morefish.knowledge.trainer.application.port.ExtensionRepository
import morefish.knowledge.trainer.application.port.TextStorage
import morefish.knowledge.trainer.domain.extension.BankBinding
import morefish.knowledge.trainer.domain.extension.ExtensionAnnotation
import morefish.knowledge.trainer.domain.extension.ExtensionPack
import morefish.knowledge.trainer.domain.extension.ExtensionRelation
import morefish.knowledge.trainer.domain.extension.validate
import morefish.knowledge.trainer.domain.graph.DependencyKind
import morefish.knowledge.trainer.domain.graph.GraphRelation
import morefish.knowledge.trainer.domain.graph.QuestionRef
import morefish.knowledge.trainer.domain.model.Severity

internal const val EXTENSIONS_FILE = "extensions-v1.json"

/**
 * 扩展包仓储：整文件重写。
 *
 * 理由与笔记、图谱一致 —— 扩展包是人手工挑着装的，量级是"几个"，
 * 不是"十万条"。整文件重写换来的是"删得干净"：移除一个包之后，
 * 文件里不会残留它的关系。
 *
 * 单个包读坏只丢那个包并计数：装坏了三个包不该让另外两个也消失。
 */
class JsonExtensionRepository(
    private val storage: TextStorage,
) : ExtensionRepository {

    private val json = storageJson
    private val mutex = Mutex()
    private var warnings: List<String> = emptyList()

    override val loadWarnings: List<String> get() = warnings

    override suspend fun all(): List<ExtensionPack> = mutex.withLock { readLocked() }

    override suspend fun save(pack: ExtensionPack) {
        mutex.withLock {
            val current = readLocked()
            val next = (current.filterNot { it.packageId == pack.packageId } + pack)
                .sortedBy { it.importedAtMillis }
            writeLocked(next)
        }
    }

    override suspend fun remove(packageId: String) {
        mutex.withLock {
            val current = readLocked()
            writeLocked(current.filterNot { it.packageId == packageId })
        }
    }

    private fun writeLocked(packs: List<ExtensionPack>) {
        storage.write(
            EXTENSIONS_FILE,
            json.encodeToString(
                ExtensionsFileDto.serializer(),
                ExtensionsFileDto(packs = packs.map { it.toDto() }),
            ),
        )
    }

    private fun readLocked(): List<ExtensionPack> {
        val raw = storage.read(EXTENSIONS_FILE) ?: run {
            warnings = emptyList()
            return emptyList()
        }
        if (raw.isBlank()) return emptyList()

        val dto = runCatching { json.decodeFromString(ExtensionsFileDto.serializer(), raw) }.getOrNull()
        if (dto == null) {
            // 整份坏掉时不覆盖：那是用户装过的包清单，重建不出来
            warnings = listOf("扩展包清单无法解析，已按未安装处理（原文件未被覆盖）")
            return emptyList()
        }

        val packs = mutableListOf<ExtensionPack>()
        var skipped = 0
        dto.packs.forEach { packDto ->
            val pack = packDto.toDomain()
            val broken = pack == null || pack.validate().any { it.severity == Severity.ERROR }
            if (broken) skipped++ else packs += pack
        }
        warnings = if (skipped > 0) {
            listOf("有 $skipped 个扩展包记录不完整，已跳过（原文件未修改）")
        } else {
            emptyList()
        }
        return packs
    }
}

private fun ExtensionPack.toDto(): ExtensionPackDto = ExtensionPackDto(
    packageId = packageId,
    name = name,
    version = version,
    author = author,
    license = license,
    bankId = binding.bankId,
    bankTitleHint = binding.bankTitleHint,
    importedAtMillis = importedAtMillis,
    sourceLabel = sourceLabel,
    relations = relations.map { rel ->
        ExtensionRelationDto(
            relationId = rel.relationId,
            sourceQuestionId = rel.source.questionId,
            targetBankId = rel.target.bankId,
            targetQuestionId = rel.target.questionId,
            relation = rel.relation.wireName,
            dependency = rel.dependency.wireName,
            noteMarkdown = rel.noteMarkdown,
        )
    },
    annotations = annotations.map { ann ->
        ExtensionAnnotationDto(
            annotationId = ann.annotationId,
            questionId = ann.questionId,
            title = ann.title,
            bodyMarkdown = ann.bodyMarkdown,
        )
    },
)

private fun ExtensionPackDto.toDomain(): ExtensionPack? {
    if (packageId.isBlank() || bankId.isBlank()) return null
    val binding = BankBinding(bankId = bankId, bankTitleHint = bankTitleHint)
    val relations = relations.mapNotNull { rel ->
        val relation = GraphRelation.fromWire(rel.relation) ?: return@mapNotNull null
        if (rel.sourceQuestionId.isBlank() || rel.targetBankId.isBlank() || rel.targetQuestionId.isBlank()) {
            return@mapNotNull null
        }
        ExtensionRelation(
            relationId = rel.relationId,
            // 起点永远是绑定的题库：文件里只存 questionId，避免同一个包出现两个起点库
            source = QuestionRef(binding.bankId, rel.sourceQuestionId),
            target = QuestionRef(rel.targetBankId, rel.targetQuestionId),
            relation = relation,
            dependency = DependencyKind.fromWire(rel.dependency),
            noteMarkdown = rel.noteMarkdown,
        )
    }
    val annotations = annotations.mapNotNull { ann ->
        if (ann.annotationId.isBlank() || ann.bodyMarkdown.isBlank()) return@mapNotNull null
        ExtensionAnnotation(
            annotationId = ann.annotationId,
            questionId = ann.questionId?.takeIf { it.isNotBlank() },
            title = ann.title,
            bodyMarkdown = ann.bodyMarkdown,
        )
    }
    return ExtensionPack(
        packageId = packageId,
        name = name,
        version = version,
        author = author,
        license = license,
        binding = binding,
        relations = relations,
        annotations = annotations,
        importedAtMillis = importedAtMillis,
        sourceLabel = sourceLabel,
    )
}
