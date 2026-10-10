package com.doomedams.app.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Native Room Entity representing CONFIDENTIAL worked solutions and explanations.
 */
@Entity(
    tableName = "worked_solutions",
    foreignKeys = [
        ForeignKey(
            entity = QuestionEntity::class,
            parentColumns = ["questionId"],
            childColumns = ["questionId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ExamEntity::class,
            parentColumns = ["examId"],
            childColumns = ["examId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["questionId"], unique = true),
        Index(value = ["examId"])
    ]
)
data class WorkedSolutionEntity(
    @PrimaryKey
    val solutionId: String,
    val questionId: String,
    val examId: String,
    val solutionText: String,
    val sourceReference: String?,
    val verificationStatus: String, // UNVERIFIED, VERIFIED
    val reviewerId: String?,
    val verifiedAt: String?,
    val updatedAt: String
)
