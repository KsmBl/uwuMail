package de.uwumail.di

import android.content.Context
import de.uwumail.data.crypto.CredentialStore
import de.uwumail.data.db.AppDatabase
import de.uwumail.data.repo.AccountRepository
import de.uwumail.data.repo.BackupRepository
import de.uwumail.data.repo.BlocklistRepository
import de.uwumail.data.settings.SettingsStore
import de.uwumail.mail.ImapPool
import de.uwumail.mail.SmtpSender
import de.uwumail.mail.oauth.OAuthClient
import de.uwumail.mail.oauth.OAuthConfig
import de.uwumail.mail.oauth.OAuthResultBus
import de.uwumail.mail.oauth.PendingAuthStore
import de.uwumail.mail.oauth.TokenStore
import de.uwumail.notify.Notifier
import de.uwumail.rules.RuleEngine
import de.uwumail.rules.RuleSuggester
import de.uwumail.sync.SyncManager
import de.uwumail.ui.mail.MessageOrder

/**
 * Hand-rolled dependency graph.
 *
 * The object count is small and every dependency is a plain constructor
 * argument, so a container beats pulling in an annotation processor here.
 */
class AppContainer(private val context: Context) {

    /** For the few call sites that need a Context (WorkManager, FileProvider). */
    val appContext: Context get() = context

    val db: AppDatabase by lazy { AppDatabase.build(context) }
    val credentials: CredentialStore by lazy { CredentialStore(context) }
    val settings: SettingsStore by lazy { SettingsStore(context) }

    /** What the list is showing, so the message screen can swipe along it. */
    val messageOrder: MessageOrder by lazy { MessageOrder() }
    val notifier: Notifier by lazy { Notifier(context) }

    val oauthClient: OAuthClient by lazy { OAuthClient() }
    val oauthConfig: OAuthConfig by lazy { OAuthConfig(context) }
    val pendingAuth: PendingAuthStore by lazy { PendingAuthStore(context) }
    val oauthResults: OAuthResultBus by lazy { OAuthResultBus() }

    val tokenStore: TokenStore by lazy {
        TokenStore(db.accountDao(), credentials, oauthClient, oauthConfig)
    }

    val imapPool: ImapPool by lazy { ImapPool(db.accountDao(), tokenStore) }
    val smtpSender: SmtpSender by lazy { SmtpSender() }
    val ruleEngine: RuleEngine by lazy { RuleEngine(db.ruleDao()) }
    val ruleSuggester: RuleSuggester by lazy { RuleSuggester() }

    val syncManager: SyncManager by lazy {
        SyncManager(context, db, imapPool, credentials, tokenStore, ruleEngine, notifier, smtpSender)
    }

    val blocklistRepository: BlocklistRepository by lazy { BlocklistRepository(db.blocklistDao()) }

    val backupRepository: BackupRepository by lazy { BackupRepository(db, settings) }

    val accountRepository: AccountRepository by lazy {
        AccountRepository(context, db, credentials, imapPool, tokenStore, notifier)
    }
}
