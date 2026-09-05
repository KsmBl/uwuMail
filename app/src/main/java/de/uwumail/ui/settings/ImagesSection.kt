package de.uwumail.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import de.uwumail.ui.LocalAppContainer

/** Remote content in a message body: images and scripts. */
@Composable
fun ImagesSection() {
    val container = LocalAppContainer.current
    val settings by container.settings.state.collectAsState()

    SettingSwitch(
        title = "Block remote images",
        subtitle = "A banner in each message loads them on request. Loading them " +
            "tells the sender the mail was opened.",
        icon = Icons.Default.HideImage,
        checked = settings.blockRemoteImages,
        onChange = { value -> container.settings.update { it.copy(blockRemoteImages = value) } }
    )

    SettingSwitch(
        title = "Allow JavaScript in mail",
        subtitle = "Off is the safe setting: nothing a mail needs to be read requires scripts",
        icon = Icons.Default.Code,
        checked = settings.allowJavaScript,
        onChange = { value -> container.settings.update { it.copy(allowJavaScript = value) } }
    )
}
