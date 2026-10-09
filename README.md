# DOOMEDAMS

Offline-First Examination Platform for coaching institutes, colleges, and examination branches.

## Architecture & Technology Decisions

- **Frontend:** React 19 + Vite
- **Type Checking:** TypeScript (Strict mode enabled)
- **Styling:** Standard Vanilla CSS (`src/styles/global.css`); Tailwind CSS prohibited.
- **Runtime Validation:** Zod schemas
- **Automated Testing:** Vitest (Local execution, 0 external network dependencies)
- **Deployment Model:** Offline-first architecture intended for local network hosting on examiner's device and Android APK distribution.

## Project Structure

```
doomdam/
├── .gitignore
├── README.md
├── package.json
├── index.html
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
```

## Commands

### 1. Install Dependencies
```bash
npm install
```

### 2. Development Server
```bash
npm run dev
```

### 3. Run Automated Tests
```bash
npm test
```
Or for interactive test watching:
```bash
npm run test:watch
```

### 4. Type Checking
```bash
npm run typecheck
```

### 5. Production Build
```bash
npm run build
```

## Step 2 Domain Contracts

- **User Roles:** `STUDENT`, `INVIGILATOR`, `INCIDENT_REVIEWER`, `EXAMINATION_ADMIN`, `CHIEF_EXAMINATION_OFFICER`, `AUDITOR`, `SYSTEM_ADMIN`
- **Integrity Telemetry:** Supports signals such as `UNAUTHORISED_APP_SWITCH`, `WINDOW_BLUR`, etc., without assuming automatic misconduct.
- **Incident Workflow Contracts:** Explicit separation between policy actions (`HOLD`, `CANCEL_ATTEMPT`), evidence levels (`SUFFICIENT`, `LIMITED`, `UNAVAILABLE`, `CONTRADICTORY`), and lifecycle states.
- **Attempt vs. Result State Separation:** Result status `HELD_PENDING_REVIEW` is decoupled from attempt lifecycle status.
- **Audit Records:** Schema contracts for both successful and rejected administrative actions.
