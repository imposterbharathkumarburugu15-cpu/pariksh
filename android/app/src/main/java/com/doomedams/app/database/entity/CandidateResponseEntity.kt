package com.doomedams.app.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Candidate Response Entity: Stores candidate answer selections for a specific question.
 */
@Entity(
    tableName = "candidate_responses",
    foreignKeys = [
        ForeignKey(
            entity = ExamAttemptEntity::class,
            parentColumns = ["attemptId"],
            childColumns = ["attemptId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["attemptId"]),
        Index(value = ["attemptId", "questionId"], unique = true)
    ]
)
data class CandidateResponseEntity(
    @PrimaryKey
    val responseId: String,
    val attemptId: String,
    val questionId: String,
    val responsePayload: String,
    val revisionSequence: Int,
    val savedAt: String
)
