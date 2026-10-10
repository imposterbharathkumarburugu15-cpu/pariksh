package com.doomedams.app.server.routes

import com.doomedams.app.database.AppDatabase
import com.doomedams.app.database.audit.AuditEngine
import com.doomedams.app.database.entity.AnswerKeyEntity
import com.doomedams.app.database.entity.CandidateResponseEntity
import com.doomedams.app.database.entity.ExamEntity
import com.doomedams.app.database.entity.IncidentCaseEntity
import com.doomedams.app.database.entity.IntegrityEventEntity
import com.doomedams.app.database.entity.MarkingSchemeEntity
import com.doomedams.app.database.entity.PaperImportEntity
import com.doomedams.app.database.entity.PaperSectionEntity
import com.doomedams.app.database.entity.PolicyActionEntity
import com.doomedams.app.database.entity.QuestionEntity
import com.doomedams.app.database.entity.UserCredentialEntity
import com.doomedams.app.database.entity.UserEntity
import com.doomedams.app.database.entity.ValidationIssueEntity
import com.doomedams.app.database.entity.WorkedSolutionEntity
import com.doomedams.app.database.repository.AttemptRepository
import com.doomedams.app.database.repository.ExamRepository
import com.doomedams.app.server.analytics.AnalyticsService
import com.doomedams.app.server.auth.SessionManager
import com.doomedams.app.server.auth.UserSession
import com.doomedams.app.server.compiler.DocumentExtractor
import com.doomedams.app.server.compiler.PaperValidationEngine
import com.doomedams.app.server.compiler.QuestionStructuralParser
import com.doomedams.app.server.incident.IncidentReviewService
import com.doomedams.app.server.incident.IncidentOperationResult
import com.doomedams.app.server.rbac.AuthorizationResult
import com.doomedams.app.server.rbac.Permission
import com.doomedams.app.server.rbac.PermissionService
import com.doomedams.app.server.scoring.MultiSelectPolicy
import com.doomedams.app.server.scoring.ScoringEngine
import com.doomedams.app.server.security.PasswordHasher
import com.doomedams.app.server.security.RateLimiter
import com.doomedams.app.server.sync.SyncEventEnvelope
import com.doomedams.app.server.sync.SyncService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Base64

val PERMITTED_INTEGRITY_CATEGORIES = setOf(
    "EXAM_APP_BACKGROUND",
    "EXAM_APP_RESUMED",
    "LOCK_TASK_ENTERED",
    "LOCK_TASK_EXITED",
    "PROHIBITED_APP_FOREGROUND",
    "APP_NAME_UNAVAILABLE",
    "USAGE_ACCESS_REVOKED",
    "NETWORK_DISCONNECTED",
    "NETWORK_RECONNECTED",
    "EXAM_PACKAGE_VALIDATION_FAILED",
    "CLOCK_ANOMALY",
    "ATTEMPT_RESUMED",
    "UNEXPECTED_EXAM_RESTART",
    "MONITORING_CAPABILITY_LOST",
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
    configureExamServerRoutes(
        database = database,
        sessionManager = sessionManager,
        auditEngine = auditEngine,
        attemptRepository = attemptRepository,
        examRepository = ExamRepository(database, auditEngine),
        rateLimiter = rateLimiter,
        tlsFingerprint = tlsFingerprint
    )
}

fun Application.configureExamServerRoutes(
    database: AppDatabase,
    sessionManager: SessionManager,
    auditEngine: AuditEngine,
    attemptRepository: AttemptRepository,
    examRepository: ExamRepository,
    rateLimiter: RateLimiter,
    tlsFingerprint: String? = null
) {
    val syncService = SyncService(database, auditEngine, attemptRepository)

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

            val userId = reqJson.optString("userId", "").trim()
            val password = reqJson.optString("password", "")

            if (userId.isEmpty() || password.isEmpty()) {
                call.respondText(errorJson("INVALID_CREDENTIALS", "Missing userId or password"), ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            if (!rateLimiter.isAllowed(userId)) {
                call.respondText(errorJson("TOO_MANY_REQUESTS", "Too many failed attempts. Please wait before retrying."), ContentType.Application.Json, HttpStatusCode.TooManyRequests)
                return@post
            }

            val user = database.userDao().getUserById(userId)
            val credential = database.userCredentialDao().getCredentialByUserId(userId)

            if (user == null || credential == null || user.accountStatus != "ACTIVE") {
                auditEngine.recordAuditEvent(
                    auditEventId = "aud_login_fail_${System.currentTimeMillis()}",
                    actorId = user?.userId ?: "unknown",
                    actorRole = user?.role ?: "UNKNOWN",
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
                put("expiresAt", session.expiresAt)
                put("user", JSONObject().apply {
                    put("userId", user.userId)
                    put("displayName", user.displayName)
                    put("role", user.role)
                    put("instituteId", user.instituteId)
                    put("accountStatus", user.accountStatus)
                    put("createdAt", user.createdAt)
                })
            }
            call.respondText(resp.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }

        // 3. Authentication: Get Current Profile
        get("/api/auth/me") {
            val session = authenticate(call, sessionManager) ?: return@get
            val user = database.userDao().getUserById(session.userId)
            if (user == null) {
                call.respondText(errorJson("USER_NOT_FOUND", "User profile not found"), ContentType.Application.Json, HttpStatusCode.NotFound)
                return@get
            }

            val resp = JSONObject().apply {
                put("userId", user.userId)
                put("displayName", user.displayName)
                put("role", user.role)
                put("instituteId", user.instituteId)
                put("accountStatus", user.accountStatus)
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

            val authCheck = PermissionService.authorize(
                session = session,
                permission = Permission.USER_PROVISION,
                resourceInstituteId = session.instituteScope
            )
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

            val newUserId = req.optString("userId", "").trim()
            val displayName = req.optString("displayName", "").trim()
            val role = req.optString("role", "").trim()
            val instituteId = req.optString("instituteId", session.instituteScope).trim()
            val password = req.optString("password", "")

            if (newUserId.isEmpty() || displayName.isEmpty() || role.isEmpty() || password.length < 8) {
                call.respondText(errorJson("VALIDATION_ERROR", "User ID, name, valid role, and minimum 8-char password required"), ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            if (!PERMITTED_ROLES.contains(role)) {
                call.respondText(errorJson("INVALID_ROLE", "Role '$role' is not recognized"), ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            if (instituteId != session.instituteScope && session.role != "SYSTEM_ADMIN") {
                call.respondText(errorJson("SCOPE_VIOLATION", "Cannot provision users outside your assigned institute"), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@post
            }

            val existing = database.userDao().getUserById(newUserId)
            if (existing != null) {
                call.respondText(errorJson("DUPLICATE_USER", "User with ID '$newUserId' already exists"), ContentType.Application.Json, HttpStatusCode.Conflict)
                return@post
            }

            val (hash, salt) = PasswordHasher.hashPassword(password)
            val nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now())

            val newUser = UserEntity(
                userId = newUserId,
                instituteId = instituteId,
                role = role,
                displayName = displayName,
                accountStatus = "ACTIVE",
                createdAt = nowIso
            )

            auditEngine.executeAuditedTransaction(
                auditEventId = "aud_prov_${System.currentTimeMillis()}",
                actorId = session.userId,
                actorRole = session.role,
                actionType = "USER_PROVISIONED",
                aggregateType = "USER",
                aggregateId = newUserId,
                previousState = null,
                newState = "{\"role\":\"$role\",\"instituteId\":\"$instituteId\"}",
                reason = "User provisioned by administrator"
            ) {
                database.userDao().insertUser(newUser)
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

        // 6. Exam: Get Exam Details (Student-visible endpoint; NEVER reveals answer keys!)
        get("/api/exams/{id}") {
            val session = authenticate(call, sessionManager) ?: return@get
            val examId = call.parameters["id"] ?: ""

            val exam = database.examDao().getExamById(examId)
            if (exam == null) {
                call.respondText(errorJson("NOT_FOUND", "Exam not found"), ContentType.Application.Json, HttpStatusCode.NotFound)
                return@get
            }

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

        // =========================================================================
        // AUTHORING & COMPILER API ENDPOINTS (STEP 5)
        // =========================================================================

        // 7. Authoring: Import Document (PDF or TXT)
        post("/api/authoring/import") {
            val session = authenticate(call, sessionManager) ?: return@post
            val authCheck = PermissionService.authorize(
                session = session,
                permission = Permission.EXAM_CREATE,
                resourceInstituteId = session.instituteScope
            )
            if (authCheck is AuthorizationResult.Denied) {
                recordPermissionDenial(auditEngine, session, Permission.EXAM_CREATE, authCheck.reason)
                call.respondText(errorJson("FORBIDDEN", authCheck.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@post
            }

            val body = call.receiveText()
            val req = try { JSONObject(body) } catch (e: Exception) {
                call.respondText(errorJson("MALFORMED_JSON", "Invalid JSON payload"), ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            val filename = req.optString("filename", "").trim()
            val fileType = req.optString("fileType", "").trim().uppercase()
            val contentBase64 = req.optString("contentBase64", "").trim()
            val customTitle = req.optString("title", "").trim()
            val durationSeconds = req.optLong("durationSeconds", 10800L)

            if (filename.isEmpty()) {
                call.respondText(errorJson("INVALID_REQUEST", "Filename must be provided"), ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            if (fileType != "PDF" && fileType != "TXT") {
                call.respondText(errorJson("UNSUPPORTED_FILE_TYPE", "Only PDF and TXT files are supported"), ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            if (contentBase64.isEmpty()) {
                call.respondText(errorJson("EMPTY_FILE", "File content cannot be empty"), ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            val fileBytes = try {
                Base64.getDecoder().decode(contentBase64)
            } catch (e: Exception) {
                call.respondText(errorJson("INVALID_BASE64", "Invalid base64 file content"), ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            if (fileBytes.isEmpty()) {
                call.respondText(errorJson("EMPTY_FILE", "File content is empty (0 bytes)"), ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            if (fileBytes.size > 25 * 1024 * 1024) {
                call.respondText(errorJson("PAYLOAD_TOO_LARGE", "File exceeds maximum size of 25MB"), ContentType.Application.Json, HttpStatusCode.PayloadTooLarge)
                return@post
            }

            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(fileBytes)
            val sha256Hex = hashBytes.joinToString("") { "%02x".format(it) }

            val nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
            val examId = "exam_${System.currentTimeMillis()}"
            val importId = "imp_${System.currentTimeMillis()}"

            val extractionResult = DocumentExtractor.extractText(fileBytes, fileType)
            val parsedPackage = QuestionStructuralParser.parse(examId, importId, extractionResult, nowIso)

            val exam = ExamEntity(
                examId = examId,
                instituteId = session.instituteScope,
                title = if (customTitle.isNotEmpty()) customTitle else filename.substringBeforeLast("."),
                status = "DRAFT",
                durationSeconds = if (durationSeconds > 0) durationSeconds else 10800L,
                markingSchemeVersion = 1,
                createdAt = nowIso,
                updatedAt = nowIso
            )

            val paperImport = PaperImportEntity(
                importId = importId,
                originalFilename = filename,
                fileType = fileType,
                originalFileHash = sha256Hex,
                fileSizeBytes = fileBytes.size.toLong(),
                privateStoragePath = "imports/$importId.$fileType",
                importStatus = "EXTRACTED",
                extractedAt = nowIso,
                createdBy = session.userId,
                createdAt = nowIso,
                updatedAt = nowIso
            )

            examRepository.saveDraftPackage(exam, paperImport, parsedPackage, session.userId, session.role)

            val validationReport = PaperValidationEngine.validateDraft(
                exam = exam,
                questions = parsedPackage.questions,
                answerKeys = parsedPackage.answerKeys,
                markingScheme = parsedPackage.defaultMarkingScheme,
                validationIssues = parsedPackage.validationIssues
            )

            val resp = JSONObject().apply {
                put("examId", exam.examId)
                put("title", exam.title)
                put("status", exam.status)
                put("durationSeconds", exam.durationSeconds)
                put("questionsCount", parsedPackage.questions.size)
                put("sectionsCount", parsedPackage.sections.size)
                put("isReadyForPublication", validationReport.isReadyForPublication)
                put("blockingErrors", JSONArray(validationReport.blockingErrors))
                put("warnings", JSONArray(validationReport.warnings))
            }
            call.respondText(resp.toString(), ContentType.Application.Json, HttpStatusCode.Created)
        }

        // 8. Authoring: List Drafts
        get("/api/authoring/drafts") {
            val session = authenticate(call, sessionManager) ?: return@get
            val authCheck = PermissionService.authorize(
                session = session,
                permission = Permission.EXAM_READ,
                resourceInstituteId = session.instituteScope
            )
            if (authCheck is AuthorizationResult.Denied) {
                recordPermissionDenial(auditEngine, session, Permission.EXAM_READ, authCheck.reason)
                call.respondText(errorJson("FORBIDDEN", authCheck.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@get
            }

            val exams = database.examDao().getExamsByInstitute(session.instituteScope)
            val arr = JSONArray()
            for (e in exams) {
                arr.put(JSONObject().apply {
                    put("examId", e.examId)
                    put("title", e.title)
                    put("status", e.status)
                    put("durationSeconds", e.durationSeconds)
                    put("createdAt", e.createdAt)
                    put("updatedAt", e.updatedAt)
                })
            }
            call.respondText(arr.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }

        // 9. Authoring: Get Draft Details
        get("/api/authoring/drafts/{id}") {
            val session = authenticate(call, sessionManager) ?: return@get
            val examId = call.parameters["id"] ?: ""
            val exam = database.examDao().getExamById(examId)
            if (exam == null) {
                call.respondText(errorJson("NOT_FOUND", "Draft not found"), ContentType.Application.Json, HttpStatusCode.NotFound)
                return@get
            }

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

            val sections = database.paperSectionDao().getSectionsByExamId(examId)
            val questions = database.questionDao().getQuestionsByExamId(examId)
            val issues = database.validationIssueDao().getIssuesByExamId(examId)
            val markingScheme = database.markingSchemeDao().getMarkingSchemeByExamId(examId)

            val questionsArr = JSONArray()
            for (q in questions) {
                questionsArr.put(JSONObject().apply {
                    put("questionId", q.questionId)
                    put("questionNumber", q.questionNumber)
                    put("sectionId", q.sectionId ?: JSONObject.NULL)
                    put("questionType", q.questionType)
                    put("questionText", q.questionText)
                    put("answerOptions", JSONArray(q.answerOptionsJson))
                    put("reviewStatus", q.reviewStatus)
                    put("extractionConfidence", q.extractionConfidence)
                    put("sourceStartLine", q.sourceStartLine ?: JSONObject.NULL)
                    put("sourceSnippet", q.sourceSnippet ?: JSONObject.NULL)
                })
            }

            val sectionsArr = JSONArray()
            for (s in sections) {
                sectionsArr.put(JSONObject().apply {
                    put("sectionId", s.sectionId)
                    put("title", s.title)
                    put("sectionOrder", s.sectionOrder)
                })
            }

            val issuesArr = JSONArray()
            for (iss in issues) {
                issuesArr.put(JSONObject().apply {
                    put("issueId", iss.issueId)
                    put("questionId", iss.questionId ?: JSONObject.NULL)
                    put("severity", iss.severity)
                    put("issueCode", iss.issueCode)
                    put("description", iss.description)
                    put("isResolved", iss.isResolved)
                })
            }

            val resp = JSONObject().apply {
                put("examId", exam.examId)
                put("title", exam.title)
                put("status", exam.status)
                put("durationSeconds", exam.durationSeconds)
                put("instituteId", exam.instituteId)
                put("sections", sectionsArr)
                put("questions", questionsArr)
                put("validationIssues", issuesArr)
                if (markingScheme != null) {
                    put("markingScheme", JSONObject().apply {
                        put("schemeId", markingScheme.schemeId)
                        put("correctScaledMark", markingScheme.correctScaledMark)
                        put("incorrectScaledMark", markingScheme.incorrectScaledMark)
                        put("unansweredScaledMark", markingScheme.unansweredScaledMark)
                        put("allowNegativeMarking", markingScheme.allowNegativeMarking)
                    })
                }
            }
            call.respondText(resp.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }

        // 10. Authoring: Update Draft
        put("/api/authoring/drafts/{id}") {
            val session = authenticate(call, sessionManager) ?: return@put
            val examId = call.parameters["id"] ?: ""
            val exam = database.examDao().getExamById(examId)
            if (exam == null) {
                call.respondText(errorJson("NOT_FOUND", "Draft not found"), ContentType.Application.Json, HttpStatusCode.NotFound)
                return@put
            }

            val authCheck = PermissionService.authorize(
                session = session,
                permission = Permission.EXAM_UPDATE,
                resourceInstituteId = exam.instituteId,
                resourceExamId = exam.examId
            )
            if (authCheck is AuthorizationResult.Denied) {
                recordPermissionDenial(auditEngine, session, Permission.EXAM_UPDATE, authCheck.reason)
                call.respondText(errorJson("FORBIDDEN", authCheck.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@put
            }

            val body = call.receiveText()
            val req = try { JSONObject(body) } catch (e: Exception) {
                call.respondText(errorJson("MALFORMED_JSON", "Invalid JSON payload"), ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@put
            }

            val updatedTitle = req.optString("title", exam.title).trim()
            val updatedDuration = req.optLong("durationSeconds", exam.durationSeconds)
            val nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now())

            val updatedExam = exam.copy(
                title = if (updatedTitle.isNotEmpty()) updatedTitle else exam.title,
                durationSeconds = if (updatedDuration > 0) updatedDuration else exam.durationSeconds,
                updatedAt = nowIso
            )
            database.examDao().updateExam(updatedExam)

            if (req.has("questions")) {
                val qArray = req.getJSONArray("questions")
                val updatedQuestions = mutableListOf<QuestionEntity>()
                for (i in 0 until qArray.length()) {
                    val qObj = qArray.getJSONObject(i)
                    val qId = qObj.getString("questionId")
                    val existing = database.questionDao().getQuestionById(qId)
                    val updatedQ = QuestionEntity(
                        questionId = qId,
                        examId = examId,
                        sectionId = if (qObj.isNull("sectionId")) null else qObj.optString("sectionId"),
                        questionNumber = qObj.getInt("questionNumber"),
                        questionType = qObj.getString("questionType"),
                        questionText = qObj.getString("questionText"),
                        answerOptionsJson = qObj.getJSONArray("answerOptions").toString(),
                        sourcePage = existing?.sourcePage,
                        sourceStartLine = existing?.sourceStartLine,
                        sourceEndLine = existing?.sourceEndLine,
                        sourceSnippet = existing?.sourceSnippet,
                        extractionConfidence = existing?.extractionConfidence ?: "MANUAL",
                        reviewStatus = qObj.optString("reviewStatus", "VERIFIED"),
                        createdAt = existing?.createdAt ?: nowIso,
                        updatedAt = nowIso
                    )
                    updatedQuestions.add(updatedQ)
                }
                examRepository.updateDraftQuestions(examId, updatedQuestions, session.userId, session.role)
            }

            call.respondText(JSONObject().put("success", true).toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }

        // 11. Authoring: Get Confidential Answer Keys & Solutions
        get("/api/authoring/drafts/{id}/answer-keys") {
            val session = authenticate(call, sessionManager) ?: return@get
            val examId = call.parameters["id"] ?: ""
            val exam = database.examDao().getExamById(examId)
            if (exam == null) {
                call.respondText(errorJson("NOT_FOUND", "Draft not found"), ContentType.Application.Json, HttpStatusCode.NotFound)
                return@get
            }

            val authCheck = PermissionService.authorize(
                session = session,
                permission = Permission.EXAM_UPDATE,
                resourceInstituteId = exam.instituteId,
                resourceExamId = exam.examId
            )
            if (authCheck is AuthorizationResult.Denied) {
                recordPermissionDenial(auditEngine, session, Permission.EXAM_UPDATE, authCheck.reason)
                call.respondText(errorJson("FORBIDDEN", authCheck.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@get
            }

            val keys = database.answerKeyDao().getAnswerKeysByExamId(examId)
            val solutions = database.workedSolutionDao().getSolutionsByExamId(examId)

            val keysArr = JSONArray()
            for (k in keys) {
                val sol = solutions.find { it.questionId == k.questionId }
                keysArr.put(JSONObject().apply {
                    put("questionId", k.questionId)
                    put("expectedAnswer", k.expectedAnswerJson)
                    put("numericalTolerance", k.numericalTolerance ?: JSONObject.NULL)
                    put("answerKeySource", k.answerKeySource)
                    put("verificationStatus", k.verificationStatus)
                    put("solutionText", sol?.solutionText ?: "")
                })
            }
            call.respondText(keysArr.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }

        // 12. Authoring: Update Confidential Answer Keys & Solutions
        put("/api/authoring/drafts/{id}/answer-keys") {
            val session = authenticate(call, sessionManager) ?: return@put
            val examId = call.parameters["id"] ?: ""
            val exam = database.examDao().getExamById(examId)
            if (exam == null) {
                call.respondText(errorJson("NOT_FOUND", "Draft not found"), ContentType.Application.Json, HttpStatusCode.NotFound)
                return@put
            }

            val authCheck = PermissionService.authorize(
                session = session,
                permission = Permission.EXAM_UPDATE,
                resourceInstituteId = exam.instituteId,
                resourceExamId = exam.examId
            )
            if (authCheck is AuthorizationResult.Denied) {
                recordPermissionDenial(auditEngine, session, Permission.EXAM_UPDATE, authCheck.reason)
                call.respondText(errorJson("FORBIDDEN", authCheck.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@put
            }

            val body = call.receiveText()
            val req = try { JSONObject(body) } catch (e: Exception) {
                call.respondText(errorJson("MALFORMED_JSON", "Invalid JSON payload"), ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@put
            }

            val nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
            val keysArr = req.getJSONArray("answerKeys")
            val updatedKeys = mutableListOf<AnswerKeyEntity>()
            val updatedSolutions = mutableListOf<WorkedSolutionEntity>()

            for (i in 0 until keysArr.length()) {
                val kObj = keysArr.getJSONObject(i)
                val qId = kObj.getString("questionId")
                val ansStr = kObj.getString("expectedAnswer")
                val isVerified = kObj.optBoolean("isVerified", true)
                val solText = kObj.optString("solutionText", "")

                val formattedAns = if (ansStr.startsWith("[") || ansStr.startsWith("\"")) ansStr else JSONObject.quote(ansStr)

                val key = AnswerKeyEntity(
                    questionId = qId,
                    examId = examId,
                    expectedAnswerJson = formattedAns,
                    numericalTolerance = if (kObj.has("numericalTolerance") && !kObj.isNull("numericalTolerance")) kObj.optDouble("numericalTolerance") else null,
                    answerKeySource = "MANUAL",
                    verificationStatus = if (isVerified) "VERIFIED" else "UNVERIFIED",
                    reviewerId = session.userId,
                    verifiedAt = if (isVerified) nowIso else null,
                    updatedAt = nowIso
                )
                updatedKeys.add(key)

                if (solText.isNotBlank()) {
                    updatedSolutions.add(
                        WorkedSolutionEntity(
                            solutionId = "sol_$qId",
                            questionId = qId,
                            examId = examId,
                            solutionText = solText,
                            sourceReference = "Administrator Solution",
                            verificationStatus = if (isVerified) "VERIFIED" else "UNVERIFIED",
                            reviewerId = session.userId,
                            verifiedAt = if (isVerified) nowIso else null,
                            updatedAt = nowIso
                        )
                    )
                }
            }

            examRepository.updateAnswerKeysAndSolutions(examId, updatedKeys, updatedSolutions, session.userId, session.role)
            call.respondText(JSONObject().put("success", true).toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }

        // 13. Authoring: Run Publication Validation
        post("/api/authoring/drafts/{id}/validate") {
            val session = authenticate(call, sessionManager) ?: return@post
            val examId = call.parameters["id"] ?: ""
            val exam = database.examDao().getExamById(examId)
            if (exam == null) {
                call.respondText(errorJson("NOT_FOUND", "Draft not found"), ContentType.Application.Json, HttpStatusCode.NotFound)
                return@post
            }

            val authCheck = PermissionService.authorize(
                session = session,
                permission = Permission.EXAM_READ,
                resourceInstituteId = exam.instituteId,
                resourceExamId = exam.examId
            )
            if (authCheck is AuthorizationResult.Denied) {
                recordPermissionDenial(auditEngine, session, Permission.EXAM_READ, authCheck.reason)
                call.respondText(errorJson("FORBIDDEN", authCheck.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@post
            }

            val questions = database.questionDao().getQuestionsByExamId(examId)
            val answerKeys = database.answerKeyDao().getAnswerKeysByExamId(examId)
            val issues = database.validationIssueDao().getIssuesByExamId(examId)
            val markingScheme = database.markingSchemeDao().getMarkingSchemeByExamId(examId)

            val report = PaperValidationEngine.validateDraft(exam, questions, answerKeys, markingScheme, issues)

            val resp = JSONObject().apply {
                put("isReadyForPublication", report.isReadyForPublication)
                put("blockingErrors", JSONArray(report.blockingErrors))
                put("warnings", JSONArray(report.warnings))
                put("unverifiedAnswerKeyCount", report.unverifiedAnswerKeyCount)
                put("totalQuestionCount", report.totalQuestionCount)
            }
            call.respondText(resp.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }

        // 14. Authoring: Publish Examination (PUBLICATION GATE)
        post("/api/authoring/drafts/{id}/publish") {
            val session = authenticate(call, sessionManager) ?: return@post
            val examId = call.parameters["id"] ?: ""
            val exam = database.examDao().getExamById(examId)
            if (exam == null) {
                call.respondText(errorJson("NOT_FOUND", "Draft not found"), ContentType.Application.Json, HttpStatusCode.NotFound)
                return@post
            }

            // Fail closed: PUBLICATION GATE requires EXAM_PUBLISH permission!
            val authCheck = PermissionService.authorize(
                session = session,
                permission = Permission.EXAM_PUBLISH,
                resourceInstituteId = exam.instituteId,
                resourceExamId = exam.examId
            )
            if (authCheck is AuthorizationResult.Denied) {
                recordPermissionDenial(auditEngine, session, Permission.EXAM_PUBLISH, authCheck.reason)
                call.respondText(errorJson("FORBIDDEN", authCheck.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@post
            }

            if (exam.status != "DRAFT") {
                call.respondText(errorJson("INVALID_STATE", "Cannot publish exam in status '${exam.status}'"), ContentType.Application.Json, HttpStatusCode.Conflict)
                return@post
            }

            val questions = database.questionDao().getQuestionsByExamId(examId)
            val answerKeys = database.answerKeyDao().getAnswerKeysByExamId(examId)
            val issues = database.validationIssueDao().getIssuesByExamId(examId)
            val markingScheme = database.markingSchemeDao().getMarkingSchemeByExamId(examId)

            val report = PaperValidationEngine.validateDraft(exam, questions, answerKeys, markingScheme, issues)
            if (!report.isReadyForPublication) {
                val errorResp = JSONObject().apply {
                    put("error", "PUBLICATION_VALIDATION_FAILED")
                    put("message", "Cannot publish draft with unresolved blocking errors.")
                    put("blockingErrors", JSONArray(report.blockingErrors))
                }
                call.respondText(errorResp.toString(), ContentType.Application.Json, HttpStatusCode.UnprocessableEntity)
                return@post
            }

            val publishedExam = examRepository.publishExam(exam, session.userId, session.role)

            val resp = JSONObject().apply {
                put("success", true)
                put("examId", publishedExam.examId)
                put("status", publishedExam.status)
                put("publishedAt", publishedExam.updatedAt)
            }
            call.respondText(resp.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }

        // 15. Answers: Save Candidate Answer
        post("/api/attempts/{attemptId}/answers") {
            val session = authenticate(call, sessionManager) ?: return@post
            val attemptId = call.parameters["attemptId"] ?: ""

            val attempt = database.examAttemptDao().getAttemptById(attemptId)
            if (attempt == null) {
                call.respondText(errorJson("NOT_FOUND", "Attempt not found"), ContentType.Application.Json, HttpStatusCode.NotFound)
                return@post
            }

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

            val questionId = req.optString("questionId", "").trim()
            val value = if (req.has("responsePayload")) req.getString("responsePayload") else req.optString("value", "").trim()
            val clientSeq = if (req.has("revisionSequence")) req.getInt("revisionSequence") else req.optInt("clientSequenceNumber", 1)
            val clientRecordedAt = if (req.has("clientRecordedAt")) req.getString("clientRecordedAt").trim() else DateTimeFormatter.ISO_INSTANT.format(Instant.now())

            if (questionId.isEmpty() || value.isEmpty()) {
                call.respondText(errorJson("VALIDATION_ERROR", "Missing questionId or response payload"), ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            val existing = database.candidateResponseDao().getResponse(attemptId, questionId)
            val responseId = existing?.responseId ?: "resp_${System.currentTimeMillis()}_${questionId}"
            val serverReceivedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
            val revisionSequence = if (req.has("revisionSequence")) req.getInt("revisionSequence") else ((existing?.revisionSequence ?: 0) + 1)

            val candidateResponse = CandidateResponseEntity(
                responseId = responseId,
                attemptId = attemptId,
                questionId = questionId,
                responsePayload = value,
                revisionSequence = revisionSequence,
                savedAt = serverReceivedAt
            )

            attemptRepository.saveAnswerTransactionally(
                candidateResponse,
                "aud_resp_${System.currentTimeMillis()}_${questionId.take(8)}",
                session.userId
            )

            val resp = JSONObject().apply {
                put("responseId", responseId)
                put("revisionSequence", revisionSequence)
                put("status", "SAVED")
                put("serverReceivedAt", serverReceivedAt)
            }
            call.respondText(resp.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }



        // 17. Exam Authoring: Create Draft Exam
        post("/api/exams/drafts") {
            val session = authenticate(call, sessionManager) ?: return@post
            val authCheck = PermissionService.authorize(session, Permission.EXAM_CREATE)
            if (authCheck is AuthorizationResult.Denied) {
                recordPermissionDenial(auditEngine, session, Permission.EXAM_CREATE, authCheck.reason)
                call.respondText(errorJson("FORBIDDEN", authCheck.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@post
            }

            val body = call.receiveText()
            val req = try { JSONObject(body) } catch (e: Exception) {
                call.respondText(errorJson("MALFORMED_JSON", "Invalid JSON payload"), ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            val examId = req.optString("examId", "exam_${System.currentTimeMillis()}").trim()
            val title = req.optString("title", "").trim()
            val durationSeconds = req.optLong("durationSeconds", 3600L)
            val markingSchemeVersion = req.optInt("markingSchemeVersion", 1)

            if (title.isEmpty()) {
                call.respondText(errorJson("VALIDATION_ERROR", "title is required"), ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            val nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
            val draft = ExamEntity(
                examId = examId,
                instituteId = session.instituteScope,
                title = title,
                status = "DRAFT",
                durationSeconds = durationSeconds,
                markingSchemeVersion = markingSchemeVersion,
                createdAt = nowIso,
                updatedAt = nowIso
            )

            examRepository.createExam(
                exam = draft,
                auditEventId = "aud_exam_draft_${System.currentTimeMillis()}",
                actorId = session.userId,
                actorRole = session.role
            )

            val resp = JSONObject().apply {
                put("examId", draft.examId)
                put("instituteId", draft.instituteId)
                put("title", draft.title)
                put("status", draft.status)
                put("durationSeconds", draft.durationSeconds)
                put("markingSchemeVersion", draft.markingSchemeVersion)
                put("createdAt", draft.createdAt)
            }
            call.respondText(resp.toString(), ContentType.Application.Json, HttpStatusCode.Created)
        }

        // 18. Exam Authoring: Get Draft Details
        get("/api/exams/drafts/{examId}") {
            val session = authenticate(call, sessionManager) ?: return@get
            val examId = call.parameters["examId"] ?: ""
            val exam = examRepository.getExam(examId)
            if (exam == null) {
                call.respondText(errorJson("NOT_FOUND", "Exam not found"), ContentType.Application.Json, HttpStatusCode.NotFound)
                return@get
            }

            // Students cannot view unreleased drafts
            if (session.role == "STUDENT" && exam.status != "PUBLISHED") {
                call.respondText(errorJson("FORBIDDEN", "Draft exams are inaccessible to candidates"), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@get
            }

            val questions = database.questionDao().getQuestionsByExamId(examId)
            val sections = database.paperSectionDao().getSectionsByExamId(examId)
            val markingScheme = database.markingSchemeDao().getMarkingSchemeByExamId(examId)

            val resp = JSONObject().apply {
                put("examId", exam.examId)
                put("instituteId", exam.instituteId)
                put("title", exam.title)
                put("status", exam.status)
                put("durationSeconds", exam.durationSeconds)
                put("markingSchemeVersion", exam.markingSchemeVersion)
                put("questions", JSONArray().apply {
                    for (q in questions) {
                        put(JSONObject().apply {
                            put("questionId", q.questionId)
                            put("questionNumber", q.questionNumber)
                            put("questionType", q.questionType)
                            put("questionText", q.questionText)
                            put("answerOptions", JSONArray(q.answerOptionsJson))
                            put("sourcePage", q.sourcePage ?: JSONObject.NULL)
                            put("extractionConfidence", q.extractionConfidence)
                        })
                    }
                })
                put("sections", JSONArray().apply {
                    for (s in sections) {
                        put(JSONObject().apply {
                            put("sectionId", s.sectionId)
                            put("title", s.title)
                            put("sectionOrder", s.sectionOrder)
                        })
                    }
                })
                put("markingScheme", if (markingScheme != null) JSONObject().apply {
                    put("schemeId", markingScheme.schemeId)
                    put("version", markingScheme.version)
                    put("correctScaledMark", markingScheme.correctScaledMark)
                    put("incorrectScaledMark", markingScheme.incorrectScaledMark)
                    put("unansweredScaledMark", markingScheme.unansweredScaledMark)
                    put("allowNegativeMarking", markingScheme.allowNegativeMarking)
                } else JSONObject.NULL)
            }
            call.respondText(resp.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }

        // 19. Exam Authoring: Update Draft Questions
        put("/api/exams/drafts/{examId}/questions") {
            val session = authenticate(call, sessionManager) ?: return@put
            val examId = call.parameters["examId"] ?: ""
            val authCheck = PermissionService.authorize(session, Permission.EXAM_UPDATE)
            if (authCheck is AuthorizationResult.Denied) {
                recordPermissionDenial(auditEngine, session, Permission.EXAM_UPDATE, authCheck.reason)
                call.respondText(errorJson("FORBIDDEN", authCheck.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@put
            }

            val exam = examRepository.getExam(examId)
            if (exam == null) {
                call.respondText(errorJson("NOT_FOUND", "Exam not found"), ContentType.Application.Json, HttpStatusCode.NotFound)
                return@put
            }

            val body = call.receiveText()
            val req = try { JSONObject(body) } catch (e: Exception) {
                call.respondText(errorJson("MALFORMED_JSON", "Invalid JSON payload"), ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@put
            }

            val nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
            val qArray = req.optJSONArray("questions") ?: JSONArray()
            val questionEntities = mutableListOf<QuestionEntity>()

            for (i in 0 until qArray.length()) {
                val qObj = qArray.getJSONObject(i)
                val qId = qObj.optString("questionId", "q_${examId}_${i + 1}")
                val qNum = qObj.optInt("questionNumber", i + 1)
                val qType = qObj.optString("questionType", "UNKNOWN")
                val qText = qObj.optString("questionText", "")
                val optsArray = qObj.optJSONArray("answerOptions") ?: JSONArray()
                val conf = qObj.optString("extractionConfidence", "MEDIUM")

                questionEntities.add(
                    QuestionEntity(
                        questionId = qId,
                        examId = examId,
                        sectionId = qObj.optString("sectionId").takeIf { it.isNotEmpty() },
                        questionNumber = qNum,
                        questionType = qType,
                        questionText = qText,
                        answerOptionsJson = optsArray.toString(),
                        sourcePage = if (qObj.has("sourcePage")) qObj.getInt("sourcePage") else null,
                        sourceStartLine = null,
                        sourceEndLine = null,
                        sourceSnippet = null,
                        extractionConfidence = conf,
                        reviewStatus = "ACCEPTED",
                        createdAt = nowIso,
                        updatedAt = nowIso
                    )
                )
            }

            examRepository.updateDraftQuestions(examId, questionEntities, session.userId, session.role)

            val resp = JSONObject().apply {
                put("status", "UPDATED")
                put("examId", examId)
                put("questionCount", questionEntities.size)
            }
            call.respondText(resp.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }

        // 20. Exam Authoring: Get Answer Keys (Protected)
        get("/api/exams/drafts/{examId}/answer-keys") {
            val session = authenticate(call, sessionManager) ?: return@get
            val examId = call.parameters["examId"] ?: ""

            // Confidential Answer Keys are STRICTLY FORBIDDEN to Candidates
            if (session.role == "STUDENT") {
                recordPermissionDenial(auditEngine, session, Permission.EXAM_UPDATE, "Confidential answer keys are restricted from candidate view")
                call.respondText(errorJson("FORBIDDEN", "Confidential answer keys are restricted from candidate view"), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@get
            }

            val authCheck = PermissionService.authorize(session, Permission.EXAM_UPDATE)
            if (authCheck is AuthorizationResult.Denied) {
                recordPermissionDenial(auditEngine, session, Permission.EXAM_UPDATE, authCheck.reason)
                call.respondText(errorJson("FORBIDDEN", authCheck.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@get
            }

            val answerKeys = database.answerKeyDao().getAnswerKeysByExamId(examId)
            val solutions = database.workedSolutionDao().getSolutionsByExamId(examId)

            val resp = JSONObject().apply {
                put("examId", examId)
                put("answerKeys", JSONArray().apply {
                    for (ak in answerKeys) {
                        put(JSONObject().apply {
                            put("questionId", ak.questionId)
                            put("expectedAnswer", JSONArray(ak.expectedAnswerJson))
                            put("numericalTolerance", ak.numericalTolerance ?: JSONObject.NULL)
                            put("answerKeySource", ak.answerKeySource)
                            put("verificationStatus", ak.verificationStatus)
                        })
                    }
                })
                put("solutions", JSONArray().apply {
                    for (sol in solutions) {
                        put(JSONObject().apply {
                            put("solutionId", sol.solutionId)
                            put("questionId", sol.questionId)
                            put("solutionText", sol.solutionText)
                            put("verificationStatus", sol.verificationStatus)
                        })
                    }
                })
            }
            call.respondText(resp.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }

        // 21. Exam Authoring: Update Answer Keys and Solutions
        put("/api/exams/drafts/{examId}/answer-keys") {
            val session = authenticate(call, sessionManager) ?: return@put
            val examId = call.parameters["examId"] ?: ""

            if (session.role == "STUDENT") {
                recordPermissionDenial(auditEngine, session, Permission.EXAM_UPDATE, "Candidates cannot modify answer keys")
                call.respondText(errorJson("FORBIDDEN", "Candidates cannot modify answer keys"), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@put
            }

            val authCheck = PermissionService.authorize(session, Permission.EXAM_UPDATE)
            if (authCheck is AuthorizationResult.Denied) {
                recordPermissionDenial(auditEngine, session, Permission.EXAM_UPDATE, authCheck.reason)
                call.respondText(errorJson("FORBIDDEN", authCheck.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@put
            }

            val body = call.receiveText()
            val req = try { JSONObject(body) } catch (e: Exception) {
                call.respondText(errorJson("MALFORMED_JSON", "Invalid JSON payload"), ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@put
            }

            val nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
            val akArray = req.optJSONArray("answerKeys") ?: JSONArray()
            val solArray = req.optJSONArray("solutions") ?: JSONArray()

            val answerKeyEntities = mutableListOf<AnswerKeyEntity>()
            for (i in 0 until akArray.length()) {
                val akObj = akArray.getJSONObject(i)
                val qId = akObj.getString("questionId")
                val expectedArr = akObj.optJSONArray("expectedAnswer") ?: JSONArray()
                val status = akObj.optString("verificationStatus", "VERIFIED")
                val src = akObj.optString("answerKeySource", "ADMIN_VERIFIED")
                val tol = if (akObj.has("numericalTolerance") && !akObj.isNull("numericalTolerance")) akObj.getDouble("numericalTolerance") else null

                answerKeyEntities.add(
                    AnswerKeyEntity(
                        questionId = qId,
                        examId = examId,
                        expectedAnswerJson = expectedArr.toString(),
                        numericalTolerance = tol,
                        answerKeySource = src,
                        verificationStatus = status,
                        reviewerId = session.userId,
                        verifiedAt = nowIso,
                        updatedAt = nowIso
                    )
                )
            }

            val solutionEntities = mutableListOf<WorkedSolutionEntity>()
            for (i in 0 until solArray.length()) {
                val solObj = solArray.getJSONObject(i)
                val solId = solObj.optString("solutionId", "sol_${examId}_${i + 1}")
                val qId = solObj.getString("questionId")
                val text = solObj.optString("solutionText", "")
                val status = solObj.optString("verificationStatus", "VERIFIED")

                solutionEntities.add(
                    WorkedSolutionEntity(
                        solutionId = solId,
                        questionId = qId,
                        examId = examId,
                        solutionText = text,
                        sourceReference = "Admin verification",
                        verificationStatus = status,
                        reviewerId = session.userId,
                        verifiedAt = nowIso,
                        updatedAt = nowIso
                    )
                )
            }

            examRepository.updateAnswerKeysAndSolutions(examId, answerKeyEntities, solutionEntities, session.userId, session.role)

            val resp = JSONObject().apply {
                put("status", "SAVED")
                put("examId", examId)
                put("answerKeyCount", answerKeyEntities.size)
                put("solutionCount", solutionEntities.size)
            }
            call.respondText(resp.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }

        // 22. Exam Authoring: Update Marking Scheme
        put("/api/exams/drafts/{examId}/marking-scheme") {
            val session = authenticate(call, sessionManager) ?: return@put
            val examId = call.parameters["examId"] ?: ""

            val authCheck = PermissionService.authorize(session, Permission.EXAM_UPDATE)
            if (authCheck is AuthorizationResult.Denied) {
                recordPermissionDenial(auditEngine, session, Permission.EXAM_UPDATE, authCheck.reason)
                call.respondText(errorJson("FORBIDDEN", authCheck.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@put
            }

            val body = call.receiveText()
            val req = try { JSONObject(body) } catch (e: Exception) {
                call.respondText(errorJson("MALFORMED_JSON", "Invalid JSON payload"), ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@put
            }

            val nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
            val schemeId = req.optString("schemeId", "ms_${examId}")
            val version = req.optInt("version", 1)
            val correct = req.optLong("correctScaledMark", 400L)
            val incorrect = req.optLong("incorrectScaledMark", -100L)
            val unanswered = req.optLong("unansweredScaledMark", 0L)
            val allowNegative = req.optBoolean("allowNegativeMarking", true)

            val markingScheme = MarkingSchemeEntity(
                schemeId = schemeId,
                examId = examId,
                version = version,
                correctScaledMark = correct,
                incorrectScaledMark = incorrect,
                unansweredScaledMark = unanswered,
                allowNegativeMarking = allowNegative,
                sectionDefaultsJson = req.optJSONObject("sectionDefaults")?.toString() ?: "{}",
                questionOverridesJson = req.optJSONObject("questionOverrides")?.toString() ?: "{}",
                updatedAt = nowIso
            )

            database.markingSchemeDao().insertMarkingScheme(markingScheme)

            auditEngine.recordAuditEvent(
                auditEventId = "aud_scheme_${System.currentTimeMillis()}",
                actorId = session.userId,
                actorRole = session.role,
                actionType = "MARKING_SCHEME_SAVED",
                aggregateType = "EXAM",
                aggregateId = examId,
                previousState = null,
                newState = "{\"correct\":$correct,\"incorrect\":$incorrect,\"unanswered\":$unanswered}",
                reason = "Marking scheme updated by administrator",
                operationResult = "SUCCESS"
            )

            val resp = JSONObject().apply {
                put("status", "SAVED")
                put("examId", examId)
                put("schemeId", schemeId)
            }
            call.respondText(resp.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }

        // 23. Exam Authoring: Run Validation
        get("/api/exams/drafts/{examId}/validate") {
            val session = authenticate(call, sessionManager) ?: return@get
            val examId = call.parameters["examId"] ?: ""

            val exam = examRepository.getExam(examId)
            if (exam == null) {
                call.respondText(errorJson("NOT_FOUND", "Exam not found"), ContentType.Application.Json, HttpStatusCode.NotFound)
                return@get
            }

            val questions = database.questionDao().getQuestionsByExamId(examId)
            val answerKeys = database.answerKeyDao().getAnswerKeysByExamId(examId)
            val markingScheme = database.markingSchemeDao().getMarkingSchemeByExamId(examId)
            val validationIssues = database.validationIssueDao().getIssuesByExamId(examId)

            val report = PaperValidationEngine.validateDraft(
                exam = exam,
                questions = questions,
                answerKeys = answerKeys,
                markingScheme = markingScheme,
                validationIssues = validationIssues
            )

            val resp = JSONObject().apply {
                put("examId", examId)
                put("isReadyForPublication", report.isReadyForPublication)
                put("blockingErrors", JSONArray(report.blockingErrors))
                put("warnings", JSONArray(report.warnings))
                put("unverifiedAnswerKeyCount", report.unverifiedAnswerKeyCount)
                put("totalQuestionCount", report.totalQuestionCount)
            }
            call.respondText(resp.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }

        // 24. Exam Authoring: Publication Gate
        post("/api/exams/drafts/{examId}/publish") {
            val session = authenticate(call, sessionManager) ?: return@post
            val examId = call.parameters["examId"] ?: ""

            val authCheck = PermissionService.authorize(session, Permission.EXAM_PUBLISH)
            if (authCheck is AuthorizationResult.Denied) {
                recordPermissionDenial(auditEngine, session, Permission.EXAM_PUBLISH, authCheck.reason)
                call.respondText(errorJson("FORBIDDEN", authCheck.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@post
            }

            val exam = examRepository.getExam(examId)
            if (exam == null) {
                call.respondText(errorJson("NOT_FOUND", "Exam not found"), ContentType.Application.Json, HttpStatusCode.NotFound)
                return@post
            }

            try {
                val publishedExam = examRepository.publishExam(
                    exam = exam,
                    actorId = session.userId,
                    actorRole = session.role
                )

                val resp = JSONObject().apply {
                    put("status", publishedExam.status)
                    put("examId", publishedExam.examId)
                    put("publishedAt", publishedExam.updatedAt)
                }
                call.respondText(resp.toString(), ContentType.Application.Json, HttpStatusCode.OK)
            } catch (e: IllegalStateException) {
                call.respondText(
                    errorJson("PUBLICATION_REJECTED", e.message ?: "Validation rules not met"),
                    ContentType.Application.Json,
                    HttpStatusCode.UnprocessableEntity
                )
            }
        }

        // =========================================================================
        // SCORING & RESULTS API ENDPOINTS (STEP 6)
        // =========================================================================

        // 25. Scoring: Grade Attempt
        post("/api/attempts/{attemptId}/grade") {
            val session = authenticate(call, sessionManager) ?: return@post
            val attemptId = call.parameters["attemptId"] ?: ""

            val authCheck = PermissionService.authorize(session, Permission.RESULT_GRADE)
            if (authCheck is AuthorizationResult.Denied) {
                recordPermissionDenial(auditEngine, session, Permission.RESULT_GRADE, authCheck.reason)
                call.respondText(errorJson("FORBIDDEN", authCheck.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@post
            }

            try {
                val result = attemptRepository.gradeAttempt(attemptId, session.userId, session.role)
                val resp = JSONObject().apply {
                    put("resultId", result.resultId)
                    put("attemptId", result.attemptId)
                    put("examId", result.examId)
                    put("studentId", result.studentId)
                    put("version", result.version)
                    put("resultStatus", result.resultStatus)
                    put("totalScaledScore", result.totalScaledScore)
                    put("maxPossibleScaledScore", result.maxPossibleScaledScore)
                    put("correctCount", result.correctCount)
                    put("incorrectCount", result.incorrectCount)
                    put("unansweredCount", result.unansweredCount)
                    put("partiallyCorrectCount", result.partiallyCorrectCount)
                    put("pendingManualCount", result.pendingManualCount)
                    put("markingSchemeVersion", result.markingSchemeVersion)
                    put("scoringEngineVersion", result.scoringEngineVersion)
                    put("sectionTotals", JSONArray(result.sectionTotalsJson))
                    put("resultHash", result.resultHash)
                    put("scoredAt", result.scoredAt)
                    put("publishedAt", result.publishedAt ?: JSONObject.NULL)
                }
                call.respondText(resp.toString(), ContentType.Application.Json, HttpStatusCode.OK)
            } catch (e: IllegalArgumentException) {
                call.respondText(errorJson("NOT_FOUND", e.message ?: "Attempt not found"), ContentType.Application.Json, HttpStatusCode.NotFound)
            } catch (e: Exception) {
                call.respondText(errorJson("GRADING_FAILED", e.message ?: "Failed to grade attempt"), ContentType.Application.Json, HttpStatusCode.InternalServerError)
            }
        }

        // 26. Scoring: Get Result
        get("/api/attempts/{attemptId}/result") {
            val session = authenticate(call, sessionManager) ?: return@get
            val attemptId = call.parameters["attemptId"] ?: ""

            val attempt = attemptRepository.getAttempt(attemptId)
            if (attempt == null) {
                call.respondText(errorJson("NOT_FOUND", "Attempt not found"), ContentType.Application.Json, HttpStatusCode.NotFound)
                return@get
            }

            val result = attemptRepository.getGradingResult(attemptId)
            if (result == null) {
                call.respondText(errorJson("NOT_FOUND", "Result not yet generated for attempt"), ContentType.Application.Json, HttpStatusCode.NotFound)
                return@get
            }

            if (session.role == "STUDENT") {
                val authCheck = PermissionService.authorize(
                    session = session,
                    permission = Permission.RESULT_READ_OWN,
                    resourceStudentId = attempt.studentId
                )
                if (authCheck is AuthorizationResult.Denied) {
                    recordPermissionDenial(auditEngine, session, Permission.RESULT_READ_OWN, authCheck.reason)
                    call.respondText(errorJson("FORBIDDEN", authCheck.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                    return@get
                }

                // If held under review, student cannot view result
                if (result.resultStatus == "HELD_PENDING_REVIEW" || attempt.resultStatus == "HELD_PENDING_REVIEW") {
                    call.respondText(errorJson("RESULT_WITHHELD", "Result is held under administrative review."), ContentType.Application.Json, HttpStatusCode.Forbidden)
                    return@get
                }

                // If not yet published, student cannot view result
                if (result.resultStatus != "PUBLISHED") {
                    call.respondText(errorJson("RESULT_NOT_RELEASED", "Result has not been released yet."), ContentType.Application.Json, HttpStatusCode.Forbidden)
                    return@get
                }
            } else {
                val authCheck = PermissionService.authorize(
                    session = session,
                    permission = Permission.RESULT_READ_ASSIGNED,
                    resourceExamId = attempt.examId
                )
                if (authCheck is AuthorizationResult.Denied) {
                    recordPermissionDenial(auditEngine, session, Permission.RESULT_READ_ASSIGNED, authCheck.reason)
                    call.respondText(errorJson("FORBIDDEN", authCheck.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                    return@get
                }
            }

            val resp = JSONObject().apply {
                put("resultId", result.resultId)
                put("attemptId", result.attemptId)
                put("examId", result.examId)
                put("studentId", result.studentId)
                put("version", result.version)
                put("resultStatus", result.resultStatus)
                put("totalScaledScore", result.totalScaledScore)
                put("maxPossibleScaledScore", result.maxPossibleScaledScore)
                put("correctCount", result.correctCount)
                put("incorrectCount", result.incorrectCount)
                put("unansweredCount", result.unansweredCount)
                put("partiallyCorrectCount", result.partiallyCorrectCount)
                put("pendingManualCount", result.pendingManualCount)
                put("markingSchemeVersion", result.markingSchemeVersion)
                put("scoringEngineVersion", result.scoringEngineVersion)
                put("sectionTotals", JSONArray(result.sectionTotalsJson))
                put("resultHash", result.resultHash)
                put("scoredAt", result.scoredAt)
                put("publishedAt", result.publishedAt ?: JSONObject.NULL)
            }
            call.respondText(resp.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }

        // 27. Scoring: Get Result Breakdown
        get("/api/attempts/{attemptId}/result/breakdown") {
            val session = authenticate(call, sessionManager) ?: return@get
            val attemptId = call.parameters["attemptId"] ?: ""

            val attempt = attemptRepository.getAttempt(attemptId)
            if (attempt == null) {
                call.respondText(errorJson("NOT_FOUND", "Attempt not found"), ContentType.Application.Json, HttpStatusCode.NotFound)
                return@get
            }

            val result = attemptRepository.getGradingResult(attemptId)
            if (result == null) {
                call.respondText(errorJson("NOT_FOUND", "Result not yet generated for attempt"), ContentType.Application.Json, HttpStatusCode.NotFound)
                return@get
            }

            if (session.role == "STUDENT") {
                val authCheck = PermissionService.authorize(
                    session = session,
                    permission = Permission.RESULT_READ_OWN,
                    resourceStudentId = attempt.studentId
                )
                if (authCheck is AuthorizationResult.Denied) {
                    recordPermissionDenial(auditEngine, session, Permission.RESULT_READ_OWN, authCheck.reason)
                    call.respondText(errorJson("FORBIDDEN", authCheck.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                    return@get
                }

                if (result.resultStatus == "HELD_PENDING_REVIEW" || attempt.resultStatus == "HELD_PENDING_REVIEW") {
                    call.respondText(errorJson("RESULT_WITHHELD", "Result is held under administrative review."), ContentType.Application.Json, HttpStatusCode.Forbidden)
                    return@get
                }

                if (result.resultStatus != "PUBLISHED") {
                    call.respondText(errorJson("RESULT_NOT_RELEASED", "Result has not been released yet."), ContentType.Application.Json, HttpStatusCode.Forbidden)
                    return@get
                }
            } else {
                val authCheck = PermissionService.authorize(
                    session = session,
                    permission = Permission.RESULT_READ_ASSIGNED,
                    resourceExamId = attempt.examId
                )
                if (authCheck is AuthorizationResult.Denied) {
                    recordPermissionDenial(auditEngine, session, Permission.RESULT_READ_ASSIGNED, authCheck.reason)
                    call.respondText(errorJson("FORBIDDEN", authCheck.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                    return@get
                }
            }

            val records = attemptRepository.getGradingRecords(result.resultId)
            val arr = JSONArray().apply {
                for (r in records) {
                    put(JSONObject().apply {
                        put("recordId", r.recordId)
                        put("questionId", r.questionId)
                        put("sectionId", r.sectionId ?: JSONObject.NULL)
                        put("questionNumber", r.questionNumber)
                        put("questionType", r.questionType)
                        put("submittedAnswer", r.submittedAnswerJson ?: JSONObject.NULL)
                        put("correctAnswerSnapshot", r.correctAnswerSnapshot ?: JSONObject.NULL)
                        put("answerClassification", r.answerClassification)
                        put("marksAwarded", r.marksAwarded)
                        put("maxMarks", r.maxMarks)
                        put("explanationCode", r.explanationCode)
                        put("calculationMetadata", r.calculationMetadataJson ?: JSONObject.NULL)
                    })
                }
            }
            call.respondText(arr.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }

        // 28. Scoring: Publish Result
        post("/api/attempts/{attemptId}/result/publish") {
            val session = authenticate(call, sessionManager) ?: return@post
            val attemptId = call.parameters["attemptId"] ?: ""

            val authCheck = PermissionService.authorize(session, Permission.RESULT_PUBLISH)
            if (authCheck is AuthorizationResult.Denied) {
                recordPermissionDenial(auditEngine, session, Permission.RESULT_PUBLISH, authCheck.reason)
                call.respondText(errorJson("FORBIDDEN", authCheck.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@post
            }

            try {
                val published = attemptRepository.publishResult(attemptId, session.userId, session.role)
                val resp = JSONObject().apply {
                    put("resultId", published.resultId)
                    put("resultStatus", published.resultStatus)
                    put("publishedAt", published.publishedAt)
                }
                call.respondText(resp.toString(), ContentType.Application.Json, HttpStatusCode.OK)
            } catch (e: IllegalStateException) {
                call.respondText(errorJson("PUBLICATION_BLOCKED", e.message ?: "Publication blocked"), ContentType.Application.Json, HttpStatusCode.UnprocessableEntity)
            } catch (e: IllegalArgumentException) {
                call.respondText(errorJson("NOT_FOUND", e.message ?: "Result not found"), ContentType.Application.Json, HttpStatusCode.NotFound)
            } catch (e: Exception) {
                call.respondText(errorJson("INTERNAL_ERROR", e.message ?: "Failed to publish result"), ContentType.Application.Json, HttpStatusCode.InternalServerError)
            }
        }

        // 29. Scoring: Rescore Attempt
        post("/api/attempts/{attemptId}/rescore") {
            val session = authenticate(call, sessionManager) ?: return@post
            val attemptId = call.parameters["attemptId"] ?: ""

            val authCheck = PermissionService.authorize(session, Permission.RESULT_GRADE)
            if (authCheck is AuthorizationResult.Denied) {
                recordPermissionDenial(auditEngine, session, Permission.RESULT_GRADE, authCheck.reason)
                call.respondText(errorJson("FORBIDDEN", authCheck.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@post
            }

            try {
                val rescored = attemptRepository.gradeAttempt(attemptId, session.userId, session.role)
                val resp = JSONObject().apply {
                    put("resultId", rescored.resultId)
                    put("version", rescored.version)
                    put("totalScaledScore", rescored.totalScaledScore)
                    put("resultHash", rescored.resultHash)
                    put("rescoredAt", rescored.scoredAt)
                }
                call.respondText(resp.toString(), ContentType.Application.Json, HttpStatusCode.OK)
            } catch (e: IllegalArgumentException) {
                call.respondText(errorJson("NOT_FOUND", e.message ?: "Attempt not found"), ContentType.Application.Json, HttpStatusCode.NotFound)
            } catch (e: Exception) {
                call.respondText(errorJson("RESCORING_FAILED", e.message ?: "Failed to rescore attempt"), ContentType.Application.Json, HttpStatusCode.InternalServerError)
            }
        }

        // 30. Scoring: Simulation / Preview
        post("/api/scoring/simulate") {
            val session = authenticate(call, sessionManager) ?: return@post
            val body = call.receiveText()
            val req = try { JSONObject(body) } catch (e: Exception) {
                call.respondText(errorJson("MALFORMED_JSON", "Invalid JSON payload"), ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            val questionType = req.optString("questionType", "SINGLE_SELECT")
            val expectedAnswer = req.optString("expectedAnswer", "")
            val submittedAnswer = if (req.has("submittedAnswer") && !req.isNull("submittedAnswer")) req.getString("submittedAnswer") else null
            val numericalTolerance = if (req.has("numericalTolerance") && !req.isNull("numericalTolerance")) req.getDouble("numericalTolerance") else null
            val correctScaledMark = req.optLong("correctScaledMark", 400L)
            val incorrectScaledMark = req.optLong("incorrectScaledMark", -100L)
            val unansweredScaledMark = req.optLong("unansweredScaledMark", 0L)
            val allowNegativeMarking = req.optBoolean("allowNegativeMarking", true)
            val policyStr = req.optString("multiSelectPolicy", "EXACT_MATCH")
            val multiSelectPolicy = try { MultiSelectPolicy.valueOf(policyStr) } catch (_: Exception) { MultiSelectPolicy.EXACT_MATCH }

            val simResult = ScoringEngine.simulateQuestion(
                questionType = questionType,
                expectedAnswer = expectedAnswer,
                submittedAnswer = submittedAnswer,
                numericalTolerance = numericalTolerance,
                correctScaledMark = correctScaledMark,
                incorrectScaledMark = incorrectScaledMark,
                unansweredScaledMark = unansweredScaledMark,
                allowNegativeMarking = allowNegativeMarking,
                multiSelectPolicy = multiSelectPolicy
            )

            val resp = JSONObject().apply {
                put("questionType", simResult.questionType)
                put("classification", simResult.classification.name)
                put("marksAwarded", simResult.marksAwarded)
                put("maxPossibleMarks", simResult.maxPossibleMarks)
                put("explanationCode", simResult.explanationCode.name)
                put("calculationMetadata", simResult.calculationMetadata ?: JSONObject.NULL)
                put("effectiveRule", JSONObject().apply {
                    put("correctScaledMark", simResult.effectiveRule.correctScaledMark)
                    put("incorrectScaledMark", simResult.effectiveRule.incorrectScaledMark)
                    put("unansweredScaledMark", simResult.effectiveRule.unansweredScaledMark)
                    put("allowNegativeMarking", simResult.effectiveRule.allowNegativeMarking)
                    put("multiSelectPolicy", simResult.effectiveRule.multiSelectPolicy.name)
                })
            }
            call.respondText(resp.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }

        // =========================================================================
        // CANDIDATE / STUDENT EXAMINATION ENDPOINTS (STEP 7)
        // =========================================================================

        // 31. Student: List Assigned Published Examinations
        get("/api/student/exams") {
            val session = authenticate(call, sessionManager) ?: return@get
            val authCheck = PermissionService.authorize(session, Permission.EXAM_READ)
            if (authCheck is AuthorizationResult.Denied) {
                recordPermissionDenial(auditEngine, session, Permission.EXAM_READ, authCheck.reason)
                call.respondText(errorJson("FORBIDDEN", authCheck.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@get
            }

            val exams = database.examDao().getExamsByInstitute(session.instituteScope)
                .filter { it.status == "PUBLISHED" }

            val arr = JSONArray()
            for (e in exams) {
                val attempt = database.examAttemptDao().getAttemptByStudentAndExam(session.userId, e.examId)
                val result = attempt?.let { database.examResultDao().getResultByAttemptId(it.attemptId) }
                val qCount = database.questionDao().getQuestionsByExamId(e.examId).size
                val sCount = database.paperSectionDao().getSectionsByExamId(e.examId).size

                arr.put(JSONObject().apply {
                    put("examId", e.examId)
                    put("title", e.title)
                    put("durationSeconds", e.durationSeconds)
                    put("instituteId", e.instituteId)
                    put("markingSchemeVersion", e.markingSchemeVersion)
                    put("publishedAt", e.updatedAt)
                    put("totalQuestions", qCount)
                    put("totalSections", sCount)
                    if (attempt != null) {
                        put("attempt", JSONObject().apply {
                            put("attemptId", attempt.attemptId)
                            put("attemptStatus", attempt.attemptStatus)
                            put("resultStatus", attempt.resultStatus)
                            put("startedAt", attempt.startedAt)
                            put("submittedAt", attempt.submittedAt ?: JSONObject.NULL)
                            put("deadlineMetadata", JSONObject(attempt.deadlineMetadata))
                        })
                    } else {
                        put("attempt", JSONObject.NULL)
                    }
                    put("hasPublishedResult", result != null && result.resultStatus == "PUBLISHED")
                    put("isResultHeld", (result != null && result.resultStatus == "HELD_PENDING_REVIEW") || (attempt?.resultStatus == "HELD_PENDING_REVIEW"))
                })
            }
            call.respondText(arr.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }

        // 32. Student: Download Authorised Candidate Examination Package (CONFIDENTIALITY PRESERVED)
        get("/api/exams/{id}/candidate-package") {
            val session = authenticate(call, sessionManager) ?: return@get
            val examId = call.parameters["id"] ?: ""
            val exam = database.examDao().getExamById(examId)
            if (exam == null) {
                call.respondText(errorJson("NOT_FOUND", "Exam not found"), ContentType.Application.Json, HttpStatusCode.NotFound)
                return@get
            }

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

            if (exam.status != "PUBLISHED") {
                call.respondText(errorJson("FORBIDDEN", "Exam is not published yet"), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@get
            }

            val sections = database.paperSectionDao().getSectionsByExamId(examId)
            val questions = database.questionDao().getQuestionsByExamId(examId)
            val markingScheme = database.markingSchemeDao().getMarkingSchemeByExamId(examId)

            val packageHash = computeCandidatePackageHash(exam.examId, exam.title, exam.markingSchemeVersion, questions, sections)

            val sectionsArr = JSONArray()
            for (s in sections) {
                sectionsArr.put(JSONObject().apply {
                    put("sectionId", s.sectionId)
                    put("title", s.title)
                    put("sectionOrder", s.sectionOrder)
                })
            }

            val questionsArr = JSONArray()
            for (q in questions) {
                questionsArr.put(JSONObject().apply {
                    put("questionId", q.questionId)
                    put("questionNumber", q.questionNumber)
                    put("sectionId", q.sectionId ?: JSONObject.NULL)
                    put("questionType", q.questionType)
                    put("questionText", q.questionText)
                    put("answerOptions", JSONArray(q.answerOptionsJson))
                    put("sourcePage", q.sourcePage ?: JSONObject.NULL)
                })
            }

            val resp = JSONObject().apply {
                put("examId", exam.examId)
                put("title", exam.title)
                put("durationSeconds", exam.durationSeconds)
                put("instituteId", exam.instituteId)
                put("markingSchemeVersion", exam.markingSchemeVersion)
                put("packageHash", packageHash)
                put("sections", sectionsArr)
                put("questions", questionsArr)
                if (markingScheme != null) {
                    put("markingSummary", JSONObject().apply {
                        put("correctScaledMark", markingScheme.correctScaledMark)
                        put("incorrectScaledMark", markingScheme.incorrectScaledMark)
                        put("unansweredScaledMark", markingScheme.unansweredScaledMark)
                        put("allowNegativeMarking", markingScheme.allowNegativeMarking)
                    })
                }
            }
            call.respondText(resp.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }

        // 33. Student: Start or Resume Examination Attempt (AUTHORITATIVE SESSION)
        post("/api/exams/{id}/attempts/start") {
            val session = authenticate(call, sessionManager) ?: return@post
            val examId = call.parameters["id"] ?: ""

            val exam = database.examDao().getExamById(examId)
            if (exam == null) {
                call.respondText(errorJson("NOT_FOUND", "Exam not found"), ContentType.Application.Json, HttpStatusCode.NotFound)
                return@post
            }

            val authCheck = PermissionService.authorize(
                session = session,
                permission = Permission.EXAM_READ,
                resourceInstituteId = exam.instituteId,
                resourceExamId = exam.examId
            )
            if (authCheck is AuthorizationResult.Denied) {
                recordPermissionDenial(auditEngine, session, Permission.EXAM_READ, authCheck.reason)
                call.respondText(errorJson("FORBIDDEN", authCheck.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@post
            }

            if (exam.status != "PUBLISHED") {
                call.respondText(errorJson("FORBIDDEN", "Exam is not published yet"), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@post
            }

            try {
                val attempt = attemptRepository.startOrResumeAttempt(
                    examId = examId,
                    studentId = session.userId,
                    actorId = session.userId,
                    actorRole = session.role
                )

                val resp = JSONObject().apply {
                    put("attemptId", attempt.attemptId)
                    put("examId", attempt.examId)
                    put("studentId", attempt.studentId)
                    put("attemptStatus", attempt.attemptStatus)
                    put("resultStatus", attempt.resultStatus)
                    put("startedAt", attempt.startedAt)
                    put("deadlineMetadata", JSONObject(attempt.deadlineMetadata))
                }
                call.respondText(resp.toString(), ContentType.Application.Json, HttpStatusCode.OK)
            } catch (e: IllegalStateException) {
                call.respondText(errorJson("ATTEMPT_START_FAILED", e.message ?: "Failed to start attempt"), ContentType.Application.Json, HttpStatusCode.Conflict)
            } catch (e: Exception) {
                call.respondText(errorJson("INTERNAL_ERROR", e.message ?: "Unexpected error"), ContentType.Application.Json, HttpStatusCode.InternalServerError)
            }
        }

        // 34. Student: Get Attempt Status
        get("/api/attempts/{attemptId}/status") {
            val session = authenticate(call, sessionManager) ?: return@get
            val attemptId = call.parameters["attemptId"] ?: ""
            val attempt = database.examAttemptDao().getAttemptById(attemptId)
            if (attempt == null) {
                call.respondText(errorJson("NOT_FOUND", "Attempt not found"), ContentType.Application.Json, HttpStatusCode.NotFound)
                return@get
            }

            val authCheck = PermissionService.authorize(
                session = session,
                permission = Permission.ATTEMPT_READ_OWN,
                resourceStudentId = attempt.studentId
            )
            if (authCheck is AuthorizationResult.Denied) {
                recordPermissionDenial(auditEngine, session, Permission.ATTEMPT_READ_OWN, authCheck.reason)
                call.respondText(errorJson("FORBIDDEN", authCheck.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@get
            }

            val resp = JSONObject().apply {
                put("attemptId", attempt.attemptId)
                put("examId", attempt.examId)
                put("studentId", attempt.studentId)
                put("attemptStatus", attempt.attemptStatus)
                put("resultStatus", attempt.resultStatus)
                put("startedAt", attempt.startedAt)
                put("submittedAt", attempt.submittedAt ?: JSONObject.NULL)
                put("deadlineMetadata", JSONObject(attempt.deadlineMetadata))
            }
            call.respondText(resp.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }

        // 35. Student: Batch Sync Outbox Responses (IDEMPOTENT RECONCILIATION)
        post("/api/attempts/{attemptId}/sync-responses") {
            val session = authenticate(call, sessionManager) ?: return@post
            val attemptId = call.parameters["attemptId"] ?: ""
            val attempt = database.examAttemptDao().getAttemptById(attemptId)
            if (attempt == null) {
                call.respondText(errorJson("NOT_FOUND", "Attempt not found"), ContentType.Application.Json, HttpStatusCode.NotFound)
                return@post
            }

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
                call.respondText(errorJson("INVALID_STATE", "Cannot sync answers to attempt in status '${attempt.attemptStatus}'"), ContentType.Application.Json, HttpStatusCode.Conflict)
                return@post
            }

            val body = call.receiveText()
            val req = try { JSONObject(body) } catch (e: Exception) {
                call.respondText(errorJson("MALFORMED_JSON", "Invalid JSON payload"), ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            val responsesArray = req.optJSONArray("responses") ?: JSONArray()
            val nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
            var syncedCount = 0

            for (i in 0 until responsesArray.length()) {
                val rObj = responsesArray.getJSONObject(i)
                val qId = rObj.getString("questionId")
                val payload = rObj.getString("responsePayload")
                val revSeq = rObj.getInt("revisionSequence")

                val existing = database.candidateResponseDao().getResponse(attemptId, qId)
                if (existing == null || revSeq >= existing.revisionSequence) {
                    val responseEntity = CandidateResponseEntity(
                        responseId = existing?.responseId ?: "resp_${System.currentTimeMillis()}_${qId}",
                        attemptId = attemptId,
                        questionId = qId,
                        responsePayload = payload,
                        revisionSequence = revSeq,
                        savedAt = nowIso
                    )
                    database.candidateResponseDao().upsertResponse(responseEntity)
                    syncedCount++
                }
            }

            auditEngine.recordAuditEvent(
                auditEventId = "aud_batchsync_${System.currentTimeMillis()}_${attemptId.take(8)}",
                actorId = session.userId,
                actorRole = session.role,
                actionType = "BATCH_RESPONSES_SYNCED",
                aggregateType = "EXAM_ATTEMPT",
                aggregateId = attemptId,
                previousState = null,
                newState = "{\"syncedCount\":$syncedCount}",
                reason = "Batch candidate response synchronization replayed from student outbox",
                operationResult = "SUCCESS"
            )

            val resp = JSONObject().apply {
                put("status", "SYNCED")
                put("syncedCount", syncedCount)
                put("serverTimestamp", nowIso)
            }
            call.respondText(resp.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }

        // 36. Student: Authoritative Attempt Submission
        post("/api/attempts/{attemptId}/submit") {
            val session = authenticate(call, sessionManager) ?: return@post
            val attemptId = call.parameters["attemptId"] ?: ""
            val attempt = database.examAttemptDao().getAttemptById(attemptId)
            if (attempt == null) {
                call.respondText(errorJson("NOT_FOUND", "Attempt not found"), ContentType.Application.Json, HttpStatusCode.NotFound)
                return@post
            }

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

            val body = call.receiveText()
            val req = try { JSONObject(body) } catch (e: Exception) {
                JSONObject()
            }

            val clientChecksum = req.optString("clientChecksum", "none")
            val totalAnswered = req.optInt("totalQuestionsAnswered", 0)

            try {
                val receipt = attemptRepository.submitAttempt(
                    attemptId = attemptId,
                    studentId = session.userId,
                    clientChecksum = clientChecksum,
                    totalAnswered = totalAnswered,
                    actorId = session.userId,
                    actorRole = session.role
                )
                val resp = JSONObject().apply {
                    put("receiptId", receipt.receiptId)
                    put("attemptId", receipt.attemptId)
                    put("examId", receipt.examId)
                    put("studentId", receipt.studentId)
                    put("submissionStatus", receipt.submissionStatus)
                    put("submittedAt", receipt.submittedAt)
                    put("receiptHash", receipt.receiptHash)
                    put("totalQuestionsAnswered", receipt.totalQuestionsAnswered)
                }
                call.respondText(resp.toString(), ContentType.Application.Json, HttpStatusCode.OK)
            } catch (e: IllegalStateException) {
                call.respondText(errorJson("SUBMISSION_REJECTED", e.message ?: "Submission rejected"), ContentType.Application.Json, HttpStatusCode.Conflict)
            } catch (e: Exception) {
                call.respondText(errorJson("INTERNAL_ERROR", e.message ?: "Submission failed"), ContentType.Application.Json, HttpStatusCode.InternalServerError)
            }
        }

        // 37. Student: Report & Ingest Integrity Event / Batch (STEP 8 TELEMETRY & ENFORCEMENT)
        post("/api/attempts/{attemptId}/integrity-events") {
            val session = authenticate(call, sessionManager) ?: return@post
            val attemptId = call.parameters["attemptId"] ?: ""
            val attempt = database.examAttemptDao().getAttemptById(attemptId)
            if (attempt == null) {
                call.respondText(errorJson("NOT_FOUND", "Attempt not found"), ContentType.Application.Json, HttpStatusCode.NotFound)
                return@post
            }

            val exam = database.examDao().getExamById(attempt.examId)
            if (exam == null) {
                call.respondText(errorJson("NOT_FOUND", "Associated exam not found"), ContentType.Application.Json, HttpStatusCode.NotFound)
                return@post
            }

            val authCheck = PermissionService.authorize(
                session = session,
                permission = Permission.INTEGRITY_EVENT_REPORT_OWN,
                resourceInstituteId = exam.instituteId,
                resourceStudentId = attempt.studentId
            )
            if (authCheck is AuthorizationResult.Denied) {
                recordPermissionDenial(auditEngine, session, Permission.INTEGRITY_EVENT_REPORT_OWN, authCheck.reason)
                call.respondText(errorJson("FORBIDDEN", authCheck.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@post
            }

            val body = call.receiveText()
            val reqJson = try { JSONObject(body) } catch (e: Exception) {
                call.respondText(errorJson("MALFORMED_JSON", "Invalid JSON payload"), ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            val eventsArray = if (reqJson.has("events")) {
                reqJson.getJSONArray("events")
            } else {
                JSONArray().put(reqJson)
            }

            val nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
            val processedResults = JSONArray()

            for (i in 0 until eventsArray.length()) {
                val ev = eventsArray.getJSONObject(i)
                val eventId = ev.optString("eventId", "integ_${System.currentTimeMillis()}_$i")
                val category = ev.optString("eventCategory", "EXAM_APP_BACKGROUND")
                val deviceTimestamp = ev.optString("deviceReportedAt", nowIso)
                val seq = ev.optLong("sequenceNumber", 1L)
                val sourceDeviceId = ev.optString("sourceDeviceId", "device_unknown")
                val appPackageName = if (ev.has("appPackageName") && !ev.isNull("appPackageName")) ev.getString("appPackageName") else null
                val displayedAppName = if (ev.has("displayedAppName") && !ev.isNull("displayedAppName")) ev.getString("displayedAppName") else null
                val payloadJson = ev.optString("payloadJson", "{}")

                // Idempotency check
                val existing = database.integrityEventDao().getEventById(eventId)
                if (existing != null) {
                    if (!reqJson.has("events") && !reqJson.has("sequenceNumber")) {
                        val dupResp = JSONObject().apply {
                            put("status", "DUPLICATE_IGNORED")
                            put("eventId", eventId)
                            put("serverReceivedAt", existing.receivedAt)
                        }
                        call.respondText(dupResp.toString(), ContentType.Application.Json, HttpStatusCode.OK)
                        return@post
                    }
                    processedResults.put(JSONObject().apply {
                        put("eventId", eventId)
                        put("ingestionStatus", "ALREADY_INGESTED")
                        put("actionTaken", "LOG_ONLY")
                        put("attemptStatus", attempt.attemptStatus)
                    })
                    continue
                }

                if (!PERMITTED_INTEGRITY_CATEGORIES.contains(category)) {
                    call.respondText(errorJson("UNSUPPORTED_CATEGORY", "Integrity event category '$category' is not recognized"), ContentType.Application.Json, HttpStatusCode.BadRequest)
                    return@post
                }

                // Policy evaluation
                var actionTaken = "LOG_ONLY"
                var warningMessage: String? = null

                val isHighConfidenceViolation = (category == "PROHIBITED_APP_FOREGROUND" && !appPackageName.isNullOrBlank()) ||
                        (category == "LOCK_TASK_EXITED")

                if (isHighConfidenceViolation && attempt.attemptStatus == "IN_PROGRESS") {
                    val autoCancellationPolicy = true // Exam policy enforcement
                    if (autoCancellationPolicy) {
                        database.examAttemptDao().updateAttemptAndResultStatus(attemptId, "CANCELLED", "WITHHELD", nowIso)
                        actionTaken = "CANCEL_ATTEMPT"

                        val incId = "inc_auto_${System.currentTimeMillis()}_${eventId.take(8)}"
                        database.incidentDao().insertIncident(
                            IncidentCaseEntity(
                                incidentId = incId,
                                examId = exam.examId,
                                attemptId = attemptId,
                                studentId = session.userId,
                                category = "UNAUTHORISED_APP_SWITCH",
                                incidentStatus = "CONFIRMED",
                                evidenceAssessment = "SUFFICIENT",
                                decision = "CONFIRMED",
                                reviewerId = "SYSTEM_POLICY_ENGINE",
                                policyVersion = "v1.0",
                                version = 1,
                                createdAt = nowIso,
                                updatedAt = nowIso
                            )
                        )

                        database.incidentDao().insertPolicyAction(
                            PolicyActionEntity(
                                actionId = "act_cancel_${System.currentTimeMillis()}_${eventId.take(8)}",
                                incidentId = incId,
                                actionType = "CANCEL_ATTEMPT",
                                executionStatus = "APPLIED",
                                requestedBy = "SYSTEM_POLICY_ENGINE",
                                approvedBy = "SYSTEM_POLICY_ENGINE",
                                policyVersion = "v1.0",
                                reason = "Automatic cancellation: verified $category ($appPackageName)",
                                createdAt = nowIso,
                                completedAt = nowIso
                            )
                        )

                        auditEngine.recordAuditEvent(
                            auditEventId = "aud_cancel_${System.currentTimeMillis()}_${eventId.take(6)}",
                            actorId = "SYSTEM_POLICY_ENGINE",
                            actorRole = "SYSTEM",
                            actionType = "AUTOMATIC_ATTEMPT_CANCELLATION",
                            aggregateType = "EXAM_ATTEMPT",
                            aggregateId = attemptId,
                            operationResult = "SUCCESS"
                        )
                    } else {
                        actionTaken = "ALERT_EXAMINER"
                    }
                } else if (category == "EXAM_APP_BACKGROUND" || category == "WINDOW_BLUR") {
                    // Low confidence interruption: warn student and alert examiner; never instant cancellation!
                    actionTaken = "WARN_STUDENT"
                    warningMessage = "Leaving the examination window is recorded. Return immediately to avoid violation escalation."
                } else {
                    actionTaken = "ALERT_EXAMINER"
                }

                val integrityEntity = IntegrityEventEntity(
                    eventId = eventId,
                    examId = exam.examId,
                    attemptId = attemptId,
                    studentId = session.userId,
                    eventCategory = category,
                    deviceReportedAt = deviceTimestamp,
                    receivedAt = nowIso,
                    sequenceNumber = seq,
                    sourceDeviceId = sourceDeviceId,
                    payloadJson = payloadJson,
                    payloadHash = null,
                    appPackageName = appPackageName,
                    displayedAppName = displayedAppName,
                    ingestionStatus = "RECEIVED"
                )
                database.integrityEventDao().insertEvent(integrityEntity)

                auditEngine.recordAuditEvent(
                    auditEventId = "aud_integ_${System.currentTimeMillis()}_${eventId.take(6)}",
                    actorId = session.userId,
                    actorRole = session.role,
                    actionType = "INTEGRITY_EVENT_INGESTED",
                    aggregateType = "INTEGRITY_EVENT",
                    aggregateId = eventId,
                    operationResult = "SUCCESS"
                )

                // Refresh current attempt state
                val updatedAttempt = database.examAttemptDao().getAttemptById(attemptId)

                processedResults.put(JSONObject().apply {
                    put("eventId", eventId)
                    put("ingestionStatus", "RECEIVED")
                    put("actionTaken", actionTaken)
                    put("attemptStatus", updatedAttempt?.attemptStatus ?: attempt.attemptStatus)
                    if (warningMessage != null) {
                        put("warningMessage", warningMessage)
                    }
                })
            }

            if (!reqJson.has("events") && !reqJson.has("sequenceNumber")) {
                val singleResp = JSONObject().apply {
                    put("status", "INGESTED")
                    put("eventId", if (processedResults.length() > 0) processedResults.getJSONObject(0).optString("eventId") else "")
                    put("receivedAt", nowIso)
                    put("results", processedResults)
                }
                call.respondText(singleResp.toString(), ContentType.Application.Json, HttpStatusCode.Created)
                return@post
            }

            val resp = JSONObject().apply {
                put("attemptId", attemptId)
                put("processedCount", processedResults.length())
                put("results", processedResults)
            }
            call.respondText(resp.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }

        // 38. Examiner: Get Real-Time Integrity Alerts (EXAMINER INTEGRITY MONITOR)
        get("/api/examiner/integrity/alerts") {
            val session = authenticate(call, sessionManager) ?: return@get

            val authCheck = PermissionService.authorize(
                session = session,
                permission = Permission.INTEGRITY_EVENT_READ_ASSIGNED,
                resourceInstituteId = session.instituteScope
            )
            if (authCheck is AuthorizationResult.Denied) {
                recordPermissionDenial(auditEngine, session, Permission.INTEGRITY_EVENT_READ_ASSIGNED, authCheck.reason)
                call.respondText(errorJson("FORBIDDEN", authCheck.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@get
            }

            val examIdFilter = call.parameters["examId"]
            val events = if (examIdFilter != null && examIdFilter.isNotBlank()) {
                database.integrityEventDao().getEventsByExam(examIdFilter)
            } else {
                database.integrityEventDao().getAllEvents()
            }

            val alertsArray = JSONArray()
            for (ev in events) {
                val student = database.userDao().getUserById(ev.studentId)
                val exam = database.examDao().getExamById(ev.examId)
                val attempt = database.examAttemptDao().getAttemptById(ev.attemptId)

                val confidenceLevel = if (ev.appPackageName != null || ev.eventCategory == "LOCK_TASK_EXITED") "HIGH" else "LOW"
                val severity = when {
                    ev.eventCategory == "LOCK_TASK_EXITED" || ev.eventCategory == "PROHIBITED_APP_FOREGROUND" -> "CRITICAL"
                    ev.eventCategory == "EXAM_APP_BACKGROUND" -> "HIGH"
                    ev.eventCategory == "WINDOW_BLUR" -> "MEDIUM"
                    else -> "LOW"
                }

                val alertObj = JSONObject().apply {
                    put("alertId", "alert_${ev.eventId}")
                    put("eventId", ev.eventId)
                    put("incidentId", ev.eventId)
                    put("examId", ev.examId)
                    put("examTitle", exam?.title ?: "Unknown Exam")
                    put("attemptId", ev.attemptId)
                    put("studentId", ev.studentId)
                    put("studentDisplayName", student?.displayName ?: ev.studentId)
                    put("eventCategory", ev.eventCategory)
                    put("deviceTimestamp", ev.deviceReportedAt)
                    put("receivedAt", ev.receivedAt ?: ev.deviceReportedAt)
                    put("appPackageName", ev.appPackageName ?: JSONObject.NULL)
                    put("displayedAppName", ev.displayedAppName ?: "APP_NAME_UNAVAILABLE")
                    put("detectionSource", if (ev.appPackageName != null) "USAGE_STATS" else "APP_LIFECYCLE")
                    put("confidenceLevel", confidenceLevel)
                    put("severity", severity)
                    put("policyAction", if (attempt?.attemptStatus == "CANCELLED") "CANCEL_ATTEMPT" else "ALERT_EXAMINER")
                    put("actionStatus", if (attempt?.attemptStatus == "CANCELLED") "APPLIED" else "PENDING")
                    put("alertStatus", ev.ingestionStatus)
                    put("attemptStatus", attempt?.attemptStatus ?: "UNKNOWN")
                }
                alertsArray.put(alertObj)
            }

            call.respondText(alertsArray.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }

        // 39. Examiner: Acknowledge Integrity Alert
        post("/api/examiner/integrity/alerts/{eventId}/acknowledge") {
            val session = authenticate(call, sessionManager) ?: return@post
            val eventId = call.parameters["eventId"] ?: ""

            val authCheck = PermissionService.authorize(
                session = session,
                permission = Permission.INTEGRITY_EVENT_READ_ASSIGNED,
                resourceInstituteId = session.instituteScope
            )
            if (authCheck is AuthorizationResult.Denied) {
                recordPermissionDenial(auditEngine, session, Permission.INTEGRITY_EVENT_READ_ASSIGNED, authCheck.reason)
                call.respondText(errorJson("FORBIDDEN", authCheck.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@post
            }

            val event = database.integrityEventDao().getEventById(eventId)
            if (event == null) {
                call.respondText(errorJson("NOT_FOUND", "Event not found"), ContentType.Application.Json, HttpStatusCode.NotFound)
                return@post
            }

            val nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
            database.integrityEventDao().updateIngestionStatus(eventId, "ACKNOWLEDGED", nowIso)

            auditEngine.recordAuditEvent(
                auditEventId = "aud_ack_${System.currentTimeMillis()}",
                actorId = session.userId,
                actorRole = session.role,
                actionType = "INTEGRITY_ALERT_ACKNOWLEDGED",
                aggregateType = "INTEGRITY_EVENT",
                aggregateId = eventId,
                operationResult = "SUCCESS"
            )

            call.respondText(JSONObject().apply {
                put("eventId", eventId)
                put("status", "ACKNOWLEDGED")
                put("acknowledgedBy", session.userId)
                put("acknowledgedAt", nowIso)
            }.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }

        // =========================================================================
        // STEP 9: ADMINISTRATOR INCIDENT REVIEW, RESULT HOLDS & APPEALS
        // =========================================================================
        val incidentReviewService = IncidentReviewService(database, auditEngine)

        // 40. List Authorised Incidents with Filtering
        get("/api/incidents") {
            val session = authenticate(call, sessionManager) ?: return@get

            if (session.role != "STUDENT") {
                val authCheck = PermissionService.authorize(session, Permission.INCIDENT_READ_ASSIGNED, session.instituteScope)
                if (authCheck is AuthorizationResult.Denied) {
                    recordPermissionDenial(auditEngine, session, Permission.INCIDENT_READ_ASSIGNED, authCheck.reason)
                    call.respondText(errorJson("FORBIDDEN", authCheck.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                    return@get
                }
            }

            val examIdFilter = call.parameters["examId"]
            val studentIdFilter = if (session.role == "STUDENT") session.userId else call.parameters["studentId"]
            val statusFilter = call.parameters["status"]
            val categoryFilter = call.parameters["category"]

            val rawIncidents = when {
                examIdFilter != null && examIdFilter.isNotBlank() -> database.incidentDao().getIncidentsByExam(examIdFilter)
                studentIdFilter != null && studentIdFilter.isNotBlank() -> database.incidentDao().getIncidentsByStudent(studentIdFilter)
                statusFilter != null && statusFilter.isNotBlank() -> database.incidentDao().getIncidentsByStatus(statusFilter)
                else -> database.incidentDao().getAllIncidents()
            }

            val filtered = rawIncidents.filter { inc ->
                val matchStudent = if (session.role == "STUDENT") inc.studentId == session.userId else true
                val matchCat = if (categoryFilter != null && categoryFilter.isNotBlank()) inc.category == categoryFilter else true
                val matchStat = if (statusFilter != null && statusFilter.isNotBlank()) inc.incidentStatus == statusFilter else true
                matchStudent && matchCat && matchStat
            }

            val resultArray = JSONArray()
            for (inc in filtered) {
                val student = database.userDao().getUserById(inc.studentId)
                val exam = database.examDao().getExamById(inc.examId)
                val actions = database.incidentDao().getPolicyActionsForIncident(inc.incidentId)
                val appeals = database.incidentDao().getAppealsForIncident(inc.incidentId)
                val latestAction = actions.lastOrNull()
                val pendingAppeals = appeals.filter { it.appealStatus == "SUBMITTED" || it.appealStatus == "PENDING_REVIEW" }

                resultArray.put(JSONObject().apply {
                    put("incidentId", inc.incidentId)
                    put("examId", inc.examId)
                    put("examTitle", exam?.title ?: inc.examId)
                    put("attemptId", inc.attemptId)
                    put("studentId", inc.studentId)
                    put("studentDisplayName", student?.displayName ?: inc.studentId)
                    put("category", inc.category)
                    put("incidentStatus", inc.incidentStatus)
                    put("evidenceAssessment", inc.evidenceAssessment)
                    put("decision", inc.decision)
                    put("requestedPolicyAction", latestAction?.actionType ?: "NO_ACTION")
                    put("policyActionExecutionStatus", latestAction?.executionStatus ?: "NONE")
                    put("reviewerId", inc.reviewerId ?: JSONObject.NULL)
                    put("policyVersion", inc.policyVersion)
                    put("version", inc.version)
                    put("hasPendingAppeals", pendingAppeals.isNotEmpty())
                    put("appealsCount", appeals.size)
                    put("createdAt", inc.createdAt)
                    put("updatedAt", inc.updatedAt)
                })
            }

            call.respondText(resultArray.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }

        // 41. Retrieve Incident Detail with Full Evidence & Timeline
        get("/api/incidents/{incidentId}") {
            val session = authenticate(call, sessionManager) ?: return@get
            val incidentId = call.parameters["incidentId"] ?: ""

            val incident = database.incidentDao().getIncidentById(incidentId)
            if (incident == null) {
                call.respondText(errorJson("NOT_FOUND", "Incident not found"), ContentType.Application.Json, HttpStatusCode.NotFound)
                return@get
            }

            // Student ownership protection
            if (session.role == "STUDENT") {
                if (incident.studentId != session.userId) {
                    recordPermissionDenial(auditEngine, session, Permission.INCIDENT_READ_ASSIGNED, "Students cannot inspect other students' incidents")
                    call.respondText(errorJson("FORBIDDEN", "Forbidden: Incident belongs to another candidate"), ContentType.Application.Json, HttpStatusCode.Forbidden)
                    return@get
                }
            } else {
                val authCheck = PermissionService.authorize(session, Permission.INCIDENT_READ_ASSIGNED, session.instituteScope)
                if (authCheck is AuthorizationResult.Denied) {
                    recordPermissionDenial(auditEngine, session, Permission.INCIDENT_READ_ASSIGNED, authCheck.reason)
                    call.respondText(errorJson("FORBIDDEN", authCheck.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                    return@get
                }
            }

            val student = database.userDao().getUserById(incident.studentId)
            val exam = database.examDao().getExamById(incident.examId)
            val attempt = database.examAttemptDao().getAttemptById(incident.attemptId)
            val result = database.examResultDao().getResultByAttemptId(incident.attemptId)
            val events = database.integrityEventDao().getEventsByAttempt(incident.attemptId)
            val decisions = database.incidentDao().getDecisionsForIncident(incidentId)
            val policyActions = database.incidentDao().getPolicyActionsForIncident(incidentId)
            val appeals = database.incidentDao().getAppealsForIncident(incidentId)
            val responses = database.candidateResponseDao().getResponsesByAttempt(incident.attemptId)

            val detailObj = JSONObject().apply {
                put("incidentId", incident.incidentId)
                put("examId", incident.examId)
                put("examTitle", exam?.title ?: incident.examId)
                put("attemptId", incident.attemptId)
                put("studentId", incident.studentId)
                put("studentDisplayName", student?.displayName ?: incident.studentId)
                put("category", incident.category)
                put("incidentStatus", incident.incidentStatus)
                put("evidenceAssessment", incident.evidenceAssessment)
                put("decision", incident.decision)
                put("reviewerId", incident.reviewerId ?: JSONObject.NULL)
                put("policyVersion", incident.policyVersion)
                put("version", incident.version)
                put("createdAt", incident.createdAt)
                put("updatedAt", incident.updatedAt)

                put("attempt", JSONObject().apply {
                    put("attemptStatus", attempt?.attemptStatus ?: "UNKNOWN")
                    put("resultStatus", result?.resultStatus ?: attempt?.resultStatus ?: "UNKNOWN")
                    put("startedAt", attempt?.startedAt ?: "")
                    put("submittedAt", attempt?.submittedAt ?: JSONObject.NULL)
                    put("savedResponseCount", responses.size)
                })

                if (result != null && session.role != "STUDENT") {
                    put("result", JSONObject().apply {
                        put("resultId", result.resultId)
                        put("resultStatus", result.resultStatus)
                        put("totalScaledScore", result.totalScaledScore)
                        put("maxPossibleScaledScore", result.maxPossibleScaledScore)
                        put("correctCount", result.correctCount)
                        put("holdReason", result.holdReason ?: JSONObject.NULL)
                    })
                }

                put("events", JSONArray().apply {
                    for (ev in events) {
                        put(JSONObject().apply {
                            put("eventId", ev.eventId)
                            put("eventCategory", ev.eventCategory)
                            put("deviceReportedAt", ev.deviceReportedAt)
                            put("receivedAt", ev.receivedAt)
                            put("sequenceNumber", ev.sequenceNumber)
                            put("sourceDeviceId", ev.sourceDeviceId)
                            put("appPackageName", ev.appPackageName ?: JSONObject.NULL)
                            put("displayedAppName", ev.displayedAppName ?: "APP_NAME_UNAVAILABLE")
                            put("detectionSource", if (ev.appPackageName != null) "USAGE_STATS" else "APP_LIFECYCLE")
                            put("ingestionStatus", ev.ingestionStatus)
                        })
                    }
                })

                put("decisions", JSONArray().apply {
                    for (dec in decisions) {
                        put(JSONObject().apply {
                            put("decisionId", dec.decisionId)
                            put("reviewerId", dec.reviewerId)
                            put("decision", dec.decision)
                            put("evidenceAssessment", dec.evidenceAssessment)
                            put("reviewerJustification", dec.reviewerJustification)
                            put("policyVersion", dec.policyVersion)
                            put("createdAt", dec.createdAt)
                        })
                    }
                })

                put("policyActions", JSONArray().apply {
                    for (act in policyActions) {
                        put(JSONObject().apply {
                            put("actionId", act.actionId)
                            put("actionType", act.actionType)
                            put("executionStatus", act.executionStatus)
                            put("requestedBy", act.requestedBy)
                            put("approvedBy", act.approvedBy ?: JSONObject.NULL)
                            put("reason", act.reason)
                            put("createdAt", act.createdAt)
                            put("completedAt", act.completedAt ?: JSONObject.NULL)
                        })
                    }
                })

                put("appeals", JSONArray().apply {
                    for (appl in appeals) {
                        put(JSONObject().apply {
                            put("appealId", appl.appealId)
                            put("studentId", appl.studentId)
                            put("challengedAction", appl.challengedAction)
                            put("appealReason", appl.appealReason)
                            put("evidenceReferences", appl.evidenceReferences ?: JSONObject.NULL)
                            put("appealStatus", appl.appealStatus)
                            put("assignedReviewerId", appl.assignedReviewerId ?: JSONObject.NULL)
                            put("reviewerDecision", appl.reviewerDecision ?: JSONObject.NULL)
                            put("outcomeReason", appl.outcomeReason ?: JSONObject.NULL)
                            put("submittedAt", appl.submittedAt)
                            put("resolvedAt", appl.resolvedAt ?: JSONObject.NULL)
                        })
                    }
                })
            }

            call.respondText(detailObj.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }

        // 42. Begin Incident Review
        post("/api/incidents/{incidentId}/review/begin") {
            val session = authenticate(call, sessionManager) ?: return@post
            val incidentId = call.parameters["incidentId"] ?: ""

            when (val res = incidentReviewService.beginReview(incidentId, session)) {
                is IncidentOperationResult.Success -> {
                    call.respondText(JSONObject().apply {
                        put("status", "SUCCESS")
                        put("incidentStatus", res.value.incidentStatus)
                        put("reviewerId", res.value.reviewerId)
                        put("auditEventId", res.auditEventId)
                    }.toString(), ContentType.Application.Json, HttpStatusCode.OK)
                }
                is IncidentOperationResult.PermissionDenied -> call.respondText(errorJson("FORBIDDEN", res.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                is IncidentOperationResult.InvalidTransition -> call.respondText(errorJson("INVALID_TRANSITION", res.reason), ContentType.Application.Json, HttpStatusCode.BadRequest)
                is IncidentOperationResult.NotFound -> call.respondText(errorJson("NOT_FOUND", "${res.entity} not found"), ContentType.Application.Json, HttpStatusCode.NotFound)
                is IncidentOperationResult.ValidationError -> call.respondText(errorJson("VALIDATION_ERROR", res.message), ContentType.Application.Json, HttpStatusCode.BadRequest)
            }
        }

        // 43. Assess Evidence and Record Formal Incident Decision
        post("/api/incidents/{incidentId}/assess") {
            val session = authenticate(call, sessionManager) ?: return@post
            val incidentId = call.parameters["incidentId"] ?: ""

            val body = call.receiveText()
            val reqJson = try { JSONObject(body) } catch (e: Exception) {
                call.respondText(errorJson("MALFORMED_JSON", "Invalid JSON payload"), ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            val evidenceAssessment = reqJson.optString("evidenceAssessment", "LIMITED").trim()
            val decision = reqJson.optString("decision", "PENDING_FURTHER_REVIEW").trim()
            val justification = reqJson.optString("justification", "").trim()
            val policyVersion = reqJson.optString("policyVersion", "v1.0").trim()

            when (val res = incidentReviewService.recordAssessmentAndDecision(incidentId, session, evidenceAssessment, decision, justification, policyVersion)) {
                is IncidentOperationResult.Success -> {
                    call.respondText(JSONObject().apply {
                        put("status", "SUCCESS")
                        put("incidentId", res.value.incidentId)
                        put("incidentStatus", res.value.incidentStatus)
                        put("evidenceAssessment", res.value.evidenceAssessment)
                        put("decision", res.value.decision)
                        put("auditEventId", res.auditEventId)
                    }.toString(), ContentType.Application.Json, HttpStatusCode.OK)
                }
                is IncidentOperationResult.PermissionDenied -> call.respondText(errorJson("FORBIDDEN", res.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                is IncidentOperationResult.InvalidTransition -> call.respondText(errorJson("INVALID_TRANSITION", res.reason), ContentType.Application.Json, HttpStatusCode.BadRequest)
                is IncidentOperationResult.NotFound -> call.respondText(errorJson("NOT_FOUND", "${res.entity} not found"), ContentType.Application.Json, HttpStatusCode.NotFound)
                is IncidentOperationResult.ValidationError -> call.respondText(errorJson("VALIDATION_ERROR", res.message), ContentType.Application.Json, HttpStatusCode.BadRequest)
            }
        }

        // 44. Execute Required Limited-Evidence Result-Hold Scenario Fixture
        post("/api/incidents/{incidentId}/scenario/limited-evidence-hold") {
            val session = authenticate(call, sessionManager) ?: return@post
            val incidentId = call.parameters["incidentId"] ?: ""

            when (val res = incidentReviewService.executeLimitedEvidenceHoldScenario(incidentId, session)) {
                is IncidentOperationResult.Success -> {
                    val summary = res.value
                    call.respondText(JSONObject().apply {
                        put("status", "SUCCESS")
                        put("scenario", "REQUIRED_LIMITED_EVIDENCE_HOLD_SCENARIO")
                        put("incidentId", summary.incident.incidentId)
                        put("incidentCategory", summary.incident.category)
                        put("evidenceAssessment", summary.incident.evidenceAssessment)
                        put("incidentStatus", summary.incident.incidentStatus)
                        put("decision", summary.incident.decision)
                        put("requestedPolicyAction", "HOLD")
                        put("reviewerJustification", "No notes supplied")
                        put("resultStatus", "HELD_PENDING_REVIEW")
                        put("attemptStatusPreserved", true)
                        put("auditEventId", res.auditEventId)
                    }.toString(), ContentType.Application.Json, HttpStatusCode.OK)
                }
                is IncidentOperationResult.PermissionDenied -> call.respondText(errorJson("FORBIDDEN", res.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                is IncidentOperationResult.InvalidTransition -> call.respondText(errorJson("INVALID_TRANSITION", res.reason), ContentType.Application.Json, HttpStatusCode.BadRequest)
                is IncidentOperationResult.NotFound -> call.respondText(errorJson("NOT_FOUND", "${res.entity} not found"), ContentType.Application.Json, HttpStatusCode.NotFound)
                is IncidentOperationResult.ValidationError -> call.respondText(errorJson("VALIDATION_ERROR", res.message), ContentType.Application.Json, HttpStatusCode.BadRequest)
            }
        }

        // 45. Apply or Release Result Hold
        post("/api/incidents/{incidentId}/hold") {
            val session = authenticate(call, sessionManager) ?: return@post
            val incidentId = call.parameters["incidentId"] ?: ""

            val body = call.receiveText()
            val reqJson = try { JSONObject(body) } catch (e: Exception) {
                call.respondText(errorJson("MALFORMED_JSON", "Invalid JSON payload"), ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            val actionType = reqJson.optString("action", "HOLD").trim()
            val reason = reqJson.optString("reason", "").trim()

            if (actionType == "HOLD") {
                when (val res = incidentReviewService.applyResultHold(incidentId, session, reason)) {
                    is IncidentOperationResult.Success -> {
                        call.respondText(JSONObject().apply {
                            put("status", "SUCCESS")
                            put("actionType", res.value.actionType)
                            put("executionStatus", res.value.executionStatus)
                            put("resultStatus", "HELD_PENDING_REVIEW")
                            put("auditEventId", res.auditEventId)
                        }.toString(), ContentType.Application.Json, HttpStatusCode.OK)
                    }
                    is IncidentOperationResult.PermissionDenied -> call.respondText(errorJson("FORBIDDEN", res.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                    is IncidentOperationResult.NotFound -> call.respondText(errorJson("NOT_FOUND", "${res.entity} not found"), ContentType.Application.Json, HttpStatusCode.NotFound)
                    is IncidentOperationResult.ValidationError -> call.respondText(errorJson("VALIDATION_ERROR", res.message), ContentType.Application.Json, HttpStatusCode.BadRequest)
                    is IncidentOperationResult.InvalidTransition -> call.respondText(errorJson("INVALID_TRANSITION", res.reason), ContentType.Application.Json, HttpStatusCode.BadRequest)
                }
            } else if (actionType == "RELEASE_HOLD") {
                when (val res = incidentReviewService.releaseResultHold(incidentId, session, reason)) {
                    is IncidentOperationResult.Success -> {
                        call.respondText(JSONObject().apply {
                            put("status", "SUCCESS")
                            put("actionType", res.value.actionType)
                            put("executionStatus", res.value.executionStatus)
                            put("resultStatus", "GRADED")
                            put("auditEventId", res.auditEventId)
                        }.toString(), ContentType.Application.Json, HttpStatusCode.OK)
                    }
                    is IncidentOperationResult.PermissionDenied -> call.respondText(errorJson("FORBIDDEN", res.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                    is IncidentOperationResult.NotFound -> call.respondText(errorJson("NOT_FOUND", "${res.entity} not found"), ContentType.Application.Json, HttpStatusCode.NotFound)
                    is IncidentOperationResult.ValidationError -> call.respondText(errorJson("VALIDATION_ERROR", res.message), ContentType.Application.Json, HttpStatusCode.BadRequest)
                    is IncidentOperationResult.InvalidTransition -> call.respondText(errorJson("INVALID_TRANSITION", res.reason), ContentType.Application.Json, HttpStatusCode.BadRequest)
                }
            } else {
                call.respondText(errorJson("INVALID_ACTION", "Action must be HOLD or RELEASE_HOLD"), ContentType.Application.Json, HttpStatusCode.BadRequest)
            }
        }

        // 46. Cancel Exam Attempt
        post("/api/incidents/{incidentId}/cancel") {
            val session = authenticate(call, sessionManager) ?: return@post
            val incidentId = call.parameters["incidentId"] ?: ""

            val body = call.receiveText()
            val reqJson = try { JSONObject(body) } catch (e: Exception) {
                call.respondText(errorJson("MALFORMED_JSON", "Invalid JSON payload"), ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }
            val reason = reqJson.optString("reason", "").trim()

            when (val res = incidentReviewService.cancelAttempt(incidentId, session, reason)) {
                is IncidentOperationResult.Success -> {
                    call.respondText(JSONObject().apply {
                        put("status", "SUCCESS")
                        put("actionType", res.value.actionType)
                        put("executionStatus", res.value.executionStatus)
                        put("attemptStatus", "CANCELLED")
                        put("resultStatus", "WITHHELD")
                        put("auditEventId", res.auditEventId)
                    }.toString(), ContentType.Application.Json, HttpStatusCode.OK)
                }
                is IncidentOperationResult.PermissionDenied -> call.respondText(errorJson("FORBIDDEN", res.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                is IncidentOperationResult.NotFound -> call.respondText(errorJson("NOT_FOUND", "${res.entity} not found"), ContentType.Application.Json, HttpStatusCode.NotFound)
                is IncidentOperationResult.ValidationError -> call.respondText(errorJson("VALIDATION_ERROR", res.message), ContentType.Application.Json, HttpStatusCode.BadRequest)
                is IncidentOperationResult.InvalidTransition -> call.respondText(errorJson("INVALID_TRANSITION", res.reason), ContentType.Application.Json, HttpStatusCode.BadRequest)
            }
        }

        // 47. Reinstate Exam Attempt
        post("/api/incidents/{incidentId}/reinstate") {
            val session = authenticate(call, sessionManager) ?: return@post
            val incidentId = call.parameters["incidentId"] ?: ""

            val body = call.receiveText()
            val reqJson = try { JSONObject(body) } catch (e: Exception) {
                call.respondText(errorJson("MALFORMED_JSON", "Invalid JSON payload"), ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }
            val reason = reqJson.optString("reason", "").trim()

            when (val res = incidentReviewService.reinstateAttempt(incidentId, session, reason)) {
                is IncidentOperationResult.Success -> {
                    call.respondText(JSONObject().apply {
                        put("status", "SUCCESS")
                        put("actionType", res.value.actionType)
                        put("executionStatus", res.value.executionStatus)
                        put("attemptStatus", "RESTORED")
                        put("auditEventId", res.auditEventId)
                    }.toString(), ContentType.Application.Json, HttpStatusCode.OK)
                }
                is IncidentOperationResult.PermissionDenied -> call.respondText(errorJson("FORBIDDEN", res.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                is IncidentOperationResult.NotFound -> call.respondText(errorJson("NOT_FOUND", "${res.entity} not found"), ContentType.Application.Json, HttpStatusCode.NotFound)
                is IncidentOperationResult.ValidationError -> call.respondText(errorJson("VALIDATION_ERROR", res.message), ContentType.Application.Json, HttpStatusCode.BadRequest)
                is IncidentOperationResult.InvalidTransition -> call.respondText(errorJson("INVALID_TRANSITION", res.reason), ContentType.Application.Json, HttpStatusCode.BadRequest)
            }
        }

        // 48. Submit Formal Student Appeal
        post("/api/incidents/{incidentId}/appeals") {
            val session = authenticate(call, sessionManager) ?: return@post
            val incidentId = call.parameters["incidentId"] ?: ""

            val body = call.receiveText()
            val reqJson = try { JSONObject(body) } catch (e: Exception) {
                call.respondText(errorJson("MALFORMED_JSON", "Invalid JSON payload"), ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            val challengedAction = reqJson.optString("challengedAction", "RESULT_HOLD").trim()
            val appealReason = reqJson.optString("appealReason", "").trim()
            val evidenceReferences = if (reqJson.has("evidenceReferences") && !reqJson.isNull("evidenceReferences")) reqJson.getString("evidenceReferences") else null

            when (val res = incidentReviewService.submitAppeal(incidentId, session, challengedAction, appealReason, evidenceReferences)) {
                is IncidentOperationResult.Success -> {
                    call.respondText(JSONObject().apply {
                        put("status", "SUCCESS")
                        put("appealId", res.value.appealId)
                        put("appealStatus", res.value.appealStatus)
                        put("submittedAt", res.value.submittedAt)
                        put("auditEventId", res.auditEventId)
                    }.toString(), ContentType.Application.Json, HttpStatusCode.Created)
                }
                is IncidentOperationResult.PermissionDenied -> call.respondText(errorJson("FORBIDDEN", res.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                is IncidentOperationResult.NotFound -> call.respondText(errorJson("NOT_FOUND", "${res.entity} not found"), ContentType.Application.Json, HttpStatusCode.NotFound)
                is IncidentOperationResult.ValidationError -> call.respondText(errorJson("VALIDATION_ERROR", res.message), ContentType.Application.Json, HttpStatusCode.BadRequest)
                is IncidentOperationResult.InvalidTransition -> call.respondText(errorJson("INVALID_TRANSITION", res.reason), ContentType.Application.Json, HttpStatusCode.BadRequest)
            }
        }

        // 49. Adjudicate Formal Student Appeal (Independent Reviewer)
        post("/api/incidents/{incidentId}/appeals/{appealId}/decide") {
            val session = authenticate(call, sessionManager) ?: return@post
            val appealId = call.parameters["appealId"] ?: ""

            val body = call.receiveText()
            val reqJson = try { JSONObject(body) } catch (e: Exception) {
                call.respondText(errorJson("MALFORMED_JSON", "Invalid JSON payload"), ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            val decision = reqJson.optString("decision", "").trim()
            val outcomeReason = reqJson.optString("outcomeReason", "").trim()

            when (val res = incidentReviewService.adjudicateAppeal(appealId, session, decision, outcomeReason)) {
                is IncidentOperationResult.Success -> {
                    call.respondText(JSONObject().apply {
                        put("status", "SUCCESS")
                        put("appealId", res.value.appealId)
                        put("appealStatus", res.value.appealStatus)
                        put("reviewerDecision", res.value.reviewerDecision)
                        put("resolvedAt", res.value.resolvedAt ?: JSONObject.NULL)
                        put("auditEventId", res.auditEventId)
                    }.toString(), ContentType.Application.Json, HttpStatusCode.OK)
                }
                is IncidentOperationResult.PermissionDenied -> call.respondText(errorJson("FORBIDDEN", res.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                is IncidentOperationResult.NotFound -> call.respondText(errorJson("NOT_FOUND", "${res.entity} not found"), ContentType.Application.Json, HttpStatusCode.NotFound)
                is IncidentOperationResult.ValidationError -> call.respondText(errorJson("VALIDATION_ERROR", res.message), ContentType.Application.Json, HttpStatusCode.BadRequest)
                is IncidentOperationResult.InvalidTransition -> call.respondText(errorJson("INVALID_TRANSITION", res.reason), ContentType.Application.Json, HttpStatusCode.BadRequest)
            }
        }

        // 50. Get Chronological Incident Audit Timeline
        get("/api/incidents/{incidentId}/audit") {
            val session = authenticate(call, sessionManager) ?: return@get
            val incidentId = call.parameters["incidentId"] ?: ""

            val incident = database.incidentDao().getIncidentById(incidentId)
            if (incident == null) {
                call.respondText(errorJson("NOT_FOUND", "Incident not found"), ContentType.Application.Json, HttpStatusCode.NotFound)
                return@get
            }

            val authCheck = PermissionService.authorize(session, Permission.AUDIT_READ, session.instituteScope)
            if (authCheck is AuthorizationResult.Denied) {
                recordPermissionDenial(auditEngine, session, Permission.AUDIT_READ, authCheck.reason)
                call.respondText(errorJson("FORBIDDEN", authCheck.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@get
            }

            val auditEvents = database.auditEventDao().getAuditEventsForAggregate(incidentId)
            val timeline = JSONArray()
            for (aud in auditEvents) {
                timeline.put(JSONObject().apply {
                    put("auditEventId", aud.auditEventId)
                    put("actorId", aud.actorId)
                    put("actorRole", aud.actorRole)
                    put("actionType", aud.actionType)
                    put("previousState", aud.previousState ?: JSONObject.NULL)
                    put("newState", aud.newState ?: JSONObject.NULL)
                    put("reason", aud.reason ?: JSONObject.NULL)
                    put("operationResult", aud.operationResult)
                    put("currentHash", aud.currentHash)
                    put("timestamp", aud.eventTimestamp)
                })
            }

            call.respondText(timeline.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }

        // =========================================================================
        // STEP 10: EXAMINATION INTELLIGENCE DASHBOARD & ANALYTICS
        // =========================================================================
        val analyticsService = AnalyticsService(database)

        // 51. Overview Analytics
        get("/api/analytics/overview") {
            val session = authenticate(call, sessionManager) ?: return@get

            val authCheck = PermissionService.authorize(session, Permission.ANALYTICS_OVERVIEW_READ, resourceInstituteId = session.instituteScope)
            if (authCheck is AuthorizationResult.Denied) {
                recordPermissionDenial(auditEngine, session, Permission.ANALYTICS_OVERVIEW_READ, authCheck.reason)
                call.respondText(errorJson("FORBIDDEN", authCheck.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@get
            }

            val data = analyticsService.getOverviewAnalytics(session.instituteScope)
            val json = JSONObject().apply {
                put("totalExams", data.totalExams)
                put("publishedExams", data.publishedExams)
                put("inProgressExams", data.inProgressExams)
                put("completedExams", data.completedExams)
                put("totalAssignedStudents", data.totalAssignedStudents)
                put("activeAttempts", data.activeAttempts)
                put("acceptedSubmissions", data.acceptedSubmissions)
                put("pendingSubmissions", data.pendingSubmissions)
                put("gradingCompletionPercentage", data.gradingCompletionPercentage)
                put("publishedResultsCount", data.publishedResultsCount)
                put("resultsAwaitingPublicationCount", data.resultsAwaitingPublicationCount)
                put("resultsHeldCount", data.resultsHeldCount)
                put("withheldResultsCount", data.withheldResultsCount)
                put("outstandingIncidentsCount", data.outstandingIncidentsCount)
                put("pendingAppealsCount", data.pendingAppealsCount)
                put("generatedAt", data.generatedAt)
            }
            call.respondText(json.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }

        // 52. Exam Analytics Summary
        get("/api/analytics/exams/{examId}/summary") {
            val session = authenticate(call, sessionManager) ?: return@get
            val examId = call.parameters["examId"] ?: ""

            val authCheck = PermissionService.authorize(session, Permission.ANALYTICS_EXAM_READ, resourceExamId = examId)
            if (authCheck is AuthorizationResult.Denied) {
                recordPermissionDenial(auditEngine, session, Permission.ANALYTICS_EXAM_READ, authCheck.reason)
                call.respondText(errorJson("FORBIDDEN", authCheck.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@get
            }

            val summary = analyticsService.getExamAnalyticsSummary(examId)
            if (summary == null) {
                call.respondText(errorJson("NOT_FOUND", "Exam not found"), ContentType.Application.Json, HttpStatusCode.NotFound)
                return@get
            }

            val json = JSONObject().apply {
                put("examId", summary.examId)
                put("examTitle", summary.examTitle)
                put("durationMinutes", summary.durationMinutes)
                put("totalMarks", summary.totalMarks)
                put("publishedAt", summary.publishedAt ?: JSONObject.NULL)
                put("totalAssignedStudents", summary.totalAssignedStudents)
                put("startedAttemptsCount", summary.startedAttemptsCount)
                put("inProgressAttemptsCount", summary.inProgressAttemptsCount)
                put("submittedAttemptsCount", summary.submittedAttemptsCount)
                put("cancelledAttemptsCount", summary.cancelledAttemptsCount)
                put("timedOutAttemptsCount", summary.timedOutAttemptsCount)
                put("evaluatedResultsCount", summary.evaluatedResultsCount)
                put("publishedResultsCount", summary.publishedResultsCount)
                put("heldResultsCount", summary.heldResultsCount)
                put("meanScore", summary.meanScore)
                put("medianScore", summary.medianScore)
                put("highestScore", summary.highestScore)
                put("lowestScore", summary.lowestScore)
                put("maxPossibleScore", summary.maxPossibleScore)
                put("overallAccuracyRate", summary.overallAccuracyRate)
                put("averageDurationMinutes", summary.averageDurationMinutes ?: JSONObject.NULL)
                put("integrityEventsCount", summary.integrityEventsCount)
                put("flaggedAttemptsCount", summary.flaggedAttemptsCount)
                put("incidentCasesCount", summary.incidentCasesCount)
                put("pendingIncidentsCount", summary.pendingIncidentsCount)
                put("appealsCount", summary.appealsCount)
                put("pendingAppealsCount", summary.pendingAppealsCount)
                put("scoreDistribution", JSONArray().apply {
                    for (b in summary.scoreDistribution) {
                        put(JSONObject().apply {
                            put("rangeLabel", b.rangeLabel)
                            put("count", b.count)
                            put("percentageOfStudents", b.percentageOfStudents)
                        })
                    }
                })
            }
            call.respondText(json.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }

        // 53. Exam Student Performance List
        get("/api/analytics/exams/{examId}/students") {
            val session = authenticate(call, sessionManager) ?: return@get
            val examId = call.parameters["examId"] ?: ""

            val authCheck = PermissionService.authorize(session, Permission.ANALYTICS_STUDENT_READ, resourceExamId = examId)
            if (authCheck is AuthorizationResult.Denied) {
                recordPermissionDenial(auditEngine, session, Permission.ANALYTICS_STUDENT_READ, authCheck.reason)
                call.respondText(errorJson("FORBIDDEN", authCheck.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@get
            }

            val list = analyticsService.getStudentPerformanceList(examId)
            val jsonArray = JSONArray()
            for (s in list) {
                jsonArray.put(JSONObject().apply {
                    put("studentId", s.studentId)
                    put("studentDisplayName", s.studentDisplayName)
                    put("attemptId", s.attemptId)
                    put("attemptStatus", s.attemptStatus)
                    put("resultStatus", s.resultStatus)
                    put("score", s.score ?: JSONObject.NULL)
                    put("maxPossibleMarks", s.maxPossibleMarks ?: JSONObject.NULL)
                    put("percentage", s.percentage ?: JSONObject.NULL)
                    put("correctCount", s.correctCount ?: JSONObject.NULL)
                    put("incorrectCount", s.incorrectCount ?: JSONObject.NULL)
                    put("unansweredCount", s.unansweredCount ?: JSONObject.NULL)
                    put("partiallyCorrectCount", s.partiallyCorrectCount ?: JSONObject.NULL)
                    put("accuracyPercentage", s.accuracyPercentage ?: JSONObject.NULL)
                    put("rank", s.rank ?: JSONObject.NULL)
                    put("isTiedRank", s.isTiedRank)
                    put("holdReason", s.holdReason ?: JSONObject.NULL)
                    put("startedAt", s.startedAt)
                    put("submittedAt", s.submittedAt ?: JSONObject.NULL)
                    put("durationMinutes", s.durationMinutes ?: JSONObject.NULL)
                    put("incidentCount", s.incidentCount)
                    put("hasPendingAppeals", s.hasPendingAppeals)
                })
            }
            call.respondText(jsonArray.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }

        // 54. Exam Question Analytics
        get("/api/analytics/exams/{examId}/questions") {
            val session = authenticate(call, sessionManager) ?: return@get
            val examId = call.parameters["examId"] ?: ""

            val authCheck = PermissionService.authorize(session, Permission.ANALYTICS_EXAM_READ, resourceExamId = examId)
            if (authCheck is AuthorizationResult.Denied) {
                recordPermissionDenial(auditEngine, session, Permission.ANALYTICS_EXAM_READ, authCheck.reason)
                call.respondText(errorJson("FORBIDDEN", authCheck.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@get
            }

            val questions = analyticsService.getQuestionAnalyticsList(examId)
            val jsonArray = JSONArray()
            for (q in questions) {
                jsonArray.put(JSONObject().apply {
                    put("questionId", q.questionId)
                    put("questionNumber", q.questionNumber)
                    put("sectionId", q.sectionId ?: JSONObject.NULL)
                    put("sectionTitle", q.sectionTitle)
                    put("questionType", q.questionType)
                    put("questionText", q.questionText)
                    put("maxMarks", q.maxMarks)
                    put("eligibleCandidatesDenominator", q.eligibleCandidatesDenominator)
                    put("attemptedCount", q.attemptedCount)
                    put("unansweredCount", q.unansweredCount)
                    put("correctCount", q.correctCount)
                    put("incorrectCount", q.incorrectCount)
                    put("partiallyCorrectCount", q.partiallyCorrectCount)
                    put("attemptRate", q.attemptRate)
                    put("correctnessRate", q.correctnessRate)
                    put("averageMarksAwarded", q.averageMarksAwarded)
                    put("difficultyCategory", q.difficultyCategory)
                    put("recordedTimeNotice", q.recordedTimeNotice)
                    put("optionDistributions", JSONArray().apply {
                        for (opt in q.optionDistributions) {
                            put(JSONObject().apply {
                                put("optionId", opt.optionId)
                                put("label", opt.label)
                                put("selectionCount", opt.selectionCount)
                                put("selectionPercentage", opt.selectionPercentage)
                            })
                        }
                    })
                })
            }
            call.respondText(jsonArray.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }

        // 55. Exam Section Analytics
        get("/api/analytics/exams/{examId}/sections") {
            val session = authenticate(call, sessionManager) ?: return@get
            val examId = call.parameters["examId"] ?: ""

            val authCheck = PermissionService.authorize(session, Permission.ANALYTICS_EXAM_READ, resourceExamId = examId)
            if (authCheck is AuthorizationResult.Denied) {
                recordPermissionDenial(auditEngine, session, Permission.ANALYTICS_EXAM_READ, authCheck.reason)
                call.respondText(errorJson("FORBIDDEN", authCheck.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@get
            }

            val sections = analyticsService.getSectionAnalyticsList(examId)
            val jsonArray = JSONArray()
            for (sec in sections) {
                jsonArray.put(JSONObject().apply {
                    put("sectionId", sec.sectionId)
                    put("title", sec.title)
                    put("sectionOrder", sec.sectionOrder)
                    put("questionCount", sec.questionCount)
                    put("totalMarks", sec.totalMarks)
                    put("averageScore", sec.averageScore)
                    put("accuracyPercentage", sec.accuracyPercentage)
                    put("attemptRate", sec.attemptRate)
                    put("highestScore", sec.highestScore)
                    put("lowestScore", sec.lowestScore)
                })
            }
            call.respondText(jsonArray.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }

        // 56. Learning Gaps and Recommendations
        get("/api/analytics/exams/{examId}/learning-gaps") {
            val session = authenticate(call, sessionManager) ?: return@get
            val examId = call.parameters["examId"] ?: ""

            val authCheck = PermissionService.authorize(session, Permission.ANALYTICS_EXAM_READ, resourceExamId = examId)
            if (authCheck is AuthorizationResult.Denied) {
                recordPermissionDenial(auditEngine, session, Permission.ANALYTICS_EXAM_READ, authCheck.reason)
                call.respondText(errorJson("FORBIDDEN", authCheck.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@get
            }

            val gaps = analyticsService.getLearningGaps(examId)
            val json = JSONObject().apply {
                put("curriculumTagsAvailable", gaps.curriculumTagsAvailable)
                put("disclosureNotice", gaps.disclosureNotice)
                put("weakestSections", JSONArray(gaps.weakestSections))
                put("highIncorrectQuestions", JSONArray().apply {
                    for (q in gaps.highIncorrectQuestions) {
                        put(JSONObject().apply {
                            put("questionNumber", q.questionNumber)
                            put("sectionTitle", q.sectionTitle)
                            put("questionType", q.questionType)
                            put("correctnessRate", q.correctnessRate)
                            put("incorrectRate", q.incorrectRate)
                        })
                    }
                })
                put("explainableRecommendations", JSONArray(gaps.explainableRecommendations))
            }
            call.respondText(json.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }

        // 57. Integrity Analytics Summary
        get("/api/analytics/exams/{examId}/integrity") {
            val session = authenticate(call, sessionManager) ?: return@get
            val examId = call.parameters["examId"] ?: ""

            val authCheck = PermissionService.authorize(session, Permission.ANALYTICS_INTEGRITY_READ, resourceExamId = examId)
            if (authCheck is AuthorizationResult.Denied) {
                recordPermissionDenial(auditEngine, session, Permission.ANALYTICS_INTEGRITY_READ, authCheck.reason)
                call.respondText(errorJson("FORBIDDEN", authCheck.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@get
            }

            val integrity = analyticsService.getIntegrityAnalytics(examId)
            val json = JSONObject().apply {
                put("examId", integrity.examId)
                put("totalEvents", integrity.totalEvents)
                put("uniqueFlaggedAttempts", integrity.uniqueFlaggedAttempts)
                put("appNameUnavailableCount", integrity.appNameUnavailableCount)
                put("totalIncidentCases", integrity.totalIncidentCases)
                put("confirmedMisconductCount", integrity.confirmedMisconductCount)
                put("dismissedIncidentsCount", integrity.dismissedIncidentsCount)
                put("pendingReviewIncidentsCount", integrity.pendingReviewIncidentsCount)
                put("heldResultsCount", integrity.heldResultsCount)
                put("cancelledAttemptsCount", integrity.cancelledAttemptsCount)
                put("reinstatedAttemptsCount", integrity.reinstatedAttemptsCount)
                put("totalAppealsCount", integrity.totalAppealsCount)
                put("pendingAppealsCount", integrity.pendingAppealsCount)
                put("eventsByCategory", JSONObject(integrity.eventsByCategory as Map<*, *>))
                put("incidentsByStatus", JSONObject(integrity.incidentsByStatus as Map<*, *>))
            }
            call.respondText(json.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }

        // 58. Individual Student Examination Profile
        get("/api/analytics/exams/{examId}/students/{studentId}/profile") {
            val session = authenticate(call, sessionManager) ?: return@get
            val examId = call.parameters["examId"] ?: ""
            val studentId = call.parameters["studentId"] ?: ""

            if (session.role == "STUDENT") {
                if (session.userId != studentId) {
                    recordPermissionDenial(auditEngine, session, Permission.ANALYTICS_STUDENT_READ, "Candidates cannot view other students' profiles")
                    call.respondText(errorJson("FORBIDDEN", "Candidates cannot view other students' profiles"), ContentType.Application.Json, HttpStatusCode.Forbidden)
                    return@get
                }
            } else {
                val authCheck = PermissionService.authorize(session, Permission.ANALYTICS_STUDENT_READ, resourceExamId = examId, resourceStudentId = studentId)
                if (authCheck is AuthorizationResult.Denied) {
                    recordPermissionDenial(auditEngine, session, Permission.ANALYTICS_STUDENT_READ, authCheck.reason)
                    call.respondText(errorJson("FORBIDDEN", authCheck.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                    return@get
                }
            }

            val profile = analyticsService.getStudentExamProfile(examId, studentId, session)
            if (profile == null) {
                call.respondText(errorJson("NOT_FOUND", "Student examination profile not found"), ContentType.Application.Json, HttpStatusCode.NotFound)
                return@get
            }

            val json = JSONObject().apply {
                put("studentId", profile.studentId)
                put("studentDisplayName", profile.studentDisplayName)
                put("examId", profile.examId)
                put("examTitle", profile.examTitle)
                put("attemptId", profile.attemptId)
                put("attemptStatus", profile.attemptStatus)
                put("resultStatus", profile.resultStatus)
                put("startedAt", profile.startedAt)
                put("submittedAt", profile.submittedAt ?: JSONObject.NULL)
                put("durationMinutes", profile.durationMinutes ?: JSONObject.NULL)
                put("totalScaledScore", profile.totalScaledScore ?: JSONObject.NULL)
                put("maxPossibleScore", profile.maxPossibleScore ?: JSONObject.NULL)
                put("percentage", profile.percentage ?: JSONObject.NULL)
                put("accuracyPercentage", profile.accuracyPercentage ?: JSONObject.NULL)
                put("correctCount", profile.correctCount ?: JSONObject.NULL)
                put("incorrectCount", profile.incorrectCount ?: JSONObject.NULL)
                put("unansweredCount", profile.unansweredCount ?: JSONObject.NULL)
                put("holdReason", profile.holdReason ?: JSONObject.NULL)
                put("localSaveCount", profile.localSaveCount)
                put("submissionReceiptHash", profile.submissionReceiptHash ?: JSONObject.NULL)
                put("questions", JSONArray().apply {
                    for (q in profile.questions) {
                        put(JSONObject().apply {
                            put("questionId", q.questionId)
                            put("questionNumber", q.questionNumber)
                            put("sectionTitle", q.sectionTitle)
                            put("questionType", q.questionType)
                            put("questionText", q.questionText)
                            put("optionsJson", q.optionsJson)
                            put("submittedAnswer", q.submittedAnswer ?: JSONObject.NULL)
                            put("answerClassification", q.answerClassification)
                            put("marksAwarded", q.marksAwarded)
                            put("maxMarks", q.maxMarks)
                            put("explanationCode", q.explanationCode)
                            put("revisionCount", q.revisionCount)
                            put("answerSavedAt", q.answerSavedAt ?: JSONObject.NULL)
                            put("timeUsedNotice", q.timeUsedNotice)
                            put("correctAnswerSnapshot", q.correctAnswerSnapshot ?: JSONObject.NULL)
                        })
                    }
                })
                put("integrityEvents", JSONArray().apply {
                    for (evt in profile.integrityEvents) {
                        put(JSONObject().apply {
                            put("eventId", evt.eventId)
                            put("eventCategory", evt.eventCategory)
                            put("deviceReportedAt", evt.deviceReportedAt)
                            put("appPackageName", evt.appPackageName ?: JSONObject.NULL)
                            put("displayedAppName", evt.displayedAppName ?: JSONObject.NULL)
                        })
                    }
                })
                put("incidentCases", JSONArray().apply {
                    for (inc in profile.incidentCases) {
                        put(JSONObject().apply {
                            put("incidentId", inc.incidentId)
                            put("category", inc.category)
                            put("incidentStatus", inc.incidentStatus)
                            put("evidenceAssessment", inc.evidenceAssessment)
                            put("decision", inc.decision)
                        })
                    }
                })
                put("appeals", JSONArray().apply {
                    for (appl in profile.appeals) {
                        put(JSONObject().apply {
                            put("appealId", appl.appealId)
                            put("challengedAction", appl.challengedAction)
                            put("appealStatus", appl.appealStatus)
                            put("appealReason", appl.appealReason)
                            put("outcomeReason", appl.outcomeReason ?: JSONObject.NULL)
                        })
                    }
                })
            }
            call.respondText(json.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }

        // 59. Export Report CSV
        get("/api/analytics/exams/{examId}/reports/{reportType}/csv") {
            val session = authenticate(call, sessionManager) ?: return@get
            val examId = call.parameters["examId"] ?: ""
            val reportType = call.parameters["reportType"] ?: "EXAM_SUMMARY"

            val authCheck = PermissionService.authorize(session, Permission.REPORT_EXPORT, resourceExamId = examId)
            if (authCheck is AuthorizationResult.Denied) {
                recordPermissionDenial(auditEngine, session, Permission.REPORT_EXPORT, authCheck.reason)
                call.respondText(errorJson("FORBIDDEN", authCheck.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@get
            }

            val csv = analyticsService.generateReportCsv(examId, reportType)

            auditEngine.recordAuditEvent(
                auditEventId = "aud_csv_${System.currentTimeMillis()}",
                actorId = session.userId,
                actorRole = session.role,
                actionType = "REPORT_EXPORT_CSV",
                aggregateType = "EXAM",
                aggregateId = examId,
                reason = "Report type: $reportType",
                operationResult = "SUCCESS"
            )

            call.response.headers.append("Content-Disposition", "attachment; filename=\"${examId}_${reportType.lowercase()}.csv\"")
            call.respondText(csv, ContentType("text", "csv"), HttpStatusCode.OK)
        }

        // 60. Live Examination Monitor Stats
        get("/api/analytics/live/{examId}") {
            val session = authenticate(call, sessionManager) ?: return@get
            val examId = call.parameters["examId"] ?: ""

            val authCheck = PermissionService.authorize(session, Permission.ANALYTICS_EXAM_READ, resourceExamId = examId)
            if (authCheck is AuthorizationResult.Denied) {
                recordPermissionDenial(auditEngine, session, Permission.ANALYTICS_EXAM_READ, authCheck.reason)
                call.respondText(errorJson("FORBIDDEN", authCheck.reason), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@get
            }

            val exam = database.examDao().getExamById(examId)
            if (exam == null) {
                call.respondText(errorJson("NOT_FOUND", "Exam not found"), ContentType.Application.Json, HttpStatusCode.NotFound)
                return@get
            }

            val attempts = database.examAttemptDao().getAttemptsByExam(examId)
            val events = database.integrityEventDao().getEventsByExam(examId)
            val users = database.userDao().getAllUsers().associateBy { it.userId }

            val totalAssigned = attempts.map { it.studentId }.distinct().size
            val activeAttempts = attempts.filter { it.attemptStatus == "IN_PROGRESS" }
            val submittedAttempts = attempts.filter { it.attemptStatus == "SUBMITTED" }
            val timedOutAttempts = attempts.filter { it.attemptStatus == "TIMED_OUT" }
            val cancelledAttempts = attempts.filter { it.attemptStatus == "CANCELLED" }

            val recentEvents = events.take(15)

            val studentList = attempts.map { att ->
                val usr = users[att.studentId]
                val attEvents = events.filter { it.attemptId == att.attemptId }
                val lastActivity = attEvents.maxByOrNull { it.deviceReportedAt }?.deviceReportedAt
                    ?: att.updatedAt
                val isRecent = try {
                    val actTime = Instant.parse(lastActivity)
                    java.time.Duration.between(actTime, Instant.now()).toMinutes() < 5
                } catch (e: Exception) { false }

                JSONObject().apply {
                    put("studentId", att.studentId)
                    put("displayName", usr?.displayName ?: att.studentId)
                    put("attemptId", att.attemptId)
                    put("attemptStatus", att.attemptStatus)
                    put("resultStatus", att.resultStatus)
                    put("startedAt", att.startedAt)
                    put("submittedAt", att.submittedAt ?: JSONObject.NULL)
                    put("lastActivityAt", lastActivity)
                    put("connectionState", if (att.attemptStatus == "SUBMITTED") "SUBMITTED" else if (isRecent) "CONNECTED" else "LAST_KNOWN_CONNECTED")
                    put("integrityAlertCount", attEvents.size)
                }
            }

            val json = JSONObject().apply {
                put("examId", exam.examId)
                put("title", exam.title)
                put("durationMinutes", (exam.durationSeconds / 60).toInt())
                put("totalAssignedCandidates", totalAssigned)
                put("activeAttemptsCount", activeAttempts.size)
                put("submittedCount", submittedAttempts.size)
                put("timedOutCount", timedOutAttempts.size)
                put("cancelledCount", cancelledAttempts.size)
                put("recentIntegrityAlertsCount", events.size)
                put("candidates", JSONArray(studentList))
                put("recentAlerts", JSONArray().apply {
                    for (evt in recentEvents) {
                        put(JSONObject().apply {
                            put("eventId", evt.eventId)
                            put("studentId", evt.studentId)
                            put("attemptId", evt.attemptId)
                            put("category", evt.eventCategory)
                            put("timestamp", evt.deviceReportedAt)
                            put("displayedAppName", evt.displayedAppName ?: JSONObject.NULL)
                        })
                    }
                })
            }
            call.respondText(json.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }

        // 53. Student: Versioned Batch Synchronization Ingestion (STEP 11 MULTI-DEVICE RELIABILITY)
        post("/api/sync/events") {
            val session = authenticate(call, sessionManager) ?: return@post
            val body = call.receiveText()
            val reqJson = try {
                JSONObject(body)
            } catch (e: Exception) {
                call.respondText(errorJson("MALFORMED_JSON", "Invalid JSON payload"), ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            val eventsArray = reqJson.optJSONArray("events")
            if (eventsArray == null) {
                call.respondText(errorJson("MISSING_EVENTS", "Missing 'events' array in payload"), ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            if (eventsArray.length() > 50) {
                call.respondText(errorJson("BATCH_SIZE_EXCEEDED", "Maximum batch size is 50 events"), ContentType.Application.Json, HttpStatusCode.PayloadTooLarge)
                return@post
            }

            val envelopeList = mutableListOf<SyncEventEnvelope>()
            for (i in 0 until eventsArray.length()) {
                val item = eventsArray.getJSONObject(i)
                envelopeList.add(
                    SyncEventEnvelope(
                        eventId = item.getString("eventId"),
                        schemaVersion = item.optInt("schemaVersion", 1),
                        eventType = item.getString("eventType"),
                        instituteId = item.optString("instituteId", session.instituteScope),
                        examId = item.getString("examId"),
                        attemptId = item.getString("attemptId"),
                        studentId = item.optString("studentId", session.userId),
                        sourceDeviceId = item.optString("sourceDeviceId", "unknown"),
                        sequenceNumber = item.optLong("sequenceNumber", 1L),
                        aggregateId = item.optString("aggregateId", item.getString("attemptId")),
                        aggregateVersion = item.optInt("aggregateVersion", 1),
                        baseServerVersion = if (item.has("baseServerVersion") && !item.isNull("baseServerVersion")) item.getInt("baseServerVersion") else null,
                        clientWallClockTimestamp = item.optString("clientWallClockTimestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.now())),
                        clientMonotonicTimestamp = if (item.has("clientMonotonicTimestamp") && !item.isNull("clientMonotonicTimestamp")) item.getLong("clientMonotonicTimestamp") else null,
                        payload = item.optString("payload", "{}"),
                        payloadHash = item.getString("payloadHash"),
                        idempotencyKey = if (item.has("idempotencyKey") && !item.isNull("idempotencyKey")) item.getString("idempotencyKey") else null,
                        deliveryAttempt = item.optInt("deliveryAttempt", 1)
                    )
                )
            }

            val batchResult = syncService.processBatch(
                events = envelopeList,
                authenticatedStudentId = session.userId,
                actorRole = session.role
            )

            val respObj = JSONObject().apply {
                put("status", batchResult.status)
                put("totalReceived", batchResult.totalReceived)
                put("acceptedCount", batchResult.acceptedCount)
                put("alreadyProcessedCount", batchResult.alreadyProcessedCount)
                put("rejectedCount", batchResult.rejectedCount)
                put("conflictCount", batchResult.conflictCount)
                val resultsArr = JSONArray()
                for (r in batchResult.results) {
                    val rObj = JSONObject().apply {
                        put("eventId", r.eventId)
                        put("status", r.status)
                        put("receiptId", r.receiptId ?: JSONObject.NULL)
                        put("resultingVersion", r.resultingVersion)
                        put("rejectionReason", r.rejectionReason ?: JSONObject.NULL)
                        put("serverReceivedAt", r.serverReceivedAt)
                        put("receiptHash", r.receiptHash ?: JSONObject.NULL)
                    }
                    resultsArr.put(rObj)
                }
                put("results", resultsArr)
            }

            val httpStatus = when (batchResult.status) {
                "FAILED" -> HttpStatusCode.BadRequest
                "PARTIAL_SUCCESS_WITH_CONFLICTS" -> HttpStatusCode.Conflict
                else -> HttpStatusCode.OK
            }

            call.respondText(respObj.toString(), ContentType.Application.Json, httpStatus)
        }

        // 54. Student: Authoritative Reconcile Query (STEP 11 CRASH & RECONNECT RECOVERY)
        post("/api/sync/reconcile") {
            val session = authenticate(call, sessionManager) ?: return@post
            val body = call.receiveText()
            val reqJson = try { JSONObject(body) } catch (e: Exception) { JSONObject() }
            val attemptId = reqJson.optString("attemptId", "").trim()

            if (attemptId.isBlank()) {
                call.respondText(errorJson("MISSING_ATTEMPT_ID", "attemptId is required"), ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            val state = syncService.reconcileAttempt(attemptId, session.userId)
            if (state == null) {
                call.respondText(errorJson("NOT_FOUND", "Attempt '$attemptId' not found or access denied"), ContentType.Application.Json, HttpStatusCode.NotFound)
                return@post
            }

            val lastAcceptedObj = JSONObject()
            state.lastAcceptedRevisions.forEach { (qId, rev) ->
                lastAcceptedObj.put(qId, rev)
            }

            val respObj = JSONObject().apply {
                put("attemptId", state.attemptId)
                put("examId", state.examId)
                put("studentId", state.studentId)
                put("attemptStatus", state.attemptStatus)
                put("resultStatus", state.resultStatus)
                put("officialStartTime", state.officialStartTime)
                put("officialDeadline", state.officialDeadline)
                put("durationSeconds", state.durationSeconds)
                put("deadlineRemainingSeconds", state.deadlineRemainingSeconds)
                put("isDeadlineExpired", state.isDeadlineExpired)
                put("totalQuestionsAnswered", state.totalQuestionsAnswered)
                put("lastAcceptedRevisions", lastAcceptedObj)
                put("serverTimestamp", state.serverTimestamp)
            }

            call.respondText(respObj.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }

        // 55. Staff / Admin: Synchronization Observability & Diagnostics (STEP 11)
        get("/api/sync/status") {
            val session = authenticate(call, sessionManager) ?: return@get
            val permittedRoles = setOf("INVIGILATOR", "INCIDENT_REVIEWER", "EXAMINATION_ADMIN", "CHIEF_EXAMINATION_OFFICER", "AUDITOR", "SYSTEM_ADMIN")
            if (!permittedRoles.contains(session.role)) {
                call.respondText(errorJson("FORBIDDEN", "Staff role required for synchronization diagnostics"), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@get
            }

            val overview = syncService.getSyncObservabilityOverview()
            call.respondText(overview.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }
    }
}

fun computeCandidatePackageHash(
    examId: String,
    title: String,
    version: Int,
    questions: List<QuestionEntity>,
    sections: List<PaperSectionEntity>
): String {
    val sb = StringBuilder()
    sb.append("$examId|$title|$version|")
    sections.sortedBy { it.sectionOrder }.forEach {
        sb.append("${it.sectionId}:${it.title}:${it.sectionOrder};")
    }
    sb.append("|")
    questions.sortedBy { it.questionNumber }.forEach {
        sb.append("${it.questionId}:${it.questionNumber}:${it.questionType}:${it.questionText}:${it.answerOptionsJson};")
    }
    val md = MessageDigest.getInstance("SHA-256")
    return md.digest(sb.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
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
