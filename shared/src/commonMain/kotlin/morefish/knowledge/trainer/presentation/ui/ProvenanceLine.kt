package morefish.knowledge.trainer.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import morefish.knowledge.trainer.domain.model.BankProvenance
import morefish.knowledge.trainer.domain.model.ProvenanceOrigin
import morefish.knowledge.trainer.domain.model.Question
import morefish.knowledge.trainer.presentation.theme.Palette

/**
 * 一道题的出处，一行。
 *
 * 三种状态刻意长得不一样，因为它们的含义完全不同：
 *
 * - **作者原创** / **改编自某书 第 3 章** —— 作者交代了。灰字，安静。
 * - **AI 参与生成** —— 琥珀字。不是警告，是让人知道"这段文字可能没经过人审"。
 * - **来源未标注** —— 最淡的灰。它既不是"可信"也不是"不可信"，是"作者没说"。
 *   把它渲染成中性甚至正面，等同于替作者声明了他没说过的话。
 *
 * 位置在题干之下、作答之上：作答前就该知道这道题哪来的，而不是等判完才看到。
 */
@Composable
fun ProvenanceLine(
    question: Question,
    provenance: BankProvenance,
    modifier: Modifier = Modifier,
) {
    val source = provenance.source(question.sourceId)
    val locator = question.sourceLocator?.takeIf { it.isNotBlank() }
    val text: String
    val color: androidx.compose.ui.graphics.Color
    val chip: String?
    when (question.origin) {
        ProvenanceOrigin.AI_ASSISTED -> {
            chip = "AI 参与生成"
            color = Palette.Pending
            text = source?.let { "参考 ${it.shortLabel}" } ?: ""
        }

        ProvenanceOrigin.AUTHORED -> {
            chip = null
            color = Palette.Faint
            text = "作者原创" + (source?.let { "（参考 ${it.shortLabel}）" } ?: "")
        }

        ProvenanceOrigin.ADAPTED, ProvenanceOrigin.IMPORTED -> {
            chip = null
            color = Palette.Faint
            val verb = if (question.origin == ProvenanceOrigin.ADAPTED) "改编自" else "引自"
            text = when {
                source == null -> "$verb 未注明的来源"
                locator == null -> "$verb ${source.shortLabel}"
                else -> "$verb ${source.shortLabel} · $locator"
            }
        }

        ProvenanceOrigin.UNSPECIFIED -> {
            chip = null
            color = Palette.Faint
            text = if (source == null) {
                "来源未标注"
            } else {
                // 给了出处却没说出处性质：把已有的信息照实说，不补一句"原创"
                "出处 ${source.shortLabel}" + (locator?.let { " · $it" } ?: "") + "（性质未声明）"
            }
        }
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (chip != null) {
            Text(
                chip,
                style = MaterialTheme.typography.labelSmall,
                color = Palette.Pending,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Palette.PendingSoft)
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )
        }
        if (text.isNotEmpty()) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = color,
                maxLines = 2,
            )
        }
        if (chip == null && text.isEmpty()) {
            Spacer(Modifier.width(0.dp))
        }
    }
}
