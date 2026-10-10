import { z } from 'zod';
import { EntityIdSchema, IsoTimestampSchema } from './common';
import { ResultStatusSchema } from './attempts';

/**
 * Incident categories reflecting integrity signals requiring review
 */
export const IncidentCategorySchema = z.enum([
  'UNAUTHORISED_APP_SWITCH',
  'WINDOW_BLUR',
  'APP_BACKGROUND',
  'NETWORK_ANOMALY',
  'UNAUTHORISED_DEVICE',
  'MULTIPLE_DISPLAYS',
  'PACKAGE_VALIDATION_FAILURE'
]);

export type IncidentCategory = z.infer<typeof IncidentCategorySchema>;

/**
 * Objective evidence assessment classifications
 */
export const EvidenceAssessmentSchema = z.enum([
  'SUFFICIENT',
  'LIMITED',
  'UNAVAILABLE',
  'CONTRADICTORY'
]);

export type EvidenceAssessment = z.infer<typeof EvidenceAssessmentSchema>;

/**
 * Adjudicated incident decision
 */
export const IncidentDecisionSchema = z.enum([
  'NONE',
  'INCONCLUSIVE',
  'CONFIRMED',
  'DISMISSED'
]);

export type IncidentDecision = z.infer<typeof IncidentDecisionSchema>;

/**
 * Formal incident lifecycle states
 */
export const IncidentLifecycleStatusSchema = z.enum([
  'DETECTED',
  'PENDING_REVIEW',
  'UNDER_REVIEW',
  'PENDING_FURTHER_REVIEW',
  'CONFIRMED',
  'DISMISSED',
  'CLOSED'
]);

export type IncidentLifecycleStatus = z.infer<typeof IncidentLifecycleStatusSchema>;

/**
 * Policy actions available to administrators
 * Note: 'HOLD' and 'CANCEL_ATTEMPT' are strictly separate actions.
 */
export const PolicyActionSchema = z.enum([
  'NONE',
  'WARN',
  'HOLD',
  'CANCEL_ATTEMPT',
  'REINSTATE_ATTEMPT'
]);

export type PolicyAction = z.infer<typeof PolicyActionSchema>;

/**
 * Execution state of a policy action.
 * Kept strictly separate from the requested action.
 */
export const PolicyActionExecutionStatusSchema = z.enum([
  'REQUESTED',
  'PENDING',
  'APPLIED',
  'REJECTED',
  'FAILED',
  'REVERSED'
]);

export type PolicyActionExecutionStatus = z.infer<typeof PolicyActionExecutionStatusSchema>;

/**
 * Comprehensive Incident Review Record
 */
export const IncidentReviewSchema = z.object({
  incidentId: EntityIdSchema,
  examId: EntityIdSchema,
  attemptId: EntityIdSchema,
  studentId: EntityIdSchema,
  category: IncidentCategorySchema,
  lifecycleStatus: IncidentLifecycleStatusSchema,
  evidenceAssessment: EvidenceAssessmentSchema,
  decision: IncidentDecisionSchema.default('NONE'),
  evidenceReferences: z.array(EntityIdSchema).default([]),
  reviewerId: EntityIdSchema.optional(),
  reviewerJustification: z.string().default('No notes supplied'),
  associatedPolicyAction: PolicyActionSchema.default('NONE'),
  policyActionExecutionStatus: PolicyActionExecutionStatusSchema.default('PENDING'),
  policyVersion: z.string().min(1).default('v1.0'),
  createdAt: IsoTimestampSchema,
  updatedAt: IsoTimestampSchema,
  version: z.number().int().nonnegative().default(1)
});

export type IncidentReview = z.infer<typeof IncidentReviewSchema>;

/**
 * Proposal / Intent contract for incident review actions
 */
export const IncidentReviewProposalSchema = z.object({
  category: IncidentCategorySchema,
  evidenceAssessment: EvidenceAssessmentSchema,
  requestedPolicyAction: PolicyActionSchema,
  reviewerJustification: z.string().min(1),
  intendedIncidentStatus: IncidentLifecycleStatusSchema,
  intendedResultStatus: ResultStatusSchema
});

export type IncidentReviewProposal = z.infer<typeof IncidentReviewProposalSchema>;

/**
 * Prescribed test fixture for the limited-evidence hold scenario
 */
export const REQUIRED_LIMITED_EVIDENCE_HOLD_FIXTURE: IncidentReviewProposal = {
  category: 'UNAUTHORISED_APP_SWITCH',
  evidenceAssessment: 'LIMITED',
  requestedPolicyAction: 'HOLD',
  reviewerJustification: 'No notes supplied',
  intendedIncidentStatus: 'PENDING_FURTHER_REVIEW',
  intendedResultStatus: 'HELD_PENDING_REVIEW'
};

/**
 * Formal Appeal status values
 */
export const AppealStatusSchema = z.enum([
  'SUBMITTED',
  'PENDING_REVIEW',
  'UNDER_REVIEW',
  'UPHELD',
  'OVERTURNED',
  'REQUIRES_MORE_INFORMATION',
  'CLOSED'
]);

export type AppealStatus = z.infer<typeof AppealStatusSchema>;

export const AppealChallengedActionSchema = z.enum([
  'RESULT_HOLD',
  'CANCEL_ATTEMPT',
  'CONFIRMED_VIOLATION'
]);

export type AppealChallengedAction = z.infer<typeof AppealChallengedActionSchema>;

export const AppealDecisionOutcomeSchema = z.enum([
  'UPHELD',
  'OVERTURNED',
  'REQUIRES_MORE_INFO'
]);

export type AppealDecisionOutcome = z.infer<typeof AppealDecisionOutcomeSchema>;

export const IncidentAppealSchema = z.object({
  appealId: EntityIdSchema,
  incidentId: EntityIdSchema,
  attemptId: EntityIdSchema,
  studentId: EntityIdSchema,
  challengedAction: AppealChallengedActionSchema,
  appealReason: z.string().min(1),
  evidenceReferences: z.string().nullable().optional(),
  appealStatus: AppealStatusSchema,
  assignedReviewerId: EntityIdSchema.nullable().optional(),
  reviewerDecision: AppealDecisionOutcomeSchema.nullable().optional(),
  outcomeReason: z.string().nullable().optional(),
  submittedAt: IsoTimestampSchema,
  resolvedAt: IsoTimestampSchema.nullable().optional(),
  updatedAt: IsoTimestampSchema
});

export type IncidentAppeal = z.infer<typeof IncidentAppealSchema>;
