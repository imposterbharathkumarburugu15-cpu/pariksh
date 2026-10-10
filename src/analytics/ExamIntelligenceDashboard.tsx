import React, { useState, useEffect, useMemo, useCallback } from 'react';
import '../styles/analytics.css';
import {
  OverviewAnalytics,
  ExamAnalyticsSummary,
  StudentPerformanceItem,
  QuestionAnalyticsItem,
  SectionAnalyticsItem,
  LearningGapsAnalytics,
  IntegrityAnalyticsSummary,
  LiveExamMonitorData,
  StudentExamProfile,
  OverviewAnalyticsSchema,
  ExamAnalyticsSummarySchema,
  StudentPerformanceItemSchema,
  QuestionAnalyticsItemSchema,
  SectionAnalyticsItemSchema,
  LearningGapsAnalyticsSchema,
  IntegrityAnalyticsSummarySchema,
  LiveExamMonitorDataSchema,
  StudentExamProfileSchema,
} from '../domain/analytics';

interface UserProfile {
  userId: string;
  displayName: string;
  role: string;
  instituteId: string;
}

interface ExamOption {
  examId: string;
  title: string;
  status: string;
}

interface Props {
  serverUrl: string;
  sessionToken: string | null;
  currentUser: UserProfile | null;
  onOpenIncidents?: () => void;
  onClose?: () => void;
}

export const ExamIntelligenceDashboard: React.FC<Props> = ({
  serverUrl,
  sessionToken,
  currentUser,
  onOpenIncidents,
  onClose,
}) => {
  const [activeTab, setActiveTab] = useState<
    'overview' | 'live' | 'students' | 'questions' | 'sections' | 'gaps' | 'integrity' | 'results' | 'reports'
  >('overview');

  const [examsList, setExamsList] = useState<ExamOption[]>([]);
  const [selectedExamId, setSelectedExamId] = useState<string>('');

  const [overview, setOverview] = useState<OverviewAnalytics | null>(null);
  const [examSummary, setExamSummary] = useState<ExamAnalyticsSummary | null>(null);
  const [students, setStudents] = useState<StudentPerformanceItem[]>([]);
  const [questions, setQuestions] = useState<QuestionAnalyticsItem[]>([]);
  const [sections, setSections] = useState<SectionAnalyticsItem[]>([]);
  const [learningGaps, setLearningGaps] = useState<LearningGapsAnalytics | null>(null);
  const [integrity, setIntegrity] = useState<IntegrityAnalyticsSummary | null>(null);
  const [liveMonitor, setLiveMonitor] = useState<LiveExamMonitorData | null>(null);

  const [selectedProfile, setSelectedProfile] = useState<StudentExamProfile | null>(null);
  const [loading, setLoading] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);

  // Filters & sorting for Student Table
  const [searchStudent, setSearchStudent] = useState<string>('');
  const [filterResultStatus, setFilterResultStatus] = useState<string>('ALL');
  const [sortField, setSortField] = useState<'scaledScore' | 'accuracy' | 'studentName'>('scaledScore');
  const [sortAsc, setSortAsc] = useState<boolean>(false);

  // Fetch headers helper
  const authHeaders = useMemo(() => {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (sessionToken) {
      headers['Authorization'] = `Bearer ${sessionToken}`;
    }
    return headers;
  }, [sessionToken]);

  // Load Exam List on mount
  useEffect(() => {
    const loadExams = async () => {
      try {
        const res = await fetch(`${serverUrl}/api/exams`, { headers: authHeaders });
        if (res.ok) {
          const data = await res.json();
          const items: ExamOption[] = (data.exams || data || []).map((e: any) => ({
            examId: e.examId,
            title: e.title,
            status: e.status,
          }));
          setExamsList(items);
          if (items.length > 0 && !selectedExamId) {
            setSelectedExamId(items[0]?.examId || '');
          }
        }
      } catch (err: any) {
        // Fallback or server offline
      }
    };
    loadExams();
  }, [serverUrl, authHeaders]);

  // Load Overview Data
  const loadOverview = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch(`${serverUrl}/api/analytics/overview`, { headers: authHeaders });
      if (!res.ok) {
        throw new Error(`Failed to load overview: HTTP ${res.status}`);
      }
      const data = await res.json();
      const parsed = OverviewAnalyticsSchema.parse(data);
      setOverview(parsed);
    } catch (err: any) {
      setError(err.message || 'Error loading overview');
    } finally {
      setLoading(false);
    }
  }, [serverUrl, authHeaders]);

  // Load Exam-specific analytics when selectedExamId or activeTab changes
  const loadExamAnalytics = useCallback(async () => {
    if (!selectedExamId) return;
    setLoading(true);
    setError(null);
    try {
      if (activeTab === 'overview' || activeTab === 'results') {
        const [sumRes, studRes] = await Promise.all([
          fetch(`${serverUrl}/api/analytics/exams/${selectedExamId}/summary`, { headers: authHeaders }),
          fetch(`${serverUrl}/api/analytics/exams/${selectedExamId}/students`, { headers: authHeaders }),
        ]);
        if (sumRes.ok) {
          const sumData = await sumRes.json();
          setExamSummary(ExamAnalyticsSummarySchema.parse(sumData));
        }
        if (studRes.ok) {
          const studData = await studRes.json();
          const list = Array.isArray(studData) ? studData.map((s) => StudentPerformanceItemSchema.parse(s)) : [];
          setStudents(list);
        }
      } else if (activeTab === 'students') {
        const res = await fetch(`${serverUrl}/api/analytics/exams/${selectedExamId}/students`, { headers: authHeaders });
        if (res.ok) {
          const studData = await res.json();
          const list = Array.isArray(studData) ? studData.map((s) => StudentPerformanceItemSchema.parse(s)) : [];
          setStudents(list);
        }
      } else if (activeTab === 'questions') {
        const res = await fetch(`${serverUrl}/api/analytics/exams/${selectedExamId}/questions`, { headers: authHeaders });
        if (res.ok) {
          const qData = await res.json();
          const list = Array.isArray(qData) ? qData.map((q) => QuestionAnalyticsItemSchema.parse(q)) : [];
          setQuestions(list);
        }
      } else if (activeTab === 'sections') {
        const res = await fetch(`${serverUrl}/api/analytics/exams/${selectedExamId}/sections`, { headers: authHeaders });
        if (res.ok) {
          const sData = await res.json();
          const list = Array.isArray(sData) ? sData.map((s) => SectionAnalyticsItemSchema.parse(s)) : [];
          setSections(list);
        }
      } else if (activeTab === 'gaps') {
        const res = await fetch(`${serverUrl}/api/analytics/exams/${selectedExamId}/learning-gaps`, { headers: authHeaders });
        if (res.ok) {
          const gData = await res.json();
          setLearningGaps(LearningGapsAnalyticsSchema.parse(gData));
        }
      } else if (activeTab === 'integrity') {
        const res = await fetch(`${serverUrl}/api/analytics/exams/${selectedExamId}/integrity`, { headers: authHeaders });
        if (res.ok) {
          const iData = await res.json();
          setIntegrity(IntegrityAnalyticsSummarySchema.parse(iData));
        }
      } else if (activeTab === 'live') {
        const res = await fetch(`${serverUrl}/api/analytics/live/${selectedExamId}`, { headers: authHeaders });
        if (res.ok) {
          const lData = await res.json();
          setLiveMonitor(LiveExamMonitorDataSchema.parse(lData));
        }
      }
    } catch (err: any) {
      setError(err.message || 'Error loading exam analytics');
    } finally {
      setLoading(false);
    }
  }, [serverUrl, selectedExamId, activeTab, authHeaders]);

  useEffect(() => {
    loadOverview();
  }, [loadOverview]);

  useEffect(() => {
    loadExamAnalytics();
  }, [loadExamAnalytics]);

  // Load individual student profile
  const handleOpenStudentProfile = async (studentId: string) => {
    if (!selectedExamId) return;
    try {
      const res = await fetch(`${serverUrl}/api/analytics/exams/${selectedExamId}/students/${studentId}/profile`, {
        headers: authHeaders,
      });
      if (!res.ok) {
        throw new Error(`Failed to load student profile: HTTP ${res.status}`);
      }
      const data = await res.json();
      setSelectedProfile(StudentExamProfileSchema.parse(data));
    } catch (err: any) {
      alert(`Could not load student profile: ${err.message}`);
    }
  };

  // Download CSV report helper
  const handleExportCsv = async (reportType: string) => {
    if (!selectedExamId) return;
    try {
      const res = await fetch(`${serverUrl}/api/analytics/exams/${selectedExamId}/reports/${reportType}/csv`, {
        headers: authHeaders,
      });
      if (!res.ok) {
        throw new Error(`Export failed: HTTP ${res.status}`);
      }
      const csvText = await res.text();
      const blob = new Blob([csvText], { type: 'text/csv;charset=utf-8;' });
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.setAttribute('href', url);
      link.setAttribute('download', `DOOMEDAMS_${reportType}_${selectedExamId}.csv`);
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
    } catch (err: any) {
      alert(`Export error: ${err.message}`);
    }
  };

  // Dense-rank calculation for students with tie-handling
  const sortedAndRankedStudents = useMemo(() => {
    let list = [...students];
    if (searchStudent.trim()) {
      const q = searchStudent.toLowerCase();
      list = list.filter((s) => s.studentId.toLowerCase().includes(q) || s.studentDisplayName.toLowerCase().includes(q));
    }
    if (filterResultStatus !== 'ALL') {
      list = list.filter((s) => s.resultStatus === filterResultStatus);
    }

    // Sort
    list.sort((a, b) => {
      if (sortField === 'scaledScore') {
        const aVal = a.score ?? -999999;
        const bVal = b.score ?? -999999;
        return sortAsc ? aVal - bVal : bVal - aVal;
      } else if (sortField === 'accuracy') {
        const aVal = a.accuracyPercentage ?? -1;
        const bVal = b.accuracyPercentage ?? -1;
        return sortAsc ? aVal - bVal : bVal - aVal;
      } else {
        return sortAsc
          ? a.studentDisplayName.localeCompare(b.studentDisplayName)
          : b.studentDisplayName.localeCompare(a.studentDisplayName);
      }
    });

    // Compute dense ranks for published/comparable scores
    let currentRank = 1;
    let prevScore: number | null = null;
    return list.map((item, index) => {
      if (item.score === null || item.resultStatus === 'HELD_PENDING_REVIEW' || item.resultStatus === 'PENDING_GRADING') {
        return { ...item, displayRank: 'N/A (Provisional/Held)' };
      }
      if (prevScore !== null && item.score < prevScore) {
        currentRank = index + 1;
      }
      prevScore = item.score;
      return { ...item, displayRank: `#${currentRank}` };
    });
  }, [students, searchStudent, filterResultStatus, sortField, sortAsc]);

  return (
    <div className="analytics-dashboard-root">
      {/* Top Header */}
      <header className="analytics-header">
        <div className="analytics-header-brand">
          <h1 className="analytics-title">Examination Intelligence Dashboard</h1>
          <span className="analytics-badge-node">Step 10 Authority Console</span>
          {currentUser && (
            <span style={{ fontSize: '0.8rem', color: '#94a3b8' }}>
              Institute: <strong style={{ color: '#38bdf8' }}>{currentUser.instituteId}</strong> | Operator:{' '}
              <strong style={{ color: '#f1f5f9' }}>{currentUser.displayName}</strong> ({currentUser.role})
            </span>
          )}
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '1rem' }}>
          <select
            className="analytics-exam-selector"
            value={selectedExamId}
            onChange={(e) => setSelectedExamId(e.target.value)}
          >
            {examsList.map((ex) => (
              <option key={ex.examId} value={ex.examId}>
                {ex.title} ({ex.examId}) - {ex.status}
              </option>
            ))}
          </select>

          {loading && (
            <span style={{ fontSize: '0.8rem', color: '#38bdf8', fontStyle: 'italic' }}>
              Syncing analytics...
            </span>
          )}
          <button className="dev-btn dev-btn-outline btn-xs" onClick={() => { loadOverview(); loadExamAnalytics(); }}>
            🔄 Refresh
          </button>
          {onClose && (
            <button className="dev-btn dev-btn-danger btn-xs" onClick={onClose}>
              Exit Console
            </button>
          )}
        </div>
      </header>

      {/* Tab Navigation */}
      <nav className="analytics-nav-bar">
        <button
          className={`analytics-nav-btn ${activeTab === 'overview' ? 'active' : ''}`}
          onClick={() => setActiveTab('overview')}
        >
          📊 Examination Overview
        </button>
        <button
          className={`analytics-nav-btn ${activeTab === 'live' ? 'active' : ''}`}
          onClick={() => setActiveTab('live')}
        >
          📡 Live Monitor
        </button>
        <button
          className={`analytics-nav-btn ${activeTab === 'students' ? 'active' : ''}`}
          onClick={() => setActiveTab('students')}
        >
          👥 Student Performance
        </button>
        <button
          className={`analytics-nav-btn ${activeTab === 'questions' ? 'active' : ''}`}
          onClick={() => setActiveTab('questions')}
        >
          ❓ Question Analytics
        </button>
        <button
          className={`analytics-nav-btn ${activeTab === 'sections' ? 'active' : ''}`}
          onClick={() => setActiveTab('sections')}
        >
          📑 Section Performance
        </button>
        <button
          className={`analytics-nav-btn ${activeTab === 'gaps' ? 'active' : ''}`}
          onClick={() => setActiveTab('gaps')}
        >
          💡 Learning Gaps
        </button>
        <button
          className={`analytics-nav-btn ${activeTab === 'integrity' ? 'active' : ''}`}
          onClick={() => setActiveTab('integrity')}
        >
          🚨 Integrity Summary
        </button>
        <button
          className={`analytics-nav-btn ${activeTab === 'results' ? 'active' : ''}`}
          onClick={() => setActiveTab('results')}
        >
          ⚖️ Result Management
        </button>
        <button
          className={`analytics-nav-btn ${activeTab === 'reports' ? 'active' : ''}`}
          onClick={() => setActiveTab('reports')}
        >
          📥 Reports & Exports
        </button>
      </nav>

      {/* Main Content Area */}
      <main className="analytics-container">
        {error && (
          <div className="analytics-warning-box">
            <strong>Notice:</strong> {error}
          </div>
        )}

        {/* 1. OVERVIEW TAB */}
        {activeTab === 'overview' && (
          <div>
            <div className="analytics-notice-box">
              <strong>Authoritative Node Data:</strong> Displaying aggregate metrics derived strictly from local SQLite/Room
              examiner database records. No cloud reliance, zero simulated values.
            </div>

            {overview && (
              <div className="analytics-kpi-grid">
                <div className="analytics-kpi-card">
                  <span className="analytics-kpi-label">Total Examinations</span>
                  <span className="analytics-kpi-value">{overview.totalExams}</span>
                  <span className="analytics-kpi-sub">
                    Published: {overview.publishedExams} | In Progress: {overview.inProgressExams}
                  </span>
                </div>
                <div className="analytics-kpi-card">
                  <span className="analytics-kpi-label">Assigned Students</span>
                  <span className="analytics-kpi-value">{overview.totalAssignedStudents}</span>
                  <span className="analytics-kpi-sub">Authorised cohort members</span>
                </div>
                <div className="analytics-kpi-card">
                  <span className="analytics-kpi-label">Submissions Accepted</span>
                  <span className="analytics-kpi-value" style={{ color: '#4ade80' }}>
                    {overview.acceptedSubmissions}
                  </span>
                  <span className="analytics-kpi-sub">Pending: {overview.pendingSubmissions}</span>
                </div>
                <div className="analytics-kpi-card">
                  <span className="analytics-kpi-label">Grading Completion</span>
                  <span className="analytics-kpi-value" style={{ color: '#38bdf8' }}>
                    {overview.gradingCompletionPercentage.toFixed(1)}%
                  </span>
                  <span className="analytics-kpi-sub">
                    Published: {overview.publishedResultsCount} | Held: {overview.resultsHeldCount}
                  </span>
                </div>
                <div className="analytics-kpi-card">
                  <span className="analytics-kpi-label">Outstanding Incidents</span>
                  <span
                    className="analytics-kpi-value"
                    style={{ color: overview.outstandingIncidentsCount > 0 ? '#f87171' : '#f8fafc' }}
                  >
                    {overview.outstandingIncidentsCount}
                  </span>
                  <span className="analytics-kpi-sub">Pending Appeals: {overview.pendingAppealsCount}</span>
                </div>
              </div>
            )}

            {/* Selected Exam Summary */}
            {examSummary && (
              <div className="analytics-panel">
                <div className="analytics-panel-header">
                  <h2 className="analytics-panel-title">
                    📋 Selected Examination: {examSummary.examTitle}
                  </h2>
                  <span className="badge-status badge-published">{examSummary.publishedAt ? 'PUBLISHED' : 'EVALUATED'}</span>
                </div>

                <div className="analytics-kpi-grid" style={{ marginBottom: 0 }}>
                  <div className="analytics-kpi-card">
                    <span className="analytics-kpi-label">Average Score</span>
                    <span className="analytics-kpi-value">
                      {(examSummary.meanScore / 100).toFixed(2)}
                      <small style={{ fontSize: '0.9rem', color: '#64748b' }}>
                        {' '}/ {(examSummary.maxPossibleScore / 100).toFixed(2)}
                      </small>
                    </span>
                    <span className="analytics-kpi-sub">Exact Scaled Math</span>
                  </div>
                  <div className="analytics-kpi-card">
                    <span className="analytics-kpi-label">Cohort Accuracy</span>
                    <span className="analytics-kpi-value" style={{ color: '#14b8a6' }}>
                      {examSummary.overallAccuracyRate.toFixed(1)}%
                    </span>
                    <span className="analytics-kpi-sub">Across verified submissions</span>
                  </div>
                  <div className="analytics-kpi-card">
                    <span className="analytics-kpi-label">Score Range</span>
                    <span className="analytics-kpi-value">
                      {(examSummary.highestScore / 100).toFixed(2)}
                      <small style={{ fontSize: '0.9rem', color: '#64748b' }}>
                        {' '}to {(examSummary.lowestScore / 100).toFixed(2)}
                      </small>
                    </span>
                    <span className="analytics-kpi-sub">Highest vs Lowest</span>
                  </div>
                  <div className="analytics-kpi-card">
                    <span className="analytics-kpi-label">Average Duration</span>
                    <span className="analytics-kpi-value">
                      {examSummary.averageDurationMinutes !== null
                        ? `${examSummary.averageDurationMinutes.toFixed(0)} min`
                        : 'N/A'}
                    </span>
                    <span className="analytics-kpi-sub">Wall-clock attempt time</span>
                  </div>
                </div>
              </div>
            )}
          </div>
        )}

        {/* 2. LIVE MONITOR TAB */}
        {activeTab === 'live' && (
          <div>
            <div className="analytics-notice-box">
              <strong>Operational Reality Guarantee:</strong> Live connectivity and synchronization states reflect
              verifiable local server activity. Devices are never assumed to be disconnected or cheating solely due to
              intermittent WiFi telemetry delays.
            </div>

            {liveMonitor ? (
              <div className="analytics-panel">
                <div className="analytics-panel-header">
                  <h2 className="analytics-panel-title">
                    📡 Active Session Telemetry: {liveMonitor.examTitle}
                  </h2>
                  <span style={{ fontSize: '0.8rem', color: '#94a3b8' }}>
                    Server Timestamp: {new Date(liveMonitor.serverTimestamp).toLocaleTimeString()}
                  </span>
                </div>

                <div className="analytics-kpi-grid">
                  <div className="analytics-kpi-card">
                    <span className="analytics-kpi-label">Assigned Candidates</span>
                    <span className="analytics-kpi-value">{liveMonitor.totalAssignedCandidates}</span>
                  </div>
                  <div className="analytics-kpi-card">
                    <span className="analytics-kpi-label">Attempts Started</span>
                    <span className="analytics-kpi-value" style={{ color: '#38bdf8' }}>
                      {liveMonitor.startedCount}
                    </span>
                  </div>
                  <div className="analytics-kpi-card">
                    <span className="analytics-kpi-label">Submitted & Sealed</span>
                    <span className="analytics-kpi-value" style={{ color: '#4ade80' }}>
                      {liveMonitor.submittedCount}
                    </span>
                  </div>
                  <div className="analytics-kpi-card">
                    <span className="analytics-kpi-label">In-Progress CBT</span>
                    <span className="analytics-kpi-value" style={{ color: '#facc15' }}>
                      {liveMonitor.inProgressCount}
                    </span>
                  </div>
                </div>

                <div className="analytics-table-wrap" style={{ marginTop: '1.5rem' }}>
                  <table className="analytics-table">
                    <thead>
                      <tr>
                        <th>Candidate</th>
                        <th>Device Status</th>
                        <th>Attempt State</th>
                        <th>Last Activity</th>
                        <th>Lockdown State</th>
                      </tr>
                    </thead>
                    <tbody>
                      {liveMonitor.devices.length === 0 ? (
                        <tr>
                          <td colSpan={5} style={{ textAlign: 'center', padding: '2rem', color: '#64748b' }}>
                            No active student devices currently connected to this coordinator node.
                          </td>
                        </tr>
                      ) : (
                        liveMonitor.devices.map((d) => (
                          <tr key={d.studentId}>
                            <td>
                              <strong>{d.studentDisplayName}</strong> ({d.studentId})
                            </td>
                            <td>
                              <span
                                className={`badge-status ${
                                  d.connectivityStatus === 'CONNECTED'
                                    ? 'badge-published'
                                    : d.connectivityStatus === 'LAST_KNOWN_CONNECTED'
                                    ? 'badge-withheld'
                                    : 'badge-pending'
                                }`}
                              >
                                {d.connectivityStatus}
                              </span>
                            </td>
                            <td>{d.attemptStatus}</td>
                            <td>{d.lastActivityAt ? new Date(d.lastActivityAt).toLocaleTimeString() : 'N/A'}</td>
                            <td>
                              <span style={{ color: d.lockdownActive ? '#4ade80' : '#f87171' }}>
                                {d.lockdownActive ? '🔒 Active' : '⚠️ Disabled'}
                              </span>
                            </td>
                          </tr>
                        ))
                      )}
                    </tbody>
                  </table>
                </div>
              </div>
            ) : (
              <div className="analytics-panel" style={{ textAlign: 'center', padding: '3rem' }}>
                <p>Loading live operational telemetry...</p>
              </div>
            )}
          </div>
        )}

        {/* 3. STUDENT PERFORMANCE TAB */}
        {activeTab === 'students' && (
          <div>
            <div className="analytics-notice-box">
              <strong>Dense Ranking & Scoring Policy:</strong> Ranks are calculated only for final published results with
              tied scores sharing identical ranks. Held results are never counted as zeroes and are explicitly designated.
            </div>

            <div className="analytics-panel">
              <div
                style={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'center',
                  marginBottom: '1rem',
                  gap: '1rem',
                  flexWrap: 'wrap',
                }}
              >
                <div style={{ display: 'flex', gap: '0.75rem', flex: 1, minWidth: '280px' }}>
                  <input
                    type="text"
                    className="dev-input"
                    placeholder="Search candidate by name or ID..."
                    value={searchStudent}
                    onChange={(e) => setSearchStudent(e.target.value)}
                    style={{ flex: 1 }}
                  />
                  <select
                    className="analytics-exam-selector"
                    value={filterResultStatus}
                    onChange={(e) => setFilterResultStatus(e.target.value)}
                  >
                    <option value="ALL">All Result Statuses</option>
                    <option value="PUBLISHED">Published Only</option>
                    <option value="HELD_PENDING_REVIEW">Held Pending Review</option>
                    <option value="WITHHELD">Withheld</option>
                    <option value="PENDING_GRADING">Pending Grading</option>
                  </select>
                </div>

                <div style={{ display: 'flex', gap: '0.5rem' }}>
                  <button
                    className={`dev-btn btn-xs ${sortField === 'scaledScore' ? 'dev-btn-accent' : 'dev-btn-outline'}`}
                    onClick={() => {
                      if (sortField === 'scaledScore') setSortAsc(!sortAsc);
                      else { setSortField('scaledScore'); setSortAsc(false); }
                    }}
                  >
                    Score {sortField === 'scaledScore' ? (sortAsc ? '▲' : '▼') : ''}
                  </button>
                  <button
                    className={`dev-btn btn-xs ${sortField === 'accuracy' ? 'dev-btn-accent' : 'dev-btn-outline'}`}
                    onClick={() => {
                      if (sortField === 'accuracy') setSortAsc(!sortAsc);
                      else { setSortField('accuracy'); setSortAsc(false); }
                    }}
                  >
                    Accuracy {sortField === 'accuracy' ? (sortAsc ? '▲' : '▼') : ''}
                  </button>
                </div>
              </div>

              <div className="analytics-table-wrap">
                <table className="analytics-table">
                  <thead>
                    <tr>
                      <th>Rank</th>
                      <th>Student</th>
                      <th>Attempt Status</th>
                      <th>Score</th>
                      <th>Accuracy</th>
                      <th>Correct / Wrong</th>
                      <th>Unanswered</th>
                      <th>Result State</th>
                      <th>Incident Flag</th>
                      <th>Dossier</th>
                    </tr>
                  </thead>
                  <tbody>
                    {sortedAndRankedStudents.length === 0 ? (
                      <tr>
                        <td colSpan={10} style={{ textAlign: 'center', padding: '2.5rem', color: '#64748b' }}>
                          No student records match the selected filters.
                        </td>
                      </tr>
                    ) : (
                      sortedAndRankedStudents.map((s) => (
                        <tr key={s.studentId}>
                          <td>
                            <strong>{s.displayRank}</strong>
                          </td>
                          <td>
                            <strong>{s.studentDisplayName}</strong>
                            <div style={{ fontSize: '0.75rem', color: '#64748b' }}>{s.studentId}</div>
                          </td>
                          <td>{s.attemptStatus}</td>
                          <td>
                            {s.score !== null ? (
                              <span style={{ fontWeight: 600, color: s.score >= 0 ? '#f8fafc' : '#f87171' }}>
                                {(s.score / 100).toFixed(2)}
                                <small style={{ color: '#64748b' }}> / {s.maxPossibleMarks !== null ? (s.maxPossibleMarks / 100).toFixed(2) : '0.00'}</small>
                              </span>
                            ) : (
                              <span style={{ color: '#94a3b8' }}>Pending</span>
                            )}
                          </td>
                          <td>
                            {s.accuracyPercentage !== null ? (
                              <span style={{ color: '#14b8a6', fontWeight: 600 }}>
                                {s.accuracyPercentage.toFixed(1)}%
                              </span>
                            ) : (
                              'N/A'
                            )}
                          </td>
                          <td>
                            <span style={{ color: '#4ade80' }}>{s.correctCount ?? 0}</span> /{' '}
                            <span style={{ color: '#f87171' }}>{s.incorrectCount ?? 0}</span>
                          </td>
                          <td>{s.unansweredCount ?? 0}</td>
                          <td>
                            <span
                              className={`badge-status ${
                                s.resultStatus === 'PUBLISHED'
                                  ? 'badge-published'
                                  : s.resultStatus === 'HELD_PENDING_REVIEW'
                                  ? 'badge-held'
                                  : s.resultStatus === 'WITHHELD'
                                  ? 'badge-withheld'
                                  : 'badge-pending'
                              }`}
                            >
                              {s.resultStatus}
                            </span>
                          </td>
                          <td>
                            {s.incidentCount > 0 ? (
                              <span style={{ color: '#f87171', fontWeight: 600 }}>⚠️ Under Review</span>
                            ) : (
                              <span style={{ color: '#64748b' }}>Clean</span>
                            )}
                          </td>
                          <td>
                            <button
                              className="dev-btn dev-btn-outline btn-xs"
                              onClick={() => handleOpenStudentProfile(s.studentId)}
                            >
                              🔍 View Dossier
                            </button>
                          </td>
                        </tr>
                      ))
                    )}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        )}

        {/* 4. QUESTION ANALYTICS TAB */}
        {activeTab === 'questions' && (
          <div>
            <div className="analytics-warning-box">
              <strong>Telemetry Disclosure:</strong> DOOMEDAMS CBT client records answer responses, revision sequences, and
              submission receipts. Fine-grained per-question millisecond stopwatches are not recorded on student devices to
              prevent battery throttling and timer desynchronization. Duration is displayed as{' '}
              <code>TIME DATA UNAVAILABLE</code>.
            </div>

            <div className="analytics-panel">
              <div className="analytics-panel-header">
                <h2 className="analytics-panel-title">❓ Item Response & Question-Level Diagnostics</h2>
                <span style={{ fontSize: '0.8rem', color: '#94a3b8' }}>
                  Total Questions: {questions.length}
                </span>
              </div>

              <div className="analytics-table-wrap">
                <table className="analytics-table">
                  <thead>
                    <tr>
                      <th>Q#</th>
                      <th>Section</th>
                      <th>Type</th>
                      <th>Max Marks</th>
                      <th>Eligible Candidates</th>
                      <th>Attempt Rate</th>
                      <th>Correctness Rate</th>
                      <th>Avg Marks</th>
                      <th>Recorded Timing</th>
                    </tr>
                  </thead>
                  <tbody>
                    {questions.length === 0 ? (
                      <tr>
                        <td colSpan={9} style={{ textAlign: 'center', padding: '2.5rem', color: '#64748b' }}>
                          No question records found for this examination.
                        </td>
                      </tr>
                    ) : (
                      questions.map((q) => (
                        <tr key={q.questionId}>
                          <td>
                            <strong>Q{q.questionNumber}</strong>
                          </td>
                          <td>{q.sectionTitle}</td>
                          <td>{q.questionType}</td>
                          <td>{(q.maxMarks / 100).toFixed(2)}</td>
                          <td>{q.eligibleCandidatesDenominator}</td>
                          <td>
                            <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                              <span>{q.attemptRate.toFixed(1)}%</span>
                              <div className="bar-track" style={{ width: '60px' }}>
                                <div className="bar-fill bar-fill-cyan" style={{ width: `${q.attemptRate}%` }} />
                              </div>
                            </div>
                          </td>
                          <td>
                            <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                              <span style={{ color: q.correctnessRate < 50 ? '#f87171' : '#4ade80' }}>
                                {q.correctnessRate.toFixed(1)}%
                              </span>
                              <div className="bar-track" style={{ width: '60px' }}>
                                <div
                                  className={`bar-fill ${q.correctnessRate < 50 ? 'bar-fill-red' : 'bar-fill-teal'}`}
                                  style={{ width: `${q.correctnessRate}%` }}
                                />
                              </div>
                            </div>
                          </td>
                          <td>{(q.averageMarksAwarded / 100).toFixed(2)}</td>
                          <td>
                            <span style={{ fontSize: '0.75rem', color: '#94a3b8', fontStyle: 'italic' }}>
                              {q.recordedTimeNotice}
                            </span>
                          </td>
                        </tr>
                      ))
                    )}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        )}

        {/* 5. SECTION PERFORMANCE TAB */}
        {activeTab === 'sections' && (
          <div>
            <div className="analytics-panel">
              <div className="analytics-panel-header">
                <h2 className="analytics-panel-title">📑 Section-by-Section Performance Breakdown</h2>
              </div>

              <div className="analytics-table-wrap">
                <table className="analytics-table">
                  <thead>
                    <tr>
                      <th>Order</th>
                      <th>Section Title</th>
                      <th>Questions</th>
                      <th>Total Marks</th>
                      <th>Average Score</th>
                      <th>Accuracy</th>
                      <th>Attempt Rate</th>
                      <th>Score Range (High / Low)</th>
                    </tr>
                  </thead>
                  <tbody>
                    {sections.length === 0 ? (
                      <tr>
                        <td colSpan={8} style={{ textAlign: 'center', padding: '2.5rem', color: '#64748b' }}>
                          No section records available.
                        </td>
                      </tr>
                    ) : (
                      sections.map((sec) => (
                        <tr key={sec.sectionId}>
                          <td>#{sec.sectionOrder}</td>
                          <td>
                            <strong>{sec.title}</strong>
                          </td>
                          <td>{sec.questionCount}</td>
                          <td>{(sec.totalMarks / 100).toFixed(2)}</td>
                          <td>{(sec.averageScore / 100).toFixed(2)}</td>
                          <td>
                            <span style={{ color: '#14b8a6', fontWeight: 600 }}>
                              {sec.accuracyPercentage.toFixed(1)}%
                            </span>
                          </td>
                          <td>{sec.attemptRate.toFixed(1)}%</td>
                          <td>
                            {(sec.highestScore / 100).toFixed(2)} / {(sec.lowestScore / 100).toFixed(2)}
                          </td>
                        </tr>
                      ))
                    )}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        )}

        {/* 6. LEARNING GAPS TAB */}
        {activeTab === 'gaps' && (
          <div>
            {learningGaps && (
              <div>
                <div className="analytics-warning-box">
                  <strong>Tagging Disclosure:</strong> {learningGaps.disclosureNotice}
                </div>

                <div className="analytics-panel">
                  <div className="analytics-panel-header">
                    <h2 className="analytics-panel-title">⚠️ Identified Difficulties & Recommendations</h2>
                  </div>

                  <div style={{ marginBottom: '1.5rem' }}>
                    <h3 style={{ fontSize: '1rem', color: '#f8fafc', marginBottom: '0.75rem' }}>
                      Questions with High Incorrect Response Rates (&gt; 40%)
                    </h3>
                    <div className="analytics-table-wrap">
                      <table className="analytics-table">
                        <thead>
                          <tr>
                            <th>Question</th>
                            <th>Section</th>
                            <th>Type</th>
                            <th>Correctness Rate</th>
                            <th>Incorrect Rate</th>
                          </tr>
                        </thead>
                        <tbody>
                          {learningGaps.highIncorrectQuestions.length === 0 ? (
                            <tr>
                              <td colSpan={5} style={{ textAlign: 'center', color: '#64748b', padding: '1.5rem' }}>
                                No questions exceeded the 40% error threshold.
                              </td>
                            </tr>
                          ) : (
                            learningGaps.highIncorrectQuestions.map((q) => (
                              <tr key={q.questionNumber}>
                                <td>
                                  <strong>Question #{q.questionNumber}</strong>
                                </td>
                                <td>{q.sectionTitle}</td>
                                <td>{q.questionType}</td>
                                <td style={{ color: '#4ade80' }}>{q.correctnessRate.toFixed(1)}%</td>
                                <td style={{ color: '#f87171', fontWeight: 600 }}>{q.incorrectRate.toFixed(1)}%</td>
                              </tr>
                            ))
                          )}
                        </tbody>
                      </table>
                    </div>
                  </div>

                  <div>
                    <h3 style={{ fontSize: '1rem', color: '#f8fafc', marginBottom: '0.75rem' }}>
                      Data-Traceable Pedagogical Recommendations
                    </h3>
                    <ul style={{ paddingLeft: '1.25rem', color: '#cbd5e1', lineHeight: '1.6' }}>
                      {learningGaps.explainableRecommendations.map((rec, idx) => (
                        <li key={idx} style={{ marginBottom: '0.5rem' }}>
                          {rec}
                        </li>
                      ))}
                    </ul>
                  </div>
                </div>
              </div>
            )}
          </div>
        )}

        {/* 7. INTEGRITY SUMMARY TAB */}
        {activeTab === 'integrity' && (
          <div>
            <div className="analytics-notice-box">
              <strong>Event vs. Finding Separation:</strong> Recorded telemetry events (e.g. background switch) are not
              automatically counted as confirmed cheating. Official misconduct requires an explicit administrative review
              decision.
            </div>

            {integrity && (
              <div className="analytics-panel">
                <div className="analytics-panel-header">
                  <h2 className="analytics-panel-title">🚨 Integrity Telemetry & Incident Review Aggregate</h2>
                  {onOpenIncidents && (
                    <button className="dev-btn dev-btn-danger btn-xs" onClick={onOpenIncidents}>
                      ⚖️ Open Incident Review Dashboard
                    </button>
                  )}
                </div>

                <div className="analytics-kpi-grid">
                  <div className="analytics-kpi-card">
                    <span className="analytics-kpi-label">Total Integrity Events</span>
                    <span className="analytics-kpi-value">{integrity.totalEvents}</span>
                    <span className="analytics-kpi-sub">
                      Unique Attempts Flagged: {integrity.uniqueFlaggedAttempts}
                    </span>
                  </div>
                  <div className="analytics-kpi-card">
                    <span className="analytics-kpi-label">App Switch Events</span>
                    <span className="analytics-kpi-value">
                      {integrity.totalEvents - integrity.appNameUnavailableCount}
                      <small style={{ fontSize: '0.8rem', color: '#64748b' }}>
                        {' '}(Unavail: {integrity.appNameUnavailableCount})
                      </small>
                    </span>
                    <span className="analytics-kpi-sub">Verified package identification</span>
                  </div>
                  <div className="analytics-kpi-card">
                    <span className="analytics-kpi-label">Formal Incident Cases</span>
                    <span className="analytics-kpi-value">{integrity.totalIncidentCases}</span>
                    <span className="analytics-kpi-sub">
                      Pending: {integrity.pendingReviewIncidentsCount} | Confirmed: {integrity.confirmedMisconductCount}
                    </span>
                  </div>
                  <div className="analytics-kpi-card">
                    <span className="analytics-kpi-label">Result Holds</span>
                    <span className="analytics-kpi-value" style={{ color: '#f87171' }}>
                      {integrity.heldResultsCount}
                    </span>
                    <span className="analytics-kpi-sub">Pending Appeals: {integrity.pendingAppealsCount}</span>
                  </div>
                </div>
              </div>
            )}
          </div>
        )}

        {/* 8. RESULT MANAGEMENT TAB */}
        {activeTab === 'results' && (
          <div>
            <div className="analytics-panel">
              <div className="analytics-panel-header">
                <h2 className="analytics-panel-title">⚖️ Result Publication & Release State</h2>
              </div>

              <div className="analytics-table-wrap">
                <table className="analytics-table">
                  <thead>
                    <tr>
                      <th>Candidate</th>
                      <th>Attempt ID</th>
                      <th>Score</th>
                      <th>Publication State</th>
                      <th>Hold Reason</th>
                      <th>Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {students.map((st) => (
                      <tr key={st.studentId}>
                        <td>
                          <strong>{st.studentDisplayName}</strong> ({st.studentId})
                        </td>
                        <td>{st.attemptId}</td>
                        <td>
                          {st.score !== null ? `${(st.score / 100).toFixed(2)} marks` : 'Pending'}
                        </td>
                        <td>
                          <span
                            className={`badge-status ${
                              st.resultStatus === 'PUBLISHED'
                                ? 'badge-published'
                                : st.resultStatus === 'HELD_PENDING_REVIEW'
                                ? 'badge-held'
                                : 'badge-pending'
                            }`}
                          >
                            {st.resultStatus}
                          </span>
                        </td>
                        <td>{st.resultStatus === 'HELD_PENDING_REVIEW' ? 'Proctor review active' : 'None'}</td>
                        <td>
                          <button
                            className="dev-btn dev-btn-outline btn-xs"
                            onClick={() => handleOpenStudentProfile(st.studentId)}
                          >
                            Inspect Audit Record
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        )}

        {/* 9. REPORTS & EXPORTS TAB */}
        {activeTab === 'reports' && (
          <div>
            <div className="analytics-notice-box">
              <strong>Offline RFC 4180 CSV Reports:</strong> All tabular reports are generated deterministically directly
              from the local coordinator Android SQLite database with strict field escaping and RBAC enforcement.
            </div>

            <div className="analytics-panel">
              <div className="analytics-panel-header">
                <h2 className="analytics-panel-title">📥 Authoritative Examination Report Generators</h2>
                <button className="dev-btn dev-btn-outline btn-xs" onClick={() => window.print()}>
                  🖨️ Print View
                </button>
              </div>

              <div className="analytics-kpi-grid">
                <div className="analytics-kpi-card">
                  <span className="analytics-kpi-label">Summary Report</span>
                  <p style={{ fontSize: '0.8rem', color: '#94a3b8', margin: '0.5rem 0' }}>
                    Cohort-level statistics, averages, and pass/fail metrics.
                  </p>
                  <button className="dev-btn dev-btn-accent btn-xs" onClick={() => handleExportCsv('summary')}>
                    Download Summary CSV
                  </button>
                </div>

                <div className="analytics-kpi-card">
                  <span className="analytics-kpi-label">Student Performance</span>
                  <p style={{ fontSize: '0.8rem', color: '#94a3b8', margin: '0.5rem 0' }}>
                    Full roster scores, accuracy %, publication state, and hold flags.
                  </p>
                  <button className="dev-btn dev-btn-accent btn-xs" onClick={() => handleExportCsv('students')}>
                    Download Student CSV
                  </button>
                </div>

                <div className="analytics-kpi-card">
                  <span className="analytics-kpi-label">Question Item Analysis</span>
                  <p style={{ fontSize: '0.8rem', color: '#94a3b8', margin: '0.5rem 0' }}>
                    Item-level correctness rates, attempt counts, and difficulty tiers.
                  </p>
                  <button className="dev-btn dev-btn-accent btn-xs" onClick={() => handleExportCsv('questions')}>
                    Download Questions CSV
                  </button>
                </div>

                <div className="analytics-kpi-card">
                  <span className="analytics-kpi-label">Section Analytics</span>
                  <p style={{ fontSize: '0.8rem', color: '#94a3b8', margin: '0.5rem 0' }}>
                    Aggregates grouped by paper section and order.
                  </p>
                  <button className="dev-btn dev-btn-accent btn-xs" onClick={() => handleExportCsv('sections')}>
                    Download Sections CSV
                  </button>
                </div>

                <div className="analytics-kpi-card">
                  <span className="analytics-kpi-label">Integrity & Incidents</span>
                  <p style={{ fontSize: '0.8rem', color: '#94a3b8', margin: '0.5rem 0' }}>
                    Complete audit log of lock switches, incidents, and appeal counts.
                  </p>
                  <button className="dev-btn dev-btn-accent btn-xs" onClick={() => handleExportCsv('integrity')}>
                    Download Integrity CSV
                  </button>
                </div>
              </div>
            </div>
          </div>
        )}
      </main>

      {/* Individual Student Examination Dossier Modal */}
      {selectedProfile && (
        <div className="analytics-modal-backdrop" onClick={() => setSelectedProfile(null)}>
          <div className="analytics-modal-content" onClick={(e) => e.stopPropagation()}>
            <div className="analytics-modal-header">
              <div>
                <h2 style={{ margin: 0, fontSize: '1.2rem', color: '#f8fafc' }}>
                  Candidate Examination Dossier: {selectedProfile.studentDisplayName}
                </h2>
                <span style={{ fontSize: '0.75rem', color: '#94a3b8' }}>
                  Student ID: {selectedProfile.studentId} | Attempt: {selectedProfile.attemptId}
                </span>
              </div>
              <button
                style={{ background: 'none', border: 'none', color: '#94a3b8', fontSize: '1.2rem', cursor: 'pointer' }}
                onClick={() => setSelectedProfile(null)}
              >
                ✕
              </button>
            </div>

            <div className="analytics-modal-body">
              {/* Metric summary */}
              <div className="analytics-kpi-grid" style={{ marginBottom: '1.25rem' }}>
                <div className="analytics-kpi-card">
                  <span className="analytics-kpi-label">Score</span>
                  <span className="analytics-kpi-value">
                    {selectedProfile.totalScaledScore !== null
                      ? (selectedProfile.totalScaledScore / 100).toFixed(2)
                      : 'N/A'}
                    <small style={{ fontSize: '0.8rem', color: '#64748b' }}>
                      {' '}
                      / {selectedProfile.maxPossibleScore !== null ? (selectedProfile.maxPossibleScore / 100).toFixed(2) : ''}
                    </small>
                  </span>
                </div>
                <div className="analytics-kpi-card">
                  <span className="analytics-kpi-label">Accuracy</span>
                  <span className="analytics-kpi-value" style={{ color: '#14b8a6' }}>
                    {selectedProfile.accuracyPercentage !== null
                      ? `${selectedProfile.accuracyPercentage.toFixed(1)}%`
                      : 'N/A'}
                  </span>
                </div>
                <div className="analytics-kpi-card">
                  <span className="analytics-kpi-label">Local Autosaves</span>
                  <span className="analytics-kpi-value">{selectedProfile.localSaveCount}</span>
                </div>
                <div className="analytics-kpi-card">
                  <span className="analytics-kpi-label">Result State</span>
                  <span className="analytics-kpi-value" style={{ fontSize: '1.1rem' }}>
                    {selectedProfile.resultStatus}
                  </span>
                </div>
              </div>

              {selectedProfile.holdReason && (
                <div className="analytics-warning-box">
                  <strong>Administrative Hold Reason:</strong> {selectedProfile.holdReason}
                </div>
              )}

              {/* Submission Receipt */}
              <div style={{ marginBottom: '1.25rem', fontSize: '0.8rem', color: '#94a3b8' }}>
                Receipt Fingerprint: <code>{selectedProfile.submissionReceiptHash || 'UNSEALED'}</code> | Duration:{' '}
                {selectedProfile.durationMinutes !== null ? `${selectedProfile.durationMinutes} min` : 'In Progress'}
              </div>

              {/* Question breakdown table */}
              <h3 style={{ fontSize: '0.95rem', color: '#f8fafc', marginBottom: '0.5rem' }}>
                Item-by-Item Candidate Response History
              </h3>
              <div className="analytics-table-wrap">
                <table className="analytics-table">
                  <thead>
                    <tr>
                      <th>Q#</th>
                      <th>Section</th>
                      <th>Candidate Answer</th>
                      <th>Evaluation</th>
                      <th>Marks Awarded</th>
                      <th>Applied Rule</th>
                    </tr>
                  </thead>
                  <tbody>
                    {selectedProfile.questions.map((q) => (
                      <tr key={q.questionId}>
                        <td>
                          <strong>Q{q.questionNumber}</strong>
                        </td>
                        <td>{q.sectionTitle}</td>
                        <td>
                          <code>{q.submittedAnswer || '(Unanswered)'}</code>
                        </td>
                        <td>
                          <span
                            className={`badge-status ${
                              q.answerClassification === 'CORRECT'
                                ? 'badge-published'
                                : q.answerClassification === 'INCORRECT'
                                ? 'badge-held'
                                : 'badge-pending'
                            }`}
                          >
                            {q.answerClassification || 'UNANSWERED'}
                          </span>
                        </td>
                        <td>
                          {q.marksAwarded !== null
                            ? (q.marksAwarded / 100).toFixed(2)
                            : '0.00'}
                        </td>
                        <td>{q.explanationCode || 'NONE'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>

            <div className="analytics-modal-footer">
              <button className="dev-btn dev-btn-outline btn-xs" onClick={() => setSelectedProfile(null)}>
                Close Dossier
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
