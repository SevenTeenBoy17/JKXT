// App — auth gate + state-based router + super admin mode + OverlayHost + Tweaks

const { useState, useEffect, useRef } = React;

// ── Page Transition (GSAP-managed, single live layer) ───────────────
function PageTransition({ pageKey, children }) {
  const stageRef = useRef(null);
  useEffect(() => {
    let cleanup;
    const frame = requestAnimationFrame(() => {
      cleanup = window.JKXTMotion?.enterPage?.(stageRef.current, pageKey);
    });
    return () => { cancelAnimationFrame(frame); cleanup?.(); };
  }, [pageKey]);

  return (
    <div ref={stageRef} className="page-transition-stage" data-page-key={pageKey}>
      <div key={pageKey} className="page-layer page-layer-current">
        {children}
      </div>
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
  "panelStyle": "airy",
  "demoValidation": "warn",
  "demoVolume": "48",
  "demoAiCount": 3,
  "demoSpeed": "1x",
  "demoUploadResult": "clean"
}/*EDITMODE-END*/;

const BRAND_PALETTES = [
  ['#4F46E5', '#4338CA', '#EEF2FF'],  // 默认紫 (Indigo)
  ['#2563EB', '#1D4ED8', '#DBEAFE'],  // 静谧蓝 (Blue)
  ['#059669', '#047857', '#D1FAE5'],  // 教育绿 (Emerald)
  ['#DB2777', '#BE185D', '#FCE7F3'],  // 沉稳玫 (Rose)
  ['#0891B2', '#0E7490', '#CFFAFE'],  // 青学者 (Cyan)
  ['#7C3AED', '#6D28D9', '#F3E8FF'],  // 深邃紫 (Violet)
];

const PAGE_MAP = window.JKXTNavigation?.pageMap || {};

window.navigateTo = (pageId) => {
  try { localStorage.setItem('jkxt:page', pageId); } catch {}
  window.dispatchEvent(new CustomEvent('jkxt:nav', { detail: { pageId } }));
};

function App() {
  const [t, setTweak] = useTweaks(TWEAK_DEFAULTS);

  const [loggedIn, setLoggedIn] = useState(() => {
    try { return localStorage.getItem('jkxt:auth') === '1'; } catch { return false; }
  });
  const [authChecked, setAuthChecked] = useState(false);
  const [me, setMe] = useState(null);
  const [authError, setAuthError] = useState(null);
  const [sessionUnavailable, setSessionUnavailable] = useState(null);
  const [sessionAttempt, setSessionAttempt] = useState(0);
  const sessionCheckRef = useRef({ busy: false, sequence: 0 });

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

  const [onboarding, setOnboarding] = useState(null);
  const [navOpen, setNavOpen] = useState(false);
  const [isMobileNav, setIsMobileNav] = useState(() => Boolean(window.matchMedia?.('(max-width: 1023px)').matches));
  const menuButtonRef = useRef(null);
  const sidebarRef = useRef(null);
  const mainRef = useRef(null);
  const navReturnFocusRef = useRef(null);

  useEffect(() => {
    const pageTitle = PAGE_MAP[active]?.title || '仪表盘';
    document.title = loggedIn ? `${pageTitle} · Quick 排监考` : '登录 · Quick 排监考';
  }, [active, loggedIn]);

  const closeMobileNav = (restoreFocus = false) => {
    setNavOpen(false);
    if (restoreFocus) requestAnimationFrame(() => (navReturnFocusRef.current || menuButtonRef.current)?.focus?.());
  };

  const openMobileNav = () => {
    navReturnFocusRef.current = document.activeElement;
    setNavOpen(true);
  };

  const handleNav = (pageId) => {
    setActive(pageId);
    // Navigation from the mobile drawer returns focus to the trigger after the
    // route changes; desktop navigation keeps the existing focus behavior.
    closeMobileNav(isMobileNav && navOpen);
  };

  const applySession = (profile, opts = {}) => {
    const role = String(profile?.user?.role || profile?.role || '').toUpperCase();
    const nextMode = role === 'SUPER_ADMIN' ? 'super' : 'admin';
    const fallbackPage = nextMode === 'super' ? 'super-dashboard' : 'dashboard';
    let nextPage = fallbackPage;

    if (opts.preservePage) {
      try {
        const stored = localStorage.getItem('jkxt:page');
        const info = PAGE_MAP[stored];
        if (info && info.mode === nextMode) nextPage = stored;
      } catch {}
    }

    window.__jkxtMe = profile;
    window.JKXTTheme?.setIdentity?.(profile);
    setMe(profile);
    setAuthError(null);
    setMode(nextMode);
    setActive(nextPage);
    setLoggedIn(true);
    try {
      localStorage.setItem('jkxt:auth', '1');
      localStorage.setItem('jkxt:mode', nextMode);
      localStorage.setItem('jkxt:page', nextPage);
      localStorage.setItem('jkxt:onboarded', profile?.onboarded ? '1' : '0');
    } catch {}
    window.dispatchEvent(new CustomEvent('jkxt:me-changed', { detail: profile }));
  };

  const handleLogin = (profile) => {
    applySession(profile, { preservePage: false });
  };

  const retrySession = () => {
    // Protect against a second click before React paints the loading state.
    if (sessionCheckRef.current.busy) return;
    sessionCheckRef.current.busy = true;
    setSessionUnavailable(null);
    setAuthChecked(false);
    setSessionAttempt(attempt => attempt + 1);
  };

  useEffect(() => {
    let alive = true;
    const controller = new AbortController();
    const sequence = ++sessionCheckRef.current.sequence;
    sessionCheckRef.current.busy = true;
    let timer;
    const check = async () => {
      try {
        if (!window.api?.get) {
          throw Object.assign(new Error('接口尚未就绪，请刷新页面后重新连接。'), { code: 'SESSION_API_UNAVAILABLE' });
        }
        // Only this read-only bootstrap has a 15s deadline. Do not impose it on
        // writes, exports or SSE. The race also bounds a transport ignoring abort.
        const deadline = new Promise((_, reject) => {
          timer = window.setTimeout(() => {
            reject(Object.assign(new Error('会话连接超时。'), { code: 'SESSION_TIMEOUT' }));
            controller.abort();
          }, 15000);
        });
        const profile = await Promise.race([
          window.api.get('/auth/me', undefined, { signal: controller.signal }), deadline,
        ]);
        if (!alive) return;
        // Match the backend UserView and persisted role contract, not merely a
        // truthy proxy JSON object. Authorization remains enforced server-side.
        if (typeof profile?.user?.id !== 'string' || !profile.user.id.trim()
            || !['SUPER_ADMIN', 'ADMIN', 'VIEWER', 'TEACHER_QUERY'].includes(profile.user.role)) {
          throw Object.assign(new Error('会话响应不完整，无法确认登录状态。请重新连接或联系管理员。'), { code: 'SESSION_INVALID_RESPONSE' });
        }
        applySession(profile, { preservePage: true });
        setSessionUnavailable(null);
        setAuthChecked(true);
      } catch (error) {
        if (!alive) return;
        if (error?.status === 401) {
          window.__jkxtMe = null;
          window.JKXTTheme?.clearIdentity?.();
          setMe(null);
          setLoggedIn(false);
          setAuthError(null);
          setSessionUnavailable(null);
          try { localStorage.removeItem('jkxt:auth'); } catch {}
          setAuthChecked(true);
        } else {
          // A gateway outage is not evidence of an expired session. Keep the
          // identity gate closed and preserve local hints until /auth/me answers.
          const message = error?.status === 404
            ? '会话接口不存在，请联系管理员核对服务部署与接口地址。'
            : ['SESSION_API_UNAVAILABLE', 'SESSION_INVALID_RESPONSE'].includes(error?.code)
              ? error.message
              : '服务可能正在启动或暂时不可用。请稍后重新连接；这不代表密码错误或登录已过期。';
          setSessionUnavailable(message);
        }
      } finally {
        window.clearTimeout(timer);
        if (sessionCheckRef.current.sequence === sequence) sessionCheckRef.current.busy = false;
      }
    };
    check();
    return () => {
      alive = false;
      window.clearTimeout(timer);
      controller.abort();
      if (sessionCheckRef.current.sequence === sequence) sessionCheckRef.current.busy = false;
    };
  }, [sessionAttempt]);

  // Refresh cached appearance from the authenticated source of truth. A failed
  // request never fabricates preferences; the settings page exposes the error.
  useEffect(() => {
    if (!loggedIn || !me || me.user?.mustChangePassword || !window.api?.preferences || !window.api?.appearance) return undefined;
    let live = true;
    Promise.allSettled([window.api.preferences.get(), window.api.appearance.get()]).then(([pref, school]) => {
      if (!live) return;
      if (school.status === 'fulfilled') {
        const payload = school.value;
        // Preserve the complete response and read the backend's top-level
        // `defaultPalette` contract; JKXTTheme also accepts nested legacy shapes.
        window.JKXTTheme?.cacheSchoolAppearance?.(payload);
      }
      if (pref.status === 'fulfilled') {
        const payload = pref.value;
        window.JKXTTheme?.cachePreferences?.(payload?.theme || payload?.preferences?.theme || payload);
      }
    });
    return () => { live = false; };
  }, [loggedIn, me]);

  useEffect(() => {
    const query = window.matchMedia?.('(max-width: 1023px)');
    if (!query) return undefined;
    const sync = () => {
      setIsMobileNav(query.matches);
      if (!query.matches) setNavOpen(false);
    };
    sync();
    query.addEventListener?.('change', sync);
    return () => query.removeEventListener?.('change', sync);
  }, []);

  useEffect(() => {
    if (!isMobileNav || !navOpen) return undefined;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    const onKeyDown = (event) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        closeMobileNav(true);
        return;
      }
      if (event.key === 'Tab') {
        const focusable = Array.from(sidebarRef.current?.querySelectorAll(
          'button:not([disabled]), a[href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
        ) || []).filter((node) => !node.hasAttribute('inert') && node.getClientRects().length > 0);
        if (!focusable.length) {
          event.preventDefault();
          return;
        }
        const first = focusable[0];
        const last = focusable[focusable.length - 1];
        if (event.shiftKey && (document.activeElement === first || !sidebarRef.current?.contains(document.activeElement))) {
          event.preventDefault();
          last.focus();
        } else if (!event.shiftKey && (document.activeElement === last || !sidebarRef.current?.contains(document.activeElement))) {
          event.preventDefault();
          first.focus();
        }
      }
    };
    document.addEventListener('keydown', onKeyDown);
    requestAnimationFrame(() => sidebarRef.current?.querySelector('button:not([disabled])')?.focus?.());
    return () => {
      document.body.style.overflow = previousOverflow;
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [isMobileNav, navOpen]);

  // The edit-mode panel is an isolated preview tool. Persisted user/school
  // appearance is owned exclusively by JKXTTheme and never overwritten here.
  useEffect(() => {
    window.JKXTTheme?.apply?.();
  }, []);

  // Mirror demo tweaks to a global so per-page mounts can read initial values
  useEffect(() => {
    window.__jkxtDemo = {
      validation:    t.demoValidation,
      volume:        t.demoVolume,
      aiCount:       t.demoAiCount,
      speed:         t.demoSpeed,
      uploadResult:  t.demoUploadResult,
    };
  }, [t.demoValidation, t.demoVolume, t.demoAiCount, t.demoSpeed, t.demoUploadResult]);

  // 迭代9 T3-P0-2：全局会话失效处理。api.js 在任何 401 时 dispatch 'jkxt:api-unauthorized'，
  // 但此前全仓无人监听 → 会话过期/后端重启后用户停留旧页(叠加部分页 mock 兜底会看到假数据)。
  // 仅当本地处于已登录态才触发(避免首屏未登录的 /auth/me 401 误杀)，触发即清会话回登录页。
  useEffect(() => {
    const onUnauthorized = () => {
      let wasLoggedIn = false;
      try { wasLoggedIn = localStorage.getItem('jkxt:auth') === '1'; } catch {}
      if (!wasLoggedIn) return;
      window.__jkxtMe = null;
      window.JKXTTheme?.clearIdentity?.();
      setMe(null);
      setLoggedIn(false);
      setAuthError(null);
      try { localStorage.removeItem('jkxt:auth'); } catch {}
      try { window.ui?.toast?.('登录已过期，请重新登录', { kind: 'error' }); } catch {}
    };
    window.addEventListener('jkxt:api-unauthorized', onUnauthorized);
    return () => window.removeEventListener('jkxt:api-unauthorized', onUnauthorized);
  }, []);

  // Listen for global navigation
  useEffect(() => {
    const onNav = (e) => handleNav(e.detail.pageId);
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
    document.getElementById('main-content')?.scrollTo({ top: 0, left: 0, behavior: 'auto' });
  }, [active, mode]);

  // 首次导览按账号、单位与版本隔离；等待真实会话完成后再触发，并清理延时器。
  useEffect(() => {
    if (!authChecked || !loggedIn || !me || me.user?.mustChangePassword) return undefined;
    window.JKXTOnboarding?.syncPending?.(me, mode);
    if (!window.JKXTOnboarding?.shouldAutoOpen?.(me, mode)) return undefined;
    const timer = setTimeout(() => setOnboarding({ source: 'auto' }), 650);
    return () => clearTimeout(timer);
  }, [authChecked, loggedIn, me, mode]);

  useEffect(() => {
    if (!loggedIn) setOnboarding(null);
  }, [loggedIn]);

  useEffect(() => {
    const open = (options = {}) => setOnboarding({ source: options.source || 'help' });
    window.__openOnboarding = open;
    return () => {
      if (window.__openOnboarding === open) delete window.__openOnboarding;
    };
  }, []);

  const handleLogout = async () => {
    const ok = await window.ui.confirm({
      title: '确认退出登录?',
      desc: '退出后将返回登录页。请先保存尚未提交的内容，未保存的修改可能丢失。',
      confirmText: '确认退出',
      cancelText: '取消',
      danger: true,
      icon: IconLogout,
    });
    if (!ok) return;
    try {
      if (!window.api?.post) throw new Error('退出接口尚未就绪');
      const result = await window.api.post('/auth/logout', {});
      if (result?.ok !== true) throw new Error('服务器未确认退出');
    } catch (error) {
      window.ui.toast('退出未完成，请重试', { kind: 'error', desc: '服务器尚未确认会话已失效，请勿将当前设备交给他人。' });
      return;
    }
    setLoggedIn(false);
    window.__jkxtMe = null;
    window.JKXTTheme?.clearIdentity?.();
    setMe(null);
    setAuthError(null);
    try { localStorage.removeItem('jkxt:auth'); } catch {}
    setOnboarding(null);
    window.ui.toast('已安全退出', { kind: 'success', desc: '正在返回登录页…' });
  };
  window.__handleLogout = handleLogout;

  if (!authChecked) {
    return (
      <>
        <main className="min-h-screen flex items-center justify-center p-6" style={{ background: 'var(--bg-base)', color: 'var(--text-muted)' }}>
          {sessionUnavailable ? (
            <section className="w-full max-w-md space-y-4 rounded-xl border p-7" aria-labelledby="session-unavailable-title"
              style={{ background: 'var(--surface-card, white)', borderColor: 'var(--border-subtle, #e2e8f0)' }}>
              <h1 id="session-unavailable-title" className="text-lg font-semibold" style={{ color: 'var(--text-strong)' }}>暂时无法连接服务</h1>
              <p role="alert" className="text-sm leading-6">{sessionUnavailable}</p>
              <button type="button" onClick={retrySession} disabled={sessionCheckRef.current.busy}
                className="w-full rounded-lg bg-indigo-700 px-4 py-3 text-sm font-medium text-white focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-700">
                重新连接
              </button>
              <p className="text-xs leading-5">尚未确认当前登录状态，业务页面不会提前打开。</p>
            </section>
          ) : (
            <div role="status" aria-live="polite" className="text-center space-y-3">
              <div className="inline-flex items-center gap-3 text-[13px] font-medium">
                <span aria-hidden="true" className="w-4 h-4 rounded-full border-2 border-current border-t-transparent animate-spin" />
                正在加载会话…
              </div>
              <p className="text-xs">正在连接服务，最长等待 15 秒；超时后可手动重试。</p>
            </div>
          )}
        </main>
        <OverlayHost />
      </>
    );
  }

  // Login screen
  if (!loggedIn) {
    return (
      <>
        <LoginPage onSuccess={handleLogin} sessionError={authError} />
        <OverlayHost />
      </>
    );
  }

  if (me?.user?.mustChangePassword) {
    return <><RequiredPasswordChange onChanged={handleLogin} onLogout={handleLogout} /><OverlayHost /></>;
  }

  const PageInfo = PAGE_MAP[active] || PAGE_MAP.dashboard;
  window.__jkxtActivePage = active;
  const Page = (PageInfo.Comp?.() || PAGE_MAP.dashboard.Comp()) || (() => <div className="p-6 text-slate-400">页面加载中...</div>);
  const sessionUsername = me?.user?.username || me?.user?.displayName || '当前账号';

  // Super mode stays visibly distinct through semantics and copy, not an "AI purple" wash.
  const superModeBg = mode === 'super' ? 'var(--surface-canvas)' : 'var(--bg-base)';

  return (
    <div className={`app-shell min-h-screen ${navOpen ? 'nav-open' : ''}`} data-screen-label={PageInfo.title} data-page-id={active}
      style={{ background: superModeBg }}>
      {mode === 'super' && (
        <div className="app-super-mode-banner relative h-9 flex items-center justify-center text-[11.5px] font-medium overflow-hidden whitespace-nowrap"
          style={{
            background: '#17233A',
            color: 'var(--fg-on-inverse)',
            boxShadow: 'inset 0 -1px 0 rgba(0,0,0,.2)',
          }}>
          <div className="relative inline-flex items-center gap-2.5">
            <ProductGlyph name="security" size={17} active />
            <span className="font-mono text-[10.5px] tracking-[.18em] uppercase opacity-85">超级管理员模式</span>
            <span className="hidden md:inline opacity-40">·</span>
            <span className="hidden md:inline text-[11.5px]">您正处于超级管理员视图,跨租户操作请谨慎 · 所有操作将记录在审计日志</span>
            <span className="hidden lg:inline opacity-40 ml-3">·</span>
            <span className="hidden lg:inline font-mono text-[10.5px] opacity-85">{sessionUsername}</span>
          </div>
        </div>
      )}
      <Topbar onNav={handleNav} active={active} mode={mode} onMenu={openMobileNav} menuButtonRef={menuButtonRef} menuOpen={navOpen} />
      <button
        type="button"
        className="mobile-nav-backdrop"
        aria-label="关闭导航"
        aria-hidden={!navOpen}
        tabIndex={navOpen ? 0 : -1}
        onClick={() => closeMobileNav(true)}
      />
      <div className="app-layout flex">
        <Sidebar active={active} onChange={handleNav} onLogout={handleLogout} mode={mode}
          asideRef={sidebarRef} mobileOpen={navOpen} isMobile={isMobileNav} />
        <main ref={mainRef} className="app-main flex-1 min-w-0 relative" id="main-content" tabIndex={-1}
          inert={isMobileNav && navOpen ? '' : undefined}
          aria-hidden={isMobileNav && navOpen ? true : undefined}>
          <PageTransition pageKey={active}>
            <div className="app-page-pad space-y-7">
              <Page routeId={active} />
              {t.showWatermark && (
                <div className="pb-2 text-center text-[11px] font-mono" style={{ color: 'var(--text-soft)' }}>
                  JKXT v2.0 · 2026 © {orgName()} 教务处 · K-12 排监考系统
                </div>
              )}
            </div>
          </PageTransition>
        </main>
      </div>
      <OverlayHost />
      {onboarding && (
        <OnboardingModal
          mode={mode}
          me={me}
          activePage={active}
          source={onboarding.source}
          onClose={() => setOnboarding(null)}
          onFinish={() => setOnboarding(null)}
        />
      )}

      <TweaksPanel title="编辑器预览（不保存）">
        <div className="rounded-lg p-3" style={{
          background: (t.brandPalette || BRAND_PALETTES[0])[2],
          border: `1px solid ${(t.brandPalette || BRAND_PALETTES[0])[0]}33`,
          color: (t.brandPalette || BRAND_PALETTES[0])[0],
          fontSize: `${t.fontSize}px`,
        }}>
          <div className="font-semibold">局部主题预览</div>
          <div className="mt-1 opacity-75" style={{ fontSize: '.86em' }}>仅供编辑器校对，不会覆盖账号主题。</div>
        </div>
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
          onClick={() => {
            try {
              localStorage.removeItem('jkxt:onboarded');
              const key = window.JKXTOnboarding?.storageKey?.(me, mode);
              if (key) localStorage.removeItem(key);
            } catch {}
            setOnboarding({ source: 'tweaks' });
          }} />

        <TweakSection label="一键排监考 · 演示开关" />
        <TweakRadio label="校验状态"
          value={t.demoValidation}
          options={[{ value: 'pass', label: '全通过' }, { value: 'warn', label: '有提示' }, { value: 'block', label: '有阻断' }]}
          onChange={(v) => setTweak('demoValidation', v)} />
        <TweakSelect label="上传卡演示状态"
          value={t.demoUploadResult}
          options={[
            { value: 'clean',    label: '全部通过' },
            { value: 'warn-t01', label: '表 01 含警告' },
            { value: 'warn-t02', label: '表 02 含警告' },
            { value: 'err-t03',  label: '表 03 有错误 (阻塞)' },
          ]}
          onChange={(v) => setTweak('demoUploadResult', v)} />
        <TweakRadio label="结果数据量"
          value={t.demoVolume}
          options={[{ value: '48', label: '48 人次' }, { value: '240', label: '240 人次' }]}
          onChange={(v) => setTweak('demoVolume', v)} />
        <TweakRadio label="AI 总结条数"
          value={String(t.demoAiCount)}
          options={[{ value: '0', label: '关闭' }, { value: '3', label: '3 条' }, { value: '5', label: '5 条' }]}
          onChange={(v) => setTweak('demoAiCount', Number(v))} />
        <TweakRadio label="进度耗时"
          value={t.demoSpeed}
          options={[{ value: '1x', label: '标准' }, { value: '2x', label: '快 2×' }, { value: 'instant', label: '跳过' }]}
          onChange={(v) => setTweak('demoSpeed', v)} />
      </TweaksPanel>

      <style>{`
        /* The stage owns layout; GSAP owns motion in glass-motion.js. */
        .page-transition-stage {
          position: relative;
          min-height: calc(100vh - 64px);
        }
        .page-layer {
          width: 100%;
        }
        body[data-density="compact"] { --pad-scale: 0.85; }
        body[data-density="spacious"] { --pad-scale: 1.15; }
      `}</style>
    </div>
  );
}

function RequiredPasswordChange({ onChanged, onLogout }) {
  const [currentPassword, setCurrentPassword] = React.useState('');
  const [newPassword, setNewPassword] = React.useState('');
  const [confirmation, setConfirmation] = React.useState('');
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState('');
  const submit = async (event) => {
    event.preventDefault();
    if (newPassword.trim().length < 8 || newPassword !== confirmation) {
      setError('新密码至少 8 位，且两次输入须一致。');
      return;
    }
    setBusy(true); setError('');
    try {
      const profile = await window.api.post('/auth/me/change-password', { currentPassword, newPassword });
      if (profile?.user?.mustChangePassword !== false) throw new Error('服务器尚未确认密码修改完成');
      setCurrentPassword(''); setNewPassword(''); setConfirmation('');
      onChanged(profile);
    } catch (failure) {
      setError(failure?.message || '修改失败，请重试。');
    } finally { setBusy(false); }
  };
  return <main className="min-h-screen flex items-center justify-center p-6" style={{ background: 'var(--bg-base)', color: 'var(--text-strong)' }}>
    <form onSubmit={submit} className="w-full max-w-md space-y-5 rounded-xl p-7" style={{ background: 'var(--surface-card, white)' }}>
      <h1 className="text-xl font-semibold">请先修改初始密码</h1>
      <p className="text-sm">完成修改后即可进入系统。请使用仅由您本人掌握的新密码。</p>
      {[
        ['initial-password', '当前或临时密码', currentPassword, setCurrentPassword, 'current-password'],
        ['new-password', '新密码（至少 8 位）', newPassword, setNewPassword, 'new-password'],
        ['confirm-password', '再次输入新密码', confirmation, setConfirmation, 'new-password'],
      ].map(([id, label, value, setter, autoComplete]) => <label key={id} htmlFor={id} className="block text-sm">
        {label}<input id={id} type="password" required autoComplete={autoComplete} value={value}
          disabled={busy} onChange={event => setter(event.target.value)} className="mt-2 w-full rounded border p-3" />
      </label>)}
      {error && <p role="alert" className="text-sm text-red-700">{error}</p>}
      <button type="submit" disabled={busy} className="w-full rounded bg-indigo-700 p-3 text-white">{busy ? '正在保存…' : '修改密码并继续'}</button>
      <button type="button" disabled={busy} onClick={onLogout} className="w-full p-2">退出登录</button>
    </form>
  </main>;
}

const root = ReactDOM.createRoot(document.getElementById('root'));
root.render(<App />);
