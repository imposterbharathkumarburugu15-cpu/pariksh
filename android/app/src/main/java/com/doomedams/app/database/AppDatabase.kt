package com.doomedams.app.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.doomedams.app.database.dao.AnswerKeyDao
import com.doomedams.app.database.dao.AuditEventDao
import com.doomedams.app.database.dao.CandidateResponseDao
import com.doomedams.app.database.dao.ExamAttemptDao
import com.doomedams.app.database.dao.ExamDao
import com.doomedams.app.database.dao.ExamResultDao
import com.doomedams.app.database.dao.IncidentDao
import com.doomedams.app.database.dao.IntegrityEventDao
import com.doomedams.app.database.dao.MarkingSchemeDao
import com.doomedams.app.database.dao.PaperImportDao
import com.doomedams.app.database.dao.PaperSectionDao
import com.doomedams.app.database.dao.QuestionDao
import com.doomedams.app.database.dao.QuestionGradingRecordDao
import com.doomedams.app.database.dao.ServerEventReceiptDao
import com.doomedams.app.database.dao.SyncOutboxDao
import com.doomedams.app.database.dao.UserCredentialDao
import com.doomedams.app.database.dao.UserDao
import com.doomedams.app.database.dao.ValidationIssueDao
import com.doomedams.app.database.dao.WorkedSolutionDao
import com.doomedams.app.database.entity.AnswerKeyEntity
import com.doomedams.app.database.entity.AuditEventEntity
import com.doomedams.app.database.entity.CandidateResponseEntity
import com.doomedams.app.database.entity.ExamAttemptEntity
import com.doomedams.app.database.entity.ExamEntity
import com.doomedams.app.database.entity.ExamResultEntity
import com.doomedams.app.database.entity.IncidentCaseEntity
import com.doomedams.app.database.entity.IncidentDecisionEntity
import com.doomedams.app.database.entity.IntegrityEventEntity
import com.doomedams.app.database.entity.MarkingSchemeEntity
import com.doomedams.app.database.entity.PaperImportEntity
import com.doomedams.app.database.entity.PaperSectionEntity
import com.doomedams.app.database.entity.PolicyActionEntity
import com.doomedams.app.database.entity.QuestionEntity
import com.doomedams.app.database.entity.QuestionGradingRecordEntity
import com.doomedams.app.database.entity.ServerEventReceiptEntity
import com.doomedams.app.database.entity.SyncOutboxEntity
import com.doomedams.app.database.entity.UserCredentialEntity
import com.doomedams.app.database.entity.UserEntity
import com.doomedams.app.database.entity.AppealEntity
import com.doomedams.app.database.entity.ValidationIssueEntity
import com.doomedams.app.database.entity.WorkedSolutionEntity

@Database(
    entities = [
        UserEntity::class,
        UserCredentialEntity::class,
        ExamEntity::class,
        ExamAttemptEntity::class,
        CandidateResponseEntity::class,
        IntegrityEventEntity::class,
        IncidentCaseEntity::class,
        IncidentDecisionEntity::class,
        PolicyActionEntity::class,
        AppealEntity::class,
        AuditEventEntity::class,
        SyncOutboxEntity::class,
        PaperImportEntity::class,
        PaperSectionEntity::class,
        QuestionEntity::class,
        AnswerKeyEntity::class,
        WorkedSolutionEntity::class,
        ValidationIssueEntity::class,
        MarkingSchemeEntity::class,
        ExamResultEntity::class,
        QuestionGradingRecordEntity::class,
        ServerEventReceiptEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun userDao(): UserDao
    abstract fun userCredentialDao(): UserCredentialDao
    abstract fun examDao(): ExamDao
    abstract fun examAttemptDao(): ExamAttemptDao
    abstract fun candidateResponseDao(): CandidateResponseDao
    abstract fun integrityEventDao(): IntegrityEventDao
    abstract fun incidentDao(): IncidentDao
    abstract fun auditEventDao(): AuditEventDao
    abstract fun syncOutboxDao(): SyncOutboxDao

    // Compiler & Exam Authoring DAOs
    abstract fun paperImportDao(): PaperImportDao
    abstract fun paperSectionDao(): PaperSectionDao
    abstract fun questionDao(): QuestionDao
    abstract fun answerKeyDao(): AnswerKeyDao
    abstract fun workedSolutionDao(): WorkedSolutionDao
    abstract fun validationIssueDao(): ValidationIssueDao
    abstract fun markingSchemeDao(): MarkingSchemeDao

    // Scoring & Result DAOs
    abstract fun examResultDao(): ExamResultDao
    abstract fun questionGradingRecordDao(): QuestionGradingRecordDao

    // Step 11: Server Inbox & Event Receipts DAO
    abstract fun serverEventReceiptDao(): ServerEventReceiptDao

    companion object {
        const val DATABASE_NAME = "doomedams.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. paper_imports
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `paper_imports` (
                        `importId` TEXT NOT NULL PRIMARY KEY,
                        `originalFilename` TEXT NOT NULL,
                        `fileType` TEXT NOT NULL,
                        `originalFileHash` TEXT NOT NULL,
                        `fileSizeBytes` INTEGER NOT NULL,
                        `privateStoragePath` TEXT NOT NULL,
                        `importStatus` TEXT NOT NULL,
                        `extractedAt` TEXT,
                        `createdBy` TEXT NOT NULL,
                        `createdAt` TEXT NOT NULL,
                        `updatedAt` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_paper_imports_originalFileHash` ON `paper_imports` (`originalFileHash`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_paper_imports_importStatus` ON `paper_imports` (`importStatus`)")

                // 2. paper_sections
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `paper_sections` (
                        `sectionId` TEXT NOT NULL PRIMARY KEY,
                        `examId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `sectionOrder` INTEGER NOT NULL,
                        `sourceReference` TEXT,
                        `createdAt` TEXT NOT NULL,
                        FOREIGN KEY(`examId`) REFERENCES `exams`(`examId`) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_paper_sections_examId_sectionOrder` ON `paper_sections` (`examId`, `sectionOrder`)")

                // 3. questions
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `questions` (
                        `questionId` TEXT NOT NULL PRIMARY KEY,
                        `examId` TEXT NOT NULL,
                        `sectionId` TEXT,
                        `questionNumber` INTEGER NOT NULL,
                        `questionType` TEXT NOT NULL,
                        `questionText` TEXT NOT NULL,
                        `answerOptionsJson` TEXT NOT NULL,
                        `sourcePage` INTEGER,
                        `sourceStartLine` INTEGER,
                        `sourceEndLine` INTEGER,
                        `sourceSnippet` TEXT,
                        `extractionConfidence` TEXT NOT NULL,
                        `reviewStatus` TEXT NOT NULL,
                        `createdAt` TEXT NOT NULL,
                        `updatedAt` TEXT NOT NULL,
                        FOREIGN KEY(`examId`) REFERENCES `exams`(`examId`) ON DELETE CASCADE,
                        FOREIGN KEY(`sectionId`) REFERENCES `paper_sections`(`sectionId`) ON DELETE SET NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_questions_examId_questionNumber` ON `questions` (`examId`, `questionNumber`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_questions_sectionId` ON `questions` (`sectionId`)")

                // 4. answer_keys
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `answer_keys` (
                        `questionId` TEXT NOT NULL PRIMARY KEY,
                        `examId` TEXT NOT NULL,
                        `expectedAnswerJson` TEXT NOT NULL,
                        `numericalTolerance` REAL,
                        `answerKeySource` TEXT NOT NULL,
                        `verificationStatus` TEXT NOT NULL,
                        `reviewerId` TEXT,
                        `verifiedAt` TEXT,
                        `updatedAt` TEXT NOT NULL,
                        FOREIGN KEY(`questionId`) REFERENCES `questions`(`questionId`) ON DELETE CASCADE,
                        FOREIGN KEY(`examId`) REFERENCES `exams`(`examId`) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_answer_keys_examId` ON `answer_keys` (`examId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_answer_keys_verificationStatus` ON `answer_keys` (`verificationStatus`)")

                // 5. worked_solutions
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `worked_solutions` (
                        `solutionId` TEXT NOT NULL PRIMARY KEY,
                        `questionId` TEXT NOT NULL,
                        `examId` TEXT NOT NULL,
                        `solutionText` TEXT NOT NULL,
                        `sourceReference` TEXT,
                        `verificationStatus` TEXT NOT NULL,
                        `reviewerId` TEXT,
                        `verifiedAt` TEXT,
                        `updatedAt` TEXT NOT NULL,
                        FOREIGN KEY(`questionId`) REFERENCES `questions`(`questionId`) ON DELETE CASCADE,
                        FOREIGN KEY(`examId`) REFERENCES `exams`(`examId`) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_worked_solutions_questionId` ON `worked_solutions` (`questionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_worked_solutions_examId` ON `worked_solutions` (`examId`)")

                // 6. validation_issues
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `validation_issues` (
                        `issueId` TEXT NOT NULL PRIMARY KEY,
                        `examId` TEXT NOT NULL,
                        `importId` TEXT,
                        `questionId` TEXT,
                        `severity` TEXT NOT NULL,
                        `issueCode` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `sourceReference` TEXT,
                        `isResolved` INTEGER NOT NULL,
                        `resolvedBy` TEXT,
                        `resolvedAt` TEXT,
                        `createdAt` TEXT NOT NULL,
                        FOREIGN KEY(`examId`) REFERENCES `exams`(`examId`) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_validation_issues_examId_severity` ON `validation_issues` (`examId`, `severity`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_validation_issues_questionId` ON `validation_issues` (`questionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_validation_issues_isResolved` ON `validation_issues` (`isResolved`)")

                // 7. marking_schemes
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `marking_schemes` (
                        `schemeId` TEXT NOT NULL PRIMARY KEY,
                        `examId` TEXT NOT NULL,
                        `version` INTEGER NOT NULL,
                        `correctScaledMark` INTEGER NOT NULL,
                        `incorrectScaledMark` INTEGER NOT NULL,
                        `unansweredScaledMark` INTEGER NOT NULL,
                        `allowNegativeMarking` INTEGER NOT NULL,
                        `sectionDefaultsJson` TEXT NOT NULL,
                        `questionOverridesJson` TEXT NOT NULL,
                        `updatedAt` TEXT NOT NULL,
                        FOREIGN KEY(`examId`) REFERENCES `exams`(`examId`) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_marking_schemes_examId` ON `marking_schemes` (`examId`)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. exam_results
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `exam_results` (
                        `resultId` TEXT NOT NULL PRIMARY KEY,
                        `attemptId` TEXT NOT NULL,
                        `examId` TEXT NOT NULL,
                        `studentId` TEXT NOT NULL,
                        `version` INTEGER NOT NULL,
                        `resultStatus` TEXT NOT NULL,
                        `totalScaledScore` INTEGER NOT NULL,
                        `maxPossibleScaledScore` INTEGER NOT NULL,
                        `correctCount` INTEGER NOT NULL,
                        `incorrectCount` INTEGER NOT NULL,
                        `unansweredCount` INTEGER NOT NULL,
                        `partiallyCorrectCount` INTEGER NOT NULL,
                        `pendingManualCount` INTEGER NOT NULL,
                        `markingSchemeVersion` INTEGER NOT NULL,
                        `scoringEngineVersion` TEXT NOT NULL,
                        `sectionTotalsJson` TEXT NOT NULL,
                        `resultHash` TEXT NOT NULL,
                        `holdReason` TEXT,
                        `scoredAt` TEXT NOT NULL,
                        `publishedAt` TEXT,
                        `createdAt` TEXT NOT NULL,
                        `updatedAt` TEXT NOT NULL,
                        FOREIGN KEY(`attemptId`) REFERENCES `exam_attempts`(`attemptId`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`examId`) REFERENCES `exams`(`examId`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`studentId`) REFERENCES `users`(`userId`) ON UPDATE NO ACTION ON DELETE RESTRICT
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_exam_results_attemptId` ON `exam_results` (`attemptId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_exam_results_examId` ON `exam_results` (`examId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_exam_results_studentId` ON `exam_results` (`studentId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_exam_results_resultStatus` ON `exam_results` (`resultStatus`)")

                // 2. question_grading_records
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `question_grading_records` (
                        `recordId` TEXT NOT NULL PRIMARY KEY,
                        `resultId` TEXT NOT NULL,
                        `questionId` TEXT NOT NULL,
                        `sectionId` TEXT,
                        `questionNumber` INTEGER NOT NULL,
                        `questionType` TEXT NOT NULL,
                        `submittedAnswerJson` TEXT,
                        `correctAnswerSnapshot` TEXT,
                        `answerClassification` TEXT NOT NULL,
                        `marksAwarded` INTEGER NOT NULL,
                        `maxMarks` INTEGER NOT NULL,
                        `explanationCode` TEXT NOT NULL,
                        `calculationMetadataJson` TEXT,
                        FOREIGN KEY(`resultId`) REFERENCES `exam_results`(`resultId`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`questionId`) REFERENCES `questions`(`questionId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_question_grading_records_resultId` ON `question_grading_records` (`resultId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_question_grading_records_questionId` ON `question_grading_records` (`questionId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_question_grading_records_resultId_questionId` ON `question_grading_records` (`resultId`, `questionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_question_grading_records_answerClassification` ON `question_grading_records` (`answerClassification`)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `incident_appeals` (
                        `appealId` TEXT NOT NULL PRIMARY KEY,
                        `incidentId` TEXT NOT NULL,
                        `attemptId` TEXT NOT NULL,
                        `studentId` TEXT NOT NULL,
                        `challengedAction` TEXT NOT NULL,
                        `appealReason` TEXT NOT NULL,
                        `evidenceReferences` TEXT,
                        `appealStatus` TEXT NOT NULL,
                        `assignedReviewerId` TEXT,
                        `reviewerDecision` TEXT,
                        `outcomeReason` TEXT,
                        `submittedAt` TEXT NOT NULL,
                        `resolvedAt` TEXT,
                        `updatedAt` TEXT NOT NULL,
                        FOREIGN KEY(`incidentId`) REFERENCES `incident_cases`(`incidentId`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                        FOREIGN KEY(`studentId`) REFERENCES `users`(`userId`) ON UPDATE NO ACTION ON DELETE RESTRICT
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_incident_appeals_incidentId` ON `incident_appeals` (`incidentId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_incident_appeals_studentId` ON `incident_appeals` (`studentId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_incident_appeals_appealStatus` ON `incident_appeals` (`appealStatus`)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `server_event_receipts` (
                        `receiptId` TEXT NOT NULL PRIMARY KEY,
                        `eventId` TEXT NOT NULL,
                        `attemptId` TEXT NOT NULL,
                        `studentId` TEXT NOT NULL,
                        `eventType` TEXT NOT NULL,
                        `payloadHash` TEXT NOT NULL,
                        `idempotencyKey` TEXT,
                        `processingStatus` TEXT NOT NULL,
                        `resultingVersion` INTEGER NOT NULL,
                        `rejectionReason` TEXT,
                        `serverReceivedAt` TEXT NOT NULL,
                        `processedAt` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_server_event_receipts_eventId` ON `server_event_receipts` (`eventId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_server_event_receipts_attemptId` ON `server_event_receipts` (`attemptId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_server_event_receipts_studentId` ON `server_event_receipts` (`studentId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_server_event_receipts_processingStatus` ON `server_event_receipts` (`processingStatus`)")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .addCallback(DatabaseSecurityCallback)
                    .build()
                INSTANCE = instance
                instance
            }
        }

        /**
         * Enforces foreign key constraints and creates database triggers
         * preventing any UPDATE or DELETE operations on the audit log.
         */
        val DatabaseSecurityCallback = object : Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                db.execSQL("PRAGMA foreign_keys = ON;")
                createAuditImmutabilityTriggers(db)
            }

            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                db.execSQL("PRAGMA foreign_keys = ON;")
                createAuditImmutabilityTriggers(db)
            }

            private fun createAuditImmutabilityTriggers(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TRIGGER IF NOT EXISTS trg_prevent_audit_update
                    BEFORE UPDATE ON audit_events
                    BEGIN
                        SELECT RAISE(ABORT, 'IMMUTABILITY_VIOLATION: Audit event records cannot be modified.');
                    END;
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TRIGGER IF NOT EXISTS trg_prevent_audit_delete
                    BEFORE DELETE ON audit_events
                    BEGIN
                        SELECT RAISE(ABORT, 'IMMUTABILITY_VIOLATION: Audit event records cannot be deleted.');
                    END;
                    """.trimIndent()
                )
            }
        }
    }
}
