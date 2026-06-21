package com.rdp.sync.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.rdp.sync.ui.screens.DeviceDetailScreen
import com.rdp.sync.ui.screens.DeviceEditScreen
import com.rdp.sync.ui.screens.DeviceListScreen

/**
 * 导航路由
 */
object NavRoutes {
    const val DEVICE_LIST = "device_list"
    const val DEVICE_DETAIL = "device_detail/{deviceId}"
    const val DEVICE_EDIT = "device_edit/{deviceId?}"
    const val SETTINGS = "settings"
    
    fun deviceDetail(deviceId: Long) = "device_detail/$deviceId"
    fun deviceEdit(deviceId: Long? = null) = "device_edit/${deviceId?.toString() ?: ""}"
}

/**
 * 应用导航
 */
@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    
    NavHost(
        navController = navController,
        startDestination = NavRoutes.DEVICE_LIST
    ) {
        // 设备列表页面
        composable(NavRoutes.DEVICE_LIST) {
            DeviceListScreen(
                onDeviceClick = { deviceId ->
                    navController.navigate(NavRoutes.deviceDetail(deviceId))
                },
                onAddDevice = {
                    navController.navigate(NavRoutes.deviceEdit())
                },
                onEditDevice = { deviceId ->
                    navController.navigate(NavRoutes.deviceEdit(deviceId))
                }
            )
        }
        
        // 设备详情页面
        composable(
            route = NavRoutes.DEVICE_DETAIL,
            arguments = listOf(navArgument("deviceId") { type = NavType.LongType })
        ) { backStackEntry ->
            val deviceId = backStackEntry.arguments?.getLong("deviceId") ?: return@composable
            DeviceDetailScreen(
                deviceId = deviceId,
                onBack = { navController.popBackStack() },
                onEdit = { 
                    navController.popBackStack()
                    navController.navigate(NavRoutes.deviceEdit(deviceId))
                }
            )
        }
        
        // 设备编辑页面
        composable(
            route = NavRoutes.DEVICE_EDIT,
            arguments = listOf(navArgument("deviceId") { type = NavType.LongType; defaultValue = -1L })
        ) { backStackEntry ->
            val deviceId = backStackEntry.arguments?.getLong("deviceId")
            DeviceEditScreen(
                deviceId = if (deviceId == -1L) null else deviceId,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
