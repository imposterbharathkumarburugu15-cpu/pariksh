import React from 'react';

interface SubmissionModalProps {
  totalQuestions: number;
  answeredCount: number;
  unansweredCount: number;
  markedForReviewCount: number;
  remainingTimeFormatted: string;
  isSubmitting: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

export const SubmissionModal: React.FC<SubmissionModalProps> = ({
  totalQuestions,
  answeredCount,
  unansweredCount,
  markedForReviewCount,
  remainingTimeFormatted,
  isSubmitting,
  onConfirm,
  onCancel
}) => {
  return (
    <div className="exam-modal-backdrop" role="dialog" aria-modal="true" aria-labelledby="sub-modal-title">
      <div className="exam-modal-card">
        <div className="exam-modal-header">
          <h2 id="sub-modal-title" style={{ margin: 0, fontSize: '1.25rem', color: '#f8fafc' }}>
            Final Examination Submission
          </h2>
          <span className="exam-timer-pill" style={{ fontSize: '0.85rem' }}>
            ⏳ {remainingTimeFormatted} Remaining
          </span>
        </div>

        <p style={{ color: '#94a3b8', fontSize: '0.9rem', marginTop: '0.75rem' }}>
          Please review your attempt statistics below before confirming your submission. Once submitted, answers cannot be modified.
        </p>

        <div className="exam-stats-summary-grid">
          <div className="exam-stat-box">
            <span className="exam-stat-label">Total Questions</span>
            <span className="exam-stat-val">{totalQuestions}</span>
          </div>
          <div className="exam-stat-box exam-stat-answered">
            <span className="exam-stat-label">Answered</span>
            <span className="exam-stat-val" style={{ color: '#10b981' }}>{answeredCount}</span>
          </div>
          <div className="exam-stat-box exam-stat-unanswered">
            <span className="exam-stat-label">Unanswered</span>
            <span className="exam-stat-val" style={{ color: '#f59e0b' }}>{unansweredCount}</span>
          </div>
          <div className="exam-stat-box exam-stat-review">
            <span className="exam-stat-label">Marked for Review</span>
            <span className="exam-stat-val" style={{ color: '#a855f7' }}>{markedForReviewCount}</span>
          </div>
        </div>

        {unansweredCount > 0 && (
          <div className="exam-warning-notice" style={{ marginTop: '1rem' }}>
            ⚠️ <strong>Warning:</strong> You have <strong>{unansweredCount}</strong> unanswered questions remaining.
          </div>
        )}

        <div className="exam-modal-actions" style={{ marginTop: '1.5rem', display: 'flex', gap: '0.75rem', justifyContent: 'flex-end' }}>
          <button
            type="button"
            className="exam-btn exam-btn-outline"
            onClick={onCancel}
            disabled={isSubmitting}
          >
            Return to Questions
          </button>
          <button
            type="button"
            className="exam-btn exam-btn-primary"
            onClick={onConfirm}
            disabled={isSubmitting}
            style={{ minWidth: '160px' }}
          >
            {isSubmitting ? 'Finalizing...' : 'Confirm & Submit'}
          </button>
        </div>
      </div>
    </div>
  );
};
