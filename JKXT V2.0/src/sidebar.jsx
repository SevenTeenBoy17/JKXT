// Sidebar — 240px, white, active item gets brand-soft bg + 3px left bar
const { useState: useStateSb, useEffect: useEffectSb } = React;

function Sidebar({ active, onChange, onLogout, mode = 'admin', asideRef, mobileOpen = false, isMobile = false }) {
  // 侧边栏计数此前写死（任务总览 8、租户管理 7、管理员账号 7），与库里真实数量无关——
  // 空库也会显示 8 个任务。这里改为按当前身份取真实值；取不到就不显示徽章，绝不显示假数字。
  const [counts, setCounts] = useStateSb({});
  useEffectSb(() => {
    let alive = true;
    if (!window.api?.get) return undefined;
    const load = (path, key) => window.api.get(path)
      .then((rows) => { if (alive && Array.isArray(rows)) setCounts((c) => ({ ...c, [key]: rows.length })); })
      .catch(() => {});   // 取不到就保持 undefined → 不渲染徽章
    if (mode === 'super') {
      load('/admin/tenants', 'tenants');
      load('/admin/accounts', 'accounts');
      // footer「平台运行状态」的真实数据源（此前写死 99.98%/uptime 286 天·0 次故障）
      window.api.get('/admin/system-health')
        .then((h) => { if (alive && h) setCounts((c) => ({ ...c, uptimeSeconds: h.uptimeSeconds })); })
        .catch(() => {});
    } else {
      // footer「本学期排班进度」= 已完成任务占比（此前写死 68%）
      window.api.get('/exam-tasks')
        .then((rows) => {
          if (!alive || !Array.isArray(rows)) return;
          const doneN = rows.filter((t) => ['scheduled', 'published', 'succeeded'].includes(String(t.latestJob?.status || t.status || ''))).length;
          setCounts((c) => ({ ...c, tasks: rows.length, donePct: rows.length ? Math.round((doneN / rows.length) * 100) : null }));
        })
        .catch(() => {});
    }
    return () => { alive = false; };
  }, [mode]);
  const fmtUp = (sec) => sec == null ? null : sec >= 86400 ? `${Math.floor(sec/86400)} 天` : sec >= 3600 ? `${Math.floor(sec/3600)} 小时` : `${Math.floor(sec/60)} 分钟`;
  const navActive = window.JKXTNavigation?.resolveActive?.(active) || (active === 'task-detail' ? 'tasks' : active);
  // Auto-derive which parent groups should be expanded based on active child
  const childToParent = window.JKXTNavigation?.parentByChild || { classes: 'classes-subjects', subjects: 'classes-subjects' };
  const initiallyExpanded = childToParent[navActive] ? { [childToParent[navActive]]: true } : {};
  const [expanded, setExpanded] = useStateSb(initiallyExpanded);
  const groups = window.JKXTNavigation?.groups?.(mode, counts) || [];

  const Item = ({ item, depth = 0 }) => {
    const isActive = navActive === item.id;
    const hasChildren = !!item.children;
    const childActive = hasChildren && item.children.some((c) => c.id === navActive);
    const hasExplicitState = Object.prototype.hasOwnProperty.call(expanded, item.id);
    const isOpen = hasExplicitState ? expanded[item.id] : childActive;

    return (
      <>
        <button
          onClick={() => {
            if (hasChildren) {
              setExpanded((e) => ({ ...e, [item.id]: !isOpen }));
            } else onChange?.(item.id);
          }}
          className="sidebar-nav-item relative w-full flex items-center min-h-11 rounded-lg transition-all text-left"
          data-tour-id={`nav-${item.id}`}
          data-active={isActive ? 'true' : 'false'}
          data-child-active={childActive ? 'true' : 'false'}
          aria-current={isActive ? 'page' : undefined}
          aria-expanded={hasChildren ? isOpen : undefined}
          style={{
            paddingLeft: depth === 0 ? '12px' : '42px',
            paddingRight: '10px',
            background: isActive
              ? 'var(--brand-soft)'
              : (hasChildren && childActive ? 'var(--surface-muted)' : 'transparent'),
            color: isActive ? 'var(--brand)' : 'var(--text-medium)',
            boxShadow: 'none',
            border: '1px solid transparent',
          }}
        >
          {isActive && (
            <span
              className="absolute left-0 top-1.5 bottom-1.5 w-[3px] rounded-r-full"
              style={{ background: 'var(--brand)' }}
            />
          )}
          {item.glyph && (
            <span className={`sidebar-nav-icon ${depth > 0 ? 'sidebar-nav-icon--child' : ''} mr-2.5`} aria-hidden="true">
              <SidebarGlyph name={item.glyph} size={depth > 0 ? 17 : 20} active={isActive || childActive} />
            </span>
          )}
          <span
            className="sidebar-nav-label flex-1"
            style={{ fontWeight: isActive ? 600 : 500 }}
          >
            {item.label}
          </span>
          {item.badge && (
            <span
              className="px-1.5 py-0.5 rounded text-[10px] font-mono font-semibold ml-2"
              style={{ background: 'var(--surface-muted)', color: 'var(--text-medium)', border: '1px solid var(--border-soft)' }}
            >
              {item.badge}
            </span>
          )}
          {item.count !== undefined && (
            <span
              className="px-1.5 py-0.5 rounded text-[10.5px] font-mono font-medium ml-2"
              style={{ background: isActive ? 'var(--surface-raised)' : 'var(--surface-muted)', color: 'var(--text-medium)' }}
            >
              {item.count}
            </span>
          )}
          {hasChildren && (
            <IconChevron
              dir={isOpen ? 'up' : 'down'}
              size={14}
              className="text-slate-400 ml-1"
            />
          )}
        </button>
        {hasChildren && isOpen && (
          <div className="relative mt-0.5">
            <span
              className="absolute left-[22px] top-1 bottom-1 w-px"
              style={{ background: 'var(--border-soft)' }}
            />
            {item.children.map((c) => (
              <Item key={c.id} item={c} depth={1} />
            ))}
          </div>
        )}
      </>
    );
  };

  return (
    <aside
      id="primary-sidebar"
      ref={asideRef}
      className="app-sidebar theme-surface w-[240px] shrink-0 flex flex-col sticky top-16 self-start"
      aria-label="主导航"
      role={isMobile ? 'dialog' : undefined}
      aria-modal={isMobile && mobileOpen ? true : undefined}
      aria-hidden={isMobile && !mobileOpen ? true : undefined}
      inert={isMobile && !mobileOpen ? '' : undefined}
      style={{
        borderRight: '1px solid var(--border-soft)',
        background: 'var(--surface-raised)',
        height: 'calc(100vh - 64px)',
      }}
    >
      <nav className="flex-1 overflow-y-auto scrollbar-thin px-3 py-4 space-y-5">
        {groups.map((g) => (
          <div key={g.label} className="space-y-0.5">
            <div
              className="px-3 mb-1.5 font-mono text-[10px] uppercase tracking-[0.14em] font-medium"
              style={{ color: 'var(--text-soft)' }}
            >
              {g.label}
            </div>
            {g.items.map((it) => (
              <Item key={it.id} item={it} />
            ))}
          </div>
        ))}
      </nav>

      {/* Footer cluster — navigation lives above; logout stays anchored. */}
      <div className="px-3 py-3 space-y-0.5" style={{ borderTop: '1px solid var(--border-soft)' }}>
        <button
          onClick={() => onLogout?.()}
          className="sidebar-nav-item sidebar-nav-item--danger w-full flex items-center min-h-11 px-3 rounded-lg transition-colors text-left"
          style={{ color: 'var(--text-medium)' }}
        >
          <span className="sidebar-nav-icon mr-2.5" aria-hidden="true"><SidebarGlyph name="logout" size={20} /></span>
          <span className="text-[13.5px] font-medium">退出登录</span>
        </button>
      </div>

      {/* Storage hint footer */}
      <div className="px-5 pb-4 pt-1">
        <div className="sidebar-status-card rounded-lg p-3" data-mode={mode} style={{ background: mode === 'super' ? 'var(--accent-soft)' : 'var(--surface-muted)' }}>
          <div className="flex items-baseline justify-between mb-1.5">
            <span className="text-[11px] font-medium" style={{ color: 'var(--text-medium)' }}>
              {mode === 'super' ? '平台运行状态' : '本学期排班进度'}
            </span>
            <span className="font-mono text-[11px] font-semibold" style={{ color: 'var(--brand)' }}>
              {mode === 'super' ? (counts.uptimeSeconds != null ? '运行中' : '—') : (counts.donePct != null ? counts.donePct + '%' : '—')}
            </span>
          </div>
          <div className="h-1.5 rounded-full overflow-hidden" style={{ background: 'var(--surface-sunken)' }}>
            <div className="h-full rounded-full" style={{ width: `${mode === 'super' ? (counts.uptimeSeconds != null ? 100 : 0) : (counts.donePct ?? 0)}%`, background: 'var(--brand)' }} />
          </div>
          {mode === 'super' && (
            <div className="text-[10px] mt-1.5 font-mono" style={{ color: 'var(--text-soft)' }}>
              {counts.uptimeSeconds != null ? `uptime ${fmtUp(counts.uptimeSeconds)}` : 'uptime 暂无数据'}
            </div>
          )}
        </div>
      </div>
    </aside>
  );
}

window.Sidebar = Sidebar;
