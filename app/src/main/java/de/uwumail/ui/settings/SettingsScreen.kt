package de.uwumail.ui.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Outbox
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Sync
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import android.content.ClipData
import android.content.ClipboardManager
import de.uwumail.R
import de.uwumail.mail.oauth.OAuthProvider
import androidx.compose.ui.platform.LocalContext
import de.uwumail.ui.LocalAppContainer
import de.uwumail.ui.common.SectionHeader
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onManageBlocked: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    val outboxCount by container.db.outboxDao().observeCount().collectAsState(initial = 0)
    // Read here rather than inside the coroutines below, which are not composable.
    val notificationsEnabled = stringResource(R.string.notif_enabled)
    val notificationsDenied = stringResource(R.string.notif_denied)
    val syncQueued = stringResource(R.string.sync_queued)
    val outboxFlushed = stringResource(R.string.outbox_flushed)
    val ruleLogCleared = stringResource(R.string.rule_log_cleared)

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        scope.launch {
            snackbarHost.showSnackbar(
                if (granted) notificationsEnabled else notificationsDenied
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item { SectionHeader(stringResource(R.string.section_appearance)) }
            item { AppearanceSection() }

            item { HorizontalDivider(); SectionHeader(stringResource(R.string.section_notifications)) }
            item {
                ListItem(
                    modifier = Modifier.clickable {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    leadingContent = { Icon(Icons.Default.Notifications, null) },
                    headlineContent = { Text(stringResource(R.string.notif_allow)) },
                    supportingContent = { Text(stringResource(R.string.notif_allow_sub)) }
                )
                ListItem(
                    modifier = Modifier.clickable {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            )
                        }
                    },
                    leadingContent = { Icon(Icons.Default.Notifications, null) },
                    headlineContent = { Text(stringResource(R.string.notif_channels)) },
                    supportingContent = {
                        Text(stringResource(R.string.notif_channels_sub))
                    }
                )
            }

            item { HorizontalDivider(); SectionHeader(stringResource(R.string.section_background)) }
            item {
                BackgroundSection { message ->
                    scope.launch { snackbarHost.showSnackbar(message) }
                }
            }

            item { HorizontalDivider(); SectionHeader(stringResource(R.string.section_reading)) }
            item { PrivacySection() }

            item { HorizontalDivider(); SectionHeader(stringResource(R.string.section_swipe)) }
            item { SwipeSection() }

            item { HorizontalDivider(); SectionHeader(stringResource(R.string.section_images)) }
            item { ImagesSection() }

            item { HorizontalDivider(); SectionHeader(stringResource(R.string.section_sync)) }
            item {
                ListItem(
                    modifier = Modifier.clickable {
                        de.uwumail.sync.SyncScheduler.syncNow(context)
                        scope.launch { snackbarHost.showSnackbar(syncQueued) }
                    },
                    leadingContent = { Icon(Icons.Default.Sync, null) },
                    headlineContent = { Text(stringResource(R.string.sync_now)) }
                )
                ListItem(
                    modifier = Modifier.clickable {
                        scope.launch {
                            container.syncManager.sendOutbox()
                            snackbarHost.showSnackbar(outboxFlushed)
                        }
                    },
                    leadingContent = { Icon(Icons.Default.Outbox, null) },
                    headlineContent = { Text(stringResource(R.string.outbox_retry)) },
                    supportingContent = {
                        Text(
                            if (outboxCount == 0) stringResource(R.string.outbox_empty)
                            else "$outboxCount message(s) waiting"
                        )
                    }
                )
            }

            item { HorizontalDivider(); SectionHeader(stringResource(R.string.section_window)) }
            item { SyncWindowSection() }

            item { HorizontalDivider(); SectionHeader(stringResource(R.string.section_spam)) }
            item {
                SpamListsSection(onManageBlocked = onManageBlocked) { message ->
                    scope.launch { snackbarHost.showSnackbar(message) }
                }
            }

            item { HorizontalDivider(); SectionHeader(stringResource(R.string.section_google)) }
            item { GoogleSignInSettings() }

            item { HorizontalDivider(); SectionHeader(stringResource(R.string.section_backup)) }
            item {
                BackupSection { message -> scope.launch { snackbarHost.showSnackbar(message) } }
            }

            item { HorizontalDivider(); SectionHeader(stringResource(R.string.section_maintenance)) }
            item {
                ListItem(
                    modifier = Modifier.clickable {
                        scope.launch {
                            container.db.ruleDao().pruneLog(System.currentTimeMillis())
                            snackbarHost.showSnackbar(ruleLogCleared)
                        }
                    },
                    leadingContent = { Icon(Icons.Default.CleaningServices, null) },
                    headlineContent = { Text(stringResource(R.string.clear_rule_log)) }
                )
            }

            item { HorizontalDivider(); SectionHeader(stringResource(R.string.section_about)) }
            item {
                ListItem(
                    headlineContent = { Text("uwuMail") },
                    supportingContent = {
                        Text(stringResource(R.string.about_body))
                    }
                )
            }
        }
    }
}

/**
 * uwuMail ships without a Google OAuth client id: each install registers its own
 * so the sign-in is bound to this app's package and signing certificate. These
 * are the two values Google Cloud Console asks for.
 */
@Composable
private fun GoogleSignInSettings() {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val config = container.oauthConfig
    var clientId by remember {
        mutableStateOf(config.clientId(OAuthProvider.GOOGLE).orEmpty())
    }
    val fingerprint = remember { config.signingFingerprintSha1() }

    fun copy(label: String, value: String) {
        runCatching {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        }
    }

    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.google_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            CopyableRow(stringResource(R.string.google_package), config.packageName) { copy("package", it) }
            CopyableRow(stringResource(R.string.google_sha1), fingerprint ?: stringResource(R.string.unavailable)) {
                copy("sha1", it)
            }
            CopyableRow(stringResource(R.string.google_redirect), config.redirectUri) { copy("redirect", it) }

            OutlinedTextField(
                value = clientId,
                onValueChange = {
                    clientId = it
                    config.setClientId(OAuthProvider.GOOGLE, it)
                },
                label = { Text(stringResource(R.string.google_client_id)) },
                placeholder = { Text("....apps.googleusercontent.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            )
            if (clientId.isBlank()) {
                Text(
                    stringResource(R.string.google_unset),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun CopyableRow(label: String, value: String, onCopy: (String) -> Unit) {
    Column(Modifier.padding(top = 12.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { onCopy(value) }) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy $label")
            }
        }
    }
}
