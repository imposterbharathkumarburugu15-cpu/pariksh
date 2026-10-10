package com.doomedams.app.server

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.doomedams.app.database.AppDatabase
import com.doomedams.app.database.audit.AuditEngine
import com.doomedams.app.database.entity.AnswerKeyEntity
import com.doomedams.app.database.entity.CandidateResponseEntity
import com.doomedams.app.database.entity.ExamEntity
import com.doomedams.app.database.entity.MarkingSchemeEntity
import com.doomedams.app.database.entity.PaperSectionEntity
import com.doomedams.app.database.entity.QuestionEntity
import com.doomedams.app.database.entity.UserCredentialEntity
import com.doomedams.app.database.entity.UserEntity
import com.doomedams.app.database.entity.WorkedSolutionEntity
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class CandidateExaminationTest {

    private lateinit var database: AppDatabase
    private lateinit var sessionManager: SessionManager
    private lateinit var auditEngine: AuditEngine
    private lateinit var attemptRepository: AttemptRepository
    private lateinit var examRepository: ExamRepository
    private lateinit var rateLimiter: RateLimiter

    private val studentId = "usr_student_001"
    private val foreignStudentId = "usr_student_foreign"
    private val examId = "exam_cbt_001"
    private val draftExamId = "exam_draft_unreleased"
    private val instituteId = "inst_apex"

    private lateinit var studentToken: String
    private lateinit var foreignStudentToken: String

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

        val nowIso = Instant.now().toString()

        // 1. Seed Student Users
        val (hash1, salt1) = PasswordHasher.hashPassword("Password123!")
        database.userDao().insertUser(
            UserEntity(
                userId = studentId,
                instituteId = instituteId,
                role = "STUDENT",
                displayName = "Alice Student",
                accountStatus = "ACTIVE",
                createdAt = nowIso
            )
        )
        database.userCredentialDao().insertCredential(
            UserCredentialEntity(userId = studentId, passwordHash = hash1, salt = salt1, updatedAt = nowIso)
        )

        val (hash2, salt2) = PasswordHasher.hashPassword("Password123!")
        database.userDao().insertUser(
            UserEntity(
                userId = foreignStudentId,
                instituteId = "inst_other",
                role = "STUDENT",
                displayName = "Bob Foreign",
                accountStatus = "ACTIVE",
                createdAt = nowIso
            )
        )
        database.userCredentialDao().insertCredential(
            UserCredentialEntity(userId = foreignStudentId, passwordHash = hash2, salt = salt2, updatedAt = nowIso)
        )

        // 2. Create Active Sessions
        studentToken = sessionManager.createSession(studentId, "Alice Student", "STUDENT", instituteId).token
        foreignStudentToken = sessionManager.createSession(foreignStudentId, "Bob Foreign", "STUDENT", "inst_other").token

        // 3. Seed Published Examination with Questions, Answer Keys, and Solutions
        database.examDao().insertExam(
            ExamEntity(
                examId = examId,
                instituteId = instituteId,
                title = "Algorithms & Data Structures CBT",
                status = "PUBLISHED",
                durationSeconds = 7200L,
                markingSchemeVersion = 1,
                createdAt = nowIso,
                updatedAt = nowIso
            )
        )

        // Seed Unpublished Draft Exam
        database.examDao().insertExam(
            ExamEntity(
                examId = draftExamId,
                instituteId = instituteId,
                title = "Confidential Upcoming Draft",
                status = "DRAFT",
                durationSeconds = 3600L,
                markingSchemeVersion = 1,
                createdAt = nowIso,
                updatedAt = nowIso
            )
        )

        // Seed Sections
        database.paperSectionDao().insertSections(
            listOf(
                PaperSectionEntity(sectionId = "sec_1", examId = examId, title = "Section A: Core Concepts", sectionOrder = 1, sourceReference = null, createdAt = nowIso)
            )
        )

        // Seed Questions
        database.questionDao().insertQuestion(
            QuestionEntity(
                questionId = "q_001",
                examId = examId,
                sectionId = "sec_1",
                questionNumber = 1,
                questionType = "SINGLE_SELECT",
                questionText = "Which data structure uses LIFO ordering?",
                answerOptionsJson = "[\"Queue\",\"Stack\",\"Tree\",\"Graph\"]",
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
        database.questionDao().insertQuestion(
            QuestionEntity(
                questionId = "q_002",
                examId = examId,
                sectionId = "sec_1",
                questionNumber = 2,
                questionType = "NUMERICAL",
                questionText = "What is 2 raised to power 5?",
                answerOptionsJson = "[]",
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

        // Seed Confidential Answer Keys & Worked Solutions (Examiner Authority Only!)
        database.answerKeyDao().insertAnswerKey(
            AnswerKeyEntity(
                questionId = "q_001",
                examId = examId,
                expectedAnswerJson = "\"B\"",
                numericalTolerance = null,
                answerKeySource = "EXAMINER",
                verificationStatus = "VERIFIED",
                reviewerId = "usr_admin",
                verifiedAt = nowIso,
                updatedAt = nowIso
            )
        )
        database.workedSolutionDao().insertSolution(
            WorkedSolutionEntity(
                solutionId = "sol_q_001",
                questionId = "q_001",
                examId = examId,
                solutionText = "Stacks operate strictly on Last-In-First-Out (LIFO) semantics.",
                sourceReference = "Textbook ch. 3",
                verificationStatus = "VERIFIED",
                reviewerId = "usr_admin",
                verifiedAt = nowIso,
                updatedAt = nowIso
            )
        )

        // Seed Marking Scheme
        database.markingSchemeDao().insertMarkingScheme(
            MarkingSchemeEntity(
                schemeId = "ms_$examId",
                examId = examId,
                version = 1,
                correctScaledMark = 400L,
                incorrectScaledMark = -100L,
                unansweredScaledMark = 0L,
                allowNegativeMarking = true,
                sectionDefaultsJson = "{}",
                questionOverridesJson = "{}",
                updatedAt = nowIso
            )
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    // =========================================================================
    // 1. STUDENT DASHBOARD & PACKAGE ACCESS TESTS
    // =========================================================================

    @Test
    fun `unauthenticated access to student endpoints is rejected`() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepository, examRepository, rateLimiter)
        }

        val res = client.get("/api/student/exams")
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test
    fun `student sees only assigned published exams for their institute`() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepository, examRepository, rateLimiter)
        }

        val res = client.get("/api/student/exams") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
        }
        assertEquals(HttpStatusCode.OK, res.status)

        val array = JSONArray(res.bodyAsText())
        assertEquals(1, array.length())

        val examObj = array.getJSONObject(0)
        assertEquals(examId, examObj.getString("examId"))
        assertEquals("Algorithms & Data Structures CBT", examObj.getString("title"))
        assertEquals(2, examObj.getInt("totalQuestions"))
        assertTrue(examObj.isNull("attempt"))
    }

    @Test
    fun `foreign student in different institute cannot access exam`() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepository, examRepository, rateLimiter)
        }

        val res = client.get("/api/exams/$examId/candidate-package") {
            header(HttpHeaders.Authorization, "Bearer $foreignStudentToken")
        }
        assertEquals(HttpStatusCode.Forbidden, res.status)
    }

    @Test
    fun `unpublished draft exam cannot be downloaded as candidate package`() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepository, examRepository, rateLimiter)
        }

        val res = client.get("/api/exams/$draftExamId/candidate-package") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
        }
        assertEquals(HttpStatusCode.Forbidden, res.status)
    }

    @Test
    fun `candidate package 100 percent omits confidential answer keys and worked solutions`() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepository, examRepository, rateLimiter)
        }

        val res = client.get("/api/exams/$examId/candidate-package") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
        }
        assertEquals(HttpStatusCode.OK, res.status)

        val bodyText = res.bodyAsText()
        val pkg = JSONObject(bodyText)

        assertEquals(examId, pkg.getString("examId"))
        assertTrue(pkg.has("packageHash"))
        assertNotNull(pkg.getString("packageHash"))

        val questions = pkg.getJSONArray("questions")
        assertEquals(2, questions.length())

        // Critical verification: NEVER leak answers or solutions in payload
        assertFalse(bodyText.contains("expectedAnswer"))
        assertFalse(bodyText.contains("workedSolution"))
        assertFalse(bodyText.contains("solutionText"))
        assertFalse(bodyText.contains("Last-In-First-Out"))

        val q1 = questions.getJSONObject(0)
        assertEquals("q_001", q1.getString("questionId"))
        assertEquals("Which data structure uses LIFO ordering?", q1.getString("questionText"))
        assertFalse(q1.has("expectedAnswer"))
        assertFalse(q1.has("expectedAnswerJson"))
    }

    // =========================================================================
    // 2. ATTEMPT START & AUTHORITATIVE TIMING TESTS
    // =========================================================================

    @Test
    fun `server authoritatively starts attempt with deadline`() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepository, examRepository, rateLimiter)
        }

        val res = client.post("/api/exams/$examId/attempts/start") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
        }
        assertEquals(HttpStatusCode.OK, res.status)

        val json = JSONObject(res.bodyAsText())
        val attemptId = json.getString("attemptId")
        assertEquals(examId, json.getString("examId"))
        assertEquals(studentId, json.getString("studentId"))
        assertEquals("IN_PROGRESS", json.getString("attemptStatus"))

        val deadlineMeta = json.getJSONObject("deadlineMetadata")
        assertTrue(deadlineMeta.has("officialStartTime"))
        assertTrue(deadlineMeta.has("officialDeadline"))
        assertEquals(7200, deadlineMeta.getInt("durationSeconds"))

        // Repeating start is idempotent and returns the same active attempt
        val repeatRes = client.post("/api/exams/$examId/attempts/start") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
        }
        assertEquals(HttpStatusCode.OK, repeatRes.status)
        val repeatJson = JSONObject(repeatRes.bodyAsText())
        assertEquals(attemptId, repeatJson.getString("attemptId"))
    }

    // =========================================================================
    // 3. ANSWER AUTOSAVE, SYNC & IDEMPOTENCY TESTS
    // =========================================================================

    @Test
    fun `saving answer validates ownership and records revision sequence`() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepository, examRepository, rateLimiter)
        }

        // Start attempt first
        val startRes = client.post("/api/exams/$examId/attempts/start") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
        }
        val attemptId = JSONObject(startRes.bodyAsText()).getString("attemptId")

        // 1. Save Answer
        val saveRes = client.post("/api/attempts/$attemptId/answers") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(
                JSONObject().apply {
                    put("questionId", "q_001")
                    put("responsePayload", "B")
                    put("revisionSequence", 1)
                }.toString()
            )
        }
        assertEquals(HttpStatusCode.OK, saveRes.status)
        val saveJson = JSONObject(saveRes.bodyAsText())
        assertEquals(1, saveJson.getInt("revisionSequence"))
        assertEquals("SAVED", saveJson.getString("status"))

        // 2. Reject foreign student from saving answers to this attempt
        val foreignRes = client.post("/api/attempts/$attemptId/answers") {
            header(HttpHeaders.Authorization, "Bearer $foreignStudentToken")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(
                JSONObject().apply {
                    put("questionId", "q_001")
                    put("responsePayload", "A")
                    put("revisionSequence", 2)
                }.toString()
            )
        }
        assertEquals(HttpStatusCode.Forbidden, foreignRes.status)
    }

    @Test
    fun `batch sync responses reconciles multiple queued revisions idempotently`() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepository, examRepository, rateLimiter)
        }

        val startRes = client.post("/api/exams/$examId/attempts/start") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
        }
        val attemptId = JSONObject(startRes.bodyAsText()).getString("attemptId")

        val syncPayload = JSONObject().apply {
            put(
                "responses",
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("questionId", "q_001")
                            put("responsePayload", "B")
                            put("revisionSequence", 1)
                        }
                    )
                    put(
                        JSONObject().apply {
                            put("questionId", "q_002")
                            put("responsePayload", "32")
                            put("revisionSequence", 1)
                        }
                    )
                }
            )
        }

        val syncRes = client.post("/api/attempts/$attemptId/sync-responses") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(syncPayload.toString())
        }
        assertEquals(HttpStatusCode.OK, syncRes.status)

        val syncJson = JSONObject(syncRes.bodyAsText())
        assertEquals("SYNCED", syncJson.getString("status"))
        assertEquals(2, syncJson.getInt("syncedCount"))

        // Verify stored responses in database
        val resp1 = database.candidateResponseDao().getResponse(attemptId, "q_001")
        assertNotNull(resp1)
        assertEquals("B", resp1?.responsePayload)

        val resp2 = database.candidateResponseDao().getResponse(attemptId, "q_002")
        assertNotNull(resp2)
        assertEquals("32", resp2?.responsePayload)
    }

    // =========================================================================
    // 4. SUBMISSION WORKFLOW & DURABLE RECEIPTS
    // =========================================================================

    @Test
    fun `submission transitions attempt and issues cryptographic receipt`() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepository, examRepository, rateLimiter)
        }

        val startRes = client.post("/api/exams/$examId/attempts/start") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
        }
        val attemptId = JSONObject(startRes.bodyAsText()).getString("attemptId")

        val submitPayload = JSONObject().apply {
            put("clientChecksum", "abc123456789def0")
            put("totalQuestionsAnswered", 2)
        }

        val submitRes = client.post("/api/attempts/$attemptId/submit") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(submitPayload.toString())
        }
        assertEquals(HttpStatusCode.OK, submitRes.status)

        val receipt = JSONObject(submitRes.bodyAsText())
        assertEquals("ACCEPTED", receipt.getString("submissionStatus"))
        assertEquals(attemptId, receipt.getString("attemptId"))
        assertTrue(receipt.has("receiptId"))
        assertTrue(receipt.has("receiptHash"))
        assertEquals(64, receipt.getString("receiptHash").length) // SHA-256 hex length

        // Attempt in database must now be SUBMITTED
        val updatedAttempt = database.examAttemptDao().getAttemptById(attemptId)
        assertEquals("SUBMITTED", updatedAttempt?.attemptStatus)

        // Answers cannot be submitted to closed attempt
        val lateAnswerRes = client.post("/api/attempts/$attemptId/answers") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(
                JSONObject().apply {
                    put("questionId", "q_001")
                    put("responsePayload", "C")
                    put("revisionSequence", 5)
                }.toString()
            )
        }
        assertEquals(HttpStatusCode.Conflict, lateAnswerRes.status)

        // Repeated submission is idempotent and returns accepted receipt
        val repeatSubmitRes = client.post("/api/attempts/$attemptId/submit") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(submitPayload.toString())
        }
        assertEquals(HttpStatusCode.OK, repeatSubmitRes.status)
        val repeatReceipt = JSONObject(repeatSubmitRes.bodyAsText())
        assertEquals("ACCEPTED", repeatReceipt.getString("submissionStatus"))
    }

    // =========================================================================
    // 5. RESULT CONFIDENTIALITY & ACCESS CONTROL
    // =========================================================================

    @Test
    fun `unreleased results are strictly withheld from students`() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepository, examRepository, rateLimiter)
        }

        val startRes = client.post("/api/exams/$examId/attempts/start") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
        }
        val attemptId = JSONObject(startRes.bodyAsText()).getString("attemptId")

        // Submit and grade attempt (as admin)
        attemptRepository.gradeAttempt(attemptId, "usr_admin", "EXAMINATION_ADMIN")

        // Student tries to view result before publication
        val res = client.get("/api/attempts/$attemptId/result") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
        }
        // Result is GRADED but not PUBLISHED -> Forbidden
        assertEquals(HttpStatusCode.Forbidden, res.status)
        assertTrue(res.bodyAsText().contains("RESULT_NOT_RELEASED"))

        // Admin publishes result
        attemptRepository.publishResult(attemptId, "usr_admin", "EXAMINATION_ADMIN")

        // Now student can view result!
        val publishedRes = client.get("/api/attempts/$attemptId/result") {
            header(HttpHeaders.Authorization, "Bearer $studentToken")
        }
        assertEquals(HttpStatusCode.OK, publishedRes.status)
        val resultJson = JSONObject(publishedRes.bodyAsText())
        assertEquals("PUBLISHED", resultJson.getString("resultStatus"))
    }
}
