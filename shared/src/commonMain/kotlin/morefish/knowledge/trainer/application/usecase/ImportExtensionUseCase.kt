package morefish.knowledge.trainer.application.usecase

import morefish.knowledge.trainer.application.port.ExtensionRepository
import morefish.knowledge.trainer.application.port.WallClock
import morefish.knowledge.trainer.domain.extension.ExtensionPack
import morefish.knowledge.trainer.domain.extension.compareVersions
import morefish.knowledge.trainer.infrastructure.archive.DeflateCodec
import morefish.knowledge.trainer.infrastructure.archive.PureDeflateCodec
import morefish.knowledge.trainer.infrastructure.importing.PackageFormat
import morefish.knowledge.trainer.infrastructure.importing.PackageProbe
import morefish.knowledge.trainer.infrastructure.importing.QbextImporter

sealed interface ExtensionOutcome {
    /** 装好了。[replaced] 非空表示这次是版本升级，顶掉了旧包。 */
    data class Installed(
        val pack: ExtensionPack,
        val replaced: ExtensionPack?,
        val warnings: List<String>,
    ) : ExtensionOutcome

    /** 同版本重复安装：不静默替换，也不假装装了一个新的。 */
    data class Duplicate(val existing: ExtensionPack) : ExtensionOutcome

    data class Failed(val message: String) : ExtensionOutcome
}

/**
 * 扩展包安装编排：解析 → 校验 → 版本判定 → 落盘。
 *
 * 刻意**不检查题库在不在**：扩展包可以先到，题库后到。
 * "装上但还没生效"是一个正常状态，不是错误 —— 这正是跨库关系能留待恢复的方式。
 */
class ImportExtensionUseCase(
    private val extensions: ExtensionRepository,
    private val clock: WallClock = WallClock.System,
    private val deflate: DeflateCodec = PureDeflateCodec,
) {

    fun looksLikeQbext(bytes: ByteArray): Boolean =
        PackageProbe.formatOf(bytes, deflate) == PackageFormat.QBEXT

    suspend fun install(bytes: ByteArray, sourceLabel: String): ExtensionOutcome {
        val parsed = when (val result = QbextImporter.parse(bytes, deflate)) {
            is QbextImporter.Result.Failure -> return ExtensionOutcome.Failed(
                result.issues.joinToString("；") { it.message },
            )

            is QbextImporter.Result.Success -> result
        }

        val incoming = parsed.pack.copy(
            importedAtMillis = clock.nowMillis(),
            sourceLabel = sourceLabel,
        )
        val existing = extensions.all().firstOrNull { it.packageId == incoming.packageId }

        if (existing != null) {
            val order = compareVersions(incoming.version, existing.version)
            if (order == 0) return ExtensionOutcome.Duplicate(existing)
            if (order < 0) {
                return ExtensionOutcome.Failed(
                    "已安装 ${existing.version}，这个包是 ${incoming.version}；" +
                        "需要降级请先移除已安装的版本（软件不替你覆盖更新的包）",
                )
            }
        }

        extensions.save(incoming)
        return ExtensionOutcome.Installed(
            pack = incoming,
            replaced = existing,
            warnings = parsed.warnings.map { it.message },
        )
    }

    suspend fun remove(packageId: String) {
        if (packageId.isBlank()) return
        extensions.remove(packageId)
    }
}
