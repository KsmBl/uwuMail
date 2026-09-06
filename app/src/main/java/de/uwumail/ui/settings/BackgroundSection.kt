package de.uwumail.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.uwumail.R
import de.uwumail.sync.PushService
import de.uwumail.ui.LocalAppContainer

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

    Column {
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    when {
                        pushAccounts == 0 -> stringResource(R.string.bg_push_off)
                        serviceRunning -> "Watching $pushAccounts account" +
                            (if (pushAccounts == 1) "" else "s") + " for new mail"
                        else -> stringResource(R.string.bg_push_stopped)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.bg_explains),
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

        Text(
            stringResource(R.string.bg_vendors),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
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
