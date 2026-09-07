package de.uwumail.ui.mail

import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.TextButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Save
import de.uwumail.data.db.AttachmentEntity
import androidx.compose.material3.BottomAppBar
import androidx.compose.material.icons.automirrored.filled.ReplyAll
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import de.uwumail.R
import de.uwumail.core.Json
import de.uwumail.mail.MimeUtil
import de.uwumail.ui.common.rememberHaptics
import de.uwumail.ui.mail.gravity.FallingPiece
import de.uwumail.ui.mail.gravity.GravityOverlay
import de.uwumail.ui.mail.gravity.MAX_GRAVITY_LETTERS
import de.uwumail.ui.LocalAppContainer
import de.uwumail.ui.common.ConfirmDialog
import de.uwumail.ui.common.FolderPickerSheet
import de.uwumail.ui.common.formatFullDate
import de.uwumail.ui.common.formatSize
import de.uwumail.ui.containerViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun MessageScreen(
    messageId: Long,
    onBack: () -> Unit,
    onReply: (Long, Boolean) -> Unit,
    onForward: (Long) -> Unit,
    onOpenMessage: (Long) -> Unit = {}
) {
    val viewModel = containerViewModel(key = "message-$messageId") {
        MessageViewModel(it, messageId)
    }
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }
    val errorPrefix = stringResource(R.string.error_prefix)
    // Swiping moves along whatever the list was showing when this was opened.
    val order = LocalAppContainer.current.messageOrder
    val siblings by order.ids.collectAsState()
    val previousMessage = remember(siblings, messageId) { order.previousOf(messageId) }
    val nextMessage = remember(siblings, messageId) { order.nextOf(messageId) }
    var overflow by remember { mutableStateOf(false) }
    var showMove by remember { mutableStateOf(false) }
    var showCopy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    // Set when a tapped link carries tracking parameters and the user has asked
    // to be consulted; the dialog is what actually opens it.
    var trackingLink by remember { mutableStateOf<String?>(null) }
    // The system picker chooses where a saved message goes, so it lands
    // somewhere the user actually chose rather than wherever the app can write.
    // Which attachment is being asked about, and which one a save is for.
    var attachmentChoice by remember { mutableStateOf<AttachmentEntity?>(null) }
    var attachmentToSave by remember { mutableStateOf<AttachmentEntity?>(null) }
    val saveAttachment = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { destination ->
        val attachment = attachmentToSave
        attachmentToSave = null
        if (destination != null && attachment != null) {
            viewModel.saveAttachmentTo(attachment.id, destination)
        }
    }
    // Attachments picked out for a bulk save; empty means nothing is being
    // picked and a tap means "what shall I do with this one".
    var picked by remember { mutableStateOf(emptySet<Long>()) }
    val saveAll = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { folder ->
        val chosen = picked.toList()
        picked = emptySet()
        if (folder != null && chosen.isNotEmpty()) viewModel.saveAttachmentsTo(chosen, folder)
    }
    val saveEml = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("message/rfc822")
    ) { destination -> destination?.let(viewModel::exportTo) }
    // Every part of the message hands its characters over separately; the
    // overlay wants them as one list.
    val lifted = remember { mutableStateMapOf<String, List<FallingPiece>>() }
    val glyphs = remember(lifted.size, lifted.values.sumOf { it.size }) {
        lifted.entries.sortedBy { it.key }.flatMap { it.value }
    }
    LaunchedEffect(state.gravity) { if (!state.gravity) lifted.clear() }

    // While the letters are loose the screen must not rotate with the phone:
    // the whole point is that turning it changes which way they fall, and a
    // layout that turns with it would simply put "down" back at the bottom.
    DisposableEffect(state.gravity) {
        val activity = context.findActivity()
        val previous = activity?.requestedOrientation
        if (state.gravity && activity != null) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        }
        onDispose {
            if (previous != null) activity?.requestedOrientation = previous
        }
    }

    fun follow(url: String) {
        if (state.settings.askStripTracking && de.uwumail.mail.TrackingParams.hasTracking(url)) {
            trackingLink = url
        } else {
            openLink(context, url)
        }
    }

    LaunchedEffect(state.closed) { if (state.closed) onBack() }
    LaunchedEffect(state.status, state.error) {
        val text = state.error?.let { errorPrefix.format(it) } ?: state.status
        if (text != null) {
            snackbarHost.showSnackbar(text)
            viewModel.clearStatus()
        }
    }

    val message = state.message

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        bottomBar = {
            // Answering a message is the commonest thing anyone does with one,
            // and it was two taps into an overflow menu. Hidden while the
            // letters are loose, which is not a message to reply to.
            if (showsReplyBar(hasMessage = message != null, gravity = state.gravity)) {
                BottomAppBar(actions = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        ReplyAction(
                            icon = Icons.AutoMirrored.Filled.Reply,
                            label = stringResource(R.string.reply),
                            onClick = { onReply(messageId, false) }
                        )
                        ReplyAction(
                            icon = Icons.AutoMirrored.Filled.ReplyAll,
                            label = stringResource(R.string.reply_all),
                            onClick = { onReply(messageId, true) }
                        )
                        ReplyAction(
                            icon = Icons.AutoMirrored.Filled.Forward,
                            label = stringResource(R.string.forward),
                            onClick = { onForward(messageId) }
                        )
                    }
                })
            }
        },
        topBar = {
            Column {
            TopAppBar(
                title = { Text(stringResource(R.string.message), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.setFlagged(!(message?.flagged ?: false)) }) {
                        Icon(
                            if (message?.flagged == true) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = stringResource(R.string.star)
                        )
                    }
                    IconButton(onClick = viewModel::archive) {
                        Icon(Icons.Default.Archive, stringResource(R.string.archive))
                    }
                    // In the bin there is nowhere left to move it to, so the
                    // same button finishes the job — after asking, since this
                    // is the one thing that cannot be taken back.
                    IconButton(
                        onClick = {
                            if (state.isInTrash) confirmDelete = true else viewModel.trash()
                        }
                    ) {
                        if (state.isInTrash) {
                            Icon(
                                Icons.Default.DeleteForever,
                                stringResource(R.string.delete_permanently)
                            )
                        } else {
                            Icon(Icons.Default.Delete, stringResource(R.string.trash))
                        }
                    }
                    IconButton(onClick = { overflow = true }) {
                        Icon(Icons.Default.MoreVert, stringResource(R.string.more))
                    }
                    DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.move_to_folder)) },
                            leadingIcon = { Icon(Icons.Default.DriveFileMove, null) },
                            onClick = { overflow = false; showMove = true }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.copy_to_folder)) },
                            leadingIcon = { Icon(Icons.Default.FileCopy, null) },
                            onClick = { overflow = false; showCopy = true }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.mark_unread)) },
                            leadingIcon = { Icon(Icons.Default.MarkEmailUnread, null) },
                            onClick = { overflow = false; viewModel.setSeen(false); onBack() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.save_eml)) },
                            leadingIcon = { Icon(Icons.Default.Download, null) },
                            onClick = {
                                overflow = false
                                saveEml.launch(state.message.emlFileName())
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (state.showHtml) stringResource(R.string.show_plain) else stringResource(R.string.show_html)) },
                            leadingIcon = { Icon(Icons.Default.Code, null) },
                            onClick = { overflow = false; viewModel.toggleHtml() }
                        )
                        DropdownMenuItem(
                            text = { Text(if (state.showHeaders) stringResource(R.string.hide_headers) else stringResource(R.string.show_headers)) },
                            onClick = { overflow = false; viewModel.toggleHeaders() }
                        )
                        if (state.settings.gravityUnlocked) {
                            DropdownMenuItem(
                                text = {
                                    Text(if (state.gravity) stringResource(R.string.disable_gravity) else stringResource(R.string.enable_gravity))
                                },
                                leadingIcon = { Icon(Icons.Default.ArrowDownward, null) },
                                onClick = { overflow = false; viewModel.toggleGravity() }
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = {
                                Text(stringResource(R.string.delete_permanently), color = MaterialTheme.colorScheme.error)
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
        HorizontalSwipeNavigator(
            // Letters loose on the floor are not a message to swipe away from.
            canGoPrevious = previousMessage != null && !state.gravity,
            canGoNext = nextMessage != null && !state.gravity,
            onPrevious = { previousMessage?.let(onOpenMessage) },
            onNext = { nextMessage?.let(onOpenMessage) },
            modifier = Modifier.padding(padding)
        ) {
        Box(Modifier.fillMaxSize()) {
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
                FallingText(
                    text = message.subject.ifBlank { stringResource(R.string.no_subject) },
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    handOverGlyphs = state.gravity,
                    glyphLimit = HEADER_LETTERS,
                    onGlyphs = { lifted["1subject"] = it }
                )
                FallingText(
                    text = buildString {
                        append(message.fromName?.takeIf { it.isNotBlank() } ?: "")
                        if (isNotEmpty()) append(" ")
                        message.fromAddress?.let { append("<$it>") }
                    }.trim(),
                    style = MaterialTheme.typography.bodyMedium
                        .copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    handOverGlyphs = state.gravity,
                    glyphLimit = HEADER_LETTERS,
                    onGlyphs = { lifted["2from"] = it },
                    modifier = Modifier.padding(top = 8.dp)
                )
                if (message.toList.isNotBlank()) {
                    FallingText(
                        text = "to ${message.toList}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        handOverGlyphs = state.gravity,
                        glyphLimit = HEADER_LETTERS,
                        onGlyphs = { lifted["4to"] = it }
                    )
                }
                if (message.ccList.isNotBlank()) {
                    FallingText(
                        text = "cc ${message.ccList}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        handOverGlyphs = state.gravity,
                        glyphLimit = HEADER_LETTERS,
                        onGlyphs = { lifted["5cc"] = it }
                    )
                }
                FallingText(
                    text = formatFullDate(message.receivedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    handOverGlyphs = state.gravity,
                    glyphLimit = HEADER_LETTERS,
                    onGlyphs = { lifted["6date"] = it }
                )
            }

            val files = state.attachments.filter { !it.isInline }
            if (files.isNotEmpty()) {
                // A long press starts picking; a plain tap on a single one
                // still asks what to do with it.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        files.forEach { attachment ->
                            AttachmentChip(
                                attachment = attachment,
                                picking = picked.isNotEmpty(),
                                selected = attachment.id in picked,
                                onClick = {
                                    if (picked.isEmpty()) attachmentChoice = attachment
                                    else picked = picked.toggle(attachment.id)
                                },
                                onLongClick = { picked = picked.toggle(attachment.id) }
                            )
                        }
                    }
                    if (picked.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                stringResource(R.string.attachments_selected, picked.size),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { picked = emptySet() }) {
                                Text(stringResource(R.string.cancel))
                            }
                            // Several files need somewhere to go, not a name
                            // each, so this picks the folder once.
                            Button(onClick = { saveAll.launch(null) }) {
                                Text(stringResource(R.string.attachments_save_selected))
                            }
                        }
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
                    // The stripped body, so the beacons are not merely hidden
                    // but never requested.
                    html = state.insights.displayHtml ?: html,
                    allowRemoteImages = !state.settings.blockRemoteImages || state.imagesUnblocked,
                    allowJavaScript = state.settings.allowJavaScript,
                    imagePolicy = state.imagePolicy,
                    onLink = ::follow,
                    inlineImage = viewModel::inlineImage,
                    darkTheme = isSystemInDarkTheme() || state.settings.theme.isDark,
                    handOverGlyphs = state.gravity,
                    glyphLimit = MAX_GRAVITY_LETTERS,
                    onGlyphs = { lifted["3body"] = it }
                )
            } else {
                FallingText(
                    text = plain.ifBlank { if (state.loading) stringResource(R.string.loading) else stringResource(R.string.empty_message) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    handOverGlyphs = state.gravity,
                    glyphLimit = MAX_GRAVITY_LETTERS,
                    onGlyphs = { lifted["3body"] = it },
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                )
            }
        }

        if (state.gravity && glyphs.isNotEmpty()) {
            GravityOverlay(glyphs, Modifier.fillMaxSize())
        }
        }
        }
    }

    if (showMove) {
        FolderPickerSheet(
            folders = state.moveTargets(),
            accounts = state.accounts,
            title = stringResource(R.string.move_to),
            confirmLabel = stringResource(R.string.move_here),
            preferredAccountId = state.message?.accountId,
            onPick = { showMove = false; viewModel.moveTo(it.id) },
            onDismiss = { showMove = false }
        )
    }

    if (showCopy) {
        FolderPickerSheet(
            folders = state.moveTargets(),
            accounts = state.accounts,
            title = stringResource(R.string.copy_to),
            confirmLabel = stringResource(R.string.copy_here),
            preferredAccountId = state.message?.accountId,
            onPick = { showCopy = false; viewModel.copyTo(it.id) },
            onDismiss = { showCopy = false }
        )
    }

    attachmentChoice?.let { attachment ->
        AlertDialog(
            onDismissRequest = { attachmentChoice = null },
            title = { Text(attachment.fileName) },
            text = {
                Column {
                    Text(
                        formatSize(attachment.sizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ListItem(
                        modifier = Modifier.clickable {
                            attachmentChoice = null
                            viewModel.openAttachment(attachment.id) { file, mime ->
                                shareFile(context, file, mime)
                            }
                        },
                        leadingContent = { Icon(Icons.Default.OpenInNew, null) },
                        headlineContent = { Text(stringResource(R.string.attachment_open)) },
                        supportingContent = {
                            Text(stringResource(R.string.attachment_open_sub))
                        }
                    )
                    ListItem(
                        modifier = Modifier.clickable {
                            attachmentChoice = null
                            attachmentToSave = attachment
                            saveAttachment.launch(
                                MimeUtil.sanitizeFileName(attachment.fileName)
                            )
                        },
                        leadingContent = { Icon(Icons.Default.Save, null) },
                        headlineContent = { Text(stringResource(R.string.attachment_save)) },
                        supportingContent = {
                            Text(stringResource(R.string.attachment_save_sub))
                        }
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { attachmentChoice = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
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
            title = stringResource(R.string.delete_forever_q),
            message = stringResource(R.string.delete_forever_body),
            confirmLabel = stringResource(R.string.delete),
            destructive = true,
            onConfirm = viewModel::deleteForever,
            onDismiss = { confirmDelete = false }
        )
    }
}

/** The Activity behind a Compose context, for the few things only it can do. */
private fun android.content.Context.findActivity(): Activity? {
    var context: android.content.Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

/** The subject and sender are short; the body is what needs a budget. */
private const val HEADER_LETTERS = 120

/** A name the picker can offer, from the subject rather than the row id. */
private fun de.uwumail.data.db.MessageEntity?.emlFileName(): String {
    val subject = this?.subject?.takeIf { it.isNotBlank() } ?: "message"
    return MimeUtil.sanitizeFileName(subject).take(80) + ".eml"
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
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.open_with)))
    }
}

private fun Set<Long>.toggle(id: Long): Set<Long> =
    if (id in this) this - id else this + id

/**
 * A chip that can also be held. The Material chips take a click and nothing
 * else, and holding one is how a selection starts here, so this wears their
 * shape rather than borrowing their code.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AttachmentChip(
    attachment: AttachmentEntity,
    picking: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val haptics = rememberHaptics()
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                       else MaterialTheme.colorScheme.onSurface,
        border = if (selected) null
                 else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = { haptics.longPress(); onLongClick() }
        )
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = when {
                    selected -> Icons.Default.Check
                    picking -> Icons.Default.CheckBoxOutlineBlank
                    else -> Icons.Default.AttachFile
                },
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Text(
                "${attachment.fileName} ${formatSize(attachment.sizeBytes)}",
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

/**
 * One labelled action on the message's bottom bar. The word is under the icon
 * because "reply" and "reply all" are the same picture at a glance.
 */
@Composable
private fun ReplyAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Icon(icon, contentDescription = null)
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/**
 * Whether the reply bar belongs on screen.
 *
 * Not before the message has loaded, since there is nothing to answer yet, and
 * not while the letters are falling: a heap on the floor is not a message.
 */
fun showsReplyBar(hasMessage: Boolean, gravity: Boolean): Boolean = hasMessage && !gravity
