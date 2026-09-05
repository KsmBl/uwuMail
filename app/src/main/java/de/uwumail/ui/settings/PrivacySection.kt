package de.uwumail.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Unsubscribe
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import de.uwumail.ui.LocalAppContainer

/** Everything governing what a message body is allowed to do when it is opened. */
@Composable
fun PrivacySection() {
    val container = LocalAppContainer.current
    val settings by container.settings.state.collectAsState()

    SettingSwitch(
        title = "Unsubscribe banner",
        subtitle = "Offer a one-tap unsubscribe when a message advertises one",
        icon = Icons.Default.Unsubscribe,
        checked = settings.unsubscribeBanner,
        onChange = { value -> container.settings.update { it.copy(unsubscribeBanner = value) } }
    )
}
