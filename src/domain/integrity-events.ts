import { z } from 'zod';
import { EntityIdSchema, IsoTimestampSchema } from './common';

/**
 * Permitted categories of examination integrity telemetry events
 */
export const IntegrityEventCategorySchema = z.enum([
  'UNAUTHORISED_APP_SWITCH',
  'WINDOW_BLUR',
  'APP_BACKGROUND',
  'NETWORK_DISCONNECT',
  'NETWORK_RECONNECT',
  'EXAM_RESUMED',
  'SUBMISSION_ATTEMPT',
  'PACKAGE_VALIDATION_FAILURE'
]);

export type IntegrityEventCategory = z.infer<typeof IntegrityEventCategorySchema>;

/**
 * Telemetry payload content for integrity events
 */
export const IntegrityPayloadSchema = z.record(z.string(), z.unknown());

export type IntegrityPayload = z.infer<typeof IntegrityPayloadSchema>;

/**
 * Integrity Event Record:
 * Captures on-device security signals.
 *
 * NOTE: An observed event (such as UNAUTHORISED_APP_SWITCH) is a telemetry signal
 * and is NOT automatically proof of misconduct.
 * Application identity fields (appPackageName, displayedAppName) are explicitly optional
 * because standard Android sandbox security does not guarantee detection of foreground target apps.
 */
export const IntegrityEventSchema = z.object({
  eventId: EntityIdSchema,
  examId: EntityIdSchema,
  attemptId: EntityIdSchema,
  studentId: EntityIdSchema,
  eventCategory: IntegrityEventCategorySchema,
  deviceTimestamp: IsoTimestampSchema,
  sequenceNumber: z.number().int().nonnegative(),
  eventPayload: IntegrityPayloadSchema.default({}),
  sourceDeviceId: EntityIdSchema,
  appPackageName: z.string().trim().min(1).max(256).optional(),
  displayedAppName: z.string().trim().min(1).max(256).optional(),
  payloadHash: z.string().min(16).max(128).optional(),
  serverReceivedTimestamp: IsoTimestampSchema.optional()
});

export type IntegrityEvent = z.infer<typeof IntegrityEventSchema>;
