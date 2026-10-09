package com.doomedams.app.server

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.doomedams.app.database.AppDatabase
import com.doomedams.app.database.audit.AuditEngine
import com.doomedams.app.database.entity.ExamAttemptEntity
import com.doomedams.app.database.entity.ExamEntity
import com.doomedams.app.database.entity.UserCredentialEntity
import com.doomedams.app.database.entity.UserEntity
import com.doomedams.app.database.repository.AttemptRepository
import com.doomedams.app.server.auth.SessionManager
import com.doomedams.app.server.rbac.AuthorizationResult
import com.doomedams.app.server.rbac.Permission
import com.doomedams.app.server.rbac.PermissionService
import com.doomedams.app.server.routes.configureExamServerRoutes
import com.doomedams.app.server.security.PasswordHasher
import com.doomedams.app.server.security.RateLimiter
import com.doomedams.app.server.security.TlsCertificateManager
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
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ExamServerRbacTest {

    private lateinit var database: AppDatabase
    private lateinit var sessionManager: SessionManager
    private lateinit var auditEngine: AuditEngine
    private lateinit var attemptRepository: AttemptRepository
    private lateinit var rateLimiter: RateLimiter

    @Before
    fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(AppDatabase.DatabaseSecurityCallback)
            .build()
        database.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = ON;")

        sessionManager = SessionManager()
        auditEngine = AuditEngine(database)
        attemptRepository = AttemptRepository(database, auditEngine)
        rateLimiter = RateLimiter()

        // Seed Admin user
        val adminUser = UserEntity(
            userId = "admin_01",
            displayName = "Admin Alice",
            role = "EXAMINATION_ADMIN",
            accountStatus = "ACTIVE",
            instituteId = "inst_apex",
            createdAt = "2026-10-09T08:00:00Z"
        )
        val (adminHash, adminSalt) = PasswordHasher.hashPassword("SuperSecret123")
        database.userDao().insertUser(adminUser)
        database.userCredentialDao().insertCredential(
            UserCredentialEntity(
                userId = "admin_01",
                passwordHash = adminHash,
                salt = adminSalt,
                updatedAt = "2026-10-09T08:00:00Z"
            )
        )

        // Seed Student user
        val studentUser = UserEntity(
            userId = "student_01",
            displayName = "Bob Student",
            role = "STUDENT",
            accountStatus = "ACTIVE",
            instituteId = "inst_apex",
            createdAt = "2026-10-09T08:00:00Z"
        )
        val (stdHash, stdSalt) = PasswordHasher.hashPassword("StudentPass456")
        database.userDao().insertUser(studentUser)
        database.userCredentialDao().insertCredential(
            UserCredentialEntity(
                userId = "student_01",
                passwordHash = stdHash,
                salt = stdSalt,
                updatedAt = "2026-10-09T08:00:00Z"
            )
        )

        // Seed Disabled user
        val disabledUser = UserEntity(
            userId = "disabled_01",
            displayName = "Suspended User",
            role = "STUDENT",
            accountStatus = "SUSPENDED",
            instituteId = "inst_apex",
            createdAt = "2026-10-09T08:00:00Z"
        )
        val (disHash, disSalt) = PasswordHasher.hashPassword("Pass123")
        database.userDao().insertUser(disabledUser)
        database.userCredentialDao().insertCredential(
            UserCredentialEntity(
                userId = "disabled_01",
                passwordHash = disHash,
                salt = disSalt,
                updatedAt = "2026-10-09T08:00:00Z"
            )
        )

        // Seed Exam
        val exam = ExamEntity(
            examId = "exam_101",
            instituteId = "inst_apex",
            title = "Physics Offline Finals",
            status = "PUBLISHED",
            durationSeconds = 7200,
            markingSchemeVersion = 1,
            createdAt = "2026-10-09T08:00:00Z",
            updatedAt = "2026-10-09T08:00:00Z"
        )
        database.examDao().insertExam(exam)

        // Seed Student Attempt
        val attempt = ExamAttemptEntity(
            attemptId = "att_bob_101",
            examId = "exam_101",
            studentId = "student_01",
            attemptStatus = "IN_PROGRESS",
            resultStatus = "PENDING_GRADING",
            startedAt = "2026-10-09T09:00:00Z",
            submittedAt = null,
            deadlineMetadata = "{}",
            createdAt = "2026-10-09T09:00:00Z",
            updatedAt = "2026-10-09T09:00:00Z"
        )
        database.examAttemptDao().insertAttempt(attempt)
    }

    @After
    fun teardown() {
        database.close()
    }

    // -------------------------------------------------------------
    // AUTHENTICATION TESTS (1-7)
    // -------------------------------------------------------------

    @Test
    fun test1_validProvisionedUserCanAuthenticate() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepository, rateLimiter)
        }
        val response = client.post("/api/auth/login") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("{\"userId\":\"admin_01\",\"password\":\"SuperSecret123\"}")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val json = JSONObject(response.bodyAsText())
        assertTrue(json.has("token"))
        assertEquals("admin_01", json.getJSONObject("user").getString("userId"))
        assertEquals("EXAMINATION_ADMIN", json.getJSONObject("user").getString("role"))
    }

    @Test
    fun test2_invalidCredentialIsRejected() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepository, rateLimiter)
        }
        val response = client.post("/api/auth/login") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("{\"userId\":\"admin_01\",\"password\":\"WrongPassword!\"}")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        val json = JSONObject(response.bodyAsText())
        assertEquals("INVALID_CREDENTIALS", json.getString("error"))
    }

    @Test
    fun test3_disabledAccountCannotEstablishActiveSession() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepository, rateLimiter)
        }
        val response = client.post("/api/auth/login") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("{\"userId\":\"disabled_01\",\"password\":\"Pass123\"}")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        val json = JSONObject(response.bodyAsText())
        assertEquals("ACCOUNT_DISABLED", json.getString("error"))
    }

    @Test
    fun test4_expiredSessionIsRejected() = testApplication {
        // Create session with 0s lifetime to simulate expiry
        val shortLivedManager = SessionManager(sessionValiditySeconds = -10)
        application {
            configureExamServerRoutes(database, shortLivedManager, auditEngine, attemptRepository, rateLimiter)
        }
        val session = shortLivedManager.createSession(
            userId = "admin_01",
            displayName = "Admin",
            role = "EXAMINATION_ADMIN",
            instituteScope = "inst_apex"
        )
        val response = client.get("/api/auth/me") {
            header("Authorization", "Bearer ${session.token}")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains("SESSION_EXPIRED"))
    }

    @Test
    fun test5_logoutInvalidatesSession() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepository, rateLimiter)
        }
        val session = sessionManager.createSession("admin_01", "Admin", "EXAMINATION_ADMIN", "inst_apex")
        
        // Confirm active
        val activeResp = client.get("/api/auth/me") {
            header("Authorization", "Bearer ${session.token}")
        }
        assertEquals(HttpStatusCode.OK, activeResp.status)

        // Logout
        val logoutResp = client.post("/api/auth/logout") {
            header("Authorization", "Bearer ${session.token}")
        }
        assertEquals(HttpStatusCode.OK, logoutResp.status)

        // Confirm now unauthorized
        val postLogoutResp = client.get("/api/auth/me") {
            header("Authorization", "Bearer ${session.token}")
        }
        assertEquals(HttpStatusCode.Unauthorized, postLogoutResp.status)
    }

    @Test
    fun test6_fabricatedClientSideRoleDoesNotGrantPermissions() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepository, rateLimiter)
        }
        // Student tries to pass an admin role header or body
        val studentSession = sessionManager.createSession("student_01", "Bob", "STUDENT", "inst_apex")
        val response = client.post("/api/admin/users/provision") {
            header("Authorization", "Bearer ${studentSession.token}")
            header("X-Role-Override", "CHIEF_EXAMINATION_OFFICER")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("{\"userId\":\"new_user\",\"displayName\":\"New\",\"role\":\"STUDENT\",\"instituteId\":\"inst_apex\",\"password\":\"123456\"}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun test7_unauthenticatedRequestsCannotAccessProtectedResources() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepository, rateLimiter)
        }
        val response = client.get("/api/exams/exam_101")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // -------------------------------------------------------------
    // RBAC & SCOPE TESTS (8-15)
    // -------------------------------------------------------------

    @Test
    fun test8_studentCannotProvisionUsers() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepository, rateLimiter)
        }
        val studentSession = sessionManager.createSession("student_01", "Bob", "STUDENT", "inst_apex")
        val response = client.post("/api/admin/users/provision") {
            header("Authorization", "Bearer ${studentSession.token}")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("{\"userId\":\"intruder\",\"displayName\":\"Intruder\",\"role\":\"STUDENT\",\"instituteId\":\"inst_apex\",\"password\":\"123456\"}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertNull(database.userDao().getUserById("intruder"))
    }

    @Test
    fun test9_studentCannotReadOrWriteAnotherStudentsAnswers() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepository, rateLimiter)
        }
        // Bob is student_01. Create another student charlie
        val charlieSession = sessionManager.createSession("charlie_02", "Charlie", "STUDENT", "inst_apex")

        // Charlie attempts to submit an answer for Bob's attempt
        val response = client.post("/api/attempts/att_bob_101/answers") {
            header("Authorization", "Bearer ${charlieSession.token}")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("{\"questionId\":\"q1\",\"responsePayload\":\"B\",\"revisionSequence\":1}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(response.bodyAsText().contains("Forbidden"))
    }

    @Test
    fun test10_invigilatorCannotAccessUnassignedExamination() {
        val invigilatorSession = sessionManager.createSession(
            userId = "inv_01",
            displayName = "Invigilator One",
            role = "INVIGILATOR",
            instituteScope = "inst_apex",
            examinationScope = "exam_MATH_200" // Assigned to Math, not Physics
        )

        val result = PermissionService.authorize(
            session = invigilatorSession,
            permission = Permission.EXAM_READ,
            resourceInstituteId = "inst_apex",
            resourceExamId = "exam_101" // Physics exam
        )
        assertTrue(result is AuthorizationResult.Denied)
    }

    @Test
    fun test11_auditorCannotModifyIncidentDecisions() {
        assertFalse(PermissionService.hasPermission("AUDITOR", Permission.INCIDENT_REVIEW))
        assertFalse(PermissionService.hasPermission("AUDITOR", Permission.RESULT_HOLD_APPLY))
        assertFalse(PermissionService.hasPermission("AUDITOR", Permission.ATTEMPT_CANCEL))
        assertTrue(PermissionService.hasPermission("AUDITOR", Permission.AUDIT_READ))
    }

    @Test
    fun test12_systemAdminDoesNotGainAcademicDecisionPermissions() {
        assertFalse(PermissionService.hasPermission("SYSTEM_ADMIN", Permission.INCIDENT_REVIEW))
        assertFalse(PermissionService.hasPermission("SYSTEM_ADMIN", Permission.RESULT_HOLD_APPLY))
        assertFalse(PermissionService.hasPermission("SYSTEM_ADMIN", Permission.ATTEMPT_CANCEL))
        assertTrue(PermissionService.hasPermission("SYSTEM_ADMIN", Permission.SERVER_HOST_START))
    }

    @Test
    fun test13_and_14_reviewerCanApplyHold_butCannotCancelAttempt() {
        // Reviewer has RESULT_HOLD_APPLY
        assertTrue(PermissionService.hasPermission("INCIDENT_REVIEWER", Permission.RESULT_HOLD_APPLY))
        // Reviewer DOES NOT have ATTEMPT_CANCEL (Strict separation enforced)
        assertFalse(PermissionService.hasPermission("INCIDENT_REVIEWER", Permission.ATTEMPT_CANCEL))
    }

    @Test
    fun test15_deniedOperationsDoNotMutateProtectedDomainState() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepository, rateLimiter)
        }
        val studentSession = sessionManager.createSession("student_01", "Bob", "STUDENT", "inst_apex")
        val initialUserCount = database.userDao().getUsersByInstitute("inst_apex").size

        val response = client.post("/api/admin/users/provision") {
            header("Authorization", "Bearer ${studentSession.token}")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("{\"userId\":\"fake_admin\",\"displayName\":\"Fake\",\"role\":\"EXAMINATION_ADMIN\",\"instituteId\":\"inst_apex\",\"password\":\"123456\"}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals(initialUserCount, database.userDao().getUsersByInstitute("inst_apex").size)
    }

    // -------------------------------------------------------------
    // DATA AND NETWORK SECURITY TESTS (16-25)
    // -------------------------------------------------------------

    @Test
    fun test16_malformedRequestBodiesAreRejected() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepository, rateLimiter)
        }
        val adminSession = sessionManager.createSession("admin_01", "Admin", "EXAMINATION_ADMIN", "inst_apex")
        val response = client.post("/api/admin/users/provision") {
            header("Authorization", "Bearer ${adminSession.token}")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("{{{ not a json }}}")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("MALFORMED_JSON"))
    }

    @Test
    fun test17_unsupportedIntegrityEventCategoriesAreRejected() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepository, rateLimiter)
        }
        val studentSession = sessionManager.createSession("student_01", "Bob", "STUDENT", "inst_apex")
        val response = client.post("/api/attempts/att_bob_101/integrity-events") {
            header("Authorization", "Bearer ${studentSession.token}")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("{\"eventId\":\"evt_01\",\"eventCategory\":\"SUSPICIOUS_FACIAL_LOOKAWAY\",\"deviceReportedAt\":\"2026-10-09T09:10:00Z\",\"sourceDeviceId\":\"tab_01\"}")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("UNSUPPORTED_CATEGORY"))
    }

    @Test
    fun test18_missingOptionalAppNamesAreHandledCorrectly() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepository, rateLimiter)
        }
        val studentSession = sessionManager.createSession("student_01", "Bob", "STUDENT", "inst_apex")
        val response = client.post("/api/attempts/att_bob_101/integrity-events") {
            header("Authorization", "Bearer ${studentSession.token}")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("{\"eventId\":\"evt_switch_01\",\"eventCategory\":\"UNAUTHORISED_APP_SWITCH\",\"deviceReportedAt\":\"2026-10-09T09:15:00Z\",\"sourceDeviceId\":\"tab_01\"}")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val saved = database.integrityEventDao().getEventById("evt_switch_01")
        assertNotNull(saved)
        assertNull(saved?.appPackageName)
        assertNull(saved?.displayedAppName)
    }

    @Test
    fun test19_repeatedIntegrityEventIdsAreDeduplicated() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepository, rateLimiter)
        }
        val studentSession = sessionManager.createSession("student_01", "Bob", "STUDENT", "inst_apex")
        val body = "{\"eventId\":\"evt_dup_01\",\"eventCategory\":\"WINDOW_BLUR\",\"deviceReportedAt\":\"2026-10-09T09:15:00Z\",\"sourceDeviceId\":\"tab_01\"}"

        val resp1 = client.post("/api/attempts/att_bob_101/integrity-events") {
            header("Authorization", "Bearer ${studentSession.token}")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(body)
        }
        assertEquals(HttpStatusCode.Created, resp1.status)

        // Resubmit duplicate event ID
        val resp2 = client.post("/api/attempts/att_bob_101/integrity-events") {
            header("Authorization", "Bearer ${studentSession.token}")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, resp2.status)
        assertTrue(resp2.bodyAsText().contains("DUPLICATE_IGNORED"))
    }

    @Test
    fun test20_repeatedIdempotentAnswerRequests() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepository, rateLimiter)
        }
        val studentSession = sessionManager.createSession("student_01", "Bob", "STUDENT", "inst_apex")
        val body = "{\"questionId\":\"q_math_01\",\"responsePayload\":\"C\",\"revisionSequence\":1}"

        val r1 = client.post("/api/attempts/att_bob_101/answers") {
            header("Authorization", "Bearer ${studentSession.token}")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, r1.status)

        // Same answer update idempotent re-send
        val r2 = client.post("/api/attempts/att_bob_101/answers") {
            header("Authorization", "Bearer ${studentSession.token}")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, r2.status)

        val answer = database.candidateResponseDao().getResponse("att_bob_101", "q_math_01")
        assertEquals("C", answer?.responsePayload)
    }

    @Test
    fun test22_and_23_serverConfigValidation() {
        // Valid port
        val validConfig = ServerConfig(port = 8443)
        assertEquals(8443, validConfig.port)

        // Invalid ports (< 1024 or > 65535) rejected
        try {
            ServerConfig(port = 80)
            org.junit.Assert.fail("Expected exception for port < 1024")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Port must be between") == true)
        }

        try {
            ServerConfig(port = 70000)
            org.junit.Assert.fail("Expected exception for port > 65535")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Port must be between") == true)
        }
    }

    @Test
    fun test24_secureTransportTlsGeneration() {
        val tls = TlsCertificateManager.generateSelfSignedCertificate("DOOMEDAMS-Test-Node")
        assertNotNull(tls.keyStore)
        assertTrue(tls.sha256Fingerprint.startsWith("SHA256:") || tls.sha256Fingerprint.length == 95)
        assertTrue(tls.certificatePem.contains("BEGIN CERTIFICATE"))
    }
}
