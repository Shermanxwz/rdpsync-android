package com.rdp.sync.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.rdp.sync.data.Device
import com.rdp.sync.ui.screens.DeviceDetailScreen
import com.rdp.sync.ui.screens.DeviceEditScreen
import com.rdp.sync.ui.screens.DeviceListScreen
import com.rdp.sync.ui.screens.RdpConnectionScreen

sealed class Screen(val route: String) {
    object DeviceList : Screen("device_list")
    object DeviceAdd : Screen("device_add")
    object DeviceEdit : Screen("device_edit/{deviceId}") {
        fun createRoute(deviceId: Long) = "device_edit/$deviceId"
    }
    object DeviceDetail : Screen("device_detail/{deviceId}") {
        fun createRoute(deviceId: Long) = "device_detail/$deviceId"
    }
    object RdpConnection : Screen("rdp_connection")
}

@Composable
fun AppNavigation(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    onDeviceClick: (Long) -> Unit = {},
    onAddDevice: () -> Unit = {},
    onEditDevice: (Long) -> Unit = {},
    onRdpConnected: () -> Unit = {}
) {
    NavHost(
        navController = navController,
        startDestination = Screen.DeviceList.route,
        modifier = modifier
    ) {
        composable(Screen.DeviceList.route) {
            DeviceListScreen(
                onAddDevice = onAddDevice,
                onDeviceClick = { deviceId ->
                    navController.navigate(Screen.DeviceDetail.createRoute(deviceId))
                },
                onSyncClick = {
                    // Trigger sync
                }
            )
        }

        composable(Screen.DeviceAdd.route) {
            DeviceEditScreen(
                device = null,
                onSave = { device ->
                    // Handled by parent
                },
                onCancel = {
                    navController.popBackStack()
                }
            )
        }

        composable(Screen.DeviceEdit.route) { backStackEntry ->
            val deviceId = backStackEntry.arguments?.getString("deviceId")?.toLongOrNull()
            DeviceEditScreen(
                device = deviceId?.let { /* fetch device */ null },
                onSave = { device ->
                    navController.popBackStack()
                },
                onCancel = {
                    navController.popBackStack()
                }
            )
        }

        composable(Screen.DeviceDetail.route) { backStackEntry ->
            val deviceId = backStackEntry.arguments?.getString("deviceId")?.toLongOrNull()
            DeviceDetailScreen(
                deviceId = deviceId ?: return@composable,
                onBack = { navController.popBackStack() },
                onEdit = { deviceId?.let { onEditDevice(it) } },
                onConnect = {
                    navController.navigate(Screen.RdpConnection.route)
                }
            )
        }

        composable(Screen.RdpConnection.route) {
            RdpConnectionScreen(
                onBack = onRdpConnected
            )
        }
    }
}
