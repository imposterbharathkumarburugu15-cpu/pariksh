package com.doomedams.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.doomedams.app.database.entity.PaperImportEntity

@Dao
interface PaperImportDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertImport(paperImport: PaperImportEntity)

    @Query("SELECT * FROM paper_imports WHERE importId = :importId")
    suspend fun getImportById(importId: String): PaperImportEntity?

    @Query("SELECT * FROM paper_imports WHERE originalFileHash = :hash LIMIT 1")
    suspend fun getImportByHash(hash: String): PaperImportEntity?

    @Query("SELECT * FROM paper_imports ORDER BY createdAt DESC")
    suspend fun getAllImports(): List<PaperImportEntity>

    @Update
    suspend fun updateImport(paperImport: PaperImportEntity)
}
