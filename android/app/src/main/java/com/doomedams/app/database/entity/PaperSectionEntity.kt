package com.doomedams.app.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Native Room Entity representing paper sections (e.g., Section A - Physics, Part I).
 */
@Entity(
    tableName = "paper_sections",
    foreignKeys = [
        ForeignKey(
            entity = ExamEntity::class,
            parentColumns = ["examId"],
            childColumns = ["examId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["examId", "sectionOrder"])
    ]
)
data class PaperSectionEntity(
    @PrimaryKey
    val sectionId: String,
    val examId: String,
    val title: String,
    val sectionOrder: Int,
    val sourceReference: String?,
    val createdAt: String
)
