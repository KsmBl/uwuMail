package de.uwumail.ui.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.uwumail.R
import de.uwumail.core.Json
import de.uwumail.data.db.AccountEntity
import de.uwumail.data.db.IdentityEntity
import de.uwumail.data.db.OutboxEntity
import de.uwumail.data.repo.IdentitySaveResult
import de.uwumail.di.AppContainer
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import de.uwumail.mail.MimeUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

/** A file waiting to go out with the message, already copied somewhere stable. */
data class PendingAttachment(val name: String, val path: String, val sizeBytes: Long)

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
    /** Handed to the outbox because it could not go out now. */
    val queued: Boolean = false,
    /** The draft this was opened from, which a save replaces. */
    val attachments: List<PendingAttachment> = emptyList(),
    val editingDraftId: Long? = null,
    val savingDraft: Boolean = false,
    val savedDraft: Boolean = false,
    val status: String? = null,
    val error: String? = null
) {
    val account: AccountEntity? get() = accounts.firstOrNull { it.id == accountId }

    /** Whether there is anything here that would be a shame to lose. */
    val hasContent: Boolean
        get() = to.isNotBlank() || cc.isNotBlank() || bcc.isNotBlank() ||
            subject.isNotBlank() || body.isNotBlank() || attachments.isNotEmpty()

    val canSaveDraft: Boolean get() = hasContent && account?.draftsFolder != null

    /** The one shape sending, queueing and saving a draft all need. */
    fun toOutbox() = OutboxEntity(
        accountId = accountId,
        identityId = null,
        fromAddress = fromAddress,
        fromName = fromName,
        to = to,
        cc = cc,
        bcc = bcc,
        replyTo = null,
        subject = subject,
        bodyPlain = body,
        bodyHtml = null,
        attachmentPaths = attachments.joinToString("\n") { it.path },
        inReplyTo = inReplyTo,
        references = references,
        createdAt = System.currentTimeMillis(),
        draftMessageId = editingDraftId
    )
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
    private val draftMessageId: Long,
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
                draftMessageId > 0 -> prefillDraft(draftMessageId)
                replyToMessageId > 0 -> prefillReply(replyToMessageId, replyAll)
                forwardMessageId > 0 -> prefillForward(forwardMessageId)
                mailto != null -> prefillMailto(mailto)
            }
        }
    }

    /** Reopens a saved draft as what it is: the message, mid-sentence. */
    private suspend fun prefillDraft(messageId: Long) {
        val message = container.syncManager.ensureBody(messageId)
            ?: container.db.messageDao().get(messageId) ?: return
        val body = message.bodyPlain
            ?: message.bodyHtml?.let { MimeUtil.htmlToText(it) }
            ?: ""
        _state.update {
            it.copy(
                accountId = message.accountId,
                fromAddress = message.fromAddress ?: it.fromAddress,
                fromName = message.fromName ?: it.fromName,
                to = message.toList,
                cc = message.ccList,
                bcc = message.bccList,
                subject = message.subject,
                body = body,
                showCcBcc = message.ccList.isNotBlank() || message.bccList.isNotBlank(),
                editingDraftId = messageId
            )
        }
    }

    /**
     * Copies a picked file somewhere the sender can reach it.
     *
     * A content URI is a loan from whichever app produced it and may not
     * outlive this screen, let alone a spell in the outbox — so the bytes are
     * taken now rather than the reference kept.
     *
     * The copy keeps the name the file arrived with and is made unique by the
     * directory around it rather than by a prefix: the outbox carries paths,
     * and whatever the file is called on disk is what the recipient sees.
     */
    fun attach(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val resolver = container.appContext.contentResolver
                    val name = resolver.displayNameOf(uri)
                    val directory = File(
                        File(container.appContext.filesDir, "outgoing"),
                        UUID.randomUUID().toString()
                    ).apply { mkdirs() }
                    val file = File(directory, name)
                    resolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { input.copyTo(it) }
                    } ?: error(container.appContext.getString(R.string.error_read_file))
                    PendingAttachment(name, file.absolutePath, file.length())
                }
            }.onSuccess { attachment ->
                _state.update { it.copy(attachments = it.attachments + attachment) }
            }.onFailure { e ->
                _state.update { it.copy(error = e.message ?: e.toString()) }
            }
        }
    }

    fun removeAttachment(attachment: PendingAttachment) {
        runCatching { discard(attachment) }
        _state.update { it.copy(attachments = it.attachments - attachment) }
    }

    /** Removes a copied-in file and the directory that was only there to hold it. */
    private fun discard(attachment: PendingAttachment) {
        val file = File(attachment.path)
        file.delete()
        file.parentFile?.takeIf { it.name.isUuid() }?.delete()
    }

    /** Saves what is here to the Drafts folder, replacing the draft it came from. */
    fun saveDraft() {
        val current = _state.value
        if (!current.canSaveDraft || current.savingDraft) return
        _state.update { it.copy(savingDraft = true, error = null) }
        viewModelScope.launch {
            runCatching {
                container.syncManager.saveDraft(current.toOutbox(), current.editingDraftId)
            }.onSuccess {
                _state.update { it.copy(savingDraft = false, savedDraft = true) }
            }.onFailure { e ->
                _state.update {
                    it.copy(savingDraft = false, error = e.message ?: e.toString())
                }
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
                body = it.body + "\n\n" + container.appContext.getString(R.string.forwarded_separator) + "\n" +
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
    fun clearStatus() = _state.update { it.copy(status = null, error = null) }

    /** True when the address in the From field is already saved on this account. */
    fun savedIdentityFor(email: String): IdentityEntity? = _state.value.accountIdentities
        .firstOrNull { it.email.equals(email.trim(), ignoreCase = true) }

    fun deleteIdentity(identity: IdentityEntity) {
        viewModelScope.launch {
            runCatching {
                container.accountRepository.deleteIdentity(identity)
                reloadIdentities()
            }.onSuccess {
                _state.update { it.copy(status = "Removed ${identity.email}") }
            }.onFailure { e ->
                _state.update { it.copy(error = e.message ?: e.toString()) }
            }
        }
    }

    private suspend fun reloadIdentities() {
        val identities = container.db.accountDao().getAll()
            .flatMap { container.db.identityDao().forAccount(it.id) }
        _state.update { it.copy(identities = identities) }
    }

    /** Saves the current From address as a reusable identity on the account. */
    fun saveCurrentAsIdentity() {
        val current = _state.value
        viewModelScope.launch {
            runCatching {
                // Bookmarking the same address twice would otherwise pile up
                // duplicates that then have to be deleted one by one.
                val result = container.accountRepository.saveIdentity(
                    IdentityEntity(
                        accountId = current.accountId,
                        displayName = current.fromName.ifBlank { current.fromAddress },
                        email = current.fromAddress,
                        isDefault = false
                    )
                )
                reloadIdentities()
                result
            }.onSuccess { result ->
                val address = current.fromAddress.trim().lowercase()
                _state.update {
                    it.copy(
                        status = when (result) {
                            IdentitySaveResult.CREATED -> "Saved $address"
                            IdentitySaveResult.UPDATED -> "Updated $address"
                            IdentitySaveResult.UNCHANGED -> "$address is already saved"
                        }
                    )
                }
            }.onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    /**
     * Sends now if it can, and hands the message to the outbox if it cannot.
     *
     * A send that fails on a train is not a send that failed, and a composer
     * that hands the message back with an error is a message that only exists
     * as long as the screen does. The queued copy carries its own attachments
     * and the draft it grew from, so nothing here has to be held in memory
     * until the network comes back.
     */
    fun send() {
        val current = _state.value
        if (!current.canSend) return
        _state.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            val item = current.toOutbox()
            runCatching {
                val account = container.db.accountDao().get(current.accountId)
                    ?: error(container.appContext.getString(R.string.error_no_account))
                val secret = container.accountRepository.smtpSecret(account)
                val raw = container.smtpSender.send(account, secret, item)
                account.sentFolder?.let { sent ->
                    runCatching {
                        container.imapPool.use(account.id) { it.append(sent, raw, seen = true) }
                    }
                }
                // The draft it grew from is finished with now.
                current.editingDraftId?.let { draft ->
                    runCatching { container.syncManager.deletePermanently(listOf(draft), false) }
                }
            }.onSuccess {
                // The copies were only ever there to be sent.
                current.attachments.forEach { runCatching { discard(it) } }
                _state.update { it.copy(sending = false, sent = true) }
            }.onFailure { failure ->
                // The attachments are deliberately left on disk: the queued copy
                // refers to them by path and needs them when its turn comes.
                runCatching {
                    container.db.outboxDao().insert(
                        item.copy(lastError = failure.message ?: failure.toString(), attempts = 1)
                    )
                }.onSuccess {
                    _state.update { it.copy(sending = false, queued = true) }
                }.onFailure { e ->
                    _state.update { it.copy(sending = false, error = e.message ?: e.toString()) }
                }
            }
        }
    }

    private companion object {
        val dateFormat = SimpleDateFormat("EEE, d MMM yyyy 'at' HH:mm", Locale.getDefault())
    }
}

/** Whether a directory name is one of ours, so only our own is removed. */
private fun String.isUuid(): Boolean = runCatching { UUID.fromString(this) }.isSuccess

/** The name the producing app gives a file, falling back to something usable. */
private fun ContentResolver.displayNameOf(uri: Uri): String {
    val name = runCatching {
        query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
    return MimeUtil.sanitizeFileName(name ?: uri.lastPathSegment ?: "attachment")
}
