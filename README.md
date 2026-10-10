# DOOMEDAMS

Offline-First Examination Platform for coaching institutes, colleges, and examination branches.

## Architecture & Technology Stack

- **Frontend:** React 19 + Vite + TypeScript (Strict mode enabled)
- **Styling:** Standard Vanilla CSS (`src/styles/global.css`); Tailwind CSS is strictly prohibited.
- **Domain Validation:** Zod schemas (`src/domain/`)
- **Native Android Shell:** Capacitor (`android/`)
- **Native Persistence:** Android Room (SQLite) with strict foreign keys, transactional boundaries, and append-only immutability triggers.
- **Audit Engine:** SHA-256 cryptographic hash-chained audit engine (`com.doomedams.app.database.audit.AuditEngine`).
- **Target Platform:** Offline Android APK deployment with examiner device acting as the trusted local node.

---

## Directory Structure

```
doomdam/
├── .gitignore
├── README.md
├── package.json
├── index.html
├── capacitor.config.ts
├── vite.config.ts
├── tsconfig.json
├── src/
│   ├── main.tsx
│   ├── App.tsx
│   ├── styles/
│   │   └── global.css
│   ├── domain/
│   │   ├── common.ts
│   │   ├── users.ts
│   │   ├── exams.ts
│   │   ├── attempts.ts
│   │   ├── integrity-events.ts
│   │   ├── incidents.ts
│   │   ├── audit.ts
│   │   └── index.ts
│   └── test/
│       └── domain/
│           ├── users.test.ts
│           ├── exams.test.ts
│           ├── attempts.test.ts
│           ├── integrity-events.test.ts
│           ├── incidents.test.ts
│           └── audit.test.ts
└── android/
    ├── app/
    │   ├── build.gradle
    │   └── src/
    │       ├── main/
    │       │   └── java/com/doomedams/app/
    │       │       ├── MainActivity.java
    │       │       └── database/
    │       │           ├── AppDatabase.kt
    │       │           ├── entity/
    │       │           │   ├── UserEntity.kt
    │       │           │   ├── ExamEntity.kt
    │       │           │   ├── ExamAttemptEntity.kt
    │       │           │   ├── CandidateResponseEntity.kt
    │       │           │   ├── IntegrityEventEntity.kt
    │       │           │   ├── IncidentCaseEntity.kt
    │       │           │   ├── IncidentDecisionEntity.kt
    │       │           │   ├── PolicyActionEntity.kt
    │       │           │   ├── AuditEventEntity.kt
    │       │           │   └── SyncOutboxEntity.kt
    │       │           ├── dao/
    │       │           │   ├── UserDao.kt
    │       │           │   ├── ExamDao.kt
    │       │           │   ├── ExamAttemptDao.kt
    │       │           │   ├── CandidateResponseDao.kt
    │       │           │   ├── IntegrityEventDao.kt
    │       │           │   ├── IncidentDao.kt
    │       │           │   ├── AuditEventDao.kt
    │       │           │   └── SyncOutboxDao.kt
    │       │           ├── audit/
    │       │           │   └── AuditEngine.kt
    │       │           └── repository/
    │       │               ├── ExamRepository.kt
    │       │               ├── AttemptRepository.kt
    │       │               ├── IntegrityEventRepository.kt
    │       │               └── IncidentRepository.kt
    │       └── test/
    │           └── java/com/doomedams/app/database/
    │               └── RoomDatabaseTest.kt
    ├── build.gradle
    └── variables.gradle
```

---

## Room Database & Persistence Architecture

The native Android database is implemented via Room (`AppDatabase.kt`) under namespace `com.doomedams.app.database`:

1. **Foreign-Key Constraints:** Strictly enforced (`PRAGMA foreign_keys = ON;`).
2. **Decoupled Attempt and Result Status:**
   - In `ExamAttemptEntity`, `attemptStatus` (e.g. `SUBMITTED`) and `resultStatus` (e.g. `HELD_PENDING_REVIEW`) are separate columns.
   - An administrative hold updates only `resultStatus`, preserving the attempt's lifecycle.
3. **Database-Level Immutability Triggers:**
   - Native SQLite triggers (`trg_prevent_audit_update`, `trg_prevent_audit_delete`) abort any `UPDATE` or `DELETE` statements on the `audit_events` table.
4. **Entities:**
   - `UserEntity`: User roles (`STUDENT`, `INVIGILATOR`, `INCIDENT_REVIEWER`, `EXAMINATION_ADMIN`, etc.).
   - `ExamEntity`: Exam definition with strictly positive durations.
   - `ExamAttemptEntity`: Attempt lifecycle, timing metadata, and decoupled result status.
   - `CandidateResponseEntity`: Question responses and revision counters.
   - `IntegrityEventEntity`: Integrity telemetry; optional package/app name fields accommodate Android sandbox limits.
   - `IncidentCaseEntity`: Formal incident review tracking.
   - `IncidentDecisionEntity`: Append-only history of adjudications.
   - `PolicyActionEntity`: Policy actions (keeps action type e.g. `HOLD` strictly separate from execution status e.g. `REQUESTED`).
   - `AuditEventEntity`: Append-only audit trail with sequence ordering and hash pointers.
   - `SyncOutboxEntity`: Reliable offline local-network sync queue.

---

## Cryptographic Audit Engine Design

Implemented in `com.doomedams.app.database.audit.AuditEngine`:
- **Algorithm:** SHA-256 cryptographic chaining.
- **Genesis Hash:** `0000000000000000000000000000000000000000000000000000000000000000`.
- **Deterministic Payload Format:**
  `sequenceNumber|auditEventId|actorId|actorRole|actionType|aggregateType|aggregateId|previousState|newState|reason|policyVersion|eventTimestamp|idempotencyKey|operationResult|previousHash`
- **Atomicity:** State transitions and their corresponding audit records are committed together in a single `database.withTransaction` block. If either fails, the entire transaction rolls back.
- **Idempotency:** Unique index on `idempotencyKey` prevents duplicate operations.

### Security Guarantees & Known Limitations
- **Guarantees:** Tamper detection, strict foreign-key integrity, replay prevention, and immutable SQL triggers prevent application-level tampering.
- **Limitations:** Local SQLite controls alone do not protect against an attacker with root OS access or database file replacement. Complete tamper-proofing requires future signed audit checkpoints exported to a separately controlled device or central authority.

---

## Commands & Verification

### Frontend (React + Vite + TypeScript)
```bash
# Install dependencies
npm install

# Start Vite development server
npm run dev

# Run Vitest domain unit tests (34 tests)
npm test

# TypeScript typecheck
npm run typecheck

# Production build
npm run build
```

### Capacitor Synchronization
```bash
# Copy web assets to Android project
npx cap sync android
```

### Android Native Build & Test (Gradle)
```bash
# Run native Room and Audit Engine Robolectric unit tests (14 tests)
cd android
.\gradlew.bat testDebugUnitTest

# Assemble debug APK
.\gradlew.bat assembleDebug
```
*Generated APK location:* `android/app/build/outputs/apk/debug/app-debug.apk`

---

## Trusted Offline Exam Server & RBAC Architecture (Step 4)

DOOMEDAMS transforms the examiner's physical Android device into an offline examination coordinator on a local Wi-Fi or hotspot network. The server enforces all authentication, role permissions, and resource scopes independently of the client UI.

### 1. Embedded Server Architecture
- **Engine:** Ktor Server with CIO (`io.ktor:ktor-server-cio-jvm:2.3.12`) — 100% pure Kotlin asynchronous coroutine engine without native JNI/Netty epoll dependencies.
- **Portability:** Operates inside the Android application process using Room persistence and coroutine concurrency.
- **Lifecycle Control:** Observable server status (`STOPPED`, `STARTING`, `RUNNING`, `STOPPING`, `ERROR`), port validation (1024–65535), and concurrency safety via `kotlinx.coroutines.sync.Mutex`.

### 2. Transport Security & Dynamic TLS
- **Dynamic Certificate Generation:** `TlsCertificateManager` generates an ephemeral RSA-2048 keypair and X.509 certificate on demand using BouncyCastle (`org.bouncycastle:bcpkix-jdk18on:1.78.1`).
- **No Embedded Private Keys:** Key material is created in memory inside a PKCS12 `KeyStore` on the examiner device and never checked into source control or distributed in bundles.
- **Certificate Pinning & Identity:** The server exports its SHA-256 certificate fingerprint and PEM representation so student devices can pin the exact certificate for the examination session.
- **No Insecure Fallback:** The server strictly rejects falling back to unencrypted HTTP when TLS is enabled.

### 3. Authentication & Offline Identity
- **Credential Storage:** Stored in an isolated SQLite table (`user_credentials`) linked by foreign key to `users`. Never bundled with public profile data.
- **Password Hashing:** `PBKDF2WithHmacSHA256` with 16-byte cryptographically secure random salt, 100,000 iterations, 256-bit derived key, and constant-time `MessageDigest.isEqual` comparison.
- **Session Tokens:** 32-byte `SecureRandom` hexadecimal tokens stored in an in-memory `SessionManager` with a 4-hour expiration window and immediate invalidation on logout. No tokens in browser `localStorage`.
- **Brute-Force Throttling:** `RateLimiter` restricts failed login attempts using a sliding-window rate limit (5 failed attempts per 60 seconds).

### 4. Role-to-Permission Matrix

DOOMEDAMS enforces 22 explicit, fine-grained permissions mapped across all 7 domain roles:

| Permission | STUDENT | INVIGILATOR | INCIDENT_REVIEWER | EXAMINATION_ADMIN | CHIEF_EXAMINATION_OFFICER | AUDITOR | SYSTEM_ADMIN |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| `SERVER_STATUS_READ` |  |  |  | ✓ | ✓ | ✓ | ✓ |
| `SERVER_HOST_START` |  |  |  |  |  |  | ✓ |
| `SERVER_HOST_STOP` |  |  |  |  |  |  | ✓ |
| `USER_READ` |  | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `USER_PROVISION` |  |  |  | ✓ | ✓ |  | ✓ |
| `EXAM_READ` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |  |
| `EXAM_CREATE` |  |  |  | ✓ | ✓ |  |  |
| `EXAM_UPDATE` |  |  |  | ✓ | ✓ |  |  |
| `EXAM_PUBLISH` |  |  |  |  | ✓ |  |  |
| `ATTEMPT_READ_OWN` | ✓ |  |  |  |  |  |  |
| `ATTEMPT_READ_ASSIGNED` |  | ✓ | ✓ | ✓ | ✓ | ✓ |  |
| `ANSWER_WRITE_OWN` | ✓ |  |  |  |  |  |  |
| `ANSWER_READ_ASSIGNED` |  | ✓ | ✓ | ✓ | ✓ | ✓ |  |
| `INTEGRITY_EVENT_REPORT_OWN` | ✓ |  |  |  |  |  |  |
| `INTEGRITY_EVENT_READ_ASSIGNED` |  | ✓ | ✓ | ✓ | ✓ | ✓ |  |
| `INCIDENT_READ_ASSIGNED` |  | ✓ | ✓ | ✓ | ✓ | ✓ |  |
| `INCIDENT_REVIEW` |  |  | ✓ | ✓ | ✓ |  |  |
| `RESULT_HOLD_APPLY` |  |  | ✓ | ✓ | ✓ |  |  |
| `RESULT_HOLD_RELEASE` |  |  |  | ✓ | ✓ |  |  |
| `ATTEMPT_CANCEL` |  |  |  |  | ✓ |  |  |
| `AUDIT_READ` |  |  |  |  | ✓ | ✓ |  |
| `POLICY_ADMINISTER` |  |  |  |  | ✓ |  |  |

### 5. Resource Scope & Fail-Closed Enforcement
- **Student Ownership:** Students may only write answers and submit integrity events for attempts matching their authenticated `userId`. Attempts to access another student's attempt fail closed with `403 Forbidden`.
- **Examination Scoping:** Invigilators and reviewers may only access examinations within their assigned scope.
- **Institute Boundaries:** Users cannot access resources or provision accounts outside their assigned `instituteId`.
- **Decoupled Sensitive Authorities:** `RESULT_HOLD_APPLY` does not grant `ATTEMPT_CANCEL`. Only `CHIEF_EXAMINATION_OFFICER` possesses cancellation authority. `SYSTEM_ADMIN` manages server infrastructure but possesses zero academic decision authority.

### 6. Implemented API Contracts

| Route | Method | Auth / Permission | Request Body | Description |
| :--- | :---: | :--- | :--- | :--- |
| `/api/server/status` | GET | Public | None | Server lifecycle status, host, port, TLS state, active sessions. |
| `/api/auth/login` | POST | Public (Throttled) | `{ userId, password }` | Authenticates user; returns session token & safe profile. |
| `/api/auth/me` | GET | Authenticated | None | Returns authenticated user's profile and assigned role. |
| `/api/auth/logout` | POST | Authenticated | None | Invalidates active session token and clears cache. |
| `/api/admin/users/provision` | POST | `USER_PROVISION` | `{ userId, displayName, role, instituteId, password }` | Provisions new user with PBKDF2-hashed credentials. |
| `/api/exams/{id}` | GET | `EXAM_READ` + Scope | None | Retrieves permitted exam metadata without answer keys. |
| `/api/attempts/{attemptId}/answers` | POST | `ANSWER_WRITE_OWN` | `{ questionId, value, clientSequenceNumber, clientRecordedAt }` | Transactionally persists student answer; idempotent deduplication. |
| `/api/attempts/{attemptId}/integrity-events` | POST | `INTEGRITY_EVENT_REPORT_OWN` | `{ eventId, eventCategory, clientTimestamp, sequenceNumber, ... }` | Ingests integrity telemetry; deduplicates repeated events. |

### 7. Error Response Conventions
All API errors return consistent JSON payloads:
```json
{
  "error": "PERMISSION_DENIED",
  "message": "Actor 'usr_student' lacks required permission 'USER_PROVISION'"
}
```
Standard HTTP status codes are strictly mapped: `400 Bad Request`, `401 Unauthorized`, `403 Forbidden`, `404 Not Found`, `422 Unprocessable Entity`, `429 Too Many Requests`, and `500 Internal Server Error`.

---

## Step 5: Exam Paper Compiler Architecture

DOOMEDAMS Step 5 introduces the **Exam Paper Compiler**, an offline-first examination authoring workflow that ingests PDF or TXT question papers, extracts structure deterministically, facilitates administrator review and marking scheme configuration, isolates confidential answer keys, and enforces an authoritative publication gate.

### 1. Document Ingestion & Extraction Workflow
1. **Document Selection:** The administrator selects a `.txt` or `.pdf` file via the app interface.
2. **Offline Validation:** File size (capped at 25 MB) and MIME types are validated immediately.
3. **App-Private Storage:** Imported files are streamed into private storage (`/data/user/0/com.doomedams.app/app_papers/{examId}/{filename}`) with SHA-256 fingerprinting.
4. **Offline Text Extraction (`DocumentExtractor.kt`):**
   - **TXT Files:** Decoded as UTF-8 with line-number tracking and preservation of mathematical/symbolic content.
   - **PDF Files:** Processed page-by-page via `com.tom-roush:pdfbox-android`. Scanned image pages are detected via character-density checks and explicitly flagged as requiring OCR.
   - **Zero Cloud Dependence:** No external CDNs, Gemini APIs, Qwen, or remote parsing microservices are contacted.
5. **Structural Question Parsing (`QuestionStructuralParser.kt`):**
   - Identifies section breaks (`SECTION A:`, `PART 1:`).
   - Detects question numbering (`Q1.`, `1.`, `1)`, `Question 1:`).
   - Extracts answer options (`(A)`, `(B)`, `A.`, `B.`).
   - Parses answer keys (`ANS:`, `KEY:`) and worked solutions (`SOLUTION:`, `EXPLANATION:`).
   - Classifies question types (`SINGLE_SELECT`, `MULTI_SELECT`, `NUMERICAL`, `SUBJECTIVE`, `UNKNOWN`).
   - **Zero Synthetic Hallucination Rule:** Missing questions, options, or answer keys are **never** invented; ambiguous boundaries trigger `PaperValidationIssue` records.

### 2. Confidentiality & Security Protections
- **Confidential Answer Keys:** Persisted in the dedicated `answer_keys` table and isolated from student-visible payloads.
- **Access Control:** Student requests to `/api/exams/drafts/{id}/answer-keys` are rejected with `403 Forbidden`.
- **Publication Gate:** Requires `EXAM_PUBLISH` permission and 0 unresolved blocking errors. Rejects invalid publication requests with `422 Unprocessable Entity`.
- **Audited State Transitions:** All draft saves, question updates, answer key modifications, and publications generate transactional, SHA-256 hash-chained audit events (`SAVE_EXAM_DRAFT`, `UPDATE_DRAFT_QUESTIONS`, `UPDATE_ANSWER_KEYS`, `MARKING_SCHEME_SAVED`, `EXAM_PUBLISHED`).

### 3. Room Database Migration (`MIGRATION_1_2`)
The database was bumped from version 1 to 2, introducing 7 new entities:
- `paper_imports`: Records file metadata, SHA-256 hash, and private storage paths.
- `paper_sections`: Organizes examination sections and ordering.
- `questions`: Holds structured question stems, JSON options, line/page references, and confidence.
- `answer_keys`: Holds confidential expected answers with strict foreign keys to `questions` and `exams`.
- `worked_solutions`: Holds confidential step-by-step explanations.
- `validation_issues`: Records structural warnings and blocking errors (`INFO`, `WARNING`, `ERROR`).
- `marking_schemes`: Manages scaled-integer grading rules, negative penalties, and overrides.

### 4. Authoring API Endpoints
- `POST /api/exams/drafts` — Create a draft examination.
- `GET /api/exams/drafts/{examId}` — Retrieve draft metadata, sections, and questions (drafts hidden from candidates).
- `PUT /api/exams/drafts/{examId}/questions` — Update questions and options transactionally.
- `GET /api/exams/drafts/{examId}/answer-keys` — Read confidential answer keys (Forbidden to candidates).
- `PUT /api/exams/drafts/{examId}/answer-keys` — Update verified answer keys and worked solutions.
- `PUT /api/exams/drafts/{examId}/marking-scheme` — Update scaled-integer marking rules.
- `GET /api/exams/drafts/{examId}/validate` — Run authoritative publication readiness checks.
- `POST /api/exams/drafts/{examId}/publish` — Authorize and publish validated examination draft.

### 5. Step 6: Deterministic Examination Scoring Engine

#### Authoritative Architecture
authoritative scoring occurs strictly on the trusted local Android Kotlin server (`com.doomedams.app.server.scoring.ScoringEngine`). AI models and client-side calculations are strictly prohibited from determining official grades. The frontend simulator provides immediate preview and validation support, but the server retains exclusive scoring authority.

#### Numeric Precision Strategy
All marks are stored and calculated using scaled integers (100 internal units = 1.00 mark).authoritative calculations never use floating-point types for persistence, avoiding IEEE 754 precision drift across devices:
- +4.00 marks = `400L`
- -1.00 marks = `-100L`
- +0.33 marks = `33L`
- Negative marking penalty magnitudes are clamped strictly non-positive when enabled, and `0L` when disabled.

#### Marking Scheme Precedence
Scoring strictly enforces an unambiguous 3-tier precedence hierarchy:
1. **Question Override:** Explicit configuration in `questionOverridesJson` takes highest precedence (explicit `0` values are distinguished from absent overrides).
2. **Section Default:** Configuration in `sectionDefaultsJson` for the question's section takes second precedence.
3. **Examination Default:** Scheme baseline (`correctScaledMark`, `incorrectScaledMark`, `unansweredScaledMark`).

#### Question Types Supported & Rules
- **`SINGLE_SELECT`:** Normalized string comparison against verified answer key. Matches award positive marks; mismatches apply negative penalty (if enabled); empty/unselected responses receive unanswered marks.
- **`MULTI_SELECT`:**
  - `EXACT_MATCH`: Complete set equality required; partial or wrong selections receive incorrect penalty.
  - `PARTIAL_CREDIT`: Proportional credit awarded for correct selections (`floor(correctPicks * correctMarks / totalExpected)`). Any incorrect selection incurs the configured incorrect penalty. Duplicate options in submitted input are deduplicated and cannot grant extra credit.
- **`NUMERICAL`:** Locale-independent parsing (`42,5` and `42.5` parse identically). Values within `numericalTolerance` earn correct marks; values outside incur incorrect penalty; unparseable strings receive `INVALID_RESPONSE` with `0` marks.
- **`SUBJECTIVE`:** Automated scores are never fabricated. Categorized as `PENDING_MANUAL_GRADING` with `0` marks awarded; examination total is flagged as provisional until manual assessment.
- **`UNKNOWN`:** Flagged as `UNGRADABLE` with `0` marks.

#### Answer Classifications & Explanations
Every evaluated question produces:
- **Classification:** `CORRECT`, `INCORRECT`, `PARTIALLY_CORRECT`, `UNANSWERED`, `PENDING_MANUAL_GRADING`, `INVALID_RESPONSE`, `UNGRADABLE`.
- **Explanation Code:** `MATCHED_VERIFIED_ANSWER`, `ANSWER_MISMATCH`, `NO_RESPONSE`, `PARTIAL_CREDIT_RULE_APPLIED`, `NUMERICAL_TOLERANCE_MATCH`, `INVALID_RESPONSE_FORMAT`, `MANUAL_GRADING_REQUIRED`, `MISSING_ANSWER_KEY`, `UNVERIFIED_ANSWER_KEY`, `UNSUPPORTED_QUESTION_TYPE`.

#### Result Persistence, Migration & Hold Safeguards
- **Room Migration (`MIGRATION_2_3`):** Bumps database version to 3, introducing `exam_results` and `question_grading_records`.
- **Result Hold Decoupling:** `resultStatus` (e.g. `HELD_PENDING_REVIEW`) is completely decoupled from `attemptStatus` (e.g. `SUBMITTED`). Holding a result preserves all earned marks and question records, but strictly blocks result publication or release to students.
- **Canonical Hash:** Each result computes a deterministic SHA-256 fingerprint over all question scores, section totals, and rule versions.

#### Authoritative Scoring Endpoints
- `POST /api/attempts/{attemptId}/grade` — Grades an accepted attempt (Permission: `RESULT_GRADE`).
- `GET /api/attempts/{attemptId}/result` — Retrieves grading result (Students restricted to own published, unheld results; Staff require `RESULT_READ_ASSIGNED`).
- `GET /api/attempts/{attemptId}/result/breakdown` — Retrieves per-question score breakdown and explanation codes.
- `POST /api/attempts/{attemptId}/result/publish` — Releases result (Permission: `RESULT_PUBLISH`; blocked if held).
- `POST /api/attempts/{attemptId}/rescore` — Rescores attempt with incremented version and audit trail.
- `POST /api/scoring/simulate` — Stateless examiner rule preview endpoint.

---

## Commands & Verification

### Frontend (React 19 + Vite + TypeScript)
```bash
# Run Vitest domain, compiler, scoring, and candidate unit tests (76 tests)
npm test

# Strict TypeScript typecheck
npm run typecheck

# Production build
npm run build
```

### Capacitor Synchronization
```bash
# Copy web assets to Android project
npx cap copy android
```

### Android Native Build & Test (Gradle)
```bash
# Run all native Room database, ExamServer RBAC, Compiler, Scoring & Candidate unit tests (76 tests)
cd android
.\gradlew.bat testDebugUnitTest

# Assemble debug APK
.\gradlew.bat assembleDebug
```
*Generated APK location:* `android/app/build/outputs/apk/debug/app-debug.apk` (~14.9 MB)

---

## Step 7: The Student Candidate Examination Experience

### Architecture Overview
DOOMEDAMS utilizes an offline-first architecture where the examiner's device acts as the local coordination server while candidate Android devices run a resilient CBT client:
- **Examiner Node:** Authoritative for attempt creation, start time, deadline validation, answer ingestion, durable submission acknowledgement, and scoring.
- **Candidate Device:** Maintains an authorized cached exam package, durable local Room SQLite storage, monotonic elapsed countdown, outbound event queueing (`sync_outbox`), and idempotent synchronisation.

### Candidate-Facing API Endpoints
1. `GET /api/student/exams`: Returns published exams in the candidate's institute with attempt status and result publication state.
2. `GET /api/exams/{id}/candidate-package`: Delivers sanitized exam package with sections and questions.
   - **CONFIDENTIALITY GUARANTEE:** Correct answers, answer keys, worked solutions, and hidden grading rubrics are 100% excluded. Canonical SHA-256 package hash is verified.
3. `POST /api/exams/{id}/attempts/start`: Authoritatively starts or resumes an attempt, assigning server-issued start time and deadline metadata.
4. `GET /api/attempts/{attemptId}/status`: Returns authoritative attempt status and lifecycle timestamps.
5. `POST /api/attempts/{attemptId}/answers`: Saves single answer revision with ownership validation (`ANSWER_WRITE_OWN`).
6. `POST /api/attempts/{attemptId}/sync-responses`: Batch idempotent synchronization for replaying offline outbox events.
7. `POST /api/attempts/{attemptId}/submit`: Authoritative submission endpoint issuing cryptographic SHA-256 submission receipts.
8. `GET /api/attempts/{attemptId}/result`: Candidate result access strictly gated: 403 `RESULT_NOT_RELEASED` if unreleased; 403 `RESULT_WITHHELD` if held under review.

### Local Room Persistence & Save-Before-Acknowledgement Invariant
- Responses are never stored solely in React state or browser `localStorage`.
- **Capacitor Native Bridge (`ExamNativeBridge.kt`):** Subclasses `@CapacitorPlugin(name = "ExamNativeBridge")`, providing typed operations for:
  - `getMonotonicTime()`: Native `SystemClock.elapsedRealtime()`.
  - `saveResponseLocal()`: Persists response into `candidate_responses` table in Room SQLite.
  - `loadAttemptSnapshotLocal()`: Reads local responses for attempt restoration on app reopen.
  - `enqueueSyncEventLocal()`: Enqueues outbound sync events to `sync_outbox`.
  - `getPendingSyncEventsLocal()` & `markSyncEventAcknowledgedLocal()`: Manages outbox queue.
- **Save Invariant:** Whenever an answer is changed, the local durable Room write and outbox enqueue succeed *before* the UI displays "Saved locally". Remote synchronisation follows as a separate step.

### Monotonic Countdown Timer & Deadline Enforcement
- Official deadline is set authoritatively by the examiner server.
- The candidate interface uses Android `SystemClock.elapsedRealtime()` via `ExamStorageBridge.getMonotonicTime()`:
  - Monotonic elapsed time cannot be extended by changing the device wall clock.
  - Timer anchors survive backgrounding and UI recreation.
  - Detects monotonic clock resets (device reboot): if `elapsedRealtime` resets, flags recovery requirement.
  - Edits are locally blocked upon reaching the deadline, and the server independently rejects late answers.

### Question Palette & Supported Question Types
- **Supported Question Types:**
  - `SINGLE_SELECT`: Radio choices.
  - `MULTI_SELECT`: Checkbox choices stored as JSON array.
  - `NUMERICAL`: String input preserving user formatting and decimals without locale destruction.
  - `SUBJECTIVE`: Multiline text area.
  - Unsupported types display a safe fallback notice without skipping.
- **Accessible Palette Indicators:**
  - ⚪ `NOT_VISITED`
  - 🟡 `UNANSWERED` (visited)
  - 🟢 `ANSWERED`
  - 🟣 `MARKED_FOR_REVIEW`
  - 🟢🟣 `ANSWERED_AND_MARKED`

### Submission Workflow & Durable Receipts
- Candidate reviews answer statistics (Answered, Unanswered, Marked for Review, Remaining Time) in `SubmissionModal`.
- On confirm, computes a client response checksum and sends submission request.
- Server validates deadline, transitions attempt to `SUBMITTED`, and issues a cryptographic receipt:
  `receiptHash = SHA-256(attemptId + studentId + examId + submittedAt + clientChecksum)`
- Repeated submissions are idempotent. If network drops during submission, local durability is preserved with a pending receipt until connection returns.

---

## Implementation Status & Scope Boundaries

### Verified in Step 7 (Candidate Examination Experience)
- [x] Mobile-first CBT candidate interface with dark navy/graphite surfaces and teal/cyan accents.
- [x] Authenticated student portal dashboard listing assigned published exams with attempt and result indicators.
- [x] Instructions modal with duration, marking rules (+4 / -1 / 0), negative marking notice, and honor code.
- [x] Authoritative server-managed attempt creation and start (`POST /api/exams/{id}/attempts/start`).
- [x] Candidate package delivery with 100% answer-key confidentiality and canonical package hash.
- [x] Native Room SQLite persistence bridge (`ExamNativeBridge.kt`) registered in `MainActivity.java`.
- [x] Save-before-acknowledgement invariant with incrementing revision sequences.
- [x] Monotonic countdown timer with `SystemClock.elapsedRealtime()` and clock-tampering detection.
- [x] Offline answering tolerance and outbound queueing via `sync_outbox`.
- [x] Submission confirmation modal and durable SHA-256 submission receipts.
- [x] Access-controlled results view respecting publication and administrative hold states.
- [x] 76/76 frontend Vitest unit tests passing.
- [x] 76/76 native Android Robolectric unit tests passing.
- [x] Clean TypeScript type-check and Vite production build.
- [x] Android debug APK built successfully (`app-debug.apk`, 14.89 MB).

## Step 8: Examination Lockdown & Integrity Monitoring

### Architecture & Integrity Pipeline
DOOMEDAMS implements multi-layered integrity monitoring designed specifically for offline Android deployments:
- **Lockdown Mode Management:** Enforces Android Lock Task Mode on appropriately managed devices while providing graceful fallback telemetry on unmanaged devices.
- **Client Integrity Telemetry:** Captures window blurs, app backgrounding, task exits, and prohibited app foregrounding with monotonic timestamps, event sequences, and device metrics.
- **Ingestion & De-duplication:** Events are queued locally and batched over `POST /api/attempts/{attemptId}/integrity-events` with monotonically increasing sequence numbers.
- **Incident Escalation:** Evaluates incoming telemetry against the exam's active integrity policy (`policyVersion`). Repeated or severe violations escalate into formal `incident_cases`.

---

## Step 9: Administrator Incident Review, Result Holds & Appeals

### Architecture Overview
Step 9 connects integrity monitoring and scoring to the examination branch's decision-making and justice workflow:
- **Examiner/Admin Node:** Hosts the authoritative incident review state machine, cryptographic audit trail, hold/cancellation controls, and appeals adjudication.
- **Student Candidate Portal:** Enables candidates to inspect review status, view transparent hold notices, and file formal appeals against adverse decisions.

### Authoritative Lifecycle State Machine
Incident progression follows a strict, server-enforced state machine:
```
                ┌──────────────────────────────────────────────┐
                │                  DETECTED                    │
                └──────────────────────┬───────────────────────┘
                                       │ (begin review)
                ┌──────────────────────▼───────────────────────┐
                │               PENDING_REVIEW                 │
                └──────────────────────┬───────────────────────┘
                                       │ (assign reviewer)
                ┌──────────────────────▼───────────────────────┐
                │                UNDER_REVIEW                  │
                └───┬──────────────────┬───────────────────┬───┘
                    │ (insufficient)   │ (clear violation) │ (false positive)
┌───────────────────▼───────────┐ ┌────▼──────────┐ ┌──────▼────────┐
│    PENDING_FURTHER_REVIEW     │ │   CONFIRMED   │ │   DISMISSED    │
└───────────────────┬───────────┘ └────┬──────────┘ └──────┬─────────┘
                    │                  │                   │
                    └──────────────────┼───────────────────┘
                                       │ (finalized / appeal resolved)
                        ┌──────────────▼─────────────┐
                        │           CLOSED           │
                        └────────────────────────────┘
```

### The Prescribed Test Scenario Fixture
A central requirement of Prompt 9 is handling ambiguous signals with proportionate procedural justice:
- **Incident Category:** `UNAUTHORISED_APP_SWITCH`
- **Evidence Assessment:** `LIMITED` (e.g. system broadcast or transient focus loss without prohibited package payload)
- **Reviewer Justification:** `"No notes supplied"`
- **Requested Policy Action:** `HOLD`
- **Resulting Incident Status:** `PENDING_FURTHER_REVIEW`
- **Resulting Result Status:** `HELD_PENDING_REVIEW`
- **Adjudication Outcome:** No final misconduct finding (`NONE` / `INCONCLUSIVE`).
- **Safeguard:** Attempt cancellation is **strictly forbidden** from executing automatically.

### Strict Conceptual Separation of Concerns
DOOMEDAMS strictly decouples 7 distinct assessment and administrative dimensions:
1. **Original Integrity Event:** The raw client telemetry signal (`UNAUTHORISED_APP_SWITCH`, `WINDOW_BLUR`, etc.).
2. **Evidence Assessment:** Objective evaluation of telemetry quality (`SUFFICIENT`, `LIMITED`, `UNAVAILABLE`, `CONTRADICTORY`).
3. **Incident Decision:** The finding on whether a violation occurred (`NONE`, `INCONCLUSIVE`, `CONFIRMED`, `DISMISSED`).
4. **Policy Action:** Administrative intervention (`NONE`, `WARN`, `HOLD`, `CANCEL_ATTEMPT`, `REINSTATE_ATTEMPT`).
5. **Attempt Status:** Operational state of the test session (`IN_PROGRESS`, `SUBMITTED`, `TIMED_OUT`, `CANCELLED`).
6. **Result Status:** Score release state (`NOT_EVALUATED`, `EVALUATED`, `PUBLISHED`, `HELD_PENDING_REVIEW`, `CANCELLED`, `WITHHELD`).
7. **Appeal Status:** Student contestation state (`SUBMITTED`, `PENDING_REVIEW`, `UNDER_REVIEW`, `UPHELD`, `OVERTURNED`, `REQUIRES_MORE_INFORMATION`, `CLOSED`).

### Role-Based Access Control (RBAC)
Permission checks are enforced authoritatively on the server node:
- `INCIDENT_READ_ASSIGNED`: View incidents assigned to the user or institute (`PROCTOR`, `INVIGILATOR`, `EXAMINATION_ADMIN`, `CHIEF_EXAMINATION_OFFICER`).
- `INCIDENT_REVIEW`: Begin review and record evidence assessments (`INVIGILATOR`, `EXAMINATION_ADMIN`, `CHIEF_EXAMINATION_OFFICER`).
- `RESULT_HOLD_APPLY` & `RESULT_HOLD_RELEASE`: Apply and release administrative result holds (`EXAMINATION_ADMIN`, `CHIEF_EXAMINATION_OFFICER`).
- `ATTEMPT_CANCEL`: Cancel an attempt for confirmed violations (`EXAMINATION_ADMIN`, `CHIEF_EXAMINATION_OFFICER`).
- `ATTEMPT_REINSTATE`: Un-cancel a previously cancelled attempt (`EXAMINATION_ADMIN`, `CHIEF_EXAMINATION_OFFICER`).
- `APPEAL_SUBMIT`: Submit formal appeal for candidate's own incident (`STUDENT` with ownership match, `EXAMINATION_ADMIN`).
- `APPEAL_DECIDE`: Adjudicate appeals (`EXAMINATION_ADMIN`, `CHIEF_EXAMINATION_OFFICER`).
  - **Independent Reviewer Rule:** The administrator who conducted the original incident review cannot decide the subsequent appeal against their own finding.

### Score Preservation Invariants
1. **Result Hold Invariant:** Applying a hold changes `resultStatus` to `HELD_PENDING_REVIEW` but preserves all computed scores, question-level grading records, and response snapshots. Releasing a hold restores `PUBLISHED` status without re-grading distortion.
2. **Cancellation Invariant:** Cancelling an attempt sets `attemptStatus = 'CANCELLED'` and records an immutable audit reason. All student response history is retained intact in the database for auditing and appeals.
3. **Reinstatement Invariant:** Reinstating a cancelled attempt restores `attemptStatus = 'SUBMITTED'` and recalculates or releases the score.

### Endpoints Implemented in Step 9
- `GET /api/incidents`: Filtered incident queue with multi-attribute search and pending-appeals indicators.
- `GET /api/incidents/{incidentId}`: Full incident dossier with attempt metadata, results, and raw telemetry events.
- `POST /api/incidents/{incidentId}/review/begin`: Starts review and transitions state to `UNDER_REVIEW`.
- `POST /api/incidents/{incidentId}/assess`: Records evidence assessment and proposed lifecycle decision.
- `POST /api/incidents/{incidentId}/scenario/limited-evidence-hold`: Executes the atomic prescribed limited-evidence hold workflow.
- `POST /api/incidents/{incidentId}/hold`: Applies or releases result holds with mandatory reason logging.
- `POST /api/incidents/{incidentId}/cancel`: Cancels an attempt with justification and audit trail.
- `POST /api/incidents/{incidentId}/reinstate`: Reinstates a previously cancelled attempt.
- `POST /api/incidents/{incidentId}/appeals`: Submits a student appeal with reason and evidence references.
- `POST /api/incidents/{incidentId}/appeals/{appealId}/decide`: Adjudicates appeal with independent reviewer enforcement.
- `GET /api/incidents/{incidentId}/audit`: Retrieves cryptographic SHA-256 audit timeline for the case.

---

## Implementation Status & Scope Boundaries

### Verified in Step 9 (Incident Review, Holds & Appeals)
- [x] Authoritative server-side state machine (`DETECTED` -> `PENDING_REVIEW` -> `UNDER_REVIEW` -> `PENDING_FURTHER_REVIEW` / `CONFIRMED` / `DISMISSED` -> `CLOSED`).
- [x] Atomic execution of prescribed limited-evidence hold scenario (`UNAUTHORISED_APP_SWITCH` + `LIMITED` + `HOLD` -> `PENDING_FURTHER_REVIEW` + `HELD_PENDING_REVIEW`).
- [x] Strict concept separation between events, assessments, decisions, policy actions, attempt states, result states, and appeals.
- [x] RBAC enforcement for review, hold, cancel, reinstate, and appeal permissions.
- [x] Independent reviewer check preventing original reviewer from deciding their own incident appeal.
- [x] Preserved question scores and response history during result holds and attempt cancellations.
- [x] SHA-256 hash-chained immutable audit log for every administrative review and appeal decision.
- [x] React `IncidentReviewDashboard` in DOOMEDAMS dark theme with queue filters, evidence inspector, and audit trail.
- [x] 97/97 frontend Vitest unit tests passing (`src/test/domain/incident-review.test.ts`).
- [x] 97/97 native Android Robolectric unit tests passing (`IncidentReviewWorkflowTest.kt`).
- [x] Clean TypeScript type-check (`tsc --noEmit`).
- [x] Production web bundle generated (`vite build`).
- [x] Android debug APK compiled and verified (`app-debug.apk`).

---

## Step 10: Examination Intelligence Dashboard & Learning Intelligence

### Overview & Architecture
The Examination Intelligence Dashboard provides authorised examination staff with a comprehensive, data-driven console operating strictly on authoritative persisted SQLite/Room database records on the coordinator Android node.

### Core Architecture & Privacy Invariants
1. **Zero Simulated Metrics:** Metrics reflect real persisted Room entities (`ExamAttemptEntity`, `ExamResultEntity`, `CandidateResponseEntity`, `QuestionGradingRecordEntity`, `IntegrityEventEntity`, `IncidentCaseEntity`). No cloud telemetry dependency, zero simulated or hardcoded data.
2. **Timing Telemetry Separation:** Fine-grained per-question millisecond stopwatches are not collected by student devices to avoid battery throttling and timer desynchronization. Duration metrics honestly declare `TIME DATA UNAVAILABLE` with explicit explanation. Attempt-level wall-clock elapsed time is reported only from verified start and submission timestamps.
3. **Curriculum Tag Disclosure:** Concept-level intelligence explicitly requires tags (`curriculumTagsAvailable = false`). When unconfigured, performance is grouped strictly by section and question type, avoiding fabricated pedagogical claims.
4. **Separation of Events from Misconduct:** Raw telemetry events (such as app switches) are separated from formal incident cases and confirmed violations. Holds are not treated as zero marks.
5. **Dense Ranking with Tie-Handling:** Ranks are computed only for final comparable results. Students with identical scores share the same rank, and held/provisional scores are designated `N/A`.

### Server Endpoints Implemented in Step 10
- `GET /api/analytics/overview`: Institute-wide overview metrics (`ANALYTICS_OVERVIEW_READ`).
- `GET /api/analytics/exams/{examId}/summary`: Selected examination summary and score distributions (`ANALYTICS_EXAM_READ`).
- `GET /api/analytics/exams/{examId}/students`: Student cohort performance roster with score, accuracy, and hold states (`ANALYTICS_STUDENT_READ`).
- `GET /api/analytics/exams/{examId}/questions`: Item-response analytics with attempt and correctness rates (`ANALYTICS_EXAM_READ`).
- `GET /api/analytics/exams/{examId}/sections`: Section-by-section breakdown (`ANALYTICS_EXAM_READ`).
- `GET /api/analytics/exams/{examId}/learning-gaps`: Difficulty diagnostics and data-traceable recommendations (`ANALYTICS_EXAM_READ`).
- `GET /api/analytics/exams/{examId}/integrity`: Aggregate telemetry events, formal cases, and holds (`ANALYTICS_INTEGRITY_READ`).
- `GET /api/analytics/exams/{examId}/students/{studentId}/profile`: Comprehensive individual candidate examination dossier (`ANALYTICS_STUDENT_READ` or student reading own record).
- `GET /api/analytics/exams/{examId}/reports/{reportType}/csv`: Offline RFC 4180 compliant CSV report export (`REPORT_EXPORT`).
- `GET /api/analytics/live/{examId}`: Real-time operational monitoring of active examination sessions.

### Role-Based Access Control (RBAC)
- `ANALYTICS_OVERVIEW_READ`: `EXAMINATION_ADMIN`, `CHIEF_EXAMINATION_OFFICER`, `AUDITOR`.
- `ANALYTICS_EXAM_READ`: `INVIGILATOR`, `EXAMINATION_ADMIN`, `CHIEF_EXAMINATION_OFFICER`, `AUDITOR`.
- `ANALYTICS_STUDENT_READ`: `INVIGILATOR`, `EXAMINATION_ADMIN`, `CHIEF_EXAMINATION_OFFICER` (and candidate reading their own profile).
- `ANALYTICS_TIMING_READ`: `INVIGILATOR`, `EXAMINATION_ADMIN`, `CHIEF_EXAMINATION_OFFICER`.
- `ANALYTICS_INTEGRITY_READ`: `INCIDENT_REVIEWER`, `EXAMINATION_ADMIN`, `CHIEF_EXAMINATION_OFFICER`, `AUDITOR`.
- `REPORT_EXPORT`: `EXAMINATION_ADMIN`, `CHIEF_EXAMINATION_OFFICER`.

### Verified in Step 10 (Administrator Dashboard & Learning Intelligence)
- [x] Authoritative analytics calculation service (`AnalyticsService.kt`) with Room DAO queries and zero fake metrics.
- [x] Ktor server endpoints (Routes 51-60) enforcing strict RBAC via `PermissionService`.
- [x] React `ExamIntelligenceDashboard` in DOOMEDAMS dark theme (`#0b0f19`, `#131b2e`, cyan `#38bdf8`, teal `#14b8a6`).
- [x] Individual candidate examination profile dossier with question history and receipt verification.
- [x] Offline RFC 4180 CSV export for summary, student, question, section, and integrity reports.
- [x] 108/108 frontend Vitest unit tests passing (`src/test/domain/analytics.test.ts`).
- [x] Native Robolectric unit tests passing (`ExamIntelligenceAnalyticsTest.kt`).
- [x] Clean TypeScript type-check (`tsc --noEmit`).
- [x] Production web bundle generated (`vite build`).
- [x] Android debug APK compiled and verified (`app-debug.apk`).

---

## Step 11: Offline Synchronisation, Crash Recovery & Multi-Device Reliability

### 1. Synchronisation Protocol & Event Envelope (Schema Version 1)
DOOMEDAMS implements an explicit, versioned, bidirectional synchronization protocol between student devices and the authoritative examiner node over local Wi-Fi without internet access.

All events transmitted across the local network adhere to the canonical envelope:
```json
{
  "eventId": "evt_att_std01_q01_rev3",
  "schemaVersion": 1,
  "eventType": "ANSWER_UPDATE",
  "instituteId": "inst_apex",
  "examId": "exam_cbt_001",
  "attemptId": "att_std01_001",
  "studentId": "usr_student_01",
  "sourceDeviceId": "phone_samsung_a52",
  "sequenceNumber": 3,
  "aggregateId": "att_std01_001",
  "aggregateVersion": 3,
  "baseServerVersion": 2,
  "clientWallClockTimestamp": "2026-10-10T10:15:30.123Z",
  "clientMonotonicTimestamp": 18239045,
  "payload": "{\"questionId\":\"q_01\",\"responsePayload\":\"B\",\"revisionSequence\":3}",
  "payloadHash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "idempotencyKey": "idem_att_std01_q01_rev3",
  "deliveryAttempt": 1
}
```

### 2. Durable Student Outbox & Lifecycle States
Student devices store pending operations in the native Room `sync_outbox` table before transmission.
- `PENDING`: Durably enqueued locally, awaiting transmission.
- `IN_FLIGHT`: Currently being transmitted over the local network.
- `ACKNOWLEDGED`: Confirmed and accepted by the authoritative examiner server. Removed from active retry queue.
- `RETRY_PENDING`: Network transmission timed out or lost; scheduled for exponential backoff retry.
- `CONFLICT`: Server detected a revision conflict or sequence anomaly; preserved for reconciliation.
- `REJECTED`: Rejected by server policy (e.g., attempt cancelled or invalid schema). Preserved with rejection reason.
- `FAILED_PERMANENTLY`: Unrecoverable failure. Preserved for administrative audit.

### 3. Server-Side Receipts (Inbox) & Idempotency Rules
The examiner node maintains the `server_event_receipts` table with a unique index on `eventId`.
1. **Exact Duplicate Retry:**
   When an authenticated sender retries an `eventId` with the exact same canonical `payloadHash`:
   - Mutation is **never repeated**.
   - Original processing outcome (`ALREADY_PROCESSED`) and server receipt timestamp are returned.
2. **Event ID Collision with Different Payload:**
   When an `eventId` is reused with a conflicting `payloadHash`:
   - Request is immediately **rejected** (HTTP 409 Conflict, `EVENT_ID_COLLISION_DIFFERENT_PAYLOAD`).
   - Security audit event `EVENT_ID_COLLISION_DETECTED` is recorded.
   - Original accepted answer remains untouched.
3. **Partial Batch Acceptance:**
   Batches (up to 50 events) are evaluated on a per-event basis. The server returns an array of per-event outcomes (`results: [...]`), allowing clients to acknowledge successful events without re-transmitting accepted items.

### 4. Monotonic Revision Semantics & Conflict Resolution
- **Strictly Newer Revisions (`incoming.revisionSequence > current`):** Accepted and upserted transactionally.
- **Stale Revisions (`incoming.revisionSequence < current`):** Rejected as `CONFLICT` (`STALE_REVISION`). Newer server answer is never overwritten.
- **Identical Revisions (`incoming == current`):** Treated as idempotent duplicate.
- **Client Clock Disregard:** Client wall-clock timestamps are never used for ordering or deadlines; the examiner server's clock and monotonic sequence numbers govern validity.

### 5. Authoritative Deadlines & Late Submission Policy
- **Deadlines do not extend:** Device reboots, app crashes, network disconnects, or retries never extend the official attempt deadline.
- **Network Transit Grace (120s):** Offline responses recorded locally prior to the deadline receive a 120-second network sync grace window and are recorded with status `LATE_ACCEPTED` with an audit trail note.
- **Expired Submissions:** Responses received beyond the grace period are marked `LATE_REJECTED` (`DEADLINE_EXPIRED`) and preserved as evidence.

### 6. Submission Lifecycle & Recovery
Distinguishes client and server state:
- `SUBMITTED_LOCALLY`: Final answer snapshot saved in local SQLite outbox; network sync pending.
- `RECEIVED_BY_SERVER`: Server has received final snapshot.
- `ACCEPTED`: Attempt authoritatively transitioned to `SUBMITTED` with cryptographic SHA-256 receipt hash.
- `REJECTED`: Rejected under policy.
- `NEEDS_REVIEW`: Held for administrative review.
- Retrying final submission always returns the original receipt hash idempotently.

### 7. Persistent TLS Identity Lifecycle
In `TlsCertificateManager.kt`, the self-signed X.509 certificate and private key are saved to internal application storage in PKCS12 format (`examiner_keystore.p12`).
- Server restarts reload the existing keystore instead of generating a new certificate.
- The SHA-256 fingerprint remains constant across server reboots, ensuring reconnecting student devices validate TLS without certificate mismatch errors.

### 8. Crash Recovery & Session Reconciliation
- Reconnecting students query `POST /api/sync/reconcile` with `attemptId`.
- Server returns authoritative attempt status, remaining time, answered questions, and the map of last accepted revision sequence numbers (`lastAcceptedRevisions`).
- If examiner server restarted and in-memory session was cleared (HTTP 401), the student device preserves all queued outbox events and requests re-authentication without losing local work.

### 9. Room Migration 4 -> 5
Room schema version updated to `5` with non-destructive migration `MIGRATION_4_5`:
- Creates `server_event_receipts` table.
- Creates unique index on `eventId`.
- Creates indices on `attemptId`, `studentId`, and `processingStatus`.

### 10. Observability & Diagnostics (`GET /api/sync/status`)
Exposes real-time sync metrics to invigilators and admins:
- Total event receipts count.
- Accepted, rejected, conflict, and already-processed event counts.
- Active attempts count and per-attempt synchronization progress.

### 11. Physical-Device Testing Procedure (1 Examiner + 2+ Students)
1. **Host Examination:** Examiner device starts exam server on local Wi-Fi or portable hotspot without internet. Note displayed IP and SHA-256 fingerprint.
2. **Student Join:** Connect 2 student devices to the same local Wi-Fi. Verify TLS certificate fingerprint match.
3. **Concurrent Answering:** Both students start exams and submit answers simultaneously. Verify `/api/sync/status` records increasing accepted counts without contention.
4. **Network Loss Simulation:** Turn off Wi-Fi on Student 1. Student continues answering questions; observe `SAVED_LOCALLY` status.
5. **Reconnection Drain:** Turn Wi-Fi back on. Observe outbox drain to `SYNCED` and receipt acknowledgment.
6. **Student Reboot Simulation:** Force-quit or restart Student 1 phone. Re-launch app; verify all answers restored from Room snapshot and reconciliation restores correct palette state.
7. **Examiner Restart Simulation:** Restart examiner app. Verify student reconnects, re-authenticates if prompted, and pending outbox items sync without duplicate submissions.
8. **Deadline Enforcement:** Verify student reaching time limit is locked out and cannot extend time by restarting phone or disconnecting Wi-Fi.





