package morefish.knowledge.trainer.domain.model

/**
 * 媒体在数据目录里的落点。
 *
 * `assetId` 是题库作者声明的名字，`bankId` 是导入时生成的 UUID ——
 * 两者都可能带着奇怪字符，所以落盘前都要过一遍 [mediaFileId]。
 *
 * **这个函数只有一份实现**：写入方（媒体仓库）、备份自检、将来的清理工具都调它。
 * 各写一份的结局是"自检说图片都在，打开却是坏的" —— 而那种错误只有用户点开某道题
 * 才会发现，不会有人去追到路径规则上。
 */
fun mediaRelativePath(bankId: String, assetId: String): String =
    "media/${mediaFileId(bankId)}/${mediaFileId(assetId)}"

/**
 * 把标识压成安全的单段文件名。
 *
 * 不做 URL 编码：编码出来的 `%2F` 在文件系统里是一段普通字符，读的时候还得再解码，
 * 多一次往返就多一个不一致的机会。换成下划线更笨，但两边一定算得一样。
 */
fun mediaFileId(raw: String): String =
    raw.replace(UNSAFE_FILE_CHARS, "_").take(MAX_FILE_ID)

/** 与 [mediaFileId] 共用一个正则实例：它在自检里会被调用成千上万次。 */
private val UNSAFE_FILE_CHARS = Regex("[^a-zA-Z0-9_.-]")

/** 单段文件名上限。留够余量，同时不让一个畸形 assetId 撑爆路径长度限制。 */
const val MAX_FILE_ID = 80
