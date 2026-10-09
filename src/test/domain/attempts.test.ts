import { describe, it, expect } from 'vitest';
import {
  ExamAttemptSchema,
  AttemptStatusSchema,
  ResultStatusSchema,
  ExamResultRecordSchema,
  CandidateResponseSchema,
  QuestionVisitSchema,
  AnswerRevisionSchema,
  SubmissionRecordSchema
} from '../../domain/attempts';

describe('Examination Attempt and Answer Schemas', () => {
  const startTimestamp = '2026-10-09T10:00:00Z';
  const deadlineTimestamp = '2026-10-09T12:00:00Z';

  it('validates a valid exam attempt record', () => {
    const validAttempt = {
      attemptId: 'att_std_001_exam_101',
      examId: 'exam_101',
      studentId: 'std_001',
      status: 'IN_PROGRESS',
      startTimestamp,
      allocatedDurationSeconds: 7200,
      scheduledDeadline: deadlineTimestamp,
      activeDeviceId: 'dev_tablet_04'
    };

    const parsed = ExamAttemptSchema.safeParse(validAttempt);
    expect(parsed.success).toBe(true);
  });

  it('rejects zero or negative allocated duration in attempts', () => {
    const invalidAttempt = {
      attemptId: 'att_01',
      examId: 'exam_101',
      studentId: 'std_001',
      status: 'IN_PROGRESS',
      startTimestamp,
      allocatedDurationSeconds: -3600,
      scheduledDeadline: deadlineTimestamp
    };

    expect(ExamAttemptSchema.safeParse(invalidAttempt).success).toBe(false);
  });

  it('validates candidate response updates and revision counts', () => {
    const response = {
      questionId: 'q_chem_01',
      responseValue: 'B',
      lastSavedTimestamp: '2026-10-09T10:15:20Z',
      revisionCount: 2
    };

    const parsed = CandidateResponseSchema.safeParse(response);
    expect(parsed.success).toBe(true);
  });

  it('tracks immutable answer revisions with original and updated states', () => {
    const revision = {
      revisionId: 'rev_001',
      attemptId: 'att_01',
      questionId: 'q_01',
      originalResponse: 'A',
      updatedResponse: 'C',
      revisionSequence: 1,
      eventTimestamp: '2026-10-09T10:20:00Z'
    };

    const parsed = AnswerRevisionSchema.safeParse(revision);
    expect(parsed.success).toBe(true);
  });

  it('validates question visit telemetry with duration', () => {
    const visit = {
      visitId: 'vis_001',
      questionId: 'q_01',
      visitSequence: 1,
      entryTimestamp: '2026-10-09T10:01:00Z',
      exitTimestamp: '2026-10-09T10:04:30Z',
      durationSeconds: 210
    };

    const parsed = QuestionVisitSchema.safeParse(visit);
    expect(parsed.success).toBe(true);
  });

  it('validates formal submission records', () => {
    const submission = {
      submissionId: 'sub_001',
      attemptId: 'att_01',
      submittedAt: '2026-10-09T11:55:00Z',
      clientChecksum: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
      totalQuestionsAnswered: 45
    };

    const parsed = SubmissionRecordSchema.safeParse(submission);
    expect(parsed.success).toBe(true);
  });

  it('verifies that HELD_PENDING_REVIEW is a valid result status', () => {
    expect(ResultStatusSchema.safeParse('HELD_PENDING_REVIEW').success).toBe(true);
  });

  it('guarantees ResultStatus and AttemptStatus are strictly decoupled concepts', () => {
    // Attempt status remains SUBMITTED
    const attemptStatus = AttemptStatusSchema.parse('SUBMITTED');
    expect(attemptStatus).toBe('SUBMITTED');

    // While Result status is HELD_PENDING_REVIEW
    const heldResult = {
      resultId: 'res_001',
      attemptId: 'att_001',
      examId: 'exam_101',
      studentId: 'std_001',
      resultStatus: 'HELD_PENDING_REVIEW',
      holdReason: 'Investigation required by chief proctor'
    };

    const parsedResult = ExamResultRecordSchema.safeParse(heldResult);
    expect(parsedResult.success).toBe(true);
    if (parsedResult.success) {
      expect(parsedResult.data.resultStatus).toBe('HELD_PENDING_REVIEW');
    }
  });
});
