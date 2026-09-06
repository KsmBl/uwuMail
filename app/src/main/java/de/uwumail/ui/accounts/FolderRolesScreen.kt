package de.uwumail.ui.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Drafts
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.uwumail.R
import de.uwumail.data.db.AccountEntity
import de.uwumail.data.db.FolderEntity
import de.uwumail.ui.LocalAppContainer
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** One job a folder can be given, and where the account records it. */
private enum class FolderRole(
    val label: Int,
    val explanation: Int,
    val icon: ImageVector,
    val read: (AccountEntity) -> String?,
    val write: (AccountEntity, String?) -> AccountEntity
) {
    ARCHIVE(
        R.string.role_archive, R.string.role_archive_sub, Icons.Default.Archive,
        { it.archiveFolder }, { account, path -> account.copy(archiveFolder = path) }
    ),
    SENT(
        R.string.role_sent, R.string.role_sent_sub, Icons.AutoMirrored.Filled.Send,
        { it.sentFolder }, { account, path -> account.copy(sentFolder = path) }
    ),
    DRAFTS(
        R.string.role_drafts, R.string.role_drafts_sub, Icons.Default.Drafts,
        { it.draftsFolder }, { account, path -> account.copy(draftsFolder = path) }
    ),
    TRASH(
        R.string.role_trash, R.string.role_trash_sub, Icons.Default.Delete,
        { it.trashFolder }, { account, path -> account.copy(trashFolder = path) }
    )
}

/**
 * Which folder does which job, for one account.
 *
 * These are guessed from what the server advertises when an account is first
 * seen, which is right for most and wrong for the rest — a server with no
 * SPECIAL-USE attributes, or with folders in another language, leaves archive
 * or trash pointing nowhere. Nothing in the app could fix that from the
 * outside, so it is set here instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderRolesScreen(accountId: Long, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()

    val account by container.db.accountDao().observe(accountId)
        .collectAsState(initial = null)
    val folders by container.db.folderDao().observeForAccount(accountId)
        .map { list -> list.filter { !it.isLocal && it.selectable } }
        .collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.configure_folders))
                        account?.let {
                            Text(
                                it.email,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        val current = account ?: return@Scaffold
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Text(
                    stringResource(R.string.roles_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
            items(FolderRole.entries.size) { index ->
                val role = FolderRole.entries[index]
                RoleRow(
                    role = role,
                    chosen = role.read(current),
                    folders = folders,
                    onPick = { path ->
                        scope.launch {
                            container.db.accountDao().update(role.write(current, path))
                        }
                    }
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun RoleRow(
    role: FolderRole,
    chosen: String?,
    folders: List<FolderEntity>,
    onPick: (String?) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val name = folders.firstOrNull { it.path == chosen }?.displayName
        ?: chosen
        ?: stringResource(R.string.role_unset)

    Box {
        ListItem(
            modifier = Modifier.clickable { open = true },
            leadingContent = { Icon(role.icon, null) },
            headlineContent = { Text(stringResource(role.label)) },
            supportingContent = {
                Column {
                    Text(name)
                    Text(
                        stringResource(role.explanation),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.role_unset)) },
                onClick = { open = false; onPick(null) }
            )
            folders.forEach { folder ->
                DropdownMenuItem(
                    text = { Text(folder.displayName) },
                    onClick = { open = false; onPick(folder.path) }
                )
            }
        }
    }
}
