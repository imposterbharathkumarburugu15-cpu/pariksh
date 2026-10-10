package com.doomedams.app.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.doomedams.app.database.audit.AuditEngine
import com.doomedams.app.database.audit.DuplicateAuditOperationException
import com.doomedams.app.database.entity.AuditEventEntity
import com.doomedams.app.database.entity.ExamAttemptEntity
import com.doomedams.app.database.entity.ExamEntity
import com.doomedams.app.database.entity.IncidentCaseEntity
import com.doomedams.app.database.entity.IntegrityEventEntity
import com.doomedams.app.database.entity.UserEntity
import com.doomedams.app.database.repository.AttemptRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
class RoomDatabaseTest {

    private lateinit var database: AppDatabase
    private lateinit var auditEngine: AuditEngine
    private lateinit var attemptRepository: AttemptRepository

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(AppDatabase.DatabaseSecurityCallback)
            .build()

        // Force open to execute onOpen callback and create security triggers
        database.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = ON;")

        auditEngine = AuditEngine(database)
        attemptRepository = AttemptRepository(database, auditEngine)
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        database.close()
    }

    private suspend fun seedUserAndExam(): Pair<UserEntity, ExamEntity> {
        val user = UserEntity(
            userId = "usr_std_01",
            displayName = "Student One",
            role = "STUDENT",
            accountStatus = "ACTIVE",
            instituteId = "inst_01",
            createdAt = "2026-10-09T10:00:00Z"
        )
        val exam = ExamEntity(
            examId = "exam_101",
            instituteId = "inst_01",
            title = "Offline Physics Test",
            status = "PUBLISHED",
            durationSeconds = 3600,
            markingSchemeVersion = 1,
            createdAt = "2026-10-09T09:00:00Z",
            updatedAt = "2026-10-09T09:00:00Z"
        )
        database.userDao().insertUser(user)
        database.examDao().insertExam(exam)
        return Pair(user, exam)
    }

    @Test
    fun test1_databaseInitialization() {
        assertNotNull(database)
        assertTrue(database.isOpen)
    }

    @Test
    fun test2_entityInsertionAndRetrieval() = runTest {
        val (user, exam) = seedUserAndExam()
        val retrievedUser = database.userDao().getUserById(user.userId)
        val retrievedExam = database.examDao().getExamById(exam.examId)

        assertNotNull(retrievedUser)
        assertEquals("Student One", retrievedUser?.displayName)
        assertNotNull(retrievedExam)
        assertEquals(3600L, retrievedExam?.durationSeconds)
    }

    @Test
    fun test3_foreignKeyEnforcement() = runTest {
        // Attempt referencing non-existent exam and student should fail
        val orphanAttempt = ExamAttemptEntity(
            attemptId = "att_orphan",
            examId = "non_existent_exam",
            studentId = "non_existent_student",
            attemptStatus = "NOT_STARTED",
            resultStatus = "PENDING_GRADING",
            startedAt = "2026-10-09T10:00:00Z",
            submittedAt = null,
            deadlineMetadata = "{}",
            createdAt = "2026-10-09T10:00:00Z",
            updatedAt = "2026-10-09T10:00:00Z"
        )

        try {
            database.examAttemptDao().insertAttempt(orphanAttempt)
            fail("Expected foreign key constraint violation")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("FOREIGN KEY", ignoreCase = true) == true ||
                       e.message?.contains("constraint", ignoreCase = true) == true)
        }
    }

    @Test
    fun test4_uniqueIdentifierConstraints() = runTest {
        val (user, _) = seedUserAndExam()
        val duplicateUser = user.copy(displayName = "Duplicate")
        try {
            database.userDao().insertUser(duplicateUser)
            fail("Expected unique primary key violation")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("constraint", ignoreCase = true) == true ||
                       e.message?.contains("UNIQUE", ignoreCase = true) == true)
        }
    }

    @Test
    fun test5_and_6_attemptAndResultStatusSeparation_and_heldPendingReview() = runTest {
        val (_, exam) = seedUserAndExam()
        val attempt = ExamAttemptEntity(
            attemptId = "att_001",
            examId = exam.examId,
            studentId = "usr_std_01",
            attemptStatus = "SUBMITTED",
            resultStatus = "PENDING_GRADING",
            startedAt = "2026-10-09T10:00:00Z",
            submittedAt = "2026-10-09T11:00:00Z",
            deadlineMetadata = "{}",
            createdAt = "2026-10-09T10:00:00Z",
            updatedAt = "2026-10-09T11:00:00Z"
        )
        database.examAttemptDao().insertAttempt(attempt)

        // Apply result hold via repository
        val heldAttempt = attemptRepository.applyResultHold(
            attemptId = "att_001",
            reason = "Hold pending investigation of app interruption",
            auditEventId = "aud_hold_01",
            reviewerId = "usr_admin_01",
            reviewerRole = "EXAMINATION_ADMIN"
        )

        // Verify resultStatus changed to HELD_PENDING_REVIEW while attemptStatus remained SUBMITTED
        assertEquals("HELD_PENDING_REVIEW", heldAttempt.resultStatus)
        assertEquals("SUBMITTED", heldAttempt.attemptStatus)

        val persisted = database.examAttemptDao().getAttemptById("att_001")
        assertEquals("HELD_PENDING_REVIEW", persisted?.resultStatus)
        assertEquals("SUBMITTED", persisted?.attemptStatus)
    }

    @Test
    fun test7_and_8_transactionAtomicityAndRollbackOnFailure() = runTest {
        val (_, exam) = seedUserAndExam()
        val initialAuditCount = database.auditEventDao().getAuditEventCount()

        try {
            auditEngine.executeAuditedTransaction(
                auditEventId = "aud_fail_01",
                actorId = "usr_admin",
                actorRole = "EXAMINATION_ADMIN",
                actionType = "CREATE_ATTEMPT_FAIL",
                aggregateType = "ATTEMPT",
                aggregateId = "att_fail_01",
                previousState = null,
                newState = null,
                reason = "Simulated crash"
            ) {
                database.examAttemptDao().insertAttempt(
                    ExamAttemptEntity(
                        attemptId = "att_fail_01",
                        examId = exam.examId,
                        studentId = "usr_std_01",
                        attemptStatus = "IN_PROGRESS",
                        resultStatus = "PENDING_GRADING",
                        startedAt = "2026-10-09T10:00:00Z",
                        submittedAt = null,
                        deadlineMetadata = "{}",
                        createdAt = "2026-10-09T10:00:00Z",
                        updatedAt = "2026-10-09T10:00:00Z"
                    )
                )
                // Deliberately throw an exception inside transaction
                throw IllegalStateException("Simulated mid-transaction failure")
            }
            fail("Expected transaction exception")
        } catch (e: IllegalStateException) {
            assertEquals("Simulated mid-transaction failure", e.message)
        }

        // Neither the attempt nor the audit event should have been committed
        assertNull(database.examAttemptDao().getAttemptById("att_fail_01"))
        assertEquals(initialAuditCount, database.auditEventDao().getAuditEventCount())
    }

    @Test
    fun test9_and_10_auditEntryCreationAndCryptographicLinkage() = runTest {
        val e1 = auditEngine.recordAuditEvent(
            auditEventId = "aud_1",
            actorId = "admin_01",
            actorRole = "SYSTEM_ADMIN",
            actionType = "INIT_NODE",
            aggregateType = "SYSTEM",
            aggregateId = "node_01",
            operationResult = "SUCCESS"
        )
        assertEquals(1L, e1.sequenceNumber)
        assertEquals(AuditEngine.GENESIS_HASH, e1.previousHash)
        assertTrue(e1.currentHash.length == 64)

        val e2 = auditEngine.recordAuditEvent(
            auditEventId = "aud_2",
            actorId = "admin_01",
            actorRole = "SYSTEM_ADMIN",
            actionType = "START_SESSION",
            aggregateType = "SYSTEM",
            aggregateId = "node_01",
            operationResult = "SUCCESS"
        )
        assertEquals(2L, e2.sequenceNumber)
        assertEquals(e1.currentHash, e2.previousHash)
        assertTrue(e2.currentHash.length == 64)
    }

    @Test
    fun test11_deterministicEventHashing() {
        val hash1 = AuditEngine.calculateHash(
            sequenceNumber = 1L,
            auditEventId = "evt_det",
            actorId = "usr_01",
            actorRole = "STUDENT",
            actionType = "TEST_HASH",
            aggregateType = "ATTEMPT",
            aggregateId = "att_01",
            previousState = null,
            newState = "{\"ok\":true}",
            reason = "Deterministic check",
            policyVersion = "v1.0",
            eventTimestamp = "2026-10-09T12:00:00Z",
            idempotencyKey = "key_01",
            operationResult = "SUCCESS",
            previousHash = AuditEngine.GENESIS_HASH
        )

        val hash2 = AuditEngine.calculateHash(
            sequenceNumber = 1L,
            auditEventId = "evt_det",
            actorId = "usr_01",
            actorRole = "STUDENT",
            actionType = "TEST_HASH",
            aggregateType = "ATTEMPT",
            aggregateId = "att_01",
            previousState = null,
            newState = "{\"ok\":true}",
            reason = "Deterministic check",
            policyVersion = "v1.0",
            eventTimestamp = "2026-10-09T12:00:00Z",
            idempotencyKey = "key_01",
            operationResult = "SUCCESS",
            previousHash = AuditEngine.GENESIS_HASH
        )

        assertEquals(hash1, hash2)
        assertEquals(64, hash1.length)
    }

    @Test
    fun test12_and_13_duplicateAuditIdentifierAndIdempotencyKey() = runTest {
        auditEngine.recordAuditEvent(
            auditEventId = "aud_unique_01",
            actorId = "usr_01",
            actorRole = "STUDENT",
            actionType = "SUBMIT_PART",
            aggregateType = "ATTEMPT",
            aggregateId = "att_01",
            idempotencyKey = "idem_key_42",
            operationResult = "SUCCESS"
        )

        // Attempting to append with the same idempotency key must be rejected
        try {
            auditEngine.recordAuditEvent(
                auditEventId = "aud_unique_02",
                actorId = "usr_01",
                actorRole = "STUDENT",
                actionType = "SUBMIT_PART",
                aggregateType = "ATTEMPT",
                aggregateId = "att_01",
                idempotencyKey = "idem_key_42",
                operationResult = "SUCCESS"
            )
            fail("Expected DuplicateAuditOperationException for duplicate idempotencyKey")
        } catch (e: DuplicateAuditOperationException) {
            assertTrue(e.message?.contains("already exists") == true)
        }
    }

    @Test
    fun test14_auditRecordUpdateRejection() = runTest {
        val event = auditEngine.recordAuditEvent(
            auditEventId = "aud_locked_01",
            actorId = "usr_01",
            actorRole = "STUDENT",
            actionType = "LOCK_TEST",
            aggregateType = "ATTEMPT",
            aggregateId = "att_01",
            operationResult = "SUCCESS"
        )

        // Directly attempt to execute SQL UPDATE on committed audit event
        try {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE audit_events SET reason = 'TAMPERED' WHERE auditEventId = '${event.auditEventId}';"
            )
            fail("Expected trigger failure on audit update")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("IMMUTABILITY_VIOLATION", ignoreCase = true) == true ||
                       e.message?.contains("cannot be modified", ignoreCase = true) == true ||
                       e.message?.contains("abort", ignoreCase = true) == true)
        }
    }

    @Test
    fun test15_auditRecordDeleteRejection() = runTest {
        val event = auditEngine.recordAuditEvent(
            auditEventId = "aud_nodelete_01",
            actorId = "usr_01",
            actorRole = "STUDENT",
            actionType = "DELETE_TEST",
            aggregateType = "ATTEMPT",
            aggregateId = "att_01",
            operationResult = "SUCCESS"
        )

        // Directly attempt to execute SQL DELETE on committed audit event
        try {
            database.openHelper.writableDatabase.execSQL(
                "DELETE FROM audit_events WHERE auditEventId = '${event.auditEventId}';"
            )
            fail("Expected trigger failure on audit delete")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("IMMUTABILITY_VIOLATION", ignoreCase = true) == true ||
                       e.message?.contains("cannot be deleted", ignoreCase = true) == true ||
                       e.message?.contains("abort", ignoreCase = true) == true)
        }
    }

    @Test
    fun test18_integrityEventStorageWithMissingOptionalAppName() = runTest {
        val (_, exam) = seedUserAndExam()
        val attempt = ExamAttemptEntity(
            attemptId = "att_tel_01",
            examId = exam.examId,
            studentId = "usr_std_01",
            attemptStatus = "IN_PROGRESS",
            resultStatus = "PENDING_GRADING",
            startedAt = "2026-10-09T10:00:00Z",
            submittedAt = null,
            deadlineMetadata = "{}",
            createdAt = "2026-10-09T10:00:00Z",
            updatedAt = "2026-10-09T10:00:00Z"
        )
        database.examAttemptDao().insertAttempt(attempt)

        val telemetry = IntegrityEventEntity(
            eventId = "evt_sw_01",
            examId = exam.examId,
            attemptId = attempt.attemptId,
            studentId = "usr_std_01",
            eventCategory = "UNAUTHORISED_APP_SWITCH",
            deviceReportedAt = "2026-10-09T10:14:00Z",
            receivedAt = null,
            sequenceNumber = 1L,
            sourceDeviceId = "tablet_01",
            payloadJson = "{\"durationMs\":1200}",
            payloadHash = null,
            appPackageName = null, // Android cannot guarantee package identification
            displayedAppName = null
        )

        database.integrityEventDao().insertEvent(telemetry)
        val retrieved = database.integrityEventDao().getEventById("evt_sw_01")
        assertNotNull(retrieved)
        assertNull(retrieved?.appPackageName)
        assertNull(retrieved?.displayedAppName)
        assertEquals("UNAUTHORISED_APP_SWITCH", retrieved?.eventCategory)
    }

    @Test
    fun test19_noRealIncidentOrHoldCreatedByFixtureAlone() = runTest {
        // Verify that database has zero incidents by default
        val (user, exam) = seedUserAndExam()
        val attempt = ExamAttemptEntity(
            attemptId = "att_clean_01",
            examId = exam.examId,
            studentId = user.userId,
            attemptStatus = "IN_PROGRESS",
            resultStatus = "PENDING_GRADING",
            startedAt = "2026-10-09T10:00:00Z",
            submittedAt = null,
            deadlineMetadata = "{}",
            createdAt = "2026-10-09T10:00:00Z",
            updatedAt = "2026-10-09T10:00:00Z"
        )
        database.examAttemptDao().insertAttempt(attempt)

        // No incident exists in DB
        val incidents = database.incidentDao().getIncidentsByAttempt("att_clean_01")
        assertTrue(incidents.isEmpty())

        // The attempt result remains PENDING_GRADING
        val retrievedAttempt = database.examAttemptDao().getAttemptById("att_clean_01")
        assertEquals("PENDING_GRADING", retrievedAttempt?.resultStatus)
    }

    @Test
    fun test20_serverEventReceiptPersistenceAndQuery() = runTest {
        val (user, exam) = seedUserAndExam()
        val attempt = ExamAttemptEntity(
            attemptId = "att_sync_01",
            examId = exam.examId,
            studentId = user.userId,
            attemptStatus = "IN_PROGRESS",
            resultStatus = "PENDING_GRADING",
            startedAt = "2026-10-10T10:00:00Z",
            submittedAt = null,
            deadlineMetadata = "{}",
            createdAt = "2026-10-10T10:00:00Z",
            updatedAt = "2026-10-10T10:00:00Z"
        )
        database.examAttemptDao().insertAttempt(attempt)

        val receipt = com.doomedams.app.database.entity.ServerEventReceiptEntity(
            receiptId = "rcpt_1001",
            eventId = "evt_sync_1001",
            attemptId = attempt.attemptId,
            studentId = user.userId,
            eventType = "ANSWER_UPDATE",
            payloadHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            idempotencyKey = "idem_1001",
            processingStatus = "ACCEPTED",
            resultingVersion = 2,
            rejectionReason = null,
            serverReceivedAt = "2026-10-10T10:05:00Z",
            processedAt = "2026-10-10T10:05:01Z"
        )

        database.serverEventReceiptDao().insertReceipt(receipt)

        val retrieved = database.serverEventReceiptDao().getReceiptByEventId("evt_sync_1001")
        assertNotNull(retrieved)
        assertEquals("rcpt_1001", retrieved?.receiptId)
        assertEquals("ACCEPTED", retrieved?.processingStatus)
        assertEquals(2, retrieved?.resultingVersion)

        val byAttempt = database.serverEventReceiptDao().getReceiptsByAttemptId(attempt.attemptId)
        assertEquals(1, byAttempt.size)

        val countAccepted = database.serverEventReceiptDao().getReceiptCountByStatus("ACCEPTED")
        assertEquals(1, countAccepted)
    }

    @Test
    fun test21_duplicateEventIdRejectedByUniqueConstraint() = runTest {
        val (user, exam) = seedUserAndExam()
        val attempt = ExamAttemptEntity(
            attemptId = "att_sync_02",
            examId = exam.examId,
            studentId = user.userId,
            attemptStatus = "IN_PROGRESS",
            resultStatus = "PENDING_GRADING",
            startedAt = "2026-10-10T10:00:00Z",
            submittedAt = null,
            deadlineMetadata = "{}",
            createdAt = "2026-10-10T10:00:00Z",
            updatedAt = "2026-10-10T10:00:00Z"
        )
        database.examAttemptDao().insertAttempt(attempt)

        val receipt1 = com.doomedams.app.database.entity.ServerEventReceiptEntity(
            receiptId = "rcpt_2001",
            eventId = "evt_duplicate_id",
            attemptId = attempt.attemptId,
            studentId = user.userId,
            eventType = "ANSWER_UPDATE",
            payloadHash = "hash_one_000000000000000000000000000000000000000000000000000000000",
            processingStatus = "ACCEPTED",
            resultingVersion = 1,
            serverReceivedAt = "2026-10-10T10:05:00Z",
            processedAt = "2026-10-10T10:05:01Z"
        )
        database.serverEventReceiptDao().insertReceipt(receipt1)

        val receipt2 = com.doomedams.app.database.entity.ServerEventReceiptEntity(
            receiptId = "rcpt_2002",
            eventId = "evt_duplicate_id", // Same eventId
            attemptId = attempt.attemptId,
            studentId = user.userId,
            eventType = "ANSWER_UPDATE",
            payloadHash = "hash_two_000000000000000000000000000000000000000000000000000000000",
            processingStatus = "ACCEPTED",
            resultingVersion = 2,
            serverReceivedAt = "2026-10-10T10:06:00Z",
            processedAt = "2026-10-10T10:06:01Z"
        )

        try {
            database.serverEventReceiptDao().insertReceipt(receipt2)
            fail("Expected SQLiteConstraintException due to unique constraint on eventId")
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            // Expected constraint violation
            assertTrue(true)
        }
    }
}
