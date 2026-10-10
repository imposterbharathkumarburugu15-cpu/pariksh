import { registerPlugin } from '@capacitor/core';

export interface MonotonicTimeResponse {
  elapsedRealtimeMs: number;
  currentWallMs: number;
}

export interface SaveResponseLocalResult {
  responseId: string;
  revisionSequence: number;
  savedAt: string;
  isDurablyPersisted: boolean;
}

export interface SavedCandidateResponseItem {
  responseId: string;
  questionId: string;
  responsePayload: string;
  revisionSequence: number;
  savedAt: string;
}

export interface LoadAttemptSnapshotResult {
  attemptId: string;
  responses: SavedCandidateResponseItem[];
}

export interface PendingSyncEvent {
  eventId: string;
  aggregateId: string;
  eventType: string;
  payloadJson: string;
  createdAt: string;
  deliveryStatus?: string;
  retryCount?: number;
  lastError?: string | null;
}

export interface LockdownCapabilities {
  deviceMode: 'MANAGED_DEVICE' | 'PERSONAL_PHONE';
  isDeviceOwner: boolean;
  isProfileOwner: boolean;
  isLockTaskPermitted: boolean;
  hasUsageAccess: boolean;
  hasCamera: boolean;
  cameraMonitoringStatus: string;
  lockdownSupport: 'FULL' | 'SCREEN_PINNING_ONLY_OR_UNSUPPORTED';
}

export interface LockdownStatus {
  lockTaskModeState: 'LOCKED' | 'PINNED' | 'NONE';
  isLockTaskActive: boolean;
  isManagedLockdown: boolean;
  isPinnedOnly: boolean;
}

export interface RecordedInterruptionResult {
  eventId: string;
  eventCategory: string;
  deviceReportedAt: string;
  appPackageName?: string;
  displayedAppName?: string;
  detectionMethod: string;
  confidenceLevel: string;
  isPersistedLocally: boolean;
}

export interface IntegrityMonitorStatusResult {
  recordedEventsCount: number;
  pendingSyncCount: number;
  monitoringActive: boolean;
}

export interface NativeIntegrityEventItem {
  eventId: string;
  examId: string;
  attemptId: string;
  studentId: string;
  eventCategory: string;
  deviceReportedAt: string;
  receivedAt?: string;
  sequenceNumber: number;
  sourceDeviceId: string;
  appPackageName?: string;
  displayedAppName?: string;
  payloadJson?: string;
  ingestionStatus: string;
}

interface ExamNativeBridgePlugin {
  getMonotonicTime(): Promise<MonotonicTimeResponse>;
  saveResponseLocal(options: {
    attemptId: string;
    questionId: string;
    responsePayload: string;
    revisionSequence: number;
  }): Promise<SaveResponseLocalResult>;
  loadAttemptSnapshotLocal(options: {
    attemptId: string;
  }): Promise<LoadAttemptSnapshotResult>;
  enqueueSyncEventLocal(options: {
    eventId: string;
    aggregateId: string;
    eventType: string;
    payloadJson: string;
  }): Promise<{ eventId: string; status: string }>;
  getPendingSyncEventsLocal(): Promise<{ pendingEvents: PendingSyncEvent[] }>;
  markSyncEventAcknowledgedLocal(options: {
    eventId: string;
  }): Promise<{ eventId: string; status: string }>;
  updateSyncEventStatusLocal(options: {
    eventId: string;
    status: string;
    lastError?: string;
    retryCount?: number;
  }): Promise<{ eventId: string; status: string }>;
  // Step 8 methods
  getLockdownCapabilities(): Promise<LockdownCapabilities>;
  getCurrentLockdownStatus(): Promise<LockdownStatus>;
  checkDeviceManagementStatus(): Promise<LockdownCapabilities>;
  startExamLockdown(): Promise<{
    success: boolean;
    lockTaskModeState: string;
    isManagedLockdown: boolean;
    isPinnedOnly: boolean;
    isPermittedByDpm?: boolean;
    error?: string;
    message?: string;
  }>;
  stopExamLockdown(): Promise<{
    success: boolean;
    lockTaskModeState: string;
    error?: string;
    message?: string;
  }>;
  recordLifecycleInterruption(options: {
    attemptId: string;
    examId: string;
    studentId: string;
    eventCategory?: string;
    sequenceNumber?: number;
    sourceDeviceId?: string;
  }): Promise<RecordedInterruptionResult>;
  recordExamResume(options: {
    attemptId: string;
    examId: string;
    studentId: string;
    sequenceNumber?: number;
    sourceDeviceId?: string;
  }): Promise<{
    eventId: string;
    eventCategory: string;
    deviceReportedAt: string;
    isPersistedLocally: boolean;
  }>;
  getIntegrityMonitorStatus(options?: {
    attemptId?: string;
  }): Promise<IntegrityMonitorStatusResult>;
  getUsageAccessStatus(): Promise<{ hasUsageAccess: boolean }>;
  getRecordedIntegrityEvents(options: {
    attemptId: string;
  }): Promise<{ attemptId: string; events: NativeIntegrityEventItem[] }>;
}

const NativeBridge = registerPlugin<ExamNativeBridgePlugin>('ExamNativeBridge');

// Fallback in-memory SQLite emulator for browser preview & Vitest unit tests
class WebFallbackStorage {
  private responses = new Map<string, Map<string, SavedCandidateResponseItem>>();
  private outbox = new Map<string, PendingSyncEvent & { status: string }>();
  private integrityEvents: NativeIntegrityEventItem[] = [];
  private lockTaskActive = false;
  private isManaged = false;

  clear(): void {
    this.responses.clear();
    this.outbox.clear();
    this.integrityEvents = [];
    this.lockTaskActive = false;
    this.isManaged = false;
  }

  getMonotonicTime(): MonotonicTimeResponse {
    const elapsed = Math.round(performance.now());
    return {
      elapsedRealtimeMs: elapsed,
      currentWallMs: Date.now()
    };
  }

  saveResponse(
    attemptId: string,
    questionId: string,
    payload: string,
    seq: number
  ): SaveResponseLocalResult {
    let attemptMap = this.responses.get(attemptId);
    if (!attemptMap) {
      attemptMap = new Map();
      this.responses.set(attemptId, attemptMap);
    }
    const item: SavedCandidateResponseItem = {
      responseId: `resp_web_${Date.now()}_${questionId.slice(0, 8)}`,
      questionId,
      responsePayload: payload,
      revisionSequence: seq,
      savedAt: new Date().toISOString()
    };
    attemptMap.set(questionId, item);
    return {
      responseId: item.responseId,
      revisionSequence: seq,
      savedAt: item.savedAt,
      isDurablyPersisted: true
    };
  }

  loadAttemptSnapshot(attemptId: string): LoadAttemptSnapshotResult {
    const attemptMap = this.responses.get(attemptId);
    if (!attemptMap) {
      return { attemptId, responses: [] };
    }
    return {
      attemptId,
      responses: Array.from(attemptMap.values())
    };
  }

  enqueueSyncEvent(eventId: string, aggId: string, type: string, payload: string): void {
    if (!this.outbox.has(eventId)) {
      this.outbox.set(eventId, {
        eventId,
        aggregateId: aggId,
        eventType: type,
        payloadJson: payload,
        createdAt: new Date().toISOString(),
        status: 'PENDING'
      });
    }
  }

  getPendingSyncEvents(): PendingSyncEvent[] {
    const list: PendingSyncEvent[] = [];
    for (const v of this.outbox.values()) {
      if (v.status === 'PENDING' || v.status === 'RETRY_PENDING') {
        list.push({
          eventId: v.eventId,
          aggregateId: v.aggregateId,
          eventType: v.eventType,
          payloadJson: v.payloadJson,
          createdAt: v.createdAt,
          deliveryStatus: v.status,
          retryCount: (v as any).retryCount || 0,
          lastError: (v as any).lastError || null
        });
      }
    }
    return list;
  }

  markSyncEventAcknowledged(eventId: string): void {
    const item = this.outbox.get(eventId);
    if (item) {
      item.status = 'ACKNOWLEDGED';
    }
  }

  updateSyncEventStatus(eventId: string, status: string, error?: string, retryCount = 0): void {
    const item = this.outbox.get(eventId);
    if (item) {
      item.status = status;
      (item as any).deliveryStatus = status;
      (item as any).lastError = error || null;
      (item as any).retryCount = retryCount;
    }
  }

  // Step 8 Web Emulations
  getLockdownCapabilities(): LockdownCapabilities {
    return {
      deviceMode: this.isManaged ? 'MANAGED_DEVICE' : 'PERSONAL_PHONE',
      isDeviceOwner: this.isManaged,
      isProfileOwner: false,
      isLockTaskPermitted: this.isManaged,
      hasUsageAccess: false,
      hasCamera: true,
      cameraMonitoringStatus: 'NOT_AVAILABLE',
      lockdownSupport: this.isManaged ? 'FULL' : 'SCREEN_PINNING_ONLY_OR_UNSUPPORTED'
    };
  }

  setManagedMode(managed: boolean): void {
    this.isManaged = managed;
  }

  getCurrentLockdownStatus(): LockdownStatus {
    return {
      lockTaskModeState: this.lockTaskActive ? (this.isManaged ? 'LOCKED' : 'PINNED') : 'NONE',
      isLockTaskActive: this.lockTaskActive,
      isManagedLockdown: this.lockTaskActive && this.isManaged,
      isPinnedOnly: this.lockTaskActive && !this.isManaged
    };
  }

  startExamLockdown() {
    this.lockTaskActive = true;
    return {
      success: true,
      lockTaskModeState: this.isManaged ? 'LOCKED' : 'PINNED',
      isManagedLockdown: this.isManaged,
      isPinnedOnly: !this.isManaged,
      isPermittedByDpm: this.isManaged
    };
  }

  stopExamLockdown() {
    this.lockTaskActive = false;
    return {
      success: true,
      lockTaskModeState: 'NONE'
    };
  }

  recordLifecycleInterruption(
    attemptId: string,
    examId: string,
    studentId: string,
    category = 'EXAM_APP_BACKGROUND',
    seq = 1,
    sourceDeviceId = 'device_local'
  ): RecordedInterruptionResult {
    const nowIso = new Date().toISOString();
    const eventId = `integ_web_${Date.now()}`;
    const item: NativeIntegrityEventItem = {
      eventId,
      examId,
      attemptId,
      studentId,
      eventCategory: category,
      deviceReportedAt: nowIso,
      sequenceNumber: seq,
      sourceDeviceId,
      displayedAppName: 'APP_NAME_UNAVAILABLE',
      ingestionStatus: 'PENDING'
    };
    this.integrityEvents.push(item);
    this.enqueueSyncEvent(eventId, attemptId, 'INTEGRITY_EVENT', JSON.stringify(item));
    return {
      eventId,
      eventCategory: category,
      deviceReportedAt: nowIso,
      displayedAppName: 'APP_NAME_UNAVAILABLE',
      detectionMethod: 'APP_LIFECYCLE',
      confidenceLevel: 'LOW',
      isPersistedLocally: true
    };
  }

  recordExamResume(
    attemptId: string,
    examId: string,
    studentId: string,
    seq = 1,
    sourceDeviceId = 'device_local'
  ) {
    const nowIso = new Date().toISOString();
    const eventId = `integ_res_web_${Date.now()}`;
    const item: NativeIntegrityEventItem = {
      eventId,
      examId,
      attemptId,
      studentId,
      eventCategory: 'EXAM_APP_RESUMED',
      deviceReportedAt: nowIso,
      sequenceNumber: seq,
      sourceDeviceId,
      ingestionStatus: 'PENDING'
    };
    this.integrityEvents.push(item);
    this.enqueueSyncEvent(eventId, attemptId, 'INTEGRITY_EVENT', JSON.stringify(item));
    return {
      eventId,
      eventCategory: 'EXAM_APP_RESUMED',
      deviceReportedAt: nowIso,
      isPersistedLocally: true
    };
  }

  getIntegrityMonitorStatus(attemptId?: string): IntegrityMonitorStatusResult {
    const events = attemptId
      ? this.integrityEvents.filter((e) => e.attemptId === attemptId)
      : this.integrityEvents;
    const pending = this.getPendingSyncEvents().filter((e) => e.eventType === 'INTEGRITY_EVENT');
    return {
      recordedEventsCount: events.length,
      pendingSyncCount: pending.length,
      monitoringActive: true
    };
  }

  getRecordedIntegrityEvents(attemptId: string): NativeIntegrityEventItem[] {
    return this.integrityEvents.filter((e) => e.attemptId === attemptId);
  }
}

export const webStorageInstance = new WebFallbackStorage();

export const ExamStorage = {
  async getMonotonicTime(): Promise<MonotonicTimeResponse> {
    try {
      return await NativeBridge.getMonotonicTime();
    } catch {
      return webStorageInstance.getMonotonicTime();
    }
  },

  async saveResponseLocal(
    attemptId: string,
    questionId: string,
    responsePayload: string,
    revisionSequence: number
  ): Promise<SaveResponseLocalResult> {
    try {
      return await NativeBridge.saveResponseLocal({
        attemptId,
        questionId,
        responsePayload,
        revisionSequence
      });
    } catch {
      return webStorageInstance.saveResponse(
        attemptId,
        questionId,
        responsePayload,
        revisionSequence
      );
    }
  },

  async loadAttemptSnapshotLocal(attemptId: string): Promise<LoadAttemptSnapshotResult> {
    try {
      return await NativeBridge.loadAttemptSnapshotLocal({ attemptId });
    } catch {
      return webStorageInstance.loadAttemptSnapshot(attemptId);
    }
  },

  async enqueueSyncEventLocal(
    eventId: string,
    aggregateId: string,
    eventType: string,
    payloadJson: string
  ): Promise<void> {
    try {
      await NativeBridge.enqueueSyncEventLocal({
        eventId,
        aggregateId,
        eventType,
        payloadJson
      });
    } catch {
      webStorageInstance.enqueueSyncEvent(eventId, aggregateId, eventType, payloadJson);
    }
  },

  async getPendingSyncEventsLocal(): Promise<PendingSyncEvent[]> {
    try {
      const res = await NativeBridge.getPendingSyncEventsLocal();
      return res.pendingEvents;
    } catch {
      return webStorageInstance.getPendingSyncEvents();
    }
  },

  async markSyncEventAcknowledgedLocal(eventId: string): Promise<void> {
    try {
      await NativeBridge.markSyncEventAcknowledgedLocal({ eventId });
    } catch {
      webStorageInstance.markSyncEventAcknowledged(eventId);
    }
  },

  async updateSyncEventStatusLocal(
    eventId: string,
    status: string,
    lastError?: string,
    retryCount = 0
  ): Promise<void> {
    try {
      await NativeBridge.updateSyncEventStatusLocal({
        eventId,
        status,
        lastError,
        retryCount
      });
    } catch {
      webStorageInstance.updateSyncEventStatus(eventId, status, lastError, retryCount);
    }
  },

  // Step 8 methods
  async getLockdownCapabilities(): Promise<LockdownCapabilities> {
    try {
      return await NativeBridge.getLockdownCapabilities();
    } catch {
      return webStorageInstance.getLockdownCapabilities();
    }
  },

  async getCurrentLockdownStatus(): Promise<LockdownStatus> {
    try {
      return await NativeBridge.getCurrentLockdownStatus();
    } catch {
      return webStorageInstance.getCurrentLockdownStatus();
    }
  },

  async checkDeviceManagementStatus(): Promise<LockdownCapabilities> {
    try {
      return await NativeBridge.checkDeviceManagementStatus();
    } catch {
      return webStorageInstance.getLockdownCapabilities();
    }
  },

  async startExamLockdown(): Promise<{
    success: boolean;
    lockTaskModeState: string;
    isManagedLockdown: boolean;
    isPinnedOnly: boolean;
    isPermittedByDpm?: boolean;
    error?: string;
    message?: string;
  }> {
    try {
      return await NativeBridge.startExamLockdown();
    } catch {
      return webStorageInstance.startExamLockdown();
    }
  },

  async stopExamLockdown(): Promise<{
    success: boolean;
    lockTaskModeState: string;
    error?: string;
    message?: string;
  }> {
    try {
      return await NativeBridge.stopExamLockdown();
    } catch {
      return webStorageInstance.stopExamLockdown();
    }
  },

  async recordLifecycleInterruption(
    attemptId: string,
    examId: string,
    studentId: string,
    eventCategory?: string,
    sequenceNumber?: number,
    sourceDeviceId?: string
  ): Promise<RecordedInterruptionResult> {
    try {
      return await NativeBridge.recordLifecycleInterruption({
        attemptId,
        examId,
        studentId,
        eventCategory,
        sequenceNumber,
        sourceDeviceId
      });
    } catch {
      return webStorageInstance.recordLifecycleInterruption(
        attemptId,
        examId,
        studentId,
        eventCategory,
        sequenceNumber,
        sourceDeviceId
      );
    }
  },

  async recordExamResume(
    attemptId: string,
    examId: string,
    studentId: string,
    sequenceNumber?: number,
    sourceDeviceId?: string
  ): Promise<{
    eventId: string;
    eventCategory: string;
    deviceReportedAt: string;
    isPersistedLocally: boolean;
  }> {
    try {
      return await NativeBridge.recordExamResume({
        attemptId,
        examId,
        studentId,
        sequenceNumber,
        sourceDeviceId
      });
    } catch {
      return webStorageInstance.recordExamResume(
        attemptId,
        examId,
        studentId,
        sequenceNumber,
        sourceDeviceId
      );
    }
  },

  async getIntegrityMonitorStatus(attemptId?: string): Promise<IntegrityMonitorStatusResult> {
    try {
      return await NativeBridge.getIntegrityMonitorStatus({ attemptId });
    } catch {
      return webStorageInstance.getIntegrityMonitorStatus(attemptId);
    }
  },

  async getUsageAccessStatus(): Promise<{ hasUsageAccess: boolean }> {
    try {
      return await NativeBridge.getUsageAccessStatus();
    } catch {
      return { hasUsageAccess: false };
    }
  },

  async getRecordedIntegrityEvents(attemptId: string): Promise<NativeIntegrityEventItem[]> {
    try {
      const res = await NativeBridge.getRecordedIntegrityEvents({ attemptId });
      return res.events;
    } catch {
      return webStorageInstance.getRecordedIntegrityEvents(attemptId);
    }
  }
};

export const ExamStorageBridge = ExamStorage;
