package morefish.knowledge.trainer.infrastructure

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import morefish.knowledge.trainer.domain.model.GradingSpec

/**
 * GradingSpec 的线格式编解码。
 *
 * exact-set / strict-text 是无参数策略，只用 wireName 字符串；
 * keywords / ordering 带参数，参数以 JSON 字符串随 `gradingParams` 字段携带。
 * 解码未知/损坏参数一律返回 null，由调用方回退到题型默认 —— 不猜、不抛异常。
 */
object GradingWire {

    private val json = Json { ignoreUnknownKeys = true }

    /** 领域规格 → (wireName, paramsJson)。无参数策略 paramsJson 为 null。 */
    fun paramsOf(spec: GradingSpec): String? = when (spec) {
        is GradingSpec.Keywords -> buildJsonObject {
            put("required", buildJsonArray { spec.required.forEach { add(JsonPrimitive(it)) } })
            put("forbidden", buildJsonArray { spec.forbidden.forEach { add(JsonPrimitive(it)) } })
        }.toString()

        is GradingSpec.Ordering -> buildJsonObject {
            put("canonical", buildJsonArray { spec.canonical.forEach { add(JsonPrimitive(it)) } })
            put("alternatives", buildJsonArray {
                spec.alternatives.forEach { alt ->
                    add(buildJsonArray { alt.forEach { add(JsonPrimitive(it)) } })
                }
            })
        }.toString()

        else -> null
    }

    /** (wireName, paramsJson) → 领域规格；解析不出参数时回退 toWire 的无参形态。 */
    fun parse(wire: String?, paramsJson: String?): GradingSpec? {
        val base = GradingSpec.fromWire(wire) ?: return null
        if (paramsJson.isNullOrBlank()) return base
        val obj = runCatching { json.parseToJsonElement(paramsJson).jsonObject }.getOrNull() ?: return base
        return when (base) {
            is GradingSpec.Keywords -> GradingSpec.Keywords(
                required = obj.stringList("required") ?: emptyList(),
                forbidden = obj.stringList("forbidden") ?: emptyList(),
            )

            is GradingSpec.Ordering -> GradingSpec.Ordering(
                canonical = obj.stringList("canonical") ?: emptyList(),
                alternatives = obj["alternatives"]?.jsonArray
                    ?.mapNotNull { alt -> (alt as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } }
                    ?: emptyList(),
            )

            else -> base
        }
    }

    /** 从导入 JSON 的 grading 对象（或顶层便捷字段）解析关键词规则。 */
    fun keywordsFrom(obj: JsonObject): GradingSpec.Keywords? {
        val required = obj.stringList("required")
            ?: obj.stringList("requiredKeywords")
            ?: obj.stringList("keywords")
        val forbidden = obj.stringList("forbidden") ?: obj.stringList("forbiddenKeywords")
        if (required == null && forbidden == null) return null
        return GradingSpec.Keywords(required = required ?: emptyList(), forbidden = forbidden ?: emptyList())
    }

    /** 从导入 JSON 的 grading 对象解析等价次序。 */
    fun alternativesFrom(obj: JsonObject): List<List<String>> =
        obj["alternatives"]?.jsonArray
            ?.mapNotNull { alt -> (alt as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } }
            ?: emptyList()

    private fun JsonObject.stringList(key: String): List<String>? =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) }
}
