package morefish.knowledge.trainer.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import morefish.knowledge.trainer.presentation.Screen
import morefish.knowledge.trainer.presentation.standard.BankStandardDocs
import morefish.knowledge.trainer.presentation.standard.SkillGuide
import morefish.knowledge.trainer.presentation.theme.Palette
import morefish.knowledge.trainer.presentation.ui.Panel
import morefish.knowledge.trainer.presentation.ui.Pill
import morefish.knowledge.trainer.presentation.ui.ScreenScaffold
import morefish.knowledge.trainer.presentation.ui.SectionTitle
import morefish.knowledge.trainer.presentation.ui.StaggeredAppear

/** 三块本地说明共用一个外壳：不需要联网、不需要账号，返回来源页。 */
@Composable
fun StandardScreen(screen: Screen, onBack: () -> Unit) {
    when (screen) {
        Screen.STANDARD -> ScreenScaffold(
            title = "题库标准",
            subtitle = "${BankStandardDocs.FORMAT_NAME} · 接受 ${BankStandardDocs.ACCEPTED_VERSION_RANGE}",
            overline = "本地文档",
            onBack = onBack,
        ) {
            StandardBody()
        }

        Screen.SKILL -> ScreenScaffold(
            title = "AI 制包 Skill",
            subtitle = "把整个 Skill 目录交给 AI，而不是只给它一段说明",
            overline = "本地文档",
            onBack = onBack,
        ) {
            SkillBody()
        }

        Screen.TOOLS -> ScreenScaffold(
            title = "示例包与校验工具",
            subtitle = "离线工具链：打包 / 校验 / 检视 / 解包，零第三方依赖",
            overline = "本地文档",
            onBack = onBack,
        ) {
            ToolsBody()
        }

        else -> Unit
    }
}

// ---------------------------------------------------------------- 题库标准

@Composable
private fun StandardBody() {
    BankStandardDocs.sections.forEachIndexed { index, section ->
        StaggeredAppear(index) {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NumberBadge(index + 1)
                    Spacer(Modifier.width(10.dp))
                    Text(section.title, style = MaterialTheme.typography.titleMedium, color = Palette.Ink)
                }
                Spacer(Modifier.height(8.dp))
                Text(section.body, style = MaterialTheme.typography.bodyMedium, color = Palette.Ink)
                if (section.bullets.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    section.bullets.forEach { line -> Bullet(line) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    StaggeredAppear(BankStandardDocs.sections.size) {
        Panel(background = Palette.PanelMuted) {
            SectionTitle("当前实现到哪一步", hint = "两个方向都要如实")
            Spacer(Modifier.height(8.dp))
            BankStandardDocs.implementationNotes.forEach { Bullet(it) }
        }
    }
    Spacer(Modifier.height(8.dp))
}

// ---------------------------------------------------------------- Skill

@Composable
private fun SkillBody() {
    StaggeredAppear(0) {
        Panel {
            SectionTitle("为什么不能只给 SKILL.md")
            Spacer(Modifier.height(8.dp))
            Text(
                "SKILL.md 只是一段说明。它引用的规范正文、Schema、打包脚本如果不在场，" +
                    "AI 会退化成凭印象猜格式，写出来的东西过不了校验。必须整目录一起给。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Palette.CodeBg, RoundedCornerShape(10.dp))
                    .padding(12.dp),
            ) {
                Text(
                    SkillGuide.SKILL_DIR,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = Palette.Ink,
                )
            }
        }
    }
    Spacer(Modifier.height(12.dp))

    StaggeredAppear(1) {
        Panel {
            SectionTitle("必须一起提供的文件")
            Spacer(Modifier.height(10.dp))
            SkillGuide.bundle.forEach { item ->
                Text(
                    item.path,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = Palette.Ink,
                )
                Text("  ${item.purpose}", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                Row(verticalAlignment = Alignment.Top) {
                    Text("  缺了会怎样：", style = MaterialTheme.typography.bodySmall, color = Palette.Pending)
                    Text(
                        item.missingConsequence,
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.Pending,
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
    Spacer(Modifier.height(12.dp))

    StaggeredAppear(2) {
        Panel {
            SectionTitle("一段可以直接粘给 AI 的话", hint = "把方括号里换成你的信息")
            Spacer(Modifier.height(10.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Palette.CodeBg, RoundedCornerShape(10.dp))
                    .padding(12.dp),
            ) {
                Text(
                    SkillGuide.promptTemplate.trimEnd(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = Palette.Ink,
                )
            }
        }
    }
    Spacer(Modifier.height(12.dp))

    StaggeredAppear(3) {
        Panel(background = Palette.PendingSoft, borderColor = Palette.Pending.copy(alpha = 0.35f)) {
            SectionTitle("提醒")
            Spacer(Modifier.height(8.dp))
            Bullet("Skill 本身不带任何权限。AI 能不能写文件、能不能跑 Python，取决于你给它什么环境。")
            Bullet("环境不具备时，合格的 AI 必须说「未生成 / 未验证」，而不是声称已经通过校验。")
            Bullet("AI 生成的干扰项与语义重组必须标 unreviewed；它们不会、也不能覆盖标准答案。")
        }
    }
    Spacer(Modifier.height(8.dp))
}

// ---------------------------------------------------------------- 工具与示例

@Composable
private fun ToolsBody() {
    StaggeredAppear(0) {
        Panel {
            SectionTitle("四个命令", hint = "Python 3.9+，零第三方依赖，不联网")
            Spacer(Modifier.height(10.dp))
            SkillGuide.verifyHints.forEachIndexed { index, hint ->
                Row(modifier = Modifier.padding(vertical = 3.dp)) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Palette.AccentInk,
                        modifier = Modifier.width(18.dp),
                    )
                    Text(hint, style = MaterialTheme.typography.bodyMedium, color = Palette.Ink)
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))

    StaggeredAppear(1) {
        Panel {
            SectionTitle("两个示例", hint = "各有作者源目录 + 已打好的真包")
            Spacer(Modifier.height(10.dp))
            ExampleRow("minimal", "3 题：单选 / 多选 / 简答。验证「什么都不配也能得到可练题库」。")
            Spacer(Modifier.height(10.dp))
            ExampleRow(
                "advanced",
                "6 题：多分片、简体中文全量 + 英文部分翻译、LaTeX、真实 PNG、" +
                    "一条已审核重组 + 一条未审核干扰项。",
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "示例包在 tools/qbank-authoring-skill/examples/_packages/，可以直接导入 App 试一遍。",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Faint,
            )
        }
    }
    Spacer(Modifier.height(12.dp))

    StaggeredAppear(2) {
        Panel {
            SectionTitle("校验报告里最常见的几个错误码")
            Spacer(Modifier.height(10.dp))
            ErrorCode("HASH_MISMATCH", "文件内容变了但 manifest 里的 sha256 没更新")
            ErrorCode("FILE_NOT_LISTED", "ZIP 里有 manifest 没声明的文件（通常是未引用媒体）")
            ErrorCode("OPTION_MISMATCH", "文案层的选项集合与结构层的 optionIds 不一致")
            ErrorCode("LOCALE_INCOMPLETE", "sourceLocale 没有覆盖全部题目")
            ErrorCode("ANSWER_COUNT", "单选给了多个正确答案，或多选只给了一个")
            ErrorCode("ZIP_HEADER_MISMATCH", "本地文件头与中央目录矛盾，包被改过或下载损坏")
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ExampleRow(name: String, description: String) {
    Row(verticalAlignment = Alignment.Top) {
        Pill(name, Palette.AccentInk, Palette.AccentSoft)
        Spacer(Modifier.width(10.dp))
        Text(description, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
    }
}

@Composable
private fun ErrorCode(code: String, meaning: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .background(Palette.WrongSoft, RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                code,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = Palette.Wrong,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(meaning, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
    }
}

@Composable
private fun NumberBadge(n: Int) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(Palette.AccentSoft, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("$n", style = MaterialTheme.typography.labelMedium, color = Palette.AccentInk)
    }
}

@Composable
private fun Bullet(text: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .size(4.dp)
                .background(Palette.Accent.copy(alpha = 0.45f), CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.Ink)
    }
}
