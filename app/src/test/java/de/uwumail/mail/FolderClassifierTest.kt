package de.uwumail.mail

import de.uwumail.core.FolderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderClassifierTest {

    @Test
    fun `inbox is recognised whatever the server capitalises it as`() {
        listOf("INBOX", "Inbox", "inbox", "InBoX").forEach {
            assertTrue("$it should be the inbox", FolderClassifier.isInbox(it))
            assertEquals(FolderType.INBOX.name, FolderClassifier.guess(it))
        }
    }

    @Test
    fun `a nested folder called inbox is not the inbox`() {
        assertFalse(FolderClassifier.isInbox("Archive/Inbox"))
        assertEquals(FolderType.CUSTOM.name, FolderClassifier.guess("Archive/Inbox"))
        assertEquals(FolderType.CUSTOM.name, FolderClassifier.guess("INBOX.Inbox", '.'))
    }

    @Test
    fun `special-use attributes win over the folder name`() {
        assertEquals(
            FolderType.ARCHIVE.name,
            FolderClassifier.guess("Weird Name", '/', listOf("\\Archive"))
        )
        assertEquals(
            FolderType.TRASH.name,
            FolderClassifier.guess("Something", '/', listOf("\\HasNoChildren", "\\Trash"))
        )
    }

    @Test
    fun `well-known names are matched on the leaf, using the server delimiter`() {
        assertEquals(FolderType.SENT.name, FolderClassifier.guess("INBOX.Sent", '.'))
        assertEquals(FolderType.TRASH.name, FolderClassifier.guess("INBOX.Trash", '.'))
        assertEquals(FolderType.DRAFTS.name, FolderClassifier.guess("Drafts"))
        assertEquals(FolderType.SPAM.name, FolderClassifier.guess("Junk"))
        assertEquals(FolderType.ARCHIVE.name, FolderClassifier.guess("Archiv"))
    }

    @Test
    fun `a dotted folder name is not split when the delimiter is a slash`() {
        // Splitting on "." regardless of delimiter used to turn this into "de".
        assertEquals(FolderType.CUSTOM.name, FolderClassifier.guess("Projects/uwumail.de", '/'))
    }

    @Test
    fun `inbox sorts before every other folder`() {
        val folders = listOf(
            "Archive" to FolderType.ARCHIVE.name,
            "Zebra" to FolderType.CUSTOM.name,
            "INBOX" to FolderType.INBOX.name,
            "Trash" to FolderType.TRASH.name,
            "Apples" to FolderType.CUSTOM.name,
            "Sent" to FolderType.SENT.name,
            "Notes" to FolderType.LOCAL.name
        )
        val ordered = FolderClassifier.order(folders, { it.second }, { it.first }).map { it.first }

        assertEquals("INBOX", ordered.first())
        assertEquals(
            listOf("INBOX", "Sent", "Archive", "Trash", "Apples", "Zebra", "Notes"),
            ordered
        )
    }

    @Test
    fun `custom folders are alphabetical and case-insensitive`() {
        val folders = listOf("banana", "Apple", "cherry", "Beta")
            .map { it to FolderType.CUSTOM.name }
        assertEquals(
            listOf("Apple", "banana", "Beta", "cherry"),
            FolderClassifier.order(folders, { it.second }, { it.first }).map { it.first }
        )
    }

    @Test
    fun `device folders always come last`() {
        val folders = listOf(
            "Saved" to FolderType.LOCAL.name,
            "Work" to FolderType.CUSTOM.name,
            "INBOX" to FolderType.INBOX.name
        )
        assertEquals(
            "Saved",
            FolderClassifier.order(folders, { it.second }, { it.first }).last().first
        )
    }
}
