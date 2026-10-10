package com.doomedams.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.doomedams.app.database.entity.SyncOutboxEntity

@Dao
interface SyncOutboxDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun enqueue(outboxItem: SyncOutboxEntity)

    @Query("SELECT * FROM sync_outbox WHERE deliveryStatus = 'PENDING' ORDER BY createdAt ASC")
    suspend fun getPendingItems(): List<SyncOutboxEntity>

    @Query("SELECT * FROM sync_outbox WHERE deliveryStatus IN ('PENDING', 'RETRY_PENDING') ORDER BY createdAt ASC")
    suspend fun getDeliverableItems(): List<SyncOutboxEntity>

    @Query("SELECT * FROM sync_outbox WHERE eventId = :eventId LIMIT 1")
    suspend fun getItemByEventId(eventId: String): SyncOutboxEntity?

    @Query("SELECT * FROM sync_outbox WHERE deliveryStatus = :status ORDER BY createdAt ASC")
    suspend fun getItemsByStatus(status: String): List<SyncOutboxEntity>

    @Query("SELECT * FROM sync_outbox ORDER BY createdAt ASC")
    suspend fun getAllItems(): List<SyncOutboxEntity>

    @Query("UPDATE sync_outbox SET deliveryStatus = :status WHERE eventId = :eventId")
    suspend fun updateStatusByEventId(eventId: String, status: String)

    @Query("UPDATE sync_outbox SET deliveryStatus = :status, lastError = :error, retryCount = :retryCount WHERE eventId = :eventId")
    suspend fun updateDeliveryAttempt(eventId: String, status: String, error: String?, retryCount: Int)

    @Update
    suspend fun updateOutboxItem(outboxItem: SyncOutboxEntity)
}
