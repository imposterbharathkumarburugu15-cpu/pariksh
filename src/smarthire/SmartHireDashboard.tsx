import React, { useState, useMemo } from 'react';

export interface CandidateApplicant {
  id: string;
  name: string;
  email: string;
  institute: string;
  roleTrack: string;
  round: string;
  totalScore: number;
  maxScore: number;
  percentile: number;
  accuracy: number;
  speedMinutes: number;
  integrityScore: number;
  switchCount: number;
  status: 'APPLIED' | 'SCREENING' | 'SHORTLISTED' | 'INTERVIEW_SCHEDULED' | 'SELECTED' | 'REJECTED';
  skills: { name: string; score: number }[];
  submissionReceipt: string;
  aiRecommendation: 'STRONG_HIRE' | 'HIRE' | 'BORDERLINE' | 'NO_HIRE';
  recommendationNotes: string;
}

const INITIAL_CANDIDATES: CandidateApplicant[] = [
  {
    id: 'cand_101',
    name: 'Aarav Sharma',
    email: 'aarav.sharma@iitd.ac.in',
    institute: 'IIT Delhi',
    roleTrack: 'Software Development Engineer (Backend)',
    round: 'Round 2: Proctored Core Technical',
    totalScore: 94,
    maxScore: 100,
    percentile: 99.6,
    accuracy: 96,
    speedMinutes: 42,
    integrityScore: 99,
    switchCount: 0,
    status: 'SHORTLISTED',
    skills: [
      { name: 'Data Structures & Algorithms', score: 98 },
      { name: 'System Design & OS', score: 92 },
      { name: 'SQL & Database Optimization', score: 95 },
      { name: 'Problem Solving Speed', score: 91 }
    ],
    submissionReceipt: 'rcpt_7f92a10b_c101_verified',
    aiRecommendation: 'STRONG_HIRE',
    recommendationNotes: 'Flawless execution in dynamic programming & concurrency questions. Zero focus loss alerts.'
  },
  {
    id: 'cand_102',
    name: 'Diya Patel',
    email: 'diya.patel@bits.ac.in',
    institute: 'BITS Pilani',
    roleTrack: 'Full Stack & Mobile Engineer',
    round: 'Round 2: Proctored Core Technical',
    totalScore: 89,
    maxScore: 100,
    percentile: 97.4,
    accuracy: 91,
    speedMinutes: 48,
    integrityScore: 97,
    switchCount: 0,
    status: 'SHORTLISTED',
    skills: [
      { name: 'Data Structures & Algorithms', score: 88 },
      { name: 'System Design & OS', score: 90 },
      { name: 'SQL & Database Optimization', score: 92 },
      { name: 'Problem Solving Speed', score: 86 }
    ],
    submissionReceipt: 'rcpt_8b41e30a_c102_verified',
    aiRecommendation: 'STRONG_HIRE',
    recommendationNotes: 'Exceptional architectural clarity; strong handling of API constraints and edge cases.'
  },
  {
    id: 'cand_103',
    name: 'Rohan Verma',
    email: 'rohan.v@nitt.edu',
    institute: 'NIT Trichy',
    roleTrack: 'Systems & Cloud Infrastructure',
    round: 'Round 2: Proctored Core Technical',
    totalScore: 84,
    maxScore: 100,
    percentile: 92.1,
    accuracy: 87,
    speedMinutes: 54,
    integrityScore: 95,
    switchCount: 1,
    status: 'INTERVIEW_SCHEDULED',
    skills: [
      { name: 'Data Structures & Algorithms', score: 82 },
      { name: 'System Design & OS', score: 89 },
      { name: 'SQL & Database Optimization', score: 85 },
      { name: 'Problem Solving Speed', score: 80 }
    ],
    submissionReceipt: 'rcpt_3f19c99d_c103_verified',
    aiRecommendation: 'HIRE',
    recommendationNotes: 'Solid fundamentals in memory allocation and network transport protocols.'
  },
  {
    id: 'cand_104',
    name: 'Ananya Deshmukh',
    email: 'ananya.d@coep.ac.in',
    institute: 'COEP Pune',
    roleTrack: 'Data Engineering & AI Pipelines',
    round: 'Round 2: Proctored Core Technical',
    totalScore: 81,
    maxScore: 100,
    percentile: 89.5,
    accuracy: 85,
    speedMinutes: 52,
    integrityScore: 98,
    switchCount: 0,
    status: 'SCREENING',
    skills: [
      { name: 'Data Structures & Algorithms', score: 79 },
      { name: 'System Design & OS', score: 84 },
      { name: 'SQL & Database Optimization', score: 91 },
      { name: 'Problem Solving Speed', score: 70 }
    ],
    submissionReceipt: 'rcpt_5a27d118_c104_verified',
    aiRecommendation: 'HIRE',
    recommendationNotes: 'High database proficiency and clean query optimization; recommended for data infrastructure.'
  },
  {
    id: 'cand_105',
    name: 'Vikram Joshi',
    email: 'vikram.j@iiith.ac.in',
    institute: 'IIIT Hyderabad',
    roleTrack: 'Software Development Engineer (Backend)',
    round: 'Round 2: Proctored Core Technical',
    totalScore: 76,
    maxScore: 100,
    percentile: 79.8,
    accuracy: 78,
    speedMinutes: 58,
    integrityScore: 72,
    switchCount: 4,
    status: 'SCREENING',
    skills: [
      { name: 'Data Structures & Algorithms', score: 78 },
      { name: 'System Design & OS', score: 74 },
      { name: 'SQL & Database Optimization', score: 72 },
      { name: 'Problem Solving Speed', score: 80 }
    ],
    submissionReceipt: 'rcpt_9e33b412_c105_flagged',
    aiRecommendation: 'BORDERLINE',
    recommendationNotes: 'Multiple window blur telemetry events recorded; requires proctor review before proceeding.'
  },
  {
    id: 'cand_106',
    name: 'Sneha Kulkarni',
    email: 'sneha.k@manipal.edu',
    institute: 'Manipal Institute of Technology',
    roleTrack: 'Frontend & UI Engineering',
    round: 'Round 2: Proctored Core Technical',
    totalScore: 68,
    maxScore: 100,
    percentile: 65.2,
    accuracy: 72,
    speedMinutes: 59,
    integrityScore: 94,
    switchCount: 0,
    status: 'APPLIED',
    skills: [
      { name: 'Data Structures & Algorithms', score: 65 },
      { name: 'System Design & OS', score: 68 },
      { name: 'SQL & Database Optimization', score: 70 },
      { name: 'Problem Solving Speed', score: 69 }
    ],
    submissionReceipt: 'rcpt_1a98c772_c106_verified',
    aiRecommendation: 'NO_HIRE',
    recommendationNotes: 'Below competitive technical cutoff for this round; eligible for junior frontend pool.'
  }
];

export const SmartHireDashboard: React.FC = () => {
  const [candidates, setCandidates] = useState<CandidateApplicant[]>(INITIAL_CANDIDATES);
  const [activeTab, setActiveTab] = useState<'pipeline' | 'drives' | 'benchmarks'>('pipeline');
  const [searchQuery, setSearchQuery] = useState('');
  const [roleFilter, setRoleFilter] = useState('ALL');
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [minCutoff, setMinCutoff] = useState(75);
  const [selectedCandidate, setSelectedCandidate] = useState<CandidateApplicant | null>(null);
  const [toastMessage, setToastMessage] = useState<string | null>(null);

  const showToast = (msg: string) => {
    setToastMessage(msg);
    setTimeout(() => setToastMessage(null), 3000);
  };

  const filteredCandidates = useMemo(() => {
    return candidates.filter((c) => {
      const matchesSearch =
        c.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
        c.institute.toLowerCase().includes(searchQuery.toLowerCase()) ||
        c.roleTrack.toLowerCase().includes(searchQuery.toLowerCase());
      const matchesRole = roleFilter === 'ALL' || c.roleTrack.includes(roleFilter);
      const matchesStatus = statusFilter === 'ALL' || c.status === statusFilter;
      const matchesCutoff = c.totalScore >= minCutoff;
      return matchesSearch && matchesRole && matchesStatus && matchesCutoff;
    });
  }, [candidates, searchQuery, roleFilter, statusFilter, minCutoff]);

  const kpis = useMemo(() => {
    const total = candidates.length;
    const shortlisted = candidates.filter((c) => c.status === 'SHORTLISTED' || c.status === 'INTERVIEW_SCHEDULED' || c.status === 'SELECTED').length;
    const avgScore = Math.round(candidates.reduce((acc, c) => acc + c.totalScore, 0) / (total || 1));
    const avgIntegrity = Math.round(candidates.reduce((acc, c) => acc + c.integrityScore, 0) / (total || 1));
    return {
      total,
      shortlisted,
      shortlistRate: Math.round((shortlisted / total) * 100),
      avgScore,
      avgIntegrity
    };
  }, [candidates]);

  const handleUpdateStatus = (candidateId: string, newStatus: CandidateApplicant['status']) => {
    setCandidates((prev) =>
      prev.map((c) => (c.id === candidateId ? { ...c, status: newStatus } : c))
    );
    if (selectedCandidate && selectedCandidate.id === candidateId) {
      setSelectedCandidate((prev) => (prev ? { ...prev, status: newStatus } : null));
    }
    showToast(`Status updated to ${newStatus}`);
  };

  const handleAutoShortlistTopPercent = () => {
    setCandidates((prev) =>
      prev.map((c) => {
        if (c.totalScore >= 85 && c.integrityScore >= 90) {
          return { ...c, status: 'SHORTLISTED' };
        }
        return c;
      })
    );
    showToast('Auto-shortlisted candidates scoring ≥ 85 marks with clean proctor score!');
  };

  const handleExportShortlistCsv = () => {
    const rows = [
      ['Candidate Name', 'Institute', 'Role Track', 'Score', 'Percentile', 'Integrity Trust', 'Recommendation', 'Status'],
      ...filteredCandidates.map((c) => [
        c.name,
        c.institute,
        c.roleTrack,
        c.totalScore.toString(),
        `${c.percentile}%ile`,
        `${c.integrityScore}%`,
        c.aiRecommendation,
        c.status
      ])
    ];
    const csvContent = 'data:text/csv;charset=utf-8,' + rows.map((e) => e.join(',')).join('\n');
    const encodedUri = encodeURI(csvContent);
    const link = document.createElement('a');
    link.setAttribute('href', encodedUri);
    link.setAttribute('download', `pariksh_smart_hire_shortlist_${Date.now()}.csv`);
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    showToast('Downloaded Smart Hire candidate shortlist CSV.');
  };

  return (
    <div className="smarthire-container">
      {/* Toast Notification */}
      {toastMessage && (
        <div className="smarthire-toast">
          <span>✨ {toastMessage}</span>
        </div>
      )}

      {/* Header Banner */}
      <div className="smarthire-hero">
        <div className="smarthire-hero-badge">
          <span>💼 UNSTOP-GRADE ENTERPRISE SMART HIRE</span>
          <span className="smarthire-hero-pill">Autonomous Screening & Proctored Assessments</span>
        </div>
        <div className="smarthire-hero-flex">
          <div>
            <h1 className="smarthire-title">Talent Assessment & Smart Hire Pipeline</h1>
            <p className="smarthire-subtitle">
              Screen, rank, and hire verified top-tier tech talent using high-integrity proctored challenges,
              deterministic benchmarks, and forensic anti-cheat telemetry.
            </p>
          </div>
          <div className="smarthire-hero-actions">
            <button onClick={handleAutoShortlistTopPercent} className="smarthire-btn-primary">
              ⚡ Auto-Shortlist Top 15%
            </button>
            <button onClick={handleExportShortlistCsv} className="smarthire-btn-outline">
              📥 Export Shortlist CSV
            </button>
          </div>
        </div>

        {/* Tab Navigation */}
        <div className="smarthire-tabs-bar">
          <button
            className={`smarthire-tab-btn ${activeTab === 'pipeline' ? 'is-active' : ''}`}
            onClick={() => setActiveTab('pipeline')}
          >
            📋 Assessment Roster & Funnel
          </button>
          <button
            className={`smarthire-tab-btn ${activeTab === 'drives' ? 'is-active' : ''}`}
            onClick={() => setActiveTab('drives')}
          >
            🏆 Active Hiring Drives (3)
          </button>
          <button
            className={`smarthire-tab-btn ${activeTab === 'benchmarks' ? 'is-active' : ''}`}
            onClick={() => setActiveTab('benchmarks')}
          >
            🎯 Competency Benchmarks & Insights
          </button>
        </div>
      </div>

      {/* KPI Cards */}
      <div className="smarthire-kpi-grid">
        <div className="smarthire-kpi-card">
          <div className="smarthire-kpi-label">TOTAL ASSESSED</div>
          <div className="smarthire-kpi-value">{kpis.total} Candidates</div>
          <div className="smarthire-kpi-footer text-emerald">100% Verified Submissions</div>
        </div>

        <div className="smarthire-kpi-card">
          <div className="smarthire-kpi-label">SHORTLISTED PIPELINE</div>
          <div className="smarthire-kpi-value text-blue">{kpis.shortlisted} Candidates</div>
          <div className="smarthire-kpi-footer">{kpis.shortlistRate}% Qualified Rate</div>
        </div>

        <div className="smarthire-kpi-card">
          <div className="smarthire-kpi-label">AVERAGE COHORT SCORE</div>
          <div className="smarthire-kpi-value">{kpis.avgScore} / 100</div>
          <div className="smarthire-kpi-footer text-muted">Scaled integer arithmetic</div>
        </div>

        <div className="smarthire-kpi-card">
          <div className="smarthire-kpi-label">PROCTOR INTEGRITY INDEX</div>
          <div className="smarthire-kpi-value text-emerald">{kpis.avgIntegrity}% Clean</div>
          <div className="smarthire-kpi-footer">Zero tampering guarantee</div>
        </div>
      </div>

      {activeTab === 'pipeline' && (
        <div className="smarthire-pipeline-view">
          {/* Controls & Filter Bar */}
          <div className="smarthire-controls-card">
            <div className="smarthire-search-box">
              <input
                type="text"
                placeholder="🔍 Search candidate name, college, or role..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                className="smarthire-search-input"
              />
            </div>

            <div className="smarthire-filter-group">
              <select
                value={roleFilter}
                onChange={(e) => setRoleFilter(e.target.value)}
                className="smarthire-select"
              >
                <option value="ALL">All Role Tracks</option>
                <option value="Backend">Backend Engineer</option>
                <option value="Full Stack">Full Stack Engineer</option>
                <option value="Cloud">Systems & Cloud</option>
                <option value="Data">Data & AI</option>
              </select>

              <select
                value={statusFilter}
                onChange={(e) => setStatusFilter(e.target.value)}
                className="smarthire-select"
              >
                <option value="ALL">All Hiring Stages</option>
                <option value="APPLIED">Applied</option>
                <option value="SCREENING">Screening</option>
                <option value="SHORTLISTED">Shortlisted</option>
                <option value="INTERVIEW_SCHEDULED">Interview Scheduled</option>
                <option value="SELECTED">Selected</option>
                <option value="REJECTED">Rejected</option>
              </select>

              <div className="smarthire-cutoff-slider-wrapper">
                <span className="smarthire-cutoff-label">
                  Score Cutoff: <strong>≥ {minCutoff}</strong>
                </span>
                <input
                  type="range"
                  min="50"
                  max="95"
                  value={minCutoff}
                  onChange={(e) => setMinCutoff(Number(e.target.value))}
                  className="smarthire-slider"
                />
              </div>
            </div>
          </div>

          {/* Candidate Table */}
          <div className="smarthire-table-wrapper">
            <table className="smarthire-table">
              <thead>
                <tr>
                  <th>CANDIDATE & INSTITUTE</th>
                  <th>ROLE TRACK</th>
                  <th>ASSESSMENT SCORE</th>
                  <th>SPEED & ACCURACY</th>
                  <th>PROCTOR TRUST</th>
                  <th>AI RECOM</th>
                  <th>HIRING STAGE</th>
                  <th>ACTION</th>
                </tr>
              </thead>
              <tbody>
                {filteredCandidates.map((c) => (
                  <tr key={c.id} className="smarthire-row">
                    <td>
                      <div className="smarthire-candidate-info">
                        <div className="smarthire-avatar">{c.name.charAt(0)}</div>
                        <div>
                          <div className="smarthire-name">{c.name}</div>
                          <div className="smarthire-subtext">{c.institute} • {c.email}</div>
                        </div>
                      </div>
                    </td>

                    <td>
                      <span className="smarthire-role-pill">{c.roleTrack}</span>
                    </td>

                    <td>
                      <div className="smarthire-score-box">
                        <span className="smarthire-score-num">{c.totalScore}</span>
                        <span className="smarthire-percentile-pill">
                          {c.percentile}%ile
                        </span>
                      </div>
                    </td>

                    <td>
                      <div className="smarthire-subtext">
                        ⏱️ {c.speedMinutes} mins • 🎯 {c.accuracy}% acc
                      </div>
                    </td>

                    <td>
                      <div className="smarthire-integrity-cell">
                        <span
                          className={`smarthire-trust-badge ${
                            c.integrityScore >= 95
                              ? 'trust-high'
                              : c.integrityScore >= 80
                              ? 'trust-med'
                              : 'trust-low'
                          }`}
                        >
                          {c.integrityScore}% {c.switchCount > 0 ? `(${c.switchCount} switch)` : '🛡️ Clean'}
                        </span>
                      </div>
                    </td>

                    <td>
                      <span
                        className={`smarthire-recom-badge recom-${c.aiRecommendation.toLowerCase()}`}
                      >
                        {c.aiRecommendation.replace('_', ' ')}
                      </span>
                    </td>

                    <td>
                      <span className={`smarthire-status-badge status-${c.status.toLowerCase()}`}>
                        {c.status.replace('_', ' ')}
                      </span>
                    </td>

                    <td>
                      <button
                        onClick={() => setSelectedCandidate(c)}
                        className="smarthire-view-btn"
                      >
                        Dossier ↗
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {activeTab === 'drives' && (
        <div className="smarthire-drives-grid">
          <div className="smarthire-drive-card">
            <div className="smarthire-drive-header">
              <span className="smarthire-drive-badge">ACTIVE DRIVE</span>
              <span className="text-muted">Batch 2026</span>
            </div>
            <h3 className="smarthire-drive-title">National Campus Hiring League — Software Engineering</h3>
            <p className="smarthire-drive-desc">
              Multi-stage hackathon & proctored algorithmic assessment for SDE-1 backend, frontend, and systems roles across 80+ engineering institutions.
            </p>
            <div className="smarthire-drive-meta">
              <div>🎯 3 Assessment Rounds</div>
              <div>👥 1,420 Candidates</div>
              <div>⏳ Authoritative Timer: 90 Mins</div>
            </div>
            <div className="smarthire-drive-footer">
              <span className="text-emerald">● Proctored Submissions Open</span>
              <button className="smarthire-btn-sm" onClick={() => setActiveTab('pipeline')}>
                Manage Pipeline →
              </button>
            </div>
          </div>

          <div className="smarthire-drive-card">
            <div className="smarthire-drive-header">
              <span className="smarthire-drive-badge">SCHEDULED DRIVE</span>
              <span className="text-muted">Specialized</span>
            </div>
            <h3 className="smarthire-drive-title">Systems & Distributed Infrastructure Fellowship</h3>
            <p className="smarthire-drive-desc">
              Rigorous screening covering Linux kernel internals, concurrency primitives, networking, and memory-safe systems engineering.
            </p>
            <div className="smarthire-drive-meta">
              <div>🎯 2 Rounds + Case Study</div>
              <div>👥 450 Candidates</div>
              <div>⏳ Authoritative Timer: 120 Mins</div>
            </div>
            <div className="smarthire-drive-footer">
              <span className="text-blue">● Starts Next Week</span>
              <button className="smarthire-btn-sm" onClick={() => setActiveTab('pipeline')}>
                Inspect Roster →
              </button>
            </div>
          </div>

          <div className="smarthire-drive-card">
            <div className="smarthire-drive-header">
              <span className="smarthire-drive-badge">COMPLETED DRIVE</span>
              <span className="text-muted">Analytics</span>
            </div>
            <h3 className="smarthire-drive-title">Data Engineering & Analytics Challenge</h3>
            <p className="smarthire-drive-desc">
              SQL optimization, pipeline design, stream processing, and dimensional modeling assessment for data engineering roles.
            </p>
            <div className="smarthire-drive-meta">
              <div>🎯 1 Round (60 Mins)</div>
              <div>👥 780 Candidates</div>
              <div>🏆 84 Shortlisted</div>
            </div>
            <div className="smarthire-drive-footer">
              <span className="text-muted">● Archived & Ranked</span>
              <button className="smarthire-btn-sm" onClick={handleExportShortlistCsv}>
                Export Results →
              </button>
            </div>
          </div>
        </div>
      )}

      {activeTab === 'benchmarks' && (
        <div className="smarthire-benchmarks-section">
          <div className="smarthire-benchmark-grid">
            <div className="smarthire-benchmark-card">
              <h4>📊 Competency Mastery Breakdown</h4>
              <p className="smarthire-subtext">Normalized performance across key technical modules</p>
              <div className="smarthire-skill-bars">
                <div className="smarthire-bar-group">
                  <div className="smarthire-bar-label">
                    <span>Data Structures & Algorithms</span>
                    <span>88% Cohort Avg</span>
                  </div>
                  <div className="smarthire-bar-track">
                    <div className="smarthire-bar-fill" style={{ width: '88%' }} />
                  </div>
                </div>

                <div className="smarthire-bar-group">
                  <div className="smarthire-bar-label">
                    <span>System Design & Architecture</span>
                    <span>79% Cohort Avg</span>
                  </div>
                  <div className="smarthire-bar-track">
                    <div className="smarthire-bar-fill" style={{ width: '79%', background: '#38bdf8' }} />
                  </div>
                </div>

                <div className="smarthire-bar-group">
                  <div className="smarthire-bar-label">
                    <span>SQL & Query Optimization</span>
                    <span>91% Cohort Avg</span>
                  </div>
                  <div className="smarthire-bar-track">
                    <div className="smarthire-bar-fill" style={{ width: '91%', background: '#34d399' }} />
                  </div>
                </div>

                <div className="smarthire-bar-group">
                  <div className="smarthire-bar-label">
                    <span>Problem Solving Velocity</span>
                    <span>72% Cohort Avg</span>
                  </div>
                  <div className="smarthire-bar-track">
                    <div className="smarthire-bar-fill" style={{ width: '72%', background: '#f59e0b' }} />
                  </div>
                </div>
              </div>
            </div>

            <div className="smarthire-benchmark-card">
              <h4>🛡️ Proctoring & Integrity Telemetry Summary</h4>
              <p className="smarthire-subtext">Real-time anti-cheat metrics across local mesh cohort</p>
              <div className="smarthire-stat-list">
                <div className="smarthire-stat-row">
                  <span>Clean Sessions (Zero focus-loss events)</span>
                  <strong className="text-emerald">94.2%</strong>
                </div>
                <div className="smarthire-stat-row">
                  <span>Minor Window Blur (&lt; 2 seconds)</span>
                  <strong className="text-blue">4.5%</strong>
                </div>
                <div className="smarthire-stat-row">
                  <span>Unauthorised App Switch (&gt; 5 seconds)</span>
                  <strong className="text-danger">1.3% (Flagged)</strong>
                </div>
                <div className="smarthire-stat-row">
                  <span>Cryptographic Receipts Issued</span>
                  <strong>100% Verified</strong>
                </div>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Candidate Dossier Modal */}
      {selectedCandidate && (
        <div className="smarthire-modal-backdrop" onClick={() => setSelectedCandidate(null)}>
          <div className="smarthire-modal-card" onClick={(e) => e.stopPropagation()}>
            <div className="smarthire-modal-header">
              <div className="smarthire-modal-title-box">
                <div className="smarthire-modal-avatar">{selectedCandidate.name.charAt(0)}</div>
                <div>
                  <h3 className="smarthire-modal-name">{selectedCandidate.name}</h3>
                  <div className="smarthire-subtext">{selectedCandidate.institute} • {selectedCandidate.email}</div>
                </div>
              </div>
              <button onClick={() => setSelectedCandidate(null)} className="smarthire-close-btn">
                ✕
              </button>
            </div>

            <div className="smarthire-modal-body">
              {/* Score Highlight Box */}
              <div className="smarthire-score-banner">
                <div>
                  <div className="smarthire-score-banner-label">OVERALL TEST SCORE</div>
                  <div className="smarthire-score-banner-val">{selectedCandidate.totalScore} / 100</div>
                  <div className="smarthire-subtext">{selectedCandidate.percentile}th Percentile</div>
                </div>
                <div>
                  <div className="smarthire-score-banner-label">AI HIRING RECOMMENDATION</div>
                  <span className={`smarthire-recom-badge recom-${selectedCandidate.aiRecommendation.toLowerCase()}`}>
                    {selectedCandidate.aiRecommendation.replace('_', ' ')}
                  </span>
                  <div className="smarthire-subtext" style={{ marginTop: '4px' }}>
                    Speed: {selectedCandidate.speedMinutes} mins
                  </div>
                </div>
                <div>
                  <div className="smarthire-score-banner-label">PROCTOR INTEGRITY</div>
                  <div className="smarthire-score-banner-val text-emerald">{selectedCandidate.integrityScore}%</div>
                  <div className="smarthire-subtext">Switches: {selectedCandidate.switchCount}</div>
                </div>
              </div>

              {/* Competency Ratings */}
              <div className="smarthire-modal-section">
                <h4>🎯 Verified Skill Competencies</h4>
                <div className="smarthire-modal-skills-grid">
                  {selectedCandidate.skills.map((s, idx) => (
                    <div key={idx} className="smarthire-skill-badge-item">
                      <span>{s.name}</span>
                      <strong>{s.score}%</strong>
                    </div>
                  ))}
                </div>
              </div>

              {/* Recommendation Notes */}
              <div className="smarthire-modal-section">
                <h4>📝 Assessment Evaluation Notes</h4>
                <div className="smarthire-eval-notes">
                  {selectedCandidate.recommendationNotes}
                </div>
              </div>

              {/* Cryptographic Receipt */}
              <div className="smarthire-modal-section">
                <h4>🔒 Submission Verification Receipt</h4>
                <div className="smarthire-receipt-box">
                  <code>{selectedCandidate.submissionReceipt}</code>
                  <span className="smarthire-verified-tag">✓ SHA-256 Match</span>
                </div>
              </div>

              {/* Hiring Decision Actions */}
              <div className="smarthire-decision-bar">
                <span>Update Hiring Stage:</span>
                <div className="smarthire-decision-btns">
                  <button
                    onClick={() => handleUpdateStatus(selectedCandidate.id, 'SHORTLISTED')}
                    className={`smarthire-action-btn ${selectedCandidate.status === 'SHORTLISTED' ? 'is-active' : ''}`}
                  >
                    ⭐ Shortlist
                  </button>
                  <button
                    onClick={() => handleUpdateStatus(selectedCandidate.id, 'INTERVIEW_SCHEDULED')}
                    className={`smarthire-action-btn ${selectedCandidate.status === 'INTERVIEW_SCHEDULED' ? 'is-active' : ''}`}
                  >
                    📅 Schedule Interview
                  </button>
                  <button
                    onClick={() => handleUpdateStatus(selectedCandidate.id, 'SELECTED')}
                    className={`smarthire-action-btn btn-success ${selectedCandidate.status === 'SELECTED' ? 'is-active' : ''}`}
                  >
                    🎉 Select / Offer
                  </button>
                  <button
                    onClick={() => handleUpdateStatus(selectedCandidate.id, 'REJECTED')}
                    className={`smarthire-action-btn btn-danger ${selectedCandidate.status === 'REJECTED' ? 'is-active' : ''}`}
                  >
                    ✕ Reject
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
