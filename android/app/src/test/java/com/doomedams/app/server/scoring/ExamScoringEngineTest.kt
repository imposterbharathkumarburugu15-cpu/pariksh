package com.doomedams.app.server.scoring

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.doomedams.app.database.AppDatabase
import com.doomedams.app.database.audit.AuditEngine
import com.doomedams.app.database.entity.AnswerKeyEntity
import com.doomedams.app.database.entity.CandidateResponseEntity
import com.doomedams.app.database.entity.ExamAttemptEntity
import com.doomedams.app.database.entity.ExamEntity
import com.doomedams.app.database.entity.MarkingSchemeEntity
import com.doomedams.app.database.entity.PaperSectionEntity
import com.doomedams.app.database.entity.QuestionEntity
import com.doomedams.app.database.entity.UserEntity
import com.doomedams.app.database.repository.AttemptRepository
import com.doomedams.app.server.auth.SessionManager
import com.doomedams.app.server.rbac.Permission
import com.doomedams.app.server.rbac.PermissionService
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ExamScoringEngineTest {

    private lateinit var database: AppDatabase
    private lateinit var auditEngine: AuditEngine
    private lateinit var attemptRepository: AttemptRepository
    private lateinit var sessionManager: SessionManager

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(AppDatabase.DatabaseSecurityCallback)
            .build()

        database.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = ON;")
        auditEngine = AuditEngine(database)
        attemptRepository = AttemptRepository(database, auditEngine)
        sessionManager = SessionManager()
    }

    @After
    @Throws(IOException::class)
    fun teardown() {
        database.close()
    }

    // =========================================================================
    // 1. BASIC SINGLE-SELECT MARKING & SCALED PRECISION
    // =========================================================================

    @Test
    fun testSingleSelectCorrectAnswerAwardsPositiveMarks() {
        val result = ScoringEngine.simulateQuestion(
            questionType = "SINGLE_SELECT",
            expectedAnswer = "[\"B\"]",
            submittedAnswer = "\"B\"",
            correctScaledMark = 400L,
            incorrectScaledMark = -100L
        )

        assertEquals(AnswerClassification.CORRECT, result.classification)
        assertEquals(400L, result.marksAwarded)
        assertEquals(400L, result.maxPossibleMarks)
        assertEquals(ScoringExplanationCode.MATCHED_VERIFIED_ANSWER, result.explanationCode)
    }

    @Test
    fun testSingleSelectIncorrectAnswerAppliesNegativePenalty() {
        val result = ScoringEngine.simulateQuestion(
            questionType = "SINGLE_SELECT",
            expectedAnswer = "[\"B\"]",
            submittedAnswer = "\"A\"",
            correctScaledMark = 400L,
            incorrectScaledMark = -100L,
            allowNegativeMarking = true
        )

        assertEquals(AnswerClassification.INCORRECT, result.classification)
        assertEquals(-100L, result.marksAwarded)
        assertEquals(ScoringExplanationCode.ANSWER_MISMATCH, result.explanationCode)
    }

    @Test
    fun testSingleSelectIncorrectAnswerWhenNegativeMarkingDisabledAwardsZero() {
        val result = ScoringEngine.simulateQuestion(
            questionType = "SINGLE_SELECT",
            expectedAnswer = "[\"B\"]",
            submittedAnswer = "\"C\"",
            correctScaledMark = 400L,
            incorrectScaledMark = -100L,
            allowNegativeMarking = false
        )

        assertEquals(AnswerClassification.INCORRECT, result.classification)
        assertEquals(0L, result.marksAwarded)
    }

    @Test
    fun testSingleSelectUnansweredAwardsConfiguredMarks() {
        val resultDefault = ScoringEngine.simulateQuestion(
            questionType = "SINGLE_SELECT",
            expectedAnswer = "[\"A\"]",
            submittedAnswer = null,
            unansweredScaledMark = 0L
        )
        assertEquals(AnswerClassification.UNANSWERED, resultDefault.classification)
        assertEquals(0L, resultDefault.marksAwarded)
        assertEquals(ScoringExplanationCode.NO_RESPONSE, resultDefault.explanationCode)

        // Explicit non-zero penalty for unanswered
        val resultPenalty = ScoringEngine.simulateQuestion(
            questionType = "SINGLE_SELECT",
            expectedAnswer = "[\"A\"]",
            submittedAnswer = null,
            unansweredScaledMark = -50L
        )
        assertEquals(AnswerClassification.UNANSWERED, resultPenalty.classification)
        assertEquals(-50L, resultPenalty.marksAwarded)
    }

    @Test
    fun testDecimalMarksAndExactIntegerScaling() {
        // 3.33 marks = 333 scaled units, -0.66 marks = -66 scaled units
        val correct = ScoringEngine.simulateQuestion(
            questionType = "SINGLE_SELECT",
            expectedAnswer = "\"C\"",
            submittedAnswer = "\"C\"",
            correctScaledMark = 333L,
            incorrectScaledMark = -66L
        )
        assertEquals(333L, correct.marksAwarded)

        val incorrect = ScoringEngine.simulateQuestion(
            questionType = "SINGLE_SELECT",
            expectedAnswer = "\"C\"",
            submittedAnswer = "\"D\"",
            correctScaledMark = 333L,
            incorrectScaledMark = -66L
        )
        assertEquals(-66L, incorrect.marksAwarded)
    }

    // =========================================================================
    // 2. OVERRIDE PRECEDENCE: QUESTION > SECTION > EXAM
    // =========================================================================

    @Test
    fun testMarkingPrecedenceHierarchy() {
        val exam = ExamEntity(
            examId = "exam_prec_1",
            instituteId = "inst_1",
            title = "Precedence Test",
            status = "PUBLISHED",
            durationSeconds = 3600,
            markingSchemeVersion = 1,
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z"
        )
        val section = PaperSectionEntity(
            sectionId = "sec_1",
            examId = "exam_prec_1",
            title = "Section A",
            sectionOrder = 1,
            sourceReference = null,
            createdAt = "2026-01-01T00:00:00Z"
        )
        val q1 = QuestionEntity(
            questionId = "q_default",
            examId = "exam_prec_1",
            sectionId = "sec_1",
            questionNumber = 1,
            questionType = "SINGLE_SELECT",
            questionText = "Default Rule Q",
            answerOptionsJson = "[]",
            sourcePage = null,
            sourceStartLine = null,
            sourceEndLine = null,
            sourceSnippet = null,
            extractionConfidence = "MANUAL",
            reviewStatus = "VERIFIED",
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z"
        )
        val q2 = QuestionEntity(
            questionId = "q_sec_override",
            examId = "exam_prec_1",
            sectionId = "sec_1",
            questionNumber = 2,
            questionType = "SINGLE_SELECT",
            questionText = "Section Override Q",
            answerOptionsJson = "[]",
            sourcePage = null,
            sourceStartLine = null,
            sourceEndLine = null,
            sourceSnippet = null,
            extractionConfidence = "MANUAL",
            reviewStatus = "VERIFIED",
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z"
        )
        val q3 = QuestionEntity(
            questionId = "q_override",
            examId = "exam_prec_1",
            sectionId = "sec_1",
            questionNumber = 3,
            questionType = "SINGLE_SELECT",
            questionText = "Question Override Q",
            answerOptionsJson = "[]",
            sourcePage = null,
            sourceStartLine = null,
            sourceEndLine = null,
            sourceSnippet = null,
            extractionConfidence = "MANUAL",
            reviewStatus = "VERIFIED",
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z"
        )

        // Scheme with:
        // Exam default: +400, -100
        // Section override: +500, -200
        // Question override on q3: +1000, 0 (explicit zero negative mark)
        val scheme = MarkingSchemeEntity(
            schemeId = "sch_1",
            examId = "exam_prec_1",
            correctScaledMark = 400,
            incorrectScaledMark = 100,
            unansweredScaledMark = 0,
            allowNegativeMarking = true,
            sectionDefaultsJson = """{"sec_1": {"correct": 500, "incorrect": 200, "unanswered": 0}}""",
            questionOverridesJson = """{"q_override": {"correct": 1000, "incorrect": 0, "unanswered": -10}}""",
            version = 1,
            updatedAt = "2026-01-01T00:00:00Z"
        )

        // 1. Question with section should use section override
        val ruleQ2 = ScoringEngine.resolveEffectiveMarkingRule(q2, section, scheme)
        assertEquals("SECTION_DEFAULT", ruleQ2.ruleSource)
        assertEquals(500L, ruleQ2.correctScaledMark)
        assertEquals(-200L, ruleQ2.incorrectScaledMark)

        // 2. Question with specific override takes top priority
        val ruleQ3 = ScoringEngine.resolveEffectiveMarkingRule(q3, section, scheme)
        assertEquals("QUESTION_OVERRIDE", ruleQ3.ruleSource)
        assertEquals(1000L, ruleQ3.correctScaledMark)
        assertEquals(0L, ruleQ3.incorrectScaledMark) // Explicit 0 override honored!
        assertEquals(-10L, ruleQ3.unansweredScaledMark)

        // 3. Question without section should fallback to exam default
        val qNoSec = q1.copy(sectionId = null)
        val ruleDefault = ScoringEngine.resolveEffectiveMarkingRule(qNoSec, null, scheme)
        assertEquals("EXAM_DEFAULT", ruleDefault.ruleSource)
        assertEquals(400L, ruleDefault.correctScaledMark)
        assertEquals(-100L, ruleDefault.incorrectScaledMark)
    }

    // =========================================================================
    // 3. MULTI-SELECT SCORING (EXACT_MATCH & PARTIAL_CREDIT)
    // =========================================================================

    @Test
    fun testMultiSelectExactMatchPolicy() {
        val fullMatch = ScoringEngine.simulateQuestion(
            questionType = "MULTI_SELECT",
            expectedAnswer = "[\"A\", \"B\", \"C\"]",
            submittedAnswer = "[\"A\", \"B\", \"C\"]",
            correctScaledMark = 400L,
            incorrectScaledMark = -100L,
            multiSelectPolicy = MultiSelectPolicy.EXACT_MATCH
        )
        assertEquals(AnswerClassification.CORRECT, fullMatch.classification)
        assertEquals(400L, fullMatch.marksAwarded)

        val partial = ScoringEngine.simulateQuestion(
            questionType = "MULTI_SELECT",
            expectedAnswer = "[\"A\", \"B\", \"C\"]",
            submittedAnswer = "[\"A\", \"B\"]",
            correctScaledMark = 400L,
            incorrectScaledMark = -100L,
            multiSelectPolicy = MultiSelectPolicy.EXACT_MATCH
        )
        assertEquals(AnswerClassification.INCORRECT, partial.classification)
        assertEquals(-100L, partial.marksAwarded)

        val wrong = ScoringEngine.simulateQuestion(
            questionType = "MULTI_SELECT",
            expectedAnswer = "[\"A\", \"B\"]",
            submittedAnswer = "[\"A\", \"B\", \"D\"]",
            correctScaledMark = 400L,
            incorrectScaledMark = -100L,
            multiSelectPolicy = MultiSelectPolicy.EXACT_MATCH
        )
        assertEquals(AnswerClassification.INCORRECT, wrong.classification)
        assertEquals(-100L, wrong.marksAwarded)
    }

    @Test
    fun testMultiSelectPartialCreditPolicy() {
        val partialTwo = ScoringEngine.simulateQuestion(
            questionType = "MULTI_SELECT",
            expectedAnswer = "[\"A\", \"B\", \"C\", \"D\"]",
            submittedAnswer = "[\"A\", \"B\"]",
            correctScaledMark = 400L,
            incorrectScaledMark = -100L,
            multiSelectPolicy = MultiSelectPolicy.PARTIAL_CREDIT
        )
        assertEquals(AnswerClassification.PARTIALLY_CORRECT, partialTwo.classification)
        assertEquals(200L, partialTwo.marksAwarded) // 2 of 4 * 400 = 200

        // Duplicate options in submitted response must NOT award extra credit
        val partialDup = ScoringEngine.simulateQuestion(
            questionType = "MULTI_SELECT",
            expectedAnswer = "[\"A\", \"B\", \"C\", \"D\"]",
            submittedAnswer = "[\"A\", \"A\", \"B\", \"B\"]",
            correctScaledMark = 400L,
            incorrectScaledMark = -100L,
            multiSelectPolicy = MultiSelectPolicy.PARTIAL_CREDIT
        )
        assertEquals(AnswerClassification.PARTIALLY_CORRECT, partialDup.classification)
        assertEquals(200L, partialDup.marksAwarded)

        // Selecting an incorrect option in partial credit incurs penalty
        val incorrectSelection = ScoringEngine.simulateQuestion(
            questionType = "MULTI_SELECT",
            expectedAnswer = "[\"A\", \"B\"]",
            submittedAnswer = "[\"A\", \"C\"]",
            correctScaledMark = 400L,
            incorrectScaledMark = -100L,
            multiSelectPolicy = MultiSelectPolicy.PARTIAL_CREDIT
        )
        assertEquals(AnswerClassification.INCORRECT, incorrectSelection.classification)
        assertEquals(-100L, incorrectSelection.marksAwarded)
    }

    // =========================================================================
    // 4. NUMERICAL SCORING & TOLERANCE
    // =========================================================================

    @Test
    fun testNumericalAnswerWithinAndOutsideTolerance() {
        val exact = ScoringEngine.simulateQuestion(
            questionType = "NUMERICAL",
            expectedAnswer = "42.5",
            submittedAnswer = "42.5",
            numericalTolerance = 0.5,
            correctScaledMark = 400L
        )
        assertEquals(AnswerClassification.CORRECT, exact.classification)
        assertEquals(400L, exact.marksAwarded)
        assertEquals(ScoringExplanationCode.NUMERICAL_TOLERANCE_MATCH, exact.explanationCode)

        val within = ScoringEngine.simulateQuestion(
            questionType = "NUMERICAL",
            expectedAnswer = "42.5",
            submittedAnswer = "42.8",
            numericalTolerance = 0.5,
            correctScaledMark = 400L
        )
        assertEquals(AnswerClassification.CORRECT, within.classification)
        assertEquals(400L, within.marksAwarded)

        val outside = ScoringEngine.simulateQuestion(
            questionType = "NUMERICAL",
            expectedAnswer = "42.5",
            submittedAnswer = "43.2",
            numericalTolerance = 0.5,
            correctScaledMark = 400L,
            incorrectScaledMark = -100L
        )
        assertEquals(AnswerClassification.INCORRECT, outside.classification)
        assertEquals(-100L, outside.marksAwarded)
    }

    @Test
    fun testNumericalLocaleIndependenceAndInvalidFormat() {
        val commaParsed = ScoringEngine.simulateQuestion(
            questionType = "NUMERICAL",
            expectedAnswer = "42.5",
            submittedAnswer = "42,5",
            numericalTolerance = 0.1,
            correctScaledMark = 400L
        )
        assertEquals(AnswerClassification.CORRECT, commaParsed.classification)
        assertEquals(400L, commaParsed.marksAwarded)

        val invalidStr = ScoringEngine.simulateQuestion(
            questionType = "NUMERICAL",
            expectedAnswer = "42.5",
            submittedAnswer = "abc",
            correctScaledMark = 400L,
            incorrectScaledMark = -100L
        )
        assertEquals(AnswerClassification.INVALID_RESPONSE, invalidStr.classification)
        assertEquals(0L, invalidStr.marksAwarded)
        assertEquals(ScoringExplanationCode.INVALID_RESPONSE_FORMAT, invalidStr.explanationCode)
    }

    // =========================================================================
    // 5. SUBJECTIVE & UNKNOWN QUESTION TYPES
    // =========================================================================

    @Test
    fun testSubjectiveQuestionRemainsPendingManualGrading() {
        val subjective = ScoringEngine.simulateQuestion(
            questionType = "SUBJECTIVE",
            expectedAnswer = "Rubric points",
            submittedAnswer = "The mitochondria is the powerhouse of the cell.",
            correctScaledMark = 500L
        )
        assertEquals(AnswerClassification.PENDING_MANUAL_GRADING, subjective.classification)
        assertEquals(0L, subjective.marksAwarded)
        assertEquals(500L, subjective.maxPossibleMarks)
        assertEquals(ScoringExplanationCode.MANUAL_GRADING_REQUIRED, subjective.explanationCode)
    }

    @Test
    fun testUnsupportedQuestionTypeIsUngradable() {
        val unknown = ScoringEngine.simulateQuestion(
            questionType = "UNKNOWN_CUSTOM_TYPE",
            expectedAnswer = "test",
            submittedAnswer = "test",
            correctScaledMark = 300L
        )
        assertEquals(AnswerClassification.UNGRADABLE, unknown.classification)
        assertEquals(0L, unknown.marksAwarded)
        assertEquals(ScoringExplanationCode.UNSUPPORTED_QUESTION_TYPE, unknown.explanationCode)
    }

    // =========================================================================
    // 6. TOTAL RECONCILIATION & PERSISTENCE
    // =========================================================================

    @Test
    fun testExamGradingAndTotalReconciliationAcrossDatabase() = runTest {
        val user = UserEntity(
            userId = "std_full_1",
            instituteId = "inst_full",
            role = "STUDENT",
            displayName = "Student Full",
            accountStatus = "ACTIVE",
            createdAt = "2026-01-01T00:00:00Z"
        )
        database.userDao().insertUser(user)

        val exam = ExamEntity(
            examId = "exam_full_1",
            instituteId = "inst_full",
            title = "Final Physics Examination",
            status = "PUBLISHED",
            durationSeconds = 7200,
            markingSchemeVersion = 1,
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z"
        )
        database.examDao().insertExam(exam)

        val secA = PaperSectionEntity("sec_a", "exam_full_1", "Section A", 1, null, "2026-01-01T00:00:00Z")
        val secB = PaperSectionEntity("sec_b", "exam_full_1", "Section B", 2, null, "2026-01-01T00:00:00Z")
        database.paperSectionDao().insertSections(listOf(secA, secB))

        // 4 questions
        val q1 = QuestionEntity("q_1", "exam_full_1", "sec_a", 1, "SINGLE_SELECT", "Q1", "[]", null, null, null, null, "MANUAL", "VERIFIED", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z")
        val q2 = QuestionEntity("q_2", "exam_full_1", "sec_a", 2, "SINGLE_SELECT", "Q2", "[]", null, null, null, null, "MANUAL", "VERIFIED", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z")
        val q3 = QuestionEntity("q_3", "exam_full_1", "sec_b", 3, "NUMERICAL", "Q3", "[]", null, null, null, null, "MANUAL", "VERIFIED", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z")
        val q4 = QuestionEntity("q_4", "exam_full_1", "sec_b", 4, "MULTI_SELECT", "Q4", "[]", null, null, null, null, "MANUAL", "VERIFIED", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z")
        database.questionDao().insertQuestions(listOf(q1, q2, q3, q4))

        val k1 = AnswerKeyEntity("q_1", "exam_full_1", "[\"A\"]", null, "MANUAL", "VERIFIED", "EXAMINER", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z")
        val k2 = AnswerKeyEntity("q_2", "exam_full_1", "[\"B\"]", null, "MANUAL", "VERIFIED", "EXAMINER", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z")
        val k3 = AnswerKeyEntity("q_3", "exam_full_1", "25.0", 0.5, "MANUAL", "VERIFIED", "EXAMINER", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z")
        val k4 = AnswerKeyEntity("q_4", "exam_full_1", "[\"C\",\"D\"]", null, "MANUAL", "VERIFIED", "EXAMINER", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z")
        database.answerKeyDao().insertAnswerKeys(listOf(k1, k2, k3, k4))

        val scheme = MarkingSchemeEntity(
            schemeId = "sch_full",
            examId = "exam_full_1",
            version = 1,
            correctScaledMark = 400L,
            incorrectScaledMark = 100L,
            unansweredScaledMark = 0L,
            allowNegativeMarking = true,
            sectionDefaultsJson = "{}",
            questionOverridesJson = "{}",
            updatedAt = "2026-01-01T00:00:00Z"
        )
        database.markingSchemeDao().insertMarkingScheme(scheme)

        val attempt = ExamAttemptEntity(
            attemptId = "att_full_1",
            examId = "exam_full_1",
            studentId = "std_full_1",
            attemptStatus = "SUBMITTED",
            resultStatus = "UNGRADED",
            startedAt = "2026-01-01T10:00:00Z",
            submittedAt = "2026-01-01T11:00:00Z",
            deadlineMetadata = "{}",
            createdAt = "2026-01-01T10:00:00Z",
            updatedAt = "2026-01-01T11:00:00Z"
        )
        database.examAttemptDao().insertAttempt(attempt)

        val resp1 = CandidateResponseEntity("r_1", "att_full_1", "q_1", "\"A\"", 1, "2026-01-01T10:10:00Z")
        val resp2 = CandidateResponseEntity("r_2", "att_full_1", "q_2", "\"C\"", 1, "2026-01-01T10:15:00Z")
        val resp3 = CandidateResponseEntity("r_3", "att_full_1", "q_3", "25.2", 1, "2026-01-01T10:20:00Z")
        database.candidateResponseDao().upsertResponse(resp1)
        database.candidateResponseDao().upsertResponse(resp2)
        database.candidateResponseDao().upsertResponse(resp3)

        // Grade Attempt
        val result = attemptRepository.gradeAttempt("att_full_1", "admin_1", "EXAMINATION_ADMIN")

        assertNotNull(result)
        assertEquals(1, result.version)
        assertEquals("GRADED", result.resultStatus)

        // Score Reconciliation
        assertEquals(700L, result.totalScaledScore)
        assertEquals(1600L, result.maxPossibleScaledScore)
        assertEquals(2, result.correctCount)
        assertEquals(1, result.incorrectCount)
        assertEquals(1, result.unansweredCount)

        // Verify Question Grading Records persisted in DB
        val records = attemptRepository.getGradingRecords(result.resultId)
        assertEquals(4, records.size)
        val q1Rec = records.first { it.questionId == "q_1" }
        assertEquals(400L, q1Rec.marksAwarded)
        assertEquals("CORRECT", q1Rec.answerClassification)

        val q2Rec = records.first { it.questionId == "q_2" }
        assertEquals(-100L, q2Rec.marksAwarded)
        assertEquals("INCORRECT", q2Rec.answerClassification)

        // Rescoring increments version
        val rescored = attemptRepository.gradeAttempt("att_full_1", "admin_1", "EXAMINATION_ADMIN")
        assertEquals(2, rescored.version)
        assertEquals(result.resultHash, rescored.resultHash)
    }

    // =========================================================================
    // 7. RESULT HOLD SAFEGUARDS
    // =========================================================================

    @Test
    fun testResultHoldBlocksPublicationWhilePreservingMarks() = runTest {
        val user = UserEntity("std_hold_1", "inst_h", "STUDENT", "Student Hold", "ACTIVE", "2026-01-01T00:00:00Z")
        database.userDao().insertUser(user)

        val exam = ExamEntity("exam_h", "inst_h", "Exam H", "PUBLISHED", 3600, 1, "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z")
        database.examDao().insertExam(exam)

        val q = QuestionEntity("q_h", "exam_h", null, 1, "SINGLE_SELECT", "Q", "[]", null, null, null, null, "MANUAL", "VERIFIED", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z")
        database.questionDao().insertQuestions(listOf(q))

        val k = AnswerKeyEntity("q_h", "exam_h", "[\"A\"]", null, "MANUAL", "VERIFIED", "ADMIN", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z")
        database.answerKeyDao().insertAnswerKeys(listOf(k))

        val scheme = MarkingSchemeEntity(
            schemeId = "sch_h",
            examId = "exam_h",
            version = 1,
            correctScaledMark = 400L,
            incorrectScaledMark = 100L,
            unansweredScaledMark = 0L,
            allowNegativeMarking = true,
            sectionDefaultsJson = "{}",
            questionOverridesJson = "{}",
            updatedAt = "2026-01-01T00:00:00Z"
        )
        database.markingSchemeDao().insertMarkingScheme(scheme)

        val attempt = ExamAttemptEntity("att_h", "exam_h", "std_hold_1", "SUBMITTED", "UNGRADED", "2026-01-01T10:00:00Z", "2026-01-01T11:00:00Z", "{}", "2026-01-01T10:00:00Z", "2026-01-01T11:00:00Z")
        database.examAttemptDao().insertAttempt(attempt)

        val resp = CandidateResponseEntity("r_h", "att_h", "q_h", "\"A\"", 1, "2026-01-01T10:05:00Z")
        database.candidateResponseDao().upsertResponse(resp)

        val initialResult = attemptRepository.gradeAttempt("att_h", "admin", "EXAMINATION_ADMIN")
        assertEquals(400L, initialResult.totalScaledScore)
        assertEquals("GRADED", initialResult.resultStatus)

        // Apply Administrative Hold
        attemptRepository.applyResultHold(
            attemptId = "att_h",
            reason = "Suspected app switch incident",
            auditEventId = "aud_hold_1",
            reviewerId = "rev_1",
            reviewerRole = "INCIDENT_REVIEWER"
        )

        // Verify hold preserves earned marks but updates resultStatus
        val heldResult = attemptRepository.getGradingResult("att_h")
        assertNotNull(heldResult)
        assertEquals("HELD_PENDING_REVIEW", heldResult!!.resultStatus)
        assertEquals(400L, heldResult.totalScaledScore)

        // Publication must be blocked
        try {
            attemptRepository.publishResult("att_h", "admin", "EXAMINATION_ADMIN")
            fail("Publication must be blocked when resultStatus is HELD_PENDING_REVIEW")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("active administrative hold"))
        }
    }

    // =========================================================================
    // 8. SECURITY & ROLE-BASED ACCESS CONTROL
    // =========================================================================

    @Test
    fun testStudentCannotGradeOrReadUnpublishedResults() {
        val studentSession = com.doomedams.app.server.auth.UserSession(
            sessionId = "sess_std",
            token = "tok_std",
            userId = "std_sec_1",
            displayName = "Student Sec",
            role = "STUDENT",
            instituteScope = "inst_sec",
            examinationScope = null,
            createdAt = java.time.Instant.now(),
            expiresAt = java.time.Instant.now().plusSeconds(3600)
        )

        assertFalse(PermissionService.hasPermission("STUDENT", Permission.RESULT_GRADE))

        val authResult = PermissionService.authorize(
            session = studentSession,
            permission = Permission.RESULT_READ_OWN,
            resourceStudentId = "other_student_id"
        )
        assertTrue(authResult is com.doomedams.app.server.rbac.AuthorizationResult.Denied)

        assertTrue(PermissionService.hasPermission("EXAMINATION_ADMIN", Permission.RESULT_GRADE))
        assertTrue(PermissionService.hasPermission("EXAMINATION_ADMIN", Permission.RESULT_PUBLISH))
    }
}
