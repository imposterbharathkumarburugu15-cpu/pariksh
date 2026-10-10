package com.doomedams.app.server.sync

import com.doomedams.app.database.AppDatabase
import com.doomedams.app.database.audit.AuditEngine
import com.doomedams.app.database.entity.CandidateResponseEntity
import com.doomedams.app.database.entity.ServerEventReceiptEntity
import com.doomedams.app.database.repository.AttemptRepository
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SyncEventEnvelope(
    val eventId: String,
    val schemaVersion: Int,
    val eventType: String,
    val instituteId: String,
    val examId: String,
    val attemptId: String,
    val studentId: String,
    val sourceDeviceId: String,
    val sequenceNumber: Long,
    val aggregateId: String,
    val aggregateVersion: Int,
    val baseServerVersion: Int? = null,
    val clientWallClockTimestamp: String,
    val clientMonotonicTimestamp: Long? = null,
    val payload: String,
    val payloadHash: String,
    val idempotencyKey: String? = null,
    val deliveryAttempt: Int = 1
)

data class SyncEventResult(
    val eventId: String,
    val status: String, // ACCEPTED, ALREADY_PROCESSED, CONFLICT, REJECTED, NEEDS_REVIEW, LATE_ACCEPTED, LATE_REJECTED
    val receiptId: String? = null,
    val resultingVersion: Int = 1,
    val rejectionReason: String? = null,
    val serverReceivedAt: String,
    val receiptHash: String? = null
)

data class SyncBatchResult(
    val status: String,
    val totalReceived: Int,
    val acceptedCount: Int,
    val alreadyProcessedCount: Int,
    val rejectedCount: Int,
    val conflictCount: Int,
    val results: List<SyncEventResult>
)

data class SyncReconcileState(
    val attemptId: String,
    val examId: String,
    val studentId: String,
    val attemptStatus: String,
    val resultStatus: String,
    val officialStartTime: String,
    val officialDeadline: String,
    val durationSeconds: Long,
    val deadlineRemainingSeconds: Long,
    val isDeadlineExpired: Boolean,
    val totalQuestionsAnswered: Int,
    val lastAcceptedRevisions: Map<String, Int>,
    val serverTimestamp: String
)

/**
 * Authoritative Synchronization Service:
 * Handles offline synchronization, atomic event receipts, idempotency,
 * monotonic revision conflict resolution, deadline enforcement, and state reconciliation.
 */
class SyncService(
    private val database: AppDatabase,
    private val auditEngine: AuditEngine,
    private val attemptRepository: AttemptRepository
) {
    private val attemptMutexes = ConcurrentHashMap<String, Mutex>()

    private fun getAttemptMutex(attemptId: String): Mutex {
        return attemptMutexes.computeIfAbsent(attemptId) { Mutex() }
    }

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1
        const val LATE_SUBMISSION_GRACE_SECONDS = 120L // 2-minute network transit grace for offline responses

        val PERMITTED_EVENT_TYPES = setOf(
            "ANSWER_UPDATE",
            "FINAL_SUBMISSION",
            "INTEGRITY_EVENT",
            "HEARTBEAT"
        )

        fun computeSha256(content: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(content.toByteArray(Charsets.UTF_8))
            return hashBytes.joinToString("") { "%02x".format(it) }
        }
    }

    /**
     * Processes a batch of synchronization events atomically per attempt.
     */
    suspend fun processBatch(
        events: List<SyncEventEnvelope>,
        authenticatedStudentId: String,
        actorRole: String
    ): SyncBatchResult {
        if (events.isEmpty()) {
            return SyncBatchResult(
                status = "EMPTY_BATCH",
                totalReceived = 0,
                acceptedCount = 0,
                alreadyProcessedCount = 0,
                rejectedCount = 0,
                conflictCount = 0,
                results = emptyList()
            )
        }

        val results = mutableListOf<SyncEventResult>()
        var accepted = 0
        var alreadyProcessed = 0
        var rejected = 0
        var conflicts = 0

        for (event in events) {
            val res = processSingleEvent(event, authenticatedStudentId, actorRole)
            results.add(res)
            when (res.status) {
                "ACCEPTED", "LATE_ACCEPTED" -> accepted++
                "ALREADY_PROCESSED" -> alreadyProcessed++
                "CONFLICT" -> conflicts++
                else -> rejected++
            }
        }

        val overallStatus = when {
            conflicts > 0 -> "PARTIAL_SUCCESS_WITH_CONFLICTS"
            rejected > 0 && accepted > 0 -> "PARTIAL_SUCCESS"
            rejected > 0 && accepted == 0 && alreadyProcessed == 0 -> "FAILED"
            else -> "SUCCESS"
        }

        return SyncBatchResult(
            status = overallStatus,
            totalReceived = events.size,
            acceptedCount = accepted,
            alreadyProcessedCount = alreadyProcessed,
            rejectedCount = rejected,
            conflictCount = conflicts,
            results = results
        )
    }

    /**
     * Processes a single synchronization event with strict idempotency and optimistic locking.
     */
    suspend fun processSingleEvent(
        event: SyncEventEnvelope,
        authenticatedStudentId: String,
        actorRole: String
    ): SyncEventResult {
        val nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now())

        // 1. Schema version validation
        if (event.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            return SyncEventResult(
                eventId = event.eventId,
                status = "REJECTED",
                rejectionReason = "UNSUPPORTED_SCHEMA_VERSION: Server supports version $SUPPORTED_SCHEMA_VERSION, received ${event.schemaVersion}",
                serverReceivedAt = nowIso
            )
        }

        // 2. Event type validation
        if (!PERMITTED_EVENT_TYPES.contains(event.eventType)) {
            return SyncEventResult(
                eventId = event.eventId,
                status = "REJECTED",
                rejectionReason = "UNSUPPORTED_EVENT_TYPE: '${event.eventType}' is not supported.",
                serverReceivedAt = nowIso
            )
        }

        // 3. Payload integrity check (SHA-256)
        val calculatedHash = computeSha256(event.payload)
        if (!calculatedHash.equals(event.payloadHash, ignoreCase = true)) {
            return SyncEventResult(
                eventId = event.eventId,
                status = "REJECTED",
                rejectionReason = "PAYLOAD_HASH_MISMATCH: Provided hash does not match computed SHA-256.",
                serverReceivedAt = nowIso
            )
        }

        // 4. Authenticated identity validation
        if (event.studentId != authenticatedStudentId) {
            return SyncEventResult(
                eventId = event.eventId,
                status = "REJECTED",
                rejectionReason = "STUDENT_IDENTITY_MISMATCH: Authenticated user '$authenticatedStudentId' cannot submit events for '${event.studentId}'.",
                serverReceivedAt = nowIso
            )
        }

        // 5. Attempt mutex lock to guarantee thread-safe isolation across concurrent student requests
        val mutex = getAttemptMutex(event.attemptId)
        return mutex.withLock {
            // 6. Idempotency Check via server_event_receipts
            val existingReceipt = database.serverEventReceiptDao().getReceiptByEventId(event.eventId)
            if (existingReceipt != null) {
                // Check if the payload hash matches
                if (existingReceipt.payloadHash.equals(event.payloadHash, ignoreCase = true)) {
                    val receiptHash = if (existingReceipt.eventType == "FINAL_SUBMISSION") {
                        existingReceipt.idempotencyKey
                    } else null

                    // Exact duplicate retry: return original outcome without repeating mutations
                    return@withLock SyncEventResult(
                        eventId = event.eventId,
                        status = "ALREADY_PROCESSED",
                        receiptId = existingReceipt.receiptId,
                        resultingVersion = existingReceipt.resultingVersion,
                        rejectionReason = existingReceipt.rejectionReason,
                        serverReceivedAt = existingReceipt.serverReceivedAt,
                        receiptHash = receiptHash
                    )
                } else {
                    // Security Violation: Event ID reused with conflicting payload!
                    auditEngine.recordAuditEvent(
                        auditEventId = "aud_collision_${System.currentTimeMillis()}_${event.eventId.take(8)}",
                        actorId = authenticatedStudentId,
                        actorRole = actorRole,
                        actionType = "EVENT_ID_COLLISION_DETECTED",
                        aggregateType = "SYNC_EVENT",
                        aggregateId = event.eventId,
                        previousState = "{\"existingPayloadHash\":\"${existingReceipt.payloadHash}\"}",
                        newState = "{\"conflictingPayloadHash\":\"${event.payloadHash}\"}",
                        reason = "Security event: Client resubmitted event ID '${event.eventId}' with conflicting payload hash.",
                        operationResult = "REJECTED"
                    )

                    return@withLock SyncEventResult(
                        eventId = event.eventId,
                        status = "CONFLICT",
                        rejectionReason = "EVENT_ID_COLLISION_DIFFERENT_PAYLOAD: Event ID was previously recorded with a different payload hash.",
                        serverReceivedAt = nowIso
                    )
                }
            }

            // 7. Verify attempt ownership & authoritative state
            val attempt = database.examAttemptDao().getAttemptById(event.attemptId)
            if (attempt == null) {
                return@withLock SyncEventResult(
                    eventId = event.eventId,
                    status = "REJECTED",
                    rejectionReason = "ATTEMPT_NOT_FOUND: Attempt '${event.attemptId}' does not exist.",
                    serverReceivedAt = nowIso
                )
            }

            if (attempt.studentId != authenticatedStudentId) {
                return@withLock SyncEventResult(
                    eventId = event.eventId,
                    status = "REJECTED",
                    rejectionReason = "FORBIDDEN: Attempt does not belong to authenticated student.",
                    serverReceivedAt = nowIso
                )
            }

            // 8. Authoritative deadline evaluation
            val deadlineMeta = try {
                JSONObject(attempt.deadlineMetadata)
            } catch (e: Exception) {
                JSONObject()
            }
            val officialDeadlineStr = deadlineMeta.optString("officialDeadline", "")
            val nowInstant = Instant.now()
            val isLate = if (officialDeadlineStr.isNotBlank()) {
                try {
                    val deadlineInstant = Instant.parse(officialDeadlineStr)
                    nowInstant.isAfter(deadlineInstant)
                } catch (e: Exception) {
                    false
                }
            } else {
                false
            }

            val secondsLate = if (isLate) {
                val deadlineInstant = Instant.parse(officialDeadlineStr)
                Duration.between(deadlineInstant, nowInstant).seconds
            } else {
                0L
            }

            // 9. Dispatch by eventType
            when (event.eventType) {
                "ANSWER_UPDATE" -> {
                    handleAnswerUpdate(
                        event = event,
                        attempt = attempt,
                        isLate = isLate,
                        secondsLate = secondsLate,
                        nowIso = nowIso,
                        authenticatedStudentId = authenticatedStudentId,
                        actorRole = actorRole
                    )
                }
                "FINAL_SUBMISSION" -> {
                    handleFinalSubmission(
                        event = event,
                        attempt = attempt,
                        isLate = isLate,
                        secondsLate = secondsLate,
                        nowIso = nowIso,
                        authenticatedStudentId = authenticatedStudentId,
                        actorRole = actorRole
                    )
                }
                "INTEGRITY_EVENT" -> {
                    handleIntegrityEvent(
                        event = event,
                        attempt = attempt,
                        nowIso = nowIso
                    )
                }
                "HEARTBEAT" -> {
                    val receiptId = "rcpt_hb_${System.currentTimeMillis()}_${event.eventId.take(8)}"
                    val receipt = ServerEventReceiptEntity(
                        receiptId = receiptId,
                        eventId = event.eventId,
                        attemptId = event.attemptId,
                        studentId = authenticatedStudentId,
                        eventType = "HEARTBEAT",
                        payloadHash = event.payloadHash,
                        idempotencyKey = event.idempotencyKey,
                        processingStatus = "ACCEPTED",
                        resultingVersion = 1,
                        serverReceivedAt = nowIso,
                        processedAt = nowIso
                    )
                    database.serverEventReceiptDao().insertReceipt(receipt)
                    SyncEventResult(
                        eventId = event.eventId,
                        status = "ACCEPTED",
                        receiptId = receiptId,
                        serverReceivedAt = nowIso
                    )
                }
                else -> {
                    SyncEventResult(
                        eventId = event.eventId,
                        status = "REJECTED",
                        rejectionReason = "UNSUPPORTED_EVENT_TYPE: '${event.eventType}'",
                        serverReceivedAt = nowIso
                    )
                }
            }
        }
    }

    private suspend fun handleAnswerUpdate(
        event: SyncEventEnvelope,
        attempt: com.doomedams.app.database.entity.ExamAttemptEntity,
        isLate: Boolean,
        secondsLate: Long,
        nowIso: String,
        authenticatedStudentId: String,
        actorRole: String
    ): SyncEventResult {
        if (attempt.attemptStatus != "IN_PROGRESS") {
            return SyncEventResult(
                eventId = event.eventId,
                status = "REJECTED",
                rejectionReason = "INVALID_ATTEMPT_STATE: Cannot record answers for attempt in status '${attempt.attemptStatus}'.",
                serverReceivedAt = nowIso
            )
        }

        // Parse answer payload
        val payloadObj = try {
            JSONObject(event.payload)
        } catch (e: Exception) {
            return SyncEventResult(
                eventId = event.eventId,
                status = "REJECTED",
                rejectionReason = "MALFORMED_PAYLOAD: Payload is not valid JSON.",
                serverReceivedAt = nowIso
            )
        }

        val questionId = payloadObj.optString("questionId", "")
        val responsePayload = payloadObj.optString("responsePayload", "")
        val incomingRevision = payloadObj.optInt("revisionSequence", 1)

        if (questionId.isBlank()) {
            return SyncEventResult(
                eventId = event.eventId,
                status = "REJECTED",
                rejectionReason = "MISSING_QUESTION_ID",
                serverReceivedAt = nowIso
            )
        }

        // Evaluate late policy: if beyond grace period, reject
        if (isLate && secondsLate > LATE_SUBMISSION_GRACE_SECONDS) {
            val receiptId = "rcpt_rej_${System.currentTimeMillis()}_${event.eventId.take(8)}"
            val receipt = ServerEventReceiptEntity(
                receiptId = receiptId,
                eventId = event.eventId,
                attemptId = attempt.attemptId,
                studentId = authenticatedStudentId,
                eventType = event.eventType,
                payloadHash = event.payloadHash,
                idempotencyKey = event.idempotencyKey,
                processingStatus = "LATE_REJECTED",
                resultingVersion = incomingRevision,
                rejectionReason = "DEADLINE_EXPIRED: Answer received ${secondsLate}s after official deadline (grace period: ${LATE_SUBMISSION_GRACE_SECONDS}s).",
                serverReceivedAt = nowIso,
                processedAt = nowIso
            )
            database.serverEventReceiptDao().insertReceipt(receipt)

            auditEngine.recordAuditEvent(
                auditEventId = "aud_laterej_${System.currentTimeMillis()}_${event.eventId.take(8)}",
                actorId = authenticatedStudentId,
                actorRole = actorRole,
                actionType = "LATE_ANSWER_REJECTED",
                aggregateType = "EXAM_ATTEMPT",
                aggregateId = attempt.attemptId,
                previousState = null,
                newState = "{\"questionId\":\"$questionId\",\"secondsLate\":$secondsLate}",
                reason = "Offline answer rejected because official deadline has elapsed.",
                operationResult = "REJECTED"
            )

            return SyncEventResult(
                eventId = event.eventId,
                status = "LATE_REJECTED",
                receiptId = receiptId,
                rejectionReason = "DEADLINE_EXPIRED",
                serverReceivedAt = nowIso
            )
        }

        // Conflict check: Check current revision on server
        val existing = database.candidateResponseDao().getResponse(attempt.attemptId, questionId)
        if (existing != null && incomingRevision < existing.revisionSequence) {
            // Stale revision: do not overwrite newer answer!
            val receiptId = "rcpt_conflict_${System.currentTimeMillis()}_${event.eventId.take(8)}"
            val conflictReason = "STALE_REVISION: Incoming revision $incomingRevision is older than accepted server revision ${existing.revisionSequence}."
            val receipt = ServerEventReceiptEntity(
                receiptId = receiptId,
                eventId = event.eventId,
                attemptId = attempt.attemptId,
                studentId = authenticatedStudentId,
                eventType = event.eventType,
                payloadHash = event.payloadHash,
                idempotencyKey = event.idempotencyKey,
                processingStatus = "CONFLICT",
                resultingVersion = existing.revisionSequence,
                rejectionReason = conflictReason,
                serverReceivedAt = nowIso,
                processedAt = nowIso
            )
            database.serverEventReceiptDao().insertReceipt(receipt)

            auditEngine.recordAuditEvent(
                auditEventId = "aud_conflict_${System.currentTimeMillis()}_${event.eventId.take(8)}",
                actorId = authenticatedStudentId,
                actorRole = actorRole,
                actionType = "STALE_REVISION_REJECTED",
                aggregateType = "EXAM_ATTEMPT",
                aggregateId = attempt.attemptId,
                previousState = "{\"questionId\":\"$questionId\",\"serverRev\":${existing.revisionSequence}}",
                newState = "{\"questionId\":\"$questionId\",\"incomingRev\":$incomingRevision,\"payloadHash\":\"${event.payloadHash}\"}",
                reason = conflictReason,
                operationResult = "CONFLICT"
            )

            return SyncEventResult(
                eventId = event.eventId,
                status = "CONFLICT",
                receiptId = receiptId,
                resultingVersion = existing.revisionSequence,
                rejectionReason = conflictReason,
                serverReceivedAt = nowIso
            )
        }

        // Competing edit conflict check: Same revision sequence but different answer payload
        if (existing != null && incomingRevision == existing.revisionSequence && existing.responsePayload != responsePayload) {
            val receiptId = "rcpt_conflict_${System.currentTimeMillis()}_${event.eventId.take(8)}"
            val conflictReason = "COMPETING_REVISION_CONFLICT: Sequence $incomingRevision already exists with different answer payload."
            val receipt = ServerEventReceiptEntity(
                receiptId = receiptId,
                eventId = event.eventId,
                attemptId = attempt.attemptId,
                studentId = authenticatedStudentId,
                eventType = event.eventType,
                payloadHash = event.payloadHash,
                idempotencyKey = event.idempotencyKey,
                processingStatus = "CONFLICT",
                resultingVersion = existing.revisionSequence,
                rejectionReason = conflictReason,
                serverReceivedAt = nowIso,
                processedAt = nowIso
            )
            database.serverEventReceiptDao().insertReceipt(receipt)

            auditEngine.recordAuditEvent(
                auditEventId = "aud_compete_${System.currentTimeMillis()}_${event.eventId.take(8)}",
                actorId = authenticatedStudentId,
                actorRole = actorRole,
                actionType = "COMPETING_REVISION_REJECTED",
                aggregateType = "EXAM_ATTEMPT",
                aggregateId = attempt.attemptId,
                previousState = "{\"questionId\":\"$questionId\",\"serverRev\":${existing.revisionSequence}}",
                newState = "{\"questionId\":\"$questionId\",\"incomingRev\":$incomingRevision,\"payloadHash\":\"${event.payloadHash}\"}",
                reason = conflictReason,
                operationResult = "CONFLICT"
            )

            return SyncEventResult(
                eventId = event.eventId,
                status = "CONFLICT",
                receiptId = receiptId,
                resultingVersion = existing.revisionSequence,
                rejectionReason = conflictReason,
                serverReceivedAt = nowIso
            )
        }

        // Accept strictly newer revision (or same revision idempotent update)
        val responseEntity = CandidateResponseEntity(
            responseId = existing?.responseId ?: "resp_${System.currentTimeMillis()}_${questionId}",
            attemptId = attempt.attemptId,
            questionId = questionId,
            responsePayload = responsePayload,
            revisionSequence = incomingRevision,
            savedAt = nowIso
        )

        val receiptId = "rcpt_ans_${System.currentTimeMillis()}_${event.eventId.take(8)}"
        val finalStatus = if (isLate) "LATE_ACCEPTED" else "ACCEPTED"

        val receipt = ServerEventReceiptEntity(
            receiptId = receiptId,
            eventId = event.eventId,
            attemptId = attempt.attemptId,
            studentId = authenticatedStudentId,
            eventType = event.eventType,
            payloadHash = event.payloadHash,
            idempotencyKey = event.idempotencyKey,
            processingStatus = finalStatus,
            resultingVersion = incomingRevision,
            rejectionReason = if (isLate) "LATE_ACCEPTED_WITHIN_GRACE (${secondsLate}s late)" else null,
            serverReceivedAt = nowIso,
            processedAt = nowIso
        )

        database.candidateResponseDao().upsertResponse(responseEntity)
        database.serverEventReceiptDao().insertReceipt(receipt)

        auditEngine.recordAuditEvent(
            auditEventId = "aud_sync_${System.currentTimeMillis()}_${event.eventId.take(8)}",
            actorId = authenticatedStudentId,
            actorRole = actorRole,
            actionType = if (isLate) "LATE_ANSWER_ACCEPTED" else "ANSWER_SYNCHRONIZED",
            aggregateType = "EXAM_ATTEMPT",
            aggregateId = attempt.attemptId,
            previousState = existing?.let { "{\"rev\":${it.revisionSequence}}" },
            newState = "{\"questionId\":\"$questionId\",\"rev\":$incomingRevision,\"status\":\"$finalStatus\"}",
            reason = if (isLate) "Answer accepted under late grace policy (${secondsLate}s late)" else "Candidate answer synchronized from outbox",
            operationResult = "SUCCESS"
        )

        return SyncEventResult(
            eventId = event.eventId,
            status = finalStatus,
            receiptId = receiptId,
            resultingVersion = incomingRevision,
            serverReceivedAt = nowIso
        )
    }

    private suspend fun handleFinalSubmission(
        event: SyncEventEnvelope,
        attempt: com.doomedams.app.database.entity.ExamAttemptEntity,
        isLate: Boolean,
        secondsLate: Long,
        nowIso: String,
        authenticatedStudentId: String,
        actorRole: String
    ): SyncEventResult {
        val payloadObj = try {
            JSONObject(event.payload)
        } catch (e: Exception) {
            JSONObject()
        }

        val clientChecksum = payloadObj.optString("clientChecksum", "none")
        val totalAnswered = payloadObj.optInt("totalQuestionsAnswered", 0)

        // If attempt is already SUBMITTED: return original submission receipt hash!
        if (attempt.attemptStatus == "SUBMITTED") {
            val receipt = attemptRepository.submitAttempt(
                attemptId = attempt.attemptId,
                studentId = authenticatedStudentId,
                clientChecksum = clientChecksum,
                totalAnswered = totalAnswered,
                actorId = authenticatedStudentId,
                actorRole = actorRole
            )

            val receiptId = "rcpt_sub_${System.currentTimeMillis()}_${event.eventId.take(8)}"
            val receiptEntity = ServerEventReceiptEntity(
                receiptId = receiptId,
                eventId = event.eventId,
                attemptId = attempt.attemptId,
                studentId = authenticatedStudentId,
                eventType = event.eventType,
                payloadHash = event.payloadHash,
                idempotencyKey = receipt.receiptHash,
                processingStatus = "ALREADY_PROCESSED",
                resultingVersion = 1,
                rejectionReason = "ATTEMPT_ALREADY_SUBMITTED",
                serverReceivedAt = nowIso,
                processedAt = nowIso
            )
            database.serverEventReceiptDao().insertReceipt(receiptEntity)

            return SyncEventResult(
                eventId = event.eventId,
                status = "ALREADY_PROCESSED",
                receiptId = receipt.receiptId,
                receiptHash = receipt.receiptHash,
                serverReceivedAt = nowIso
            )
        }

        if (attempt.attemptStatus != "IN_PROGRESS") {
            return SyncEventResult(
                eventId = event.eventId,
                status = "REJECTED",
                rejectionReason = "INVALID_ATTEMPT_STATE: Cannot submit attempt in status '${attempt.attemptStatus}'.",
                serverReceivedAt = nowIso
            )
        }

        // Strict deadline policy: If final submission arrives past late grace period, reject authoritatively
        if (isLate && secondsLate > LATE_SUBMISSION_GRACE_SECONDS) {
            val receiptId = "rcpt_sub_rej_${System.currentTimeMillis()}_${event.eventId.take(8)}"
            val receipt = ServerEventReceiptEntity(
                receiptId = receiptId,
                eventId = event.eventId,
                attemptId = attempt.attemptId,
                studentId = authenticatedStudentId,
                eventType = event.eventType,
                payloadHash = event.payloadHash,
                idempotencyKey = event.idempotencyKey,
                processingStatus = "LATE_REJECTED",
                resultingVersion = 1,
                rejectionReason = "DEADLINE_EXPIRED: Final submission received ${secondsLate}s after official deadline (grace period: ${LATE_SUBMISSION_GRACE_SECONDS}s).",
                serverReceivedAt = nowIso,
                processedAt = nowIso
            )
            database.serverEventReceiptDao().insertReceipt(receipt)

            auditEngine.recordAuditEvent(
                auditEventId = "aud_latesub_rej_${System.currentTimeMillis()}_${event.eventId.take(8)}",
                actorId = authenticatedStudentId,
                actorRole = actorRole,
                actionType = "LATE_FINAL_SUBMISSION_REJECTED",
                aggregateType = "EXAM_ATTEMPT",
                aggregateId = attempt.attemptId,
                previousState = "{\"attemptStatus\":\"${attempt.attemptStatus}\"}",
                newState = "{\"secondsLate\":$secondsLate,\"status\":\"LATE_REJECTED\"}",
                reason = "Final submission rejected because official deadline has elapsed beyond grace period.",
                operationResult = "REJECTED"
            )

            return SyncEventResult(
                eventId = event.eventId,
                status = "LATE_REJECTED",
                receiptId = receiptId,
                rejectionReason = "DEADLINE_EXPIRED",
                serverReceivedAt = nowIso
            )
        }

        // Authoritatively submit attempt via repository
        val submissionReceipt = attemptRepository.submitAttempt(
            attemptId = attempt.attemptId,
            studentId = authenticatedStudentId,
            clientChecksum = clientChecksum,
            totalAnswered = totalAnswered,
            actorId = authenticatedStudentId,
            actorRole = actorRole
        )

        val receiptId = "rcpt_sub_${System.currentTimeMillis()}_${event.eventId.take(8)}"
        val finalStatus = if (isLate) "LATE_ACCEPTED" else "ACCEPTED"

        val receiptEntity = ServerEventReceiptEntity(
            receiptId = receiptId,
            eventId = event.eventId,
            attemptId = attempt.attemptId,
            studentId = authenticatedStudentId,
            eventType = event.eventType,
            payloadHash = event.payloadHash,
            idempotencyKey = submissionReceipt.receiptHash,
            processingStatus = finalStatus,
            resultingVersion = 1,
            rejectionReason = if (isLate) "LATE_SUBMISSION (${secondsLate}s late)" else null,
            serverReceivedAt = nowIso,
            processedAt = nowIso
        )
        database.serverEventReceiptDao().insertReceipt(receiptEntity)

        return SyncEventResult(
            eventId = event.eventId,
            status = finalStatus,
            receiptId = submissionReceipt.receiptId,
            receiptHash = submissionReceipt.receiptHash,
            serverReceivedAt = nowIso
        )
    }

    private suspend fun handleIntegrityEvent(
        event: SyncEventEnvelope,
        attempt: com.doomedams.app.database.entity.ExamAttemptEntity,
        nowIso: String
    ): SyncEventResult {
        val receiptId = "rcpt_int_${System.currentTimeMillis()}_${event.eventId.take(8)}"
        val receipt = ServerEventReceiptEntity(
            receiptId = receiptId,
            eventId = event.eventId,
            attemptId = attempt.attemptId,
            studentId = event.studentId,
            eventType = "INTEGRITY_EVENT",
            payloadHash = event.payloadHash,
            idempotencyKey = event.idempotencyKey,
            processingStatus = "ACCEPTED",
            resultingVersion = 1,
            serverReceivedAt = nowIso,
            processedAt = nowIso
        )
        database.serverEventReceiptDao().insertReceipt(receipt)

        return SyncEventResult(
            eventId = event.eventId,
            status = "ACCEPTED",
            receiptId = receiptId,
            serverReceivedAt = nowIso
        )
    }

    /**
     * Authoritative Reconcile Query:
     * When a student reconnects or reboots their phone, this returns the authoritative
     * server attempt state, active deadline remaining, and accepted response revisions.
     */
    suspend fun reconcileAttempt(attemptId: String, studentId: String): SyncReconcileState? {
        val attempt = database.examAttemptDao().getAttemptById(attemptId) ?: return null
        if (attempt.studentId != studentId) return null

        val nowInstant = Instant.now()
        val nowIso = DateTimeFormatter.ISO_INSTANT.format(nowInstant)

        val meta = try {
            JSONObject(attempt.deadlineMetadata)
        } catch (e: Exception) {
            JSONObject()
        }

        val startTimeStr = meta.optString("officialStartTime", attempt.startedAt)
        val deadlineStr = meta.optString("officialDeadline", "")
        val durationSeconds = meta.optLong("durationSeconds", 0L)

        val deadlineRemainingSeconds: Long
        val isExpired: Boolean

        if (deadlineStr.isNotBlank()) {
            val deadlineInstant = try {
                Instant.parse(deadlineStr)
            } catch (e: Exception) {
                nowInstant
            }
            if (nowInstant.isAfter(deadlineInstant)) {
                deadlineRemainingSeconds = 0L
                isExpired = true
            } else {
                deadlineRemainingSeconds = Duration.between(nowInstant, deadlineInstant).seconds
                isExpired = false
            }
        } else {
            deadlineRemainingSeconds = 0L
            isExpired = false
        }

        val responses = database.candidateResponseDao().getResponsesByAttempt(attemptId)
        val revisionMap = responses.associate { it.questionId to it.revisionSequence }

        return SyncReconcileState(
            attemptId = attempt.attemptId,
            examId = attempt.examId,
            studentId = attempt.studentId,
            attemptStatus = attempt.attemptStatus,
            resultStatus = attempt.resultStatus,
            officialStartTime = startTimeStr,
            officialDeadline = deadlineStr,
            durationSeconds = durationSeconds,
            deadlineRemainingSeconds = deadlineRemainingSeconds,
            isDeadlineExpired = isExpired,
            totalQuestionsAnswered = responses.count { it.responsePayload.isNotBlank() },
            lastAcceptedRevisions = revisionMap,
            serverTimestamp = nowIso
        )
    }

    /**
     * Observability metrics for invigilators and admins:
     * Returns total counts of receipts, accepted, rejected, conflicts, and per-attempt progress.
     */
    suspend fun getSyncObservabilityOverview(): JSONObject {
        val totalReceipts = database.serverEventReceiptDao().getTotalReceiptCount()
        val acceptedCount = database.serverEventReceiptDao().getReceiptCountByStatus("ACCEPTED")
        val alreadyProcessedCount = database.serverEventReceiptDao().getReceiptCountByStatus("ALREADY_PROCESSED")
        val rejectedCount = database.serverEventReceiptDao().getReceiptCountByStatus("REJECTED")
        val conflictCount = database.serverEventReceiptDao().getReceiptCountByStatus("CONFLICT")
        val lateAcceptedCount = database.serverEventReceiptDao().getReceiptCountByStatus("LATE_ACCEPTED")
        val lateRejectedCount = database.serverEventReceiptDao().getReceiptCountByStatus("LATE_REJECTED")

        val allReceipts = database.serverEventReceiptDao().getAllReceipts()
        val attemptReceiptMap = allReceipts.groupBy { it.attemptId }

        val attemptsSummary = org.json.JSONArray()
        for ((attId, rList) in attemptReceiptMap) {
            val att = database.examAttemptDao().getAttemptById(attId)
            val obj = JSONObject().apply {
                put("attemptId", attId)
                put("studentId", att?.studentId ?: "unknown")
                put("attemptStatus", att?.attemptStatus ?: "unknown")
                put("totalEvents", rList.size)
                put("acceptedEvents", rList.count { it.processingStatus == "ACCEPTED" || it.processingStatus == "LATE_ACCEPTED" })
                put("conflicts", rList.count { it.processingStatus == "CONFLICT" })
                put("lastEventAt", rList.firstOrNull()?.processedAt ?: JSONObject.NULL)
            }
            attemptsSummary.put(obj)
        }

        return JSONObject().apply {
            put("totalReceipts", totalReceipts)
            put("acceptedCount", acceptedCount + lateAcceptedCount)
            put("alreadyProcessedCount", alreadyProcessedCount)
            put("rejectedCount", rejectedCount + lateRejectedCount)
            put("conflictCount", conflictCount)
            put("lateAcceptedCount", lateAcceptedCount)
            put("lateRejectedCount", lateRejectedCount)
            put("activeAttemptsCount", attemptReceiptMap.size)
            put("attempts", attemptsSummary)
            put("serverTimestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
        }
    }
}
