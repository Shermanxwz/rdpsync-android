package com.rdp.sync.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "devices")
data class Device(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val host: String,
    val port: Int = 3389,
    val username: String,
    val password: String,
    val domain: String = "",
    val width: Int = 1280,
    val height: Int = 720
)
