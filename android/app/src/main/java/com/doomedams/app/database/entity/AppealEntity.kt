package com.doomedams.app.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Appeal Entity:
 * Represents formal student appeals against incident decisions or policy actions
 * (e.g. against a result hold, attempt cancellation, or misconduct finding).
 *
 * Strict Architectural Separation:
 * Filing an appeal does not automatically lift a hold or reinstate an attempt;
 * decisions require independent review by an authorized official who was not the original reviewer.
 */
@Entity(
    tableName = "incident_appeals",
    foreignKeys = [
        ForeignKey(
            entity = IncidentCaseEntity::class,
            parentColumns = ["incidentId"],
            childColumns = ["incidentId"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["userId"],
            childColumns = ["studentId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["incidentId"]),
        Index(value = ["studentId"]),
        Index(value = ["appealStatus"])
    ]
)
data class AppealEntity(
    @PrimaryKey
    val appealId: String,
    val incidentId: String,
    val attemptId: String,
    val studentId: String,
    val challengedAction: String, // e.g. "RESULT_HOLD", "CANCEL_ATTEMPT", "CONFIRMED_VIOLATION"
    val appealReason: String,
    val evidenceReferences: String?,
    val appealStatus: String, // SUBMITTED, PENDING_REVIEW, UNDER_REVIEW, UPHELD, OVERTURNED, REQUIRES_MORE_INFORMATION, CLOSED
    val assignedReviewerId: String?,
    val reviewerDecision: String?,
    val outcomeReason: String?,
    val submittedAt: String,
    val resolvedAt: String?,
    val updatedAt: String
)
