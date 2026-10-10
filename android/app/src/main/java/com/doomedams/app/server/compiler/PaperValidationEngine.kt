package com.doomedams.app.server.compiler

import com.doomedams.app.database.entity.AnswerKeyEntity
import com.doomedams.app.database.entity.ExamEntity
import com.doomedams.app.database.entity.MarkingSchemeEntity
import com.doomedams.app.database.entity.QuestionEntity
import com.doomedams.app.database.entity.ValidationIssueEntity
import org.json.JSONArray

data class ValidationReport(
    val isReadyForPublication: Boolean,
    val blockingErrors: List<String>,
    val warnings: List<String>,
    val unverifiedAnswerKeyCount: Int,
    val totalQuestionCount: Int
)

/**
 * Authoritative Server-Side Validation Engine for Examination Publication.
 * Ensures an examination paper draft meets all structural, consistency, and integrity criteria
 * before publication is permitted.
 */
object PaperValidationEngine {

    fun validateDraft(
        exam: ExamEntity,
        questions: List<QuestionEntity>,
        answerKeys: List<AnswerKeyEntity>,
        markingScheme: MarkingSchemeEntity?,
        validationIssues: List<ValidationIssueEntity>
    ): ValidationReport {
        val blockingErrors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // 1. Question count check
        if (questions.isEmpty()) {
            blockingErrors.add("The examination paper contains zero questions.")
        }

        // 2. Exam Duration check
        if (exam.durationSeconds <= 0) {
            blockingErrors.add("Examination duration must be a positive integer in seconds.")
        }

        // 3. Exam Title check
        if (exam.title.isBlank()) {
            blockingErrors.add("Examination title cannot be empty.")
        }

        // 4. Duplicate Question Numbers & Question Integrity
        val seenNumbers = mutableSetOf<Int>()
        for (q in questions) {
            if (seenNumbers.contains(q.questionNumber)) {
                blockingErrors.add("Duplicate question number detected: Q${q.questionNumber}.")
            }
            seenNumbers.add(q.questionNumber)

            if (q.questionText.isBlank()) {
                blockingErrors.add("Question Q${q.questionNumber} has blank question text.")
            }

            if (q.questionType == "SINGLE_SELECT" || q.questionType == "MULTI_SELECT") {
                val optsCount = try {
                    JSONArray(q.answerOptionsJson).length()
                } catch (e: Exception) {
                    0
                }
                if (optsCount < 2) {
                    blockingErrors.add("Multiple choice question Q${q.questionNumber} must have at least 2 options (found $optsCount).")
                }
            }

            if (q.questionType == "UNKNOWN") {
                warnings.add("Question Q${q.questionNumber} has unclassified type (UNKNOWN). Review recommended.")
            }
        }

        // 5. Answer Key Coverage & Verification
        var unverifiedKeysCount = 0
        for (q in questions) {
            val key = answerKeys.find { it.questionId == q.questionId }
            if (key == null) {
                blockingErrors.add("Question Q${q.questionNumber} is missing an answer key.")
            } else {
                val expectedAns = key.expectedAnswerJson.trim().removeSurrounding("\"")
                if (expectedAns.isEmpty() || expectedAns == "[]") {
                    blockingErrors.add("Question Q${q.questionNumber} has an empty expected answer key.")
                }
                if (key.verificationStatus != "VERIFIED") {
                    unverifiedKeysCount++
                    warnings.add("Answer key for Q${q.questionNumber} is unverified.")
                }
            }
        }

        // 6. Marking Scheme Validation
        if (markingScheme != null) {
            if (markingScheme.correctScaledMark <= 0) {
                blockingErrors.add("Marking scheme must award positive marks for correct answers.")
            }
            if (markingScheme.allowNegativeMarking && markingScheme.incorrectScaledMark > 0) {
                blockingErrors.add("Negative marking penalty cannot be a positive value.")
            }
        } else {
            blockingErrors.add("No marking scheme configured for this examination.")
        }

        // 7. Unresolved Database Issues
        for (issue in validationIssues) {
            if (!issue.isResolved) {
                if (issue.severity == "ERROR") {
                    blockingErrors.add("Unresolved blocking issue: [${issue.issueCode}] ${issue.description}")
                } else if (issue.severity == "WARNING") {
                    warnings.add("Unresolved warning: [${issue.issueCode}] ${issue.description}")
                }
            }
        }

        return ValidationReport(
            isReadyForPublication = blockingErrors.isEmpty(),
            blockingErrors = blockingErrors,
            warnings = warnings,
            unverifiedAnswerKeyCount = unverifiedKeysCount,
            totalQuestionCount = questions.size
        )
    }
}
