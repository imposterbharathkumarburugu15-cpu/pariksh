package com.doomedams.app.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Integrity Event Entity:
 * Telemetry log for offline device integrity signals.
 * App package and name are explicitly optional due to Android OS sandbox constraints.
 */
@Entity(
    tableName = "integrity_events",
    foreignKeys = [
        ForeignKey(
            entity = ExamAttemptEntity::class,
            parentColumns = ["attemptId"],
            childColumns = ["attemptId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["attemptId"]),
        Index(value = ["examId"]),
        Index(value = ["studentId"]),
        Index(value = ["eventCategory"]),
        Index(value = ["deviceReportedAt"])
    ]
)
data class IntegrityEventEntity(
    @PrimaryKey
    val eventId: String,
    val examId: String,
    val attemptId: String,
    val studentId: String,
    val eventCategory: String,
    val deviceReportedAt: String,
    val receivedAt: String?,
    val sequenceNumber: Long,
    val sourceDeviceId: String,
    val payloadJson: String,
    val payloadHash: String?,
    val appPackageName: String?,
    val displayedAppName: String?,
    val ingestionStatus: String = "PENDING"
)
