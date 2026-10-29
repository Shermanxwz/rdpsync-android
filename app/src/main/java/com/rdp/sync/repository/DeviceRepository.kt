package com.rdp.sync.repository

import androidx.room.withTransaction
import com.rdp.sync.data.Device
import com.rdp.sync.database.DeviceDatabase
import com.rdp.sync.security.AndroidCredentialCipher
import com.rdp.sync.security.CredentialCipher
import com.rdp.sync.security.CredentialStorageException
import com.rdp.sync.security.credentialContext
import com.rdp.sync.security.stableIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Sole application write boundary for Room credentials. DAO rows are storage-only. */
class DeviceRepository(private val database: DeviceDatabase, private val cipher: CredentialCipher = AndroidCredentialCipher()) {
    private val dao = database.deviceDao()

    suspend fun migrateLegacy() = withContext(Dispatchers.IO) {
        database.withTransaction {
            // Encryption failure rolls back ALL row changes; schema migration itself never touches passwords.
            val rows = dao.getSnapshot()
            if (rows.any { it.passwordFormat == 0 }) {
                rows.filter { it.passwordFormat != 0 && it.passwordFormat != 2 }.forEach {
                    if (it.passwordFormat != 1) throw CredentialStorageException()
                    cipher.decrypt(it.password, it.credentialContext())
                }
                rows.filter { it.passwordFormat == 0 }.forEach { dao.updateDevice(encode(it)) }
            }
        }
    }

    fun getAllDevices(): Flow<List<Device>> = flow {
        try { migrateLegacy() } catch (_: CredentialStorageException) { /* Retain metadata and original bytes. */ }
        emitAll(dao.getAllDevices().map { rows -> rows.map(::decode) })
    }.flowOn(Dispatchers.IO)

    suspend fun getDeviceById(id: Long): Device? = withContext(Dispatchers.IO) {
        migrateLegacy()
        dao.getDeviceById(id)?.let(::decode)
    }

    suspend fun insertDevice(device: Device): Long = withContext(Dispatchers.IO) {
        migrateLegacy()
        require(!device.credentialUnavailable && device.passwordProvided)
        // New credentials are an explicit entry; never use this to overwrite an existing row.
        require(device.id == 0L)
        dao.insertDevice(encode(device))
    }

    suspend fun updateDevice(device: Device) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val previous = dao.getDeviceById(device.id) ?: error("设备不存在")
            val unavailable = decode(previous).credentialUnavailable
            if (!device.passwordProvided || (unavailable && device.password.isEmpty())) throw CredentialStorageException()
            // A nonempty password entered in the editor is deliberate per-row recovery, not an automatic reset.
            dao.updateDevice(encode(device))
        }
    }

    suspend fun deleteDevice(device: Device) = withContext(Dispatchers.IO) { dao.deleteDevice(device) }

    /** Remote records must have id=0. Nonzero local snapshots are ignored, including deleted rows.
     * MERGE inserts only identities absent at commit time; DOWNLOAD intentionally applies remote edits.
     * Missing passwords retain existing ciphertext; newly missing credentials persist as format=2. */
    suspend fun importDevices(devices: List<Device>, mergeOnlyNew: Boolean = false) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val existing = dao.getSnapshot().associateBy { it.stableIdentity() }.toMutableMap()
            devices.forEach { incoming ->
                // A nonzero ID denotes a local MERGE snapshot, not a remote record. Never replay it.
                if (incoming.id != 0L) return@forEach
                val old = existing[incoming.stableIdentity()]
                if (mergeOnlyNew && old != null) return@forEach
                val row = when {
                    old == null && !incoming.passwordProvided -> incoming.copy(password = "", passwordFormat = 2)
                    old != null && (incoming.credentialUnavailable || !incoming.passwordProvided) ->
                        incoming.copy(id = old.id, password = old.password, passwordFormat = old.passwordFormat)
                    old != null && old.passwordFormat != 2 && decode(old).credentialUnavailable -> throw CredentialStorageException()
                    else -> encode(incoming.copy(id = old?.id ?: 0L))
                }
                val id = dao.insertDevice(row)
                existing[incoming.stableIdentity()] = row.copy(id = id)
            }
        }
    }

    private fun encode(device: Device): Device = device.copy(
        password = cipher.encrypt(device.password, device.credentialContext()), passwordFormat = 1,
        credentialUnavailable = false, passwordProvided = true)

    private fun decode(row: Device): Device = try {
        if (row.passwordFormat == 2) row.copy(password = "", credentialUnavailable = true, passwordProvided = false)
        else {
            if (row.passwordFormat != 1) throw CredentialStorageException()
            row.copy(password = cipher.decrypt(row.password, row.credentialContext()), passwordFormat = 0,
                credentialUnavailable = false, passwordProvided = true)
        }
    } catch (_: CredentialStorageException) {
        row.copy(password = "", passwordFormat = 0).apply { credentialUnavailable = true; passwordProvided = false }
    }
}
