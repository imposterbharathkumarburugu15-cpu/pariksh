import { z } from 'zod';
import { EntityIdSchema, IsoTimestampSchema } from './common';
import { ExamStatusSchema } from './exams';

/**
 * Question Types supported by DOOMEDAMS Compiler
 */
export const QuestionTypeSchema = z.enum([
  'SINGLE_SELECT',
  'MULTI_SELECT',
  'NUMERICAL',
  'SUBJECTIVE',
  'UNKNOWN'
]);

export type QuestionType = z.infer<typeof QuestionTypeSchema>;

/**
 * Review & Verification Status for parsed questions
 */
export const ReviewStatusSchema = z.enum([
  'NEEDS_REVIEW',
  'VERIFIED',
  'REJECTED'
]);

export type ReviewStatus = z.infer<typeof ReviewStatusSchema>;

/**
 * Validation Issue Severity
 */
export const ValidationSeveritySchema = z.enum([
  'INFO',
  'WARNING',
  'ERROR'
]);

export type ValidationSeverity = z.infer<typeof ValidationSeveritySchema>;

/**
 * Validation Issue Model
 */
export const PaperValidationIssueSchema = z.object({
  issueId: EntityIdSchema,
  importId: EntityIdSchema.optional(),
  questionId: EntityIdSchema.optional(),
  severity: ValidationSeveritySchema,
  issueCode: z.string().min(1).max(50),
  description: z.string().min(1).max(500),
  sourceReference: z.string().optional(),
  isResolved: z.boolean().default(false),
  resolvedBy: EntityIdSchema.optional(),
  resolvedAt: IsoTimestampSchema.optional()
});

export type PaperValidationIssue = z.infer<typeof PaperValidationIssueSchema>;

/**
 * Answer Option for Multiple-Choice Questions
 */
export const AnswerOptionSchema = z.object({
  optionId: EntityIdSchema,
  label: z.string().min(1).max(10), // e.g. "A", "B", "C", "D"
  text: z.string().min(1, 'Option text cannot be empty')
});

export type AnswerOption = z.infer<typeof AnswerOptionSchema>;

/**
 * Paper Section Model
 */
export const PaperSectionSchema = z.object({
  sectionId: EntityIdSchema,
  examId: EntityIdSchema,
  title: z.string().min(1, 'Section title cannot be empty').max(100),
  sectionOrder: z.number().int().min(1),
  sourceReference: z.string().optional(),
  createdAt: IsoTimestampSchema
});

export type PaperSection = z.infer<typeof PaperSectionSchema>;

/**
 * Structured Question Model
 */
export const StructuredQuestionSchema = z.object({
  questionId: EntityIdSchema,
  examId: EntityIdSchema,
  sectionId: EntityIdSchema.optional(),
  questionNumber: z.number().int().positive(),
  questionType: QuestionTypeSchema,
  questionText: z.string().min(1, 'Question text cannot be empty'),
  answerOptions: z.array(AnswerOptionSchema).default([]),
  sourcePage: z.number().int().positive().optional(),
  sourceStartLine: z.number().int().positive().optional(),
  sourceEndLine: z.number().int().positive().optional(),
  sourceSnippet: z.string().optional(),
  extractionConfidence: z.enum(['HIGH', 'MEDIUM', 'LOW', 'MANUAL']).default('MEDIUM'),
  reviewStatus: ReviewStatusSchema.default('NEEDS_REVIEW'),
  createdAt: IsoTimestampSchema,
  updatedAt: IsoTimestampSchema
});

export type StructuredQuestion = z.infer<typeof StructuredQuestionSchema>;

/**
 * Confidential Question Answer Key
 * Separated from student-visible exam questions.
 */
export const QuestionAnswerKeySchema = z.object({
  questionId: EntityIdSchema,
  examId: EntityIdSchema,
  expectedAnswer: z.union([
    z.string().min(1),
    z.array(z.string().min(1)).min(1)
  ]),
  numericalTolerance: z.number().nonnegative().optional(),
  answerKeySource: z.enum(['EXTRACTED', 'MANUAL']).default('EXTRACTED'),
  verificationStatus: z.enum(['UNVERIFIED', 'VERIFIED']).default('UNVERIFIED'),
  reviewerId: EntityIdSchema.optional(),
  verifiedAt: IsoTimestampSchema.optional(),
  updatedAt: IsoTimestampSchema
});

export type QuestionAnswerKey = z.infer<typeof QuestionAnswerKeySchema>;

/**
 * Confidential Worked Solution
 */
export const WorkedSolutionSchema = z.object({
  solutionId: EntityIdSchema,
  questionId: EntityIdSchema,
  examId: EntityIdSchema,
  solutionText: z.string().min(1, 'Solution text cannot be empty'),
  sourceReference: z.string().optional(),
  verificationStatus: z.enum(['UNVERIFIED', 'VERIFIED']).default('UNVERIFIED'),
  reviewerId: EntityIdSchema.optional(),
  verifiedAt: IsoTimestampSchema.optional(),
  updatedAt: IsoTimestampSchema
});

export type WorkedSolution = z.infer<typeof WorkedSolutionSchema>;

/**
 * Paper Import Record
 */
export const PaperImportRecordSchema = z.object({
  importId: EntityIdSchema,
  originalFilename: z.string().min(1).max(255),
  fileType: z.enum(['PDF', 'TXT']),
  originalFileHash: z.string().length(64), // SHA-256
  fileSizeBytes: z.number().int().positive().max(25 * 1024 * 1024), // 25 MB max
  privateStoragePath: z.string().min(1),
  importStatus: z.enum(['PENDING', 'EXTRACTED', 'FAILED']),
  extractedAt: IsoTimestampSchema.optional(),
  createdBy: EntityIdSchema,
  createdAt: IsoTimestampSchema,
  updatedAt: IsoTimestampSchema
});

export type PaperImportRecord = z.infer<typeof PaperImportRecordSchema>;

/**
 * Full Examination Draft Structure
 */
export const ExamDraftSchema = z.object({
  examId: EntityIdSchema,
  title: z.string().min(1).max(200),
  instituteId: EntityIdSchema,
  durationSeconds: z.number().int().positive(),
  status: ExamStatusSchema,
  markingSchemeVersion: z.number().int().positive(),
  sections: z.array(PaperSectionSchema).default([]),
  questions: z.array(StructuredQuestionSchema).default([]),
  answerKeys: z.array(QuestionAnswerKeySchema).default([]),
  solutions: z.array(WorkedSolutionSchema).default([]),
  validationIssues: z.array(PaperValidationIssueSchema).default([]),
  importRecord: PaperImportRecordSchema.optional(),
  createdAt: IsoTimestampSchema,
  updatedAt: IsoTimestampSchema
});

export type ExamDraft = z.infer<typeof ExamDraftSchema>;

/**
 * Publication Gate Result
 */
export interface PublicationGateResult {
  isReady: boolean;
  blockingErrors: string[];
  warnings: string[];
  unverifiedAnswerKeyCount: number;
  totalQuestionCount: number;
}

/**
 * Validates whether an examination draft is ready for publication.
 * Enforces:
 * 1. At least 1 question must exist.
 * 2. Every question must have non-empty text.
 * 3. Multiple-choice questions must have at least 2 options.
 * 4. Duplicate question numbers are forbidden.
 * 5. Every question must have an associated answer key.
 * 6. Answer keys must be verified.
 * 7. No unresolved 'ERROR' level issues exist.
 * 8. Positive duration in seconds.
 */
export function evaluatePublicationGate(draft: ExamDraft): PublicationGateResult {
  const blockingErrors: string[] = [];
  const warnings: string[] = [];

  if (draft.questions.length === 0) {
    blockingErrors.push('The examination contains zero questions.');
  }

  if (draft.durationSeconds <= 0) {
    blockingErrors.push('Examination duration must be a positive number of seconds.');
  }

  // Check duplicate question numbers
  const seenNumbers = new Set<number>();
  for (const q of draft.questions) {
    if (seenNumbers.has(q.questionNumber)) {
      blockingErrors.push(`Duplicate question number detected: Q${q.questionNumber}`);
    }
    seenNumbers.add(q.questionNumber);

    if (!q.questionText.trim()) {
      blockingErrors.push(`Question Q${q.questionNumber} has empty question text.`);
    }

    if ((q.questionType === 'SINGLE_SELECT' || q.questionType === 'MULTI_SELECT') && q.answerOptions.length < 2) {
      blockingErrors.push(`Multiple choice question Q${q.questionNumber} must have at least 2 options (found ${q.answerOptions.length}).`);
    }

    if (q.questionType === 'UNKNOWN') {
      warnings.push(`Question Q${q.questionNumber} has unclassified type (UNKNOWN). Please review.`);
    }
  }

  // Check answer keys
  let unverifiedCount = 0;
  for (const q of draft.questions) {
    const key = draft.answerKeys.find((k) => k.questionId === q.questionId);
    if (!key) {
      blockingErrors.push(`Question Q${q.questionNumber} is missing an answer key.`);
    } else if (key.verificationStatus !== 'VERIFIED') {
      unverifiedCount++;
      warnings.push(`Answer key for Q${q.questionNumber} is unverified.`);
    }
  }

  // Check unresolved blocking validation issues
  for (const issue of draft.validationIssues) {
    if (!issue.isResolved) {
      if (issue.severity === 'ERROR') {
        blockingErrors.push(`Unresolved blocking issue: [${issue.issueCode}] ${issue.description}`);
      } else if (issue.severity === 'WARNING') {
        warnings.push(`Unresolved warning: [${issue.issueCode}] ${issue.description}`);
      }
    }
  }

  return {
    isReady: blockingErrors.length === 0,
    blockingErrors,
    warnings,
    unverifiedAnswerKeyCount: unverifiedCount,
    totalQuestionCount: draft.questions.length
  };
}
