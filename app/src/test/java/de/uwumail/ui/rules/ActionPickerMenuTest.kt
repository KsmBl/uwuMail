package de.uwumail.ui.rules

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import de.uwumail.core.ActionType
import de.uwumail.data.db.AccountEntity
import de.uwumail.data.db.FolderEntity
import de.uwumail.data.db.TestDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Adding a "then" to a rule.
 *
 * The actions needing a folder were drawn from `"${type.label}…"`, and a label
 * is a string *resource id* — so the three of them arrived in the menu as bare
 * numbers, which then opened onto every folder of every account in one flat
 * list where the same names repeat once per mailbox. Both halves are checked
 * here: the actions say what they are, and the folder is reached by being asked
 * which mailbox first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ActionPickerMenuTest {

    @get:Rule
    val compose = createComposeRule()

    private val first = TestDatabase.account("first@example.com").copy(id = 1)
    private val second = TestDatabase.account("second@example.com").copy(id = 2)

    /** The same folder name on both accounts, which is the ambiguity in question. */
    private val folders = listOf(
        folder(id = 10, accountId = 1, path = "Archive"),
        folder(id = 11, accountId = 1, path = "Lists.Kotlin", name = "Kotlin"),
        folder(id = 12, accountId = 2, path = "Archive"),
        folder(id = 13, accountId = 1, path = "local/Keepsakes", name = "Keepsakes", local = true)
    )

    private fun folder(
        id: Long,
        accountId: Long,
        path: String,
        name: String = path,
        local: Boolean = false
    ) = FolderEntity(
        id = id,
        accountId = accountId,
        path = path,
        displayName = name,
        type = if (local) "LOCAL" else "CUSTOM",
        isLocal = local,
        syncEnabled = !local
    )

    private var picked: Pair<ActionType, String?>? = null

    private fun show(accounts: List<AccountEntity>) {
        compose.setContent {
            Column {
                ActionPickerMenu(
                    types = listOf(
                        ActionType.MARK_READ,
                        ActionType.MOVE_TO_FOLDER,
                        ActionType.MOVE_TO_LOCAL
                    ),
                    folders = folders,
                    accounts = accounts,
                    onPick = { type, arg -> picked = type to arg }
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `the folder actions are named rather than numbered`() {
        show(listOf(first, second))

        compose.onNodeWithText("Move to folder…").assertIsDisplayed()
        compose.onNodeWithText("Move to local folder…").assertIsDisplayed()
    }

    /** An action that needs nothing else is added on the spot, with no ellipsis. */
    @Test
    fun `an action that asks nothing is added straight away`() {
        show(listOf(first, second))

        compose.onNodeWithText("Mark as read").performClick()

        assertEquals(ActionType.MARK_READ to null, picked)
    }

    @Test
    fun `the mailbox is asked for before the folder`() {
        show(listOf(first, second))

        compose.onNodeWithText("Move to folder…").performClick()

        compose.onNodeWithText("first@example.com").assertIsDisplayed()
        compose.onNodeWithText("second@example.com").assertIsDisplayed()
        compose.onNodeWithText("Which mailbox?").assertIsDisplayed()
        // The ambiguous name is not on screen until a mailbox has been named.
        compose.onNodeWithText("Kotlin").assertDoesNotExist()
    }

    @Test
    fun `only the chosen mailbox's folders are offered`() {
        show(listOf(first, second))

        compose.onNodeWithText("Move to folder…").performClick()
        compose.onNodeWithText("second@example.com").performClick()

        compose.onNodeWithText("Archive").assertIsDisplayed()
        compose.onNodeWithText("Kotlin").assertDoesNotExist()
    }

    /** One mailbox is not a question, so it is not asked. */
    @Test
    fun `with a single account the folders come straight up`() {
        show(listOf(first))

        compose.onNodeWithText("Move to folder…").performClick()

        compose.onNodeWithText("Kotlin").assertIsDisplayed()
        compose.onNodeWithText("first@example.com").assertDoesNotExist()
    }

    /**
     * The folder is remembered as its path, not as one account's folder, so the
     * rule still means something on every account that has that path.
     */
    @Test
    fun `the folder is reported as its path`() {
        show(listOf(first))

        compose.onNodeWithText("Move to folder…").performClick()
        compose.onNodeWithText("Kotlin").performClick()

        assertEquals(ActionType.MOVE_TO_FOLDER to "Lists.Kotlin", picked)
    }

    @Test
    fun `a device folder is reported by its name without the local prefix`() {
        show(listOf(first))

        compose.onNodeWithText("Move to local folder…").performClick()
        compose.onNodeWithText("Keepsakes").performClick()

        assertEquals(ActionType.MOVE_TO_LOCAL to "Keepsakes", picked)
    }

    @Test
    fun `a move to the device offers only the device's folders`() {
        show(listOf(first))

        compose.onNodeWithText("Move to local folder…").performClick()

        compose.onNodeWithText("Keepsakes").assertIsDisplayed()
        compose.onNodeWithText("Archive").assertDoesNotExist()
    }

    @Test
    fun `a move on the server does not offer the device's folders`() {
        show(listOf(first))

        compose.onNodeWithText("Move to folder…").performClick()

        compose.onNodeWithText("Keepsakes").assertDoesNotExist()
    }

    @Test
    fun `the way back out of the folder list is the action list`() {
        show(listOf(first))

        compose.onNodeWithText("Move to folder…").performClick()
        compose.onNodeWithText("Which folder?").performClick()

        compose.onNodeWithText("Move to folder…").assertIsDisplayed()
        assertNull(picked)
    }

    @Test
    fun `the way back out of the folder list is the mailbox list`() {
        show(listOf(first, second))

        compose.onNodeWithText("Move to folder…").performClick()
        compose.onNodeWithText("first@example.com").performClick()
        compose.onNodeWithText("Which folder?").performClick()

        compose.onNodeWithText("Which mailbox?").assertIsDisplayed()
    }

    /** Every action that asks for a folder wants one kind of folder or the other. */
    @Test
    fun `each folder action knows which kind of folder it means`() {
        val asking = ActionType.entries.filter { it.needsTargetFolder }
        assertEquals(4, asking.size)
        assertTrue(asking.filter { it.wantsLocalFolder }.size == 2)
    }
}
