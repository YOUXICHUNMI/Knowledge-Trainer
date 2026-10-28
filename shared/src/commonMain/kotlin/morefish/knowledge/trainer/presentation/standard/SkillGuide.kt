package morefish.knowledge.trainer.presentation.standard

/**
 * 「怎么把 Skill 交给 AI 用」的说明与可复制文本。
 *
 * 这里刻意不内嵌整套规范与工具源码：把它们塞进应用会让 APK 变成"另一份规范副本"，
 * 两份一改就分叉。应用只负责告诉用户 **Skill 在哪里、必须整目录给、缺了会怎样**。
 */
object SkillGuide {

    const val SKILL_DIR = "tools/qbank-authoring-skill/"
    const val SKILL_MD = "${SKILL_DIR}SKILL.md"

    data class Bundle(val path: String, val purpose: String, val missingConsequence: String)

    val bundle: List<Bundle> = listOf(
        Bundle(
            path = "SKILL.md",
            purpose = "入口说明：十条强制规则、工作流、交互示例、交付摘要格式",
            missingConsequence = "AI 只能凭印象猜字段名，写出来的包通常连 Schema 都过不了",
        ),
        Bundle(
            path = "references/KT-QBF-1.0.md",
            purpose = "规范全文：目录结构、字段规则、答案定义、安全规则、错误码表",
            missingConsequence = "AI 不知道 optionId 与显示序号的区别，判题语义会写错",
        ),
        Bundle(
            path = "references/schema-*.json",
            purpose = "5 个 JSON Schema：字段级约束，多一个字段就失败",
            missingConsequence = "AI 会加自创字段（如 difficulty），包被 additionalProperties 拒绝",
        ),
        Bundle(
            path = "scripts/build_qbank.py",
            purpose = "把 authoring 目录打成真 ZIP 包，自动重算 files 哈希",
            missingConsequence = "AI 可能只把后缀改成 .qbank —— 那不是包，导入必然失败",
        ),
        Bundle(
            path = "scripts/validate_qbank.py",
            purpose = "validate / inspect / unpack / digest 四个子命令",
            missingConsequence = "AI 没能力自证「已通过检查」，只能声称，不可信",
        ),
        Bundle(
            path = "scripts/tests/run_bad_packages.py",
            purpose = "40 条坏包回归，确认改坏了会 fail-closed",
            missingConsequence = "无法验证 AI 改过的工具是否还挡得住路径穿越和炸弹",
        ),
        Bundle(
            path = "examples/",
            purpose = "最小样例 + 综合样例（双语、LaTeX、真实图片、增强资料）+ 已打好的真包",
            missingConsequence = "AI 没有可对照的正确写法，容易自创结构",
        ),
    )

    /** 用户可以直接复制粘贴给 AI Skill 的一段话。 */
    val promptTemplate: String = buildString {
        appendLine("我要给 Knowledge Trainer 做一个 KT-QBF 1.0 题库包（.qbank）。")
        appendLine()
        appendLine("规则：")
        appendLine("1. 先读 skills/qbank-authoring/SKILL.md，再读 references/KT-QBF-1.0.md 和 references/ 下全部 schema。")
        appendLine("2. 我的资料在 [换成你的笔记 / 文件]。只使用原文能确认的知识点；原文没写答案的题，不要编答案。")
        appendLine("3. 语言 [简体中文]；题型 [可以客观确认的单选 / 多选分别生成原生题型]；保留 Markdown 和 LaTeX。")
        appendLine("4. 作者署名 [你的昵称]，公开邮箱 [你的公开邮箱，不想公开就写 none]。")
        appendLine("5. 先把歧义列出来让我核对，不要边猜边做。")
        appendLine("6. 核对完再生成 authoring 目录，用 scripts/build_qbank.py 真打包，用 scripts/validate_qbank.py 校验。")
        appendLine("7. 最后给我导入前摘要：题数、各题型数量、语言覆盖率、缺答案或歧义数、媒体数、校验结果、未解决问题。")
        appendLine()
        appendLine("不要调用云端收费接口。如果当前环境不能写文件或不能运行 Python，")
        appendLine("请直接说「未生成真实包 / 未验证」，并把源文件全文给我，我自己在本地打包。")
    }

    /** 用户拿到包之后，如何在没有 Python 的环境里自己复核。 */
    val verifyHints: List<String> = listOf(
        "命令行：python tools/qbank/validate_qbank.py validate your.qbank",
        "只想看结构不解压：python tools/qbank/validate_qbank.py inspect your.qbank",
        "核对规范化 digest：python tools/qbank/validate_qbank.py digest your.qbank",
        "把包安全解出来看原文：python tools/qbank/validate_qbank.py unpack your.qbank ./out",
        "导入 App：首页 → 导入题库 → 「选择 .qbank 文件」（桌面端已接入文件选择器；其他平台会明确提示不可用，不假装支持）。",
        "校验器 fail-closed：报错就说明这个包真的不能用，不要尝试「忽略错误继续导入」。",
    )
}
