package de.uwumail.core

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Puts a file where the user can actually find it.
 *
 * The app's own storage is private, so a "download" that lands there is not one
 * — it cannot be opened from a file manager or attached to anything else.
 * MediaStore's Downloads collection needs no permission and is shared, which is
 * what "downloaded" is supposed to mean.
 */
object DeviceDownloads {

    /** Everything uwuMail saves goes in one place rather than loose in Downloads. */
    private const val FOLDER = "uwuMail"

    /**
     * Copies [source] into the device's Downloads folder and returns the name
     * it was given, or null if it could not be written.
     *
     * MediaStore renames rather than overwrites when a name is taken, so saving
     * the same attachment twice leaves both copies instead of destroying one.
     */
    fun save(context: Context, source: File, displayName: String, mimeType: String): String? {
        if (!source.exists()) return null
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType.ifBlank { "application/octet-stream" })
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + File.separator + FOLDER
            )
            // Hidden from other apps until the bytes are all there.
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val uri = runCatching {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return null

        val written = runCatching {
            resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                ?: throw IllegalStateException("no output stream for $uri")
        }.isSuccess

        if (!written) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }

        runCatching {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null
            )
        }
        return runCatching { nameOf(context, uri) }.getOrNull() ?: displayName
    }

    /** Where the files end up, for telling the user. */
    val folderLabel: String get() = "Downloads/$FOLDER"

    private fun nameOf(context: Context, uri: android.net.Uri): String? =
        context.contentResolver.query(
            uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
}
