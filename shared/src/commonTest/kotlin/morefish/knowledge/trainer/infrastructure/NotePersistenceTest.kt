package morefish.knowledge.trainer.infrastructure

import kotlinx.coroutines.test.runTest
import morefish.knowledge.trainer.application.port.InMemoryTextStorage
import morefish.knowledge.trainer.domain.model.Note
import morefish.knowledge.trainer.domain.model.NoteScope
import morefish.knowledge.trainer.infrastructure.storage.NOTES_FILE
import morefish.knowledge.trainer.infrastructure.storage.JsonNoteRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotePersistenceTest {

    private fun note(
        id: String,
        scope: NoteScope = NoteScope.DRAFT,
        bankId: String? = null,
        questionId: String? = null,
        title: String = "",
        body: String = "正文",
        pinned: Boolean = false,
        updatedAt: Long = 10,
    ) = Note(
        noteId = id,
        scope = scope,
        bankId = bankId,
        questionId = questionId,
        title = title,
        bodyMarkdown = body,
        pinned = pinned,
        createdAtMillis = 1,
        updatedAtMillis = updatedAt,
    )

    @Test
    fun notesRoundTripIncludingScopeAndLinks() = runTest {
        val storage = InMemoryTextStorage()
        JsonNoteRepository(storage).apply {
            upsert(note("n1", NoteScope.BANK, bankId = "b1", title = "资料范围"))
            upsert(note("n2", NoteScope.QUESTION, bankId = "b1", questionId = "q1", body = "易错点：单位", pinned = true))
            upsert(note("n3", NoteScope.DRAFT, body = "随手记"))
        }

        // 新实例 = 下次启动
        val reopened = JsonNoteRepository(storage)
        val all = reopened.all().associateBy { it.noteId }
        assertEquals(3, all.size)
        assertEquals(NoteScope.BANK, all.getValue("n1").scope)
        assertEquals("b1", all.getValue("n2").bankId)
        assertEquals("q1", all.getValue("n2").questionId)
        assertTrue(all.getValue("n2").pinned)
        assertNull(all.getValue("n3").bankId)
        assertEquals("随手记", reopened.find("n3")?.bodyMarkdown)
    }

    @Test
    fun upsertReplacesInsteadOfDuplicating() = runTest {
        val storage = InMemoryTextStorage()
        val repository = JsonNoteRepository(storage)
        repository.upsert(note("n1", body = "第一版"))
        repository.upsert(note("n1", body = "第二版"))

        val all = JsonNoteRepository(storage).all()
        assertEquals(1, all.size)
        assertEquals("第二版", all.single().bodyMarkdown)
    }

    @Test
    fun deleteRemovesOnlyTheTargetNote() = runTest {
        val storage = InMemoryTextStorage()
        val repository = JsonNoteRepository(storage)
        repository.upsert(note("n1"))
        repository.upsert(note("n2"))

        repository.delete("n1")
        val remaining = JsonNoteRepository(storage).all()
        assertEquals(listOf("n2"), remaining.map { it.noteId })

        // 删不存在的 id 不应把整个文件写坏
        repository.delete("nope")
        assertEquals(1, JsonNoteRepository(storage).all().size)
    }

    @Test
    fun corruptFileIsReportedInsteadOfCrashing() = runTest {
        val storage = InMemoryTextStorage()
        storage.write(NOTES_FILE, "{ 这不是笔记文件")

        val repository = JsonNoteRepository(storage)
        assertTrue(repository.all().isEmpty())
        assertTrue(repository.loadWarnings.single().contains("无法解析"))
    }

    @Test
    fun unsupportedRecordsAreSkippedWithWarning() = runTest {
        val storage = InMemoryTextStorage()
        storage.write(
            NOTES_FILE,
            """{"schemaVersion":1,"notes":[""" +
                """{"noteId":"ok","scope":"draft","bodyMarkdown":"能读"},""" +
                """{"noteId":"bad","scope":"unknown-scope","bodyMarkdown":"读不了"},""" +
                """{"noteId":"","scope":"draft","bodyMarkdown":"没有 id"}""" +
                """]}""",
        )

        val repository = JsonNoteRepository(storage)
        assertEquals(listOf("ok"), repository.all().map { it.noteId })
        assertTrue(repository.loadWarnings.single().contains("2 条"))
    }

    @Test
    fun storedNotesRemainReadableAfterSchemaGainsFields() = runTest {
        val storage = InMemoryTextStorage()
        // 只有必需字段：模拟更早版本写下的文件
        storage.write(
            NOTES_FILE,
            """{"schemaVersion":1,"notes":[{"noteId":"n1","scope":"draft","bodyMarkdown":"旧文件"}]}""",
        )
        val loaded = JsonNoteRepository(storage).all().single()
        assertEquals("旧文件", loaded.bodyMarkdown)
        assertFalse(loaded.pinned)
        assertEquals(0L, loaded.createdAtMillis)
    }
}
