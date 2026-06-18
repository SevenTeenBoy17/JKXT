// Topbar — interactive: search ⌘K, semester, notifications drawer, avatar dropdown, tenant switcher

const { useState: useStateTb, useEffect: useEffectTb, useRef: useRefTb } = React;

// ── Notification list (mock) ─────────────────────────────
const NOTIFICATIONS = [
  { id: 1, kind: 'conflict', title: '高三·一模联考 检测到 1 处冲突', desc: 'A-303 14:30 教师 黄建国 同时段已被占用', at: '5 分钟前', unread: true, page: 'edit' },
  { id: 2, kind: 'system',   title: 'AI 排班完成 · 初三月考',         desc: '8 场考试 · 16 名监考已自动分配,可下载结果',         at: '20 分钟前', unread: true, page: 'tasks' },
  { id: 3, kind: 'task',     title: '王伟明 提交了请假申请',           desc: '2025-11-28 周五全天,涉及 2 场已排监考,需要调整',     at: '1 小时前', unread: true, page: 'people' },
  { id: 4, kind: 'system',   title: '本周排班进度 68%',                desc: '本学期累计 186 场,距离学期结束还有 8 周',           at: '今天 09:30', unread: false, page: 'dashboard' },
  { id: 5, kind: 'task',     title: '高三 11 月第二次月考 · 发布成功', desc: '已通过邮件通知 20 名监考教师,可在任务总览查看',     at: '昨天 14:32', unread: false, page: 'tasks' },
];

// ── Super-admin notifications (platform-level) ───────────
const SUPER_NOTIFICATIONS = [
  { id: 101, kind: 'security', title: '可疑 IP · 45.32.118.207 连续尝试登录 8 次', desc: '来源:美国 · 已自动拦截 · 建议加入永久封禁名单',         at: '12 分钟前', unread: true, page: 'super-security' },
  { id: 102, kind: 'tenant',   title: '新租户注册申请 · 天津外国语小学',         desc: '试用版 · 需审核基础资料并分配套餐与管理员账号',           at: '38 分钟前', unread: true, page: 'super-tenants' },
  { id: 103, kind: 'security', title: '李子衿 · 5 次密码错误 已锁定',             desc: '账号 lizj@yh.edu.cn · 来源 IP 203.93.211.4 · 待解锁',     at: '1 小时前',  unread: true, page: 'super-accounts' },
  { id: 104, kind: 'trial',    title: '天津外国语小学 试用即将到期',             desc: '剩余 6 天 · 标准版试用 · 需引导升级或续期',               at: '今天 09:14', unread: true, page: 'super-tenants' },
  { id: 105, kind: 'system',   title: '自动备份完成 · 4.2 GB',                   desc: '今日 03:00 · 全平台数据库快照已上传云端 · 校验通过',     at: '今天 03:02', unread: false, page: 'super-security' },
  { id: 106, kind: 'update',   title: 'v2.0.4 系统升级已部署',                   desc: '新增 K-12 完全中学排班模板 · 7 个租户已通知',             at: '昨天 22:00', unread: false, page: 'super-announcements' },
  { id: 107, kind: 'audit',    title: '高危审计事件 · 删除教师档案 3 条',         desc: '操作人 李子衿 · 租户 YH · 已发送复核邮件',                 at: '昨天 08:15', unread: false, page: 'super-audit' },
];

const NOTIF_META = {
  conflict: { icon: IconAlert,    bg: '#FEE2E2', fg: '#DC2626' },
  system:   { icon: IconSparkle,  bg: '#EEF2FF', fg: '#4F46E5' },
  task:     { icon: IconList,     bg: '#ECFDF5', fg: '#059669' },
  security: { icon: IconAlert,    bg: '#FEE2E2', fg: '#DC2626' },
  tenant:   { icon: IconBuilding, bg: '#FAF5FF', fg: '#9333EA' },
  trial:    { icon: IconClock,    bg: '#FFF7ED', fg: '#D97706' },
  update:   { icon: IconSparkle,  bg: '#E0F2FE', fg: '#0369A1' },
  audit:    { icon: IconArchive,  bg: '#FFF7ED', fg: '#D97706' },
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
const PALETTE_ITEMS = [
  { kind: 'page', label: '仪表盘',        kw: '总览 dashboard home',  icon: IconDashboard, page: 'dashboard' },
  { kind: 'page', label: '一键排监考',   kw: 'quick 排班 schedule',   icon: IconBolt,      page: 'quick' },
  { kind: 'page', label: 'AI 策略监考',  kw: 'ai 策略 智能',           icon: IconSparkle,   page: 'ai' },
  { kind: 'page', label: '修改监考安排', kw: 'edit 修改 排班',         icon: IconEdit,      page: 'edit' },
  { kind: 'page', label: '任务总览',     kw: 'tasks 任务',             icon: IconList,      page: 'tasks' },
  { kind: 'page', label: '监考次数统计', kw: 'stats 统计 报表',         icon: IconChart,     page: 'stats-supervise' },
  { kind: 'page', label: '监考人员管理', kw: 'people 教师 teacher',     icon: IconUsers,     page: 'people' },
  { kind: 'page', label: '考场管理',     kw: 'classroom 教室 room',     icon: IconBuilding,  page: 'classrooms' },
  { kind: 'page', label: '班级管理',     kw: 'classes 班级',            icon: IconBook,      page: 'classes' },
  { kind: 'page', label: '科目管理',     kw: 'subjects 学科',           icon: IconBook,      page: 'subjects' },
  { kind: 'page', label: '历史统计',     kw: 'history 历史 archive',    icon: IconArchive,   page: 'history' },
  { kind: 'page', label: '信息设置',     kw: 'settings 设置 个人',      icon: IconSettings,  page: 'settings' },
  // — Actions —
  { kind: 'action', label: '新建排监考任务',   kw: 'new task 新建',     icon: IconPlus,      run: () => window.navigateTo('quick') },
  { kind: 'action', label: '导入教师数据',     kw: 'import teacher',    icon: IconUpload,    run: () => window.navigateTo('people') },
  { kind: 'action', label: '切换学期',         kw: 'semester switch',   icon: IconCalendar,  run: () => window.ui.toast('已切换至 2025–2026 第二学期', { kind: 'success' }) },
  { kind: 'action', label: '重新查看新手引导', kw: 'onboarding 引导',   icon: IconHelp,      run: () => window.__openOnboarding?.() },
  { kind: 'action', label: '导出本学期报表',   kw: 'export 导出 报表',  icon: IconChart,     run: () => window.ui.toast('正在生成报表…', { kind: 'info' }) },
  { kind: 'action', label: '退出登录',         kw: 'logout sign out',   icon: IconLogout,    run: () => window.__handleLogout?.() },
  // — Teachers (深搜索) —
  { kind: 'teacher', label: '王伟明', kw: 'wangwm 高三 数学 T240101', desc: '高三·数学·班主任 T240101', icon: IconUsers, run: () => window.navigateTo('people') },
  { kind: 'teacher', label: '李慧君', kw: 'lihj 高三 语文 T240102',   desc: '高三·语文·班主任 T240102', icon: IconUsers, run: () => window.navigateTo('people') },
  { kind: 'teacher', label: '陈志远', kw: 'chenzy 高二 物理 T240202', desc: '高二·物理·班主任 T240202', icon: IconUsers, run: () => window.navigateTo('people') },
  { kind: 'teacher', label: '赵思琪', kw: 'zhaosq 初三 生物 J240901', desc: '初三·生物·班主任 J240901', icon: IconUsers, run: () => window.navigateTo('people') },
  // — Tasks (深搜索) —
  { kind: 'task', label: '高三·一模联考',     kw: 'gaosan moni T-2025-018', desc: 'T-2025-018 · 有冲突 · 12 场', icon: IconList, run: () => window.navigateTo('task-detail') },
  { kind: 'task', label: '高三 11 月月考',    kw: 'gaosan yuekao T-2025-016', desc: 'T-2025-016 · 进行中 · 10 场', icon: IconList, run: () => window.navigateTo('task-detail') },
  { kind: 'task', label: '初三年级月考',      kw: 'chusan yuekao T-2025-017', desc: 'T-2025-017 · 进行中 · 8 场',  icon: IconList, run: () => window.navigateTo('task-detail') },
];

const KIND_LABELS = { page: '页面', action: '操作', teacher: '教师', task: '任务' };
const KIND_COLORS = { page: 'indigo', action: 'amber', teacher: 'emerald', task: 'violet' };

// ── Super-admin command palette ──────────────────────────
const SUPER_PALETTE_ITEMS = [
  { kind: 'page', label: '超管仪表盘',  kw: 'dashboard 总览',         icon: IconDashboard, page: 'super-dashboard' },
  { kind: 'page', label: '租户管理',    kw: 'tenants 租户 学校 school', icon: IconBuilding, page: 'super-tenants' },
  { kind: 'page', label: '管理员账号',  kw: 'accounts admins 账号',    icon: IconUsers,    page: 'super-accounts' },
  { kind: 'page', label: '系统设置',    kw: 'settings 系统',           icon: IconSettings, page: 'super-system-settings' },
  { kind: 'page', label: '审计日志',    kw: 'audit logs 日志',         icon: IconArchive,  page: 'super-audit' },
  { kind: 'page', label: '安全中心',    kw: 'security 安全',           icon: IconAlert,    page: 'super-security' },
  { kind: 'page', label: '公告发布',    kw: 'announce 公告 announcements', icon: IconBell, page: 'super-announcements' },
  // — Actions —
  { kind: 'action', label: '注册新租户',         kw: 'add tenant new 新建 租户', icon: IconPlus,    run: () => window.ui.toast('打开租户注册向导', { kind: 'info' }) },
  { kind: 'action', label: '撰写平台公告',       kw: 'new announcement 公告',    icon: IconEdit,    run: () => window.navigateTo('super-announcements') },
  { kind: 'action', label: '导出审计日志',       kw: 'export audit 导出 日志',   icon: IconUpload,  run: () => window.ui.toast('正在导出审计日志…', { kind: 'info' }) },
  { kind: 'action', label: '立即执行手动备份',   kw: 'backup 备份',              icon: IconArchive, run: () => window.ui.toast('备份任务已加入队列', { kind: 'info', desc: '预计 2 分钟完成' }) },
  { kind: 'action', label: '退出登录',           kw: 'logout sign out',          icon: IconLogout,  run: () => window.__handleLogout?.() },
  // — Tenants (deep search) —
  { kind: 'tenant', label: '天津市实验中学',  kw: 'TSY tsy 实验',     desc: '完全中学 · 企业版 · 2400 学生 · 4 管理员', icon: IconBuilding, run: () => window.navigateTo('super-tenants') },
  { kind: 'tenant', label: '天津南开中学',    kw: 'NK nky 南开',      desc: '完全中学 · 企业版 · 2800 学生 · 6 管理员', icon: IconBuilding, run: () => window.navigateTo('super-tenants') },
  { kind: 'tenant', label: '天津外国语小学',  kw: 'WJX wjx 外国语',   desc: '小学 · 试用版 · 800 学生 · 6 天后到期',    icon: IconBuilding, run: () => window.navigateTo('super-tenants') },
];

const SUPER_KIND_LABELS = { page: '页面', action: '操作', tenant: '租户' };

// ── Tenants ──────────────────────────────────────────────
const TENANTS = [
  { id: 'tsy', name: '天津市实验中学',  type: '完全中学', abbr: 'TSY', active: true,  color: '#9333EA' },
  { id: 'nky', name: '天津南开中学',    type: '完全中学', abbr: 'NK',  active: false, color: '#4F46E5' },
  { id: 'yjs', name: '天津耀华小学',    type: '小学',     abbr: 'YH',  active: false, color: '#059669' },
  { id: 'tg',  name: '天津大学附属中学', type: '完全中学', abbr: 'TG',  active: false, color: '#D97706' },
];

function normalizeTopbarTenant(t, index = 0) {
  const id = t?.slug || t?.id || t?.uuid || `tenant-${index}`;
  const abbr = t?.abbr || String(id).slice(0, 3).toUpperCase();
  return {
    id,
    uuid: t?.uuid,
    slug: t?.slug || id,
    name: t?.name || id,
    type: t?.type || t?.plan || 'school',
    abbr,
    active: t?.active !== false && t?.status !== 'suspended',
    color: t?.color || ['#9333EA', '#4F46E5', '#059669', '#D97706'][index % 4],
    status: t?.status || 'active',
  };
}

function useClickOutside(ref, handler, deps = []) {
  useEffectTb(() => {
    const fn = (e) => { if (ref.current && !ref.current.contains(e.target)) handler(); };
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
          className="text-[12px] font-medium hover:underline"
          style={{ color: 'var(--brand)' }}
          onClick={() => { setList(list.map((n) => ({ ...n, unread: false }))); if (onMarkAllRead) onMarkAllRead(); else window.ui.toast('已标记全部为已读'); }}
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
            <li
              key={n.id}
              className="rounded-xl p-3 flex items-start gap-3 cursor-pointer transition-colors hover:bg-slate-50"
              style={{
                background: n.unread ? '#FAFBFC' : '#fff',
                border: '1px solid var(--border-soft)',
              }}
              onClick={() => {
                if (onMarkRead && n.unread) onMarkRead(n.id);
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
            </li>
          );
        })}
      </ul>
    </div>
  );
}

// ── Command Palette ──────────────────────────────────────
function CommandPalette({ onClose, onNav, items: paletteItems = PALETTE_ITEMS, mode = 'admin' }) {
  const [q, setQ] = useStateTb('');
  const [idx, setIdx] = useStateTb(0);
  const inputRef = useRefTb(null);
  const items = q
    ? paletteItems.filter((it) => (it.label + it.kw).toLowerCase().includes(q.toLowerCase()))
    : paletteItems.slice(0, 8);

  useEffectTb(() => {
    inputRef.current?.focus();
    const onKey = (e) => {
      if (e.key === 'Escape') onClose();
      if (e.key === 'ArrowDown') { e.preventDefault(); setIdx((i) => Math.min(items.length - 1, i + 1)); }
      if (e.key === 'ArrowUp')   { e.preventDefault(); setIdx((i) => Math.max(0, i - 1)); }
      if (e.key === 'Enter') {
        const it = items[idx];
        if (it) {
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
      <div onClick={(e) => e.stopPropagation()}
        className="relative bg-white rounded-2xl w-[560px] max-h-[60vh] overflow-hidden flex flex-col"
        style={{ boxShadow: '0 32px 80px rgba(15,23,42,.24)' }}>
        <div className="px-4 py-3 flex items-center gap-3" style={{ borderBottom: '1px solid var(--border-soft)' }}>
          <IconSearch size={16} className="text-slate-400" />
          <input
            ref={inputRef}
            value={q}
            onChange={(e) => { setQ(e.target.value); setIdx(0); }}
            placeholder="搜索页面、教师、操作..."
            className="flex-1 bg-transparent outline-none text-[14px]"
            style={{ color: 'var(--text-strong)' }}
          />
          <span className="kbd">esc</span>
        </div>
        <div className="flex-1 overflow-y-auto py-2 scrollbar-thin">
          {items.length === 0 && (
            <div className="px-4 py-8 text-center text-[12.5px]" style={{ color: 'var(--text-soft)' }}>
              没有找到匹配项,试试其他关键词
            </div>
          )}
          {items.map((it, i) => (
            <button
              key={i}
              className="w-full px-3 py-2.5 flex items-center gap-3 text-left transition-colors"
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
                style={{ background: i === idx ? '#fff' : '#F1F5F9', color: i === idx ? 'var(--brand)' : 'var(--text-medium)' }}>
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
        <div className="px-4 py-2.5 flex items-center gap-3 text-[10.5px] font-mono" style={{ borderTop: '1px solid var(--border-soft)', background: '#FAFBFC', color: 'var(--text-soft)' }}>
          <span><span className="kbd">↑↓</span> 导航</span>
          <span><span className="kbd">↵</span> 进入</span>
          <span><span className="kbd">esc</span> 关闭</span>
          <span className="ml-auto">{mode === 'super' ? 'JKXT Super · Cross-Tenant' : 'JKXT Command · K-12'}</span>
        </div>
      </div>
    </div>
  );
}

// ── Dropdown menu primitive ─────────────────────────────
function Dropdown({ anchor, children, onClose }) {
  const ref = useRefTb(null);
  useClickOutside(ref, onClose, []);
  return (
    <div
      ref={ref}
      className="absolute right-0 top-full mt-2 bg-white rounded-xl z-50 overflow-hidden"
      style={{
        minWidth: 240,
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
function Topbar({ onNav, active, mode = 'admin' }) {
  const isSuper = mode === 'super';
  const [tenantOpen,  setTenantOpen]  = useStateTb(false);
  const [avatarOpen,  setAvatarOpen]  = useStateTb(false);
  const [paletteOpen, setPaletteOpen] = useStateTb(false);
  const [tenants,     setTenants]     = useStateTb(TENANTS.map(normalizeTopbarTenant));
  const [tenant,      setTenant]      = useStateTb(normalizeTopbarTenant(TENANTS[0]));

  // ⌘K shortcut
  useEffectTb(() => {
    const onKey = (e) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        setPaletteOpen(true);
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, []);

  useEffectTb(() => {
    let cancelled = false;
    async function loadTenants() {
      if (!window.api?.get) return;
      try {
        const rows = await window.api.get('/admin/tenants');
        const normalized = (Array.isArray(rows) ? rows : []).map(normalizeTopbarTenant);
        if (cancelled || normalized.length === 0) return;
        setTenants(normalized);
        const saved = localStorage.getItem('jkxt:tenantId');
        setTenant(normalized.find((t) => t.id === saved || t.slug === saved || t.uuid === saved) || normalized[0]);
      } catch (error) {
        console.warn('[topbar] admin tenants fallback', error);
      }
    }
    loadTenants();
    return () => { cancelled = true; };
  }, []);

  const selectTenant = (t) => {
    setTenant(t);
    localStorage.setItem('jkxt:tenantId', t.slug || t.id);
    window.dispatchEvent(new CustomEvent('jkxt:tenant-change', { detail: t }));
  };

  // Admin notifications come from the real backend inbox (GET /api/v1/notifications). Super-admin
  // platform notifications remain illustrative (no platform-notification endpoint yet, R6).
  const [realNotifs, setRealNotifs] = useStateTb(null);
  const refreshNotifs = () => {
    if (isSuper || !window.api) return;
    window.api.get('/notifications')
      .then((rows) => setRealNotifs(Array.isArray(rows) ? rows.map(mapNotification) : []))
      .catch(() => setRealNotifs([]));
  };
  useEffectTb(() => { refreshNotifs(); }, [isSuper]);

  const notifSource = isSuper ? SUPER_NOTIFICATIONS : (realNotifs || []);
  const paletteItems = isSuper ? SUPER_PALETTE_ITEMS : PALETTE_ITEMS;
  const unreadCount = notifSource.filter((n) => n.unread).length;

  const markRead = (id) => {
    setRealNotifs((prev) => (prev || []).map((x) => (x.id === id ? { ...x, unread: false } : x)));
    try { window.api?.post(`/notifications/${id}/read`, {})?.catch?.(() => {}); } catch {}
  };
  const markAllRead = () => {
    setRealNotifs((prev) => (prev || []).map((x) => ({ ...x, unread: false })));
    try { window.api?.post('/notifications/read-all', {})?.catch?.(() => {}); } catch {}
    window.ui.toast('已标记全部为已读');
  };

  const openNotifications = () => {
    refreshNotifs();
    window.ui.drawer({
      title: isSuper ? '平台通知中心' : '通知中心',
      subtitle: `${unreadCount} 条未读 · ${isSuper ? '跨租户 · 平台级' : '本校 · 本学期'}`,
      width: 460,
      content: <NotificationsList onNav={(p) => onNav?.(p)} source={notifSource}
        onMarkRead={isSuper ? undefined : markRead} onMarkAllRead={isSuper ? undefined : markAllRead} />,
    });
  };

  return (
    <header
      className="h-16 bg-white flex items-center px-6 sticky top-0 z-30"
      style={{
        borderBottom: '1px solid var(--border-soft)',
        background: isSuper ? 'linear-gradient(180deg, #FAFAFD 0%, #FFFFFF 100%)' : '#fff',
      }}
    >
      {/* Logo lockup */}
      <button
        onClick={() => onNav?.(isSuper ? 'super-dashboard' : 'dashboard')}
        className="flex items-center gap-2.5 w-[208px] shrink-0 hover:opacity-80 transition-opacity"
      >
        <BrandMark size={26} />
        <div className="flex items-baseline gap-1.5">
          <span className="text-[15px] font-semibold tracking-tight" style={{ color: 'var(--text-strong)' }}>
            排监考系统
          </span>
          <span className="font-mono text-[10px] uppercase tracking-[0.12em]" style={{ color: 'var(--text-soft)' }}>
            v2.0
          </span>
        </div>
      </button>

      {/* Context: semester (admin) or platform indicator (super) */}
      {isSuper ? (
        <div
          className="ml-2 inline-flex items-center gap-2 h-9 pl-2 pr-3 rounded-lg whitespace-nowrap relative"
          style={{
            border: '1px solid #E9D5FF',
            background: 'linear-gradient(135deg, #FAF5FF 0%, #F5F3FF 100%)',
            boxShadow: 'inset 0 1px 0 rgba(255,255,255,.6)',
          }}
          title="您处于跨租户的超级管理员视图"
        >
          <span className="inline-flex items-center justify-center w-6 h-6 rounded-md"
            style={{ background: 'linear-gradient(135deg, #7C3AED, #A21CAF)', color: '#fff' }}>
            <IconSparkle size={12} strokeWidth={2.25} />
          </span>
          <span className="text-[12.5px] font-semibold whitespace-nowrap" style={{ color: '#6B21A8' }}>
            全平台视图
          </span>
          <span className="font-mono text-[10.5px] px-1.5 py-0.5 rounded"
            style={{ background: 'rgba(124,58,237,.12)', color: '#7C3AED', letterSpacing: '.05em' }}>
            7 租户
          </span>
        </div>
      ) : (
        <button
          className="ml-2 inline-flex items-center gap-2 h-9 pl-2 pr-3 rounded-lg hover:bg-slate-50 transition-colors whitespace-nowrap"
          style={{ border: '1px solid var(--border-soft)' }}
          onClick={() => window.ui.toast('切换学期需在「信息设置」中操作', { kind: 'info', action: { label: '前往设置', onClick: () => onNav?.('settings') } })}
        >
          <span
            className="inline-flex items-center justify-center w-6 h-6 rounded-md"
            style={{ background: 'var(--brand-soft)', color: 'var(--brand)' }}
          >
            <IconCalendar size={14} strokeWidth={2} />
          </span>
          <span className="text-[13px] font-medium whitespace-nowrap" style={{ color: 'var(--text-strong)' }}>
            2025–2026
          </span>
          <span
            className="px-1.5 py-0.5 rounded text-[11px] font-medium whitespace-nowrap"
            style={{ background: '#F1F5F9', color: 'var(--text-medium)' }}
          >
            第二学期
          </span>
          <IconChevron size={14} className="text-slate-400" />
        </button>
      )}

      {/* Search */}
      <div className="flex-1 flex justify-center px-8">
        <button
          onClick={() => setPaletteOpen(true)}
          className="group flex items-center w-full max-w-[460px] h-10 px-3.5 rounded-xl bg-slate-50 hover:bg-white transition-all"
          style={{ border: '1px solid transparent' }}
          onMouseEnter={(e) => (e.currentTarget.style.borderColor = 'var(--border-soft)')}
          onMouseLeave={(e) => (e.currentTarget.style.borderColor = 'transparent')}
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
      <div className="flex items-center gap-1.5 shrink-0">
        <button
          className="w-9 h-9 rounded-lg hover:bg-slate-50 flex items-center justify-center transition-colors"
          style={{ color: 'var(--text-medium)' }}
          aria-label="帮助"
          onClick={() => window.ui.toast(isSuper ? '超管手册 · 平台运维指南' : '在线帮助文档', { kind: 'info', desc: isSuper ? '租户管理、安全策略、审计、备份恢复 SOP' : '常见问题、操作视频、联系教务处' })}
        >
          <IconHelp size={18} />
        </button>

        <button
          className="relative w-9 h-9 rounded-lg hover:bg-slate-50 flex items-center justify-center transition-colors"
          style={{ color: 'var(--text-medium)' }}
          aria-label="通知"
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

        <div className="w-px h-6 mx-1.5" style={{ background: 'var(--border-soft)' }} />

        {/* Tenant switcher (admin) / Super status pill (super) */}
        {isSuper ? (
          <button
            className="h-9 inline-flex items-center gap-2 pl-2 pr-3 rounded-lg transition-colors"
            style={{
              background: 'linear-gradient(135deg, rgba(124,58,237,.10), rgba(217,70,239,.08))',
              border: '1px solid rgba(124,58,237,.22)',
            }}
            onMouseEnter={(e) => { e.currentTarget.style.background = 'linear-gradient(135deg, rgba(124,58,237,.16), rgba(217,70,239,.12))'; }}
            onMouseLeave={(e) => { e.currentTarget.style.background = 'linear-gradient(135deg, rgba(124,58,237,.10), rgba(217,70,239,.08))'; }}
            onClick={() => window.navigateTo('super-tenants')}
            title="进入租户管理"
          >
            <span className="w-1.5 h-1.5 rounded-full" style={{ background: '#10B981', boxShadow: '0 0 6px #10B981' }} />
            <span className="text-[12.5px] font-semibold whitespace-nowrap" style={{ color: '#6B21A8' }}>
              系统运行正常
            </span>
            <span className="font-mono text-[10.5px]" style={{ color: '#9333EA' }}>
              99.98%
            </span>
          </button>
        ) : (
          <div className="relative">
            <button
              onClick={() => setTenantOpen((v) => !v)}
              className="h-9 inline-flex items-center gap-2 pl-2 pr-2.5 rounded-lg hover:bg-slate-50 transition-colors"
            >
              <span
                className="w-6 h-6 rounded-md font-mono text-[10px] font-semibold flex items-center justify-center"
                style={{ background: tenant.color + '20', color: tenant.color }}
              >
                {tenant.abbr}
              </span>
              <span className="text-[13px] font-medium hidden xl:inline whitespace-nowrap" style={{ color: 'var(--text-strong)' }}>
                {tenant.name}
              </span>
              <IconChevron size={14} className="text-slate-400" />
            </button>
            {tenantOpen && (
              <Dropdown onClose={() => setTenantOpen(false)}>
                <div className="px-3 py-2 font-mono text-[10.5px] uppercase tracking-wider"
                  style={{ color: 'var(--text-soft)', borderBottom: '1px solid var(--border-soft)' }}>
                  切换学校 / 租户
                </div>
                <ul className="py-1.5">
                  {tenants.map((t) => (
                    <li key={t.id}>
                      <button
                        className="w-full px-3 py-2 flex items-center gap-2.5 text-left hover:bg-slate-50 transition-colors"
                        onClick={() => {
                          selectTenant(t);
                          setTenantOpen(false);
                          window.ui.toast(`已切换至 ${t.name}`, { kind: 'success' });
                        }}
                      >
                        <span
                          className="w-7 h-7 rounded-md font-mono text-[11px] font-semibold flex items-center justify-center"
                          style={{ background: t.color + '20', color: t.color }}
                        >
                          {t.abbr}
                        </span>
                        <div className="flex-1 min-w-0">
                          <div className="text-[13px] font-medium" style={{ color: 'var(--text-strong)' }}>{t.name}</div>
                          <div className="text-[11px] mt-0.5" style={{ color: 'var(--text-soft)' }}>{t.type}</div>
                        </div>
                        {t.id === tenant.id && <IconCheck size={14} strokeWidth={2.25} style={{ color: 'var(--brand)' }} />}
                      </button>
                    </li>
                  ))}
                </ul>
                <div className="px-3 py-2.5 border-t" style={{ borderColor: 'var(--border-soft)' }}>
                  <button
                    className="w-full text-[12.5px] inline-flex items-center gap-1.5 hover:underline"
                    style={{ color: 'var(--brand)' }}
                    onClick={() => { setTenantOpen(false); window.ui.toast('如需新增租户,请联系超管', { kind: 'info' }); }}
                  >
                    <IconPlus size={12} strokeWidth={2.5} /> 申请加入新学校
                  </button>
                </div>
              </Dropdown>
            )}
          </div>
        )}

        {/* Avatar */}
        <div className="relative">
          <button
            onClick={() => setAvatarOpen((v) => !v)}
            className="ml-1 h-9 inline-flex items-center gap-2 pl-1 pr-2 rounded-lg hover:bg-slate-50 transition-colors"
          >
            <div
              className="w-7 h-7 rounded-full flex items-center justify-center text-[11px] font-semibold text-white relative"
              style={{ background: isSuper
                ? 'linear-gradient(135deg, #7C3AED 0%, #A21CAF 60%, #DB2777 100%)'
                : 'linear-gradient(135deg, #6366F1 0%, #9333EA 100%)' }}
            >
              {isSuper ? 'S' : '王'}
              {isSuper && (
                <span className="absolute -bottom-0.5 -right-0.5 w-3 h-3 rounded-full flex items-center justify-center"
                  style={{ background: '#fff' }}>
                  <span className="w-2 h-2 rounded-full" style={{ background: 'linear-gradient(135deg, #7C3AED, #DB2777)' }} />
                </span>
              )}
            </div>
            <div className="hidden xl:flex flex-col items-start leading-none whitespace-nowrap">
              <span className="text-[12px] font-medium" style={{ color: 'var(--text-strong)' }}>
                {isSuper ? '超级管理员' : '王明远'}
              </span>
              <span className="text-[10px] mt-0.5" style={{ color: isSuper ? '#9333EA' : 'var(--text-soft)' }}>
                {isSuper ? 'JKXT 平台 · SUPER' : '教务主任'}
              </span>
            </div>
            <IconChevron size={14} className="text-slate-400" />
          </button>
          {avatarOpen && (
            <Dropdown onClose={() => setAvatarOpen(false)}>
              {isSuper ? (
                <>
                  <div className="px-4 py-4 flex items-center gap-3 relative overflow-hidden"
                    style={{ background: 'linear-gradient(135deg, #1E1B4B 0%, #4F46E5 50%, #A21CAF 100%)' }}>
                    <svg className="absolute inset-0 opacity-15 pointer-events-none" width="100%" height="100%">
                      <defs>
                        <pattern id="topavatar-grid" width="14" height="14" patternUnits="userSpaceOnUse">
                          <path d="M14 0 L0 0 0 14" fill="none" stroke="#fff" strokeWidth=".6" />
                        </pattern>
                      </defs>
                      <rect width="100%" height="100%" fill="url(#topavatar-grid)" />
                    </svg>
                    <div className="relative w-12 h-12 rounded-full flex items-center justify-center text-[18px] font-bold text-white shrink-0"
                      style={{ background: 'rgba(255,255,255,.18)', backdropFilter: 'blur(8px)', border: '1.5px solid rgba(255,255,255,.4)' }}>
                      <IconSparkle size={20} strokeWidth={2} />
                    </div>
                    <div className="relative min-w-0 text-white">
                      <div className="text-[14.5px] font-bold tracking-tight">超级管理员</div>
                      <div className="text-[11px] mt-0.5 font-mono opacity-85">admin@jkxt.cn</div>
                      <div className="mt-1.5 inline-flex items-center gap-1.5">
                        <span className="font-mono text-[9.5px] uppercase tracking-[.18em] px-1.5 py-0.5 rounded"
                          style={{ background: 'rgba(255,255,255,.18)', color: '#fff', border: '1px solid rgba(255,255,255,.28)' }}>SUPER</span>
                        <span className="inline-flex items-center gap-1 font-mono text-[10px]" style={{ color: 'rgba(255,255,255,.85)' }}>
                          <span className="w-1.5 h-1.5 rounded-full" style={{ background: '#10B981', boxShadow: '0 0 6px #10B981' }} />
                          在线
                        </span>
                      </div>
                    </div>
                  </div>
                  {/* Platform stats strip */}
                  <div className="px-4 py-3 grid grid-cols-3 gap-2"
                    style={{ background: '#FAFBFC', borderBottom: '1px solid var(--border-soft)' }}>
                    <div className="text-center">
                      <div className="num text-[15px] font-bold" style={{ color: 'var(--text-strong)' }}>7</div>
                      <div className="text-[10px] font-mono uppercase tracking-wider mt-0.5" style={{ color: 'var(--text-soft)' }}>租户</div>
                    </div>
                    <div className="text-center" style={{ borderLeft: '1px solid var(--border-soft)', borderRight: '1px solid var(--border-soft)' }}>
                      <div className="num text-[15px] font-bold" style={{ color: '#10B981' }}>99.98%</div>
                      <div className="text-[10px] font-mono uppercase tracking-wider mt-0.5" style={{ color: 'var(--text-soft)' }}>UPTIME</div>
                    </div>
                    <div className="text-center">
                      <div className="num text-[15px] font-bold" style={{ color: '#9333EA' }}>412</div>
                      <div className="text-[10px] font-mono uppercase tracking-wider mt-0.5" style={{ color: 'var(--text-soft)' }}>登录</div>
                    </div>
                  </div>
                  <ul className="py-1.5">
                    {[
                      { label: '超管个人资料',   icon: IconUsers,    onClick: () => window.navigateTo('super-accounts') },
                      { label: '平台系统设置',   icon: IconSettings, onClick: () => window.navigateTo('super-system-settings') },
                      { label: '安全策略',       icon: IconAlert,    onClick: () => window.navigateTo('super-security') },
                      { label: '我的审计记录',   icon: IconArchive,  onClick: () => window.navigateTo('super-audit') },
                      { label: '快捷键',         icon: IconHelp,     kbd: '⌘K', onClick: () => window.ui.toast('快捷键面板', { kind: 'info', desc: '⌘K 命令面板 · g+s 安全中心 · g+t 租户管理' }) },
                    ].map((it) => (
                      <li key={it.label}>
                        <button
                          onClick={() => { setAvatarOpen(false); it.onClick(); }}
                          className="w-full px-3 py-2 flex items-center gap-2.5 text-left hover:bg-slate-50 transition-colors"
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
                      onClick={() => { setAvatarOpen(false); window.__handleLogout?.(); }}
                      className="w-full px-3 py-2 flex items-center gap-2.5 text-left transition-colors hover:bg-rose-50"
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
                    style={{ background: 'linear-gradient(135deg, #EEF2FF, #FAF5FF)' }}>
                    <div className="w-11 h-11 rounded-full flex items-center justify-center text-[16px] font-semibold text-white shrink-0"
                      style={{ background: 'linear-gradient(135deg, #6366F1 0%, #9333EA 100%)' }}>
                      王
                    </div>
                    <div className="min-w-0">
                      <div className="text-[14px] font-semibold" style={{ color: 'var(--text-strong)' }}>王明远</div>
                      <div className="text-[11px] mt-0.5 font-mono" style={{ color: 'var(--text-soft)' }}>wangmy@tsy.edu.cn</div>
                      <div className="mt-1.5 inline-flex items-center gap-1.5">
                        <Tag color="violet" dot>教务主任</Tag>
                        <Tag color="emerald" dot>在线</Tag>
                      </div>
                    </div>
                  </div>
                  <ul className="py-1.5">
                    {[
                      { label: '个人资料',   icon: IconUsers,    onClick: () => onNav?.('settings') },
                      { label: '信息设置',   icon: IconSettings, onClick: () => onNav?.('settings') },
                      { label: '操作历史',   icon: IconArchive,  onClick: () => window.ui.toast('操作历史', { kind: 'info', desc: '本月共记录 142 条操作' }) },
                      { label: '快捷键',     icon: IconHelp,     kbd: '?', onClick: () => window.ui.toast('快捷键面板', { kind: 'info', desc: '⌘K 命令面板 · / 搜索 · g+d 仪表盘' }) },
                    ].map((it) => (
                      <li key={it.label}>
                        <button
                          onClick={() => { setAvatarOpen(false); it.onClick(); }}
                          className="w-full px-3 py-2 flex items-center gap-2.5 text-left hover:bg-slate-50 transition-colors"
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
                      onClick={() => { setAvatarOpen(false); window.__handleLogout?.(); }}
                      className="w-full px-3 py-2 flex items-center gap-2.5 text-left transition-colors hover:bg-rose-50"
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
          onClose={() => setPaletteOpen(false)}
          onNav={(p) => onNav?.(p)}
          items={paletteItems}
          mode={mode}
        />
      )}
    </header>
  );
}

window.Topbar = Topbar;
