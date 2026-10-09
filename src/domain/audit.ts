import { z } from 'zod';
import { EntityIdSchema, IsoTimestampSchema } from './common';
import { UserRoleSchema } from './users';

/**
 * Outcome of an attempted administrative or system operation
 */
export const AuditOperationResultSchema = z.enum([
  'SUCCESS',
  'REJECTED',
  'FAILED'
]);

export type AuditOperationResult = z.infer<typeof AuditOperationResultSchema>;

/**
 * Audit Event Record Contract:
 * Captures discrete state changes, administrative actions, and policy interventions.
 *
 * NOTE: Cryptographic chaining, tamper-evident hash validation, and append-only
 * database guarantees will be enforced in Step 3.
 */
export const AuditEventSchema = z.object({
  auditEventId: EntityIdSchema,
  incidentId: EntityIdSchema.optional(),
  examId: EntityIdSchema.optional(),
  attemptId: EntityIdSchema.optional(),
  actorId: EntityIdSchema,
  actorRole: UserRoleSchema,
  actionType: z.string().min(1).max(100),
  previousState: z.record(z.string(), z.unknown()).optional(),
  newState: z.record(z.string(), z.unknown()).optional(),
  eventTimestamp: IsoTimestampSchema,
  serverReceivedTimestamp: IsoTimestampSchema.optional(),
  reason: z.string().optional(),
  evidenceReferences: z.array(EntityIdSchema).default([]),
  policyVersion: z.string().min(1).optional(),
  idempotencyKey: z.string().min(1).max(128).optional(),
  operationResult: AuditOperationResultSchema,
  failureReason: z.string().optional(),
  previousAuditHash: z.string().min(16).max(128).optional(),
  currentAuditHash: z.string().min(16).max(128).optional()
});

export type AuditEvent = z.infer<typeof AuditEventSchema>;
