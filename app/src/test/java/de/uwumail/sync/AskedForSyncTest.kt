package de.uwumail.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Asking for mail is not the app checking on its own.
 *
 * The hours in *Only check at set times* were meant to govern unattended
 * checking, and the setting says so. Two things that are anything but
 * unattended went through the same worker and were dropped on the same rule:
 * **Sync now**, and the first fetch after adding an account — so a mailbox
 * added in the evening with a daytime window stayed empty until morning with
 * nothing on screen to say why.
 *
 * Pulling down in the list never went through here and was never affected;
 * these are the two that were.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AskedForSyncTest {

    @Test
    fun `a check the app makes on its own waits for the window`() {
        assertFalse(SyncWorker.runsNow(userAsked = false, allowedNow = false))
    }

    @Test
    fun `and runs inside it`() {
        assertTrue(SyncWorker.runsNow(userAsked = false, allowedNow = true))
    }

    @Test
    fun `a sync somebody asked for runs whatever the hours say`() {
        assertTrue(SyncWorker.runsNow(userAsked = true, allowedNow = false))
    }

    @Test
    fun `and is not held back inside the window either`() {
        assertTrue(SyncWorker.runsNow(userAsked = true, allowedNow = true))
    }

    @Test
    fun `Sync now says it was asked for`() {
        val input = SyncScheduler.askedForInput(-1L)

        assertTrue(input.getBoolean(SyncWorker.KEY_USER_ASKED, false))
    }

    /** A freshly added account fetches straight away, at any hour. */
    @Test
    fun `a new account's first fetch says it was asked for`() {
        val input = SyncScheduler.askedForInput(7L)

        assertTrue(input.getBoolean(SyncWorker.KEY_USER_ASKED, false))
        assertTrue(SyncWorker.runsNow(input.getBoolean(SyncWorker.KEY_USER_ASKED, false), false))
    }

    /** The periodic check must not claim it: that is what the hours are for. */
    @Test
    fun `the unattended check does not say it was asked for`() {
        val input = SyncScheduler.periodicInput(7L)

        assertFalse(input.getBoolean(SyncWorker.KEY_USER_ASKED, false))
        assertFalse(SyncWorker.runsNow(input.getBoolean(SyncWorker.KEY_USER_ASKED, false), false))
    }

    @Test
    fun `either way the account it is for is carried along`() {
        assertTrue(SyncScheduler.askedForInput(7L).getLong(SyncWorker.KEY_ACCOUNT_ID, -1L) == 7L)
        assertTrue(SyncScheduler.periodicInput(7L).getLong(SyncWorker.KEY_ACCOUNT_ID, -1L) == 7L)
    }
}
