package com.doomedams.app.database.repository

import com.doomedams.app.database.AppDatabase
import com.doomedams.app.database.audit.AuditEngine
import com.doomedams.app.database.entity.CandidateResponseEntity
import com.doomedams.app.database.entity.ExamAttemptEntity
import java.time.Instant
import java.time.format.DateTimeFormatter

class AttemptRepository(
    private val database: AppDatabase,
    private val auditEngine: AuditEngine
) {
    suspend fun createAttempt(attempt: ExamAttemptEntity): ExamAttemptEntity {
        database.examAttemptDao().insertAttempt(attempt)
        return attempt
    }

    suspend fun getAttempt(attemptId: String): ExamAttemptEntity? {
        return database.examAttemptDao().getAttemptById(attemptId)
    }

    suspend fun saveAnswerTransactionally(
        response: CandidateResponseEntity,
        auditEventId: String,
        studentId: String
    ): CandidateResponseEntity {
        return auditEngine.executeAuditedTransaction(
            auditEventId = auditEventId,
            actorId = studentId,
            actorRole = "STUDENT",
            actionType = "SAVE_ANSWER",
            aggregateType = "ATTEMPT",
            aggregateId = response.attemptId,
            previousState = null,
            newState = "{\"questionId\":\"${response.questionId}\",\"rev\":${response.revisionSequence}}",
            reason = "Periodic auto-save response by candidate"
        ) {
            database.candidateResponseDao().upsertResponse(response)
            response
        }
    }

    /**
     * Applies an administrative hold to an attempt's results.
     *
     * GUARANTEES:
     * 1. resultStatus transitions to 'HELD_PENDING_REVIEW'.
     * 2. attemptStatus remains untouched and unaltered.
     * 3. An immutable audit record is committed atomically in the same database transaction.
     */
    suspend fun applyResultHold(
        attemptId: String,
        reason: String,
        auditEventId: String,
        reviewerId: String,
        reviewerRole: String
    ): ExamAttemptEntity {
        val existing = database.examAttemptDao().getAttemptById(attemptId)
            ?: throw IllegalArgumentException("Attempt with ID '$attemptId' not found.")

        val updatedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
        val previousResultStatus = existing.resultStatus

        return auditEngine.executeAuditedTransaction(
            auditEventId = auditEventId,
            actorId = reviewerId,
            actorRole = reviewerRole,
            actionType = "APPLY_RESULT_HOLD",
            aggregateType = "EXAM_ATTEMPT",
            aggregateId = attemptId,
            previousState = "{\"resultStatus\":\"$previousResultStatus\",\"attemptStatus\":\"${existing.attemptStatus}\"}",
            newState = "{\"resultStatus\":\"HELD_PENDING_REVIEW\",\"attemptStatus\":\"${existing.attemptStatus}\"}",
            reason = reason
        ) {
            database.examAttemptDao().updateResultStatus(
                attemptId = attemptId,
                resultStatus = "HELD_PENDING_REVIEW",
                updatedAt = updatedAt
            )
            existing.copy(resultStatus = "HELD_PENDING_REVIEW", updatedAt = updatedAt)
        }
    }
}
