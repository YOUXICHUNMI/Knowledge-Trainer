package morefish.knowledge.trainer.infrastructure.importing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import morefish.knowledge.trainer.domain.model.ProvenanceOrigin
import morefish.knowledge.trainer.domain.model.Question
import morefish.knowledge.trainer.domain.model.QuestionOption
import morefish.knowledge.trainer.domain.model.QuestionRules
import morefish.knowledge.trainer.domain.model.QuestionType
import morefish.knowledge.trainer.domain.patch.BankPatch
import morefish.knowledge.trainer.domain.patch.BankPatchOp
import morefish.knowledge.trainer.domain.patch.QuestionChanges
import morefish.knowledge.trainer.infrastructure.archive.DeflateCodec
import morefish.knowledge.trainer.infrastructure.archive.Sha256
import morefish.knowledge.trainer.infrastructure.archive.ZipFormatException
import morefish.knowledge.trainer.infrastructure.archive.ZipReader

/**
 * `.qbpatch` 更新补丁读取器（KT-QBP 1.0）。
 *
 * 与 `.qbank` / `.qbext` 同一套纪律：清单与逐文件哈希双向核对，任何一条不合规就整包拒绝。
 * 差别在**它描述的是改动**，所以校验关心的东西不一样：
 *
 * - `.qbank` 关心"整库是否自洽"（每题、每语言、每媒体）；
 * - `.qbpatch` 关心"改动能不能落地"（改的题在不在、改完还成不成立、有没有同一题改两次）。
 *
 * 有一件事这里**做不到**，必须在应用端做：`update` 合并之后的题目是否自洽。
 * 因为那需要本地那道题的现状。所以这里只把 `set` 原样读出来，
 * 合并与复检交给 `BankPatcher` —— 少了这一步，"改完变成非法题目"就会写进题库。
 */
object QbpatchImporter {

    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    private const val MANIFEST = "manifest.json"
    const val FORMAT_ID = "knowledge-trainer-bank-patch"

    private val PATCH_ID = Regex("^[a-z0-9][a-z0-9_.-]{0,63}$")
    private val QID = Regex("^[a-z0-9][a-z0-9_.-]{0,63}$")
    private val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val HEX16 = Regex("^[0-9a-f]{16}$")
    private val SEMVER = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$")

    private const val MAX_OPERATIONS = 2000
    private const val MAX_TITLE_CHARS = 120

    sealed interface Result {
        data class Success(val patch: BankPatch, val warnings: List<Issue> = emptyList()) : Result
        data class Failure(val issues: List<Issue>) : Result
    }

    data class Issue(val code: String, val message: String)

    fun isPatch(bytes: ByteArray): Boolean = ZipReader.isZip(bytes)

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

        if (MANIFEST !in byName) {
            return Result.Failure(listOf(Issue("MANIFEST_MISSING", "包内缺少 manifest.json，这不是有效的 .qbpatch")))
        }
        val manifestText = runCatching { readEntry(MANIFEST).decodeToString().removePrefix("\uFEFF") }
            .getOrElse { e ->
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
                        "补丁格式版本 $schemaVersion 不受支持（本版本只支持 1.0.x）",
                    ),
                ),
            )
        }
        if (manifest.str("format") != FORMAT_ID) {
            return Result.Failure(listOf(Issue("MANIFEST_INVALID", "manifest.format 不是 $FORMAT_ID")))
        }

        val patchId = manifest.str("patchId") ?: ""
        if (!PATCH_ID.matches(patchId)) {
            fail("MANIFEST_INVALID", "patchId 非法：$patchId（小写字母数字开头，可用 . _ -）")
        }
        val version = manifest.str("version") ?: ""
        if (!SEMVER.matches(version)) {
            fail("MANIFEST_INVALID", "version 必须是 主.次.修 形式，实际是 $version")
        }
        val name = manifest.str("name")?.trim() ?: ""
        if (name.isEmpty() || name.length > MAX_TITLE_CHARS) {
            fail("MANIFEST_INVALID", "name 缺失或超过 $MAX_TITLE_CHARS 字")
        }
        val bankId = manifest.str("bankId") ?: ""
        if (!UUID.matches(bankId)) {
            fail("MANIFEST_INVALID", "bankId 必须是 UUID，实际是 $bankId")
        }

        val baseRevision = manifest.int("baseRevision") ?: 0
        val targetRevision = manifest.int("targetRevision") ?: 0
        if (baseRevision < 1) {
            fail("MANIFEST_INVALID", "baseRevision 必须是正整数（补丁基于第几版）")
        }
        if (targetRevision <= baseRevision) {
            fail("MANIFEST_INVALID", "targetRevision 必须大于 baseRevision（$baseRevision → $targetRevision）")
        }

        val baseFingerprint = manifest.str("baseFingerprint") ?: ""
        if (!HEX16.matches(baseFingerprint)) {
            fail(
                "BASE_FINGERPRINT_MISSING",
                "缺少 baseFingerprint（16 位十六进制，题库内容指纹）。它是「这份补丁改的是哪一版」的锚点，" +
                    "没有它就无法判断该不该改，只能整包拒绝",
            )
        }

        val title = manifest.str("title")?.let { t ->
            if (t.isBlank() || t.length > MAX_TITLE_CHARS) {
                fail("MANIFEST_INVALID", "title 不合法（非空且不超过 $MAX_TITLE_CHARS 字）")
                null
            } else {
                t
            }
        }
        val notes = manifest.str("notes")?.take(2000).orEmpty()
        val createdAt = manifest.str("createdAt").orEmpty()

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
                fail("HASH_MISMATCH", "$path 的内容摘要与清单不符，文件可能被改动或损坏")
            }
        }
        for (fileName in byName.keys) {
            if (fileName != MANIFEST && fileName !in listed) {
                fail("FILE_NOT_LISTED", "包内有未列入清单的文件：$fileName")
            }
        }
        if (issues.isNotEmpty()) return Result.Failure(issues)

        // ---------- 操作 ----------
        val rawOps = manifest.array("operations")
        if (rawOps == null || rawOps.isEmpty()) {
            return Result.Failure(listOf(Issue("PATCH_EMPTY", "operations 为空：一个什么都没改的补丁不该发出去")))
        }
        if (rawOps.size > MAX_OPERATIONS) {
            return Result.Failure(listOf(Issue("MANIFEST_INVALID", "单个补丁最多 $MAX_OPERATIONS 条操作")))
        }

        val operations = mutableListOf<BankPatchOp>()
        val seen = mutableMapOf<String, Int>()
        rawOps.forEachIndexed { index, element ->
            val obj = element as? JsonObject ?: run {
                fail("OPERATION_INVALID", "operations[$index] 必须是对象")
                return@forEachIndexed
            }
            val kind = obj.str("op") ?: ""
            if (kind !in setOf("add", "update", "remove")) {
                fail("OPERATION_INVALID", "operations[$index] 的 op=$kind 不可识别（add / update / remove）")
                return@forEachIndexed
            }
            val qid = obj.str("questionId") ?: ""
            if (!QID.matches(qid)) {
                fail("OPERATION_INVALID", "operations[$index] 的 questionId 非法：$qid")
                return@forEachIndexed
            }
            seen[qid]?.let { earlier ->
                fail(
                    "OPERATION_CONFLICT",
                    "operations[$index] 与 operations[$earlier] 改的是同一道题 $qid；" +
                        "同一道题只能有一条操作",
                )
                return@forEachIndexed
            }
            seen[qid] = index

            val expect = obj.str("expect")?.also {
                if (!HEX16.matches(it)) {
                    fail("OPERATION_INVALID", "operations[$index] 的 expect 必须是 16 位十六进制的题目指纹")
                }
            }

            when (kind) {
                "add" -> {
                    val questionObj = obj["question"] as? JsonObject
                    if (questionObj == null) {
                        fail("OPERATION_INVALID", "operations[$index] 是 add 但没有 question")
                        return@forEachIndexed
                    }
                    val question = readQuestion(questionObj, qid, index, ::fail) ?: return@forEachIndexed
                    if (expect != null) {
                        warnings += Issue("EXPECT_IGNORED", "operations[$index] 是 add，expect 没有意义（新题在旧库里不存在）")
                    }
                    operations += BankPatchOp.Add(question)
                }

                "update" -> {
                    val set = obj["set"] as? JsonObject
                    if (set == null || set.isEmpty()) {
                        fail("OPERATION_INVALID", "operations[$index] 是 update 但没有 set（要改什么）")
                        return@forEachIndexed
                    }
                    val changes = readChanges(set, qid, index, ::fail) ?: return@forEachIndexed
                    operations += BankPatchOp.Update(questionId = qid, expect = expect, changes = changes)
                }

                else -> operations += BankPatchOp.Remove(questionId = qid, expect = expect)
            }
        }

        if (issues.isNotEmpty()) return Result.Failure(issues)
        if (operations.isEmpty()) return Result.Failure(listOf(Issue("PATCH_EMPTY", "补丁里没有任何实际操作")))

        return Result.Success(
            BankPatch(
                patchId = patchId,
                version = version,
                name = name,
                bankId = bankId,
                baseRevision = baseRevision,
                targetRevision = targetRevision,
                baseFingerprint = baseFingerprint,
                operations = operations,
                title = title,
                notes = notes,
                createdAt = createdAt,
            ),
            warnings,
        )
    }

    // ---------------------------------------------------------------- 题目解析

    private fun readQuestion(
        obj: JsonObject,
        qid: String,
        index: Int,
        fail: (String, String) -> Unit,
    ): Question? {
        val declaredId = obj.str("questionId") ?: ""
        if (declaredId != qid) {
            fail(
                "OPERATION_INVALID",
                "operations[$index] 的 questionId 与 question.questionId 不一致（$qid / $declaredId）",
            )
            return null
        }
        val type = QuestionType.fromWire(obj.str("type") ?: "")
        if (type == null) {
            fail("OPERATION_INVALID", "operations[$index] 的题型 ${obj.str("type")} 无法识别")
            return null
        }
        val prompt = obj.str("promptMarkdown").orEmpty()
        if (prompt.isBlank()) {
            fail("OPERATION_INVALID", "operations[$index] 的题干为空")
            return null
        }
        val options = readOptions(obj.array("options"), "operations[$index]", fail)
        if (type.isChoice && options == null) return null

        val question = Question(
            questionId = qid,
            type = type,
            promptMarkdown = prompt,
            options = options.orEmpty(),
            correctOptionIds = obj.array("correctOptionIds")?.mapNotNull { it.asString() }?.toSet().orEmpty(),
            answerText = obj.str("answerText"),
            explanationMarkdown = obj.str("explanationMarkdown"),
            assetIds = obj.array("assetIds")?.mapNotNull { it.asString() } ?: emptyList(),
            shuffleAllowed = (obj["shuffleAllowed"] as? JsonPrimitive)?.booleanOrNull ?: true,
            grading = Question.defaultGradingFor(type),
            tags = obj.array("tags")?.mapNotNull { it.asString() } ?: emptyList(),
            origin = ProvenanceOrigin.fromWire(obj.str("origin")),
            sourceId = obj.str("sourceId")?.takeIf { it.isNotBlank() },
            sourceLocator = obj.str("sourceLocator")?.takeIf { it.isNotBlank() },
        )
        // 新题必须自己站得住：这里就把"多选题只有一个正确答案"这类问题拦在门外
        val problems = QuestionRules.problemsOf(question)
        if (problems.isNotEmpty()) {
            fail("QUESTION_INVALID", "operations[$index] 新增的 $qid 不成立：${problems.joinToString("；")}")
            return null
        }
        return question
    }

    private fun readChanges(
        set: JsonObject,
        qid: String,
        index: Int,
        fail: (String, String) -> Unit,
    ): QuestionChanges? {
        val where = "operations[$index]"
        val allowed = setOf(
            "promptMarkdown", "options", "correctOptionIds", "answerText",
            "explanationMarkdown", "assetIds", "shuffleAllowed", "tags",
            // 出处可以改：补上一个漏写的出处是最常见的合法修复，而且它不影响内容指纹
            "sourceId", "sourceLocator", "origin",
        )
        set.keys.filter { it !in allowed }.forEach { key ->
            fail(
                "OPERATION_INVALID",
                "$where 试图修改 $key；update 只能改 ${allowed.joinToString("/")}。" +
                    "改题型或判题策略请用 remove + add —— 那会改变「什么算答对」",
            )
        }
        if (set.keys.any { it !in allowed }) return null

        if ("origin" in set) {
            val raw = set.str("origin").orEmpty()
            if (raw.isNotBlank() && ProvenanceOrigin.fromWire(raw) == ProvenanceOrigin.UNSPECIFIED) {
                // 拼错就拒绝，不猜 —— 猜错等于替作者改了一件他没说过的事
                fail("OPERATION_INVALID", "$where 的 origin=$raw 不在约定取值内（authored/adapted/imported/ai-assisted）")
            }
        }
        if ("sourceId" in set) {
            val raw = set.str("sourceId").orEmpty()
            if (raw.isNotBlank() && !QID.matches(raw)) {
                fail("OPERATION_INVALID", "$where 的 sourceId 非法：$raw")
            }
        }

        val options = if ("options" in set) readOptions(set.array("options"), where, fail) else null
        if ("options" in set && options == null) return null

        val correct = if ("correctOptionIds" in set) {
            set.array("correctOptionIds")?.mapNotNull { it.asString() }?.toSet()?.also {
                if (it.isEmpty()) fail("OPERATION_INVALID", "$where 的 correctOptionIds 不能为空数组")
            }
        } else {
            null
        }
        if (correct != null && correct.isEmpty()) return null

        return QuestionChanges(
            promptMarkdown = set.str("promptMarkdown")?.also {
                if (it.isBlank()) fail("OPERATION_INVALID", "$where 把题干改成了空的")
            },
            options = options,
            correctOptionIds = correct,
            answerText = set.str("answerText"),
            explanationMarkdown = set.str("explanationMarkdown"),
            assetIds = if ("assetIds" in set) set.array("assetIds")?.mapNotNull { it.asString() } ?: emptyList() else null,
            shuffleAllowed = (set["shuffleAllowed"] as? JsonPrimitive)?.booleanOrNull,
            tags = if ("tags" in set) set.array("tags")?.mapNotNull { it.asString() } ?: emptyList() else null,
            // 空串 = 把这处出处清掉（null 才表示"不改")
            sourceId = if ("sourceId" in set) set.str("sourceId").orEmpty() else null,
            sourceLocator = if ("sourceLocator" in set) set.str("sourceLocator").orEmpty() else null,
            origin = if ("origin" in set) ProvenanceOrigin.fromWire(set.str("origin")) else null,
        )
    }

    private fun readOptions(
        array: JsonArray?,
        where: String,
        fail: (String, String) -> Unit,
    ): List<QuestionOption>? {
        if (array == null) {
            fail("OPERATION_INVALID", "$where 缺少 options")
            return null
        }
        val out = mutableListOf<QuestionOption>()
        val seen = mutableSetOf<String>()
        array.forEach { el ->
            val obj = el as? JsonObject ?: run {
                fail("OPERATION_INVALID", "$where 的 options 里有非对象项")
                return@forEach
            }
            val id = obj.str("optionId") ?: ""
            val text = obj.str("contentMarkdown") ?: ""
            if (!QID.matches(id)) {
                fail("OPERATION_INVALID", "$where 的选项 id 非法：$id")
                return@forEach
            }
            if (text.isBlank()) {
                fail("OPERATION_INVALID", "$where 的选项 $id 文案为空")
                return@forEach
            }
            if (!seen.add(id)) {
                fail("OPERATION_INVALID", "$where 的选项 id 重复：$id")
                return@forEach
            }
            out += QuestionOption(id, text)
        }
        if (out.size < 2) {
            fail("OPERATION_INVALID", "$where 的 options 至少需要两项")
            return null
        }
        return out
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()

    private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

    private fun kotlinx.serialization.json.JsonElement.asString(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content
}
