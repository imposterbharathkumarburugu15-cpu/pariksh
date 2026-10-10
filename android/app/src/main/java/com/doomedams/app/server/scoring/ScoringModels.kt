package com.doomedams.app.server.scoring

/**
 * Categorical classification of a candidate's answer for an examination question.
 */
enum class AnswerClassification {
    CORRECT,
    INCORRECT,
    PARTIALLY_CORRECT,
    UNANSWERED,
    PENDING_MANUAL_GRADING,
    INVALID_RESPONSE,
    UNGRADABLE
}

/**
 * Structured diagnostic reason code explaining how marks were determined.
 */
enum class ScoringExplanationCode {
    MATCHED_VERIFIED_ANSWER,
    ANSWER_MISMATCH,
    NO_RESPONSE,
    PARTIAL_CREDIT_RULE_APPLIED,
    NUMERICAL_TOLERANCE_MATCH,
    INVALID_RESPONSE_FORMAT,
    MANUAL_GRADING_REQUIRED,
    UNSUPPORTED_QUESTION_TYPE,
    MISSING_ANSWER_KEY,
    UNVERIFIED_ANSWER_KEY
}

/**
 * Configurable multi-select scoring policy.
 */
enum class MultiSelectPolicy {
    EXACT_MATCH,
    PARTIAL_CREDIT
}

/**
 * Resolved marking rule applicable to a specific question after checking precedence:
 * 1. Question-level override
 * 2. Section-level default
 * 3. Examination-level default
 */
data class EffectiveMarkingRule(
    val correctScaledMark: Long,      // Scaled integer (e.g. 400 for +4.00)
    val incorrectScaledMark: Long,    // Scaled integer (e.g. -100 for -1.00)
    val unansweredScaledMark: Long,   // Scaled integer (e.g. 0)
    val allowNegativeMarking: Boolean,
    val multiSelectPolicy: MultiSelectPolicy = MultiSelectPolicy.EXACT_MATCH,
    val ruleSource: String            // "QUESTION_OVERRIDE", "SECTION_DEFAULT", "EXAM_DEFAULT"
)

/**
 * Detailed question-level grading result record.
 */
data class QuestionScoreResult(
    val questionId: String,
    val sectionId: String?,
    val questionNumber: Int,
    val questionType: String,
    val submittedAnswer: String?,
    val correctAnswerSnapshot: String?,
    val classification: AnswerClassification,
    val marksAwarded: Long,           // Scaled integer
    val maxPossibleMarks: Long,       // Scaled integer
    val explanationCode: ScoringExplanationCode,
    val effectiveRule: EffectiveMarkingRule,
    val calculationMetadata: String? = null
)

/**
 * Reconciled section-level summary scores.
 */
data class SectionScoreResult(
    val sectionId: String,
    val title: String,
    val totalQuestions: Int,
    val answeredCount: Int,
    val unansweredCount: Int,
    val correctCount: Int,
    val incorrectCount: Int,
    val partiallyCorrectCount: Int,
    val pendingManualCount: Int,
    val totalScaledScore: Long,
    val maxPossibleScaledScore: Long
)

/**
 * Complete, auditable, and reproducible examination attempt scoring result.
 */
data class ExaminationScoringResult(
    val attemptId: String,
    val examId: String,
    val studentId: String,
    val markingSchemeVersion: Int,
    val publishedExamVersion: Int,
    val scoringEngineVersion: String,
    val totalQuestions: Int,
    val answeredCount: Int,
    val unansweredCount: Int,
    val correctCount: Int,
    val incorrectCount: Int,
    val partiallyCorrectCount: Int,
    val pendingManualCount: Int,
    val totalMarksEarned: Long,       // Sum of all positive & partial credits
    val totalPenaltyApplied: Long,    // Sum of all negative marking penalties
    val totalScaledScore: Long,       // Reconciled total score
    val maxPossibleScaledScore: Long, // Sum of max possible marks across questions
    val sectionScores: List<SectionScoreResult>,
    val questionScores: List<QuestionScoreResult>,
    val gradingStatus: String,        // "COMPLETED" or "PENDING_MANUAL_REVIEW"
    val resultHash: String            // Canonical SHA-256 digest of results
)
