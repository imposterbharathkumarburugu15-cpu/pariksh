import { z } from 'zod';
import { EntityIdSchema, IsoTimestampSchema } from './common';

/**
 * Permitted categories of examination integrity telemetry events
 */
export const IntegrityEventCategorySchema = z.enum([
  'EXAM_APP_BACKGROUND',
  'EXAM_APP_RESUMED',
  'LOCK_TASK_ENTERED',
  'LOCK_TASK_EXITED',
  'PROHIBITED_APP_FOREGROUND',
  'APP_NAME_UNAVAILABLE',
  'USAGE_ACCESS_REVOKED',
  'NETWORK_DISCONNECTED',
  'NETWORK_RECONNECTED',
  'EXAM_PACKAGE_VALIDATION_FAILED',
  'CLOCK_ANOMALY',
  'ATTEMPT_RESUMED',
  'UNEXPECTED_EXAM_RESTART',
  'MONITORING_CAPABILITY_LOST',
  // Backward compatibility categories
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
 * Objective evidence confidence classification
 */
export const DetectionConfidenceSchema = z.enum([
  'HIGH',
  'MEDIUM',
  'LOW',
  'INFERRED',
  'AMBIGUOUS'
]);

export type DetectionConfidence = z.infer<typeof DetectionConfidenceSchema>;

/**
 * Observable detection method
 */
export const DetectionMethodSchema = z.enum([
  'DEVICE_POLICY_LOCK_TASK',
  'APP_LIFECYCLE',
  'USAGE_STATS',
  'SYSTEM_BROADCAST',
  'HEARTBEAT_LOSS',
  'MANUAL_OBSERVATION'
]);

export type DetectionMethod = z.infer<typeof DetectionMethodSchema>;

/**
 * Device Operating Mode
 */
export const DeviceOperatingModeSchema = z.enum([
  'MANAGED_DEVICE',
  'PERSONAL_PHONE',
  'ANY'
]);

export type DeviceOperatingMode = z.infer<typeof DeviceOperatingModeSchema>;

/**
 * Pre-exam readiness checklist item status
 */
export const ReadinessStatusSchema = z.enum([
  'READY',
  'LIMITED',
  'NOT_AVAILABLE',
  'BLOCKED'
]);

export type ReadinessStatus = z.infer<typeof ReadinessStatusSchema>;

/**
 * Telemetry payload content for integrity events
 */
export const IntegrityPayloadSchema = z.record(z.string(), z.unknown());

export type IntegrityPayload = z.infer<typeof IntegrityPayloadSchema>;

/**
 * Integrity Event Record:
 * Captures on-device security signals.
 *
 * NOTE: An observed event (such as EXAM_APP_BACKGROUND) is a telemetry signal
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
  detectionMethod: DetectionMethodSchema.default('APP_LIFECYCLE'),
  confidenceLevel: DetectionConfidenceSchema.default('MEDIUM'),
  payloadHash: z.string().min(16).max(128).optional(),
  serverReceivedTimestamp: IsoTimestampSchema.optional()
});

export type IntegrityEvent = z.infer<typeof IntegrityEventSchema>;

/**
 * Versioned Examination Integrity Policy
 */
export const IntegrityPolicySchema = z.object({
  policyId: EntityIdSchema,
  examId: EntityIdSchema,
  policyVersion: z.string().min(1).default('v1.0'),
  requiredDeviceMode: DeviceOperatingModeSchema.default('ANY'),
  lockdownRequired: z.boolean().default(false),
  appIdentityMonitoringEnabled: z.boolean().default(false),
  prohibitedPackages: z.array(z.string()).default([]),
  prohibitedAppAction: z.enum([
    'LOG_ONLY',
    'WARN_STUDENT',
    'ALERT_EXAMINER',
    'FLAG_FOR_REVIEW',
    'HOLD_RESULT',
    'CANCEL_ATTEMPT'
  ]).default('ALERT_EXAMINER'),
  backgroundInterruptionAction: z.enum([
    'LOG_ONLY',
    'WARN_STUDENT',
    'ALERT_EXAMINER',
    'FLAG_FOR_REVIEW'
  ]).default('WARN_STUDENT'),
  maxAllowedBackgroundCount: z.number().int().positive().default(3),
  autoCancellationEnabled: z.boolean().default(false),
  requiresSecondConfirmation: z.boolean().default(false),
  ambiguousEventsAction: z.enum([
    'FLAG_FOR_REVIEW',
    'WARN_STUDENT'
  ]).default('FLAG_FOR_REVIEW')
});

export type IntegrityPolicy = z.infer<typeof IntegrityPolicySchema>;

/**
 * Real-time Examiner Alert Schema
 */
export const IntegrityAlertSchema = z.object({
  alertId: EntityIdSchema,
  incidentId: EntityIdSchema.optional(),
  examId: EntityIdSchema,
  examTitle: z.string(),
  attemptId: EntityIdSchema,
  studentId: EntityIdSchema,
  studentDisplayName: z.string().optional(),
  eventCategory: IntegrityEventCategorySchema,
  deviceTimestamp: IsoTimestampSchema,
  receivedAt: IsoTimestampSchema,
  appPackageName: z.string().optional(),
  displayedAppName: z.string().optional(),
  detectionSource: DetectionMethodSchema,
  confidenceLevel: DetectionConfidenceSchema,
  policyAction: z.string(),
  actionStatus: z.string(),
  alertStatus: z.enum([
    'NEW',
    'ACKNOWLEDGED',
    'ACTION_APPLIED',
    'ACTION_FAILED',
    'AWAITING_REVIEW',
    'RESOLVED'
  ]),
  attemptStatus: z.string()
});

export type IntegrityAlert = z.infer<typeof IntegrityAlertSchema>;
