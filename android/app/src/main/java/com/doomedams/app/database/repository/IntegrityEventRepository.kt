package com.doomedams.app.database.repository

import com.doomedams.app.database.AppDatabase
import com.doomedams.app.database.entity.IntegrityEventEntity

class IntegrityEventRepository(private val database: AppDatabase) {

    suspend fun recordEvent(event: IntegrityEventEntity): IntegrityEventEntity {
        database.integrityEventDao().insertEvent(event)
        return event
    }

    suspend fun getEventsForAttempt(attemptId: String): List<IntegrityEventEntity> {
        return database.integrityEventDao().getEventsByAttempt(attemptId)
    }

    suspend fun getEventsByCategory(category: String): List<IntegrityEventEntity> {
        return database.integrityEventDao().getEventsByCategory(category)
    }
}
