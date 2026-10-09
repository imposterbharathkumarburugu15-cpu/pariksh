package com.doomedams.app.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Incident Decision Entity:
 * Historical audit trail of adjudications for an incident.
 * Multiple decisions per incident are preserved to maintain full review history.
 */
@Entity(
    tableName = "incident_decisions",
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
        Index(value = ["reviewerId"])
    ]
)
data class IncidentDecisionEntity(
    @PrimaryKey
    val decisionId: String,
    val incidentId: String,
    val reviewerId: String,
    val decision: String,
    val evidenceAssessment: String,
    val reviewerJustification: String,
    val policyVersion: String,
    val createdAt: String
)
