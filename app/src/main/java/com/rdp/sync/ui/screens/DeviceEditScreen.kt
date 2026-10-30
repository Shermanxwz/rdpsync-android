package com.rdp.sync.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import com.rdp.sync.R
import com.rdp.sync.data.Device
import com.rdp.sync.ui.components.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceEditScreen(device: Device?, onSave: (Device) -> Unit, onCancel: () -> Unit,
    errorMessage: String? = null, isSaving: Boolean = false) {
    val context = LocalContext.current
    var name by rememberSaveable(device?.id) { mutableStateOf(device?.name.orEmpty()) }
    var host by rememberSaveable(device?.id) { mutableStateOf(device?.host.orEmpty()) }
    var portText by rememberSaveable(device?.id) { mutableStateOf((device?.port ?: 3389).toString()) }
    var username by rememberSaveable(device?.id) { mutableStateOf(device?.username.orEmpty()) }
    // Never put plaintext credentials in saved instance state.
    var password by remember(device?.id) { mutableStateOf(device?.password.orEmpty()) }
    var domain by rememberSaveable(device?.id) { mutableStateOf(device?.domain.orEmpty()) }
    var rdpServerName by rememberSaveable(device?.id) { mutableStateOf(device?.rdpServerName.orEmpty()) }
    var showPassword by remember { mutableStateOf(false) }
    var attemptedSave by remember { mutableStateOf(false) }
    val port = portText.toIntOrNull()
    val validPort = port != null && port in 1..65535
    val validRequired = name.isNotBlank() && host.isNotBlank()
    val canSave = !isSaving && (device?.credentialUnavailable != true || password.isNotEmpty())
    fun save() {
        attemptedSave = true
        if (!canSave || !validPort || !validRequired) return
        onSave(Device(id = device?.id ?: 0, name = name, host = host, port = requireNotNull(port),
            username = username, password = password, domain = domain, rdpServerName = rdpServerName,
            width = device?.width ?: 1280, height = device?.height ?: 720))
    }
    BackHandler(enabled = isSaving) { /* Keep the atomic credential write owned by this route. */ }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(if (device == null) R.string.add_device else R.string.edit_device)) },
            navigationIcon = { IconButton(onClick = onCancel, enabled = !isSaving) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } })
    }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (!errorMessage.isNullOrBlank()) ProductNotice(UiMessages.text(context, errorMessage), error = true)
                if (device?.credentialUnavailable == true) ProductNotice(stringResource(
                    if (device.passwordFormat == 2) R.string.credential_missing_help else R.string.credential_locked_help), error = true)
                SectionHeading(stringResource(R.string.connection_info), stringResource(R.string.connection_section_help))
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.connection_name)) },
                    leadingIcon = { Icon(Icons.Default.Computer, null) }, singleLine = true, enabled = !isSaving,
                    isError = attemptedSave && name.isBlank(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(host, { host = it }, label = { Text(stringResource(R.string.host)) },
                    singleLine = true, enabled = !isSaving, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    isError = attemptedSave && host.isBlank(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(portText, { portText = it }, label = { Text(stringResource(R.string.port)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, enabled = !isSaving,
                    isError = attemptedSave && !validPort, modifier = Modifier.fillMaxWidth())
                if (attemptedSave && !validRequired) ProductNotice(stringResource(R.string.validation_required), error = true)
                if (attemptedSave && !validPort) ProductNotice(stringResource(R.string.validation_port), error = true)
                Spacer(Modifier.height(4.dp))
                SectionHeading(stringResource(R.string.credentials_section))
                OutlinedTextField(username, { username = it }, label = { Text(stringResource(R.string.username)) },
                    leadingIcon = { Icon(Icons.Default.Person, null) }, singleLine = true, enabled = !isSaving, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.password)) },
                    leadingIcon = { Icon(Icons.Default.Lock, null) }, singleLine = true, enabled = !isSaving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            stringResource(if (showPassword) R.string.hide_password else R.string.show_password))
                    } }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.credentials_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SectionHeading(stringResource(R.string.advanced_section), stringResource(R.string.advanced_help))
                OutlinedTextField(domain, { domain = it }, label = { Text(stringResource(R.string.domain)) }, singleLine = true,
                    enabled = !isSaving, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(rdpServerName, { rdpServerName = it }, label = { Text(stringResource(R.string.server_name)) },
                    placeholder = { Text(stringResource(R.string.server_name_hint)) }, supportingText = { Text(stringResource(R.string.server_name_help)) },
                    singleLine = true, enabled = !isSaving, modifier = Modifier.fillMaxWidth())
                Button(onClick = ::save, enabled = canSave, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    if (isSaving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Check, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (isSaving) R.string.saving else R.string.save_connection))
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
