package com.doomedams.app.server

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.doomedams.app.database.AppDatabase
import com.doomedams.app.database.audit.AuditEngine
import com.doomedams.app.database.entity.CandidateResponseEntity
import com.doomedams.app.database.entity.ExamAttemptEntity
import com.doomedams.app.database.entity.ExamEntity
import com.doomedams.app.database.entity.ExamResultEntity
import com.doomedams.app.database.entity.IncidentCaseEntity
import com.doomedams.app.database.entity.IntegrityEventEntity
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IncidentReviewWorkflowTest {

    private lateinit var database: AppDatabase
    private lateinit var sessionManager: SessionManager
    private lateinit var auditEngine: AuditEngine
    private lateinit var attemptRepo: AttemptRepository
    private lateinit var examRepo: ExamRepository

    private val instituteId = "inst_apex"
    private val examId = "exam_step9_001"
    private val studentId = "usr_student_s9"
    private val foreignStudentId = "usr_student_foreign"
    private val attemptId = "att_step9_001"
    private val incidentId = "inc_step9_001"

    private val reviewerId = "usr_reviewer_s9"
    private val adminId = "usr_admin_s9"
    private val ceoId = "usr_ceo_s9"
    private val auditorId = "usr_auditor_s9"
    private val sysAdminId = "usr_sysadmin_s9"

    private lateinit var studentToken: String
    private lateinit var foreignStudentToken: String
    private lateinit var reviewerToken: String
    private lateinit var adminToken: String
    private lateinit var ceoToken: String
    private lateinit var auditorToken: String
    private lateinit var sysAdminToken: String

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

        val (pwHash, salt) = PasswordHasher.hashPassword("Password@123")

        suspend fun seedUser(id: String, name: String, role: String, inst: String = instituteId): String {
            database.userDao().insertUser(
                UserEntity(userId = id, displayName = name, role = role, accountStatus = "ACTIVE", instituteId = inst, createdAt = "2026-10-10T00:00:00Z")
            )
            database.userCredentialDao().insertCredential(
                UserCredentialEntity(userId = id, passwordHash = pwHash, salt = salt, updatedAt = "2026-10-10T00:00:00Z")
            )
            return sessionManager.createSession(id, name, role, inst).token
        }

        studentToken = seedUser(studentId, "Alice Student", "STUDENT")
        foreignStudentToken = seedUser(foreignStudentId, "Charlie Foreign", "STUDENT", "inst_other")
        reviewerToken = seedUser(reviewerId, "Bob Reviewer", "INCIDENT_REVIEWER")
        adminToken = seedUser(adminId, "Dave Admin", "EXAMINATION_ADMIN")
        ceoToken = seedUser(ceoId, "Eve Chief", "CHIEF_EXAMINATION_OFFICER")
        auditorToken = seedUser(auditorId, "Frank Auditor", "AUDITOR")
        sysAdminToken = seedUser(sysAdminId, "Grace SysAdmin", "SYSTEM_ADMIN")

        // Seed Exam
        database.examDao().insertExam(
            ExamEntity(
                examId = examId,
                title = "Computer Science Honours CBT",
                durationSeconds = 7200L,
                instituteId = instituteId,
                status = "PUBLISHED",
                markingSchemeVersion = 1,
                createdAt = "2026-10-10T00:00:00Z",
                updatedAt = "2026-10-10T00:00:00Z"
            )
        )

        // Seed Attempt
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

        // Seed Candidate Response
        database.candidateResponseDao().upsertResponse(
            CandidateResponseEntity(
                responseId = "resp_s9_001",
                attemptId = attemptId,
                questionId = "q_algo_01",
                responsePayload = "OPTION_C",
                revisionSequence = 1,
                savedAt = "2026-10-10T10:15:00Z"
            )
        )

        // Seed Exam Result
        database.examResultDao().insertResult(
            ExamResultEntity(
                resultId = "res_step9_001",
                attemptId = attemptId,
                examId = examId,
                studentId = studentId,
                version = 1,
                resultStatus = "GRADED",
                totalScaledScore = 8500L,
                maxPossibleScaledScore = 10000L,
                correctCount = 17,
                incorrectCount = 3,
                unansweredCount = 0,
                partiallyCorrectCount = 0,
                pendingManualCount = 0,
                markingSchemeVersion = 1,
                scoringEngineVersion = "1.0",
                sectionTotalsJson = "{}",
                resultHash = "hash_result_s9_001",
                holdReason = null,
                scoredAt = "2026-10-10T10:30:00Z",
                publishedAt = null,
                createdAt = "2026-10-10T10:30:00Z",
                updatedAt = "2026-10-10T10:30:00Z"
            )
        )

        // Seed Initial Integrity Event
        database.integrityEventDao().insertEvent(
            IntegrityEventEntity(
                eventId = "ev_integ_s9_001",
                examId = examId,
                attemptId = attemptId,
                studentId = studentId,
                eventCategory = "UNAUTHORISED_APP_SWITCH",
                deviceReportedAt = "2026-10-10T10:20:00Z",
                receivedAt = "2026-10-10T10:20:02Z",
                sequenceNumber = 1L,
                sourceDeviceId = "dev_pixel_s9",
                payloadJson = "{}",
                payloadHash = null,
                appPackageName = null,
                displayedAppName = "APP_NAME_UNAVAILABLE",
                ingestionStatus = "RECEIVED"
            )
        )

        // Seed Incident Case
        database.incidentDao().insertIncident(
            IncidentCaseEntity(
                incidentId = incidentId,
                examId = examId,
                attemptId = attemptId,
                studentId = studentId,
                category = "UNAUTHORISED_APP_SWITCH",
                incidentStatus = "PENDING_REVIEW",
                evidenceAssessment = "LIMITED",
                decision = "PENDING_REVIEW",
                reviewerId = null,
                policyVersion = "v1.0",
                version = 1,
                createdAt = "2026-10-10T10:20:05Z",
                updatedAt = "2026-10-10T10:20:05Z"
            )
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun test01_incidentQueueListingAndRoleAccess() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepo, examRepo, RateLimiter())
        }

        // Reviewer can list incidents
        val res = client.get("/api/incidents") {
            header(HttpHeaders.Authorization, "Bearer $reviewerToken")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val arr = JSONArray(res.bodyAsText())
        assertEquals(1, arr.length())
        assertEquals(incidentId, arr.getJSONObject(0).getString("incidentId"))
        assertEquals("PENDING_REVIEW", arr.getJSONObject(0).getString("incidentStatus"))

        // Student can only see own incidents
        val resStudent = client.get("/api/incidents") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
        }
        assertEquals(HttpStatusCode.OK, resStudent.status)
        val studentArr = JSONArray(resStudent.bodyAsText())
        assertEquals(1, studentArr.length())

        // Foreign student sees 0 incidents
        val resForeign = client.get("/api/incidents") {
            header(HttpHeaders.Authorization, "Bearer $foreignStudentToken")
        }
        assertEquals(HttpStatusCode.OK, resForeign.status)
        val foreignArr = JSONArray(resForeign.bodyAsText())
        assertEquals(0, foreignArr.length())
    }

    @Test
    fun test02_studentCannotInspectOtherStudentIncidentDetail() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepo, examRepo, RateLimiter())
        }

        val res = client.get("/api/incidents/$incidentId") {
            header(HttpHeaders.Authorization, "Bearer $foreignStudentToken")
        }
        assertEquals(HttpStatusCode.Forbidden, res.status)
    }

    @Test
    fun test03_beginReviewTransitionsPendingReviewToUnderReview() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepo, examRepo, RateLimiter())
        }

        val res = client.post("/api/incidents/$incidentId/review/begin") {
            header(HttpHeaders.Authorization, "Bearer $reviewerToken")
        }
        assertEquals(HttpStatusCode.OK, res.status)

        val updated = database.incidentDao().getIncidentById(incidentId)
        assertNotNull(updated)
        assertEquals("UNDER_REVIEW", updated!!.incidentStatus)
        assertEquals(reviewerId, updated.reviewerId)
    }

    @Test
    fun test04_limitedEvidenceCannotDirectlyProduceConfirmedFinding() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepo, examRepo, RateLimiter())
        }

        // Begin review first
        client.post("/api/incidents/$incidentId/review/begin") {
            header(HttpHeaders.Authorization, "Bearer $reviewerToken")
        }

        // Attempt CONFIRMED with LIMITED evidence -> must be rejected by server!
        val payload = JSONObject().apply {
            put("evidenceAssessment", "LIMITED")
            put("decision", "CONFIRMED")
            put("justification", "Attempting confirmation on limited evidence")
        }

        val res = client.post("/api/incidents/$incidentId/assess") {
            header(HttpHeaders.Authorization, "Bearer $reviewerToken")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(payload.toString())
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertTrue(res.bodyAsText().contains("Cannot confirm misconduct violation when evidence assessment is 'LIMITED'"))

        // Incident status must remain UNDER_REVIEW
        val incident = database.incidentDao().getIncidentById(incidentId)
        assertEquals("UNDER_REVIEW", incident!!.incidentStatus)
    }

    @Test
    fun test05_confirmedFindingRequiresSufficientEvidenceAndMeaningfulJustification() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepo, examRepo, RateLimiter())
        }

        client.post("/api/incidents/$incidentId/review/begin") {
            header(HttpHeaders.Authorization, "Bearer $reviewerToken")
        }

        // Too short justification -> rejected
        val shortPayload = JSONObject().apply {
            put("evidenceAssessment", "SUFFICIENT")
            put("decision", "CONFIRMED")
            put("justification", "ok")
        }
        val resShort = client.post("/api/incidents/$incidentId/assess") {
            header(HttpHeaders.Authorization, "Bearer $reviewerToken")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(shortPayload.toString())
        }
        assertEquals(HttpStatusCode.BadRequest, resShort.status)

        // Valid SUFFICIENT evidence and detailed justification -> succeeds
        val validPayload = JSONObject().apply {
            put("evidenceAssessment", "SUFFICIENT")
            put("decision", "CONFIRMED")
            put("justification", "Prohibited app package usage verified via audit trail and corroborated evidence")
        }
        val resValid = client.post("/api/incidents/$incidentId/assess") {
            header(HttpHeaders.Authorization, "Bearer $reviewerToken")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(validPayload.toString())
        }
        assertEquals(HttpStatusCode.OK, resValid.status)

        val updated = database.incidentDao().getIncidentById(incidentId)
        assertEquals("CONFIRMED", updated!!.incidentStatus)
        assertEquals("SUFFICIENT", updated.evidenceAssessment)
    }

    @Test
    fun test06_invalidLifecycleTransitionIsRejected() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepo, examRepo, RateLimiter())
        }

        // Incident is in PENDING_REVIEW; cannot jump directly to CONFIRMED or CLOSED
        val payload = JSONObject().apply {
            put("evidenceAssessment", "SUFFICIENT")
            put("decision", "CONFIRMED")
            put("justification", "Bypassing under review state")
        }
        val res = client.post("/api/incidents/$incidentId/assess") {
            header(HttpHeaders.Authorization, "Bearer $reviewerToken")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(payload.toString())
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertTrue(res.bodyAsText().contains("INVALID_TRANSITION"))
    }

    @Test
    fun test07_prescribedLimitedEvidenceHoldScenarioEndToEnd() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepo, examRepo, RateLimiter())
        }

        // Move to UNDER_REVIEW
        client.post("/api/incidents/$incidentId/review/begin") {
            header(HttpHeaders.Authorization, "Bearer $reviewerToken")
        }

        // Execute prescribed scenario
        val res = client.post("/api/incidents/$incidentId/scenario/limited-evidence-hold") {
            header(HttpHeaders.Authorization, "Bearer $reviewerToken")
        }
        assertEquals(HttpStatusCode.OK, res.status)

        val data = JSONObject(res.bodyAsText())
        assertEquals("UNAUTHORISED_APP_SWITCH", data.getString("incidentCategory"))
        assertEquals("LIMITED", data.getString("evidenceAssessment"))
        assertEquals("HOLD", data.getString("requestedPolicyAction"))
        assertEquals("No notes supplied", data.getString("reviewerJustification"))
        assertEquals("PENDING_FURTHER_REVIEW", data.getString("incidentStatus"))
        assertEquals("HELD_PENDING_REVIEW", data.getString("resultStatus"))
        assertTrue(data.getBoolean("attemptStatusPreserved"))

        // Database assertions
        val storedIncident = database.incidentDao().getIncidentById(incidentId)
        assertNotNull(storedIncident)
        assertEquals("PENDING_FURTHER_REVIEW", storedIncident!!.incidentStatus)
        assertEquals("LIMITED", storedIncident.evidenceAssessment)

        val storedResult = database.examResultDao().getResultByAttemptId(attemptId)
        assertNotNull(storedResult)
        assertEquals("HELD_PENDING_REVIEW", storedResult!!.resultStatus)
        // Marks preserved
        assertEquals(8500L, storedResult.totalScaledScore)
        assertEquals(17, storedResult.correctCount)

        // Attempt NOT cancelled
        val storedAttempt = database.examAttemptDao().getAttemptById(attemptId)
        assertEquals("IN_PROGRESS", storedAttempt!!.attemptStatus)

        // Answers preserved
        val savedAnswers = database.candidateResponseDao().getResponsesByAttempt(attemptId)
        assertEquals(1, savedAnswers.size)
        assertEquals("OPTION_C", savedAnswers[0].responsePayload)

        // Policy action recorded
        val actions = database.incidentDao().getPolicyActionsForIncident(incidentId)
        assertEquals(1, actions.size)
        assertEquals("HOLD", actions[0].actionType)
        assertEquals("APPLIED", actions[0].executionStatus)
    }

    @Test
    fun test08_holdReleaseRevertsStatusPreservingAuditHistory() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepo, examRepo, RateLimiter())
        }

        // Apply hold
        val holdPayload = JSONObject().apply {
            put("action", "HOLD")
            put("reason", "Suspected unverified background activity")
        }
        val resHold = client.post("/api/incidents/$incidentId/hold") {
            header(HttpHeaders.Authorization, "Bearer $reviewerToken")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(holdPayload.toString())
        }
        assertEquals(HttpStatusCode.OK, resHold.status)
        assertEquals("HELD_PENDING_REVIEW", database.examResultDao().getResultByAttemptId(attemptId)!!.resultStatus)

        // Release hold requires valid documented reason
        val emptyReasonPayload = JSONObject().apply {
            put("action", "RELEASE_HOLD")
            put("reason", "")
        }
        val resEmpty = client.post("/api/incidents/$incidentId/hold") {
            header(HttpHeaders.Authorization, "Bearer $reviewerToken")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(emptyReasonPayload.toString())
        }
        assertEquals(HttpStatusCode.BadRequest, resEmpty.status)

        // Valid release
        val validReleasePayload = JSONObject().apply {
            put("action", "RELEASE_HOLD")
            put("reason", "Investigation determined system update dialogue caused window blur; no misconduct")
        }
        val resRelease = client.post("/api/incidents/$incidentId/hold") {
            header(HttpHeaders.Authorization, "Bearer $reviewerToken")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(validReleasePayload.toString())
        }
        assertEquals(HttpStatusCode.OK, resRelease.status)
        assertEquals("GRADED", database.examResultDao().getResultByAttemptId(attemptId)!!.resultStatus)

        // Historical hold actions preserved
        val actions = database.incidentDao().getPolicyActionsForIncident(incidentId)
        assertTrue(actions.any { it.actionType == "HOLD" })
        assertTrue(actions.any { it.actionType == "RELEASE_HOLD" })
    }

    @Test
    fun test09_incidentReviewerCannotCancelAttempt_cancellationRequiresPrivilegedRole() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepo, examRepo, RateLimiter())
        }

        val cancelPayload = JSONObject().apply {
            put("reason", "Attempting cancellation")
        }

        // INCIDENT_REVIEWER denied ATTEMPT_CANCEL
        val resReviewer = client.post("/api/incidents/$incidentId/cancel") {
            header(HttpHeaders.Authorization, "Bearer $reviewerToken")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(cancelPayload.toString())
        }
        assertEquals(HttpStatusCode.Forbidden, resReviewer.status)

        // EXAMINATION_ADMIN can cancel
        val resAdmin = client.post("/api/incidents/$incidentId/cancel") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(JSONObject().apply {
                put("reason", "Confirmed unauthorized communication during active examination")
            }.toString())
        }
        assertEquals(HttpStatusCode.OK, resAdmin.status)

        val updatedAttempt = database.examAttemptDao().getAttemptById(attemptId)
        assertEquals("CANCELLED", updatedAttempt!!.attemptStatus)
        assertEquals("WITHHELD", updatedAttempt.resultStatus)

        // Candidate response snapshot remains intact
        val responses = database.candidateResponseDao().getResponsesByAttempt(attemptId)
        assertEquals(1, responses.size)
        assertEquals("OPTION_C", responses[0].responsePayload)
    }

    @Test
    fun test10_reinstatementRestoresAttemptStatusWithAuditedTransition() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepo, examRepo, RateLimiter())
        }

        // Cancel first
        client.post("/api/incidents/$incidentId/cancel") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("{\"reason\":\"Pre-hearing cancellation\"}")
        }
        assertEquals("CANCELLED", database.examAttemptDao().getAttemptById(attemptId)!!.attemptStatus)

        // Reinstate attempt
        val reinstatePayload = JSONObject().apply {
            put("reason", "Formal hearing concluded technical glitch without fault; attempt reinstated")
        }
        val res = client.post("/api/incidents/$incidentId/reinstate") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(reinstatePayload.toString())
        }
        assertEquals(HttpStatusCode.OK, res.status)

        val restored = database.examAttemptDao().getAttemptById(attemptId)
        assertEquals("SUBMITTED", restored!!.attemptStatus) // responses exist -> SUBMITTED
    }

    @Test
    fun test11_appealsWorkflowEndToEndWithIndependentReviewEnforcement() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepo, examRepo, RateLimiter())
        }

        // Reviewer begins and decides incident
        client.post("/api/incidents/$incidentId/review/begin") {
            header(HttpHeaders.Authorization, "Bearer $reviewerToken")
        }
        client.post("/api/incidents/$incidentId/assess") {
            header(HttpHeaders.Authorization, "Bearer $reviewerToken")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(JSONObject().apply {
                put("evidenceAssessment", "SUFFICIENT")
                put("decision", "CONFIRMED")
                put("justification", "Verified unauthorized window exit corroborated by examiner log")
            }.toString())
        }

        // Foreign student cannot appeal Alice's incident
        val resForeign = client.post("/api/incidents/$incidentId/appeals") {
            header(HttpHeaders.Authorization, "Bearer $foreignStudentToken")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(JSONObject().apply {
                put("challengedAction", "CONFIRMED_VIOLATION")
                put("appealReason", "I am appealing on their behalf")
            }.toString())
        }
        assertEquals(HttpStatusCode.Forbidden, resForeign.status)

        // Alice submits appeal for own incident
        val appealPayload = JSONObject().apply {
            put("challengedAction", "CONFIRMED_VIOLATION")
            put("appealReason", "The tablet screen glitched and triggered auto-rotation outside my control.")
            put("evidenceReferences", "device_log_sensor_event_102")
        }
        val resAppeal = client.post("/api/incidents/$incidentId/appeals") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(appealPayload.toString())
        }
        assertEquals(HttpStatusCode.Created, resAppeal.status)
        val appealData = JSONObject(resAppeal.bodyAsText())
        val appealId = appealData.getString("appealId")
        assertEquals("SUBMITTED", appealData.getString("appealStatus"))

        // Independent Review Rule: Original reviewer Bob CANNOT decide the appeal!
        val resBobDecide = client.post("/api/incidents/$incidentId/appeals/$appealId/decide") {
            header(HttpHeaders.Authorization, "Bearer $reviewerToken")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(JSONObject().apply {
                put("decision", "UPHELD")
                put("outcomeReason", "I stand by my original finding")
            }.toString())
        }
        assertEquals(HttpStatusCode.Forbidden, resBobDecide.status)

        // Independent Administrator (Dave Admin) adjudicates the appeal
        val resAdminDecide = client.post("/api/incidents/$incidentId/appeals/$appealId/decide") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(JSONObject().apply {
                put("decision", "OVERTURNED")
                put("outcomeReason", "Independent hardware diagnostic verified screen digitizer failure during test window.")
            }.toString())
        }
        assertEquals(HttpStatusCode.OK, resAdminDecide.status)

        val updatedAppeal = database.incidentDao().getAppealById(appealId)
        assertNotNull(updatedAppeal)
        assertEquals("OVERTURNED", updatedAppeal!!.appealStatus)
        assertEquals(adminId, updatedAppeal.assignedReviewerId)
    }

    @Test
    fun test12_auditorAndSysAdminRoleRestrictions() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepo, examRepo, RateLimiter())
        }

        // Auditor has read-only access to incidents and audit log
        val resAuditorList = client.get("/api/incidents") {
            header(HttpHeaders.Authorization, "Bearer $auditorToken")
        }
        assertEquals(HttpStatusCode.OK, resAuditorList.status)

        // Auditor cannot decide an incident
        val resAuditorDecide = client.post("/api/incidents/$incidentId/assess") {
            header(HttpHeaders.Authorization, "Bearer $auditorToken")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("{\"evidenceAssessment\":\"SUFFICIENT\",\"decision\":\"CONFIRMED\",\"justification\":\"audit override\"}")
        }
        assertEquals(HttpStatusCode.Forbidden, resAuditorDecide.status)

        // System Administrator cannot make academic decisions or cancel attempts
        val resSysAdminCancel = client.post("/api/incidents/$incidentId/cancel") {
            header(HttpHeaders.Authorization, "Bearer $sysAdminToken")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("{\"reason\":\"sys admin cancel\"}")
        }
        assertEquals(HttpStatusCode.Forbidden, resSysAdminCancel.status)
    }

    @Test
    fun test13_incidentAuditTimelineReturnsChronologicalHistory() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepo, examRepo, RateLimiter())
        }

        // Reviewer begins review
        client.post("/api/incidents/$incidentId/review/begin") {
            header(HttpHeaders.Authorization, "Bearer $reviewerToken")
        }

        // Fetch audit timeline
        val res = client.get("/api/incidents/$incidentId/audit") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val timeline = JSONArray(res.bodyAsText())
        assertTrue(timeline.length() > 0)
        val firstEvent = timeline.getJSONObject(0)
        assertEquals("INCIDENT_REVIEW_STARTED", firstEvent.getString("actionType"))
        assertNotNull(firstEvent.getString("currentHash"))
        assertNotNull(firstEvent.getString("timestamp"))
    }
}
