package de.uwumail.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which folders and which accounts a rule runs in.
 *
 * It used to be one folder or all of them, which cannot say the commonest
 * thing anybody wants: run this over the inbox and the spam folder and nowhere
 * else. The chosen paths live newline-separated in the column that already
 * held one, because IMAP forbids CR and LF in a mailbox name — so nothing can
 * contain the separator, and every rule written before this is still one path
 * that reads back unchanged with nothing to migrate.
 */
class RuleFolderScopeTest {

    private fun rule(scope: String?) = RuleEntity(name = "rule", folderPath = scope)

    /** The old shape, which has to keep working exactly as it did. */
    @Test
    fun `a rule naming one folder still names that folder`() {
        val rule = rule("INBOX")

        assertEquals(listOf("INBOX"), rule.folderPaths)
        assertTrue(rule.appliesToFolder("INBOX"))
        assertFalse(rule.appliesToFolder("Archive"))
    }

    @Test
    fun `a rule naming no folder runs everywhere`() {
        val rule = rule(null)

        assertTrue(rule.folderPaths.isEmpty())
        assertTrue(rule.appliesToFolder("INBOX"))
        assertTrue(rule.appliesToFolder("Anything At All"))
    }

    @Test
    fun `a rule naming several folders runs in each of them`() {
        val rule = rule(folderScopeOf(listOf("INBOX", "Spamverdacht")))

        assertEquals(listOf("INBOX", "Spamverdacht"), rule.folderPaths)
        assertTrue(rule.appliesToFolder("INBOX"))
        assertTrue(rule.appliesToFolder("Spamverdacht"))
        assertFalse(rule.appliesToFolder("Archive"))
    }

    /** Folder names are matched the way the rest of the app matches them. */
    @Test
    fun `the folder is matched without regard to case`() {
        assertTrue(rule(folderScopeOf(listOf("INBOX"))).appliesToFolder("inbox"))
    }

    @Test
    fun `nested paths keep their separators`() {
        val rule = rule(folderScopeOf(listOf("Lists/Kotlin", "INBOX.Work")))

        assertTrue(rule.appliesToFolder("Lists/Kotlin"))
        assertTrue(rule.appliesToFolder("INBOX.Work"))
        assertFalse(rule.appliesToFolder("Lists"))
    }

    @Test
    fun `choosing nothing is every folder rather than none`() {
        assertNull(folderScopeOf(emptyList()))
        assertTrue(rule(folderScopeOf(emptyList())).appliesToFolder("INBOX"))
    }

    /** Ticking the same folder twice is one folder, not two. */
    @Test
    fun `a folder chosen twice is stored once`() {
        assertEquals(listOf("INBOX"), rule(folderScopeOf(listOf("INBOX", "INBOX"))).folderPaths)
    }

    @Test
    fun `blank entries are not folders`() {
        assertNull(folderScopeOf(listOf("", "   ")))
        assertEquals(listOf("INBOX"), rule(folderScopeOf(listOf("INBOX", ""))).folderPaths)
    }

    /** Whatever is written has to read back as the same set of folders. */
    @Test
    fun `a scope survives being stored and read again`() {
        val chosen = listOf("INBOX", "Spamverdacht", "Lists/Kotlin")

        assertEquals(chosen, rule(folderScopeOf(chosen)).folderPaths)
    }

    /** A row left by an older version, or edited by hand, must not become a folder named "". */
    @Test
    fun `a stray separator does not become an empty folder`() {
        assertEquals(listOf("INBOX"), rule("INBOX\n").folderPaths)
        assertEquals(listOf("INBOX", "Archive"), rule("INBOX\n\nArchive").folderPaths)
    }
    // ---------------------------------------------------------------- accounts

    private fun onAccounts(vararg ids: Long) =
        RuleEntity(name = "rule", accountIds = accountScopeOf(ids.toList()))

    @Test
    fun `a rule naming no account runs on every account`() {
        val rule = RuleEntity(name = "rule")

        assertTrue(rule.accountIdsIn.isEmpty())
        assertTrue(rule.appliesToAccount(1))
        assertTrue(rule.appliesToAccount(99))
    }

    @Test
    fun `a rule naming one account runs only on it`() {
        val rule = onAccounts(7)

        assertTrue(rule.appliesToAccount(7))
        assertFalse(rule.appliesToAccount(8))
    }

    @Test
    fun `a rule naming several accounts runs on each of them`() {
        val rule = onAccounts(7, 9)

        assertEquals(listOf(7L, 9L), rule.accountIdsIn)
        assertTrue(rule.appliesToAccount(7))
        assertTrue(rule.appliesToAccount(9))
        assertFalse(rule.appliesToAccount(8))
    }

    @Test
    fun `choosing no account is every account rather than none`() {
        assertNull(accountScopeOf(emptyList()))
        assertTrue(RuleEntity(name = "r", accountIds = accountScopeOf(emptyList())).appliesToAccount(1))
    }

    @Test
    fun `an account chosen twice is stored once`() {
        assertEquals(listOf(7L), onAccounts(7, 7).accountIdsIn)
    }

    /**
     * A row written by hand, or left by something that did not know the shape,
     * must not become an account id of zero — which would scope the rule to a
     * mailbox that does not exist and quietly stop it doing anything.
     */
    @Test
    fun `unreadable ids are dropped rather than becoming account zero`() {
        val rule = RuleEntity(name = "rule", accountIds = "7\nnonsense\n\n9")

        assertEquals(listOf(7L, 9L), rule.accountIdsIn)
    }


}
