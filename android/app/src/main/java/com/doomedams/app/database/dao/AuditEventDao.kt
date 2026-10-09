package com.doomedams.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.doomedams.app.database.entity.AuditEventEntity

@Dao
interface AuditEventDao {
    /**
     * Appends an immutable audit event.
     * Duplicate audit IDs or duplicate sequence numbers are rejected by the database.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAuditEvent(event: AuditEventEntity)

    @Query("SELECT * FROM audit_events ORDER BY sequenceNumber DESC LIMIT 1")
    suspend fun getLatestAuditEvent(): AuditEventEntity?

    @Query("SELECT * FROM audit_events WHERE auditEventId = :auditEventId")
    suspend fun getAuditEventById(auditEventId: String): AuditEventEntity?

    @Query("SELECT * FROM audit_events WHERE idempotencyKey = :idempotencyKey")
    suspend fun getAuditEventByIdempotencyKey(idempotencyKey: String): AuditEventEntity?

    @Query("SELECT * FROM audit_events WHERE aggregateId = :aggregateId ORDER BY sequenceNumber ASC")
    suspend fun getAuditEventsForAggregate(aggregateId: String): List<AuditEventEntity>

    @Query("SELECT COUNT(*) FROM audit_events")
    suspend fun getAuditEventCount(): Long
}
