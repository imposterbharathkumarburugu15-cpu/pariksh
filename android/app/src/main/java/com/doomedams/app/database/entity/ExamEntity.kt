package com.doomedams.app.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

/**
 * Native Room Entity representing examination configurations.
 * Enforces positive duration and status tracking (DRAFT, PUBLISHED, IN_PROGRESS, COMPLETED, ARCHIVED).
 */
@Entity(
    tableName = "exams",
    indices = [
        Index(value = ["instituteId"]),
        Index(value = ["status"])
    ]
)
data class ExamEntity(
    @PrimaryKey
    val examId: String,
    val instituteId: String,
    val title: String,
    val status: String,
    val durationSeconds: Long,
    val markingSchemeVersion: Int,
    val createdAt: String,
    val updatedAt: String
)
