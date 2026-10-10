import React, { useEffect, useState, useMemo } from 'react';
import { IntegrityAlert } from '../domain';

interface ExaminerIntegrityMonitorProps {
  serverUrl: string;
  authToken: string;
  onClose?: () => void;
}

export const ExaminerIntegrityMonitor: React.FC<ExaminerIntegrityMonitorProps> = ({
  serverUrl,
  authToken,
  onClose
}) => {
  const [alerts, setAlerts] = useState<IntegrityAlert[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [hasLoadedOnce, setHasLoadedOnce] = useState(false);
  const [lastRefreshedAt, setLastRefreshedAt] = useState<Date | null>(null);
  const [severityFilter, setSeverityFilter] = useState<string>('ALL');
  const [statusFilter, setStatusFilter] = useState<string>('ALL');
  const [selectedAlert, setSelectedAlert] = useState<IntegrityAlert | null>(null);
  const [acknowledgingId, setAcknowledgingId] = useState<string | null>(null);

  const fetchAlerts = async () => {
    setLoading(true);
    try {
      const res = await fetch(`${serverUrl}/api/examiner/integrity/alerts`, {
        headers: {
          Authorization: `Bearer ${authToken}`
        }
      });
      if (!res.ok) {
        throw new Error(`HTTP ${res.status}: ${res.statusText || 'Endpoint unavailable'}`);
      }
      const data = await res.json();
      setAlerts(Array.isArray(data) ? data : []);
      setError(null);
      setHasLoadedOnce(true);
      setLastRefreshedAt(new Date());
    } catch (err: unknown) {
      const e = err as Error;
      setError(e?.message || 'Failed to connect to examiner integrity stream');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchAlerts();
    const interval = setInterval(fetchAlerts, 5000); // 5s local polling
    return () => clearInterval(interval);
  }, [serverUrl, authToken]);

  const handleAcknowledge = async (eventId: string) => {
    setAcknowledgingId(eventId);
    try {
      const res = await fetch(`${serverUrl}/api/examiner/integrity/alerts/${eventId}/acknowledge`, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${authToken}`
        }
      });
      if (res.ok) {
        setAlerts((prev) =>
          prev.map((a) =>
            a.alertId === `alert_${eventId}` || a.incidentId === eventId
              ? { ...a, alertStatus: 'ACKNOWLEDGED' }
              : a
          )
        );
        if (selectedAlert && (selectedAlert.incidentId === eventId || selectedAlert.alertId === `alert_${eventId}`)) {
          setSelectedAlert((prev) => (prev ? { ...prev, alertStatus: 'ACKNOWLEDGED' } : null));
        }
      }
    } catch {
      // Keep alert intact if network call fails
    } finally {
      setAcknowledgingId(null);
    }
  };

  // Summary Metrics calculated strictly from real data
  const summaryMetrics = useMemo(() => {
    const totalEvents = alerts.length;
    const requiringReview = alerts.filter(
      (a) => a.alertStatus === 'NEW' || a.alertStatus === 'AWAITING_REVIEW'
    ).length;
    const criticalEvents = alerts.filter(
      (a) => a.eventCategory === 'LOCK_TASK_EXITED' || a.eventCategory === 'PROHIBITED_APP_FOREGROUND'
    ).length;
    const distinctMonitoredAttempts = new Set(alerts.map((a) => a.attemptId).filter(Boolean)).size;

    return {
      totalEvents,
      requiringReview,
      criticalEvents,
      distinctMonitoredAttempts
    };
  }, [alerts]);

  const filteredAlerts = alerts.filter((a) => {
    if (statusFilter === 'PENDING_REVIEW') {
      return a.alertStatus === 'NEW' || a.alertStatus === 'AWAITING_REVIEW';
    }
    if (statusFilter !== 'ALL' && a.alertStatus !== statusFilter) return false;
    if (severityFilter !== 'ALL') {
      const isCritical = a.eventCategory === 'LOCK_TASK_EXITED' || a.eventCategory === 'PROHIBITED_APP_FOREGROUND';
      const isHigh = a.eventCategory === 'EXAM_APP_BACKGROUND';
      if (severityFilter === 'CRITICAL' && !isCritical) return false;
      if (severityFilter === 'HIGH' && !isHigh) return false;
      if (severityFilter === 'MEDIUM' && (isCritical || isHigh)) return false;
    }
    return true;
  });

  const getSeverityBadge = (category: string) => {
    if (category === 'LOCK_TASK_EXITED' || category === 'PROHIBITED_APP_FOREGROUND') {
      return <span className="severity-badge sev-critical">CRITICAL</span>;
    }
    if (category === 'EXAM_APP_BACKGROUND') {
      return <span className="severity-badge sev-high">HIGH</span>;
    }
    return <span className="severity-badge sev-medium">MEDIUM</span>;
  };

  const getStatusBadge = (status: string) => {
    switch (status) {
      case 'NEW':
      case 'AWAITING_REVIEW':
        return <span className="status-badge badge-new">PENDING REVIEW</span>;
      case 'ACKNOWLEDGED':
        return <span className="status-badge badge-ack">ACKNOWLEDGED</span>;
      case 'ACTION_APPLIED':
        return <span className="status-badge badge-applied">ACTION APPLIED</span>;
      case 'ACTION_FAILED':
        return <span className="status-badge sev-critical">ACTION FAILED</span>;
      case 'RESOLVED':
        return <span className="status-badge badge-ack">RESOLVED</span>;
      default:
        return <span className="status-badge badge-default">{status}</span>;
    }
  };

  // State 1: Completely disconnected and no previous data
  if (error && !hasLoadedOnce && alerts.length === 0) {
    return (
      <div className="integrity-monitor-container">
        <div className="monitor-header">
          <div className="monitor-title-group">
            <h2>PARIKSH Integrity Monitor</h2>
            <span className="live-pulse-badge offline">
              ● EXAMINER NODE DISCONNECTED
            </span>
          </div>
          <div className="monitor-header-actions">
            <button type="button" className="btn btn-secondary btn-sm" onClick={fetchAlerts} disabled={loading}>
              {loading ? 'Reconnecting...' : '↻ Retry Connection'}
            </button>
            {onClose && (
              <button type="button" className="btn btn-outline btn-sm" onClick={onClose}>
                Close Monitor
              </button>
            )}
          </div>
        </div>

        <div className="monitor-offline-card">
          <span className="monitor-offline-icon">📡</span>
          <h3>Examiner Telemetry Stream Unreachable</h3>
          <p>
            Could not reach the local examiner node at <code>{serverUrl}</code>.
            Ensure the examiner application server is running and reachable on this device or local network.
          </p>
          <div style={{ display: 'flex', gap: '0.75rem', justifyContent: 'center' }}>
            <button type="button" className="btn btn-primary" onClick={fetchAlerts} disabled={loading}>
              {loading ? 'Connecting...' : 'Retry Telemetry Connection'}
            </button>
            {onClose && (
              <button type="button" className="btn btn-secondary" onClick={onClose}>
                Return to Access Console
              </button>
            )}
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="integrity-monitor-container">
      {/* HEADER SECTION */}
      <div className="monitor-header">
        <div className="monitor-title-group">
          <h2>PARIKSH Real-Time Integrity Monitor</h2>
          <span className="live-pulse-badge">
            <span className="live-pulse-dot" /> LIVE LOCAL TELEMETRY
          </span>
          {lastRefreshedAt && (
            <span style={{ fontSize: '0.8rem', color: 'var(--color-text-muted)' }}>
              Updated {lastRefreshedAt.toLocaleTimeString()}
            </span>
          )}
        </div>
        <div className="monitor-header-actions">
          <button type="button" className="btn btn-secondary btn-sm" onClick={fetchAlerts} disabled={loading}>
            {loading ? 'Refreshing...' : '↻ Refresh Feed'}
          </button>
          {onClose && (
            <button type="button" className="btn btn-outline btn-sm" onClick={onClose}>
              Close Monitor
            </button>
          )}
        </div>
      </div>

      {/* ERROR BANNER (When previous data exists but background sync failed) */}
      {error && (
        <div className="monitor-error-banner">
          <div>
            <strong>Telemetry Sync Warning:</strong> {error} — Showing cached integrity events.
          </div>
          <button type="button" className="btn btn-outline btn-xs" onClick={fetchAlerts} disabled={loading}>
            Retry
          </button>
        </div>
      )}

      {/* SUMMARY KPI CARDS */}
      <div className="monitor-kpi-grid">
        <div className="monitor-kpi-card">
          <span className="monitor-kpi-label">Recorded Events</span>
          <span className="monitor-kpi-val">{summaryMetrics.totalEvents}</span>
          <span className="monitor-kpi-desc">Verified device integrity signals</span>
        </div>
        <div className="monitor-kpi-card">
          <span className="monitor-kpi-label">Requiring Review</span>
          <span className="monitor-kpi-val" style={{ color: summaryMetrics.requiringReview > 0 ? '#fbbf24' : '#34d399' }}>
            {summaryMetrics.requiringReview}
          </span>
          <span className="monitor-kpi-desc">Pending examiner acknowledgement</span>
        </div>
        <div className="monitor-kpi-card">
          <span className="monitor-kpi-label">Critical Violations</span>
          <span className="monitor-kpi-val" style={{ color: summaryMetrics.criticalEvents > 0 ? '#f87171' : '#38bdf8' }}>
            {summaryMetrics.criticalEvents}
          </span>
          <span className="monitor-kpi-desc">Lock-task exit or prohibited foreground</span>
        </div>
        <div className="monitor-kpi-card">
          <span className="monitor-kpi-label">Flagged Attempts</span>
          <span className="monitor-kpi-val">{summaryMetrics.distinctMonitoredAttempts}</span>
          <span className="monitor-kpi-desc">Unique examination sessions</span>
        </div>
      </div>

      {/* FILTERS TOOLBAR */}
      <div className="monitor-controls">
        <div className="control-group">
          <label>Status Filter:</label>
          <select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)}>
            <option value="ALL">All Statuses ({alerts.length})</option>
            <option value="PENDING_REVIEW">Pending Review ({summaryMetrics.requiringReview})</option>
            <option value="ACKNOWLEDGED">Acknowledged</option>
          </select>
        </div>
        <div className="control-group">
          <label>Severity Filter:</label>
          <select value={severityFilter} onChange={(e) => setSeverityFilter(e.target.value)}>
            <option value="ALL">All Severities</option>
            <option value="CRITICAL">Critical Only</option>
            <option value="HIGH">High Severity</option>
            <option value="MEDIUM">Medium / Low</option>
          </select>
        </div>
      </div>

      {/* MAIN MONITOR WORKSPACE */}
      <div className="monitor-content-grid">
        <div className="alerts-feed-pane">
          {filteredAlerts.length === 0 ? (
            <div className="empty-alerts-card">
              <span className="empty-icon">🛡️</span>
              <h3>No Integrity Violations Detected</h3>
              <p>
                {alerts.length === 0
                  ? 'All active examinations are running within permitted offline lockdown parameters.'
                  : 'No integrity events match the currently selected status or severity filters.'}
              </p>
            </div>
          ) : (
            <div className="alerts-list">
              {filteredAlerts.map((alert) => (
                <div
                  key={alert.alertId}
                  className={`alert-card ${selectedAlert?.alertId === alert.alertId ? 'selected' : ''}`}
                  onClick={() => setSelectedAlert(alert)}
                >
                  <div className="alert-card-header">
                    <div className="alert-student-name">
                      {alert.studentDisplayName || alert.studentId}
                    </div>
                    <div className="alert-badges">
                      {getSeverityBadge(alert.eventCategory)}
                      {getStatusBadge(alert.alertStatus)}
                    </div>
                  </div>

                  <div className="alert-category-line">
                    <strong>Event:</strong> {alert.eventCategory}
                  </div>

                  <div className="alert-app-line">
                    <strong>Target App:</strong>{' '}
                    {alert.displayedAppName === 'APP_NAME_UNAVAILABLE' || !alert.displayedAppName ? (
                      <span className="app-unavailable-tag">APP_NAME_UNAVAILABLE (OS Protected)</span>
                    ) : (
                      <span className="app-identified-tag">
                        {alert.displayedAppName} {alert.appPackageName ? `(${alert.appPackageName})` : ''}
                      </span>
                    )}
                  </div>

                  <div className="alert-time-line">
                    Reported: {new Date(alert.deviceTimestamp).toLocaleTimeString()} • Attempt: {alert.attemptId}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* INCIDENT DETAILS SIDEBAR */}
        {selectedAlert && (
          <div className="alert-detail-pane">
            <div className="detail-card">
              <h3>Incident Telemetry Record</h3>
              <div className="detail-meta-grid">
                <div className="detail-meta-row">
                  <strong>Student:</strong>
                  <span>{selectedAlert.studentDisplayName} ({selectedAlert.studentId})</span>
                </div>
                <div className="detail-meta-row">
                  <strong>Exam Title:</strong>
                  <span>{selectedAlert.examTitle}</span>
                </div>
                <div className="detail-meta-row">
                  <strong>Attempt ID:</strong>
                  <span><code>{selectedAlert.attemptId}</code></span>
                </div>
                <div className="detail-meta-row">
                  <strong>Attempt Status:</strong>
                  <span>{selectedAlert.attemptStatus}</span>
                </div>
                <div className="detail-meta-row">
                  <strong>Event Category:</strong>
                  <span>{selectedAlert.eventCategory}</span>
                </div>
                <div className="detail-meta-row">
                  <strong>Detection Method:</strong>
                  <span>{selectedAlert.detectionSource}</span>
                </div>
                <div className="detail-meta-row">
                  <strong>Evidence Confidence:</strong>
                  <span>{selectedAlert.confidenceLevel}</span>
                </div>
                <div className="detail-meta-row">
                  <strong>Device Reported At:</strong>
                  <span>{new Date(selectedAlert.deviceTimestamp).toLocaleString()}</span>
                </div>
                <div className="detail-meta-row">
                  <strong>Target Application:</strong>
                  <span>
                    {selectedAlert.displayedAppName === 'APP_NAME_UNAVAILABLE' || !selectedAlert.displayedAppName ? (
                      <span className="app-unavailable-tag">APP_NAME_UNAVAILABLE (OS Restricted)</span>
                    ) : (
                      <span className="app-identified-tag">
                        {selectedAlert.displayedAppName} {selectedAlert.appPackageName ? `(${selectedAlert.appPackageName})` : ''}
                      </span>
                    )}
                  </span>
                </div>
                <div className="detail-meta-row">
                  <strong>Enforced Action:</strong>
                  <span>{selectedAlert.policyAction}</span>
                </div>
              </div>

              <div className="detail-actions">
                <button
                  type="button"
                  className="btn btn-primary"
                  style={{ width: '100%' }}
                  disabled={
                    selectedAlert.alertStatus === 'ACKNOWLEDGED' ||
                    acknowledgingId === (selectedAlert.incidentId || selectedAlert.alertId.replace('alert_', ''))
                  }
                  onClick={() =>
                    handleAcknowledge(selectedAlert.incidentId || selectedAlert.alertId.replace('alert_', ''))
                  }
                >
                  {selectedAlert.alertStatus === 'ACKNOWLEDGED'
                    ? '✓ Alert Acknowledged by Examiner'
                    : acknowledgingId
                    ? 'Recording Acknowledgement...'
                    : 'Acknowledge Integrity Alert'}
                </button>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

export default ExaminerIntegrityMonitor;
