package morefish.knowledge.trainer.presentation.theme

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import kotlin.test.assertTrue

/**
 * 深色页里不许出现**成片**的浅色。
 *
 * 这条规则补的是人眼的班：20 张图逐张看不可持续，而"某块忘了换令牌"正是深色里
 * 最常犯、也最难看出来的错（本次出过两次：Hero 白按钮的字、勾选框的边）。
 *
 * 判据不能是"有没有浅色像素"——深色下正文本身就是浅色的 `Ink`；
 * 也不能是"这块平均亮度高"——大号粗体的白字能把 8×8 的均值抬到 0.6（实测撞上过）。
 * 真正分得开文字与纸面的是**均匀性**：一个 8×8 块里每个像素都亮，那才是一块面。
 * 所以判据是"块内最小亮度 ≥ 0.5"，文字永远过不了这一关。
 *
 * 调用方负责确认这一屏确实是深色（看 `Palette.current`），
 * 并放行那些"本来就有一块白"的页面（首页 Hero 的白色主按钮）。
 */
internal fun assertNoLightBlocksOnDark(name: String, bitmap: ImageBitmap) {
    val map = bitmap.toPixelMap()
    val block = 8
    var count = 0
    var worst = 0f
    var worstAt = "-"
    var y = 0
    while (y + block <= map.height) {
        var x = 0
        while (x + block <= map.width) {
            var darkest = 1f
            for (dy in 0 until block) {
                for (dx in 0 until block) {
                    val l = map[x + dx, y + dy].luminance()
                    if (l < darkest) darkest = l
                }
            }
            if (darkest >= 0.5f) {
                count++
                if (darkest > worst) {
                    worst = darkest
                    worstAt = "($x, $y)"
                }
            }
            x += block
        }
        y += block
    }
    assertTrue(
        count == 0,
        "「$name」是深色页，却有 $count 个 8×8 小块整块都是亮的，最亮的一块在 $worstAt" +
            "（块内最小亮度 ${(worst * 100).toInt() / 100f}）—— " +
            "多半是某块用了浅色令牌、或者根本没走令牌",
    )
}
