import { describe, it, expect } from 'vitest';
import {
  AuditEventSchema,
  AuditOperationResultSchema
} from '../../domain/audit';

describe('Audit Event Schemas', () => {
  const timestamp = '2026-10-09T11:05:00Z';

  it('validates a successful administrative audit event record', () => {
    const successfulEvent = {
      auditEventId: 'aud_evt_101',
      incidentId: 'inc_001',
      examId: 'exam_101',
      attemptId: 'att_001',
      actorId: 'usr_admin_01',
      actorRole: 'EXAMINATION_ADMIN',
      actionType: 'APPLY_RESULT_HOLD',
      previousState: { resultStatus: 'PENDING_GRADING' },
      newState: { resultStatus: 'HELD_PENDING_REVIEW' },
      eventTimestamp: timestamp,
      serverReceivedTimestamp: timestamp,
      reason: 'Hold applied pending manual proctor log review',
      evidenceReferences: ['evt_001'],
      policyVersion: 'v1.0',
      idempotencyKey: 'idem_hold_att_001_v1',
      operationResult: 'SUCCESS',
      previousAuditHash: '00000000000000000000000000000000',
      currentAuditHash: '9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08'
    };

    const parsed = AuditEventSchema.safeParse(successfulEvent);
    expect(parsed.success).toBe(true);
    if (parsed.success) {
      expect(parsed.data.operationResult).toBe('SUCCESS');
      expect(parsed.data.actionType).toBe('APPLY_RESULT_HOLD');
    }
  });

  it('records rejected administrative actions with explicit failure reasons', () => {
    const rejectedEvent = {
      auditEventId: 'aud_evt_102',
      incidentId: 'inc_001',
      actorId: 'usr_invigilator_01',
      actorRole: 'INVIGILATOR',
      actionType: 'CANCEL_ATTEMPT_UNAUTHORIZED',
      eventTimestamp: timestamp,
      reason: 'Invigilator requested immediate attempt cancellation without authority',
      operationResult: 'REJECTED',
      failureReason: 'INSUFFICIENT_PERMISSIONS: Invigilator role cannot execute CANCEL_ATTEMPT'
    };

    const parsed = AuditEventSchema.safeParse(rejectedEvent);
    expect(parsed.success).toBe(true);
    if (parsed.success) {
      expect(parsed.data.operationResult).toBe('REJECTED');
      expect(parsed.data.failureReason).toContain('INSUFFICIENT_PERMISSIONS');
    }
  });

  it('validates operation result enumerations', () => {
    expect(AuditOperationResultSchema.safeParse('SUCCESS').success).toBe(true);
    expect(AuditOperationResultSchema.safeParse('REJECTED').success).toBe(true);
    expect(AuditOperationResultSchema.safeParse('FAILED').success).toBe(true);
    expect(AuditOperationResultSchema.safeParse('PENDING').success).toBe(false);
  });

  it('rejects audit records missing actorId or actorRole', () => {
    const incompleteAudit = {
      auditEventId: 'aud_evt_103',
      // Missing actorId
      actorRole: 'EXAMINATION_ADMIN',
      actionType: 'VIEW_INCIDENT',
      eventTimestamp: timestamp,
      operationResult: 'SUCCESS'
    };

    expect(AuditEventSchema.safeParse(incompleteAudit).success).toBe(false);
  });
});
