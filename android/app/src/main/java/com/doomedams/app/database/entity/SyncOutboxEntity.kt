package com.doomedams.app.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Offline Sync Outbox Entity:
 * Queues events locally for eventual idempotent local-network or server synchronization.
 */
@Entity(
    tableName = "sync_outbox",
    indices = [
        Index(value = ["eventId"], unique = true),
        Index(value = ["deliveryStatus"]),
        Index(value = ["createdAt"])
    ]
)
data class SyncOutboxEntity(
    @PrimaryKey
    val outboxId: String,
    val eventId: String,
    val aggregateId: String,
    val eventType: String,
    val payloadJson: String,
    val createdAt: String,
    val deliveryStatus: String = "PENDING",
    val retryCount: Int = 0,
    val lastError: String? = null
)
