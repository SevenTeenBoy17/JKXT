// Sidebar — 240px, white, active item gets brand-soft bg + 3px left bar
const { useState: useStateSb } = React;

function Sidebar({ active, onChange, onLogout, mode = 'admin' }) {
  // Auto-derive which parent groups should be expanded based on active child
  const childToParent = { classes: 'classes-subjects', subjects: 'classes-subjects' };
  const initiallyExpanded = childToParent[active] ? { [childToParent[active]]: true } : {};
  const [expanded, setExpanded] = useStateSb(initiallyExpanded);

  const ADMIN_GROUPS = [
    {
      label: '总览',
      items: [
        { id: 'dashboard', icon: IconDashboard, label: '仪表盘' },
      ],
    },
    {
      label: '排班作业',
      items: [
        { id: 'quick', icon: IconBolt, label: '一键排监考' },
        { id: 'ai', icon: IconSparkle, label: 'AI 策略监考', badge: 'AI' },
        { id: 'edit', icon: IconEdit, label: '修改监考安排' },
        { id: 'tasks', icon: IconList, label: '任务总览', count: 8 },
      ],
    },
    {
      label: '数据与资源',
      items: [
        { id: 'stats-supervise', icon: IconChart, label: '监考次数统计' },
        { id: 'people', icon: IconUsers, label: '监考人员管理' },
        { id: 'classrooms', icon: IconBuilding, label: '考场管理' },
        {
          id: 'classes-subjects',
          icon: IconBook,
          label: '班级科目管理',
          children: [
            { id: 'classes', label: '班级管理' },
            { id: 'subjects', label: '科目管理' },
          ],
        },
        { id: 'history', icon: IconArchive, label: '历史统计' },
      ],
    },
  ];

  const SUPER_GROUPS = [
    {
      label: '总览',
      items: [
        { id: 'super-dashboard', icon: IconDashboard, label: '超管仪表盘' },
      ],
    },
    {
      label: '租户与账号',
      items: [
        { id: 'super-tenants',  icon: IconBuilding, label: '租户管理', count: 7 },
        { id: 'super-accounts', icon: IconUsers,    label: '管理员账号', count: 7 },
      ],
    },
    {
      label: '平台运维',
      items: [
        { id: 'super-system-settings', icon: IconSettings, label: '系统设置' },
        { id: 'super-audit',           icon: IconArchive,  label: '审计日志', badge: 'NEW' },
        { id: 'super-security',        icon: IconAlert,    label: '安全中心' },
        { id: 'super-announcements',   icon: IconBell,     label: '公告发布' },
      ],
    },
  ];

  const groups = mode === 'super' ? SUPER_GROUPS : ADMIN_GROUPS;

  const Item = ({ item, depth = 0 }) => {
    const isActive = active === item.id;
    const hasChildren = !!item.children;
    const childActive = hasChildren && item.children.some((c) => c.id === active);
    const isOpen = expanded[item.id] || childActive;

    return (
      <>
        <button
          onClick={() => {
            if (hasChildren) setExpanded((e) => ({ ...e, [item.id]: !isOpen }));
            else onChange?.(item.id);
          }}
          className="relative w-full flex items-center h-10 rounded-lg transition-all text-left"
          style={{
            paddingLeft: depth === 0 ? '12px' : '42px',
            paddingRight: '10px',
            background: isActive
              ? 'linear-gradient(180deg, #FFFFFF 0%, var(--brand-soft) 100%)'
              : (hasChildren && childActive ? '#F8FAFC' : 'transparent'),
            color: isActive ? 'var(--brand)' : 'var(--text-medium)',
            boxShadow: isActive
              ? '0 1px 0 rgba(255,255,255,.95) inset, 0 1px 2px rgba(79,70,229,.10), 0 4px 12px -4px var(--brand-soft)'
              : 'none',
            border: isActive ? '1px solid rgba(99,102,241,.18)' : '1px solid transparent',
          }}
          onMouseEnter={(e) => { if (!isActive) { e.currentTarget.style.background = '#F8FAFC'; e.currentTarget.style.transform = 'translateX(2px)'; } }}
          onMouseLeave={(e) => { if (!isActive) { e.currentTarget.style.background = 'transparent'; e.currentTarget.style.transform = 'translateX(0)'; } }}
        >
          {isActive && (
            <span
              className="absolute left-0 top-1.5 bottom-1.5 w-[3px] rounded-r-full"
              style={{ background: 'var(--brand)' }}
            />
          )}
          {depth === 0 && item.icon && (
            <span className="shrink-0 w-5 h-5 flex items-center justify-center mr-2.5">
              <item.icon size={18} strokeWidth={isActive ? 2 : 1.75} />
            </span>
          )}
          <span
            className="text-[13.5px] flex-1"
            style={{ fontWeight: isActive ? 600 : 500 }}
          >
            {item.label}
          </span>
          {item.badge && (
            <span
              className="px-1.5 py-0.5 rounded text-[10px] font-mono font-semibold ml-2"
              style={{ background: '#FAF5FF', color: '#9333EA' }}
            >
              {item.badge}
            </span>
          )}
          {item.count !== undefined && (
            <span
              className="px-1.5 py-0.5 rounded text-[10.5px] font-mono font-medium ml-2"
              style={{ background: isActive ? '#FFFFFF' : '#F1F5F9', color: 'var(--text-medium)' }}
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
      className="w-[240px] shrink-0 bg-white flex flex-col sticky top-16 self-start"
      style={{
        borderRight: '1px solid var(--border-soft)',
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

      {/* Footer cluster — system + logout */}
      <div className="px-3 py-3 space-y-0.5" style={{ borderTop: '1px solid var(--border-soft)' }}>
        {mode === 'super' ? (
          <button
            onClick={() => onChange?.('super-system-settings')}
            className="relative w-full flex items-center h-10 px-3 rounded-lg transition-colors text-left"
            style={{
              color: active === 'super-system-settings' ? '#9333EA' : 'var(--text-medium)',
              background: active === 'super-system-settings' ? '#FAF5FF' : 'transparent',
              fontWeight: active === 'super-system-settings' ? 600 : 500,
            }}
            onMouseEnter={(e) => { if (active !== 'super-system-settings') e.currentTarget.style.background = '#F8FAFC'; }}
            onMouseLeave={(e) => { if (active !== 'super-system-settings') e.currentTarget.style.background = 'transparent'; }}
          >
            {active === 'super-system-settings' && (
              <span className="absolute left-0 top-1.5 bottom-1.5 w-[3px] rounded-r-full" style={{ background: '#9333EA' }} />
            )}
            <IconSettings size={18} className="mr-2.5" strokeWidth={active === 'super-system-settings' ? 2 : 1.75} />
            <span className="text-[13.5px]">系统设置</span>
          </button>
        ) : (
          <button
            onClick={() => onChange?.('settings')}
            className="relative w-full flex items-center h-10 px-3 rounded-lg transition-colors text-left"
            style={{
              color: active === 'settings' ? 'var(--brand)' : 'var(--text-medium)',
              background: active === 'settings' ? 'var(--brand-soft)' : 'transparent',
              fontWeight: active === 'settings' ? 600 : 500,
            }}
            onMouseEnter={(e) => { if (active !== 'settings') e.currentTarget.style.background = '#F8FAFC'; }}
            onMouseLeave={(e) => { if (active !== 'settings') e.currentTarget.style.background = 'transparent'; }}
          >
            {active === 'settings' && (
              <span className="absolute left-0 top-1.5 bottom-1.5 w-[3px] rounded-r-full" style={{ background: 'var(--brand)' }} />
            )}
            <IconSettings size={18} className="mr-2.5" strokeWidth={active === 'settings' ? 2 : 1.75} />
            <span className="text-[13.5px]">信息设置</span>
          </button>
        )}
        <button
          onClick={() => onLogout?.()}
          className="w-full flex items-center h-10 px-3 rounded-lg transition-colors text-left"
          style={{ color: 'var(--text-medium)' }}
          onMouseEnter={(e) => {
            e.currentTarget.style.background = 'var(--danger-soft)';
            e.currentTarget.style.color = 'var(--danger)';
          }}
          onMouseLeave={(e) => {
            e.currentTarget.style.background = 'transparent';
            e.currentTarget.style.color = 'var(--text-medium)';
          }}
        >
          <IconLogout size={18} className="mr-2.5" />
          <span className="text-[13.5px] font-medium">退出登录</span>
        </button>
      </div>

      {/* Storage hint footer */}
      <div className="px-5 pb-4 pt-1">
        <div className="rounded-lg p-3" style={{ background: mode === 'super' ? '#FAF5FF' : '#F8FAFC' }}>
          <div className="flex items-baseline justify-between mb-1.5">
            <span className="text-[11px] font-medium" style={{ color: 'var(--text-medium)' }}>
              {mode === 'super' ? '平台运行状态' : '本学期排班进度'}
            </span>
            <span className="font-mono text-[11px] font-semibold" style={{ color: mode === 'super' ? '#9333EA' : 'var(--brand)' }}>
              {mode === 'super' ? '99.98%' : '68%'}
            </span>
          </div>
          <div className="h-1.5 rounded-full overflow-hidden" style={{ background: '#E2E8F0' }}>
            <div className="h-full rounded-full" style={{ width: mode === 'super' ? '99.98%' : '68%', background: mode === 'super' ? '#9333EA' : 'var(--brand)' }} />
          </div>
          {mode === 'super' && (
            <div className="text-[10px] mt-1.5 font-mono" style={{ color: 'var(--text-soft)' }}>
              uptime 286 天 · 0 次故障
            </div>
          )}
        </div>
      </div>
    </aside>
  );
}

window.Sidebar = Sidebar;
