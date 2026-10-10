package com.doomedams.app.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

/**
 * Native Room Entity representing an imported examination paper document (PDF or TXT).
 * Preserves cryptographic SHA-256 hash, private storage reference, and original metadata.
 */
@Entity(
    tableName = "paper_imports",
    indices = [
        Index(value = ["originalFileHash"]),
        Index(value = ["importStatus"])
    ]
)
data class PaperImportEntity(
    @PrimaryKey
    val importId: String,
    val originalFilename: String,
    val fileType: String, // "PDF" or "TXT"
    val originalFileHash: String, // SHA-256 hex
    val fileSizeBytes: Long,
    val privateStoragePath: String,
    val importStatus: String, // "PENDING", "EXTRACTED", "FAILED"
    val extractedAt: String?,
    val createdBy: String,
    val createdAt: String,
    val updatedAt: String
)
