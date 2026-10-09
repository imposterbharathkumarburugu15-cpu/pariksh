package com.doomedams.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.doomedams.app.database.entity.IntegrityEventEntity

@Dao
interface IntegrityEventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvent(event: IntegrityEventEntity)

    @Query("SELECT * FROM integrity_events WHERE eventId = :eventId")
    suspend fun getEventById(eventId: String): IntegrityEventEntity?

    @Query("SELECT * FROM integrity_events WHERE attemptId = :attemptId ORDER BY sequenceNumber ASC")
    suspend fun getEventsByAttempt(attemptId: String): List<IntegrityEventEntity>

    @Query("SELECT * FROM integrity_events WHERE eventCategory = :category")
    suspend fun getEventsByCategory(category: String): List<IntegrityEventEntity>
}
