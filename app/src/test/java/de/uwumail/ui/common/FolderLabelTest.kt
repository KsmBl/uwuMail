package de.uwumail.ui.common

import de.uwumail.data.db.AccountEntity
import de.uwumail.data.db.FolderEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderLabelTest {

    private fun account(id: Long, email: String) = AccountEntity(
        id = id,
        displayName = email.substringBefore('@'),
        email = email,
        color = 0,
        imapHost = "imap.$email", imapPort = 993, imapSecurity = "SSL_TLS", imapUsername = email,
        smtpHost = "smtp.$email", smtpPort = 465, smtpSecurity = "SSL_TLS", smtpUsername = email
    )

    private fun folder(id: Long, accountId: Long, name: String, local: Boolean = false) =
        FolderEntity(
            id = id,
            accountId = accountId,
            path = if (local) "local/$name" else name,
            displayName = name,
            isLocal = local
        )

    private val accounts = listOf(
        account(1, "xyz@gmail.com"),
        account(2, "abc@samplemail.de")
    )

    @Test
    fun `names the mailbox in brackets before the folder`() {
        assertEquals(
            "[xyz@gmail.com] Archive",
            folderLabel(folder(10, 1, "Archive"), accounts)
        )
        assertEquals(
            "[abc@samplemail.de] Archive",
            folderLabel(folder(11, 2, "Archive"), accounts)
        )
    }

    @Test
    fun `labels a device folder the same way`() {
        assertEquals(
            "[xyz@gmail.com] Receipts",
            folderLabel(folder(12, 1, "Receipts", local = true), accounts)
        )
    }

    @Test
    fun `falls back to the bare name when the account is unknown`() {
        assertEquals("Archive", folderLabel(folder(13, 99, "Archive"), accounts))
    }
}
