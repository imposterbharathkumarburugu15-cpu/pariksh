import React from 'react';

export const App: React.FC = () => {
  return (
    <main className="shell-container">
      <div className="shell-card">
        <span className="shell-badge">Step 2 Foundation</span>
        <h1 className="shell-title">DOOMEDAMS</h1>
        <p className="shell-subtitle">
          Offline-First Examination Platform — Architectural Foundation & Domain Schemas
        </p>

        <div className="shell-status-grid">
          <div className="shell-status-item">
            <div className="shell-status-label">Runtime Engine</div>
            <div className="shell-status-value">
              <span className="shell-status-dot"></span> React 19 + Vite
            </div>
          </div>
          <div className="shell-status-item">
            <div className="shell-status-label">Type System</div>
            <div className="shell-status-value">
              <span className="shell-status-dot"></span> Strict TypeScript
            </div>
          </div>
          <div className="shell-status-item">
            <div className="shell-status-label">Validation</div>
            <div className="shell-status-value">
              <span className="shell-status-dot"></span> Zod Contracts
            </div>
          </div>
          <div className="shell-status-item">
            <div className="shell-status-label">Mode</div>
            <div className="shell-status-value">
              <span className="shell-status-dot"></span> Offline-First Ready
            </div>
          </div>
        </div>

        <p className="shell-footer-note">
          Domain schemas initialized. Awaiting Step 3 database & transactional audit engine.
        </p>
      </div>
    </main>
  );
};

export default App;
