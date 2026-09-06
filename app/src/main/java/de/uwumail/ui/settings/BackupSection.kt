package de.uwumail.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import de.uwumail.R
import de.uwumail.core.DeviceDownloads
import de.uwumail.data.repo.BackupRepository
import de.uwumail.ui.LocalAppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Saving and restoring the rules, which exist nowhere but this phone.
 *
 * Accounts and passwords are deliberately left out: the credentials are sealed
 * to this device's keystore and could not be restored elsewhere anyway, and a
 * file of mail passwords is not a thing to leave lying in Downloads.
 */
@Composable
fun BackupSection(onMessage: (String) -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val exportFailed = stringResource(R.string.backup_export_failed)
    val importFailed = stringResource(R.string.backup_import_failed)
    val notABackup = stringResource(R.string.backup_not_a_backup)

    val restore = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val json = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().decodeToString()
                    } ?: error("could not read the file")
                }
                container.backupRepository.import(json)
            }.onSuccess { contents ->
                onMessage(
                    if (contents == null) notABackup
                    else context.getString(R.string.backup_restored, contents.rules)
                )
            }.onFailure { onMessage("$importFailed: ${it.message}") }
        }
    }

    ListItem(
        modifier = Modifier.clickable {
            scope.launch {
                runCatching {
                    val json = container.backupRepository.export()
                    withContext(Dispatchers.IO) {
                        val staged = File(context.cacheDir, BackupRepository.FILE_NAME)
                        staged.writeText(json)
                        DeviceDownloads.save(
                            context, staged, BackupRepository.FILE_NAME, "application/json"
                        ).also { staged.delete() }
                    }
                }.onSuccess { name ->
                    onMessage(
                        if (name == null) exportFailed
                        else context.getString(R.string.backup_saved, DeviceDownloads.folderLabel)
                    )
                }.onFailure { onMessage("$exportFailed: ${it.message}") }
            }
        },
        leadingContent = { Icon(Icons.Default.Save, null) },
        headlineContent = { Text(stringResource(R.string.backup_export)) },
        supportingContent = { Text(stringResource(R.string.backup_export_sub)) }
    )

    ListItem(
        modifier = Modifier.clickable {
            // Some file pickers do not offer application/json, so anything is
            // accepted and the contents decide.
            restore.launch(arrayOf("application/json", "text/plain", "*/*"))
        },
        leadingContent = { Icon(Icons.Default.Restore, null) },
        headlineContent = { Text(stringResource(R.string.backup_import)) },
        supportingContent = { Text(stringResource(R.string.backup_import_sub)) }
    )
}
