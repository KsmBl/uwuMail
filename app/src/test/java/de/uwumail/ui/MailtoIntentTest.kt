package de.uwumail.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Being offered as a mail app.
 *
 * The composer handled a VIEW intent carrying a mailto: URI, and the manifest
 * only ever advertised SENDTO — so tapping an address on a web page, which is
 * dispatched as VIEW, did not offer uwuMail at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MailtoIntentTest {

    private val packages =
        ApplicationProvider.getApplicationContext<android.content.Context>().packageManager

    private fun resolves(action: String, uri: String): Boolean {
        val intent = Intent(action, Uri.parse(uri)).addCategory(Intent.CATEGORY_DEFAULT)
        return packages.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .any { it.activityInfo?.name == MainActivity::class.java.name }
    }

    @Test
    fun `a mailto link from a web page is offered to us`() {
        assertTrue(resolves(Intent.ACTION_VIEW, "mailto:someone@example.com"))
    }

    @Test
    fun `a mailto link with a subject and body is offered to us`() {
        assertTrue(
            resolves(Intent.ACTION_VIEW, "mailto:someone@example.com?subject=Hello&body=Hi%20there")
        )
    }

    @Test
    fun `the send-to route still works`() {
        assertTrue(resolves(Intent.ACTION_SENDTO, "mailto:someone@example.com"))
    }

    @Test
    fun `a bare mailto with no address is still ours`() {
        assertTrue(resolves(Intent.ACTION_VIEW, "mailto:"))
    }

    @Test
    fun `a photo shared from another app is offered to us`() {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("image/jpeg")
            .addCategory(Intent.CATEGORY_DEFAULT)

        assertTrue(
            packages.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .any { it.activityInfo?.name == MainActivity::class.java.name }
        )
    }

    @Test
    fun `several files shared at once are offered to us`() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType("*/*")
            .addCategory(Intent.CATEGORY_DEFAULT)

        assertTrue(
            packages.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .any { it.activityInfo?.name == MainActivity::class.java.name }
        )
    }

    /** Proof that the resolver above discriminates rather than agreeing to anything. */
    @Test
    fun `an ordinary web page is not ours to open`() {
        assertFalse(resolves(Intent.ACTION_VIEW, "https://example.com"))
    }
}
