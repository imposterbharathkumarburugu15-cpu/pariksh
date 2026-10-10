import React, { useEffect, useState } from 'react';
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
  const [severityFilter, setSeverityFilter] = useState<string>('ALL');
  const [statusFilter, setStatusFilter] = useState<string>('ALL');
  const [selectedAlert, setSelectedAlert] = useState<IntegrityAlert | null>(null);
  const [acknowledgingId, setAcknowledgingId] = useState<string | null>(null);

  const fetchAlerts = async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch(`${serverUrl}/api/examiner/integrity/alerts`, {
        headers: {
          Authorization: `Bearer ${authToken}`
        }
      });
      if (!res.ok) {
        throw new Error(`Failed to load alerts: HTTP ${res.status}`);
      }
      const data = await res.json();
      setAlerts(data);
    } catch (err: unknown) {
      const e = err as Error;
      setError(e?.message || 'Error fetching examiner alerts');
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
          setSelectedAlert((prev) => prev ? { ...prev, alertStatus: 'ACKNOWLEDGED' } : null);
        }
      }
    } catch {
      // Keep alert intact if network call fails
    } finally {
      setAcknowledgingId(null);
    }
  };

  const filteredAlerts = alerts.filter((a) => {
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
      case 'RECEIVED':
      case 'PENDING':
        return <span className="status-badge badge-new">NEW</span>;
      case 'ACKNOWLEDGED':
        return <span className="status-badge badge-ack">ACKNOWLEDGED</span>;
      case 'ACTION_APPLIED':
        return <span className="status-badge badge-applied">ACTION APPLIED</span>;
      default:
        return <span className="status-badge badge-default">{status}</span>;
    }
  };

  return (
    <div className="integrity-monitor-container">
      <div className="monitor-header">
        <div className="monitor-title-group">
          <h2>🚨 Examiner Real-Time Integrity Monitor</h2>
          <span className="live-pulse-badge">LIVE LOCAL TELEMETRY</span>
        </div>
        <div className="monitor-header-actions">
          <button type="button" className="btn-secondary btn-sm" onClick={fetchAlerts} disabled={loading}>
            {loading ? 'Refreshing...' : '↻ Refresh Feed'}
          </button>
          {onClose && (
            <button type="button" className="btn-secondary btn-sm" onClick={onClose}>
              Close Monitor
            </button>
          )}
        </div>
      </div>

      {error && <div className="monitor-error-banner">⚠️ {error}</div>}

      <div className="monitor-controls">
        <div className="control-group">
          <label>Status Filter:</label>
          <select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)}>
            <option value="ALL">All Statuses ({alerts.length})</option>
            <option value="RECEIVED">New / Pending</option>
            <option value="ACKNOWLEDGED">Acknowledged</option>
          </select>
        </div>
        <div className="control-group">
          <label>Severity Filter:</label>
          <select value={severityFilter} onChange={(e) => setSeverityFilter(e.target.value)}>
            <option value="ALL">All Severities</option>
            <option value="CRITICAL">Critical</option>
            <option value="HIGH">High</option>
            <option value="MEDIUM">Medium / Low</option>
          </select>
        </div>
      </div>

      <div className="monitor-content-grid">
        <div className="alerts-feed-pane">
          {filteredAlerts.length === 0 ? (
            <div className="empty-alerts-card">
              <span className="empty-icon">🛡️</span>
              <h3>No Integrity Violations Detected</h3>
              <p>Active examinations are running within permitted parameters.</p>
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
                      <span className="app-unavailable-tag">APP_NAME_UNAVAILABLE</span>
                    ) : (
                      <span className="app-identified-tag">
                        {alert.displayedAppName} {alert.appPackageName ? `(${alert.appPackageName})` : ''}
                      </span>
                    )}
                  </div>

                  <div className="alert-time-line">
                    Reported: {new Date(alert.deviceTimestamp).toLocaleTimeString()}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>

        {selectedAlert && (
          <div className="alert-detail-pane">
            <div className="detail-card">
              <h3>Incident Details</h3>
              <div className="detail-meta-grid">
                <div>
                  <strong>Student:</strong> {selectedAlert.studentDisplayName} ({selectedAlert.studentId})
                </div>
                <div>
                  <strong>Exam:</strong> {selectedAlert.examTitle}
                </div>
                <div>
                  <strong>Attempt ID:</strong> {selectedAlert.attemptId}
                </div>
                <div>
                  <strong>Current Attempt Status:</strong>{' '}
                  <span className={`attempt-status-pill status-${selectedAlert.attemptStatus.toLowerCase()}`}>
                    {selectedAlert.attemptStatus}
                  </span>
                </div>
                <div>
                  <strong>Event Category:</strong> {selectedAlert.eventCategory}
                </div>
                <div>
                  <strong>Detection Method:</strong> {selectedAlert.detectionSource}
                </div>
                <div>
                  <strong>Evidence Confidence:</strong> {selectedAlert.confidenceLevel}
                </div>
                <div>
                  <strong>Device Reported At:</strong> {selectedAlert.deviceTimestamp}
                </div>
                <div>
                  <strong>Server Received At:</strong> {selectedAlert.receivedAt}
                </div>
                <div>
                  <strong>Target Application:</strong>{' '}
                  {selectedAlert.displayedAppName === 'APP_NAME_UNAVAILABLE' || !selectedAlert.displayedAppName ? (
                    <span className="app-unavailable-tag">APP_NAME_UNAVAILABLE (OS Restricted)</span>
                  ) : (
                    <span className="app-identified-tag">
                      {selectedAlert.displayedAppName} ({selectedAlert.appPackageName})
                    </span>
                  )}
                </div>
                <div>
                  <strong>Policy Action:</strong> {selectedAlert.policyAction}
                </div>
              </div>

              <div className="detail-actions">
                <button
                  type="button"
                  className="btn-primary"
                  disabled={selectedAlert.alertStatus === 'ACKNOWLEDGED' || acknowledgingId === (selectedAlert.incidentId || selectedAlert.alertId.replace('alert_', ''))}
                  onClick={() =>
                    handleAcknowledge(selectedAlert.incidentId || selectedAlert.alertId.replace('alert_', ''))
                  }
                >
                  {selectedAlert.alertStatus === 'ACKNOWLEDGED'
                    ? '✓ Alert Acknowledged'
                    : acknowledgingId
                    ? 'Acknowledging...'
                    : 'Acknowledge Alert'}
                </button>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};
