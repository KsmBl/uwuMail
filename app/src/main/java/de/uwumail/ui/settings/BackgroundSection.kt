package de.uwumail.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.uwumail.R
import de.uwumail.core.SyncLog
import de.uwumail.data.settings.AppSettings
import de.uwumail.sync.PushService
import de.uwumail.ui.LocalAppContainer
import kotlinx.coroutines.delay

/**
 * Explains and repairs the reasons background mail stops arriving.
 *
 * IMAP has no push service to hand off to, so the app has to hold the
 * connection itself; Doze and manufacturer battery managers are what break
 * that, and neither can be fixed from code alone.
 */
@Composable
fun BackgroundSection(onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val container = LocalAppContainer.current
    val accounts by container.db.accountDao().observeAll().collectAsState(initial = emptyList())

    // Bumped when we come back from a system screen, to re-read the exemption.
    var probe by remember { mutableIntStateOf(0) }
    val exempt = remember(probe) { isIgnoringBatteryOptimisations(context) }

    val cannotOpenBattery = stringResource(R.string.bg_battery_cannot_open)
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { probe++ }

    val pushAccounts = accounts.count { it.pushEnabled }
    val serviceRunning = remember(probe, accounts) { PushService.running }

    // Re-read as the clock moves: the answer changes at the edge of the window
    // whether or not anything on this screen was touched, and a card that said
    // "watching" until it was reopened would be wrong at exactly the moment
    // somebody had come to look at it.
    val settings by container.settings.state.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(WINDOW_RECHECK_MILLIS)
            now = System.currentTimeMillis()
        }
    }
    val paused = !settings.syncAllowedAt(now)
    val resumesAt = AppSettings.formatTime(settings.syncStartMinutes)

    Column {
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    when {
                        pushAccounts == 0 -> stringResource(R.string.bg_push_off)
                        // Asked before the running check on purpose: the service
                        // is up while it waits out the window, and reporting that
                        // as watching is how a phone that had been told not to
                        // check said it was checking.
                        paused -> stringResource(R.string.bg_push_paused, resumesAt)
                        serviceRunning -> pluralStringResource(
                            R.plurals.bg_push_watching, pushAccounts, pushAccounts
                        )
                        else -> stringResource(R.string.bg_push_stopped)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (paused && pushAccounts > 0) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    if (paused && pushAccounts > 0) stringResource(R.string.bg_push_paused_why)
                    else stringResource(R.string.bg_explains),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    if (exempt) stringResource(R.string.bg_battery_off)
                    else stringResource(R.string.bg_battery_on),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (exempt) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
                Text(
                    if (exempt) {
                        stringResource(R.string.bg_exempt)
                    } else {
                        stringResource(R.string.bg_not_exempt)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )
                if (!exempt) {
                    FilledTonalButton(
                        onClick = {
                            val intent = batteryExemptionIntent(context)
                            runCatching { launcher.launch(intent) }
                                .onFailure { onMessage(cannotOpenBattery) }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.bg_allow)) }
                }
            }
        }

        SyncLogCard()

        Text(
            stringResource(R.string.bg_vendors),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

/**
 * What fetching mail actually did, and the way to send it to somebody.
 *
 * Mail that does not arrive is the one fault that leaves nothing behind to
 * look at: the screen shows what it showed before, and on a phone I cannot
 * reach there is nothing to go on. The trace answers the questions that
 * matter — whether the check ran at all, which folders it looked at, how much
 * the server offered above the last message we hold, and what it said when it
 * refused.
 */
@Composable
private fun SyncLogCard() {
    val context = LocalContext.current
    val lines by SyncLog.lines.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    val shareTitle = stringResource(R.string.bg_log_share)

    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.bg_log_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                if (lines.isEmpty()) stringResource(R.string.bg_log_empty)
                else stringResource(R.string.bg_log_lines, lines.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            if (expanded) {
                Text(
                    // Newest first: the reason mail did not arrive is the last
                    // thing that happened, not the first.
                    lines.asReversed().take(LOG_PREVIEW_LINES).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    SyncLog.path(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            Row(Modifier.padding(top = 8.dp)) {
                TextButton(onClick = { expanded = !expanded }, enabled = lines.isNotEmpty()) {
                    Text(
                        if (expanded) stringResource(R.string.bg_log_hide)
                        else stringResource(R.string.bg_log_show)
                    )
                }
                TextButton(
                    onClick = { shareSyncLog(context, shareTitle) },
                    enabled = lines.isNotEmpty()
                ) { Text(stringResource(R.string.bg_log_share)) }
            }
        }
    }
}

/** How much of the trace is put on screen; the share carries all of it. */
private const val LOG_PREVIEW_LINES = 40

/** How often the card re-asks whether checking is allowed right now. */
private const val WINDOW_RECHECK_MILLIS = 30_000L

private fun shareSyncLog(context: Context, title: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "uwuMail sync log")
        putExtra(Intent.EXTRA_TEXT, SyncLog.text())
    }
    runCatching { context.startActivity(Intent.createChooser(intent, title)) }
}

private fun isIgnoringBatteryOptimisations(context: Context): Boolean = runCatching {
    context.getSystemService(PowerManager::class.java)
        ?.isIgnoringBatteryOptimizations(context.packageName) == true
}.getOrDefault(false)

/**
 * The direct dialog when the permission is held, otherwise the system list —
 * some builds refuse the direct request.
 */
private fun batteryExemptionIntent(context: Context): Intent = runCatching {
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${context.packageName}")
    }.takeIf { it.resolveActivity(context.packageManager) != null }
}.getOrNull() ?: Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
