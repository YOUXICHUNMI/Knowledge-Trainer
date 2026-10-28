package morefish.knowledge.trainer.domain

import morefish.knowledge.trainer.domain.model.Note
import morefish.knowledge.trainer.domain.model.NoteScope
import morefish.knowledge.trainer.domain.model.countForBank
import morefish.knowledge.trainer.domain.model.forQuestion
import morefish.knowledge.trainer.domain.model.sortedForDisplay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NoteTest {

    private fun note(
        id: String,
        scope: NoteScope = NoteScope.DRAFT,
        bankId: String? = null,
        questionId: String? = null,
        title: String = "",
        body: String = "内容",
        pinned: Boolean = false,
        updatedAt: Long = 0,
    ) = Note(
        noteId = id,
        scope = scope,
        bankId = bankId,
        questionId = questionId,
        title = title,
        bodyMarkdown = body,
        pinned = pinned,
        updatedAtMillis = updatedAt,
    )

    @Test
    fun emptyBodyIsAnError() {
        val errors = note("n1", body = "   \n  ").errors()
        assertEquals(1, errors.size)
        assertEquals("note-empty", errors.single().code)
    }

    @Test
    fun questionNoteRequiresBankAndQuestion() {
        val noBank = note("n1", scope = NoteScope.QUESTION, questionId = "q1").errors()
        assertTrue(noBank.any { it.code == "note-bank-missing" })

        val noQuestion = note("n2", scope = NoteScope.QUESTION, bankId = "b1").errors()
        assertTrue(noQuestion.any { it.code == "note-question-missing" })

        val complete = note("n3", scope = NoteScope.QUESTION, bankId = "b1", questionId = "q1").errors()
        assertTrue(complete.isEmpty())
    }

    @Test
    fun bankNoteRequiresBankButDraftDoesNot() {
        assertTrue(note("n1", scope = NoteScope.BANK).errors().any { it.code == "note-bank-missing" })
        assertTrue(note("n2", scope = NoteScope.BANK, bankId = "b1").errors().isEmpty())
        assertTrue(note("n3", scope = NoteScope.DRAFT).errors().isEmpty())
    }

    @Test
    fun oversizedTitleAndBodyAreRejected() {
        val longTitle = note("n1", title = "标".repeat(Note.MAX_TITLE + 1)).errors()
        assertTrue(longTitle.any { it.code == "note-title-too-long" })

        val longBody = note("n2", body = "字".repeat(Note.MAX_BODY + 1)).errors()
        assertTrue(longBody.any { it.code == "note-body-too-long" })
    }

    @Test
    fun displayTitleFallsBackToFirstMeaningfulLine() {
        assertEquals("标题", note("n1", title = "  标题  ").displayTitle)
        assertEquals("第一行想法", note("n2", body = "## 第一行想法\n\n第二行").displayTitle)
        assertEquals("列表项", note("n3", body = "- 列表项").displayTitle)
        assertEquals("（无标题）", note("n4", body = "   \n ").displayTitle)
    }

    @Test
    fun searchCoversTitleAndBodyIgnoringCase() {
        val n = note("n1", title = "Inductance", body = "自感电动势与 \\(L\\) 有关")
        assertTrue(n.matches(""))
        assertTrue(n.matches("induc"))
        assertTrue(n.matches("自感"))
        assertFalse(n.matches("电容"))
    }

    @Test
    fun pinnedNotesComeFirstThenMostRecentlyUpdated() {
        val notes = listOf(
            note("old", updatedAt = 10),
            note("new", updatedAt = 90),
            note("pinned", pinned = true, updatedAt = 5),
        )
        assertEquals(listOf("pinned", "new", "old"), notes.sortedForDisplay().map { it.noteId })
    }

    @Test
    fun helpersPickLatestQuestionNoteAndCountBankNotes() {
        val notes = listOf(
            note("q-old", scope = NoteScope.QUESTION, bankId = "b1", questionId = "q1", updatedAt = 1),
            note("q-new", scope = NoteScope.QUESTION, bankId = "b1", questionId = "q1", updatedAt = 2),
            note("other", scope = NoteScope.QUESTION, bankId = "b1", questionId = "q2", updatedAt = 3),
            note("bank", scope = NoteScope.BANK, bankId = "b1"),
        )
        assertEquals("q-new", notes.forQuestion("b1", "q1")?.noteId)
        assertNull(notes.forQuestion("b2", "q1"))
        assertEquals(4, notes.countForBank("b1"))
        assertEquals(0, notes.countForBank("b2"))
    }
}
