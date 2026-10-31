package com.rdp.sync.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rdp.sync.data.Device
import com.rdp.sync.database.DeviceDatabase
import com.rdp.sync.manager.SyncDirection
import com.rdp.sync.manager.SyncManager
import com.rdp.sync.network.WebDavSyncService
import com.rdp.sync.network.safeOperationMessage
import com.rdp.sync.repository.DeviceRepository
import com.rdp.sync.security.SecureWebDavSettings
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
    val isSaving: Boolean = false,
    val webDavUrl: String = "",
    val webDavUsername: String = "",
    val webDavPassword: String = "",
    val webDavCredentialUnavailable: Boolean = false,
    val webDavSettingsLoaded: Boolean = false,
    val webDavTestCompletion: Long = 0
)

class DeviceViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = DeviceRepository(DeviceDatabase.getDatabase(application.applicationContext))
    private val secureSettings = SecureWebDavSettings(application.getSharedPreferences("rdpsync_settings", Application.MODE_PRIVATE))
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val settings = withContext(Dispatchers.IO) { secureSettings.load() }
            _uiState.update { it.copy(webDavUrl = settings.url, webDavUsername = settings.username,
                webDavPassword = settings.password, webDavCredentialUnavailable = settings.unavailable, webDavSettingsLoaded = true,
                error = if (settings.unavailable) "WebDAV 密码无法解密，请在设置中重新输入；原有数据已保留。" else it.error) }
            configureSyncManager(_uiState.value)
        }
        loadDevices()
    }

    private fun loadDevices() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                repository.getAllDevices().collect { devices ->
                    _uiState.update { it.copy(devices = devices, isLoading = false) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = safeOperationMessage(e), isLoading = false) }
            }
        }
    }

    fun addDevice(name: String, host: String, port: Int, username: String, password: String, domain: String = "", rdpServerName: String = "", width: Int = 1280, height: Int = 720, onSaved: () -> Unit = {}) {
        if (_uiState.value.isSaving) return
        _uiState.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            val device = Device(name = name.trim(), host = host.trim(), port = port, username = username.trim(), password = password, domain = domain.trim(), rdpServerName = rdpServerName.trim(), width = width, height = height)
            try { repository.insertDevice(device); onSaved() } catch (e: Exception) { _uiState.update { it.copy(error = safeOperationMessage(e)) } }
            finally { _uiState.update { it.copy(isSaving = false) } }
        }
    }

    fun updateDevice(device: Device, onSaved: () -> Unit = {}) {
        if (_uiState.value.isSaving) return
        _uiState.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            try { repository.updateDevice(device.copy(name = device.name.trim(), host = device.host.trim(), username = device.username.trim(), domain = device.domain.trim(), rdpServerName = device.rdpServerName.trim())); onSaved() } catch (e: Exception) { _uiState.update { it.copy(error = safeOperationMessage(e)) } }
            finally { _uiState.update { it.copy(isSaving = false) } }
        }
    }

    fun deleteDevice(device: Device) {
        viewModelScope.launch {
            try { repository.deleteDevice(device) } catch (e: Exception) { _uiState.update { it.copy(error = safeOperationMessage(e)) } }
        }
    }

    fun saveWebDavSettings(baseUrl: String, username: String, password: String) {
        viewModelScope.launch {
            try {
                val cleanUrl = baseUrl.trim().trimEnd('/')
                withContext(Dispatchers.IO) { secureSettings.save(cleanUrl, username.trim(), password) }
                _uiState.update { it.copy(webDavUrl = cleanUrl, webDavUsername = username.trim(), webDavPassword = password,
                    webDavCredentialUnavailable = false, message = "WebDAV 设置已加密保存") }
                configureSyncManager(_uiState.value)
            } catch (e: Exception) { _uiState.update { it.copy(error = safeOperationMessage(e)) } }
        }
    }

    fun syncWebdav(direction: SyncDirection = SyncDirection.MERGE) {
        if (_uiState.value.isSyncing) return
        viewModelScope.launch {
            val state = _uiState.value
            configureSyncManager(state)
            _uiState.update { it.copy(isSyncing = true, error = null, message = null) }
            try {
                check(!state.webDavCredentialUnavailable) { "请先重新输入无法解密的 WebDAV 密码" }
                val result = withContext(Dispatchers.IO) {
                    when (direction) {
                        SyncDirection.UPLOAD, SyncDirection.UPLOAD_WITH_PASSWORDS -> SyncManager.uploadDevices(state.devices, direction.includesPasswords)
                        SyncDirection.DOWNLOAD, SyncDirection.DOWNLOAD_WITH_PASSWORDS -> SyncManager.downloadDevices(direction.includesPasswords)
                        SyncDirection.MERGE, SyncDirection.MERGE_WITH_PASSWORDS -> SyncManager.mergeDevices(state.devices, direction.includesPasswords)
                    }.getOrThrow()
                }
                result.devicesToStore?.let { repository.importDevices(it, mergeOnlyNew = result.mergeOnlyNew) }
                _uiState.update { it.copy(isSyncing = false, message = result.message) }
            } catch (e: Exception) { _uiState.update { it.copy(isSyncing = false, error = "同步失败: ${safeOperationMessage(e)}") } }
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
                onSuccess = { message -> _uiState.update { it.copy(isSyncing = false, message = message, webDavTestCompletion = it.webDavTestCompletion + 1) } },
                onFailure = { e -> _uiState.update { it.copy(isSyncing = false, error = "WebDAV 测试失败: ${safeOperationMessage(e)}", webDavTestCompletion = it.webDavTestCompletion + 1) } }
            )
        }
    }

    fun clearTransientMessages() {
        _uiState.update { it.copy(error = null, message = null) }
    }

    private fun configureSyncManager(state: UiState) {
        SyncManager.configureWebDav(state.webDavUrl, state.webDavUsername, state.webDavPassword)
    }

}
