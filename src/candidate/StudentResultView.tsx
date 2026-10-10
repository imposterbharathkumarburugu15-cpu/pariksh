import React, { useState, useEffect } from 'react';

interface ResultData {
  resultId: string;
  attemptId: string;
  examId: string;
  studentId: string;
  version: number;
  resultStatus: string;
  totalScaledScore: number;
  maxPossibleScaledScore: number;
  correctCount: number;
  incorrectCount: number;
  unansweredCount: number;
  partiallyCorrectCount: number;
  sectionTotals: Array<{
    sectionId: string;
    title: string;
    totalQuestions: number;
    correctCount: number;
    incorrectCount: number;
    unansweredCount: number;
    totalScaledScore: number;
    maxPossibleScaledScore: number;
  }>;
  resultHash: string;
  scoredAt: string;
  publishedAt: string | null;
}

interface QuestionGradingRecord {
  recordId: string;
  questionId: string;
  sectionId: string | null;
  questionNumber: number;
  questionType: string;
  submittedAnswer: string | null;
  correctAnswerSnapshot: string | null;
  answerClassification: string;
  marksAwarded: number;
  maxMarks: number;
  explanationCode: string;
}

interface StudentResultViewProps {
  attemptId: string;
  examTitle: string;
  serverUrl: string;
  authToken: string;
  onBack: () => void;
}

export const StudentResultView: React.FC<StudentResultViewProps> = ({
  attemptId,
  examTitle,
  serverUrl,
  authToken,
  onBack
}) => {
  const [result, setResult] = useState<ResultData | null>(null);
  const [breakdown, setBreakdown] = useState<QuestionGradingRecord[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    async function fetchResult() {
      setLoading(true);
      setError(null);

      try {
        const res = await fetch(`${serverUrl}/api/attempts/${attemptId}/result`, {
          headers: { Authorization: `Bearer ${authToken}` }
        });

        if (!res.ok) {
          const errData = await res.json().catch(() => ({}));
          if (res.status === 403 && errData.error === 'RESULT_WITHHELD') {
            setError('This examination result is temporarily withheld pending administrative review.');
          } else if (res.status === 403 && errData.error === 'RESULT_NOT_RELEASED') {
            setError('Official results have not been published by the examination authority yet.');
          } else {
            setError(errData.message || 'Unable to load examination result.');
          }
          setLoading(false);
          return;
        }

        const data: ResultData = await res.json();
        setResult(data);

        // Fetch question level breakdown if accessible
        try {
          const bdRes = await fetch(`${serverUrl}/api/attempts/${attemptId}/result/breakdown`, {
            headers: { Authorization: `Bearer ${authToken}` }
          });
          if (bdRes.ok) {
            const bdData = await bdRes.json();
            setBreakdown(bdData);
          }
        } catch {
          // Breakdown optional
        }
      } catch (err: any) {
        setError(err.message || 'Failed to connect to examiner server.');
      } finally {
        setLoading(false);
      }
    }

    fetchResult();
  }, [attemptId, serverUrl, authToken]);

  if (loading) {
    return (
      <div className="exam-result-container">
        <div className="exam-result-card" style={{ textAlign: 'center', padding: '3rem' }}>
          <p style={{ color: '#94a3b8' }}>Loading published result...</p>
        </div>
      </div>
    );
  }

  if (error || !result) {
    return (
      <div className="exam-result-container">
        <div className="exam-result-card">
          <h2 style={{ color: '#f8fafc', margin: '0 0 1rem 0' }}>Examination Result Notice</h2>
          <div className="exam-alert exam-alert-warning" style={{ marginBottom: '1.5rem' }}>
            {error || 'No result record found for this attempt.'}
          </div>
          <button type="button" className="exam-btn exam-btn-outline" onClick={onBack}>
            ← Back to Dashboard
          </button>
        </div>
      </div>
    );
  }

  const scoreFormatted = (result.totalScaledScore / 100).toFixed(2);
  const maxScoreFormatted = (result.maxPossibleScaledScore / 100).toFixed(2);
  const percentage =
    result.maxPossibleScaledScore > 0
      ? ((result.totalScaledScore / result.maxPossibleScaledScore) * 100).toFixed(1)
      : '0.0';

  return (
    <div className="exam-result-container">
      <div className="exam-result-card">
        <div className="exam-result-header">
          <div>
            <h1 style={{ margin: 0, fontSize: '1.5rem', color: '#f8fafc' }}>{examTitle}</h1>
            <p style={{ margin: '0.25rem 0 0 0', color: '#94a3b8', fontSize: '0.85rem' }}>
              Attempt ID: <code>{result.attemptId}</code> • Version: {result.version}
            </p>
          </div>
          <button type="button" className="exam-btn exam-btn-outline" onClick={onBack}>
            ← Back to Dashboard
          </button>
        </div>

        {/* OVERALL SCORE BANNER */}
        <div className="exam-score-hero-card">
          <div className="exam-score-main">
            <span className="exam-score-number">{scoreFormatted}</span>
            <span className="exam-score-max">/ {maxScoreFormatted} Marks</span>
          </div>
          <div className="exam-score-percentage">{percentage}%</div>
          <div className="exam-score-badge">Official Result: {result.resultStatus}</div>
        </div>

        {/* QUESTION COUNTS */}
        <div className="exam-stats-summary-grid" style={{ marginTop: '1.5rem' }}>
          <div className="exam-stat-box exam-stat-answered">
            <span className="exam-stat-label">Correct</span>
            <span className="exam-stat-val" style={{ color: '#10b981' }}>{result.correctCount}</span>
          </div>
          <div className="exam-stat-box" style={{ borderLeft: '4px solid #ef4444' }}>
            <span className="exam-stat-label">Incorrect</span>
            <span className="exam-stat-val" style={{ color: '#ef4444' }}>{result.incorrectCount}</span>
          </div>
          <div className="exam-stat-box exam-stat-unanswered">
            <span className="exam-stat-label">Unanswered</span>
            <span className="exam-stat-val" style={{ color: '#f59e0b' }}>{result.unansweredCount}</span>
          </div>
          <div className="exam-stat-box exam-stat-review">
            <span className="exam-stat-label">Partially Correct</span>
            <span className="exam-stat-val" style={{ color: '#38bdf8' }}>{result.partiallyCorrectCount}</span>
          </div>
        </div>

        {/* SECTION BREAKDOWN TABLE */}
        {result.sectionTotals && result.sectionTotals.length > 0 && (
          <div style={{ marginTop: '2rem' }}>
            <h3 style={{ color: '#f8fafc', fontSize: '1.1rem', marginBottom: '0.75rem' }}>
              Section Performance
            </h3>
            <table className="exam-table">
              <thead>
                <tr>
                  <th>Section</th>
                  <th>Questions</th>
                  <th>Correct</th>
                  <th>Incorrect</th>
                  <th>Unanswered</th>
                  <th>Score</th>
                </tr>
              </thead>
              <tbody>
                {result.sectionTotals.map((sec) => (
                  <tr key={sec.sectionId}>
                    <td style={{ fontWeight: 600, color: '#f1f5f9' }}>{sec.title}</td>
                    <td>{sec.totalQuestions}</td>
                    <td style={{ color: '#10b981' }}>{sec.correctCount}</td>
                    <td style={{ color: '#ef4444' }}>{sec.incorrectCount}</td>
                    <td style={{ color: '#f59e0b' }}>{sec.unansweredCount}</td>
                    <td style={{ fontWeight: 600, color: '#38bdf8' }}>
                      {(sec.totalScaledScore / 100).toFixed(2)} / {(sec.maxPossibleScaledScore / 100).toFixed(2)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {/* QUESTION-LEVEL GRADING RECORDS */}
        {breakdown.length > 0 && (
          <div style={{ marginTop: '2rem' }}>
            <h3 style={{ color: '#f8fafc', fontSize: '1.1rem', marginBottom: '0.75rem' }}>
              Question Grading Details
            </h3>
            <table className="exam-table">
              <thead>
                <tr>
                  <th>Q#</th>
                  <th>Type</th>
                  <th>Your Answer</th>
                  <th>Classification</th>
                  <th>Marks</th>
                  <th>Explanation Code</th>
                </tr>
              </thead>
              <tbody>
                {breakdown.map((r) => (
                  <tr key={r.recordId}>
                    <td>Q{r.questionNumber}</td>
                    <td style={{ fontSize: '0.8rem' }}>{r.questionType}</td>
                    <td style={{ fontFamily: 'monospace' }}>{r.submittedAnswer || '—'}</td>
                    <td>
                      <span
                        className={`exam-badge ${
                          r.answerClassification === 'CORRECT'
                            ? 'exam-badge-success'
                            : r.answerClassification === 'INCORRECT'
                            ? 'exam-badge-danger'
                            : 'exam-badge-warning'
                        }`}
                      >
                        {r.answerClassification}
                      </span>
                    </td>
                    <td style={{ fontWeight: 600 }}>
                      {(r.marksAwarded / 100).toFixed(2)} / {(r.maxMarks / 100).toFixed(2)}
                    </td>
                    <td style={{ fontSize: '0.8rem', color: '#94a3b8' }}>{r.explanationCode}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        <div style={{ marginTop: '2rem', padding: '1rem', background: '#0f172a', borderRadius: '8px', border: '1px solid #334155', fontSize: '0.8rem', color: '#64748b' }}>
          Cryptographic Result Hash: <code>{result.resultHash}</code> • Scored At: {new Date(result.scoredAt).toLocaleString()}
        </div>
      </div>
    </div>
  );
};
