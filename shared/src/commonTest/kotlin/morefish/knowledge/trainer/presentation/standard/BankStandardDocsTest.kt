package morefish.knowledge.trainer.presentation.standard

import morefish.knowledge.trainer.infrastructure.importing.PackageFormat
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 「当前实现到哪一步」这张清单必须与代码同步。
 *
 * 它曾经落后过整整三个阶段：题内附图、`.qbext`、`.qbpatch`、`.qbk` 都做完并各有流程用例了，
 * 页面上还写着"未实现"。对用户说"没做"和"说做了其实是空的"一样是假话，只是方向相反 ——
 * 而且更难被发现：没人会去质疑一句"还没做"。
 *
 * 这三条守的是两个方向：登记了的格式必须出现，做完的能力不许说没做。
 */
class BankStandardDocsTest {

    private val notes = BankStandardDocs.implementationNotes

    @Test
    fun everyNoteStartsWithAStatusWord() {
        notes.forEach { note ->
            assertTrue(
                note.startsWith("已实现：") || note.startsWith("尚未实现："),
                "每条都要以「已实现：」或「尚未实现：」开头，否则界面上分不清：$note",
            )
        }
    }

    /** 格式在 [PackageFormat] 里登记了，就必须在清单里有一条"已实现"讲到它。 */
    @Test
    fun everySupportedPackageFormatIsListedAsImplemented() {
        val implemented = notes.filter { it.startsWith("已实现：") }
        PackageFormat.entries
            .filter { it != PackageFormat.UNKNOWN }
            .forEach { format ->
                // label 形如 ".qbext 扩展包"，扩展名是第一个空格之前那一段
                val extension = format.label.substringBefore(' ').trim()
                assertTrue(
                    implemented.any { it.contains(extension) },
                    "包格式 $format（$extension）已登记在 PackageFormat 里，" +
                        "但清单没有把它列成「已实现」—— 新增格式时请同步这一句",
                )
            }
    }

    /** 反过来：已经做完的能力，不许再出现在"尚未实现"里。 */
    @Test
    fun nothingImplementedIsClaimedAsMissing() {
        val done = listOf(".qbank", ".qbext", ".qbpatch", ".qbk", "附图")
        notes.filter { it.startsWith("尚未实现：") }.forEach { note ->
            done.forEach { capability ->
                assertTrue(
                    !note.contains(capability),
                    "「$capability」已经实现（见 PackageFormat、QuestionFigure 与各自的 FlowTest），" +
                        "不该写在「尚未实现」里：$note",
                )
            }
        }
    }
}
