package morefish.knowledge.trainer.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 分层验收：依赖只能指向内层。
 *
 * 这不是"风格检查"，而是这套代码能继续长大的前提 —— 一旦 `domain` 认识了 Compose、
 * 或者某个用例直接 new 出具体仓储，后面换存储、换平台、单测替身都得先拆一遍。
 *
 * 检查对象是 `shared/src/commonMain` 的真实源码，按**声明层级**判断：
 *
 *   presentation → application → domain
 *                     ↖ infrastructure（实现内层定义的端口）
 *
 * `infrastructure` 允许依赖 `application`/`domain`（它实现内层定义的接口）；
 * 反过来不允许。
 *
 * 已经存在的越层依赖不假装不存在：下面的登记表逐条写明原因，
 * 测试要求登记项**仍然真实存在**，修好之后必须把登记删掉 —— 否则清单会变成谎话。
 */
class ArchitectureTest {

    private val repoRoot: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile && File(it, "shared/src/commonMain").isDirectory }
        ?: fail("找不到仓库根目录（user.dir=${System.getProperty("user.dir")}）")

    /** 包根：分层判断以它下面的第一段目录为准（domain / application / infrastructure / presentation）。 */
    private val commonMain = File(
        repoRoot,
        "shared/src/commonMain/kotlin/morefish/knowledge/trainer",
    )

    /** 已登记的 application → infrastructure 依赖。修掉一条就删一行。 */
    private val applicationInfrastructureDebt = mapOf(
        "application/usecase/ImportBankUseCase.kt" to
            "直接构造 JsonQuestionImporter / QbankImporter 与 PureDeflateCodec；" +
            "要改成端口注入，得先给 .qbank 解析结果定义 application 侧的返回类型",
        "application/usecase/ImportExtensionUseCase.kt" to
            "直接调用 QbextImporter 与 PackageProbe；同上，扩展包解析还缺一个端口",
        "application/usecase/ApplyPatchUseCase.kt" to
            "直接调用 QbpatchImporter 与 PureDeflateCodec；补丁解析还缺一个端口",
        "application/usecase/BackupUseCases.kt" to
            "直接使用 BackupArchive / BackupBundle 等备份容器类型；备份容器尚未抽象成端口",
    )

    /** 允许接触 infrastructure 的 presentation 文件：组合根。 */
    private val compositionRoots = mapOf(
        "presentation/AppController.kt" to
            "组合根：AppDependencies 在这里挑选具体存储 / AI / 媒体实现并注入，" +
            "其余界面文件只认 application 与 domain",
    )

    // ---------------------------------------------------------------- 基础工具

    private data class Imported(val path: String, val line: Int, val target: String) {
        override fun toString(): String = "$path:$line  →  $target"
    }

    private fun kotlinSources(): List<File> = commonMain.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .sortedBy { it.invariantSeparatorsPath }
        .toList()

    private fun relative(file: File): String = file.relativeTo(commonMain).invariantSeparatorsPath

    private fun importsOf(file: File): List<Imported> = file.readLines().mapIndexedNotNull { index, raw ->
        val text = raw.trim()
        if (!text.startsWith("import ")) return@mapIndexedNotNull null
        val target = text.removePrefix("import ").substringBefore(" as ").trim()
        Imported(relative(file), index + 1, target)
    }

    private fun layerOf(path: String): String = path.substringBefore('/')

    private fun violates(import: Imported, forbidden: Set<String>): Boolean {
        val body = import.target.removePrefix("morefish.knowledge.trainer.")
        return forbidden.any { body == it || body.startsWith("$it.") }
    }

    // ---------------------------------------------------------------- 断言

    @Test
    fun `domain 不依赖任何外层（零容忍）`() {
        val hits = kotlinSources()
            .filter { layerOf(relative(it)) == "domain" }
            .flatMap { importsOf(it) }
            .filter { violates(it, setOf("application", "infrastructure", "presentation")) }
        assertTrue(
            hits.isEmpty(),
            "domain 是纯规则层，不能认识外层的任何类型。越层引用：\n" +
                hits.joinToString("\n") { "  • $it" },
        )
    }

    @Test
    fun `application 不依赖 presentation`() {
        val hits = kotlinSources()
            .filter { layerOf(relative(it)) == "application" }
            .flatMap { importsOf(it) }
            .filter { violates(it, setOf("presentation")) }
        assertTrue(
            hits.isEmpty(),
            "用例层不能依赖界面（否则用例没法在没有 UI 的环境里跑）。越层引用：\n" +
                hits.joinToString("\n") { "  • $it" },
        )
    }

    @Test
    fun `application 对 infrastructure 的依赖只能出现在登记表里`() {
        val hits = kotlinSources()
            .filter { layerOf(relative(it)) == "application" }
            .flatMap { importsOf(it) }
            .filter { violates(it, setOf("infrastructure")) }
            .filterNot { it.path in applicationInfrastructureDebt }

        assertTrue(
            hits.isEmpty(),
            "新增的用例 → 基础设施依赖必须先抽象成 application/port 里的端口：\n" +
                hits.joinToString("\n") { "  • $it" } +
                "\n（如果确实是一次有意的技术债，请在本测试的 applicationInfrastructureDebt 里登记原因。）",
        )
    }

    @Test
    fun `presentation 只有组合根可以接触 infrastructure`() {
        val hits = kotlinSources()
            .filter { layerOf(relative(it)) == "presentation" }
            .flatMap { importsOf(it) }
            .filter { violates(it, setOf("infrastructure")) }
            .filterNot { it.path in compositionRoots }

        assertTrue(
            hits.isEmpty(),
            "界面只该认识 domain 与 application：具体存储 / 解析 / 网络实现一律由组合根注入。\n" +
                "越层引用：\n" + hits.joinToString("\n") { "  • $it" },
        )
    }

    @Test
    fun `登记的技术债必须仍然真实存在`() {
        val byFile = kotlinSources().associateBy { relative(it) }
        val stale = mutableListOf<String>()
        (applicationInfrastructureDebt.keys + compositionRoots.keys).forEach { path ->
            val file = byFile[path]
            if (file == null) {
                stale += "$path（文件已不存在）"
                return@forEach
            }
            val forbidden = setOf("infrastructure")
            if (importsOf(file).none { violates(it, forbidden) }) {
                stale += "$path（已经不再越层）"
            }
        }
        assertTrue(
            stale.isEmpty(),
            "这些登记项已经过期，请从测试的登记表里删掉，别让清单说谎：\n" +
                stale.joinToString("\n") { "  • $it" },
        )
    }

    @Test
    fun `commonMain 不出现 JVM 或 Android 专属 API`() {
        val hits = kotlinSources().flatMap { importsOf(it) }
            .filter { import ->
                listOf("java.", "javax.", "android.", "sun.").any { prefix ->
                    import.target.startsWith(prefix)
                }
            }
        assertTrue(
            hits.isEmpty(),
            "共享层要能编译到 iOS / JS / Wasm，JVM 与 Android 专属类型必须留在平台源集：\n" +
                hits.joinToString("\n") { "  • $it" },
        )
    }

    // ---------------------------------------------------------------- 拆分之后的边界

    /**
     * 功能包：`presentation` 下"装着 `*Controller.kt`"的那一层目录。
     *
     * 由目录内容算出来，而不是在测试里写死一份清单 —— 第十一项拆完后补做笔记那一刀，
     * 正是因为"名单"漏了它。下一个功能包因此会自动进入检查；代价是路径一旦算错就会
     * 安静地全绿，所以下面的断言里另有一条"至少认得出 12 个包"。
     */
    private val featurePackages: List<String> = File(commonMain, "presentation").listFiles().orEmpty()
        .filter { dir ->
            dir.isDirectory && dir.walkTopDown().any { it.isFile && it.name.endsWith("Controller.kt") }
        }
        .map { "presentation/${it.name}" }
        .sorted()

    private fun packageOf(path: String): String? =
        featurePackages.firstOrNull { path.startsWith("$it/") }

    private fun featureSources(): List<Pair<String, File>> =
        kotlinSources().mapNotNull { file -> packageOf(relative(file))?.let { it to file } }

    /**
     * 三条硬规则里的第一条：**子控制器之间不互相引用**，以及它的前提 —— 不碰界面。
     *
     * 拆成十二个包之后，跨功能协作（导入完成要重读题库、删除关系要问扩展包）全靠组合根
     * 串联。一旦两个控制器互相 import，再拆就变成"拆开又焊上"：改一个功能要读另一个的
     * 内部状态，而这正是拆分要消灭的东西。
     */
    @Test
    fun `功能包只认 domain 与 application，既不互相引用也不碰界面`() {
        assertTrue(
            featurePackages.size >= 12,
            "只认出 ${featurePackages.size} 个功能包（$featurePackages），路径可能算错了",
        )
        val root = "morefish.knowledge.trainer.presentation"
        val hits = featureSources().flatMap { (pkg, file) ->
            importsOf(file).mapNotNull { import ->
                if (!import.target.startsWith("$root.")) return@mapNotNull null
                val rest = import.target.removePrefix("$root.")
                val ownPackage = rest.substringBefore('.') == pkg.substringAfterLast('/')
                // 允许 presentation 顶层的常量（如 UI_LOCALE）；Screen 不是常量而是路由本身，
                // 单独排除，否则"引用导航"会借道顶层类型溜进来。
                val topLevelValue = !rest.contains('.') && rest != "Screen"
                if (ownPackage || topLevelValue) null else "$pkg ← $import"
            }
        }
        assertTrue(
            hits.isEmpty(),
            "跨功能的协作一律由组合根串联，子控制器之间互不认识；界面（screens / ui / theme）" +
                "更不该出现在这里。越界引用：\n" + hits.joinToString("\n") { "  • $it" },
        )
    }

    /** 三条硬规则里的第二条：**子控制器不导航**。下一屏由调用点决定，否则路由就没有单一入口。 */
    @Test
    fun `功能控制器里没有导航语句`() {
        val navigation = Regex("""\bScreen\b""")
        val hits = featureSources().flatMap { (pkg, file) ->
            file.readLines().mapIndexedNotNull { index, raw ->
                val code = raw.trim()
                val comment = code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")
                if (comment || !navigation.containsMatchIn(code)) null
                else "$pkg/${file.name}:${index + 1}  $code"
            }
        }
        assertTrue(
            hits.isEmpty(),
            "子控制器只回结论（Saved / Blocked / 有没有下一题），去哪儿由组合根决定。" +
                "出现导航的地方：\n" + hits.joinToString("\n") { "  • $it" },
        )
    }

    @Test
    fun `扫描确实覆盖了共享层源码`() {
        val files = kotlinSources()
        assertTrue(files.size >= 100, "只扫到 ${files.size} 个 Kotlin 文件，路径可能算错了：$commonMain")
        // 每条规则都依赖路径前缀，抽一个已知文件确认相对路径形态正确
        assertTrue(
            files.any { relative(it) == "presentation/AppController.kt" },
            "相对路径形态不符合预期，分层判断会全部失效",
        )
    }
}
