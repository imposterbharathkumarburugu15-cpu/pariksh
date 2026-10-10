package com.doomedams.app.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Native Room Entity representing the authoritative examination result for an attempt.
 *
 * CRITICAL ARCHITECTURAL CONSTRAINTS:
 * 1. resultStatus is decoupled from attemptStatus and explicitly supports 'HELD_PENDING_REVIEW'.
 * 2. totalScaledScore and maxPossibleScaledScore are stored as scaled integers (100 units = 1.00 mark).
 * 3. resultHash holds a canonical SHA-256 fingerprint of all score components.
 */
@Entity(
    tableName = "exam_results",
    foreignKeys = [
        ForeignKey(
            entity = ExamAttemptEntity::class,
            parentColumns = ["attemptId"],
            childColumns = ["attemptId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ExamEntity::class,
            parentColumns = ["examId"],
            childColumns = ["examId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["userId"],
            childColumns = ["studentId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["attemptId"], unique = true),
        Index(value = ["examId"]),
        Index(value = ["studentId"]),
        Index(value = ["resultStatus"])
    ]
)
data class ExamResultEntity(
    @PrimaryKey
    val resultId: String,
    val attemptId: String,
    val examId: String,
    val studentId: String,
    val version: Int,
    val resultStatus: String, // PENDING_GRADING, GRADED, HELD_PENDING_REVIEW, PUBLISHED, WITHHELD
    val totalScaledScore: Long,
    val maxPossibleScaledScore: Long,
    val correctCount: Int,
    val incorrectCount: Int,
    val unansweredCount: Int,
    val partiallyCorrectCount: Int,
    val pendingManualCount: Int,
    val markingSchemeVersion: Int,
    val scoringEngineVersion: String,
    val sectionTotalsJson: String,
    val resultHash: String,
    val holdReason: String?,
    val scoredAt: String,
    val publishedAt: String?,
    val createdAt: String,
    val updatedAt: String
)
