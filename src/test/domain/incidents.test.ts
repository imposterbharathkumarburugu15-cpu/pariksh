import { describe, it, expect } from 'vitest';
import {
  IncidentReviewSchema,
  IncidentCategorySchema,
  EvidenceAssessmentSchema,
  IncidentDecisionSchema,
  IncidentLifecycleStatusSchema,
  PolicyActionSchema,
  PolicyActionExecutionStatusSchema,
  IncidentReviewProposalSchema,
  REQUIRED_LIMITED_EVIDENCE_HOLD_FIXTURE
} from '../../domain/incidents';

describe('Incident Review Schemas', () => {
  const timestamp = '2026-10-09T11:00:00Z';

  it('validates a complete incident review record', () => {
    const incident = {
      incidentId: 'inc_001',
      examId: 'exam_101',
      attemptId: 'att_001',
      studentId: 'std_001',
      category: 'UNAUTHORISED_APP_SWITCH',
      lifecycleStatus: 'PENDING_REVIEW',
      evidenceAssessment: 'LIMITED',
      decision: 'NONE',
      evidenceReferences: ['evt_001'],
      reviewerId: 'usr_admin_01',
      reviewerJustification: 'Flagged for proctor scrutiny',
      associatedPolicyAction: 'HOLD',
      policyActionExecutionStatus: 'PENDING',
      policyVersion: 'v1.0',
      createdAt: timestamp,
      updatedAt: timestamp,
      version: 1
    };

    const parsed = IncidentReviewSchema.safeParse(incident);
    expect(parsed.success).toBe(true);
  });

  it('validates the prescribed limited-evidence hold fixture', () => {
    const parsed = IncidentReviewProposalSchema.safeParse(REQUIRED_LIMITED_EVIDENCE_HOLD_FIXTURE);
    expect(parsed.success).toBe(true);
    if (parsed.success) {
      expect(parsed.data.category).toBe('UNAUTHORISED_APP_SWITCH');
      expect(parsed.data.evidenceAssessment).toBe('LIMITED');
      expect(parsed.data.requestedPolicyAction).toBe('HOLD');
      expect(parsed.data.reviewerJustification).toBe('No notes supplied');
      expect(parsed.data.intendedIncidentStatus).toBe('PENDING_FURTHER_REVIEW');
      expect(parsed.data.intendedResultStatus).toBe('HELD_PENDING_REVIEW');
    }
  });

  it('rejects unsupported evidence assessments', () => {
    expect(EvidenceAssessmentSchema.safeParse('OVERWHELMING').success).toBe(false);
    expect(EvidenceAssessmentSchema.safeParse('DUBIOUS').success).toBe(false);

    const validAssessments = ['SUFFICIENT', 'LIMITED', 'UNAVAILABLE', 'CONTRADICTORY'];
    for (const val of validAssessments) {
      expect(EvidenceAssessmentSchema.safeParse(val).success).toBe(true);
    }
  });

  it('rejects unsupported incident categories', () => {
    expect(IncidentCategorySchema.safeParse('WHISPERING_VOICE').success).toBe(false);
    expect(IncidentCategorySchema.safeParse('UNAUTHORISED_APP_SWITCH').success).toBe(true);
  });

  it('strictly separates HOLD and CANCEL_ATTEMPT policy actions', () => {
    const holdAction = PolicyActionSchema.parse('HOLD');
    const cancelAction = PolicyActionSchema.parse('CANCEL_ATTEMPT');

    expect(holdAction).toBe('HOLD');
    expect(cancelAction).toBe('CANCEL_ATTEMPT');
    expect(holdAction).not.toBe(cancelAction);
  });

  it('keeps policy action and execution status strictly separate', () => {
    const action = PolicyActionSchema.parse('HOLD');
    const execution = PolicyActionExecutionStatusSchema.parse('REQUESTED');

    expect(action).toBe('HOLD');
    expect(execution).toBe('REQUESTED');

    // Action cannot be assigned an execution status enum
    expect(PolicyActionSchema.safeParse('APPLIED').success).toBe(false);
    expect(PolicyActionExecutionStatusSchema.safeParse('HOLD').success).toBe(false);
  });

  it('validates all lifecycle statuses and decisions', () => {
    const lifecycles = [
      'DETECTED',
      'PENDING_REVIEW',
      'UNDER_REVIEW',
      'PENDING_FURTHER_REVIEW',
      'CONFIRMED',
      'DISMISSED',
      'CLOSED'
    ];
    for (const s of lifecycles) {
      expect(IncidentLifecycleStatusSchema.safeParse(s).success).toBe(true);
    }

    const decisions = ['NONE', 'INCONCLUSIVE', 'CONFIRMED', 'DISMISSED'];
    for (const d of decisions) {
      expect(IncidentDecisionSchema.safeParse(d).success).toBe(true);
    }
  });
});
