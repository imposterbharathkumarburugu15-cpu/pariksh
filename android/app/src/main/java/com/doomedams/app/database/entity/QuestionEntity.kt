package com.doomedams.app.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Native Room Entity representing an extracted or administrator-edited examination question.
 */
@Entity(
    tableName = "questions",
    foreignKeys = [
        ForeignKey(
            entity = ExamEntity::class,
            parentColumns = ["examId"],
            childColumns = ["examId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = PaperSectionEntity::class,
            parentColumns = ["sectionId"],
            childColumns = ["sectionId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["examId", "questionNumber"]),
        Index(value = ["sectionId"])
    ]
)
data class QuestionEntity(
    @PrimaryKey
    val questionId: String,
    val examId: String,
    val sectionId: String?,
    val questionNumber: Int,
    val questionType: String, // SINGLE_SELECT, MULTI_SELECT, NUMERICAL, SUBJECTIVE, UNKNOWN
    val questionText: String,
    val answerOptionsJson: String, // JSON array of options: [{"optionId":"...","label":"A","text":"..."}]
    val sourcePage: Int?,
    val sourceStartLine: Int?,
    val sourceEndLine: Int?,
    val sourceSnippet: String?,
    val extractionConfidence: String, // HIGH, MEDIUM, LOW, MANUAL
    val reviewStatus: String, // NEEDS_REVIEW, VERIFIED, REJECTED
    val createdAt: String,
    val updatedAt: String
)
