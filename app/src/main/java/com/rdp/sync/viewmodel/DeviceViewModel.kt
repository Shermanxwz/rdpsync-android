package com.rdp.sync.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rdp.sync.data.Device
import com.rdp.sync.database.DeviceDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val devices: List<Device> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val isSyncing: Boolean = false
)

class DeviceViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = DeviceDatabase.getDatabase(application.applicationContext).deviceDao()

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        loadDevices()
    }

    private fun loadDevices() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                dao.getAllDevices().collect { devices ->
                    _uiState.update { UiState(devices = devices) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message, isLoading = false) }
            }
        }
    }

    fun addDevice(name: String, host: String, port: Int, username: String, password: String, domain: String = "", width: Int = 1280, height: Int = 720) {
        viewModelScope.launch {
            val device = Device(name = name, host = host, port = port, username = username, password = password, domain = domain, width = width, height = height)
            dao.insertDevice(device)
        }
    }

    fun updateDevice(device: Device) {
        viewModelScope.launch {
            dao.updateDevice(device)
        }
    }

    fun deleteDevice(device: Device) {
        viewModelScope.launch {
            dao.deleteDevice(device)
        }
    }

    fun syncWebdav() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true, error = null) }
            try {
                // TODO: implement actual sync
                Thread.sleep(500) // Simulate sync
                _uiState.update { it.copy(isSyncing = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSyncing = false, error = "同步失败: ${e.message}") }
            }
        }
    }
}
