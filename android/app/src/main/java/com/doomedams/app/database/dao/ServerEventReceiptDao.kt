package com.doomedams.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.doomedams.app.database.entity.ServerEventReceiptEntity

@Dao
interface ServerEventReceiptDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReceipt(receipt: ServerEventReceiptEntity)

    @Query("SELECT * FROM server_event_receipts WHERE eventId = :eventId LIMIT 1")
    suspend fun getReceiptByEventId(eventId: String): ServerEventReceiptEntity?

    @Query("SELECT * FROM server_event_receipts WHERE attemptId = :attemptId ORDER BY processedAt ASC")
    suspend fun getReceiptsByAttemptId(attemptId: String): List<ServerEventReceiptEntity>

    @Query("SELECT * FROM server_event_receipts WHERE studentId = :studentId ORDER BY processedAt ASC")
    suspend fun getReceiptsByStudentId(studentId: String): List<ServerEventReceiptEntity>

    @Query("SELECT COUNT(*) FROM server_event_receipts WHERE processingStatus = :status")
    suspend fun getReceiptCountByStatus(status: String): Int

    @Query("SELECT COUNT(*) FROM server_event_receipts")
    suspend fun getTotalReceiptCount(): Int

    @Query("SELECT * FROM server_event_receipts ORDER BY processedAt DESC")
    suspend fun getAllReceipts(): List<ServerEventReceiptEntity>

    @Update
    suspend fun updateReceipt(receipt: ServerEventReceiptEntity)
}
