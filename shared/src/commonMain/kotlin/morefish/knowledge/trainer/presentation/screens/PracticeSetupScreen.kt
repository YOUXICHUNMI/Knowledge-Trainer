package morefish.knowledge.trainer.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import morefish.knowledge.trainer.domain.i18n.LocaleFallback
import morefish.knowledge.trainer.domain.i18n.localeLabel
import morefish.knowledge.trainer.domain.model.QuestionType
import morefish.knowledge.trainer.domain.practice.PracticeScope
import morefish.knowledge.trainer.presentation.AppController
import morefish.knowledge.trainer.presentation.theme.Palette
import morefish.knowledge.trainer.presentation.ui.AnimatedCount
import morefish.knowledge.trainer.presentation.ui.BannerHost
import morefish.knowledge.trainer.presentation.ui.EmptyState
import morefish.knowledge.trainer.presentation.ui.KtChip
import morefish.knowledge.trainer.presentation.ui.Panel
import morefish.knowledge.trainer.presentation.ui.PrimaryButton
import morefish.knowledge.trainer.presentation.ui.ScreenScaffold
import morefish.knowledge.trainer.presentation.ui.SectionTitle
import morefish.knowledge.trainer.presentation.ui.StaggeredAppear

/**
 * 正式练习的设置页：范围 → 题型 → 标签 → 题量 → 选项顺序。
 * 所有筛选都是实时预览，抽不到题时直接说"0 题"，不会偷偷放宽条件。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PracticeSetupScreen(controller: AppController) {
    val bank = controller.setup.bank

    ScreenScaffold(
        title = "练习设置",
        subtitle = bank?.title ?: "题库缺失",
        overline = "按自己的条件出题",
        onBack = controller::goHome,
    ) {
        BannerHost(controller.banner, controller::dismissBanner)

        if (bank == null) {
            EmptyState(
                title = "这个题库不在了",
                hint = "它可能已被移除。返回首页重新选择一个题库。",
                glyph = "!",
            )
            return@ScreenScaffold
        }

        StaggeredAppear(0) {
            Column {
                SectionTitle("练习范围", "先决定从哪些题里抽")
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PracticeScope.entries.forEach { scope ->
                        KtChip(
                            label = "${scope.label} ${controller.setup.scopeCount(scope)}",
                            selected = controller.setup.scope == scope,
                            onClick = { controller.setup.selectScope(scope) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    controller.setup.scope.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Faint,
                )
            }
        }

        // 语言：只有多语言题库才显示 —— 单语言题库摆一排只有一项的胶囊是噪音
        val locales = controller.setup.locales
        if (locales.size > 1) {
            Spacer(Modifier.height(20.dp))
            StaggeredAppear(1) {
                Column {
                    SectionTitle("语言", "题面与标准答案都按这门语言")
                    Spacer(Modifier.height(10.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        locales.forEach { locale ->
                            val coverage = bank.coverageOf(locale)
                            KtChip(
                                label = "${localeLabel(locale)} ${coverage.summary}",
                                selected = controller.setup.locale == locale,
                                onClick = { controller.setup.selectLocale(locale) },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    val coverage = controller.setup.coverage
                    if (coverage != null && !coverage.complete) {
                        Text(
                            "这门语言翻译了 ${coverage.covered}/${coverage.total} 题，" +
                                "没翻译的 ${coverage.missing} 题不会被抽到 —— 不会拿另一种语言的题面顶替",
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.Pending,
                        )
                    } else {
                        Text(
                            "这门语言覆盖全部题目；切换语言等于换一套作答历史与错题记录",
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.Faint,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        StaggeredAppear(1) {
            Column {
                SectionTitle("题型", if (controller.setup.types.isEmpty()) "不选 = 全部" else "已选 ${controller.setup.types.size} 类")
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QuestionType.entries.forEach { type ->
                        val count = controller.setup.typeCount(type)
                        KtChip(
                            label = "${type.label} $count",
                            selected = type in controller.setup.types,
                            enabled = count > 0,
                            onClick = { controller.setup.toggleType(type) },
                        )
                    }
                }
            }
        }

        val tags = controller.setup.tagPool
        if (tags.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            StaggeredAppear(2) {
                Column {
                    SectionTitle("标签", if (controller.setup.tags.isEmpty()) "不选 = 全部" else null)
                    Spacer(Modifier.height(10.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        tags.forEach { tag ->
                            KtChip(
                                label = tag,
                                selected = tag in controller.setup.tags,
                                onClick = { controller.setup.toggleTag(tag) },
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        StaggeredAppear(3) {
            Column {
                SectionTitle("题量")
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(10, 20, 50).forEach { count ->
                        KtChip(
                            label = "$count 题",
                            selected = controller.setup.count == count,
                            onClick = { controller.setup.selectCount(count) },
                        )
                    }
                    KtChip(
                        label = "全部",
                        selected = controller.setup.count == null,
                        onClick = { controller.setup.selectCount(null) },
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        StaggeredAppear(4) {
            Panel(padding = 16.dp) {
                ToggleRow(
                    title = "混合题型",
                    description = "按题型轮转抽取，避免抽到的全是同一类题",
                    checked = controller.setup.mixedTypes,
                    onChange = controller.setup::toggleMixedTypes,
                )
                Spacer(Modifier.height(14.dp))
                ToggleRow(
                    title = "选项随机",
                    description = "打乱 A/B/C/D 的显示顺序；正确答案按 optionId 判定，不会漂移",
                    checked = controller.setup.shuffleOptions,
                    onChange = controller.setup::toggleShuffleOptions,
                )
                // 只有真的缺译文时才给这个开关：语言完整时它是个没有意义的选项
                if (controller.setup.untranslated > 0 && locales.size > 1) {
                    Spacer(Modifier.height(14.dp))
                    ToggleRow(
                        title = LocaleFallback.ORIGINAL.label,
                        description = "关着 = 只练有译文的题（默认）；打开 = 缺译文时显示原文并标注，判题用原文答案",
                        checked = controller.setup.fallback == LocaleFallback.ORIGINAL,
                        onChange = controller.setup::toggleFallback,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        val candidates = controller.setup.candidateCount
        val planned = minOf(controller.setup.count ?: candidates, candidates)

        StaggeredAppear(5) {
            Panel(
                background = if (candidates == 0) Palette.WrongSoft else Palette.AccentSoft,
                borderColor = if (candidates == 0) Palette.Wrong else Palette.AccentLine,
                padding = 18.dp,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                if (candidates == 0) "这个范围里没有可练的题" else "将抽到",
                                style = MaterialTheme.typography.titleSmall,
                                color = if (candidates == 0) Palette.Wrong else Palette.Ink,
                                modifier = Modifier.padding(bottom = 3.dp),
                            )
                            if (candidates != 0) {
                                Spacer(Modifier.width(6.dp))
                                AnimatedCount(
                                    value = planned,
                                    style = MaterialTheme.typography.displaySmall,
                                    color = Palette.Accent,
                                )
                                Text(
                                    "题",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Palette.Accent,
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "候选 $candidates 题 · ${controller.setup.plan.describe()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.Muted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    PrimaryButton(
                        text = "开始练习",
                        onClick = controller::startPlannedPractice,
                        enabled = candidates > 0,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onChange: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Palette.Ink)
            Text(description, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
        }
        Switch(
            checked = checked,
            onCheckedChange = { onChange() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Palette.OnAccent,
                checkedTrackColor = Palette.Accent,
                uncheckedThumbColor = Palette.Faint,
                uncheckedTrackColor = Palette.NeutralSoft,
                uncheckedBorderColor = Palette.PanelBorderStrong,
            ),
        )
    }
}
