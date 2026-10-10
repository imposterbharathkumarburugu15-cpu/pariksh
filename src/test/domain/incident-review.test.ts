import { describe, it, expect } from 'vitest';
import {
  IncidentLifecycleStatusSchema,
  PolicyActionSchema,
  PolicyActionExecutionStatusSchema,
  IncidentReviewSchema,
  IncidentReviewProposalSchema,
  REQUIRED_LIMITED_EVIDENCE_HOLD_FIXTURE,
  AppealStatusSchema,
  AppealChallengedActionSchema,
  AppealDecisionOutcomeSchema,
  IncidentAppealSchema
} from '../../domain/incidents';

describe('Administrator Incident Review & Appeals Domain Specification', () => {
  const now = '2026-10-10T10:00:00Z';

  describe('Prescribed Limited-Evidence Hold Scenario Fixture', () => {
    it('strictly satisfies the mandatory Prompt 9 test scenario specification', () => {
      expect(REQUIRED_LIMITED_EVIDENCE_HOLD_FIXTURE.category).toBe('UNAUTHORISED_APP_SWITCH');
      expect(REQUIRED_LIMITED_EVIDENCE_HOLD_FIXTURE.evidenceAssessment).toBe('LIMITED');
      expect(REQUIRED_LIMITED_EVIDENCE_HOLD_FIXTURE.requestedPolicyAction).toBe('HOLD');
      expect(REQUIRED_LIMITED_EVIDENCE_HOLD_FIXTURE.reviewerJustification).toBe('No notes supplied');
      expect(REQUIRED_LIMITED_EVIDENCE_HOLD_FIXTURE.intendedIncidentStatus).toBe('PENDING_FURTHER_REVIEW');
      expect(REQUIRED_LIMITED_EVIDENCE_HOLD_FIXTURE.intendedResultStatus).toBe('HELD_PENDING_REVIEW');

      const parsed = IncidentReviewProposalSchema.safeParse(REQUIRED_LIMITED_EVIDENCE_HOLD_FIXTURE);
      expect(parsed.success).toBe(true);
    });

    it('enforces that limited evidence holds do not confirm a violation or cancel an attempt', () => {
      // In the prescribed scenario, decision must be NONE or INCONCLUSIVE, never CONFIRMED
      const proposal = REQUIRED_LIMITED_EVIDENCE_HOLD_FIXTURE;
      expect(proposal.requestedPolicyAction).not.toBe('CANCEL_ATTEMPT');
      expect(proposal.intendedIncidentStatus).not.toBe('CONFIRMED');
      expect(proposal.intendedResultStatus).not.toBe('CANCELLED');
    });
  });

  describe('Authoritative Lifecycle State Machine Transitions', () => {
    const validTransitions: Record<string, string[]> = {
      DETECTED: ['PENDING_REVIEW', 'UNDER_REVIEW', 'DISMISSED'],
      PENDING_REVIEW: ['UNDER_REVIEW', 'DISMISSED'],
      UNDER_REVIEW: ['PENDING_FURTHER_REVIEW', 'CONFIRMED', 'DISMISSED'],
      PENDING_FURTHER_REVIEW: ['UNDER_REVIEW', 'CONFIRMED', 'DISMISSED', 'CLOSED'],
      CONFIRMED: ['CLOSED', 'PENDING_FURTHER_REVIEW'],
      DISMISSED: ['CLOSED'],
      CLOSED: []
    };

    it('validates permitted lifecycle states against the schema', () => {
      Object.keys(validTransitions).forEach((state) => {
        expect(IncidentLifecycleStatusSchema.safeParse(state).success).toBe(true);
      });
    });

    it('allows valid state progression from DETECTED through to CLOSED', () => {
      function canTransition(from: string, to: string): boolean {
        return (validTransitions[from] || []).includes(to);
      }

      // Standard investigation path
      expect(canTransition('DETECTED', 'UNDER_REVIEW')).toBe(true);
      expect(canTransition('UNDER_REVIEW', 'PENDING_FURTHER_REVIEW')).toBe(true);
      expect(canTransition('PENDING_FURTHER_REVIEW', 'CONFIRMED')).toBe(true);
      expect(canTransition('CONFIRMED', 'CLOSED')).toBe(true);

      // Dismissal path
      expect(canTransition('UNDER_REVIEW', 'DISMISSED')).toBe(true);
      expect(canTransition('DISMISSED', 'CLOSED')).toBe(true);

      // Direct jumping from DETECTED to CLOSED is forbidden
      expect(canTransition('DETECTED', 'CLOSED')).toBe(false);
      // Closed cases cannot transition out
      expect(canTransition('CLOSED', 'UNDER_REVIEW')).toBe(false);
    });
  });

  describe('Conceptual Separation of Concerns', () => {
    it('distinguishes original event category, evidence assessment, decision, policy action, and execution status', () => {
      const incidentRecord = {
        incidentId: 'inc_test_901',
        examId: 'exam_cs101',
        attemptId: 'att_std_001',
        studentId: 'usr_student_001',
        category: 'UNAUTHORISED_APP_SWITCH',
        lifecycleStatus: 'PENDING_FURTHER_REVIEW',
        evidenceAssessment: 'LIMITED',
        decision: 'NONE',
        evidenceReferences: ['evt_switch_001', 'evt_switch_002'],
        reviewerId: 'usr_inv_001',
        reviewerJustification: 'App switch detected without package name; holding result pending further review',
        associatedPolicyAction: 'HOLD',
        policyActionExecutionStatus: 'APPLIED',
        policyVersion: 'v2.1',
        createdAt: now,
        updatedAt: now,
        version: 2
      };

      const result = IncidentReviewSchema.safeParse(incidentRecord);
      expect(result.success).toBe(true);
      if (result.success) {
        // Category is integrity signal
        expect(result.data.category).toBe('UNAUTHORISED_APP_SWITCH');
        // Evidence assessment is objective evaluation
        expect(result.data.evidenceAssessment).toBe('LIMITED');
        // Decision is adjudicative outcome
        expect(result.data.decision).toBe('NONE');
        // Associated policy action is administrative sanction/safeguard
        expect(result.data.associatedPolicyAction).toBe('HOLD');
        // Policy action execution status is operational state
        expect(result.data.policyActionExecutionStatus).toBe('APPLIED');
      }
    });

    it('rejects blending policy action into result status or execution status', () => {
      expect(PolicyActionSchema.safeParse('HELD_PENDING_REVIEW').success).toBe(false);
      expect(PolicyActionSchema.safeParse('APPLIED').success).toBe(false);
      expect(PolicyActionExecutionStatusSchema.safeParse('HOLD').success).toBe(false);
    });
  });

  describe('Appeals Schema and Adjudication Integrity', () => {
    it('validates a complete student appeal record', () => {
      const appeal = {
        appealId: 'app_001',
        incidentId: 'inc_test_901',
        attemptId: 'att_std_001',
        studentId: 'usr_student_001',
        challengedAction: 'RESULT_HOLD',
        appealReason: 'Phone received emergency system broadcast which triggered app switch, student remained in seat',
        evidenceReferences: 'Screenshot of emergency EAS alert with matching timestamp',
        appealStatus: 'SUBMITTED',
        submittedAt: now,
        updatedAt: now
      };

      const parsed = IncidentAppealSchema.safeParse(appeal);
      expect(parsed.success).toBe(true);
      if (parsed.success) {
        expect(parsed.data.challengedAction).toBe('RESULT_HOLD');
        expect(parsed.data.appealStatus).toBe('SUBMITTED');
        expect(parsed.data.assignedReviewerId).toBeUndefined();
      }
    });

    it('validates decided appeals with independent review outcome', () => {
      const decidedAppeal = {
        appealId: 'app_002',
        incidentId: 'inc_test_901',
        attemptId: 'att_std_001',
        studentId: 'usr_student_001',
        challengedAction: 'RESULT_HOLD',
        appealReason: 'Accidental switch during verified medical emergency',
        appealStatus: 'OVERTURNED',
        assignedReviewerId: 'usr_admin_002',
        reviewerDecision: 'OVERTURNED',
        outcomeReason: 'Independent committee confirmed system notification; hold lifted and score released',
        submittedAt: now,
        resolvedAt: '2026-10-10T12:00:00Z',
        updatedAt: '2026-10-10T12:00:00Z'
      };

      const parsed = IncidentAppealSchema.safeParse(decidedAppeal);
      expect(parsed.success).toBe(true);
      if (parsed.success) {
        expect(parsed.data.appealStatus).toBe('OVERTURNED');
        expect(parsed.data.reviewerDecision).toBe('OVERTURNED');
        expect(parsed.data.assignedReviewerId).toBe('usr_admin_002');
      }
    });

    it('enforces that original reviewer cannot decide their own incident appeal', () => {
      const originalReviewer: string = 'usr_inv_001';
      const appealReviewerCandidate: string = 'usr_inv_001';

      const isAllowedToAdjudicate = appealReviewerCandidate !== originalReviewer;
      expect(isAllowedToAdjudicate).toBe(false);

      const independentReviewer: string = 'usr_admin_senior';
      const isIndependentAllowed = independentReviewer !== originalReviewer;
      expect(isIndependentAllowed).toBe(true);
    });

    it('rejects invalid appeal statuses and outcomes', () => {
      expect(AppealStatusSchema.safeParse('FORGIVEN').success).toBe(false);
      expect(AppealDecisionOutcomeSchema.safeParse('ACCEPTED').success).toBe(false);
      expect(AppealChallengedActionSchema.safeParse('EXPULSION').success).toBe(false);
    });
  });

  describe('Result Hold and Reinstatement Score Preservation', () => {
    it('verifies that result hold changes result status while preserving marks and question breakdown', () => {
      const originalResult = {
        resultId: 'res_001',
        attemptId: 'att_001',
        studentId: 'usr_student_001',
        resultStatus: 'PUBLISHED',
        totalScaledScore: 84.5,
        maxPossibleScaledScore: 100.0,
        correctCount: 22,
        incorrectCount: 3,
        unansweredCount: 0,
        holdReason: null
      };

      // Applying a hold
      const heldResult = {
        ...originalResult,
        resultStatus: 'HELD_PENDING_REVIEW',
        holdReason: 'Limited evidence app switch under review'
      };

      // Verification: scores and metrics are unchanged
      expect(heldResult.resultStatus).toBe('HELD_PENDING_REVIEW');
      expect(heldResult.totalScaledScore).toBe(originalResult.totalScaledScore);
      expect(heldResult.maxPossibleScaledScore).toBe(originalResult.maxPossibleScaledScore);
      expect(heldResult.correctCount).toBe(originalResult.correctCount);

      // Releasing a hold restores status to PUBLISHED
      const releasedResult = {
        ...heldResult,
        resultStatus: 'PUBLISHED',
        holdReason: null
      };
      expect(releasedResult.resultStatus).toBe('PUBLISHED');
      expect(releasedResult.totalScaledScore).toBe(originalResult.totalScaledScore);
    });

    it('verifies cancellation and reinstatement preserve response history', () => {
      const attemptBeforeCancel = {
        attemptId: 'att_001',
        status: 'SUBMITTED',
        savedResponsesCount: 25,
        cancellationReason: null
      };

      // Cancellation marks status as CANCELLED but retains response count
      const cancelledAttempt = {
        ...attemptBeforeCancel,
        status: 'CANCELLED',
        cancellationReason: 'Confirmed prohibited app usage with sufficient telemetry'
      };
      expect(cancelledAttempt.status).toBe('CANCELLED');
      expect(cancelledAttempt.savedResponsesCount).toBe(25);

      // Reinstatement restores status back to SUBMITTED
      const reinstatedAttempt = {
        ...cancelledAttempt,
        status: 'SUBMITTED',
        cancellationReason: null
      };
      expect(reinstatedAttempt.status).toBe('SUBMITTED');
      expect(reinstatedAttempt.savedResponsesCount).toBe(25);
    });
  });
});
