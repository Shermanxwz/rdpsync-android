package com.rdp.sync.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rdp.sync.data.Device
import com.rdp.sync.repository.DeviceRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class UiState(
    val devices: List<Device> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val isSyncing: Boolean = false
)

class DeviceViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = DeviceRepository.create(
        com.rdp.sync.database.RdpSyncDatabase.getDatabase(application.applicationContext)
    )

    val uiState: StateFlow<UiState> = repository.getAllDevices()
        .map { devices -> UiState(devices = devices) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = UiState()
        )

    fun addDevice(name: String, host: String, port: Int, username: String, password: String, domain: String = "", width: Int = 1280, height: Int = 720) {
        viewModelScope.launch {
            val device = Device(name = name, host = host, port = port, username = username, password = password, domain = domain, width = width, height = height)
            repository.insertDevice(device)
        }
    }

    fun updateDevice(device: Device) {
        viewModelScope.launch {
            repository.updateDevice(device)
        }
    }

    fun deleteDevice(device: Device) {
        viewModelScope.launch {
            repository.deleteDevice(device)
        }
    }
}
