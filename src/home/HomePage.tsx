import React, { useState } from 'react';

interface HomePageProps {
  onNavigate: (view: 'home' | 'compiler' | 'server' | 'student' | 'monitor' | 'incidents' | 'analytics' | 'smarthire') => void;
  serverUrl: string;
  setServerUrl: (url: string) => void;
  currentUser: {
    userId: string;
    displayName: string;
    role: string;
    instituteId: string;
  } | null;
  onQuickLogin: (userId: string, role: string) => void;
  onLogout: () => void;
}

export const HomePage: React.FC<HomePageProps> = ({
  onNavigate,
  serverUrl,
  setServerUrl,
  currentUser,
  onQuickLogin,
  onLogout
}) => {
  const [pingStatus, setPingStatus] = useState<{
    tested: boolean;
    online: boolean;
    latencyMs?: number;
    details?: any;
    error?: string;
  }>({ tested: false, online: false });
  const [isPinging, setIsPinging] = useState(false);

  const testServerConnection = async () => {
    setIsPinging(true);
    const start = performance.now();
    try {
      const res = await fetch(`${serverUrl}/api/server/status`, { signal: AbortSignal.timeout(3000) });
      const latency = Math.round(performance.now() - start);
      if (res.ok) {
        const data = await res.json();
        setPingStatus({
          tested: true,
          online: true,
          latencyMs: latency,
          details: data
        });
      } else {
        setPingStatus({
          tested: true,
          online: false,
          error: `HTTP ${res.status}`
        });
      }
    } catch (err: any) {
      setPingStatus({
        tested: true,
        online: false,
        error: err.message || 'Offline / Host unreachable'
      });
    } finally {
      setIsPinging(false);
    }
  };

  return (
    <div className="pariksh-home-container">
      {/* Background Ambient Glows */}
      <div className="pariksh-ambient-orb pariksh-ambient-orb-1" />
      <div className="pariksh-ambient-orb pariksh-ambient-orb-2" />

      {/* Hero Section */}
      <section className="pariksh-hero">
        <div className="pariksh-badge-pill">
          <span className="pariksh-pill-dot" />
          <span className="pariksh-pill-text">Sovereign Air-Gapped Architecture • v1.0 Release Candidate</span>
        </div>

        <div className="pariksh-brand-lockup">
          <div className="pariksh-logo-icon">
            <svg width="42" height="42" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M12 2L2 7l10 5 10-5-10-5zM2 17l10 5 10-5M2 12l10 5 10-5" />
            </svg>
          </div>
          <h1 className="pariksh-hero-title">
            PARIKSH
            <span className="pariksh-devanagari-subtitle">परीक्ष</span>
          </h1>
        </div>

        <p className="pariksh-hero-desc">
          High-integrity, zero-cloud offline examination platform with deterministic scoring,
          cryptographic audit immutability, and resilient local-mesh synchronization.
        </p>

        {/* Live Connectivity Banner */}
        <div className="pariksh-status-banner">
          <div className="pariksh-status-indicator">
            <span className={`pariksh-live-indicator-dot ${pingStatus.online ? 'is-online' : ''}`} />
            <div>
              <div className="pariksh-status-label">Embedded Ktor Backend Node</div>
              <div className="pariksh-status-value">
                {pingStatus.tested ? (
                  pingStatus.online ? (
                    <span style={{ color: '#34d399' }}>
                      Online ({pingStatus.latencyMs}ms) • Active Sessions: {pingStatus.details?.activeSessions ?? 0}
                    </span>
                  ) : (
                    <span style={{ color: '#f87171' }}>
                      Offline / Standalone Mode ({pingStatus.error})
                    </span>
                  )
                ) : (
                  <span>Ready at {serverUrl}</span>
                )}
              </div>
            </div>
          </div>

          <div className="pariksh-server-input-wrapper">
            <input
              type="text"
              value={serverUrl}
              onChange={(e) => setServerUrl(e.target.value)}
              placeholder="http://127.0.0.1:8443"
              className="pariksh-server-input"
            />
            <button
              onClick={testServerConnection}
              disabled={isPinging}
              className="pariksh-ping-btn"
            >
              {isPinging ? 'Pinging...' : 'Ping Node'}
            </button>
          </div>
        </div>

        {/* Quick Demo Role Bar */}
        <div className="pariksh-quick-login-card">
          <div className="pariksh-quick-login-header">
            <span>⚡ Quick Role Switcher</span>
            {currentUser && (
              <span className="pariksh-current-user-tag">
                Active: <strong>{currentUser.displayName}</strong> ({currentUser.role})
                <button onClick={onLogout} className="pariksh-logout-link">Switch</button>
              </span>
            )}
          </div>
          <div className="pariksh-quick-roles-grid">
            <button
              onClick={() => onQuickLogin('usr_student_001', 'STUDENT')}
              className={`pariksh-role-btn ${currentUser?.role === 'STUDENT' ? 'is-active' : ''}`}
            >
              🎓 Student
            </button>
            <button
              onClick={() => onQuickLogin('usr_examiner_001', 'EXAMINER')}
              className={`pariksh-role-btn ${currentUser?.role === 'EXAMINER' ? 'is-active' : ''}`}
            >
              📝 Examiner
            </button>
            <button
              onClick={() => onQuickLogin('usr_reviewer_001', 'REVIEWER')}
              className={`pariksh-role-btn ${currentUser?.role === 'REVIEWER' ? 'is-active' : ''}`}
            >
              ⚖️ Reviewer
            </button>
            <button
              onClick={() => onQuickLogin('usr_admin_001', 'ADMINISTRATOR')}
              className={`pariksh-role-btn ${currentUser?.role === 'ADMINISTRATOR' ? 'is-active' : ''}`}
            >
              🛡️ Administrator
            </button>
            <button
              onClick={() => onQuickLogin('usr_auditor_001', 'AUDITOR')}
              className={`pariksh-role-btn ${currentUser?.role === 'AUDITOR' ? 'is-active' : ''}`}
            >
              🔍 Auditor
            </button>
          </div>
        </div>
      </section>

      {/* Main Portals Grid */}
      <section className="pariksh-portals-section">
        <h2 className="pariksh-section-title">Examination Portals & Subsystems</h2>
        <div className="pariksh-portals-grid">
          {/* Featured Card: Unstop Smart Hire */}
          <div
            className="pariksh-portal-card pariksh-card-smarthire"
            onClick={() => onNavigate('smarthire')}
            style={{
              border: '1px solid rgba(56, 189, 248, 0.4)',
              background: 'linear-gradient(135deg, rgba(30, 58, 138, 0.35) 0%, rgba(19, 27, 46, 0.8) 100%)',
              boxShadow: '0 8px 24px rgba(56, 189, 248, 0.15)'
            }}
          >
            <div className="pariksh-card-badge" style={{ background: 'rgba(56, 189, 248, 0.15)', color: '#38bdf8', border: '1px solid rgba(56, 189, 248, 0.3)' }}>
              🔥 Unstop Smart Hire
            </div>
            <div className="pariksh-card-icon">💼</div>
            <h3 className="pariksh-card-title" style={{ color: '#ffffff' }}>Talent Assessment & Smart Hire</h3>
            <p className="pariksh-card-desc">
              Enterprise candidate screening pipeline, proctored coding and aptitude challenges, automated
              percentile ranking, cutoff controls, and candidate dossiers.
            </p>
            <div className="pariksh-card-footer">
              <span className="pariksh-card-action" style={{ color: '#38bdf8' }}>Open Smart Hire Pipeline →</span>
              <span className="pariksh-card-pill" style={{ color: '#34d399', border: '1px solid rgba(52, 211, 153, 0.3)' }}>Verified Talent</span>
            </div>
          </div>

          {/* Card 1: Candidate Terminal */}
          <div
            className="pariksh-portal-card pariksh-card-student"
            onClick={() => onNavigate('student')}
          >
            <div className="pariksh-card-badge">Candidate Terminal</div>
            <div className="pariksh-card-icon">🎓</div>
            <h3 className="pariksh-card-title">Student Examination</h3>
            <p className="pariksh-card-desc">
              Offline-first exam delivery, local response persistence, countdown deadline synchronization,
              outbox buffering, and cryptographic submission receipts.
            </p>
            <div className="pariksh-card-footer">
              <span className="pariksh-card-action">Launch Terminal →</span>
              <span className="pariksh-card-pill">Continuous Autosave</span>
            </div>
          </div>

          {/* Card 2: Paper Compiler */}
          <div
            className="pariksh-portal-card pariksh-card-compiler"
            onClick={() => onNavigate('compiler')}
          >
            <div className="pariksh-card-badge">Authoring Node</div>
            <div className="pariksh-card-icon">📝</div>
            <h3 className="pariksh-card-title">Exam Paper Compiler</h3>
            <p className="pariksh-card-desc">
              Import papers from PDF or text, configure sections, formulate answer keys,
              establish marking rules, and publish sanitized candidate-safe packages.
            </p>
            <div className="pariksh-card-footer">
              <span className="pariksh-card-action">Open Compiler →</span>
              <span className="pariksh-card-pill">Candidate Sanitization</span>
            </div>
          </div>

          {/* Card 3: Integrity Monitor */}
          <div
            className="pariksh-portal-card pariksh-card-monitor"
            onClick={() => onNavigate('monitor')}
          >
            <div className="pariksh-card-badge">Active Supervision</div>
            <div className="pariksh-card-icon">🚨</div>
            <h3 className="pariksh-card-title">Integrity Telemetry Monitor</h3>
            <p className="pariksh-card-desc">
              Supervise active sessions in real-time, monitor window focus loss events,
              inspect outbox synchronization state, and issue administrative holds.
            </p>
            <div className="pariksh-card-footer">
              <span className="pariksh-card-action">Monitor Cohort →</span>
              <span className="pariksh-card-pill">App Switch Detection</span>
            </div>
          </div>

          {/* Card 4: Incident Review */}
          <div
            className="pariksh-portal-card pariksh-card-incidents"
            onClick={() => onNavigate('incidents')}
          >
            <div className="pariksh-card-badge">Adjudication Engine</div>
            <div className="pariksh-card-icon">⚖️</div>
            <h3 className="pariksh-card-title">Incident Review & Appeals</h3>
            <p className="pariksh-card-desc">
              Structured misconduct review, evidence insufficiency safeguards, formal administrative
              holds, independent appeal adjudication, and attempt reinstatement.
            </p>
            <div className="pariksh-card-footer">
              <span className="pariksh-card-action">Review Cases →</span>
              <span className="pariksh-card-pill">Independent Review</span>
            </div>
          </div>

          {/* Card 5: Exam Intelligence */}
          <div
            className="pariksh-portal-card pariksh-card-analytics"
            onClick={() => onNavigate('analytics')}
          >
            <div className="pariksh-card-badge">Intelligence & Reports</div>
            <div className="pariksh-card-icon">📊</div>
            <h3 className="pariksh-card-title">Exam Intelligence & Scoring</h3>
            <p className="pariksh-card-desc">
              Deterministic integer scoring, question difficulty distribution, cohort learning gaps,
              result publication workflow, and tamper-resistant CSV exports.
            </p>
            <div className="pariksh-card-footer">
              <span className="pariksh-card-action">Inspect Analytics →</span>
              <span className="pariksh-card-pill">Scaled Integer Math</span>
            </div>
          </div>

          {/* Card 6: Server & RBAC */}
          <div
            className="pariksh-portal-card pariksh-card-server"
            onClick={() => onNavigate('server')}
          >
            <div className="pariksh-card-badge">Core Engine</div>
            <div className="pariksh-card-icon">⚙️</div>
            <h3 className="pariksh-card-title">Node Server & RBAC Sandbox</h3>
            <p className="pariksh-card-desc">
              Inspect embedded Ktor runtime health, verify TLS certificate fingerprint,
              monitor active sessions, and execute granular role-based access checks.
            </p>
            <div className="pariksh-card-footer">
              <span className="pariksh-card-action">Configure Node →</span>
              <span className="pariksh-card-pill">TLS Keystore</span>
            </div>
          </div>
        </div>
      </section>

      {/* Architecture Highlights & Guarantees */}
      <section className="pariksh-highlights-section">
        <h2 className="pariksh-section-title">Core Architectural Guarantees</h2>
        <div className="pariksh-highlights-grid">
          <div className="pariksh-highlight-item">
            <div className="pariksh-highlight-icon">🔒</div>
            <h4>Cryptographic Immutability</h4>
            <p>
              Every consequential action is hashed in a SHA-256 audit chain. Native SQLite database triggers
              abort in-place updates or deletions on audit records.
            </p>
          </div>

          <div className="pariksh-highlight-item">
            <div className="pariksh-highlight-icon">🎯</div>
            <h4>Zero Floating-Point Drift</h4>
            <p>
              Scoring is strictly computed using integer-scaled arithmetic (100 units = 1.00 mark). Identical
              inputs deterministically produce identical SHA-256 result hashes.
            </p>
          </div>

          <div className="pariksh-highlight-item">
            <div className="pariksh-highlight-icon">📶</div>
            <h4>120-Second Transit Grace</h4>
            <p>
              Candidate offline outbox provides continuous resilience. Responses arriving within transit grace
              are audited as accepted; expired submissions receive explicit authoritative records.
            </p>
          </div>

          <div className="pariksh-highlight-item">
            <div className="pariksh-highlight-icon">🛡️</div>
            <h4>Candidate Safe Sanitization</h4>
            <p>
              Candidate packages strictly strip answer keys, expected answers, worked solutions, and author
              metadata before delivery to candidate devices.
            </p>
          </div>
        </div>
      </section>

      {/* Footer */}
      <footer className="pariksh-footer">
        <div className="pariksh-footer-brand">
          <strong>PARIKSH (परीक्ष)</strong> — Sovereign Offline Examination System
        </div>
        <div className="pariksh-footer-meta">
          Application ID: <code>com.doomedams.app</code> • Built for Android 7.0+ (API 24 to 35) • Zero External Cloud Telemetry
        </div>
      </footer>
    </div>
  );
};
