package com.doomedams.app.server

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.doomedams.app.database.AppDatabase
import com.doomedams.app.database.audit.AuditEngine
import com.doomedams.app.database.entity.AnswerKeyEntity
import com.doomedams.app.database.entity.ExamEntity
import com.doomedams.app.database.entity.MarkingSchemeEntity
import com.doomedams.app.database.entity.QuestionEntity
import com.doomedams.app.database.entity.UserCredentialEntity
import com.doomedams.app.database.entity.UserEntity
import com.doomedams.app.database.entity.ValidationIssueEntity
import com.doomedams.app.database.entity.WorkedSolutionEntity
import com.doomedams.app.database.repository.AttemptRepository
import com.doomedams.app.database.repository.ExamRepository
import com.doomedams.app.server.auth.SessionManager
import com.doomedams.app.server.compiler.DocumentExtractor
import com.doomedams.app.server.compiler.ExtractedPage
import com.doomedams.app.server.compiler.ExtractionResult
import com.doomedams.app.server.compiler.PaperValidationEngine
import com.doomedams.app.server.compiler.ParsedDraftPackage
import com.doomedams.app.server.compiler.QuestionStructuralParser
import com.doomedams.app.server.routes.configureExamServerRoutes
import com.doomedams.app.server.security.PasswordHasher
import com.doomedams.app.server.security.RateLimiter
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
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
class ExamPaperCompilerTest {

    private lateinit var database: AppDatabase
    private lateinit var auditEngine: AuditEngine
    private lateinit var attemptRepository: AttemptRepository
    private lateinit var examRepository: ExamRepository
    private lateinit var sessionManager: SessionManager
    private lateinit var rateLimiter: RateLimiter
    private lateinit var testStorageDir: File

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        testStorageDir = File(context.filesDir, "test_exam_papers").apply { mkdirs() }

        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(AppDatabase.DatabaseSecurityCallback)
            .build()

        database.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = ON;")

        auditEngine = AuditEngine(database)
        attemptRepository = AttemptRepository(database, auditEngine)
        examRepository = ExamRepository(database, auditEngine)
        sessionManager = SessionManager()
        rateLimiter = RateLimiter()

        seedTestUsers()
    }

    @After
    fun tearDown() {
        database.close()
        testStorageDir.deleteRecursively()
    }

    private fun seedTestUsers() = runTest {
        val now = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
        val (hash, salt) = PasswordHasher.hashPassword("SuperSecret123")

        val admin = UserEntity(
            userId = "admin_01",
            instituteId = "inst_apex",
            role = "EXAMINATION_ADMIN",
            displayName = "Apex Admin",
            accountStatus = "ACTIVE",
            createdAt = now
        )
        val student = UserEntity(
            userId = "student_01",
            instituteId = "inst_apex",
            role = "STUDENT",
            displayName = "Student Bob",
            accountStatus = "ACTIVE",
            createdAt = now
        )

        database.userDao().insertUser(admin)
        database.userCredentialDao().insertCredential(
            UserCredentialEntity(userId = "admin_01", passwordHash = hash, salt = salt, updatedAt = now)
        )
        database.userDao().insertUser(student)
        database.userCredentialDao().insertCredential(
            UserCredentialEntity(userId = "student_01", passwordHash = hash, salt = salt, updatedAt = now)
        )
    }

    // =========================================================================
    // 1. DOCUMENT EXTRACTOR TESTS
    // =========================================================================

    @Test
    fun test01_validTxtImportAndExtraction() {
        val txtContent = """
            SECTION 1: PHYSICS
            1. An object of mass 2 kg moves with velocity v = 5 m/s. Calculate its kinetic energy.
            (A) 25 J
            (B) 50 J
            (C) 10 J
            (D) 100 J

            2. What is the value of gravitational acceleration on Earth (in m/s^2)?
            
            ANSWER KEY:
            1. A
            2. 9.8
        """.trimIndent()

        val tempFile = File(testStorageDir, "physics_sample.txt").apply {
            writeText(txtContent, Charsets.UTF_8)
        }

        val result = DocumentExtractor.extractText(tempFile.readBytes(), "TXT")
        assertEquals("TXT", result.fileType)
        assertEquals(1, result.pages.size)
        assertTrue(result.pages[0].text.contains("kinetic energy"))
        assertFalse(result.requiresOcr)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun test02_unsupportedFileTypeRejected() {
        val dummyBytes = byteArrayOf(0x4D, 0x5A, 0x00, 0x00)

        try {
            DocumentExtractor.extractText(dummyBytes, "EXE")
            org.junit.Assert.fail("Expected IllegalArgumentException for unsupported file type")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Unsupported file type") == true)
        }
    }

    @Test
    fun test03_emptyFileHasWarning() {
        val emptyBytes = byteArrayOf()
        val result = DocumentExtractor.extractText(emptyBytes, "TXT")
        assertTrue(result.warnings.any { it.contains("empty") })
    }

    @Test
    fun test04_mathematicalSymbolsPreserved() {
        val mathContent = """
            Q1. Evaluate the integral $\int_0^1 x^2 dx = \frac{1}{3}$.
            Angles: $\alpha = 30^\circ$, $\beta = 45^\circ$, $\gamma = 60^\circ$.
            Square root: $\sqrt{2x + 1} \ge 0$.
            (A) True
            (B) False
        """.trimIndent()

        val mathFile = File(testStorageDir, "math_symbols.txt").apply {
            writeText(mathContent, Charsets.UTF_8)
        }

        val result = DocumentExtractor.extractText(mathFile.readBytes(), "TXT")
        val extractedText = result.pages[0].text
        assertTrue(extractedText.contains("\\int_0^1"))
        assertTrue(extractedText.contains("\\alpha"))
        assertTrue(extractedText.contains("\\sqrt{2x + 1}"))
    }

    @Test
    fun test05_scannedPdfLowDensityFlagsOcrUnavailable() {
        val mockScannedPages = listOf(
            ExtractedPage(pageNumber = 1, text = "   \n  \n", isLowDensityScan = true)
        )
        assertTrue(mockScannedPages[0].isLowDensityScan)
        val extractionResult = ExtractionResult(
            fileType = "PDF",
            pages = mockScannedPages,
            fullText = "",
            warnings = listOf("Page 1 has low text density and likely contains scanned imagery."),
            requiresOcr = true
        )
        val nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
        val parsed = QuestionStructuralParser.parse("exam_scan_01", "imp_01", extractionResult, nowIso)
        assertTrue(parsed.questions.isEmpty())
        assertTrue(parsed.validationIssues.any { it.issueCode == "LOW_TEXT_DENSITY_PAGE" })
    }

    // =========================================================================
    // 2. QUESTION STRUCTURAL PARSER TESTS
    // =========================================================================

    @Test
    fun test06_commonQuestionNumberingAndOptionsRecognised() {
        val text = """
            SECTION 1: MATHEMATICS
            1. What is the value of 2 + 2?
            (A) 3
            (B) 4
            (C) 5
            (D) 6

            Q2. Which of the following numbers are prime?
            A. 2
            B. 3
            C. 4
            D. 5

            Question 3: Calculate the area of a circle with radius r = 7 cm.

            4) Describe Newton's first law of motion.
        """.trimIndent()

        val pages = listOf(ExtractedPage(pageNumber = 1, text = text, isLowDensityScan = false))
        val extraction = ExtractionResult(
            fileType = "TXT",
            pages = pages,
            fullText = text,
            warnings = emptyList(),
            requiresOcr = false
        )

        val nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
        val parsed = QuestionStructuralParser.parse("exam_m_01", "imp_01", extraction, nowIso)

        assertEquals(4, parsed.questions.size)
        assertEquals(1, parsed.sections.size)
        assertEquals("SECTION 1: MATHEMATICS", parsed.sections[0].title)

        // Question 1: Multiple choice (SINGLE_SELECT)
        val q1 = parsed.questions[0]
        assertEquals(1, q1.questionNumber)
        assertEquals("SINGLE_SELECT", q1.questionType)
        val opts1 = JSONArray(q1.answerOptionsJson)
        assertEquals(4, opts1.length())
        assertEquals("A", opts1.getJSONObject(0).getString("label"))
        assertEquals("3", opts1.getJSONObject(0).getString("text"))

        // Question 2: Letter prefix options
        val q2 = parsed.questions[1]
        assertEquals(2, q2.questionNumber)
        assertEquals("SINGLE_SELECT", q2.questionType)
        val opts2 = JSONArray(q2.answerOptionsJson)
        assertEquals(4, opts2.length())

        // Question 3: Numerical question with no options
        val q3 = parsed.questions[2]
        assertEquals(3, q3.questionNumber)
        assertEquals("NUMERICAL", q3.questionType)
        assertEquals("[]", q3.answerOptionsJson)

        // Question 4: Subjective question
        val q4 = parsed.questions[3]
        assertEquals(4, q4.questionNumber)
        assertEquals("SUBJECTIVE", q4.questionType)
        assertEquals("[]", q4.answerOptionsJson)
    }

    @Test
    fun test07_duplicateQuestionNumbersDetected() {
        val text = """
            1. First question stem.
            (A) Opt 1
            (B) Opt 2

            1. Duplicate question stem with same number!
            (A) Opt A
            (B) Opt B
        """.trimIndent()

        val pages = listOf(ExtractedPage(pageNumber = 1, text = text, isLowDensityScan = false))
        val extraction = ExtractionResult(
            fileType = "TXT",
            pages = pages,
            fullText = text,
            warnings = emptyList(),
            requiresOcr = false
        )

        val nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
        val parsed = QuestionStructuralParser.parse("exam_dup_01", "imp_01", extraction, nowIso)

        assertEquals(2, parsed.questions.size)
        assertTrue(parsed.validationIssues.any { it.issueCode == "DUPLICATE_QUESTION_NUMBER" })
        val dupIssue = parsed.validationIssues.first { it.issueCode == "DUPLICATE_QUESTION_NUMBER" }
        assertEquals("ERROR", dupIssue.severity)
    }

    @Test
    fun test08_parserExtractsAnswerKeyAndSolutionWithoutFabrication() {
        val text = """
            1. Solve for x: 3x - 6 = 0.
            (A) 1
            (B) 2
            (C) 3
            (D) 4

            2. Numerical question.

            ANSWER KEY:
            1: B
            2: 42.5

            SOLUTIONS:
            1: 3x = 6 implies x = 2. Option B is correct.
        """.trimIndent()

        val pages = listOf(ExtractedPage(pageNumber = 1, text = text, isLowDensityScan = false))
        val extraction = ExtractionResult(
            fileType = "TXT",
            pages = pages,
            fullText = text,
            warnings = emptyList(),
            requiresOcr = false
        )

        val nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
        val parsed = QuestionStructuralParser.parse("exam_ans_01", "imp_01", extraction, nowIso)

        assertEquals(2, parsed.questions.size)
        assertEquals(2, parsed.answerKeys.size)
        assertEquals(1, parsed.solutions.size)

        // Question 1 Answer Key
        val ak1 = parsed.answerKeys.find { it.questionId == parsed.questions[0].questionId }
        assertNotNull(ak1)
        val expected1 = JSONArray(ak1?.expectedAnswerJson)
        assertEquals("B", expected1.getString(0))
        assertEquals("UNVERIFIED", ak1?.verificationStatus)

        // Question 2 Answer Key (numerical)
        val ak2 = parsed.answerKeys.find { it.questionId == parsed.questions[1].questionId }
        assertNotNull(ak2)
        val expected2 = JSONArray(ak2?.expectedAnswerJson)
        assertEquals("42.5", expected2.getString(0))

        // Question 1 Worked Solution
        val ws1 = parsed.solutions.find { it.questionId == parsed.questions[0].questionId }
        assertNotNull(ws1)
        assertTrue(ws1?.solutionText?.contains("x = 2") == true)
        assertEquals("UNVERIFIED", ws1?.verificationStatus)
    }

    @Test
    fun test09_missingAnswerKeysMarkedMissingNeverFabricated() {
        val text = """
            1. Question without answer key.
            (A) Option 1
            (B) Option 2
        """.trimIndent()

        val pages = listOf(ExtractedPage(pageNumber = 1, text = text, isLowDensityScan = false))
        val extraction = ExtractionResult(
            fileType = "TXT",
            pages = pages,
            fullText = text,
            warnings = emptyList(),
            requiresOcr = false
        )

        val nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
        val parsed = QuestionStructuralParser.parse("exam_missing_01", "imp_01", extraction, nowIso)

        assertEquals(1, parsed.questions.size)
        assertTrue(parsed.validationIssues.any { it.issueCode == "MISSING_ANSWER_KEY" })
        assertEquals("[]", parsed.answerKeys[0].expectedAnswerJson)
        assertEquals("UNVERIFIED", parsed.answerKeys[0].verificationStatus)
        assertTrue(parsed.solutions.isEmpty()) // Never fabricated!
    }

    // =========================================================================
    // 3. PAPER VALIDATION ENGINE TESTS
    // =========================================================================

    @Test
    fun test10_validationEngineDetectsBlockingErrorsAndWarnings() {
        val now = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
        val exam = ExamEntity(
            examId = "exam_draft_01",
            instituteId = "inst_apex",
            title = "Test Draft",
            status = "DRAFT",
            durationSeconds = 3600,
            markingSchemeVersion = 1,
            createdAt = now,
            updatedAt = now
        )

        val q1 = QuestionEntity(
            questionId = "q_01",
            examId = "exam_draft_01",
            sectionId = null,
            questionNumber = 1,
            questionType = "SINGLE_SELECT",
            questionText = "Valid question text?",
            answerOptionsJson = "[{\"id\":\"opt_1\",\"label\":\"A\",\"text\":\"Yes\"},{\"id\":\"opt_2\",\"label\":\"B\",\"text\":\"No\"}]",
            sourcePage = 1,
            sourceStartLine = 1,
            sourceEndLine = 4,
            sourceSnippet = "Q1",
            extractionConfidence = "HIGH",
            reviewStatus = "ACCEPTED",
            createdAt = now,
            updatedAt = now
        )
        val q2Invalid = QuestionEntity(
            questionId = "q_02",
            examId = "exam_draft_01",
            sectionId = null,
            questionNumber = 2,
            questionType = "SINGLE_SELECT",
            questionText = "   ", // Blank stem
            answerOptionsJson = "[]", // No options for single select
            sourcePage = 1,
            sourceStartLine = 5,
            sourceEndLine = 6,
            sourceSnippet = "Q2",
            extractionConfidence = "LOW",
            reviewStatus = "PENDING_REVIEW",
            createdAt = now,
            updatedAt = now
        )

        val report = PaperValidationEngine.validateDraft(
            exam = exam,
            questions = listOf(q1, q2Invalid),
            answerKeys = emptyList(), // Missing keys
            markingScheme = null, // Missing scheme
            validationIssues = emptyList()
        )

        assertFalse(report.isReadyForPublication)
        assertTrue(report.blockingErrors.isNotEmpty())
        assertTrue(report.blockingErrors.any { it.contains("blank question text") })
        assertTrue(report.blockingErrors.any { it.contains("must have at least 2 options") })
        assertTrue(report.blockingErrors.any { it.contains("No marking scheme configured") })
    }

    @Test
    fun test11_publicationGateApprovedWhenAllValidationsPass() {
        val now = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
        val exam = ExamEntity(
            examId = "exam_draft_valid",
            instituteId = "inst_apex",
            title = "Valid Exam",
            status = "DRAFT",
            durationSeconds = 7200,
            markingSchemeVersion = 1,
            createdAt = now,
            updatedAt = now
        )

        val q1 = QuestionEntity(
            questionId = "q_01",
            examId = "exam_draft_valid",
            sectionId = null,
            questionNumber = 1,
            questionType = "SINGLE_SELECT",
            questionText = "What is 1 + 1?",
            answerOptionsJson = "[{\"id\":\"opt_1\",\"label\":\"A\",\"text\":\"2\"},{\"id\":\"opt_2\",\"label\":\"B\",\"text\":\"3\"}]",
            sourcePage = 1,
            sourceStartLine = 1,
            sourceEndLine = 3,
            sourceSnippet = "Q1",
            extractionConfidence = "HIGH",
            reviewStatus = "ACCEPTED",
            createdAt = now,
            updatedAt = now
        )
        val ak1 = AnswerKeyEntity(
            questionId = "q_01",
            examId = "exam_draft_valid",
            expectedAnswerJson = "[\"A\"]",
            numericalTolerance = null,
            answerKeySource = "ADMIN_VERIFIED",
            verificationStatus = "VERIFIED",
            reviewerId = "admin_01",
            verifiedAt = now,
            updatedAt = now
        )
        val marking = MarkingSchemeEntity(
            schemeId = "ms_01",
            examId = "exam_draft_valid",
            version = 1,
            correctScaledMark = 400,
            incorrectScaledMark = -100,
            unansweredScaledMark = 0,
            allowNegativeMarking = true,
            sectionDefaultsJson = "{}",
            questionOverridesJson = "{}",
            updatedAt = now
        )

        val report = PaperValidationEngine.validateDraft(
            exam = exam,
            questions = listOf(q1),
            answerKeys = listOf(ak1),
            markingScheme = marking,
            validationIssues = emptyList()
        )

        assertTrue(report.isReadyForPublication)
        assertTrue(report.blockingErrors.isEmpty())
        assertEquals(0, report.unverifiedAnswerKeyCount)
    }

    // =========================================================================
    // 4. ROOM PERSISTENCE & AUDITED TRANSACTION TESTS
    // =========================================================================

    @Test
    fun test12_saveDraftPackageSurvivesReopenAndPreservesAuditTrail() = runTest {
        val now = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
        val exam = ExamEntity(
            examId = "draft_exam_101",
            instituteId = "inst_apex",
            title = "Chemistry Preliminary",
            status = "DRAFT",
            durationSeconds = 5400,
            markingSchemeVersion = 1,
            createdAt = now,
            updatedAt = now
        )
        val q1 = QuestionEntity(
            questionId = "q_chem_01",
            examId = "draft_exam_101",
            sectionId = null,
            questionNumber = 1,
            questionType = "SINGLE_SELECT",
            questionText = "What is the atomic number of Carbon?",
            answerOptionsJson = "[{\"id\":\"c_1\",\"label\":\"A\",\"text\":\"6\"},{\"id\":\"c_2\",\"label\":\"B\",\"text\":\"12\"}]",
            sourcePage = 1,
            sourceStartLine = 1,
            sourceEndLine = 3,
            sourceSnippet = "Q1",
            extractionConfidence = "HIGH",
            reviewStatus = "ACCEPTED",
            createdAt = now,
            updatedAt = now
        )
        val ak1 = AnswerKeyEntity(
            questionId = "q_chem_01",
            examId = "draft_exam_101",
            expectedAnswerJson = "[\"A\"]",
            numericalTolerance = null,
            answerKeySource = "DOCUMENT_EXTRACTED",
            verificationStatus = "VERIFIED",
            reviewerId = "admin_01",
            verifiedAt = now,
            updatedAt = now
        )
        val marking = MarkingSchemeEntity(
            schemeId = "ms_chem_101",
            examId = "draft_exam_101",
            version = 1,
            correctScaledMark = 300,
            incorrectScaledMark = -100,
            unansweredScaledMark = 0,
            allowNegativeMarking = true,
            sectionDefaultsJson = "{}",
            questionOverridesJson = "{}",
            updatedAt = now
        )

        val parsedPkg = ParsedDraftPackage(
            sections = emptyList(),
            questions = listOf(q1),
            answerKeys = listOf(ak1),
            solutions = emptyList<WorkedSolutionEntity>(),
            validationIssues = emptyList<ValidationIssueEntity>(),
            defaultMarkingScheme = marking
        )

        val saved = examRepository.saveDraftPackage(
            exam = exam,
            paperImport = null,
            parsedPackage = parsedPkg,
            actorId = "admin_01",
            actorRole = "EXAMINATION_ADMIN"
        )
        assertNotNull(saved)
        assertEquals("DRAFT", saved.status)

        // Verify Questions persisted
        val questionsInDb = database.questionDao().getQuestionsByExamId("draft_exam_101")
        assertEquals(1, questionsInDb.size)
        assertEquals("What is the atomic number of Carbon?", questionsInDb[0].questionText)

        // Verify Answer Key persisted separately in confidential table
        val answerKeyInDb = database.answerKeyDao().getAnswerKeyByQuestionId("q_chem_01")
        assertNotNull(answerKeyInDb)
        assertEquals("[\"A\"]", answerKeyInDb?.expectedAnswerJson)

        // Verify Audit Trail recorded
        val auditEvents = database.auditEventDao().getAuditEventsForAggregate("draft_exam_101")
        assertTrue(auditEvents.any { it.actionType == "SAVE_EXAM_DRAFT" && it.aggregateId == "draft_exam_101" })
    }

    @Test
    fun test13_publishExamFailsWhenBlockingErrorsExist() = runTest {
        val now = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
        val exam = ExamEntity(
            examId = "draft_unvalidated",
            instituteId = "inst_apex",
            title = "Unvalidated Exam",
            status = "DRAFT",
            durationSeconds = 3600,
            markingSchemeVersion = 1,
            createdAt = now,
            updatedAt = now
        )
        database.examDao().insertExam(exam)

        // No questions, no marking scheme -> publish should throw IllegalStateException
        try {
            examRepository.publishExam(
                exam = exam,
                actorId = "admin_01",
                actorRole = "EXAMINATION_ADMIN"
            )
            org.junit.Assert.fail("Expected IllegalStateException for unvalidated publication")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("PUBLICATION_REJECTED") == true)
        }
    }

    // =========================================================================
    // 5. SERVER ROUTE AUTHORIZATION & CONFIDENTIALITY TESTS
    // =========================================================================

    @Test
    fun test14_studentCannotAccessConfidentialAnswerKeys() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepository, examRepository, rateLimiter)
        }

        val studentSession = sessionManager.createSession("student_01", "Bob", "STUDENT", "inst_apex")
        val response = client.get("/api/exams/drafts/draft_exam_101/answer-keys") {
            header("Authorization", "Bearer ${studentSession.token}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(response.bodyAsText().contains("FORBIDDEN"))
    }

    @Test
    fun test15_administratorCanSaveAndRetrieveAnswerKeys() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepository, examRepository, rateLimiter)
        }

        val now = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
        val exam = ExamEntity(
            examId = "exam_keys_test",
            instituteId = "inst_apex",
            title = "Key Test",
            status = "DRAFT",
            durationSeconds = 3600,
            markingSchemeVersion = 1,
            createdAt = now,
            updatedAt = now
        )
        database.examDao().insertExam(exam)

        val q1 = QuestionEntity(
            questionId = "q_math_01",
            examId = "exam_keys_test",
            sectionId = null,
            questionNumber = 1,
            questionType = "SINGLE_SELECT",
            questionText = "Solve for x",
            answerOptionsJson = "[{\"id\":\"o1\",\"label\":\"A\",\"text\":\"1\"},{\"id\":\"o2\",\"label\":\"B\",\"text\":\"2\"}]",
            sourcePage = 1,
            sourceStartLine = 1,
            sourceEndLine = 2,
            sourceSnippet = "Q1",
            extractionConfidence = "HIGH",
            reviewStatus = "ACCEPTED",
            createdAt = now,
            updatedAt = now
        )
        database.questionDao().insertQuestions(listOf(q1))

        val adminSession = sessionManager.createSession("admin_01", "Apex Admin", "EXAMINATION_ADMIN", "inst_apex")

        // Save Answer Keys
        val body = JSONObject().apply {
            put("answerKeys", JSONArray().apply {
                put(JSONObject().apply {
                    put("questionId", "q_math_01")
                    put("expectedAnswer", JSONArray().put("B"))
                    put("answerKeySource", "ADMIN_VERIFIED")
                    put("verificationStatus", "VERIFIED")
                })
            })
            put("solutions", JSONArray().apply {
                put(JSONObject().apply {
                    put("solutionId", "sol_01")
                    put("questionId", "q_math_01")
                    put("solutionText", "Step 1: Simplify. Step 2: Conclude.")
                    put("verificationStatus", "VERIFIED")
                })
            })
        }

        val putResp = client.put("/api/exams/drafts/exam_keys_test/answer-keys") {
            header("Authorization", "Bearer ${adminSession.token}")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(body.toString())
        }
        assertEquals(HttpStatusCode.OK, putResp.status)

        // Read Answer Keys back
        val getResp = client.get("/api/exams/drafts/exam_keys_test/answer-keys") {
            header("Authorization", "Bearer ${adminSession.token}")
        }
        assertEquals(HttpStatusCode.OK, getResp.status)
        val respObj = JSONObject(getResp.bodyAsText())
        val keysArr = respObj.getJSONArray("answerKeys")
        assertEquals(1, keysArr.length())
        assertEquals("VERIFIED", keysArr.getJSONObject(0).getString("verificationStatus"))
    }

    @Test
    fun test16_serverPublicationEndpointEnforcesValidationGate() = testApplication {
        application {
            configureExamServerRoutes(database, sessionManager, auditEngine, attemptRepository, examRepository, rateLimiter)
        }

        val adminSession = sessionManager.createSession("admin_01", "Apex Admin", "EXAMINATION_ADMIN", "inst_apex")

        // 1. Create a Draft Exam
        val createBody = JSONObject().apply {
            put("examId", "exam_pub_gate_01")
            put("title", "Gate Testing Exam")
            put("durationSeconds", 3600)
            put("markingSchemeVersion", 1)
        }
        val createResp = client.post("/api/exams/drafts") {
            header("Authorization", "Bearer ${adminSession.token}")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(createBody.toString())
        }
        assertEquals(HttpStatusCode.Created, createResp.status)

        // 2. Attempt to publish before adding questions or marking scheme -> Expect 422 Unprocessable Entity
        val publishRespFail = client.post("/api/exams/drafts/exam_pub_gate_01/publish") {
            header("Authorization", "Bearer ${adminSession.token}")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, publishRespFail.status)
        assertTrue(publishRespFail.bodyAsText().contains("PUBLICATION_REJECTED"))

        // 3. Add valid question and marking scheme
        val questionsBody = JSONObject().apply {
            put("questions", JSONArray().apply {
                put(JSONObject().apply {
                    put("questionId", "q_gate_01")
                    put("questionNumber", 1)
                    put("questionType", "SINGLE_SELECT")
                    put("questionText", "Is 5 greater than 3?")
                    put("answerOptions", JSONArray().apply {
                        put(JSONObject().apply { put("optionId", "o1"); put("optionLabel", "A"); put("optionText", "Yes") })
                        put(JSONObject().apply { put("optionId", "o2"); put("optionLabel", "B"); put("optionText", "No") })
                    })
                    put("extractionConfidence", "HIGH")
                })
            })
            put("sections", JSONArray())
        }
        val putQResp = client.put("/api/exams/drafts/exam_pub_gate_01/questions") {
            header("Authorization", "Bearer ${adminSession.token}")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(questionsBody.toString())
        }
        assertEquals(HttpStatusCode.OK, putQResp.status)

        // 4. Add verified answer key
        val keysBody = JSONObject().apply {
            put("answerKeys", JSONArray().apply {
                put(JSONObject().apply {
                    put("questionId", "q_gate_01")
                    put("expectedAnswer", JSONArray().put("A"))
                    put("verificationStatus", "VERIFIED")
                })
            })
            put("solutions", JSONArray())
        }
        client.put("/api/exams/drafts/exam_pub_gate_01/answer-keys") {
            header("Authorization", "Bearer ${adminSession.token}")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(keysBody.toString())
        }

        // 5. Add valid marking scheme
        val markingBody = JSONObject().apply {
            put("schemeId", "ms_gate_01")
            put("version", 1)
            put("correctScaledMark", 400)
            put("incorrectScaledMark", -100)
            put("unansweredScaledMark", 0)
            put("allowNegativeMarking", true)
        }
        client.put("/api/exams/drafts/exam_pub_gate_01/marking-scheme") {
            header("Authorization", "Bearer ${adminSession.token}")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(markingBody.toString())
        }

        // 6. Now publish -> Should succeed!
        val publishRespSuccess = client.post("/api/exams/drafts/exam_pub_gate_01/publish") {
            header("Authorization", "Bearer ${adminSession.token}")
        }
        assertEquals(HttpStatusCode.OK, publishRespSuccess.status)
        val pubJson = JSONObject(publishRespSuccess.bodyAsText())
        assertEquals("PUBLISHED", pubJson.getString("status"))
    }
}
