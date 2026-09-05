package de.uwumail.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import de.uwumail.ui.containerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(
    accountId: Long,
    replyToMessageId: Long,
    replyAll: Boolean,
    forwardMessageId: Long,
    mailto: String?,
    onDone: () -> Unit
) {
    val viewModel = containerViewModel(
        key = "compose-$accountId-$replyToMessageId-$forwardMessageId"
    ) { ComposeViewModel(it, accountId, replyToMessageId, replyAll, forwardMessageId, mailto) }
    val state by viewModel.state.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }

    var accountMenu by remember { mutableStateOf(false) }
    var identityMenu by remember { mutableStateOf(false) }

    LaunchedEffect(state.sent) { if (state.sent) onDone() }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHost.showSnackbar("Send failed: $it")
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text("New message") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Discard")
                    }
                },
                actions = {
                    if (state.sending) {
                        CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp))
                    } else {
                        IconButton(onClick = viewModel::send, enabled = state.canSend) {
                            Icon(Icons.AutoMirrored.Filled.Send, "Send")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // ------------------------------------------------ sending account
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Account", Modifier.width(72.dp), style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { accountMenu = true }) {
                    Text(state.account?.displayName ?: "Select account")
                    Icon(Icons.Default.ArrowDropDown, null)
                }
                DropdownMenu(expanded = accountMenu, onDismissRequest = { accountMenu = false }) {
                    state.accounts.forEach { account ->
                        DropdownMenuItem(
                            text = { Text("${account.displayName} (${account.email})") },
                            onClick = { accountMenu = false; viewModel.setAccount(account.id) }
                        )
                    }
                }
            }

            // ---------------------------------------------------- From address
            Column(Modifier.padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "From",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    if (state.accountIdentities.isNotEmpty()) {
                        TextButton(onClick = { identityMenu = true }) {
                            Icon(Icons.Default.AlternateEmail, null)
                            Text(" Identities")
                        }
                        DropdownMenu(
                            expanded = identityMenu,
                            onDismissRequest = { identityMenu = false }
                        ) {
                            state.accountIdentities.forEach { identity ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(identity.displayName)
                                            Text(
                                                identity.email,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    onClick = {
                                        identityMenu = false
                                        viewModel.setIdentity(identity)
                                    }
                                )
                            }
                        }
                    }
                    IconButton(
                        onClick = viewModel::saveCurrentAsIdentity,
                        enabled = state.fromAddress.contains('@')
                    ) {
                        Icon(Icons.Default.BookmarkAdd, "Save as identity")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.fromName,
                        onValueChange = viewModel::setFromName,
                        label = { Text("Display name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = state.fromAddress,
                        onValueChange = viewModel::setFromAddress,
                        label = { Text("Address") },
                        singleLine = true,
                        isError = state.fromAddress.isNotBlank() && !state.fromAddress.contains('@'),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.weight(1.4f)
                    )
                }
                Text(
                    "Any address your server lets you send as. Save it as an identity to reuse it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                )
            }

            HorizontalDivider()

            OutlinedTextField(
                value = state.to,
                onValueChange = viewModel::setTo,
                label = { Text("To") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                trailingIcon = {
                    TextButton(onClick = viewModel::toggleCcBcc) {
                        Text(if (state.showCcBcc) "Hide" else "Cc/Bcc")
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            )

            if (state.showCcBcc) {
                OutlinedTextField(
                    value = state.cc,
                    onValueChange = viewModel::setCc,
                    label = { Text("Cc") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                )
                OutlinedTextField(
                    value = state.bcc,
                    onValueChange = viewModel::setBcc,
                    label = { Text("Bcc") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            OutlinedTextField(
                value = state.subject,
                onValueChange = viewModel::setSubject,
                label = { Text("Subject") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            )

            OutlinedTextField(
                value = state.body,
                onValueChange = viewModel::setBody,
                label = { Text("Message") },
                minLines = 12,
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            )
        }
    }
}
