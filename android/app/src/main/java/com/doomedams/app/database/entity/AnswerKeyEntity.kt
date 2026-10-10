package com.doomedams.app.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Native Room Entity representing CONFIDENTIAL examination answer keys.
 * Kept strictly isolated from student-accessible question retrieval queries.
 */
@Entity(
    tableName = "answer_keys",
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
        Index(value = ["examId"]),
        Index(value = ["verificationStatus"])
    ]
)
data class AnswerKeyEntity(
    @PrimaryKey
    val questionId: String,
    val examId: String,
    val expectedAnswerJson: String, // String or JSON array of strings e.g. "A" or ["A","C"] or "42.5"
    val numericalTolerance: Double?,
    val answerKeySource: String, // EXTRACTED, MANUAL
    val verificationStatus: String, // UNVERIFIED, VERIFIED
    val reviewerId: String?,
    val verifiedAt: String?,
    val updatedAt: String
)
