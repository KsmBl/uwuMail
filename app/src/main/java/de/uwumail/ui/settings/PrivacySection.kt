package de.uwumail.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Unsubscribe
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import de.uwumail.R
import de.uwumail.ui.LocalAppContainer

/** Everything governing what a message body is allowed to do when it is opened. */
@Composable
fun PrivacySection() {
    val container = LocalAppContainer.current
    val settings by container.settings.state.collectAsState()

    SettingSwitch(
        title = stringResource(R.string.set_unsub),
        subtitle = stringResource(R.string.set_unsub_sub),
        icon = Icons.Default.Unsubscribe,
        checked = settings.unsubscribeBanner,
        onChange = { value -> container.settings.update { it.copy(unsubscribeBanner = value) } }
    )

    SettingSwitch(
        title = stringResource(R.string.set_tracking),
        subtitle = stringResource(R.string.set_tracking_sub),
        icon = Icons.Default.Link,
        checked = settings.askStripTracking,
        onChange = { value -> container.settings.update { it.copy(askStripTracking = value) } }
    )

    SettingSwitch(
        title = stringResource(R.string.set_preload),
        subtitle = stringResource(R.string.set_preload_sub),
        icon = Icons.Default.DownloadForOffline,
        checked = settings.preloadUnread,
        onChange = { value -> container.settings.update { it.copy(preloadUnread = value) } }
    )
}
