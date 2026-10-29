package morefish.knowledge.trainer.presentation.standard

/**
 * 应用内的题库标准说明。全部为本地静态文本 —— 不需要联网、不需要账号，
 * 也不做任何文件下载；这里只是把"标准长什么样"讲清楚，让人能判断手上一个 .qbank 是否可信。
 */
object BankStandardDocs {

    const val FORMAT_NAME = "KT-QBF 1.0"
    const val SCHEMA_VERSION = "1.0.0"

    /** 版本兼容策略：只接受 1.0.x。 */
    const val ACCEPTED_VERSION_RANGE = "1.0.x（主版本必须为 1）"

    data class Section(val title: String, val body: String, val bullets: List<String> = emptyList())

    val sections: List<Section> = listOf(
        Section(
            title = "它解决什么问题",
            body = "把「题目身份」和「题目文案」分开存：身份决定判题与历史归属，文案决定显示什么。",
            bullets = listOf(
                "换个语言翻译题目，判题记录不会丢 —— 同一个 questionId 下的作答历史是连续的。",
                "选项文本可以重写，但 optionId 不变，历史里「你选了哪个」依然可追溯。",
                "删掉的题目永不重用 ID，所以旧历史不会错误地挂到新题上。",
            ),
        ),
        Section(
            title = "包内结构",
            body = "一个 .qbank 就是一个 ZIP，没有外层目录。目录结构与校验规则一一对应。",
            bullets = listOf(
                "manifest.json —— 必需：格式标识、schemaVersion、bankId、语言、作者、以及每个文件的 sha256。",
                "questions/part-NNNN.ndjson —— 结构层：题型、optionIds、correctOptionIds、判题策略。",
                "locales/<locale>/questions.ndjson —— 文案层：题干、选项文本、解析、简答标准答案。",
                "media/index.ndjson + 资源实体 —— 可选：图片等，路径必须以 media/ 开头。",
                "enhancements/index.ndjson —— 可选：AI 或作者生成的附加资料，标 reviewed / unreviewed。",
            ),
        ),
        Section(
            title = "答案是怎么定的",
            body = "判题不靠字符串和序号猜，靠 optionId 集合。",
            bullets = listOf(
                "单选题 / 判断题：correctOptionIds 恰好一个；多选一、少选都判错。",
                "多选题：集合必须完全相等。ABC 是答案时，AB、ABD、ABCD 全错，顺序无关。",
                "简答题：与 answerText 逐字符完全一致，不做归一化 —— 空格、全半角、换行都算差异。",
                "无法判定的题（缺答案、规则冲突）标为 PENDING，不进正确率分母，不假装判对。",
            ),
        ),
        Section(
            title = "导入时的安全规则",
            body = "校验是 fail-closed：任何一条不合规，整包拒绝，绝不「修一修再放行」。",
            bullets = listOf(
                "路径穿越（../、绝对路径、盘符、NTFS 冒号）→ 整包拒绝。",
                "ZIP 炸弹：条目 ≤ 20000、单条目解压 ≤ 50 MiB、总量 ≤ 500 MiB、压缩比 ≤ 200:1。",
                "符号链接、设备文件、加密条目 → 拒绝。",
                "本地文件头与中央目录矛盾（被改过大小/CRC/条目名）→ 拒绝，防止哈希校验被绕过。",
                "manifest 里的 files 必须与 ZIP 条目双向一致；多一个、少一个都失败。",
                "内容里出现 <script>、<iframe>、javascript:、外链图片 → 拒绝。包内任何代码都不会被执行。",
            ),
        ),
        Section(
            title = "版本与兼容",
            body = "只认 $ACCEPTED_VERSION_RANGE。",
            bullets = listOf(
                "1.0.x：加可选字段，旧版本忽略未知可选字段照常读取。",
                "1.1.0：加必填字段或改语义，旧版本必须拒绝并提示升级，不允许「勉强读」。",
                "2.0.0：破坏性重构，必须配套迁移工具。",
            ),
        ),
        Section(
            title = "和其他格式的关系",
            body = "四种扩展名用途严格不同，不能互相改名使用。四种都已实现：" +
                ".qbank 与 .qbext 从导入页进，.qbpatch 从补丁页进，.qbk 从备份页进。",
            bullets = listOf(
                ".qbank —— 原始题库（本规范）：作者产出，导入后只读。",
                ".qbext —— 绑定单个 bankId 的扩展包：只补跨库关系与讲解，不改正题。",
                ".qbpatch —— 题库更新补丁：声明基于第几版、锚点逐条核对，不接受把内容往回退。",
                ".qbk —— 全量加密个人备份：题库、扩展、图谱、笔记、历史打进一个文件，换机器一键搬。",
                "旧版简单 JSON 数组 / {questions:[]} / NDJSON 不是 .qbank 的一部分，走另一条宽松导入路径。",
            ),
        ),
    )

    /**
     * 导入页显示的"当前实现到哪一步"，避免把计划当已完成，也避免反过来：
     * 把已经做完的说成"未实现"（这条以前真的发生过：媒体渲染与三种包格式都在这一句里被谎报）。
     * 与 [morefish.knowledge.trainer.infrastructure.importing.PackageFormat] 的一致性由
     * `BankStandardDocsTest` 盯着，清单过期即失败。
     */
    val implementationNotes: List<String> = listOf(
        "已实现：.qbank 严格校验与导入（manifest、Schema、哈希、多语言覆盖、媒体类型检查）。",
        "已实现：单选 / 多选 / 判断 / 简答四种题型的离线判题 —— 不需要联网，也不需要 AI。",
        "已实现：题内附图 —— 练习页与复核页按需从媒体库解码并画出来；取不到就显示占位，不打断作答。",
        "已实现：题库制作工具链（打包 / 校验 / 检视 / 安全解包）与可发布给 AI 的制包 Skill。",
        "已实现：.qbext 扩展包 —— 按 bankId 绑定，只补跨库关系与讲解；目标库不在本机时关系保留，等它回来。",
        "已实现：.qbpatch 更新补丁 —— 基于第几版、锚点逐条核对、版本不符拒绝回退；作者改答案后原作答历史不动。",
        "已实现：.qbk 全量加密备份与恢复 —— AES-256-GCM，整机数据一个文件，导入先验证后提交。",
        "尚未实现：包内「增强资料」（enhancements）在练习里使用 —— 现在只做结构与审核状态校验，" +
            "导入时会明确告诉你包里有没有、有没有审核过。",
    )
}
