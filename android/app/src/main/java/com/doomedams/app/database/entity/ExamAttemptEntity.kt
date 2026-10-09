package com.doomedams.app.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Examination Attempt Entity.
 *
 * CRITICAL ARCHITECTURAL GUARANTEE:
 * attemptStatus and resultStatus are stored as separate fields.
 * Result status explicitly supports 'HELD_PENDING_REVIEW'.
 * An administrative hold on results must never alter or overwrite the attempt's lifecycle status.
 */
@Entity(
    tableName = "exam_attempts",
    foreignKeys = [
        ForeignKey(
            entity = ExamEntity::class,
            parentColumns = ["examId"],
            childColumns = ["examId"],
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
        Index(value = ["examId"]),
        Index(value = ["studentId"]),
        Index(value = ["attemptStatus"]),
        Index(value = ["resultStatus"])
    ]
)
data class ExamAttemptEntity(
    @PrimaryKey
    val attemptId: String,
    val examId: String,
    val studentId: String,
    val attemptStatus: String,
    val resultStatus: String,
    val startedAt: String,
    val submittedAt: String?,
    val deadlineMetadata: String,
    val createdAt: String,
    val updatedAt: String
)
