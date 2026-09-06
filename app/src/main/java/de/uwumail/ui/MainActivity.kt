package de.uwumail.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import de.uwumail.UwuMailApp
import de.uwumail.ui.theme.UwuMailTheme

class MainActivity : AppCompatActivity() {

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private var pendingMessageId by mutableStateOf<Long?>(null)
    private var pendingMailto by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        askForNotificationPermission()

        val container = (application as UwuMailApp).container

        setContent {
            // The theme is a setting, so it has to be read before it is applied
            // — the container is provided inside so everything else can reach it.
            val settings by container.settings.state.collectAsState()
            UwuMailTheme(theme = settings.theme) {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    // Land on the account setup flow until there is something to show.
                    val accountCount by produceState(initialValue = -1) {
                        value = container.db.accountDao().count()
                    }
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        if (accountCount >= 0) {
                            UwuMailNavHost(
                                startOnAccounts = accountCount == 0,
                                openMessageId = pendingMessageId,
                                mailtoUri = pendingMailto
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.getLongExtra("messageId", -1L).takeIf { it > 0 }?.let { pendingMessageId = it }
        if (intent.action == Intent.ACTION_SENDTO || intent.action == Intent.ACTION_VIEW) {
            val data = intent.data
            when {
                data == null -> Unit
                data.scheme == "mailto" -> pendingMailto = data.toString()
                // The OAuth redirect comes back on our own package-name scheme.
                data.scheme == packageName ->
                    (application as UwuMailApp).container.oauthResults.post(data)
            }
        }
    }

    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
