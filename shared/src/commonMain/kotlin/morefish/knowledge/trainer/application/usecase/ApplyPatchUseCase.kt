package morefish.knowledge.trainer.application.usecase

import morefish.knowledge.trainer.application.port.AttemptRepository
import morefish.knowledge.trainer.application.port.BankRepository
import morefish.knowledge.trainer.application.port.RevisionRepository
import morefish.knowledge.trainer.application.port.WallClock
import morefish.knowledge.trainer.domain.model.QuestionBank
import morefish.knowledge.trainer.domain.patch.BankPatch
import morefish.knowledge.trainer.domain.patch.BankPatcher
import morefish.knowledge.trainer.domain.patch.BankRevision
import morefish.knowledge.trainer.domain.patch.PatchApply
import morefish.knowledge.trainer.domain.patch.PatchChangeKind
import morefish.knowledge.trainer.domain.patch.PatchConflict
import morefish.knowledge.trainer.domain.patch.PatchReport
import morefish.knowledge.trainer.infrastructure.archive.DeflateCodec
import morefish.knowledge.trainer.infrastructure.archive.PureDeflateCodec
import morefish.knowledge.trainer.infrastructure.importing.QbpatchImporter

/**
 * 读补丁之后得到的"待确认状态"。
 *
 * 读补丁与应用补丁**必须分成两步**：这一步只算、不写任何东西。
 * 用户在点"应用"之前要能看见会改哪几道题、哪几道题的答案变了、
 * 那些题自己练过几次 —— 这些数字拿不出来，"更新"就变成了闭眼按按钮。
 */
sealed interface PatchIntake {
    data class Ready(
        val patch: BankPatch,
        val bank: QuestionBank,
        val plan: PatchApply,
        /**
         * 答案变更的那几道题上已经有几条历史记录。
         *
         * 只是"这些题你练过几次"，不是"这些记录会变" ——
         * 历史一条都不会改，这个数字是给"要不要去看看旧记录"提供依据的。
         */
        val affectedAttempts: Int,
        val warnings: List<String>,
    ) : PatchIntake

    /** 补丁文件本身不合法。 */
    data class Rejected(val issues: List<QbpatchImporter.Issue>) : PatchIntake

    /** 补丁声明的题库不在这台机器上。 */
    data class BankMissing(val patch: BankPatch) : PatchIntake
}

sealed interface PatchOutcome {
    data class Applied(val bank: QuestionBank, val report: PatchReport, val revision: BankRevision) : PatchOutcome
    data class NothingToDo(val reason: String) : PatchOutcome
    data class Refused(val conflicts: List<PatchConflict>) : PatchOutcome
    data class Failed(val message: String) : PatchOutcome
}

/**
 * 应用 `.qbpatch`。
 *
 * 三条纪律：
 *
 * 1. **预览与落盘分开**：`intake` 只读，`apply` 才写。
 * 2. **应用时重算**：`apply` 不相信预览时的结论，重新定位题库、重新应用一遍。
 *    用户可能在两步之间装了别的补丁 —— 那时候按旧结论写盘会把内容改坏。
 * 3. **历史一条不动**：这个用例只碰题库与修订日志。作答记录属于历史，
 *    补丁没有资格重写它；"要不要复核"是用户的决定，不是更新的副作用。
 */
class ApplyPatchUseCase(
    private val banks: BankRepository,
    private val revisions: RevisionRepository,
    private val attempts: AttemptRepository,
    private val clock: WallClock = WallClock.System,
) {

    /**
     * 读补丁并定位目标题库。
     *
     * 定位顺序：**先按内容指纹，再按 bankId**。
     * 指纹对得上说明"本地这份就是补丁要改的那份"，这是比 ID 更硬的证据；
     * 早期版本导入 `.qbank` 时会给题库重新生成 UUID，那些库的 ID 与作者声明的不一样，
     * 只按 ID 找会让老用户永远收不到更新。
     */
    suspend fun intake(bytes: ByteArray, codec: DeflateCodec = PureDeflateCodec): PatchIntake {
        val parsed = when (val result = QbpatchImporter.parse(bytes, codec)) {
            is QbpatchImporter.Result.Failure -> return PatchIntake.Rejected(result.issues)
            is QbpatchImporter.Result.Success -> result
        }
        val patch = parsed.patch
        val bank = locate(patch) ?: return PatchIntake.BankMissing(patch)
        val plan = BankPatcher.apply(bank, patch)
        val affected = if (plan is PatchApply.Ok) countAffectedAttempts(bank.bankId, plan.report) else 0
        return PatchIntake.Ready(
            patch = patch,
            bank = bank,
            plan = plan,
            affectedAttempts = affected,
            warnings = parsed.warnings.map { it.message },
        )
    }

    suspend fun apply(patch: BankPatch): PatchOutcome {
        val bank = locate(patch) ?: return PatchOutcome.Failed("这台机器上找不到这个补丁要更新的题库")
        return when (val plan = BankPatcher.apply(bank, patch)) {
            is PatchApply.NoOp -> PatchOutcome.NothingToDo(plan.reason)

            is PatchApply.Outdated -> PatchOutcome.Refused(
                listOf(
                    PatchConflict(
                        "PATCH_OUTDATED",
                        "本地题库已经是第 ${plan.bank.contentRevision} 版，这个补丁只更新到第 ${plan.patchTarget} 版。" +
                            "应用它等于把内容往回退，所以不会这么做。",
                    ),
                ),
            )

            is PatchApply.Failed -> PatchOutcome.Refused(plan.conflicts)

            is PatchApply.Ok -> {
                banks.save(plan.bank)
                val revision = BankRevision(
                    revision = plan.report.afterRevision,
                    patchId = patch.patchId,
                    name = patch.name,
                    atMillis = clock.nowMillis(),
                    added = plan.report.added,
                    updated = plan.report.updated,
                    removed = plan.report.removed,
                    answerChangedIds = plan.report.entries
                        .filter { it.kind == PatchChangeKind.ANSWER }
                        .map { it.questionId },
                    affectedAttempts = countAffectedAttempts(plan.bank.bankId, plan.report),
                    note = patch.notes,
                )
                revisions.append(plan.bank.bankId, revision)
                PatchOutcome.Applied(plan.bank, plan.report, revision)
            }
        }
    }

    /** 内容优先、身份兜底的定位。找不到返回 null，不猜"大概是这个"。 */
    private suspend fun locate(patch: BankPatch): QuestionBank? {
        banks.findByFingerprint(patch.baseFingerprint)?.let { return it }
        val byId = banks.find(patch.bankId) ?: return null
        // ID 对上了但内容对不上：只有在这个库确实比补丁更旧/同步时才算候选，
        // 其余情况交给 BankPatcher 给出诚实结论，而不是在这里静默返回一个错的库
        return byId
    }

    private suspend fun countAffectedAttempts(bankId: String, report: PatchReport): Int {
        val changed = report.entries
            .filter { it.kind == PatchChangeKind.ANSWER }
            .mapTo(mutableSetOf()) { it.questionId }
        if (changed.isEmpty()) return 0
        return attempts.all().count { it.bankId == bankId && it.questionId in changed }
    }
}
