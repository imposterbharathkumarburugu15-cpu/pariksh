import { describe, it, expect } from 'vitest';
import {
  AnswerClassificationSchema,
  ScoringExplanationCodeSchema,
  MultiSelectPolicySchema,
  ScoringSimulationRequestSchema,
  ScoringSimulationResultSchema,
  simulateQuestionScoring
} from '../../domain/scoring';

describe('Deterministic Scoring Domain & Simulator', () => {
  describe('Schema Validation', () => {
    it('validates answer classifications', () => {
      const classifications = [
        'CORRECT',
        'INCORRECT',
        'PARTIALLY_CORRECT',
        'UNANSWERED',
        'PENDING_MANUAL_GRADING',
        'INVALID_RESPONSE',
        'UNGRADABLE'
      ];
      for (const c of classifications) {
        expect(AnswerClassificationSchema.safeParse(c).success).toBe(true);
      }
      expect(AnswerClassificationSchema.safeParse('UNKNOWN_STATUS').success).toBe(false);
    });

    it('validates scoring explanation codes', () => {
      const codes = [
        'MATCHED_VERIFIED_ANSWER',
        'ANSWER_MISMATCH',
        'NO_RESPONSE',
        'PARTIAL_CREDIT_RULE_APPLIED',
        'NUMERICAL_TOLERANCE_MATCH',
        'INVALID_RESPONSE_FORMAT',
        'MANUAL_GRADING_REQUIRED',
        'MISSING_ANSWER_KEY',
        'UNVERIFIED_ANSWER_KEY',
        'UNSUPPORTED_QUESTION_TYPE'
      ];
      for (const code of codes) {
        expect(ScoringExplanationCodeSchema.safeParse(code).success).toBe(true);
      }
      expect(ScoringExplanationCodeSchema.safeParse('FABRICATED_AI_GRADE').success).toBe(false);
    });

    it('validates simulation request defaults', () => {
      const parsed = ScoringSimulationRequestSchema.parse({
        questionType: 'SINGLE_SELECT',
        expectedAnswer: 'A'
      });
      expect(parsed.correctScaledMark).toBe(400);
      expect(parsed.incorrectScaledMark).toBe(-100);
      expect(parsed.unansweredScaledMark).toBe(0);
      expect(parsed.allowNegativeMarking).toBe(true);
      expect(parsed.multiSelectPolicy).toBe('EXACT_MATCH');
    });

    it('validates multi-select policies and simulation result schemas', () => {
      expect(MultiSelectPolicySchema.safeParse('EXACT_MATCH').success).toBe(true);
      expect(MultiSelectPolicySchema.safeParse('PARTIAL_CREDIT').success).toBe(true);
      expect(MultiSelectPolicySchema.safeParse('RANDOM_GUESS').success).toBe(false);

      const sampleResult = simulateQuestionScoring({
        questionType: 'SINGLE_SELECT',
        expectedAnswer: 'A',
        submittedAnswer: 'A'
      });
      expect(ScoringSimulationResultSchema.safeParse(sampleResult).success).toBe(true);
    });
  });

  describe('Single-Select Scoring Simulation', () => {
    it('awards positive marks for correct single-select answer', () => {
      const res = simulateQuestionScoring({
        questionType: 'SINGLE_SELECT',
        expectedAnswer: '"B"',
        submittedAnswer: '"B"',
        correctScaledMark: 400,
        incorrectScaledMark: -100,
        unansweredScaledMark: 0,
        allowNegativeMarking: true,
        multiSelectPolicy: 'EXACT_MATCH'
      });
      expect(res.classification).toBe('CORRECT');
      expect(res.marksAwarded).toBe(400);
      expect(res.explanationCode).toBe('MATCHED_VERIFIED_ANSWER');
    });

    it('applies negative marking for incorrect single-select answer', () => {
      const res = simulateQuestionScoring({
        questionType: 'SINGLE_SELECT',
        expectedAnswer: '"B"',
        submittedAnswer: '"C"',
        correctScaledMark: 400,
        incorrectScaledMark: -100,
        unansweredScaledMark: 0,
        allowNegativeMarking: true,
        multiSelectPolicy: 'EXACT_MATCH'
      });
      expect(res.classification).toBe('INCORRECT');
      expect(res.marksAwarded).toBe(-100);
      expect(res.explanationCode).toBe('ANSWER_MISMATCH');
    });

    it('awards zero when negative marking is disabled', () => {
      const res = simulateQuestionScoring({
        questionType: 'SINGLE_SELECT',
        expectedAnswer: '"B"',
        submittedAnswer: '"C"',
        correctScaledMark: 400,
        incorrectScaledMark: -100,
        unansweredScaledMark: 0,
        allowNegativeMarking: false,
        multiSelectPolicy: 'EXACT_MATCH'
      });
      expect(res.classification).toBe('INCORRECT');
      expect(res.marksAwarded).toBe(0);
    });

    it('handles unanswered questions deterministically', () => {
      const res = simulateQuestionScoring({
        questionType: 'SINGLE_SELECT',
        expectedAnswer: '"B"',
        submittedAnswer: null,
        correctScaledMark: 400,
        incorrectScaledMark: -100,
        unansweredScaledMark: 0,
        allowNegativeMarking: true,
        multiSelectPolicy: 'EXACT_MATCH'
      });
      expect(res.classification).toBe('UNANSWERED');
      expect(res.marksAwarded).toBe(0);
      expect(res.explanationCode).toBe('NO_RESPONSE');
    });
  });

  describe('Multi-Select Scoring Simulation', () => {
    it('evaluates exact match policy', () => {
      const fullMatch = simulateQuestionScoring({
        questionType: 'MULTI_SELECT',
        expectedAnswer: '["A", "B"]',
        submittedAnswer: '["A", "B"]',
        correctScaledMark: 400,
        incorrectScaledMark: -100,
        unansweredScaledMark: 0,
        allowNegativeMarking: true,
        multiSelectPolicy: 'EXACT_MATCH'
      });
      expect(fullMatch.classification).toBe('CORRECT');
      expect(fullMatch.marksAwarded).toBe(400);

      const partialAttempt = simulateQuestionScoring({
        questionType: 'MULTI_SELECT',
        expectedAnswer: '["A", "B"]',
        submittedAnswer: '["A"]',
        correctScaledMark: 400,
        incorrectScaledMark: -100,
        unansweredScaledMark: 0,
        allowNegativeMarking: true,
        multiSelectPolicy: 'EXACT_MATCH'
      });
      expect(partialAttempt.classification).toBe('INCORRECT');
      expect(partialAttempt.marksAwarded).toBe(-100);
    });

    it('evaluates partial credit policy without awarding extra credit for duplicates', () => {
      const partial = simulateQuestionScoring({
        questionType: 'MULTI_SELECT',
        expectedAnswer: '["A", "B", "C", "D"]',
        submittedAnswer: '["A", "A", "B", "B"]',
        correctScaledMark: 400,
        incorrectScaledMark: -100,
        unansweredScaledMark: 0,
        allowNegativeMarking: true,
        multiSelectPolicy: 'PARTIAL_CREDIT'
      });
      expect(partial.classification).toBe('PARTIALLY_CORRECT');
      expect(partial.marksAwarded).toBe(200); // 2 / 4 * 400 = 200
      expect(partial.explanationCode).toBe('PARTIAL_CREDIT_RULE_APPLIED');

      const withWrong = simulateQuestionScoring({
        questionType: 'MULTI_SELECT',
        expectedAnswer: '["A", "B"]',
        submittedAnswer: '["A", "C"]',
        correctScaledMark: 400,
        incorrectScaledMark: -100,
        unansweredScaledMark: 0,
        allowNegativeMarking: true,
        multiSelectPolicy: 'PARTIAL_CREDIT'
      });
      expect(withWrong.classification).toBe('INCORRECT');
      expect(withWrong.marksAwarded).toBe(-100);
    });
  });

  describe('Numerical and Subjective Scoring Simulation', () => {
    it('accepts numerical answers within tolerance and parses commas', () => {
      const within = simulateQuestionScoring({
        questionType: 'NUMERICAL',
        expectedAnswer: '42.5',
        submittedAnswer: '42,8',
        numericalTolerance: 0.5,
        correctScaledMark: 400,
        incorrectScaledMark: -100,
        unansweredScaledMark: 0,
        allowNegativeMarking: true,
        multiSelectPolicy: 'EXACT_MATCH'
      });
      expect(within.classification).toBe('CORRECT');
      expect(within.marksAwarded).toBe(400);
      expect(within.explanationCode).toBe('NUMERICAL_TOLERANCE_MATCH');

      const outside = simulateQuestionScoring({
        questionType: 'NUMERICAL',
        expectedAnswer: '42.5',
        submittedAnswer: '43.5',
        numericalTolerance: 0.5,
        correctScaledMark: 400,
        incorrectScaledMark: -100,
        unansweredScaledMark: 0,
        allowNegativeMarking: true,
        multiSelectPolicy: 'EXACT_MATCH'
      });
      expect(outside.classification).toBe('INCORRECT');
      expect(outside.marksAwarded).toBe(-100);
    });

    it('flags invalid numeric strings without crashing', () => {
      const invalid = simulateQuestionScoring({
        questionType: 'NUMERICAL',
        expectedAnswer: '42.5',
        submittedAnswer: 'not-a-number',
        correctScaledMark: 400,
        incorrectScaledMark: -100,
        unansweredScaledMark: 0,
        allowNegativeMarking: true,
        multiSelectPolicy: 'EXACT_MATCH'
      });
      expect(invalid.classification).toBe('INVALID_RESPONSE');
      expect(invalid.marksAwarded).toBe(0);
      expect(invalid.explanationCode).toBe('INVALID_RESPONSE_FORMAT');
    });

    it('marks subjective questions as pending manual grading with zero marks', () => {
      const subj = simulateQuestionScoring({
        questionType: 'SUBJECTIVE',
        expectedAnswer: 'Answer key rubric',
        submittedAnswer: 'The candidate detailed essay response.',
        correctScaledMark: 500,
        incorrectScaledMark: 0,
        unansweredScaledMark: 0,
        allowNegativeMarking: false,
        multiSelectPolicy: 'EXACT_MATCH'
      });
      expect(subj.classification).toBe('PENDING_MANUAL_GRADING');
      expect(subj.marksAwarded).toBe(0);
      expect(subj.explanationCode).toBe('MANUAL_GRADING_REQUIRED');
    });
  });
});
