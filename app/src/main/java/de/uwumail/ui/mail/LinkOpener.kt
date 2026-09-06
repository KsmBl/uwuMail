package de.uwumail.ui.mail

import de.uwumail.R
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * Hands a link from a mail body off to the system.
 *
 * Mail is untrusted, so links always leave the app rather than loading inside
 * the message WebView. `mailto:` comes back to uwuMail's own composer through
 * the SENDTO intent filter.
 */
fun openLink(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure { Toast.makeText(context, context.getString(R.string.error_no_app_for_link), Toast.LENGTH_SHORT)
            .show() }
}
