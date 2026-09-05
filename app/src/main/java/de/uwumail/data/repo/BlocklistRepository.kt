package de.uwumail.data.repo

import de.uwumail.data.db.BlocklistDao
import de.uwumail.data.db.BlocklistEntity
import de.uwumail.data.db.BlocklistEntryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Spam blocklists: sender domains that should be flagged in the message list.
 *
 * Lists are plain text, one domain per line, which is the format every public
 * list below publishes. Nothing is downloaded until the user turns a list on.
 */
class BlocklistRepository(private val dao: BlocklistDao) {

    fun observeAll() = dao.observeAll()
    fun observeEntries(listId: Long) = dao.observeEntries(listId)

    suspend fun seedIfEmpty() {
        if (dao.count() > 0) return
        BUILT_IN.forEachIndexed { index, definition ->
            dao.insert(
                BlocklistEntity(
                    name = definition.name,
                    description = definition.description,
                    url = definition.url,
                    builtIn = true,
                    position = index
                )
            )
        }
        dao.insert(
            BlocklistEntity(
                name = "Blocked senders",
                description = "Domains and addresses you add yourself",
                url = null,
                enabled = true,
                builtIn = true,
                position = BUILT_IN.size
            )
        )
    }

    /** Turning a list on downloads it if it has never been fetched. */
    suspend fun setEnabled(listId: Long, enabled: Boolean) {
        val list = dao.get(listId) ?: return
        dao.update(list.copy(enabled = enabled))
        if (enabled && list.url != null && list.entryCount == 0) refresh(listId)
    }

    suspend fun refresh(listId: Long): Result<Int> {
        val list = dao.get(listId) ?: return Result.failure(IllegalStateException("Unknown list"))
        val url = list.url ?: return Result.success(list.entryCount)
        return runCatching {
            val patterns = withContext(Dispatchers.IO) { download(url) }
            if (patterns.isEmpty()) error("The list came back empty")
            dao.replaceEntries(listId, patterns)
            val count = dao.entryCount(listId)
            dao.update(
                list.copy(
                    entryCount = count,
                    updatedAt = System.currentTimeMillis(),
                    lastError = null
                )
            )
            count
        }.onFailure { error ->
            dao.update(list.copy(lastError = error.message ?: error.toString()))
        }
    }

    suspend fun addCustomList(name: String, url: String): Long {
        val id = dao.insert(
            BlocklistEntity(
                name = name.ifBlank { url.substringAfter("//").substringBefore('/') },
                description = url,
                url = url,
                enabled = true,
                builtIn = false,
                position = dao.count()
            )
        )
        refresh(id)
        return id
    }

    suspend fun deleteList(listId: Long) = dao.delete(listId)

    // ------------------------------------------------------- manual entries

    suspend fun blockSender(raw: String) {
        val pattern = normalise(raw) ?: return
        val list = dao.manualList() ?: return
        dao.insertEntries(listOf(BlocklistEntryEntity(listId = list.id, pattern = pattern)))
        dao.update(list.copy(entryCount = dao.entryCount(list.id), enabled = true))
    }

    suspend fun unblockSender(pattern: String) {
        val list = dao.manualList() ?: return
        dao.deleteEntry(list.id, pattern)
        dao.update(list.copy(entryCount = dao.entryCount(list.id)))
    }

    suspend fun manualListId(): Long? = dao.manualList()?.id

    suspend fun isBlocked(address: String?): Boolean {
        val normalised = address?.lowercase()?.trim().orEmpty()
        if (normalised.isEmpty()) return false
        return dao.isBlocked(normalised.substringAfterLast('@'), normalised)
    }

    // ------------------------------------------------------------ internals

    private fun download(url: String): List<String> {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 60000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "uwuMail")
        }
        return try {
            if (connection.responseCode != 200) {
                error("Server returned HTTP ${connection.responseCode}")
            }
            connection.inputStream.bufferedReader().useLines { lines ->
                lines.mapNotNull(::normalise).distinct().toList()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun normalise(raw: String) = BlocklistParser.normalise(raw)

    private data class Definition(val name: String, val description: String, val url: String)

    private companion object {
        val BUILT_IN = listOf(
            Definition(
                "Disposable email domains",
                "Throwaway and temporary mailbox providers",
                "https://raw.githubusercontent.com/disposable-email-domains/" +
                    "disposable-email-domains/master/disposable_email_blocklist.conf"
            ),
            Definition(
                "StopForumSpam toxic domains",
                "Domains seen sending spam and forum abuse (large list)",
                "https://www.stopforumspam.com/downloads/toxic_domains_whole.txt"
            ),
            Definition(
                "FakeFilter",
                "Fake and temporary mail providers",
                "https://raw.githubusercontent.com/7c/fakefilter/main/txt/data.txt"
            )
        )
    }
}


/**
 * Turns a line of a published blocklist into a matchable pattern.
 *
 * Public lists are not uniform: some are bare domains, some are hosts files,
 * some carry wildcards or trailing comments. Everything that is not a usable
 * domain or address is dropped rather than stored as a pattern that can never
 * match — or worse, one that matches everything.
 */
object BlocklistParser {

    private val WHITESPACE = Regex("""\s+""")
    private val HOSTS_PREFIXES = setOf("0.0.0.0", "127.0.0.1", "::1", "::")

    fun normalise(raw: String): String? {
        var line = raw.trim()
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("//") || line.startsWith("!")) {
            return null
        }
        line = line.substringBefore('#').trim()
        if (line.isEmpty()) return null

        val parts = line.split(WHITESPACE)
        line = if (parts.size >= 2 && parts[0] in HOSTS_PREFIXES) parts[1] else parts.first()

        line = line.removePrefix("*.").removePrefix(".").lowercase().trimEnd('.')

        return when {
            line.isEmpty() -> null
            line == "localhost" -> null
            // A bare label such as "com" would match far too much.
            !line.contains('.') -> null
            line.any(Char::isWhitespace) -> null
            // Raw IPs are not sender domains.
            line.all { it.isDigit() || it == '.' } -> null
            else -> line
        }
    }

    fun parse(lines: Sequence<String>): List<String> =
        lines.mapNotNull(::normalise).distinct().toList()
}
