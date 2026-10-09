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
Standard HTTP status codes are strictly mapped: `400 Bad Request`, `401 Unauthorized`, `403 Forbidden`, `404 Not Found`, `429 Too Many Requests`, and `500 Internal Server Error`.

---

## Commands & Verification

### Frontend (React + Vite + TypeScript)
```bash
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
npx cap copy android
```

### Android Native Build & Test (Gradle)
```bash
# Run all native Room database & ExamServer RBAC Robolectric unit tests (35 tests)
cd android
.\gradlew.bat testDebugUnitTest

# Assemble debug APK
.\gradlew.bat assembleDebug
```
*Generated APK location:* `android/app/build/outputs/apk/debug/app-debug.apk`

---

## Security & Physical Limitations

1. **Examiner Device as Server:** The examiner's smartphone acts as the coordinator. Host battery depletion, OS aggressive background killing, or hardware shutdown terminates the local server. The database uses durable WAL SQLite transactions so in-flight data is recoverable upon restart.
2. **Foreground Service Requirement:** In production deployment on physical Android devices, long-running exam sessions must be bound to an Android Foreground Service with an ongoing notification to avoid Android Doze and low-memory kills.
3. **Local Wi-Fi Boundary:** Devices communicate over a local Wi-Fi or examiner hotspot. Disconnections do not cause data loss because student clients retain local sync queues until connectivity is restored.
4. **Device Clock Discrepancies:** Server-received timestamps (`serverReceivedAt`) are preserved separately from device-reported timestamps (`clientRecordedAt`) to detect clock skew.
5. **Root Compromise:** A rooted examiner device could compromise local SQLite encryption or memory. Complete offline integrity relies on post-exam multi-party audit verification and central checkpoint exports.

---

## Implementation Status & Scope Boundaries

### Verified in Step 4
- [x] Embedded Ktor server (CIO engine) operating inside the Android APK.
- [x] Dynamic RSA-2048 / X.509 TLS certificate generation via BouncyCastle without hardcoded keys.
- [x] PBKDF2-HMAC-SHA256 password hashing with random 16-byte salt (100,000 iterations).
- [x] Secure token-based session management with rate-limiting protection.
- [x] Centralized PermissionService enforcing 22 permissions and strict resource scoping.
- [x] Decoupled high-risk permissions (`RESULT_HOLD_APPLY` vs `ATTEMPT_CANCEL`).
- [x] Idempotent answer submission and integrity event deduplication.
- [x] Audit trail recording for lifecycle, logins, provisioning, submissions, and permission denials.
- [x] Minimal React dev interface demonstrating status, authentication, and fail-closed RBAC.
- [x] 34/34 frontend Vitest tests passing.
- [x] 35/35 native Android Robolectric tests passing (13 Room DB + 21 ExamServer RBAC + 1 Capacitor).
- [x] Native debug APK built successfully (`app-debug.apk`, 10.6 MB).

### Deliberately Deferred to Future Steps
- Exam Paper Compiler: PDF/TXT parsing, question structure extraction, answer keys (Step 5).
- Full Incident Review Policy State Machine (Step 6).
- Student examination answering UI and timer (Step 7).
- Camera, audio, or facial monitoring.

