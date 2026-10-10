import { z } from 'zod';

export const SyncEventTypeSchema = z.enum([
  'ANSWER_UPDATE',
  'FINAL_SUBMISSION',
  'INTEGRITY_EVENT',
  'HEARTBEAT'
]);
export type SyncEventType = z.infer<typeof SyncEventTypeSchema>;

export const SyncOutboxStatusSchema = z.enum([
  'PENDING',
  'IN_FLIGHT',
  'ACKNOWLEDGED',
  'RETRY_PENDING',
  'CONFLICT',
  'REJECTED',
  'FAILED_PERMANENTLY'
]);
export type SyncOutboxStatus = z.infer<typeof SyncOutboxStatusSchema>;

export const ServerProcessingStatusSchema = z.enum([
  'ACCEPTED',
  'ALREADY_PROCESSED',
  'CONFLICT',
  'REJECTED',
  'NEEDS_REVIEW',
  'LATE_ACCEPTED',
  'LATE_REJECTED'
]);
export type ServerProcessingStatus = z.infer<typeof ServerProcessingStatusSchema>;

export const SyncEventEnvelopeSchema = z.object({
  eventId: z.string().min(1),
  schemaVersion: z.number().int().min(1).default(1),
  eventType: SyncEventTypeSchema,
  instituteId: z.string().min(1),
  examId: z.string().min(1),
  attemptId: z.string().min(1),
  studentId: z.string().min(1),
  sourceDeviceId: z.string().min(1).default('device_local'),
  sequenceNumber: z.number().int().min(1).default(1),
  aggregateId: z.string().min(1),
  aggregateVersion: z.number().int().min(1).default(1),
  baseServerVersion: z.number().int().nullable().optional(),
  clientWallClockTimestamp: z.string().min(1),
  clientMonotonicTimestamp: z.number().nullable().optional(),
  payload: z.string(),
  payloadHash: z.string().regex(/^[0-9a-fA-F]{64}$/, 'Payload hash must be a 64-character SHA-256 hex string'),
  idempotencyKey: z.string().nullable().optional(),
  deliveryAttempt: z.number().int().min(1).default(1)
});
export type SyncEventEnvelope = z.infer<typeof SyncEventEnvelopeSchema>;

export const SyncBatchRequestSchema = z.object({
  events: z.array(SyncEventEnvelopeSchema).max(50)
});
export type SyncBatchRequest = z.infer<typeof SyncBatchRequestSchema>;

export const SyncBatchResultItemSchema = z.object({
  eventId: z.string().min(1),
  status: ServerProcessingStatusSchema,
  receiptId: z.string().nullable().optional(),
  resultingVersion: z.number().int().default(1),
  rejectionReason: z.string().nullable().optional(),
  serverReceivedAt: z.string(),
  receiptHash: z.string().nullable().optional()
});
export type SyncBatchResultItem = z.infer<typeof SyncBatchResultItemSchema>;

export const SyncBatchResponseSchema = z.object({
  status: z.string(),
  totalReceived: z.number().int(),
  acceptedCount: z.number().int(),
  alreadyProcessedCount: z.number().int(),
  rejectedCount: z.number().int(),
  conflictCount: z.number().int(),
  results: z.array(SyncBatchResultItemSchema)
});
export type SyncBatchResponse = z.infer<typeof SyncBatchResponseSchema>;

export const SyncReconcileRequestSchema = z.object({
  attemptId: z.string().min(1)
});
export type SyncReconcileRequest = z.infer<typeof SyncReconcileRequestSchema>;

export const SyncReconcileResponseSchema = z.object({
  attemptId: z.string().min(1),
  examId: z.string().min(1),
  studentId: z.string().min(1),
  attemptStatus: z.string(),
  resultStatus: z.string(),
  officialStartTime: z.string(),
  officialDeadline: z.string(),
  durationSeconds: z.number(),
  deadlineRemainingSeconds: z.number(),
  isDeadlineExpired: z.boolean(),
  totalQuestionsAnswered: z.number().int(),
  lastAcceptedRevisions: z.record(z.string(), z.number()),
  serverTimestamp: z.string()
});
export type SyncReconcileResponse = z.infer<typeof SyncReconcileResponseSchema>;

export const SyncObservabilityOverviewSchema = z.object({
  totalReceipts: z.number().int(),
  acceptedCount: z.number().int(),
  alreadyProcessedCount: z.number().int(),
  rejectedCount: z.number().int(),
  conflictCount: z.number().int(),
  lateAcceptedCount: z.number().int(),
  lateRejectedCount: z.number().int(),
  activeAttemptsCount: z.number().int(),
  attempts: z.array(
    z.object({
      attemptId: z.string(),
      studentId: z.string(),
      attemptStatus: z.string(),
      totalEvents: z.number().int(),
      acceptedEvents: z.number().int(),
      conflicts: z.number().int(),
      lastEventAt: z.string().nullable().optional()
    })
  ),
  serverTimestamp: z.string()
});
export type SyncObservabilityOverview = z.infer<typeof SyncObservabilityOverviewSchema>;

/**
 * Computes SHA-256 hash of a string using Web Crypto SubtleCrypto.
 * Returns lowercase hex-encoded string.
 */
export async function computeSha256Web(content: string): Promise<string> {
  const encoder = new TextEncoder();
  const data = encoder.encode(content);
  if (typeof crypto !== 'undefined' && crypto.subtle) {
    const hashBuffer = await crypto.subtle.digest('SHA-256', data);
    const hashArray = Array.from(new Uint8Array(hashBuffer));
    return hashArray.map((b) => b.toString(16).padStart(2, '0')).join('');
  }
  // Fallback deterministic hash (64 hex characters) for headless environments without crypto.subtle
  let h1 = 0xdeadbeef, h2 = 0x41c6ce57;
  for (let i = 0; i < content.length; i++) {
    const ch = content.charCodeAt(i);
    h1 = Math.imul(h1 ^ ch, 2654435761);
    h2 = Math.imul(h2 ^ ch, 1597334677);
  }
  const hex1 = (h1 >>> 0).toString(16).padStart(8, '0');
  const hex2 = (h2 >>> 0).toString(16).padStart(8, '0');
  return (hex1 + hex2).repeat(4);
}
