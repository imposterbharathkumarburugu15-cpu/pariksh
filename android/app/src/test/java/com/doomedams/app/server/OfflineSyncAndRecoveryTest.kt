package com.doomedams.app.server

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.doomedams.app.database.AppDatabase
import com.doomedams.app.database.audit.AuditEngine
import com.doomedams.app.database.entity.ExamAttemptEntity
import com.doomedams.app.database.entity.ExamEntity
import com.doomedams.app.database.entity.QuestionEntity
import com.doomedams.app.database.entity.UserCredentialEntity
import com.doomedams.app.database.entity.UserEntity
import com.doomedams.app.database.repository.AttemptRepository
import com.doomedams.app.database.repository.ExamRepository
import com.doomedams.app.server.auth.SessionManager
import com.doomedams.app.server.routes.configureExamServerRoutes
import com.doomedams.app.server.security.PasswordHasher
import com.doomedams.app.server.security.RateLimiter
import com.doomedams.app.server.security.TlsCertificateManager
import com.doomedams.app.server.sync.SyncEventEnvelope
import com.doomedams.app.server.sync.SyncService
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class OfflineSyncAndRecoveryTest {

    private lateinit var database: AppDatabase
    private lateinit var sessionManager: SessionManager
    private lateinit var auditEngine: AuditEngine
    private lateinit var attemptRepository: AttemptRepository
    private lateinit var examRepository: ExamRepository
    private lateinit var rateLimiter: RateLimiter
    private lateinit var syncService: SyncService

    private val studentId = "usr_student_sync_01"
    private val foreignStudentId = "usr_student_sync_02"
    private val adminId = "usr_admin_sync"
    private val examId = "exam_sync_01"
    private val instituteId = "inst_apex"

    private lateinit var studentToken: String
    private lateinit var foreignToken: String
    private lateinit var adminToken: String

    @Before
    fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(AppDatabase.DatabaseSecurityCallback)
            .build()

        sessionManager = SessionManager()
        auditEngine = AuditEngine(database)
        attemptRepository = AttemptRepository(database, auditEngine)
        examRepository = ExamRepository(database, auditEngine)
        rateLimiter = RateLimiter(maxAttempts = 10, windowSeconds = 60)
        syncService = SyncService(database, auditEngine, attemptRepository)

        seedInitialData()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun seedInitialData() {
        val nowIso = Instant.now().toString()
        val (hash1, salt1) = PasswordHasher.hashPassword("Password@123")
        val (hash2, salt2) = PasswordHasher.hashPassword("Password@123")
        val (hash3, salt3) = PasswordHasher.hashPassword("Password@123")

        // Users
        val student = UserEntity(
            userId = studentId,
            instituteId = instituteId,
            role = "STUDENT",
            displayName = "Sync Student 1",
            accountStatus = "ACTIVE",
            createdAt = nowIso
        )
        val foreignStudent = UserEntity(
            userId = foreignStudentId,
            instituteId = instituteId,
            role = "STUDENT",
            displayName = "Sync Student 2",
            accountStatus = "ACTIVE",
            createdAt = nowIso
        )
        val admin = UserEntity(
            userId = adminId,
            instituteId = instituteId,
            role = "EXAMINATION_ADMIN",
            displayName = "Exam Admin",
            accountStatus = "ACTIVE",
            createdAt = nowIso
        )
        database.userDao().insertUser(student)
        database.userDao().insertUser(foreignStudent)
        database.userDao().insertUser(admin)

        database.userCredentialDao().insertCredential(
            UserCredentialEntity(userId = studentId, passwordHash = hash1, salt = salt1, updatedAt = nowIso)
        )
        database.userCredentialDao().insertCredential(
            UserCredentialEntity(userId = foreignStudentId, passwordHash = hash2, salt = salt2, updatedAt = nowIso)
        )
        database.userCredentialDao().insertCredential(
            UserCredentialEntity(userId = adminId, passwordHash = hash3, salt = salt3, updatedAt = nowIso)
        )

        // Published Exam
        val exam = ExamEntity(
            examId = examId,
            instituteId = instituteId,
            title = "Offline Synchronisation Test Exam",
            status = "PUBLISHED",
            durationSeconds = 3600L,
            markingSchemeVersion = 1,
            createdAt = nowIso,
            updatedAt = nowIso
        )
        database.examDao().insertExam(exam)

        // Question
        database.questionDao().insertQuestion(
            QuestionEntity(
                questionId = "q_sync_01",
                examId = examId,
                sectionId = null,
                questionNumber = 1,
                questionType = "SINGLE_SELECT",
                questionText = "What is the capital of France?",
                answerOptionsJson = "[\"Berlin\",\"Paris\",\"Rome\",\"Madrid\"]",
                sourcePage = 1,
                sourceStartLine = null,
                sourceEndLine = null,
                sourceSnippet = null,
                extractionConfidence = "HIGH",
                reviewStatus = "VERIFIED",
                createdAt = nowIso,
                updatedAt = nowIso
            )
        )

        // Active Attempt for student 1
        val now = Instant.now()
        val deadline = now.plusSeconds(3600)
        val startedAtStr = DateTimeFormatter.ISO_INSTANT.format(now)
        val deadlineStr = DateTimeFormatter.ISO_INSTANT.format(deadline)

        val deadlineMeta = JSONObject().apply {
            put("officialStartTime", startedAtStr)
            put("officialDeadline", deadlineStr)
            put("durationSeconds", 3600L)
        }.toString()

        val attempt = ExamAttemptEntity(
            attemptId = "att_sync_01",
            examId = examId,
            studentId = studentId,
            attemptStatus = "IN_PROGRESS",
            resultStatus = "PENDING_GRADING",
            startedAt = startedAtStr,
            submittedAt = null,
            deadlineMetadata = deadlineMeta,
            createdAt = startedAtStr,
            updatedAt = startedAtStr
        )
        database.examAttemptDao().insertAttempt(attempt)

        // Sessions
        studentToken = sessionManager.createSession(studentId, "Student 1", "STUDENT", instituteId, examId).token
        foreignToken = sessionManager.createSession(foreignStudentId, "Student 2", "STUDENT", instituteId, examId).token
        adminToken = sessionManager.createSession(adminId, "Admin", "EXAMINATION_ADMIN", instituteId).token
    }

    @Test
    fun test01_newEventAcceptedOnce() = runBlocking {
        val payload = "{\"questionId\":\"q_sync_01\",\"responsePayload\":\"Paris\",\"revisionSequence\":1}"
        val hash = SyncService.computeSha256(payload)

        val envelope = SyncEventEnvelope(
            eventId = "evt_001",
            schemaVersion = 1,
            eventType = "ANSWER_UPDATE",
            instituteId = instituteId,
            examId = examId,
            attemptId = "att_sync_01",
            studentId = studentId,
            sourceDeviceId = "phone_01",
            sequenceNumber = 1L,
            aggregateId = "att_sync_01",
            aggregateVersion = 1,
            clientWallClockTimestamp = "2026-10-10T10:00:00Z",
            payload = payload,
            payloadHash = hash,
            deliveryAttempt = 1
        )

        val result = syncService.processSingleEvent(envelope, studentId, "STUDENT")
        assertEquals("ACCEPTED", result.status)
        assertNotNull(result.receiptId)

        // Verify stored response
        val response = database.candidateResponseDao().getResponse("att_sync_01", "q_sync_01")
        assertNotNull(response)
        assertEquals("Paris", response?.responsePayload)
        assertEquals(1, response?.revisionSequence)

        // Verify receipt
        val receipt = database.serverEventReceiptDao().getReceiptByEventId("evt_001")
        assertNotNull(receipt)
        assertEquals("ACCEPTED", receipt?.processingStatus)
    }

    @Test
    fun test02_exactDuplicateReturnsOriginalOutcomeIdempotently() = runBlocking {
        val payload = "{\"questionId\":\"q_sync_01\",\"responsePayload\":\"Paris\",\"revisionSequence\":1}"
        val hash = SyncService.computeSha256(payload)

        val envelope = SyncEventEnvelope(
            eventId = "evt_002",
            schemaVersion = 1,
            eventType = "ANSWER_UPDATE",
            instituteId = instituteId,
            examId = examId,
            attemptId = "att_sync_01",
            studentId = studentId,
            sourceDeviceId = "phone_01",
            sequenceNumber = 1L,
            aggregateId = "att_sync_01",
            aggregateVersion = 1,
            clientWallClockTimestamp = "2026-10-10T10:00:00Z",
            payload = payload,
            payloadHash = hash,
            deliveryAttempt = 1
        )

        val first = syncService.processSingleEvent(envelope, studentId, "STUDENT")
        assertEquals("ACCEPTED", first.status)

        // Retry exact same event
        val second = syncService.processSingleEvent(envelope, studentId, "STUDENT")
        assertEquals("ALREADY_PROCESSED", second.status)
        assertEquals(first.receiptId, second.receiptId)

        // Verify response revision has not increased or duplicated
        val count = database.candidateResponseDao().getResponsesByAttempt("att_sync_01").size
        assertEquals(1, count)
    }

    @Test
    fun test03_reusedEventIdWithDifferentPayloadIsRejectedAsConflict() = runBlocking {
        val payload1 = "{\"questionId\":\"q_sync_01\",\"responsePayload\":\"Paris\",\"revisionSequence\":1}"
        val hash1 = SyncService.computeSha256(payload1)

        val envelope1 = SyncEventEnvelope(
            eventId = "evt_collision_01",
            schemaVersion = 1,
            eventType = "ANSWER_UPDATE",
            instituteId = instituteId,
            examId = examId,
            attemptId = "att_sync_01",
            studentId = studentId,
            sourceDeviceId = "phone_01",
            sequenceNumber = 1L,
            aggregateId = "att_sync_01",
            aggregateVersion = 1,
            clientWallClockTimestamp = "2026-10-10T10:00:00Z",
            payload = payload1,
            payloadHash = hash1,
            deliveryAttempt = 1
        )

        val first = syncService.processSingleEvent(envelope1, studentId, "STUDENT")
        assertEquals("ACCEPTED", first.status)

        // Malicious or corrupted retry: same eventId but conflicting payload
        val payload2 = "{\"questionId\":\"q_sync_01\",\"responsePayload\":\"Berlin\",\"revisionSequence\":1}"
        val hash2 = SyncService.computeSha256(payload2)

        val envelope2 = envelope1.copy(payload = payload2, payloadHash = hash2)

        val second = syncService.processSingleEvent(envelope2, studentId, "STUDENT")
        assertEquals("CONFLICT", second.status)
        assertEquals("EVENT_ID_COLLISION_DIFFERENT_PAYLOAD: Event ID was previously recorded with a different payload hash.", second.rejectionReason)

        // Original accepted record remains completely preserved ("Paris", not "Berlin")
        val response = database.candidateResponseDao().getResponse("att_sync_01", "q_sync_01")
        assertEquals("Paris", response?.responsePayload)
    }

    @Test
    fun test04_duplicateBatchProcessesNewEventsWhilePreservingExisting() = runBlocking {
        val payload1 = "{\"questionId\":\"q_sync_01\",\"responsePayload\":\"Paris\",\"revisionSequence\":1}"
        val hash1 = SyncService.computeSha256(payload1)
        val envelope1 = SyncEventEnvelope(
            eventId = "evt_batch_01",
            schemaVersion = 1,
            eventType = "ANSWER_UPDATE",
            instituteId = instituteId,
            examId = examId,
            attemptId = "att_sync_01",
            studentId = studentId,
            sourceDeviceId = "phone_01",
            sequenceNumber = 1L,
            aggregateId = "att_sync_01",
            aggregateVersion = 1,
            clientWallClockTimestamp = "2026-10-10T10:00:00Z",
            payload = payload1,
            payloadHash = hash1,
            deliveryAttempt = 1
        )

        // Pre-process event 1
        syncService.processSingleEvent(envelope1, studentId, "STUDENT")

        // New event 2 (Heartbeat)
        val envelope2 = SyncEventEnvelope(
            eventId = "evt_batch_02",
            schemaVersion = 1,
            eventType = "HEARTBEAT",
            instituteId = instituteId,
            examId = examId,
            attemptId = "att_sync_01",
            studentId = studentId,
            sourceDeviceId = "phone_01",
            sequenceNumber = 2L,
            aggregateId = "att_sync_01",
            aggregateVersion = 1,
            clientWallClockTimestamp = "2026-10-10T10:01:00Z",
            payload = "{}",
            payloadHash = SyncService.computeSha256("{}"),
            deliveryAttempt = 1
        )

        val batchResult = syncService.processBatch(listOf(envelope1, envelope2), studentId, "STUDENT")
        assertEquals("SUCCESS", batchResult.status)
        assertEquals(2, batchResult.totalReceived)
        assertEquals(1, batchResult.acceptedCount)
        assertEquals(1, batchResult.alreadyProcessedCount)
        assertEquals("ALREADY_PROCESSED", batchResult.results[0].status)
        assertEquals("ACCEPTED", batchResult.results[1].status)
    }

    @Test
    fun test05_staleRevisionIsRejected() = runBlocking {
        // First accept revision 2
        val payloadRev2 = "{\"questionId\":\"q_sync_01\",\"responsePayload\":\"Paris\",\"revisionSequence\":2}"
        val envelopeRev2 = SyncEventEnvelope(
            eventId = "evt_rev2",
            schemaVersion = 1,
            eventType = "ANSWER_UPDATE",
            instituteId = instituteId,
            examId = examId,
            attemptId = "att_sync_01",
            studentId = studentId,
            sourceDeviceId = "phone_01",
            sequenceNumber = 2L,
            aggregateId = "att_sync_01",
            aggregateVersion = 2,
            clientWallClockTimestamp = "2026-10-10T10:02:00Z",
            payload = payloadRev2,
            payloadHash = SyncService.computeSha256(payloadRev2)
        )
        syncService.processSingleEvent(envelopeRev2, studentId, "STUDENT")

        // An older revision (rev 1) arriving delayed from an out-of-order queue
        val payloadRev1 = "{\"questionId\":\"q_sync_01\",\"responsePayload\":\"Rome\",\"revisionSequence\":1}"
        val envelopeRev1 = SyncEventEnvelope(
            eventId = "evt_rev1",
            schemaVersion = 1,
            eventType = "ANSWER_UPDATE",
            instituteId = instituteId,
            examId = examId,
            attemptId = "att_sync_01",
            studentId = studentId,
            sourceDeviceId = "phone_01",
            sequenceNumber = 1L,
            aggregateId = "att_sync_01",
            aggregateVersion = 1,
            clientWallClockTimestamp = "2026-10-10T10:01:00Z",
            payload = payloadRev1,
            payloadHash = SyncService.computeSha256(payloadRev1)
        )
        val result = syncService.processSingleEvent(envelopeRev1, studentId, "STUDENT")
        assertEquals("CONFLICT", result.status)
        assertTrue(result.rejectionReason?.contains("STALE_REVISION") == true)

        // Confirms Paris (rev 2) remains active in database
        val active = database.candidateResponseDao().getResponse("att_sync_01", "q_sync_01")
        assertEquals(2, active?.revisionSequence)
        assertEquals("Paris", active?.responsePayload)
    }

    @Test
    fun test06_strictlyNewerRevisionIsAccepted() = runBlocking {
        // Accept rev 2
        val payloadRev2 = "{\"questionId\":\"q_sync_01\",\"responsePayload\":\"Paris\",\"revisionSequence\":2}"
        val envelopeRev2 = SyncEventEnvelope(
            eventId = "evt_new_rev2",
            schemaVersion = 1,
            eventType = "ANSWER_UPDATE",
            instituteId = instituteId,
            examId = examId,
            attemptId = "att_sync_01",
            studentId = studentId,
            sourceDeviceId = "phone_01",
            sequenceNumber = 2L,
            aggregateId = "att_sync_01",
            aggregateVersion = 2,
            clientWallClockTimestamp = "2026-10-10T10:02:00Z",
            payload = payloadRev2,
            payloadHash = SyncService.computeSha256(payloadRev2)
        )
        syncService.processSingleEvent(envelopeRev2, studentId, "STUDENT")

        // Now accept strictly newer rev 3
        val payloadRev3 = "{\"questionId\":\"q_sync_01\",\"responsePayload\":\"Paris (Final)\",\"revisionSequence\":3}"
        val envelopeRev3 = SyncEventEnvelope(
            eventId = "evt_new_rev3",
            schemaVersion = 1,
            eventType = "ANSWER_UPDATE",
            instituteId = instituteId,
            examId = examId,
            attemptId = "att_sync_01",
            studentId = studentId,
            sourceDeviceId = "phone_01",
            sequenceNumber = 3L,
            aggregateId = "att_sync_01",
            aggregateVersion = 3,
            clientWallClockTimestamp = "2026-10-10T10:03:00Z",
            payload = payloadRev3,
            payloadHash = SyncService.computeSha256(payloadRev3)
        )
        val res3 = syncService.processSingleEvent(envelopeRev3, studentId, "STUDENT")
        assertEquals("ACCEPTED", res3.status)
        assertEquals(3, res3.resultingVersion)

        val updated = database.candidateResponseDao().getResponse("att_sync_01", "q_sync_01")
        assertEquals(3, updated?.revisionSequence)
        assertEquals("Paris (Final)", updated?.responsePayload)
    }

    @Test
    fun test07_duplicateFinalSubmissionReturnsOriginalReceipt() = runBlocking {
        val payload = "{\"clientChecksum\":\"chk_test_123\",\"totalQuestionsAnswered\":1}"
        val envelope = SyncEventEnvelope(
            eventId = "sub_att_sync_01",
            schemaVersion = 1,
            eventType = "FINAL_SUBMISSION",
            instituteId = instituteId,
            examId = examId,
            attemptId = "att_sync_01",
            studentId = studentId,
            sourceDeviceId = "phone_01",
            sequenceNumber = 10L,
            aggregateId = "att_sync_01",
            aggregateVersion = 1,
            clientWallClockTimestamp = "2026-10-10T11:00:00Z",
            payload = payload,
            payloadHash = SyncService.computeSha256(payload)
        )

        val first = syncService.processSingleEvent(envelope, studentId, "STUDENT")
        assertEquals("ACCEPTED", first.status)
        assertNotNull(first.receiptHash)

        // Retry final submission (e.g. lost HTTP ACK)
        val second = syncService.processSingleEvent(envelope, studentId, "STUDENT")
        assertEquals("ALREADY_PROCESSED", second.status)
        assertEquals(first.receiptHash, second.receiptHash)

        // Verify attempt is marked SUBMITTED once
        val attempt = database.examAttemptDao().getAttemptById("att_sync_01")
        assertEquals("SUBMITTED", attempt?.attemptStatus)
    }

    @Test
    fun test08_authoritativeDeadlineEnforced() = runBlocking {
        // Seed an attempt that expired 1 hour ago
        val pastStart = Instant.now().minusSeconds(7200)
        val pastDeadline = Instant.now().minusSeconds(3600)
        val deadlineMeta = JSONObject().apply {
            put("officialStartTime", DateTimeFormatter.ISO_INSTANT.format(pastStart))
            put("officialDeadline", DateTimeFormatter.ISO_INSTANT.format(pastDeadline))
            put("durationSeconds", 3600L)
        }.toString()

        val expiredAttempt = ExamAttemptEntity(
            attemptId = "att_expired_01",
            examId = examId,
            studentId = studentId,
            attemptStatus = "IN_PROGRESS",
            resultStatus = "PENDING_GRADING",
            startedAt = DateTimeFormatter.ISO_INSTANT.format(pastStart),
            submittedAt = null,
            deadlineMetadata = deadlineMeta,
            createdAt = DateTimeFormatter.ISO_INSTANT.format(pastStart),
            updatedAt = DateTimeFormatter.ISO_INSTANT.format(pastStart)
        )
        database.examAttemptDao().insertAttempt(expiredAttempt)

        val payload = "{\"questionId\":\"q_sync_01\",\"responsePayload\":\"Late Answer\",\"revisionSequence\":1}"
        val envelope = SyncEventEnvelope(
            eventId = "evt_late_01",
            schemaVersion = 1,
            eventType = "ANSWER_UPDATE",
            instituteId = instituteId,
            examId = examId,
            attemptId = "att_expired_01",
            studentId = studentId,
            sourceDeviceId = "phone_01",
            sequenceNumber = 1L,
            aggregateId = "att_expired_01",
            aggregateVersion = 1,
            clientWallClockTimestamp = "2026-10-10T11:00:00Z",
            payload = payload,
            payloadHash = SyncService.computeSha256(payload)
        )

        val result = syncService.processSingleEvent(envelope, studentId, "STUDENT")
        assertEquals("LATE_REJECTED", result.status)
        assertEquals("DEADLINE_EXPIRED", result.rejectionReason)

        // Receipt is preserved in durable store
        val receipt = database.serverEventReceiptDao().getReceiptByEventId("evt_late_01")
        assertNotNull(receipt)
        assertEquals("LATE_REJECTED", receipt?.processingStatus)
    }

    @Test
    fun test09_lateAnswerWithinGracePeriodAcceptedWithLateStatus() = runBlocking {
        // Expired 30 seconds ago (within 120s network grace)
        val pastStart = Instant.now().minusSeconds(3630)
        val pastDeadline = Instant.now().minusSeconds(30)
        val deadlineMeta = JSONObject().apply {
            put("officialStartTime", DateTimeFormatter.ISO_INSTANT.format(pastStart))
            put("officialDeadline", DateTimeFormatter.ISO_INSTANT.format(pastDeadline))
            put("durationSeconds", 3600L)
        }.toString()

        val graceAttempt = ExamAttemptEntity(
            attemptId = "att_grace_01",
            examId = examId,
            studentId = studentId,
            attemptStatus = "IN_PROGRESS",
            resultStatus = "PENDING_GRADING",
            startedAt = DateTimeFormatter.ISO_INSTANT.format(pastStart),
            submittedAt = null,
            deadlineMetadata = deadlineMeta,
            createdAt = DateTimeFormatter.ISO_INSTANT.format(pastStart),
            updatedAt = DateTimeFormatter.ISO_INSTANT.format(pastStart)
        )
        database.examAttemptDao().insertAttempt(graceAttempt)

        val payload = "{\"questionId\":\"q_sync_01\",\"responsePayload\":\"Grace Answer\",\"revisionSequence\":1}"
        val envelope = SyncEventEnvelope(
            eventId = "evt_grace_01",
            schemaVersion = 1,
            eventType = "ANSWER_UPDATE",
            instituteId = instituteId,
            examId = examId,
            attemptId = "att_grace_01",
            studentId = studentId,
            sourceDeviceId = "phone_01",
            sequenceNumber = 1L,
            aggregateId = "att_grace_01",
            aggregateVersion = 1,
            clientWallClockTimestamp = "2026-10-10T11:00:00Z",
            payload = payload,
            payloadHash = SyncService.computeSha256(payload)
        )

        val result = syncService.processSingleEvent(envelope, studentId, "STUDENT")
        assertEquals("LATE_ACCEPTED", result.status)

        val receipt = database.serverEventReceiptDao().getReceiptByEventId("evt_grace_01")
        assertEquals("LATE_ACCEPTED", receipt?.processingStatus)
    }

    @Test
    fun test10_serverRestartRecoveryPreservesAcceptedResponsesAndReceipts() = runBlocking {
        // Accept answer
        val payload = "{\"questionId\":\"q_sync_01\",\"responsePayload\":\"Persistent Answer\",\"revisionSequence\":1}"
        val envelope = SyncEventEnvelope(
            eventId = "evt_persist_01",
            schemaVersion = 1,
            eventType = "ANSWER_UPDATE",
            instituteId = instituteId,
            examId = examId,
            attemptId = "att_sync_01",
            studentId = studentId,
            sourceDeviceId = "phone_01",
            sequenceNumber = 1L,
            aggregateId = "att_sync_01",
            aggregateVersion = 1,
            clientWallClockTimestamp = "2026-10-10T10:00:00Z",
            payload = payload,
            payloadHash = SyncService.computeSha256(payload)
        )
        syncService.processSingleEvent(envelope, studentId, "STUDENT")

        // Simulate Examiner Server Restart:
        // Re-query database to simulate new service initialization
        val reopenedAttempt = database.examAttemptDao().getAttemptById("att_sync_01")
        assertNotNull(reopenedAttempt)
        assertEquals("IN_PROGRESS", reopenedAttempt?.attemptStatus)

        val reopenedReceipt = database.serverEventReceiptDao().getReceiptByEventId("evt_persist_01")
        assertNotNull(reopenedReceipt)
        assertEquals("ACCEPTED", reopenedReceipt?.processingStatus)

        val reopenedResponse = database.candidateResponseDao().getResponse("att_sync_01", "q_sync_01")
        assertNotNull(reopenedResponse)
        assertEquals("Persistent Answer", reopenedResponse?.responsePayload)
    }

    @Test
    fun test11_persistentTlsCertificateIdentityConstantAcrossRestarts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storageDir = File(context.filesDir, "tls_test_${System.currentTimeMillis()}")
        storageDir.mkdirs()

        // 1. First start: Generates persistent certificate
        val config1 = TlsCertificateManager.getOrCreatePersistentCertificate(storageDir)
        assertNotNull(config1)
        val fingerprint1 = config1.sha256Fingerprint
        val pem1 = config1.certificatePem
        assertTrue(fingerprint1.isNotBlank())
        assertTrue(pem1.startsWith("-----BEGIN CERTIFICATE-----"))

        // 2. Second start (simulated server restart): Must reload the EXACT same certificate
        val config2 = TlsCertificateManager.getOrCreatePersistentCertificate(storageDir)
        val fingerprint2 = config2.sha256Fingerprint
        val pem2 = config2.certificatePem

        assertEquals(fingerprint1, fingerprint2)
        assertEquals(pem1, pem2)
    }

    @Test
    fun test12_reconcileEndpointReturnsAccurateAuthoritativeState() = runBlocking {
        // Record an answer
        val payload = "{\"questionId\":\"q_sync_01\",\"responsePayload\":\"Reconcile Answer\",\"revisionSequence\":4}"
        val envelope = SyncEventEnvelope(
            eventId = "evt_recon_01",
            schemaVersion = 1,
            eventType = "ANSWER_UPDATE",
            instituteId = instituteId,
            examId = examId,
            attemptId = "att_sync_01",
            studentId = studentId,
            sourceDeviceId = "phone_01",
            sequenceNumber = 4L,
            aggregateId = "att_sync_01",
            aggregateVersion = 4,
            clientWallClockTimestamp = "2026-10-10T10:00:00Z",
            payload = payload,
            payloadHash = SyncService.computeSha256(payload)
        )
        syncService.processSingleEvent(envelope, studentId, "STUDENT")

        val state = syncService.reconcileAttempt("att_sync_01", studentId)
        assertNotNull(state)
        assertEquals("att_sync_01", state?.attemptId)
        assertEquals("IN_PROGRESS", state?.attemptStatus)
        assertEquals(1, state?.totalQuestionsAnswered)
        assertEquals(4, state?.lastAcceptedRevisions?.get("q_sync_01"))
        assertFalse(state!!.isDeadlineExpired)
        assertTrue(state.deadlineRemainingSeconds > 0)
    }

    @Test
    fun test13_crossStudentEventRejection() = runBlocking {
        val payload = "{\"questionId\":\"q_sync_01\",\"responsePayload\":\"Hacked Answer\",\"revisionSequence\":1}"
        val envelope = SyncEventEnvelope(
            eventId = "evt_hack_01",
            schemaVersion = 1,
            eventType = "ANSWER_UPDATE",
            instituteId = instituteId,
            examId = examId,
            attemptId = "att_sync_01",
            studentId = studentId,
            sourceDeviceId = "phone_01",
            sequenceNumber = 1L,
            aggregateId = "att_sync_01",
            aggregateVersion = 1,
            clientWallClockTimestamp = "2026-10-10T10:00:00Z",
            payload = payload,
            payloadHash = SyncService.computeSha256(payload)
        )

        // Authenticated user is foreignStudentId, claiming to be studentId
        val result = syncService.processSingleEvent(envelope, foreignStudentId, "STUDENT")
        assertEquals("REJECTED", result.status)
        assertTrue(result.rejectionReason?.contains("STUDENT_IDENTITY_MISMATCH") == true)
    }

    @Test
    fun test14_syncObservabilityOverviewReturnsAccurateCounts() = runBlocking {
        val payload = "{\"questionId\":\"q_sync_01\",\"responsePayload\":\"Answer A\",\"revisionSequence\":1}"
        val envelope = SyncEventEnvelope(
            eventId = "evt_obs_01",
            schemaVersion = 1,
            eventType = "ANSWER_UPDATE",
            instituteId = instituteId,
            examId = examId,
            attemptId = "att_sync_01",
            studentId = studentId,
            sourceDeviceId = "phone_01",
            sequenceNumber = 1L,
            aggregateId = "att_sync_01",
            aggregateVersion = 1,
            clientWallClockTimestamp = "2026-10-10T10:00:00Z",
            payload = payload,
            payloadHash = SyncService.computeSha256(payload)
        )
        syncService.processSingleEvent(envelope, studentId, "STUDENT")

        val overview = syncService.getSyncObservabilityOverview()
        assertTrue(overview.getInt("totalReceipts") >= 1)
        assertTrue(overview.getInt("acceptedCount") >= 1)
        assertEquals(1, overview.getInt("activeAttemptsCount"))
    }

    @Test
    fun test15_httpSyncRoutesWithTestApplication() = testApplication {
        application {
            configureExamServerRoutes(
                database = database,
                sessionManager = sessionManager,
                auditEngine = auditEngine,
                attemptRepository = attemptRepository,
                examRepository = examRepository,
                rateLimiter = rateLimiter,
                tlsFingerprint = "AA:BB:CC:DD:EE:FF"
            )
        }

        val client = createClient { }

        // 1. Submit batch via POST /api/sync/events
        val payload = "{\"questionId\":\"q_sync_01\",\"responsePayload\":\"HTTP Answer\",\"revisionSequence\":1}"
        val hash = SyncService.computeSha256(payload)

        val batchBody = JSONObject().apply {
            put("events", JSONArray().apply {
                put(JSONObject().apply {
                    put("eventId", "evt_http_01")
                    put("schemaVersion", 1)
                    put("eventType", "ANSWER_UPDATE")
                    put("instituteId", instituteId)
                    put("examId", examId)
                    put("attemptId", "att_sync_01")
                    put("studentId", studentId)
                    put("sourceDeviceId", "device_http")
                    put("sequenceNumber", 1)
                    put("aggregateId", "att_sync_01")
                    put("aggregateVersion", 1)
                    put("clientWallClockTimestamp", "2026-10-10T10:00:00Z")
                    put("payload", payload)
                    put("payloadHash", hash)
                    put("deliveryAttempt", 1)
                })
            })
        }

        val resEvents = client.post("/api/sync/events") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(batchBody.toString())
        }

        assertEquals(HttpStatusCode.OK, resEvents.status)
        val resJson = JSONObject(resEvents.bodyAsText())
        assertEquals("SUCCESS", resJson.getString("status"))
        assertEquals(1, resJson.getInt("acceptedCount"))

        // 2. Reconcile via POST /api/sync/reconcile
        val resReconcile = client.post("/api/sync/reconcile") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(JSONObject().apply { put("attemptId", "att_sync_01") }.toString())
        }

        assertEquals(HttpStatusCode.OK, resReconcile.status)
        val recJson = JSONObject(resReconcile.bodyAsText())
        assertEquals("att_sync_01", recJson.getString("attemptId"))
        assertEquals(1, recJson.getJSONObject("lastAcceptedRevisions").getInt("q_sync_01"))

        // 3. Staff Observability via GET /api/sync/status
        val resStatus = client.get("/api/sync/status") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }

        assertEquals(HttpStatusCode.OK, resStatus.status)
        val statJson = JSONObject(resStatus.bodyAsText())
        assertTrue(statJson.getInt("totalReceipts") >= 1)
        assertTrue(statJson.getInt("acceptedCount") >= 1)
    }

    @Test
    fun test16_finalSubmissionPastGracePeriodIsRejected() = runBlocking {
        // Attempt expired 1 hour ago
        val pastStart = Instant.now().minusSeconds(7200)
        val pastDeadline = Instant.now().minusSeconds(3600)
        val deadlineMeta = JSONObject().apply {
            put("officialStartTime", DateTimeFormatter.ISO_INSTANT.format(pastStart))
            put("officialDeadline", DateTimeFormatter.ISO_INSTANT.format(pastDeadline))
            put("durationSeconds", 3600L)
        }.toString()

        val expiredAttempt = ExamAttemptEntity(
            attemptId = "att_expired_sub_01",
            examId = examId,
            studentId = studentId,
            attemptStatus = "IN_PROGRESS",
            resultStatus = "PENDING_GRADING",
            startedAt = DateTimeFormatter.ISO_INSTANT.format(pastStart),
            submittedAt = null,
            deadlineMetadata = deadlineMeta,
            createdAt = DateTimeFormatter.ISO_INSTANT.format(pastStart),
            updatedAt = DateTimeFormatter.ISO_INSTANT.format(pastStart)
        )
        database.examAttemptDao().insertAttempt(expiredAttempt)

        val payload = "{\"clientChecksum\":\"chk_late_999\",\"totalQuestionsAnswered\":2}"
        val envelope = SyncEventEnvelope(
            eventId = "sub_late_expired_01",
            schemaVersion = 1,
            eventType = "FINAL_SUBMISSION",
            instituteId = instituteId,
            examId = examId,
            attemptId = "att_expired_sub_01",
            studentId = studentId,
            sourceDeviceId = "phone_01",
            sequenceNumber = 5L,
            aggregateId = "att_expired_sub_01",
            aggregateVersion = 1,
            clientWallClockTimestamp = "2026-10-10T12:00:00Z",
            payload = payload,
            payloadHash = SyncService.computeSha256(payload)
        )

        val result = syncService.processSingleEvent(envelope, studentId, "STUDENT")
        assertEquals("LATE_REJECTED", result.status)
        assertEquals("DEADLINE_EXPIRED", result.rejectionReason)

        // Verifies the attempt status was NOT modified to SUBMITTED
        val attemptInDb = database.examAttemptDao().getAttemptById("att_expired_sub_01")
        assertEquals("IN_PROGRESS", attemptInDb?.attemptStatus)

        // Receipt is preserved in durable inbox
        val receipt = database.serverEventReceiptDao().getReceiptByEventId("sub_late_expired_01")
        assertNotNull(receipt)
        assertEquals("LATE_REJECTED", receipt?.processingStatus)
    }

    @Test
    fun test17_competingEditSameSequenceNumberRejectedAsConflict() = runBlocking {
        // Accept revision 2 "Berlin"
        val payload1 = "{\"questionId\":\"q_sync_01\",\"responsePayload\":\"Berlin\",\"revisionSequence\":2}"
        val envelope1 = SyncEventEnvelope(
            eventId = "evt_compete_01",
            schemaVersion = 1,
            eventType = "ANSWER_UPDATE",
            instituteId = instituteId,
            examId = examId,
            attemptId = "att_sync_01",
            studentId = studentId,
            sourceDeviceId = "phone_01",
            sequenceNumber = 2L,
            aggregateId = "att_sync_01",
            aggregateVersion = 2,
            clientWallClockTimestamp = "2026-10-10T10:02:00Z",
            payload = payload1,
            payloadHash = SyncService.computeSha256(payload1)
        )
        val res1 = syncService.processSingleEvent(envelope1, studentId, "STUDENT")
        assertEquals("ACCEPTED", res1.status)

        // A second competing event with same revision sequence 2 but different answer "Madrid"
        val payload2 = "{\"questionId\":\"q_sync_01\",\"responsePayload\":\"Madrid\",\"revisionSequence\":2}"
        val envelope2 = SyncEventEnvelope(
            eventId = "evt_compete_02",
            schemaVersion = 1,
            eventType = "ANSWER_UPDATE",
            instituteId = instituteId,
            examId = examId,
            attemptId = "att_sync_01",
            studentId = studentId,
            sourceDeviceId = "tablet_02",
            sequenceNumber = 2L,
            aggregateId = "att_sync_01",
            aggregateVersion = 2,
            clientWallClockTimestamp = "2026-10-10T10:02:05Z",
            payload = payload2,
            payloadHash = SyncService.computeSha256(payload2)
        )
        val res2 = syncService.processSingleEvent(envelope2, studentId, "STUDENT")
        assertEquals("CONFLICT", res2.status)
        assertTrue(res2.rejectionReason?.contains("COMPETING_REVISION_CONFLICT") == true)

        // Original answer "Berlin" remains intact
        val active = database.candidateResponseDao().getResponse("att_sync_01", "q_sync_01")
        assertEquals("Berlin", active?.responsePayload)
        assertEquals(2, active?.revisionSequence)

        // Both receipts exist in server_event_receipts table
        val r1 = database.serverEventReceiptDao().getReceiptByEventId("evt_compete_01")
        val r2 = database.serverEventReceiptDao().getReceiptByEventId("evt_compete_02")
        assertNotNull(r1)
        assertNotNull(r2)
        assertEquals("ACCEPTED", r1?.processingStatus)
        assertEquals("CONFLICT", r2?.processingStatus)
    }
}
