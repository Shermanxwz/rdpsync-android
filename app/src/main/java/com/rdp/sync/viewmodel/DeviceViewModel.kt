package com.rdp.sync.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rdp.sync.data.Device
import com.rdp.sync.database.DeviceDao
import com.rdp.sync.di.AppModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class DeviceViewModel(
    private val deviceDao: DeviceDao
) : ViewModel() {
    
    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()
    private val webDavSyncService = AppModule.webDavSyncService
    private val gson = Gson()
    
    init {
        loadDevices()
    }
    
    private fun loadDevices() {
        viewModelScope.launch {
            try {
                val remoteJson = webDavSyncService.pullDevices()
                val remoteDevices = parseDevicesFromJson(remoteJson)
                deviceDao.insertAll(remoteDevices)
                _devices.value = remoteDevices
            } catch (e: Exception) {
                println("[ViewModel] WebDAV pull failed: ${e.message}, using local DB")
                try {
                    deviceDao.getAllDevices().collect { localDevices ->
                        _devices.value = localDevices
                    }
                } catch (ex: Exception) {
                    println("[ViewModel] Local DB error: ${ex.message}")
                    _devices.value = emptyList()
                }
            }
        }
    }
    
    fun addDevice(device: Device) {
        viewModelScope.launch {
            deviceDao.insert(device)
            syncToDeviceList()
        }
    }
    
    fun updateDevice(device: Device) {
        viewModelScope.launch {
            deviceDao.update(device)
            syncToDeviceList()
        }
    }
    
    fun deleteDevice(device: Device) {
        viewModelScope.launch {
            deviceDao.delete(device)
            syncToDeviceList()
        }
    }
    
    private fun syncToDeviceList() {
        viewModelScope.launch {
            try {
                val json = gson.toJson(_devices.value)
                webDavSyncService.pushDevices(json)
            } catch (e: Exception) {
                println("[ViewModel] Sync failed: ${e.message}")
            }
        }
    }
    
    private fun parseDevicesFromJson(json: String): List<Device> {
        return try {
            val listType = object : TypeToken<List<Device>>() {}.type
            gson.fromJson<List<Device>>(json, listType) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
