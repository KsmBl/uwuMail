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
import androidx.compose.ui.unit.dp
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
                        pushAccounts == 0 -> "Push is off for every account"
                        serviceRunning -> "Watching $pushAccounts account" +
                            (if (pushAccounts == 1) "" else "s") + " for new mail"
                        else -> "Push is enabled but not running"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "uwuMail holds an IMAP connection open so mail arrives without " +
                        "opening the app. Turn push on or off per account under Accounts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    if (exempt) "Battery optimisation is off for uwuMail"
                    else "Battery optimisation is limiting uwuMail",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (exempt) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
                Text(
                    if (exempt) {
                        "Android will leave the connection alone while the screen is off."
                    } else {
                        "Doze will suspend the connection while the screen is off, so mail " +
                            "may not arrive until you open the app. Granting the exemption " +
                            "is what makes background mail reliable."
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
                                .onFailure { onMessage("Could not open battery settings") }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Allow background activity") }
                }
            }
        }

        Text(
            "Samsung, Xiaomi, OnePlus and others add their own battery managers on " +
                "top of Android's. If mail still stops arriving overnight, look for " +
                "uwuMail in your phone's app battery settings and set it to " +
                "unrestricted there too.",
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
