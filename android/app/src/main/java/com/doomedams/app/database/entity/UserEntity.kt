package com.doomedams.app.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

/**
 * Native Room Entity representing users and their administrative/student roles.
 * Supported roles align with TypeScript domain: STUDENT, INVIGILATOR, INCIDENT_REVIEWER,
 * EXAMINATION_ADMIN, CHIEF_EXAMINATION_OFFICER, AUDITOR, SYSTEM_ADMIN.
 */
@Entity(
    tableName = "users",
    indices = [
        Index(value = ["role"]),
        Index(value = ["instituteId"])
    ]
)
data class UserEntity(
    @PrimaryKey
    val userId: String,
    val displayName: String,
    val role: String,
    val accountStatus: String,
    val instituteId: String,
    val createdAt: String
)
