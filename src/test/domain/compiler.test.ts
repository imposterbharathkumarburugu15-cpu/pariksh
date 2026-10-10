import { describe, it, expect } from 'vitest';
import {
  QuestionTypeSchema,
  AnswerOptionSchema,
  PaperSectionSchema,
  StructuredQuestionSchema,
  QuestionAnswerKeySchema,
  WorkedSolutionSchema,
  PaperValidationIssueSchema,
  PaperImportRecordSchema,
  ExamDraftSchema,
  evaluatePublicationGate,
  type ExamDraft
} from '../../domain/compiler';

describe('Exam Paper Compiler Domain Schemas', () => {
  const nowIso = new Date().toISOString();

  it('validates supported question types', () => {
    expect(QuestionTypeSchema.parse('SINGLE_SELECT')).toBe('SINGLE_SELECT');
    expect(QuestionTypeSchema.parse('MULTI_SELECT')).toBe('MULTI_SELECT');
    expect(QuestionTypeSchema.parse('NUMERICAL')).toBe('NUMERICAL');
    expect(QuestionTypeSchema.parse('SUBJECTIVE')).toBe('SUBJECTIVE');
    expect(QuestionTypeSchema.parse('UNKNOWN')).toBe('UNKNOWN');
    expect(() => QuestionTypeSchema.parse('INVALID_TYPE')).toThrow();
  });

  it('validates answer options with non-empty label and text', () => {
    const validOption = AnswerOptionSchema.parse({
      optionId: 'opt_001',
      label: 'A',
      text: 'Option A text'
    });
    expect(validOption.label).toBe('A');

    expect(() =>
      AnswerOptionSchema.parse({
        optionId: 'opt_001',
        label: '',
        text: 'Text'
      })
    ).toThrow();
  });

  it('validates structured questions with options and source trace', () => {
    const question = StructuredQuestionSchema.parse({
      questionId: 'q_001',
      examId: 'exam_001',
      sectionId: 'sec_001',
      questionNumber: 1,
      questionType: 'SINGLE_SELECT',
      questionText: 'What is the velocity of light in vacuum?',
      answerOptions: [
        { optionId: 'opt_a', label: 'A', text: '3 x 10^8 m/s' },
        { optionId: 'opt_b', label: 'B', text: '3 x 10^6 m/s' }
      ],
      sourcePage: 1,
      sourceStartLine: 12,
      sourceEndLine: 18,
      sourceSnippet: '1. What is the velocity...',
      extractionConfidence: 'HIGH',
      reviewStatus: 'NEEDS_REVIEW',
      createdAt: nowIso,
      updatedAt: nowIso
    });

    expect(question.questionNumber).toBe(1);
    expect(question.answerOptions).toHaveLength(2);
  });

  it('validates paper sections with positive order', () => {
    const section = PaperSectionSchema.parse({
      sectionId: 'sec_physics',
      examId: 'exam_001',
      title: 'Physics Section 1',
      sectionOrder: 1,
      sourceReference: 'Page 1, Header',
      createdAt: nowIso
    });
    expect(section.sectionOrder).toBe(1);

    expect(() =>
      PaperSectionSchema.parse({
        sectionId: 'sec_002',
        examId: 'exam_001',
        title: '',
        sectionOrder: 0,
        createdAt: nowIso
      })
    ).toThrow();
  });

  it('validates confidential answer keys for multiple-choice and numerical questions', () => {
    // Single choice
    const mcqKey = QuestionAnswerKeySchema.parse({
      questionId: 'q_001',
      examId: 'exam_001',
      expectedAnswer: 'A',
      answerKeySource: 'EXTRACTED',
      verificationStatus: 'UNVERIFIED',
      updatedAt: nowIso
    });
    expect(mcqKey.expectedAnswer).toBe('A');

    // Multi-choice array
    const multiKey = QuestionAnswerKeySchema.parse({
      questionId: 'q_002',
      examId: 'exam_001',
      expectedAnswer: ['A', 'C'],
      answerKeySource: 'MANUAL',
      verificationStatus: 'VERIFIED',
      reviewerId: 'usr_admin',
      verifiedAt: nowIso,
      updatedAt: nowIso
    });
    expect(multiKey.expectedAnswer).toEqual(['A', 'C']);

    // Numerical with tolerance
    const numKey = QuestionAnswerKeySchema.parse({
      questionId: 'q_003',
      examId: 'exam_001',
      expectedAnswer: '42.5',
      numericalTolerance: 0.1,
      answerKeySource: 'EXTRACTED',
      verificationStatus: 'UNVERIFIED',
      updatedAt: nowIso
    });
    expect(numKey.numericalTolerance).toBe(0.1);
  });

  it('validates worked solutions', () => {
    const solution = WorkedSolutionSchema.parse({
      solutionId: 'sol_001',
      questionId: 'q_001',
      examId: 'exam_001',
      solutionText: 'The standard speed of light c = 299,792,458 m/s, approximately 3 x 10^8 m/s.',
      verificationStatus: 'VERIFIED',
      reviewerId: 'usr_reviewer',
      verifiedAt: nowIso,
      updatedAt: nowIso
    });
    expect(solution.verificationStatus).toBe('VERIFIED');
  });

  it('validates paper import records and enforces maximum 25MB file limit', () => {
    const validImport = PaperImportRecordSchema.parse({
      importId: 'imp_001',
      originalFilename: 'jee_advanced_paper_1.pdf',
      fileType: 'PDF',
      originalFileHash: 'a'.repeat(64),
      fileSizeBytes: 5 * 1024 * 1024, // 5 MB
      privateStoragePath: '/data/user/0/com.doomedams.app/files/imports/imp_001.pdf',
      importStatus: 'EXTRACTED',
      extractedAt: nowIso,
      createdBy: 'usr_admin',
      createdAt: nowIso,
      updatedAt: nowIso
    });
    expect(validImport.fileType).toBe('PDF');

    // Oversized > 25 MB
    expect(() =>
      PaperImportRecordSchema.parse({
        ...validImport,
        fileSizeBytes: 30 * 1024 * 1024
      })
    ).toThrow();
  });

  it('validates validation issues with severity levels', () => {
    const issue = PaperValidationIssueSchema.parse({
      issueId: 'iss_001',
      importId: 'imp_001',
      questionId: 'q_002',
      severity: 'WARNING',
      issueCode: 'AMBIGUOUS_QUESTION_BOUNDARY',
      description: 'Question boundary between Q2 and Q3 may be uncertain.',
      isResolved: false
    });
    expect(issue.severity).toBe('WARNING');
    expect(issue.isResolved).toBe(false);
  });

  describe('evaluatePublicationGate', () => {
    const createBaseDraft = (overrides?: Partial<ExamDraft>): ExamDraft => ({
      examId: 'exam_test_001',
      title: 'Mathematics Term Examination',
      instituteId: 'inst_001',
      durationSeconds: 10800, // 3 hours
      status: 'DRAFT',
      markingSchemeVersion: 1,
      sections: [
        {
          sectionId: 'sec_001',
          examId: 'exam_test_001',
          title: 'Section A: Multiple Choice',
          sectionOrder: 1,
          createdAt: nowIso
        }
      ],
      questions: [
        {
          questionId: 'q_001',
          examId: 'exam_test_001',
          sectionId: 'sec_001',
          questionNumber: 1,
          questionType: 'SINGLE_SELECT',
          questionText: 'Evaluate integral of x dx from 0 to 2.',
          answerOptions: [
            { optionId: 'opt_1', label: 'A', text: '2' },
            { optionId: 'opt_2', label: 'B', text: '4' }
          ],
          extractionConfidence: 'HIGH',
          reviewStatus: 'VERIFIED',
          createdAt: nowIso,
          updatedAt: nowIso
        }
      ],
      answerKeys: [
        {
          questionId: 'q_001',
          examId: 'exam_test_001',
          expectedAnswer: 'A',
          answerKeySource: 'MANUAL',
          verificationStatus: 'VERIFIED',
          updatedAt: nowIso
        }
      ],
      solutions: [
        {
          solutionId: 'sol_001',
          questionId: 'q_001',
          examId: 'exam_test_001',
          solutionText: '[x^2 / 2]_0^2 = 4/2 - 0 = 2.',
          verificationStatus: 'VERIFIED',
          updatedAt: nowIso
        }
      ],
      validationIssues: [],
      createdAt: nowIso,
      updatedAt: nowIso,
      ...overrides
    });

    it('validates a complete exam draft schema', () => {
      const draft = createBaseDraft();
      const parsed = ExamDraftSchema.parse(draft);
      expect(parsed.examId).toBe('exam_test_001');
    });

    it('approves a fully verified and consistent draft', () => {
      const draft = createBaseDraft();
      const result = evaluatePublicationGate(draft);
      expect(result.isReady).toBe(true);
      expect(result.blockingErrors).toHaveLength(0);
      expect(result.unverifiedAnswerKeyCount).toBe(0);
    });

    it('blocks publication if question list is empty', () => {
      const draft = createBaseDraft({ questions: [], answerKeys: [] });
      const result = evaluatePublicationGate(draft);
      expect(result.isReady).toBe(false);
      expect(result.blockingErrors).toContain('The examination contains zero questions.');
    });

    it('blocks publication if duration is zero or negative', () => {
      const draft = createBaseDraft({ durationSeconds: 0 });
      const result = evaluatePublicationGate(draft);
      expect(result.isReady).toBe(false);
      expect(result.blockingErrors).toContain(
        'Examination duration must be a positive number of seconds.'
      );
    });

    it('blocks publication if duplicate question numbers exist', () => {
      const base = createBaseDraft();
      const draft: ExamDraft = {
        ...base,
        questions: [
          ...base.questions,
          {
            ...base.questions[0],
            questionId: 'q_002',
            questionNumber: 1 // duplicate!
          }
        ] as any,
        answerKeys: [
          ...base.answerKeys,
          {
            questionId: 'q_002',
            examId: 'exam_test_001',
            expectedAnswer: 'B',
            answerKeySource: 'MANUAL',
            verificationStatus: 'VERIFIED',
            updatedAt: nowIso
          }
        ]
      };
      const result = evaluatePublicationGate(draft);
      expect(result.isReady).toBe(false);
      expect(result.blockingErrors.some((e) => e.includes('Duplicate question number'))).toBe(true);
    });

    it('blocks publication if multiple choice question has fewer than 2 options', () => {
      const base = createBaseDraft();
      const draft: ExamDraft = {
        ...base,
        questions: [
          {
            ...base.questions[0],
            answerOptions: [{ optionId: 'opt_1', label: 'A', text: 'Single Option' }]
          }
        ] as any
      };
      const result = evaluatePublicationGate(draft);
      expect(result.isReady).toBe(false);
      expect(result.blockingErrors.some((e) => e.includes('must have at least 2 options'))).toBe(
        true
      );
    });

    it('blocks publication if question is missing an answer key', () => {
      const draft = createBaseDraft({ answerKeys: [] });
      const result = evaluatePublicationGate(draft);
      expect(result.isReady).toBe(false);
      expect(result.blockingErrors.some((e) => e.includes('missing an answer key'))).toBe(true);
    });

    it('blocks publication if unresolved ERROR severity issues exist', () => {
      const draft = createBaseDraft({
        validationIssues: [
          {
            issueId: 'iss_blocking',
            severity: 'ERROR',
            issueCode: 'CONFIDENTIAL_KEY_MISSING',
            description: 'Critical missing key reference',
            isResolved: false
          }
        ]
      });
      const result = evaluatePublicationGate(draft);
      expect(result.isReady).toBe(false);
      expect(result.blockingErrors.some((e) => e.includes('Unresolved blocking issue'))).toBe(true);
    });

    it('warns but does not block if only WARNING severity issues exist', () => {
      const draft = createBaseDraft({
        validationIssues: [
          {
            issueId: 'iss_warn',
            severity: 'WARNING',
            issueCode: 'POSSIBLE_IMAGE_FORMULA',
            description: 'Question may contain diagram image',
            isResolved: false
          }
        ]
      });
      const result = evaluatePublicationGate(draft);
      expect(result.isReady).toBe(true);
      expect(result.warnings.some((w) => w.includes('POSSIBLE_IMAGE_FORMULA'))).toBe(true);
    });
  });
});
