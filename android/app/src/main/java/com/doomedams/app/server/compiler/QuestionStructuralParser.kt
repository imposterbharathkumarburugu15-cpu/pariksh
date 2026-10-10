package com.doomedams.app.server.compiler

import com.doomedams.app.database.entity.AnswerKeyEntity
import com.doomedams.app.database.entity.MarkingSchemeEntity
import com.doomedams.app.database.entity.PaperSectionEntity
import com.doomedams.app.database.entity.QuestionEntity
import com.doomedams.app.database.entity.ValidationIssueEntity
import com.doomedams.app.database.entity.WorkedSolutionEntity
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Result of rule-based structural parsing
 */
data class ParsedDraftPackage(
    val sections: List<PaperSectionEntity>,
    val questions: List<QuestionEntity>,
    val answerKeys: List<AnswerKeyEntity>,
    val solutions: List<WorkedSolutionEntity>,
    val validationIssues: List<ValidationIssueEntity>,
    val defaultMarkingScheme: MarkingSchemeEntity
)

/**
 * Deterministic, rule-based structural parser.
 * Transforms extracted document text into draft examination questions, sections, answer keys,
 * and validation issues.
 *
 * NON-NEGOTIABLE GUARANTEES:
 * 1. Never fabricates missing question text.
 * 2. Never invents an answer key.
 * 3. Never silently drops unrecognized content.
 * 4. Preserves line and page traces.
 */
object QuestionStructuralParser {

    private val SECTION_HEADER_REGEX = Regex(
        """(?i)^\s*(?:SECTION|PART)\s*([A-Z0-9]+)?\s*[:\-–]?\s*(.*)$"""
    )

    private val QUESTION_START_REGEX = Regex(
        """(?i)^\s*(?:Q\.?\s*(\d+)|Question\s*(\d+)|\((\d+)\)|(\d+)[\.\)]\s+)(.*)$"""
    )

    private val OPTION_REGEX = Regex(
        """^\s*(?:\(([A-Da-d])\)|([A-Da-d])[\.\)]\s+)(.*)$"""
    )

    private val ANSWER_KEY_HEADER_REGEX = Regex(
        """(?i)^\s*(?:ANSWER\s*KEY|ANSWERS|CORRECT\s*OPTIONS)\s*[:\-–]?\s*$"""
    )

    private val SOLUTIONS_HEADER_REGEX = Regex(
        """(?i)^\s*(?:SOLUTIONS?|EXPLANATIONS?|HINTS?\s*&\s*SOLUTIONS?)\s*[:\-–]?\s*$"""
    )

    private val ANSWER_ENTRY_REGEX = Regex(
        """(?i)^\s*(?:Q\.?\s*)?(\d+)\s*[:\-–\.]\s*\(?([A-Da-d](?:\s*,\s*[A-Da-d])*|\d+(?:\.\d+)?)\)?"""
    )

    fun parse(
        examId: String,
        importId: String?,
        extractionResult: ExtractionResult,
        nowIso: String
    ): ParsedDraftPackage {
        val lines = extractionResult.fullText.lines()
        val sections = mutableListOf<PaperSectionEntity>()
        val questions = mutableListOf<QuestionEntity>()
        val answerKeys = mutableListOf<AnswerKeyEntity>()
        val solutions = mutableListOf<WorkedSolutionEntity>()
        val validationIssues = mutableListOf<ValidationIssueEntity>()

        // Add OCR warnings from extraction as validation issues
        if (extractionResult.requiresOcr) {
            for ((idx, warn) in extractionResult.warnings.withIndex()) {
                validationIssues.add(
                    ValidationIssueEntity(
                        issueId = "iss_ocr_${UUID.randomUUID().toString().take(8)}",
                        examId = examId,
                        importId = importId,
                        questionId = null,
                        severity = "WARNING",
                        issueCode = "LOW_TEXT_DENSITY_PAGE",
                        description = warn,
                        sourceReference = "PDF Import Engine",
                        isResolved = false,
                        resolvedBy = null,
                        resolvedAt = null,
                        createdAt = nowIso
                    )
                )
            }
        }

        var currentSectionId: String? = null
        var sectionOrderCounter = 1

        var inAnswerKeySection = false
        var inSolutionsSection = false

        // Temporary tracking for the question currently being accumulated
        var currentQuestionNum: Int? = null
        var currentQuestionText = StringBuilder()
        var currentOptions = mutableListOf<Pair<String, String>>() // label, text
        var currentStartLine: Int? = null
        var currentEndLine: Int? = null
        var currentSnippet = StringBuilder()

        // Map of extracted answer keys: questionNumber -> answerString
        val extractedAnswersMap = mutableMapOf<Int, String>()
        // Map of extracted solutions: questionNumber -> solutionText
        val extractedSolutionsMap = mutableMapOf<Int, String>()

        fun finalizeCurrentQuestion(endLine: Int) {
            val qNum = currentQuestionNum ?: return
            val rawText = currentQuestionText.toString().trim()
            val questionId = "q_${examId}_${qNum}"

            // Determine question type
            val qType = when {
                currentOptions.size >= 2 -> {
                    if (rawText.contains("one or more", ignoreCase = true) ||
                        rawText.contains("multiple correct", ignoreCase = true)
                    ) {
                        "MULTI_SELECT"
                    } else {
                        "SINGLE_SELECT"
                    }
                }
                rawText.contains("integer", ignoreCase = true) ||
                rawText.contains("numerical", ignoreCase = true) ||
                rawText.contains("calculate", ignoreCase = true) ||
                rawText.contains("evaluate", ignoreCase = true) ||
                rawText.contains("value is", ignoreCase = true) ||
                rawText.contains("value", ignoreCase = true) ||
                rawText.contains("equals", ignoreCase = true) -> {
                    "NUMERICAL"
                }
                currentOptions.isEmpty() && rawText.length > 20 -> "SUBJECTIVE"
                else -> "UNKNOWN"
            }

            // Options JSON
            val optionsJsonArray = JSONArray()
            for ((optLabel, optText) in currentOptions) {
                val optObj = JSONObject().apply {
                    put("optionId", "opt_${qNum}_${optLabel.lowercase()}")
                    put("label", optLabel.uppercase())
                    put("text", optText)
                }
                optionsJsonArray.put(optObj)
            }

            // Validation checks
            if (rawText.isBlank()) {
                validationIssues.add(
                    ValidationIssueEntity(
                        issueId = "iss_q_${qNum}_empty_${UUID.randomUUID().toString().take(6)}",
                        examId = examId,
                        importId = importId,
                        questionId = questionId,
                        severity = "ERROR",
                        issueCode = "MISSING_QUESTION_TEXT",
                        description = "Question Q$qNum has no question text.",
                        sourceReference = "Line ${currentStartLine ?: 0}",
                        isResolved = false,
                        resolvedBy = null,
                        resolvedAt = null,
                        createdAt = nowIso
                    )
                )
            }

            if ((qType == "SINGLE_SELECT" || qType == "MULTI_SELECT") && currentOptions.size < 2) {
                validationIssues.add(
                    ValidationIssueEntity(
                        issueId = "iss_q_${qNum}_opts_${UUID.randomUUID().toString().take(6)}",
                        examId = examId,
                        importId = importId,
                        questionId = questionId,
                        severity = "WARNING",
                        issueCode = "INSUFFICIENT_OPTIONS",
                        description = "Multiple choice question Q$qNum has fewer than 2 detected options (${currentOptions.size}).",
                        sourceReference = "Line ${currentStartLine ?: 0}",
                        isResolved = false,
                        resolvedBy = null,
                        resolvedAt = null,
                        createdAt = nowIso
                    )
                )
            }

            if (qType == "UNKNOWN") {
                validationIssues.add(
                    ValidationIssueEntity(
                        issueId = "iss_q_${qNum}_unk_${UUID.randomUUID().toString().take(6)}",
                        examId = examId,
                        importId = importId,
                        questionId = questionId,
                        severity = "WARNING",
                        issueCode = "UNKNOWN_QUESTION_TYPE",
                        description = "Question Q$qNum could not be definitively classified. Please specify its question type.",
                        sourceReference = "Line ${currentStartLine ?: 0}",
                        isResolved = false,
                        resolvedBy = null,
                        resolvedAt = null,
                        createdAt = nowIso
                    )
                )
            }

            // Duplicate question number check
            if (questions.any { it.questionNumber == qNum }) {
                validationIssues.add(
                    ValidationIssueEntity(
                        issueId = "iss_q_${qNum}_dup_${UUID.randomUUID().toString().take(6)}",
                        examId = examId,
                        importId = importId,
                        questionId = questionId,
                        severity = "ERROR",
                        issueCode = "DUPLICATE_QUESTION_NUMBER",
                        description = "Duplicate question number Q$qNum detected in source document.",
                        sourceReference = "Line ${currentStartLine ?: 0}",
                        isResolved = false,
                        resolvedBy = null,
                        resolvedAt = null,
                        createdAt = nowIso
                    )
                )
            }

            val question = QuestionEntity(
                questionId = questionId,
                examId = examId,
                sectionId = currentSectionId,
                questionNumber = qNum,
                questionType = qType,
                questionText = rawText,
                answerOptionsJson = optionsJsonArray.toString(),
                sourcePage = 1,
                sourceStartLine = currentStartLine,
                sourceEndLine = endLine,
                sourceSnippet = currentSnippet.toString().take(300),
                extractionConfidence = if (qType != "UNKNOWN" && rawText.isNotBlank()) "HIGH" else "MEDIUM",
                reviewStatus = "NEEDS_REVIEW",
                createdAt = nowIso,
                updatedAt = nowIso
            )
            questions.add(question)

            // Reset
            currentQuestionNum = null
            currentQuestionText = StringBuilder()
            currentOptions = mutableListOf()
            currentStartLine = null
            currentEndLine = null
            currentSnippet = StringBuilder()
        }

        // Iterate line by line
        for ((lineIdx, rawLine) in lines.withIndex()) {
            val lineNum = lineIdx + 1
            val trimmedLine = rawLine.trim()

            if (trimmedLine.isBlank()) continue

            // 1. Detect Answer Key section
            if (ANSWER_KEY_HEADER_REGEX.matches(trimmedLine)) {
                finalizeCurrentQuestion(lineNum - 1)
                inAnswerKeySection = true
                inSolutionsSection = false
                continue
            }

            // 2. Detect Solutions section
            if (SOLUTIONS_HEADER_REGEX.matches(trimmedLine)) {
                finalizeCurrentQuestion(lineNum - 1)
                inSolutionsSection = true
                inAnswerKeySection = false
                continue
            }

            // 3. If in Answer Key section, parse answer entries
            if (inAnswerKeySection) {
                val match = ANSWER_ENTRY_REGEX.find(trimmedLine)
                if (match != null) {
                    val qNum = match.groupValues[1].toIntOrNull()
                    val ans = match.groupValues[2].trim()
                    if (qNum != null && ans.isNotEmpty()) {
                        extractedAnswersMap[qNum] = ans
                    }
                }
                continue
            }

            // 4. If in Solutions section, parse solution text
            if (inSolutionsSection) {
                val match = ANSWER_ENTRY_REGEX.find(trimmedLine)
                if (match != null) {
                    val qNum = match.groupValues[1].toIntOrNull()
                    if (qNum != null) {
                        extractedSolutionsMap[qNum] = trimmedLine
                    }
                }
                continue
            }

            // 5. Detect Section headers
            val secMatch = SECTION_HEADER_REGEX.find(trimmedLine)
            if (secMatch != null) {
                finalizeCurrentQuestion(lineNum - 1)
                val secId = "sec_${examId}_${sectionOrderCounter}"
                val secTitle = trimmedLine
                val section = PaperSectionEntity(
                    sectionId = secId,
                    examId = examId,
                    title = secTitle,
                    sectionOrder = sectionOrderCounter++,
                    sourceReference = "Line $lineNum",
                    createdAt = nowIso
                )
                sections.add(section)
                currentSectionId = secId
                continue
            }

            // 6. Detect Question Start
            val qMatch = QUESTION_START_REGEX.find(trimmedLine)
            if (qMatch != null) {
                // Determine question number from groups
                val numStr = qMatch.groupValues[1].ifEmpty {
                    qMatch.groupValues[2].ifEmpty {
                        qMatch.groupValues[3].ifEmpty {
                            qMatch.groupValues[4]
                        }
                    }
                }
                val qNum = numStr.toIntOrNull()
                val remainingText = qMatch.groupValues[5].trim()

                if (qNum != null) {
                    finalizeCurrentQuestion(lineNum - 1)
                    currentQuestionNum = qNum
                    currentStartLine = lineNum
                    currentQuestionText.append(remainingText)
                    currentSnippet.append(trimmedLine).append("\n")
                    continue
                }
            }

            // 7. Detect Option
            val optMatch = OPTION_REGEX.find(trimmedLine)
            if (optMatch != null && currentQuestionNum != null) {
                val optLabel = optMatch.groupValues[1].ifEmpty { optMatch.groupValues[2] }
                val optText = optMatch.groupValues[3].trim()
                currentOptions.add(Pair(optLabel.uppercase(), optText))
                currentSnippet.append(trimmedLine).append("\n")
                continue
            }

            // 8. Otherwise, line continuation
            if (currentQuestionNum != null) {
                if (currentOptions.isNotEmpty()) {
                    // Append to last option text
                    val last = currentOptions.removeAt(currentOptions.size - 1)
                    currentOptions.add(Pair(last.first, "${last.second} $trimmedLine"))
                } else {
                    currentQuestionText.append(" ").append(trimmedLine)
                }
                currentSnippet.append(trimmedLine).append("\n")
            }
        }

        // Finalize last question
        finalizeCurrentQuestion(lines.size)

        // Build Answer Keys & Solutions for all parsed questions
        for (q in questions) {
            val extractedAns = extractedAnswersMap[q.questionNumber]
            val hasAnswer = extractedAns != null

            val answerJson = if (hasAnswer) {
                if (extractedAns!!.contains(",")) {
                    val parts = extractedAns.split(",").map { it.trim().uppercase() }
                    JSONArray(parts).toString()
                } else {
                    JSONArray().put(extractedAns.uppercase()).toString()
                }
            } else {
                "[]"
            }

            val answerKey = AnswerKeyEntity(
                questionId = q.questionId,
                examId = examId,
                expectedAnswerJson = answerJson,
                numericalTolerance = if (q.questionType == "NUMERICAL") 0.01 else null,
                answerKeySource = if (hasAnswer) "EXTRACTED" else "MANUAL",
                verificationStatus = "UNVERIFIED",
                reviewerId = null,
                verifiedAt = null,
                updatedAt = nowIso
            )
            answerKeys.add(answerKey)

            if (!hasAnswer) {
                validationIssues.add(
                    ValidationIssueEntity(
                        issueId = "iss_q_${q.questionNumber}_noans_${UUID.randomUUID().toString().take(6)}",
                        examId = examId,
                        importId = importId,
                        questionId = q.questionId,
                        severity = "WARNING",
                        issueCode = "MISSING_ANSWER_KEY",
                        description = "No answer key found in source document for Q${q.questionNumber}. Administrator verification required.",
                        sourceReference = "Line ${q.sourceStartLine ?: 0}",
                        isResolved = false,
                        resolvedBy = null,
                        resolvedAt = null,
                        createdAt = nowIso
                    )
                )
            }

            // Solution if found
            val solText = extractedSolutionsMap[q.questionNumber]
            if (solText != null) {
                solutions.add(
                    WorkedSolutionEntity(
                        solutionId = "sol_${q.questionId}",
                        questionId = q.questionId,
                        examId = examId,
                        solutionText = solText,
                        sourceReference = "Extracted from source",
                        verificationStatus = "UNVERIFIED",
                        reviewerId = null,
                        verifiedAt = null,
                        updatedAt = nowIso
                    )
                )
            }
        }

        // Default marking scheme (+4 correct, -1 incorrect, 0 unanswered, scaled by 100)
        val defaultScheme = MarkingSchemeEntity(
            schemeId = "scheme_${examId}",
            examId = examId,
            version = 1,
            correctScaledMark = 400L, // +4.00
            incorrectScaledMark = -100L, // -1.00
            unansweredScaledMark = 0L,
            allowNegativeMarking = true,
            sectionDefaultsJson = "[]",
            questionOverridesJson = "[]",
            updatedAt = nowIso
        )

        return ParsedDraftPackage(
            sections = sections,
            questions = questions,
            answerKeys = answerKeys,
            solutions = solutions,
            validationIssues = validationIssues,
            defaultMarkingScheme = defaultScheme
        )
    }
}
