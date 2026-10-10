import React, { useState, useEffect, useCallback } from 'react';
import { REQUIRED_LIMITED_EVIDENCE_HOLD_FIXTURE } from '../domain/incidents';

interface IncidentItem {
  incidentId: string;
  examId: string;
  examTitle: string;
  attemptId: string;
  studentId: string;
  studentDisplayName: string;
  category: string;
  incidentStatus: string;
  evidenceAssessment: string;
  decision: string;
  requestedPolicyAction: string;
  policyActionExecutionStatus: string;
  reviewerId: string | null;
  policyVersion: string;
  version: number;
  hasPendingAppeals: boolean;
  appealsCount: number;
  createdAt: string;
  updatedAt: string;
}

interface IncidentDetail extends IncidentItem {
  attempt: {
    attemptStatus: string;
    resultStatus: string;
    startedAt: string;
    submittedAt: string | null;
    savedResponseCount: number;
  };
  result?: {
    resultId: string;
    resultStatus: string;
    totalScaledScore: number;
    maxPossibleScaledScore: number;
    correctCount: number;
    holdReason: string | null;
  };
  events: Array<{
    eventId: string;
    eventCategory: string;
    deviceReportedAt: string;
    receivedAt: string | null;
    sequenceNumber: number;
    sourceDeviceId: string;
    appPackageName: string | null;
    displayedAppName: string;
    detectionSource: string;
    ingestionStatus: string;
  }>;
  decisions: Array<{
    decisionId: string;
    reviewerId: string;
    decision: string;
    evidenceAssessment: string;
    reviewerJustification: string;
    policyVersion: string;
    createdAt: string;
  }>;
  policyActions: Array<{
    actionId: string;
    actionType: string;
    executionStatus: string;
    requestedBy: string;
    approvedBy: string | null;
    reason: string;
    createdAt: string;
    completedAt: string | null;
  }>;
  appeals: Array<{
    appealId: string;
    studentId: string;
    challengedAction: string;
    appealReason: string;
    evidenceReferences: string | null;
    appealStatus: string;
    assignedReviewerId: string | null;
    reviewerDecision: string | null;
    outcomeReason: string | null;
    submittedAt: string;
    resolvedAt: string | null;
  }>;
}

interface AuditTimelineEvent {
  auditEventId: string;
  actorId: string;
  actorRole: string;
  actionType: string;
  previousState: string | null;
  newState: string | null;
  reason: string | null;
  operationResult: string;
  currentHash: string;
  timestamp: string;
}

interface Props {
  serverUrl: string;
  sessionToken: string | null;
  currentUser: {
    userId: string;
    displayName: string;
    role: string;
    instituteId: string;
  } | null;
}

export const IncidentReviewDashboard: React.FC<Props> = ({
  serverUrl,
  sessionToken,
  currentUser
}) => {
  const [incidents, setIncidents] = useState<IncidentItem[]>([]);
  const [selectedIncidentId, setSelectedIncidentId] = useState<string | null>(null);
  const [incidentDetail, setIncidentDetail] = useState<IncidentDetail | null>(null);
  const [auditTimeline, setAuditTimeline] = useState<AuditTimelineEvent[]>([]);
  const [loading, setLoading] = useState<boolean>(false);
  const [detailLoading, setDetailLoading] = useState<boolean>(false);
  const [actionMessage, setActionMessage] = useState<{ type: 'success' | 'error' | 'info'; text: string } | null>(null);

  // Filters
  const [filterExam, setFilterExam] = useState<string>('');
  const [filterStudent, setFilterStudent] = useState<string>('');
  const [filterStatus, setFilterStatus] = useState<string>('ALL');
  const [filterCategory, setFilterCategory] = useState<string>('ALL');
  const [filterOnlyPendingAppeals, setFilterOnlyPendingAppeals] = useState<boolean>(false);

  // Assessment & Decision Form
  const [evidenceAssessment, setEvidenceAssessment] = useState<string>('LIMITED');
  const [decisionChoice, setDecisionChoice] = useState<string>('PENDING_FURTHER_REVIEW');
  const [justification, setJustification] = useState<string>('');

  // Policy Action Form
  const [policyActionReason, setPolicyActionReason] = useState<string>('');

  // Appeal Form (Student)
  const [appealChallengedAction, setAppealChallengedAction] = useState<string>('RESULT_HOLD');
  const [appealReason, setAppealReason] = useState<string>('');
  const [appealEvidenceRef, setAppealEvidenceRef] = useState<string>('');

  // Appeal Decision Form (Admin)
  const [selectedAppealId, setSelectedAppealId] = useState<string | null>(null);
  const [appealDecision, setAppealDecision] = useState<string>('UPHELD');
  const [appealOutcomeReason, setAppealOutcomeReason] = useState<string>('');

  const fetchIncidents = useCallback(async () => {
    if (!sessionToken) return;
    setLoading(true);
    setActionMessage(null);
    try {
      const params = new URLSearchParams();
      if (filterExam.trim()) params.append('examId', filterExam.trim());
      if (filterStudent.trim()) params.append('studentId', filterStudent.trim());
      if (filterStatus !== 'ALL') params.append('status', filterStatus);
      if (filterCategory !== 'ALL') params.append('category', filterCategory);

      const res = await fetch(`${serverUrl}/api/incidents?${params.toString()}`, {
        headers: { Authorization: `Bearer ${sessionToken}` }
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error(err.message || `Failed to fetch incidents (${res.status})`);
      }
      let data: IncidentItem[] = await res.json();
      if (filterOnlyPendingAppeals) {
        data = data.filter((item) => item.hasPendingAppeals);
      }
      setIncidents(data);
      if (data.length > 0 && !selectedIncidentId && data[0]) {
        setSelectedIncidentId(data[0].incidentId);
      }
    } catch (err: any) {
      setActionMessage({ type: 'error', text: err.message || 'Error loading incident queue' });
    } finally {
      setLoading(false);
    }
  }, [serverUrl, sessionToken, filterExam, filterStudent, filterStatus, filterCategory, filterOnlyPendingAppeals, selectedIncidentId]);

  const fetchIncidentDetail = useCallback(async (id: string) => {
    if (!sessionToken) return;
    setDetailLoading(true);
    try {
      const res = await fetch(`${serverUrl}/api/incidents/${id}`, {
        headers: { Authorization: `Bearer ${sessionToken}` }
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error(err.message || `Failed to load detail (${res.status})`);
      }
      const data: IncidentDetail = await res.json();
      setIncidentDetail(data);

      // Default assessment form to current state
      setEvidenceAssessment(data.evidenceAssessment || 'LIMITED');
      setDecisionChoice(data.decision === 'PENDING_REVIEW' ? 'PENDING_FURTHER_REVIEW' : data.decision);

      // Fetch audit timeline if reviewer/admin
      if (currentUser?.role !== 'STUDENT') {
        const auditRes = await fetch(`${serverUrl}/api/incidents/${id}/audit`, {
          headers: { Authorization: `Bearer ${sessionToken}` }
        });
        if (auditRes.ok) {
          const auditData = await auditRes.json();
          setAuditTimeline(auditData);
        }
      }
    } catch (err: any) {
      setActionMessage({ type: 'error', text: err.message || 'Error loading incident detail' });
    } finally {
      setDetailLoading(false);
    }
  }, [serverUrl, sessionToken, currentUser]);

  useEffect(() => {
    fetchIncidents();
  }, [fetchIncidents]);

  useEffect(() => {
    if (selectedIncidentId) {
      fetchIncidentDetail(selectedIncidentId);
    } else {
      setIncidentDetail(null);
      setAuditTimeline([]);
    }
  }, [selectedIncidentId, fetchIncidentDetail]);

  const handleBeginReview = async () => {
    if (!selectedIncidentId || !sessionToken) return;
    setActionMessage(null);
    try {
      const res = await fetch(`${serverUrl}/api/incidents/${selectedIncidentId}/review/begin`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${sessionToken}` }
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data.message || 'Failed to begin review');
      setActionMessage({ type: 'success', text: 'Investigation started. Case is now UNDER_REVIEW.' });
      fetchIncidentDetail(selectedIncidentId);
      fetchIncidents();
    } catch (err: any) {
      setActionMessage({ type: 'error', text: err.message });
    }
  };

  const handleExecuteScenario = async () => {
    if (!selectedIncidentId || !sessionToken) return;
    setActionMessage(null);
    try {
      const res = await fetch(`${serverUrl}/api/incidents/${selectedIncidentId}/scenario/limited-evidence-hold`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${sessionToken}` }
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data.message || 'Scenario execution failed');
      setActionMessage({
        type: 'success',
        text: `Scenario Applied: Category=${REQUIRED_LIMITED_EVIDENCE_HOLD_FIXTURE.category}, Assessment=LIMITED, Action=HOLD, Status=PENDING_FURTHER_REVIEW, Result=HELD_PENDING_REVIEW`
      });
      fetchIncidentDetail(selectedIncidentId);
      fetchIncidents();
    } catch (err: any) {
      setActionMessage({ type: 'error', text: err.message });
    }
  };

  const handleAssessDecision = async () => {
    if (!selectedIncidentId || !sessionToken) return;
    setActionMessage(null);

    // Frontend validation guard: limited evidence cannot confirm
    if (decisionChoice === 'CONFIRMED' && evidenceAssessment !== 'SUFFICIENT') {
      setActionMessage({
        type: 'error',
        text: 'Policy Guard: Misconduct cannot be confirmed on LIMITED or CONTRADICTORY evidence. Evidence must be SUFFICIENT.'
      });
      return;
    }

    try {
      const res = await fetch(`${serverUrl}/api/incidents/${selectedIncidentId}/assess`, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${sessionToken}`,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({
          evidenceAssessment,
          decision: decisionChoice,
          justification: justification.trim() || 'No notes supplied'
        })
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data.message || 'Failed to record decision');
      setActionMessage({ type: 'success', text: `Decision recorded: ${decisionChoice} (${evidenceAssessment})` });
      setJustification('');
      fetchIncidentDetail(selectedIncidentId);
      fetchIncidents();
    } catch (err: any) {
      setActionMessage({ type: 'error', text: err.message });
    }
  };

  const handleHoldAction = async (action: 'HOLD' | 'RELEASE_HOLD') => {
    if (!selectedIncidentId || !sessionToken) return;
    setActionMessage(null);
    try {
      const res = await fetch(`${serverUrl}/api/incidents/${selectedIncidentId}/hold`, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${sessionToken}`,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({
          action,
          reason: policyActionReason.trim() || (action === 'HOLD' ? 'Temporary result hold pending review' : 'Hold lifted following review')
        })
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data.message || 'Hold action failed');
      setActionMessage({ type: 'success', text: `Result hold ${action === 'HOLD' ? 'applied' : 'released'} successfully.` });
      setPolicyActionReason('');
      fetchIncidentDetail(selectedIncidentId);
      fetchIncidents();
    } catch (err: any) {
      setActionMessage({ type: 'error', text: err.message });
    }
  };

  const handleCancelAttempt = async () => {
    if (!selectedIncidentId || !sessionToken) return;
    if (!policyActionReason.trim()) {
      setActionMessage({ type: 'error', text: 'Documented justification required to cancel an attempt.' });
      return;
    }
    setActionMessage(null);
    try {
      const res = await fetch(`${serverUrl}/api/incidents/${selectedIncidentId}/cancel`, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${sessionToken}`,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({ reason: policyActionReason.trim() })
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data.message || 'Cancellation failed');
      setActionMessage({ type: 'success', text: 'Attempt cancelled. Result status set to WITHHELD.' });
      setPolicyActionReason('');
      fetchIncidentDetail(selectedIncidentId);
      fetchIncidents();
    } catch (err: any) {
      setActionMessage({ type: 'error', text: err.message });
    }
  };

  const handleReinstateAttempt = async () => {
    if (!selectedIncidentId || !sessionToken) return;
    if (!policyActionReason.trim()) {
      setActionMessage({ type: 'error', text: 'Documented justification required to reinstate an attempt.' });
      return;
    }
    setActionMessage(null);
    try {
      const res = await fetch(`${serverUrl}/api/incidents/${selectedIncidentId}/reinstate`, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${sessionToken}`,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({ reason: policyActionReason.trim() })
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data.message || 'Reinstatement failed');
      setActionMessage({ type: 'success', text: 'Attempt successfully reinstated.' });
      setPolicyActionReason('');
      fetchIncidentDetail(selectedIncidentId);
      fetchIncidents();
    } catch (err: any) {
      setActionMessage({ type: 'error', text: err.message });
    }
  };

  const handleSubmitAppeal = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedIncidentId || !sessionToken) return;
    setActionMessage(null);
    try {
      const res = await fetch(`${serverUrl}/api/incidents/${selectedIncidentId}/appeals`, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${sessionToken}`,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({
          challengedAction: appealChallengedAction,
          appealReason: appealReason.trim(),
          evidenceReferences: appealEvidenceRef.trim() || undefined
        })
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data.message || 'Appeal submission failed');
      setActionMessage({ type: 'success', text: `Appeal submitted (ID: ${data.appealId}). Awaiting independent review.` });
      setAppealReason('');
      setAppealEvidenceRef('');
      fetchIncidentDetail(selectedIncidentId);
      fetchIncidents();
    } catch (err: any) {
      setActionMessage({ type: 'error', text: err.message });
    }
  };

  const handleAdjudicateAppeal = async (appealId: string) => {
    if (!selectedIncidentId || !sessionToken) return;
    setActionMessage(null);
    try {
      const res = await fetch(`${serverUrl}/api/incidents/${selectedIncidentId}/appeals/${appealId}/decide`, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${sessionToken}`,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({
          decision: appealDecision,
          outcomeReason: appealOutcomeReason.trim()
        })
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data.message || 'Appeal decision failed');
      setActionMessage({ type: 'success', text: `Appeal adjudicated: ${appealDecision}` });
      setSelectedAppealId(null);
      setAppealOutcomeReason('');
      fetchIncidentDetail(selectedIncidentId);
      fetchIncidents();
    } catch (err: any) {
      setActionMessage({ type: 'error', text: err.message });
    }
  };

  const getStatusBadge = (status: string) => {
    let bg = '#21262d';
    let text = '#8b949e';
    if (status === 'CONFIRMED' || status === 'CANCELLED' || status === 'UPHELD') {
      bg = '#441d1d';
      text = '#ff7b72';
    } else if (status === 'UNDER_REVIEW' || status === 'HELD_PENDING_REVIEW' || status === 'PENDING_REVIEW') {
      bg = '#3d2c00';
      text = '#d29922';
    } else if (status === 'PENDING_FURTHER_REVIEW') {
      bg = '#14384a';
      text = '#58a6ff';
    } else if (status === 'DISMISSED' || status === 'GRADED' || status === 'OVERTURNED' || status === 'RESTORED') {
      bg = '#163828';
      text = '#3fb950';
    }
    return (
      <span
        style={{
          display: 'inline-block',
          padding: '2px 8px',
          fontSize: '11px',
          fontWeight: 700,
          borderRadius: '4px',
          backgroundColor: bg,
          color: text,
          letterSpacing: '0.5px'
        }}
      >
        {status}
      </span>
    );
  };

  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        height: '100%',
        backgroundColor: '#0d1117',
        color: '#c9d1d9',
        fontFamily: 'system-ui, -apple-system, sans-serif'
      }}
    >
      {/* Header Bar */}
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          padding: '12px 20px',
          borderBottom: '1px solid #30363d',
          backgroundColor: '#161b22'
        }}
      >
        <div>
          <h2 style={{ margin: 0, fontSize: '18px', color: '#00e5ff', display: 'flex', alignItems: 'center', gap: '8px' }}>
            <span>⚖️</span> Administrator Incident Review & Appeals
          </h2>
          <div style={{ fontSize: '12px', color: '#8b949e', marginTop: '2px' }}>
            DOOMEDAMS Step 9: Offline Trust Layer • Authoritative Kotlin Transition Enforcement
          </div>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
          <button
            onClick={fetchIncidents}
            disabled={loading}
            style={{
              padding: '6px 12px',
              backgroundColor: '#21262d',
              border: '1px solid #30363d',
              color: '#c9d1d9',
              borderRadius: '4px',
              cursor: 'pointer',
              fontSize: '12px'
            }}
          >
            {loading ? 'Refreshing...' : '🔄 Refresh Queue'}
          </button>
        </div>
      </div>

      {/* Action Notification */}
      {actionMessage && (
        <div
          style={{
            padding: '10px 20px',
            backgroundColor: actionMessage.type === 'error' ? '#441d1d' : actionMessage.type === 'success' ? '#163828' : '#14384a',
            color: actionMessage.type === 'error' ? '#ff7b72' : actionMessage.type === 'success' ? '#3fb950' : '#58a6ff',
            fontSize: '13px',
            borderBottom: '1px solid #30363d'
          }}
        >
          {actionMessage.text}
        </div>
      )}

      {/* Main Content Layout */}
      <div style={{ display: 'flex', flex: 1, overflow: 'hidden' }}>
        {/* Left: Incident Queue */}
        <div
          style={{
            width: '380px',
            borderRight: '1px solid #30363d',
            display: 'flex',
            flexDirection: 'column',
            backgroundColor: '#0d1117'
          }}
        >
          {/* Queue Filter Panel */}
          <div style={{ padding: '12px', borderBottom: '1px solid #21262d', backgroundColor: '#161b22' }}>
            <div style={{ fontSize: '12px', fontWeight: 600, color: '#8b949e', marginBottom: '8px' }}>FILTER QUEUE</div>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '8px', marginBottom: '8px' }}>
              <div>
                <label style={{ fontSize: '10px', color: '#8b949e', display: 'block' }}>Status</label>
                <select
                  value={filterStatus}
                  onChange={(e) => setFilterStatus(e.target.value)}
                  style={{ width: '100%', padding: '4px', backgroundColor: '#0d1117', color: '#c9d1d9', border: '1px solid #30363d', borderRadius: '4px', fontSize: '11px' }}
                >
                  <option value="ALL">All Statuses</option>
                  <option value="PENDING_REVIEW">Pending Review</option>
                  <option value="UNDER_REVIEW">Under Review</option>
                  <option value="PENDING_FURTHER_REVIEW">Pending Further Review</option>
                  <option value="CONFIRMED">Confirmed</option>
                  <option value="DISMISSED">Dismissed</option>
                  <option value="CLOSED">Closed</option>
                </select>
              </div>

              <div>
                <label style={{ fontSize: '10px', color: '#8b949e', display: 'block' }}>Category</label>
                <select
                  value={filterCategory}
                  onChange={(e) => setFilterCategory(e.target.value)}
                  style={{ width: '100%', padding: '4px', backgroundColor: '#0d1117', color: '#c9d1d9', border: '1px solid #30363d', borderRadius: '4px', fontSize: '11px' }}
                >
                  <option value="ALL">All Categories</option>
                  <option value="UNAUTHORISED_APP_SWITCH">App Switch</option>
                  <option value="LOCK_TASK_EXITED">Lock Task Exit</option>
                  <option value="PROHIBITED_APP_FOREGROUND">Prohibited App</option>
                  <option value="EXAM_APP_BACKGROUND">Backgrounded</option>
                  <option value="WINDOW_BLUR">Window Blur</option>
                </select>
              </div>
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '8px', marginBottom: '8px' }}>
              <div>
                <label style={{ fontSize: '10px', color: '#8b949e', display: 'block' }}>Exam ID</label>
                <input
                  type="text"
                  placeholder="Filter exam..."
                  value={filterExam}
                  onChange={(e) => setFilterExam(e.target.value)}
                  style={{ width: '100%', padding: '4px', backgroundColor: '#0d1117', color: '#c9d1d9', border: '1px solid #30363d', borderRadius: '4px', fontSize: '11px' }}
                />
              </div>
              <div>
                <label style={{ fontSize: '10px', color: '#8b949e', display: 'block' }}>Student ID</label>
                <input
                  type="text"
                  placeholder="Filter student..."
                  value={filterStudent}
                  onChange={(e) => setFilterStudent(e.target.value)}
                  style={{ width: '100%', padding: '4px', backgroundColor: '#0d1117', color: '#c9d1d9', border: '1px solid #30363d', borderRadius: '4px', fontSize: '11px' }}
                />
              </div>
            </div>

            <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
              <input
                type="checkbox"
                id="pendingAppealsToggle"
                checked={filterOnlyPendingAppeals}
                onChange={(e) => setFilterOnlyPendingAppeals(e.target.checked)}
              />
              <label htmlFor="pendingAppealsToggle" style={{ fontSize: '11px', color: '#c9d1d9', cursor: 'pointer' }}>
                Show cases with pending appeals only
              </label>
            </div>
          </div>

          {/* Incident List */}
          <div style={{ flex: 1, overflowY: 'auto' }}>
            {incidents.length === 0 ? (
              <div style={{ padding: '30px', textAlign: 'center', color: '#8b949e', fontSize: '13px' }}>
                No incidents match the active filter criteria.
              </div>
            ) : (
              incidents.map((inc) => (
                <div
                  key={inc.incidentId}
                  onClick={() => setSelectedIncidentId(inc.incidentId)}
                  style={{
                    padding: '12px',
                    borderBottom: '1px solid #21262d',
                    cursor: 'pointer',
                    backgroundColor: selectedIncidentId === inc.incidentId ? '#1f2937' : 'transparent',
                    borderLeft: selectedIncidentId === inc.incidentId ? '3px solid #00e5ff' : '3px solid transparent'
                  }}
                >
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '4px' }}>
                    <span style={{ fontSize: '13px', fontWeight: 600, color: '#f0f6fc' }}>{inc.studentDisplayName}</span>
                    {getStatusBadge(inc.incidentStatus)}
                  </div>
                  <div style={{ fontSize: '11px', color: '#58a6ff', marginBottom: '4px' }}>
                    {inc.category}
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '10px', color: '#8b949e' }}>
                    <span>Action: {inc.requestedPolicyAction} ({inc.policyActionExecutionStatus})</span>
                    {inc.hasPendingAppeals && <span style={{ color: '#ffb703', fontWeight: 700 }}>⚠️ APPEAL PENDING</span>}
                  </div>
                </div>
              ))
            )}
          </div>
        </div>

        {/* Right: Detail & Investigation Workspace */}
        <div style={{ flex: 1, overflowY: 'auto', padding: '20px', backgroundColor: '#0d1117' }}>
          {detailLoading ? (
            <div style={{ padding: '40px', textAlign: 'center', color: '#8b949e' }}>Loading incident details...</div>
          ) : !incidentDetail ? (
            <div style={{ padding: '40px', textAlign: 'center', color: '#8b949e' }}>
              Select an incident from the queue on the left to begin review.
            </div>
          ) : (
            <div>
              {/* Top Banner: Incident Identification & Status */}
              <div
                style={{
                  padding: '16px',
                  backgroundColor: '#161b22',
                  border: '1px solid #30363d',
                  borderRadius: '6px',
                  marginBottom: '16px'
                }}
              >
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                  <div>
                    <h3 style={{ margin: 0, fontSize: '16px', color: '#f0f6fc' }}>
                      Incident #{incidentDetail.incidentId}
                    </h3>
                    <div style={{ fontSize: '13px', color: '#8b949e', marginTop: '4px' }}>
                      Candidate: <strong style={{ color: '#c9d1d9' }}>{incidentDetail.studentDisplayName}</strong> ({incidentDetail.studentId}) • Exam: <strong style={{ color: '#c9d1d9' }}>{incidentDetail.examTitle}</strong>
                    </div>
                  </div>

                  <div style={{ display: 'flex', gap: '8px' }}>
                    {getStatusBadge(incidentDetail.incidentStatus)}
                    <span style={{ padding: '2px 8px', fontSize: '11px', fontWeight: 700, borderRadius: '4px', backgroundColor: '#1e293b', color: '#94a3b8' }}>
                      EVIDENCE: {incidentDetail.evidenceAssessment}
                    </span>
                  </div>
                </div>

                {/* State Transition Controls */}
                <div style={{ marginTop: '14px', paddingTop: '12px', borderTop: '1px solid #21262d', display: 'flex', gap: '10px', alignItems: 'center' }}>
                  {incidentDetail.incidentStatus === 'PENDING_REVIEW' && (
                    <button
                      onClick={handleBeginReview}
                      style={{
                        padding: '6px 14px',
                        backgroundColor: '#0284c7',
                        border: 'none',
                        color: '#fff',
                        borderRadius: '4px',
                        fontSize: '12px',
                        fontWeight: 600,
                        cursor: 'pointer'
                      }}
                    >
                      ▶️ Begin Formal Review
                    </button>
                  )}

                  {/* MANDATORY PRESCRIBED SCENARIO BUTTON */}
                  <button
                    onClick={handleExecuteScenario}
                    style={{
                      padding: '6px 14px',
                      backgroundColor: '#3b0764',
                      border: '1px solid #a855f7',
                      color: '#e9d5ff',
                      borderRadius: '4px',
                      fontSize: '12px',
                      fontWeight: 600,
                      cursor: 'pointer'
                    }}
                    title="Execute Step 9 Required Scenario: UNAUTHORISED_APP_SWITCH, LIMITED evidence, HOLD, 'No notes supplied' -> PENDING_FURTHER_REVIEW + HELD_PENDING_REVIEW"
                  >
                    🧪 Run Limited-Evidence Hold Scenario Fixture
                  </button>
                </div>
              </div>

              {/* Grid: Events & Attempt State */}
              <div style={{ display: 'grid', gridTemplateColumns: '1.2fr 1fr', gap: '16px', marginBottom: '16px' }}>
                {/* Integrity Events Telemetry */}
                <div style={{ padding: '16px', backgroundColor: '#161b22', border: '1px solid #30363d', borderRadius: '6px' }}>
                  <h4 style={{ margin: '0 0 10px 0', fontSize: '13px', color: '#00e5ff' }}>
                    📡 Corroborated Integrity Events ({incidentDetail.events.length})
                  </h4>
                  {incidentDetail.events.length === 0 ? (
                    <div style={{ fontSize: '12px', color: '#8b949e' }}>No telemetry events recorded.</div>
                  ) : (
                    incidentDetail.events.map((ev) => (
                      <div
                        key={ev.eventId}
                        style={{
                          padding: '10px',
                          backgroundColor: '#0d1117',
                          border: '1px solid #21262d',
                          borderRadius: '4px',
                          marginBottom: '8px',
                          fontSize: '11px'
                        }}
                      >
                        <div style={{ display: 'flex', justifyContent: 'space-between', fontWeight: 600, color: '#f0f6fc' }}>
                          <span>{ev.eventCategory}</span>
                          <span style={{ color: '#8b949e' }}>Seq #{ev.sequenceNumber}</span>
                        </div>
                        <div style={{ color: '#8b949e', marginTop: '4px' }}>
                          Reported: {ev.deviceReportedAt} | Received: {ev.receivedAt || 'Direct Local'}
                        </div>
                        <div style={{ color: '#c9d1d9', marginTop: '4px' }}>
                          App: <strong>{ev.displayedAppName}</strong> {ev.appPackageName ? `(${ev.appPackageName})` : '(No package identifier reliably observed)'}
                        </div>
                      </div>
                    ))
                  )}
                </div>

                {/* Candidate Attempt & Result State */}
                <div style={{ padding: '16px', backgroundColor: '#161b22', border: '1px solid #30363d', borderRadius: '6px' }}>
                  <h4 style={{ margin: '0 0 10px 0', fontSize: '13px', color: '#00e5ff' }}>
                    📝 Examination & Result State
                  </h4>
                  <div style={{ fontSize: '12px', lineHeight: '1.8' }}>
                    <div>Attempt Status: {getStatusBadge(incidentDetail.attempt.attemptStatus)}</div>
                    <div>Result Status: {getStatusBadge(incidentDetail.attempt.resultStatus)}</div>
                    <div>Persisted Working Responses: <strong>{incidentDetail.attempt.savedResponseCount}</strong> (Preserved)</div>
                    {incidentDetail.result && (
                      <div style={{ marginTop: '8px', padding: '8px', backgroundColor: '#0d1117', borderRadius: '4px' }}>
                        <div>Scaled Score: <strong>{(incidentDetail.result.totalScaledScore / 100).toFixed(2)}</strong> / {(incidentDetail.result.maxPossibleScaledScore / 100).toFixed(2)}</div>
                        <div>Correct Answers: <strong>{incidentDetail.result.correctCount}</strong></div>
                        {incidentDetail.result.holdReason && (
                          <div style={{ color: '#d29922', marginTop: '4px' }}>Hold Reason: {incidentDetail.result.holdReason}</div>
                        )}
                      </div>
                    )}
                  </div>
                </div>
              </div>

              {/* Evidence Assessment & Decision Panel (Examiner/Admin) */}
              {currentUser?.role !== 'STUDENT' && (
                <div
                  style={{
                    padding: '16px',
                    backgroundColor: '#161b22',
                    border: '1px solid #30363d',
                    borderRadius: '6px',
                    marginBottom: '16px'
                  }}
                >
                  <h4 style={{ margin: '0 0 12px 0', fontSize: '14px', color: '#00e5ff' }}>
                    📋 Evidence Assessment & Authorised Decision
                  </h4>

                  <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px', marginBottom: '12px' }}>
                    <div>
                      <label style={{ fontSize: '11px', color: '#8b949e', display: 'block', marginBottom: '4px' }}>
                        Evidence Assessment Classification
                      </label>
                      <select
                        value={evidenceAssessment}
                        onChange={(e) => setEvidenceAssessment(e.target.value)}
                        style={{ width: '100%', padding: '6px', backgroundColor: '#0d1117', color: '#c9d1d9', border: '1px solid #30363d', borderRadius: '4px', fontSize: '12px' }}
                      >
                        <option value="SUFFICIENT">SUFFICIENT (Reliable, corroborated violation)</option>
                        <option value="LIMITED">LIMITED (Uncertain or incomplete evidence)</option>
                        <option value="UNAVAILABLE">UNAVAILABLE (App or log details inaccessible)</option>
                        <option value="CONTRADICTORY">CONTRADICTORY (Conflicting hardware/sensor evidence)</option>
                      </select>
                    </div>

                    <div>
                      <label style={{ fontSize: '11px', color: '#8b949e', display: 'block', marginBottom: '4px' }}>
                        Adjudicated Decision
                      </label>
                      <select
                        value={decisionChoice}
                        onChange={(e) => setDecisionChoice(e.target.value)}
                        style={{ width: '100%', padding: '6px', backgroundColor: '#0d1117', color: '#c9d1d9', border: '1px solid #30363d', borderRadius: '4px', fontSize: '12px' }}
                      >
                        <option value="PENDING_FURTHER_REVIEW">PENDING_FURTHER_REVIEW (Further evidence required)</option>
                        <option value="CONFIRMED" disabled={evidenceAssessment !== 'SUFFICIENT'}>
                          CONFIRMED {evidenceAssessment !== 'SUFFICIENT' ? '(Disabled: Requires SUFFICIENT evidence)' : ''}
                        </option>
                        <option value="DISMISSED">DISMISSED (Allegation unsupported or benign)</option>
                      </select>
                    </div>
                  </div>

                  <div style={{ marginBottom: '12px' }}>
                    <label style={{ fontSize: '11px', color: '#8b949e', display: 'block', marginBottom: '4px' }}>
                      Reviewer Justification & Notes (Required for final determination)
                    </label>
                    <textarea
                      value={justification}
                      onChange={(e) => setJustification(e.target.value)}
                      rows={2}
                      placeholder="Enter detailed reviewer rationale..."
                      style={{ width: '100%', padding: '8px', backgroundColor: '#0d1117', color: '#c9d1d9', border: '1px solid #30363d', borderRadius: '4px', fontSize: '12px' }}
                    />
                  </div>

                  <button
                    onClick={handleAssessDecision}
                    style={{
                      padding: '8px 16px',
                      backgroundColor: '#0284c7',
                      border: 'none',
                      color: '#fff',
                      borderRadius: '4px',
                      fontSize: '12px',
                      fontWeight: 600,
                      cursor: 'pointer'
                    }}
                  >
                    Commit Authoritative Decision
                  </button>
                </div>
              )}

              {/* Policy Actions Panel: Result Holds & Cancellation */}
              {currentUser?.role !== 'STUDENT' && (
                <div
                  style={{
                    padding: '16px',
                    backgroundColor: '#161b22',
                    border: '1px solid #30363d',
                    borderRadius: '6px',
                    marginBottom: '16px'
                  }}
                >
                  <h4 style={{ margin: '0 0 12px 0', fontSize: '14px', color: '#00e5ff' }}>
                    🛡️ Enforceable Policy Actions
                  </h4>

                  <div style={{ marginBottom: '12px' }}>
                    <label style={{ fontSize: '11px', color: '#8b949e', display: 'block', marginBottom: '4px' }}>
                      Action Reason / Documented Ground
                    </label>
                    <input
                      type="text"
                      value={policyActionReason}
                      onChange={(e) => setPolicyActionReason(e.target.value)}
                      placeholder="e.g. Temporary result hold applied pending candidate hearing"
                      style={{ width: '100%', padding: '6px', backgroundColor: '#0d1117', color: '#c9d1d9', border: '1px solid #30363d', borderRadius: '4px', fontSize: '12px' }}
                    />
                  </div>

                  <div style={{ display: 'flex', gap: '10px', flexWrap: 'wrap' }}>
                    {/* Hold Actions */}
                    <button
                      onClick={() => handleHoldAction('HOLD')}
                      style={{
                        padding: '6px 14px',
                        backgroundColor: '#d97706',
                        border: 'none',
                        color: '#fff',
                        borderRadius: '4px',
                        fontSize: '12px',
                        fontWeight: 600,
                        cursor: 'pointer'
                      }}
                    >
                      ⏸️ Apply Result Hold
                    </button>

                    <button
                      onClick={() => handleHoldAction('RELEASE_HOLD')}
                      style={{
                        padding: '6px 14px',
                        backgroundColor: '#059669',
                        border: 'none',
                        color: '#fff',
                        borderRadius: '4px',
                        fontSize: '12px',
                        fontWeight: 600,
                        cursor: 'pointer'
                      }}
                    >
                      ▶️ Release Result Hold
                    </button>

                    {/* Privileged Cancellation & Reinstatement */}
                    {(currentUser?.role === 'EXAMINATION_ADMIN' || currentUser?.role === 'CHIEF_EXAMINATION_OFFICER') && (
                      <>
                        <button
                          onClick={handleCancelAttempt}
                          style={{
                            padding: '6px 14px',
                            backgroundColor: '#dc2626',
                            border: 'none',
                            color: '#fff',
                            borderRadius: '4px',
                            fontSize: '12px',
                            fontWeight: 600,
                            cursor: 'pointer'
                          }}
                        >
                          ⛔ Cancel Attempt
                        </button>

                        <button
                          onClick={handleReinstateAttempt}
                          style={{
                            padding: '6px 14px',
                            backgroundColor: '#4f46e5',
                            border: 'none',
                            color: '#fff',
                            borderRadius: '4px',
                            fontSize: '12px',
                            fontWeight: 600,
                            cursor: 'pointer'
                          }}
                        >
                          🔄 Reinstate Attempt
                        </button>
                      </>
                    )}
                  </div>
                </div>
              )}

              {/* Appeals Workflow Section */}
              <div
                style={{
                  padding: '16px',
                  backgroundColor: '#161b22',
                  border: '1px solid #30363d',
                  borderRadius: '6px',
                  marginBottom: '16px'
                }}
              >
                <h4 style={{ margin: '0 0 12px 0', fontSize: '14px', color: '#00e5ff' }}>
                  🏛️ Candidate Appeals & Second Reviews ({incidentDetail.appeals.length})
                </h4>

                {/* Existing Appeals List */}
                {incidentDetail.appeals.length === 0 ? (
                  <div style={{ fontSize: '12px', color: '#8b949e', marginBottom: '12px' }}>
                    No appeals filed for this incident.
                  </div>
                ) : (
                  <div style={{ marginBottom: '16px' }}>
                    {incidentDetail.appeals.map((appl) => (
                      <div
                        key={appl.appealId}
                        style={{
                          padding: '10px',
                          backgroundColor: '#0d1117',
                          border: '1px solid #21262d',
                          borderRadius: '4px',
                          marginBottom: '8px',
                          fontSize: '12px'
                        }}
                      >
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                          <span>Appeal #{appl.appealId} ({appl.challengedAction})</span>
                          {getStatusBadge(appl.appealStatus)}
                        </div>
                        <div style={{ color: '#c9d1d9', marginTop: '4px' }}>
                          Reason: <em>"{appl.appealReason}"</em>
                        </div>
                        {appl.outcomeReason && (
                          <div style={{ color: '#3fb950', marginTop: '4px' }}>
                            Decision Outcome: <strong>{appl.reviewerDecision}</strong> - {appl.outcomeReason}
                          </div>
                        )}

                        {/* Adjudicate Button for Independent Admin */}
                        {currentUser?.role !== 'STUDENT' && (appl.appealStatus === 'SUBMITTED' || appl.appealStatus === 'PENDING_REVIEW') && (
                          <div style={{ marginTop: '8px' }}>
                            <button
                              onClick={() => setSelectedAppealId(appl.appealId)}
                              style={{ padding: '4px 10px', fontSize: '11px', backgroundColor: '#21262d', color: '#58a6ff', border: '1px solid #30363d', borderRadius: '4px', cursor: 'pointer' }}
                            >
                              ⚖️ Adjudicate Appeal
                            </button>
                          </div>
                        )}
                      </div>
                    ))}
                  </div>
                )}

                {/* Appeal Adjudication Modal / Sub-form */}
                {selectedAppealId && (
                  <div style={{ padding: '12px', backgroundColor: '#0d1117', border: '1px solid #00e5ff', borderRadius: '4px', marginBottom: '14px' }}>
                    <div style={{ fontSize: '13px', fontWeight: 600, color: '#00e5ff', marginBottom: '8px' }}>
                      Adjudicate Appeal #{selectedAppealId} (Independent Review)
                    </div>
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 2fr', gap: '10px', marginBottom: '8px' }}>
                      <div>
                        <label style={{ fontSize: '10px', color: '#8b949e', display: 'block' }}>Decision</label>
                        <select
                          value={appealDecision}
                          onChange={(e) => setAppealDecision(e.target.value)}
                          style={{ width: '100%', padding: '4px', backgroundColor: '#161b22', color: '#c9d1d9', border: '1px solid #30363d', borderRadius: '4px', fontSize: '11px' }}
                        >
                          <option value="UPHELD">UPHELD (Original decision maintained)</option>
                          <option value="OVERTURNED">OVERTURNED (Finding reversed)</option>
                          <option value="REQUIRES_MORE_INFORMATION">REQUIRES_MORE_INFORMATION</option>
                        </select>
                      </div>
                      <div>
                        <label style={{ fontSize: '10px', color: '#8b949e', display: 'block' }}>Justification</label>
                        <input
                          type="text"
                          value={appealOutcomeReason}
                          onChange={(e) => setAppealOutcomeReason(e.target.value)}
                          placeholder="Documented adjudication findings..."
                          style={{ width: '100%', padding: '4px', backgroundColor: '#161b22', color: '#c9d1d9', border: '1px solid #30363d', borderRadius: '4px', fontSize: '11px' }}
                        />
                      </div>
                    </div>
                    <div style={{ display: 'flex', gap: '8px' }}>
                      <button
                        onClick={() => handleAdjudicateAppeal(selectedAppealId)}
                        style={{ padding: '4px 12px', backgroundColor: '#0284c7', color: '#fff', border: 'none', borderRadius: '4px', fontSize: '11px', cursor: 'pointer' }}
                      >
                        Submit Adjudication
                      </button>
                      <button
                        onClick={() => setSelectedAppealId(null)}
                        style={{ padding: '4px 12px', backgroundColor: '#21262d', color: '#8b949e', border: 'none', borderRadius: '4px', fontSize: '11px', cursor: 'pointer' }}
                      >
                        Cancel
                      </button>
                    </div>
                  </div>
                )}

                {/* Student Appeal Submission Form */}
                {currentUser?.role === 'STUDENT' && (
                  <form onSubmit={handleSubmitAppeal} style={{ borderTop: '1px solid #21262d', paddingTop: '12px' }}>
                    <div style={{ fontSize: '12px', fontWeight: 600, color: '#f0f6fc', marginBottom: '8px' }}>
                      File Formal Appeal against Decision
                    </div>
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 2fr', gap: '10px', marginBottom: '8px' }}>
                      <div>
                        <label style={{ fontSize: '10px', color: '#8b949e', display: 'block' }}>Challenged Action</label>
                        <select
                          value={appealChallengedAction}
                          onChange={(e) => setAppealChallengedAction(e.target.value)}
                          style={{ width: '100%', padding: '6px', backgroundColor: '#0d1117', color: '#c9d1d9', border: '1px solid #30363d', borderRadius: '4px', fontSize: '11px' }}
                        >
                          <option value="RESULT_HOLD">Result Hold</option>
                          <option value="CONFIRMED_VIOLATION">Misconduct Finding</option>
                          <option value="CANCEL_ATTEMPT">Attempt Cancellation</option>
                        </select>
                      </div>
                      <div>
                        <label style={{ fontSize: '10px', color: '#8b949e', display: 'block' }}>Evidence References (Optional)</label>
                        <input
                          type="text"
                          value={appealEvidenceRef}
                          onChange={(e) => setAppealEvidenceRef(e.target.value)}
                          placeholder="e.g. system_glitch_sensor_101"
                          style={{ width: '100%', padding: '6px', backgroundColor: '#0d1117', color: '#c9d1d9', border: '1px solid #30363d', borderRadius: '4px', fontSize: '11px' }}
                        />
                      </div>
                    </div>
                    <div style={{ marginBottom: '8px' }}>
                      <label style={{ fontSize: '10px', color: '#8b949e', display: 'block' }}>Reason for Appeal (Required)</label>
                      <textarea
                        value={appealReason}
                        onChange={(e) => setAppealReason(e.target.value)}
                        required
                        minLength={5}
                        rows={2}
                        placeholder="State your factual grounds and explanation..."
                        style={{ width: '100%', padding: '6px', backgroundColor: '#0d1117', color: '#c9d1d9', border: '1px solid #30363d', borderRadius: '4px', fontSize: '11px' }}
                      />
                    </div>
                    <button
                      type="submit"
                      style={{ padding: '6px 14px', backgroundColor: '#0284c7', color: '#fff', border: 'none', borderRadius: '4px', fontSize: '11px', fontWeight: 600, cursor: 'pointer' }}
                    >
                      Submit Appeal
                    </button>
                  </form>
                )}
              </div>

              {/* Cryptographic SHA-256 Hash Chained Audit Timeline */}
              {auditTimeline.length > 0 && (
                <div style={{ padding: '16px', backgroundColor: '#161b22', border: '1px solid #30363d', borderRadius: '6px' }}>
                  <h4 style={{ margin: '0 0 10px 0', fontSize: '13px', color: '#00e5ff' }}>
                    🔗 Immutable Audit Trail ({auditTimeline.length} events)
                  </h4>
                  <div style={{ fontSize: '11px', color: '#8b949e' }}>
                    {auditTimeline.map((item) => (
                      <div key={item.auditEventId} style={{ padding: '8px', borderBottom: '1px solid #21262d' }}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', color: '#f0f6fc' }}>
                          <span><strong>{item.actionType}</strong> by {item.actorRole} ({item.actorId})</span>
                          <span>{item.timestamp}</span>
                        </div>
                        {item.reason && <div style={{ color: '#c9d1d9', marginTop: '2px' }}>Reason: {item.reason}</div>}
                        <div style={{ color: '#00e5ff', fontSize: '10px', fontFamily: 'monospace', marginTop: '2px' }}>
                          SHA-256: {item.currentHash.slice(0, 24)}...
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
