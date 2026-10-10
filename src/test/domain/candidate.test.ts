import { describe, it, expect, beforeEach } from 'vitest';
import {
  computePaletteStatus,
  CandidateExamPackageSchema,
  CandidateSubmissionReceiptSchema,
  StudentExamSummarySchema,
  LocalWorkingAnswer
} from '../../domain/candidate';
import { formatTimeRemaining } from '../../candidate/useMonotonicTimer';
import { webStorageInstance } from '../../candidate/nativeBridge';

describe('Candidate Domain & Examination Experience', () => {
  beforeEach(() => {
    webStorageInstance.clear();
  });

  describe('Question Palette Status Computation', () => {
    it('returns NOT_VISITED when answer is undefined or unvisited', () => {
      expect(computePaletteStatus(undefined)).toBe('NOT_VISITED');

      const unvisited: LocalWorkingAnswer = {
        questionId: 'q-1',
        responsePayload: '',
        revisionSequence: 0,
        isMarkedForReview: false,
        isVisited: false,
        savedLocallyAt: new Date().toISOString(),
        serverSyncedAt: null,
        syncStatus: 'SAVED_LOCALLY'
      };
      expect(computePaletteStatus(unvisited)).toBe('NOT_VISITED');
    });

    it('returns UNANSWERED when visited without a response', () => {
      const visitedEmpty: LocalWorkingAnswer = {
        questionId: 'q-1',
        responsePayload: '',
        revisionSequence: 0,
        isMarkedForReview: false,
        isVisited: true,
        savedLocallyAt: new Date().toISOString(),
        serverSyncedAt: null,
        syncStatus: 'SAVED_LOCALLY'
      };
      expect(computePaletteStatus(visitedEmpty)).toBe('UNANSWERED');

      const visitedEmptyArray: LocalWorkingAnswer = {
        ...visitedEmpty,
        responsePayload: '[]'
      };
      expect(computePaletteStatus(visitedEmptyArray)).toBe('UNANSWERED');
    });

    it('returns ANSWERED when visited with a valid selection', () => {
      const answered: LocalWorkingAnswer = {
        questionId: 'q-1',
        responsePayload: 'A',
        revisionSequence: 1,
        isMarkedForReview: false,
        isVisited: true,
        savedLocallyAt: new Date().toISOString(),
        serverSyncedAt: null,
        syncStatus: 'SAVED_LOCALLY'
      };
      expect(computePaletteStatus(answered)).toBe('ANSWERED');
    });

    it('returns MARKED_FOR_REVIEW when review flag is true without response', () => {
      const marked: LocalWorkingAnswer = {
        questionId: 'q-1',
        responsePayload: '',
        revisionSequence: 0,
        isMarkedForReview: true,
        isVisited: true,
        savedLocallyAt: new Date().toISOString(),
        serverSyncedAt: null,
        syncStatus: 'SAVED_LOCALLY'
      };
      expect(computePaletteStatus(marked)).toBe('MARKED_FOR_REVIEW');
    });

    it('returns ANSWERED_AND_MARKED when answer exists and review flag is true', () => {
      const answeredAndMarked: LocalWorkingAnswer = {
        questionId: 'q-1',
        responsePayload: '["A","C"]',
        revisionSequence: 2,
        isMarkedForReview: true,
        isVisited: true,
        savedLocallyAt: new Date().toISOString(),
        serverSyncedAt: null,
        syncStatus: 'SAVED_LOCALLY'
      };
      expect(computePaletteStatus(answeredAndMarked)).toBe('ANSWERED_AND_MARKED');
    });
  });

  describe('Candidate Package Confidentiality Schema Validation', () => {
    it('validates a sanitized student-visible package schema without leakage', () => {
      const candidatePackage = {
        examId: 'exam-cbt-001',
        title: 'Computer Science Final 2026',
        durationSeconds: 7200,
        instituteId: 'inst-001',
        markingSchemeVersion: 1,
        packageHash: 'a1b2c3d4e5f67890abcdef1234567890',
        sections: [
          {
            sectionId: 'sec-1',
            title: 'Section A: Algorithms',
            sectionOrder: 1
          }
        ],
        questions: [
          {
            questionId: 'q-001',
            questionNumber: 1,
            sectionId: 'sec-1',
            questionType: 'SINGLE_SELECT',
            questionText: 'What is the time complexity of binary search?',
            answerOptions: ['O(1)', 'O(log n)', 'O(n)', 'O(n^2)']
          },
          {
            questionId: 'q-002',
            questionNumber: 2,
            sectionId: 'sec-1',
            questionType: 'NUMERICAL',
            questionText: 'Calculate the value of 2^8.',
            answerOptions: []
          }
        ],
        markingSummary: {
          correctScaledMark: 400,
          incorrectScaledMark: -100,
          unansweredScaledMark: 0,
          allowNegativeMarking: true
        }
      };

      const parsed = CandidateExamPackageSchema.safeParse(candidatePackage);
      expect(parsed.success).toBe(true);
      if (parsed.success) {
        expect(parsed.data.questions).toHaveLength(2);
        // Guarantee confidential answers are not present in schema
        expect((parsed.data.questions[0] as any).expectedAnswer).toBeUndefined();
        expect((parsed.data.questions[0] as any).expectedAnswerJson).toBeUndefined();
      }
    });

    it('rejects an invalid package with negative duration or missing questions', () => {
      const invalidPackage = {
        examId: 'exam-cbt-001',
        title: 'Physics',
        durationSeconds: -60,
        instituteId: 'inst-001',
        markingSchemeVersion: 1,
        packageHash: 'abc',
        sections: [],
        questions: []
      };

      const parsed = CandidateExamPackageSchema.safeParse(invalidPackage);
      expect(parsed.success).toBe(false);
    });
  });

  describe('Monotonic Timer Format Helper', () => {
    it('formats seconds into HH:MM:SS format', () => {
      expect(formatTimeRemaining(3665)).toBe('01:01:05');
      expect(formatTimeRemaining(7200)).toBe('02:00:00');
      expect(formatTimeRemaining(59)).toBe('00:00:59');
      expect(formatTimeRemaining(0)).toBe('00:00:00');
      expect(formatTimeRemaining(-10)).toBe('00:00:00');
    });
  });

  describe('Durable Local Answer Storage & Save-Before-Acknowledgement Invariant', () => {
    it('persists response locally and increments revision sequence', () => {
      const attemptId = 'att-test-001';
      const questionId = 'q-001';

      // First revision
      const res1 = webStorageInstance.saveResponse(attemptId, questionId, 'B', 1);
      expect(res1.isDurablyPersisted).toBe(true);
      expect(res1.revisionSequence).toBe(1);

      // Verify stored snapshot
      const snapshot1 = webStorageInstance.loadAttemptSnapshot(attemptId);
      expect(snapshot1.responses).toHaveLength(1);
      expect(snapshot1.responses[0]!.responsePayload).toBe('B');

      // Second revision update
      const res2 = webStorageInstance.saveResponse(attemptId, questionId, 'C', 2);
      expect(res2.revisionSequence).toBe(2);

      const snapshot2 = webStorageInstance.loadAttemptSnapshot(attemptId);
      expect(snapshot2.responses).toHaveLength(1);
      expect(snapshot2.responses[0]!.responsePayload).toBe('C');
      expect(snapshot2.responses[0]!.revisionSequence).toBe(2);
    });

    it('enqueues sync events into outbox and marks acknowledged', () => {
      const eventId = 'sync-evt-001';
      webStorageInstance.enqueueSyncEvent(
        eventId,
        'att-test-001',
        'ANSWER_RECORDED',
        JSON.stringify({ questionId: 'q-1', payload: 'A' })
      );

      const pending = webStorageInstance.getPendingSyncEvents();
      expect(pending).toHaveLength(1);
      expect(pending[0]!.eventId).toBe(eventId);

      webStorageInstance.markSyncEventAcknowledged(eventId);
      const pendingAfterAck = webStorageInstance.getPendingSyncEvents();
      expect(pendingAfterAck).toHaveLength(0);
    });
  });

  describe('Submission Receipt Schema', () => {
    it('validates a cryptographic submission receipt', () => {
      const receipt = {
        receiptId: 'rcpt_att-001_1728000000000',
        attemptId: 'att-001',
        examId: 'exam-001',
        studentId: 'usr-student-001',
        submissionStatus: 'ACCEPTED',
        submittedAt: '2026-10-09T18:00:00.000Z',
        receiptHash: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
        totalQuestionsAnswered: 25
      };

      const parsed = CandidateSubmissionReceiptSchema.safeParse(receipt);
      expect(parsed.success).toBe(true);
    });
  });

  describe('Student Exam Summary Schema', () => {
    it('validates summary with and without active attempt', () => {
      const summaryWithAttempt = {
        examId: 'exam-001',
        title: 'Mathematics Final',
        durationSeconds: 3600,
        instituteId: 'inst-001',
        markingSchemeVersion: 1,
        totalQuestions: 30,
        totalSections: 2,
        attempt: {
          attemptId: 'att-001',
          attemptStatus: 'IN_PROGRESS',
          resultStatus: 'PENDING_GRADING',
          startedAt: '2026-10-09T17:00:00.000Z'
        },
        hasPublishedResult: false,
        isResultHeld: false
      };

      const parsed = StudentExamSummarySchema.safeParse(summaryWithAttempt);
      expect(parsed.success).toBe(true);
    });
  });
});
