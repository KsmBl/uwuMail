package de.uwumail.mail

import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeUtility

object MimeUtil {

    fun decode(text: String?): String = when {
        text.isNullOrEmpty() -> ""
        else -> runCatching { MimeUtility.decodeText(text) }.getOrDefault(text)
    }

    fun addressList(addresses: Array<javax.mail.Address>?): List<String> =
        addresses.orEmpty().mapNotNull { addr ->
            (addr as? InternetAddress)?.address ?: addr.toString()
        }

    fun personalOf(addresses: Array<javax.mail.Address>?): String? =
        (addresses?.firstOrNull() as? InternetAddress)?.personal?.let { decode(it) }

    fun addressOf(addresses: Array<javax.mail.Address>?): String? =
        (addresses?.firstOrNull() as? InternetAddress)?.address

    /**
     * Walks a MIME tree collecting the best text/plain and text/html bodies plus
     * every attachment part. Part ids follow the IMAP convention (`1`, `1.2`, ...).
     */
    fun extractBody(message: Message): FetchedBody {
        val acc = Accumulator()
        runCatching { walk(message, acc, "1") }
        return FetchedBody(
            plain = acc.plain.takeIf { it.isNotBlank() },
            html = acc.html.takeIf { it.isNotBlank() },
            attachments = acc.attachments
        )
    }

    private class Accumulator {
        var plain: String = ""
        var html: String = ""
        val attachments = mutableListOf<FetchedPart>()
    }

    private fun walk(part: Part, acc: Accumulator, partId: String) {
        val contentType = runCatching { part.contentType?.lowercase() ?: "" }.getOrDefault("")
        val disposition = runCatching { part.disposition }.getOrNull()
        val rawFileName = runCatching { part.fileName }.getOrNull()

        val content = runCatching { part.content }.getOrNull()

        if (content is Multipart) {
            for (i in 0 until content.count) {
                walk(content.getBodyPart(i), acc, "$partId.${i + 1}")
            }
            return
        }

        val isAttachment = Part.ATTACHMENT.equals(disposition, ignoreCase = true) ||
            (rawFileName != null && !contentType.startsWith("text/"))

        if (isAttachment || (rawFileName != null && Part.INLINE.equals(disposition, true))) {
            val name = decode(rawFileName) .ifBlank { "part-$partId" }
            acc.attachments += FetchedPart(
                partId = partId,
                fileName = name,
                mimeType = contentType.substringBefore(';').trim(),
                sizeBytes = runCatching { part.size.toLong() }.getOrDefault(-1L).coerceAtLeast(-1L),
                isInline = Part.INLINE.equals(disposition, ignoreCase = true),
                contentId = runCatching { (part as? javax.mail.internet.MimePart)?.getHeader("Content-ID")?.firstOrNull() }.getOrNull()
            )
            return
        }

        when {
            contentType.startsWith("text/plain") && acc.plain.isEmpty() ->
                acc.plain = contentAsString(content, part)
            contentType.startsWith("text/html") && acc.html.isEmpty() ->
                acc.html = contentAsString(content, part)
        }
    }

    private fun contentAsString(content: Any?, part: Part): String = when (content) {
        is String -> content
        else -> runCatching {
            val out = ByteArrayOutputStream()
            part.inputStream.use { it.copyTo(out) }
            out.toString(Charsets.UTF_8.name())
        }.getOrDefault("")
    }

    fun htmlToText(html: String): String = runCatching {
        Jsoup.parse(html).let { doc ->
            doc.select("script, style, head").remove()
            doc.body()?.wholeText() ?: doc.wholeText()
        }
    }.getOrDefault(html)

    /** Short single-line snippet shown under the subject in the message list. */
    fun preview(plain: String?, html: String?, limit: Int = 200): String {
        val source = plain?.takeIf { it.isNotBlank() } ?: html?.let { htmlToText(it) } ?: ""
        return source.replace(Regex("""\s+"""), " ").trim().take(limit)
    }

    fun collectHeaders(message: Message): Map<String, List<String>> = runCatching {
        val result = LinkedHashMap<String, MutableList<String>>()
        val headers = message.allHeaders
        while (headers.hasMoreElements()) {
            val h = headers.nextElement()
            result.getOrPut(h.name.lowercase()) { mutableListOf() } += decode(h.value)
        }
        result.mapValues { it.value.toList() }
    }.getOrDefault(emptyMap())

    fun sanitizeFileName(name: String): String =
        name.replace(Regex("""[^A-Za-z0-9._\- ]"""), "_").take(120).ifBlank { "attachment" }
}
