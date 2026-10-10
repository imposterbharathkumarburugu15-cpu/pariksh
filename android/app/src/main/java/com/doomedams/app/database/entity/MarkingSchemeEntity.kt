package com.doomedams.app.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Native Room Entity representing an examination marking scheme.
 * Marks are stored in scaled integer representation (e.g., 400 = 4.00, -100 = -1.00).
 */
@Entity(
    tableName = "marking_schemes",
    foreignKeys = [
        ForeignKey(
            entity = ExamEntity::class,
            parentColumns = ["examId"],
            childColumns = ["examId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["examId"], unique = true)
    ]
)
data class MarkingSchemeEntity(
    @PrimaryKey
    val schemeId: String,
    val examId: String,
    val version: Int,
    val correctScaledMark: Long, // e.g. 400 for +4.00
    val incorrectScaledMark: Long, // e.g. -100 for -1.00
    val unansweredScaledMark: Long, // e.g. 0
    val allowNegativeMarking: Boolean,
    val sectionDefaultsJson: String, // JSON array of section marking rules
    val questionOverridesJson: String, // JSON array of question marking overrides
    val updatedAt: String
)
