package com.rdp.sync.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.rdp.sync.network.SharedDriveMode
import com.rdp.sync.security.CertificateEndpoint
import com.rdp.sync.storage.PhoneStorageAccess
import com.rdp.sync.ui.screens.DeviceDetailScreen
import com.rdp.sync.ui.screens.DeviceEditScreen
import com.rdp.sync.ui.screens.DeviceListScreen
import com.rdp.sync.ui.screens.RdpConnectionScreen
import com.rdp.sync.viewmodel.DeviceViewModel

@Composable
fun AppNavigation() {
    val context = LocalContext.current
    val navController = rememberNavController()
    val viewModel: DeviceViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()
    // Deliberately not rememberSaveable/SavedStateHandle: process restoration cannot restore a drive grant.
    var sharedDriveGrant by remember { mutableStateOf<Pair<CertificateEndpoint, SharedDriveMode>?>(null) }

    NavHost(
        navController = navController,
        startDestination = "device_list"
    ) {
        composable("device_list") {
            DeviceListScreen(
                uiState = uiState,
                onAddDevice = { navController.navigate("device_edit") },
                onDeviceClick = { deviceId -> navController.navigate("device_detail/$deviceId") },
                onSync = { direction -> viewModel.syncWebdav(direction) },
                onSaveWebDavSettings = { url, username, password -> viewModel.saveWebDavSettings(url, username, password) },
                onTestWebDavSettings = { url, username, password -> viewModel.testWebDavSettings(url, username, password) },
                onMessageShown = { viewModel.clearTransientMessages() },
                onMoveDevice = { deviceId, delta -> viewModel.moveDevice(deviceId, delta) },
                onPinDeviceTop = { deviceId -> viewModel.pinDeviceToTop(deviceId) }
            )
        }

        composable("device_edit") {
            DeviceEditScreen(
                device = null,
                errorMessage = uiState.error,
                isSaving = uiState.isSaving,
                onSave = { device ->
                    viewModel.addDevice(
                        name = device.name,
                        host = device.host,
                        port = device.port,
                        username = device.username,
                        password = device.password,
                        domain = device.domain,
                        rdpServerName = device.rdpServerName,
                        width = device.width,
                        height = device.height,
                        onSaved = { navController.popBackStack() }
                    )
                },
                onCancel = { navController.popBackStack() }
            )
        }

        composable(
            route = "device_edit/{deviceId}",
            arguments = listOf(navArgument("deviceId") { defaultValue = 0L })
        ) { backStackEntry ->
            val deviceId = backStackEntry.arguments?.getLong("deviceId") ?: 0L
            val device = uiState.devices.find { it.id == deviceId }
            if (device != null) {
                DeviceEditScreen(
                    device = device,
                    errorMessage = uiState.error,
                    isSaving = uiState.isSaving,
                    onSave = { updatedDevice ->
                        viewModel.updateDevice(updatedDevice, onSaved = { navController.popBackStack() })
                    },
                    onCancel = { navController.popBackStack() }
                )
            }
        }

        composable(
            route = "device_detail/{deviceId}",
            arguments = listOf(navArgument("deviceId") { defaultValue = 0L })
        ) { backStackEntry ->
            val deviceId = backStackEntry.arguments?.getLong("deviceId") ?: 0L
            val device = uiState.devices.find { it.id == deviceId }
            if (device != null) {
                DeviceDetailScreen(
                    device = device,
                    onBack = { navController.popBackStack() },
                    onEdit = { navController.navigate("device_edit/${device.id}") },
                    onConnect = {
                        val endpoint = CertificateEndpoint.of(device.id, device.host, device.port, device.rdpServerName)
                        sharedDriveGrant = if (endpoint != null && PhoneStorageAccess.shouldMountOnConnect(context)) {
                            endpoint to SharedDriveMode.READ_WRITE
                        } else {
                            null
                        }
                        navController.navigate("rdp_connection/${device.id}")
                    },
                    onDelete = {
                        viewModel.deleteDevice(device)
                        navController.popBackStack()
                    }
                )
            }
        }

        composable(
            route = "rdp_connection/{deviceId}",
            arguments = listOf(navArgument("deviceId") { defaultValue = 0L })
        ) { backStackEntry ->
            val deviceId = backStackEntry.arguments?.getLong("deviceId") ?: 0L
            val device = uiState.devices.find { it.id == deviceId }
            if (device != null) {
                val endpoint = CertificateEndpoint.of(device.id, device.host, device.port, device.rdpServerName)
                val initialMode = sharedDriveGrant?.takeIf { it.first == endpoint }?.second
                    ?: if (endpoint != null && PhoneStorageAccess.shouldMountOnConnect(context)) {
                        SharedDriveMode.READ_WRITE
                    } else {
                        SharedDriveMode.OFF
                    }
                RdpConnectionScreen(
                    device = device,
                    initialSharedDriveMode = initialMode,
                    onBack = { sharedDriveGrant = null; navController.popBackStack() }
                )
            }
        }
    }
}
