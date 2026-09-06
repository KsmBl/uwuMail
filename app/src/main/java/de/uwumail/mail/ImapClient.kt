package de.uwumail.mail

import de.uwumail.core.Security
import de.uwumail.data.db.AccountEntity
import de.uwumail.mail.oauth.AuthType
import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.IMAPStore
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.util.Properties
import javax.mail.FetchProfile
import javax.mail.Flags
import javax.mail.search.FlagTerm
import javax.mail.search.FromStringTerm
import javax.mail.search.MessageIDTerm
import javax.mail.search.OrTerm
import javax.mail.search.RecipientStringTerm
import javax.mail.search.SubjectTerm
import javax.mail.Folder
import javax.mail.Message
import javax.mail.MessageRemovedException
import javax.mail.Session
import javax.mail.UIDFolder
import javax.mail.internet.MimeMessage

/**
 * Thin blocking wrapper over JavaMail's IMAP store.
 *
 * Every method assumes it is called off the main thread; [ImapPool] owns the
 * lifetime and serialises access per account, since a single IMAP connection
 * cannot service concurrent commands.
 */
class ImapClient(
    private val account: AccountEntity,
    /** Password, or an OAuth access token when the account uses OAUTH2. */
    private val secret: String,
    /**
     * Socket read timeout. IDLE parks on a read, so a connection used for push
     * needs one longer than the server's idle limit — otherwise it tears down
     * and reconnects every [DEFAULT_READ_TIMEOUT_MILLIS].
     */
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS
) : Closeable {

    private var store: IMAPStore? = null
    private val openFolders = LinkedHashMap<String, IMAPFolder>()

    val isConnected: Boolean get() = store?.isConnected == true

    private val isOAuth: Boolean
        get() = runCatching { AuthType.valueOf(account.authType) }
            .getOrDefault(AuthType.PASSWORD) == AuthType.OAUTH2

    fun connect(): IMAPStore {
        store?.takeIf { it.isConnected }?.let { return it }
        val security = runCatching { Security.valueOf(account.imapSecurity) }.getOrDefault(Security.SSL_TLS)
        val protocol = if (security == Security.SSL_TLS) "imaps" else "imap"
        val props = Properties().apply {
            put("mail.store.protocol", protocol)
            put("mail.$protocol.host", account.imapHost)
            put("mail.$protocol.port", account.imapPort.toString())
            put("mail.$protocol.connectiontimeout", "20000")
            put("mail.$protocol.timeout", readTimeoutMillis.toString())
            put("mail.$protocol.writetimeout", "40000")
            // Keep \Seen untouched while syncing; the user decides what is read.
            put("mail.$protocol.peek", "true")
            put("mail.$protocol.partialfetch", "false")
            put("mail.$protocol.fetchsize", "262144")
            put("mail.mime.decodetext.strict", "false")
            put("mail.mime.base64.ignoreerrors", "true")
            when (security) {
                Security.SSL_TLS -> put("mail.imaps.ssl.enable", "true")
                Security.STARTTLS -> {
                    put("mail.imap.starttls.enable", "true")
                    put("mail.imap.starttls.required", "true")
                }
                Security.NONE -> Unit
            }
            if (account.trustAllCerts) put("mail.$protocol.ssl.trust", "*")
            if (isOAuth) {
                // The access token is passed where the password normally goes;
                // forcing the mechanism stops JavaMail trying PLAIN first.
                put("mail.$protocol.auth.mechanisms", "XOAUTH2")
                put("mail.$protocol.auth.login.disable", "true")
                put("mail.$protocol.auth.plain.disable", "true")
            }
        }
        val session = Session.getInstance(props)
        val newStore = session.getStore(protocol) as IMAPStore
        try {
            newStore.connect(account.imapHost, account.imapPort, account.imapUsername, secret)
        } catch (e: Exception) {
            throw MailException(
                "IMAP connect to ${account.imapHost}:${account.imapPort} failed: ${describe(e)}",
                e
            )
        }
        store = newStore
        return newStore
    }

    // ---------------------------------------------------------------- folders

    fun listFolders(): List<RemoteFolder> {
        val s = connect()
        val root = s.defaultFolder
        val all = runCatching { root.list("*") }.getOrElse { emptyArray() }
        val subscribed = runCatching { root.listSubscribed("*").map { it.fullName }.toSet() }
            .getOrDefault(emptySet())
        return all.mapNotNull { folder ->
            val attrs = (folder as? IMAPFolder)?.let {
                runCatching { it.attributes.orEmpty().toList() }.getOrDefault(emptyList())
            }.orEmpty()
            if (attrs.any { it.equals("\\NonExistent", true) }) return@mapNotNull null
            val selectable = attrs.none { it.equals("\\Noselect", true) }
            RemoteFolder(
                path = folder.fullName,
                displayName = folder.name,
                delimiter = runCatching { folder.separator.toString() }.getOrDefault("/"),
                selectable = selectable,
                subscribed = folder.fullName in subscribed,
                type = FolderClassifier.guess(
                    folder.fullName,
                    runCatching { folder.separator }.getOrDefault('/'),
                    attrs
                )
            )
        }.sortedBy { it.path }
    }

    fun createFolder(path: String) {
        val folder = connect().getFolder(path)
        if (folder.exists()) throw MailException("Folder \"$path\" already exists")
        if (!folder.create(Folder.HOLDS_MESSAGES or Folder.HOLDS_FOLDERS)) {
            throw MailException("Server refused to create \"$path\"")
        }
        runCatching { folder.isSubscribed = true }
    }

    fun deleteFolder(path: String) {
        val folder = connect().getFolder(path)
        if (!folder.exists()) return
        closeCached(path)
        if (folder.isOpen) folder.close(false)
        runCatching { folder.isSubscribed = false }
        if (!folder.delete(true)) throw MailException("Server refused to delete \"$path\"")
    }

    fun renameFolder(from: String, to: String) {
        val source = connect().getFolder(from)
        if (!source.exists()) throw MailException("Folder \"$from\" does not exist")
        closeCached(from)
        if (source.isOpen) source.close(false)
        val target = connect().getFolder(to)
        if (!source.renameTo(target)) throw MailException("Server refused to rename \"$from\"")
        runCatching { target.isSubscribed = true }
    }

    fun status(path: String): FolderStatus {
        val folder = open(path, Folder.READ_ONLY)
        return FolderStatus(
            uidValidity = folder.uidValidity,
            uidNext = runCatching { folder.uidNext }.getOrDefault(0L),
            total = folder.messageCount,
            unread = runCatching { folder.unreadMessageCount }.getOrDefault(0)
        )
    }

    // --------------------------------------------------------------- messages

    /**
     * Fetches envelopes for UIDs greater than [afterUid], newest [limit] first.
     * Returns an empty list when the folder has nothing newer.
     */
    fun fetchNewer(path: String, afterUid: Long, limit: Int): List<FetchedMessage> {
        val folder = open(path, Folder.READ_ONLY)
        val uidNext = runCatching { folder.uidNext }.getOrDefault(0L)
        if (uidNext > 0 && afterUid >= uidNext - 1) return emptyList()
        val start = if (afterUid <= 0) 1L else afterUid + 1
        val messages = runCatching {
            folder.getMessagesByUID(start, UIDFolder.LASTUID)
        }.getOrElse { emptyArray() }.filterNotNull()
        val window = if (messages.size > limit) messages.takeLast(limit) else messages
        if (window.isEmpty()) return emptyList()
        fetchEnvelopes(folder, window.toTypedArray())
        return window.mapNotNull { toFetched(folder, it) }
            .filter { it.uid > afterUid }
    }

    /** Fetches the [limit] envelopes immediately older than [beforeUid], for paging back. */
    fun fetchOlder(path: String, beforeUid: Long, limit: Int): List<FetchedMessage> {
        val folder = open(path, Folder.READ_ONLY)
        if (beforeUid <= 1) return emptyList()
        val start = (beforeUid - limit).coerceAtLeast(1L)
        val messages = runCatching {
            folder.getMessagesByUID(start, beforeUid - 1)
        }.getOrElse { emptyArray() }.filterNotNull()
        if (messages.isEmpty()) return emptyList()
        fetchEnvelopes(folder, messages.toTypedArray())
        return messages.mapNotNull { toFetched(folder, it) }
    }

    /** Returns the UIDs the server currently holds, so vanished ones can be pruned locally. */
    fun listUids(path: String, sinceUid: Long): Set<Long> {
        val folder = open(path, Folder.READ_ONLY)
        val messages = runCatching {
            folder.getMessagesByUID(sinceUid.coerceAtLeast(1L), UIDFolder.LASTUID)
        }.getOrElse { emptyArray() }.filterNotNull()
        return messages.mapNotNull { runCatching { folder.getUID(it) }.getOrNull() }.toSet()
    }

    /** Reads current flags for a set of UIDs so local state can follow the server. */
    fun fetchFlags(path: String, uids: List<Long>): Map<Long, FlagState> {
        if (uids.isEmpty()) return emptyMap()
        val folder = open(path, Folder.READ_ONLY)
        val messages = runCatching { folder.messagesFor(uids).toList() }
            .getOrElse { emptyList() }
        val fp = FetchProfile().apply {
            add(FetchProfile.Item.FLAGS)
            add(UIDFolder.FetchProfileItem.UID)
        }
        runCatching { folder.fetch(messages.toTypedArray(), fp) }
        return messages.mapNotNull { msg ->
            val uid = runCatching { folder.getUID(msg) }.getOrNull() ?: return@mapNotNull null
            val flags = runCatching { msg.flags }.getOrNull() ?: return@mapNotNull null
            uid to FlagState(
                seen = flags.contains(Flags.Flag.SEEN),
                flagged = flags.contains(Flags.Flag.FLAGGED),
                answered = flags.contains(Flags.Flag.ANSWERED),
                deleted = flags.contains(Flags.Flag.DELETED)
            )
        }.toMap()
    }

    fun fetchBody(path: String, uid: Long): FetchedBody? {
        val folder = open(path, Folder.READ_ONLY)
        val message = folder.messageFor(uid) ?: return null
        return ignoringRemoved { MimeUtil.extractBody(message) }
    }

    fun fetchRaw(path: String, uid: Long): ByteArray? {
        val folder = open(path, Folder.READ_ONLY)
        val message = folder.messageFor(uid) as? MimeMessage ?: return null
        return ignoringRemoved {
            val out = ByteArrayOutputStream()
            message.writeTo(out)
            out.toByteArray()
        }
    }

    fun fetchAttachment(path: String, uid: Long, partId: String): ByteArray? {
        val folder = open(path, Folder.READ_ONLY)
        val message = folder.messageFor(uid) ?: return null
        return ignoringRemoved {
            val part = findPart(message, partId) ?: return null
            val out = ByteArrayOutputStream()
            part.inputStream.use { it.copyTo(out) }
            out.toByteArray()
        }
    }

    private fun findPart(part: javax.mail.Part, wanted: String, current: String = "1"): javax.mail.Part? {
        if (current == wanted) return part
        val content = runCatching { part.content }.getOrNull()
        if (content is javax.mail.Multipart) {
            for (i in 0 until content.count) {
                findPart(content.getBodyPart(i), wanted, "$current.${i + 1}")?.let { return it }
            }
        }
        return null
    }

    /**
     * Asks the server to search a folder, which reaches mail that was never
     * downloaded — the local search can only ever see what has been synced.
     *
     * Subject, sender and recipients are searched but not the body: a full-text
     * search over every message is expensive on the server and slow on a phone,
     * and it is not what someone typing a name is usually after.
     */
    fun search(path: String, query: String, limit: Int): List<FetchedMessage> {
        if (query.isBlank()) return emptyList()
        val folder = open(path, Folder.READ_ONLY)
        val term = OrTerm(
            arrayOf(
                SubjectTerm(query),
                FromStringTerm(query),
                RecipientStringTerm(Message.RecipientType.TO, query)
            )
        )
        val found = ignoringRemoved { folder.search(term) } ?: return emptyList()
        // Newest first, and only as many as anyone will read.
        val newest = found.takeLast(limit).reversed().toTypedArray()
        if (newest.isEmpty()) return emptyList()
        fetchEnvelopes(folder, newest)
        return newest.mapNotNull { toFetched(folder, it) }
    }

    fun setFlags(path: String, uids: List<Long>, flag: Flags.Flag, value: Boolean) {
        if (uids.isEmpty()) return
        val folder = open(path, Folder.READ_WRITE)
        val messages = folder.messagesFor(uids)
        if (messages.isEmpty()) return
        ignoringRemoved { folder.setFlags(messages, Flags(flag), value) }
    }

    /**
     * Copies then removes, which every IMAP server supports. `MOVE` (RFC 6851) is
     * used implicitly by the server when it advertises it; we do not depend on it.
     */
    fun moveMessages(fromPath: String, uids: List<Long>, toPath: String) {
        if (uids.isEmpty() || fromPath == toPath) return
        val source = open(fromPath, Folder.READ_WRITE)
        val target = connect().getFolder(toPath)
        if (!target.exists()) throw MailException("Target folder \"$toPath\" does not exist")
        val messages = source.messagesFor(uids)
        if (messages.isEmpty()) return
        // The copy must succeed and is allowed to throw: swallowing it here
        // would report a move that never put the mail anywhere. Only the
        // removal afterwards may find the message already gone, which is a
        // request to remove it that has been satisfied.
        source.copyMessages(messages, target)
        ignoringRemoved {
            source.setFlags(messages, Flags(Flags.Flag.DELETED), true)
            expunge(source, messages)
        }
    }

    fun copyMessages(fromPath: String, uids: List<Long>, toPath: String) {
        if (uids.isEmpty()) return
        val source = open(fromPath, Folder.READ_ONLY)
        val target = connect().getFolder(toPath)
        if (!target.exists()) throw MailException("Target folder \"$toPath\" does not exist")
        val messages = source.messagesFor(uids)
        if (messages.isEmpty()) return
        // Allowed to throw: a copy that quietly did nothing is worse than one
        // that says so.
        source.copyMessages(messages, target)
    }

    /**
     * Whether [path] holds a message with this Message-ID.
     *
     * Used to check that a move or a copy actually arrived. IMAP reports
     * success for a COPY the server may still have refused for its own
     * reasons — quota, permissions, a folder that cannot hold messages — and
     * mail that silently went nowhere is the worst outcome of the lot.
     */
    fun containsMessageId(path: String, messageId: String): Boolean {
        if (messageId.isBlank()) return false
        val folder = runCatching { open(path, Folder.READ_ONLY) }.getOrNull() ?: return false
        return runCatching { folder.search(MessageIDTerm(messageId)).isNotEmpty() }
            .getOrDefault(false)
    }

    fun deleteMessages(path: String, uids: List<Long>) {
        if (uids.isEmpty()) return
        val folder = open(path, Folder.READ_WRITE)
        val messages = folder.messagesFor(uids)
        if (messages.isEmpty()) return
        ignoringRemoved {
            folder.setFlags(messages, Flags(Flags.Flag.DELETED), true)
            expunge(folder, messages)
        }
    }

    fun append(path: String, raw: ByteArray, seen: Boolean, draft: Boolean = false) {
        val folder = connect().getFolder(path)
        if (!folder.exists()) throw MailException("Folder \"$path\" does not exist")
        if (!folder.isOpen) folder.open(Folder.READ_WRITE)
        val session = Session.getInstance(Properties())
        val message = MimeMessage(session, raw.inputStream())
        if (seen) message.setFlag(Flags.Flag.SEEN, true)
        // \Draft is what tells every other client this is unfinished.
        if (draft) message.setFlag(Flags.Flag.DRAFT, true)
        folder.appendMessages(arrayOf(message))
        folder.close(false)
    }

    /** Blocks until the server reports activity on [path] or the connection drops. */
    fun idle(path: String) {
        val folder = open(path, Folder.READ_ONLY)
        folder.idle(true)
    }

    companion object {
        /**
         * Whether a blanket `EXPUNGE` would remove only what was asked for.
         *
         * True when every `\Deleted` message in the mailbox is one of ours.
         * Anything else flagged belongs to another client, and expunging is
         * how it stops existing.
         */
        fun blanketExpungeIsSafe(ours: Set<Long>, flagged: Set<Long>): Boolean =
            flagged.all { it in ours }

        const val DEFAULT_READ_TIMEOUT_MILLIS = 40_000

        /**
         * RFC 2177 tells clients to re-issue IDLE at least every 29 minutes, and
         * servers drop it around then anyway. Timing out just under that keeps a
         * half-open socket from parking forever without churning the connection.
         */
        const val IDLE_READ_TIMEOUT_MILLIS = 28 * 60 * 1000
    }

    // --------------------------------------------------------------- internals

    /**
     * Resolves UIDs to live messages.
     *
     * The server may hand back entries for messages that have already been
     * expunged — by another client, by a rule, or by an earlier action in this
     * session. Touching one throws [MessageRemovedException], so they are
     * dropped here rather than turning a successful delete into an error.
     */
    private fun IMAPFolder.messagesFor(uids: List<Long>): Array<Message> =
        runCatching { getMessagesByUID(uids.toLongArray()) }
            .getOrElse { emptyArray() }
            .filterNotNull()
            .filter { runCatching { !it.isExpunged }.getOrDefault(false) }
            .toTypedArray()

    private fun IMAPFolder.messageFor(uid: Long): Message? =
        runCatching { getMessageByUID(uid) }.getOrNull()
            ?.takeIf { runCatching { !it.isExpunged }.getOrDefault(false) }

    /**
     * A message that is already gone satisfies any request to remove it, so
     * "removed" is a successful outcome here, not a failure to report.
     */
    private inline fun <T> ignoringRemoved(block: () -> T): T? = try {
        block()
    } catch (e: MessageRemovedException) {
        null
    }

    /**
     * Removes exactly the messages asked for.
     *
     * UID EXPUNGE (RFC 4315) does that on its own. Where the server does not
     * offer it the only other command is a blanket `EXPUNGE`, which takes every
     * `\Deleted` message in the mailbox with it — including mail another client
     * has flagged and not finished with. So the blanket form is used only once
     * it has been established that ours are the only ones flagged, and when
     * somebody else's are in there too the removal is refused rather than
     * quietly destroying their mail.
     */
    private fun expunge(folder: IMAPFolder, messages: Array<Message>) {
        if (runCatching { folder.expunge(messages) }.isSuccess) return

        val ours = messages.mapNotNullTo(HashSet()) {
            runCatching { folder.getUID(it) }.getOrNull()
        }
        val flagged = runCatching {
            folder.search(FlagTerm(Flags(Flags.Flag.DELETED), true))
                .mapNotNullTo(HashSet()) { runCatching { folder.getUID(it) }.getOrNull() }
        }.getOrElse {
            // A server that cannot be asked is not one to guess about.
            throw MailException(
                "${folder.fullName} does not support UID EXPUNGE and could not be checked; " +
                    "the message has been left on the server"
            )
        }

        if (!blanketExpungeIsSafe(ours, flagged)) {
            throw MailException(
                "${folder.fullName} does not support UID EXPUNGE and holds mail another " +
                    "client has marked deleted; removing this would have taken that with it"
            )
        }
        folder.expunge()
    }

    private fun fetchEnvelopes(folder: IMAPFolder, messages: Array<Message>) {
        val fp = FetchProfile().apply {
            add(FetchProfile.Item.ENVELOPE)
            add(FetchProfile.Item.FLAGS)
            add(FetchProfile.Item.CONTENT_INFO)
            add(UIDFolder.FetchProfileItem.UID)
            add(IMAPFolder.FetchProfileItem.SIZE)
            add(IMAPFolder.FetchProfileItem.HEADERS)
        }
        runCatching { folder.fetch(messages, fp) }
    }

    private fun toFetched(folder: IMAPFolder, message: Message): FetchedMessage? = runCatching {
        val uid = folder.getUID(message)
        val flags = message.flags
        val headers = MimeUtil.collectHeaders(message)
        val contentType = runCatching { message.contentType?.lowercase().orEmpty() }.getOrDefault("")
        FetchedMessage(
            uid = uid,
            messageIdHeader = headers["message-id"]?.firstOrNull(),
            subject = MimeUtil.decode(runCatching { message.subject }.getOrNull()),
            fromName = MimeUtil.personalOf(runCatching { message.from }.getOrNull()),
            fromAddress = MimeUtil.addressOf(runCatching { message.from }.getOrNull()),
            to = MimeUtil.addressList(runCatching { message.getRecipients(Message.RecipientType.TO) }.getOrNull()),
            cc = MimeUtil.addressList(runCatching { message.getRecipients(Message.RecipientType.CC) }.getOrNull()),
            bcc = MimeUtil.addressList(runCatching { message.getRecipients(Message.RecipientType.BCC) }.getOrNull()),
            replyTo = MimeUtil.addressOf(runCatching { message.replyTo }.getOrNull()),
            sentAt = runCatching { message.sentDate?.time }.getOrNull() ?: 0L,
            receivedAt = runCatching { message.receivedDate?.time }.getOrNull()
                ?: runCatching { message.sentDate?.time }.getOrNull() ?: System.currentTimeMillis(),
            seen = flags.contains(Flags.Flag.SEEN),
            flagged = flags.contains(Flags.Flag.FLAGGED),
            answered = flags.contains(Flags.Flag.ANSWERED),
            draft = flags.contains(Flags.Flag.DRAFT),
            sizeBytes = runCatching { message.size.toLong() }.getOrDefault(0L).coerceAtLeast(0L),
            likelyHasAttachments = contentType.startsWith("multipart/mixed") ||
                contentType.startsWith("multipart/related"),
            headers = headers
        )
    }.getOrNull()

    private fun open(path: String, mode: Int): IMAPFolder {
        val cached = openFolders[path]
        if (cached != null && cached.isOpen && (cached.mode == mode || mode == Folder.READ_ONLY)) {
            return cached
        }
        cached?.let { runCatching { if (it.isOpen) it.close(false) } }
        val folder = connect().getFolder(path) as? IMAPFolder
            ?: throw MailException("Folder \"$path\" is not an IMAP folder")
        if (!folder.exists()) throw MailException("Folder \"$path\" does not exist")
        folder.open(mode)
        openFolders[path] = folder
        return folder
    }

    private fun closeCached(path: String) {
        openFolders.remove(path)?.let { runCatching { if (it.isOpen) it.close(false) } }
    }

    override fun close() {
        openFolders.values.forEach { runCatching { if (it.isOpen) it.close(false) } }
        openFolders.clear()
        runCatching { store?.close() }
        store = null
    }

    /** Turns the provider's own wording into something a person can act on. */
    private fun describe(e: Exception): String {
        val message = e.message.orEmpty()
        return when {
            message.contains("Application-specific password required", ignoreCase = true) ->
                "Google rejected the password. Use \"Sign in with Google\" instead, or create " +
                    "an App Password at myaccount.google.com/apppasswords."
            message.contains("Invalid credentials", ignoreCase = true) && isOAuth ->
                "Google rejected the access token. Open the account and sign in again."
            message.contains("AUTHENTICATIONFAILED", ignoreCase = true) ->
                "Server rejected the credentials: $message"
            else -> message.ifBlank { e.toString() }
        }
    }


}

data class FlagState(
    val seen: Boolean,
    val flagged: Boolean,
    val answered: Boolean,
    val deleted: Boolean
)
