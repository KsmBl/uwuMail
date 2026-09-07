package de.uwumail.ui.mail

import de.uwumail.data.db.FolderEntity
import de.uwumail.data.db.MessageEntity
import de.uwumail.data.db.TestDatabase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether an open message is already in the bin.
 *
 * Trashing a message that is already in the trash has nowhere to move it to and
 * does nothing at all, which left the bin button dead on exactly the screen
 * where somebody most wants to be rid of something. It now offers to finish the
 * job, so what counts as "in the trash" has to be right.
 */
class InTrashTest {

    private val account = TestDatabase.account().copy(id = 1, trashFolder = "Trash")

    private fun folder(
        id: Long,
        path: String,
        type: String = "CUSTOM",
        local: Boolean = false
    ) = FolderEntity(
        id = id,
        accountId = 1,
        path = path,
        displayName = path,
        type = type,
        isLocal = local
    )

    private fun state(
        folderId: Long,
        folders: List<FolderEntity>,
        trashFolder: String? = "Trash"
    ) = MessageUiState(
        message = MessageEntity(accountId = 1, folderId = folderId, uid = 1),
        folders = folders,
        accounts = listOf(account.copy(trashFolder = trashFolder))
    )

    @Test
    fun `a message in the account's own trash folder is in the bin`() {
        val trash = folder(id = 5, path = "Trash", type = "TRASH")

        assertTrue(state(folderId = 5, folders = listOf(trash)).isInTrash)
    }

    @Test
    fun `a message in the inbox is not`() {
        val inbox = folder(id = 1, path = "INBOX", type = "INBOX")

        assertFalse(state(folderId = 1, folders = listOf(inbox)).isInTrash)
    }

    @Test
    fun `a folder the server called trash counts even with no routing set`() {
        val trash = folder(id = 5, path = "Deleted Items", type = "TRASH")

        assertTrue(state(folderId = 5, folders = listOf(trash), trashFolder = null).isInTrash)
    }

    @Test
    fun `a folder chosen by hand counts even when the server never classified it`() {
        // A server with no SPECIAL-USE attributes leaves every folder CUSTOM.
        val chosen = folder(id = 7, path = "Trash", type = "CUSTOM")

        assertTrue(state(folderId = 7, folders = listOf(chosen)).isInTrash)
    }

    @Test
    fun `a device folder is never the bin`() {
        // Its mail exists nowhere else, so "delete permanently" would be the
        // only copy going — and it is not what the account trashes to anyway.
        val keepsakes = folder(id = 9, path = "Trash", type = "TRASH", local = true)

        assertFalse(state(folderId = 9, folders = listOf(keepsakes)).isInTrash)
    }

    @Test
    fun `an archive named nothing like trash is not the bin`() {
        val archive = folder(id = 3, path = "Archive", type = "ARCHIVE")

        assertFalse(state(folderId = 3, folders = listOf(archive)).isInTrash)
    }

    @Test
    fun `a message with no folder loaded yet is not assumed to be in the bin`() {
        assertFalse(state(folderId = 5, folders = emptyList()).isInTrash)
    }

    @Test
    fun `a screen with no message at all is not in the bin`() {
        assertFalse(MessageUiState().isInTrash)
    }

    @Test
    fun `the right folder is picked out of several`() {
        val folders = listOf(
            folder(id = 1, path = "INBOX", type = "INBOX"),
            folder(id = 3, path = "Archive", type = "ARCHIVE"),
            folder(id = 5, path = "Trash", type = "TRASH")
        )

        assertFalse(state(folderId = 1, folders = folders).isInTrash)
        assertTrue(state(folderId = 5, folders = folders).isInTrash)
    }
}
