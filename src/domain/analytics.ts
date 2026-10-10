import { z } from 'zod';
import { EntityIdSchema, IsoTimestampSchema } from './common';

export const ScoreDistributionBucketSchema = z.object({
  rangeLabel: z.string(),
  count: z.number().int().nonnegative(),
  percentageOfStudents: z.number().min(0).max(100)
});

export type ScoreDistributionBucket = z.infer<typeof ScoreDistributionBucketSchema>;

export const OverviewAnalyticsSchema = z.object({
  totalExams: z.number().int().nonnegative(),
  publishedExams: z.number().int().nonnegative(),
  inProgressExams: z.number().int().nonnegative(),
  completedExams: z.number().int().nonnegative(),
  totalAssignedStudents: z.number().int().nonnegative(),
  activeAttempts: z.number().int().nonnegative(),
  acceptedSubmissions: z.number().int().nonnegative(),
  pendingSubmissions: z.number().int().nonnegative(),
  gradingCompletionPercentage: z.number().min(0).max(100),
  publishedResultsCount: z.number().int().nonnegative(),
  resultsAwaitingPublicationCount: z.number().int().nonnegative(),
  resultsHeldCount: z.number().int().nonnegative(),
  withheldResultsCount: z.number().int().nonnegative(),
  outstandingIncidentsCount: z.number().int().nonnegative(),
  pendingAppealsCount: z.number().int().nonnegative(),
  generatedAt: IsoTimestampSchema
});

export type OverviewAnalytics = z.infer<typeof OverviewAnalyticsSchema>;

export const ExamSummaryAnalyticsSchema = z.object({
  examId: EntityIdSchema,
  examTitle: z.string(),
  durationMinutes: z.number().int().positive(),
  totalMarks: z.number().nonnegative(),
  publishedAt: IsoTimestampSchema.nullable(),
  totalAssignedStudents: z.number().int().nonnegative(),
  startedAttemptsCount: z.number().int().nonnegative(),
  inProgressAttemptsCount: z.number().int().nonnegative(),
  submittedAttemptsCount: z.number().int().nonnegative(),
  cancelledAttemptsCount: z.number().int().nonnegative(),
  timedOutAttemptsCount: z.number().int().nonnegative(),
  evaluatedResultsCount: z.number().int().nonnegative(),
  publishedResultsCount: z.number().int().nonnegative(),
  heldResultsCount: z.number().int().nonnegative(),
  meanScore: z.number(),
  medianScore: z.number(),
  highestScore: z.number(),
  lowestScore: z.number(),
  maxPossibleScore: z.number().nonnegative(),
  overallAccuracyRate: z.number().min(0).max(100),
  averageDurationMinutes: z.number().nullable(),
  scoreDistribution: z.array(ScoreDistributionBucketSchema),
  integrityEventsCount: z.number().int().nonnegative(),
  flaggedAttemptsCount: z.number().int().nonnegative(),
  incidentCasesCount: z.number().int().nonnegative(),
  pendingIncidentsCount: z.number().int().nonnegative(),
  appealsCount: z.number().int().nonnegative(),
  pendingAppealsCount: z.number().int().nonnegative()
});

export type ExamSummaryAnalytics = z.infer<typeof ExamSummaryAnalyticsSchema>;

export const StudentPerformanceSchema = z.object({
  studentId: EntityIdSchema,
  studentDisplayName: z.string(),
  attemptId: EntityIdSchema,
  attemptStatus: z.string(),
  resultStatus: z.string(),
  score: z.number().nullable(),
  maxPossibleMarks: z.number().nullable(),
  percentage: z.number().nullable(),
  correctCount: z.number().int().nonnegative().nullable(),
  incorrectCount: z.number().int().nonnegative().nullable(),
  unansweredCount: z.number().int().nonnegative().nullable(),
  partiallyCorrectCount: z.number().int().nonnegative().nullable(),
  accuracyPercentage: z.number().min(0).max(100).nullable(),
  rank: z.number().int().positive().nullable(),
  isTiedRank: z.boolean(),
  holdReason: z.string().nullable(),
  startedAt: IsoTimestampSchema,
  submittedAt: IsoTimestampSchema.nullable(),
  durationMinutes: z.number().nullable(),
  incidentCount: z.number().int().nonnegative(),
  hasPendingAppeals: z.boolean()
});

export type StudentPerformance = z.infer<typeof StudentPerformanceSchema>;

export const OptionDistributionItemSchema = z.object({
  optionId: z.string(),
  label: z.string(),
  selectionCount: z.number().int().nonnegative(),
  selectionPercentage: z.number().min(0).max(100)
});

export type OptionDistributionItem = z.infer<typeof OptionDistributionItemSchema>;

export const QuestionAnalyticsSchema = z.object({
  questionId: EntityIdSchema,
  questionNumber: z.number().int().positive(),
  sectionId: EntityIdSchema.nullable(),
  sectionTitle: z.string(),
  questionType: z.string(),
  questionText: z.string(),
  maxMarks: z.number(),
  eligibleCandidatesDenominator: z.number().int().nonnegative(),
  attemptedCount: z.number().int().nonnegative(),
  unansweredCount: z.number().int().nonnegative(),
  correctCount: z.number().int().nonnegative(),
  incorrectCount: z.number().int().nonnegative(),
  partiallyCorrectCount: z.number().int().nonnegative(),
  attemptRate: z.number().min(0).max(100),
  correctnessRate: z.number().min(0).max(100),
  averageMarksAwarded: z.number(),
  difficultyCategory: z.string(),
  recordedTimeNotice: z.string(),
  optionDistributions: z.array(OptionDistributionItemSchema)
});

export type QuestionAnalytics = z.infer<typeof QuestionAnalyticsSchema>;

export const SectionAnalyticsSchema = z.object({
  sectionId: EntityIdSchema,
  title: z.string(),
  sectionOrder: z.number().int().positive(),
  questionCount: z.number().int().nonnegative(),
  totalMarks: z.number().nonnegative(),
  averageScore: z.number(),
  accuracyPercentage: z.number().min(0).max(100),
  attemptRate: z.number().min(0).max(100),
  highestScore: z.number(),
  lowestScore: z.number()
});

export type SectionAnalytics = z.infer<typeof SectionAnalyticsSchema>;

export const QuestionGapItemSchema = z.object({
  questionNumber: z.number().int().positive(),
  sectionTitle: z.string(),
  questionType: z.string(),
  correctnessRate: z.number().min(0).max(100),
  incorrectRate: z.number().min(0).max(100)
});

export type QuestionGapItem = z.infer<typeof QuestionGapItemSchema>;

export const LearningGapsSchema = z.object({
  curriculumTagsAvailable: z.boolean(),
  disclosureNotice: z.string(),
  weakestSections: z.array(z.string()),
  highIncorrectQuestions: z.array(QuestionGapItemSchema),
  explainableRecommendations: z.array(z.string())
});

export type LearningGaps = z.infer<typeof LearningGapsSchema>;

export const IntegrityAnalyticsSchema = z.object({
  examId: EntityIdSchema,
  totalEvents: z.number().int().nonnegative(),
  uniqueFlaggedAttempts: z.number().int().nonnegative(),
  appNameUnavailableCount: z.number().int().nonnegative(),
  totalIncidentCases: z.number().int().nonnegative(),
  confirmedMisconductCount: z.number().int().nonnegative(),
  dismissedIncidentsCount: z.number().int().nonnegative(),
  pendingReviewIncidentsCount: z.number().int().nonnegative(),
  heldResultsCount: z.number().int().nonnegative(),
  cancelledAttemptsCount: z.number().int().nonnegative(),
  reinstatedAttemptsCount: z.number().int().nonnegative(),
  totalAppealsCount: z.number().int().nonnegative(),
  pendingAppealsCount: z.number().int().nonnegative(),
  eventsByCategory: z.record(z.number().int().nonnegative()),
  incidentsByStatus: z.record(z.number().int().nonnegative())
});

export type IntegrityAnalytics = z.infer<typeof IntegrityAnalyticsSchema>;

export const StudentQuestionAnswerDetailSchema = z.object({
  questionId: EntityIdSchema,
  questionNumber: z.number().int().positive(),
  sectionTitle: z.string(),
  questionType: z.string(),
  questionText: z.string(),
  optionsJson: z.string(),
  submittedAnswer: z.string().nullable(),
  answerClassification: z.string(),
  marksAwarded: z.number(),
  maxMarks: z.number(),
  explanationCode: z.string(),
  revisionCount: z.number().int().nonnegative(),
  answerSavedAt: IsoTimestampSchema.nullable(),
  timeUsedNotice: z.string(),
  correctAnswerSnapshot: z.string().nullable()
});

export type StudentQuestionAnswerDetail = z.infer<typeof StudentQuestionAnswerDetailSchema>;

export const StudentExamProfileSchema = z.object({
  studentId: EntityIdSchema,
  studentDisplayName: z.string(),
  examId: EntityIdSchema,
  examTitle: z.string(),
  attemptId: EntityIdSchema,
  attemptStatus: z.string(),
  resultStatus: z.string(),
  startedAt: IsoTimestampSchema,
  submittedAt: IsoTimestampSchema.nullable(),
  durationMinutes: z.number().nullable(),
  totalScaledScore: z.number().nullable(),
  maxPossibleScore: z.number().nullable(),
  percentage: z.number().nullable(),
  accuracyPercentage: z.number().nullable(),
  correctCount: z.number().int().nonnegative().nullable(),
  incorrectCount: z.number().int().nonnegative().nullable(),
  unansweredCount: z.number().int().nonnegative().nullable(),
  holdReason: z.string().nullable(),
  localSaveCount: z.number().int().nonnegative(),
  submissionReceiptHash: z.string().nullable(),
  questions: z.array(StudentQuestionAnswerDetailSchema)
});

export type StudentExamProfile = z.infer<typeof StudentExamProfileSchema>;

export const ReportTypeSchema = z.enum([
  'EXAM_SUMMARY',
  'STUDENT_PERFORMANCE',
  'QUESTION_ANALYSIS',
  'SECTION_PERFORMANCE',
  'INTEGRITY_SUMMARY'
]);

export type ReportType = z.infer<typeof ReportTypeSchema>;

export const LiveExamMonitorDeviceSchema = z.object({
  studentId: z.string(),
  studentDisplayName: z.string(),
  connectivityStatus: z.string(),
  attemptStatus: z.string(),
  lastActivityAt: z.string().nullable(),
  lockdownActive: z.boolean()
});

export type LiveExamMonitorDevice = z.infer<typeof LiveExamMonitorDeviceSchema>;

export const LiveExamMonitorDataSchema = z.object({
  examId: z.string(),
  examTitle: z.string(),
  serverTimestamp: z.string(),
  totalAssignedCandidates: z.number().int().nonnegative(),
  startedCount: z.number().int().nonnegative(),
  submittedCount: z.number().int().nonnegative(),
  inProgressCount: z.number().int().nonnegative(),
  recentIntegrityAlertsCount: z.number().int().nonnegative(),
  devices: z.array(LiveExamMonitorDeviceSchema)
});

export type LiveExamMonitorData = z.infer<typeof LiveExamMonitorDataSchema>;

// Aliases for comprehensive API compatibility
export const ExamAnalyticsSummarySchema = ExamSummaryAnalyticsSchema;
export type ExamAnalyticsSummary = ExamSummaryAnalytics;

export const StudentPerformanceItemSchema = StudentPerformanceSchema;
export type StudentPerformanceItem = StudentPerformance;

export const QuestionAnalyticsItemSchema = QuestionAnalyticsSchema;
export type QuestionAnalyticsItem = QuestionAnalytics;

export const SectionAnalyticsItemSchema = SectionAnalyticsSchema;
export type SectionAnalyticsItem = SectionAnalytics;

export const LearningGapsAnalyticsSchema = LearningGapsSchema;
export type LearningGapsAnalytics = LearningGaps;

export const IntegrityAnalyticsSummarySchema = IntegrityAnalyticsSchema;
export type IntegrityAnalyticsSummary = IntegrityAnalytics;
