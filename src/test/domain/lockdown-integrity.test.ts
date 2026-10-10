import { describe, it, expect, beforeEach } from 'vitest';
import {
  IntegrityEventCategorySchema,
  IntegrityEventSchema,
  IntegrityPolicySchema,
  IntegrityAlertSchema
} from '../../domain';
import { webStorageInstance, ExamStorage } from '../../candidate/nativeBridge';

describe('DOOMEDAMS Step 8: Examination Lockdown & Integrity Monitoring', () => {
  beforeEach(() => {
    webStorageInstance.clear();
  });

  describe('1. Operating Modes & Capability Distinctions', () => {
    it('correctly reports personal phone mode by default', async () => {
      webStorageInstance.setManagedMode(false);
      const caps = await ExamStorage.getLockdownCapabilities();

      expect(caps.deviceMode).toBe('PERSONAL_PHONE');
      expect(caps.isDeviceOwner).toBe(false);
      expect(caps.isLockTaskPermitted).toBe(false);
      expect(caps.lockdownSupport).toBe('SCREEN_PINNING_ONLY_OR_UNSUPPORTED');
      expect(caps.cameraMonitoringStatus).toBe('NOT_AVAILABLE');
    });

    it('reports managed device mode when device policy permits', async () => {
      webStorageInstance.setManagedMode(true);
      const caps = await ExamStorage.getLockdownCapabilities();

      expect(caps.deviceMode).toBe('MANAGED_DEVICE');
      expect(caps.isDeviceOwner).toBe(true);
      expect(caps.isLockTaskPermitted).toBe(true);
      expect(caps.lockdownSupport).toBe('FULL');
    });

    it('distinguishes user-exitable screen pinning from managed lock task', async () => {
      // Unmanaged mode: enters PINNED state
      webStorageInstance.setManagedMode(false);
      const resUnmanaged = await ExamStorage.startExamLockdown();
      expect(resUnmanaged.success).toBe(true);
      expect(resUnmanaged.lockTaskModeState).toBe('PINNED');
      expect(resUnmanaged.isPinnedOnly).toBe(true);
      expect(resUnmanaged.isManagedLockdown).toBe(false);

      await ExamStorage.stopExamLockdown();

      // Managed mode: enters LOCKED state
      webStorageInstance.setManagedMode(true);
      const resManaged = await ExamStorage.startExamLockdown();
      expect(resManaged.success).toBe(true);
      expect(resManaged.lockTaskModeState).toBe('LOCKED');
      expect(resManaged.isManagedLockdown).toBe(true);
      expect(resManaged.isPinnedOnly).toBe(false);
    });
  });

  describe('2. Telemetry Classification & App Identity Integrity', () => {
    it('validates all required Step 8 integrity event categories', () => {
      const categories = [
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
        'MONITORING_CAPABILITY_LOST'
      ];

      for (const cat of categories) {
        expect(() => IntegrityEventCategorySchema.parse(cat)).not.toThrow();
      }
    });

    it('records APP_NAME_UNAVAILABLE when operating system does not expose external package', async () => {
      const res = await ExamStorage.recordLifecycleInterruption(
        'att-test-01',
        'exam-test-01',
        'usr-stu-01',
        'EXAM_APP_BACKGROUND',
        1
      );

      expect(res.isPersistedLocally).toBe(true);
      expect(res.eventCategory).toBe('EXAM_APP_BACKGROUND');
      expect(res.displayedAppName).toBe('APP_NAME_UNAVAILABLE');
      expect(res.confidenceLevel).toBe('LOW');
      expect(res.detectionMethod).toBe('APP_LIFECYCLE');
    });

    it('validates structured integrity event schema with separate timestamps', () => {
      const rawEvent = {
        eventId: 'integ_evt_001',
        examId: 'exam_bio_101',
        attemptId: 'att_bio_101_01',
        studentId: 'usr_student_001',
        eventCategory: 'PROHIBITED_APP_FOREGROUND',
        deviceTimestamp: '2026-10-10T10:00:00.000Z',
        sequenceNumber: 2,
        eventPayload: { targetWindow: 'external' },
        sourceDeviceId: 'dev_pixel_7',
        appPackageName: 'com.android.chrome',
        displayedAppName: 'Google Chrome',
        detectionMethod: 'USAGE_STATS',
        confidenceLevel: 'HIGH',
        serverReceivedTimestamp: '2026-10-10T10:00:01.200Z'
      };

      const parsed = IntegrityEventSchema.parse(rawEvent);
      expect(parsed.appPackageName).toBe('com.android.chrome');
      expect(parsed.confidenceLevel).toBe('HIGH');
      expect(parsed.serverReceivedTimestamp).not.toBe(parsed.deviceTimestamp);
    });
  });

  describe('3. Versioned Examination Integrity Policy', () => {
    it('enforces valid policy structure with distinct actions', () => {
      const policy = {
        policyId: 'pol_exam_001',
        examId: 'exam_001',
        policyVersion: 'v2.0',
        requiredDeviceMode: 'ANY',
        lockdownRequired: false,
        appIdentityMonitoringEnabled: true,
        prohibitedPackages: ['com.android.chrome', 'com.google.android.googlequicksearchbox'],
        prohibitedAppAction: 'CANCEL_ATTEMPT',
        backgroundInterruptionAction: 'WARN_STUDENT',
        maxAllowedBackgroundCount: 3,
        autoCancellationEnabled: true,
        requiresSecondConfirmation: false,
        ambiguousEventsAction: 'FLAG_FOR_REVIEW'
      };

      const parsed = IntegrityPolicySchema.parse(policy);
      expect(parsed.autoCancellationEnabled).toBe(true);
      expect(parsed.prohibitedAppAction).toBe('CANCEL_ATTEMPT');
      expect(parsed.backgroundInterruptionAction).toBe('WARN_STUDENT');
    });
  });

  describe('4. Examiner Alerts & Local Offline Delivery', () => {
    it('validates examiner alert schema with proper status enum', () => {
      const alert = {
        alertId: 'alert_integ_001',
        incidentId: 'inc_001',
        examId: 'exam_001',
        examTitle: 'Mathematics Final 2026',
        attemptId: 'att_001',
        studentId: 'usr_student_001',
        studentDisplayName: 'Aarav Sharma',
        eventCategory: 'LOCK_TASK_EXITED',
        deviceTimestamp: '2026-10-10T10:15:00.000Z',
        receivedAt: '2026-10-10T10:15:00.500Z',
        appPackageName: 'com.android.settings',
        displayedAppName: 'Settings',
        detectionSource: 'DEVICE_POLICY_LOCK_TASK',
        confidenceLevel: 'HIGH',
        policyAction: 'CANCEL_ATTEMPT',
        actionStatus: 'APPLIED',
        alertStatus: 'NEW',
        attemptStatus: 'CANCELLED'
      };

      const parsed = IntegrityAlertSchema.parse(alert);
      expect(parsed.alertStatus).toBe('NEW');
      expect(parsed.attemptStatus).toBe('CANCELLED');
    });

    it('queues integrity events in outbox when offline and tracks pending sync count', async () => {
      await ExamStorage.recordLifecycleInterruption(
        'att-offline-01',
        'exam-001',
        'usr-stu-01',
        'EXAM_APP_BACKGROUND',
        1
      );

      const status = await ExamStorage.getIntegrityMonitorStatus('att-offline-01');
      expect(status.recordedEventsCount).toBe(1);
      expect(status.pendingSyncCount).toBe(1);
      expect(status.monitoringActive).toBe(true);

      const pendingEvents = await ExamStorage.getPendingSyncEventsLocal();
      const integrityPending = pendingEvents.filter((e) => e.eventType === 'INTEGRITY_EVENT');
      expect(integrityPending).toHaveLength(1);

      await ExamStorage.markSyncEventAcknowledgedLocal(integrityPending[0]!.eventId);
      const afterAck = await ExamStorage.getPendingSyncEventsLocal();
      const integrityAfterAck = afterAck.filter((e) => e.eventType === 'INTEGRITY_EVENT');
      expect(integrityAfterAck).toHaveLength(0);
    });
  });
});
