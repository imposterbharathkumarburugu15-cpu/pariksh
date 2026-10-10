package com.doomedams.app.server.analytics

import com.doomedams.app.database.AppDatabase
import com.doomedams.app.database.entity.*
import com.doomedams.app.server.auth.UserSession
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.Duration
import kotlin.math.roundToInt

data class OverviewAnalyticsDto(
    val totalExams: Int,
    val publishedExams: Int,
    val inProgressExams: Int,
    val completedExams: Int,
    val totalAssignedStudents: Int,
    val activeAttempts: Int,
    val acceptedSubmissions: Int,
    val pendingSubmissions: Int,
    val gradingCompletionPercentage: Double,
    val publishedResultsCount: Int,
    val resultsAwaitingPublicationCount: Int,
    val resultsHeldCount: Int,
    val withheldResultsCount: Int,
    val outstandingIncidentsCount: Int,
    val pendingAppealsCount: Int,
    val generatedAt: String
)

data class ScoreDistributionBucket(
    val rangeLabel: String,
    val count: Int,
    val percentageOfStudents: Double
)

data class ExamSummaryAnalyticsDto(
    val examId: String,
    val examTitle: String,
    val durationMinutes: Int,
    val totalMarks: Double,
    val publishedAt: String?,
    val totalAssignedStudents: Int,
    val startedAttemptsCount: Int,
    val inProgressAttemptsCount: Int,
    val submittedAttemptsCount: Int,
    val cancelledAttemptsCount: Int,
    val timedOutAttemptsCount: Int,
    val evaluatedResultsCount: Int,
    val publishedResultsCount: Int,
    val heldResultsCount: Int,
    val meanScore: Double,
    val medianScore: Double,
    val highestScore: Double,
    val lowestScore: Double,
    val maxPossibleScore: Double,
    val overallAccuracyRate: Double,
    val averageDurationMinutes: Double?,
    val scoreDistribution: List<ScoreDistributionBucket>,
    val integrityEventsCount: Int,
    val flaggedAttemptsCount: Int,
    val incidentCasesCount: Int,
    val pendingIncidentsCount: Int,
    val appealsCount: Int,
    val pendingAppealsCount: Int
)

data class StudentPerformanceDto(
    val studentId: String,
    val studentDisplayName: String,
    val attemptId: String,
    val attemptStatus: String,
    val resultStatus: String,
    val score: Double?,
    val maxPossibleMarks: Double?,
    val percentage: Double?,
    val correctCount: Int?,
    val incorrectCount: Int?,
    val unansweredCount: Int?,
    val partiallyCorrectCount: Int?,
    val accuracyPercentage: Double?,
    val rank: Int?,
    val isTiedRank: Boolean,
    val holdReason: String?,
    val startedAt: String,
    val submittedAt: String?,
    val durationMinutes: Double?,
    val incidentCount: Int,
    val hasPendingAppeals: Boolean
)

data class OptionDistributionItem(
    val optionId: String,
    val label: String,
    val selectionCount: Int,
    val selectionPercentage: Double
)

data class QuestionAnalyticsDto(
    val questionId: String,
    val questionNumber: Int,
    val sectionId: String?,
    val sectionTitle: String,
    val questionType: String,
    val questionText: String,
    val maxMarks: Double,
    val eligibleCandidatesDenominator: Int,
    val attemptedCount: Int,
    val unansweredCount: Int,
    val correctCount: Int,
    val incorrectCount: Int,
    val partiallyCorrectCount: Int,
    val attemptRate: Double,
    val correctnessRate: Double,
    val averageMarksAwarded: Double,
    val difficultyCategory: String,
    val recordedTimeNotice: String, // Explicitly explains that question visit durations are not collected
    val optionDistributions: List<OptionDistributionItem>
)

data class SectionAnalyticsDto(
    val sectionId: String,
    val title: String,
    val sectionOrder: Int,
    val questionCount: Int,
    val totalMarks: Double,
    val averageScore: Double,
    val accuracyPercentage: Double,
    val attemptRate: Double,
    val highestScore: Double,
    val lowestScore: Double
)

data class QuestionGapItem(
    val questionNumber: Int,
    val sectionTitle: String,
    val questionType: String,
    val correctnessRate: Double,
    val incorrectRate: Double
)

data class LearningGapsDto(
    val curriculumTagsAvailable: Boolean,
    val disclosureNotice: String,
    val sectionPerformanceList: List<SectionAnalyticsDto>,
    val weakestSections: List<String>,
    val highIncorrectQuestions: List<QuestionGapItem>,
    val explainableRecommendations: List<String>
)

data class IntegrityAnalyticsDto(
    val examId: String,
    val totalEvents: Int,
    val uniqueFlaggedAttempts: Int,
    val eventsByCategory: Map<String, Int>,
    val appNameUnavailableCount: Int,
    val totalIncidentCases: Int,
    val incidentsByStatus: Map<String, Int>,
    val confirmedMisconductCount: Int,
    val dismissedIncidentsCount: Int,
    val pendingReviewIncidentsCount: Int,
    val heldResultsCount: Int,
    val cancelledAttemptsCount: Int,
    val reinstatedAttemptsCount: Int,
    val totalAppealsCount: Int,
    val pendingAppealsCount: Int
)

data class StudentQuestionAnswerDetailDto(
    val questionId: String,
    val questionNumber: Int,
    val sectionTitle: String,
    val questionType: String,
    val questionText: String,
    val optionsJson: String,
    val submittedAnswer: String?,
    val answerClassification: String,
    val marksAwarded: Double,
    val maxMarks: Double,
    val explanationCode: String,
    val revisionCount: Int,
    val answerSavedAt: String?,
    val timeUsedNotice: String, // "TIME DATA UNAVAILABLE"
    val correctAnswerSnapshot: String? // Protected based on role/permissions
)

data class StudentExamProfileDto(
    val studentId: String,
    val studentDisplayName: String,
    val examId: String,
    val examTitle: String,
    val attemptId: String,
    val attemptStatus: String,
    val resultStatus: String,
    val startedAt: String,
    val submittedAt: String?,
    val durationMinutes: Double?,
    val totalScaledScore: Double?,
    val maxPossibleScore: Double?,
    val percentage: Double?,
    val accuracyPercentage: Double?,
    val correctCount: Int?,
    val incorrectCount: Int?,
    val unansweredCount: Int?,
    val holdReason: String?,
    val questions: List<StudentQuestionAnswerDetailDto>,
    val localSaveCount: Int,
    val submissionReceiptHash: String?,
    val integrityEvents: List<IntegrityEventEntity>,
    val incidentCases: List<IncidentCaseEntity>,
    val appeals: List<AppealEntity>
)

class AnalyticsService(private val db: AppDatabase) {

    suspend fun getOverviewAnalytics(instituteId: String): OverviewAnalyticsDto {
        val exams = db.examDao().getExamsByInstitute(instituteId)
        val allAttempts = db.examAttemptDao().getAllAttempts()
        val allResults = db.examResultDao().getAllResults()
        val allUsers = db.userDao().getUsersByInstitute(instituteId)
        val allIncidents = db.incidentDao().getAllIncidents()
        val allAppeals = db.incidentDao().getAllAppeals()

        val instituteExamIds = exams.map { it.examId }.toSet()
        val instituteAttempts = allAttempts.filter { it.examId in instituteExamIds }
        val instituteResults = allResults.filter { it.examId in instituteExamIds }
        val instituteIncidents = allIncidents.filter { it.examId in instituteExamIds }
        val instituteAppeals = allAppeals.filter { appeal ->
            instituteIncidents.any { it.incidentId == appeal.incidentId }
        }

        val totalExams = exams.size
        val publishedExams = exams.count { it.status == "PUBLISHED" }

        // Determine in progress and completed exams
        var inProgressExamsCount = 0
        var completedExamsCount = 0
        for (exam in exams) {
            val examAttempts = instituteAttempts.filter { it.examId == exam.examId }
            if (examAttempts.any { it.attemptStatus == "IN_PROGRESS" }) {
                inProgressExamsCount++
            } else if (examAttempts.isNotEmpty() && examAttempts.all { it.attemptStatus in listOf("SUBMITTED", "TIMED_OUT", "CANCELLED") }) {
                completedExamsCount++
            }
        }

        val totalAssignedStudents = allUsers.count { it.role == "STUDENT" }
        val activeAttempts = instituteAttempts.count { it.attemptStatus == "IN_PROGRESS" }
        val acceptedSubmissions = instituteAttempts.count { it.attemptStatus == "SUBMITTED" }
        val pendingSubmissions = activeAttempts

        val submittedAttempts = instituteAttempts.filter { it.attemptStatus == "SUBMITTED" }
        val gradedSubmissionsCount = submittedAttempts.count { attempt ->
            instituteResults.any { it.attemptId == attempt.attemptId && it.resultStatus in listOf("GRADED", "PUBLISHED", "HELD_PENDING_REVIEW") }
        }
        val gradingCompletionPercentage = if (submittedAttempts.isNotEmpty()) {
            ((gradedSubmissionsCount.toDouble() / submittedAttempts.size) * 1000.0).roundToInt() / 10.0
        } else {
            100.0
        }

        val publishedResultsCount = instituteResults.count { it.resultStatus == "PUBLISHED" }
        val resultsAwaitingPublicationCount = instituteResults.count { it.resultStatus in listOf("GRADED", "PENDING_GRADING") }
        val resultsHeldCount = instituteResults.count { it.resultStatus == "HELD_PENDING_REVIEW" }
        val withheldResultsCount = instituteResults.count { it.resultStatus == "WITHHELD" }

        val outstandingIncidentsCount = instituteIncidents.count {
            it.incidentStatus in listOf("DETECTED", "PENDING_REVIEW", "UNDER_REVIEW", "PENDING_FURTHER_REVIEW")
        }
        val pendingAppealsCount = instituteAppeals.count {
            it.appealStatus in listOf("SUBMITTED", "PENDING_REVIEW", "UNDER_REVIEW")
        }

        return OverviewAnalyticsDto(
            totalExams = totalExams,
            publishedExams = publishedExams,
            inProgressExams = inProgressExamsCount,
            completedExams = completedExamsCount,
            totalAssignedStudents = totalAssignedStudents,
            activeAttempts = activeAttempts,
            acceptedSubmissions = acceptedSubmissions,
            pendingSubmissions = pendingSubmissions,
            gradingCompletionPercentage = gradingCompletionPercentage,
            publishedResultsCount = publishedResultsCount,
            resultsAwaitingPublicationCount = resultsAwaitingPublicationCount,
            resultsHeldCount = resultsHeldCount,
            withheldResultsCount = withheldResultsCount,
            outstandingIncidentsCount = outstandingIncidentsCount,
            pendingAppealsCount = pendingAppealsCount,
            generatedAt = Instant.now().toString()
        )
    }

    suspend fun getExamAnalyticsSummary(examId: String): ExamSummaryAnalyticsDto? {
        val exam = db.examDao().getExamById(examId) ?: return null
        val attempts = db.examAttemptDao().getAttemptsByExam(examId)
        val results = db.examResultDao().getResultsByExamId(examId)
        val questions = db.questionDao().getQuestionsByExamId(examId)
        val incidents = db.incidentDao().getIncidentsByExam(examId)
        val integrityEvents = db.integrityEventDao().getEventsByExam(examId)
        val allAppeals = db.incidentDao().getAllAppeals()
        val examAppeals = allAppeals.filter { appeal -> incidents.any { it.incidentId == appeal.incidentId } }

        val totalAssigned = attempts.map { it.studentId }.distinct().size
        val startedAttemptsCount = attempts.size
        val inProgressAttemptsCount = attempts.count { it.attemptStatus == "IN_PROGRESS" }
        val submittedAttemptsCount = attempts.count { it.attemptStatus == "SUBMITTED" }
        val cancelledAttemptsCount = attempts.count { it.attemptStatus == "CANCELLED" }
        val timedOutAttemptsCount = attempts.count { it.attemptStatus == "TIMED_OUT" }

        val evaluatedResults = results.filter { it.resultStatus in listOf("GRADED", "PUBLISHED", "HELD_PENDING_REVIEW") }
        val publishedResultsCount = results.count { it.resultStatus == "PUBLISHED" }
        val heldResultsCount = results.count { it.resultStatus == "HELD_PENDING_REVIEW" }

        val scores = evaluatedResults.map { it.totalScaledScore / 100.0 }
        val maxPossibleScore = if (evaluatedResults.isNotEmpty()) {
            evaluatedResults.first().maxPossibleScaledScore / 100.0
        } else {
            questions.size * 4.0 // fallback estimation
        }

        val meanScore = if (scores.isNotEmpty()) {
            ((scores.average()) * 100.0).roundToInt() / 100.0
        } else 0.0

        val medianScore = if (scores.isNotEmpty()) {
            val sorted = scores.sorted()
            if (sorted.size % 2 == 1) {
                sorted[sorted.size / 2]
            } else {
                ((sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0 * 100.0).roundToInt() / 100.0
            }
        } else 0.0

        val highestScore = if (scores.isNotEmpty()) scores.maxOrNull() ?: 0.0 else 0.0
        val lowestScore = if (scores.isNotEmpty()) scores.minOrNull() ?: 0.0 else 0.0

        // Accuracy rate
        var totalCorrect = 0
        var totalAnswered = 0
        evaluatedResults.forEach { res ->
            totalCorrect += res.correctCount
            totalAnswered += (res.correctCount + res.incorrectCount + res.partiallyCorrectCount)
        }
        val overallAccuracyRate = if (totalAnswered > 0) {
            ((totalCorrect.toDouble() / totalAnswered) * 1000.0).roundToInt() / 10.0
        } else 0.0

        // Durations
        val durations = attempts.mapNotNull { att ->
            if (att.submittedAt != null && att.startedAt.isNotEmpty()) {
                try {
                    val start = Instant.parse(att.startedAt)
                    val end = Instant.parse(att.submittedAt)
                    Duration.between(start, end).toMinutes().toDouble()
                } catch (e: Exception) {
                    null
                }
            } else null
        }
        val averageDurationMinutes = if (durations.isNotEmpty()) {
            ((durations.average()) * 10.0).roundToInt() / 10.0
        } else null

        // Score Distribution buckets
        val buckets = listOf(
            Pair("0 - 20%", 0..20),
            Pair("21 - 40%", 21..40),
            Pair("41 - 60%", 41..60),
            Pair("61 - 80%", 61..80),
            Pair("81 - 100%", 81..100)
        )
        val scoreDistribution = buckets.map { (label, range) ->
            val count = evaluatedResults.count { res ->
                val pct = if (res.maxPossibleScaledScore > 0) {
                    ((res.totalScaledScore.toDouble() / res.maxPossibleScaledScore) * 100.0).roundToInt()
                } else 0
                pct in range
            }
            val pctOfStudents = if (evaluatedResults.isNotEmpty()) {
                ((count.toDouble() / evaluatedResults.size) * 1000.0).roundToInt() / 10.0
            } else 0.0
            ScoreDistributionBucket(label, count, pctOfStudents)
        }

        val flaggedAttemptsCount = integrityEvents.map { it.attemptId }.distinct().size
        val pendingIncidentsCount = incidents.count {
            it.incidentStatus in listOf("DETECTED", "PENDING_REVIEW", "UNDER_REVIEW", "PENDING_FURTHER_REVIEW")
        }
        val pendingAppealsCount = examAppeals.count {
            it.appealStatus in listOf("SUBMITTED", "PENDING_REVIEW", "UNDER_REVIEW")
        }

        return ExamSummaryAnalyticsDto(
            examId = exam.examId,
            examTitle = exam.title,
            durationMinutes = (exam.durationSeconds / 60).toInt(),
            totalMarks = maxPossibleScore,
            publishedAt = if (exam.status == "PUBLISHED") exam.updatedAt else null,
            totalAssignedStudents = totalAssigned,
            startedAttemptsCount = startedAttemptsCount,
            inProgressAttemptsCount = inProgressAttemptsCount,
            submittedAttemptsCount = submittedAttemptsCount,
            cancelledAttemptsCount = cancelledAttemptsCount,
            timedOutAttemptsCount = timedOutAttemptsCount,
            evaluatedResultsCount = evaluatedResults.size,
            publishedResultsCount = publishedResultsCount,
            heldResultsCount = heldResultsCount,
            meanScore = meanScore,
            medianScore = medianScore,
            highestScore = highestScore,
            lowestScore = lowestScore,
            maxPossibleScore = maxPossibleScore,
            overallAccuracyRate = overallAccuracyRate,
            averageDurationMinutes = averageDurationMinutes,
            scoreDistribution = scoreDistribution,
            integrityEventsCount = integrityEvents.size,
            flaggedAttemptsCount = flaggedAttemptsCount,
            incidentCasesCount = incidents.size,
            pendingIncidentsCount = pendingIncidentsCount,
            appealsCount = examAppeals.size,
            pendingAppealsCount = pendingAppealsCount
        )
    }

    suspend fun getStudentPerformanceList(examId: String): List<StudentPerformanceDto> {
        val attempts = db.examAttemptDao().getAttemptsByExam(examId)
        val results = db.examResultDao().getResultsByExamId(examId).associateBy { it.attemptId }
        val incidents = db.incidentDao().getIncidentsByExam(examId).groupBy { it.attemptId }
        val appeals = db.incidentDao().getAllAppeals().groupBy { it.attemptId }
        val users = db.userDao().getAllUsers().associateBy { it.userId }

        // Sort candidates with final or held scores for rank assignment
        val scoredList = attempts.mapNotNull { att ->
            val res = results[att.attemptId]
            if (res != null && att.attemptStatus == "SUBMITTED") {
                Pair(att.attemptId, res.totalScaledScore)
            } else null
        }.sortedByDescending { it.second }

        // Compute dense ranks with ties handled consistently
        val rankMap = mutableMapOf<String, Pair<Int, Boolean>>()
        var currentRank = 1
        var index = 0
        while (index < scoredList.size) {
            val score = scoredList[index].second
            val ties = scoredList.filter { it.second == score }
            val isTied = ties.size > 1
            ties.forEach { (attId, _) ->
                rankMap[attId] = Pair(currentRank, isTied)
            }
            currentRank += ties.size
            index += ties.size
        }

        return attempts.map { att ->
            val res = results[att.attemptId]
            val user = users[att.studentId]
            val attIncidents = incidents[att.attemptId] ?: emptyList()
            val attAppeals = appeals[att.attemptId] ?: emptyList()

            val score = res?.let { (it.totalScaledScore / 100.0 * 100.0).roundToInt() / 100.0 }
            val maxMarks = res?.let { (it.maxPossibleScaledScore / 100.0 * 100.0).roundToInt() / 100.0 }
            val percentage = if (res != null && res.maxPossibleScaledScore > 0) {
                ((res.totalScaledScore.toDouble() / res.maxPossibleScaledScore) * 1000.0).roundToInt() / 10.0
            } else null

            val totalAnswered = res?.let { it.correctCount + it.incorrectCount + it.partiallyCorrectCount } ?: 0
            val accuracy = if (res != null && totalAnswered > 0) {
                ((res.correctCount.toDouble() / totalAnswered) * 1000.0).roundToInt() / 10.0
            } else null

            val rankInfo = rankMap[att.attemptId]

            val durationMinutes = if (att.submittedAt != null && att.startedAt.isNotEmpty()) {
                try {
                    val s = Instant.parse(att.startedAt)
                    val e = Instant.parse(att.submittedAt)
                    (Duration.between(s, e).toSeconds().toDouble() / 60.0 * 10.0).roundToInt() / 10.0
                } catch (e: Exception) {
                    null
                }
            } else null

            StudentPerformanceDto(
                studentId = att.studentId,
                studentDisplayName = user?.displayName ?: att.studentId,
                attemptId = att.attemptId,
                attemptStatus = att.attemptStatus,
                resultStatus = res?.resultStatus ?: att.resultStatus,
                score = score,
                maxPossibleMarks = maxMarks,
                percentage = percentage,
                correctCount = res?.correctCount,
                incorrectCount = res?.incorrectCount,
                unansweredCount = res?.unansweredCount,
                partiallyCorrectCount = res?.partiallyCorrectCount,
                accuracyPercentage = accuracy,
                rank = rankInfo?.first,
                isTiedRank = rankInfo?.second ?: false,
                holdReason = res?.holdReason,
                startedAt = att.startedAt,
                submittedAt = att.submittedAt,
                durationMinutes = durationMinutes,
                incidentCount = attIncidents.size,
                hasPendingAppeals = attAppeals.any { it.appealStatus in listOf("SUBMITTED", "PENDING_REVIEW", "UNDER_REVIEW") }
            )
        }
    }

    suspend fun getQuestionAnalyticsList(examId: String): List<QuestionAnalyticsDto> {
        val questions = db.questionDao().getQuestionsByExamId(examId)
        val sections = db.paperSectionDao().getSectionsByExamId(examId).associateBy { it.sectionId }
        val attempts = db.examAttemptDao().getAttemptsByExam(examId).filter { it.attemptStatus == "SUBMITTED" }
        val eligibleCount = attempts.size // Authoritative denominator

        return questions.map { q ->
            val records = db.questionGradingRecordDao().getRecordsByQuestionId(q.questionId)
                .filter { rec -> attempts.any { att -> att.attemptId == rec.resultId || att.studentId.isNotEmpty() } }
            val responses = db.candidateResponseDao().getResponsesByQuestionId(q.questionId)

            val correctCount = records.count { it.answerClassification == "CORRECT" }
            val incorrectCount = records.count { it.answerClassification == "INCORRECT" }
            val partiallyCorrectCount = records.count { it.answerClassification == "PARTIALLY_CORRECT" }
            val unansweredCount = if (eligibleCount > 0) {
                eligibleCount - (correctCount + incorrectCount + partiallyCorrectCount)
            } else 0
            val attemptedCount = eligibleCount - unansweredCount

            val attemptRate = if (eligibleCount > 0) {
                ((attemptedCount.toDouble() / eligibleCount) * 1000.0).roundToInt() / 10.0
            } else 0.0

            val correctnessRate = if (attemptedCount > 0) {
                ((correctCount.toDouble() / attemptedCount) * 1000.0).roundToInt() / 10.0
            } else 0.0

            val totalMarksAwarded = records.sumOf { it.marksAwarded / 100.0 }
            val averageMarks = if (eligibleCount > 0) {
                ((totalMarksAwarded / eligibleCount) * 100.0).roundToInt() / 100.0
            } else 0.0

            val difficultyCategory = when {
                correctnessRate < 35.0 -> "High Incorrect-Response Rate"
                correctnessRate <= 70.0 -> "Moderate Incorrect-Response Rate"
                else -> "Low Incorrect-Response Rate"
            }

            // Distractor Option Analysis
            val optionDistributions = mutableListOf<OptionDistributionItem>()
            if (q.answerOptionsJson.isNotEmpty() && q.answerOptionsJson != "[]") {
                try {
                    val opts = JSONArray(q.answerOptionsJson)
                    for (i in 0 until opts.length()) {
                        val opt = opts.getJSONObject(i)
                        val optId = opt.optString("optionId", opt.optString("label", "opt_$i"))
                        val label = opt.optString("label", optId)
                        val count = responses.count { resp ->
                            resp.responsePayload.contains(optId) || resp.responsePayload.contains(label)
                        }
                        val pct = if (eligibleCount > 0) {
                            ((count.toDouble() / eligibleCount) * 1000.0).roundToInt() / 10.0
                        } else 0.0
                        optionDistributions.add(OptionDistributionItem(optId, label, count, pct))
                    }
                } catch (e: Exception) {
                    // Option json parse fallback
                }
            }

            QuestionAnalyticsDto(
                questionId = q.questionId,
                questionNumber = q.questionNumber,
                sectionId = q.sectionId,
                sectionTitle = sections[q.sectionId]?.title ?: "General",
                questionType = q.questionType,
                questionText = q.questionText,
                maxMarks = 4.0, // standard default
                eligibleCandidatesDenominator = eligibleCount,
                attemptedCount = attemptedCount,
                unansweredCount = unansweredCount,
                correctCount = correctCount,
                incorrectCount = incorrectCount,
                partiallyCorrectCount = partiallyCorrectCount,
                attemptRate = attemptRate,
                correctnessRate = correctnessRate,
                averageMarksAwarded = averageMarks,
                difficultyCategory = difficultyCategory,
                recordedTimeNotice = "TIME DATA UNAVAILABLE (per-question timer telemetry is not collected)",
                optionDistributions = optionDistributions
            )
        }
    }

    suspend fun getSectionAnalyticsList(examId: String): List<SectionAnalyticsDto> {
        val sections = db.paperSectionDao().getSectionsByExamId(examId)
        val questionAnalytics = getQuestionAnalyticsList(examId)

        return sections.map { sec ->
            val secQuestions = questionAnalytics.filter { it.sectionId == sec.sectionId }
            val qCount = secQuestions.size
            val totalMarks = secQuestions.sumOf { it.maxMarks }
            val avgScore = if (qCount > 0) {
                ((secQuestions.sumOf { it.averageMarksAwarded }) * 100.0).roundToInt() / 100.0
            } else 0.0

            val accuracy = if (secQuestions.isNotEmpty()) {
                ((secQuestions.map { it.correctnessRate }.average()) * 10.0).roundToInt() / 10.0
            } else 0.0

            val attemptRate = if (secQuestions.isNotEmpty()) {
                ((secQuestions.map { it.attemptRate }.average()) * 10.0).roundToInt() / 10.0
            } else 0.0

            SectionAnalyticsDto(
                sectionId = sec.sectionId,
                title = sec.title,
                sectionOrder = sec.sectionOrder,
                questionCount = qCount,
                totalMarks = totalMarks,
                averageScore = avgScore,
                accuracyPercentage = accuracy,
                attemptRate = attemptRate,
                highestScore = totalMarks,
                lowestScore = 0.0
            )
        }
    }

    suspend fun getLearningGaps(examId: String): LearningGapsDto {
        val sectionAnalytics = getSectionAnalyticsList(examId)
        val questionAnalytics = getQuestionAnalyticsList(examId)

        // Identify sections with lowest correctness rates
        val weakestSections = sectionAnalytics.sortedBy { it.accuracyPercentage }.map { it.title }

        // Identify high incorrect response questions
        val highIncorrect = questionAnalytics
            .filter { it.correctnessRate < 50.0 && it.attemptedCount > 0 }
            .sortedBy { it.correctnessRate }
            .map { q ->
                QuestionGapItem(
                    questionNumber = q.questionNumber,
                    sectionTitle = q.sectionTitle,
                    questionType = q.questionType,
                    correctnessRate = q.correctnessRate,
                    incorrectRate = (((100.0 - q.correctnessRate)) * 10.0).roundToInt() / 10.0
                )
            }

        val recommendations = mutableListOf<String>()
        if (weakestSections.isNotEmpty()) {
            recommendations.add("Schedule targeted review for section '${weakestSections.first()}', which recorded the lowest aggregate accuracy.")
        }
        if (highIncorrect.isNotEmpty()) {
            val qNums = highIncorrect.take(3).map { "Q${it.questionNumber}" }.joinToString(", ")
            recommendations.add("Provide guided problem-solving sessions on high-incorrect questions: $qNums.")
        }
        recommendations.add("Review question-level distractor patterns to address common student misunderstandings.")

        return LearningGapsDto(
            curriculumTagsAvailable = false,
            disclosureNotice = "Concept-level intelligence requires curriculum tags. Analysis is currently grouped by section and question type.",
            sectionPerformanceList = sectionAnalytics,
            weakestSections = weakestSections,
            highIncorrectQuestions = highIncorrect,
            explainableRecommendations = recommendations
        )
    }

    suspend fun getIntegrityAnalytics(examId: String): IntegrityAnalyticsDto {
        val events = db.integrityEventDao().getEventsByExam(examId)
        val incidents = db.incidentDao().getIncidentsByExam(examId)
        val allAppeals = db.incidentDao().getAllAppeals()
        val appeals = allAppeals.filter { appeal -> incidents.any { it.incidentId == appeal.incidentId } }
        val results = db.examResultDao().getResultsByExamId(examId)
        val attempts = db.examAttemptDao().getAttemptsByExam(examId)

        val totalEvents = events.size
        val uniqueFlaggedAttempts = events.map { it.attemptId }.distinct().size
        val eventsByCategory = events.groupBy { it.eventCategory }.mapValues { it.value.size }
        val appNameUnavailableCount = events.count { it.displayedAppName.isNullOrEmpty() && it.appPackageName.isNullOrEmpty() }

        val incidentsByStatus = incidents.groupBy { it.incidentStatus }.mapValues { it.value.size }
        val confirmedMisconductCount = incidents.count { it.incidentStatus == "CONFIRMED" }
        val dismissedIncidentsCount = incidents.count { it.incidentStatus == "DISMISSED" }
        val pendingReviewIncidentsCount = incidents.count {
            it.incidentStatus in listOf("DETECTED", "PENDING_REVIEW", "UNDER_REVIEW", "PENDING_FURTHER_REVIEW")
        }

        val heldResultsCount = results.count { it.resultStatus == "HELD_PENDING_REVIEW" }
        val cancelledAttemptsCount = attempts.count { it.attemptStatus == "CANCELLED" }
        // Count reinstatements from policy actions
        var reinstatedCount = 0
        incidents.forEach { inc ->
            val actions = db.incidentDao().getPolicyActionsForIncident(inc.incidentId)
            reinstatedCount += actions.count { it.actionType == "REINSTATE_ATTEMPT" && it.executionStatus == "APPLIED" }
        }

        val pendingAppealsCount = appeals.count {
            it.appealStatus in listOf("SUBMITTED", "PENDING_REVIEW", "UNDER_REVIEW")
        }

        return IntegrityAnalyticsDto(
            examId = examId,
            totalEvents = totalEvents,
            uniqueFlaggedAttempts = uniqueFlaggedAttempts,
            eventsByCategory = eventsByCategory,
            appNameUnavailableCount = appNameUnavailableCount,
            totalIncidentCases = incidents.size,
            incidentsByStatus = incidentsByStatus,
            confirmedMisconductCount = confirmedMisconductCount,
            dismissedIncidentsCount = dismissedIncidentsCount,
            pendingReviewIncidentsCount = pendingReviewIncidentsCount,
            heldResultsCount = heldResultsCount,
            cancelledAttemptsCount = cancelledAttemptsCount,
            reinstatedAttemptsCount = reinstatedCount,
            totalAppealsCount = appeals.size,
            pendingAppealsCount = pendingAppealsCount
        )
    }

    suspend fun getStudentExamProfile(examId: String, studentId: String, session: UserSession): StudentExamProfileDto? {
        val exam = db.examDao().getExamById(examId) ?: return null
        val user = db.userDao().getUserById(studentId) ?: return null
        val attempt = db.examAttemptDao().getAttemptByStudentAndExam(studentId, examId) ?: return null
        val result = db.examResultDao().getResultByAttemptId(attempt.attemptId)
        val questions = db.questionDao().getQuestionsByExamId(examId)
        val sections = db.paperSectionDao().getSectionsByExamId(examId).associateBy { it.sectionId }
        val responses = db.candidateResponseDao().getResponsesByAttempt(attempt.attemptId).associateBy { it.questionId }
        val gradingRecords = if (result != null) {
            db.questionGradingRecordDao().getRecordsByResultId(result.resultId).associateBy { it.questionId }
        } else emptyMap()

        val events = db.integrityEventDao().getEventsByAttempt(attempt.attemptId)
        val incidents = db.incidentDao().getIncidentsByAttempt(attempt.attemptId)
        val appeals = db.incidentDao().getAppealsByStudent(studentId).filter { it.attemptId == attempt.attemptId }

        val canViewAnswerKey = session.role in listOf("EXAMINATION_ADMIN", "CHIEF_EXAMINATION_OFFICER")

        val durationMinutes = if (attempt.submittedAt != null && attempt.startedAt.isNotEmpty()) {
            try {
                val s = Instant.parse(attempt.startedAt)
                val e = Instant.parse(attempt.submittedAt)
                (Duration.between(s, e).toSeconds().toDouble() / 60.0 * 10.0).roundToInt() / 10.0
            } catch (e: Exception) {
                null
            }
        } else null

        val percentage = if (result != null && result.maxPossibleScaledScore > 0) {
            ((result.totalScaledScore.toDouble() / result.maxPossibleScaledScore) * 1000.0).roundToInt() / 10.0
        } else null

        val totalAnswered = result?.let { it.correctCount + it.incorrectCount + it.partiallyCorrectCount } ?: 0
        val accuracy = if (result != null && totalAnswered > 0) {
            ((result.correctCount.toDouble() / totalAnswered) * 1000.0).roundToInt() / 10.0
        } else null

        val questionDetails = questions.map { q ->
            val resp = responses[q.questionId]
            val record = gradingRecords[q.questionId]
            val secTitle = sections[q.sectionId]?.title ?: "General"

            StudentQuestionAnswerDetailDto(
                questionId = q.questionId,
                questionNumber = q.questionNumber,
                sectionTitle = secTitle,
                questionType = q.questionType,
                questionText = q.questionText,
                optionsJson = q.answerOptionsJson,
                submittedAnswer = resp?.responsePayload,
                answerClassification = record?.answerClassification ?: if (resp != null) "UNGRADED" else "UNANSWERED",
                marksAwarded = (record?.marksAwarded ?: 0L) / 100.0,
                maxMarks = (record?.maxMarks ?: 400L) / 100.0,
                explanationCode = record?.explanationCode ?: "PENDING_EVALUATION",
                revisionCount = resp?.revisionSequence ?: 0,
                answerSavedAt = resp?.savedAt,
                timeUsedNotice = "TIME DATA UNAVAILABLE",
                correctAnswerSnapshot = if (canViewAnswerKey) record?.correctAnswerSnapshot else null
            )
        }

        val submissionReceiptHash = if (attempt.attemptStatus == "SUBMITTED" && attempt.submittedAt != null) {
            "rcpt_${attempt.attemptId}_${attempt.studentId}_${attempt.submittedAt.hashCode().toUInt()}"
        } else null

        return StudentExamProfileDto(
            studentId = studentId,
            studentDisplayName = user.displayName,
            examId = examId,
            examTitle = exam.title,
            attemptId = attempt.attemptId,
            attemptStatus = attempt.attemptStatus,
            resultStatus = result?.resultStatus ?: attempt.resultStatus,
            startedAt = attempt.startedAt,
            submittedAt = attempt.submittedAt,
            durationMinutes = durationMinutes,
            totalScaledScore = result?.let { it.totalScaledScore / 100.0 },
            maxPossibleScore = result?.let { it.maxPossibleScaledScore / 100.0 },
            percentage = percentage,
            accuracyPercentage = accuracy,
            correctCount = result?.correctCount,
            incorrectCount = result?.incorrectCount,
            unansweredCount = result?.unansweredCount,
            holdReason = result?.holdReason,
            questions = questionDetails,
            localSaveCount = responses.size,
            submissionReceiptHash = submissionReceiptHash,
            integrityEvents = events,
            incidentCases = incidents,
            appeals = appeals
        )
    }

    suspend fun generateReportCsv(examId: String, reportType: String): String {
        val exam = db.examDao().getExamById(examId) ?: return "ERROR: Exam not found"
        val sb = StringBuilder()

        fun escapeCsv(value: Any?): String {
            if (value == null) return ""
            val str = value.toString()
            return if (str.contains(",") || str.contains("\"") || str.contains("\n") || str.contains("\r")) {
                "\"${str.replace("\"", "\"\"")}\""
            } else {
                str
            }
        }

        when (reportType.uppercase()) {
            "STUDENT_PERFORMANCE" -> {
                sb.append("Student ID,Student Name,Attempt Status,Result Status,Rank,Score,Max Marks,Percentage,Correct,Incorrect,Unanswered,Accuracy %,Duration (mins),Hold Reason\n")
                val students = getStudentPerformanceList(examId)
                students.forEach { s ->
                    val rankStr = if (s.rank != null) "${s.rank}${if (s.isTiedRank) " (Tied)" else ""}" else "N/A"
                    sb.append("${escapeCsv(s.studentId)},")
                    sb.append("${escapeCsv(s.studentDisplayName)},")
                    sb.append("${escapeCsv(s.attemptStatus)},")
                    sb.append("${escapeCsv(s.resultStatus)},")
                    sb.append("${escapeCsv(rankStr)},")
                    sb.append("${escapeCsv(s.score ?: "N/A")},")
                    sb.append("${escapeCsv(s.maxPossibleMarks ?: "N/A")},")
                    sb.append("${escapeCsv(s.percentage?.let { "$it%" } ?: "N/A")},")
                    sb.append("${escapeCsv(s.correctCount ?: "N/A")},")
                    sb.append("${escapeCsv(s.incorrectCount ?: "N/A")},")
                    sb.append("${escapeCsv(s.unansweredCount ?: "N/A")},")
                    sb.append("${escapeCsv(s.accuracyPercentage?.let { "$it%" } ?: "N/A")},")
                    sb.append("${escapeCsv(s.durationMinutes ?: "N/A")},")
                    sb.append("${escapeCsv(s.holdReason ?: "")}\n")
                }
            }

            "QUESTION_ANALYSIS" -> {
                sb.append("Question No,Section,Question Type,Max Marks,Eligible Candidates,Attempted,Unanswered,Correct,Incorrect,Attempt Rate %,Correctness Rate %,Avg Marks Awarded,Difficulty Category\n")
                val questions = getQuestionAnalyticsList(examId)
                questions.forEach { q ->
                    sb.append("${escapeCsv(q.questionNumber)},")
                    sb.append("${escapeCsv(q.sectionTitle)},")
                    sb.append("${escapeCsv(q.questionType)},")
                    sb.append("${escapeCsv(q.maxMarks)},")
                    sb.append("${escapeCsv(q.eligibleCandidatesDenominator)},")
                    sb.append("${escapeCsv(q.attemptedCount)},")
                    sb.append("${escapeCsv(q.unansweredCount)},")
                    sb.append("${escapeCsv(q.correctCount)},")
                    sb.append("${escapeCsv(q.incorrectCount)},")
                    sb.append("${escapeCsv("${q.attemptRate}%")},")
                    sb.append("${escapeCsv("${q.correctnessRate}%")},")
                    sb.append("${escapeCsv(q.averageMarksAwarded)},")
                    sb.append("${escapeCsv(q.difficultyCategory)}\n")
                }
            }

            "SECTION_PERFORMANCE" -> {
                sb.append("Section Title,Order,Questions Count,Total Marks,Average Score,Accuracy %,Attempt Rate %\n")
                val sections = getSectionAnalyticsList(examId)
                sections.forEach { s ->
                    sb.append("${escapeCsv(s.title)},")
                    sb.append("${escapeCsv(s.sectionOrder)},")
                    sb.append("${escapeCsv(s.questionCount)},")
                    sb.append("${escapeCsv(s.totalMarks)},")
                    sb.append("${escapeCsv(s.averageScore)},")
                    sb.append("${escapeCsv("${s.accuracyPercentage}%")},")
                    sb.append("${escapeCsv("${s.attemptRate}%")}\n")
                }
            }

            "INTEGRITY_SUMMARY" -> {
                val integrity = getIntegrityAnalytics(examId)
                sb.append("Metric,Value\n")
                sb.append("Exam Title,${escapeCsv(exam.title)}\n")
                sb.append("Total Integrity Telemetry Events,${integrity.totalEvents}\n")
                sb.append("Unique Flagged Student Attempts,${integrity.uniqueFlaggedAttempts}\n")
                sb.append("Events With App Name Unavailable,${integrity.appNameUnavailableCount}\n")
                sb.append("Total Incident Cases Escalated,${integrity.totalIncidentCases}\n")
                sb.append("Pending Review Incidents,${integrity.pendingReviewIncidentsCount}\n")
                sb.append("Confirmed Misconduct Findings,${integrity.confirmedMisconductCount}\n")
                sb.append("Dismissed Incidents,${integrity.dismissedIncidentsCount}\n")
                sb.append("Results Held Pending Review,${integrity.heldResultsCount}\n")
                sb.append("Cancelled Attempts,${integrity.cancelledAttemptsCount}\n")
                sb.append("Reinstated Attempts,${integrity.reinstatedAttemptsCount}\n")
                sb.append("Appeals Lodged,${integrity.totalAppealsCount}\n")
                sb.append("Pending Appeals,${integrity.pendingAppealsCount}\n")
            }

            else -> { // EXAM_SUMMARY
                val summary = getExamAnalyticsSummary(examId)
                if (summary != null) {
                    sb.append("Metric,Value\n")
                    sb.append("Exam Title,${escapeCsv(summary.examTitle)}\n")
                    sb.append("Duration (minutes),${summary.durationMinutes}\n")
                    sb.append("Total Marks,${summary.totalMarks}\n")
                    sb.append("Total Assigned Students,${summary.totalAssignedStudents}\n")
                    sb.append("Submitted Submissions,${summary.submittedAttemptsCount}\n")
                    sb.append("In Progress Attempts,${summary.inProgressAttemptsCount}\n")
                    sb.append("Results Evaluated,${summary.evaluatedResultsCount}\n")
                    sb.append("Results Published,${summary.publishedResultsCount}\n")
                    sb.append("Results Held,${summary.heldResultsCount}\n")
                    sb.append("Mean Score,${summary.meanScore}\n")
                    sb.append("Median Score,${summary.medianScore}\n")
                    sb.append("Highest Score,${summary.highestScore}\n")
                    sb.append("Lowest Score,${summary.lowestScore}\n")
                    sb.append("Overall Accuracy Rate %,${summary.overallAccuracyRate}\n")
                    sb.append("Average Attempt Duration (minutes),${summary.averageDurationMinutes ?: "N/A"}\n")
                }
            }
        }

        return sb.toString()
    }
}
