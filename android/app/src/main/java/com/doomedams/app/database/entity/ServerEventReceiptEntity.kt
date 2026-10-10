package com.doomedams.app.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Server Event Receipt Entity:
 * Durable inbox/receipt store on the authoritative examiner node.
 * Guarantees per-event idempotency, collision detection, and auditability.
 */
@Entity(
    tableName = "server_event_receipts",
    indices = [
        Index(value = ["eventId"], unique = true),
        Index(value = ["attemptId"]),
        Index(value = ["studentId"]),
        Index(value = ["processingStatus"])
    ]
)
data class ServerEventReceiptEntity(
    @PrimaryKey
    val receiptId: String,
    val eventId: String,
    val attemptId: String,
    val studentId: String,
    val eventType: String,
    val payloadHash: String,
    val idempotencyKey: String? = null,
    val processingStatus: String, // ACCEPTED, REJECTED, ALREADY_PROCESSED, CONFLICT, NEEDS_REVIEW, LATE_ACCEPTED, LATE_REJECTED
    val resultingVersion: Int = 1,
    val rejectionReason: String? = null,
    val serverReceivedAt: String,
    val processedAt: String
)
