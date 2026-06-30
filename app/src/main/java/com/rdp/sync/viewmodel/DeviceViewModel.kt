package com.rdp.sync.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rdp.sync.data.Device
import com.rdp.sync.database.DeviceDatabase
import com.rdp.sync.manager.SyncDirection
import com.rdp.sync.manager.SyncManager
import com.rdp.sync.network.WebDavSyncService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val devices: List<Device> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val isSyncing: Boolean = false,
    val webDavUrl: String = "",
    val webDavUsername: String = "",
    val webDavPassword: String = ""
)

class DeviceViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = DeviceDatabase.getDatabase(application.applicationContext).deviceDao()
    private val prefs = application.getSharedPreferences("rdpsync_settings", Application.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(
        UiState(
            webDavUrl = prefs.getString(KEY_WEBDAV_URL, "") ?: "",
            webDavUsername = prefs.getString(KEY_WEBDAV_USERNAME, "") ?: "",
            webDavPassword = prefs.getString(KEY_WEBDAV_PASSWORD, "") ?: ""
        )
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        configureSyncManager(_uiState.value)
        loadDevices()
    }

    private fun loadDevices() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                dao.getAllDevices().collect { devices ->
                    _uiState.update { it.copy(devices = devices, isLoading = false) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message, isLoading = false) }
            }
        }
    }

    fun addDevice(name: String, host: String, port: Int, username: String, password: String, domain: String = "", rdpServerName: String = "", width: Int = 1280, height: Int = 720) {
        viewModelScope.launch {
            val device = Device(name = name.trim(), host = host.trim(), port = port, username = username.trim(), password = password, domain = domain.trim(), rdpServerName = rdpServerName.trim(), width = width, height = height)
            dao.insertDevice(device)
        }
    }

    fun updateDevice(device: Device) {
        viewModelScope.launch {
            dao.updateDevice(device.copy(name = device.name.trim(), host = device.host.trim(), username = device.username.trim(), domain = device.domain.trim(), rdpServerName = device.rdpServerName.trim()))
        }
    }

    fun deleteDevice(device: Device) {
        viewModelScope.launch {
            dao.deleteDevice(device)
        }
    }

    fun saveWebDavSettings(baseUrl: String, username: String, password: String) {
        val cleanUrl = baseUrl.trim().trimEnd('/')
        prefs.edit()
            .putString(KEY_WEBDAV_URL, cleanUrl)
            .putString(KEY_WEBDAV_USERNAME, username.trim())
            .putString(KEY_WEBDAV_PASSWORD, password)
            .apply()
        _uiState.update {
            it.copy(
                webDavUrl = cleanUrl,
                webDavUsername = username.trim(),
                webDavPassword = password,
                message = "WebDAV 设置已保存"
            )
        }
        configureSyncManager(_uiState.value)
    }

    fun syncWebdav(direction: SyncDirection = SyncDirection.MERGE) {
        viewModelScope.launch {
            val state = _uiState.value
            configureSyncManager(state)
            _uiState.update { it.copy(isSyncing = true, error = null, message = null) }
            val result = withContext(Dispatchers.IO) {
                when (direction) {
                    SyncDirection.UPLOAD -> SyncManager.uploadDevices(state.devices)
                    SyncDirection.DOWNLOAD -> SyncManager.downloadDevices()
                    SyncDirection.MERGE -> SyncManager.mergeDevices(state.devices)
                }
            }

            result.fold(
                onSuccess = { syncResult ->
                    if (syncResult.devicesToStore != null) {
                        withContext(Dispatchers.IO) {
                            dao.replaceAll(syncResult.devicesToStore)
                        }
                    }
                    _uiState.update { it.copy(isSyncing = false, message = syncResult.message) }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isSyncing = false, error = "同步失败: ${e.message}") }
                }
            )
        }
    }

    fun testWebDavSettings(baseUrl: String, username: String, password: String) {
        viewModelScope.launch {
            val cleanUrl = baseUrl.trim().trimEnd('/')
            _uiState.update { it.copy(isSyncing = true, error = null, message = null) }
            val result = withContext(Dispatchers.IO) {
                WebDavSyncService.testConnection(cleanUrl, username.trim(), password)
            }
            result.fold(
                onSuccess = { message -> _uiState.update { it.copy(isSyncing = false, message = message) } },
                onFailure = { e -> _uiState.update { it.copy(isSyncing = false, error = "WebDAV 测试失败: ${e.message}") } }
            )
        }
    }

    fun clearTransientMessages() {
        _uiState.update { it.copy(error = null, message = null) }
    }

    private fun configureSyncManager(state: UiState) {
        SyncManager.configureWebDav(state.webDavUrl, state.webDavUsername, state.webDavPassword)
    }

    private companion object {
        const val KEY_WEBDAV_URL = "webdav_url"
        const val KEY_WEBDAV_USERNAME = "webdav_username"
        const val KEY_WEBDAV_PASSWORD = "webdav_password"
    }
}
