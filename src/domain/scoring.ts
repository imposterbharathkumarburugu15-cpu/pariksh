import { z } from 'zod';
import { ScaledMarkSchema } from './common';

/**
 * Deterministic Answer Classification Categories
 */
export const AnswerClassificationSchema = z.enum([
  'CORRECT',
  'INCORRECT',
  'PARTIALLY_CORRECT',
  'UNANSWERED',
  'PENDING_MANUAL_GRADING',
  'INVALID_RESPONSE',
  'UNGRADABLE'
]);

export type AnswerClassification = z.infer<typeof AnswerClassificationSchema>;

/**
 * Structured Auditable Explanation Codes for Score Derivation
 */
export const ScoringExplanationCodeSchema = z.enum([
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
]);

export type ScoringExplanationCode = z.infer<typeof ScoringExplanationCodeSchema>;

/**
 * Multi-Select Evaluation Policies
 */
export const MultiSelectPolicySchema = z.enum([
  'EXACT_MATCH',
  'PARTIAL_CREDIT'
]);

export type MultiSelectPolicy = z.infer<typeof MultiSelectPolicySchema>;

/**
 * Simulator Preview Request Schema
 */
export const ScoringSimulationRequestSchema = z.object({
  questionType: z.enum(['SINGLE_SELECT', 'MULTI_SELECT', 'NUMERICAL', 'SUBJECTIVE', 'UNKNOWN']),
  expectedAnswer: z.string(),
  submittedAnswer: z.string().nullable().optional(),
  numericalTolerance: z.number().nonnegative().nullable().optional(),
  correctScaledMark: ScaledMarkSchema.default(400),
  incorrectScaledMark: ScaledMarkSchema.default(-100),
  unansweredScaledMark: ScaledMarkSchema.default(0),
  allowNegativeMarking: z.boolean().default(true),
  multiSelectPolicy: MultiSelectPolicySchema.default('EXACT_MATCH')
});

export type ScoringSimulationInput = z.input<typeof ScoringSimulationRequestSchema>;
export type ScoringSimulationRequest = z.infer<typeof ScoringSimulationRequestSchema>;

/**
 * Simulator Preview Result Schema
 */
export const ScoringSimulationResultSchema = z.object({
  questionType: z.string(),
  classification: AnswerClassificationSchema,
  marksAwarded: ScaledMarkSchema,
  maxPossibleMarks: ScaledMarkSchema,
  explanationCode: ScoringExplanationCodeSchema,
  calculationMetadata: z.string().nullable().optional(),
  effectiveRule: z.object({
    correctScaledMark: ScaledMarkSchema,
    incorrectScaledMark: ScaledMarkSchema,
    unansweredScaledMark: ScaledMarkSchema,
    allowNegativeMarking: z.boolean(),
    multiSelectPolicy: MultiSelectPolicySchema
  })
});

export type ScoringSimulationResult = z.infer<typeof ScoringSimulationResultSchema>;

/**
 * Client-Side Preview Simulator Helper.
 * NOTE: Strictly for examiner preview/simulation in the UI; authoritative grading
 * is executed exclusively on the offline Kotlin server.
 */
export function simulateQuestionScoring(
  input: ScoringSimulationInput
): ScoringSimulationResult {
  const req = ScoringSimulationRequestSchema.parse(input);
  const {
    questionType,
    expectedAnswer,
    submittedAnswer,
    numericalTolerance,
    correctScaledMark,
    incorrectScaledMark,
    unansweredScaledMark,
    allowNegativeMarking,
    multiSelectPolicy
  } = req;

  const negativePenalty = allowNegativeMarking ? -Math.abs(incorrectScaledMark) : 0;
  const effectiveRule = {
    correctScaledMark,
    incorrectScaledMark: negativePenalty,
    unansweredScaledMark,
    allowNegativeMarking,
    multiSelectPolicy
  };

  const rawSubmitted = submittedAnswer?.trim();
  const isSubmittedEmpty = !rawSubmitted || rawSubmitted === '[]' || rawSubmitted === '""';

  if (isSubmittedEmpty) {
    return {
      questionType,
      classification: 'UNANSWERED',
      marksAwarded: unansweredScaledMark,
      maxPossibleMarks: correctScaledMark,
      explanationCode: 'NO_RESPONSE',
      effectiveRule
    };
  }

  switch (questionType) {
    case 'SINGLE_SELECT': {
      const normalizedSubmitted = rawSubmitted.replace(/[\[\]"]/g, '').trim().toUpperCase();
      const expectedNormalized = expectedAnswer.replace(/[\[\]"]/g, '').trim().toUpperCase();

      if (!normalizedSubmitted) {
        return {
          questionType,
          classification: 'UNANSWERED',
          marksAwarded: unansweredScaledMark,
          maxPossibleMarks: correctScaledMark,
          explanationCode: 'NO_RESPONSE',
          effectiveRule
        };
      }

      const isMatch = normalizedSubmitted === expectedNormalized;
      return {
        questionType,
        classification: isMatch ? 'CORRECT' : 'INCORRECT',
        marksAwarded: isMatch ? correctScaledMark : negativePenalty,
        maxPossibleMarks: correctScaledMark,
        explanationCode: isMatch ? 'MATCHED_VERIFIED_ANSWER' : 'ANSWER_MISMATCH',
        effectiveRule
      };
    }

    case 'MULTI_SELECT': {
      const parseOptions = (str: string): Set<string> => {
        try {
          const parsed = JSON.parse(str);
          if (Array.isArray(parsed)) {
            return new Set(parsed.map(x => String(x).trim().toUpperCase()).filter(Boolean));
          }
        } catch {
          // Fallback comma split
        }
        return new Set(str.replace(/[\[\]"]/g, '').split(',').map(s => s.trim().toUpperCase()).filter(Boolean));
      };

      const candSet = parseOptions(rawSubmitted);
      const expectedSet = parseOptions(expectedAnswer);

      if (candSet.size === 0) {
        return {
          questionType,
          classification: 'UNANSWERED',
          marksAwarded: unansweredScaledMark,
          maxPossibleMarks: correctScaledMark,
          explanationCode: 'NO_RESPONSE',
          effectiveRule
        };
      }

      if (multiSelectPolicy === 'EXACT_MATCH') {
        const isMatch = candSet.size === expectedSet.size && [...candSet].every(x => expectedSet.has(x));
        return {
          questionType,
          classification: isMatch ? 'CORRECT' : 'INCORRECT',
          marksAwarded: isMatch ? correctScaledMark : negativePenalty,
          maxPossibleMarks: correctScaledMark,
          explanationCode: isMatch ? 'MATCHED_VERIFIED_ANSWER' : 'ANSWER_MISMATCH',
          effectiveRule
        };
      } else {
        // PARTIAL_CREDIT
        const incorrectCount = [...candSet].filter(x => !expectedSet.has(x)).length;
        if (incorrectCount > 0) {
          return {
            questionType,
            classification: 'INCORRECT',
            marksAwarded: negativePenalty,
            maxPossibleMarks: correctScaledMark,
            explanationCode: 'ANSWER_MISMATCH',
            effectiveRule
          };
        }

        const correctCount = [...candSet].filter(x => expectedSet.has(x)).length;
        const totalExpected = expectedSet.size;
        if (totalExpected === 0) {
          return {
            questionType,
            classification: 'UNGRADABLE',
            marksAwarded: 0,
            maxPossibleMarks: correctScaledMark,
            explanationCode: 'UNVERIFIED_ANSWER_KEY',
            effectiveRule
          };
        }

        if (correctCount === totalExpected) {
          return {
            questionType,
            classification: 'CORRECT',
            marksAwarded: correctScaledMark,
            maxPossibleMarks: correctScaledMark,
            explanationCode: 'MATCHED_VERIFIED_ANSWER',
            effectiveRule
          };
        }

        const earned = Math.floor((correctCount * correctScaledMark) / totalExpected);
        return {
          questionType,
          classification: 'PARTIALLY_CORRECT',
          marksAwarded: earned,
          maxPossibleMarks: correctScaledMark,
          explanationCode: 'PARTIAL_CREDIT_RULE_APPLIED',
          effectiveRule,
          calculationMetadata: JSON.stringify({ correctCount, totalExpected, earnedScaled: earned })
        };
      }
    }

    case 'NUMERICAL': {
      const cleanNum = rawSubmitted.replace(',', '.').replace(/[\[\]"]/g, '').trim();
      const candVal = parseFloat(cleanNum);
      if (isNaN(candVal)) {
        return {
          questionType,
          classification: 'INVALID_RESPONSE',
          marksAwarded: 0,
          maxPossibleMarks: correctScaledMark,
          explanationCode: 'INVALID_RESPONSE_FORMAT',
          effectiveRule
        };
      }

      const expectedClean = expectedAnswer.replace(',', '.').replace(/[\[\]"]/g, '').trim();
      const targetVal = parseFloat(expectedClean);
      if (isNaN(targetVal)) {
        return {
          questionType,
          classification: 'UNGRADABLE',
          marksAwarded: 0,
          maxPossibleMarks: correctScaledMark,
          explanationCode: 'MISSING_ANSWER_KEY',
          effectiveRule
        };
      }

      const tolerance = Math.max(0, numericalTolerance ?? 0);
      const diff = Math.abs(candVal - targetVal);
      const isWithinTolerance = diff <= tolerance + 1e-9;

      return {
        questionType,
        classification: isWithinTolerance ? 'CORRECT' : 'INCORRECT',
        marksAwarded: isWithinTolerance ? correctScaledMark : negativePenalty,
        maxPossibleMarks: correctScaledMark,
        explanationCode: isWithinTolerance ? 'NUMERICAL_TOLERANCE_MATCH' : 'ANSWER_MISMATCH',
        calculationMetadata: JSON.stringify({ diff, tolerance }),
        effectiveRule
      };
    }

    case 'SUBJECTIVE': {
      return {
        questionType,
        classification: 'PENDING_MANUAL_GRADING',
        marksAwarded: 0,
        maxPossibleMarks: correctScaledMark,
        explanationCode: 'MANUAL_GRADING_REQUIRED',
        effectiveRule
      };
    }

    default: {
      return {
        questionType,
        classification: 'UNGRADABLE',
        marksAwarded: 0,
        maxPossibleMarks: correctScaledMark,
        explanationCode: 'UNSUPPORTED_QUESTION_TYPE',
        effectiveRule
      };
    }
  }
}
