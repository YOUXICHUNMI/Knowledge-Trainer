package morefish.knowledge.trainer.application.usecase

import morefish.knowledge.trainer.application.port.AttemptRepository
import morefish.knowledge.trainer.application.port.SessionRepository
import morefish.knowledge.trainer.application.port.WallClock
import morefish.knowledge.trainer.domain.model.PracticeSession

/**
 * 会话生命周期：保存进度、暂停、恢复、结束、丢弃。
 *
 * 会话只存"题目清单 + 游标 + 草稿"，已提交记录仍然只存在历史仓储里，
 * 恢复时按 sessionId 取回 —— 同一份作答不会在两个地方各存一遍。
 */
class SessionLifecycle(
    private val sessions: SessionRepository,
    private val attempts: AttemptRepository,
    private val clock: WallClock = WallClock.System,
) {

    suspend fun saveProgress(session: PracticeSession) {
        if (session.isResumable && session.current != null) {
            sessions.save(session.touch(clock.nowMillis()))
        } else {
            sessions.clear(session.sessionId)
        }
    }

    suspend fun active(): PracticeSession? = sessions.active()

    /** 合并已提交记录并对齐游标，返回可以直接继续作答的会话。 */
    suspend fun resume(session: PracticeSession): PracticeSession {
        val records = attempts.forSession(session.sessionId).sortedBy { it.submittedAtMillis }
        val restored = session.copy(attempts = records)
        val normalized = restored.resumeNormalized(clock.nowMillis())
        sessions.save(normalized)
        return normalized
    }

    suspend fun pause(session: PracticeSession): PracticeSession {
        val paused = session.paused(clock.nowMillis())
        sessions.save(paused)
        return paused
    }

    suspend fun complete(session: PracticeSession): PracticeSession {
        val done = session.completed()
        sessions.clear(done.sessionId)
        return done
    }

    suspend fun discard(session: PracticeSession) {
        sessions.clear(session.sessionId)
    }
}
