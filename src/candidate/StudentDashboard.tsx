import React, { useState, useEffect } from 'react';
import {
  StudentExamSummary,
  CandidateExamPackage,
  CandidateAttemptSession
} from '../domain/candidate';
import { CandidateExamView } from './CandidateExamView';
import { StudentResultView } from './StudentResultView';
import { IntegrityReadinessCheck } from './IntegrityReadinessCheck';

interface StudentDashboardProps {
  currentUser: {
    userId: string;
    displayName: string;
    role: string;
    instituteId: string;
  };
  serverUrl: string;
  sessionToken: string;
  onLogout: () => void;
}

export const StudentDashboard: React.FC<StudentDashboardProps> = ({
  currentUser,
  serverUrl,
  sessionToken,
  onLogout
}) => {
  const [exams, setExams] = useState<StudentExamSummary[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  // Active examination state
  const [activeExamPackage, setActiveExamPackage] = useState<CandidateExamPackage | null>(null);
  const [activeAttemptSession, setActiveAttemptSession] = useState<CandidateAttemptSession | null>(null);

  // Instructions Modal state
  const [instructionExam, setInstructionExam] = useState<StudentExamSummary | null>(null);
  const [instructionPackage, setInstructionPackage] = useState<CandidateExamPackage | null>(null);
  const [isStartingAttempt, setIsStartingAttempt] = useState<boolean>(false);
  const [startError, setStartError] = useState<string | null>(null);
  const [isReadinessOpen, setIsReadinessOpen] = useState<boolean>(false);

  // Result Viewing state
  const [viewingResultAttemptId, setViewingResultAttemptId] = useState<string | null>(null);
  const [viewingResultExamTitle, setViewingResultExamTitle] = useState<string>('');

  const fetchAssignedExams = async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch(`${serverUrl}/api/student/exams`, {
        headers: { Authorization: `Bearer ${sessionToken}` }
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error(err.message || `HTTP ${res.status}`);
      }
      const data: StudentExamSummary[] = await res.json();
      setExams(data);
    } catch (err: any) {
      setError(err.message || 'Failed to fetch assigned examinations');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchAssignedExams();
  }, [serverUrl, sessionToken]);

  // Open instructions modal & download candidate package
  const openInstructionsModal = async (exam: StudentExamSummary) => {
    setInstructionExam(exam);
    setInstructionPackage(null);
    setStartError(null);

    try {
      const res = await fetch(`${serverUrl}/api/exams/${exam.examId}/candidate-package`, {
        headers: { Authorization: `Bearer ${sessionToken}` }
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error(err.message || 'Failed to download exam package');
      }
      const pkg: CandidateExamPackage = await res.json();
      setInstructionPackage(pkg);
    } catch (err: any) {
      setStartError(err.message || 'Unable to download candidate examination package');
    }
  };

  // Authoritatively start or resume examination attempt
  const handleStartAttempt = async () => {
    if (!instructionExam || !instructionPackage) return;
    setIsStartingAttempt(true);
    setStartError(null);

    try {
      const res = await fetch(`${serverUrl}/api/exams/${instructionExam.examId}/attempts/start`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${sessionToken}`
        }
      });

      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error(err.message || `HTTP ${res.status}`);
      }

      const sessionData: CandidateAttemptSession = await res.json();

      setActiveExamPackage(instructionPackage);
      setActiveAttemptSession(sessionData);
      setInstructionExam(null);
      setInstructionPackage(null);
    } catch (err: any) {
      setStartError(err.message || 'Failed to create or resume attempt on server');
    } finally {
      setIsStartingAttempt(false);
    }
  };

  // If device readiness check is open, show pre-exam integrity inspection
  if (isReadinessOpen && instructionPackage) {
    return (
      <IntegrityReadinessCheck
        pkg={instructionPackage}
        serverOnline={navigator.onLine}
        onReadyToStart={() => {
          setIsReadinessOpen(false);
          handleStartAttempt();
        }}
        onCancel={() => {
          setIsReadinessOpen(false);
        }}
      />
    );
  }

  // If in an active exam session, show the CBT interface
  if (activeExamPackage && activeAttemptSession) {
    return (
      <CandidateExamView
        examPackage={activeExamPackage}
        session={activeAttemptSession}
        serverUrl={serverUrl}
        authToken={sessionToken}
        onExit={() => {
          setActiveExamPackage(null);
          setActiveAttemptSession(null);
          fetchAssignedExams();
        }}
      />
    );
  }

  // If viewing a published result, show the Result View
  if (viewingResultAttemptId) {
    return (
      <StudentResultView
        attemptId={viewingResultAttemptId}
        examTitle={viewingResultExamTitle}
        serverUrl={serverUrl}
        authToken={sessionToken}
        onBack={() => {
          setViewingResultAttemptId(null);
          fetchAssignedExams();
        }}
      />
    );
  }

  return (
    <div className="student-dashboard-root">
      {/* 1. TOP HEADER */}
      <header className="student-dash-header">
        <div>
          <h1 style={{ margin: 0, fontSize: '1.4rem', color: '#f8fafc' }}>Candidate Portal</h1>
          <p style={{ margin: '0.2rem 0 0 0', color: '#94a3b8', fontSize: '0.85rem' }}>
            Welcome back, <strong style={{ color: '#38bdf8' }}>{currentUser.displayName}</strong> ({currentUser.userId}) • Institute: <code>{currentUser.instituteId}</code>
          </p>
        </div>

        <div style={{ display: 'flex', gap: '0.75rem', alignItems: 'center' }}>
          <button type="button" className="exam-btn exam-btn-outline" onClick={fetchAssignedExams}>
            ↻ Refresh
          </button>
          <button type="button" className="exam-btn exam-btn-danger" onClick={onLogout}>
            Sign Out
          </button>
        </div>
      </header>

      {/* 2. MAIN DASHBOARD CONTENT */}
      <main className="student-dash-content">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
          <h2 style={{ fontSize: '1.2rem', color: '#f1f5f9', margin: 0 }}>Assigned Examinations</h2>
          <span style={{ fontSize: '0.85rem', color: '#94a3b8' }}>
            Local Examiner Node: <code>{serverUrl}</code>
          </span>
        </div>

        {loading && (
          <div className="student-dash-empty-card">
            <p style={{ color: '#94a3b8' }}>Loading assigned examinations...</p>
          </div>
        )}

        {error && (
          <div className="exam-alert exam-alert-danger" style={{ marginBottom: '1.5rem' }}>
            {error}
          </div>
        )}

        {!loading && !error && exams.length === 0 && (
          <div className="student-dash-empty-card">
            <div style={{ fontSize: '2rem', marginBottom: '0.5rem' }}>📋</div>
            <h3 style={{ margin: 0, color: '#f8fafc' }}>No Examinations Assigned</h3>
            <p style={{ color: '#94a3b8', fontSize: '0.9rem', maxWidth: '400px', margin: '0.5rem auto 0 auto' }}>
              There are currently no published examinations assigned to your batch or institute. Please contact your invigilator if you believe this is an error.
            </p>
          </div>
        )}

        <div className="student-exam-grid">
          {exams.map((exam) => {
            const hasAttempt = !!exam.attempt;
            const attemptStatus = exam.attempt?.attemptStatus;
            const isSubmitted = attemptStatus === 'SUBMITTED';
            const isInProgress = attemptStatus === 'IN_PROGRESS';
            const durationMins = Math.round(exam.durationSeconds / 60);

            return (
              <div key={exam.examId} className="student-exam-card">
                <div className="student-exam-card-header">
                  <div>
                    <h3 className="student-exam-title">{exam.title}</h3>
                    <span className="student-exam-meta">
                      ID: <code>{exam.examId}</code> • {exam.totalQuestions} Questions • {exam.totalSections} Sections
                    </span>
                  </div>

                  <div>
                    {isInProgress && (
                      <span className="exam-badge exam-badge-warning">In Progress</span>
                    )}
                    {isSubmitted && !exam.hasPublishedResult && !exam.isResultHeld && (
                      <span className="exam-badge exam-badge-info">Submitted</span>
                    )}
                    {exam.isResultHeld && (
                      <span className="exam-badge exam-badge-danger">Result Held Under Review</span>
                    )}
                    {exam.hasPublishedResult && (
                      <span className="exam-badge exam-badge-success">Result Available</span>
                    )}
                    {!hasAttempt && (
                      <span className="exam-badge exam-badge-primary">Ready to Start</span>
                    )}
                  </div>
                </div>

                <div className="student-exam-card-body">
                  <div className="student-exam-info-grid">
                    <div className="student-info-item">
                      <span className="student-info-label">Duration</span>
                      <span className="student-info-val">{durationMins} Minutes</span>
                    </div>
                    <div className="student-info-item">
                      <span className="student-info-label">Marking Version</span>
                      <span className="student-info-val">v{exam.markingSchemeVersion}</span>
                    </div>
                    <div className="student-info-item">
                      <span className="student-info-label">Published</span>
                      <span className="student-info-val">
                        {exam.publishedAt ? new Date(exam.publishedAt).toLocaleDateString() : 'Active'}
                      </span>
                    </div>
                  </div>

                  {exam.isResultHeld && (
                    <div className="exam-notice-box" style={{ marginTop: '0.75rem', padding: '0.75rem', background: '#450a0a', border: '1px solid #b91c1c', borderRadius: '6px' }}>
                      <p style={{ margin: 0, fontSize: '0.8rem', color: '#fca5a5' }}>
                        This attempt result has been flagged for administrative review by the examination coordinator. Scoring details will become available once the review is finalized.
                      </p>
                    </div>
                  )}
                </div>

                <div className="student-exam-card-footer">
                  {!hasAttempt && (
                    <button
                      type="button"
                      className="exam-btn exam-btn-primary"
                      onClick={() => openInstructionsModal(exam)}
                    >
                      View Instructions & Start Test
                    </button>
                  )}

                  {isInProgress && (
                    <button
                      type="button"
                      className="exam-btn exam-btn-warning"
                      onClick={() => openInstructionsModal(exam)}
                    >
                      Resume Examination Attempt →
                    </button>
                  )}

                  {isSubmitted && !exam.hasPublishedResult && !exam.isResultHeld && (
                    <span style={{ fontSize: '0.85rem', color: '#94a3b8' }}>
                      ⏳ Submitted. Results pending official publication.
                    </span>
                  )}

                  {exam.hasPublishedResult && exam.attempt && (
                    <button
                      type="button"
                      className="exam-btn exam-btn-success"
                      onClick={() => {
                        setViewingResultAttemptId(exam.attempt!.attemptId);
                        setViewingResultExamTitle(exam.title);
                      }}
                    >
                      View Official Results & Scores →
                    </button>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      </main>

      {/* 3. INSTRUCTIONS & ATTEMPT START MODAL */}
      {instructionExam && (
        <div className="exam-modal-backdrop" role="dialog" aria-modal="true" aria-labelledby="inst-title">
          <div className="exam-modal-card" style={{ maxWidth: '640px' }}>
            <div className="exam-modal-header">
              <h2 id="inst-title" style={{ margin: 0, fontSize: '1.25rem', color: '#f8fafc' }}>
                Examination Instructions: {instructionExam.title}
              </h2>
              <button
                type="button"
                className="exam-btn-close"
                onClick={() => setInstructionExam(null)}
              >
                ✕
              </button>
            </div>

            <div style={{ marginTop: '1rem', maxHeight: '420px', overflowY: 'auto', paddingRight: '0.5rem' }}>
              <div className="exam-instructions-list">
                <div className="exam-inst-item">
                  <strong>Official Duration:</strong> You have{' '}
                  <strong style={{ color: '#38bdf8' }}>
                    {Math.round(instructionExam.durationSeconds / 60)} minutes
                  </strong>{' '}
                  to complete this examination.
                </div>

                <div className="exam-inst-item">
                  <strong>Total Structure:</strong> This paper contains{' '}
                  <strong>{instructionExam.totalQuestions} questions</strong> across{' '}
                  <strong>{instructionExam.totalSections} section(s)</strong>.
                </div>

                {instructionPackage?.markingSummary && (
                  <div className="exam-inst-item">
                    <strong>Marking Scheme:</strong>
                    <ul style={{ margin: '0.25rem 0 0 1.25rem', padding: 0 }}>
                      <li>Correct response: <strong>+{instructionPackage.markingSummary.correctScaledMark / 100} Marks</strong></li>
                      <li>
                        Incorrect response:{' '}
                        <strong>
                          {instructionPackage.markingSummary.allowNegativeMarking
                            ? `${instructionPackage.markingSummary.incorrectScaledMark / 100} Marks (Negative Marking Applies)`
                            : '0 Marks (No Negative Marking)'}
                        </strong>
                      </li>
                      <li>Unanswered questions: <strong>0 Marks</strong></li>
                    </ul>
                  </div>
                )}

                <div className="exam-inst-item">
                  <strong>Offline Continuity & Autosave:</strong> All answers are automatically stored in durable local SQLite (Room) storage on your device. Temporary loss of local network connectivity will not interrupt your test or erase your work.
                </div>

                <div className="exam-inst-item">
                  <strong>Submission Policy:</strong> Once you confirm submission or time runs out, the test will finalize automatically. A cryptographic submission receipt will be issued.
                </div>

                <div className="exam-inst-item">
                  <strong>Academic Integrity Notice:</strong> Unauthorised app switching, window blur, and suspicious actions are logged to the local audit ledger.
                </div>
              </div>

              {startError && (
                <div className="exam-alert exam-alert-danger" style={{ marginTop: '1rem' }}>
                  {startError}
                </div>
              )}
            </div>

            <div className="exam-modal-actions" style={{ marginTop: '1.5rem', display: 'flex', gap: '0.75rem', justifyContent: 'flex-end' }}>
              <button
                type="button"
                className="exam-btn exam-btn-outline"
                onClick={() => setInstructionExam(null)}
                disabled={isStartingAttempt}
              >
                Cancel
              </button>
              <button
                type="button"
                className="exam-btn exam-btn-primary"
                onClick={() => {
                  if (instructionExam.attempt?.attemptStatus === 'IN_PROGRESS') {
                    handleStartAttempt();
                  } else {
                    setIsReadinessOpen(true);
                  }
                }}
                disabled={isStartingAttempt || !instructionPackage}
                style={{ minWidth: '180px' }}
              >
                {isStartingAttempt
                  ? 'Authorizing Session...'
                  : instructionExam.attempt?.attemptStatus === 'IN_PROGRESS'
                  ? 'Resume Attempt'
                  : 'Check Device & Start'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
