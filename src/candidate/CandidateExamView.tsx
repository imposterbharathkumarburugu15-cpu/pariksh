import React, { useState, useEffect, useMemo, useCallback } from 'react';
import {
  CandidateExamPackage,
  CandidateAttemptSession,
  LocalWorkingAnswer,
  CandidateSubmissionReceipt,
  computePaletteStatus
} from '../domain/candidate';
import { ExamStorageBridge } from './nativeBridge';
import { useMonotonicTimer } from './useMonotonicTimer';
import { SubmissionModal } from './SubmissionModal';
import { SubmissionReceiptView } from './SubmissionReceiptView';
import { computeSha256Web, SyncEventEnvelope } from '../domain/sync';

interface CandidateExamViewProps {
  examPackage: CandidateExamPackage;
  session: CandidateAttemptSession;
  serverUrl: string;
  authToken: string;
  onExit: () => void;
}

export const CandidateExamView: React.FC<CandidateExamViewProps> = ({
  examPackage,
  session,
  serverUrl,
  authToken,
  onExit
}) => {
  const [currentQuestionIndex, setCurrentQuestionIndex] = useState<number>(0);
  const [activeSectionId, setActiveSectionId] = useState<string | null>(
    examPackage.sections.length > 0 ? (examPackage.sections[0]?.sectionId ?? null) : null
  );

  // Map of questionId -> LocalWorkingAnswer
  const [answers, setAnswers] = useState<Map<string, LocalWorkingAnswer>>(new Map());
  const [isOnline, setIsOnline] = useState<boolean>(navigator.onLine);
  const [globalSaveState, setGlobalSaveState] = useState<
    'SAVED_LOCALLY' | 'SAVING' | 'SYNCED' | 'SYNC_PENDING' | 'ERROR'
  >('SAVED_LOCALLY');

  // Submission state
  const [isSubmitModalOpen, setIsSubmitModalOpen] = useState<boolean>(false);
  const [isSubmitting, setIsSubmitting] = useState<boolean>(false);
  const [submissionReceipt, setSubmissionReceipt] = useState<CandidateSubmissionReceipt | null>(null);

  // Step 8: Integrity & Cancellation state
  const [isAttemptCancelled, setIsAttemptCancelled] = useState<boolean>(false);
  const [cancellationReason, setCancellationReason] = useState<string | null>(null);
  const [activeWarning, setActiveWarning] = useState<string | null>(null);

  // Load existing attempt snapshot on mount
  useEffect(() => {
    let isCancelled = false;

    async function loadSnapshot() {
      const snapshot = await ExamStorageBridge.loadAttemptSnapshotLocal(session.attemptId);
      if (isCancelled) return;

      const initialMap = new Map<string, LocalWorkingAnswer>();

      for (const q of examPackage.questions) {
        const saved = snapshot.responses.find((r) => r.questionId === q.questionId);
        initialMap.set(q.questionId, {
          questionId: q.questionId,
          responsePayload: saved ? saved.responsePayload : '',
          revisionSequence: saved ? saved.revisionSequence : 0,
          isMarkedForReview: false,
          isVisited: saved ? true : false,
          savedLocallyAt: saved ? saved.savedAt : new Date().toISOString(),
          serverSyncedAt: saved ? saved.savedAt : null,
          syncStatus: saved ? 'SAVED_LOCALLY' : 'SAVED_LOCALLY'
        });
      }

      // Mark the first question as visited
      if (examPackage.questions.length > 0 && examPackage.questions[0]) {
        const firstQ = examPackage.questions[0];
        const existingFirst = initialMap.get(firstQ.questionId);
        if (existingFirst) {
          existingFirst.isVisited = true;
        }
      }

      setAnswers(initialMap);
    }

    loadSnapshot();

    return () => {
      isCancelled = true;
    };
  }, [session.attemptId, examPackage.questions]);

  // Outbox drain helper: drains pending events to /api/sync/events
  const drainPendingOutbox = useCallback(async () => {
    try {
      const pending = await ExamStorageBridge.getPendingSyncEventsLocal();
      if (!pending || pending.length === 0) return;

      const envelopes: SyncEventEnvelope[] = [];
      for (const item of pending.slice(0, 50)) {
        const hash = await computeSha256Web(item.payloadJson);
        const mappedEventType = item.eventType === 'CANDIDATE_ANSWER_RECORDED'
          ? 'ANSWER_UPDATE'
          : (item.eventType === 'EXAM_SUBMITTED' ? 'FINAL_SUBMISSION' : item.eventType);
        envelopes.push({
          eventId: item.eventId,
          schemaVersion: 1,
          eventType: mappedEventType as any,
          instituteId: examPackage.instituteId || 'inst_default',
          examId: examPackage.examId,
          attemptId: session.attemptId,
          studentId: session.studentId,
          sourceDeviceId: 'device_local',
          sequenceNumber: (item.retryCount || 0) + 1,
          aggregateId: session.attemptId,
          aggregateVersion: 1,
          clientWallClockTimestamp: item.createdAt,
          payload: item.payloadJson,
          payloadHash: hash,
          deliveryAttempt: (item.retryCount || 0) + 1
        });
      }

      const res = await fetch(`${serverUrl}/api/sync/events`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${authToken}`
        },
        body: JSON.stringify({ events: envelopes })
      });

      if (res.ok) {
        const data = await res.json();
        for (const r of data.results || []) {
          if (r.status === 'ACCEPTED' || r.status === 'LATE_ACCEPTED' || r.status === 'ALREADY_PROCESSED') {
            await ExamStorageBridge.markSyncEventAcknowledgedLocal(r.eventId);
          } else if (r.status === 'CONFLICT') {
            await ExamStorageBridge.updateSyncEventStatusLocal(r.eventId, 'CONFLICT', r.rejectionReason);
          } else if (r.status === 'REJECTED' || r.status === 'LATE_REJECTED') {
            await ExamStorageBridge.updateSyncEventStatusLocal(r.eventId, 'REJECTED', r.rejectionReason);
          }
        }
        setGlobalSaveState('SYNCED');
      } else if (res.status === 401) {
        setActiveWarning('Server session expired or restarted. Local work is preserved. Please re-authenticate.');
      }
    } catch {
      // Offline: work safely retained in durable Room outbox
    }
  }, [examPackage, session, serverUrl, authToken]);

  // Online / Offline network listeners & periodic outbox drain
  useEffect(() => {
    const handleOnline = () => {
      setIsOnline(true);
      drainPendingOutbox();
    };
    const handleOffline = () => setIsOnline(false);

    window.addEventListener('online', handleOnline);
    window.addEventListener('offline', handleOffline);

    const intervalId = setInterval(() => {
      if (navigator.onLine) {
        drainPendingOutbox();
      }
    }, 15000);

    return () => {
      window.removeEventListener('online', handleOnline);
      window.removeEventListener('offline', handleOffline);
      clearInterval(intervalId);
    };
  }, [drainPendingOutbox]);

  // Step 8: Hardware Lockdown & Lifecycle Interruption Monitoring
  useEffect(() => {
    ExamStorageBridge.startExamLockdown().catch(() => {});

    let seq = 1;

    const handleInterruption = async () => {
      if (isAttemptCancelled) return;
      seq++;
      try {
        const res = await ExamStorageBridge.recordLifecycleInterruption(
          session.attemptId,
          session.examId,
          session.studentId,
          'EXAM_APP_BACKGROUND',
          seq
        );

        if (navigator.onLine) {
          const sRes = await fetch(`${serverUrl}/api/attempts/${session.attemptId}/integrity-events`, {
            method: 'POST',
            headers: {
              'Content-Type': 'application/json',
              Authorization: `Bearer ${authToken}`
            },
            body: JSON.stringify({
              eventId: res.eventId,
              eventCategory: 'EXAM_APP_BACKGROUND',
              deviceReportedAt: res.deviceReportedAt,
              sequenceNumber: seq,
              sourceDeviceId: 'device_local',
              appPackageName: res.appPackageName || null,
              displayedAppName: res.displayedAppName || 'APP_NAME_UNAVAILABLE'
            })
          });

          if (sRes.ok) {
            const data = await sRes.json();
            const resultItem = data.results?.[0];
            if (resultItem?.actionTaken === 'CANCEL_ATTEMPT' || resultItem?.attemptStatus === 'CANCELLED') {
              setIsAttemptCancelled(true);
              setCancellationReason(
                'Automatic cancellation executed by server policy engine due to verified policy violation.'
              );
            } else if (resultItem?.actionTaken === 'WARN_STUDENT' && resultItem?.warningMessage) {
              setActiveWarning(resultItem.warningMessage);
            }
          }
        }
      } catch {
        // Durably queued in Room SQLite sync_outbox
      }
    };

    const handleResume = async () => {
      if (isAttemptCancelled) return;
      seq++;
      try {
        const res = await ExamStorageBridge.recordExamResume(
          session.attemptId,
          session.examId,
          session.studentId,
          seq
        );

        if (navigator.onLine) {
          await fetch(`${serverUrl}/api/attempts/${session.attemptId}/integrity-events`, {
            method: 'POST',
            headers: {
              'Content-Type': 'application/json',
              Authorization: `Bearer ${authToken}`
            },
            body: JSON.stringify({
              eventId: res.eventId,
              eventCategory: 'EXAM_APP_RESUMED',
              deviceReportedAt: res.deviceReportedAt,
              sequenceNumber: seq,
              sourceDeviceId: 'device_local'
            })
          }).catch(() => {});
        }
      } catch {
        // Durably queued
      }
    };

    const onVisibilityChange = () => {
      if (document.visibilityState === 'hidden') {
        handleInterruption();
      } else if (document.visibilityState === 'visible') {
        handleResume();
      }
    };

    const onBlur = () => handleInterruption();
    const onFocus = () => handleResume();

    document.addEventListener('visibilitychange', onVisibilityChange);
    window.addEventListener('blur', onBlur);
    window.addEventListener('focus', onFocus);

    return () => {
      document.removeEventListener('visibilitychange', onVisibilityChange);
      window.removeEventListener('blur', onBlur);
      window.removeEventListener('focus', onFocus);
      ExamStorageBridge.stopExamLockdown().catch(() => {});
    };
  }, [session.attemptId, session.examId, session.studentId, serverUrl, authToken, isAttemptCancelled]);

  // Monotonic timer hook
  const timer = useMonotonicTimer({
    officialDeadlineIso: session.deadlineMetadata.officialDeadline,
    officialStartTimeIso: session.deadlineMetadata.officialStartTime,
    durationSeconds: session.deadlineMetadata.durationSeconds,
    onTimeout: () => {
      handleFinalSubmission();
    }
  });

  const currentQuestion = examPackage.questions[currentQuestionIndex];
  const currentAnswer = currentQuestion ? answers.get(currentQuestion.questionId) : undefined;

  // Track question visit when currentQuestionIndex changes
  useEffect(() => {
    if (!currentQuestion) return;

    setAnswers((prev) => {
      const updated = new Map(prev);
      const ans = updated.get(currentQuestion.questionId);
      if (ans && !ans.isVisited) {
        updated.set(currentQuestion.questionId, { ...ans, isVisited: true });
      } else if (!ans) {
        updated.set(currentQuestion.questionId, {
          questionId: currentQuestion.questionId,
          responsePayload: '',
          revisionSequence: 0,
          isMarkedForReview: false,
          isVisited: true,
          savedLocallyAt: new Date().toISOString(),
          serverSyncedAt: null,
          syncStatus: 'SAVED_LOCALLY'
        });
      }
      return updated;
    });

    if (currentQuestion.sectionId) {
      setActiveSectionId(currentQuestion.sectionId);
    }
  }, [currentQuestionIndex, currentQuestion]);

  // Save answer to native Room storage before acknowledging
  const persistAnswerChange = useCallback(
    async (questionId: string, newPayload: string) => {
      setGlobalSaveState('SAVING');

      const existing = answers.get(questionId);
      const nextRev = (existing?.revisionSequence || 0) + 1;

      // 1. Save durably in local Room SQLite via native bridge
      const saveResult = await ExamStorageBridge.saveResponseLocal(
        session.attemptId,
        questionId,
        newPayload,
        nextRev
      );

      // 2. Enqueue event in local sync outbox for durability & replay
      const eventId = `sync_${session.attemptId}_${questionId}_rev${nextRev}`;
      await ExamStorageBridge.enqueueSyncEventLocal(
        eventId,
        session.attemptId,
        'CANDIDATE_ANSWER_RECORDED',
        JSON.stringify({
          questionId,
          responsePayload: newPayload,
          revisionSequence: nextRev,
          clientRecordedAt: saveResult.savedAt
        })
      );

      // 3. Update local in-memory UI state (GUARANTEED: save succeeded before this runs)
      setAnswers((prev) => {
        const nextMap = new Map(prev);
        nextMap.set(questionId, {
          questionId,
          responsePayload: newPayload,
          revisionSequence: nextRev,
          isMarkedForReview: existing?.isMarkedForReview || false,
          isVisited: true,
          savedLocallyAt: saveResult.savedAt,
          serverSyncedAt: null,
          syncStatus: 'SAVED_LOCALLY'
        });
        return nextMap;
      });

      setGlobalSaveState('SAVED_LOCALLY');

      // 4. Remote synchronization attempt if connected
      if (isOnline) {
        try {
          const res = await fetch(`${serverUrl}/api/attempts/${session.attemptId}/answers`, {
            method: 'POST',
            headers: {
              'Content-Type': 'application/json',
              Authorization: `Bearer ${authToken}`
            },
            body: JSON.stringify({
              questionId,
              responsePayload: newPayload,
              revisionSequence: nextRev,
              clientRecordedAt: saveResult.savedAt
            })
          });

          if (res.ok) {
            await ExamStorageBridge.markSyncEventAcknowledgedLocal(eventId);
            setAnswers((prev) => {
              const nextMap = new Map(prev);
              const cur = nextMap.get(questionId);
              if (cur) {
                nextMap.set(questionId, {
                  ...cur,
                  serverSyncedAt: new Date().toISOString(),
                  syncStatus: 'ACCEPTED'
                });
              }
              return nextMap;
            });
            setGlobalSaveState('SYNCED');
          } else {
            setGlobalSaveState('SYNC_PENDING');
          }
        } catch {
          setGlobalSaveState('SYNC_PENDING');
        }
      } else {
        setGlobalSaveState('SYNC_PENDING');
      }
    },
    [answers, session.attemptId, isOnline, serverUrl, authToken]
  );

  // Toggle Mark for Review
  const toggleMarkForReview = () => {
    if (!currentQuestion) return;

    setAnswers((prev) => {
      const nextMap = new Map(prev);
      const existing = nextMap.get(currentQuestion.questionId);
      if (existing) {
        nextMap.set(currentQuestion.questionId, {
          ...existing,
          isMarkedForReview: !existing.isMarkedForReview
        });
      }
      return nextMap;
    });
  };

  // Clear answer
  const clearCurrentAnswer = () => {
    if (!currentQuestion) return;
    persistAnswerChange(currentQuestion.questionId, '');
  };

  // Calculate answer statistics
  const stats = useMemo(() => {
    let answered = 0;
    let unanswered = 0;
    let marked = 0;

    for (const q of examPackage.questions) {
      const ans = answers.get(q.questionId);
      const status = computePaletteStatus(ans);
      if (status === 'ANSWERED' || status === 'ANSWERED_AND_MARKED') {
        answered++;
      } else {
        unanswered++;
      }
      if (status === 'MARKED_FOR_REVIEW' || status === 'ANSWERED_AND_MARKED') {
        marked++;
      }
    }

    return { answered, unanswered, marked };
  }, [answers, examPackage.questions]);

  // Final submission action
  const handleFinalSubmission = async () => {
    setIsSubmitting(true);

    try {
      // 1. Calculate response checksum (simple deterministic hash of sorted responses)
      const sortedKeys = Array.from(answers.keys()).sort();
      let rawChecksum = '';
      for (const k of sortedKeys) {
        const item = answers.get(k);
        rawChecksum += `${k}:${item?.responsePayload || ''};`;
      }

      // Hash raw checksum with native/browser SubtleCrypto if available
      let checksumHash = `chk_${Date.now()}_${rawChecksum.length}`;
      try {
        const buffer = new TextEncoder().encode(rawChecksum);
        const digest = await crypto.subtle.digest('SHA-256', buffer);
        checksumHash = Array.from(new Uint8Array(digest))
          .map((b) => b.toString(16).padStart(2, '0'))
          .join('');
      } catch {
        // Fallback simple checksum
      }

      const totalAnswered = stats.answered;
      const submissionEventId = `sub_${session.attemptId}`;

      // 2. Save final submission snapshot in local Room outbox first
      await ExamStorageBridge.enqueueSyncEventLocal(
        submissionEventId,
        session.attemptId,
        'EXAM_SUBMITTED',
        JSON.stringify({
          clientChecksum: checksumHash,
          totalQuestionsAnswered: totalAnswered,
          submittedAt: new Date().toISOString()
        })
      );

      // 3. Attempt server submission
      let receiptData: CandidateSubmissionReceipt | null = null;

      try {
        const res = await fetch(`${serverUrl}/api/attempts/${session.attemptId}/submit`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Authorization: `Bearer ${authToken}`
          },
          body: JSON.stringify({
            clientChecksum: checksumHash,
            submissionEventId,
            totalQuestionsAnswered: totalAnswered
          })
        });

        if (res.ok) {
          receiptData = await res.json();
          await ExamStorageBridge.markSyncEventAcknowledgedLocal(submissionEventId);
        }
      } catch {
        // Network unavailable during submission: local durability is maintained!
      }

      // If server could not be reached, generate durable local submission receipt
      if (!receiptData) {
        receiptData = {
          receiptId: `rcpt_local_${session.attemptId}`,
          attemptId: session.attemptId,
          examId: examPackage.examId,
          studentId: session.studentId,
          submissionStatus: 'SUBMITTED_LOCALLY (Sync Pending)',
          submittedAt: new Date().toISOString(),
          receiptHash: checksumHash,
          totalQuestionsAnswered: totalAnswered
        };
      }

      setSubmissionReceipt(receiptData);
      setIsSubmitModalOpen(false);
    } finally {
      setIsSubmitting(false);
    }
  };

  if (submissionReceipt) {
    return (
      <SubmissionReceiptView
        receipt={submissionReceipt}
        examTitle={examPackage.title}
        onReturnToDashboard={onExit}
      />
    );
  }

  return (
    <div className="exam-cbt-root">
      {/* 1. TOP APP BAR */}
      <header className="exam-top-bar">
        <div className="exam-title-group">
          <span className="exam-title-text">{examPackage.title}</span>
          <span className="exam-section-badge">
            {examPackage.sections.find((s) => s.sectionId === activeSectionId)?.title || 'All Questions'}
          </span>
        </div>

        <div className="exam-header-controls">
          <div className="exam-sync-status-indicator">
            <span
              className={`exam-dot ${
                globalSaveState === 'SYNCED'
                  ? 'exam-dot-green'
                  : globalSaveState === 'SAVING'
                  ? 'exam-dot-yellow'
                  : 'exam-dot-teal'
              }`}
            />
            <span className="exam-sync-label">
              {globalSaveState === 'SAVING'
                ? 'Saving locally...'
                : globalSaveState === 'SAVED_LOCALLY'
                ? 'Saved locally (Room)'
                : globalSaveState === 'SYNC_PENDING'
                ? 'Sync pending (offline)'
                : 'Synced with Examiner'}
            </span>
          </div>

          <div className={`exam-connection-pill ${isOnline ? 'online' : 'offline'}`}>
            {isOnline ? '● Online' : '⚠️ Offline Mode'}
          </div>

          <div
            className={`exam-timer-pill ${
              timer.remainingSeconds < 300 ? 'exam-timer-urgent' : ''
            }`}
          >
            ⏱️ {timer.formattedRemaining}
          </div>

          <button
            type="button"
            className="exam-btn exam-btn-submit-action"
            onClick={() => setIsSubmitModalOpen(true)}
          >
            Submit Test
          </button>
        </div>
      </header>

      {/* INTEGRITY WARNING BANNER */}
      {activeWarning && (
        <div className="exam-warning-banner" role="alert">
          <div className="warning-text">
            <strong>⚠️ Integrity Notice:</strong> {activeWarning}
          </div>
          <button
            type="button"
            className="exam-btn btn-xs exam-btn-outline"
            onClick={() => setActiveWarning(null)}
          >
            Acknowledge
          </button>
        </div>
      )}

      {/* ATTEMPT CANCELLED MODAL */}
      {isAttemptCancelled && (
        <div className="exam-modal-backdrop" role="dialog" aria-modal="true">
          <div className="exam-modal-card exam-modal-danger">
            <h3 style={{ color: '#ef4444', marginBottom: '0.75rem' }}>
              🚫 Examination Attempt Cancelled
            </h3>
            <p style={{ color: '#cbd5e1', lineHeight: '1.5' }}>
              {cancellationReason ||
                'The server policy engine has cancelled this examination attempt due to a verified high-confidence integrity violation.'}
            </p>
            <div className="exam-alert exam-alert-info" style={{ marginTop: '1rem' }}>
              All previously saved answers have been preserved durably in the examination ledger.
              Further answer changes are locked.
            </div>
            <div className="exam-modal-actions" style={{ marginTop: '1.5rem', display: 'flex', justifyContent: 'flex-end' }}>
              <button
                type="button"
                className="exam-btn exam-btn-primary"
                onClick={onExit}
              >
                Exit to Dashboard
              </button>
            </div>
          </div>
        </div>
      )}

      {/* 2. SECTION BAR (IF MULTIPLE SECTIONS) */}
      {examPackage.sections.length > 1 && (
        <nav className="exam-section-tabs" aria-label="Examination Sections">
          {examPackage.sections.map((sec) => (
            <button
              key={sec.sectionId}
              type="button"
              className={`exam-section-tab-btn ${activeSectionId === sec.sectionId ? 'active' : ''}`}
              onClick={() => {
                setActiveSectionId(sec.sectionId);
                const firstQIndex = examPackage.questions.findIndex((q) => q.sectionId === sec.sectionId);
                if (firstQIndex !== -1) {
                  setCurrentQuestionIndex(firstQIndex);
                }
              }}
            >
              {sec.title}
            </button>
          ))}
        </nav>
      )}

      {/* 3. MAIN EXAMINATION WORKSPACE */}
      <div className="exam-body-grid">
        {/* LEFT / CENTER: QUESTION WORKSPACE */}
        <main className="exam-question-main" role="region" aria-label="Active Question">
          {currentQuestion ? (
            <div className="exam-question-content">
              <div className="exam-q-header">
                <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                  <span className="exam-q-number">Question {currentQuestion.questionNumber}</span>
                  <span className="exam-q-type-badge">{currentQuestion.questionType.replace('_', ' ')}</span>
                </div>

                {examPackage.markingSummary && (
                  <span className="exam-q-marks-pill">
                    +{examPackage.markingSummary.correctScaledMark / 100} /{' '}
                    {examPackage.markingSummary.allowNegativeMarking
                      ? `${examPackage.markingSummary.incorrectScaledMark / 100}`
                      : '0'}
                  </span>
                )}
              </div>

              {/* Question Stem */}
              <div className="exam-q-text">{currentQuestion.questionText}</div>

              {/* Response Controls based on Question Type */}
              <div className="exam-q-response-box" style={{ marginTop: '1.5rem' }}>
                {currentQuestion.questionType === 'SINGLE_SELECT' && (
                  <div className="exam-options-list" role="radiogroup" aria-label="Answer Options">
                    {currentQuestion.answerOptions.map((optText, optIdx) => {
                      const optKey = String.fromCharCode(65 + optIdx);
                      const isSelected = currentAnswer?.responsePayload === optKey;

                      return (
                        <label
                          key={optKey}
                          className={`exam-option-item ${isSelected ? 'selected' : ''}`}
                        >
                          <input
                            type="radio"
                            name={`q_${currentQuestion.questionId}`}
                            value={optKey}
                            checked={isSelected}
                            onChange={() => persistAnswerChange(currentQuestion.questionId, optKey)}
                          />
                          <span className="exam-option-marker">{optKey}</span>
                          <span className="exam-option-text">{optText}</span>
                        </label>
                      );
                    })}
                  </div>
                )}

                {currentQuestion.questionType === 'MULTI_SELECT' && (
                  <div className="exam-options-list" role="group" aria-label="Multiple Select Options">
                    {currentQuestion.answerOptions.map((optText, optIdx) => {
                      const optKey = String.fromCharCode(65 + optIdx);
                      let currentSelection: string[] = [];
                      try {
                        currentSelection = currentAnswer?.responsePayload
                          ? JSON.parse(currentAnswer.responsePayload)
                          : [];
                      } catch {
                        currentSelection = currentAnswer?.responsePayload ? [currentAnswer.responsePayload] : [];
                      }
                      const isSelected = currentSelection.includes(optKey);

                      return (
                        <label
                          key={optKey}
                          className={`exam-option-item ${isSelected ? 'selected' : ''}`}
                        >
                          <input
                            type="checkbox"
                            checked={isSelected}
                            onChange={() => {
                              const nextSelection = isSelected
                                ? currentSelection.filter((k) => k !== optKey)
                                : [...currentSelection, optKey].sort();
                              persistAnswerChange(
                                currentQuestion.questionId,
                                JSON.stringify(nextSelection)
                              );
                            }}
                          />
                          <span className="exam-option-marker">{optKey}</span>
                          <span className="exam-option-text">{optText}</span>
                        </label>
                      );
                    })}
                  </div>
                )}

                {currentQuestion.questionType === 'NUMERICAL' && (
                  <div className="exam-numerical-input-group">
                    <label htmlFor={`num_${currentQuestion.questionId}`} style={{ color: '#94a3b8', fontSize: '0.9rem' }}>
                      Enter Numerical Value:
                    </label>
                    <input
                      id={`num_${currentQuestion.questionId}`}
                      type="text"
                      inputMode="decimal"
                      className="exam-num-input"
                      placeholder="e.g. 42.5"
                      value={currentAnswer?.responsePayload || ''}
                      onChange={(e) => {
                        // Allow digits, minus, and period
                        const val = e.target.value.replace(/[^0-9.-]/g, '');
                        persistAnswerChange(currentQuestion.questionId, val);
                      }}
                    />
                  </div>
                )}

                {currentQuestion.questionType === 'SUBJECTIVE' && (
                  <div className="exam-subjective-group">
                    <label htmlFor={`subj_${currentQuestion.questionId}`} style={{ color: '#94a3b8', fontSize: '0.9rem' }}>
                      Detailed Response:
                    </label>
                    <textarea
                      id={`subj_${currentQuestion.questionId}`}
                      className="exam-textarea"
                      rows={6}
                      placeholder="Type your response here..."
                      value={currentAnswer?.responsePayload || ''}
                      onChange={(e) => persistAnswerChange(currentQuestion.questionId, e.target.value)}
                    />
                  </div>
                )}

                {!['SINGLE_SELECT', 'MULTI_SELECT', 'NUMERICAL', 'SUBJECTIVE'].includes(
                  currentQuestion.questionType
                ) && (
                  <div className="exam-fallback-warning">
                    ⚠️ Unsupported question type: <code>{currentQuestion.questionType}</code>. This
                    question cannot be rendered. Please consult your examination invigilator.
                  </div>
                )}
              </div>

              {/* ACTION TOOLBAR */}
              <div className="exam-bottom-toolbar">
                <div style={{ display: 'flex', gap: '0.5rem' }}>
                  <button
                    type="button"
                    className={`exam-btn ${
                      currentAnswer?.isMarkedForReview ? 'exam-btn-review-active' : 'exam-btn-outline'
                    }`}
                    onClick={toggleMarkForReview}
                  >
                    {currentAnswer?.isMarkedForReview ? '★ Marked for Review' : '☆ Mark for Review'}
                  </button>

                  <button
                    type="button"
                    className="exam-btn exam-btn-outline"
                    onClick={clearCurrentAnswer}
                    disabled={!currentAnswer?.responsePayload}
                  >
                    Clear Response
                  </button>
                </div>

                <div style={{ display: 'flex', gap: '0.5rem' }}>
                  <button
                    type="button"
                    className="exam-btn exam-btn-outline"
                    onClick={() => setCurrentQuestionIndex((prev) => Math.max(0, prev - 1))}
                    disabled={currentQuestionIndex === 0}
                  >
                    ← Previous
                  </button>
                  <button
                    type="button"
                    className="exam-btn exam-btn-primary"
                    onClick={() =>
                      setCurrentQuestionIndex((prev) =>
                        Math.min(examPackage.questions.length - 1, prev + 1)
                      )
                    }
                    disabled={currentQuestionIndex === examPackage.questions.length - 1}
                  >
                    Next →
                  </button>
                </div>
              </div>
            </div>
          ) : (
            <div style={{ color: '#94a3b8', padding: '2rem' }}>No question available.</div>
          )}
        </main>

        {/* RIGHT: QUESTION PALETTE */}
        <aside className="exam-palette-sidebar" aria-label="Question Palette">
          <div className="exam-palette-title">Question Palette</div>

          <div className="exam-palette-grid">
            {examPackage.questions.map((q, idx) => {
              const ans = answers.get(q.questionId);
              const status = computePaletteStatus(ans);
              const isCurrent = idx === currentQuestionIndex;

              return (
                <button
                  key={q.questionId}
                  type="button"
                  className={`exam-palette-cell status-${status.toLowerCase()} ${
                    isCurrent ? 'current' : ''
                  }`}
                  onClick={() => setCurrentQuestionIndex(idx)}
                  title={`Question ${q.questionNumber}: ${status.replace(/_/g, ' ')}`}
                  aria-label={`Question ${q.questionNumber}, status: ${status.replace(/_/g, ' ')}`}
                >
                  {q.questionNumber}
                </button>
              );
            })}
          </div>

          {/* PALETTE LEGEND */}
          <div className="exam-palette-legend">
            <div className="legend-item">
              <span className="legend-swatch status-not_visited" />
              <span>Not Visited</span>
            </div>
            <div className="legend-item">
              <span className="legend-swatch status-unanswered" />
              <span>Unanswered</span>
            </div>
            <div className="legend-item">
              <span className="legend-swatch status-answered" />
              <span>Answered</span>
            </div>
            <div className="legend-item">
              <span className="legend-swatch status-marked_for_review" />
              <span>Review</span>
            </div>
            <div className="legend-item">
              <span className="legend-swatch status-answered_and_marked" />
              <span>Answered & Review</span>
            </div>
          </div>
        </aside>
      </div>

      {/* SUBMISSION MODAL */}
      {isSubmitModalOpen && (
        <SubmissionModal
          totalQuestions={examPackage.questions.length}
          answeredCount={stats.answered}
          unansweredCount={stats.unanswered}
          markedForReviewCount={stats.marked}
          remainingTimeFormatted={timer.formattedRemaining}
          isSubmitting={isSubmitting}
          onConfirm={handleFinalSubmission}
          onCancel={() => setIsSubmitModalOpen(false)}
        />
      )}
    </div>
  );
};
