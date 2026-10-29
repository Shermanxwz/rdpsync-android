package com.rdp.sync.data

import androidx.room.ColumnInfo
import androidx.room.Ignore
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "devices")
class Device(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val host: String,
    val port: Int = 3389,
    val username: String,
    val password: String,
    val domain: String = "",
    val rdpServerName: String = "",
    val width: Int = 1280,
    val height: Int = 720,
    @ColumnInfo(defaultValue = "0") val passwordFormat: Int = 0
) {
    /** Only in-memory state. Ciphertext never leaves the repository. */
    @Ignore var credentialUnavailable: Boolean = false
    @Ignore var passwordProvided: Boolean = true

    /** Unlike a generated data-class copy, security presentation flags must survive copies. */
    fun copy(id: Long = this.id, name: String = this.name, host: String = this.host, port: Int = this.port,
        username: String = this.username, password: String = this.password, domain: String = this.domain,
        rdpServerName: String = this.rdpServerName, width: Int = this.width, height: Int = this.height,
        passwordFormat: Int = this.passwordFormat, credentialUnavailable: Boolean = this.credentialUnavailable,
        passwordProvided: Boolean = this.passwordProvided): Device =
        Device(id, name, host, port, username, password, domain, rdpServerName, width, height, passwordFormat).also {
            it.credentialUnavailable = credentialUnavailable
            it.passwordProvided = passwordProvided
        }

    override fun toString(): String = "Device(id=$id, credentials=REDACTED)"

    // Include transient availability in StateFlow equality (an empty valid password is NOT a locked one).
    override fun equals(other: Any?): Boolean = other is Device && id == other.id && name == other.name &&
        host == other.host && port == other.port && username == other.username && password == other.password &&
        domain == other.domain && rdpServerName == other.rdpServerName && width == other.width &&
        height == other.height && passwordFormat == other.passwordFormat &&
        credentialUnavailable == other.credentialUnavailable && passwordProvided == other.passwordProvided
    override fun hashCode(): Int = arrayOf(id, name, host, port, username, password, domain, rdpServerName,
        width, height, passwordFormat, credentialUnavailable, passwordProvided).contentHashCode()
}
