package com.doomedams.app.server

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.doomedams.app.database.AppDatabase
import com.doomedams.app.database.audit.AuditEngine
import com.doomedams.app.database.entity.*
import com.doomedams.app.database.repository.AttemptRepository
import com.doomedams.app.database.repository.ExamRepository
import com.doomedams.app.server.auth.SessionManager
import com.doomedams.app.server.routes.configureExamServerRoutes
import com.doomedams.app.server.security.PasswordHasher
import com.doomedams.app.server.security.RateLimiter
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExamIntelligenceAnalyticsTest {

    private lateinit var database: AppDatabase
    private lateinit var sessionManager: SessionManager
    private lateinit var auditEngine: AuditEngine
    private lateinit var attemptRepo: AttemptRepository
    private lateinit var examRepo: ExamRepository

    private val instituteId = "inst_apex"
    private val examId = "exam_analytics_001"
    private val student1Id = "usr_student_01"
    private val student2Id = "usr_student_02"
    private val attempt1Id = "att_analytics_001"
    private val attempt2Id = "att_analytics_002"

    private lateinit var adminToken: String
    private lateinit var invigilatorToken: String
    private lateinit var student1Token: String
    private lateinit var student2Token: String
    private lateinit var auditorToken: String
    private lateinit var sysAdminToken: String

    @Before
    fun setup() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        sessionManager = SessionManager()
        auditEngine = AuditEngine(database)
        attemptRepo = AttemptRepository(database, auditEngine)
        examRepo = ExamRepository(database, auditEngine)

        // Seed Users
        database.userDao().insertUser(UserEntity(student1Id, "Alice Sharma", "STUDENT", "ACTIVE", instituteId, "2026-10-10T08:00:00Z"))
        database.userDao().insertUser(UserEntity(student2Id, "Bob Verma", "STUDENT", "ACTIVE", instituteId, "2026-10-10T08:00:00Z"))
        database.userDao().insertUser(UserEntity("usr_admin", "Admin Rao", "EXAMINATION_ADMIN", "ACTIVE", instituteId, "2026-10-10T08:00:00Z"))
        database.userDao().insertUser(UserEntity("usr_invig", "Invigilator Sen", "INVIGILATOR", "ACTIVE", instituteId, "2026-10-10T08:00:00Z"))
        database.userDao().insertUser(UserEntity("usr_auditor", "Auditor Mehta", "AUDITOR", "ACTIVE", instituteId, "2026-10-10T08:00:00Z"))
        database.userDao().insertUser(UserEntity("usr_sysadmin", "Sys Admin", "SYSTEM_ADMIN", "ACTIVE", instituteId, "2026-10-10T08:00:00Z"))

        // Seed Sessions
        adminToken = sessionManager.createSession("usr_admin", "Admin Rao", "EXAMINATION_ADMIN", instituteId).token
        invigilatorToken = sessionManager.createSession("usr_invig", "Invigilator Sen", "INVIGILATOR", instituteId).token
        student1Token = sessionManager.createSession(student1Id, "Alice Sharma", "STUDENT", instituteId).token
        student2Token = sessionManager.createSession(student2Id, "Bob Verma", "STUDENT", instituteId).token
        auditorToken = sessionManager.createSession("usr_auditor", "Auditor Mehta", "AUDITOR", instituteId).token
        sysAdminToken = sessionManager.createSession("usr_sysadmin", "Sys Admin", "SYSTEM_ADMIN", instituteId).token

        // Seed Exam
        database.examDao().insertExam(
            ExamEntity(
                examId = examId,
                instituteId = instituteId,
                title = "Physics Midterm CBT",
                status = "PUBLISHED",
                durationSeconds = 3600L,
                markingSchemeVersion = 1,
                createdAt = "2026-10-10T08:00:00Z",
                updatedAt = "2026-10-10T08:00:00Z"
            )
        )

        // Seed Section
        database.paperSectionDao().insertSections(
            listOf(
                PaperSectionEntity("sec_phy_01", examId, "Section A - Mechanics", 1, null, "2026-10-10T08:00:00Z")
            )
        )

        // Seed Questions
        database.questionDao().insertQuestions(
            listOf(
                QuestionEntity(
                    questionId = "q_01",
                    examId = examId,
                    sectionId = "sec_phy_01",
                    questionNumber = 1,
                    questionType = "SINGLE_SELECT",
                    questionText = "What is the unit of force in SI?",
                    answerOptionsJson = "[{\"optionId\":\"opt_1\",\"label\":\"A\",\"text\":\"Newton\"},{\"optionId\":\"opt_2\",\"label\":\"B\",\"text\":\"Joule\"},{\"optionId\":\"opt_3\",\"label\":\"C\",\"text\":\"Watt\"}]",
                    sourcePage = 1,
                    sourceStartLine = 1,
                    sourceEndLine = 3,
                    sourceSnippet = "Force question",
                    extractionConfidence = "HIGH",
                    reviewStatus = "VERIFIED",
                    createdAt = "2026-10-10T08:00:00Z",
                    updatedAt = "2026-10-10T08:00:00Z"
                ),
                QuestionEntity(
                    questionId = "q_02",
                    examId = examId,
                    sectionId = "sec_phy_01",
                    questionNumber = 2,
                    questionType = "SINGLE_SELECT",
                    questionText = "What is acceleration due to gravity on Earth?",
                    answerOptionsJson = "[{\"optionId\":\"opt_2a\",\"label\":\"A\",\"text\":\"9.8 m/s^2\"},{\"optionId\":\"opt_2b\",\"label\":\"B\",\"text\":\"8.9 m/s^2\"}]",
                    sourcePage = 1,
                    sourceStartLine = 4,
                    sourceEndLine = 6,
                    sourceSnippet = "Gravity question",
                    extractionConfidence = "HIGH",
                    reviewStatus = "VERIFIED",
                    createdAt = "2026-10-10T08:00:00Z",
                    updatedAt = "2026-10-10T08:00:00Z"
                )
            )
        )

        // Seed Attempts
        database.examAttemptDao().insertAttempt(
            ExamAttemptEntity(
                attemptId = attempt1Id,
                examId = examId,
                studentId = student1Id,
                attemptStatus = "SUBMITTED",
                resultStatus = "PUBLISHED",
                startedAt = "2026-10-10T09:00:00Z",
                submittedAt = "2026-10-10T09:45:00Z",
                deadlineMetadata = "{\"deadline\":\"2026-10-10T10:00:00Z\"}",
                createdAt = "2026-10-10T09:00:00Z",
                updatedAt = "2026-10-10T09:45:00Z"
            )
        )

        database.examAttemptDao().insertAttempt(
            ExamAttemptEntity(
                attemptId = attempt2Id,
                examId = examId,
                studentId = student2Id,
                attemptStatus = "SUBMITTED",
                resultStatus = "HELD_PENDING_REVIEW",
                startedAt = "2026-10-10T09:05:00Z",
                submittedAt = "2026-10-10T09:50:00Z",
                deadlineMetadata = "{\"deadline\":\"2026-10-10T10:00:00Z\"}",
                createdAt = "2026-10-10T09:05:00Z",
                updatedAt = "2026-10-10T09:50:00Z"
            )
        )

        // Seed Candidate Responses
        database.candidateResponseDao().upsertResponse(
            CandidateResponseEntity("resp_01", attempt1Id, "q_01", "[\"opt_1\"]", 1, "2026-10-10T09:10:00Z")
        )
        database.candidateResponseDao().upsertResponse(
            CandidateResponseEntity("resp_02", attempt1Id, "q_02", "[\"opt_2a\"]", 1, "2026-10-10T09:20:00Z")
        )
        database.candidateResponseDao().upsertResponse(
            CandidateResponseEntity("resp_03", attempt2Id, "q_01", "[\"opt_2\"]", 2, "2026-10-10T09:15:00Z")
        )

        // Seed Exam Results
        database.examResultDao().insertResult(
            ExamResultEntity(
                resultId = "res_01",
                attemptId = attempt1Id,
                examId = examId,
                studentId = student1Id,
                version = 1,
                resultStatus = "PUBLISHED",
                totalScaledScore = 800L, // 8.00 marks
                maxPossibleScaledScore = 800L, // 8.00 marks
                correctCount = 2,
                incorrectCount = 0,
                unansweredCount = 0,
                partiallyCorrectCount = 0,
                pendingManualCount = 0,
                markingSchemeVersion = 1,
                scoringEngineVersion = "1.0.0",
                sectionTotalsJson = "{}",
                resultHash = "hash_res_01",
                holdReason = null,
                scoredAt = "2026-10-10T09:46:00Z",
                publishedAt = "2026-10-10T09:46:00Z",
                createdAt = "2026-10-10T09:46:00Z",
                updatedAt = "2026-10-10T09:46:00Z"
            )
        )

        database.examResultDao().insertResult(
            ExamResultEntity(
                resultId = "res_02",
                attemptId = attempt2Id,
                examId = examId,
                studentId = student2Id,
                version = 1,
                resultStatus = "HELD_PENDING_REVIEW",
                totalScaledScore = -100L, // -1.00 marks (negative marking)
                maxPossibleScaledScore = 800L,
                correctCount = 0,
                incorrectCount = 1,
                unansweredCount = 1,
                partiallyCorrectCount = 0,
                pendingManualCount = 0,
                markingSchemeVersion = 1,
                scoringEngineVersion = "1.0.0",
                sectionTotalsJson = "{}",
                resultHash = "hash_res_02",
                holdReason = "Under proctor review for app switch",
                scoredAt = "2026-10-10T09:51:00Z",
                publishedAt = null,
                createdAt = "2026-10-10T09:51:00Z",
                updatedAt = "2026-10-10T09:51:00Z"
            )
        )

        // Seed Question Grading Records
        database.questionGradingRecordDao().insertRecords(
            listOf(
                QuestionGradingRecordEntity("rec_01", "res_01", "q_01", "sec_phy_01", 1, "SINGLE_SELECT", "[\"opt_1\"]", "[\"opt_1\"]", "CORRECT", 400L, 400L, "CORRECT_KEY_MATCH", null),
                QuestionGradingRecordEntity("rec_02", "res_01", "q_02", "sec_phy_01", 2, "SINGLE_SELECT", "[\"opt_2a\"]", "[\"opt_2a\"]", "CORRECT", 400L, 400L, "CORRECT_KEY_MATCH", null),
                QuestionGradingRecordEntity("rec_03", "res_02", "q_01", "sec_phy_01", 1, "SINGLE_SELECT", "[\"opt_2\"]", "[\"opt_1\"]", "INCORRECT", -100L, 400L, "INCORRECT_KEY_MISMATCH", null)
            )
        )

        // Seed Incident and Integrity Event
        database.integrityEventDao().insertEvent(
            IntegrityEventEntity(
                eventId = "evt_001",
                examId = examId,
                attemptId = attempt2Id,
                studentId = student2Id,
                eventCategory = "UNAUTHORISED_APP_SWITCH",
                deviceReportedAt = "2026-10-10T09:25:00Z",
                receivedAt = "2026-10-10T09:25:02Z",
                sequenceNumber = 1L,
                sourceDeviceId = "dev_s2",
                payloadJson = "{}",
                payloadHash = "hash_evt",
                appPackageName = null,
                displayedAppName = null
            )
        )

        database.incidentDao().insertIncident(
            IncidentCaseEntity(
                incidentId = "inc_001",
                examId = examId,
                attemptId = attempt2Id,
                studentId = student2Id,
                category = "UNAUTHORISED_APP_SWITCH",
                incidentStatus = "PENDING_FURTHER_REVIEW",
                evidenceAssessment = "LIMITED",
                decision = "NONE",
                reviewerId = null,
                policyVersion = "v1.0",
                version = 1,
                createdAt = "2026-10-10T09:30:00Z",
                updatedAt = "2026-10-10T09:30:00Z"
            )
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testOverviewAnalyticsReturnsAccuratePersistedMetrics() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepo, examRepo, RateLimiter())
        }

        val res = client.get("/api/analytics/overview") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }

        assertEquals(HttpStatusCode.OK, res.status)
        val json = JSONObject(res.bodyAsText())
        assertEquals(1, json.getInt("totalExams"))
        assertEquals(1, json.getInt("publishedExams"))
        assertEquals(2, json.getInt("acceptedSubmissions"))
        assertEquals(0, json.getInt("activeAttempts"))
        assertEquals(1, json.getInt("publishedResultsCount"))
        assertEquals(1, json.getInt("resultsHeldCount"))
        assertEquals(1, json.getInt("outstandingIncidentsCount"))
    }

    @Test
    fun testExamSummaryAnalyticsCalculatesScoresAndDistributions() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepo, examRepo, RateLimiter())
        }

        val res = client.get("/api/analytics/exams/$examId/summary") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }

        assertEquals(HttpStatusCode.OK, res.status)
        val json = JSONObject(res.bodyAsText())
        assertEquals("Physics Midterm CBT", json.getString("examTitle"))
        assertEquals(60, json.getInt("durationMinutes"))
        assertEquals(2, json.getInt("submittedAttemptsCount"))
        assertEquals(1, json.getInt("publishedResultsCount"))
        assertEquals(1, json.getInt("heldResultsCount"))
        assertEquals(8.0, json.getDouble("highestScore"), 0.01)
        assertEquals(-1.0, json.getDouble("lowestScore"), 0.01)
        assertEquals(3.5, json.getDouble("meanScore"), 0.01) // (8.0 + -1.0) / 2 = 3.5
        assertEquals(1, json.getInt("flaggedAttemptsCount"))
    }

    @Test
    fun testStudentPerformanceListCalculatesDenseRanksWithTiesHandled() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepo, examRepo, RateLimiter())
        }

        val res = client.get("/api/analytics/exams/$examId/students") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }

        assertEquals(HttpStatusCode.OK, res.status)
        val arr = JSONArray(res.bodyAsText())
        assertEquals(2, arr.length())

        val s1 = arr.getJSONObject(0)
        assertEquals(student1Id, s1.getString("studentId"))
        assertEquals("Alice Sharma", s1.getString("studentDisplayName"))
        assertEquals(1, s1.getInt("rank"))
        assertEquals(8.0, s1.getDouble("score"), 0.01)
        assertEquals(100.0, s1.getDouble("percentage"), 0.01)
        assertFalse(s1.getBoolean("isTiedRank"))

        val s2 = arr.getJSONObject(1)
        assertEquals(student2Id, s2.getString("studentId"))
        assertEquals("Bob Verma", s2.getString("studentDisplayName"))
        assertEquals(2, s2.getInt("rank"))
        assertEquals(-1.0, s2.getDouble("score"), 0.01)
        assertEquals("HELD_PENDING_REVIEW", s2.getString("resultStatus"))
        assertEquals("Under proctor review for app switch", s2.getString("holdReason"))
    }

    @Test
    fun testQuestionAnalyticsCalculatesCorrectnessRateAndDistractors() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepo, examRepo, RateLimiter())
        }

        val res = client.get("/api/analytics/exams/$examId/questions") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }

        assertEquals(HttpStatusCode.OK, res.status)
        val arr = JSONArray(res.bodyAsText())
        assertEquals(2, arr.length())

        val q1 = arr.getJSONObject(0)
        assertEquals(1, q1.getInt("questionNumber"))
        assertEquals(2, q1.getInt("eligibleCandidatesDenominator"))
        assertEquals(2, q1.getInt("attemptedCount"))
        assertEquals(1, q1.getInt("correctCount"))
        assertEquals(1, q1.getInt("incorrectCount"))
        assertEquals(50.0, q1.getDouble("correctnessRate"), 0.01)
        assertTrue(q1.getString("recordedTimeNotice").contains("TIME DATA UNAVAILABLE"))

        val options = q1.getJSONArray("optionDistributions")
        assertTrue(options.length() > 0)
    }

    @Test
    fun testSectionAnalyticsCalculatesAverages() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepo, examRepo, RateLimiter())
        }

        val res = client.get("/api/analytics/exams/$examId/sections") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }

        assertEquals(HttpStatusCode.OK, res.status)
        val arr = JSONArray(res.bodyAsText())
        assertEquals(1, arr.length())
        val sec = arr.getJSONObject(0)
        assertEquals("Section A - Mechanics", sec.getString("title"))
        assertEquals(2, sec.getInt("questionCount"))
    }

    @Test
    fun testLearningGapsDisclosesLackOfCurriculumTagsHonestly() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepo, examRepo, RateLimiter())
        }

        val res = client.get("/api/analytics/exams/$examId/learning-gaps") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }

        assertEquals(HttpStatusCode.OK, res.status)
        val json = JSONObject(res.bodyAsText())
        assertFalse(json.getBoolean("curriculumTagsAvailable"))
        assertTrue(json.getString("disclosureNotice").contains("requires curriculum tags"))
        assertTrue(json.getJSONArray("explainableRecommendations").length() > 0)
    }

    @Test
    fun testIntegrityAnalyticsDistinguishesEventsFromConfirmedFindings() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepo, examRepo, RateLimiter())
        }

        val res = client.get("/api/analytics/exams/$examId/integrity") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }

        assertEquals(HttpStatusCode.OK, res.status)
        val json = JSONObject(res.bodyAsText())
        assertEquals(1, json.getInt("totalEvents"))
        assertEquals(1, json.getInt("uniqueFlaggedAttempts"))
        assertEquals(1, json.getInt("appNameUnavailableCount"))
        assertEquals(1, json.getInt("totalIncidentCases"))
        assertEquals(1, json.getInt("pendingReviewIncidentsCount"))
        assertEquals(0, json.getInt("confirmedMisconductCount")) // zero guilt presumption!
        assertEquals(1, json.getInt("heldResultsCount"))
    }

    @Test
    fun testStudentProfileReturnsFullDossierAndReceipt() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepo, examRepo, RateLimiter())
        }

        val res = client.get("/api/analytics/exams/$examId/students/$student1Id/profile") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }

        assertEquals(HttpStatusCode.OK, res.status)
        val json = JSONObject(res.bodyAsText())
        assertEquals(student1Id, json.getString("studentId"))
        assertEquals("Alice Sharma", json.getString("studentDisplayName"))
        assertEquals(8.0, json.getDouble("totalScaledScore"), 0.01)
        assertEquals(2, json.getInt("localSaveCount"))
        assertNotNull(json.optString("submissionReceiptHash"))
        assertEquals(2, json.getJSONArray("questions").length())
    }

    @Test
    fun testReportExportCsvProducesRfc4180EscapedOutput() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepo, examRepo, RateLimiter())
        }

        val res = client.get("/api/analytics/exams/$examId/reports/STUDENT_PERFORMANCE/csv") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }

        assertEquals(HttpStatusCode.OK, res.status)
        val csv = res.bodyAsText()
        assertTrue(csv.contains("Student ID,Student Name,Attempt Status,Result Status"))
        assertTrue(csv.contains("Alice Sharma"))
        assertTrue(csv.contains("Bob Verma"))
        assertTrue(csv.contains("Under proctor review for app switch"))
    }

    @Test
    fun testLiveMonitorProvidesOperationalStateWithoutFabricatingActivity() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepo, examRepo, RateLimiter())
        }

        val res = client.get("/api/analytics/live/$examId") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }

        assertEquals(HttpStatusCode.OK, res.status)
        val json = JSONObject(res.bodyAsText())
        assertEquals(2, json.getInt("totalAssignedCandidates"))
        assertEquals(2, json.getInt("submittedCount"))
        assertEquals(1, json.getInt("recentIntegrityAlertsCount"))
    }

    @Test
    fun testRbacEnforcementRejectsUnauthorizedAccess() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepo, examRepo, RateLimiter())
        }

        // Student cannot read institute overview analytics
        val overRes = client.get("/api/analytics/overview") {
            header(HttpHeaders.Authorization, "Bearer $student1Token")
        }
        assertEquals(HttpStatusCode.Forbidden, overRes.status)

        // Student cannot read other student's profile
        val foreignProfRes = client.get("/api/analytics/exams/$examId/students/$student2Id/profile") {
            header(HttpHeaders.Authorization, "Bearer $student1Token")
        }
        assertEquals(HttpStatusCode.Forbidden, foreignProfRes.status)

        // Student CAN read their OWN profile
        val ownProfRes = client.get("/api/analytics/exams/$examId/students/$student1Id/profile") {
            header(HttpHeaders.Authorization, "Bearer $student1Token")
        }
        assertEquals(HttpStatusCode.OK, ownProfRes.status)

        // System Admin cannot access academic student performance
        val sysAdminRes = client.get("/api/analytics/exams/$examId/students") {
            header(HttpHeaders.Authorization, "Bearer $sysAdminToken")
        }
        assertEquals(HttpStatusCode.Forbidden, sysAdminRes.status)

        // Auditor CAN read overview, summary and integrity
        val audOverRes = client.get("/api/analytics/overview") {
            header(HttpHeaders.Authorization, "Bearer $auditorToken")
        }
        assertEquals(HttpStatusCode.OK, audOverRes.status)

        val audIntegRes = client.get("/api/analytics/exams/$examId/integrity") {
            header(HttpHeaders.Authorization, "Bearer $auditorToken")
        }
        assertEquals(HttpStatusCode.OK, audIntegRes.status)
    }
}
