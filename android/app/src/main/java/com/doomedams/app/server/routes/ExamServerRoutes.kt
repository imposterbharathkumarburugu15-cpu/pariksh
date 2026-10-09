package com.doomedams.app.server.routes

import com.doomedams.app.database.AppDatabase
import com.doomedams.app.database.audit.AuditEngine
import com.doomedams.app.database.entity.CandidateResponseEntity
import com.doomedams.app.database.entity.IntegrityEventEntity
import com.doomedams.app.database.entity.UserCredentialEntity
import com.doomedams.app.database.entity.UserEntity
import com.doomedams.app.database.repository.AttemptRepository
import com.doomedams.app.server.auth.SessionManager
import com.doomedams.app.server.auth.UserSession
import com.doomedams.app.server.rbac.AuthorizationResult
import com.doomedams.app.server.rbac.Permission
import com.doomedams.app.server.rbac.PermissionService
import com.doomedams.app.server.security.PasswordHasher
import com.doomedams.app.server.security.RateLimiter
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeFormatter

val PERMITTED_INTEGRITY_CATEGORIES = setOf(
    "UNAUTHORISED_APP_SWITCH",
    "WINDOW_BLUR",
    "APP_BACKGROUND",
    "NETWORK_DISCONNECT",
    "NETWORK_RECONNECT",
    "EXAM_RESUMED",
    "SUBMISSION_ATTEMPT",
    "PACKAGE_VALIDATION_FAILURE"
)

val PERMITTED_ROLES = setOf(
    "STUDENT",
    "INVIGILATOR",
    "INCIDENT_REVIEWER",
    "EXAMINATION_ADMIN",
    "CHIEF_EXAMINATION_OFFICER",
    "AUDITOR",
    "SYSTEM_ADMIN"
)

fun Application.configureExamServerRoutes(
    database: AppDatabase,
    sessionManager: SessionManager,
    auditEngine: AuditEngine,
    attemptRepository: AttemptRepository,
    rateLimiter: RateLimiter,
    tlsFingerprint: String? = null
) {
    routing {
        // 1. Server Status Endpoint
        get("/api/server/status") {
            val json = JSONObject().apply {
                put("status", "RUNNING")
                put("version", "1.0.0")
                put("tlsEnabled", tlsFingerprint != null)
                put("sha256Fingerprint", tlsFingerprint ?: JSONObject.NULL)
                put("activeSessions", sessionManager.getActiveSessionCount())
            }
            call.respondText(json.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }

        // 2. Authentication: Login
        post("/api/auth/login") {
            val bodyText = call.receiveText()
            val reqJson = try {
                JSONObject(bodyText)
            } catch (e: Exception) {
                call.respondText(errorJson("MALFORMED_JSON", "Invalid JSON payload"), ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            val userId = reqJson.optString("userId").trim()
            val password = reqJson.optString("password")

            if (userId.isEmpty() || password.isEmpty()) {
                call.respondText(errorJson("INVALID_INPUT", "userId and password are required"), ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            // Rate limit check to throttle brute-force attacks
            if (!rateLimiter.isAllowed(userId)) {
                call.respondText(errorJson("RATE_LIMITED", "Too many authentication attempts. Please try again later."), ContentType.Application.Json, HttpStatusCode.TooManyRequests)
                return@post
            }

            val user = database.userDao().getUserById(userId)
            val credential = database.userCredentialDao().getCredentialByUserId(userId)

            if (user == null || credential == null || user.accountStatus != "ACTIVE") {
                // Audit failed login
                auditEngine.recordAuditEvent(
                    auditEventId = "aud_login_fail_${System.currentTimeMillis()}",
                    actorId = if (user != null) user.userId else "unknown",
                    actorRole = if (user != null) user.role else "UNKNOWN",
                    actionType = "LOGIN_ATTEMPT_FAILED",
                    aggregateType = "USER",
                    aggregateId = userId,
                    operationResult = "REJECTED",
                    failureReason = if (user?.accountStatus != "ACTIVE") "ACCOUNT_DISABLED" else "INVALID_CREDENTIALS"
                )
                val statusReason = if (user != null && user.accountStatus != "ACTIVE") "ACCOUNT_DISABLED" else "INVALID_CREDENTIALS"
                call.respondText(errorJson(statusReason, "Invalid user ID, password, or account status"), ContentType.Application.Json, HttpStatusCode.Unauthorized)
                return@post
            }

            val passwordValid = PasswordHasher.verifyPassword(password, credential.passwordHash)
            if (!passwordValid) {
                auditEngine.recordAuditEvent(
                    auditEventId = "aud_login_fail_${System.currentTimeMillis()}",
                    actorId = user.userId,
                    actorRole = user.role,
                    actionType = "LOGIN_ATTEMPT_FAILED",
                    aggregateType = "USER",
                    aggregateId = userId,
                    operationResult = "REJECTED",
                    failureReason = "INVALID_PASSWORD"
                )
                call.respondText(errorJson("INVALID_CREDENTIALS", "Invalid user ID or password"), ContentType.Application.Json, HttpStatusCode.Unauthorized)
                return@post
            }

            // Authentication succeeded
            rateLimiter.reset(userId)
            val session = sessionManager.createSession(
                userId = user.userId,
                displayName = user.displayName,
                role = user.role,
                instituteScope = user.instituteId
            )

            auditEngine.recordAuditEvent(
                auditEventId = "aud_login_success_${System.currentTimeMillis()}",
                actorId = user.userId,
                actorRole = user.role,
                actionType = "LOGIN_SUCCESS",
                aggregateType = "SESSION",
                aggregateId = session.sessionId,
                operationResult = "SUCCESS"
            )

            val resp = JSONObject().apply {
                put("token", session.token)
                put("expiresAt", session.expiresAt.toString())
                put("user", JSONObject().apply {
                    put("userId", user.userId)
                    put("displayName", user.displayName)
                    put("role", user.role)
                    put("instituteId", user.instituteId)
                })
            }
            call.respondText(resp.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }

        // 3. Authentication: Get Current Safe Profile
        get("/api/auth/me") {
            val session = authenticate(call, sessionManager) ?: return@get
            val user = database.userDao().getUserById(session.userId)
            if (user == null || user.accountStatus != "ACTIVE") {
                call.respondText(errorJson("ACCOUNT_INACTIVE", "Account is not active"), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@get
            }

            val resp = JSONObject().apply {
                put("userId", user.userId)
                put("displayName", user.displayName)
                put("role", user.role)
                put("accountStatus", user.accountStatus)
                put("instituteId", user.instituteId)
                put("createdAt", user.createdAt)
            }
            call.respondText(resp.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }

        // 4. Authentication: Logout
        post("/api/auth/logout") {
            val session = authenticate(call, sessionManager) ?: return@post
            sessionManager.invalidateSession(session.token)

            auditEngine.recordAuditEvent(
                auditEventId = "aud_logout_${System.currentTimeMillis()}",
                actorId = session.userId,
                actorRole = session.role,
                actionType = "LOGOUT",
                aggregateType = "SESSION",
                aggregateId = session.sessionId,
                operationResult = "SUCCESS"
            )

            call.respondText(JSONObject().put("success", true).toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }

        // 5. Admin: User Provisioning
        post("/api/admin/users/provision") {
            val session = authenticate(call, sessionManager) ?: return@post

            // Server-side RBAC check
            val authCheck = PermissionService.authorize(session, Permission.USER_PROVISION)
            if (authCheck is AuthorizationResult.Denied) {
                recordPermissionDenial(auditEngine, session, Permission.USER_PROVISION, authCheck.reason)
                call.respondText(errorJson("FORBIDDEN", authCheck.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@post
            }

            val body = call.receiveText()
            val req = try { JSONObject(body) } catch (e: Exception) {
                call.respondText(errorJson("MALFORMED_JSON", "Invalid JSON payload"), ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            val newUserId = req.optString("userId").trim()
            val displayName = req.optString("displayName").trim()
            val role = req.optString("role").trim()
            val instituteId = req.optString("instituteId").trim()
            val initialPassword = req.optString("password").trim()

            if (newUserId.isEmpty() || displayName.isEmpty() || role.isEmpty() || instituteId.isEmpty() || initialPassword.isEmpty()) {
                call.respondText(errorJson("INVALID_INPUT", "Missing required fields for user provisioning"), ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            if (!PERMITTED_ROLES.contains(role)) {
                call.respondText(errorJson("INVALID_ROLE", "Unsupported role '$role'"), ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            if (database.userDao().getUserById(newUserId) != null) {
                call.respondText(errorJson("CONFLICT", "User with ID '$newUserId' already exists"), ContentType.Application.Json, HttpStatusCode.Conflict)
                return@post
            }

            val nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
            val (hash, salt) = PasswordHasher.hashPassword(initialPassword)

            auditEngine.executeAuditedTransaction(
                auditEventId = "aud_prov_${System.currentTimeMillis()}",
                actorId = session.userId,
                actorRole = session.role,
                actionType = "USER_PROVISION",
                aggregateType = "USER",
                aggregateId = newUserId,
                previousState = null,
                newState = "{\"role\":\"$role\",\"instituteId\":\"$instituteId\"}",
                reason = "User provisioned by administrator"
            ) {
                database.userDao().insertUser(
                    UserEntity(
                        userId = newUserId,
                        displayName = displayName,
                        role = role,
                        accountStatus = "ACTIVE",
                        instituteId = instituteId,
                        createdAt = nowIso
                    )
                )
                database.userCredentialDao().insertCredential(
                    UserCredentialEntity(
                        userId = newUserId,
                        passwordHash = hash,
                        salt = salt,
                        updatedAt = nowIso
                    )
                )
            }

            val resp = JSONObject().apply {
                put("success", true)
                put("userId", newUserId)
                put("role", role)
            }
            call.respondText(resp.toString(), ContentType.Application.Json, HttpStatusCode.Created)
        }

        // 6. Exam: Get Exam Details
        get("/api/exams/{id}") {
            val session = authenticate(call, sessionManager) ?: return@get
            val examId = call.parameters["id"] ?: ""

            val exam = database.examDao().getExamById(examId)
            if (exam == null) {
                call.respondText(errorJson("NOT_FOUND", "Exam not found"), ContentType.Application.Json, HttpStatusCode.NotFound)
                return@get
            }

            // Scope & Permission check
            val authCheck = PermissionService.authorize(
                session = session,
                permission = Permission.EXAM_READ,
                resourceInstituteId = exam.instituteId,
                resourceExamId = exam.examId
            )
            if (authCheck is AuthorizationResult.Denied) {
                recordPermissionDenial(auditEngine, session, Permission.EXAM_READ, authCheck.reason)
                call.respondText(errorJson("FORBIDDEN", authCheck.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@get
            }

            val resp = JSONObject().apply {
                put("examId", exam.examId)
                put("title", exam.title)
                put("status", exam.status)
                put("durationSeconds", exam.durationSeconds)
                put("instituteId", exam.instituteId)
                put("markingSchemeVersion", exam.markingSchemeVersion)
                put("createdAt", exam.createdAt)
            }
            call.respondText(resp.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }

        // 7. Answers: Save Candidate Answer
        post("/api/attempts/{attemptId}/answers") {
            val session = authenticate(call, sessionManager) ?: return@post
            val attemptId = call.parameters["attemptId"] ?: ""

            val attempt = database.examAttemptDao().getAttemptById(attemptId)
            if (attempt == null) {
                call.respondText(errorJson("NOT_FOUND", "Attempt not found"), ContentType.Application.Json, HttpStatusCode.NotFound)
                return@post
            }

            // Server-side RBAC: Candidate must own the attempt
            val authCheck = PermissionService.authorize(
                session = session,
                permission = Permission.ANSWER_WRITE_OWN,
                resourceStudentId = attempt.studentId
            )
            if (authCheck is AuthorizationResult.Denied) {
                recordPermissionDenial(auditEngine, session, Permission.ANSWER_WRITE_OWN, authCheck.reason)
                call.respondText(errorJson("FORBIDDEN", authCheck.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@post
            }

            if (attempt.attemptStatus != "IN_PROGRESS") {
                call.respondText(errorJson("INVALID_STATE", "Cannot submit answers to attempt in status '${attempt.attemptStatus}'"), ContentType.Application.Json, HttpStatusCode.Conflict)
                return@post
            }

            val body = call.receiveText()
            val req = try { JSONObject(body) } catch (e: Exception) {
                call.respondText(errorJson("MALFORMED_JSON", "Invalid JSON payload"), ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            val questionId = req.optString("questionId").trim()
            val responsePayload = req.optString("responsePayload")
            val revisionSequence = req.optInt("revisionSequence", 1)
            val idempotencyKey = req.optString("idempotencyKey").takeIf { it.isNotEmpty() }

            if (questionId.isEmpty() || responsePayload.isEmpty()) {
                call.respondText(errorJson("INVALID_INPUT", "questionId and responsePayload are required"), ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            val responseId = "resp_${attemptId}_$questionId"
            val nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
            val candidateResp = CandidateResponseEntity(
                responseId = responseId,
                attemptId = attemptId,
                questionId = questionId,
                responsePayload = responsePayload,
                revisionSequence = revisionSequence,
                savedAt = nowIso
            )

            // Persist transactionally and audit
            attemptRepository.saveAnswerTransactionally(
                response = candidateResp,
                auditEventId = "aud_ans_${System.currentTimeMillis()}_${questionId.take(6)}",
                studentId = session.userId
            )

            val resp = JSONObject().apply {
                put("success", true)
                put("responseId", responseId)
                put("savedAt", nowIso)
            }
            call.respondText(resp.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }

        // 8. Integrity Telemetry: Report Integrity Event
        post("/api/attempts/{attemptId}/integrity-events") {
            val session = authenticate(call, sessionManager) ?: return@post
            val attemptId = call.parameters["attemptId"] ?: ""

            val attempt = database.examAttemptDao().getAttemptById(attemptId)
            if (attempt == null) {
                call.respondText(errorJson("NOT_FOUND", "Attempt not found"), ContentType.Application.Json, HttpStatusCode.NotFound)
                return@post
            }

            val authCheck = PermissionService.authorize(
                session = session,
                permission = Permission.INTEGRITY_EVENT_REPORT_OWN,
                resourceStudentId = attempt.studentId
            )
            if (authCheck is AuthorizationResult.Denied) {
                recordPermissionDenial(auditEngine, session, Permission.INTEGRITY_EVENT_REPORT_OWN, authCheck.reason)
                call.respondText(errorJson("FORBIDDEN", authCheck.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@post
            }

            val body = call.receiveText()
            val req = try { JSONObject(body) } catch (e: Exception) {
                call.respondText(errorJson("MALFORMED_JSON", "Invalid JSON payload"), ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            val eventId = req.optString("eventId").trim()
            val eventCategory = req.optString("eventCategory").trim()
            val deviceReportedAt = req.optString("deviceReportedAt").trim()
            val sequenceNumber = req.optLong("sequenceNumber", 1L)
            val sourceDeviceId = req.optString("sourceDeviceId").trim()
            val payloadJson = req.optString("payloadJson", "{}")
            val appPackageName = if (req.has("appPackageName") && !req.isNull("appPackageName")) req.getString("appPackageName") else null
            val displayedAppName = if (req.has("displayedAppName") && !req.isNull("displayedAppName")) req.getString("displayedAppName") else null

            if (eventId.isEmpty() || eventCategory.isEmpty() || deviceReportedAt.isEmpty() || sourceDeviceId.isEmpty()) {
                call.respondText(errorJson("INVALID_INPUT", "Missing required integrity event identifiers"), ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            if (!PERMITTED_INTEGRITY_CATEGORIES.contains(eventCategory)) {
                call.respondText(errorJson("UNSUPPORTED_CATEGORY", "Integrity event category '$eventCategory' is not supported"), ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            // Deduplication: Check if eventId already exists
            val existing = database.integrityEventDao().getEventById(eventId)
            if (existing != null) {
                val dupResp = JSONObject().apply {
                    put("status", "DUPLICATE_IGNORED")
                    put("eventId", eventId)
                }
                call.respondText(dupResp.toString(), ContentType.Application.Json, HttpStatusCode.OK)
                return@post
            }

            val serverReceivedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
            val eventEntity = IntegrityEventEntity(
                eventId = eventId,
                examId = attempt.examId,
                attemptId = attemptId,
                studentId = attempt.studentId,
                eventCategory = eventCategory,
                deviceReportedAt = deviceReportedAt,
                receivedAt = serverReceivedAt,
                sequenceNumber = sequenceNumber,
                sourceDeviceId = sourceDeviceId,
                payloadJson = payloadJson,
                payloadHash = null,
                appPackageName = appPackageName,
                displayedAppName = displayedAppName,
                ingestionStatus = "INGESTED"
            )

            database.integrityEventDao().insertEvent(eventEntity)

            auditEngine.recordAuditEvent(
                auditEventId = "aud_integ_${System.currentTimeMillis()}_${eventId.take(8)}",
                actorId = session.userId,
                actorRole = session.role,
                actionType = "INTEGRITY_EVENT_INGESTED",
                aggregateType = "INTEGRITY_EVENT",
                aggregateId = eventId,
                previousState = null,
                newState = "{\"category\":\"$eventCategory\"}",
                reason = "Integrity telemetry event logged by client",
                operationResult = "SUCCESS"
            )

            val resp = JSONObject().apply {
                put("status", "INGESTED")
                put("eventId", eventId)
                put("receivedAt", serverReceivedAt)
            }
            call.respondText(resp.toString(), ContentType.Application.Json, HttpStatusCode.Created)
        }
    }
}

private suspend fun authenticate(call: ApplicationCall, sessionManager: SessionManager): UserSession? {
    val authHeader = call.request.headers["Authorization"]
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
        call.respondText(
            errorJson("UNAUTHORIZED", "Missing or invalid Authorization header"),
            ContentType.Application.Json,
            HttpStatusCode.Unauthorized
        )
        return null
    }

    val token = authHeader.removePrefix("Bearer ").trim()
    val session = sessionManager.getSession(token)
    if (session == null) {
        call.respondText(
            errorJson("SESSION_EXPIRED", "Session is invalid or has expired"),
            ContentType.Application.Json,
            HttpStatusCode.Unauthorized
        )
        return null
    }

    return session
}

private suspend fun recordPermissionDenial(
    auditEngine: AuditEngine,
    session: UserSession,
    permission: Permission,
    reason: String
) {
    auditEngine.recordAuditEvent(
        auditEventId = "aud_deny_${System.currentTimeMillis()}",
        actorId = session.userId,
        actorRole = session.role,
        actionType = "PERMISSION_DENIED",
        aggregateType = "PERMISSION",
        aggregateId = permission.name,
        operationResult = "REJECTED",
        failureReason = reason
    )
}

private fun errorJson(code: String, message: String): String {
    return JSONObject().apply {
        put("error", code)
        put("message", message)
    }.toString()
}
