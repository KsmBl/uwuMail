package de.uwumail.ui.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.uwumail.core.Json
import de.uwumail.data.db.AccountEntity
import de.uwumail.data.db.IdentityEntity
import de.uwumail.data.db.OutboxEntity
import de.uwumail.di.AppContainer
import de.uwumail.mail.MimeUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

data class ComposeUiState(
    val accounts: List<AccountEntity> = emptyList(),
    val identities: List<IdentityEntity> = emptyList(),
    val accountId: Long = 0,
    /** The literal From address that goes on the wire. Free text on purpose. */
    val fromAddress: String = "",
    val fromName: String = "",
    val to: String = "",
    val cc: String = "",
    val bcc: String = "",
    val subject: String = "",
    val body: String = "",
    val inReplyTo: String? = null,
    val references: String? = null,
    val showCcBcc: Boolean = false,
    val sending: Boolean = false,
    val sent: Boolean = false,
    val error: String? = null
) {
    val account: AccountEntity? get() = accounts.firstOrNull { it.id == accountId }
    val accountIdentities: List<IdentityEntity> get() = identities.filter { it.accountId == accountId }
    val canSend: Boolean
        get() = to.isNotBlank() && fromAddress.contains('@') && !sending
}

/**
 * Compose state, including the arbitrary From address.
 *
 * [ComposeUiState.fromAddress] is never constrained to the account's own address:
 * the server decides what it will accept, and this app is built for servers that
 * allow sending as any address on the domain.
 */
class ComposeViewModel(
    private val container: AppContainer,
    private val accountIdHint: Long,
    private val replyToMessageId: Long,
    private val replyAll: Boolean,
    private val forwardMessageId: Long,
    private val mailto: String?
) : ViewModel() {

    private val _state = MutableStateFlow(ComposeUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val accounts = container.db.accountDao().getAll()
            val identities = accounts.flatMap { container.db.identityDao().forAccount(it.id) }
            val accountId = when {
                accountIdHint > 0 -> accountIdHint
                else -> accounts.firstOrNull()?.id ?: 0
            }
            val defaultIdentity = identities.firstOrNull { it.accountId == accountId && it.isDefault }
                ?: identities.firstOrNull { it.accountId == accountId }
            val account = accounts.firstOrNull { it.id == accountId }

            _state.update {
                it.copy(
                    accounts = accounts,
                    identities = identities,
                    accountId = accountId,
                    fromAddress = defaultIdentity?.email ?: account?.email.orEmpty(),
                    fromName = defaultIdentity?.displayName ?: account?.displayName.orEmpty(),
                    body = account?.signature?.let { sig -> "\n\n-- \n$sig" }.orEmpty()
                )
            }

            when {
                replyToMessageId > 0 -> prefillReply(replyToMessageId, replyAll)
                forwardMessageId > 0 -> prefillForward(forwardMessageId)
                mailto != null -> prefillMailto(mailto)
            }
        }
    }

    private suspend fun prefillReply(messageId: Long, all: Boolean) {
        val message = container.syncManager.ensureBody(messageId)
            ?: container.db.messageDao().get(messageId) ?: return
        val account = container.db.accountDao().get(message.accountId)
        val headers = Json.decodeHeaders(message.headersJson)
        val originalTo = message.toList.split(',').map { it.trim() }.filter { it.isNotEmpty() }

        // Reply from the address the mail was actually sent to, so a custom
        // alias keeps the same identity in the thread.
        val ownAddresses = container.db.identityDao().forAccount(message.accountId)
            .map { it.email.lowercase() } + listOfNotNull(account?.email?.lowercase())
        val replyFrom = originalTo.firstOrNull { it.lowercase() in ownAddresses }
            ?: account?.email.orEmpty()

        val recipients = listOfNotNull(message.replyTo ?: message.fromAddress)
        val ccList = if (all) {
            (originalTo + message.ccList.split(',').map { it.trim() })
                .filter { it.isNotEmpty() && it.lowercase() !in ownAddresses }
                .filter { it != recipients.firstOrNull() }
                .distinct()
        } else emptyList()

        _state.update {
            it.copy(
                accountId = message.accountId,
                fromAddress = replyFrom,
                to = recipients.joinToString(", "),
                cc = ccList.joinToString(", "),
                showCcBcc = ccList.isNotEmpty(),
                subject = if (message.subject.startsWith("Re:", true)) message.subject
                else "Re: ${message.subject}",
                inReplyTo = message.messageIdHeader,
                references = listOfNotNull(
                    headers["references"]?.firstOrNull(),
                    message.messageIdHeader
                ).joinToString(" ").ifBlank { null },
                body = it.body + "\n\n" + quote(message.bodyPlain
                    ?: message.bodyHtml?.let(MimeUtil::htmlToText).orEmpty(), message)
            )
        }
    }

    private suspend fun prefillForward(messageId: Long) {
        val message = container.syncManager.ensureBody(messageId)
            ?: container.db.messageDao().get(messageId) ?: return
        val text = message.bodyPlain ?: message.bodyHtml?.let(MimeUtil::htmlToText).orEmpty()
        _state.update {
            it.copy(
                accountId = message.accountId,
                subject = if (message.subject.startsWith("Fwd:", true)) message.subject
                else "Fwd: ${message.subject}",
                body = it.body + "\n\n---------- Forwarded message ----------\n" +
                    "From: ${message.fromName.orEmpty()} <${message.fromAddress.orEmpty()}>\n" +
                    "Date: ${dateFormat.format(java.util.Date(message.receivedAt))}\n" +
                    "Subject: ${message.subject}\n" +
                    "To: ${message.toList}\n\n" + text
            )
        }
    }

    private fun prefillMailto(raw: String) {
        val withoutScheme = raw.removePrefix("mailto:")
        val to = withoutScheme.substringBefore('?')
        val params = withoutScheme.substringAfter('?', "")
            .split('&')
            .mapNotNull { part ->
                val key = part.substringBefore('=', "").lowercase()
                val value = part.substringAfter('=', "")
                if (key.isEmpty()) null
                else key to runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
            }.toMap()
        _state.update {
            it.copy(
                to = runCatching { java.net.URLDecoder.decode(to, "UTF-8") }.getOrDefault(to),
                cc = params["cc"].orEmpty(),
                bcc = params["bcc"].orEmpty(),
                subject = params["subject"].orEmpty(),
                body = params["body"] ?: it.body
            )
        }
    }

    private fun quote(text: String, message: de.uwumail.data.db.MessageEntity): String {
        val header = "On ${dateFormat.format(java.util.Date(message.receivedAt))}, " +
            "${message.fromName ?: message.fromAddress} wrote:"
        return header + "\n" + text.lineSequence().joinToString("\n") { "> $it" }
    }

    // ------------------------------------------------------------- mutations

    fun setAccount(accountId: Long) {
        val identity = _state.value.identities.firstOrNull { it.accountId == accountId && it.isDefault }
            ?: _state.value.identities.firstOrNull { it.accountId == accountId }
        val account = _state.value.accounts.firstOrNull { it.id == accountId }
        _state.update {
            it.copy(
                accountId = accountId,
                fromAddress = identity?.email ?: account?.email.orEmpty(),
                fromName = identity?.displayName ?: account?.displayName.orEmpty()
            )
        }
    }

    fun setIdentity(identity: IdentityEntity) = _state.update {
        it.copy(fromAddress = identity.email, fromName = identity.displayName)
    }

    fun setFromAddress(value: String) = _state.update { it.copy(fromAddress = value.trim()) }
    fun setFromName(value: String) = _state.update { it.copy(fromName = value) }
    fun setTo(value: String) = _state.update { it.copy(to = value) }
    fun setCc(value: String) = _state.update { it.copy(cc = value) }
    fun setBcc(value: String) = _state.update { it.copy(bcc = value) }
    fun setSubject(value: String) = _state.update { it.copy(subject = value) }
    fun setBody(value: String) = _state.update { it.copy(body = value) }
    fun toggleCcBcc() = _state.update { it.copy(showCcBcc = !it.showCcBcc) }
    fun clearError() = _state.update { it.copy(error = null) }

    /** Saves the current From address as a reusable identity on the account. */
    fun saveCurrentAsIdentity() {
        val current = _state.value
        viewModelScope.launch {
            runCatching {
                container.accountRepository.saveIdentity(
                    de.uwumail.data.db.IdentityEntity(
                        accountId = current.accountId,
                        displayName = current.fromName.ifBlank { current.fromAddress },
                        email = current.fromAddress
                    )
                )
                val identities = container.db.accountDao().getAll()
                    .flatMap { container.db.identityDao().forAccount(it.id) }
                _state.update { it.copy(identities = identities) }
            }.onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    fun send() {
        val current = _state.value
        if (!current.canSend) return
        _state.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            runCatching {
                val account = container.db.accountDao().get(current.accountId)
                    ?: error("No account selected")
                val secret = container.accountRepository.smtpSecret(account)
                val item = OutboxEntity(
                    accountId = account.id,
                    identityId = null,
                    fromAddress = current.fromAddress,
                    fromName = current.fromName,
                    to = current.to,
                    cc = current.cc,
                    bcc = current.bcc,
                    replyTo = null,
                    subject = current.subject,
                    bodyPlain = current.body,
                    bodyHtml = null,
                    inReplyTo = current.inReplyTo,
                    references = current.references,
                    createdAt = System.currentTimeMillis()
                )
                val raw = container.smtpSender.send(account, secret, item)
                account.sentFolder?.let { sent ->
                    runCatching {
                        container.imapPool.use(account.id) { it.append(sent, raw, seen = true) }
                    }
                }
            }.onSuccess {
                _state.update { it.copy(sending = false, sent = true) }
            }.onFailure { e ->
                // Keep the draft; queue it so the user can retry from the outbox.
                _state.update { it.copy(sending = false, error = e.message ?: e.toString()) }
            }
        }
    }

    private companion object {
        val dateFormat = SimpleDateFormat("EEE, d MMM yyyy 'at' HH:mm", Locale.getDefault())
    }
}
