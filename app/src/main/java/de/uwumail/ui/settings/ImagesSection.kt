package de.uwumail.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.PhotoSizeSelectSmall
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.uwumail.R
import de.uwumail.ui.LocalAppContainer

/** Remote content in a message body: images, their minimum size, and scripts. */
@Composable
fun ImagesSection() {
    val container = LocalAppContainer.current
    val settings by container.settings.state.collectAsState()

    SettingSwitch(
        title = stringResource(R.string.set_block_images),
        subtitle = stringResource(R.string.set_block_images_sub),
        icon = Icons.Default.HideImage,
        checked = settings.blockRemoteImages,
        onChange = { value -> container.settings.update { it.copy(blockRemoteImages = value) } }
    )

    SettingSwitch(
        title = stringResource(R.string.set_tiny),
        subtitle = stringResource(R.string.set_tiny_sub),
        icon = Icons.Default.PhotoSizeSelectSmall,
        checked = settings.filterTinyImages,
        onChange = { value -> container.settings.update { it.copy(filterTinyImages = value) } }
    )

    if (settings.filterTinyImages) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
        ) {
            Text(stringResource(R.string.set_min_size), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            PixelField(
                value = settings.minImageWidth,
                label = stringResource(R.string.set_width),
                onChange = { value -> container.settings.update { it.copy(minImageWidth = value) } }
            )
            Text(" × ", modifier = Modifier.padding(horizontal = 4.dp))
            PixelField(
                value = settings.minImageHeight,
                label = stringResource(R.string.set_height),
                onChange = { value -> container.settings.update { it.copy(minImageHeight = value) } }
            )
            Text(" px", modifier = Modifier.padding(start = 4.dp))
        }
    }

    SettingSwitch(
        title = stringResource(R.string.set_js),
        subtitle = stringResource(R.string.set_js_sub),
        icon = Icons.Default.Code,
        checked = settings.allowJavaScript,
        onChange = { value -> container.settings.update { it.copy(allowJavaScript = value) } }
    )
}

@Composable
private fun PixelField(value: Int, label: String, onChange: (Int) -> Unit) {
    Column {
        OutlinedTextField(
            value = value.toString(),
            onValueChange = { text ->
                // An empty or silly value would disable the filter by accident.
                text.filter(Char::isDigit).take(4).toIntOrNull()
                    ?.coerceIn(1, 2000)
                    ?.let(onChange)
            },
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done
            ),
            modifier = Modifier.width(96.dp)
        )
    }
}
