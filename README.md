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

## Implementation Status & Scope Boundaries

### Verified in Step 3
- [x] Capacitor Android integration (`com.doomedams.app`).
- [x] Kotlin + Room database with 10 entities and 8 DAOs.
- [x] Immutability triggers on audit records.
- [x] SHA-256 cryptographic hash-chained AuditEngine with atomic transactions.
- [x] Decoupled attempt vs result status (`HELD_PENDING_REVIEW`).
- [x] 34/34 TypeScript domain tests passing.
- [x] 14/14 native Android Room & Robolectric tests passing.
- [x] Native debug APK built successfully (`app-debug.apk`).

### Deliberately Deferred to Future Steps
- Local HTTP / WebSocket server (Step 4).
- Server-side RBAC and session token validation (Step 4).
- Paper parsing and scoring engine (Step 5).
- Full Incident Review Policy State Machine (Step 6).
- Student examination answering UI (Step 7).
