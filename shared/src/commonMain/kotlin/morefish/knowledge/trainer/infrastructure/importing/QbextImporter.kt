package morefish.knowledge.trainer.infrastructure.importing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import morefish.knowledge.trainer.domain.extension.BankBinding
import morefish.knowledge.trainer.domain.extension.ExtensionAnnotation
import morefish.knowledge.trainer.domain.extension.ExtensionPack
import morefish.knowledge.trainer.domain.extension.ExtensionRelation
import morefish.knowledge.trainer.domain.extension.MAX_ANNOTATION_CHARS
import morefish.knowledge.trainer.domain.extension.MAX_EXTENSION_ANNOTATIONS
import morefish.knowledge.trainer.domain.extension.MAX_EXTENSION_RELATIONS
import morefish.knowledge.trainer.domain.extension.semanticVersionOf
import morefish.knowledge.trainer.domain.extension.validate
import morefish.knowledge.trainer.domain.graph.DependencyKind
import morefish.knowledge.trainer.domain.graph.GraphRelation
import morefish.knowledge.trainer.domain.graph.QuestionRef
import morefish.knowledge.trainer.domain.model.Severity
import morefish.knowledge.trainer.infrastructure.archive.DeflateCodec
import morefish.knowledge.trainer.infrastructure.archive.Sha256
import morefish.knowledge.trainer.infrastructure.archive.ZipFormatException
import morefish.knowledge.trainer.infrastructure.archive.ZipReader

/**
 * `.qbext` 扩展包读取器（KT-QBX 1.0）。
 *
 * 与 `.qbank` 同样的纪律：清单与逐文件哈希双向核对，任何一条不合规就整包拒绝，
 * 不写半份数据。区别在于**它不改题库**：装上的东西只有关系与注释，
 * 卸载时也只需要删掉这个包。
 *
 * 绑定是做在 manifest 里的：一个扩展包只认一个 bankId。
 * 题库暂时没装不算错误 —— 包照样保存下来，等题库回来再自动生效。
 */
object QbextImporter {

    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    private const val MANIFEST = "manifest.json"
    const val FORMAT_ID = "knowledge-trainer-bank-extension"
    private const val RELATIONS_FILE = "relations/edges.ndjson"
    private const val ANNOTATIONS_FILE = "annotations/annotations.ndjson"
    private val PACKAGE_ID = Regex("^[a-z0-9][a-z0-9_.-]{0,63}$")
    private val QID = Regex("^[a-z0-9][a-z0-9_.-]{0,63}$")
    private val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    sealed interface Result {
        data class Success(val pack: ExtensionPack, val warnings: List<Issue> = emptyList()) : Result
        data class Failure(val issues: List<Issue>) : Result
    }

    data class Issue(val code: String, val message: String)

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
        fun readText(name: String): String = readEntry(name).decodeToString().removePrefix("\uFEFF")

        // ---------- manifest ----------
        if (MANIFEST !in byName) {
            return Result.Failure(listOf(Issue("MANIFEST_MISSING", "包内缺少 manifest.json，这不是有效的 .qbext")))
        }
        val manifestText = runCatching { readText(MANIFEST) }.getOrElse { e ->
            return Result.Failure(listOf(Issue("ZIP_INVALID", "无法解压 manifest.json：${e.message ?: "解压失败"}")))
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
                        "扩展包格式版本 $schemaVersion 不受支持（本版本只支持 1.0.x）",
                    ),
                ),
            )
        }
        if (manifest.str("format") != FORMAT_ID) {
            return Result.Failure(listOf(Issue("MANIFEST_INVALID", "manifest.format 不是 $FORMAT_ID")))
        }

        val packageId = manifest.str("packageId") ?: ""
        if (!PACKAGE_ID.matches(packageId)) {
            fail("MANIFEST_INVALID", "packageId 非法：$packageId（小写字母数字开头，可用 . _ -）")
        }
        val version = manifest.str("version") ?: ""
        if (semanticVersionOf(version) == null) {
            fail("MANIFEST_INVALID", "version 必须是 主.次.修 形式，实际是 $version")
        }
        val binds = manifest["binds"] as? JsonObject
        val bankId = binds?.str("bankId") ?: ""
        if (!UUID.matches(bankId)) {
            fail("BINDING_INVALID", "binds.bankId 必须是题库 UUID，实际是 ${bankId.ifBlank { "（空）" }}")
        }
        val bankTitleHint = binds?.str("bankTitle") ?: ""
        val name = manifest.str("name")?.trim().orEmpty()
        if (name.isBlank()) {
            fail("MANIFEST_INVALID", "扩展包缺少 name")
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
            if (Sha256.hex(readEntry(path)) != expected) {
                fail("HASH_MISMATCH", "$path 的内容摘要与清单不符，文件可能被篡改或损坏")
            }
        }
        for (entry in byName.keys) {
            if (entry != MANIFEST && entry !in listed) {
                fail("FILE_NOT_LISTED", "包内有未列入清单的文件：$entry")
            }
        }
        if (issues.isNotEmpty()) return Result.Failure(issues)

        // ---------- 关系 ----------
        val relations = mutableListOf<ExtensionRelation>()
        val seenRelationIds = HashSet<String>()
        if (RELATIONS_FILE in byName) {
            lines(readText(RELATIONS_FILE)).forEach { (lineno, line) ->
                if (relations.size >= MAX_EXTENSION_RELATIONS) {
                    fail("EXT_TOO_MANY_RELATIONS", "关系超过 $MAX_EXTENSION_RELATIONS 条，本包不导入")
                    return@forEach
                }
                val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
                if (obj == null) {
                    fail("NDJSON_INVALID", "$RELATIONS_FILE 第 $lineno 行不是合法 JSON 对象")
                    return@forEach
                }
                val relationId = obj.str("relationId")
                if (relationId == null || !QID.matches(relationId)) {
                    fail("SCHEMA_INVALID", "$RELATIONS_FILE 第 $lineno 行 relationId 非法：$relationId")
                    return@forEach
                }
                if (!seenRelationIds.add(relationId)) {
                    fail("EXT_RELATION_DUPLICATE", "relationId $relationId 在包内重复")
                    return@forEach
                }
                val relation = GraphRelation.fromWire(obj.str("relation"))
                if (relation == null) {
                    fail("SCHEMA_INVALID", "$RELATIONS_FILE 第 $lineno 行 relation 无法识别：${obj.str("relation")}")
                    return@forEach
                }

                val sourceObj = obj["source"] as? JsonObject
                val targetObj = obj["target"] as? JsonObject
                if (sourceObj == null || targetObj == null) {
                    fail("SCHEMA_INVALID", "$RELATIONS_FILE 第 $lineno 行缺少 source / target")
                    return@forEach
                }
                // 起点缺 bankId 时默认用绑定的题库；显式写了就必须一致
                val sourceBankId = sourceObj.str("bankId") ?: bankId
                if (sourceBankId != bankId) {
                    fail(
                        "EXT_FOREIGN_SOURCE",
                        "$RELATIONS_FILE 第 $lineno 行的起点属于 $sourceBankId，" +
                            "但这个扩展包绑定的是 $bankId（扩展包无权给别的题库加关系）",
                    )
                    return@forEach
                }
                val sourceQid = sourceObj.str("questionId") ?: ""
                val targetBankId = targetObj.str("bankId") ?: ""
                val targetQid = targetObj.str("questionId") ?: ""
                if (!QID.matches(sourceQid) || !QID.matches(targetQid) || !UUID.matches(targetBankId)) {
                    fail(
                        "SCHEMA_INVALID",
                        "$RELATIONS_FILE 第 $lineno 行的端点非法：" +
                            "source.questionId=$sourceQid target=$targetBankId/$targetQid",
                    )
                    return@forEach
                }
                relations += ExtensionRelation(
                    relationId = relationId,
                    source = QuestionRef(sourceBankId, sourceQid),
                    target = QuestionRef(targetBankId, targetQid),
                    relation = relation,
                    dependency = DependencyKind.fromWire(obj.str("dependency")),
                    noteMarkdown = obj.str("noteMarkdown")?.trim().orEmpty(),
                )
            }
        }

        // ---------- 注释 ----------
        val annotations = mutableListOf<ExtensionAnnotation>()
        val seenAnnotationIds = HashSet<String>()
        if (ANNOTATIONS_FILE in byName) {
            lines(readText(ANNOTATIONS_FILE)).forEach { (lineno, line) ->
                if (annotations.size >= MAX_EXTENSION_ANNOTATIONS) {
                    fail("EXT_TOO_MANY_ANNOTATIONS", "注释超过 $MAX_EXTENSION_ANNOTATIONS 条，本包不导入")
                    return@forEach
                }
                val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
                if (obj == null) {
                    fail("NDJSON_INVALID", "$ANNOTATIONS_FILE 第 $lineno 行不是合法 JSON 对象")
                    return@forEach
                }
                val annotationId = obj.str("annotationId")
                if (annotationId == null || !QID.matches(annotationId)) {
                    fail("SCHEMA_INVALID", "$ANNOTATIONS_FILE 第 $lineno 行 annotationId 非法：$annotationId")
                    return@forEach
                }
                if (!seenAnnotationIds.add(annotationId)) {
                    fail("EXT_ANNOTATION_DUPLICATE", "annotationId $annotationId 在包内重复")
                    return@forEach
                }
                val questionId = obj.str("questionId")
                if (questionId != null && !QID.matches(questionId)) {
                    fail("SCHEMA_INVALID", "$ANNOTATIONS_FILE 第 $lineno 行 questionId 非法：$questionId")
                    return@forEach
                }
                val body = obj.str("bodyMarkdown").orEmpty()
                if (body.isBlank()) {
                    fail("SCHEMA_INVALID", "$ANNOTATIONS_FILE 第 $lineno 行 bodyMarkdown 为空")
                    return@forEach
                }
                if (body.length > MAX_ANNOTATION_CHARS) {
                    fail(
                        "SCHEMA_INVALID",
                        "$ANNOTATIONS_FILE 第 $lineno 行超过 $MAX_ANNOTATION_CHARS 字，请拆成多条",
                    )
                    return@forEach
                }
                annotations += ExtensionAnnotation(
                    annotationId = annotationId,
                    questionId = questionId,
                    title = obj.str("title")?.trim().orEmpty(),
                    bodyMarkdown = body,
                )
            }
        }

        if (issues.isNotEmpty()) return Result.Failure(issues)

        if (relations.isEmpty() && annotations.isEmpty()) {
            return Result.Failure(
                listOf(
                    Issue(
                        "EXT_EMPTY",
                        "包内既没有 relations/edges.ndjson 也没有 annotations/annotations.ndjson，" +
                            "装上不会有任何变化",
                    ),
                ),
            )
        }

        val pack = ExtensionPack(
            packageId = packageId,
            name = name,
            version = version,
            author = (manifest["author"] as? JsonObject)?.str("displayName") ?: "未知作者",
            license = manifest.str("license").orEmpty(),
            binding = BankBinding(bankId, bankTitleHint),
            relations = relations,
            annotations = annotations,
        )

        // 领域校验兜底：包的规则与图谱共用一套，不在读取器里另写一遍
        val problems = pack.validate()
        val errors = problems.filter { it.severity == Severity.ERROR }
        if (errors.isNotEmpty()) {
            return Result.Failure(errors.map { Issue(it.code.uppercase().replace('-', '_'), it.message) })
        }
        problems.filter { it.severity == Severity.WARNING }.forEach {
            warnings += Issue(it.code.uppercase().replace('-', '_'), it.message)
        }

        return Result.Success(pack, warnings)
    }

    // ---- 工具 ----

    private fun lines(text: String): List<Pair<Int, String>> =
        text.lineSequence().withIndex()
            .map { (index, raw) -> (index + 1) to raw.trim() }
            .filter { (_, line) -> line.isNotEmpty() && !line.startsWith("#") }
            .toList()

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.array(key: String): JsonArray? = (this[key] as? JsonArray)
}
