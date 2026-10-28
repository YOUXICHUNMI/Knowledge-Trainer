package morefish.knowledge.trainer.presentation.theme

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 防手滑：`AccentInk`（当字用的主色）不许被当成底色。
 *
 * 这不是类型问题，编译器看不出来 —— 两者都是 `Color`。深色下 `AccentInk` 是浅紫、
 * 上面压的却还是 `OnAccent`（近白），结果是一块白底白字。本次改动里真的发生过一次
 * （练习页选项左侧的序号徽标），靠人眼在截图里发现。
 *
 * 所以留一条源码扫描：**会当作底色用的那几个接口**一旦出现 `AccentInk` 就报错。
 * 它抓不了所有情况（比如颜色先存进变量再当底色），但足以拦住最直白的那种写法，
 * 而且规则只有一句，看过一次就不会再犯。
 */
class AccentInkUsageTest {

    /** 这些写法后面跟的都是"一块底"，不是一行字。 */
    private val fillContexts = listOf(
        ".background(Palette.AccentInk",
        "background = Palette.AccentInk",
        "bg = Palette.AccentInk",
        "track = Palette.AccentInk",
        "containerColor = Palette.AccentInk",
        "accent = Palette.AccentInk",
    )

    @Test
    fun theInkFlavouredAccentIsNeverUsedAsAFill() {
        val root = File("src/commonMain/kotlin")
        assertTrue(root.isDirectory, "找不到源码目录：${root.absolutePath}")

        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().withIndex()
                    // 注释里提到这些写法不算（说明文字经常要举反例）
                    .filterNot { (_, line) -> line.trimStart().let { it.startsWith("//") || it.startsWith("*") } }
                    .filter { (_, line) -> fillContexts.any { line.contains(it) } }
                    .map { (index, line) -> "${file.relativeTo(root)}:${index + 1}  ${line.trim()}" }
            }
            .toList()

        assertTrue(
            offenders.isEmpty(),
            "AccentInk 是当字用的主色，压白字会看不见 —— 底色请用 Accent：\n" + offenders.joinToString("\n"),
        )
    }
}
