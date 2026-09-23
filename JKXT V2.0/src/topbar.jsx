// Topbar — interactive: search ⌘K, semester, notifications drawer, avatar dropdown, tenant switcher

const { useState: useStateTb, useEffect: useEffectTb, useRef: useRefTb } = React;

// Notifications are populated only from the authenticated inbox endpoint.
const NOTIFICATIONS = [];

// ── Super-admin notifications (platform-level) ───────────
// P18 批次7：原来这里是 7 条编造的平台通知（假可疑 IP、假租户申请、假「自动备份完成 4.2GB」——
// 后者正是批次1 定性为伪造的那类内容）。超管铃铛现改读真实公告(/admin/announcements，见下方加载)；
// 安全类平台事件流后端尚无端点，接入前不再编造。
const SUPER_NOTIFICATIONS = [];

const NOTIF_META = {
  conflict: { icon: IconAlert,    bg: '#FEE2E2', fg: '#DC2626' },
  system:   { icon: IconSettings, bg: '#EEF2FF', fg: '#4F46E5' },
  task:     { icon: IconList,     bg: '#ECFDF5', fg: '#059669' },
  security: { icon: IconAlert,    bg: '#FEE2E2', fg: '#DC2626' },
  tenant:   { icon: IconBuilding, bg: '#FAF5FF', fg: '#9333EA' },
  trial:    { icon: IconClock,    bg: '#FFF7ED', fg: '#D97706' },
  update:   { icon: IconBell,     bg: '#E0F2FE', fg: '#0369A1' },
  audit:    { icon: PRODUCT_ICON_COMPONENTS.audit, bg: '#FFF7ED', fg: '#D97706' },
};

// Map a backend notification (GET /api/v1/notifications) to the drawer's display shape.
function relTimeTb(iso) {
  if (!iso) return '';
  const t = new Date(iso).getTime();
  if (!t) return '';
  const m = Math.floor((Date.now() - t) / 60000);
  if (m < 1) return '刚刚';
  if (m < 60) return `${m} 分钟前`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h} 小时前`;
  const d = Math.floor(h / 24);
  if (d < 30) return `${d} 天前`;
  try { return new Date(iso).toLocaleDateString('zh-CN'); } catch { return ''; }
}
function mapNotification(n) {
  const type = String(n.type || '');
  const kind = type === 'schedule_published' ? 'task'
    : type.includes('conflict') ? 'conflict'
    : type.includes('security') ? 'security'
    : 'system';
  const url = String(n.targetUrl || '');
  return {
    id: n.id,
    kind,
    title: n.title || '通知',
    desc: n.body || '',
    at: relTimeTb(n.createdAt || n.publishedAt),
    unread: !n.read,
    page: url.startsWith('/tasks') ? 'tasks' : null,
  };
}

// ── Command palette items ────────────────────────────────
const NAV_PALETTE_ITEMS = (window.JKXTNavigation?.groups?.('admin', {}) || [])
  .flatMap((group) => group.items)
  .flatMap((item) => item.children?.length ? item.children : [item])
  .map((item) => ({
    kind: 'page',
    label: item.label,
    kw: `${item.id} ${item.label}`,
    icon: PRODUCT_ICON_COMPONENTS[item.glyph] || PRODUCT_ICON_COMPONENTS.tasks,
    page: item.id,
  }));

const PALETTE_ITEMS = [
  ...NAV_PALETTE_ITEMS,
  // — Actions —
  { kind: 'action', label: '新建排监考任务',   kw: 'new task 新建',     icon: IconPlus,      run: () => window.navigateTo('quick') },
  { kind: 'action', label: '导入教师数据',     kw: 'import teacher',    icon: IconUpload,    run: () => window.navigateTo('people') },
  // 此前这里直接谎报「已切换至 2025–2026 第二学期」却什么都没做；改为跳转到信息设置的「学年学期」区块真操作。
  { kind: 'action', label: '切换学期',         kw: 'semester switch',   icon: IconCalendar,  run: () => window.navigateTo && window.navigateTo('settings') },
  { kind: 'action', label: '重新查看新手引导', kw: 'onboarding 引导',   icon: IconHelp,      run: () => window.__openOnboarding?.() },
  { kind: 'action', label: '查看报表与导出',   kw: 'export 导出 报表',  icon: IconChart,     run: () => window.navigateTo('history') },
  { kind: 'action', label: '退出登录',         kw: 'logout sign out',   icon: IconLogout,    run: () => window.__handleLogout?.() },
];

const KIND_LABELS = { page: '页面', action: '操作', teacher: '教师', task: '任务' };
const KIND_COLORS = { page: 'indigo', action: 'amber', teacher: 'emerald', task: 'violet' };

// ── Super-admin command palette ──────────────────────────
const SUPER_NAV_PALETTE_ITEMS = (window.JKXTNavigation?.groups?.('super', {}) || [])
  .flatMap((group) => group.items)
  .map((item) => ({
    kind: 'page', label: item.label, kw: `${item.id} ${item.label}`,
    icon: PRODUCT_ICON_COMPONENTS[item.glyph] || PRODUCT_ICON_COMPONENTS.settings,
    page: item.id,
  }));
const SUPER_PALETTE_ITEMS = [
  ...SUPER_NAV_PALETTE_ITEMS,
  // — Actions —
  { kind: 'action', label: '注册新租户',         kw: 'add tenant new 新建 租户', icon: IconPlus,    run: () => window.navigateTo('super-tenants') },   // 真实向导在租户管理页
  { kind: 'action', label: '撰写平台公告',       kw: 'new announcement 公告',    icon: IconEdit,    run: () => window.navigateTo('super-announcements') },
  { kind: 'action', label: '审计日志',           kw: 'export audit 导出 日志',   icon: IconUpload,  run: () => window.navigateTo('super-audit') },   // 原「正在导出…」是假 toast，无导出实现
  { kind: 'action', label: '退出登录',           kw: 'logout sign out',          icon: IconLogout,  run: () => window.__handleLogout?.() },
  // 深搜索租户项改由运行时从真实 /admin/tenants 生成（见 paletteItems），不再写死编造学校。
];

const SUPER_KIND_LABELS = { page: '页面', action: '操作', tenant: '租户' };

function normalizeTopbarTenant(t, index = 0) {
  const id = t?.slug || t?.id || t?.uuid || `tenant-${index}`;
  const abbr = t?.abbr || String(id).slice(0, 3).toUpperCase();
  const rawStudentCount = t?.students;
  const studentCount = rawStudentCount === null || rawStudentCount === undefined || rawStudentCount === ''
    ? null
    : Number(rawStudentCount);
  return {
    id,
    uuid: t?.uuid,
    slug: t?.slug || id,
    name: t?.name || id,
    type: t?.type || '学校',
    plan: t?.plan || '',
    students: Number.isFinite(studentCount) ? studentCount : null,
    abbr,
    active: t?.active !== false && t?.status !== 'suspended',
    color: t?.color || 'var(--brand)',
    status: t?.status || 'active',
  };
}

function useClickOutside(ref, handler, deps = []) {
  useEffectTb(() => {
    const fn = (e) => { if (ref.current && !ref.current.contains(e.target)) handler(e); };
    document.addEventListener('mousedown', fn);
    return () => document.removeEventListener('mousedown', fn);
  }, deps);
}

// ── Notifications Drawer Content ─────────────────────────
function NotificationsList({ onNav, source = NOTIFICATIONS, onActions, onMarkRead, onMarkAllRead }) {
  const [list, setList] = useStateTb(source);
  const unread = list.filter((n) => n.unread).length;
  return (
    <div>
      <div className="flex items-center justify-between mb-4">
        <div className="text-[12.5px]" style={{ color: 'var(--text-medium)' }}>
          有 <span className="num font-semibold" style={{ color: 'var(--text-strong)' }}>{unread}</span> 条未读
        </div>
        <button
          type="button"
          className="notification-action min-h-11 px-2 text-[12px] font-medium hover:underline"
          style={{ color: 'var(--brand)' }}
          onClick={async () => {
            if (onMarkAllRead) {
              const saved = await onMarkAllRead();
              if (!saved) return;
            } else {
              window.ui.toast('已标记全部为已读');
            }
            setList(list.map((n) => ({ ...n, unread: false })));
          }}
        >
          全部标为已读
        </button>
      </div>
      {list.length === 0 && (
        <div className="rounded-xl px-4 py-8 text-center text-[12.5px]" style={{ border: '1px dashed var(--border-strong)', color: 'var(--text-soft)' }}>
          暂无通知 · 发布排班后将在此收到提醒
        </div>
      )}
      <ul className="space-y-2">
        {list.map((n) => {
          const m = NOTIF_META[n.kind] || NOTIF_META.system;
          return (
            <li key={n.id}>
              <button
                type="button"
                className="notification-action semantic-hover min-h-11 w-full rounded-xl p-3 flex items-start gap-3 text-left transition-colors"
                style={{
                  background: n.unread ? 'var(--surface-muted)' : 'var(--surface-raised)',
                  border: '1px solid var(--border-soft)',
                }}
                onClick={async () => {
                if (onMarkRead && n.unread) {
                  const saved = await onMarkRead(n.id);
                  if (!saved) return;
                }
                setList(list.map((x) => (x.id === n.id ? { ...x, unread: false } : x)));
                if (n.page) onNav(n.page);
                window.ui.closeDrawer();
                }}
              >
              <span
                className="w-8 h-8 rounded-lg flex items-center justify-center shrink-0"
                style={{ background: m.bg, color: m.fg }}
              >
                <m.icon size={14} strokeWidth={2} />
              </span>
              <div className="flex-1 min-w-0">
                <div className="text-[13px] font-semibold leading-tight" style={{ color: 'var(--text-strong)' }}>
                  {n.title}
                </div>
                <div className="text-[11.5px] mt-1 leading-relaxed" style={{ color: 'var(--text-medium)' }}>
                  {n.desc}
                </div>
                <div className="text-[10.5px] mt-1.5 font-mono" style={{ color: 'var(--text-soft)' }}>
                  {n.at}
                </div>
              </div>
              {n.unread && (
                <span className="w-2 h-2 rounded-full shrink-0 mt-1" style={{ background: 'var(--brand)' }} />
              )}
              </button>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

function NotificationsPanel({ load, onNav, onMarkRead, onMarkAllRead }) {
  const [state, setState] = useStateTb({ status: 'loading', data: [], message: '' });
  const aliveRef = useRefTb(true);

  const run = async () => {
    setState({ status: 'loading', data: [], message: '' });
    try {
      const rows = await load();
      if (!aliveRef.current) return;
      const data = Array.isArray(rows) ? rows : [];
      setState({ status: data.length ? 'ready' : 'empty', data, message: '' });
    } catch (error) {
      if (!aliveRef.current) return;
      const permission = error?.status === 403;
      setState({
        status: permission ? 'permission' : 'error',
        data: [],
        message: permission ? '当前账号无权读取通知。' : (error?.message || '通知加载失败，请稍后重试。'),
      });
    }
  };

  useEffectTb(() => {
    aliveRef.current = true;
    run();
    return () => { aliveRef.current = false; };
  }, []);

  if (state.status === 'loading') {
    return <div role="status" aria-live="polite" data-testid="notifications-loading" className="rounded-xl px-4 py-8 text-center text-[12.5px]" style={{ background: 'var(--surface-muted)', color: 'var(--text-medium)' }}>正在加载通知…</div>;
  }
  if (state.status === 'permission') {
    return (
      <div role="alert" data-testid="notifications-permission" className="rounded-xl px-4 py-6 text-center text-[12.5px]" style={{ background: 'var(--warning-soft)', color: 'var(--text-medium)' }}>
        <div>{state.message}</div>
        <button type="button" className="notification-action mt-3 min-h-11 rounded-lg px-4 font-semibold" style={{ background: 'var(--surface-raised)', color: 'var(--brand)', border: '1px solid var(--border-soft)' }} onClick={run}>重新检查权限</button>
      </div>
    );
  }
  if (state.status === 'error') {
    return (
      <div role="alert" data-testid="notifications-error" className="rounded-xl px-4 py-6 text-center text-[12.5px]" style={{ background: 'var(--danger-soft)', color: 'var(--text-medium)' }}>
        <div>{state.message}</div>
        <button type="button" className="notification-action mt-3 min-h-11 rounded-lg px-4 font-semibold" style={{ background: 'var(--surface-raised)', color: 'var(--brand)', border: '1px solid var(--border-soft)' }} onClick={run}>重新加载</button>
      </div>
    );
  }
  if (state.status === 'empty') {
    return <div role="status" data-testid="notifications-empty" className="rounded-xl px-4 py-8 text-center text-[12.5px]" style={{ border: '1px dashed var(--border-strong)', color: 'var(--text-soft)' }}>暂无通知 · 发布排班后将在此收到提醒</div>;
  }
  return <div data-testid="notifications-ready"><NotificationsList onNav={onNav} source={state.data} onMarkRead={onMarkRead} onMarkAllRead={onMarkAllRead} /></div>;
}

// ── Command Palette ──────────────────────────────────────
function CommandPalette({ onClose, onNav, items: paletteItems = PALETTE_ITEMS, mode = 'admin' }) {
  const [q, setQ] = useStateTb('');
  const [idx, setIdx] = useStateTb(0);
  const inputRef = useRefTb(null);
  const dialogRef = useRefTb(null);
  const items = q
    ? paletteItems.filter((it) => (it.label + it.kw).toLowerCase().includes(q.toLowerCase()))
    : paletteItems.slice(0, 8);

  useEffectTb(() => {
    inputRef.current?.focus();
    const onKey = (e) => {
      if (e.key === 'Escape') { e.preventDefault(); onClose(); }
      if (e.key === 'ArrowDown') { e.preventDefault(); setIdx((i) => Math.min(items.length - 1, i + 1)); }
      if (e.key === 'ArrowUp')   { e.preventDefault(); setIdx((i) => Math.max(0, i - 1)); }
      if (e.key === 'Home') { e.preventDefault(); setIdx(0); }
      if (e.key === 'End') { e.preventDefault(); setIdx(Math.max(0, items.length - 1)); }
      if (e.key === 'Tab') {
        const focusable = Array.from(dialogRef.current?.querySelectorAll(
          'input:not([disabled]), button:not([disabled]), [href], [tabindex]:not([tabindex="-1"])'
        ) || []).filter((node) => node.getClientRects().length > 0);
        if (!focusable.length) return;
        const first = focusable[0];
        const last = focusable[focusable.length - 1];
        if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
        else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
      }
      if (e.key === 'Enter') {
        const it = items[idx];
        if (it) {
          e.preventDefault();
          if (it.kind === 'page') onNav(it.page);
          else if (it.run) it.run();
          onClose();
        }
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [items, idx]);

  return (
    <div className="fixed inset-0 z-[120] flex items-start justify-center pt-[12vh]"
      onClick={onClose}>
      <div className="absolute inset-0" style={{ background: 'rgba(15,23,42,.48)', backdropFilter: 'blur(4px)' }} />
      <div ref={dialogRef} role="dialog" aria-modal="true" aria-labelledby="command-palette-title" onClick={(e) => e.stopPropagation()}
        className="relative theme-surface rounded-2xl w-[560px] max-h-[60vh] overflow-hidden flex flex-col"
        style={{ background: 'var(--surface-raised)', boxShadow: '0 32px 80px rgba(15,23,42,.24)' }}>
        <h2 id="command-palette-title" className="sr-only">全局命令面板</h2>
        <div className="px-4 py-3 flex items-center gap-3" style={{ borderBottom: '1px solid var(--border-soft)' }}>
          <IconSearch size={16} className="text-slate-400" />
          <input
            ref={inputRef}
            value={q}
            onChange={(e) => { setQ(e.target.value); setIdx(0); }}
            placeholder="搜索页面、教师、操作..."
            role="combobox"
            aria-autocomplete="list"
            aria-expanded="true"
            aria-controls="command-palette-options"
            aria-activedescendant={items[idx] ? `command-option-${idx}` : undefined}
            className="flex-1 bg-transparent outline-none text-[14px]"
            style={{ color: 'var(--text-strong)' }}
          />
          <span className="kbd">esc</span>
        </div>
        <div id="command-palette-options" role="listbox" aria-label="命令结果" className="flex-1 overflow-y-auto py-2 scrollbar-thin">
          {items.length === 0 && (
            <div className="px-4 py-8 text-center text-[12.5px]" style={{ color: 'var(--text-soft)' }}>
              没有找到匹配项,试试其他关键词
            </div>
          )}
          {items.map((it, i) => (
            <button
              key={i}
              id={`command-option-${i}`}
              type="button"
              role="option"
              aria-selected={i === idx}
              className="min-h-11 w-full px-3 py-2.5 flex items-center gap-3 text-left transition-colors"
              style={{
                background: i === idx ? 'var(--brand-soft)' : 'transparent',
                color: i === idx ? 'var(--brand)' : 'var(--text-strong)',
              }}
              onMouseEnter={() => setIdx(i)}
              onClick={() => {
                if (it.kind === 'page') onNav(it.page);
                else if (it.run) it.run();
                onClose();
              }}
            >
              <span className="w-7 h-7 rounded-md flex items-center justify-center"
                style={{ background: i === idx ? 'var(--surface-raised)' : 'var(--surface-muted)', color: i === idx ? 'var(--brand)' : 'var(--text-medium)' }}>
                <it.icon size={14} strokeWidth={1.75} />
              </span>
              <span className="flex-1 text-[13px] font-medium">{it.label}</span>
              <span className="font-mono text-[10px] uppercase tracking-wider"
                style={{ color: i === idx ? 'var(--brand)' : 'var(--text-soft)' }}>
                {(mode === 'super' ? SUPER_KIND_LABELS : KIND_LABELS)[it.kind] || it.kind}
              </span>
            </button>
          ))}
        </div>
        <div className="px-4 py-2.5 flex items-center gap-3 text-[10.5px] font-mono" style={{ borderTop: '1px solid var(--border-soft)', background: 'var(--surface-muted)', color: 'var(--text-soft)' }}>
          <span><span className="kbd">↑↓</span> 导航</span>
          <span><span className="kbd">↵</span> 进入</span>
          <span><span className="kbd">esc</span> 关闭</span>
          <span className="ml-auto">{mode === 'super' ? 'JKXT 超管 · 跨租户' : 'JKXT 命令面板 · K-12'}</span>
        </div>
      </div>
    </div>
  );
}

// ── Dropdown menu primitive ─────────────────────────────
function Dropdown({ anchor, children, onClose }) {
  const ref = useRefTb(null);
  const closeAndRestore = () => {
    onClose?.();
    requestAnimationFrame(() => anchor?.current?.focus?.());
  };
  useClickOutside(ref, (event) => {
    if (!anchor?.current?.contains?.(event.target)) closeAndRestore();
  }, [anchor]);
  useEffectTb(() => {
    const menu = ref.current;
    const getItems = () => Array.from(menu?.querySelectorAll('button:not([disabled]), [href], [role="menuitem"]') || []);
    getItems().forEach((item) => {
      if (!item.getAttribute('role')) item.setAttribute('role', 'menuitem');
    });
    getItems()[0]?.focus?.();
    const onKeyDown = (event) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        closeAndRestore();
        return;
      }
      const items = getItems();
      if (!items.length) return;
      if (event.key === 'Tab') {
        event.preventDefault();
        const current = Math.max(0, items.indexOf(document.activeElement));
        const next = event.shiftKey ? (current - 1 + items.length) % items.length : (current + 1) % items.length;
        items[next]?.focus?.();
        return;
      }
      if (event.key === 'Home' || event.key === 'End') {
        event.preventDefault();
        items[event.key === 'Home' ? 0 : items.length - 1]?.focus?.();
        return;
      }
      if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return;
      event.preventDefault();
      const current = Math.max(0, items.indexOf(document.activeElement));
      const next = event.key === 'ArrowDown' ? (current + 1) % items.length : (current - 1 + items.length) % items.length;
      items[next]?.focus?.();
    };
    menu?.addEventListener('keydown', onKeyDown);
    return () => menu?.removeEventListener('keydown', onKeyDown);
  }, [anchor]);
  return (
    <div
      ref={ref}
      role="menu"
      aria-label="账户与租户菜单"
      className="absolute right-0 top-full mt-2 theme-surface rounded-xl z-50 overflow-hidden"
      style={{
        minWidth: 240,
        background: 'var(--surface-raised)',
        border: '1px solid var(--border-soft)',
        boxShadow: '0 20px 48px rgba(15,23,42,.16), 0 4px 12px rgba(15,23,42,.06)',
        animation: 'dropIn 160ms cubic-bezier(0,0,.2,1)',
      }}
    >
      {children}
      <style>{`
        @keyframes dropIn { from { opacity: 0; transform: translateY(-6px); } to { opacity: 1; transform: translateY(0); } }
      `}</style>
    </div>
  );
}

// ── Topbar ──────────────────────────────────────────────
function Topbar({ onNav, active, mode = 'admin', onMenu, menuButtonRef, menuOpen = false }) {
  const isSuper = mode === 'super';
  const sessionUsername = window.__jkxtMe?.user?.username
    || window.__jkxtMe?.user?.displayName
    || '当前账号';
  const userProfile = window.__jkxtMe?.user || {};
  const profileName = userProfile.displayName || userProfile.username || '未提供姓名';
  const profileInitial = profileName === '未提供姓名' ? '—' : Array.from(profileName)[0];
  const profileEmail = userProfile.email || '未提供邮箱';
  const role = String(userProfile.role || '').toUpperCase();
  const roleLabel = { SUPER_ADMIN: '超级管理员', ADMIN: '管理员', JIAOWU: '教务', VIEWER: '只读', TEACHER: '教师' }[role] || role || '未提供角色';
  const canSwitchTenant = isSuper || role === 'SUPER_ADMIN';
  const ownOrgName = window.__jkxtMe?.org?.name || '未提供单位';
  const ownOrgKey = window.__jkxtMe?.org?.slug || window.__jkxtMe?.org?.id || '';
  const ownOrgAbbr = ownOrgKey ? String(ownOrgKey).slice(0, 3).toUpperCase() : '—';
  const [tenantOpen,  setTenantOpen]  = useStateTb(false);
  const [avatarOpen,  setAvatarOpen]  = useStateTb(false);
  const [paletteOpen, setPaletteOpen] = useStateTb(false);
  const [tenants,     setTenants]     = useStateTb([]);
  const [tenant,      setTenant]      = useStateTb(null);
  const [tenantState, setTenantState] = useStateTb({ status: canSwitchTenant ? 'loading' : 'unavailable', message: '' });
  const [tenantReloadKey, setTenantReloadKey] = useStateTb(0);
  const tenantTriggerRef = useRefTb(null);
  const avatarTriggerRef = useRefTb(null);
  const searchTriggerRef = useRefTb(null);
  const paletteReturnFocusRef = useRefTb(null);
  // 学期徽章的真实数据源：/auth/me 下发的 term（app.jsx 已挂到 window.__jkxtMe）。
  // 在信息设置页切换当前学期后会派发 jkxt:term-changed，这里据此刷新，避免顶栏显示过期学期。
  const [term, setTerm] = useStateTb(() => (window.__jkxtMe && window.__jkxtMe.term) || null);
  useEffectTb(() => {
    const sync = () => setTerm((window.__jkxtMe && window.__jkxtMe.term) || null);
    sync();
    window.addEventListener('jkxt:term-changed', sync);
    window.addEventListener('jkxt:me-changed', sync);
    return () => {
      window.removeEventListener('jkxt:term-changed', sync);
      window.removeEventListener('jkxt:me-changed', sync);
    };
  }, []);

  // ⌘K shortcut
  useEffectTb(() => {
    const onKey = (e) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        paletteReturnFocusRef.current = document.activeElement;
        setPaletteOpen(true);
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, []);

  useEffectTb(() => {
    let cancelled = false;
    async function loadTenants() {
      // 非超管不发这个必然 403 的请求——此前失败被静默 catch，界面就一直留着写死的假租户列表。
      if (!canSwitchTenant) {
        setTenantState({ status: 'unavailable', message: '当前账号不具备跨租户权限。' });
        return;
      }
      if (!window.api?.get) {
        setTenantState({ status: 'unavailable', message: '租户目录接口未接入。' });
        return;
      }
      setTenantState({ status: 'loading', message: '' });
      try {
        const rows = await window.api.get('/admin/tenants');
        const normalized = (Array.isArray(rows) ? rows : []).map(normalizeTopbarTenant);
        if (cancelled) return;
        setTenants(normalized);
        if (normalized.length === 0) {
          setTenant(null);
          setTenantState({ status: 'empty', message: '租户目录为空。' });
          return;
        }
        const saved = localStorage.getItem('jkxt:tenantId');
        setTenant(normalized.find((t) => t.id === saved || t.slug === saved || t.uuid === saved) || normalized[0]);
        setTenantState({ status: 'ready', message: '' });
      } catch (error) {
        if (cancelled) return;
        setTenants([]);
        setTenant(null);
        setTenantState({ status: error?.status === 403 ? 'permission' : 'error', message: error?.status === 403 ? '当前账号无权读取租户目录。' : (error?.message || '租户目录加载失败。') });
      }
    }
    loadTenants();
    return () => { cancelled = true; };
  }, [canSwitchTenant, tenantReloadKey]);

  const selectTenant = (t) => {
    if (!t) return;
    setTenant(t);
    localStorage.setItem('jkxt:tenantId', t.slug || t.id);
    window.dispatchEvent(new CustomEvent('jkxt:tenant-change', { detail: t }));
  };

  // Both admin and super-admin notification surfaces use real endpoints. State
  // remains explicit so a failed/forbidden request can never masquerade as an
  // empty inbox.
  const [notificationState, setNotificationState] = useStateTb({ status: 'loading', data: [], message: '' });
  const refreshNotifs = async () => {
    setNotificationState({ status: 'loading', data: [], message: '' });
    if (!window.api?.get) {
      const error = new Error('通知接口尚未就绪，请刷新页面。');
      setNotificationState({ status: 'error', data: [], message: error.message });
      throw error;
    }
    try {
      if (isSuper) {
        // P18 批次7：超管铃铛改读真实公告（/admin/announcements，批次1 后端已真实落库）。
        // 公告无按人已读追踪，unread 一律 false——不伪造小红点计数。
        const rows = await window.api.get('/admin/announcements');
        const next = (Array.isArray(rows) ? rows : []).map((a) => ({
          id: a.id,
          kind: a.severity === 'critical' ? 'security' : a.severity === 'reminder' ? 'task' : 'update',
          title: a.title || '平台公告',
          desc: a.body || '',
          at: a.publishAt || a.status || '',
          unread: false,
          page: 'super-announcements',
        }));
        setNotificationState({ status: next.length ? 'ready' : 'empty', data: next, message: '' });
        return next;
      }
      const rows = await window.api.get('/notifications');
      const next = Array.isArray(rows) ? rows.map(mapNotification) : [];
      setNotificationState({ status: next.length ? 'ready' : 'empty', data: next, message: '' });
      return next;
    } catch (error) {
      const permission = error?.status === 403;
      setNotificationState({
        status: permission ? 'permission' : 'error',
        data: [],
        message: permission ? '当前账号无权读取通知。' : (error?.message || '通知加载失败，请稍后重试。'),
      });
      throw error;
    }
  };

  const closePalette = () => {
    setPaletteOpen(false);
    requestAnimationFrame(() => (paletteReturnFocusRef.current || searchTriggerRef.current)?.focus?.());
  };
  useEffectTb(() => { refreshNotifs().catch(() => {}); }, [isSuper]);

  const notifSource = notificationState.data;
  // 深搜索的租户项来自真实 /admin/tenants（原 SUPER_PALETTE_ITEMS 里写死 3 所编造学校）
  const paletteItems = isSuper
    ? [...SUPER_PALETTE_ITEMS, ...tenants.map((t) => ({
        kind: 'tenant', label: t.name, kw: `${t.slug || ''} ${t.abbr || ''}`,
        desc: [t.type || '学校', t.plan, t.students !== null ? `${t.students.toLocaleString()} 学生` : null].filter(Boolean).join(' · '),
        icon: PRODUCT_ICON_COMPONENTS.tenant, run: () => window.navigateTo('super-tenants'),
      }))]
    : PALETTE_ITEMS;
  const unreadCount = notifSource.filter((n) => n.unread).length;

  const markRead = async (id) => {
    // 超管铃铛的条目是公告(/admin/announcements)，不存在 /notifications/{id}/read 资源。
    if (isSuper) return false;
    try {
      if (!window.api?.post) throw new Error('通知服务暂不可用');
      await window.api.post(`/notifications/${id}/read`, {});
      setNotificationState((prev) => ({
        ...prev,
        data: (prev.data || []).map((x) => (x.id === id ? { ...x, unread: false } : x)),
      }));
      return true;
    } catch (error) {
      window.ui.toast('标记已读失败', { kind: 'error', desc: error?.message || '请稍后重试' });
      return false;
    }
  };
  const markAllRead = async () => {
    if (isSuper) return false;
    try {
      if (!window.api?.post) throw new Error('通知服务暂不可用');
      await window.api.post('/notifications/read-all', {});
      setNotificationState((prev) => ({
        ...prev,
        data: (prev.data || []).map((x) => ({ ...x, unread: false })),
      }));
      window.ui.toast('已标记全部为已读');
      return true;
    } catch (error) {
      window.ui.toast('全部标记已读失败', { kind: 'error', desc: error?.message || '请稍后重试' });
      return false;
    }
  };

  const openNotifications = () => {
    window.ui.drawer({
      title: isSuper ? '平台通知中心' : '通知中心',
      subtitle: `${isSuper ? '跨租户 · 平台级' : '本校 · 本学期'} · 实时通知`,
      width: 460,
      content: <NotificationsPanel load={refreshNotifs} onNav={(p) => onNav?.(p)}
        onMarkRead={isSuper ? undefined : markRead} onMarkAllRead={isSuper ? undefined : markAllRead} />,
    });
  };

  return (
    <header
      className="app-topbar theme-surface h-16 flex items-center px-6 sticky top-0 z-30"
      style={{
        borderBottom: '1px solid var(--border-soft)',
        background: 'var(--surface-raised)',
      }}
    >
      <button
        type="button"
        ref={menuButtonRef}
        className="mobile-menu-trigger topbar-icon-button w-11 h-11 rounded-lg semantic-hover items-center justify-center transition-colors"
        style={{ color: 'var(--text-medium)' }}
        aria-label="打开导航"
        aria-controls="primary-sidebar"
        aria-expanded={menuOpen}
        onClick={() => onMenu?.()}
      >
        <IconList size={19} strokeWidth={2} />
      </button>

      {/* Logo lockup */}
      <button
        onClick={() => onNav?.(isSuper ? 'super-dashboard' : 'dashboard')}
        className="app-brand topbar-brand flex items-center gap-2.5 w-[208px] shrink-0"
        aria-label="返回仪表盘"
      >
        <BrandLockup version="v2.0" markSrc="assets/brand/jkxt-topbar-logo-user-v1.png" />
      </button>

      {/* Context: semester (admin) or platform indicator (super) */}
      {isSuper ? (
        <div
          className="topbar-context ml-2 inline-flex items-center gap-2 min-h-11 pl-2 pr-3 rounded-lg whitespace-nowrap relative"
          data-tour-id="platform-context"
          style={{
            border: '1px solid var(--border-soft)',
            background: 'var(--surface-muted)',
            boxShadow: 'none',
          }}
          title="您处于跨租户的超级管理员视图"
        >
          <span className="inline-flex items-center justify-center w-6 h-6">
            <ProductGlyph name="tenant" size={22} active />
          </span>
          <span className="text-[12.5px] font-semibold whitespace-nowrap" style={{ color: '#3157D5' }}>
            全平台视图
          </span>
          <span className="font-mono text-[10.5px] px-1.5 py-0.5 rounded"
            style={{ background: 'var(--surface-sunken)', color: 'var(--text-medium)', letterSpacing: '.05em' }}>
            {tenantState.status === 'ready' ? `${tenants.length} 租户` : tenantState.status === 'loading' ? '读取中' : '不可用'}
          </span>
        </div>
      ) : (
        <button
          className="topbar-context topbar-context-button ml-2 inline-flex items-center gap-2 min-h-11 pl-2 pr-3 rounded-lg semantic-hover transition-colors whitespace-nowrap"
          data-tour-id="term-selector"
          style={{ border: '1px solid var(--border-soft)' }}
          onClick={() => onNav?.('settings')}
          title="学年学期设置"
        >
          <span
            className="inline-flex items-center justify-center w-6 h-6 rounded-md"
            style={{ background: 'var(--brand-soft)', color: 'var(--brand)' }}
          >
            <IconCalendar size={14} strokeWidth={2} />
          </span>
          {/* 读 /auth/me 下发的真实学期（此前是写死的「2025–2026 / 第二学期」字面量）；
              点击直达信息设置的「学年学期」区块——那里现在有真实的切换能力，不再是死循环提示。 */}
          <span className="text-[13px] font-medium whitespace-nowrap" style={{ color: 'var(--text-strong)' }}>
            {term ? term.yearLabel : '未设置学期'}
          </span>
          {term && (
            <span
              className="px-1.5 py-0.5 rounded text-[11px] font-medium whitespace-nowrap"
              style={{ background: 'var(--surface-muted)', color: 'var(--text-medium)' }}
            >
              {term.term === 'S1' ? '第一学期' : '第二学期'}
            </span>
          )}
          <IconChevron size={14} className="text-slate-400" />
        </button>
      )}

      {/* Search */}
      <div className="topbar-search flex-1 flex justify-center px-8">
        <button
          ref={searchTriggerRef}
          onClick={() => { paletteReturnFocusRef.current = document.activeElement; setPaletteOpen(true); }}
          className="topbar-search-trigger semantic-hover group flex items-center w-full max-w-[460px] min-h-11 px-3.5 rounded-xl transition-all"
          data-tour-id="global-search"
          style={{ border: '1px solid transparent', background: 'var(--surface-muted)' }}
          aria-label="打开全局搜索"
        >
          <IconSearch size={16} className="text-slate-400" strokeWidth={1.75} />
          <span className="ml-2.5 text-[13px] flex-1 text-left" style={{ color: 'var(--text-soft)' }}>
            {isSuper ? '搜索租户、账号、审计、操作…' : '搜索页面、教师、操作…'}
          </span>
          <span className="kbd">⌘</span>
          <span className="kbd ml-1">K</span>
        </button>
      </div>

      {/* Right cluster */}
      <div className="topbar-actions flex items-center gap-1.5 shrink-0">
        <button
          className="topbar-icon-button topbar-desktop-only w-11 h-11 rounded-lg semantic-hover flex items-center justify-center transition-colors"
          style={{ color: 'var(--text-medium)' }}
          data-tour-id="tour-help"
          aria-label="打开新手导览与帮助"
          title="新手导览与帮助"
          onClick={() => window.__openOnboarding?.({ source: 'help' })}
        >
          <IconHelp size={18} />
        </button>

        <button
          className="topbar-icon-button relative w-11 h-11 rounded-lg semantic-hover flex items-center justify-center transition-colors"
          style={{ color: 'var(--text-medium)' }}
          aria-label={notificationState.status === 'error' ? '通知加载失败，打开通知中心重试' : notificationState.status === 'permission' ? '无权读取通知' : '通知'}
          onClick={openNotifications}
        >
          <IconBell size={18} />
          {unreadCount > 0 && (
            <span
              className="absolute top-1 right-1 min-w-[16px] h-4 px-1 rounded-full font-mono text-[9.5px] font-bold text-white flex items-center justify-center"
              style={{ background: 'var(--danger)', boxShadow: '0 0 0 2px #fff' }}
            >
              {unreadCount}
            </span>
          )}
        </button>

        <div className="topbar-desktop-only w-px h-6 mx-1.5" style={{ background: 'var(--border-soft)' }} />

        {/* Tenant switcher (admin) / Super status pill (super) */}
        {isSuper ? (
          <button
            className="topbar-desktop-only min-h-11 inline-flex items-center gap-2 pl-2 pr-3 rounded-lg transition-colors"
            style={{
              background: 'var(--surface-muted)',
              border: '1px solid var(--border-soft)',
            }}
            onClick={() => window.navigateTo('super-tenants')}
            disabled={tenantState.status !== 'ready'}
            title={tenantState.status === 'ready' ? '进入租户管理' : (tenantState.message || '租户目录尚不可用')}
          >
            <span className="w-1.5 h-1.5 rounded-full" style={{ background: tenantState.status === 'ready' ? 'var(--success)' : 'var(--text-soft)' }} />
            <span className="text-[12.5px] font-semibold whitespace-nowrap" style={{ color: 'var(--text-medium)' }}>
              租户目录
            </span>
            <span className="font-mono text-[10.5px]" style={{ color: tenantState.status === 'ready' ? 'var(--success)' : 'var(--text-soft)' }}>
              {tenantState.status === 'ready' ? '已连接' : tenantState.status === 'loading' ? '读取中' : '不可用'}
            </span>
          </button>
        ) : !canSwitchTenant ? (
          /* 非超管：只显示自己学校，不给切换入口。
             此前这里对所有人都渲染切换器，但 loadTenants 调的 /admin/tenants 需要 SUPER_ADMIN，
             普通教务管理员必然 403 → 静默 catch → 界面上留着写死的 4 所**编造学校**
             （天津市实验中学 / 南开 / 耀华 / 天大附中）任其"切换"；而 selectTenant 派发的
             jkxt:tenant-change 全仓零监听、api.js 也从不发租户头，切了等于什么都没发生。 */
          <div className="topbar-desktop-only">
            <div className="min-h-11 inline-flex items-center gap-2 pl-2 pr-2.5 rounded-lg">
              <span
                className="w-6 h-6 rounded-md font-mono text-[10px] font-semibold flex items-center justify-center"
                style={{ background: 'var(--brand-soft)', color: 'var(--brand)' }}
              >
                {ownOrgAbbr}
              </span>
              <span className="text-[13px] font-medium hidden xl:inline whitespace-nowrap" style={{ color: 'var(--text-strong)' }}>
                {ownOrgName}
              </span>
            </div>
          </div>
        ) : (
          <div className="topbar-desktop-only relative">
            <button
              ref={tenantTriggerRef}
              onClick={() => setTenantOpen((v) => !v)}
              className="min-h-11 inline-flex items-center gap-2 pl-2 pr-2.5 rounded-lg semantic-hover transition-colors"
              aria-haspopup="menu"
              aria-expanded={tenantOpen}
            >
              <span
                className="w-6 h-6 rounded-md font-mono text-[10px] font-semibold flex items-center justify-center"
                style={{ background: 'var(--brand-soft)', color: tenant?.color || 'var(--text-soft)' }}
              >
                {tenant?.abbr || '—'}
              </span>
              <span className="text-[13px] font-medium hidden xl:inline whitespace-nowrap" style={{ color: 'var(--text-strong)' }}>
                {tenant?.name || (tenantState.status === 'loading' ? '读取租户目录…' : '租户目录不可用')}
              </span>
              <IconChevron size={14} className="text-slate-400" />
            </button>
            {tenantOpen && (
              <Dropdown anchor={tenantTriggerRef} onClose={() => setTenantOpen(false)}>
                <div className="px-3 py-2 font-mono text-[10.5px] uppercase tracking-wider"
                  style={{ color: 'var(--text-soft)', borderBottom: '1px solid var(--border-soft)' }}>
                  切换学校 / 租户
                </div>
                <ul className="py-1.5">
                  {tenantState.status !== 'ready' && <li className="px-3 py-3 text-[12px]" style={{ color: 'var(--text-medium)' }}>
                    <div>{tenantState.status === 'loading' ? '正在读取租户目录…' : (tenantState.message || '租户目录不可用。')}</div>
                    {tenantState.status !== 'loading' && tenantState.status !== 'permission' && <button type="button" role="menuitem" className="mt-2 min-h-11 px-2 font-medium underline underline-offset-2" style={{ color: 'var(--brand)' }} onClick={() => setTenantReloadKey((value) => value + 1)}>重试</button>}
                  </li>}
                  {tenants.map((t) => (
                    <li key={t.id}>
                      <button
                        type="button"
                        role="menuitem"
                        className="semantic-hover min-h-11 w-full px-3 py-2 flex items-center gap-2.5 text-left transition-colors"
                        onClick={() => {
                          selectTenant(t);
                          setTenantOpen(false);
                          window.ui.toast(`已切换至 ${t.name}`, { kind: 'success' });
                        }}
                      >
                        <span
                          className="w-7 h-7 rounded-md font-mono text-[11px] font-semibold flex items-center justify-center"
                          style={{ background: 'var(--brand-soft)', color: t.color }}
                        >
                          {t.abbr}
                        </span>
                        <div className="flex-1 min-w-0">
                          <div className="text-[13px] font-medium" style={{ color: 'var(--text-strong)' }}>{t.name}</div>
                          <div className="text-[11px] mt-0.5" style={{ color: 'var(--text-soft)' }}>{t.type}</div>
                        </div>
                        {t.id === tenant?.id && <IconCheck size={14} strokeWidth={2.25} style={{ color: 'var(--brand)' }} />}
                      </button>
                    </li>
                  ))}
                </ul>
              </Dropdown>
            )}
          </div>
        )}

        {/* Avatar */}
        <div className="relative">
          <button
            ref={avatarTriggerRef}
            data-tour-id="profile-trigger"
            onClick={() => setAvatarOpen((v) => !v)}
            aria-haspopup="menu"
            aria-expanded={avatarOpen}
            className="ml-1 min-h-11 inline-flex items-center gap-2 pl-1 pr-2 rounded-lg semantic-hover transition-colors"
          >
            <div
              className="w-7 h-7 rounded-full flex items-center justify-center text-[11px] font-semibold text-white relative"
              style={{ background: isSuper ? '#3157D5' : '#53647A' }}
            >
              {profileInitial}
              {isSuper && (
                <span className="absolute -bottom-0.5 -right-0.5 w-3 h-3 rounded-full flex items-center justify-center"
                  style={{ background: 'var(--surface-on-inverse)' }}>
                  <span className="w-2 h-2 rounded-full" style={{ background: '#16815A' }} />
                </span>
              )}
            </div>
            <div className="hidden xl:flex flex-col items-start leading-none whitespace-nowrap">
              <span className="text-[12px] font-medium" style={{ color: 'var(--text-strong)' }}>
                {profileName}
              </span>
              <span className="text-[10px] mt-0.5" style={{ color: isSuper ? '#9333EA' : 'var(--text-soft)' }}>
                {isSuper ? `JKXT 平台 · ${roleLabel}` : ownOrgName}
              </span>
            </div>
            <IconChevron size={14} className="text-slate-400" />
          </button>
          {avatarOpen && (
            <Dropdown anchor={avatarTriggerRef} onClose={() => setAvatarOpen(false)}>
              {isSuper ? (
                <>
                  <div className="px-4 py-4 flex items-center gap-3 relative overflow-hidden"
                    style={{ background: '#17233A' }}>
                    <div className="relative w-12 h-12 rounded-full flex items-center justify-center text-[18px] font-bold text-white shrink-0"
                      style={{ background: 'var(--surface-on-inverse)', border: '1px solid rgba(255,255,255,.45)' }}>
                      <ProductGlyph name="security" size={30} active />
                    </div>
                    <div className="relative min-w-0 text-white">
                      <div className="text-[14.5px] font-bold tracking-tight">{profileName}</div>
                      <div className="text-[11px] mt-0.5 font-mono opacity-85">{sessionUsername}</div>
                      <div className="mt-1.5 inline-flex items-center gap-1.5">
                        <span className="font-mono text-[9.5px] uppercase tracking-[.18em] px-1.5 py-0.5 rounded"
                          style={{ background: 'rgba(255,255,255,.18)', color: '#fff', border: '1px solid rgba(255,255,255,.28)' }}>超管</span>
                        <span className="inline-flex items-center gap-1 font-mono text-[10px]" style={{ color: 'rgba(255,255,255,.85)' }}>
                          <span className="w-1.5 h-1.5 rounded-full" style={{ background: '#79C94B' }} />
                          在线
                        </span>
                      </div>
                    </div>
                  </div>
                  {/* Platform stats strip — 真实值（原 7/99.98%/412 全为编造；在线率/登录数无数据源即不展示） */}
                  <div className="px-4 py-3 grid grid-cols-2 gap-2"
                    style={{ background: 'var(--surface-muted)', borderBottom: '1px solid var(--border-soft)' }}>
                    <div className="text-center">
                      <div className="num text-[15px] font-bold" style={{ color: 'var(--text-strong)' }}>{tenantState.status === 'ready' ? tenants.length : '—'}</div>
                      <div className="text-[10px] font-mono uppercase tracking-wider mt-0.5" style={{ color: 'var(--text-soft)' }}>租户</div>
                    </div>
                    <div className="text-center" style={{ borderLeft: '1px solid var(--border-soft)' }}>
                      <div className="num text-[15px] font-bold" style={{ color: '#10B981' }}>
                        {notificationState.status === 'ready' || notificationState.status === 'empty' ? notifSource.length : '—'}
                      </div>
                      <div className="text-[10px] font-mono uppercase tracking-wider mt-0.5" style={{ color: 'var(--text-soft)' }}>公告</div>
                    </div>
                  </div>
                  <ul className="py-1.5">
                    {[
                      { label: '超管个人资料',   icon: IconUsers,    onClick: () => window.navigateTo('super-accounts') },
                      { label: '平台系统设置',   icon: IconSettings, onClick: () => window.navigateTo('super-system-settings') },
                      { label: '安全策略',       icon: IconAlert,    onClick: () => window.navigateTo('super-security') },
                      { label: '我的审计记录',   icon: PRODUCT_ICON_COMPONENTS.audit, onClick: () => window.navigateTo('super-audit') },
                      { label: '新手导览',       icon: PRODUCT_ICON_COMPONENTS.help, onClick: () => window.__openOnboarding?.({ source: 'profile-menu' }) },
                      { label: '快捷键',         icon: IconHelp,     kbd: '⌘K', onClick: () => window.ui.toast('快捷键面板', { kind: 'info', desc: '⌘K 命令面板 · g+s 安全中心 · g+t 租户管理' }) },
                    ].map((it) => (
                      <li key={it.label}>
                        <button
                          type="button"
                          role="menuitem"
                          onClick={() => { setAvatarOpen(false); it.onClick(); }}
                          className="semantic-hover min-h-11 w-full px-3 py-2 flex items-center gap-2.5 text-left transition-colors"
                        >
                          <it.icon size={15} className="text-slate-400" />
                          <span className="text-[13px] flex-1" style={{ color: 'var(--text-strong)' }}>{it.label}</span>
                          {it.kbd && <span className="kbd">{it.kbd}</span>}
                        </button>
                      </li>
                    ))}
                  </ul>
                  <div className="border-t py-1.5" style={{ borderColor: 'var(--border-soft)' }}>
                    <button
                      type="button"
                      role="menuitem"
                      onClick={() => { setAvatarOpen(false); window.__handleLogout?.(); }}
                      className="semantic-danger-hover min-h-11 w-full px-3 py-2 flex items-center gap-2.5 text-left transition-colors"
                      style={{ color: 'var(--text-medium)' }}
                      onMouseEnter={(e) => (e.currentTarget.style.color = 'var(--danger)')}
                      onMouseLeave={(e) => (e.currentTarget.style.color = 'var(--text-medium)')}
                    >
                      <IconLogout size={15} />
                      <span className="text-[13px] flex-1">退出登录</span>
                      <span className="kbd">⇧⌘Q</span>
                    </button>
                  </div>
                </>
              ) : (
                <>
                  <div className="px-4 py-3.5 flex items-center gap-3"
                    style={{ background: 'var(--surface-muted)' }}>
                    <div className="w-11 h-11 rounded-full flex items-center justify-center text-[16px] font-semibold text-white shrink-0"
                      style={{ background: '#53647A' }}>
                      {profileInitial}
                    </div>
                    <div className="min-w-0">
                      <div className="text-[14px] font-semibold" style={{ color: 'var(--text-strong)' }}>{profileName}</div>
                      <div className="text-[11px] mt-0.5 font-mono" style={{ color: 'var(--text-soft)' }}>{profileEmail}</div>
                      <div className="mt-1.5 inline-flex items-center gap-1.5">
                        <Tag color="violet" dot>{roleLabel}</Tag>
                        <Tag color="emerald" dot>在线</Tag>
                      </div>
                    </div>
                  </div>
                  <ul className="py-1.5">
                    {[
                      { label: '个人资料',   icon: IconUsers,    onClick: () => onNav?.('settings') },
                      { label: '信息设置',   icon: IconSettings, onClick: () => onNav?.('settings') },
                      { label: '历史统计与导出', icon: IconArchive, onClick: () => onNav?.('history') },
                      { label: '新手导览',   icon: PRODUCT_ICON_COMPONENTS.help, onClick: () => window.__openOnboarding?.({ source: 'profile-menu' }) },
                      { label: '快捷键',     icon: IconHelp,     kbd: '?', onClick: () => window.ui.toast('快捷键面板', { kind: 'info', desc: '⌘K 命令面板 · / 搜索 · g+d 仪表盘' }) },
                    ].map((it) => (
                      <li key={it.label}>
                        <button
                          type="button"
                          role="menuitem"
                          onClick={() => { setAvatarOpen(false); it.onClick(); }}
                          className="semantic-hover min-h-11 w-full px-3 py-2 flex items-center gap-2.5 text-left transition-colors"
                        >
                          <it.icon size={15} className="text-slate-400" />
                          <span className="text-[13px] flex-1" style={{ color: 'var(--text-strong)' }}>{it.label}</span>
                          {it.kbd && <span className="kbd">{it.kbd}</span>}
                        </button>
                      </li>
                    ))}
                  </ul>
                  <div className="border-t py-1.5" style={{ borderColor: 'var(--border-soft)' }}>
                    <button
                      type="button"
                      role="menuitem"
                      onClick={() => { setAvatarOpen(false); window.__handleLogout?.(); }}
                      className="semantic-danger-hover min-h-11 w-full px-3 py-2 flex items-center gap-2.5 text-left transition-colors"
                      style={{ color: 'var(--text-medium)' }}
                      onMouseEnter={(e) => (e.currentTarget.style.color = 'var(--danger)')}
                      onMouseLeave={(e) => (e.currentTarget.style.color = 'var(--text-medium)')}
                    >
                      <IconLogout size={15} />
                      <span className="text-[13px] flex-1">退出登录</span>
                      <span className="kbd">⇧⌘Q</span>
                    </button>
                  </div>
                </>
              )}
            </Dropdown>
          )}
        </div>
      </div>

      {paletteOpen && (
        <CommandPalette
          onClose={closePalette}
          onNav={(p) => onNav?.(p)}
          items={paletteItems}
          mode={mode}
        />
      )}
    </header>
  );
}

window.Topbar = Topbar;
