package com.doomedams.app.database.repository

import com.doomedams.app.database.AppDatabase
import com.doomedams.app.database.audit.AuditEngine
import com.doomedams.app.database.entity.ExamEntity

class ExamRepository(
    private val database: AppDatabase,
    private val auditEngine: AuditEngine
) {
    suspend fun createExam(
        exam: ExamEntity,
        auditEventId: String,
        actorId: String,
        actorRole: String
    ): ExamEntity {
        require(exam.durationSeconds > 0) { "Exam duration must be positive" }
        return auditEngine.executeAuditedTransaction(
            auditEventId = auditEventId,
            actorId = actorId,
            actorRole = actorRole,
            actionType = "CREATE_EXAM",
            aggregateType = "EXAM",
            aggregateId = exam.examId,
            previousState = null,
            newState = "{\"status\":\"${exam.status}\",\"title\":\"${exam.title}\"}",
            reason = "Exam created by authorized administrator"
        ) {
            database.examDao().insertExam(exam)
            exam
        }
    }

    suspend fun getExam(examId: String): ExamEntity? {
        return database.examDao().getExamById(examId)
    }

    suspend fun getExamsByInstitute(instituteId: String): List<ExamEntity> {
        return database.examDao().getExamsByInstitute(instituteId)
    }
}
