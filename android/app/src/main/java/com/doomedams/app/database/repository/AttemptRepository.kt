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
            // Also propagate hold to exam_results table if a result already exists
            database.examResultDao().applyResultHold(
                attemptId = attemptId,
                newStatus = "HELD_PENDING_REVIEW",
                holdReason = reason,
                updatedAt = updatedAt
            )
            existing.copy(resultStatus = "HELD_PENDING_REVIEW", updatedAt = updatedAt)
        }
    }

    /**
     * Grades a submitted attempt transactionally using ScoringEngine.
     */
    suspend fun gradeAttempt(
        attemptId: String,
        actorId: String,
        actorRole: String
    ): com.doomedams.app.database.entity.ExamResultEntity {
        val attempt = database.examAttemptDao().getAttemptById(attemptId)
            ?: throw IllegalArgumentException("Attempt '$attemptId' not found.")

        val exam = database.examDao().getExamById(attempt.examId)
            ?: throw IllegalStateException("Exam '${attempt.examId}' not found.")

        val questions = database.questionDao().getQuestionsByExamId(attempt.examId)
        val sections = database.paperSectionDao().getSectionsByExamId(attempt.examId)
        val answerKeys = database.answerKeyDao().getAnswerKeysByExamId(attempt.examId)
        val responses = database.candidateResponseDao().getResponsesByAttempt(attemptId)
        val markingScheme = database.markingSchemeDao().getMarkingSchemeByExamId(attempt.examId)
            ?: throw IllegalStateException("Marking scheme not configured for exam '${attempt.examId}'.")

        val scoringResult = com.doomedams.app.server.scoring.ScoringEngine.calculateResult(
            exam = exam,
            attempt = attempt,
            questions = questions,
            sections = sections,
            answerKeys = answerKeys,
            responses = responses,
            markingScheme = markingScheme
        )

        val existingResult = database.examResultDao().getResultByAttemptId(attemptId)
        val version = (existingResult?.version ?: 0) + 1
        val resultId = existingResult?.resultId ?: "res_${attemptId}"
        val nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now())

        // Preserve hold if currently under review
        val resultStatus = if (attempt.resultStatus == "HELD_PENDING_REVIEW" || existingResult?.resultStatus == "HELD_PENDING_REVIEW") {
            "HELD_PENDING_REVIEW"
        } else {
            "GRADED"
        }

        val sectionTotalsJson = org.json.JSONArray().apply {
            for (sec in scoringResult.sectionScores) {
                put(org.json.JSONObject().apply {
                    put("sectionId", sec.sectionId)
                    put("title", sec.title)
                    put("totalQuestions", sec.totalQuestions)
                    put("correctCount", sec.correctCount)
                    put("incorrectCount", sec.incorrectCount)
                    put("unansweredCount", sec.unansweredCount)
                    put("partiallyCorrectCount", sec.partiallyCorrectCount)
                    put("totalScaledScore", sec.totalScaledScore)
                    put("maxPossibleScaledScore", sec.maxPossibleScaledScore)
                })
            }
        }.toString()

        val resultEntity = com.doomedams.app.database.entity.ExamResultEntity(
            resultId = resultId,
            attemptId = attemptId,
            examId = attempt.examId,
            studentId = attempt.studentId,
            version = version,
            resultStatus = resultStatus,
            totalScaledScore = scoringResult.totalScaledScore,
            maxPossibleScaledScore = scoringResult.maxPossibleScaledScore,
            correctCount = scoringResult.correctCount,
            incorrectCount = scoringResult.incorrectCount,
            unansweredCount = scoringResult.unansweredCount,
            partiallyCorrectCount = scoringResult.partiallyCorrectCount,
            pendingManualCount = scoringResult.pendingManualCount,
            markingSchemeVersion = scoringResult.markingSchemeVersion,
            scoringEngineVersion = scoringResult.scoringEngineVersion,
            sectionTotalsJson = sectionTotalsJson,
            resultHash = scoringResult.resultHash,
            holdReason = existingResult?.holdReason,
            scoredAt = nowIso,
            publishedAt = existingResult?.publishedAt,
            createdAt = existingResult?.createdAt ?: nowIso,
            updatedAt = nowIso
        )

        val gradingRecords = scoringResult.questionScores.map { qs ->
            com.doomedams.app.database.entity.QuestionGradingRecordEntity(
                recordId = "gr_${resultId}_${qs.questionId}",
                resultId = resultId,
                questionId = qs.questionId,
                sectionId = qs.sectionId,
                questionNumber = qs.questionNumber,
                questionType = qs.questionType,
                submittedAnswerJson = qs.submittedAnswer,
                correctAnswerSnapshot = qs.correctAnswerSnapshot,
                answerClassification = qs.classification.name,
                marksAwarded = qs.marksAwarded,
                maxMarks = qs.maxPossibleMarks,
                explanationCode = qs.explanationCode.name,
                calculationMetadataJson = qs.calculationMetadata
            )
        }

        val auditEventId = "aud_grade_${System.currentTimeMillis()}_${attemptId.take(8)}"

        return auditEngine.executeAuditedTransaction(
            auditEventId = auditEventId,
            actorId = actorId,
            actorRole = actorRole,
            actionType = if (version > 1) "RESCORING_COMPLETED" else "RESULT_CALCULATED",
            aggregateType = "EXAM_ATTEMPT",
            aggregateId = attemptId,
            previousState = existingResult?.let { "{\"score\":${it.totalScaledScore},\"status\":\"${it.resultStatus}\",\"ver\":${it.version}}" },
            newState = "{\"score\":${resultEntity.totalScaledScore},\"status\":\"${resultEntity.resultStatus}\",\"ver\":$version,\"hash\":\"${resultEntity.resultHash}\"}",
            reason = if (version > 1) "Attempt rescored with version $version" else "Attempt graded by ScoringEngine"
        ) {
            database.examResultDao().insertResult(resultEntity)
            database.questionGradingRecordDao().deleteRecordsByResultId(resultId)
            database.questionGradingRecordDao().insertRecords(gradingRecords)

            // Update attempt's resultStatus
            database.examAttemptDao().updateResultStatus(
                attemptId = attemptId,
                resultStatus = resultStatus,
                updatedAt = nowIso
            )

            resultEntity
        }
    }

    suspend fun getGradingResult(attemptId: String): com.doomedams.app.database.entity.ExamResultEntity? {
        return database.examResultDao().getResultByAttemptId(attemptId)
    }

    suspend fun getGradingRecords(resultId: String): List<com.doomedams.app.database.entity.QuestionGradingRecordEntity> {
        return database.questionGradingRecordDao().getRecordsByResultId(resultId)
    }

    suspend fun publishResult(
        attemptId: String,
        actorId: String,
        actorRole: String
    ): com.doomedams.app.database.entity.ExamResultEntity {
        val result = database.examResultDao().getResultByAttemptId(attemptId)
            ?: throw IllegalArgumentException("Grading result for attempt '$attemptId' not found.")

        if (result.resultStatus == "HELD_PENDING_REVIEW") {
            throw IllegalStateException("Cannot publish result: Attempt has an active administrative hold (HELD_PENDING_REVIEW).")
        }

        val nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
        val updatedResult = result.copy(
            resultStatus = "PUBLISHED",
            publishedAt = nowIso,
            updatedAt = nowIso
        )

        val auditEventId = "aud_pubres_${System.currentTimeMillis()}_${attemptId.take(8)}"

        return auditEngine.executeAuditedTransaction(
            auditEventId = auditEventId,
            actorId = actorId,
            actorRole = actorRole,
            actionType = "RESULT_PUBLISHED",
            aggregateType = "EXAM_RESULT",
            aggregateId = result.resultId,
            previousState = "{\"resultStatus\":\"${result.resultStatus}\"}",
            newState = "{\"resultStatus\":\"PUBLISHED\",\"publishedAt\":\"$nowIso\"}",
            reason = "Authorised publication of examination result"
        ) {
            database.examResultDao().updateResult(updatedResult)
            database.examAttemptDao().updateResultStatus(
                attemptId = attemptId,
                resultStatus = "PUBLISHED",
                updatedAt = nowIso
            )
            updatedResult
        }
    }

    suspend fun startOrResumeAttempt(
        examId: String,
        studentId: String,
        actorId: String,
        actorRole: String
    ): ExamAttemptEntity {
        val existing = database.examAttemptDao().getAttemptByStudentAndExam(studentId, examId)
        if (existing != null) {
            return existing
        }

        val exam = database.examDao().getExamById(examId)
            ?: throw IllegalArgumentException("Exam '$examId' not found.")

        if (exam.status != "PUBLISHED") {
            throw IllegalStateException("Cannot start attempt for exam in status '${exam.status}'. Exam must be PUBLISHED.")
        }

        val now = Instant.now()
        val startedAt = DateTimeFormatter.ISO_INSTANT.format(now)
        val deadline = DateTimeFormatter.ISO_INSTANT.format(now.plusSeconds(exam.durationSeconds))
        val attemptId = "att_${studentId}_${examId.take(12)}_${System.currentTimeMillis()}"

        val deadlineMetadata = org.json.JSONObject().apply {
            put("officialStartTime", startedAt)
            put("officialDeadline", deadline)
            put("durationSeconds", exam.durationSeconds)
        }.toString()

        val attempt = ExamAttemptEntity(
            attemptId = attemptId,
            examId = examId,
            studentId = studentId,
            attemptStatus = "IN_PROGRESS",
            resultStatus = "PENDING_GRADING",
            startedAt = startedAt,
            submittedAt = null,
            deadlineMetadata = deadlineMetadata,
            createdAt = startedAt,
            updatedAt = startedAt
        )

        val auditEventId = "aud_att_start_${System.currentTimeMillis()}_${attemptId.take(8)}"

        return auditEngine.executeAuditedTransaction(
            auditEventId = auditEventId,
            actorId = actorId,
            actorRole = actorRole,
            actionType = "ATTEMPT_STARTED",
            aggregateType = "EXAM_ATTEMPT",
            aggregateId = attemptId,
            previousState = null,
            newState = "{\"examId\":\"$examId\",\"studentId\":\"$studentId\",\"startedAt\":\"$startedAt\",\"deadline\":\"$deadline\"}",
            reason = "Authorised examination attempt started"
        ) {
            database.examAttemptDao().insertAttempt(attempt)
            attempt
        }
    }

    suspend fun submitAttempt(
        attemptId: String,
        studentId: String,
        clientChecksum: String,
        totalAnswered: Int,
        actorId: String,
        actorRole: String
    ): SubmissionReceipt {
        val attempt = database.examAttemptDao().getAttemptById(attemptId)
            ?: throw IllegalArgumentException("Attempt '$attemptId' not found.")

        if (attempt.studentId != studentId) {
            throw IllegalStateException("Student '$studentId' does not own attempt '$attemptId'.")
        }

        if (attempt.attemptStatus == "SUBMITTED") {
            val receiptHash = computeReceiptHash(attemptId, studentId, attempt.examId, attempt.submittedAt ?: attempt.updatedAt, clientChecksum)
            return SubmissionReceipt(
                receiptId = "rcpt_${attemptId}",
                attemptId = attemptId,
                examId = attempt.examId,
                studentId = studentId,
                submissionStatus = "ACCEPTED",
                submittedAt = attempt.submittedAt ?: attempt.updatedAt,
                receiptHash = receiptHash,
                totalQuestionsAnswered = totalAnswered
            )
        }

        if (attempt.attemptStatus != "IN_PROGRESS") {
            throw IllegalStateException("Cannot submit attempt in status '${attempt.attemptStatus}'.")
        }

        val now = Instant.now()
        val submittedAt = DateTimeFormatter.ISO_INSTANT.format(now)
        val receiptId = "rcpt_${attemptId}_${System.currentTimeMillis()}"
        val receiptHash = computeReceiptHash(attemptId, studentId, attempt.examId, submittedAt, clientChecksum)

        val auditEventId = "aud_submit_${System.currentTimeMillis()}_${attemptId.take(8)}"

        return auditEngine.executeAuditedTransaction(
            auditEventId = auditEventId,
            actorId = actorId,
            actorRole = actorRole,
            actionType = "ATTEMPT_SUBMITTED",
            aggregateType = "EXAM_ATTEMPT",
            aggregateId = attemptId,
            previousState = "{\"attemptStatus\":\"${attempt.attemptStatus}\"}",
            newState = "{\"attemptStatus\":\"SUBMITTED\",\"submittedAt\":\"$submittedAt\",\"receiptHash\":\"$receiptHash\"}",
            reason = "Candidate submitted final examination attempt"
        ) {
            database.examAttemptDao().submitAttempt(
                attemptId = attemptId,
                status = "SUBMITTED",
                submittedAt = submittedAt,
                updatedAt = submittedAt
            )
            SubmissionReceipt(
                receiptId = receiptId,
                attemptId = attemptId,
                examId = attempt.examId,
                studentId = studentId,
                submissionStatus = "ACCEPTED",
                submittedAt = submittedAt,
                receiptHash = receiptHash,
                totalQuestionsAnswered = totalAnswered
            )
        }
    }

    private fun computeReceiptHash(
        attemptId: String,
        studentId: String,
        examId: String,
        timestamp: String,
        clientChecksum: String
    ): String {
        val raw = "$attemptId:$studentId:$examId:$timestamp:$clientChecksum"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

data class SubmissionReceipt(
    val receiptId: String,
    val attemptId: String,
    val examId: String,
    val studentId: String,
    val submissionStatus: String,
    val submittedAt: String,
    val receiptHash: String,
    val totalQuestionsAnswered: Int
)
