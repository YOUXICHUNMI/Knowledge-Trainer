package morefish.knowledge.trainer.domain.patch

/**
 * 题库修订日志的一条记录。
 *
 * 为什么单独存一份，而不是从题库文件里推：
 *
 * 题库只保存"现在长什么样"，推不出"以前是什么样、什么时候变的、为什么变"。
 * 而当作者改了一道题的答案之后，用户最需要的恰恰是后面这半句 ——
 * 他已经练过那道题了，得知道自己当时的判断建立在哪个版本上。
 *
 * 这里只记**改动摘要**，不存旧题目全文：修订日志的用途是"回溯发生了什么"，
 * 不是"回滚到旧版本"。真要回滚应该重新安装旧包，那才是干净的做法。
 */
data class BankRevision(
    /** 更新之后题库处于第几版。 */
    val revision: Int,
    val patchId: String,
    val name: String,
    val atMillis: Long,
    val added: Int,
    val updated: Int,
    val removed: Int,
    /** 其中"什么算答对"变了的题号 —— 历史复核要按它来找。 */
    val answerChangedIds: List<String>,
    /** 应用时这些题已经有多少条历史记录。0 表示当时还没人练过，也就没有影响。 */
    val affectedAttempts: Int,
    val note: String = "",
) {
    val total: Int get() = added + updated + removed
    val touchedHistory: Boolean get() = answerChangedIds.isNotEmpty() && affectedAttempts > 0
}
