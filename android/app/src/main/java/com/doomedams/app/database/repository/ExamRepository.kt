package com.doomedams.app.database.repository

import com.doomedams.app.database.AppDatabase
import com.doomedams.app.database.audit.AuditEngine
import com.doomedams.app.database.entity.AnswerKeyEntity
import com.doomedams.app.database.entity.ExamEntity
import com.doomedams.app.database.entity.MarkingSchemeEntity
import com.doomedams.app.database.entity.PaperImportEntity
import com.doomedams.app.database.entity.PaperSectionEntity
import com.doomedams.app.database.entity.QuestionEntity
import com.doomedams.app.database.entity.ValidationIssueEntity
import com.doomedams.app.database.entity.WorkedSolutionEntity
import com.doomedams.app.server.compiler.ParsedDraftPackage

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

    /**
     * Transactionally saves an entire parsed exam package (paper import, exam draft,
     * sections, questions, confidential answer keys, solutions, validation issues, marking scheme)
     * and appends an immutable audit event in a single atomic transaction.
     */
    suspend fun saveDraftPackage(
        exam: ExamEntity,
        paperImport: PaperImportEntity?,
        parsedPackage: ParsedDraftPackage,
        actorId: String,
        actorRole: String
    ): ExamEntity {
        require(exam.durationSeconds > 0) { "Exam duration must be positive" }
        val auditEventId = "aud_draft_create_${System.currentTimeMillis()}"

        return auditEngine.executeAuditedTransaction(
            auditEventId = auditEventId,
            actorId = actorId,
            actorRole = actorRole,
            actionType = "SAVE_EXAM_DRAFT",
            aggregateType = "EXAM",
            aggregateId = exam.examId,
            previousState = null,
            newState = "{\"examId\":\"${exam.examId}\",\"title\":\"${exam.title}\",\"questionsCount\":${parsedPackage.questions.size}}",
            reason = "Exam draft and extracted components saved by administrator"
        ) {
            // 1. Insert Paper Import Record if present
            if (paperImport != null) {
                database.paperImportDao().insertImport(paperImport)
            }

            // 2. Insert Exam Entity (in DRAFT status)
            database.examDao().insertExam(exam)

            // 3. Insert Sections
            if (parsedPackage.sections.isNotEmpty()) {
                database.paperSectionDao().insertSections(parsedPackage.sections)
            }

            // 4. Insert Questions
            if (parsedPackage.questions.isNotEmpty()) {
                database.questionDao().insertQuestions(parsedPackage.questions)
            }

            // 5. Insert Answer Keys
            if (parsedPackage.answerKeys.isNotEmpty()) {
                database.answerKeyDao().insertAnswerKeys(parsedPackage.answerKeys)
            }

            // 6. Insert Worked Solutions
            if (parsedPackage.solutions.isNotEmpty()) {
                database.workedSolutionDao().insertSolutions(parsedPackage.solutions)
            }

            // 7. Insert Validation Issues
            if (parsedPackage.validationIssues.isNotEmpty()) {
                database.validationIssueDao().insertIssues(parsedPackage.validationIssues)
            }

            // 8. Insert Marking Scheme
            database.markingSchemeDao().insertMarkingScheme(parsedPackage.defaultMarkingScheme)

            exam
        }
    }

    /**
     * Transactionally updates questions and sections for an existing draft.
     */
    suspend fun updateDraftQuestions(
        examId: String,
        questions: List<QuestionEntity>,
        actorId: String,
        actorRole: String
    ) {
        val auditEventId = "aud_draft_q_upd_${System.currentTimeMillis()}"
        auditEngine.executeAuditedTransaction(
            auditEventId = auditEventId,
            actorId = actorId,
            actorRole = actorRole,
            actionType = "UPDATE_DRAFT_QUESTIONS",
            aggregateType = "EXAM",
            aggregateId = examId,
            previousState = null,
            newState = "{\"updatedQuestionsCount\":${questions.size}}",
            reason = "Administrator updated draft questions"
        ) {
            database.questionDao().deleteQuestionsByExamId(examId)
            database.questionDao().insertQuestions(questions)
        }
    }

    /**
     * Transactionally updates confidential answer keys and worked solutions.
     */
    suspend fun updateAnswerKeysAndSolutions(
        examId: String,
        answerKeys: List<AnswerKeyEntity>,
        solutions: List<WorkedSolutionEntity>,
        actorId: String,
        actorRole: String
    ) {
        val auditEventId = "aud_draft_ans_upd_${System.currentTimeMillis()}"
        auditEngine.executeAuditedTransaction(
            auditEventId = auditEventId,
            actorId = actorId,
            actorRole = actorRole,
            actionType = "UPDATE_ANSWER_KEYS",
            aggregateType = "EXAM",
            aggregateId = examId,
            previousState = null,
            newState = "{\"verifiedKeysCount\":${answerKeys.count { it.verificationStatus == "VERIFIED" }}}",
            reason = "Administrator verified/updated answer keys and solutions"
        ) {
            database.answerKeyDao().deleteAnswerKeysByExamId(examId)
            database.answerKeyDao().insertAnswerKeys(answerKeys)

            if (solutions.isNotEmpty()) {
                database.workedSolutionDao().deleteSolutionsByExamId(examId)
                database.workedSolutionDao().insertSolutions(solutions)
            }
        }
    }

    /**
     * Publishes an examination draft if publication conditions are satisfied.
     */
    suspend fun publishExam(
        exam: ExamEntity,
        actorId: String,
        actorRole: String
    ): ExamEntity {
        require(exam.status == "DRAFT") { "Can only publish exams in DRAFT status" }

        val questions = database.questionDao().getQuestionsByExamId(exam.examId)
        val answerKeys = database.answerKeyDao().getAnswerKeysByExamId(exam.examId)
        val markingScheme = database.markingSchemeDao().getMarkingSchemeByExamId(exam.examId)
        val validationIssues = database.validationIssueDao().getIssuesByExamId(exam.examId)

        val report = com.doomedams.app.server.compiler.PaperValidationEngine.validateDraft(
            exam = exam,
            questions = questions,
            answerKeys = answerKeys,
            markingScheme = markingScheme,
            validationIssues = validationIssues
        )

        if (!report.isReadyForPublication) {
            throw IllegalStateException(
                "PUBLICATION_REJECTED: Examination draft has blocking validation errors: ${report.blockingErrors.joinToString("; ")}"
            )
        }

        val nowIso = java.time.format.DateTimeFormatter.ISO_INSTANT.format(java.time.Instant.now())
        val publishedExam = exam.copy(
            status = "PUBLISHED",
            updatedAt = nowIso
        )
        val auditEventId = "aud_exam_pub_${System.currentTimeMillis()}"

        return auditEngine.executeAuditedTransaction(
            auditEventId = auditEventId,
            actorId = actorId,
            actorRole = actorRole,
            actionType = "PUBLISH_EXAM",
            aggregateType = "EXAM",
            aggregateId = exam.examId,
            previousState = "{\"status\":\"DRAFT\"}",
            newState = "{\"status\":\"PUBLISHED\",\"publishedAt\":\"$nowIso\"}",
            reason = "Examination draft published by authorized Chief Examination Officer"
        ) {
            database.examDao().updateExam(publishedExam)
            publishedExam
        }
    }
}
