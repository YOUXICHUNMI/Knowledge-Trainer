package morefish.knowledge.trainer.presentation.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import morefish.knowledge.trainer.domain.model.Question
import morefish.knowledge.trainer.presentation.media.decodeImageBytes
import morefish.knowledge.trainer.presentation.theme.Palette

/**
 * 题目附图。媒体字节来自 MediaStore（按 bankId + assetId），
 * 取不到 / 解码失败都显示一个不碍事的占位 —— 图片是辅助信息，不能因为它的缺失打断作答。
 */
@Composable
fun QuestionFigure(
    question: Question,
    bankId: String?,
    loadMedia: suspend (bankId: String, assetId: String) -> ByteArray?,
    modifier: Modifier = Modifier,
) {
    if (question.assetIds.isEmpty() || bankId == null) return
    Column(modifier = modifier.fillMaxWidth()) {
        question.assetIds.forEach { assetId ->
            AssetFigure(
                bankId = bankId,
                assetId = assetId,
                loadMedia = loadMedia,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private sealed interface FigureState {
    data object Loading : FigureState
    data object Missing : FigureState
    data class Ready(val bitmap: ImageBitmap) : FigureState
}

@Composable
private fun AssetFigure(
    bankId: String,
    assetId: String,
    loadMedia: suspend (String, String) -> ByteArray?,
    modifier: Modifier = Modifier,
) {
    var state by remember(bankId, assetId) { mutableStateOf<FigureState>(FigureState.Loading) }
    LaunchedEffect(bankId, assetId) {
        val bytes = loadMedia(bankId, assetId)
        state = when {
            bytes == null -> FigureState.Missing
            else -> decodeImageBytes(bytes)?.let { FigureState.Ready(it) } ?: FigureState.Missing
        }
    }

    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(Palette.PanelMuted)
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (val s = state) {
            FigureState.Loading -> Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp)
                    .background(Palette.PanelBorder.copy(alpha = 0.4f), shape),
            )

            FigureState.Missing -> Text(
                "附图 $assetId 无法显示（媒体缺失或格式不支持）",
                style = MaterialTheme.typography.labelMedium,
                color = Palette.Faint,
                modifier = Modifier.padding(vertical = 24.dp, horizontal = 12.dp),
            )

            is FigureState.Ready -> Image(
                bitmap = s.bitmap,
                contentDescription = assetId,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 340.dp),
            )
        }
    }
}
