import { describe, it, expect } from 'vitest';
import {
  ExamSchema,
  ExamStatusSchema,
  MarkingSchemeSchema
} from '../../domain/exams';

describe('Examination Schemas', () => {
  const validTimestamp = '2026-10-09T12:00:00Z';

  const validMarkingScheme = {
    schemeId: 'scheme_standard_v1',
    version: 1,
    defaultCorrectScaledMark: 400, // +4.00 marks
    defaultIncorrectScaledMark: -100, // -1.00 negative mark
    defaultUnansweredScaledMark: 0,
    allowNegativeMarking: true,
    sectionDefaults: [],
    questionOverrides: [
      {
        questionId: 'q_bonus_01',
        correctScaledMark: 500,
        incorrectScaledMark: 0,
        unansweredScaledMark: 0
      }
    ]
  };

  it('validates a complete, compliant exam schema', () => {
    const validExam = {
      examId: 'exam_phy_101',
      title: 'Physics Midterm Offline 2026',
      instituteId: 'inst_apex',
      durationSeconds: 7200, // 2 hours
      status: 'PUBLISHED',
      createdAt: validTimestamp,
      publishedAt: validTimestamp,
      markingSchemeVersion: 1,
      markingScheme: validMarkingScheme,
      questionReferences: [
        { questionId: 'q_001', sequenceOrder: 1 },
        { questionId: 'q_002', sequenceOrder: 2 }
      ]
    };

    const parsed = ExamSchema.safeParse(validExam);
    expect(parsed.success).toBe(true);
    if (parsed.success) {
      expect(parsed.data.durationSeconds).toBe(7200);
      expect(parsed.data.markingScheme.allowNegativeMarking).toBe(true);
    }
  });

  it('rejects non-positive exam durations', () => {
    const invalidExamZero = {
      examId: 'exam_01',
      title: 'Zero duration exam',
      instituteId: 'inst_01',
      durationSeconds: 0,
      status: 'DRAFT',
      createdAt: validTimestamp,
      markingSchemeVersion: 1,
      markingScheme: validMarkingScheme,
      questionReferences: [{ questionId: 'q_001', sequenceOrder: 1 }]
    };

    expect(ExamSchema.safeParse(invalidExamZero).success).toBe(false);

    const invalidExamNegative = {
      ...invalidExamZero,
      durationSeconds: -600
    };
    expect(ExamSchema.safeParse(invalidExamNegative).success).toBe(false);
  });

  it('rejects invalid examination statuses', () => {
    expect(ExamStatusSchema.safeParse('UNKNOWN_STATUS').success).toBe(false);
    expect(ExamStatusSchema.safeParse('DELETED').success).toBe(false);

    const validStatuses = ['DRAFT', 'PUBLISHED', 'IN_PROGRESS', 'COMPLETED', 'ARCHIVED'];
    for (const status of validStatuses) {
      expect(ExamStatusSchema.safeParse(status).success).toBe(true);
    }
  });

  it('rejects exams with empty question references', () => {
    const emptyQuestionsExam = {
      examId: 'exam_02',
      title: 'Empty exam',
      instituteId: 'inst_01',
      durationSeconds: 3600,
      status: 'DRAFT',
      createdAt: validTimestamp,
      markingSchemeVersion: 1,
      markingScheme: validMarkingScheme,
      questionReferences: []
    };

    expect(ExamSchema.safeParse(emptyQuestionsExam).success).toBe(false);
  });

  it('enforces scaled integer precision on marking schemes', () => {
    const floatingMarkScheme = {
      schemeId: 'scheme_float',
      version: 1,
      defaultCorrectScaledMark: 4.5, // Not an integer!
      defaultIncorrectScaledMark: -1,
      defaultUnansweredScaledMark: 0,
      allowNegativeMarking: true
    };

    expect(MarkingSchemeSchema.safeParse(floatingMarkScheme).success).toBe(false);
  });
});
