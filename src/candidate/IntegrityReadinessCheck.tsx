import React, { useEffect, useState } from 'react';
import {
  ExamStorage,
  LockdownCapabilities,
  LockdownStatus
} from './nativeBridge';
import { CandidateExamPackage, ReadinessStatus } from '../domain';

interface ReadinessItem {
  id: string;
  label: string;
  status: ReadinessStatus;
  description: string;
}

interface IntegrityReadinessCheckProps {
  pkg: CandidateExamPackage;
  onReadyToStart: () => void;
  onCancel: () => void;
  serverOnline: boolean;
}

export const IntegrityReadinessCheck: React.FC<IntegrityReadinessCheckProps> = ({
  pkg,
  onReadyToStart,
  onCancel,
  serverOnline
}) => {
  const [capabilities, setCapabilities] = useState<LockdownCapabilities | null>(null);
  const [lockdownStatus, setLockdownStatus] = useState<LockdownStatus | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let active = true;
    const inspectDevice = async () => {
      try {
        const caps = await ExamStorage.getLockdownCapabilities();
        const status = await ExamStorage.getCurrentLockdownStatus();
        if (active) {
          setCapabilities(caps);
          setLockdownStatus(status);
          setLoading(false);
        }
      } catch {
        if (active) {
          setLoading(false);
        }
      }
    };
    inspectDevice();
    return () => {
      active = false;
    };
  }, []);

  const isManaged = capabilities?.deviceMode === 'MANAGED_DEVICE';
  const isLockTaskPermitted = capabilities?.isLockTaskPermitted ?? false;
  const hasUsageAccess = capabilities?.hasUsageAccess ?? false;

  const items: ReadinessItem[] = [
    {
      id: 'device_mode',
      label: 'Device Operating Mode',
      status: isManaged ? 'READY' : 'LIMITED',
      description: isManaged
        ? 'Institution-managed Android device configured for dedicated examination use.'
        : 'Personal phone mode (BYOD). Standard Android sandbox restrictions apply.'
    },
    {
      id: 'lockdown_support',
      label: 'Kiosk Lockdown Support (Lock Task)',
      status: isLockTaskPermitted ? 'READY' : 'LIMITED',
      description: isLockTaskPermitted
        ? lockdownStatus?.isManagedLockdown
          ? 'Managed Lock Task Mode is confirmed and ready.'
          : 'DevicePolicyManager allows non-exitable Lock Task Mode.'
        : 'Device owner provisioning not detected. Strict hardware kiosk locking is unavailable.'
    },
    {
      id: 'app_identity',
      label: 'External App Identity Detection',
      status: hasUsageAccess ? 'READY' : 'LIMITED',
      description: hasUsageAccess
        ? 'Usage access granted. Foreground app switches can be identified via UsageEvents.'
        : 'Usage access not granted. Target app names will be recorded as APP_NAME_UNAVAILABLE.'
    },
    {
      id: 'integrity_monitoring',
      label: 'Lifecycle Interruption Monitoring',
      status: 'READY',
      description: 'App backgrounding, window blur, and local network changes will be recorded.'
    },
    {
      id: 'examiner_network',
      label: 'Examiner Local Network Connection',
      status: serverOnline ? 'READY' : 'BLOCKED',
      description: serverOnline
        ? 'Connected to local examination server.'
        : 'Examiner host unreachable. Reconnect to the examination Wi-Fi before starting.'
    },
    {
      id: 'storage_engine',
      label: 'Durable Local Room SQLite Storage',
      status: 'READY',
      description: 'Answers will be saved locally on-device before transmission.'
    },
    {
      id: 'deadline_anchor',
      label: 'Monotonic Elapsed-Time Anchor',
      status: 'READY',
      description: 'Official countdown anchored to native elapsed real-time to prevent clock tampering.'
    },
    {
      id: 'camera_monitoring',
      label: 'Camera / Biometric Monitoring',
      status: 'NOT_AVAILABLE',
      description: 'Offline face model is not bundled. Biometric monitoring is honestly disabled.'
    }
  ];

  const hasBlocked = items.some((i) => i.status === 'BLOCKED');

  const getStatusBadge = (status: ReadinessStatus) => {
    switch (status) {
      case 'READY':
        return <span className="readiness-badge badge-ready">READY</span>;
      case 'LIMITED':
        return <span className="readiness-badge badge-limited">LIMITED</span>;
      case 'NOT_AVAILABLE':
        return <span className="readiness-badge badge-not-avail">NOT AVAILABLE</span>;
      case 'BLOCKED':
        return <span className="readiness-badge badge-blocked">BLOCKED</span>;
    }
  };

  return (
    <div className="readiness-check-container">
      <div className="readiness-check-card">
        <div className="readiness-header">
          <h2>🛡️ Pre-Examination Integrity Readiness Check</h2>
          <p className="readiness-subtitle">
            Verifying system security, device mode, and monitoring capabilities for:
          </p>
          <div className="readiness-exam-title">{pkg.title}</div>
        </div>

        {loading ? (
          <div className="readiness-loading">Inspecting device capabilities...</div>
        ) : (
          <div className="readiness-list">
            {items.map((item) => (
              <div key={item.id} className={`readiness-row status-${item.status.toLowerCase()}`}>
                <div className="readiness-item-main">
                  <div className="readiness-item-title">{item.label}</div>
                  <div className="readiness-item-desc">{item.description}</div>
                </div>
                <div className="readiness-item-badge">{getStatusBadge(item.status)}</div>
              </div>
            ))}
          </div>
        )}

        <div className="readiness-notice">
          <strong>Notice on Personal Phone Mode:</strong>
          <p>
            On personal devices, PARIKSH records when the examination app enters the background or loses focus.
            Leaving the exam window is immediately reported to the examiner as a telemetry event.
            Return to the exam promptly to prevent automatic escalation or attempt cancellation.
          </p>
        </div>

        <div className="readiness-actions">
          <button type="button" className="btn-secondary" onClick={onCancel}>
            Return to Dashboard
          </button>
          <button
            type="button"
            className="btn-primary"
            disabled={hasBlocked || loading}
            onClick={onReadyToStart}
          >
            {hasBlocked ? 'Cannot Start (Resolve Blockers)' : 'Acknowledge & Begin Exam'}
          </button>
        </div>
      </div>
    </div>
  );
};
