import React from 'react';
import { CandidateSubmissionReceipt } from '../domain/candidate';

interface SubmissionReceiptViewProps {
  receipt: CandidateSubmissionReceipt;
  examTitle: string;
  onReturnToDashboard: () => void;
}

export const SubmissionReceiptView: React.FC<SubmissionReceiptViewProps> = ({
  receipt,
  examTitle,
  onReturnToDashboard
}) => {
  const isServerAccepted = receipt.submissionStatus === 'ACCEPTED';

  return (
    <div className="exam-receipt-container">
      <div className="exam-receipt-card">
        <div style={{ textAlign: 'center', marginBottom: '1.5rem' }}>
          <div className="exam-receipt-icon">
            {isServerAccepted ? '✅' : '⏳'}
          </div>
          <h1 style={{ fontSize: '1.5rem', color: '#f8fafc', margin: '0.5rem 0' }}>
            {isServerAccepted ? 'Examination Submitted Successfully' : 'Submission Recorded Locally'}
          </h1>
          <p style={{ color: '#94a3b8', fontSize: '0.9rem', margin: 0 }}>
            {isServerAccepted
              ? 'Your submission has been durably acknowledged by the examiner server.'
              : 'Your submission is safely persisted on this device and queued for server synchronization when connectivity returns.'}
          </p>
        </div>

        <div className="exam-receipt-details-list">
          <div className="exam-receipt-row">
            <span className="exam-receipt-key">Examination</span>
            <span className="exam-receipt-val" style={{ fontWeight: 600, color: '#38bdf8' }}>{examTitle}</span>
          </div>

          <div className="exam-receipt-row">
            <span className="exam-receipt-key">Attempt ID</span>
            <span className="exam-receipt-val" style={{ fontFamily: 'monospace' }}>{receipt.attemptId}</span>
          </div>

          <div className="exam-receipt-row">
            <span className="exam-receipt-key">Receipt ID</span>
            <span className="exam-receipt-val" style={{ fontFamily: 'monospace' }}>{receipt.receiptId}</span>
          </div>

          <div className="exam-receipt-row">
            <span className="exam-receipt-key">Submission Status</span>
            <span className="exam-receipt-val">
              <span className={`exam-badge ${isServerAccepted ? 'exam-badge-success' : 'exam-badge-warning'}`}>
                {receipt.submissionStatus}
              </span>
            </span>
          </div>

          <div className="exam-receipt-row">
            <span className="exam-receipt-key">Recorded Timestamp</span>
            <span className="exam-receipt-val">{new Date(receipt.submittedAt).toLocaleString()}</span>
          </div>

          <div className="exam-receipt-row">
            <span className="exam-receipt-key">Total Answers Submitted</span>
            <span className="exam-receipt-val">{receipt.totalQuestionsAnswered}</span>
          </div>

          <div className="exam-receipt-row" style={{ flexDirection: 'column', alignItems: 'flex-start', gap: '0.25rem' }}>
            <span className="exam-receipt-key">Cryptographic Receipt Hash (SHA-256)</span>
            <span className="exam-receipt-hash" style={{ wordBreak: 'break-all', fontFamily: 'monospace', fontSize: '0.8rem', color: '#14b8a6' }}>
              {receipt.receiptHash}
            </span>
          </div>
        </div>

        <div className="exam-notice-box" style={{ marginTop: '1.5rem', padding: '1rem', background: '#0f172a', borderRadius: '8px', border: '1px solid #334155' }}>
          <h4 style={{ margin: '0 0 0.5rem 0', fontSize: '0.9rem', color: '#e2e8f0' }}>Official Results Notice</h4>
          <p style={{ margin: 0, fontSize: '0.85rem', color: '#94a3b8', lineHeight: 1.5 }}>
            Examination grading is performed deterministically by the authoritative assessment engine. Official results and score reports are released only when authorized by the examination coordinator. You may monitor result publication status from your student dashboard.
          </p>
        </div>

        <div style={{ marginTop: '2rem', display: 'flex', justifyContent: 'center' }}>
          <button
            type="button"
            className="exam-btn exam-btn-primary"
            onClick={onReturnToDashboard}
            style={{ minWidth: '200px' }}
          >
            Return to Student Dashboard
          </button>
        </div>
      </div>
    </div>
  );
};
