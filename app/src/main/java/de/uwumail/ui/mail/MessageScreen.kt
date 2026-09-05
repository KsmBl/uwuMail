package de.uwumail.ui.mail

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import de.uwumail.core.Json
import de.uwumail.mail.MimeUtil
import de.uwumail.ui.mail.gravity.GravityGlyph
import de.uwumail.ui.mail.gravity.GravityOverlay
import de.uwumail.ui.mail.gravity.MAX_GRAVITY_LETTERS
import de.uwumail.ui.common.ConfirmDialog
import de.uwumail.ui.common.FolderPickerSheet
import de.uwumail.ui.common.formatFullDate
import de.uwumail.ui.common.formatSize
import de.uwumail.ui.containerViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageScreen(
    messageId: Long,
    onBack: () -> Unit,
    onReply: (Long, Boolean) -> Unit,
    onForward: (Long) -> Unit
) {
    val viewModel = containerViewModel(key = "message-$messageId") {
        MessageViewModel(it, messageId)
    }
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }
    var overflow by remember { mutableStateOf(false) }
    var showMove by remember { mutableStateOf(false) }
    var showCopy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    // Set when a tapped link carries tracking parameters and the user has asked
    // to be consulted; the dialog is what actually opens it.
    var trackingLink by remember { mutableStateOf<String?>(null) }
    // The characters the body handed over, once gravity is on.
    var glyphs by remember { mutableStateOf<List<GravityGlyph>>(emptyList()) }
    LaunchedEffect(state.gravity) { if (!state.gravity) glyphs = emptyList() }

    fun follow(url: String) {
        if (state.settings.askStripTracking && de.uwumail.mail.TrackingParams.hasTracking(url)) {
            trackingLink = url
        } else {
            openLink(context, url)
        }
    }

    LaunchedEffect(state.closed) { if (state.closed) onBack() }
    LaunchedEffect(state.status, state.error) {
        val text = state.error?.let { "Error: $it" } ?: state.status
        if (text != null) {
            snackbarHost.showSnackbar(text)
            viewModel.clearStatus()
        }
    }

    val message = state.message

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            Column {
            TopAppBar(
                title = { Text("Message", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.setFlagged(!(message?.flagged ?: false)) }) {
                        Icon(
                            if (message?.flagged == true) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = "Star"
                        )
                    }
                    IconButton(onClick = viewModel::archive) {
                        Icon(Icons.Default.Archive, "Archive")
                    }
                    IconButton(onClick = viewModel::trash) {
                        Icon(Icons.Default.Delete, "Trash")
                    }
                    IconButton(onClick = { overflow = true }) {
                        Icon(Icons.Default.MoreVert, "More")
                    }
                    DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                        DropdownMenuItem(
                            text = { Text("Reply") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Reply, null) },
                            onClick = { overflow = false; onReply(messageId, false) }
                        )
                        DropdownMenuItem(
                            text = { Text("Reply all") },
                            onClick = { overflow = false; onReply(messageId, true) }
                        )
                        DropdownMenuItem(
                            text = { Text("Forward") },
                            onClick = { overflow = false; onForward(messageId) }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Move to folder") },
                            leadingIcon = { Icon(Icons.Default.DriveFileMove, null) },
                            onClick = { overflow = false; showMove = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Copy to folder") },
                            leadingIcon = { Icon(Icons.Default.FileCopy, null) },
                            onClick = { overflow = false; showCopy = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Mark as unread") },
                            leadingIcon = { Icon(Icons.Default.MarkEmailUnread, null) },
                            onClick = { overflow = false; viewModel.setSeen(false); onBack() }
                        )
                        DropdownMenuItem(
                            text = { Text("Save .eml to device") },
                            leadingIcon = { Icon(Icons.Default.Download, null) },
                            onClick = {
                                overflow = false
                                viewModel.download { shareFile(context, it, "message/rfc822") }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (state.showHtml) "Show plain text" else "Show HTML") },
                            leadingIcon = { Icon(Icons.Default.Code, null) },
                            onClick = { overflow = false; viewModel.toggleHtml() }
                        )
                        DropdownMenuItem(
                            text = { Text(if (state.showHeaders) "Hide headers" else "Show headers") },
                            onClick = { overflow = false; viewModel.toggleHeaders() }
                        )
                        if (state.settings.gravityUnlocked) {
                            DropdownMenuItem(
                                text = {
                                    Text(if (state.gravity) "Disable gravity" else "Enable gravity")
                                },
                                leadingIcon = { Icon(Icons.Default.ArrowDownward, null) },
                                onClick = { overflow = false; viewModel.toggleGravity() }
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = {
                                Text("Delete permanently", color = MaterialTheme.colorScheme.error)
                            },
                            onClick = { overflow = false; confirmDelete = true }
                        )
                    }
                }
            )
            if (state.busy || (state.loading && message != null)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            }
        }
    ) { padding ->
        if (message == null) {
            Column(
                Modifier.padding(padding).fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        // The message is drawn exactly as it always is. Under gravity the
        // letters are lifted off it into the overlay below, which is why the
        // first frame of the fall is indistinguishable from it sitting still.
        Box(Modifier.padding(padding).fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState(), enabled = !state.gravity)
        ) {
            state.unsubscribe?.let { target ->
                UnsubscribeBanner(target) { follow(target.url) }
            }
            if (state.imagesBlocked) {
                BlockedImagesBanner(
                    blockedCount = state.insights.remoteImageCount,
                    onShow = viewModel::showRemoteImages
                )
            }

            Column(Modifier.padding(16.dp)) {
                Text(
                    message.subject.ifBlank { "(no subject)" },
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    buildString {
                        append(message.fromName?.takeIf { it.isNotBlank() } ?: "")
                        if (isNotEmpty()) append(" ")
                        message.fromAddress?.let { append("<$it>") }
                    }.trim(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 8.dp)
                )
                if (message.toList.isNotBlank()) {
                    Text(
                        "to ${message.toList}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (message.ccList.isNotBlank()) {
                    Text(
                        "cc ${message.ccList}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    formatFullDate(message.receivedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (state.attachments.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    state.attachments.filter { !it.isInline }.forEach { attachment ->
                        AssistChip(
                            onClick = {
                                viewModel.openAttachment(attachment.id) { file, mime ->
                                    shareFile(context, file, mime)
                                }
                            },
                            leadingIcon = { Icon(Icons.Default.AttachFile, null) },
                            label = {
                                Text("${attachment.fileName} ${formatSize(attachment.sizeBytes)}")
                            }
                        )
                    }
                }
            }

            if (state.showHeaders) {
                Card(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Json.decodeHeaders(message.headersJson).forEach { (name, values) ->
                            values.forEach { value ->
                                Text(
                                    "$name: $value",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Light
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            val html = message.bodyHtml
            val plain = message.bodyPlain
                ?: html?.let { MimeUtil.htmlToText(it) }
                ?: message.preview

            if (state.showHtml && !html.isNullOrBlank()) {
                HtmlBody(
                    html = html,
                    allowRemoteImages = !state.settings.blockRemoteImages || state.imagesUnblocked,
                    allowJavaScript = state.settings.allowJavaScript,
                    imagePolicy = state.imagePolicy,
                    onLink = ::follow,
                    handOverGlyphs = state.gravity,
                    glyphLimit = MAX_GRAVITY_LETTERS,
                    onGlyphs = { glyphs = it }
                )
            } else {
                PlainBody(
                    text = plain.ifBlank { if (state.loading) "Loading…" else "(empty message)" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    handOverGlyphs = state.gravity,
                    glyphLimit = MAX_GRAVITY_LETTERS,
                    onGlyphs = { glyphs = it }
                )
            }
        }

        if (state.gravity && glyphs.isNotEmpty()) {
            GravityOverlay(glyphs, Modifier.fillMaxSize())
        }
        }
    }

    if (showMove) {
        FolderPickerSheet(
            folders = state.moveTargets(),
            accounts = state.accounts,
            preferredAccountId = state.message?.accountId,
            onPick = { showMove = false; viewModel.moveTo(it.id) },
            onDismiss = { showMove = false }
        )
    }

    if (showCopy) {
        FolderPickerSheet(
            folders = state.moveTargets(),
            accounts = state.accounts,
            title = "Copy to",
            confirmLabel = "Copy here",
            preferredAccountId = state.message?.accountId,
            onPick = { showCopy = false; viewModel.copyTo(it.id) },
            onDismiss = { showCopy = false }
        )
    }

    trackingLink?.let { url ->
        TrackingLinkDialog(
            url = url,
            onOpen = { openLink(context, it) },
            onDismiss = { trackingLink = null }
        )
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete permanently?",
            message = "This removes the message from the server. It cannot be undone.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = viewModel::deleteForever,
            onDismiss = { confirmDelete = false }
        )
    }
}

private fun shareFile(context: android.content.Context, file: File, mimeType: String) {
    runCatching {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Open with"))
    }
}
