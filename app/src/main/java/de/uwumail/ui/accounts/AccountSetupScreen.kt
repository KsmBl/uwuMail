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
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Folder
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.browser.customtabs.CustomTabsIntent
import de.uwumail.R
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
fun AccountSetupScreen(
    accountId: Long,
    onConfigureFolders: (Long) -> Unit,
    onDone: () -> Unit
) {
    val viewModel = containerViewModel(key = "setup-$accountId") {
        AccountSetupViewModel(it, accountId)
    }
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var showPassword by remember { mutableStateOf(false) }
    var addIdentity by remember { mutableStateOf(false) }
    var identityToDelete by remember { mutableStateOf<IdentityEntity?>(null) }
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(state.saved) { if (state.saved) onDone() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHost.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

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
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            Column {
            TopAppBar(
                title = { Text(if (state.isNew) stringResource(R.string.setup_add) else stringResource(R.string.setup_edit)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    if (state.saving) {
                        CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp))
                    } else {
                        IconButton(onClick = viewModel::save, enabled = state.canSave) {
                            Icon(Icons.Default.Save, stringResource(R.string.save))
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

            SectionHeader(stringResource(R.string.identity))
            LabeledField(stringResource(R.string.your_name), state.displayName, { v -> viewModel.update { it.copy(displayName = v) } })
            LabeledField(
                stringResource(R.string.email_address), state.email,
                { v -> viewModel.update { it.copy(email = v) } },
                supportingText = stringResource(R.string.used_as_default_from)
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
                        Text("  " + stringResource(R.string.find_settings))
                    }
                }
            }

            if (state.needsPassword) {
            SectionHeader(stringResource(R.string.password))
            OutlinedTextField(
                value = state.imapPassword,
                onValueChange = { v -> viewModel.update { it.copy(imapPassword = v) } },
                label = { Text(if (state.isNew) stringResource(R.string.password) else stringResource(R.string.new_password)) },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            stringResource(R.string.toggle_password)
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            )
            SwitchRow(
                stringResource(R.string.same_password_smtp),
                state.samePassword
            ) { v -> viewModel.update { it.copy(samePassword = v) } }
            if (!state.samePassword) {
                OutlinedTextField(
                    value = state.smtpPassword,
                    onValueChange = { v -> viewModel.update { it.copy(smtpPassword = v) } },
                    label = { Text(stringResource(R.string.smtp_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            }

            SectionHeader(stringResource(R.string.incoming_imap))
            LabeledField(stringResource(R.string.host), state.imapHost, { v -> viewModel.update { it.copy(imapHost = v) } })
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.imapPort,
                    onValueChange = { v -> viewModel.update { it.copy(imapPort = v.filter(Char::isDigit)) } },
                    label = { Text(stringResource(R.string.port)) },
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
                stringResource(R.string.username), state.imapUsername,
                { v -> viewModel.update { it.copy(imapUsername = v) } },
                supportingText = stringResource(R.string.defaults_to_email)
            )

            SectionHeader(stringResource(R.string.outgoing_smtp))
            LabeledField(stringResource(R.string.host), state.smtpHost, { v -> viewModel.update { it.copy(smtpHost = v) } })
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.smtpPort,
                    onValueChange = { v -> viewModel.update { it.copy(smtpPort = v.filter(Char::isDigit)) } },
                    label = { Text(stringResource(R.string.port)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f).padding(start = 16.dp, end = 8.dp)
                )
                SecurityPicker(
                    state.smtpSecurity,
                    Modifier.weight(1.2f).padding(end = 16.dp)
                ) { v -> viewModel.update { it.copy(smtpSecurity = v) } }
            }
            LabeledField(stringResource(R.string.username), state.smtpUsername, { v -> viewModel.update { it.copy(smtpUsername = v) } })

            SectionHeader(stringResource(R.string.sync_and_notifications))
            SwitchRow(stringResource(R.string.background_sync), state.syncEnabled) { v ->
                viewModel.update { it.copy(syncEnabled = v) }
            }
            // How often is set in Settings, beside the hours checking is
            // allowed in: both answer the same question and belong together.
            SwitchRow(
                stringResource(R.string.push_idle),
                state.pushEnabled
            ) { v -> viewModel.update { it.copy(pushEnabled = v) } }
            SwitchRow(stringResource(R.string.notifications), state.notificationsEnabled) { v ->
                viewModel.update { it.copy(notificationsEnabled = v) }
            }

            SectionHeader(stringResource(R.string.sending_as))
            SwitchRow(
                stringResource(R.string.envelope_follows),
                state.useIdentityAsEnvelopeSender
            ) { v -> viewModel.update { it.copy(useIdentityAsEnvelopeSender = v) } }
            Text(
                stringResource(R.string.envelope_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            if (!state.isNew) {
                SectionHeader(stringResource(R.string.identities))
                Text(
                    stringResource(R.string.identities_hint),
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
                                    contentDescription = if (identity.isDefault) stringResource(R.string.default_address)
                                    else stringResource(R.string.use_as_default),
                                    tint = if (identity.isDefault) MaterialTheme.colorScheme.tertiary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        trailingContent = {
                            IconButton(onClick = { identityToDelete = identity }) {
                                Icon(
                                    Icons.Default.Delete,
                                    stringResource(R.string.remove_identity),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    )
                }
                if (state.identities.isEmpty()) {
                    Text(
                        stringResource(R.string.no_identities),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                TextButton(
                    onClick = { addIdentity = true },
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) { Text(stringResource(R.string.add_identity)) }
            }

            // Only for an account that exists: there are no folders to map
            // until it has been saved and synced once.
            if (accountId > 0) {
                SectionHeader(stringResource(R.string.section_folders))
                ListItem(
                    modifier = Modifier.clickable { onConfigureFolders(accountId) },
                    leadingContent = { Icon(Icons.Default.Folder, null) },
                    headlineContent = { Text(stringResource(R.string.configure_folders)) },
                    supportingContent = { Text(stringResource(R.string.configure_folders_sub)) }
                )
            }

            SectionHeader(stringResource(R.string.advanced))
            SwitchRow(
                stringResource(R.string.accept_any_cert),
                state.trustAllCerts
            ) { v -> viewModel.update { it.copy(trustAllCerts = v) } }
            OutlinedTextField(
                value = state.signature,
                onValueChange = { v -> viewModel.update { it.copy(signature = v) } },
                label = { Text(stringResource(R.string.signature)) },
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
                    Text(if (state.testing) "  " + stringResource(R.string.testing) else stringResource(R.string.test_connection))
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
            title = stringResource(R.string.delete_identity_q),
            message = stringResource(R.string.identity_delete_body, identity.email),
            confirmLabel = stringResource(R.string.delete),
            destructive = true,
            onConfirm = { viewModel.deleteIdentity(identity) },
            onDismiss = { identityToDelete = null }
        )
    }

    if (addIdentity) {
        TextPromptDialog(
            title = stringResource(R.string.add_identity),
            label = stringResource(R.string.email_address),
            confirmLabel = stringResource(R.string.add),
            supportingText = stringResource(R.string.any_from_address),
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
            Text(stringResource(R.string.sign_in_google), style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    state.signedIn && state.isOAuth ->
                        stringResource(R.string.google_signed_in, state.email)
                    !state.googleConfigured ->
                        stringResource(R.string.google_needs_id)
                    else ->
                        stringResource(R.string.google_only_option)
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
                        Text("  " + stringResource(R.string.waiting))
                    } else {
                        Icon(Icons.Default.AccountCircle, null)
                        Text(if (state.signedIn) "  " + stringResource(R.string.sign_in_again) else "  " + stringResource(R.string.sign_in_google))
                    }
                }
                if (state.isOAuth) {
                    TextButton(onClick = onUsePassword) { Text(stringResource(R.string.use_a_password)) }
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
                    Security.NONE -> stringResource(R.string.sec_none)
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
                                Security.NONE -> stringResource(R.string.sec_none_insecure)
                            }
                        )
                    },
                    onClick = { open = false; onChange(option) }
                )
            }
        }
    }
}
