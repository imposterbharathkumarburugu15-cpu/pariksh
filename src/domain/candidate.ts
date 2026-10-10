import { z } from 'zod';
import { EntityIdSchema, IsoTimestampSchema, ScaledMarkSchema } from './common';
import { QuestionTypeSchema } from './compiler';

/**
 * Palette status for individual questions in CBT interface.
 */
export const QuestionPaletteStatusSchema = z.enum([
  'NOT_VISITED',
  'UNANSWERED',
  'ANSWERED',
  'MARKED_FOR_REVIEW',
  'ANSWERED_AND_MARKED'
]);

export type QuestionPaletteStatus = z.infer<typeof QuestionPaletteStatusSchema>;

/**
 * Student-visible Question schema:
 * CRITICAL CONFIDENTIALITY INVARIANT:
 * NEVER contains expectedAnswer, answerKey, or worked solutions!
 */
export const CandidateQuestionSchema = z.object({
  questionId: EntityIdSchema,
  questionNumber: z.number().int().positive(),
  sectionId: EntityIdSchema.nullable().optional(),
  questionType: QuestionTypeSchema,
  questionText: z.string().min(1),
  answerOptions: z.array(z.string()).default([]),
  sourcePage: z.number().int().positive().nullable().optional()
});

export type CandidateQuestion = z.infer<typeof CandidateQuestionSchema>;

/**
 * Student-visible Section schema
 */
export const CandidateSectionSchema = z.object({
  sectionId: EntityIdSchema,
  title: z.string().min(1),
  sectionOrder: z.number().int().positive()
});

export type CandidateSection = z.infer<typeof CandidateSectionSchema>;

/**
 * Student-visible Marking Summary
 */
export const CandidateMarkingSummarySchema = z.object({
  correctScaledMark: ScaledMarkSchema,
  incorrectScaledMark: ScaledMarkSchema,
  unansweredScaledMark: ScaledMarkSchema,
  allowNegativeMarking: z.boolean()
});

export type CandidateMarkingSummary = z.infer<typeof CandidateMarkingSummarySchema>;

/**
 * Authorized Candidate Examination Package (Student-facing download)
 */
export const CandidateExamPackageSchema = z.object({
  examId: EntityIdSchema,
  title: z.string().min(1),
  durationSeconds: z.number().int().positive(),
  instituteId: EntityIdSchema,
  markingSchemeVersion: z.number().int().positive(),
  packageHash: z.string().min(16),
  sections: z.array(CandidateSectionSchema),
  questions: z.array(CandidateQuestionSchema),
  markingSummary: CandidateMarkingSummarySchema.optional()
});

export type CandidateExamPackage = z.infer<typeof CandidateExamPackageSchema>;

/**
 * Attempt Metadata returned by authoritative server session start
 */
export const CandidateAttemptSessionSchema = z.object({
  attemptId: EntityIdSchema,
  examId: EntityIdSchema,
  studentId: EntityIdSchema,
  attemptStatus: z.string(),
  resultStatus: z.string(),
  startedAt: IsoTimestampSchema,
  deadlineMetadata: z.object({
    officialStartTime: IsoTimestampSchema,
    officialDeadline: IsoTimestampSchema,
    durationSeconds: z.number().int().positive()
  })
});

export type CandidateAttemptSession = z.infer<typeof CandidateAttemptSessionSchema>;

/**
 * Submission Receipt schema
 */
export const CandidateSubmissionReceiptSchema = z.object({
  receiptId: EntityIdSchema,
  attemptId: EntityIdSchema,
  examId: EntityIdSchema,
  studentId: EntityIdSchema,
  submissionStatus: z.string(),
  submittedAt: IsoTimestampSchema,
  receiptHash: z.string().min(16),
  totalQuestionsAnswered: z.number().int().nonnegative()
});

export type CandidateSubmissionReceipt = z.infer<typeof CandidateSubmissionReceiptSchema>;

/**
 * Student Assigned Exam Summary
 */
export const StudentExamSummarySchema = z.object({
  examId: EntityIdSchema,
  title: z.string().min(1),
  durationSeconds: z.number().int().positive(),
  instituteId: EntityIdSchema,
  markingSchemeVersion: z.number().int().positive(),
  publishedAt: IsoTimestampSchema.optional(),
  totalQuestions: z.number().int().nonnegative(),
  totalSections: z.number().int().nonnegative(),
  attempt: z.object({
    attemptId: EntityIdSchema,
    attemptStatus: z.string(),
    resultStatus: z.string(),
    startedAt: IsoTimestampSchema,
    submittedAt: IsoTimestampSchema.nullable().optional(),
    deadlineMetadata: z.object({
      officialStartTime: IsoTimestampSchema,
      officialDeadline: IsoTimestampSchema,
      durationSeconds: z.number().int().positive()
    }).optional()
  }).nullable().optional(),
  hasPublishedResult: z.boolean().default(false),
  isResultHeld: z.boolean().default(false)
});

export type StudentExamSummary = z.infer<typeof StudentExamSummarySchema>;

/**
 * Working local answer record on candidate device
 */
export interface LocalWorkingAnswer {
  questionId: string;
  responsePayload: string;
  revisionSequence: number;
  isMarkedForReview: boolean;
  isVisited: boolean;
  savedLocallyAt: string;
  serverSyncedAt: string | null;
  syncStatus: 'SAVING_LOCALLY' | 'SAVED_LOCALLY' | 'SYNC_PENDING' | 'ACCEPTED' | 'ERROR';
}

/**
 * Helper to compute Question Palette Status
 */
export function computePaletteStatus(answer?: LocalWorkingAnswer): QuestionPaletteStatus {
  if (!answer || !answer.isVisited) {
    return 'NOT_VISITED';
  }

  const hasAnswer = answer.responsePayload.trim().length > 0 && answer.responsePayload !== '[]';

  if (answer.isMarkedForReview && hasAnswer) {
    return 'ANSWERED_AND_MARKED';
  }
  if (answer.isMarkedForReview) {
    return 'MARKED_FOR_REVIEW';
  }
  if (hasAnswer) {
    return 'ANSWERED';
  }
  return 'UNANSWERED';
}
