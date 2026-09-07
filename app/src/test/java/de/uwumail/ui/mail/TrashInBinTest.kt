package de.uwumail.ui.mail

import de.uwumail.core.SwipeAction
import de.uwumail.data.db.AccountEntity
import de.uwumail.data.db.FolderEntity
import de.uwumail.data.db.MessageSummary
import de.uwumail.data.db.TestDatabase
import de.uwumail.data.db.isBinFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Trashing mail that is already in the bin.
 *
 * There is nowhere left to move it to, so the gesture did nothing at all —
 * dead on exactly the screen where somebody is trying to be rid of something.
 * A swipe becomes the deletion it was reaching for, and the bin button on a
 * selection does each half of a mixed one properly rather than half the job.
 */
class TrashInBinTest {

    private val account = TestDatabase.account().copy(id = 1, trashFolder = "Trash")

    private val inbox = FolderEntity(
        id = 1, accountId = 1, path = "INBOX", displayName = "Inbox", type = "INBOX"
    )
    private val bin = FolderEntity(
        id = 2, accountId = 1, path = "Trash", displayName = "Trash", type = "TRASH"
    )
    private val device = FolderEntity(
        id = 3, accountId = 1, path = "local/Keepsakes", displayName = "Keepsakes",
        type = "LOCAL", isLocal = true
    )

    private fun row(id: Long, folderId: Long) = MessageSummary(
        id = id, accountId = 1, folderId = folderId, uid = id, subject = "Message $id",
        fromName = null, fromAddress = null, toList = "", receivedAt = id,
        seen = false, flagged = false, answered = false, hasAttachments = false,
        sizeBytes = 0, preview = "", isLocal = false, bodyDownloaded = false, spam = false
    )

    private fun state(
        rows: List<MessageSummary>,
        selection: Set<Long>,
        folders: List<FolderEntity> = listOf(inbox, bin, device),
        accounts: List<AccountEntity> = listOf(account)
    ) = MailUiState(
        accounts = accounts, folders = folders, messages = rows, selection = selection
    )

    // ------------------------------------------------------------ the swipe

    @Test
    fun `a trash swipe on mail already in the bin becomes a deletion`() {
        assertEquals(SwipeAction.DELETE, SwipeAction.TRASH.inBin(true))
    }

    @Test
    fun `a trash swipe anywhere else still trashes`() {
        assertEquals(SwipeAction.TRASH, SwipeAction.TRASH.inBin(false))
    }

    @Test
    fun `the deletion it becomes is one that asks first`() {
        val swipe = SwipeAction.TRASH.inBin(true)

        assertTrue(swipe.needsConfirmation)
        assertTrue(swipe.carriesRowAway)
    }

    @Test
    fun `no other swipe changes in the bin`() {
        SwipeAction.entries.filter { it != SwipeAction.TRASH }.forEach { action ->
            assertEquals(action, action.inBin(true))
        }
    }

    @Test
    fun `a swipe already set to delete is unchanged`() {
        assertEquals(SwipeAction.DELETE, SwipeAction.DELETE.inBin(true))
    }

    // ------------------------------------------------- which folders are bins

    @Test
    fun `the account's trash folder is a bin`() {
        assertTrue(bin.isBinFor(account))
    }

    @Test
    fun `the inbox is not`() {
        assertFalse(inbox.isBinFor(account))
    }

    @Test
    fun `a device folder named trash is not`() {
        assertFalse(device.copy(path = "Trash").isBinFor(account))
    }

    // -------------------------------------------------------- the selection

    @Test
    fun `a selection entirely in the bin is all for deleting`() {
        val split = state(
            rows = listOf(row(1, bin.id), row(2, bin.id)),
            selection = setOf(1, 2)
        ).selectionByBin()

        assertEquals(listOf(1L, 2L), split.first)
        assertTrue(split.second.isEmpty())
    }

    @Test
    fun `a selection with nothing in the bin is all for trashing`() {
        val split = state(
            rows = listOf(row(1, inbox.id), row(2, inbox.id)),
            selection = setOf(1, 2)
        ).selectionByBin()

        assertTrue(split.first.isEmpty())
        assertEquals(listOf(1L, 2L), split.second)
    }

    @Test
    fun `a mixed selection is split down the middle`() {
        val split = state(
            rows = listOf(row(1, inbox.id), row(2, bin.id), row(3, inbox.id), row(4, bin.id)),
            selection = setOf(1, 2, 3, 4)
        ).selectionByBin()

        assertEquals(listOf(2L, 4L), split.first)
        assertEquals(listOf(1L, 3L), split.second)
    }

    @Test
    fun `rows that were not selected are left out of both halves`() {
        val split = state(
            rows = listOf(row(1, inbox.id), row(2, bin.id), row(3, bin.id)),
            selection = setOf(2)
        ).selectionByBin()

        assertEquals(listOf(2L), split.first)
        assertTrue(split.second.isEmpty())
    }

    @Test
    fun `mail in a device folder is trashed rather than wiped`() {
        val split = state(
            rows = listOf(row(1, device.id)),
            selection = setOf(1)
        ).selectionByBin()

        assertTrue(split.first.isEmpty())
        assertEquals(listOf(1L), split.second)
    }

    @Test
    fun `each account's own bin counts, not another's`() {
        val other = TestDatabase.account("other@example.com").copy(id = 2, trashFolder = "Bin")
        val theirBin = FolderEntity(
            id = 9, accountId = 2, path = "Bin", displayName = "Bin", type = "CUSTOM"
        )
        val split = state(
            rows = listOf(row(1, inbox.id), row(2, theirBin.id)),
            selection = setOf(1, 2),
            folders = listOf(inbox, bin, theirBin),
            accounts = listOf(account, other)
        ).selectionByBin()

        assertEquals(listOf(2L), split.first)
        assertEquals(listOf(1L), split.second)
    }

    @Test
    fun `an empty selection splits into nothing at all`() {
        val split = state(rows = listOf(row(1, bin.id)), selection = emptySet()).selectionByBin()

        assertTrue(split.first.isEmpty())
        assertTrue(split.second.isEmpty())
    }
}
