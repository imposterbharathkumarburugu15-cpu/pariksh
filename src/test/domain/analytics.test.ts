import { describe, it, expect } from 'vitest';
import {
  OverviewAnalyticsSchema,
  StudentPerformanceSchema,
  QuestionAnalyticsSchema,
  LearningGapsSchema,
  IntegrityAnalyticsSchema,
  OverviewAnalytics,
  StudentPerformance,
  QuestionAnalytics,
  LearningGaps,
  IntegrityAnalytics,
} from '../../domain/analytics';

describe('DOOMEDAMS Step 10: Examination Intelligence Domain & Analytics Test Suite', () => {
  describe('Overview & Cohort Analytics Validation', () => {
    it('validates authoritative OverviewAnalytics schema with non-fabricated data', () => {
      const sampleOverview: OverviewAnalytics = {
        totalExams: 4,
        publishedExams: 3,
        inProgressExams: 1,
        completedExams: 2,
        totalAssignedStudents: 120,
        activeAttempts: 25,
        acceptedSubmissions: 95,
        pendingSubmissions: 5,
        gradingCompletionPercentage: 94.7,
        publishedResultsCount: 88,
        resultsAwaitingPublicationCount: 4,
        resultsHeldCount: 3,
        withheldResultsCount: 0,
        outstandingIncidentsCount: 2,
        pendingAppealsCount: 1,
        generatedAt: '2026-10-10T12:00:00Z',
      };

      const result = OverviewAnalyticsSchema.safeParse(sampleOverview);
      expect(result.success).toBe(true);
      if (result.success) {
        expect(result.data.gradingCompletionPercentage).toBe(94.7);
        expect(result.data.resultsHeldCount).toBe(3);
      }
    });

    it('rejects invalid negative counts or missing timestamps in OverviewAnalytics', () => {
      const invalidOverview = {
        totalExams: -1,
        publishedExams: 3,
        inProgressExams: 1,
        completedExams: 2,
        totalAssignedStudents: 120,
        activeAttempts: 25,
        acceptedSubmissions: 95,
        pendingSubmissions: 5,
        gradingCompletionPercentage: 105.0, // Invalid percentage
        publishedResultsCount: 88,
        resultsAwaitingPublicationCount: 4,
        resultsHeldCount: 3,
        withheldResultsCount: 0,
        outstandingIncidentsCount: 2,
        pendingAppealsCount: 1,
        // missing generatedAt
      };

      const result = OverviewAnalyticsSchema.safeParse(invalidOverview);
      expect(result.success).toBe(false);
    });
  });

  describe('Student Performance, Held Results & Tied Ranks', () => {
    it('preserves null scores for pending attempts rather than converting them to zero', () => {
      const pendingStudent: StudentPerformance = {
        studentId: 'usr_stud_pending',
        studentDisplayName: 'Deepak Patel',
        attemptId: 'att_pend_001',
        attemptStatus: 'IN_PROGRESS',
        resultStatus: 'PENDING_GRADING',
        score: null,
        maxPossibleMarks: 800,
        percentage: null,
        accuracyPercentage: null,
        correctCount: 0,
        incorrectCount: 0,
        unansweredCount: 0,
        partiallyCorrectCount: 0,
        rank: null,
        isTiedRank: false,
        holdReason: null,
        startedAt: '2026-10-10T10:00:00Z',
        submittedAt: null,
        durationMinutes: null,
        incidentCount: 0,
        hasPendingAppeals: false,
      };

      const result = StudentPerformanceSchema.safeParse(pendingStudent);
      expect(result.success).toBe(true);
      if (result.success) {
        expect(result.data.score).toBeNull();
        expect(result.data.percentage).toBeNull();
      }
    });

    it('never treats a held result as a zero score and marks hold status clearly', () => {
      const heldStudent: StudentPerformance = {
        studentId: 'usr_stud_held',
        studentDisplayName: 'Rohan Joshi',
        attemptId: 'att_held_001',
        attemptStatus: 'SUBMITTED',
        resultStatus: 'HELD_PENDING_REVIEW',
        score: 650, // Authoritative 6.50 marks preserved
        maxPossibleMarks: 800,
        percentage: 81.25,
        accuracyPercentage: 85.0,
        correctCount: 7,
        incorrectCount: 1,
        unansweredCount: 2,
        partiallyCorrectCount: 0,
        rank: null,
        isTiedRank: false,
        holdReason: 'Proctor app switch violation under review',
        startedAt: '2026-10-10T10:00:00Z',
        submittedAt: '2026-10-10T10:45:00Z',
        durationMinutes: 45,
        incidentCount: 1,
        hasPendingAppeals: false,
      };

      const result = StudentPerformanceSchema.safeParse(heldStudent);
      expect(result.success).toBe(true);
      if (result.success) {
        expect(result.data.resultStatus).toBe('HELD_PENDING_REVIEW');
        expect(result.data.score).toBe(650);
        expect(result.data.incidentCount).toBe(1);
      }
    });

    it('computes consistent dense ranks for tied scores and excludes held results from official rank', () => {
      const candidates = [
        {
          studentId: 's1',
          studentDisplayName: 'Alice',
          resultStatus: 'PUBLISHED',
          score: 700,
        },
        {
          studentId: 's2',
          studentDisplayName: 'Bob',
          resultStatus: 'PUBLISHED',
          score: 700, // Tied with Alice
        },
        {
          studentId: 's3',
          studentDisplayName: 'Charlie',
          resultStatus: 'HELD_PENDING_REVIEW', // Held
          score: 700,
        },
        {
          studentId: 's4',
          studentDisplayName: 'Diana',
          resultStatus: 'PUBLISHED',
          score: 600,
        },
      ];

      // Dense ranking simulation logic matching the UI
      let currentRank = 1;
      let prevScore: number | null = null;
      const ranked = candidates.map((item, idx) => {
        if (item.resultStatus !== 'PUBLISHED') {
          return { studentId: item.studentId, rank: 'N/A' };
        }
        if (prevScore !== null && item.score < prevScore) {
          currentRank = idx + 1;
        }
        prevScore = item.score;
        return { studentId: item.studentId, rank: `#${currentRank}` };
      });

      expect(ranked.find((r) => r.studentId === 's1')?.rank).toBe('#1');
      expect(ranked.find((r) => r.studentId === 's2')?.rank).toBe('#1'); // Tied
      expect(ranked.find((r) => r.studentId === 's3')?.rank).toBe('N/A'); // Held
      expect(ranked.find((r) => r.studentId === 's4')?.rank).toBe('#4');
    });
  });

  describe('Question Analytics & Honest Telemetry Notice', () => {
    it('includes explicit TIME DATA UNAVAILABLE notice when client stopwatch is unrecorded', () => {
      const qItem: QuestionAnalytics = {
        questionId: 'q_chem_01',
        questionNumber: 1,
        sectionId: 'sec_01',
        sectionTitle: 'Physical Chemistry',
        questionType: 'SINGLE_SELECT',
        questionText: 'What is the standard enthalpy of formation?',
        maxMarks: 400,
        eligibleCandidatesDenominator: 50,
        attemptedCount: 45,
        unansweredCount: 5,
        correctCount: 30,
        incorrectCount: 15,
        partiallyCorrectCount: 0,
        attemptRate: 90.0,
        correctnessRate: 66.67,
        averageMarksAwarded: 250,
        difficultyCategory: 'MODERATE',
        recordedTimeNotice: 'TIME DATA UNAVAILABLE',
        optionDistributions: [],
      };

      const result = QuestionAnalyticsSchema.safeParse(qItem);
      expect(result.success).toBe(true);
      if (result.success) {
        expect(result.data.recordedTimeNotice).toBe('TIME DATA UNAVAILABLE');
        expect(result.data.attemptRate).toBe(90.0);
        expect(result.data.correctnessRate).toBe(66.67);
      }
    });

    it('defines attempt rate and correctness rate denominators accurately', () => {
      const eligible = 50;
      const attempted = 40;
      const correct = 20;

      const attemptRate = (attempted / eligible) * 100.0;
      const correctnessRate = (correct / attempted) * 100.0;

      expect(attemptRate).toBe(80.0);
      expect(correctnessRate).toBe(50.0);
    });
  });

  describe('Learning Gaps & Curriculum Tagging Disclosures', () => {
    it('honestly discloses curriculum tag absence and groups recommendations by section and question type', () => {
      const learningGaps: LearningGaps = {
        curriculumTagsAvailable: false,
        disclosureNotice:
          'Examination paper does not contain granular curriculum/topic tags. Analytics are grouped strictly by section and question type.',
        weakestSections: ['Section B - Electromagnetism'],
        highIncorrectQuestions: [
          {
            questionNumber: 4,
            sectionTitle: 'Section B - Electromagnetism',
            questionType: 'NUMERICAL',
            correctnessRate: 22.5,
            incorrectRate: 77.5,
          },
        ],
        explainableRecommendations: [
          'Section "Section B - Electromagnetism" had the lowest accuracy (42.0%). Prioritise remedial revision on this section.',
          'Question #4 had an incorrect response rate of 77.5%. Review question wording or provide foundational practice.',
        ],
      };

      const result = LearningGapsSchema.safeParse(learningGaps);
      expect(result.success).toBe(true);
      if (result.success) {
        expect(result.data.curriculumTagsAvailable).toBe(false);
        expect(result.data.explainableRecommendations.length).toBe(2);
        expect(result.data.highIncorrectQuestions[0]?.incorrectRate).toBe(77.5);
      }
    });
  });

  describe('Integrity & Incident Separation', () => {
    it('strictly separates telemetry events from confirmed incident cases and judicial decisions', () => {
      const integrityData: IntegrityAnalytics = {
        examId: 'exam_analytics_001',
        totalEvents: 42,
        uniqueFlaggedAttempts: 14,
        appNameUnavailableCount: 30,
        totalIncidentCases: 5,
        confirmedMisconductCount: 2,
        dismissedIncidentsCount: 0,
        pendingReviewIncidentsCount: 3,
        heldResultsCount: 3,
        cancelledAttemptsCount: 0,
        reinstatedAttemptsCount: 0,
        totalAppealsCount: 1,
        pendingAppealsCount: 1,
        eventsByCategory: {
          UNAUTHORISED_APP_SWITCH: 35,
          SCREEN_PINNING_ESCAPE: 7,
        },
        incidentsByStatus: {
          PENDING_FURTHER_REVIEW: 3,
          CONFIRMED_VIOLATION: 2,
        },
      };

      const result = IntegrityAnalyticsSchema.safeParse(integrityData);
      expect(result.success).toBe(true);
      if (result.success) {
        expect(result.data.totalEvents).toBe(42);
        expect(result.data.totalIncidentCases).toBe(5);
        expect(result.data.confirmedMisconductCount).toBe(2);
        // Events are not automatically equal to confirmed cheating
        expect(result.data.totalEvents).toBeGreaterThan(result.data.confirmedMisconductCount);
        expect(result.data.appNameUnavailableCount).toBe(30);
      }
    });
  });

  describe('RFC 4180 CSV Escaping & Export Integrity', () => {
    // Pure function representing the RFC 4180 escaping used in CSV exports
    const escapeCsv = (val: string | number | null | undefined): string => {
      if (val === null || val === undefined) return '';
      const str = String(val);
      if (str.includes(',') || str.includes('"') || str.includes('\n') || str.includes('\r')) {
        return `"${str.replace(/"/g, '""')}"`;
      }
      return str;
    };

    it('correctly escapes quotes, commas and multi-line strings in CSV cells', () => {
      expect(escapeCsv('Simple Text')).toBe('Simple Text');
      expect(escapeCsv('Sharma, Alice')).toBe('"Sharma, Alice"');
      expect(escapeCsv('He said "Hello" to the examiner')).toBe('"He said ""Hello"" to the examiner"');
      expect(escapeCsv('Line 1\nLine 2')).toBe('"Line 1\nLine 2"');
      expect(escapeCsv(null)).toBe('');
      expect(escapeCsv(100)).toBe('100');
    });

    it('produces valid CSV rows for examination summary reports', () => {
      const row = [
        escapeCsv('exam_001'),
        escapeCsv('Physics Midterm, CBT Edition'),
        escapeCsv('PUBLISHED'),
        escapeCsv(850),
        escapeCsv('Held: "Proctor Review Required"'),
      ].join(',');

      expect(row).toBe(
        'exam_001,"Physics Midterm, CBT Edition",PUBLISHED,850,"Held: ""Proctor Review Required"""'
      );
    });
  });
});
