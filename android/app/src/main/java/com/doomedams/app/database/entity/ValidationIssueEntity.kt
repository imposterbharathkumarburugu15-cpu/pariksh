package com.doomedams.app.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Native Room Entity representing structural and validation issues on an examination draft.
 * Blocking issues (severity = 'ERROR') prevent publication.
 */
@Entity(
    tableName = "validation_issues",
    foreignKeys = [
        ForeignKey(
            entity = ExamEntity::class,
            parentColumns = ["examId"],
            childColumns = ["examId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["examId", "severity"]),
        Index(value = ["questionId"]),
        Index(value = ["isResolved"])
    ]
)
data class ValidationIssueEntity(
    @PrimaryKey
    val issueId: String,
    val examId: String,
    val importId: String?,
    val questionId: String?,
    val severity: String, // INFO, WARNING, ERROR
    val issueCode: String,
    val description: String,
    val sourceReference: String?,
    val isResolved: Boolean,
    val resolvedBy: String?,
    val resolvedAt: String?,
    val createdAt: String
)
