package com.rdp.sync.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.rdp.sync.viewmodel.DeviceViewModel
import com.rdp.sync.ui.screens.DeviceDetailScreen
import com.rdp.sync.ui.screens.DeviceEditScreen
import com.rdp.sync.ui.screens.DeviceListScreen
import com.rdp.sync.ui.screens.RdpConnectionScreen

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val viewModel: DeviceViewModel = viewModel()

    NavHost(
        navController = navController,
        startDestination = "device_list"
    ) {
        composable("device_list") {
            DeviceListScreen(
                devices = viewModel.uiState.value.devices,
                onAddDevice = {
                    navController.navigate("device_edit")
                },
                onDeviceClick = { deviceId ->
                    navController.navigate("device_detail/$deviceId")
                },
                onSync = {
                    viewModel.syncWebdav()
                }
            )
        }

        composable("device_edit") {
            DeviceEditScreen(
                device = null,
                onSave = { device ->
                    viewModel.addDevice(
                        name = device.name,
                        host = device.host,
                        port = device.port,
                        username = device.username,
                        password = device.password,
                        domain = device.domain
                    )
                    navController.popBackStack()
                },
                onCancel = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = "device_edit/{deviceId}",
            arguments = listOf(navArgument("deviceId") { defaultValue = 0L })
        ) { backStackEntry ->
            val deviceId = backStackEntry.arguments?.getLong("deviceId") ?: 0L
            val device = viewModel.uiState.value.devices.find { it.id == deviceId }
            if (device != null) {
                DeviceEditScreen(
                    device = device,
                    onSave = { updatedDevice ->
                        viewModel.updateDevice(updatedDevice)
                        navController.popBackStack()
                    },
                    onCancel = {
                        navController.popBackStack()
                    }
                )
            } else {
                navController.popBackStack()
            }
        }

        composable(
            route = "device_detail/{deviceId}",
            arguments = listOf(navArgument("deviceId") { defaultValue = 0L })
        ) { backStackEntry ->
            val deviceId = backStackEntry.arguments?.getLong("deviceId") ?: 0L
            val device = viewModel.uiState.value.devices.find { it.id == deviceId }
            if (device != null) {
                DeviceDetailScreen(
                    device = device,
                    onBack = { navController.popBackStack() },
                    onEdit = {
                        navController.navigate("device_edit/${device.id}")
                    },
                    onConnect = {
                        navController.navigate("rdp_connection/${device.id}")
                    },
                    onDelete = {
                        viewModel.deleteDevice(device)
                        navController.popBackStack()
                    }
                )
            } else {
                navController.popBackStack()
            }
        }

        composable(
            route = "rdp_connection/{deviceId}",
            arguments = listOf(navArgument("deviceId") { defaultValue = 0L })
        ) { backStackEntry ->
            val deviceId = backStackEntry.arguments?.getLong("deviceId") ?: 0L
            val device = viewModel.uiState.value.devices.find { it.id == deviceId }
            if (device != null) {
                RdpConnectionScreen(
                    device = device,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
