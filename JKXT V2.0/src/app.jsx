// App — auth gate + state-based router + super admin mode + OverlayHost + Tweaks

const { useState, useEffect, useRef } = React;

// ── Page Transition (PPT-style slide + fade overlap) ────────────────
function PageTransition({ pageKey, children }) {
  const idRef = useRef(1);
  const [layers, setLayers] = useState(() => [{ id: 1 }]);
  const contentMap = useRef({});
  const prevKey = useRef(pageKey);
  const cleanupRef = useRef(null);

  // Always keep the freshest children mapped to the current (entering) layer.
  // Skip when pageKey just changed but the new layer hasn't been added yet —
  // that way the OLD layer's frozen content isn't overwritten.
  const lastLayer = layers[layers.length - 1];
  if (pageKey === prevKey.current) {
    contentMap.current[lastLayer.id] = children;
  }

  useEffect(() => {
    if (pageKey === prevKey.current) return;
    prevKey.current = pageKey;
    idRef.current += 1;
    const newId = idRef.current;
    contentMap.current[newId] = children;
    setLayers((prev) => [
      ...prev.map((l) => ({ ...l, exiting: true })),
      { id: newId },
    ]);

    if (cleanupRef.current) clearTimeout(cleanupRef.current);
    cleanupRef.current = setTimeout(() => {
      setLayers((prev) => {
        const remaining = prev.filter((l) => !l.exiting);
        const remainingIds = new Set(remaining.map((l) => l.id));
        Object.keys(contentMap.current).forEach((k) => {
          if (!remainingIds.has(Number(k))) delete contentMap.current[k];
        });
        return remaining;
      });
      cleanupRef.current = null;
    }, 440);
  }, [pageKey, children]);

  return (
    <div className="page-transition-stage">
      {layers.map((l) => (
        <div
          key={l.id}
          className={l.exiting ? 'page-layer page-layer-exit' : 'page-layer page-layer-enter'}
          style={{ pointerEvents: l.exiting ? 'none' : 'auto' }}
        >
          {contentMap.current[l.id]}
        </div>
      ))}
    </div>
  );
}

// Tweak defaults — edit-mode block (editable via host)
const TWEAK_DEFAULTS = /*EDITMODE-BEGIN*/{
  "brandPalette": ["#4F46E5", "#4338CA", "#EEF2FF"],
  "density": "comfy",
  "fontSize": 14,
  "showWatermark": true,
  "showSemester": true,
  "panelStyle": "airy"
}/*EDITMODE-END*/;

const BRAND_PALETTES = [
  ['#4F46E5', '#4338CA', '#EEF2FF'],  // 默认紫 (Indigo)
  ['#2563EB', '#1D4ED8', '#DBEAFE'],  // 静谧蓝 (Blue)
  ['#059669', '#047857', '#D1FAE5'],  // 教育绿 (Emerald)
  ['#DB2777', '#BE185D', '#FCE7F3'],  // 沉稳玫 (Rose)
  ['#0891B2', '#0E7490', '#CFFAFE'],  // 青学者 (Cyan)
  ['#7C3AED', '#6D28D9', '#F3E8FF'],  // 深邃紫 (Violet)
];

const PAGE_MAP = {
  // Admin pages
  dashboard:        { Comp: () => window.DashboardPage,        title: '仪表盘',      mode: 'admin' },
  quick:            { Comp: () => window.QuickSchedulePage,    title: '一键排监考',   mode: 'admin' },
  ai:               { Comp: () => window.AIPolicyPage,         title: 'AI 策略监考',   mode: 'admin' },
  edit:             { Comp: () => window.EditSchedulePage,     title: '修改监考安排', mode: 'admin' },
  tasks:            { Comp: () => window.TasksPage,            title: '任务总览',     mode: 'admin' },
  'stats-supervise':{ Comp: () => window.StatsSupervisePage,   title: '监考次数统计', mode: 'admin' },
  people:           { Comp: () => window.PeoplePage,           title: '监考人员管理', mode: 'admin' },
  classrooms:       { Comp: () => window.ClassroomsPage,       title: '考场管理',     mode: 'admin' },
  classes:          { Comp: () => window.ClassesSubjectsPage,  title: '班级管理',     mode: 'admin' },
  subjects:         { Comp: () => window.ClassesSubjectsPage,  title: '科目管理',     mode: 'admin' },
  history:          { Comp: () => window.HistoryPage,          title: '历史统计',     mode: 'admin' },
  settings:         { Comp: () => window.SettingsPage,         title: '信息设置',     mode: 'admin' },
  'task-detail':    { Comp: () => window.TaskDetailPage,       title: '任务详情',     mode: 'admin' },
  // Super admin pages
  'super-dashboard':       { Comp: () => window.SuperDashboardPage,        title: '超管仪表盘',   mode: 'super' },
  'super-tenants':         { Comp: () => window.SuperTenantsPage,          title: '租户管理',     mode: 'super' },
  'super-accounts':        { Comp: () => window.SuperAccountsPage,         title: '管理员账号',   mode: 'super' },
  'super-system-settings': { Comp: () => window.SuperSystemSettingsPage,   title: '系统设置',     mode: 'super' },
  'super-audit':           { Comp: () => window.SuperAuditPage,            title: '审计日志',     mode: 'super' },
  'super-security':        { Comp: () => window.SuperSecurityPage,         title: '安全中心',     mode: 'super' },
  'super-announcements':   { Comp: () => window.SuperAnnouncementsPage,    title: '公告发布',     mode: 'super' },
};

window.navigateTo = (pageId) => {
  try { localStorage.setItem('jkxt:page', pageId); } catch {}
  window.dispatchEvent(new CustomEvent('jkxt:nav', { detail: { pageId } }));
};

function App() {
  const [t, setTweak] = useTweaks(TWEAK_DEFAULTS);

  const [loggedIn, setLoggedIn] = useState(() => {
    try { return localStorage.getItem('jkxt:auth') === '1'; } catch { return false; }
  });

  const [mode, setMode] = useState(() => {
    try { return localStorage.getItem('jkxt:mode') || 'admin'; } catch { return 'admin'; }
  });

  const [active, setActive] = useState(() => {
    try {
      const p = localStorage.getItem('jkxt:page');
      const info = PAGE_MAP[p];
      if (info && (info.mode === (localStorage.getItem('jkxt:mode') || 'admin'))) return p;
      return (localStorage.getItem('jkxt:mode') === 'super') ? 'super-dashboard' : 'dashboard';
    } catch { return 'dashboard'; }
  });

  const [onboarding, setOnboarding] = useState(false);

  const handleLogin = (creds) => {
    try { localStorage.setItem('jkxt:auth', '1'); } catch {}
    // If logged in as super role, switch mode + page
    if (creds?.role === 'super') {
      setMode('super');
      setActive('super-dashboard');
      try {
        localStorage.setItem('jkxt:mode', 'super');
        localStorage.setItem('jkxt:page', 'super-dashboard');
      } catch {}
    } else {
      setMode('admin');
      setActive('dashboard');
      try {
        localStorage.setItem('jkxt:mode', 'admin');
        localStorage.setItem('jkxt:page', 'dashboard');
      } catch {}
    }
    setLoggedIn(true);
  };

  // Sync CSS variables to tweaks
  useEffect(() => {
    const root = document.documentElement;
    const [brand, hover, soft] = t.brandPalette || BRAND_PALETTES[0];
    root.style.setProperty('--brand',       brand);
    root.style.setProperty('--brand-hover', hover);
    root.style.setProperty('--brand-soft',  soft);
    document.body.style.fontSize = t.fontSize + 'px';
    document.body.dataset.density = t.density;
  }, [t.brandPalette, t.fontSize, t.density]);

  // Listen for global navigation
  useEffect(() => {
    const onNav = (e) => setActive(e.detail.pageId);
    const onMode = (e) => {
      setMode(e.detail.mode);
      if (e.detail.mode === 'super') setActive('super-dashboard');
      else setActive('dashboard');
    };
    window.addEventListener('jkxt:nav', onNav);
    window.addEventListener('jkxt:mode', onMode);
    return () => {
      window.removeEventListener('jkxt:nav', onNav);
      window.removeEventListener('jkxt:mode', onMode);
    };
  }, []);

  useEffect(() => {
    try {
      localStorage.setItem('jkxt:page', active);
      localStorage.setItem('jkxt:mode', mode);
    } catch {}
    window.scrollTo({ top: 0 });
  }, [active, mode]);

  // First-time onboarding trigger
  useEffect(() => {
    if (!loggedIn) return;
    try {
      const done = localStorage.getItem('jkxt:onboarded') === '1';
      if (!done) {
        // small delay so login transition completes
        setTimeout(() => setOnboarding(true), 500);
      }
    } catch {}
  }, [loggedIn]);

  const handleLogout = async () => {
    const ok = await window.ui.confirm({
      title: '确认退出登录?',
      desc: '退出后将返回登录页,任何未保存的草稿会被自动保留 24 小时。',
      confirmText: '确认退出',
      cancelText: '取消',
      danger: true,
      icon: IconLogout,
    });
    if (!ok) return;
    try { localStorage.removeItem('jkxt:auth'); } catch {}
    window.ui.toast('已安全退出', { kind: 'success', desc: '正在返回登录页…' });
    setTimeout(() => setLoggedIn(false), 600);
  };
  window.__handleLogout = handleLogout;

  // Expose onboarding trigger globally for help icon
  window.__openOnboarding = () => setOnboarding(true);

  // Login screen
  if (!loggedIn) {
    return (
      <>
        <LoginPage onSuccess={handleLogin} />
        <OverlayHost />
      </>
    );
  }

  const PageInfo = PAGE_MAP[active] || PAGE_MAP.dashboard;
  const Page = (PageInfo.Comp?.() || PAGE_MAP.dashboard.Comp()) || (() => <div className="p-6 text-slate-400">页面加载中...</div>);

  // Super mode styling — slightly tinted header
  const superModeBg = mode === 'super' ? 'linear-gradient(180deg, #FAF5FF 0%, #F5F7FA 80px)' : 'var(--bg-base)';

  return (
    <div className="min-h-screen" data-screen-label={PageInfo.title}
      style={{ background: superModeBg }}>
      {mode === 'super' && (
        <div className="relative h-9 flex items-center justify-center text-[11.5px] font-medium overflow-hidden whitespace-nowrap"
          style={{
            background: 'linear-gradient(90deg, #1E1B4B 0%, #4F46E5 25%, #7C3AED 60%, #A21CAF 100%)',
            color: '#fff',
            boxShadow: 'inset 0 -1px 0 rgba(0,0,0,.15), 0 1px 3px rgba(79,70,229,.3)',
          }}>
          <div className="absolute inset-0 opacity-30 pointer-events-none"
            style={{ background: 'linear-gradient(90deg, transparent, rgba(255,255,255,.18), transparent)', backgroundSize: '200% 100%', animation: 'shimmer 6s linear infinite' }} />
          <div className="relative inline-flex items-center gap-2.5">
            <span className="w-1.5 h-1.5 rounded-full" style={{ background: '#22D3EE', boxShadow: '0 0 8px #22D3EE' }} />
            <span className="font-mono text-[10.5px] tracking-[.18em] uppercase opacity-85">Super Mode</span>
            <span className="opacity-40">·</span>
            <span className="text-[11.5px]">您正处于超级管理员视图,跨租户操作请谨慎 · 所有操作将记录在审计日志</span>
            <span className="opacity-40 ml-3">·</span>
            <span className="font-mono text-[10.5px] opacity-85">admin@jkxt.cn</span>
          </div>
        </div>
      )}
      <Topbar onNav={setActive} active={active} mode={mode} />
      <div className="flex">
        <Sidebar active={active} onChange={setActive} onLogout={handleLogout} mode={mode} />
        <main className="flex-1 min-w-0 relative">
          <PageTransition pageKey={active}>
            <div className="px-8 py-7 space-y-7">
              <Page />
              {t.showWatermark && (
                <div className="pb-2 text-center text-[11px] font-mono" style={{ color: 'var(--text-soft)' }}>
                  JKXT v2.0 · 2026 © 天津市实验中学 教务处 · K-12 排监考系统
                </div>
              )}
            </div>
          </PageTransition>
        </main>
      </div>
      <OverlayHost />
      {onboarding && (
        <OnboardingModal onClose={() => setOnboarding(false)} onFinish={() => setOnboarding(false)} />
      )}

      <TweaksPanel title="JKXT 主题调节">
        <TweakSection label="主题色调" />
        <TweakColor label="品牌色"
          value={t.brandPalette}
          options={BRAND_PALETTES}
          onChange={(v) => setTweak('brandPalette', v)} />
        <TweakSection label="布局密度" />
        <TweakRadio label="密度"
          value={t.density}
          options={[{ value: 'compact', label: '紧凑' }, { value: 'comfy', label: '标准' }, { value: 'spacious', label: '宽松' }]}
          onChange={(v) => setTweak('density', v)} />
        <TweakSlider label="基础字号"
          value={t.fontSize} min={12} max={18} step={1} unit="px"
          onChange={(v) => setTweak('fontSize', v)} />
        <TweakSection label="界面元素" />
        <TweakToggle label="底部水印"
          value={t.showWatermark}
          onChange={(v) => setTweak('showWatermark', v)} />
        <TweakToggle label="学期 Badge"
          value={t.showSemester}
          onChange={(v) => setTweak('showSemester', v)} />
        <TweakButton label="重置新手引导" secondary
          onClick={() => { try { localStorage.removeItem('jkxt:onboarded'); } catch {} setOnboarding(true); }} />
      </TweaksPanel>

      <style>{`
        /* PPT-style page transition stage */
        .page-transition-stage {
          position: relative;
          min-height: calc(100vh - 64px);
        }
        .page-layer {
          width: 100%;
          will-change: transform, opacity;
        }
        .page-layer-enter {
          position: relative;
          animation: pageEnterRight 380ms cubic-bezier(.22,.61,.36,1) both;
          z-index: 2;
        }
        .page-layer-exit {
          position: absolute;
          inset: 0;
          animation: pageExitLeft 380ms cubic-bezier(.4,0,.2,1) both;
          z-index: 1;
        }
        @keyframes pageEnterRight {
          0%   { opacity: 0; transform: translate3d(36px, 0, 0); }
          100% { opacity: 1; transform: translate3d(0, 0, 0); }
        }
        @keyframes pageExitLeft {
          0%   { opacity: 1; transform: translate3d(0, 0, 0); }
          100% { opacity: 0; transform: translate3d(-24px, 0, 0); }
        }
        @keyframes shimmer {
          0%   { background-position: -200% 0; }
          100% { background-position: 200% 0; }
        }
        body[data-density="compact"] { --pad-scale: 0.85; }
        body[data-density="spacious"] { --pad-scale: 1.15; }
      `}</style>
    </div>
  );
}

const root = ReactDOM.createRoot(document.getElementById('root'));
root.render(<App />);
