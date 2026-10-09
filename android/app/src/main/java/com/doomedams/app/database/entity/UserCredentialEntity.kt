package com.doomedams.app.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * User Credential Entity:
 * Securely stores authentication credentials isolated from public user profiles.
 * Plaintext passwords and secret keys are strictly prohibited.
 */
@Entity(
    tableName = "user_credentials",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["userId"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["userId"], unique = true)
    ]
)
data class UserCredentialEntity(
    @PrimaryKey
    val userId: String,
    val passwordHash: String,
    val salt: String,
    val hashAlgorithm: String = "PBKDF2WithHmacSHA256",
    val iterations: Int = 100000,
    val updatedAt: String
)
