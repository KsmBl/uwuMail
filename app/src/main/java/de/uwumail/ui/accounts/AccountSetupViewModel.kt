package de.uwumail.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.uwumail.core.Security
import de.uwumail.data.db.AccountEntity
import de.uwumail.data.db.IdentityEntity
import de.uwumail.di.AppContainer
import de.uwumail.mail.Autoconfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

data class AccountSetupState(
    val accountId: Long = 0,
    val displayName: String = "",
    val email: String = "",
    val imapHost: String = "",
    val imapPort: String = "993",
    val imapSecurity: Security = Security.SSL_TLS,
    val imapUsername: String = "",
    val imapPassword: String = "",
    val smtpHost: String = "",
    val smtpPort: String = "587",
    val smtpSecurity: Security = Security.STARTTLS,
    val smtpUsername: String = "",
    val smtpPassword: String = "",
    val samePassword: Boolean = true,
    val trustAllCerts: Boolean = false,
    val useIdentityAsEnvelopeSender: Boolean = true,
    val syncEnabled: Boolean = true,
    val syncIntervalMinutes: String = "15",
    val pushEnabled: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val signature: String = "",
    val identities: List<IdentityEntity> = emptyList(),
    val discovering: Boolean = false,
    val testing: Boolean = false,
    val testResult: String? = null,
    val error: String? = null,
    val saved: Boolean = false,
    val advancedOpen: Boolean = false
) {
    val isNew: Boolean get() = accountId == 0L
    val canSave: Boolean
        get() = email.contains('@') && imapHost.isNotBlank() && smtpHost.isNotBlank() &&
            (!isNew || imapPassword.isNotEmpty())
}

class AccountSetupViewModel(
    private val container: AppContainer,
    private val accountId: Long
) : ViewModel() {

    private val _state = MutableStateFlow(AccountSetupState(accountId = accountId))
    val state = _state.asStateFlow()

    init {
        if (accountId > 0) {
            viewModelScope.launch {
                val account = container.db.accountDao().get(accountId) ?: return@launch
                val identities = container.db.identityDao().forAccount(accountId)
                _state.update {
                    it.copy(
                        displayName = account.displayName,
                        email = account.email,
                        imapHost = account.imapHost,
                        imapPort = account.imapPort.toString(),
                        imapSecurity = runCatching { Security.valueOf(account.imapSecurity) }
                            .getOrDefault(Security.SSL_TLS),
                        imapUsername = account.imapUsername,
                        smtpHost = account.smtpHost,
                        smtpPort = account.smtpPort.toString(),
                        smtpSecurity = runCatching { Security.valueOf(account.smtpSecurity) }
                            .getOrDefault(Security.STARTTLS),
                        smtpUsername = account.smtpUsername,
                        trustAllCerts = account.trustAllCerts,
                        useIdentityAsEnvelopeSender = account.useIdentityAsEnvelopeSender,
                        syncEnabled = account.syncEnabled,
                        syncIntervalMinutes = account.syncIntervalMinutes.toString(),
                        pushEnabled = account.pushEnabled,
                        notificationsEnabled = account.notificationsEnabled,
                        signature = account.signature.orEmpty(),
                        identities = identities,
                        samePassword = false
                    )
                }
            }
        }
    }

    fun update(transform: (AccountSetupState) -> AccountSetupState) = _state.update(transform)

    /** Fills the server fields from the domain's published autoconfig, if any. */
    fun discover() {
        val email = _state.value.email
        if (!email.contains('@')) return
        _state.update { it.copy(discovering = true, error = null) }
        viewModelScope.launch {
            val result = runCatching { Autoconfig.discover(email) }.getOrNull()
            _state.update { current ->
                if (result == null) current.copy(discovering = false)
                else current.copy(
                    discovering = false,
                    imapHost = result.imap.host,
                    imapPort = result.imap.port.toString(),
                    imapSecurity = result.imap.security,
                    imapUsername = result.imap.username,
                    smtpHost = result.smtp.host,
                    smtpPort = result.smtp.port.toString(),
                    smtpSecurity = result.smtp.security,
                    smtpUsername = result.smtp.username,
                    displayName = current.displayName.ifBlank {
                        result.displayName ?: email.substringBefore('@')
                    },
                    testResult = if (result.source == "guess") {
                        "No autoconfig published — guessed mail.${email.substringAfter('@')}. Check the fields."
                    } else "Settings found via ${result.source.substringAfter("//").substringBefore('/')}"
                )
            }
        }
    }

    fun test() {
        _state.update { it.copy(testing = true, testResult = null, error = null) }
        viewModelScope.launch {
            val current = _state.value
            val imapPassword = current.imapPassword.ifEmpty {
                container.accountRepository.imapPassword(accountId).orEmpty()
            }
            val smtpPassword = effectiveSmtpPassword(current).ifEmpty {
                container.accountRepository.smtpPassword(accountId).orEmpty()
            }
            val result = container.accountRepository.testConnection(
                toEntity(current), imapPassword, smtpPassword
            )
            _state.update {
                it.copy(
                    testing = false,
                    testResult = result.getOrNull(),
                    error = result.exceptionOrNull()?.message
                )
            }
        }
    }

    fun save() {
        val current = _state.value
        _state.update { it.copy(error = null) }
        viewModelScope.launch {
            runCatching {
                val id = container.accountRepository.save(
                    toEntity(current),
                    current.imapPassword.takeIf { it.isNotEmpty() },
                    effectiveSmtpPassword(current).takeIf { it.isNotEmpty() }
                )
                // First sync pulls the folder list so the drawer is not empty.
                runCatching { container.syncManager.refreshFolders(id) }
                de.uwumail.sync.SyncScheduler.syncNow(container.appContext, id)
            }.onSuccess {
                _state.update { it.copy(saved = true) }
            }.onFailure { e ->
                _state.update { it.copy(error = e.message ?: e.toString()) }
            }
        }
    }

    fun addIdentity(displayName: String, email: String) {
        viewModelScope.launch {
            container.accountRepository.saveIdentity(
                IdentityEntity(accountId = accountId, displayName = displayName, email = email)
            )
            _state.update { it.copy(identities = container.db.identityDao().forAccount(accountId)) }
        }
    }

    fun deleteIdentity(identity: IdentityEntity) {
        viewModelScope.launch {
            container.accountRepository.deleteIdentity(identity)
            _state.update { it.copy(identities = container.db.identityDao().forAccount(accountId)) }
        }
    }

    private fun effectiveSmtpPassword(state: AccountSetupState) =
        if (state.samePassword) state.imapPassword else state.smtpPassword

    private fun toEntity(state: AccountSetupState) = AccountEntity(
        id = state.accountId,
        displayName = state.displayName.ifBlank { state.email },
        email = state.email.trim(),
        color = accountColor(state),
        imapHost = state.imapHost.trim(),
        imapPort = state.imapPort.toIntOrNull() ?: 993,
        imapSecurity = state.imapSecurity.name,
        imapUsername = state.imapUsername.ifBlank { state.email }.trim(),
        smtpHost = state.smtpHost.trim(),
        smtpPort = state.smtpPort.toIntOrNull() ?: 587,
        smtpSecurity = state.smtpSecurity.name,
        smtpUsername = state.smtpUsername.ifBlank { state.email }.trim(),
        trustAllCerts = state.trustAllCerts,
        useIdentityAsEnvelopeSender = state.useIdentityAsEnvelopeSender,
        syncEnabled = state.syncEnabled,
        syncIntervalMinutes = state.syncIntervalMinutes.toIntOrNull() ?: 15,
        pushEnabled = state.pushEnabled,
        notificationsEnabled = state.notificationsEnabled,
        signature = state.signature.takeIf { it.isNotBlank() }
    )

    private fun accountColor(state: AccountSetupState): Int {
        // Stable per address so the avatar colour survives edits.
        val seed = state.email.hashCode()
        val random = Random(seed)
        val hue = random.nextInt(360).toFloat()
        return android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.45f, 0.75f))
    }
}
