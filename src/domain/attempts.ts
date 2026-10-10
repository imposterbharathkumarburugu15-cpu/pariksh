import { z } from 'zod';
import { EntityIdSchema, IsoTimestampSchema, ScaledMarkSchema } from './common';

/**
 * Exam Attempt Lifecycle Status
 */
export const AttemptStatusSchema = z.enum([
  'NOT_STARTED',
  'IN_PROGRESS',
  'SUBMITTED',
  'TIMED_OUT',
  'ABANDONED',
  'CANCELLED'
]);

export type AttemptStatus = z.infer<typeof AttemptStatusSchema>;

/**
 * Distinct Exam Result Status.
 * CRITICAL ARCHITECTURAL CONSTRAINT:
 * Result status is strictly decoupled from AttemptStatus.
 * An attempt can be 'SUBMITTED' while its result is 'HELD_PENDING_REVIEW'.
 * A result hold must never overwrite or mutate the attempt lifecycle status.
 */
export const ResultStatusSchema = z.enum([
  'PENDING_GRADING',
  'HELD_PENDING_REVIEW',
  'PUBLISHED',
  'WITHHELD'
]);

export type ResultStatus = z.infer<typeof ResultStatusSchema>;

/**
 * Question Visit Record: Tracks entry, exit, sequence, and active duration
 */
export const QuestionVisitSchema = z.object({
  visitId: EntityIdSchema,
  questionId: EntityIdSchema,
  visitSequence: z.number().int().positive(),
  entryTimestamp: IsoTimestampSchema,
  exitTimestamp: IsoTimestampSchema.optional(),
  durationSeconds: z.number().int().nonnegative().optional()
});

export type QuestionVisit = z.infer<typeof QuestionVisitSchema>;

/**
 * Candidate Response: The current active answer recorded for a question
 */
export const CandidateResponseSchema = z.object({
  questionId: EntityIdSchema,
  responseValue: z.union([z.string(), z.array(z.string()), z.number()]),
  lastSavedTimestamp: IsoTimestampSchema,
  revisionCount: z.number().int().nonnegative().default(0)
});

export type CandidateResponse = z.infer<typeof CandidateResponseSchema>;

/**
 * Answer Revision: Immutable history of changes to an answer
 */
export const AnswerRevisionSchema = z.object({
  revisionId: EntityIdSchema,
  attemptId: EntityIdSchema,
  questionId: EntityIdSchema,
  originalResponse: z.union([z.string(), z.array(z.string()), z.number()]).nullable(),
  updatedResponse: z.union([z.string(), z.array(z.string()), z.number()]),
  revisionSequence: z.number().int().positive(),
  eventTimestamp: IsoTimestampSchema
});

export type AnswerRevision = z.infer<typeof AnswerRevisionSchema>;

/**
 * Submission Record: Formal finalized submission event
 */
export const SubmissionRecordSchema = z.object({
  submissionId: EntityIdSchema,
  attemptId: EntityIdSchema,
  submittedAt: IsoTimestampSchema,
  clientChecksum: z.string().min(16),
  totalQuestionsAnswered: z.number().int().nonnegative(),
  serverReceivedTimestamp: IsoTimestampSchema.optional()
});

export type SubmissionRecord = z.infer<typeof SubmissionRecordSchema>;

/**
 * Comprehensive Examination Attempt Schema
 */
export const ExamAttemptSchema = z.object({
  attemptId: EntityIdSchema,
  examId: EntityIdSchema,
  studentId: EntityIdSchema,
  status: AttemptStatusSchema,
  startTimestamp: IsoTimestampSchema,
  submissionTimestamp: IsoTimestampSchema.optional(),
  allocatedDurationSeconds: z.number().int().positive('Duration must be positive'),
  scheduledDeadline: IsoTimestampSchema,
  activeDeviceId: EntityIdSchema.optional()
});

export type ExamAttempt = z.infer<typeof ExamAttemptSchema>;

/**
 * Final / Interim Scoring Result Record (Decoupled from Attempt)
 */
export const ExamResultRecordSchema = z.object({
  resultId: EntityIdSchema,
  attemptId: EntityIdSchema,
  examId: EntityIdSchema,
  studentId: EntityIdSchema,
  resultStatus: ResultStatusSchema,
  totalScaledScore: ScaledMarkSchema.optional(),
  scoredAt: IsoTimestampSchema.optional(),
  publishedAt: IsoTimestampSchema.optional(),
  holdReason: z.string().optional()
});

export type ExamResultRecord = z.infer<typeof ExamResultRecordSchema>;
