package com.doomedams.app.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Policy Action Entity:
 * Tracks policy actions (e.g. HOLD, WARN, CANCEL_ATTEMPT).
 *
 * CRITICAL SEPARATION:
 * actionType (e.g. HOLD) is kept strictly separate from executionStatus (e.g. REQUESTED, APPLIED).
 * Requesting a hold is not equivalent to successfully applying one.
 */
@Entity(
    tableName = "policy_actions",
    foreignKeys = [
        ForeignKey(
            entity = IncidentCaseEntity::class,
            parentColumns = ["incidentId"],
            childColumns = ["incidentId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["incidentId"]),
        Index(value = ["actionType"]),
        Index(value = ["executionStatus"])
    ]
)
data class PolicyActionEntity(
    @PrimaryKey
    val actionId: String,
    val incidentId: String,
    val actionType: String,
    val executionStatus: String,
    val requestedBy: String,
    val approvedBy: String?,
    val policyVersion: String,
    val reason: String,
    val createdAt: String,
    val completedAt: String?
)
