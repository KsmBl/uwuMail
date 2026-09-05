package de.uwumail.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.browser.customtabs.CustomTabsIntent
import de.uwumail.core.Security
import de.uwumail.mail.oauth.OAuthProvider
import de.uwumail.data.db.IdentityEntity
import de.uwumail.ui.common.ConfirmDialog
import de.uwumail.ui.common.LabeledField
import de.uwumail.ui.common.SectionHeader
import de.uwumail.ui.common.TextPromptDialog
import de.uwumail.ui.containerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSetupScreen(accountId: Long, onDone: () -> Unit) {
    val viewModel = containerViewModel(key = "setup-$accountId") {
        AccountSetupViewModel(it, accountId)
    }
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var showPassword by remember { mutableStateOf(false) }
    var addIdentity by remember { mutableStateOf(false) }
    var identityToDelete by remember { mutableStateOf<IdentityEntity?>(null) }

    LaunchedEffect(state.saved) { if (state.saved) onDone() }

    // The view model asks for a browser hop by publishing a URL.
    LaunchedEffect(state.launchAuthUri) {
        val uri = state.launchAuthUri ?: return@LaunchedEffect
        viewModel.onAuthUriLaunched()
        runCatching {
            CustomTabsIntent.Builder().setShowTitle(true).build()
                .launchUrl(context, uri.toUri())
        }.onFailure {
            runCatching {
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri.toUri()))
            }
        }
    }

    Scaffold(
        topBar = {
            Column {
            TopAppBar(
                title = { Text(if (state.isNew) "Add account" else "Edit account") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (state.saving) {
                        CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp))
                    } else {
                        IconButton(onClick = viewModel::save, enabled = state.canSave) {
                            Icon(Icons.Default.Save, "Save")
                        }
                    }
                }
            )
            if (state.saving || state.discovering || state.testing || state.signingIn) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            }
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            SignInCard(
                state = state,
                onSignIn = { viewModel.signInWith(OAuthProvider.GOOGLE) },
                onUsePassword = viewModel::usePasswordInstead
            )

            SectionHeader("Identity")
            LabeledField("Your name", state.displayName, { v -> viewModel.update { it.copy(displayName = v) } })
            LabeledField(
                "Email address", state.email,
                { v -> viewModel.update { it.copy(email = v) } },
                supportingText = "Used as the default From address"
            )
            if (!state.isOAuth) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = viewModel::discover,
                        enabled = state.email.contains('@') && !state.discovering,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (state.discovering) CircularProgressIndicator(Modifier.size(16.dp))
                        else Icon(Icons.Default.Search, null)
                        Text("  Find settings")
                    }
                }
            }

            if (state.needsPassword) {
            SectionHeader("Password")
            OutlinedTextField(
                value = state.imapPassword,
                onValueChange = { v -> viewModel.update { it.copy(imapPassword = v) } },
                label = { Text(if (state.isNew) "Password" else "New password (leave empty to keep)") },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            "Toggle password"
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            )
            SwitchRow(
                "Same password for SMTP",
                state.samePassword
            ) { v -> viewModel.update { it.copy(samePassword = v) } }
            if (!state.samePassword) {
                OutlinedTextField(
                    value = state.smtpPassword,
                    onValueChange = { v -> viewModel.update { it.copy(smtpPassword = v) } },
                    label = { Text("SMTP password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            }

            SectionHeader("Incoming (IMAP)")
            LabeledField("Host", state.imapHost, { v -> viewModel.update { it.copy(imapHost = v) } })
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.imapPort,
                    onValueChange = { v -> viewModel.update { it.copy(imapPort = v.filter(Char::isDigit)) } },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f).padding(start = 16.dp, end = 8.dp)
                )
                SecurityPicker(
                    state.imapSecurity,
                    Modifier.weight(1.2f).padding(end = 16.dp)
                ) { v -> viewModel.update { it.copy(imapSecurity = v) } }
            }
            LabeledField(
                "Username", state.imapUsername,
                { v -> viewModel.update { it.copy(imapUsername = v) } },
                supportingText = "Defaults to the email address"
            )

            SectionHeader("Outgoing (SMTP)")
            LabeledField("Host", state.smtpHost, { v -> viewModel.update { it.copy(smtpHost = v) } })
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.smtpPort,
                    onValueChange = { v -> viewModel.update { it.copy(smtpPort = v.filter(Char::isDigit)) } },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f).padding(start = 16.dp, end = 8.dp)
                )
                SecurityPicker(
                    state.smtpSecurity,
                    Modifier.weight(1.2f).padding(end = 16.dp)
                ) { v -> viewModel.update { it.copy(smtpSecurity = v) } }
            }
            LabeledField("Username", state.smtpUsername, { v -> viewModel.update { it.copy(smtpUsername = v) } })

            SectionHeader("Sync & notifications")
            SwitchRow("Background sync", state.syncEnabled) { v ->
                viewModel.update { it.copy(syncEnabled = v) }
            }
            LabeledField(
                "Sync interval (minutes)", state.syncIntervalMinutes,
                { v -> viewModel.update { it.copy(syncIntervalMinutes = v.filter(Char::isDigit)) } },
                supportingText = "Android enforces a 15 minute minimum"
            )
            SwitchRow(
                "Push (keep IMAP IDLE open)",
                state.pushEnabled
            ) { v -> viewModel.update { it.copy(pushEnabled = v) } }
            SwitchRow("Notifications", state.notificationsEnabled) { v ->
                viewModel.update { it.copy(notificationsEnabled = v) }
            }

            SectionHeader("Sending as")
            SwitchRow(
                "Envelope sender follows the From address",
                state.useIdentityAsEnvelopeSender
            ) { v -> viewModel.update { it.copy(useIdentityAsEnvelopeSender = v) } }
            Text(
                "Off means MAIL FROM always uses the account address, which some servers require.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            if (!state.isNew) {
                SectionHeader("Identities")
                Text(
                    "Addresses you can send as from this account. The default is used " +
                        "for new messages.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                state.identities.forEach { identity ->
                    ListItem(
                        headlineContent = { Text(identity.email) },
                        supportingContent = { Text(identity.displayName) },
                        leadingContent = {
                            IconButton(
                                onClick = { viewModel.setDefaultIdentity(identity) },
                                enabled = !identity.isDefault
                            ) {
                                Icon(
                                    if (identity.isDefault) Icons.Default.Star
                                    else Icons.Default.StarBorder,
                                    contentDescription = if (identity.isDefault) "Default address"
                                    else "Use as default",
                                    tint = if (identity.isDefault) MaterialTheme.colorScheme.tertiary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        trailingContent = {
                            IconButton(onClick = { identityToDelete = identity }) {
                                Icon(
                                    Icons.Default.Delete,
                                    "Remove identity",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    )
                }
                if (state.identities.isEmpty()) {
                    Text(
                        "None saved — the account address is used.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                TextButton(
                    onClick = { addIdentity = true },
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) { Text("Add identity") }
            }

            SectionHeader("Advanced")
            SwitchRow(
                "Accept any TLS certificate",
                state.trustAllCerts
            ) { v -> viewModel.update { it.copy(trustAllCerts = v) } }
            OutlinedTextField(
                value = state.signature,
                onValueChange = { v -> viewModel.update { it.copy(signature = v) } },
                label = { Text("Signature") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            )

            HorizontalDivider()

            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = viewModel::test,
                    enabled = !state.testing && state.imapHost.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (state.testing) CircularProgressIndicator(Modifier.size(16.dp))
                    Text(if (state.testing) "  Testing…" else "Test connection")
                }
                state.testResult?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary)
                }
                state.error?.let {
                    Card {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }
    }

    identityToDelete?.let { identity ->
        ConfirmDialog(
            title = "Delete identity?",
            message = "\"${identity.email}\" is removed from this account's saved " +
                "addresses. Nothing on the server changes.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { viewModel.deleteIdentity(identity) },
            onDismiss = { identityToDelete = null }
        )
    }

    if (addIdentity) {
        TextPromptDialog(
            title = "Add identity",
            label = "Email address",
            confirmLabel = "Add",
            supportingText = "Any address your server allows in the From header",
            onConfirm = { viewModel.addIdentity(it.substringBefore('@'), it) },
            onDismiss = { addIdentity = false }
        )
    }
}


/**
 * Offers OAuth sign-in up front, because for Google it is the only thing that
 * works without the user going off to create an App Password by hand.
 */
@Composable
private fun SignInCard(
    state: AccountSetupState,
    onSignIn: () -> Unit,
    onUsePassword: () -> Unit
) {
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Sign in with Google", style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    state.signedIn && state.isOAuth ->
                        "Signed in as ${state.email}. Server settings are filled in and the " +
                            "password fields are not used."
                    !state.googleConfigured ->
                        "Needs a Google OAuth client id. Add one under Settings > Google " +
                            "sign-in, then come back."
                    else ->
                        "For Gmail this is the only option that works — Google no longer " +
                            "accepts your account password over IMAP."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = onSignIn,
                    enabled = state.googleConfigured && !state.signingIn
                ) {
                    if (state.signingIn) {
                        CircularProgressIndicator(Modifier.size(16.dp))
                        Text("  Waiting…")
                    } else {
                        Icon(Icons.Default.AccountCircle, null)
                        Text(if (state.signedIn) "  Sign in again" else "  Sign in with Google")
                    }
                }
                if (state.isOAuth) {
                    TextButton(onClick = onUsePassword) { Text("Use a password") }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SecurityPicker(
    value: Security,
    modifier: Modifier = Modifier,
    onChange: (Security) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                when (value) {
                    Security.SSL_TLS -> "SSL/TLS"
                    Security.STARTTLS -> "STARTTLS"
                    Security.NONE -> "None"
                }
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Security.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            when (option) {
                                Security.SSL_TLS -> "SSL/TLS"
                                Security.STARTTLS -> "STARTTLS"
                                Security.NONE -> "None (insecure)"
                            }
                        )
                    },
                    onClick = { open = false; onChange(option) }
                )
            }
        }
    }
}
