package de.uwumail.ui.mail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.uwumail.data.db.AccountEntity
import de.uwumail.data.db.AttachmentEntity
import de.uwumail.data.db.FolderEntity
import de.uwumail.data.db.MessageEntity
import de.uwumail.data.settings.AppSettings
import de.uwumail.di.AppContainer
import de.uwumail.core.Json
import de.uwumail.mail.RemoteImagePolicy
import de.uwumail.mail.Unsubscribe
import de.uwumail.mail.UnsubscribeTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class MessageUiState(
    val message: MessageEntity? = null,
    val attachments: List<AttachmentEntity> = emptyList(),
    val folders: List<FolderEntity> = emptyList(),
    val accounts: List<AccountEntity> = emptyList(),
    val loading: Boolean = true,
    /** An action on this message is waiting on the server. */
    val busy: Boolean = false,
    val showHtml: Boolean = true,
    val showHeaders: Boolean = false,
    val settings: AppSettings = AppSettings(),
    /** Parsed off the main thread from the body; see [BodyInsights]. */
    val insights: BodyInsights = BodyInsights(),
    /**
     * Remote content stays blocked until it is asked for here. The flag lives
     * in the view model, which dies with the screen, so reopening a message
     * blocks its images again.
     */
    val imagesUnblocked: Boolean = false,
    val status: String? = null,
    val error: String? = null,
    val closed: Boolean = false,
    /** Set once the message has been seen at least once, so a later null means gone. */
    val everLoaded: Boolean = false
) {
    /** The unsubscribe link this message advertises, when the banner is enabled. */
    val unsubscribe: UnsubscribeTarget?
        get() = insights.unsubscribe.takeIf { settings.unsubscribeBanner }

    /** Whether the body is holding back remote images the user could ask for. */
    val imagesBlocked: Boolean
        get() = settings.blockRemoteImages && !imagesUnblocked && insights.remoteImageCount > 0

    val imagePolicy: RemoteImagePolicy
        get() = RemoteImagePolicy(
            filterTiny = settings.filterTinyImages,
            minWidth = settings.minImageWidth,
            minHeight = settings.minImageHeight
        )

    /** Every account's folders, minus the one this message is already in. */
    fun moveTargets(): List<FolderEntity> = folders
        .filter { it.selectable && it.id != message?.folderId && !it.hidden }
}

/** What reading the body told us, computed once per body rather than per frame. */
data class BodyInsights(
    val unsubscribe: UnsubscribeTarget? = null,
    val remoteImageCount: Int = 0
)

class MessageViewModel(
    private val container: AppContainer,
    private val messageId: Long
) : ViewModel() {

    private val local = MutableStateFlow(
        MessageUiState(loading = true)
    )

    private val message = container.db.messageDao().observeFull(messageId)

    /**
     * Parsing a body with jsoup is far too slow to do while composing a frame,
     * so it happens once per body on a worker thread and the result is carried
     * in the state.
     */
    private val insights = message
        .map { Triple(it?.headersJson, it?.bodyHtml, it?.bodyPlain) }
        .distinctUntilChanged()
        .map { (headersJson, html, plain) ->
            BodyInsights(
                unsubscribe = Unsubscribe.find(Json.decodeHeaders(headersJson), html, plain),
                remoteImageCount = countRemoteImages(html)
            )
        }
        .flowOn(Dispatchers.Default)

    val state: StateFlow<MessageUiState> = combine(
        message,
        container.db.attachmentDao().observeFor(messageId),
        container.db.folderDao().observeAll(),
        combine(
            container.settings.state,
            insights,
            container.db.accountDao().observeAll()
        ) { settings, parsed, accounts -> Triple(settings, parsed, accounts) },
        local
    ) { message, attachments, folders, (settings, parsed, accounts), extra ->
        extra.copy(
            message = message,
            attachments = attachments,
            folders = folders,
            settings = settings,
            insights = parsed,
            accounts = accounts,
            // The row is hidden the instant a removal starts, so the view can
            // close then rather than waiting on the server.
            closed = extra.closed ||
                message?.pendingRemoval == true ||
                (extra.everLoaded && message == null)
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MessageUiState())

    init {
        viewModelScope.launch {
            runCatching {
                container.syncManager.ensureBody(messageId)
                // Opening a message is the natural point to clear its unread state.
                container.db.messageDao().get(messageId)?.let {
                    if (!it.seen) container.syncManager.setSeen(listOf(messageId), true)
                }
            }.onFailure { e -> local.update { s -> s.copy(error = e.message) } }
            local.update { it.copy(loading = false, everLoaded = true) }
        }
    }

    fun toggleHtml() = local.update { it.copy(showHtml = !it.showHtml) }
    fun showRemoteImages() = local.update { it.copy(imagesUnblocked = true) }
    fun toggleHeaders() = local.update { it.copy(showHeaders = !it.showHeaders) }
    fun clearStatus() = local.update { it.copy(status = null, error = null) }

    fun setSeen(seen: Boolean) = guarded { container.syncManager.setSeen(listOf(messageId), seen) }
    fun setFlagged(flagged: Boolean) = guarded {
        container.syncManager.setFlagged(listOf(messageId), flagged)
    }

    // The screen closes off the back of the row being hidden, so these do not
    // set `closed` themselves and do not block on the server.
    fun archive() = guarded { container.syncManager.archive(listOf(messageId)) }
    fun trash() = guarded { container.syncManager.moveToTrash(listOf(messageId)) }
    fun deleteForever() = guarded { container.syncManager.deletePermanently(listOf(messageId)) }
    fun moveTo(folderId: Long) = guarded {
        container.syncManager.moveMessages(listOf(messageId), folderId)
    }

    fun download(onReady: (File) -> Unit) = guarded {
        val file = container.syncManager.downloadRaw(messageId)
        if (file != null) {
            local.update { it.copy(status = "Saved to ${file.name}") }
            onReady(file)
        } else {
            local.update { it.copy(error = "Could not download this message") }
        }
    }

    fun openAttachment(attachmentId: Long, onReady: (File, String) -> Unit) = guarded {
        val file = container.syncManager.downloadAttachment(attachmentId)
        val attachment = container.db.attachmentDao().get(attachmentId)
        if (file != null && attachment != null) onReady(file, attachment.mimeType)
        else local.update { it.copy(error = "Could not download the attachment") }
    }

    /** How many images the body would fetch from the network if it were allowed to. */
    private fun countRemoteImages(html: String?): Int {
        if (html.isNullOrBlank()) return 0
        return runCatching {
            org.jsoup.Jsoup.parse(html).select("img[src]").count { element ->
                val src = element.attr("src")
                src.startsWith("http://", true) || src.startsWith("https://", true)
            }
        }.getOrDefault(0)
    }

    private fun guarded(block: suspend () -> Unit) {
        local.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                runCatching { block() }
                    .onFailure { e -> local.update { it.copy(error = e.message ?: e.toString()) } }
            } finally {
                local.update { it.copy(busy = false) }
            }
        }
    }

}
