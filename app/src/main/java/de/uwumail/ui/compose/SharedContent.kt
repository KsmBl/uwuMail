package de.uwumail.ui.compose

import android.content.Intent
import android.net.Uri

/**
 * What another app handed over to be mailed.
 *
 * A share arrives as an intent with a subject, some text and any number of
 * content URIs, none of which fits down a navigation argument — so it is left
 * here for the composer to collect when it opens.
 */
data class SharedContent(
    val subject: String = "",
    val text: String = "",
    val to: String = "",
    val cc: String = "",
    val bcc: String = "",
    val attachments: List<Uri> = emptyList()
) {
    val isEmpty: Boolean
        get() = subject.isBlank() && text.isBlank() && to.isBlank() &&
            cc.isBlank() && bcc.isBlank() && attachments.isEmpty()

    companion object {
        /**
         * Reads a share intent, whether it carries one file or several.
         *
         * `EXTRA_EMAIL` and friends are documented as string arrays but are
         * routinely sent as a single string, so both shapes are accepted.
         */
        fun of(intent: Intent): SharedContent {
            val streams = when (intent.action) {
                Intent.ACTION_SEND ->
                    listOfNotNull(intent.parcelable<Uri>(Intent.EXTRA_STREAM))
                Intent.ACTION_SEND_MULTIPLE ->
                    intent.parcelableList<Uri>(Intent.EXTRA_STREAM)
                else -> emptyList()
            }
            return SharedContent(
                subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty(),
                text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty(),
                to = intent.addresses(Intent.EXTRA_EMAIL),
                cc = intent.addresses(Intent.EXTRA_CC),
                bcc = intent.addresses(Intent.EXTRA_BCC),
                attachments = streams
            )
        }

        private fun Intent.addresses(key: String): String =
            getStringArrayExtra(key)?.joinToString(", ")
                ?: getStringExtra(key).orEmpty()

        @Suppress("DEPRECATION")
        private inline fun <reified T : android.os.Parcelable> Intent.parcelable(key: String): T? =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                getParcelableExtra(key, T::class.java)
            } else {
                getParcelableExtra(key) as? T
            }

        @Suppress("DEPRECATION")
        private inline fun <reified T : android.os.Parcelable> Intent.parcelableList(
            key: String
        ): List<T> =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                getParcelableArrayListExtra(key, T::class.java).orEmpty()
            } else {
                getParcelableArrayListExtra<T>(key).orEmpty()
            }
    }
}

/**
 * Holds a share until the composer is on screen to take it.
 *
 * Taken rather than read: a share is acted on once, and a rotation that
 * replayed it would attach everything a second time.
 */
class SharedContentBus {

    @Volatile
    private var pending: SharedContent? = null

    fun offer(content: SharedContent) {
        pending = content
    }

    fun take(): SharedContent? = pending.also { pending = null }
}
