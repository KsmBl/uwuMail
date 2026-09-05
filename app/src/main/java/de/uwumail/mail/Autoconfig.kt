package de.uwumail.mail

import de.uwumail.core.Security
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.net.HttpURLConnection
import java.net.URL

data class ServerGuess(
    val host: String,
    val port: Int,
    val security: Security,
    val username: String
)

data class AutoconfigResult(
    val imap: ServerGuess,
    val smtp: ServerGuess,
    val displayName: String?,
    val source: String
)

/**
 * Best-effort discovery of server settings for an address.
 *
 * Tries the provider's own `.well-known` autoconfig first, then Mozilla's ISPDB,
 * then falls back to the conventional `mail.<domain>` layout. Everything is a
 * suggestion: the setup screen keeps all fields editable.
 */
object Autoconfig {

    suspend fun discover(email: String): AutoconfigResult = withContext(Dispatchers.IO) {
        val domain = email.substringAfter('@').lowercase().trim()
        if (domain.isEmpty()) return@withContext fallback(email, domain)

        val urls = listOf(
            "https://autoconfig.$domain/mail/config-v1.1.xml?emailaddress=$email",
            "https://$domain/.well-known/autoconfig/mail/config-v1.1.xml",
            "https://autoconfig.thunderbird.net/v1.1/$domain"
        )
        for (url in urls) {
            val xml = runCatching { fetch(url) }.getOrNull() ?: continue
            val parsed = runCatching { parse(xml, email) }.getOrNull() ?: continue
            return@withContext parsed.copy(source = url)
        }
        fallback(email, domain)
    }

    private fun fetch(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "uwuMail")
        }
        return try {
            if (connection.responseCode != 200) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(xml: String, email: String): AutoconfigResult? {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(xml.reader())

        var imap: ServerGuess? = null
        var smtp: ServerGuess? = null
        var currentType: String? = null
        var host: String? = null
        var port: Int? = null
        var socket: String? = null
        var username: String? = null
        var displayName: String? = null
        var text = ""

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "incomingServer" || parser.name == "outgoingServer") {
                        currentType = parser.getAttributeValue(null, "type")
                        host = null; port = null; socket = null; username = null
                    }
                    text = ""
                }
                XmlPullParser.TEXT -> text = parser.text.orEmpty().trim()
                XmlPullParser.END_TAG -> when (parser.name) {
                    "hostname" -> host = text
                    "port" -> port = text.toIntOrNull()
                    "socketType" -> socket = text
                    "username" -> username = text
                    "displayName" -> if (displayName == null) displayName = text
                    "incomingServer" -> if (currentType == "imap" && host != null) {
                        imap = ServerGuess(
                            host!!, port ?: 993, socketSecurity(socket), resolveUser(username, email)
                        )
                    }
                    "outgoingServer" -> if (host != null) {
                        smtp = ServerGuess(
                            host!!, port ?: 587, socketSecurity(socket), resolveUser(username, email)
                        )
                    }
                }
            }
            event = parser.next()
        }
        if (imap == null || smtp == null) return null
        return AutoconfigResult(imap!!, smtp!!, displayName, "")
    }

    private fun resolveUser(template: String?, email: String): String = when (template) {
        null, "%EMAILADDRESS%" -> email
        "%EMAILLOCALPART%" -> email.substringBefore('@')
        else -> template
    }

    private fun socketSecurity(socket: String?): Security = when (socket?.uppercase()) {
        "SSL" -> Security.SSL_TLS
        "STARTTLS" -> Security.STARTTLS
        "PLAIN" -> Security.NONE
        else -> Security.SSL_TLS
    }

    private fun fallback(email: String, domain: String): AutoconfigResult {
        val host = if (domain.isEmpty()) "" else "mail.$domain"
        return AutoconfigResult(
            imap = ServerGuess(host, 993, Security.SSL_TLS, email),
            smtp = ServerGuess(host, 587, Security.STARTTLS, email),
            displayName = null,
            source = "guess"
        )
    }
}
