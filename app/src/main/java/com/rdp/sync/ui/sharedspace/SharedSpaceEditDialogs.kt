package com.rdp.sync.ui.sharedspace

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rdp.sync.R
import com.rdp.sync.sharedspace.SharedSpaceNames
import com.rdp.sync.ui.components.ProductNotice

@Composable
internal fun SharedSpaceNameDialog(originalName: String?, parentPath: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember(originalName, parentPath) { mutableStateOf(originalName.orEmpty()) }
    val valid = name.isNotBlank() && SharedSpaceNames.nativeKey(name) != null && name != originalName
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(if (originalName == null) R.string.space_new_folder else R.string.space_rename)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (parentPath.isEmpty()) stringResource(R.string.space_title) else parentPath, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(name, { if (it.length <= 240) name = it }, label = { Text(stringResource(R.string.space_item_name)) }, singleLine = true,
                isError = name.isNotEmpty() && SharedSpaceNames.nativeKey(name) == null, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.space_name_help), style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { if (valid) onConfirm(name) }, enabled = valid, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) } })
}

@Composable
internal fun SharedSpaceDeleteDialog(relativePath: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(Icons.Default.DeleteOutline, null) },
        title = { Text(stringResource(R.string.space_delete_title)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(relativePath, style = MaterialTheme.typography.titleMedium)
                ProductNotice(stringResource(R.string.space_delete_warning), error = true)
            }
        }, confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) } })
}
