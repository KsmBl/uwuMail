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
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import de.uwumail.ui.LocalAppContainer
import de.uwumail.ui.common.SectionHeader
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    val outboxCount by container.db.outboxDao().observeCount().collectAsState(initial = 0)

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        scope.launch {
            snackbarHost.showSnackbar(
                if (granted) "Notifications enabled" else "Notification permission denied"
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item { SectionHeader("Notifications") }
            item {
                ListItem(
                    modifier = Modifier.clickable {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    leadingContent = { Icon(Icons.Default.Notifications, null) },
                    headlineContent = { Text("Allow notifications") },
                    supportingContent = { Text("Required for new-mail alerts") }
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
                    headlineContent = { Text("Per-account notification channels") },
                    supportingContent = {
                        Text("Sounds and importance for each account, in Android settings")
                    }
                )
            }

            item { HorizontalDivider(); SectionHeader("Sync") }
            item {
                ListItem(
                    modifier = Modifier.clickable {
                        de.uwumail.sync.SyncScheduler.syncNow(context)
                        scope.launch { snackbarHost.showSnackbar("Sync queued") }
                    },
                    leadingContent = { Icon(Icons.Default.Sync, null) },
                    headlineContent = { Text("Sync all accounts now") }
                )
                ListItem(
                    modifier = Modifier.clickable {
                        scope.launch {
                            container.syncManager.sendOutbox()
                            snackbarHost.showSnackbar("Outbox flushed")
                        }
                    },
                    leadingContent = { Icon(Icons.Default.Outbox, null) },
                    headlineContent = { Text("Retry outbox") },
                    supportingContent = {
                        Text(
                            if (outboxCount == 0) "Nothing waiting to send"
                            else "$outboxCount message(s) waiting"
                        )
                    }
                )
            }

            item { HorizontalDivider(); SectionHeader("Maintenance") }
            item {
                ListItem(
                    modifier = Modifier.clickable {
                        scope.launch {
                            container.db.ruleDao().pruneLog(System.currentTimeMillis())
                            snackbarHost.showSnackbar("Rule activity log cleared")
                        }
                    },
                    leadingContent = { Icon(Icons.Default.CleaningServices, null) },
                    headlineContent = { Text("Clear rule activity log") }
                )
            }

            item { HorizontalDivider(); SectionHeader("About") }
            item {
                ListItem(
                    headlineContent = { Text("uwuMail") },
                    supportingContent = {
                        Text(
                            "Multi-account IMAP client with regex rules, custom From " +
                                "addresses and device-local folders."
                        )
                    }
                )
            }
        }
    }
}
