package morefish.knowledge.trainer.presentation.standard

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 「把哪些文件交给 AI」这份清单必须与仓库里的 Skill 目录对得上。
 *
 * 这条清单原本漏了 `scripts/ktqbf/` —— 而 `build_qbank.py` 与 `validate_qbank.py` 各自只有一行
 * `from ktqbf.* import main`。照着清单挑文件给 AI 的人，会拿到两个一跑就 ModuleNotFoundError 的壳，
 * 现象却是"AI 说打包失败"，看不出是清单漏了东西。
 *
 * 另一头是路径本身：给用户复制的命令里曾写着 `tools/qbank/validate_qbank.py`，
 * 而 Skill 拿出去之后是 `scripts/validate_qbank.py`；`promptTemplate` 里还留着更早的
 * `skills/qbank-authoring/SKILL.md`。用户按这些路径执行，只会得到"文件不存在"。
 *
 * 找不到仓库就跳过：这守的是"文档与目录一致"，不该因为换个构建目录让整套用例变红。
 */
class SkillGuideBundleTest {

    private fun repoFile(relative: String): File? {
        // jvmTest 的工作目录是模块目录（shared/），仓库根在上几级之一
        val candidates = listOf(
            File(relative),
            File("../$relative"),
            File("../../$relative"),
        )
        return candidates.firstOrNull { it.exists() }?.absoluteFile
    }

    private val skillRoot: File? by lazy { repoFile(SkillGuide.SKILL_DIR.removeSuffix("/")) }

    private fun skillRootOrSkip(): File? {
        val root = skillRoot
        if (root == null) {
            println("跳过：找不到 ${SkillGuide.SKILL_DIR}（这个测试需要在仓库内运行）")
        }
        return root
    }

    @Test
    fun everyListedPathExistsInTheSkillDirectory() {
        val root = skillRootOrSkip() ?: return
        SkillGuide.bundle.forEach { entry ->
            val path = entry.path
            val resolved = when {
                path.endsWith("/") -> File(root, path).isDirectory
                path.contains('*') -> {
                    // schema-*.json 这种：按前缀与后缀在目录里找
                    val dir = File(root, path.substringBeforeLast('/'))
                    val pattern = path.substringAfterLast('/')
                    val prefix = pattern.substringBefore('*')
                    val suffix = pattern.substringAfter('*')
                    dir.listFiles()?.any { it.name.startsWith(prefix) && it.name.endsWith(suffix) } == true
                }
                else -> File(root, path).isFile
            }
            assertTrue(
                resolved,
                "清单里的 ${path} 在 ${root.path} 下找不到 —— 目录改了名字、或这一条是凭空写的。\n" +
                    "清单是给人照着挑文件的，写错一条等于让人漏给一个文件。",
            )
        }
    }

    /** 两个入口脚本的实际实现在 `scripts/ktqbf/` 里，清单必须点到它。 */
    @Test
    fun theImplementationBehindTheEntryScriptsIsListed() {
        val root = skillRootOrSkip() ?: return
        listOf("scripts/build_qbank.py", "scripts/validate_qbank.py").forEach { entry ->
            val script = File(root, entry)
            if (!script.isFile) return@forEach
            val importsModule = script.readText().contains("from ktqbf.")
            if (!importsModule) return@forEach
            assertTrue(
                SkillGuide.bundle.any { it.path == "scripts/ktqbf/" },
                "$entry 依赖 ktqbf 包，但清单没有列出 scripts/ktqbf/ —— " +
                    "照着清单给文件的人会得到一个跑不起来的入口脚本",
            )
        }
    }

    /** 文案里出现的 `.py` 路径，都要能在 Skill 目录里找到。 */
    @Test
    fun everyScriptPathInTheCopyExists() {
        val root = skillRootOrSkip() ?: return
        val copy = (listOf(SkillGuide.promptTemplate) + SkillGuide.verifyHints).joinToString("\n")
        val pathPattern = Regex("""[A-Za-z0-9_./-]+\.py""")
        val claimed = pathPattern.findAll(copy).map { it.value }.toSet()
        assertTrue(claimed.isNotEmpty(), "文案里应当至少出现一个脚本路径，否则这条守卫是空转")
        claimed.forEach { relative ->
            // 文案里可能写出仓库内路径（tools/qbank/...），但用户手上只有 Skill 目录
            assertTrue(
                File(root, relative).isFile,
                "文案里的 `$relative` 在 Skill 目录里不存在 —— 用户复制去执行只会得到" +
                    "「找不到文件」。Skill 目录内的正确写法是 scripts/xxx.py",
            )
        }
    }

    /** 每条都要说清"缺了会怎样"，否则清单只是一份文件名列表。 */
    @Test
    fun everyEntryExplainsWhatBreaksWithoutIt() {
        SkillGuide.bundle.forEach { entry ->
            assertTrue(entry.purpose.isNotBlank(), "${entry.path} 没写用途")
            assertTrue(
                entry.missingConsequence.isNotBlank(),
                "${entry.path} 没写缺了会怎样 —— 这一栏才是让人愿意整目录给的理由",
            )
        }
    }
}
