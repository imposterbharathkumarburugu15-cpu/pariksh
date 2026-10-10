package com.doomedams.app.server.incident

import com.doomedams.app.database.AppDatabase
import com.doomedams.app.database.entity.AppealEntity
import com.doomedams.app.database.entity.IncidentCaseEntity
import com.doomedams.app.database.entity.IncidentDecisionEntity
import com.doomedams.app.database.entity.PolicyActionEntity
import com.doomedams.app.database.audit.AuditEngine
import com.doomedams.app.server.auth.UserSession
import com.doomedams.app.server.rbac.AuthorizationResult
import com.doomedams.app.server.rbac.Permission
import com.doomedams.app.server.rbac.PermissionService
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Incident Review & Lifecycle Management Result types
 */
sealed class IncidentOperationResult<out T> {
    data class Success<out T>(val value: T, val auditEventId: String) : IncidentOperationResult<T>()
    data class PermissionDenied(val reason: String) : IncidentOperationResult<Nothing>()
    data class InvalidTransition(val from: String, val to: String, val reason: String) : IncidentOperationResult<Nothing>()
    data class NotFound(val entity: String, val id: String) : IncidentOperationResult<Nothing>()
    data class ValidationError(val field: String, val message: String) : IncidentOperationResult<Nothing>()
}

data class IncidentSummary(
    val incident: IncidentCaseEntity,
    val decisions: List<IncidentDecisionEntity>,
    val policyActions: List<PolicyActionEntity>,
    val appeals: List<AppealEntity>,
    val studentDisplayName: String,
    val examTitle: String
)

/**
 * Authoritative Server-Side Incident Review, Policy Action, and Appeals Service
 */
class IncidentReviewService(
    private val database: AppDatabase,
    private val auditEngine: AuditEngine
) {

    companion object {
        val PERMITTED_INCIDENT_STATUSES = setOf(
            "DETECTED",
            "PENDING_REVIEW",
            "UNDER_REVIEW",
            "PENDING_FURTHER_REVIEW",
            "CONFIRMED",
            "DISMISSED",
            "CLOSED"
        )

        val PERMITTED_EVIDENCE_ASSESSMENTS = setOf(
            "SUFFICIENT",
            "LIMITED",
            "UNAVAILABLE",
            "CONTRADICTORY"
        )

        val PERMITTED_POLICY_ACTIONS = setOf(
            "NO_ACTION",
            "WARN",
            "FLAG_REVIEW",
            "HOLD",
            "CANCEL_ATTEMPT",
            "REINSTATE_ATTEMPT"
        )

        val PERMITTED_APPEAL_STATUSES = setOf(
            "SUBMITTED",
            "PENDING_REVIEW",
            "UNDER_REVIEW",
            "UPHELD",
            "OVERTURNED",
            "REQUIRES_MORE_INFORMATION",
            "CLOSED"
        )

        /**
         * Authoritative state machine mapping:
         * Defines permitted transitions between incident lifecycle states.
         */
        val PERMITTED_TRANSITIONS = mapOf(
            "DETECTED" to setOf("PENDING_REVIEW"),
            "PENDING_REVIEW" to setOf("UNDER_REVIEW"),
            "UNDER_REVIEW" to setOf("PENDING_FURTHER_REVIEW", "CONFIRMED", "DISMISSED"),
            "PENDING_FURTHER_REVIEW" to setOf("UNDER_REVIEW"),
            "CONFIRMED" to setOf("CLOSED"),
            "DISMISSED" to setOf("CLOSED"),
            "CLOSED" to emptySet()
        )
    }

    private fun nowIso(): String = DateTimeFormatter.ISO_INSTANT.format(Instant.now())

    /**
     * Start reviewing an incident: transitions PENDING_REVIEW -> UNDER_REVIEW.
     */
    suspend fun beginReview(
        incidentId: String,
        session: UserSession
    ): IncidentOperationResult<IncidentCaseEntity> {
        val auth = PermissionService.authorize(session, Permission.INCIDENT_REVIEW, session.instituteScope)
        if (auth is AuthorizationResult.Denied) {
            recordPermissionDenial(session, Permission.INCIDENT_REVIEW, auth.reason, incidentId)
            return IncidentOperationResult.PermissionDenied(auth.reason)
        }

        val incident = database.incidentDao().getIncidentById(incidentId)
            ?: return IncidentOperationResult.NotFound("IncidentCase", incidentId)

        if (incident.incidentStatus == "UNDER_REVIEW" && incident.reviewerId == session.userId) {
            return IncidentOperationResult.Success(incident, "noop")
        }

        val allowed = PERMITTED_TRANSITIONS[incident.incidentStatus] ?: emptySet()
        if (!allowed.contains("UNDER_REVIEW")) {
            return IncidentOperationResult.InvalidTransition(
                from = incident.incidentStatus,
                to = "UNDER_REVIEW",
                reason = "Cannot begin review from status '${incident.incidentStatus}'"
            )
        }

        val now = nowIso()
        val updated = incident.copy(
            incidentStatus = "UNDER_REVIEW",
            reviewerId = session.userId,
            version = incident.version + 1,
            updatedAt = now
        )

        database.incidentDao().updateIncident(updated)

        val auditId = "aud_rev_start_${System.currentTimeMillis()}"
        auditEngine.recordAuditEvent(
            auditEventId = auditId,
            actorId = session.userId,
            actorRole = session.role,
            actionType = "INCIDENT_REVIEW_STARTED",
            aggregateType = "INCIDENT_CASE",
            aggregateId = incidentId,
            previousState = incident.incidentStatus,
            newState = "UNDER_REVIEW",
            reason = "Authorised reviewer began incident investigation",
            operationResult = "SUCCESS"
        )

        return IncidentOperationResult.Success(updated, auditId)
    }

    /**
     * Assess evidence and record formal decision on an incident.
     */
    suspend fun recordAssessmentAndDecision(
        incidentId: String,
        session: UserSession,
        evidenceAssessment: String,
        decision: String,
        justification: String,
        policyVersion: String = "v1.0"
    ): IncidentOperationResult<IncidentCaseEntity> {
        val auth = PermissionService.authorize(session, Permission.INCIDENT_REVIEW, session.instituteScope)
        if (auth is AuthorizationResult.Denied) {
            recordPermissionDenial(session, Permission.INCIDENT_REVIEW, auth.reason, incidentId)
            return IncidentOperationResult.PermissionDenied(auth.reason)
        }

        if (!PERMITTED_EVIDENCE_ASSESSMENTS.contains(evidenceAssessment)) {
            return IncidentOperationResult.ValidationError(
                "evidenceAssessment",
                "Unsupported evidence assessment '$evidenceAssessment'"
            )
        }

        val incident = database.incidentDao().getIncidentById(incidentId)
            ?: return IncidentOperationResult.NotFound("IncidentCase", incidentId)

        // Target status based on decision
        val targetStatus = when (decision) {
            "CONFIRMED" -> "CONFIRMED"
            "DISMISSED" -> "DISMISSED"
            "PENDING_FURTHER_REVIEW" -> "PENDING_FURTHER_REVIEW"
            else -> return IncidentOperationResult.ValidationError("decision", "Invalid decision '$decision'")
        }

        // Safeguard 1: Limited or contradictory evidence can NEVER produce a CONFIRMED misconduct finding
        if (targetStatus == "CONFIRMED" && evidenceAssessment != "SUFFICIENT") {
            return IncidentOperationResult.ValidationError(
                "evidenceAssessment",
                "Cannot confirm misconduct violation when evidence assessment is '$evidenceAssessment'. Requires 'SUFFICIENT'."
            )
        }

        // Safeguard 2: Meaningful justification required for high-impact confirmation or dismissal
        if ((targetStatus == "CONFIRMED" || targetStatus == "DISMISSED") && justification.trim().length < 5) {
            return IncidentOperationResult.ValidationError(
                "justification",
                "Meaningful justification required for final confirmation or dismissal"
            )
        }

        // Safeguard 3: Check permitted transition
        val allowed = PERMITTED_TRANSITIONS[incident.incidentStatus] ?: emptySet()
        if (!allowed.contains(targetStatus)) {
            return IncidentOperationResult.InvalidTransition(
                from = incident.incidentStatus,
                to = targetStatus,
                reason = "Cannot transition incident from '${incident.incidentStatus}' to '$targetStatus'"
            )
        }

        val now = nowIso()
        val decisionId = "dec_${System.currentTimeMillis()}_${session.userId.take(6)}"

        // 1. Record append-only historical decision
        val decisionEntity = IncidentDecisionEntity(
            decisionId = decisionId,
            incidentId = incidentId,
            reviewerId = session.userId,
            decision = targetStatus,
            evidenceAssessment = evidenceAssessment,
            reviewerJustification = justification,
            policyVersion = policyVersion,
            createdAt = now
        )
        database.incidentDao().insertDecision(decisionEntity)

        // 2. Update incident case status
        val updatedIncident = incident.copy(
            incidentStatus = targetStatus,
            evidenceAssessment = evidenceAssessment,
            decision = targetStatus,
            reviewerId = session.userId,
            policyVersion = policyVersion,
            version = incident.version + 1,
            updatedAt = now
        )
        database.incidentDao().updateIncident(updatedIncident)

        // 3. Record audit event
        val auditId = "aud_dec_${System.currentTimeMillis()}"
        auditEngine.recordAuditEvent(
            auditEventId = auditId,
            actorId = session.userId,
            actorRole = session.role,
            actionType = "INCIDENT_DECIDED",
            aggregateType = "INCIDENT_CASE",
            aggregateId = incidentId,
            previousState = incident.incidentStatus,
            newState = targetStatus,
            reason = justification,
            operationResult = "SUCCESS"
        )

        return IncidentOperationResult.Success(updatedIncident, auditId)
    }

    /**
     * Executes the mandatory Step 9 Limited-Evidence Result-Hold Scenario:
     * Category: UNAUTHORISED_APP_SWITCH
     * Evidence Assessment: LIMITED
     * Requested Policy Action: HOLD
     * Reviewer Justification: "No notes supplied"
     * Resulting Incident Status: PENDING_FURTHER_REVIEW
     * Resulting Result Status: HELD_PENDING_REVIEW (only after authorised application)
     * Attempt Status: Preserved (NOT cancelled)
     * Final Finding: None
     */
    suspend fun executeLimitedEvidenceHoldScenario(
        incidentId: String,
        session: UserSession
    ): IncidentOperationResult<IncidentSummary> {
        val auth = PermissionService.authorize(session, Permission.RESULT_HOLD_APPLY, session.instituteScope)
        if (auth is AuthorizationResult.Denied) {
            recordPermissionDenial(session, Permission.RESULT_HOLD_APPLY, auth.reason, incidentId)
            return IncidentOperationResult.PermissionDenied(auth.reason)
        }

        val incident = database.incidentDao().getIncidentById(incidentId)
            ?: return IncidentOperationResult.NotFound("IncidentCase", incidentId)

        // Must be in UNDER_REVIEW or PENDING_REVIEW
        if (incident.incidentStatus != "UNDER_REVIEW" && incident.incidentStatus != "PENDING_REVIEW") {
            return IncidentOperationResult.InvalidTransition(
                from = incident.incidentStatus,
                to = "PENDING_FURTHER_REVIEW",
                reason = "Cannot apply limited evidence hold to incident in state '${incident.incidentStatus}'"
            )
        }

        val now = nowIso()

        // 1. Record decision: Inconclusive, further review required
        val decisionId = "dec_scen_hold_${System.currentTimeMillis()}"
        val decisionEntity = IncidentDecisionEntity(
            decisionId = decisionId,
            incidentId = incidentId,
            reviewerId = session.userId,
            decision = "PENDING_FURTHER_REVIEW",
            evidenceAssessment = "LIMITED",
            reviewerJustification = "No notes supplied",
            policyVersion = "v1.0",
            createdAt = now
        )
        database.incidentDao().insertDecision(decisionEntity)

        // 2. Transition incident to PENDING_FURTHER_REVIEW
        val updatedIncident = incident.copy(
            incidentStatus = "PENDING_FURTHER_REVIEW",
            evidenceAssessment = "LIMITED",
            decision = "PENDING_FURTHER_REVIEW",
            reviewerId = session.userId,
            version = incident.version + 1,
            updatedAt = now
        )
        database.incidentDao().updateIncident(updatedIncident)

        // 3. Record Policy Action: HOLD requested and APPLIED
        val actionId = "act_hold_${System.currentTimeMillis()}"
        val actionEntity = PolicyActionEntity(
            actionId = actionId,
            incidentId = incidentId,
            actionType = "HOLD",
            executionStatus = "APPLIED",
            requestedBy = session.userId,
            approvedBy = session.userId,
            policyVersion = "v1.0",
            reason = "Temporary result hold pending further review (limited evidence observed)",
            createdAt = now,
            completedAt = now
        )
        database.incidentDao().insertPolicyAction(actionEntity)

        // 4. Authoritative Result Status Hold Application
        // Check if an ExamResultEntity exists for this attempt
        val existingResult = database.examResultDao().getResultByAttemptId(incident.attemptId)
        if (existingResult != null) {
            database.examResultDao().applyResultHold(
                attemptId = incident.attemptId,
                newStatus = "HELD_PENDING_REVIEW",
                holdReason = "Temporary result hold pending further review: Incident $incidentId",
                updatedAt = now
            )
        }

        // Note: The attempt itself is NOT cancelled; attemptStatus remains unchanged!

        // 5. Audit Logging
        val auditId = "aud_scen_hold_${System.currentTimeMillis()}"
        auditEngine.recordAuditEvent(
            auditEventId = auditId,
            actorId = session.userId,
            actorRole = session.role,
            actionType = "RESULT_HOLD_APPLIED",
            aggregateType = "EXAM_RESULT",
            aggregateId = incident.attemptId,
            previousState = existingResult?.resultStatus ?: "PENDING_GRADING",
            newState = "HELD_PENDING_REVIEW",
            reason = "No notes supplied",
            operationResult = "SUCCESS"
        )

        val summary = getIncidentSummary(incidentId)
        return IncidentOperationResult.Success(summary, auditId)
    }

    /**
     * Apply Result Hold explicitly.
     */
    suspend fun applyResultHold(
        incidentId: String,
        session: UserSession,
        reason: String
    ): IncidentOperationResult<PolicyActionEntity> {
        val auth = PermissionService.authorize(session, Permission.RESULT_HOLD_APPLY, session.instituteScope)
        if (auth is AuthorizationResult.Denied) {
            recordPermissionDenial(session, Permission.RESULT_HOLD_APPLY, auth.reason, incidentId)
            return IncidentOperationResult.PermissionDenied(auth.reason)
        }

        val incident = database.incidentDao().getIncidentById(incidentId)
            ?: return IncidentOperationResult.NotFound("IncidentCase", incidentId)

        val now = nowIso()
        val result = database.examResultDao().getResultByAttemptId(incident.attemptId)

        // Idempotency: if already held, return success
        if (result != null && result.resultStatus == "HELD_PENDING_REVIEW") {
            val actions = database.incidentDao().getPolicyActionsForIncident(incidentId)
            val existingAction = actions.find { it.actionType == "HOLD" && it.executionStatus == "APPLIED" }
            if (existingAction != null) {
                return IncidentOperationResult.Success(existingAction, "noop_idempotent")
            }
        }

        // Apply hold to ExamResultEntity
        if (result != null) {
            database.examResultDao().applyResultHold(
                attemptId = incident.attemptId,
                newStatus = "HELD_PENDING_REVIEW",
                holdReason = reason.ifBlank { "Administrative hold pending incident review" },
                updatedAt = now
            )
        }

        val actionId = "act_hold_${System.currentTimeMillis()}"
        val action = PolicyActionEntity(
            actionId = actionId,
            incidentId = incidentId,
            actionType = "HOLD",
            executionStatus = "APPLIED",
            requestedBy = session.userId,
            approvedBy = session.userId,
            policyVersion = incident.policyVersion,
            reason = reason,
            createdAt = now,
            completedAt = now
        )
        database.incidentDao().insertPolicyAction(action)

        val auditId = "aud_hold_app_${System.currentTimeMillis()}"
        auditEngine.recordAuditEvent(
            auditEventId = auditId,
            actorId = session.userId,
            actorRole = session.role,
            actionType = "RESULT_HOLD_APPLIED",
            aggregateType = "EXAM_RESULT",
            aggregateId = incident.attemptId,
            previousState = result?.resultStatus ?: "UNKNOWN",
            newState = "HELD_PENDING_REVIEW",
            reason = reason,
            operationResult = "SUCCESS"
        )

        return IncidentOperationResult.Success(action, auditId)
    }

    /**
     * Release an applied Result Hold.
     */
    suspend fun releaseResultHold(
        incidentId: String,
        session: UserSession,
        reason: String
    ): IncidentOperationResult<PolicyActionEntity> {
        val auth = PermissionService.authorize(session, Permission.RESULT_HOLD_RELEASE, session.instituteScope)
        if (auth is AuthorizationResult.Denied) {
            recordPermissionDenial(session, Permission.RESULT_HOLD_RELEASE, auth.reason, incidentId)
            return IncidentOperationResult.PermissionDenied(auth.reason)
        }

        if (reason.trim().length < 5) {
            return IncidentOperationResult.ValidationError("reason", "A documented reason is required to release a result hold")
        }

        val incident = database.incidentDao().getIncidentById(incidentId)
            ?: return IncidentOperationResult.NotFound("IncidentCase", incidentId)

        val result = database.examResultDao().getResultByAttemptId(incident.attemptId)
        if (result == null || result.resultStatus != "HELD_PENDING_REVIEW") {
            return IncidentOperationResult.ValidationError("resultStatus", "Result is not currently in HELD_PENDING_REVIEW status")
        }

        val now = nowIso()

        // Revert to GRADED (preserving all scored marks and calculations)
        database.examResultDao().applyResultHold(
            attemptId = incident.attemptId,
            newStatus = "GRADED",
            holdReason = "",
            updatedAt = now
        )

        val actionId = "act_rel_${System.currentTimeMillis()}"
        val action = PolicyActionEntity(
            actionId = actionId,
            incidentId = incidentId,
            actionType = "RELEASE_HOLD",
            executionStatus = "APPLIED",
            requestedBy = session.userId,
            approvedBy = session.userId,
            policyVersion = incident.policyVersion,
            reason = reason,
            createdAt = now,
            completedAt = now
        )
        database.incidentDao().insertPolicyAction(action)

        val auditId = "aud_hold_rel_${System.currentTimeMillis()}"
        auditEngine.recordAuditEvent(
            auditEventId = auditId,
            actorId = session.userId,
            actorRole = session.role,
            actionType = "RESULT_HOLD_RELEASED",
            aggregateType = "EXAM_RESULT",
            aggregateId = incident.attemptId,
            previousState = "HELD_PENDING_REVIEW",
            newState = "GRADED",
            reason = reason,
            operationResult = "SUCCESS"
        )

        return IncidentOperationResult.Success(action, auditId)
    }

    /**
     * Cancel an Exam Attempt following an incident review finding.
     */
    suspend fun cancelAttempt(
        incidentId: String,
        session: UserSession,
        reason: String
    ): IncidentOperationResult<PolicyActionEntity> {
        val auth = PermissionService.authorize(session, Permission.ATTEMPT_CANCEL, session.instituteScope)
        if (auth is AuthorizationResult.Denied) {
            recordPermissionDenial(session, Permission.ATTEMPT_CANCEL, auth.reason, incidentId)
            return IncidentOperationResult.PermissionDenied(auth.reason)
        }

        if (reason.trim().length < 5) {
            return IncidentOperationResult.ValidationError("reason", "A valid justification is required to cancel an attempt")
        }

        val incident = database.incidentDao().getIncidentById(incidentId)
            ?: return IncidentOperationResult.NotFound("IncidentCase", incidentId)

        val attempt = database.examAttemptDao().getAttemptById(incident.attemptId)
            ?: return IncidentOperationResult.NotFound("ExamAttempt", incident.attemptId)

        if (attempt.attemptStatus == "CANCELLED") {
            // Idempotent
            val actions = database.incidentDao().getPolicyActionsForIncident(incidentId)
            val existing = actions.find { it.actionType == "CANCEL_ATTEMPT" && it.executionStatus == "APPLIED" }
            if (existing != null) return IncidentOperationResult.Success(existing, "noop_idempotent")
        }

        val now = nowIso()

        // Atomically cancel attempt and withhold results while preserving candidate response snapshots
        database.examAttemptDao().updateAttemptAndResultStatus(incident.attemptId, "CANCELLED", "WITHHELD", now)

        val actionId = "act_cancel_${System.currentTimeMillis()}"
        val action = PolicyActionEntity(
            actionId = actionId,
            incidentId = incidentId,
            actionType = "CANCEL_ATTEMPT",
            executionStatus = "APPLIED",
            requestedBy = session.userId,
            approvedBy = session.userId,
            policyVersion = incident.policyVersion,
            reason = reason,
            createdAt = now,
            completedAt = now
        )
        database.incidentDao().insertPolicyAction(action)

        val auditId = "aud_att_cancel_${System.currentTimeMillis()}"
        auditEngine.recordAuditEvent(
            auditEventId = auditId,
            actorId = session.userId,
            actorRole = session.role,
            actionType = "ATTEMPT_CANCELLED",
            aggregateType = "EXAM_ATTEMPT",
            aggregateId = incident.attemptId,
            previousState = attempt.attemptStatus,
            newState = "CANCELLED",
            reason = reason,
            operationResult = "SUCCESS"
        )

        return IncidentOperationResult.Success(action, auditId)
    }

    /**
     * Reinstate a previously cancelled attempt.
     */
    suspend fun reinstateAttempt(
        incidentId: String,
        session: UserSession,
        reason: String
    ): IncidentOperationResult<PolicyActionEntity> {
        val auth = PermissionService.authorize(session, Permission.ATTEMPT_REINSTATE, session.instituteScope)
        if (auth is AuthorizationResult.Denied) {
            recordPermissionDenial(session, Permission.ATTEMPT_REINSTATE, auth.reason, incidentId)
            return IncidentOperationResult.PermissionDenied(auth.reason)
        }

        if (reason.trim().length < 5) {
            return IncidentOperationResult.ValidationError("reason", "A valid reason is required to reinstate an attempt")
        }

        val incident = database.incidentDao().getIncidentById(incidentId)
            ?: return IncidentOperationResult.NotFound("IncidentCase", incidentId)

        val attempt = database.examAttemptDao().getAttemptById(incident.attemptId)
            ?: return IncidentOperationResult.NotFound("ExamAttempt", incident.attemptId)

        if (attempt.attemptStatus != "CANCELLED") {
            return IncidentOperationResult.ValidationError("attemptStatus", "Cannot reinstate attempt with status '${attempt.attemptStatus}'. Must be CANCELLED.")
        }

        val now = nowIso()

        // Reinstate attempt to SUBMITTED if responses exist, or IN_PROGRESS
        val responses = database.candidateResponseDao().getResponsesByAttempt(incident.attemptId)
        val restoredStatus = if (responses.isNotEmpty()) "SUBMITTED" else "IN_PROGRESS"

        database.examAttemptDao().updateAttemptAndResultStatus(incident.attemptId, restoredStatus, "PENDING_GRADING", now)

        val actionId = "act_reinstate_${System.currentTimeMillis()}"
        val action = PolicyActionEntity(
            actionId = actionId,
            incidentId = incidentId,
            actionType = "REINSTATE_ATTEMPT",
            executionStatus = "APPLIED",
            requestedBy = session.userId,
            approvedBy = session.userId,
            policyVersion = incident.policyVersion,
            reason = reason,
            createdAt = now,
            completedAt = now
        )
        database.incidentDao().insertPolicyAction(action)

        val auditId = "aud_att_reinstate_${System.currentTimeMillis()}"
        auditEngine.recordAuditEvent(
            auditEventId = auditId,
            actorId = session.userId,
            actorRole = session.role,
            actionType = "ATTEMPT_REINSTATED",
            aggregateType = "EXAM_ATTEMPT",
            aggregateId = incident.attemptId,
            previousState = "CANCELLED",
            newState = restoredStatus,
            reason = reason,
            operationResult = "SUCCESS"
        )

        return IncidentOperationResult.Success(action, auditId)
    }

    /**
     * Submit formal student appeal.
     */
    suspend fun submitAppeal(
        incidentId: String,
        session: UserSession,
        challengedAction: String,
        appealReason: String,
        evidenceReferences: String?
    ): IncidentOperationResult<AppealEntity> {
        val incident = database.incidentDao().getIncidentById(incidentId)
            ?: return IncidentOperationResult.NotFound("IncidentCase", incidentId)

        val auth = PermissionService.authorize(
            session = session,
            permission = Permission.APPEAL_SUBMIT,
            resourceInstituteId = session.instituteScope,
            resourceStudentId = incident.studentId
        )
        if (auth is AuthorizationResult.Denied) {
            recordPermissionDenial(session, Permission.APPEAL_SUBMIT, auth.reason, incidentId)
            return IncidentOperationResult.PermissionDenied(auth.reason)
        }

        if (appealReason.trim().length < 5) {
            return IncidentOperationResult.ValidationError("appealReason", "A detailed reason is required for appeal submission")
        }

        val now = nowIso()
        val appealId = "appl_${System.currentTimeMillis()}_${session.userId.take(6)}"

        val appeal = AppealEntity(
            appealId = appealId,
            incidentId = incidentId,
            attemptId = incident.attemptId,
            studentId = incident.studentId,
            challengedAction = challengedAction,
            appealReason = appealReason,
            evidenceReferences = evidenceReferences,
            appealStatus = "SUBMITTED",
            assignedReviewerId = null,
            reviewerDecision = null,
            outcomeReason = null,
            submittedAt = now,
            resolvedAt = null,
            updatedAt = now
        )

        database.incidentDao().insertAppeal(appeal)

        val auditId = "aud_appl_sub_${System.currentTimeMillis()}"
        auditEngine.recordAuditEvent(
            auditEventId = auditId,
            actorId = session.userId,
            actorRole = session.role,
            actionType = "APPEAL_SUBMITTED",
            aggregateType = "INCIDENT_APPEAL",
            aggregateId = appealId,
            previousState = null,
            newState = "SUBMITTED",
            reason = appealReason,
            operationResult = "SUCCESS"
        )

        return IncidentOperationResult.Success(appeal, auditId)
    }

    /**
     * Adjudicate formal student appeal by an independent review official.
     */
    suspend fun adjudicateAppeal(
        appealId: String,
        session: UserSession,
        decision: String, // UPHELD, OVERTURNED, REQUIRES_MORE_INFORMATION
        outcomeReason: String
    ): IncidentOperationResult<AppealEntity> {
        val auth = PermissionService.authorize(session, Permission.APPEAL_DECIDE, session.instituteScope)
        if (auth is AuthorizationResult.Denied) {
            recordPermissionDenial(session, Permission.APPEAL_DECIDE, auth.reason, appealId)
            return IncidentOperationResult.PermissionDenied(auth.reason)
        }

        val appeal = database.incidentDao().getAppealById(appealId)
            ?: return IncidentOperationResult.NotFound("IncidentAppeal", appealId)

        val incident = database.incidentDao().getIncidentById(appeal.incidentId)
            ?: return IncidentOperationResult.NotFound("IncidentCase", appeal.incidentId)

        // Strict Safeguard: The original reviewer cannot decide their own appeal!
        if (incident.reviewerId != null && incident.reviewerId == session.userId) {
            return IncidentOperationResult.PermissionDenied(
                "Independent Review Rule: Original reviewer '${session.userId}' cannot adjudicate their own appeal."
            )
        }

        if (!setOf("UPHELD", "OVERTURNED", "REQUIRES_MORE_INFORMATION").contains(decision)) {
            return IncidentOperationResult.ValidationError("decision", "Invalid appeal decision '$decision'")
        }

        if (outcomeReason.trim().length < 5) {
            return IncidentOperationResult.ValidationError("outcomeReason", "Documented justification required for appeal decision")
        }

        val now = nowIso()
        val updatedAppeal = appeal.copy(
            appealStatus = decision,
            assignedReviewerId = session.userId,
            reviewerDecision = decision,
            outcomeReason = outcomeReason,
            resolvedAt = if (decision != "REQUIRES_MORE_INFORMATION") now else null,
            updatedAt = now
        )

        database.incidentDao().updateAppeal(updatedAppeal)

        val auditId = "aud_appl_dec_${System.currentTimeMillis()}"
        auditEngine.recordAuditEvent(
            auditEventId = auditId,
            actorId = session.userId,
            actorRole = session.role,
            actionType = "APPEAL_DECIDED",
            aggregateType = "INCIDENT_APPEAL",
            aggregateId = appealId,
            previousState = appeal.appealStatus,
            newState = decision,
            reason = outcomeReason,
            operationResult = "SUCCESS"
        )

        return IncidentOperationResult.Success(updatedAppeal, auditId)
    }

    /**
     * Retrieve full incident summary with timeline.
     */
    suspend fun getIncidentSummary(incidentId: String): IncidentSummary {
        val incident = database.incidentDao().getIncidentById(incidentId)
            ?: throw IllegalArgumentException("Incident $incidentId not found")

        val decisions = database.incidentDao().getDecisionsForIncident(incidentId)
        val actions = database.incidentDao().getPolicyActionsForIncident(incidentId)
        val appeals = database.incidentDao().getAppealsForIncident(incidentId)
        val student = database.userDao().getUserById(incident.studentId)
        val exam = database.examDao().getExamById(incident.examId)

        return IncidentSummary(
            incident = incident,
            decisions = decisions,
            policyActions = actions,
            appeals = appeals,
            studentDisplayName = student?.displayName ?: incident.studentId,
            examTitle = exam?.title ?: incident.examId
        )
    }

    private suspend fun recordPermissionDenial(
        session: UserSession,
        permission: Permission,
        reason: String,
        resourceId: String
    ) {
        auditEngine.recordAuditEvent(
            auditEventId = "aud_perm_den_${System.currentTimeMillis()}",
            actorId = session.userId,
            actorRole = session.role,
            actionType = "PERMISSION_DENIED",
            aggregateType = "ACCESS_CONTROL",
            aggregateId = resourceId,
            reason = "Unauthorized action '$permission': $reason",
            operationResult = "DENIED"
        )
    }
}
