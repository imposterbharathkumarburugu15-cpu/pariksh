import React, { useState } from 'react';
import { ExamPaperCompiler } from './compiler/ExamPaperCompiler';
import { StudentDashboard } from './candidate/StudentDashboard';
import { ExaminerIntegrityMonitor } from './candidate/ExaminerIntegrityMonitor';
import { IncidentReviewDashboard } from './incident/IncidentReviewDashboard';
import { ExamIntelligenceDashboard } from './analytics/ExamIntelligenceDashboard';
import { HomePage } from './home/HomePage';
import { SmartHireDashboard } from './smarthire/SmartHireDashboard';

interface ServerStatusData {
  status: string;
  host: string;
  port: number;
  tlsEnabled: boolean;
  sha256Fingerprint: string | null;
  activeSessions: number;
}

interface UserProfile {
  userId: string;
  displayName: string;
  role: string;
  instituteId: string;
  accountStatus: string;
  createdAt: string;
}

export const App: React.FC = () => {
  const [activeView, setActiveView] = useState<'home' | 'compiler' | 'server' | 'student' | 'monitor' | 'incidents' | 'analytics' | 'smarthire'>('home');
  const [serverUrl, setServerUrl] = useState<string>('http://127.0.0.1:8443');
  const [serverStatus, setServerStatus] = useState<ServerStatusData | null>(null);
  const [serverError, setServerError] = useState<string | null>(null);

  // Authentication state (held in-memory, never stored in localStorage)
  const [loginUserId, setLoginUserId] = useState<string>('');
  const [loginPassword, setLoginPassword] = useState<string>('');
  const [sessionToken, setSessionToken] = useState<string | null>(null);
  const [currentUser, setCurrentUser] = useState<UserProfile | null>(null);
  const [authError, setAuthError] = useState<string | null>(null);

  // RBAC test action state
  const [lastActionResponse, setLastActionResponse] = useState<{
    endpoint: string;
    status: number;
    data: any;
  } | null>(null);
  const [actionLoading, setActionLoading] = useState<boolean>(false);

  const checkServerStatus = async () => {
    setServerError(null);
    try {
      const res = await fetch(`${serverUrl}/api/server/status`);
      if (!res.ok) {
        throw new Error(`HTTP ${res.status}: ${res.statusText}`);
      }
      const data: ServerStatusData = await res.json();
      setServerStatus(data);
    } catch (err: any) {
      setServerStatus(null);
      setServerError(err.message || 'Connection refused or server offline');
    }
  };

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    setAuthError(null);
    try {
      const res = await fetch(`${serverUrl}/api/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ userId: loginUserId, password: loginPassword })
      });
      const data = await res.json();
      if (!res.ok) {
        throw new Error(data.message || data.error || `HTTP ${res.status}`);
      }
      setSessionToken(data.token);
      setCurrentUser(data.user);
      setLoginPassword('');
      if (data.user?.role === 'STUDENT') {
        setActiveView('student');
      }
    } catch (err: any) {
      setAuthError(err.message || 'Login failed');
    }
  };

  const handleLogout = async () => {
    if (!sessionToken) return;
    try {
      await fetch(`${serverUrl}/api/auth/logout`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${sessionToken}` }
      });
    } catch (err) {
      // Ignored on logout
    } finally {
      setSessionToken(null);
      setCurrentUser(null);
      setLastActionResponse(null);
    }
  };

  const handleQuickLogin = async (userId: string, role: string) => {
    setAuthError(null);
    try {
      const res = await fetch(`${serverUrl}/api/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ userId, password: 'password123' })
      });
      if (res.ok) {
        const data = await res.json();
        setSessionToken(data.token);
        setCurrentUser(data.user);
        if (role === 'STUDENT') setActiveView('student');
        return;
      }
    } catch {
      // Backend offline or in standalone test mode
    }
    // Local demo profile fallback
    setCurrentUser({
      userId,
      displayName: `${role.charAt(0) + role.slice(1).toLowerCase()} User`,
      role,
      instituteId: 'inst_default',
      accountStatus: 'ACTIVE',
      createdAt: new Date().toISOString()
    });
    setSessionToken(`token_${userId}`);
    if (role === 'STUDENT') setActiveView('student');
  };

  const executeProtectedAction = async (
    name: string,
    endpoint: string,
    method: 'GET' | 'POST',
    body?: any
  ) => {
    setActionLoading(true);
    try {
      const headers: Record<string, string> = {
        'Content-Type': 'application/json'
      };
      if (sessionToken) {
        headers['Authorization'] = `Bearer ${sessionToken}`;
      }

      const res = await fetch(`${serverUrl}${endpoint}`, {
        method,
        headers,
        body: body ? JSON.stringify(body) : undefined
      });

      let responseData: any;
      try {
        responseData = await res.json();
      } catch {
        responseData = { text: await res.text() };
      }

      setLastActionResponse({
        endpoint: `${name} [${method} ${endpoint}]`,
        status: res.status,
        data: responseData
      });
    } catch (err: any) {
      setLastActionResponse({
        endpoint: `${name} [${method} ${endpoint}]`,
        status: 0,
        data: { error: 'NETWORK_ERROR', message: err.message || 'Request failed' }
      });
    } finally {
      setActionLoading(false);
    }
  };

  return (
    <div style={{ minHeight: '100vh', display: 'flex', flexDirection: 'column' }}>
      <header className="app-navbar">
        <div className="app-navbar-brand" onClick={() => setActiveView('home')} style={{ cursor: 'pointer' }}>
          <span className="app-navbar-logo" style={{ letterSpacing: '0.04em', fontWeight: 800 }}>PARIKSH</span>
          <span className="compiler-badge" style={{ margin: 0, background: 'rgba(52, 211, 153, 0.15)', color: '#34d399', border: '1px solid rgba(52, 211, 153, 0.3)' }}>
            परीक्ष • v1.0
          </span>
        </div>

        <nav className="app-nav-tabs">
          <button
            className={`app-nav-btn ${activeView === 'home' ? 'active' : ''}`}
            onClick={() => setActiveView('home')}
          >
            🏠 Home
          </button>
          <button
            className={`app-nav-btn ${activeView === 'smarthire' ? 'active' : ''}`}
            onClick={() => setActiveView('smarthire')}
            style={{
              color: activeView === 'smarthire' ? '#38bdf8' : '#93c5fd',
              fontWeight: 700,
              background: activeView === 'smarthire' ? 'rgba(56, 189, 248, 0.15)' : 'rgba(56, 189, 248, 0.05)',
              border: '1px solid rgba(56, 189, 248, 0.25)'
            }}
          >
            💼 Smart Hire
          </button>
          <button
            className={`app-nav-btn ${activeView === 'student' ? 'active' : ''}`}
            onClick={() => setActiveView('student')}
          >
            🎓 Candidate Portal
          </button>
          <button
            className={`app-nav-btn ${activeView === 'compiler' ? 'active' : ''}`}
            onClick={() => setActiveView('compiler')}
          >
            📝 Exam Compiler
          </button>
          <button
            className={`app-nav-btn ${activeView === 'monitor' ? 'active' : ''}`}
            onClick={() => setActiveView('monitor')}
          >
            🚨 Integrity Monitor
          </button>
          <button
            className={`app-nav-btn ${activeView === 'incidents' ? 'active' : ''}`}
            onClick={() => setActiveView('incidents')}
          >
            ⚖️ Incident Review
          </button>
          <button
            className={`app-nav-btn ${activeView === 'analytics' ? 'active' : ''}`}
            onClick={() => setActiveView('analytics')}
          >
            📊 Intelligence
          </button>
          <button
            className={`app-nav-btn ${activeView === 'server' ? 'active' : ''}`}
            onClick={() => setActiveView('server')}
          >
            ⚙️ Server & RBAC
          </button>
        </nav>

        <div>
          {currentUser ? (
            <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem' }}>
              <span style={{ fontSize: '0.8rem', color: 'var(--color-text-secondary)' }}>
                {currentUser.displayName} <strong style={{ color: '#38bdf8' }}>({currentUser.role})</strong>
              </span>
              <button className="dev-btn dev-btn-danger btn-xs" onClick={handleLogout}>
                Logout
              </button>
            </div>
          ) : (
            <button
              className="dev-btn dev-btn-outline btn-xs"
              onClick={() => setActiveView('server')}
            >
              Sign In (Sandbox)
            </button>
          )}
        </div>
      </header>

      {activeView === 'home' ? (
        <HomePage
          onNavigate={setActiveView}
          serverUrl={serverUrl}
          setServerUrl={setServerUrl}
          currentUser={currentUser}
          onQuickLogin={handleQuickLogin}
          onLogout={handleLogout}
        />
      ) : activeView === 'smarthire' ? (
        <SmartHireDashboard />
      ) : activeView === 'student' ? (
        currentUser && sessionToken ? (
          <StudentDashboard
            currentUser={currentUser}
            serverUrl={serverUrl}
            sessionToken={sessionToken}
            onLogout={handleLogout}
          />
        ) : (
          <main className="shell-container">
            <div className="shell-card">
              <span className="shell-badge">Candidate Portal Authentication</span>
              <h2 className="shell-title" style={{ fontSize: '1.6rem' }}>Sign In to Student Portal</h2>
              <p className="shell-subtitle">
                Authenticate with your student account to access assigned examinations.
              </p>
              <form onSubmit={handleLogin} className="dev-form-grid">
                <input
                  type="text"
                  className="dev-input"
                  placeholder="Student User ID (e.g. usr_student_001)"
                  value={loginUserId}
                  onChange={(e) => setLoginUserId(e.target.value)}
                  required
                />
                <input
                  type="password"
                  className="dev-input"
                  placeholder="Password"
                  value={loginPassword}
                  onChange={(e) => setLoginPassword(e.target.value)}
                  required
                />
                <button type="submit" className="dev-btn dev-btn-accent">
                  Authenticate & Launch Portal
                </button>
              </form>
              {authError && (
                <div className="dev-alert dev-alert-danger" style={{ marginTop: '1rem' }}>
                  {authError}
                </div>
              )}
            </div>
          </main>
        )
      ) : activeView === 'monitor' ? (
        <ExaminerIntegrityMonitor
          serverUrl={serverUrl}
          authToken={sessionToken || ''}
          onClose={() => setActiveView('server')}
        />
      ) : activeView === 'incidents' ? (
        <IncidentReviewDashboard
          serverUrl={serverUrl}
          sessionToken={sessionToken}
          currentUser={currentUser}
        />
      ) : activeView === 'analytics' ? (
        <ExamIntelligenceDashboard
          serverUrl={serverUrl}
          sessionToken={sessionToken}
          currentUser={currentUser}
          onOpenIncidents={() => setActiveView('incidents')}
          onClose={() => setActiveView('server')}
        />
      ) : activeView === 'compiler' ? (
        <ExamPaperCompiler
          serverUrl={serverUrl}
          sessionToken={sessionToken}
          currentUser={currentUser}
        />
      ) : (
        <main className="shell-container">
          <div className="shell-card" style={{ maxWidth: '880px' }}>
            <span className="shell-badge">Step 4 Trusted Server & RBAC</span>
            <h1 className="shell-title">DOOMEDAMS</h1>
            <p className="shell-subtitle">
              Examiner Offline Node — Local Application Server & Server-Side RBAC Enforcement
            </p>

        {/* SECTION 1: SERVER STATUS */}
        <div className="dev-panel">
          <h2 className="dev-panel-title">1. Embedded Examiner Server Node</h2>
          <div className="dev-form-row">
            <input
              type="text"
              className="dev-input"
              value={serverUrl}
              onChange={(e) => setServerUrl(e.target.value)}
              placeholder="http://127.0.0.1:8443"
            />
            <button className="dev-btn dev-btn-primary" onClick={checkServerStatus}>
              Ping Server
            </button>
          </div>

          {serverStatus && (
            <div className="shell-status-grid" style={{ marginTop: '1rem' }}>
              <div className="shell-status-item">
                <div className="shell-status-label">State</div>
                <div className="shell-status-value" style={{ color: 'var(--color-success)' }}>
                  ● {serverStatus.status}
                </div>
              </div>
              <div className="shell-status-item">
                <div className="shell-status-label">Host & Port</div>
                <div className="shell-status-value">
                  {serverStatus.host}:{serverStatus.port}
                </div>
              </div>
              <div className="shell-status-item">
                <div className="shell-status-label">Transport Security</div>
                <div className="shell-status-value">
                  {serverStatus.tlsEnabled ? '🔒 TLS Enabled' : 'Plain HTTP (Testing)'}
                </div>
              </div>
              <div className="shell-status-item">
                <div className="shell-status-label">Active Sessions</div>
                <div className="shell-status-value">{serverStatus.activeSessions}</div>
              </div>
            </div>
          )}

          {serverError && (
            <div className="dev-alert dev-alert-danger">
              Server Unreachable: {serverError}
            </div>
          )}
        </div>

        {/* SECTION 2: AUTHENTICATION */}
        <div className="dev-panel" style={{ marginTop: '1.5rem' }}>
          <h2 className="dev-panel-title">2. Offline Session Authentication</h2>
          {!currentUser ? (
            <form onSubmit={handleLogin} className="dev-form-grid">
              <input
                type="text"
                className="dev-input"
                placeholder="User ID (e.g. usr_student_001)"
                value={loginUserId}
                onChange={(e) => setLoginUserId(e.target.value)}
                required
              />
              <input
                type="password"
                className="dev-input"
                placeholder="Password"
                value={loginPassword}
                onChange={(e) => setLoginPassword(e.target.value)}
                required
              />
              <button type="submit" className="dev-btn dev-btn-accent">
                Authenticate Session
              </button>
            </form>
          ) : (
            <div>
              <div className="dev-user-card">
                <div>
                  <strong>{currentUser.displayName}</strong> ({currentUser.userId})
                  <div className="dev-role-badge">Role: {currentUser.role}</div>
                  <div className="dev-scope-badge">Institute: {currentUser.instituteId}</div>
                </div>
                <button className="dev-btn dev-btn-danger" onClick={handleLogout}>
                  Terminate Session
                </button>
              </div>
            </div>
          )}

          {authError && (
            <div className="dev-alert dev-alert-danger" style={{ marginTop: '0.75rem' }}>
              Authentication Failure: {authError}
            </div>
          )}
        </div>

        {/* SECTION 3: RBAC VERIFICATION SANDBOX */}
        <div className="dev-panel" style={{ marginTop: '1.5rem' }}>
          <h2 className="dev-panel-title">3. Server-Enforced RBAC & Scope Test Actions</h2>
          <p style={{ fontSize: '0.85rem', color: 'var(--color-text-secondary)', marginBottom: '1rem' }}>
            Trigger real API calls to the offline server. All authorization decisions are strictly enforced on the server.
          </p>

          <div className="dev-action-grid">
            <button
              className="dev-btn dev-btn-outline"
              disabled={actionLoading}
              onClick={() => executeProtectedAction('Current Profile', '/api/auth/me', 'GET')}
            >
              Verify Profile (GET /api/auth/me)
            </button>
            <button
              className="dev-btn dev-btn-outline"
              disabled={actionLoading}
              onClick={() =>
                executeProtectedAction(
                  'Provision User',
                  '/api/admin/users/provision',
                  'POST',
                  {
                    userId: `usr_test_${Date.now()}`,
                    displayName: 'Sandbox User',
                    role: 'STUDENT',
                    instituteId: currentUser?.instituteId || 'inst_001',
                    password: 'TemporaryPass123!'
                  }
                )
              }
            >
              Admin Provision User (Requires USER_PROVISION)
            </button>
            <button
              className="dev-btn dev-btn-outline"
              disabled={actionLoading}
              onClick={() =>
                executeProtectedAction('Fetch Exam', '/api/exams/exam-001', 'GET')
              }
            >
              Fetch Exam exam-001 (Requires Scope / Enrollment)
            </button>
            <button
              className="dev-btn dev-btn-outline"
              disabled={actionLoading}
              onClick={() =>
                executeProtectedAction(
                  'Submit Own Answer',
                  '/api/attempts/att-001/answers',
                  'POST',
                  {
                    questionId: 'q-001',
                    value: 'A',
                    clientSequenceNumber: 1,
                    clientRecordedAt: new Date().toISOString()
                  }
                )
              }
            >
              Submit Answer (att-001, Own Attempt)
            </button>
            <button
              className="dev-btn dev-btn-outline"
              disabled={actionLoading}
              onClick={() =>
                executeProtectedAction(
                  'Submit Other Answer',
                  '/api/attempts/att-999-forbidden/answers',
                  'POST',
                  {
                    questionId: 'q-001',
                    value: 'B',
                    clientSequenceNumber: 1,
                    clientRecordedAt: new Date().toISOString()
                  }
                )
              }
            >
              Submit to Foreign Attempt (Fail-Closed Check)
            </button>
            <button
              className="dev-btn dev-btn-outline"
              disabled={actionLoading}
              onClick={() =>
                executeProtectedAction(
                  'Report Integrity Event',
                  '/api/attempts/att-001/integrity-events',
                  'POST',
                  {
                    eventId: `evt_${Date.now()}`,
                    eventCategory: 'APP_SWITCH',
                    clientTimestamp: new Date().toISOString(),
                    sequenceNumber: 1,
                    appPackageName: 'com.browser.app',
                    displayedAppName: 'Browser'
                  }
                )
              }
            >
              Report Integrity Event (att-001)
            </button>
          </div>

          {lastActionResponse && (
            <div className="dev-response-container">
              <div className="dev-response-header">
                <span>{lastActionResponse.endpoint}</span>
                <span
                  className={
                    lastActionResponse.status >= 200 && lastActionResponse.status < 300
                      ? 'dev-badge-success'
                      : lastActionResponse.status === 403
                      ? 'dev-badge-forbidden'
                      : 'dev-badge-error'
                  }
                >
                  HTTP {lastActionResponse.status || 'FAILED'}
                </span>
              </div>
              <pre className="dev-response-body">
                {JSON.stringify(lastActionResponse.data, null, 2)}
              </pre>
            </div>
          )}
        </div>

        <p className="shell-footer-note" style={{ marginTop: '1.5rem' }}>
          Role-Based Access Control is cryptographically validated on the local Android server.
        </p>
      </div>
    </main>
      )}
    </div>
  );
};

export default App;

