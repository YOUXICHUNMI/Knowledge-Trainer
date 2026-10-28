package morefish.knowledge.trainer.application.port

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** 可替换时钟。禁止在业务代码里直接读系统时间，否则测试无法覆盖时间相关逻辑。 */
fun interface WallClock {
    fun nowMillis(): Long

    companion object {
        @OptIn(ExperimentalTime::class)
        val System: WallClock = WallClock { Clock.System.now().toEpochMilliseconds() }
    }
}
