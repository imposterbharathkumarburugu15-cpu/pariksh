package com.doomedams.app.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Native Room Entity representing the immutable grading breakdown for a single question.
 */
@Entity(
    tableName = "question_grading_records",
    foreignKeys = [
        ForeignKey(
            entity = ExamResultEntity::class,
            parentColumns = ["resultId"],
            childColumns = ["resultId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = QuestionEntity::class,
            parentColumns = ["questionId"],
            childColumns = ["questionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["resultId"]),
        Index(value = ["questionId"]),
        Index(value = ["resultId", "questionId"], unique = true),
        Index(value = ["answerClassification"])
    ]
)
data class QuestionGradingRecordEntity(
    @PrimaryKey
    val recordId: String,
    val resultId: String,
    val questionId: String,
    val sectionId: String?,
    val questionNumber: Int,
    val questionType: String,
    val submittedAnswerJson: String?,
    val correctAnswerSnapshot: String?,
    val answerClassification: String,
    val marksAwarded: Long,
    val maxMarks: Long,
    val explanationCode: String,
    val calculationMetadataJson: String?
)
