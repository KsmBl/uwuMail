package de.uwumail

import android.app.Application
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
        container = AppContainer(this)

        appScope.launch(Dispatchers.IO) {
            runCatching { container.blocklistRepository.seedIfEmpty() }
            val accounts = container.db.accountDao().getAll()
            container.notifier.ensureChannels(accounts)
            SyncScheduler.scheduleAll(this@UwuMailApp, accounts)
            if (accounts.any { it.pushEnabled }) PushService.start(this@UwuMailApp)
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
