package morefish.knowledge.trainer.infrastructure.importing

import morefish.knowledge.trainer.infrastructure.archive.Sha256

/**
 * 整包内容摘要（KT-QBF §4.2）。
 *
 * 它回答一个问题：**这份包还是作者发出来的那一份吗**。
 *
 * 与逐文件 `sha256` 的区别：逐文件哈希证明"包内自洽"（清单写的是什么，条目就是什么），
 * 但两份内容相同、只是清单顺序或打包方式不同的包会有两组不同的文件哈希。
 * 这里的摘要只依赖**逻辑内容**（路径 + 内容哈希，排序后拼接），所以：
 *
 * - 同一份内容用不同 ZIP 工具重打 → 摘要相同（能认出"这是同一个包"）；
 * - 内容被改过一个字节 → 摘要不同（能认出"这不是同一个包"）。
 *
 * 它**不是签名**。没有密钥，任何人改完内容都能重算出一个自洽的摘要。
 * 界面上必须这么写，不能让人以为它证明了作者身份。
 *
 * 摘要**不覆盖** `sources` / `tags` / `description`：那些是可改的说明性字段，
 * 算进去会让"改一句说明就换一个摘要"，而摘要要回答的是"这是不是同一份题"。
 * 它**覆盖** `author` 与 `license`，因为那是作者对读者的承诺。
 *
 * 输入格式一旦发布就不能改 —— 改了等于让所有旧包的摘要失效。
 * 与 Python 侧 `ktqbf/provenance.py` 的 `digest_input` 逐字节一致，由 parity 测试守住。
 */
object PackageDigest {

    const val VERSION = "kt-qbf-digest-1"

    /** 参与摘要的清单字段。刻意不含 sources / tags / description。 */
    data class Identity(
        val format: String,
        val schemaVersion: String,
        val bankId: String,
        val contentRevision: Int,
        val sourceLocale: String,
        val supportedLocales: List<String>,
        val title: String,
        val authorName: String,
        val license: String,
    )

    fun of(identity: Identity, files: Map<String, ByteArray>): String {
        val text = buildString {
            append(VERSION).append('\n')
            append("format=").append(escape(identity.format)).append('\n')
            append("schemaVersion=").append(escape(identity.schemaVersion)).append('\n')
            append("bankId=").append(escape(identity.bankId)).append('\n')
            append("contentRevision=").append(identity.contentRevision).append('\n')
            append("sourceLocale=").append(escape(identity.sourceLocale)).append('\n')
            append("supportedLocales=")
            identity.supportedLocales.forEachIndexed { index, locale ->
                if (index > 0) append(',')
                append(escape(locale))
            }
            append('\n')
            append("title=").append(escape(identity.title)).append('\n')
            append("author=").append(escape(identity.authorName)).append('\n')
            append("license=").append(escape(identity.license)).append('\n')
            files.keys.sorted().forEach { path ->
                append(path).append(' ').append(Sha256.hex(files.getValue(path))).append('\n')
            }
        }
        return Sha256.hex(text.encodeToByteArray())
    }

    /**
     * 压成单行。反斜杠必须**先**转义，否则后面产出的 `\n` 会被自己再转一遍。
     * 顺序与 Python 侧完全一致（`\\` → `\r` → `\n` → `\t`）。
     */
    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
        .replace("\t", "\\t")
}
