package com.doomedams.app.server

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.doomedams.app.database.AppDatabase
import com.doomedams.app.database.audit.AuditEngine
import com.doomedams.app.database.entity.CandidateResponseEntity
import com.doomedams.app.database.entity.ExamAttemptEntity
import com.doomedams.app.database.entity.ExamEntity
import com.doomedams.app.database.entity.UserCredentialEntity
import com.doomedams.app.database.entity.UserEntity
import com.doomedams.app.database.repository.AttemptRepository
import com.doomedams.app.database.repository.ExamRepository
import com.doomedams.app.server.auth.SessionManager
import com.doomedams.app.server.routes.configureExamServerRoutes
import com.doomedams.app.server.security.PasswordHasher
import com.doomedams.app.server.security.RateLimiter
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ExaminationLockdownIntegrityTest {

    private lateinit var database: AppDatabase
    private lateinit var sessionManager: SessionManager
    private lateinit var auditEngine: AuditEngine
    private lateinit var attemptRepo: AttemptRepository
    private lateinit var examRepo: ExamRepository

    private val instituteId = "inst_lockdown_test"
    private val studentId = "usr_student_step8"
    private val examinerId = "usr_examiner_step8"
    private val examId = "exam_step8_lockdown"
    private val attemptId = "att_step8_lockdown_001"

    private var studentToken = ""
    private var examinerToken = ""

    @Before
    fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        sessionManager = SessionManager()
        auditEngine = AuditEngine(database)
        attemptRepo = AttemptRepository(database, auditEngine)
        examRepo = ExamRepository(database, auditEngine)

        // Seed Users
        val (pwHash, salt) = PasswordHasher.hashPassword("Password@123")
        database.userDao().insertUser(
            UserEntity(
                userId = studentId,
                displayName = "Student Step8",
                role = "STUDENT",
                accountStatus = "ACTIVE",
                instituteId = instituteId,
                createdAt = "2026-10-10T00:00:00Z"
            )
        )
        database.userCredentialDao().insertCredential(
            UserCredentialEntity(userId = studentId, passwordHash = pwHash, salt = salt, updatedAt = "2026-10-10T00:00:00Z")
        )

        database.userDao().insertUser(
            UserEntity(
                userId = examinerId,
                displayName = "Examiner Step8",
                role = "INVIGILATOR",
                accountStatus = "ACTIVE",
                instituteId = instituteId,
                createdAt = "2026-10-10T00:00:00Z"
            )
        )
        database.userCredentialDao().insertCredential(
            UserCredentialEntity(userId = examinerId, passwordHash = pwHash, salt = salt, updatedAt = "2026-10-10T00:00:00Z")
        )

        // Seed Exam
        database.examDao().insertExam(
            ExamEntity(
                examId = examId,
                title = "Strict Integrity Examination 2026",
                durationSeconds = 3600L,
                instituteId = instituteId,
                status = "PUBLISHED",
                markingSchemeVersion = 1,
                createdAt = "2026-10-10T00:00:00Z",
                updatedAt = "2026-10-10T00:00:00Z"
            )
        )

        // Seed Active Attempt
        database.examAttemptDao().insertAttempt(
            ExamAttemptEntity(
                attemptId = attemptId,
                examId = examId,
                studentId = studentId,
                attemptStatus = "IN_PROGRESS",
                resultStatus = "PENDING_GRADING",
                startedAt = "2026-10-10T10:00:00Z",
                submittedAt = null,
                deadlineMetadata = "{}",
                createdAt = "2026-10-10T10:00:00Z",
                updatedAt = "2026-10-10T10:00:00Z"
            )
        )

        // Seed Working Response to test preservation during cancellation
        database.candidateResponseDao().upsertResponse(
            CandidateResponseEntity(
                responseId = "resp_preservation_001",
                attemptId = attemptId,
                questionId = "q_test_01",
                responsePayload = "B",
                revisionSequence = 1,
                savedAt = "2026-10-10T10:05:00Z"
            )
        )

        // Sessions
        studentToken = sessionManager.createSession(studentId, "Student Step8", "STUDENT", instituteId).token
        examinerToken = sessionManager.createSession(examinerId, "Examiner Step8", "INVIGILATOR", instituteId).token
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testIntegrityEventIngestionIdempotency() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepo, examRepo, RateLimiter())
        }

        val eventPayload = JSONObject().apply {
            put("eventId", "integ_test_idemp_01")
            put("eventCategory", "EXAM_APP_BACKGROUND")
            put("deviceReportedAt", "2026-10-10T10:10:00Z")
            put("sequenceNumber", 1)
            put("sourceDeviceId", "device_pixel")
            put("displayedAppName", "APP_NAME_UNAVAILABLE")
        }

        // First Ingestion
        val res1 = client.post("/api/attempts/$attemptId/integrity-events") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(eventPayload.toString())
        }
        assertEquals(HttpStatusCode.OK, res1.status)
        val data1 = JSONObject(res1.bodyAsText())
        val result1 = data1.getJSONArray("results").getJSONObject(0)
        assertEquals("RECEIVED", result1.getString("ingestionStatus"))

        // Duplicate replay
        val res2 = client.post("/api/attempts/$attemptId/integrity-events") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(eventPayload.toString())
        }
        assertEquals(HttpStatusCode.OK, res2.status)
        val data2 = JSONObject(res2.bodyAsText())
        val result2 = data2.getJSONArray("results").getJSONObject(0)
        assertEquals("ALREADY_INGESTED", result2.getString("ingestionStatus"))

        // Database should only have 1 row
        val storedEvents = database.integrityEventDao().getEventsByAttempt(attemptId)
        assertEquals(1, storedEvents.size)
    }

    @Test
    fun testHighConfidenceProhibitedAppTriggersAutomaticCancellation() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepo, examRepo, RateLimiter())
        }

        val violationPayload = JSONObject().apply {
            put("eventId", "integ_viol_cancel_01")
            put("eventCategory", "PROHIBITED_APP_FOREGROUND")
            put("appPackageName", "com.android.chrome")
            put("displayedAppName", "Google Chrome")
            put("deviceReportedAt", "2026-10-10T10:12:00Z")
            put("sequenceNumber", 2)
            put("sourceDeviceId", "device_pixel")
        }

        val res = client.post("/api/attempts/$attemptId/integrity-events") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(violationPayload.toString())
        }
        assertEquals(HttpStatusCode.OK, res.status)

        val data = JSONObject(res.bodyAsText())
        val result = data.getJSONArray("results").getJSONObject(0)
        assertEquals("CANCEL_ATTEMPT", result.getString("actionTaken"))
        assertEquals("CANCELLED", result.getString("attemptStatus"))

        // Verify database attempt state is cancelled
        val updatedAttempt = database.examAttemptDao().getAttemptById(attemptId)
        assertNotNull(updatedAttempt)
        assertEquals("CANCELLED", updatedAttempt!!.attemptStatus)
        assertEquals("WITHHELD", updatedAttempt.resultStatus)

        // Verify an incident record was persisted
        val incidents = database.incidentDao().getIncidentsByAttempt(attemptId)
        assertTrue(incidents.isNotEmpty())
        assertEquals("CONFIRMED", incidents[0].incidentStatus)

        // Verify policy action record was persisted
        val policyActions = database.incidentDao().getPolicyActionsForIncident(incidents[0].incidentId)
        assertTrue(policyActions.isNotEmpty())
        assertEquals("CANCEL_ATTEMPT", policyActions[0].actionType)
        assertEquals("APPLIED", policyActions[0].executionStatus)

        // Verify working answers are completely preserved
        val savedAnswers = database.candidateResponseDao().getResponsesByAttempt(attemptId)
        assertEquals(1, savedAnswers.size)
        assertEquals("B", savedAnswers[0].responsePayload)
    }

    @Test
    fun testLowConfidenceBackgroundingDoesNotCancelAttempt() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepo, examRepo, RateLimiter())
        }

        val backgroundPayload = JSONObject().apply {
            put("eventId", "integ_bg_warn_01")
            put("eventCategory", "EXAM_APP_BACKGROUND")
            put("displayedAppName", "APP_NAME_UNAVAILABLE")
            put("deviceReportedAt", "2026-10-10T10:14:00Z")
            put("sequenceNumber", 3)
            put("sourceDeviceId", "device_pixel")
        }

        val res = client.post("/api/attempts/$attemptId/integrity-events") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(backgroundPayload.toString())
        }
        assertEquals(HttpStatusCode.OK, res.status)

        val data = JSONObject(res.bodyAsText())
        val result = data.getJSONArray("results").getJSONObject(0)
        assertEquals("WARN_STUDENT", result.getString("actionTaken"))
        assertEquals("IN_PROGRESS", result.getString("attemptStatus"))
        assertTrue(result.has("warningMessage"))

        // Verify attempt is still IN_PROGRESS in database
        val currentAttempt = database.examAttemptDao().getAttemptById(attemptId)
        assertEquals("IN_PROGRESS", currentAttempt!!.attemptStatus)
    }

    @Test
    fun testStudentCannotReportEventsForAnotherStudentAttempt() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepo, examRepo, RateLimiter())
        }

        // Another student
        val otherStudentId = "usr_student_imposter"
        database.userDao().insertUser(
            UserEntity(
                userId = otherStudentId,
                displayName = "Imposter Student",
                role = "STUDENT",
                accountStatus = "ACTIVE",
                instituteId = instituteId,
                createdAt = "2026-10-10T00:00:00Z"
            )
        )
        val imposterToken = sessionManager.createSession(otherStudentId, "Imposter Student", "STUDENT", instituteId).token

        val eventPayload = JSONObject().apply {
            put("eventId", "integ_unauth_01")
            put("eventCategory", "EXAM_APP_BACKGROUND")
            put("deviceReportedAt", "2026-10-10T10:16:00Z")
        }

        val res = client.post("/api/attempts/$attemptId/integrity-events") {
            header(HttpHeaders.Authorization, "Bearer $imposterToken")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(eventPayload.toString())
        }
        assertEquals(HttpStatusCode.Forbidden, res.status)
    }

    @Test
    fun testUnsupportedCategoryIsRejected() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepo, examRepo, RateLimiter())
        }

        val invalidPayload = JSONObject().apply {
            put("eventId", "integ_invalid_cat_01")
            put("eventCategory", "UNSUPPORTED_HACK_CATEGORY")
            put("deviceReportedAt", "2026-10-10T10:17:00Z")
        }

        val res = client.post("/api/attempts/$attemptId/integrity-events") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(invalidPayload.toString())
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun testExaminerAlertsEndpointReturnsRealTimeAlerts() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepo, examRepo, RateLimiter())
        }

        // 1. Ingest event from student
        val eventPayload = JSONObject().apply {
            put("eventId", "integ_alert_test_01")
            put("eventCategory", "LOCK_TASK_EXITED")
            put("deviceReportedAt", "2026-10-10T10:20:00Z")
            put("sequenceNumber", 1)
            put("sourceDeviceId", "device_pixel")
        }
        client.post("/api/attempts/$attemptId/integrity-events") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(eventPayload.toString())
        }

        // 2. Examiner retrieves alerts
        val res = client.get("/api/examiner/integrity/alerts") {
            header(HttpHeaders.Authorization, "Bearer $examinerToken")
        }
        assertEquals(HttpStatusCode.OK, res.status)

        val alertsArr = JSONArray(res.bodyAsText())
        assertTrue(alertsArr.length() > 0)
        val alert = alertsArr.getJSONObject(0)
        assertEquals("LOCK_TASK_EXITED", alert.getString("eventCategory"))
        assertEquals("Student Step8", alert.getString("studentDisplayName"))
        assertEquals("Strict Integrity Examination 2026", alert.getString("examTitle"))
        assertEquals("CRITICAL", alert.getString("severity"))
    }

    @Test
    fun testExaminerAcknowledgeAlertUpdatesStatus() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepo, examRepo, RateLimiter())
        }

        // 1. Ingest event
        val eventId = "integ_ack_test_01"
        val eventPayload = JSONObject().apply {
            put("eventId", eventId)
            put("eventCategory", "EXAM_APP_BACKGROUND")
            put("deviceReportedAt", "2026-10-10T10:22:00Z")
            put("sequenceNumber", 1)
            put("sourceDeviceId", "device_pixel")
        }
        client.post("/api/attempts/$attemptId/integrity-events") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(eventPayload.toString())
        }

        // 2. Examiner acknowledges alert
        val res = client.post("/api/examiner/integrity/alerts/$eventId/acknowledge") {
            header(HttpHeaders.Authorization, "Bearer $examinerToken")
        }
        assertEquals(HttpStatusCode.OK, res.status)

        val body = JSONObject(res.bodyAsText())
        assertEquals("ACKNOWLEDGED", body.getString("status"))

        // Verify Room database reflects status
        val eventInDb = database.integrityEventDao().getEventById(eventId)
        assertNotNull(eventInDb)
        assertEquals("ACKNOWLEDGED", eventInDb!!.ingestionStatus)
    }
}
