package com.doomedams.app.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Incident Case Entity:
 * Represents formal administrator reviews of flagged exam incidents.
 */
@Entity(
    tableName = "incident_cases",
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
        Index(value = ["incidentStatus"]),
        Index(value = ["category"])
    ]
)
data class IncidentCaseEntity(
    @PrimaryKey
    val incidentId: String,
    val examId: String,
    val attemptId: String,
    val studentId: String,
    val category: String,
    val incidentStatus: String,
    val evidenceAssessment: String,
    val decision: String,
    val reviewerId: String?,
    val policyVersion: String,
    val version: Int,
    val createdAt: String,
    val updatedAt: String
)
