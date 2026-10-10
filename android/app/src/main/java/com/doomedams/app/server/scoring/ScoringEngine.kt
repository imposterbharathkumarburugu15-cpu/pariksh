package com.doomedams.app.server.scoring

import com.doomedams.app.database.entity.AnswerKeyEntity
import com.doomedams.app.database.entity.CandidateResponseEntity
import com.doomedams.app.database.entity.ExamAttemptEntity
import com.doomedams.app.database.entity.ExamEntity
import com.doomedams.app.database.entity.MarkingSchemeEntity
import com.doomedams.app.database.entity.PaperSectionEntity
import com.doomedams.app.database.entity.QuestionEntity
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.math.abs

/**
 * Deterministic, offline examination scoring engine for DOOMEDAMS.
 *
 * GUARANTEES:
 * 1. Strict numeric precision: Marks are calculated and stored as scaled integers (100 units = 1.00 mark).
 * 2. Absolute determinism: Identical immutable inputs always produce the identical result and SHA-256 hash.
 * 3. Precedence hierarchy: Question-level override > Section-level default > Exam-level default.
 * 4. Zero hallucination: Missing answers, unsupported types, or subjective questions are never fabricated.
 */
object ScoringEngine {

    const val SCORING_ENGINE_VERSION = "1.0.0"

    /**
     * Resolves the effective marking rule for a question following strict precedence:
     * 1. Question-specific override (if present in questionOverridesJson)
     * 2. Section-specific default (if present in sectionDefaultsJson)
     * 3. Examination-level default
     */
    fun resolveEffectiveMarkingRule(
        question: QuestionEntity,
        section: PaperSectionEntity?,
        markingScheme: MarkingSchemeEntity
    ): EffectiveMarkingRule {
        // 1. Check Question Override
        try {
            val qOverrides = JSONObject(markingScheme.questionOverridesJson)
            if (qOverrides.has(question.questionId)) {
                val qRuleObj = qOverrides.getJSONObject(question.questionId)
                return parseRuleObject(qRuleObj, "QUESTION_OVERRIDE", markingScheme)
            }
        } catch (_: Exception) {}

        // 2. Check Section Override
        if (question.sectionId != null) {
            try {
                val sDefaults = JSONObject(markingScheme.sectionDefaultsJson)
                if (sDefaults.has(question.sectionId)) {
                    val sRuleObj = sDefaults.getJSONObject(question.sectionId)
                    return parseRuleObject(sRuleObj, "SECTION_DEFAULT", markingScheme)
                }
            } catch (_: Exception) {}
        }

        // 3. Fallback to Exam Default
        val allowNegative = markingScheme.allowNegativeMarking
        val incorrectMark = if (allowNegative) {
            if (markingScheme.incorrectScaledMark > 0) -markingScheme.incorrectScaledMark else markingScheme.incorrectScaledMark
        } else {
            0L
        }

        return EffectiveMarkingRule(
            correctScaledMark = markingScheme.correctScaledMark,
            incorrectScaledMark = incorrectMark,
            unansweredScaledMark = markingScheme.unansweredScaledMark,
            allowNegativeMarking = allowNegative,
            multiSelectPolicy = MultiSelectPolicy.EXACT_MATCH,
            ruleSource = "EXAM_DEFAULT"
        )
    }

    private fun parseRuleObject(
        obj: JSONObject,
        source: String,
        fallbackScheme: MarkingSchemeEntity
    ): EffectiveMarkingRule {
        val allowNegative = if (obj.has("allowNegativeMarking")) {
            obj.getBoolean("allowNegativeMarking")
        } else {
            fallbackScheme.allowNegativeMarking
        }

        val correct = if (obj.has("correctScaledMark")) {
            obj.getLong("correctScaledMark")
        } else if (obj.has("correct")) {
            obj.getLong("correct")
        } else {
            fallbackScheme.correctScaledMark
        }

        val rawIncorrect = if (obj.has("incorrectScaledMark")) {
            obj.getLong("incorrectScaledMark")
        } else if (obj.has("incorrect")) {
            obj.getLong("incorrect")
        } else {
            fallbackScheme.incorrectScaledMark
        }

        val incorrect = if (allowNegative) {
            if (rawIncorrect > 0) -rawIncorrect else rawIncorrect
        } else {
            0L
        }

        val unanswered = if (obj.has("unansweredScaledMark")) {
            obj.getLong("unansweredScaledMark")
        } else if (obj.has("unanswered")) {
            obj.getLong("unanswered")
        } else {
            fallbackScheme.unansweredScaledMark
        }

        val policyStr = obj.optString("multiSelectPolicy", "EXACT_MATCH")
        val policy = try {
            MultiSelectPolicy.valueOf(policyStr.uppercase())
        } catch (_: Exception) {
            MultiSelectPolicy.EXACT_MATCH
        }

        return EffectiveMarkingRule(
            correctScaledMark = correct,
            incorrectScaledMark = incorrect,
            unansweredScaledMark = unanswered,
            allowNegativeMarking = allowNegative,
            multiSelectPolicy = policy,
            ruleSource = source
        )
    }

    /**
     * Preview / simulation helper to score a question without requiring DB entities.
     */
    fun simulateQuestion(
        questionType: String,
        expectedAnswer: String,
        submittedAnswer: String?,
        numericalTolerance: Double? = null,
        correctScaledMark: Long = 400L,
        incorrectScaledMark: Long = -100L,
        unansweredScaledMark: Long = 0L,
        allowNegativeMarking: Boolean = true,
        multiSelectPolicy: MultiSelectPolicy = MultiSelectPolicy.EXACT_MATCH
    ): QuestionScoreResult {
        val dummyQuestion = QuestionEntity(
            questionId = "sim_q_1",
            examId = "sim_exam",
            sectionId = "sim_sec",
            questionNumber = 1,
            questionType = questionType.uppercase(),
            questionText = "Simulation Question",
            answerOptionsJson = "[]",
            sourcePage = null,
            sourceStartLine = null,
            sourceEndLine = null,
            sourceSnippet = null,
            extractionConfidence = "MANUAL",
            reviewStatus = "VERIFIED",
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z"
        )
        val dummyKey = AnswerKeyEntity(
            questionId = "sim_q_1",
            examId = "sim_exam",
            expectedAnswerJson = expectedAnswer,
            numericalTolerance = numericalTolerance,
            answerKeySource = "MANUAL",
            verificationStatus = "VERIFIED",
            reviewerId = "SIMULATOR",
            verifiedAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z"
        )
        val dummyResp = submittedAnswer?.let {
            CandidateResponseEntity(
                responseId = "sim_resp_1",
                attemptId = "sim_att",
                questionId = "sim_q_1",
                responsePayload = it,
                revisionSequence = 1,
                savedAt = "2026-01-01T00:00:00Z"
            )
        }
        val effectiveRule = EffectiveMarkingRule(
            correctScaledMark = correctScaledMark,
            incorrectScaledMark = if (allowNegativeMarking) -kotlin.math.abs(incorrectScaledMark) else 0L,
            unansweredScaledMark = unansweredScaledMark,
            allowNegativeMarking = allowNegativeMarking,
            multiSelectPolicy = multiSelectPolicy,
            ruleSource = "SIMULATOR_OVERRIDE"
        )
        return scoreQuestion(dummyQuestion, dummyKey, dummyResp, effectiveRule)
    }

    /**
     * Scores an individual question deterministically.
     */
    fun scoreQuestion(
        question: QuestionEntity,
        answerKey: AnswerKeyEntity?,
        response: CandidateResponseEntity?,
        effectiveRule: EffectiveMarkingRule
    ): QuestionScoreResult {
        val rawSubmitted = response?.responsePayload?.trim()
        val submittedText = if (rawSubmitted.isNullOrEmpty() || rawSubmitted == "[]" || rawSubmitted == "\"\"") {
            null
        } else {
            rawSubmitted
        }

        // 1. Answer Key Integrity Checks
        if (answerKey == null) {
            return QuestionScoreResult(
                questionId = question.questionId,
                sectionId = question.sectionId,
                questionNumber = question.questionNumber,
                questionType = question.questionType,
                submittedAnswer = submittedText,
                correctAnswerSnapshot = null,
                classification = AnswerClassification.UNGRADABLE,
                marksAwarded = 0L,
                maxPossibleMarks = effectiveRule.correctScaledMark,
                explanationCode = ScoringExplanationCode.MISSING_ANSWER_KEY,
                effectiveRule = effectiveRule
            )
        }

        if (answerKey.verificationStatus != "VERIFIED") {
            return QuestionScoreResult(
                questionId = question.questionId,
                sectionId = question.sectionId,
                questionNumber = question.questionNumber,
                questionType = question.questionType,
                submittedAnswer = submittedText,
                correctAnswerSnapshot = null,
                classification = AnswerClassification.UNGRADABLE,
                marksAwarded = 0L,
                maxPossibleMarks = effectiveRule.correctScaledMark,
                explanationCode = ScoringExplanationCode.UNVERIFIED_ANSWER_KEY,
                effectiveRule = effectiveRule
            )
        }

        // 2. Question-Type Specific Scoring
        return when (question.questionType.uppercase()) {
            "SINGLE_SELECT" -> scoreSingleSelect(question, answerKey, submittedText, effectiveRule)
            "MULTI_SELECT" -> scoreMultiSelect(question, answerKey, submittedText, effectiveRule)
            "NUMERICAL" -> scoreNumerical(question, answerKey, submittedText, effectiveRule)
            "SUBJECTIVE" -> scoreSubjective(question, answerKey, submittedText, effectiveRule)
            else -> QuestionScoreResult(
                questionId = question.questionId,
                sectionId = question.sectionId,
                questionNumber = question.questionNumber,
                questionType = question.questionType,
                submittedAnswer = submittedText,
                correctAnswerSnapshot = answerKey.expectedAnswerJson,
                classification = AnswerClassification.UNGRADABLE,
                marksAwarded = 0L,
                maxPossibleMarks = effectiveRule.correctScaledMark,
                explanationCode = ScoringExplanationCode.UNSUPPORTED_QUESTION_TYPE,
                effectiveRule = effectiveRule
            )
        }
    }

    private fun scoreSingleSelect(
        question: QuestionEntity,
        answerKey: AnswerKeyEntity,
        submitted: String?,
        rule: EffectiveMarkingRule
    ): QuestionScoreResult {
        if (submitted == null) {
            return QuestionScoreResult(
                questionId = question.questionId,
                sectionId = question.sectionId,
                questionNumber = question.questionNumber,
                questionType = question.questionType,
                submittedAnswer = null,
                correctAnswerSnapshot = answerKey.expectedAnswerJson,
                classification = AnswerClassification.UNANSWERED,
                marksAwarded = rule.unansweredScaledMark,
                maxPossibleMarks = rule.correctScaledMark,
                explanationCode = ScoringExplanationCode.NO_RESPONSE,
                effectiveRule = rule
            )
        }

        val candNorm = extractSingleOption(submitted)
        val expectedOptions = extractExpectedOptionsSet(answerKey.expectedAnswerJson)

        if (candNorm.isEmpty()) {
            return QuestionScoreResult(
                questionId = question.questionId,
                sectionId = question.sectionId,
                questionNumber = question.questionNumber,
                questionType = question.questionType,
                submittedAnswer = submitted,
                correctAnswerSnapshot = answerKey.expectedAnswerJson,
                classification = AnswerClassification.UNANSWERED,
                marksAwarded = rule.unansweredScaledMark,
                maxPossibleMarks = rule.correctScaledMark,
                explanationCode = ScoringExplanationCode.NO_RESPONSE,
                effectiveRule = rule
            )
        }

        val isCorrect = expectedOptions.contains(candNorm)
        val marks = if (isCorrect) {
            rule.correctScaledMark
        } else {
            if (rule.allowNegativeMarking) rule.incorrectScaledMark else 0L
        }

        return QuestionScoreResult(
            questionId = question.questionId,
            sectionId = question.sectionId,
            questionNumber = question.questionNumber,
            questionType = question.questionType,
            submittedAnswer = candNorm,
            correctAnswerSnapshot = answerKey.expectedAnswerJson,
            classification = if (isCorrect) AnswerClassification.CORRECT else AnswerClassification.INCORRECT,
            marksAwarded = marks,
            maxPossibleMarks = rule.correctScaledMark,
            explanationCode = if (isCorrect) ScoringExplanationCode.MATCHED_VERIFIED_ANSWER else ScoringExplanationCode.ANSWER_MISMATCH,
            effectiveRule = rule
        )
    }

    private fun scoreMultiSelect(
        question: QuestionEntity,
        answerKey: AnswerKeyEntity,
        submitted: String?,
        rule: EffectiveMarkingRule
    ): QuestionScoreResult {
        if (submitted == null) {
            return QuestionScoreResult(
                questionId = question.questionId,
                sectionId = question.sectionId,
                questionNumber = question.questionNumber,
                questionType = question.questionType,
                submittedAnswer = null,
                correctAnswerSnapshot = answerKey.expectedAnswerJson,
                classification = AnswerClassification.UNANSWERED,
                marksAwarded = rule.unansweredScaledMark,
                maxPossibleMarks = rule.correctScaledMark,
                explanationCode = ScoringExplanationCode.NO_RESPONSE,
                effectiveRule = rule
            )
        }

        val candSet = extractOptionsSet(submitted)
        val expectedSet = extractExpectedOptionsSet(answerKey.expectedAnswerJson)

        if (candSet.isEmpty()) {
            return QuestionScoreResult(
                questionId = question.questionId,
                sectionId = question.sectionId,
                questionNumber = question.questionNumber,
                questionType = question.questionType,
                submittedAnswer = submitted,
                correctAnswerSnapshot = answerKey.expectedAnswerJson,
                classification = AnswerClassification.UNANSWERED,
                marksAwarded = rule.unansweredScaledMark,
                maxPossibleMarks = rule.correctScaledMark,
                explanationCode = ScoringExplanationCode.NO_RESPONSE,
                effectiveRule = rule
            )
        }

        return when (rule.multiSelectPolicy) {
            MultiSelectPolicy.EXACT_MATCH -> {
                val matches = (candSet == expectedSet)
                val marks = if (matches) {
                    rule.correctScaledMark
                } else {
                    if (rule.allowNegativeMarking) rule.incorrectScaledMark else 0L
                }

                QuestionScoreResult(
                    questionId = question.questionId,
                    sectionId = question.sectionId,
                    questionNumber = question.questionNumber,
                    questionType = question.questionType,
                    submittedAnswer = JSONArray(candSet.toList()).toString(),
                    correctAnswerSnapshot = answerKey.expectedAnswerJson,
                    classification = if (matches) AnswerClassification.CORRECT else AnswerClassification.INCORRECT,
                    marksAwarded = marks,
                    maxPossibleMarks = rule.correctScaledMark,
                    explanationCode = if (matches) ScoringExplanationCode.MATCHED_VERIFIED_ANSWER else ScoringExplanationCode.ANSWER_MISMATCH,
                    effectiveRule = rule
                )
            }

            MultiSelectPolicy.PARTIAL_CREDIT -> {
                val incorrectChosen = (candSet - expectedSet).size
                if (incorrectChosen > 0) {
                    // Any incorrect option selected attracts incorrect penalty
                    val penalty = if (rule.allowNegativeMarking) rule.incorrectScaledMark else 0L
                    QuestionScoreResult(
                        questionId = question.questionId,
                        sectionId = question.sectionId,
                        questionNumber = question.questionNumber,
                        questionType = question.questionType,
                        submittedAnswer = JSONArray(candSet.toList()).toString(),
                        correctAnswerSnapshot = answerKey.expectedAnswerJson,
                        classification = AnswerClassification.INCORRECT,
                        marksAwarded = penalty,
                        maxPossibleMarks = rule.correctScaledMark,
                        explanationCode = ScoringExplanationCode.ANSWER_MISMATCH,
                        effectiveRule = rule,
                        calculationMetadata = "{\"incorrectCount\":$incorrectChosen}"
                    )
                } else {
                    val correctChosen = (candSet intersect expectedSet).size
                    val totalExpected = expectedSet.size.coerceAtLeast(1)

                    if (correctChosen == totalExpected) {
                        QuestionScoreResult(
                            questionId = question.questionId,
                            sectionId = question.sectionId,
                            questionNumber = question.questionNumber,
                            questionType = question.questionType,
                            submittedAnswer = JSONArray(candSet.toList()).toString(),
                            correctAnswerSnapshot = answerKey.expectedAnswerJson,
                            classification = AnswerClassification.CORRECT,
                            marksAwarded = rule.correctScaledMark,
                            maxPossibleMarks = rule.correctScaledMark,
                            explanationCode = ScoringExplanationCode.MATCHED_VERIFIED_ANSWER,
                            effectiveRule = rule
                        )
                    } else if (correctChosen > 0) {
                        // Proportional partial credit: (correctChosen * correctScaledMark) / totalExpected
                        val partialMark = (correctChosen.toLong() * rule.correctScaledMark) / totalExpected.toLong()
                        QuestionScoreResult(
                            questionId = question.questionId,
                            sectionId = question.sectionId,
                            questionNumber = question.questionNumber,
                            questionType = question.questionType,
                            submittedAnswer = JSONArray(candSet.toList()).toString(),
                            correctAnswerSnapshot = answerKey.expectedAnswerJson,
                            classification = AnswerClassification.PARTIALLY_CORRECT,
                            marksAwarded = partialMark,
                            maxPossibleMarks = rule.correctScaledMark,
                            explanationCode = ScoringExplanationCode.PARTIAL_CREDIT_RULE_APPLIED,
                            effectiveRule = rule,
                            calculationMetadata = "{\"correctChosen\":$correctChosen,\"totalExpected\":$totalExpected}"
                        )
                    } else {
                        QuestionScoreResult(
                            questionId = question.questionId,
                            sectionId = question.sectionId,
                            questionNumber = question.questionNumber,
                            questionType = question.questionType,
                            submittedAnswer = JSONArray(candSet.toList()).toString(),
                            correctAnswerSnapshot = answerKey.expectedAnswerJson,
                            classification = AnswerClassification.UNANSWERED,
                            marksAwarded = rule.unansweredScaledMark,
                            maxPossibleMarks = rule.correctScaledMark,
                            explanationCode = ScoringExplanationCode.NO_RESPONSE,
                            effectiveRule = rule
                        )
                    }
                }
            }
        }
    }

    private fun scoreNumerical(
        question: QuestionEntity,
        answerKey: AnswerKeyEntity,
        submitted: String?,
        rule: EffectiveMarkingRule
    ): QuestionScoreResult {
        if (submitted == null) {
            return QuestionScoreResult(
                questionId = question.questionId,
                sectionId = question.sectionId,
                questionNumber = question.questionNumber,
                questionType = question.questionType,
                submittedAnswer = null,
                correctAnswerSnapshot = answerKey.expectedAnswerJson,
                classification = AnswerClassification.UNANSWERED,
                marksAwarded = rule.unansweredScaledMark,
                maxPossibleMarks = rule.correctScaledMark,
                explanationCode = ScoringExplanationCode.NO_RESPONSE,
                effectiveRule = rule
            )
        }

        // Clean & parse locale-independent numeric value
        val cleaned = submitted.replace(",", ".").trim().removeSurrounding("\"")
        val candidateVal = try {
            cleaned.toDouble()
        } catch (_: Exception) {
            return QuestionScoreResult(
                questionId = question.questionId,
                sectionId = question.sectionId,
                questionNumber = question.questionNumber,
                questionType = question.questionType,
                submittedAnswer = submitted,
                correctAnswerSnapshot = answerKey.expectedAnswerJson,
                classification = AnswerClassification.INVALID_RESPONSE,
                marksAwarded = 0L,
                maxPossibleMarks = rule.correctScaledMark,
                explanationCode = ScoringExplanationCode.INVALID_RESPONSE_FORMAT,
                effectiveRule = rule
            )
        }

        val targetVal = extractExpectedNumeric(answerKey.expectedAnswerJson)
        if (targetVal == null) {
            return QuestionScoreResult(
                questionId = question.questionId,
                sectionId = question.sectionId,
                questionNumber = question.questionNumber,
                questionType = question.questionType,
                submittedAnswer = submitted,
                correctAnswerSnapshot = answerKey.expectedAnswerJson,
                classification = AnswerClassification.UNGRADABLE,
                marksAwarded = 0L,
                maxPossibleMarks = rule.correctScaledMark,
                explanationCode = ScoringExplanationCode.MISSING_ANSWER_KEY,
                effectiveRule = rule
            )
        }

        val tolerance = (answerKey.numericalTolerance ?: 0.0).coerceAtLeast(0.0)
        val diff = abs(candidateVal - targetVal)
        val isWithinTolerance = diff <= (tolerance + 1e-9)

        val marks = if (isWithinTolerance) {
            rule.correctScaledMark
        } else {
            if (rule.allowNegativeMarking) rule.incorrectScaledMark else 0L
        }

        return QuestionScoreResult(
            questionId = question.questionId,
            sectionId = question.sectionId,
            questionNumber = question.questionNumber,
            questionType = question.questionType,
            submittedAnswer = candidateVal.toString(),
            correctAnswerSnapshot = answerKey.expectedAnswerJson,
            classification = if (isWithinTolerance) AnswerClassification.CORRECT else AnswerClassification.INCORRECT,
            marksAwarded = marks,
            maxPossibleMarks = rule.correctScaledMark,
            explanationCode = if (isWithinTolerance) ScoringExplanationCode.NUMERICAL_TOLERANCE_MATCH else ScoringExplanationCode.ANSWER_MISMATCH,
            effectiveRule = rule,
            calculationMetadata = "{\"diff\":$diff,\"tolerance\":$tolerance}"
        )
    }

    private fun scoreSubjective(
        question: QuestionEntity,
        answerKey: AnswerKeyEntity,
        submitted: String?,
        rule: EffectiveMarkingRule
    ): QuestionScoreResult {
        if (submitted == null) {
            return QuestionScoreResult(
                questionId = question.questionId,
                sectionId = question.sectionId,
                questionNumber = question.questionNumber,
                questionType = question.questionType,
                submittedAnswer = null,
                correctAnswerSnapshot = answerKey.expectedAnswerJson,
                classification = AnswerClassification.UNANSWERED,
                marksAwarded = rule.unansweredScaledMark,
                maxPossibleMarks = rule.correctScaledMark,
                explanationCode = ScoringExplanationCode.NO_RESPONSE,
                effectiveRule = rule
            )
        }

        return QuestionScoreResult(
            questionId = question.questionId,
            sectionId = question.sectionId,
            questionNumber = question.questionNumber,
            questionType = question.questionType,
            submittedAnswer = submitted,
            correctAnswerSnapshot = answerKey.expectedAnswerJson,
            classification = AnswerClassification.PENDING_MANUAL_GRADING,
            marksAwarded = 0L,
            maxPossibleMarks = rule.correctScaledMark,
            explanationCode = ScoringExplanationCode.MANUAL_GRADING_REQUIRED,
            effectiveRule = rule
        )
    }

    /**
     * Calculates the complete examination scoring result for an attempt.
     */
    fun calculateResult(
        exam: ExamEntity,
        attempt: ExamAttemptEntity,
        questions: List<QuestionEntity>,
        sections: List<PaperSectionEntity>,
        answerKeys: List<AnswerKeyEntity>,
        responses: List<CandidateResponseEntity>,
        markingScheme: MarkingSchemeEntity
    ): ExaminationScoringResult {
        val sectionMap = sections.associateBy { it.sectionId }
        val responseMap = responses.associateBy { it.questionId }
        val keyMap = answerKeys.associateBy { it.questionId }

        val questionScores = mutableListOf<QuestionScoreResult>()

        for (q in questions.sortedBy { it.questionNumber }) {
            val sec = q.sectionId?.let { sectionMap[it] }
            val rule = resolveEffectiveMarkingRule(q, sec, markingScheme)
            val key = keyMap[q.questionId]
            val resp = responseMap[q.questionId]

            val qResult = scoreQuestion(q, key, resp, rule)
            questionScores.add(qResult)
        }

        // Section Totals
        val sectionScores = mutableListOf<SectionScoreResult>()
        val defaultSectionId = "sec_general"
        val groupedQuestions = questionScores.groupBy { it.sectionId ?: defaultSectionId }

        for ((secId, sQuestions) in groupedQuestions) {
            val title = sectionMap[secId]?.title ?: "General Section"
            val totalSecScore = sQuestions.sumOf { it.marksAwarded }
            val maxSecScore = sQuestions.sumOf { it.maxPossibleMarks }

            sectionScores.add(
                SectionScoreResult(
                    sectionId = secId,
                    title = title,
                    totalQuestions = sQuestions.size,
                    answeredCount = sQuestions.count { it.classification != AnswerClassification.UNANSWERED },
                    unansweredCount = sQuestions.count { it.classification == AnswerClassification.UNANSWERED },
                    correctCount = sQuestions.count { it.classification == AnswerClassification.CORRECT },
                    incorrectCount = sQuestions.count { it.classification == AnswerClassification.INCORRECT },
                    partiallyCorrectCount = sQuestions.count { it.classification == AnswerClassification.PARTIALLY_CORRECT },
                    pendingManualCount = sQuestions.count { it.classification == AnswerClassification.PENDING_MANUAL_GRADING },
                    totalScaledScore = totalSecScore,
                    maxPossibleScaledScore = maxSecScore
                )
            )
        }

        // Totals
        val totalQuestions = questionScores.size
        val answeredCount = questionScores.count { it.classification != AnswerClassification.UNANSWERED }
        val unansweredCount = questionScores.count { it.classification == AnswerClassification.UNANSWERED }
        val correctCount = questionScores.count { it.classification == AnswerClassification.CORRECT }
        val incorrectCount = questionScores.count { it.classification == AnswerClassification.INCORRECT }
        val partiallyCorrectCount = questionScores.count { it.classification == AnswerClassification.PARTIALLY_CORRECT }
        val pendingManualCount = questionScores.count { it.classification == AnswerClassification.PENDING_MANUAL_GRADING }

        val totalMarksEarned = questionScores.filter { it.marksAwarded > 0 }.sumOf { it.marksAwarded }
        val totalPenaltyApplied = questionScores.filter { it.marksAwarded < 0 }.sumOf { it.marksAwarded }
        val totalScaledScore = questionScores.sumOf { it.marksAwarded }
        val maxPossibleScaledScore = questionScores.sumOf { it.maxPossibleMarks }

        val status = if (pendingManualCount > 0 || questionScores.any { it.classification == AnswerClassification.UNGRADABLE }) {
            "PENDING_MANUAL_REVIEW"
        } else {
            "COMPLETED"
        }

        // Canonical Result Digest
        val canonicalPayload = buildString {
            append("attemptId=${attempt.attemptId};")
            append("examId=${exam.examId};")
            append("schemeVersion=${markingScheme.version};")
            append("totalQuestions=$totalQuestions;")
            append("totalScore=$totalScaledScore;")
            for (qs in questionScores) {
                append("Q${qs.questionNumber}:${qs.classification.name}:${qs.marksAwarded};")
            }
        }
        val resultHash = sha256Hex(canonicalPayload)

        return ExaminationScoringResult(
            attemptId = attempt.attemptId,
            examId = exam.examId,
            studentId = attempt.studentId,
            markingSchemeVersion = markingScheme.version,
            publishedExamVersion = 1,
            scoringEngineVersion = SCORING_ENGINE_VERSION,
            totalQuestions = totalQuestions,
            answeredCount = answeredCount,
            unansweredCount = unansweredCount,
            correctCount = correctCount,
            incorrectCount = incorrectCount,
            partiallyCorrectCount = partiallyCorrectCount,
            pendingManualCount = pendingManualCount,
            totalMarksEarned = totalMarksEarned,
            totalPenaltyApplied = totalPenaltyApplied,
            totalScaledScore = totalScaledScore,
            maxPossibleScaledScore = maxPossibleScaledScore,
            sectionScores = sectionScores,
            questionScores = questionScores,
            gradingStatus = status,
            resultHash = resultHash
        )
    }

    private fun extractSingleOption(raw: String): String {
        val trimmed = raw.trim().removeSurrounding("\"").removeSurrounding("'")
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            val arr = JSONArray(trimmed)
            return if (arr.length() > 0) arr.getString(0).trim().uppercase() else ""
        }
        return trimmed.uppercase()
    }

    private fun extractOptionsSet(raw: String): Set<String> {
        val trimmed = raw.trim()
        val set = mutableSetOf<String>()
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            val arr = JSONArray(trimmed)
            for (i in 0 until arr.length()) {
                val opt = arr.getString(i).trim().removeSurrounding("\"").uppercase()
                if (opt.isNotEmpty()) set.add(opt)
            }
            return set
        }

        if (trimmed.contains(",")) {
            val parts = trimmed.split(",")
            for (p in parts) {
                val opt = p.trim().removeSurrounding("\"").uppercase()
                if (opt.isNotEmpty()) set.add(opt)
            }
            return set
        }

        val single = trimmed.removeSurrounding("\"").uppercase()
        if (single.isNotEmpty()) set.add(single)
        return set
    }

    private fun extractExpectedOptionsSet(jsonOrStr: String): Set<String> {
        return extractOptionsSet(jsonOrStr)
    }

    private fun extractExpectedNumeric(jsonOrStr: String): Double? {
        val trimmed = jsonOrStr.trim().removeSurrounding("\"")
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            val arr = JSONArray(trimmed)
            if (arr.length() > 0) {
                return try { arr.getString(0).trim().replace(",", ".").toDouble() } catch (_: Exception) { null }
            }
            return null
        }
        return try {
            trimmed.replace(",", ".").toDouble()
        } catch (_: Exception) {
            null
        }
    }

    private fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
