import { z } from 'zod';
import { EntityIdSchema, IsoTimestampSchema, ScaledMarkSchema } from './common';

/**
 * Examination lifecycle states
 */
export const ExamStatusSchema = z.enum([
  'DRAFT',
  'PUBLISHED',
  'IN_PROGRESS',
  'COMPLETED',
  'ARCHIVED'
]);

export type ExamStatus = z.infer<typeof ExamStatusSchema>;

/**
 * Question-level marking rule override
 */
export const QuestionMarkingOverrideSchema = z.object({
  questionId: EntityIdSchema,
  correctScaledMark: ScaledMarkSchema,
  incorrectScaledMark: ScaledMarkSchema,
  unansweredScaledMark: ScaledMarkSchema
});

export type QuestionMarkingOverride = z.infer<typeof QuestionMarkingOverrideSchema>;

/**
 * Section-level default marking configuration
 */
export const SectionMarkingRuleSchema = z.object({
  sectionId: EntityIdSchema,
  sectionName: z.string().min(1).max(100),
  correctScaledMark: ScaledMarkSchema,
  incorrectScaledMark: ScaledMarkSchema,
  unansweredScaledMark: ScaledMarkSchema
});

export type SectionMarkingRule = z.infer<typeof SectionMarkingRuleSchema>;

/**
 * Standalone Marking Scheme Schema.
 * Stored and evaluated independently from question content.
 */
export const MarkingSchemeSchema = z.object({
  schemeId: EntityIdSchema,
  version: z.number().int().positive(),
  defaultCorrectScaledMark: ScaledMarkSchema,
  defaultIncorrectScaledMark: ScaledMarkSchema,
  defaultUnansweredScaledMark: ScaledMarkSchema,
  allowNegativeMarking: z.boolean(),
  sectionDefaults: z.array(SectionMarkingRuleSchema).default([]),
  questionOverrides: z.array(QuestionMarkingOverrideSchema).default([])
});

export type MarkingScheme = z.infer<typeof MarkingSchemeSchema>;

/**
 * Question reference within an exam (separated from heavy content/assets)
 */
export const QuestionReferenceSchema = z.object({
  questionId: EntityIdSchema,
  sectionId: EntityIdSchema.optional(),
  sequenceOrder: z.number().int().positive()
});

export type QuestionReference = z.infer<typeof QuestionReferenceSchema>;

/**
 * Core Examination Definition Schema
 */
export const ExamSchema = z.object({
  examId: EntityIdSchema,
  title: z.string().trim().min(1, 'Title cannot be empty').max(200),
  instituteId: EntityIdSchema,
  durationSeconds: z.number().int().positive('Exam duration must be a positive integer in seconds'),
  status: ExamStatusSchema,
  createdAt: IsoTimestampSchema,
  publishedAt: IsoTimestampSchema.optional(),
  markingSchemeVersion: z.number().int().positive(),
  markingScheme: MarkingSchemeSchema,
  questionReferences: z.array(QuestionReferenceSchema).min(1, 'Exam must contain at least one question reference')
});

export type Exam = z.infer<typeof ExamSchema>;
