import { ExamDraft, StructuredQuestion, AnswerOption, QuestionAnswerKey, WorkedSolution, PaperValidationIssue, PaperSection } from '../domain/compiler';

/**
 * Calculates SHA-256 hash of a file buffer using Web Crypto API.
 */
export async function calculateSha256(buffer: ArrayBuffer): Promise<string> {
  const hashBuffer = await crypto.subtle.digest('SHA-256', buffer);
  const hashArray = Array.from(new Uint8Array(hashBuffer));
  return hashArray.map((b) => b.toString(16).padStart(2, '0')).join('');
}

export interface ParseResult {
  draft: ExamDraft;
  unclassifiedLines: string[];
  extractionStats: {
    totalLines: number;
    detectedQuestions: number;
    detectedSections: number;
    detectedAnswerKeys: number;
    detectedSolutions: number;
  };
}

/**
 * Deterministic offline rule-based parser for examination papers.
 * Never invents missing questions, options, or answers.
 * Any ambiguity generates validation warnings or unresolved issues.
 */
export function parsePlainTextExam(
  text: string,
  fileName: string,
  fileHash: string,
  fileSize: number,
  instituteId: string = 'inst_001',
  authorId: string = 'usr_admin_001'
): ParseResult {
  const lines = text.split(/\r?\n/);
  const now = new Date().toISOString();
  const examId = `exam_${Date.now()}`;

  const sections: PaperSection[] = [];
  const questions: StructuredQuestion[] = [];
  const answerKeys: QuestionAnswerKey[] = [];
  const solutions: WorkedSolution[] = [];
  const validationIssues: PaperValidationIssue[] = [];
  const unclassifiedLines: string[] = [];

  let currentSection: PaperSection | null = null;
  let currentQuestion: StructuredQuestion | null = null;
  let currentOptions: AnswerOption[] = [];
  let currentAnswerKey: QuestionAnswerKey | null = null;
  let currentSolution: WorkedSolution | null = null;

  let questionIndex = 1;

  const sectionRegex = /^(?:SECTION|PART)\s+([A-Z0-9]+)[:\-.]?\s*(.*)$/i;
  const questionRegex = /^(?:Q(?:uestion)?\.?\s*(\d+)[\.:\)]|\b(\d+)[\.:\)])\s*(.*)$/i;
  const optionRegex = /^[\(\[]?([A-D])[\)\]\.\:]\s*(.*)$/i;
  const answerRegex = /^(?:ANS(?:WER)?|KEY)\s*[:\-.]?\s*([A-D0-9\.\-\s,]+)$/i;
  const solutionRegex = /^(?:SOLUTION|EXPLANATION)\s*[:\-.]?\s*(.*)$/i;

  const finalizeQuestion = () => {
    if (currentQuestion) {
      currentQuestion.answerOptions = [...currentOptions];

      // Auto-classify type if options exist
      if (currentOptions.length >= 2) {
        if (currentQuestion.questionType === 'UNKNOWN') {
          currentQuestion.questionType = 'SINGLE_SELECT';
        }
      } else if (currentOptions.length === 0) {
        const lowerStem = currentQuestion.questionText.toLowerCase();
        if (lowerStem.includes('calculate') || lowerStem.includes('evaluate') || lowerStem.includes('value of')) {
          currentQuestion.questionType = 'NUMERICAL';
        } else {
          currentQuestion.questionType = 'SUBJECTIVE';
        }
      }

      // Check for zero options on select questions
      if ((currentQuestion.questionType === 'SINGLE_SELECT' || currentQuestion.questionType === 'MULTI_SELECT') && currentOptions.length === 0) {
        validationIssues.push({
          issueId: `iss_${Date.now()}_${Math.random().toString(36).substring(2, 7)}`,
          questionId: currentQuestion.questionId,
          severity: 'ERROR',
          issueCode: 'ZERO_ANSWER_OPTIONS',
          description: `Multiple choice question Q${currentQuestion.questionNumber} has zero answer options detected.`,
          sourceReference: `Line ${currentQuestion.sourceStartLine}`,
          isResolved: false
        });
      }

      questions.push(currentQuestion);

      if (currentAnswerKey) {
        answerKeys.push(currentAnswerKey);
      } else {
        validationIssues.push({
          issueId: `iss_${Date.now()}_${Math.random().toString(36).substring(2, 7)}`,
          questionId: currentQuestion.questionId,
          severity: 'WARNING',
          issueCode: 'MISSING_ANSWER_KEY',
          description: `Question Q${currentQuestion.questionNumber} does not have an extracted answer key. Verification required.`,
          sourceReference: `Line ${currentQuestion.sourceStartLine}`,
          isResolved: false
        });
      }

      if (currentSolution) {
        solutions.push(currentSolution);
      }
    }

    currentQuestion = null;
    currentOptions = [];
    currentAnswerKey = null;
    currentSolution = null;
  };

  for (let i = 0; i < lines.length; i++) {
    const rawLine = lines[i] ?? '';
    const line = rawLine.trim();
    const lineNum = i + 1;

    if (!line) continue;

    // Check Section header
    const secMatch = line.match(sectionRegex);
    if (secMatch) {
      finalizeQuestion();
      const secCode = secMatch[1] ?? 'A';
      const secTitle = secMatch[2]?.trim() || `Section ${secCode}`;
      const secId = `sec_${examId}_${sections.length + 1}`;
      currentSection = {
        sectionId: secId,
        examId,
        title: secTitle,
        sectionOrder: sections.length + 1,
        sourceReference: `Line ${lineNum}`,
        createdAt: now
      };
      sections.push(currentSection);
      continue;
    }

    // Check Question header
    const qMatch = line.match(questionRegex);
    if (qMatch) {
      finalizeQuestion();
      const numStr = qMatch[1] ?? qMatch[2] ?? `${questionIndex}`;
      const detectedNum = parseInt(numStr, 10);
      const stem = qMatch[3]?.trim() || '';

      const qId = `q_${examId}_${detectedNum || questionIndex}`;
      currentQuestion = {
        questionId: qId,
        examId,
        sectionId: currentSection?.sectionId,
        questionNumber: detectedNum || questionIndex,
        questionType: 'UNKNOWN',
        questionText: stem,
        answerOptions: [],
        sourceStartLine: lineNum,
        sourceEndLine: lineNum,
        sourceSnippet: line.substring(0, 100),
        extractionConfidence: 'HIGH',
        reviewStatus: 'NEEDS_REVIEW',
        createdAt: now,
        updatedAt: now
      };
      questionIndex++;
      continue;
    }

    // Check Answer Option
    const optMatch = line.match(optionRegex);
    if (optMatch && currentQuestion) {
      const label = (optMatch[1] ?? 'A').toUpperCase();
      const optText = optMatch[2]?.trim() || '';
      currentOptions.push({
        optionId: `opt_${currentQuestion.questionId}_${label.toLowerCase()}`,
        label,
        text: optText
      });
      currentQuestion.sourceEndLine = lineNum;
      continue;
    }

    // Check Answer Key
    const ansMatch = line.match(answerRegex);
    if (ansMatch && currentQuestion) {
      const rawAns = (ansMatch[1] ?? '').trim();
      const expectedAns = rawAns.includes(',')
        ? rawAns.split(',').map((s) => s.trim())
        : rawAns;

      currentAnswerKey = {
        questionId: currentQuestion.questionId,
        examId,
        expectedAnswer: expectedAns,
        answerKeySource: 'EXTRACTED',
        verificationStatus: 'UNVERIFIED',
        updatedAt: now
      };
      continue;
    }

    // Check Solution
    const solMatch = line.match(solutionRegex);
    if (solMatch && currentQuestion) {
      const solText = solMatch[1]?.trim() || '';
      currentSolution = {
        solutionId: `sol_${currentQuestion.questionId}`,
        questionId: currentQuestion.questionId,
        examId,
        solutionText: solText,
        sourceReference: `Line ${lineNum}`,
        verificationStatus: 'UNVERIFIED',
        updatedAt: now
      };
      continue;
    }

    // Append continuation text
    if (currentSolution) {
      currentSolution.solutionText += ` ${line}`;
    } else if (currentOptions.length > 0) {
      const lastOpt = currentOptions[currentOptions.length - 1];
      if (lastOpt) {
        lastOpt.text += ` ${line}`;
      }
    } else if (currentQuestion) {
      currentQuestion.questionText += ` ${line}`;
      currentQuestion.sourceEndLine = lineNum;
    } else {
      unclassifiedLines.push(`Line ${lineNum}: ${line}`);
    }
  }

  finalizeQuestion();

  // If unclassified lines exist, record an INFO issue
  if (unclassifiedLines.length > 0) {
    validationIssues.push({
      issueId: `iss_${Date.now()}_unclass`,
      severity: 'INFO',
      issueCode: 'UNCLASSIFIED_TEXT_FOUND',
      description: `${unclassifiedLines.length} lines of text could not be mapped to questions or sections. Review unclassified content.`,
      isResolved: false
    });
  }

  const draft: ExamDraft = {
    examId,
    title: fileName.replace(/\.[^/.]+$/, '').replace(/[-_]/g, ' ') || 'Imported Examination Draft',
    instituteId,
    durationSeconds: 10800, // 3 hours default (180 mins)
    status: 'DRAFT',
    markingSchemeVersion: 1,
    sections,
    questions,
    answerKeys,
    solutions,
    validationIssues,
    importRecord: {
      importId: `imp_${Date.now()}`,
      originalFilename: fileName,
      fileType: fileName.toLowerCase().endsWith('.pdf') ? 'PDF' : 'TXT',
      originalFileHash: fileHash,
      fileSizeBytes: fileSize,
      privateStoragePath: `/data/user/0/com.doomedams.app/app_papers/${examId}/${fileName}`,
      importStatus: 'EXTRACTED',
      extractedAt: now,
      createdBy: authorId,
      createdAt: now,
      updatedAt: now
    },
    createdAt: now,
    updatedAt: now
  };

  return {
    draft,
    unclassifiedLines,
    extractionStats: {
      totalLines: lines.length,
      detectedQuestions: questions.length,
      detectedSections: sections.length,
      detectedAnswerKeys: answerKeys.length,
      detectedSolutions: solutions.length
    }
  };
}
