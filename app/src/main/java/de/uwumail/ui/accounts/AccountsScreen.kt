package de.uwumail.ui.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import de.uwumail.data.db.AccountEntity
import de.uwumail.ui.LocalAppContainer
import de.uwumail.ui.common.ConfirmDialog
import de.uwumail.ui.common.EmptyState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val accounts by container.db.accountDao().observeAll()
        .collectAsState(initial = emptyList())
    var pendingDelete by remember { mutableStateOf<AccountEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Accounts") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Default.Add, "Add account")
            }
        }
    ) { padding ->
        if (accounts.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState("No accounts", "Tap + to add your first mail account.")
            }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            items(accounts, key = { it.id }) { account ->
                ListItem(
                    modifier = Modifier.clickable { onEdit(account.id) },
                    leadingContent = {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(Color(account.color)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                account.displayName.trim().take(1).uppercase(),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    },
                    headlineContent = { Text(account.displayName) },
                    supportingContent = {
                        Text(
                            buildString {
                                append(account.email)
                                append(" · ")
                                append(if (account.pushEnabled) "push" else "${account.syncIntervalMinutes} min")
                                if (!account.notificationsEnabled) append(" · muted")
                            }
                        )
                    },
                    trailingContent = {
                        IconButton(onClick = { pendingDelete = account }) {
                            Icon(
                                Icons.Default.Delete,
                                "Remove account",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                )
                HorizontalDivider()
            }
        }
    }

    pendingDelete?.let { account ->
        ConfirmDialog(
            title = "Remove ${account.displayName}?",
            message = "Cached mail, folders and stored passwords for this account are deleted " +
                "from the device. Nothing on the server changes.",
            confirmLabel = "Remove",
            destructive = true,
            onConfirm = {
                scope.launch { container.accountRepository.delete(account.id) }
            },
            onDismiss = { pendingDelete = null }
        )
    }
}
