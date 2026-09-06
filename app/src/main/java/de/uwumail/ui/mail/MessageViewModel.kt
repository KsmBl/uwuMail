package de.uwumail.ui.mail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.uwumail.R
import de.uwumail.data.db.AccountEntity
import de.uwumail.data.db.AttachmentEntity
import de.uwumail.data.db.FolderEntity
import de.uwumail.data.db.MessageEntity
import de.uwumail.data.settings.AppSettings
import de.uwumail.di.AppContainer
import de.uwumail.core.Json
import android.net.Uri
import android.provider.DocumentsContract
import de.uwumail.mail.ContentId
import de.uwumail.mail.ImagePrefilter
import de.uwumail.mail.MimeUtil
import de.uwumail.mail.RemoteImagePolicy
import de.uwumail.mail.Unsubscribe
import de.uwumail.mail.UnsubscribeTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    /**
     * Lives here and nowhere else, so closing the message and opening it again
     * puts the letters back where they belong.
     */
    val gravity: Boolean = false,
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

    val imagePolicy: RemoteImagePolicy get() = settings.imagePolicy()

    /** Every account's folders, minus the one this message is already in. */
    fun moveTargets(): List<FolderEntity> = folders
        .filter { it.selectable && it.id != message?.folderId && !it.hidden }
}

/** What reading the body told us, computed once per body rather than per frame. */
data class BodyInsights(
    val unsubscribe: UnsubscribeTarget? = null,
    /**
     * The body with its tracking pixels taken out, which is what actually gets
     * rendered — a beacon left in the markup is a beacon that gets fetched.
     */
    val displayHtml: String? = null,
    /** Remote images the body would still fetch, after the beacons are gone. */
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
     * Reading a body with jsoup is far too slow to do while composing a frame,
     * so it happens once per body on a worker thread and the result is carried
     * in the state. Stripping the beacons has to happen here too: the rendered
     * markup is what decides which requests the page makes.
     */
    private val insights = combine(
        message.map { Triple(it?.headersJson, it?.bodyHtml, it?.bodyPlain) }
            .distinctUntilChanged(),
        container.settings.state.map { it.imagePolicy() }.distinctUntilChanged()
    ) { body, policy -> body to policy }
        .map { (body, policy) ->
            val (headersJson, html, plain) = body
            val filtered = html?.takeIf { it.isNotBlank() }?.let { ImagePrefilter.strip(it, policy) }
            BodyInsights(
                unsubscribe = Unsubscribe.find(Json.decodeHeaders(headersJson), html, plain),
                displayHtml = filtered?.html,
                remoteImageCount = filtered?.remoteRemaining ?: 0
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
            fetchNeighbours()
        }
    }

    /**
     * Fetches the bodies either side of this one while it is being read.
     *
     * Swiping across is meant to feel like turning a page, and it cannot while
     * every turn waits on the server. Nothing else changes: a body fetched here
     * is not marked read and not shown, it is simply already there when the
     * finger arrives. Messages whose body is already cached cost nothing, so
     * swiping back over ground already covered asks for nothing at all.
     */
    private suspend fun fetchNeighbours() {
        val order = container.messageOrder
        listOfNotNull(order.nextOf(messageId), order.previousOf(messageId)).forEach { id ->
            runCatching { container.syncManager.ensureBody(id) }
        }
    }

    /**
     * The bytes behind a `cid:` reference, with its type.
     *
     * Inline parts came down with the message and cost nothing to show: there
     * is no request to make and so nothing for a sender to learn from it. They
     * are deliberately not held back by the remote-image setting, which exists
     * to stop the network being touched.
     */
    suspend fun inlineImage(contentId: String): Pair<ByteArray, String>? {
        if (ContentId.normalise(contentId).isEmpty()) return null
        val attachment = container.db.attachmentDao().forMessage(messageId).firstOrNull {
            ContentId.matches(contentId, it.contentId)
        } ?: return null
        val file = container.syncManager.downloadAttachment(attachment.id) ?: return null
        return runCatching { file.readBytes() to attachment.mimeType }.getOrNull()
    }

    fun toggleHtml() = local.update { it.copy(showHtml = !it.showHtml) }
    fun showRemoteImages() = local.update { it.copy(imagesUnblocked = true) }
    fun toggleGravity() = local.update { it.copy(gravity = !it.gravity) }
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

    fun copyTo(folderId: Long) = guarded {
        container.syncManager.copyMessages(listOf(messageId), folderId)
        local.update { it.copy(status = text(R.string.status_copied)) }
    }

    /**
     * Writes the message to wherever the picker was pointed.
     *
     * The bytes are fetched first and copied into the chosen document, so the
     * file only exists once there is something to put in it.
     */
    fun exportTo(destination: Uri) = guarded {
        val file = container.syncManager.downloadRaw(messageId)
        if (file == null) {
            local.update { it.copy(error = text(R.string.error_no_download)) }
            return@guarded
        }
        val written = withContext(Dispatchers.IO) {
            runCatching {
                container.appContext.contentResolver.openOutputStream(destination)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: error("could not write there")
            }.isSuccess
        }
        local.update {
            if (written) it.copy(status = text(R.string.message_saved))
            else it.copy(error = text(R.string.error_no_download))
        }
    }

    fun download(onReady: (File) -> Unit) = guarded {
        val file = container.syncManager.downloadRaw(messageId)
        if (file != null) {
            local.update { it.copy(status = text(R.string.saved_to_file, file.name)) }
            onReady(file)
        } else {
            local.update { it.copy(error = text(R.string.error_no_download)) }
        }
    }

    /**
     * Writes an attachment to wherever the picker was pointed.
     *
     * It is fetched first and then copied into the chosen document, so the
     * file the user picked is only created once there is something to put in
     * it — an empty file left behind by a failed download would look like a
     * saved attachment.
     */
    fun saveAttachmentTo(attachmentId: Long, destination: Uri) = guarded {
        val file = container.syncManager.downloadAttachment(attachmentId)
        if (file == null) {
            local.update { it.copy(error = text(R.string.error_no_attachment)) }
            return@guarded
        }
        val written = withContext(Dispatchers.IO) {
            runCatching {
                container.appContext.contentResolver.openOutputStream(destination)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: error("could not write there")
            }.isSuccess
        }
        local.update {
            if (written) it.copy(status = text(R.string.attachment_saved))
            else it.copy(error = text(R.string.error_no_attachment))
        }
    }

    /**
     * Writes several attachments into a folder the user picked. One file at a
     * time: the download is per attachment anyway, and a folder that refuses
     * one name should not cost the others.
     */
    fun saveAttachmentsTo(attachmentIds: List<Long>, folder: Uri) = guarded {
        val resolver = container.appContext.contentResolver
        val parent = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(
                folder, DocumentsContract.getTreeDocumentId(folder)
            )
        }.getOrNull()
        if (parent == null) {
            local.update { it.copy(error = text(R.string.error_no_attachment)) }
            return@guarded
        }
        var written = 0
        for (id in attachmentIds) {
            val file = container.syncManager.downloadAttachment(id) ?: continue
            val attachment = container.db.attachmentDao().get(id) ?: continue
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val target = DocumentsContract.createDocument(
                        resolver,
                        parent,
                        attachment.mimeType.ifBlank { "application/octet-stream" },
                        MimeUtil.sanitizeFileName(attachment.fileName)
                    ) ?: error("could not write there")
                    resolver.openOutputStream(target)?.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    } ?: error("could not write there")
                }.isSuccess
            }
            if (saved) written++
        }
        local.update {
            when {
                written == 0 -> it.copy(error = text(R.string.error_no_attachment))
                written < attachmentIds.size -> it.copy(
                    error = text(R.string.attachments_saved_partial, written, attachmentIds.size)
                )
                else -> it.copy(status = quantity(R.plurals.attachments_saved, written, written))
            }
        }
    }

    fun openAttachment(attachmentId: Long, onReady: (File, String) -> Unit) = guarded {
        val file = container.syncManager.downloadAttachment(attachmentId)
        val attachment = container.db.attachmentDao().get(attachmentId)
        if (file != null && attachment != null) onReady(file, attachment.mimeType)
        else local.update { it.copy(error = text(R.string.error_no_attachment)) }
    }

    private fun text(id: Int, vararg args: Any) = container.appContext.getString(id, *args)

    private fun quantity(id: Int, count: Int, vararg args: Any) =
        container.appContext.resources.getQuantityString(id, count, *args)

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
