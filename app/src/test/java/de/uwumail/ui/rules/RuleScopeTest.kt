package de.uwumail.ui.rules

import de.uwumail.core.ActionType
import de.uwumail.data.db.FolderEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which mailboxes a rule runs on, and what that leaves to browse.
 *
 * The wizard used to derive its scope from the selected mail and never say so:
 * mail from one account made a rule pinned to that account, mail from two made
 * one that ran on every account, and nothing on the screen mentioned either.
 * It is a question now, and the answer also decides which folders the action
 * menu has to offer.
 */
class RuleScopeTest {

    private val folders = listOf(
        folder(id = 10, accountId = 1, path = "Archive"),
        folder(id = 11, accountId = 2, path = "Archive"),
        folder(id = 12, accountId = 2, path = "Lists")
    )

    private fun folder(id: Long, accountId: Long, path: String) = FolderEntity(
        id = id,
        accountId = accountId,
        path = path,
        displayName = path,
        type = "CUSTOM"
    )

    @Test
    fun `a rule on every account may be sent to any account's folder`() {
        val state = WizardState(folders = folders, accountId = null)

        assertEquals(3, state.foldersForScope().size)
    }

    @Test
    fun `a rule pinned to one account only browses that account`() {
        val state = WizardState(folders = folders, accountId = 2L)

        assertEquals(listOf(11L, 12L), state.foldersForScope().map { it.id })
    }

    @Test
    fun `the editor scopes its folders the same way`() {
        val state = RuleEditState(folders = folders, accountId = 1L)

        assertEquals(listOf(10L), state.foldersForScope().map { it.id })
    }

    /**
     * An action is remembered as a path, not as a folder id, so widening a rule
     * to every account leaves what it does alone — the path exists on the other
     * mailboxes too, or it does not, and that is a question for sync.
     */
    @Test
    fun `widening the scope does not disturb the actions already picked`() {
        val state = WizardState(
            folders = folders,
            accountId = 2L,
            actions = listOf(PendingAction(ActionType.MOVE_TO_FOLDER, "Lists"))
        )

        val widened = state.copy(accountId = null)

        assertEquals(state.actions, widened.actions)
        assertTrue(widened.foldersForScope().any { it.path == "Lists" })
    }

    @Test
    fun `a rule is saveable only once it has a condition and something to do`() {
        val bare = WizardState(name = "Newsletters")
        assertTrue(!bare.canSave)

        val ready = bare.copy(
            selected = setOf(0),
            actions = listOf(PendingAction(ActionType.MARK_READ, null))
        )
        assertTrue(ready.canSave)
    }
}
