import { describe, it, expect } from 'vitest';
import {
  IntegrityEventSchema,
  IntegrityEventCategorySchema
} from '../../domain/integrity-events';

describe('Integrity Event Schemas', () => {
  const timestamp = '2026-10-09T10:30:00Z';

  it('validates a complete integrity event with optional app details', () => {
    const event = {
      eventId: 'evt_001',
      examId: 'exam_101',
      attemptId: 'att_001',
      studentId: 'std_001',
      eventCategory: 'UNAUTHORISED_APP_SWITCH',
      deviceTimestamp: timestamp,
      sequenceNumber: 14,
      eventPayload: { backgroundDurationMs: 4500 },
      sourceDeviceId: 'tablet_std_01',
      appPackageName: 'com.whatsapp',
      displayedAppName: 'WhatsApp',
      payloadHash: 'a1b2c3d4e5f6789012345678',
      serverReceivedTimestamp: '2026-10-09T10:30:02Z'
    };

    const parsed = IntegrityEventSchema.safeParse(event);
    expect(parsed.success).toBe(true);
  });

  it('permits missing app package name and displayed app name when unavailable', () => {
    // Under standard Android sandbox, the foreground app identity may be inaccessible
    const eventWithoutAppName = {
      eventId: 'evt_002',
      examId: 'exam_101',
      attemptId: 'att_001',
      studentId: 'std_001',
      eventCategory: 'UNAUTHORISED_APP_SWITCH',
      deviceTimestamp: timestamp,
      sequenceNumber: 15,
      sourceDeviceId: 'tablet_std_01'
    };

    const parsed = IntegrityEventSchema.safeParse(eventWithoutAppName);
    expect(parsed.success).toBe(true);
    if (parsed.success) {
      expect(parsed.data.appPackageName).toBeUndefined();
      expect(parsed.data.displayedAppName).toBeUndefined();
    }
  });

  it('rejects unknown or invalid integrity event categories', () => {
    const invalidCategoryEvent = {
      eventId: 'evt_003',
      examId: 'exam_101',
      attemptId: 'att_001',
      studentId: 'std_001',
      eventCategory: 'SUSPICIOUS_FACIAL_LOOKAWAY', // Not in prescribed offline schema
      deviceTimestamp: timestamp,
      sequenceNumber: 16,
      sourceDeviceId: 'tablet_std_01'
    };

    expect(IntegrityEventSchema.safeParse(invalidCategoryEvent).success).toBe(false);
  });

  it('validates all required event categories', () => {
    const requiredCategories = [
      'UNAUTHORISED_APP_SWITCH',
      'WINDOW_BLUR',
      'APP_BACKGROUND',
      'NETWORK_DISCONNECT',
      'NETWORK_RECONNECT',
      'EXAM_RESUMED',
      'SUBMISSION_ATTEMPT',
      'PACKAGE_VALIDATION_FAILURE'
    ];

    for (const cat of requiredCategories) {
      expect(IntegrityEventCategorySchema.safeParse(cat).success).toBe(true);
    }
  });

  it('rejects event records missing required identifiers', () => {
    const missingIdentifiers = {
      eventId: '',
      examId: 'exam_101',
      // Missing attemptId
      studentId: 'std_001',
      eventCategory: 'WINDOW_BLUR',
      deviceTimestamp: timestamp,
      sequenceNumber: 1,
      sourceDeviceId: 'dev_01'
    };

    expect(IntegrityEventSchema.safeParse(missingIdentifiers).success).toBe(false);
  });
});
