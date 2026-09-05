package de.uwumail.mail

import de.uwumail.core.Security
import de.uwumail.data.db.AccountEntity
import de.uwumail.data.db.OutboxEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Date
import java.util.Properties
import javax.activation.DataHandler
import javax.activation.FileDataSource
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

class SmtpSender {

    /**
     * Builds and sends [item]. The From header is taken verbatim from the outbox
     * entry, which is what makes arbitrary sender addresses work on a server that
     * permits them; [AccountEntity.useIdentityAsEnvelopeSender] decides whether
     * `MAIL FROM` follows it or stays on the account address.
     */
    suspend fun send(account: AccountEntity, password: String, item: OutboxEntity): ByteArray =
        withContext(Dispatchers.IO) {
            val security = runCatching { Security.valueOf(account.smtpSecurity) }
                .getOrDefault(Security.STARTTLS)
            val envelopeFrom =
                if (account.useIdentityAsEnvelopeSender) item.fromAddress else account.email

            val props = Properties().apply {
                put("mail.transport.protocol", "smtp")
                put("mail.smtp.host", account.smtpHost)
                put("mail.smtp.port", account.smtpPort.toString())
                put("mail.smtp.auth", "true")
                put("mail.smtp.connectiontimeout", "20000")
                put("mail.smtp.timeout", "40000")
                put("mail.smtp.writetimeout", "40000")
                put("mail.smtp.from", envelopeFrom)
                when (security) {
                    Security.SSL_TLS -> {
                        put("mail.smtp.ssl.enable", "true")
                    }
                    Security.STARTTLS -> {
                        put("mail.smtp.starttls.enable", "true")
                        put("mail.smtp.starttls.required", "true")
                    }
                    Security.NONE -> Unit
                }
                if (account.trustAllCerts) put("mail.smtp.ssl.trust", "*")
            }

            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication() =
                    PasswordAuthentication(account.smtpUsername, password)
            })

            val message = buildMessage(session, item)
            try {
                Transport.send(message)
            } catch (e: Exception) {
                throw MailException("SMTP send failed: ${e.message}", e)
            }
            ByteArrayOutputStream().also { message.writeTo(it) }.toByteArray()
        }

    fun buildMessage(session: Session, item: OutboxEntity): MimeMessage {
        val message = MimeMessage(session)
        message.setFrom(InternetAddress(item.fromAddress, item.fromName, "UTF-8"))
        item.replyTo?.takeIf { it.isNotBlank() }?.let {
            message.replyTo = InternetAddress.parse(it, false)
        }
        addRecipients(message, Message.RecipientType.TO, item.to)
        addRecipients(message, Message.RecipientType.CC, item.cc)
        addRecipients(message, Message.RecipientType.BCC, item.bcc)
        message.setSubject(item.subject, "UTF-8")
        message.sentDate = Date(item.createdAt)
        item.inReplyTo?.let { message.setHeader("In-Reply-To", it) }
        item.references?.let { message.setHeader("References", it) }
        message.setHeader("User-Agent", "uwuMail")

        val attachments = item.attachmentPaths.split('\n')
            .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            .map(::File)
            .filter { it.exists() }

        val hasHtml = !item.bodyHtml.isNullOrBlank()

        when {
            attachments.isEmpty() && !hasHtml -> message.setText(item.bodyPlain, "UTF-8")
            else -> {
                val bodyPart = if (hasHtml) {
                    MimeBodyPart().apply {
                        val alternative = MimeMultipart("alternative")
                        alternative.addBodyPart(MimeBodyPart().apply {
                            setText(item.bodyPlain, "UTF-8")
                        })
                        alternative.addBodyPart(MimeBodyPart().apply {
                            setContent(item.bodyHtml, "text/html; charset=UTF-8")
                        })
                        setContent(alternative)
                    }
                } else {
                    MimeBodyPart().apply { setText(item.bodyPlain, "UTF-8") }
                }

                if (attachments.isEmpty()) {
                    message.setContent(bodyPart.content as MimeMultipart)
                } else {
                    val mixed = MimeMultipart("mixed")
                    mixed.addBodyPart(bodyPart)
                    attachments.forEach { file ->
                        mixed.addBodyPart(MimeBodyPart().apply {
                            dataHandler = DataHandler(FileDataSource(file))
                            fileName = MimeUtil.sanitizeFileName(file.name)
                        })
                    }
                    message.setContent(mixed)
                }
            }
        }
        message.saveChanges()
        return message
    }

    private fun addRecipients(message: MimeMessage, type: Message.RecipientType, raw: String) {
        val cleaned = raw.split(',', ';')
            .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
        if (cleaned.isEmpty()) return
        message.setRecipients(type, InternetAddress.parse(cleaned.joinToString(","), false))
    }
}
