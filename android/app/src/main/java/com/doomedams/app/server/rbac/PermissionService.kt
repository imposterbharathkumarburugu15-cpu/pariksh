package com.doomedams.app.server.rbac

import com.doomedams.app.server.auth.UserSession

/**
 * Explicit Named Permissions supported by DOOMEDAMS Local Server
 */
enum class Permission {
    SERVER_STATUS_READ,
    SERVER_HOST_START,
    SERVER_HOST_STOP,
    USER_READ,
    USER_PROVISION,
    EXAM_READ,
    EXAM_CREATE,
    EXAM_UPDATE,
    EXAM_PUBLISH,
    ATTEMPT_READ_OWN,
    ATTEMPT_READ_ASSIGNED,
    ANSWER_WRITE_OWN,
    ANSWER_READ_ASSIGNED,
    INTEGRITY_EVENT_REPORT_OWN,
    INTEGRITY_EVENT_READ_ASSIGNED,
    INCIDENT_READ_ASSIGNED,
    INCIDENT_REVIEW,
    RESULT_HOLD_APPLY,
    RESULT_HOLD_RELEASE,
    ATTEMPT_CANCEL,
    ATTEMPT_REINSTATE,
    APPEAL_SUBMIT,
    APPEAL_DECIDE,
    AUDIT_READ,
    POLICY_ADMINISTER,
    RESULT_GRADE,
    RESULT_READ_OWN,
    RESULT_READ_ASSIGNED,
    RESULT_PUBLISH,
    ANALYTICS_OVERVIEW_READ,
    ANALYTICS_EXAM_READ,
    ANALYTICS_STUDENT_READ,
    ANALYTICS_TIMING_READ,
    ANALYTICS_INTEGRITY_READ,
    REPORT_EXPORT
}

/**
 * Access Decision Outcome
 */
sealed class AuthorizationResult {
    object Granted : AuthorizationResult()
    data class Denied(val reason: String) : AuthorizationResult()
}

/**
 * Central Server-Side Role-Based Access Control and Resource-Scoping Service
 */
object PermissionService {

    private val ROLE_PERMISSIONS: Map<String, Set<Permission>> = mapOf(
        "STUDENT" to setOf(
            Permission.SERVER_STATUS_READ,
            Permission.EXAM_READ,
            Permission.ATTEMPT_READ_OWN,
            Permission.ANSWER_WRITE_OWN,
            Permission.INTEGRITY_EVENT_REPORT_OWN,
            Permission.RESULT_READ_OWN,
            Permission.APPEAL_SUBMIT
        ),
        "INVIGILATOR" to setOf(
            Permission.SERVER_STATUS_READ,
            Permission.EXAM_READ,
            Permission.ATTEMPT_READ_ASSIGNED,
            Permission.ANSWER_READ_ASSIGNED,
            Permission.INTEGRITY_EVENT_READ_ASSIGNED,
            Permission.INCIDENT_READ_ASSIGNED,
            Permission.RESULT_READ_ASSIGNED,
            Permission.ANALYTICS_EXAM_READ,
            Permission.ANALYTICS_STUDENT_READ,
            Permission.ANALYTICS_TIMING_READ
        ),
        "INCIDENT_REVIEWER" to setOf(
            Permission.SERVER_STATUS_READ,
            Permission.EXAM_READ,
            Permission.ATTEMPT_READ_ASSIGNED,
            Permission.ANSWER_READ_ASSIGNED,
            Permission.INTEGRITY_EVENT_READ_ASSIGNED,
            Permission.INCIDENT_READ_ASSIGNED,
            Permission.INCIDENT_REVIEW,
            Permission.RESULT_HOLD_APPLY,
            Permission.RESULT_HOLD_RELEASE,
            Permission.RESULT_READ_ASSIGNED,
            Permission.ANALYTICS_INTEGRITY_READ
            // NOTE: ATTEMPT_CANCEL and ATTEMPT_REINSTATE are deliberately excluded from INCIDENT_REVIEWER
        ),
        "EXAMINATION_ADMIN" to setOf(
            Permission.SERVER_STATUS_READ,
            Permission.USER_READ,
            Permission.USER_PROVISION,
            Permission.EXAM_READ,
            Permission.EXAM_CREATE,
            Permission.EXAM_UPDATE,
            Permission.EXAM_PUBLISH,
            Permission.ATTEMPT_READ_ASSIGNED,
            Permission.ANSWER_READ_ASSIGNED,
            Permission.INTEGRITY_EVENT_READ_ASSIGNED,
            Permission.INCIDENT_READ_ASSIGNED,
            Permission.INCIDENT_REVIEW,
            Permission.RESULT_HOLD_APPLY,
            Permission.RESULT_HOLD_RELEASE,
            Permission.ATTEMPT_CANCEL,
            Permission.ATTEMPT_REINSTATE,
            Permission.APPEAL_DECIDE,
            Permission.AUDIT_READ,
            Permission.RESULT_GRADE,
            Permission.RESULT_READ_ASSIGNED,
            Permission.RESULT_PUBLISH,
            Permission.ANALYTICS_OVERVIEW_READ,
            Permission.ANALYTICS_EXAM_READ,
            Permission.ANALYTICS_STUDENT_READ,
            Permission.ANALYTICS_TIMING_READ,
            Permission.ANALYTICS_INTEGRITY_READ,
            Permission.REPORT_EXPORT
        ),
        "CHIEF_EXAMINATION_OFFICER" to setOf(
            Permission.SERVER_STATUS_READ,
            Permission.SERVER_HOST_START,
            Permission.SERVER_HOST_STOP,
            Permission.USER_READ,
            Permission.USER_PROVISION,
            Permission.EXAM_READ,
            Permission.EXAM_CREATE,
            Permission.EXAM_UPDATE,
            Permission.EXAM_PUBLISH,
            Permission.ATTEMPT_READ_ASSIGNED,
            Permission.ANSWER_READ_ASSIGNED,
            Permission.INTEGRITY_EVENT_READ_ASSIGNED,
            Permission.INCIDENT_READ_ASSIGNED,
            Permission.INCIDENT_REVIEW,
            Permission.RESULT_HOLD_APPLY,
            Permission.RESULT_HOLD_RELEASE,
            Permission.ATTEMPT_CANCEL,
            Permission.ATTEMPT_REINSTATE,
            Permission.APPEAL_DECIDE,
            Permission.AUDIT_READ,
            Permission.POLICY_ADMINISTER,
            Permission.RESULT_GRADE,
            Permission.RESULT_READ_ASSIGNED,
            Permission.RESULT_PUBLISH,
            Permission.ANALYTICS_OVERVIEW_READ,
            Permission.ANALYTICS_EXAM_READ,
            Permission.ANALYTICS_STUDENT_READ,
            Permission.ANALYTICS_TIMING_READ,
            Permission.ANALYTICS_INTEGRITY_READ,
            Permission.REPORT_EXPORT
        ),
        "AUDITOR" to setOf(
            Permission.SERVER_STATUS_READ,
            Permission.USER_READ,
            Permission.EXAM_READ,
            Permission.ATTEMPT_READ_ASSIGNED,
            Permission.ANSWER_READ_ASSIGNED,
            Permission.INTEGRITY_EVENT_READ_ASSIGNED,
            Permission.INCIDENT_READ_ASSIGNED,
            Permission.AUDIT_READ,
            Permission.RESULT_READ_ASSIGNED,
            Permission.ANALYTICS_OVERVIEW_READ,
            Permission.ANALYTICS_EXAM_READ,
            Permission.ANALYTICS_INTEGRITY_READ,
            Permission.REPORT_EXPORT
            // NOTE: AUDITOR has read-only access to audit logs and incidents; cannot modify or review
        ),
        "SYSTEM_ADMIN" to setOf(
            Permission.SERVER_STATUS_READ,
            Permission.SERVER_HOST_START,
            Permission.SERVER_HOST_STOP,
            Permission.USER_READ,
            Permission.USER_PROVISION,
            Permission.AUDIT_READ
            // NOTE: Device admin cannot modify academic misconduct decisions or cancel student attempts
        )
    )

    fun hasPermission(role: String, permission: Permission): Boolean {
        val permissions = ROLE_PERMISSIONS[role] ?: return false
        return permissions.contains(permission)
    }

    fun authorize(
        session: UserSession,
        permission: Permission,
        resourceInstituteId: String? = null,
        resourceStudentId: String? = null,
        resourceExamId: String? = null
    ): AuthorizationResult {
        // 1. Check Role-to-Permission Grant
        if (!hasPermission(session.role, permission)) {
            return AuthorizationResult.Denied(
                "Role '${session.role}' is not granted permission '$permission'."
            )
        }

        // 2. Institute Scope Enforcement
        if (resourceInstituteId != null && session.role != "SYSTEM_ADMIN") {
            if (session.instituteScope != resourceInstituteId) {
                return AuthorizationResult.Denied(
                    "Actor institute scope '${session.instituteScope}' does not match resource institute '$resourceInstituteId'."
                )
            }
        }

        // 3. Student Attempt/Result Ownership Enforcement
        if (permission == Permission.ANSWER_WRITE_OWN || permission == Permission.ATTEMPT_READ_OWN || permission == Permission.INTEGRITY_EVENT_REPORT_OWN || permission == Permission.RESULT_READ_OWN || permission == Permission.APPEAL_SUBMIT) {
            if (resourceStudentId != null && session.userId != resourceStudentId) {
                return AuthorizationResult.Denied(
                    "Forbidden: Student '${session.userId}' is not the owner of resource for student '$resourceStudentId'."
                )
            }
        }

        // 4. Examination Scope Enforcement (for scoped proctors)
        if (resourceExamId != null && session.examinationScope != null) {
            if (session.examinationScope != resourceExamId) {
                return AuthorizationResult.Denied(
                    "Actor is assigned strictly to examination '${session.examinationScope}', cannot access '$resourceExamId'."
                )
            }
        }

        return AuthorizationResult.Granted
    }
}
