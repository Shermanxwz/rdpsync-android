package com.rdp.sync.ui.sharedspace

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rdp.sync.R
import com.rdp.sync.sharedspace.SharedSpaceListing
import com.rdp.sync.sharedspace.SharedSpaceItem
import com.rdp.sync.transfer.TransferProgress
import com.rdp.sync.ui.components.*

internal data class SharedSpaceUiState(
    val path: String = "",
    val listing: SharedSpaceListing? = null,
    val loading: Boolean = true,
    val working: Boolean = false,
    val cancellable: Boolean = false,
    val pickerPending: Boolean = false,
    val progress: TransferProgress? = null,
    val error: String? = null,
    val notice: String? = null
)

/** Persistent workspace browser. It never implies that a source phone folder is live-shared. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SharedSpaceScreen(state: SharedSpaceUiState, onBack: () -> Unit, onRefresh: () -> Unit,
    onDirectory: (String) -> Unit, onImport: () -> Unit, onExport: (String) -> Unit, onCancel: () -> Unit,
    onCreateFolder: () -> Unit = {}, onRename: (SharedSpaceItem) -> Unit = {}, onDelete: (SharedSpaceItem) -> Unit = {}) {
    val context = LocalContext.current
    var menuFor by remember { mutableStateOf<String?>(null) }
    val interactive = !state.working && !state.pickerPending
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.space_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            actions = { IconButton(onClick = onRefresh, enabled = interactive && !state.loading) { Icon(Icons.Default.Refresh, stringResource(R.string.space_refresh)) } })
    }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 840.dp).fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = MaterialTheme.shapes.large) {
                        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Default.FolderShared, null, modifier = Modifier.size(32.dp))
                            Text(stringResource(R.string.space_headline), style = MaterialTheme.typography.titleLarge)
                            Text(stringResource(R.string.space_persistence), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                item { Text(stringResource(R.string.space_import_help), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item {
                    Button(onClick = onImport, enabled = interactive, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.space_import))
                    }
                }
                item {
                    OutlinedButton(onClick = onCreateFolder, enabled = interactive, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Default.CreateNewFolder, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.space_new_folder))
                    }
                }
                state.error?.let { message -> item { ProductNotice(message, error = true) } }
                state.notice?.let { message -> item { ProductNotice(message) } }
                if (state.pickerPending) item { ProductNotice(stringResource(R.string.space_picker_wait)) }
                if (state.working) item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val progress = state.progress
                        val total = progress?.totalBytes
                        if (total != null && total > 0) LinearProgressIndicator(progress = { (progress.bytes.toDouble() / total).coerceIn(0.0, 1.0).toFloat() }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(progress?.fileName.orEmpty().ifBlank { stringResource(R.string.space_working) }, style = MaterialTheme.typography.bodyMedium)
                        if (state.cancellable) TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) }
                    }
                }
                state.listing?.let { listing ->
                    item {
                        SectionHeading(stringResource(R.string.space_capacity), stringResource(R.string.space_capacity_value,
                            Formatter.formatShortFileSize(context, listing.usedBytes), Formatter.formatShortFileSize(context, listing.quotaBytes)))
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { if (listing.quotaBytes > 0) (listing.usedBytes.toDouble() / listing.quotaBytes).coerceIn(0.0, 1.0).toFloat() else 0f }, modifier = Modifier.fillMaxWidth())
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.path.isNotEmpty()) IconButton(onClick = { onDirectory(state.path.substringBeforeLast('/', "")) }, enabled = interactive) {
                            Icon(Icons.Default.ArrowUpward, stringResource(R.string.space_up))
                        }
                        Text(if (state.path.isEmpty()) stringResource(R.string.space_files) else state.path,
                            style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    }
                }
                if (state.loading) item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp); Text(stringResource(R.string.space_loading))
                    }
                }
                if (!state.loading && state.listing?.items?.isEmpty() == true) item {
                    ProductNotice(stringResource(R.string.space_empty))
                }
                items(state.listing?.items.orEmpty(), key = { it.relativePath }) { item ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
                        val action = if (item.directory) Modifier.clickable(enabled = interactive) { onDirectory(item.relativePath) } else Modifier
                        Row(Modifier.fillMaxWidth().then(action).padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(if (item.directory) Icons.Default.Folder else Icons.Default.InsertDriveFile, null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(item.name, style = MaterialTheme.typography.bodyLarge)
                                Text(if (item.directory) stringResource(R.string.space_folder) else Formatter.formatShortFileSize(context, item.size),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Box {
                                IconButton(onClick = { menuFor = item.relativePath }, enabled = interactive) {
                                    Icon(Icons.Default.MoreVert, stringResource(R.string.space_file_actions, item.name))
                                }
                                DropdownMenu(expanded = menuFor == item.relativePath, onDismissRequest = { menuFor = null }) {
                                    if (!item.directory) DropdownMenuItem(text = { Text(stringResource(R.string.export_action)) },
                                        leadingIcon = { Icon(Icons.Default.SaveAlt, null) },
                                        onClick = { menuFor = null; onExport(item.relativePath) })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.space_rename)) }, leadingIcon = { Icon(Icons.Default.Edit, null) },
                                        enabled = !item.readOnly, onClick = { menuFor = null; onRename(item) })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null) },
                                        enabled = !item.readOnly, onClick = { menuFor = null; onDelete(item) })
                                }
                            }
                        }
                    }
                }
                item { Text(stringResource(R.string.space_export_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item { Text(stringResource(R.string.space_limits), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
