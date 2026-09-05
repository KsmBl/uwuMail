package de.uwumail.di

import android.content.Context
import de.uwumail.data.crypto.CredentialStore
import de.uwumail.data.db.AppDatabase
import de.uwumail.data.repo.AccountRepository
import de.uwumail.mail.ImapPool
import de.uwumail.mail.SmtpSender
import de.uwumail.notify.Notifier
import de.uwumail.rules.RuleEngine
import de.uwumail.rules.RuleSuggester
import de.uwumail.sync.SyncManager

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
    val notifier: Notifier by lazy { Notifier(context) }
    val imapPool: ImapPool by lazy { ImapPool(db.accountDao(), credentials) }
    val smtpSender: SmtpSender by lazy { SmtpSender() }
    val ruleEngine: RuleEngine by lazy { RuleEngine(db.ruleDao()) }
    val ruleSuggester: RuleSuggester by lazy { RuleSuggester() }

    val syncManager: SyncManager by lazy {
        SyncManager(context, db, imapPool, credentials, ruleEngine, notifier, smtpSender)
    }

    val accountRepository: AccountRepository by lazy {
        AccountRepository(context, db, credentials, imapPool, notifier)
    }
}
