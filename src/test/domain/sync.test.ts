import { describe, it, expect } from 'vitest';
import {
  SyncEventTypeSchema,
  SyncOutboxStatusSchema,
  ServerProcessingStatusSchema,
  SyncEventEnvelopeSchema,
  SyncBatchRequestSchema,
  SyncBatchResponseSchema,
  SyncReconcileRequestSchema,
  SyncReconcileResponseSchema,
  SyncObservabilityOverviewSchema,
  computeSha256Web
} from '../../domain/sync';

describe('Step 11: Offline Synchronisation & Reliability Domain Model', () => {
  it('validates supported sync event types', () => {
    expect(SyncEventTypeSchema.parse('ANSWER_UPDATE')).toBe('ANSWER_UPDATE');
    expect(SyncEventTypeSchema.parse('FINAL_SUBMISSION')).toBe('FINAL_SUBMISSION');
    expect(SyncEventTypeSchema.parse('INTEGRITY_EVENT')).toBe('INTEGRITY_EVENT');
    expect(SyncEventTypeSchema.parse('HEARTBEAT')).toBe('HEARTBEAT');

    expect(() => SyncEventTypeSchema.parse('INVALID_EVENT_TYPE')).toThrow();
  });

  it('validates distinct outbox lifecycle states', () => {
    const states = [
      'PENDING',
      'IN_FLIGHT',
      'ACKNOWLEDGED',
      'RETRY_PENDING',
      'CONFLICT',
      'REJECTED',
      'FAILED_PERMANENTLY'
    ];
    for (const s of states) {
      expect(SyncOutboxStatusSchema.parse(s)).toBe(s);
    }

    expect(() => SyncOutboxStatusSchema.parse('UNKNOWN_STATE')).toThrow();
  });

  it('validates authoritative server receipt processing statuses', () => {
    const statuses = [
      'ACCEPTED',
      'ALREADY_PROCESSED',
      'CONFLICT',
      'REJECTED',
      'NEEDS_REVIEW',
      'LATE_ACCEPTED',
      'LATE_REJECTED'
    ];
    for (const st of statuses) {
      expect(ServerProcessingStatusSchema.parse(st)).toBe(st);
    }
  });

  it('validates a correct SyncEventEnvelope', () => {
    const validEnvelope = {
      eventId: 'evt_101',
      schemaVersion: 1,
      eventType: 'ANSWER_UPDATE',
      instituteId: 'inst_kota_01',
      examId: 'exam_neet_2026',
      attemptId: 'att_std_01',
      studentId: 'std_01',
      sourceDeviceId: 'phone_samsung_a52',
      sequenceNumber: 1,
      aggregateId: 'att_std_01',
      aggregateVersion: 1,
      baseServerVersion: 0,
      clientWallClockTimestamp: '2026-10-10T10:00:00Z',
      clientMonotonicTimestamp: 12345678,
      payload: JSON.stringify({ questionId: 'q_01', responsePayload: 'B', revisionSequence: 1 }),
      payloadHash: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
      idempotencyKey: 'idem_key_01',
      deliveryAttempt: 1
    };

    const parsed = SyncEventEnvelopeSchema.parse(validEnvelope);
    expect(parsed.eventId).toBe('evt_101');
    expect(parsed.schemaVersion).toBe(1);
    expect(parsed.eventType).toBe('ANSWER_UPDATE');
    expect(parsed.payloadHash).toHaveLength(64);
  });

  it('rejects an envelope with an invalid payload hash format', () => {
    const invalidHashEnvelope = {
      eventId: 'evt_102',
      schemaVersion: 1,
      eventType: 'ANSWER_UPDATE',
      instituteId: 'inst_01',
      examId: 'exam_01',
      attemptId: 'att_01',
      studentId: 'std_01',
      sourceDeviceId: 'dev_01',
      sequenceNumber: 1,
      aggregateId: 'att_01',
      aggregateVersion: 1,
      clientWallClockTimestamp: '2026-10-10T10:00:00Z',
      payload: '{}',
      payloadHash: 'not_a_valid_sha256_hash',
      deliveryAttempt: 1
    };

    expect(() => SyncEventEnvelopeSchema.parse(invalidHashEnvelope)).toThrow(/Payload hash must be a 64-character SHA-256 hex string/);
  });

  it('enforces maximum batch limit of 50 events', () => {
    const validItem = {
      eventId: 'evt_x',
      schemaVersion: 1,
      eventType: 'HEARTBEAT' as const,
      instituteId: 'inst_01',
      examId: 'exam_01',
      attemptId: 'att_01',
      studentId: 'std_01',
      sourceDeviceId: 'dev_01',
      sequenceNumber: 1,
      aggregateId: 'att_01',
      aggregateVersion: 1,
      clientWallClockTimestamp: '2026-10-10T10:00:00Z',
      payload: '{}',
      payloadHash: '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
      deliveryAttempt: 1
    };

    const batch50 = Array.from({ length: 50 }, (_, i) => ({ ...validItem, eventId: `evt_${i}` }));
    expect(SyncBatchRequestSchema.parse({ events: batch50 }).events).toHaveLength(50);

    const batch51 = Array.from({ length: 51 }, (_, i) => ({ ...validItem, eventId: `evt_${i}` }));
    expect(() => SyncBatchRequestSchema.parse({ events: batch51 })).toThrow();
  });

  it('validates a batch response with per-event outcomes', () => {
    const batchResponse = {
      status: 'PARTIAL_SUCCESS_WITH_CONFLICTS',
      totalReceived: 3,
      acceptedCount: 1,
      alreadyProcessedCount: 1,
      rejectedCount: 0,
      conflictCount: 1,
      results: [
        {
          eventId: 'evt_1',
          status: 'ACCEPTED',
          receiptId: 'rcpt_1',
          resultingVersion: 2,
          serverReceivedAt: '2026-10-10T10:05:00Z'
        },
        {
          eventId: 'evt_2',
          status: 'ALREADY_PROCESSED',
          receiptId: 'rcpt_2',
          resultingVersion: 1,
          serverReceivedAt: '2026-10-10T10:04:00Z'
        },
        {
          eventId: 'evt_3',
          status: 'CONFLICT',
          rejectionReason: 'STALE_REVISION: Revision 1 is older than accepted revision 2',
          resultingVersion: 2,
          serverReceivedAt: '2026-10-10T10:05:01Z'
        }
      ]
    };

    const parsed = SyncBatchResponseSchema.parse(batchResponse);
    expect(parsed.totalReceived).toBe(3);
    expect(parsed.results[0]?.status).toBe('ACCEPTED');
    expect(parsed.results[1]?.status).toBe('ALREADY_PROCESSED');
    expect(parsed.results[2]?.status).toBe('CONFLICT');
  });

  it('validates a Reconcile request and response correctly', () => {
    const reqParsed = SyncReconcileRequestSchema.parse({ attemptId: 'att_01' });
    expect(reqParsed.attemptId).toBe('att_01');

    const reconcileResponse = {
      attemptId: 'att_01',
      examId: 'exam_01',
      studentId: 'std_01',
      attemptStatus: 'IN_PROGRESS',
      resultStatus: 'PENDING_GRADING',
      officialStartTime: '2026-10-10T10:00:00Z',
      officialDeadline: '2026-10-10T13:00:00Z',
      durationSeconds: 10800,
      deadlineRemainingSeconds: 5400,
      isDeadlineExpired: false,
      totalQuestionsAnswered: 45,
      lastAcceptedRevisions: {
        q_01: 2,
        q_02: 1,
        q_03: 3
      },
      serverTimestamp: '2026-10-10T11:30:00Z'
    };

    const parsed = SyncReconcileResponseSchema.parse(reconcileResponse);
    expect(parsed.attemptStatus).toBe('IN_PROGRESS');
    expect(parsed.lastAcceptedRevisions['q_01']).toBe(2);
    expect(parsed.isDeadlineExpired).toBe(false);
  });

  it('validates SyncObservabilityOverview for examination monitoring', () => {
    const overview = {
      totalReceipts: 150,
      acceptedCount: 140,
      alreadyProcessedCount: 5,
      rejectedCount: 2,
      conflictCount: 3,
      lateAcceptedCount: 4,
      lateRejectedCount: 1,
      activeAttemptsCount: 30,
      attempts: [
        {
          attemptId: 'att_01',
          studentId: 'std_01',
          attemptStatus: 'IN_PROGRESS',
          totalEvents: 5,
          acceptedEvents: 5,
          conflicts: 0,
          lastEventAt: '2026-10-10T11:00:00Z'
        }
      ],
      serverTimestamp: '2026-10-10T11:05:00Z'
    };

    const parsed = SyncObservabilityOverviewSchema.parse(overview);
    expect(parsed.totalReceipts).toBe(150);
    expect(parsed.activeAttemptsCount).toBe(30);
    expect(parsed.attempts[0]?.acceptedEvents).toBe(5);
  });

  it('computes deterministic SHA-256 hash using computeSha256Web', async () => {
    const payload = JSON.stringify({ questionId: 'q_01', response: 'A' });
    const hash = await computeSha256Web(payload);
    expect(hash).toHaveLength(64);
    expect(hash).toMatch(/^[0-9a-f]{64}$/);

    const hash2 = await computeSha256Web(payload);
    expect(hash).toBe(hash2);
  });
});
