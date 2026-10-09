package com.doomedams.app.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Append-Only Audit Event Entity:
 * High-integrity audit record with cryptographic hash chaining (SHA-256).
 *
 * NOTE: Database triggers reject UPDATE and DELETE statements on this table.
 */
@Entity(
    tableName = "audit_events",
    indices = [
        Index(value = ["sequenceNumber"], unique = true),
        Index(value = ["idempotencyKey"], unique = true),
        Index(value = ["aggregateId"]),
        Index(value = ["actorId"]),
        Index(value = ["actionType"]),
        Index(value = ["eventTimestamp"])
    ]
)
data class AuditEventEntity(
    @PrimaryKey
    val auditEventId: String,
    val actorId: String,
    val actorRole: String,
    val actionType: String,
    val aggregateType: String,
    val aggregateId: String,
    val previousState: String?,
    val newState: String?,
    val reason: String?,
    val policyVersion: String,
    val eventTimestamp: String,
    val sequenceNumber: Long,
    val idempotencyKey: String?,
    val operationResult: String,
    val failureReason: String?,
    val previousHash: String,
    val currentHash: String
)
