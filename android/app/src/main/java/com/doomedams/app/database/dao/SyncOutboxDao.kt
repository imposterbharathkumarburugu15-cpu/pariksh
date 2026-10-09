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

    @Update
    suspend fun updateOutboxItem(outboxItem: SyncOutboxEntity)
}
