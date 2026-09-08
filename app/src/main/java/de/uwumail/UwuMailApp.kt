package de.uwumail

import android.app.Application
import de.uwumail.core.SyncLog
import de.uwumail.core.WizardLog
import de.uwumail.di.AppContainer
import de.uwumail.sync.PushService
import de.uwumail.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.activation.CommandMap
import javax.activation.MailcapCommandMap

class UwuMailApp : Application() {

    lateinit var container: AppContainer
        private set

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        registerMailHandlers()
        WizardLog.attach(this)
        SyncLog.attach(this)
        SyncLog.write("app started — ${SyncLog.device()}")
        container = AppContainer(this)

        appScope.launch(Dispatchers.IO) {
            // A removal hides its rows before it is attempted, so anything the
            // last run left hidden has no one to finish it and must come back.
            runCatching { container.syncManager.releaseAbandonedRemovals() }
            runCatching { container.blocklistRepository.seedIfEmpty() }
            // Mail cached before conversations existed has no thread yet; the
            // headers it needs are stored, but as JSON the migration could not
            // read. Finds nothing on every start after the first.
            runCatching { container.syncManager.backfillThreadIds() }
            val accounts = container.db.accountDao().getAll()
            container.notifier.ensureChannels(accounts)
            SyncScheduler.scheduleAll(this@UwuMailApp, accounts)
            PushService.ensureRunning(this@UwuMailApp, accounts.any { it.pushEnabled })
        }
    }

    /**
     * Android ships a stub JavaBeans Activation Framework, so JavaMail cannot find
     * its own content handlers unless we register them explicitly. Without this,
     * multipart messages fail to parse at runtime.
     */
    private fun registerMailHandlers() {
        val map = CommandMap.getDefaultCommandMap() as? MailcapCommandMap ?: MailcapCommandMap()
        map.addMailcap("text/html;; x-java-content-handler=com.sun.mail.handlers.text_html")
        map.addMailcap("text/xml;; x-java-content-handler=com.sun.mail.handlers.text_xml")
        map.addMailcap("text/plain;; x-java-content-handler=com.sun.mail.handlers.text_plain")
        map.addMailcap("multipart/*;; x-java-content-handler=com.sun.mail.handlers.multipart_mixed")
        map.addMailcap("message/rfc822;; x-java-content-handler=com.sun.mail.handlers.message_rfc822")
        CommandMap.setDefaultCommandMap(map)
    }
}
