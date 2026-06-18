// 超管控制台 — 7 个子页 (in single file for compactness)
// Pages: super-dashboard / super-tenants / super-accounts / super-system-settings /
//        super-audit / super-security / super-announcements

// ── Mock data ─────────────────────────────────────────────────────────
const SUPER_TENANTS = [
  { id: 't001', slug: 'tsy',  name: '天津市实验中学',   type: '完全中学', plan: '企业版',   admins: 4, students: 2400, status: 'active',    createdAt: '2024-08-10', city: '天津 · 南开区' },
  { id: 't002', slug: 'nky',  name: '天津南开中学',     type: '完全中学', plan: '企业版',   admins: 6, students: 2800, status: 'active',    createdAt: '2024-09-12', city: '天津 · 南开区' },
  { id: 't003', slug: 'yh',   name: '天津耀华小学',     type: '小学',     plan: '专业版',   admins: 2, students: 1200, status: 'active',    createdAt: '2024-10-22', city: '天津 · 和平区' },
  { id: 't004', slug: 'tg',   name: '天津大学附属中学', type: '完全中学', plan: '企业版',   admins: 5, students: 1800, status: 'active',    createdAt: '2025-02-08', city: '天津 · 南开区' },
  { id: 't005', slug: 'wjx',  name: '天津外国语小学',   type: '小学',     plan: '标准版',   admins: 1, students: 800,  status: 'trial',     createdAt: '2025-09-20', city: '天津 · 河西区' },
  { id: 't006', slug: 'bsd',  name: '天津宝坻第一中学', type: '高中',     plan: '专业版',   admins: 3, students: 1600, status: 'active',    createdAt: '2025-08-15', city: '天津 · 宝坻区' },
  { id: 't007', slug: 'wq',   name: '天津武清外国语',   type: '初中',     plan: '专业版',   admins: 2, students: 900,  status: 'suspended', createdAt: '2025-06-30', city: '天津 · 武清区' },
];

const SUPER_ACCOUNTS = [
  { id: 'A001', name: '王明远', email: 'wangmy@tsy.edu.cn',   department: '教务处', role: 'admin',  tenant: 'TSY', status: 'online',  lastLogin: '今天 08:32', twoFactor: true,  loginCount: 248 },
  { id: 'A002', name: '李慧君', email: 'lihj@tsy.edu.cn',     department: '教务处', role: 'viewer', tenant: 'TSY', status: 'offline', lastLogin: '今天 07:50', twoFactor: true,  loginCount: 86 },
  { id: 'A003', name: '陈钰',   email: 'chen@nky.edu.cn',     department: '教务处', role: 'admin',  tenant: 'NK',  status: 'online',  lastLogin: '今天 09:01', twoFactor: true,  loginCount: 192 },
  { id: 'A004', name: '李子衿', email: 'lizj@yh.edu.cn',      department: '教导处', role: 'admin',  tenant: 'YH',  status: 'offline', lastLogin: '昨天 16:25', twoFactor: false, loginCount: 64 },
  { id: 'A005', name: '张伟',   email: 'zhang@tg.edu.cn',     department: '教务处', role: 'admin',  tenant: 'TG',  status: 'online',  lastLogin: '今天 09:12', twoFactor: true,  loginCount: 138 },
  { id: 'A006', name: '超级管理员', email: 'admin@jkxt.cn',   department: '平台',   role: 'super',  tenant: '—',   status: 'online',  lastLogin: '今天 09:30', twoFactor: true,  loginCount: 412 },
  { id: 'A007', name: '徐安琪', email: 'xu@bsd.edu.cn',       department: '教务处', role: 'viewer', tenant: 'BSD', status: 'offline', lastLogin: '昨天 18:00', twoFactor: false, loginCount: 32 },
];

const SUPER_AUDIT = [
  { id: 'L0001', actor: '王明远', tenant: 'TSY', action: '发布排班 · 高三 11 月月考',      target: 'T-2025-016', severity: 'info',  ip: '124.65.123.45',  at: '今天 14:32:18' },
  { id: 'L0002', actor: '陈钰',   tenant: 'NK',  action: '导出历史报表',                  target: 'history',     severity: 'info',  ip: '113.224.45.18',  at: '今天 11:08:42' },
  { id: 'L0003', actor: '超级管理员', tenant: '—',action: '注册新租户 · 天津外国语小学',   target: 't005',        severity: 'warn',  ip: '124.65.123.45',  at: '今天 09:54:01' },
  { id: 'L0004', actor: '李子衿', tenant: 'YH',  action: '删除教师档案 (3 条)',           target: 'people',      severity: 'warn',  ip: '116.230.45.92',  at: '今天 08:15:33' },
  { id: 'L0005', actor: '王明远', tenant: 'TSY', action: '修改密码',                      target: 'settings',    severity: 'info',  ip: '124.65.123.45',  at: '昨天 16:42:09' },
  { id: 'L0006', actor: '未知',   tenant: 'YH',  action: '登录失败 · 密码错误 5 次',       target: 'lizj@...',    severity: 'high',  ip: '203.93.211.4',   at: '昨天 14:22:00' },
  { id: 'L0007', actor: '张伟',   tenant: 'TG',  action: '批量导入教师 (142 条)',         target: 'people',      severity: 'info',  ip: '124.65.18.222',  at: '昨天 10:30:55' },
  { id: 'L0008', actor: '超级管理员', tenant: '—',action: '系统设置 · 启用自动备份',       target: 'settings',    severity: 'info',  ip: '124.65.123.45',  at: '11-19 22:14:00' },
  { id: 'L0009', actor: '未知',   tenant: 'WQ',  action: '尝试访问超管接口 · 已拦截',      target: '/admin/super',severity: 'high',  ip: '45.32.118.207',  at: '11-19 18:08:12' },
];

const SUPER_SEC = {
  score: 86,
  failedLogins: 14,
  suspiciousIps: 3,
  lastBackup: '今天 03:00',
  threats: [
    { ip: '45.32.118.207', country: '美国',     attempts: 8, lastAt: '11-19 18:08', severity: 'high' },
    { ip: '203.93.211.4',  country: '中国',     attempts: 5, lastAt: '昨天 14:22',   severity: 'med'  },
    { ip: '188.40.86.122', country: '德国',     attempts: 3, lastAt: '11-17 02:15', severity: 'low'  },
  ],
};

const SUPER_ANNOUNCEMENTS = [
  { id: 'AN001', title: 'v2.0.4 系统升级 · 新增 K-12 完全中学排班模板', severity: 'info',     publishAt: '今天 09:00', expireAt: '12-20',  status: 'published', target: '全部租户', readBy: 156 },
  { id: 'AN002', title: '12 月底前完成本学期归档,生成新学期任务',         severity: 'reminder', publishAt: '昨天 14:30', expireAt: '12-31',  status: 'published', target: '全部租户', readBy: 142 },
  { id: 'AN003', title: '安全提醒 · 请所有教务管理员开启二次验证',         severity: 'critical', publishAt: '11-18',      expireAt: '12-31',  status: 'published', target: '管理员',   readBy: 98  },
  { id: 'AN004', title: '寒假期间系统维护通告 (2026-01-20 ~ 2026-02-05)',  severity: 'info',     publishAt: '—',          expireAt: '2026-01-20', status: 'draft',     target: '全部租户', readBy: 0   },
];

function useSuperResource(path, fallback) {
  const [rows, setRows] = React.useState(fallback);
  const [loading, setLoading] = React.useState(false);
  const reload = React.useCallback(async () => {
    if (!window.api?.get) return fallback;
    setLoading(true);
    try {
      const data = await window.api.get(path);
      const next = Array.isArray(data) ? data : fallback;
      setRows(next);
      return next;
    } catch (error) {
      console.warn(`[super] ${path} fallback`, error);
      setRows(fallback);
      return fallback;
    } finally {
      setLoading(false);
    }
  }, [path, fallback]);
  React.useEffect(() => { reload(); }, [reload]);
  return [rows, reload, loading, setRows];
}

function useSuperObject(path, fallback) {
  const [value, setValue] = React.useState(fallback);
  const reload = React.useCallback(async () => {
    if (!window.api?.get) return fallback;
    try {
      const data = await window.api.get(path);
      const next = data && typeof data === 'object' ? data : fallback;
      setValue(next);
      return next;
    } catch (error) {
      console.warn(`[super] ${path} fallback`, error);
      setValue(fallback);
      return fallback;
    }
  }, [path, fallback]);
  React.useEffect(() => { reload(); }, [reload]);
  return [value, reload, setValue];
}

async function superPost(path, body, reload, successText) {
  const shouldToast = successText !== false;
  if (!window.api?.post) {
    if (shouldToast) window.ui.toast(successText || '操作已记录', { kind: 'info' });
    return null;
  }
  try {
    const result = await window.api.post(path, body || {});
    if (reload) await reload();
    if (shouldToast) window.ui.toast(successText || '操作已记录', { kind: 'success', desc: '后端已写入审计日志' });
    return result;
  } catch (error) {
    console.error(`[super] ${path} failed`, error);
    window.ui.toast(error.message || '操作失败', { kind: 'error' });
    return null;
  }
}

async function superPut(path, body, reload, successText) {
  const shouldToast = successText !== false;
  if (!window.api?.put) {
    if (shouldToast) window.ui.toast(successText || '操作已记录', { kind: 'info' });
    return null;
  }
  try {
    const result = await window.api.put(path, body || {});
    if (reload) await reload();
    if (shouldToast) window.ui.toast(successText || '操作已记录', { kind: 'success', desc: '后端已写入审计日志' });
    return result;
  } catch (error) {
    console.error(`[super] ${path} failed`, error);
    window.ui.toast(error.message || '操作失败', { kind: 'error' });
    return null;
  }
}

function superBackendId(row) {
  const id = row?.uuid || row?.id;
  return /^[0-9a-f]{8}-[0-9a-f-]{27}$/i.test(String(id || '')) ? id : null;
}

function announcementStatusLabel(status) {
  if (status === 'published') return '已发布';
  if (status === 'retracted') return '已撤回';
  return '草稿';
}

function announcementStatusColor(status) {
  if (status === 'published') return 'emerald';
  if (status === 'retracted') return 'rose';
  return 'slate';
}

function announcementTargetLabel(target) {
  if (target === 'admins') return '管理员';
  if (target === 'selected') return '选定租户';
  if (target === 'all') return '全部租户';
  return target || '全部租户';
}

function normalizeAnnouncementSeverity(value) {
  if (value === '紧急' || value === 'critical') return 'critical';
  if (value === '提醒' || value === 'reminder') return 'reminder';
  return 'info';
}

function normalizeAnnouncementTarget(value) {
  if (value === '仅管理员' || value === '管理员' || value === 'admins') return 'admins';
  if (value === '选定租户' || value === 'selected') return 'selected';
  return 'all';
}

function announcementFormPayload(formId, defaults = {}) {
  const form = document.querySelector(`[data-announcement-form="${formId}"]`);
  const field = (name, fallback = '') => {
    const value = form?.querySelector(`[name="${name}"]`)?.value;
    return String(value || fallback || '').trim();
  };
  return {
    title: field('title', defaults.title || '平台公告'),
    body: field('body', defaults.body || ''),
    severity: normalizeAnnouncementSeverity(field('severity', defaults.severity || 'info')),
    target: normalizeAnnouncementTarget(field('target', defaults.target || 'all')),
    expireAt: field('expireAt', defaults.expireAt || ''),
    status: defaults.status || 'draft',
  };
}

// ── Premium gradient palettes for Super KPIs ──────────────────────
const FEATURE_GRADIENTS = {
  rose:   'linear-gradient(135deg, #FFE4D6 0%, #FFC7E0 45%, #F5B8E2 100%)',
  azure:  'linear-gradient(135deg, #DAEEFF 0%, #C7E2FF 45%, #B7D6FF 100%)',
  mint:   'linear-gradient(135deg, #D8F5E8 0%, #C2EEDC 45%, #B0E0E2 100%)',
  violet: 'linear-gradient(135deg, #ECDBFF 0%, #D9C2FF 45%, #BDADFF 100%)',
  amber:  'linear-gradient(135deg, #FFEFC9 0%, #FFD9A8 45%, #FFB6A0 100%)',
};

// ── Super Dashboard Hero ─────────────────────────────────────────
function SuperHero() {
  const now = new Date();
  const weekday = ['周日','周一','周二','周三','周四','周五','周六'][now.getDay()];
  const dateStr = `${now.getMonth() + 1} 月 ${now.getDate()} 日 · ${weekday}`;
  const greeting = (() => {
    const h = now.getHours();
    if (h < 6)  return '夜深了';
    if (h < 11) return '早上好';
    if (h < 14) return '中午好';
    if (h < 18) return '下午好';
    return '晚上好';
  })();

  return (
    <div className="relative rounded-3xl overflow-hidden"
      style={{
        background: 'linear-gradient(125deg, #1E1B4B 0%, #4F46E5 38%, #7C3AED 68%, #A21CAF 100%)',
        boxShadow: '0 24px 60px -16px rgba(79,70,229,.42), 0 6px 20px -8px rgba(15,23,42,.12)',
      }}>
      {/* Decorative grid + orbs */}
      <svg className="absolute inset-0 opacity-[.12] pointer-events-none" width="100%" height="100%">
        <defs>
          <pattern id="grid-hero" width="32" height="32" patternUnits="userSpaceOnUse">
            <path d="M 32 0 L 0 0 0 32" fill="none" stroke="#fff" strokeWidth=".7" />
          </pattern>
        </defs>
        <rect width="100%" height="100%" fill="url(#grid-hero)" />
      </svg>
      <div className="absolute -right-24 -top-24 w-[420px] h-[420px] rounded-full pointer-events-none opacity-50"
        style={{ background: 'radial-gradient(circle, rgba(236,72,153,.55), transparent 60%)' }} />
      <div className="absolute -right-10 -bottom-32 w-[360px] h-[360px] rounded-full pointer-events-none opacity-40"
        style={{ background: 'radial-gradient(circle, rgba(34,211,238,.4), transparent 60%)' }} />

      {/* Concentric rings on right */}
      <svg className="absolute right-12 top-10 opacity-30 pointer-events-none" width="280" height="280" viewBox="0 0 280 280">
        <g stroke="#fff" strokeWidth="0.8" fill="none">
          <circle cx="140" cy="140" r="50" />
          <circle cx="140" cy="140" r="80" strokeDasharray="3 6" />
          <circle cx="140" cy="140" r="115" />
          <circle cx="140" cy="140" r="150" strokeDasharray="1 5" />
        </g>
        <circle cx="190" cy="140" r="3.5" fill="#fff" />
        <circle cx="140" cy="60" r="2.5" fill="#fff" opacity=".8" />
      </svg>

      <div className="relative p-8 grid grid-cols-[1fr_360px] gap-8 items-center">
        <div>
          <div className="inline-flex items-center gap-2 px-2.5 py-1 rounded-full mb-5"
            style={{ background: 'rgba(255,255,255,.14)', border: '1px solid rgba(255,255,255,.22)', backdropFilter: 'blur(8px)' }}>
            <span className="w-1.5 h-1.5 rounded-full" style={{ background: '#22D3EE', boxShadow: '0 0 8px #22D3EE' }} />
            <span className="font-mono text-[10.5px] uppercase tracking-[.22em] text-white/90">
              JKXT PLATFORM · SUPER CONTROL
            </span>
          </div>
          <div className="flex items-baseline gap-3 flex-wrap">
            <h1 className="text-[38px] font-bold tracking-tight text-white" style={{ letterSpacing: '-0.02em' }}>
              {greeting},超级管理员
            </h1>
            <span className="font-mono text-[13px] text-white/60">{dateStr}</span>
          </div>
          <p className="text-[13.5px] text-white/75 mt-2 leading-relaxed">
            7 所学校 · 142 名教职工 · 平台运行 <span className="font-mono font-semibold text-white">286</span> 天 ·
            上次登录 <span className="font-mono text-white/85">124.65.xxx.xxx</span>
          </p>
          <div className="mt-6 flex items-center gap-3 flex-wrap">
            <button onClick={() => window.navigateTo('super-tenants')}
              className="inline-flex items-center gap-2 h-10 px-5 rounded-xl font-semibold text-[13.5px] transition-all"
              style={{
                background: '#fff', color: '#4F46E5',
                boxShadow: '0 8px 20px -6px rgba(0,0,0,.32), inset 0 1px 0 rgba(255,255,255,.9)',
              }}
              onMouseEnter={(e) => { e.currentTarget.style.transform = 'translateY(-1px)'; }}
              onMouseLeave={(e) => { e.currentTarget.style.transform = 'translateY(0)'; }}>
              <IconBuilding size={15} strokeWidth={2.2} />
              查看租户管理
            </button>
            <button onClick={() => window.navigateTo('super-security')}
              className="inline-flex items-center gap-2 h-10 px-5 rounded-xl font-semibold text-[13.5px] transition-all"
              style={{
                background: 'rgba(255,255,255,.12)', color: '#fff',
                border: '1px solid rgba(255,255,255,.22)', backdropFilter: 'blur(8px)',
              }}
              onMouseEnter={(e) => { e.currentTarget.style.background = 'rgba(255,255,255,.20)'; }}
              onMouseLeave={(e) => { e.currentTarget.style.background = 'rgba(255,255,255,.12)'; }}>
              <span className="w-1.5 h-1.5 rounded-full" style={{ background: '#F87171', boxShadow: '0 0 8px #F87171' }} />
              <IconAlert size={14} strokeWidth={2.2} />
              处理 3 条安全告警
            </button>
          </div>
        </div>

        {/* Right: micro metric grid */}
        <div className="grid grid-cols-2 gap-2.5">
          <MetricChip icon={IconCheck}    label="UPTIME"   value="99.98%" />
          <MetricChip icon={IconBuilding} label="ACTIVE"   value="6 / 7" />
          <MetricChip icon={IconSparkle}  label="AI CALLS" value="142" />
          <MetricChip icon={IconUsers}    label="ONLINE"   value="3 / 7" />
        </div>
      </div>
    </div>
  );
}

// ── Super Dashboard ─────────────────────────────────────────────
function SuperDashboardPage() {
  const [logRange, setLogRange] = React.useState('近 24 小时');

  // platform health metrics (reference-style bars)
  const healthMetrics = [
    { label: '服务器负载',  value: 32, color: '#10B981', trend: 'down', sub: 'CPU' },
    { label: '内存占用',    value: 64, color: '#4F46E5', trend: 'up',   sub: '8.4 / 16 GB' },
    { label: '存储使用',    value: 48, color: '#06B6D4', trend: 'up',   sub: '240 / 500 GB' },
    { label: '数据库连接',  value: 28, color: '#F59E0B', trend: 'up',   sub: '14 / 50' },
    { label: '今日 API 调用', value: 71, color: '#9333EA', trend: 'up', sub: '7.1 万 / 10 万' },
  ];

  return (
    <PageShell
      crumbs={['JKXT 平台', '超管控制台', '仪表盘']}
      title={<span className="inline-flex items-center gap-2.5">超管仪表盘 <Tag color="violet" dot>SUPER</Tag></span>}
      badge="服务正常 · 7 租户 · 7 账号"
      subtitle="跨租户视角的平台运营总览、近期操作日志与安全状态"
      actions={
        <>
          <Button icon={IconChart} onClick={() => {
            window.ui.toast('正在生成运营周报…', { kind: 'info', desc: '将在 30 秒后通过邮件发送至 admin@jkxt.cn' });
            setTimeout(() => window.ui.toast('运营周报已发送', { kind: 'success', desc: '7 租户 · 142 教职工 · 286 天 uptime' }), 2200);
          }}>生成周报</Button>
          <Button variant="primary" icon={IconPlus} onClick={openNewTenantModal}>注册新租户</Button>
        </>
      }
    >
      <SuperHero />

      {/* Feature KPI row — reference-inspired gradient cards */}
      <div className="grid grid-cols-4 gap-4">
        <FeatureKPI
          label="平台安全分"
          sublabel="SECURITY SCORE"
          value={SUPER_SEC.score}
          unit="/ 100"
          footnote="较上周 +2 分 · 良好"
          icon={IconCheck}
          accent="#059669"
          gradient={FEATURE_GRADIENTS.mint}
          orbColor="rgba(52,211,153,.4)"
          onClick={() => window.navigateTo('super-security')}
        />
        <FeatureKPI
          label="租户活跃率"
          sublabel="TENANT ACTIVITY"
          value="86"
          unit="%"
          footnote="6 / 7 所学校近 7 天活跃"
          icon={IconBuilding}
          accent="#7C3AED"
          gradient={FEATURE_GRADIENTS.violet}
          orbColor="rgba(167,139,250,.45)"
          onClick={() => window.navigateTo('super-tenants')}
        />
        <FeatureKPI
          label="2FA 覆盖率"
          sublabel="2FA COVERAGE"
          value="71"
          unit="%"
          footnote={`${SUPER_ACCOUNTS.filter(a=>a.twoFactor).length} / ${SUPER_ACCOUNTS.length} 管理员已启用`}
          icon={IconUsers}
          accent="#2563EB"
          gradient={FEATURE_GRADIENTS.azure}
          orbColor="rgba(96,165,250,.4)"
          onClick={() => window.navigateTo('super-accounts')}
        />
        <FeatureKPI
          label="安全告警"
          sublabel="THREAT ALERTS"
          value={SUPER_SEC.failedLogins}
          unit="次"
          footnote={`${SUPER_SEC.suspiciousIps} 个可疑 IP · 近 24h`}
          icon={IconAlert}
          accent="#DC2626"
          gradient={FEATURE_GRADIENTS.rose}
          orbColor="rgba(244,114,182,.42)"
          onClick={() => window.navigateTo('super-security')}
        />
      </div>

      {/* Main row: activity feed (left) + side rail (right) */}
      <div className="grid grid-cols-[1fr_360px] gap-5">
        <GlassPanel className="p-6">
          <div className="flex items-end justify-between mb-5">
            <div>
              <h3 className="text-[16px] font-bold tracking-tight" style={{ color: 'var(--text-strong)' }}>
                近期操作活动
              </h3>
              <div className="text-[12px] mt-0.5" style={{ color: 'var(--text-soft)' }}>
                跨租户审计流 · 实时更新
              </div>
            </div>
            <div className="flex items-center gap-2">
              <RangePill label="范围:" value={logRange}
                onClick={() => {
                  const opts = ['近 24 小时', '近 7 天', '近 30 天'];
                  setLogRange(opts[(opts.indexOf(logRange) + 1) % opts.length]);
                }} />
              <button onClick={() => window.navigateTo('super-audit')}
                className="text-[12px] font-medium px-2 hover:underline"
                style={{ color: 'var(--brand)' }}>
                全部日志 →
              </button>
            </div>
          </div>

          <div>
            {SUPER_AUDIT.slice(0, 6).map((l, i) => {
              const [date, time] = (l.at || '').split(' ');
              return (
                <ActivityRow
                  key={l.id}
                  when={i < 3 ? '今天' : '昨天'}
                  whenSub={time || date}
                  title={l.action}
                  meta={`${l.actor} · ${l.tenant} · IP ${l.ip}`}
                  icon={l.severity === 'high' ? IconAlert : l.severity === 'warn' ? IconBell : IconList}
                  severity={l.severity}
                  onClick={() => window.navigateTo('super-audit')}
                />
              );
            })}
          </div>
        </GlassPanel>

        <div className="space-y-5">
          {/* Security gauge */}
          <GlassPanel className="p-5">
            <div className="flex items-end justify-between mb-3">
              <div>
                <h3 className="text-[14.5px] font-bold" style={{ color: 'var(--text-strong)' }}>平台安全分</h3>
                <div className="text-[11px] mt-0.5" style={{ color: 'var(--text-soft)' }}>实时综合评估</div>
              </div>
              <Tag color="emerald" dot>良好</Tag>
            </div>
            <div className="flex items-center gap-4">
              <RadialGauge value={SUPER_SEC.score} sublabel="/ 100" color="#10B981" size={132} thickness={13} />
              <div className="flex-1 space-y-1.5 text-[11.5px]">
                {[
                  { l: '密码强度',     v: '良好',                          c: '#10B981' },
                  { l: '2FA 覆盖',    v: `${SUPER_ACCOUNTS.filter(a=>a.twoFactor).length}/${SUPER_ACCOUNTS.length}`, c: '#F59E0B' },
                  { l: '可疑 IP',     v: `${SUPER_SEC.suspiciousIps} 个`, c: '#EF4444' },
                  { l: '最近备份',    v: SUPER_SEC.lastBackup,            c: '#10B981' },
                ].map((r) => (
                  <div key={r.l} className="flex items-center justify-between py-1"
                    style={{ borderBottom: '1px dashed var(--border-soft)' }}>
                    <span style={{ color: 'var(--text-medium)' }}>{r.l}</span>
                    <span className="font-mono font-semibold" style={{ color: r.c }}>{r.v}</span>
                  </div>
                ))}
              </div>
            </div>
            <button
              onClick={() => window.navigateTo('super-security')}
              className="mt-4 w-full h-9 rounded-lg text-[12.5px] font-semibold transition-colors"
              style={{ background: 'var(--brand-soft)', color: 'var(--brand)' }}
              onMouseEnter={(e) => (e.currentTarget.style.background = '#E0E7FF')}
              onMouseLeave={(e) => (e.currentTarget.style.background = 'var(--brand-soft)')}
            >
              查看安全中心 →
            </button>
          </GlassPanel>

          {/* Platform card */}
          <div className="relative rounded-2xl p-5 overflow-hidden text-white"
            style={{
              background: 'linear-gradient(135deg, #0F172A 0%, #1E1B4B 50%, #4F46E5 100%)',
              boxShadow: '0 14px 36px -12px rgba(79,70,229,.42), inset 0 1px 0 rgba(255,255,255,.12)',
            }}>
            <svg className="absolute -right-6 -bottom-6 opacity-20 pointer-events-none" width="160" height="160" viewBox="0 0 160 160">
              <g stroke="#fff" strokeWidth="0.8" fill="none">
                <circle cx="80" cy="80" r="32" />
                <circle cx="80" cy="80" r="52" strokeDasharray="2 4" />
                <circle cx="80" cy="80" r="72" />
              </g>
            </svg>
            <div className="relative">
              <div className="flex items-center justify-between">
                <div>
                  <div className="text-[10px] uppercase font-mono tracking-[.2em] opacity-70">PLATFORM VERSION</div>
                  <div className="text-[24px] font-bold mt-1.5 tracking-tight" style={{ letterSpacing: '-0.02em' }}>
                    JKXT <span className="text-white/60">v</span>2.0.4
                  </div>
                </div>
                <span className="px-2 py-0.5 rounded-md font-mono text-[10px] font-semibold"
                  style={{ background: 'rgba(52,211,153,.22)', color: '#86EFAC', border: '1px solid rgba(52,211,153,.4)' }}>
                  ● LIVE
                </span>
              </div>
              <div className="grid grid-cols-2 gap-3 mt-4 pt-3" style={{ borderTop: '1px solid rgba(255,255,255,.12)' }}>
                <div>
                  <div className="font-mono text-[9.5px] uppercase tracking-[.18em] opacity-60">UPTIME</div>
                  <div className="num text-[18px] font-bold mt-0.5">286<span className="text-[11px] font-medium opacity-70 ml-1">天</span></div>
                </div>
                <div>
                  <div className="font-mono text-[9.5px] uppercase tracking-[.18em] opacity-60">INCIDENTS</div>
                  <div className="num text-[18px] font-bold mt-0.5">0<span className="text-[11px] font-medium opacity-70 ml-1">次</span></div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* Bottom row: platform health + tenant overview */}
      <div className="grid grid-cols-[420px_1fr] gap-5">
        <GlassPanel className="p-6">
          <div className="flex items-end justify-between mb-4">
            <div>
              <h3 className="text-[16px] font-bold tracking-tight" style={{ color: 'var(--text-strong)' }}>
                平台健康度
              </h3>
              <div className="text-[12px] mt-0.5" style={{ color: 'var(--text-soft)' }}>
                关键指标 · 实时
              </div>
            </div>
            <Tag color="emerald" dot>全部正常</Tag>
          </div>
          {healthMetrics.map((m, i) => (
            <div key={m.label} style={{ borderBottom: i < healthMetrics.length - 1 ? '1px solid var(--border-soft)' : 'none' }}>
              <HealthBar label={m.label} value={m.value} color={m.color} trend={m.trend} sub={m.sub} />
            </div>
          ))}
        </GlassPanel>

        <GlassPanel className="p-6">
          <div className="flex items-end justify-between mb-4">
            <div>
              <h3 className="text-[16px] font-bold tracking-tight" style={{ color: 'var(--text-strong)' }}>
                租户概览
              </h3>
              <div className="text-[12px] mt-0.5" style={{ color: 'var(--text-soft)' }}>
                7 所学校 · 按学生规模排序
              </div>
            </div>
            <button onClick={() => window.navigateTo('super-tenants')}
              className="text-[12px] font-medium px-2 hover:underline"
              style={{ color: 'var(--brand)' }}>管理租户 →</button>
          </div>
          <div className="grid grid-cols-2 gap-3">
            {SUPER_TENANTS.map((t) => {
              const s = t.status;
              const dot = s === 'active' ? '#10B981' : s === 'trial' ? '#F59E0B' : '#EF4444';
              const sLabel = s === 'active' ? '活跃' : s === 'trial' ? '试用' : '已暂停';
              return (
                <div key={t.id} className="flex items-center gap-3 p-3 rounded-xl transition-all cursor-pointer"
                  style={{ background: '#FAFBFC', border: '1px solid var(--border-soft)' }}
                  onMouseEnter={(e) => { e.currentTarget.style.background = '#fff'; e.currentTarget.style.borderColor = '#CBD5E1'; e.currentTarget.style.transform = 'translateY(-1px)'; e.currentTarget.style.boxShadow = '0 6px 16px -8px rgba(15,23,42,.12)'; }}
                  onMouseLeave={(e) => { e.currentTarget.style.background = '#FAFBFC'; e.currentTarget.style.borderColor = 'var(--border-soft)'; e.currentTarget.style.transform = 'translateY(0)'; e.currentTarget.style.boxShadow = 'none'; }}
                  onClick={() => openTenantDrawer(t)}>
                  <TenantBadge slug={t.slug} size={40} />
                  <div className="flex-1 min-w-0">
                    <div className="text-[13px] font-semibold truncate" style={{ color: 'var(--text-strong)' }}>{t.name}</div>
                    <div className="flex items-center gap-1.5 mt-0.5">
                      <span className="w-1.5 h-1.5 rounded-full" style={{ background: dot }} />
                      <span className="text-[10.5px]" style={{ color: 'var(--text-soft)' }}>
                        {sLabel} · {t.plan} · {t.students.toLocaleString()} 学生
                      </span>
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        </GlassPanel>
      </div>
    </PageShell>
  );
}

// ── 2. Tenants ────────────────────────────────────────────────────────

// Open the New Tenant registration wizard (5-step modal)
function openNewTenantModal() {
  window.ui.modal({
    title: '注册新租户',
    subtitle: '5 步引导:基础信息 → 套餐 → 管理员 → 域名 → 确认',
    size: 'lg',
    content: (
      <div className="space-y-4">
        {/* Step indicator */}
        <div className="flex items-center gap-2 px-1">
          {['基础信息', '套餐', '主管理员', '域名', '确认'].map((s, i) => (
            <React.Fragment key={s}>
              <div className={`flex items-center gap-1.5 text-[11.5px] ${i === 0 ? 'font-semibold' : ''}`}
                style={{ color: i === 0 ? 'var(--brand)' : 'var(--text-soft)' }}>
                <span className="w-5 h-5 rounded-full inline-flex items-center justify-center font-mono font-bold text-[10px]"
                  style={{ background: i === 0 ? 'var(--brand)' : '#F1F5F9', color: i === 0 ? '#fff' : 'var(--text-soft)' }}>
                  {i + 1}
                </span>
                {s}
              </div>
              {i < 4 && <span className="flex-1 h-px" style={{ background: 'var(--border-soft)' }} />}
            </React.Fragment>
          ))}
        </div>

        {/* Step 1 fields */}
        <div className="space-y-3 mt-4">
          {[
            { l: '学校名称 *',   ph: '例:天津市第二实验小学' },
            { l: '所在城市 *',   ph: '例:天津 · 河西区' },
            { l: '学段类型 *',   ph: '完全中学 / 初中 / 高中 / 小学', kind: 'select', opts: ['完全中学', '初中', '高中', '小学', '九年一贯制'] },
            { l: '预估学生数 *', ph: '例:1200', mono: true },
            { l: '联系人姓名 *', ph: '例:张明远' },
            { l: '联系电话 *',   ph: '13800138000', mono: true },
          ].map((f) => (
            <div key={f.l} className="grid grid-cols-[110px_1fr] gap-3 items-center">
              <label className="text-[12.5px] text-right" style={{ color: 'var(--text-medium)' }}>{f.l}</label>
              {f.kind === 'select' ? (
                <select className="h-10 px-3 rounded-lg text-[13px] outline-none"
                  style={{ background: '#F8FAFC', border: '1px solid var(--border-soft)' }}>
                  {f.opts.map(o => <option key={o}>{o}</option>)}
                </select>
              ) : (
                <input placeholder={f.ph}
                  className="h-10 px-3 rounded-lg text-[13px] outline-none"
                  style={{ background: '#F8FAFC', border: '1px solid var(--border-soft)', fontFamily: f.mono ? 'Geist Mono' : undefined }} />
              )}
            </div>
          ))}
        </div>

        <div className="text-[11.5px] mt-2 px-2" style={{ color: 'var(--text-soft)' }}>
          完成本步后,后续步骤将自动展开。所有信息可在租户管理页面修改。
        </div>
      </div>
    ),
    footer: (
      <>
        <Button onClick={() => window.ui.closeModal()}>取消</Button>
        <Button variant="primary" icon={IconChevron} onClick={() => { window.ui.closeModal(); window.ui.toast('已保存草稿 · 进入下一步', { kind: 'success', desc: '可在「租户管理 → 草稿」继续' }); }}>
          下一步:套餐选择
        </Button>
      </>
    ),
  });
}

// Tenant detail drawer — rich profile w/ usage, admins, billing, audit
function openTenantDrawer(t) {
  const sLabel = t.status === 'active' ? '活跃' : t.status === 'trial' ? '试用' : '已暂停';
  const sColor = t.status === 'active' ? 'emerald' : t.status === 'trial' ? 'amber' : 'rose';
  // synthetic stats derived from tenant
  const usage = Math.min(98, 40 + (t.students % 60));
  const apiCalls = Math.floor(t.students * 1.8);
  const lastSeen = t.status === 'active' ? '刚刚 · 在线' : t.status === 'trial' ? '今天 11:32' : '11-15 09:08';
  window.ui.drawer({
    title: `${t.name}`,
    subtitle: `${t.id} · ${t.city} · ${t.type} · ${t.plan}`,
    width: 580,
    content: (
      <div className="space-y-5">
        {/* Hero */}
        <div className="rounded-2xl p-4 flex items-center gap-3.5 relative overflow-hidden"
          style={{ background: 'linear-gradient(135deg, #FAF5FF 0%, #EEF2FF 100%)', border: '1px solid #E9D5FF' }}>
          <TenantBadge slug={t.slug} name={t.name} size={56} />
          <div className="flex-1 min-w-0">
            <div className="text-[16px] font-bold tracking-tight" style={{ color: 'var(--text-strong)' }}>{t.name}</div>
            <div className="text-[11.5px] mt-0.5 font-mono" style={{ color: 'var(--text-soft)' }}>
              {t.slug.toUpperCase()}.jkxt.cn
            </div>
            <div className="mt-2 inline-flex items-center gap-1.5 flex-wrap">
              <Tag color={sColor} dot>{sLabel}</Tag>
              <Tag color={t.plan === '企业版' ? 'violet' : t.plan === '专业版' ? 'cyan' : 'slate'}>{t.plan}</Tag>
              <span className="text-[10.5px] font-mono" style={{ color: 'var(--text-soft)' }}>· 注册于 {t.createdAt}</span>
            </div>
          </div>
        </div>

        {/* Usage stats */}
        <div>
          <div className="font-mono text-[10.5px] uppercase tracking-wider mb-3" style={{ color: 'var(--text-soft)' }}>
            使用概况
          </div>
          <div className="grid grid-cols-3 gap-3">
            {[
              { label: '管理员', value: t.admins, unit: '人', color: '#4F46E5' },
              { label: '学生',   value: t.students.toLocaleString(), unit: '人', color: '#10B981' },
              { label: '存储',   value: usage, unit: '%', color: '#F59E0B' },
            ].map((s) => (
              <div key={s.label} className="rounded-xl p-3.5"
                style={{ background: '#FAFBFC', border: '1px solid var(--border-soft)' }}>
                <div className="text-[10.5px] font-mono uppercase tracking-wider" style={{ color: 'var(--text-soft)' }}>{s.label}</div>
                <div className="num text-[22px] font-bold mt-1 tracking-tight" style={{ color: s.color }}>
                  {s.value}<span className="text-[12px] ml-1 font-medium" style={{ color: 'var(--text-medium)' }}>{s.unit}</span>
                </div>
              </div>
            ))}
          </div>
        </div>

        {/* Recent activity */}
        <div>
          <div className="font-mono text-[10.5px] uppercase tracking-wider mb-3 flex items-center justify-between" style={{ color: 'var(--text-soft)' }}>
            <span>最近活动</span>
            <button onClick={() => { window.ui.closeDrawer(); window.navigateTo('super-audit'); }}
              className="text-[10.5px] font-semibold hover:underline" style={{ color: 'var(--brand)' }}>查看全部审计 →</button>
          </div>
          <ul className="space-y-2">
            {[
              { at: '今天 14:32', what: '发布排班 · 高三 11 月月考', who: '王明远' },
              { at: '今天 09:15', what: '导出监考统计表',           who: '李慧君' },
              { at: '昨天 16:42', what: '修改密码',                 who: '王明远' },
            ].map((a, i) => (
              <li key={i} className="flex items-center gap-3 text-[12px] py-2"
                style={{ borderBottom: '1px dashed var(--border-soft)' }}>
                <span className="font-mono w-[80px] shrink-0" style={{ color: 'var(--text-soft)' }}>{a.at}</span>
                <span className="flex-1 font-medium" style={{ color: 'var(--text-strong)' }}>{a.what}</span>
                <span style={{ color: 'var(--text-medium)' }}>{a.who}</span>
              </li>
            ))}
          </ul>
        </div>

        {/* Sys info */}
        <div className="rounded-xl p-4" style={{ background: '#FAFBFC', border: '1px solid var(--border-soft)' }}>
          <div className="grid grid-cols-2 gap-3 text-[12px]">
            <div>
              <div className="text-[10.5px] font-mono uppercase tracking-wider" style={{ color: 'var(--text-soft)' }}>API 调用 · 本月</div>
              <div className="num font-semibold mt-0.5" style={{ color: 'var(--text-strong)' }}>{apiCalls.toLocaleString()}</div>
            </div>
            <div>
              <div className="text-[10.5px] font-mono uppercase tracking-wider" style={{ color: 'var(--text-soft)' }}>最近登录</div>
              <div className="font-mono mt-0.5" style={{ color: 'var(--text-strong)' }}>{lastSeen}</div>
            </div>
            <div>
              <div className="text-[10.5px] font-mono uppercase tracking-wider" style={{ color: 'var(--text-soft)' }}>套餐到期</div>
              <div className="font-mono mt-0.5" style={{ color: 'var(--text-strong)' }}>{t.status === 'trial' ? '2025-11-26 (6 天后)' : '2026-09-01'}</div>
            </div>
            <div>
              <div className="text-[10.5px] font-mono uppercase tracking-wider" style={{ color: 'var(--text-soft)' }}>主管理员</div>
              <div className="font-mono mt-0.5" style={{ color: 'var(--text-strong)' }}>{t.slug.toUpperCase()}-admin@{t.slug}.edu.cn</div>
            </div>
          </div>
        </div>
      </div>
    ),
    footer: (
      <>
        <Button onClick={() => window.ui.closeDrawer()}>关闭</Button>
        <Button icon={IconEdit} onClick={() => { window.ui.closeDrawer(); openTenantEditDrawer(t); }}>编辑资料</Button>
        <Button variant="primary" icon={IconList} onClick={() => { window.ui.closeDrawer(); window.navigateTo('super-audit'); window.ui.toast(`已筛选 ${t.name} 的审计记录`, { kind: 'info' }); }}>
          查看完整审计
        </Button>
      </>
    ),
  });
}

function openTenantEditDrawer(t) {
  window.ui.drawer({
    title: `编辑租户 · ${t.name}`,
    subtitle: `修改将记录至审计日志,影响该租户全部用户`,
    width: 540,
    content: (
      <div className="space-y-4">
        {[
          { l: '学校名称',       v: t.name,   mono: false },
          { l: '租户标识',       v: t.slug.toUpperCase(), mono: true, lock: true, hint: '创建后不可修改' },
          { l: '所在城市',       v: t.city,   mono: false },
          { l: '学段类型',       v: t.type,   mono: false, kind: 'select', options: ['完全中学', '初中', '高中', '小学'] },
          { l: '套餐',           v: t.plan,   mono: false, kind: 'select', options: ['企业版', '专业版', '标准版', '试用版'] },
          { l: '管理员上限',     v: t.admins, mono: true, kind: 'number' },
          { l: '学生上限',       v: t.students, mono: true, kind: 'number' },
        ].map((f) => (
          <div key={f.l} className="grid grid-cols-[100px_1fr] gap-3 items-center">
            <label className="text-[12.5px] text-right" style={{ color: 'var(--text-medium)' }}>{f.l}</label>
            {f.kind === 'select' ? (
              <select defaultValue={f.v} disabled={f.lock}
                className="h-10 px-3 rounded-lg text-[13px] outline-none"
                style={{ background: f.lock ? '#F1F5F9' : '#F8FAFC', border: '1px solid var(--border-soft)', color: 'var(--text-strong)' }}>
                {f.options.map(o => <option key={o}>{o}</option>)}
              </select>
            ) : (
              <input defaultValue={f.v} disabled={f.lock}
                className="h-10 px-3 rounded-lg text-[13px] outline-none"
                style={{ background: f.lock ? '#F1F5F9' : '#F8FAFC', border: '1px solid var(--border-soft)', color: 'var(--text-strong)', fontFamily: f.mono ? 'Geist Mono' : undefined }} />
            )}
          </div>
        ))}
        <div className="rounded-xl p-3.5" style={{ background: '#FEF3C7', border: '1px solid #FCD34D' }}>
          <div className="text-[12px] font-semibold flex items-center gap-1.5" style={{ color: '#92400E' }}>
            <IconAlert size={12} strokeWidth={2.25} />
            修改套餐或上限将影响计费,确认前请核对合同
          </div>
        </div>
      </div>
    ),
    footer: (
      <>
        <Button onClick={() => window.ui.closeDrawer()}>取消</Button>
        <Button variant="primary" icon={IconCheck} onClick={() => { window.ui.closeDrawer(); window.ui.toast(`${t.name} · 资料已更新`, { kind: 'success', desc: '修改已记录在审计日志' }); }}>
          保存修改
        </Button>
      </>
    ),
  });
}

function SuperTenantsPage() {
  const [tenants, reloadTenants] = useSuperResource('/admin/tenants', SUPER_TENANTS);
  const changeTenantStatus = async (r, action) => {
    const tenantId = r.uuid || r.id;
    if (!tenantId) {
      window.ui.toast('租户缺少后端 ID', { kind: 'warning' });
      return;
    }
    await superPost(`/admin/tenants/${tenantId}/${action}`, {}, reloadTenants, action === 'suspend' ? `${r.name} 已暂停` : `${r.name} 已恢复`);
  };
  const cols = [
    { key: 'name', label: '租户名称', width: 220,
      render: (r) => (
        <div className="inline-flex items-center gap-2.5">
          <TenantBadge slug={r.slug} name={r.name} size={36} />
          <div>
            <div className="font-semibold" style={{ color: 'var(--text-strong)' }}>{r.name}</div>
            <div className="text-[11px] mt-0.5" style={{ color: 'var(--text-soft)' }}>{r.city}</div>
          </div>
        </div>
      ),
    },
    { key: 'type',     label: '类型', width: 100, render: (r) => <Tag color="indigo">{r.type}</Tag> },
    { key: 'plan',     label: '套餐', width: 100,
      render: (r) => <Tag color={r.plan === '企业版' ? 'violet' : r.plan === '专业版' ? 'cyan' : 'slate'}>{r.plan}</Tag>,
    },
    { key: 'admins',   label: '管理员', width: 80,  align: 'center', mono: true, render: (r) => <span>{r.admins} 人</span> },
    { key: 'students', label: '学生数', width: 100, align: 'center', mono: true, render: (r) => <span>{r.students.toLocaleString()}</span> },
    { key: 'createdAt',label: '注册时间', width: 130, mono: true,
      render: (r) => <span className="text-[12px]" style={{ color: 'var(--text-soft)' }}>{r.createdAt}</span> },
    { key: 'status',   label: '状态', width: 90,
      render: (r) => r.status === 'active' ? <Tag color="emerald" dot>活跃</Tag> :
                     r.status === 'trial'  ? <Tag color="amber" dot>试用</Tag> :
                                              <Tag color="rose" dot>已暂停</Tag>,
    },
    { key: 'actions',  label: '操作', width: 140, align: 'right',
      render: (r) => (
        <div className="inline-flex items-center gap-1" onClick={(e) => e.stopPropagation()}>
          <button className="text-[12px] font-medium px-1.5 hover:underline" style={{ color: 'var(--brand)' }}
            onClick={() => openTenantDrawer(r)}>查看</button>
          <span className="text-slate-300">·</span>
          <button className="text-[12px] font-medium px-1.5 hover:underline" style={{ color: 'var(--text-medium)' }}
            onClick={() => openTenantEditDrawer(r)}>编辑</button>
          {r.status !== 'suspended' && (
            <button className="text-[12px] font-medium px-1.5 hover:underline" style={{ color: '#DC2626' }}
              onClick={async () => {
                const ok = await window.ui.confirm({ title: `暂停 ${r.name}?`, desc: '该租户的所有管理员将无法登录,数据保留 90 天后可恢复', danger: true, confirmText: '暂停租户' });
                if (ok) await changeTenantStatus(r, 'suspend');
              }}>暂停</button>
          )}
          {r.status === 'suspended' && (
            <button className="text-[12px] font-medium px-1.5 hover:underline" style={{ color: '#059669' }}
              onClick={async () => {
                const ok = await window.ui.confirm({ title: `恢复 ${r.name}?`, desc: '该租户的管理员将立即可以登录', confirmText: '恢复访问' });
                if (ok) await changeTenantStatus(r, 'restore');
              }}>恢复</button>
          )}
        </div>
      ),
    },
  ];

  const totalStudents = tenants.reduce((a,b)=>a+Number(b.students || 0),0);
  return (
    <PageShell
      crumbs={['JKXT 平台', '超管控制台', '租户管理']}
      title="租户(单位)管理"
      badge={`${tenants.length} 所学校`}
      subtitle="跨租户管理学校档案、套餐、管理员配额"
      actions={
        <>
          <Button icon={IconUpload} onClick={() => window.ui.modal({
            title: '批量导入租户',
            subtitle: '上传 CSV 文件,系统将创建批量草稿,可后续编辑',
            size: 'md',
            content: (
              <div className="space-y-3">
                <div className="rounded-xl p-6 flex flex-col items-center text-center"
                  style={{ background: '#F8FAFC', border: '1.5px dashed #CBD5E1' }}>
                  <div className="w-12 h-12 rounded-lg flex items-center justify-center mb-3 bg-white"
                    style={{ boxShadow: 'var(--elevation-1)', color: 'var(--brand)' }}>
                    <IconUpload size={20} strokeWidth={2} />
                  </div>
                  <div className="text-[13px] font-semibold" style={{ color: 'var(--text-strong)' }}>点击或拖拽上传 CSV</div>
                  <div className="text-[11px] mt-1 font-mono" style={{ color: 'var(--text-soft)' }}>UTF-8 · ≤ 2 MB · 首行为表头</div>
                </div>
                <div className="text-[11.5px] flex items-center justify-between p-3 rounded-lg" style={{ background: '#FAF5FF', border: '1px solid #E9D5FF' }}>
                  <span style={{ color: 'var(--text-medium)' }}>需要 CSV 模板?</span>
                  <button className="font-semibold hover:underline" style={{ color: '#7C3AED' }}
                    onClick={() => window.ui.toast('正在下载模板 tenants_template.csv', { kind: 'info' })}>下载模板 →</button>
                </div>
              </div>
            ),
            footer: <><Button onClick={() => window.ui.closeModal()}>关闭</Button></>,
          })}>批量导入</Button>
          <Button variant="primary" icon={IconPlus} onClick={openNewTenantModal}>注册新租户</Button>
        </>
      }
    >
      <div className="grid grid-cols-4 gap-4">
        <FeatureKPI
          label="活跃租户" sublabel="ACTIVE"
          value={tenants.filter(t => t.status === 'active').length} unit={`/ ${tenants.length}`}
          footnote="近 7 天有登录记录"
          icon={IconCheck} accent="#059669"
          gradient={FEATURE_GRADIENTS.mint} orbColor="rgba(52,211,153,.4)"
        />
        <FeatureKPI
          label="试用中" sublabel="ON TRIAL"
          value={tenants.filter(t => t.status === 'trial').length} unit="所"
          footnote="平均剩余 18 天"
          icon={IconClock} accent="#D97706"
          gradient={FEATURE_GRADIENTS.amber} orbColor="rgba(251,191,36,.42)"
        />
        <FeatureKPI
          label="已暂停" sublabel="SUSPENDED"
          value={tenants.filter(t => t.status === 'suspended').length} unit="所"
          footnote="需联系恢复"
          icon={IconAlert} accent="#DC2626"
          gradient={FEATURE_GRADIENTS.rose} orbColor="rgba(244,114,182,.42)"
        />
        <FeatureKPI
          label="累计学生" sublabel="TOTAL STUDENTS"
          value={(totalStudents/1000).toFixed(1)} unit="K"
          footnote={`${totalStudents.toLocaleString()} 人 · 较上月 +9.2%`}
          icon={IconUsers} accent="#4F46E5"
          gradient={FEATURE_GRADIENTS.violet} orbColor="rgba(167,139,250,.42)"
        />
      </div>

      <FilterBar
        search="搜索学校名称、域名..."
        filters={[
          { label: '类型:', value: '全部', options: ['全部', '公立', '民办', '国际学校'] },
          { label: '套餐:', value: '全部', options: ['全部', '基础版', '专业版', '旗舰版'] },
          { label: '城市:', value: '全部', options: ['全部', '天津', '北京', '上海', '广州', '深圳'] },
          { label: '状态:', value: '全部', options: ['全部', '正常', '试用', '欠费', '停用'] },
        ]}
        total={tenants.length}
      />

      <DataTable columns={cols} rows={tenants} selectable onRowClick={(r) => openTenantDrawer(r)} />
    </PageShell>
  );
}

// ── 3. Accounts ───────────────────────────────────────────────────────
const SUPER_FIELD_STYLE = {
  height: 40, padding: '0 12px', borderRadius: 8, fontSize: 13, outline: 'none',
  background: '#F8FAFC', border: '1px solid var(--border-soft)', width: '100%',
};

// 新增管理员账号 — 真实写入后端 (POST /api/v1/admin/accounts，SUPER_ADMIN)
function SuperAccountFormModal({ onSaved }) {
  const [form, setForm] = React.useState({ name: '', email: '', phone: '', tenantId: '', role: 'admin' });
  const [tenants, setTenants] = React.useState([]);
  const [saving, setSaving] = React.useState(false);
  const [err, setErr] = React.useState('');

  React.useEffect(() => {
    let alive = true;
    window.api.get('/admin/tenants')
      .then((rows) => {
        if (!alive) return;
        const list = Array.isArray(rows) ? rows : [];
        setTenants(list);
        setForm((prev) => ({ ...prev, tenantId: list[0]?.uuid || '' }));
      })
      .catch(() => {});
    return () => { alive = false; };
  }, []);

  const bind = (key) => (e) => setForm((prev) => ({ ...prev, [key]: e.target.value }));

  const submit = async () => {
    if (saving) return;
    if (!form.name.trim() || !form.email.trim()) { setErr('姓名和邮箱为必填项'); return; }
    setSaving(true); setErr('');
    try {
      await window.api.post('/admin/accounts', {
        name: form.name.trim(), email: form.email.trim(), phone: form.phone.trim() || null,
        tenantId: form.tenantId || null, role: form.role,
      });
      window.ui.closeModal();
      window.ui.toast('账号已创建', { kind: 'success', desc: '已生成临时密码,首次登录需修改 · 审计已记录' });
      if (onSaved) onSaved();
    } catch (e) {
      setErr(e.message || '创建失败');
      setSaving(false);
    }
  };

  return (
    <div className="space-y-3">
      <div className="grid grid-cols-[100px_1fr] gap-3 items-center">
        <label className="text-[12.5px] text-right" style={{ color: 'var(--text-medium)' }}>姓名 *</label>
        <input style={SUPER_FIELD_STYLE} value={form.name} onChange={bind('name')} placeholder="例:张明远" />
      </div>
      <div className="grid grid-cols-[100px_1fr] gap-3 items-center">
        <label className="text-[12.5px] text-right" style={{ color: 'var(--text-medium)' }}>邮箱 *</label>
        <input style={SUPER_FIELD_STYLE} value={form.email} onChange={bind('email')} placeholder="name@school.edu.cn" />
      </div>
      <div className="grid grid-cols-[100px_1fr] gap-3 items-center">
        <label className="text-[12.5px] text-right" style={{ color: 'var(--text-medium)' }}>手机号</label>
        <input style={SUPER_FIELD_STYLE} value={form.phone} onChange={bind('phone')} placeholder="13800138000" />
      </div>
      <div className="grid grid-cols-[100px_1fr] gap-3 items-center">
        <label className="text-[12.5px] text-right" style={{ color: 'var(--text-medium)' }}>所属租户</label>
        <select style={SUPER_FIELD_STYLE} value={form.tenantId} onChange={bind('tenantId')}>
          {tenants.length === 0 && <option value="">（默认当前租户）</option>}
          {tenants.map((t) => <option key={t.uuid} value={t.uuid}>{t.name || t.slug || t.uuid}</option>)}
        </select>
      </div>
      <div className="grid grid-cols-[100px_1fr] gap-3 items-center">
        <label className="text-[12.5px] text-right" style={{ color: 'var(--text-medium)' }}>角色 *</label>
        <select style={SUPER_FIELD_STYLE} value={form.role} onChange={bind('role')}>
          <option value="admin">本校管理员 (admin)</option>
          <option value="viewer">只读 (viewer)</option>
          <option value="super">超级管理员 (super)</option>
        </select>
      </div>
      <div className="rounded-lg p-3 text-[11.5px]" style={{ background: '#EEF2FF', border: '1px solid #C7D2FE', color: 'var(--text-medium)' }}>
        系统将自动生成临时密码,首次登录需修改并配置 2FA。
      </div>
      {err && <div className="rounded-lg px-3 py-2 text-[12px]" style={{ background: '#FEF2F2', color: '#B91C1C', border: '1px solid #FECACA' }}>{err}</div>}
      <div className="flex items-center justify-end gap-2 pt-1">
        <Button onClick={() => window.ui.closeModal()}>取消</Button>
        <Button variant="primary" icon={IconCheck} disabled={saving} onClick={submit}>{saving ? '创建中…' : '创建账号'}</Button>
      </div>
    </div>
  );
}

function SuperAccountsPage() {
  const [accounts, reloadAccounts] = useSuperResource('/admin/accounts', SUPER_ACCOUNTS);
  const accountAction = async (acc, action, label) => {
    const accountId = acc.uuid || acc.id;
    if (!accountId) {
      window.ui.toast('账号缺少后端 ID', { kind: 'warning' });
      return;
    }
    await superPost(`/admin/accounts/${accountId}/${action}`, {}, reloadAccounts, label);
  };
  const cols = [
    { key: 'id', label: '账号 ID', width: 90, mono: true,
      render: (r) => <span className="font-mono text-[12px]" style={{ color: 'var(--text-soft)' }}>{r.id}</span> },
    { key: 'name', label: '姓名', width: 150,
      render: (r) => (
        <div className="inline-flex items-center gap-2">
          <div className="relative">
            <AvatarChip name={r.name} size={28} />
            <span className="absolute -bottom-0 -right-0 w-2.5 h-2.5 rounded-full border-2 border-white"
              style={{ background: r.status === 'online' ? '#10B981' : '#94A3B8' }} />
          </div>
          <div>
            <div className="font-medium" style={{ color: 'var(--text-strong)' }}>{r.name}</div>
            <div className="text-[11px] mt-0.5 font-mono" style={{ color: 'var(--text-soft)' }}>{r.email}</div>
          </div>
        </div>
      ),
    },
    { key: 'tenant',     label: '所属租户', width: 100, render: (r) => <Tag color="violet">{r.tenant}</Tag> },
    { key: 'department', label: '部门',     width: 100,
      render: (r) => <span className="text-[12.5px]" style={{ color: 'var(--text-medium)' }}>{r.department}</span> },
    { key: 'role',       label: '角色',     width: 100,
      render: (r) => {
        const c = { super: 'rose', admin: 'indigo', viewer: 'slate' }[r.role];
        const l = { super: '超管', admin: '管理员', viewer: '只读' }[r.role];
        return <Tag color={c} dot>{l}</Tag>;
      },
    },
    { key: 'twoFactor', label: '二次验证', width: 100, align: 'center',
      render: (r) => r.twoFactor
        ? <Tag color="emerald" dot>已启用</Tag>
        : <Tag color="rose" dot>未启用</Tag>,
    },
    { key: 'lastLogin', label: '上次登录', width: 130, mono: true,
      render: (r) => <span className="text-[12px]" style={{ color: 'var(--text-soft)' }}>{r.lastLogin}</span> },
    { key: 'loginCount', label: '登录次数', width: 90, align: 'right', mono: true,
      render: (r) => <span className="font-semibold" style={{ color: 'var(--text-strong)' }}>{r.loginCount}</span> },
    { key: 'actions', label: '操作', width: 150, align: 'right',
      render: (r) => (
        <div className="inline-flex items-center gap-1" onClick={(e) => e.stopPropagation()}>
          <button className="text-[12px] font-medium px-1.5 hover:underline" style={{ color: 'var(--brand)' }}
            onClick={() => openCredentialDrawer(r)}>凭证管理</button>
          <span className="text-slate-300">·</span>
          <button className="text-[12px] font-medium px-1.5 hover:underline" style={{ color: 'var(--text-medium)' }}
            onClick={async () => {
              const ok = await window.ui.confirm({ title: `重置 ${r.name} 密码?`, desc: '系统将生成临时密码并发送至验证邮箱,管理员首次登录需修改', confirmText: '重置并发送' });
              if (ok) await accountAction(r, 'reset-password', `已发送临时密码至 ${r.email.replace(/^.*@/,'***@')}`);
            }}>重置密码</button>
        </div>
      ),
    },
  ];

  // 凭证管理抽屉:超管对单个管理员账号的完全控制
  const openCredentialDrawer = (acc) => {
    window.ui.drawer({
      title: `凭证管理 · ${acc.name}`,
      subtitle: `${acc.id} · ${acc.tenant} · ${acc.role === 'super' ? '超级管理员' : acc.role === 'admin' ? '本校管理员' : '只读账号'}`,
      width: 540,
      content: (
        <div className="space-y-5">
          {/* 账号档案 */}
          <div className="rounded-xl p-4 flex items-center gap-3"
            style={{ background: 'linear-gradient(135deg, #FAF5FF, #EEF2FF)', border: '1px solid #E9D5FF' }}>
            <div className="relative">
              <AvatarChip name={acc.name} size={52} />
              <span className="absolute -bottom-0.5 -right-0.5 w-3.5 h-3.5 rounded-full border-2 border-white"
                style={{ background: acc.status === 'online' ? '#10B981' : '#94A3B8' }} />
            </div>
            <div className="flex-1">
              <div className="text-[16px] font-semibold" style={{ color: 'var(--text-strong)' }}>{acc.name}</div>
              <div className="text-[11.5px] mt-0.5 font-mono" style={{ color: 'var(--text-soft)' }}>{acc.email}</div>
              <div className="mt-1.5 inline-flex items-center gap-1.5">
                <Tag color="violet" dot>{acc.tenant}</Tag>
                {acc.twoFactor ? <Tag color="emerald" dot>2FA 已启用</Tag> : <Tag color="rose" dot>2FA 未启用</Tag>}
              </div>
            </div>
          </div>

          {/* 凭证字段 — 超管可编辑 */}
          <div>
            <div className="font-mono text-[10.5px] uppercase tracking-wider mb-2.5" style={{ color: 'var(--text-soft)' }}>
              登录凭证 · 超管可编辑
            </div>
            <div className="space-y-3">
              {[
                { l: '登录账号', v: acc.email,   mono: true, lock: true, hint: '账号创建后不可修改' },
                { l: '当前密码', v: '••••••••••••', mono: true, lock: true, hint: '已加密存储 · 无法查看明文' },
                { l: '验证邮箱', v: acc.email.replace('@', '.recover@'), mono: true, hint: '用于找回密码与紧急通知' },
                { l: '手机号码', v: acc.id === 'A001' ? '138-****-1234' : acc.id === 'A002' ? '139-****-5678' : '136-****-9012', mono: true, hint: '用于短信二次验证' },
              ].map((f) => (
                <div key={f.l} className="grid grid-cols-[90px_1fr_auto] gap-3 items-center">
                  <label className="text-[12px] text-right" style={{ color: 'var(--text-medium)' }}>{f.l}</label>
                  <div className="flex items-center h-10 px-3 rounded-lg"
                    style={{ background: f.lock ? '#F1F5F9' : '#F8FAFC', border: '1px solid var(--border-soft)' }}>
                    <input defaultValue={f.v} disabled={f.lock}
                      className="flex-1 bg-transparent outline-none text-[13px]"
                      style={{ color: f.lock ? 'var(--text-medium)' : 'var(--text-strong)', fontFamily: f.mono ? 'Geist Mono' : undefined, cursor: f.lock ? 'not-allowed' : 'text' }} />
                    {f.lock && (
                      <span className="text-slate-400 ml-2" title="只读">
                        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><rect x="6" y="11" width="12" height="10" rx="2"/><path d="M8 11 V8 a4 4 0 0 1 8 0 V11"/></svg>
                      </span>
                    )}
                  </div>
                  <span className="text-[10.5px] w-[120px]" style={{ color: 'var(--text-soft)' }}>{f.hint}</span>
                </div>
              ))}
            </div>
          </div>

          {/* 安全设置 */}
          <div>
            <div className="font-mono text-[10.5px] uppercase tracking-wider mb-2.5" style={{ color: 'var(--text-soft)' }}>
              安全策略
            </div>
            {[
              { l: '强制启用 2FA', desc: '下次登录必须配置二次验证', on: acc.twoFactor },
              { l: '密码 90 天过期', desc: '到期自动要求修改密码',     on: true },
              { l: '允许跨地区登录', desc: '禁用后异地登录将被拒绝',   on: false },
              { l: '会话最长 8 小时', desc: '超时自动登出',            on: true },
            ].map((s) => (
              <div key={s.l} className="flex items-center justify-between py-2.5"
                style={{ borderBottom: '1px solid var(--border-soft)' }}>
                <div>
                  <div className="text-[12.5px] font-medium" style={{ color: 'var(--text-strong)' }}>{s.l}</div>
                  <div className="text-[10.5px] mt-0.5" style={{ color: 'var(--text-soft)' }}>{s.desc}</div>
                </div>
                <button className="relative w-9 h-5 rounded-full transition-colors"
                  style={{ background: s.on ? '#10B981' : '#CBD5E1' }}>
                  <span className="absolute top-0.5 w-4 h-4 rounded-full bg-white" style={{ left: s.on ? 18 : 2 }} />
                </button>
              </div>
            ))}
          </div>

          {/* 危险区 */}
          <div className="rounded-xl p-4" style={{ background: '#FEF2F2', border: '1px solid #FCA5A5' }}>
            <div className="text-[12.5px] font-semibold mb-2" style={{ color: '#991B1B' }}>危险操作</div>
            <div className="flex flex-wrap gap-2">
              <Button size="sm" danger
                onClick={async () => {
                  const ok = await window.ui.confirm({ title: `强制下线 ${acc.name}?`, desc: '该账号将立即失去会话,需重新登录', danger: true });
                  if (ok) await accountAction(acc, 'force-logout', `${acc.name} 已强制下线`);
                }}>强制下线</Button>
              <Button size="sm" danger
                onClick={async () => {
                  const ok = await window.ui.confirm({ title: `禁用 ${acc.name} 账号?`, desc: '账号将无法登录,可在 30 天内恢复', danger: true });
                  if (ok) await accountAction(acc, 'disable', `${acc.name} 账号已禁用`);
                }}>禁用账号</Button>
              <Button size="sm" danger
                onClick={async () => {
                  const ok = await window.ui.confirm({ title: `删除 ${acc.name}?`, desc: '该账号将被永久删除,无法恢复。所有关联数据将保留 30 天。', danger: true, confirmText: '确认删除' });
                  if (ok) await accountAction(acc, 'delete', `${acc.name} 已删除`);
                }}>永久删除</Button>
            </div>
          </div>
        </div>
      ),
      footer: (
        <>
          <Button onClick={() => window.ui.closeDrawer()}>关闭</Button>
          <Button icon={IconUpload} onClick={() => window.ui.toast('已发送临时密码至验证邮箱', { kind: 'success' })}>
            重置密码
          </Button>
          <Button variant="primary" icon={IconCheck} onClick={() => { window.ui.closeDrawer(); window.ui.toast('凭证已更新', { kind: 'success', desc: '修改已记录在审计日志' }); }}>
            保存修改
          </Button>
        </>
      ),
    });
  };

  return (
    <PageShell
      crumbs={['JKXT 平台', '超管控制台', '管理员账号']}
      title="管理员账号"
      badge={`${accounts.length} 个账号`}
      subtitle="跨租户管理所有管理员、查看登录状态、强制密码重置"
      actions={
        <>
          <Button icon={IconUpload} onClick={() => window.ui.toast('批量导入账号', { kind: 'info', desc: '请上传 .csv 文件 · 模板已发送至 admin@jkxt.cn' })}>批量导入</Button>
          <Button variant="primary" icon={IconPlus} onClick={() => window.ui.modal({
            title: '新增管理员账号',
            subtitle: '该账号将获得选定租户的管理员权限',
            size: 'md',
            content: <SuperAccountFormModal onSaved={reloadAccounts} />,
          })}>新增账号</Button>
        </>
      }
    >
      <div className="grid grid-cols-4 gap-4">
        <FeatureKPI
          label="在线账号" sublabel="ONLINE NOW"
          value={accounts.filter(a => a.status === 'online').length} unit="人"
          footnote="实时 · 含超管账号"
          icon={IconCheck} accent="#059669"
          gradient={FEATURE_GRADIENTS.mint} orbColor="rgba(52,211,153,.4)"
        />
        <FeatureKPI
          label="超级管理员" sublabel="SUPER ADMINS"
          value={accounts.filter(a => a.role === 'super').length} unit="人"
          footnote="平台级最高权限"
          icon={IconSettings} accent="#7C3AED"
          gradient={FEATURE_GRADIENTS.violet} orbColor="rgba(167,139,250,.4)"
        />
        <FeatureKPI
          label="未启用 2FA" sublabel="2FA RISK"
          value={accounts.filter(a => !a.twoFactor).length} unit="人"
          footnote="建议立即推送启用提醒"
          icon={IconAlert} accent="#DC2626"
          gradient={FEATURE_GRADIENTS.rose} orbColor="rgba(244,114,182,.42)"
        />
        <FeatureKPI
          label="本月活跃" sublabel="MONTHLY ACTIVE"
          value={Math.max(0, accounts.length - 1)} unit="人"
          footnote="较上月 +5.0%"
          icon={IconUsers} accent="#2563EB"
          gradient={FEATURE_GRADIENTS.azure} orbColor="rgba(96,165,250,.4)"
        />
      </div>

      <FilterBar
        search="搜索姓名、邮箱..."
        filters={[
          { label: '角色:', value: '全部', options: ['全部', '超级管理员', '运维', '客服', '只读'] },
          { label: '租户:', value: '全部', options: ['全部', '天津市实验中学', '北京一中', '上海中学'] },
          { label: '状态:', value: '全部', options: ['全部', '启用', '禁用'] },
          { label: '2FA:', value: '全部', options: ['全部', '已开启', '未开启'] },
        ]}
        total={accounts.length}
      />

      <DataTable columns={cols} rows={accounts} selectable />
    </PageShell>
  );
}

// ── 4. System Settings ────────────────────────────────────────────────
function SuperSystemSettingsPage() {
  const [switches, setSwitches] = React.useState({
    emailNotif: true,
    twoFactor: true,
    guestAccess: false,
    autoBackup: true,
    maintenance: false,
  });

  return (
    <PageShell
      crumbs={['JKXT 平台', '超管控制台', '系统设置']}
      title="系统设置"
      subtitle="平台级配置;修改将影响所有租户"
      actions={
        <>
          <Button onClick={() => window.ui.toast('已取消修改', { kind: 'info' })}>取消</Button>
          <Button variant="primary" icon={IconCheck} onClick={async () => {
            const ok = await window.ui.confirm({
              title: '保存系统设置?',
              desc: '修改将立即生效并影响所有 7 个租户,且自动记录在审计日志中。建议先在维护时段保存。',
              confirmText: '确认保存并广播',
            });
            if (ok) {
              window.ui.toast('系统设置已保存', { kind: 'success', desc: '配置已下发至所有租户 · 审计 L0010 已记录' });
            }
          }}>保存设置</Button>
        </>
      }
    >
      <div className="grid grid-cols-[1fr_360px] gap-5">
        {/* Basics */}
        <div className="bg-white rounded-2xl p-6" style={{ border: '1px solid var(--border-soft)', boxShadow: 'var(--elevation-1)' }}>
          <SectionTitle title="平台基础信息" sub="显示于所有租户的顶栏与导出文件" />
          {[
            { l: '系统名称',   v: '排监考系统 JKXT', mono: false },
            { l: '系统域名',   v: 'app.jkxt.cn', mono: true },
            { l: '联系邮箱',   v: 'support@jkxt.cn', mono: true },
            { l: '时区',       v: 'Asia/Shanghai (UTC+8)', mono: true },
            { l: '系统语言',   v: '中文简体 (zh-CN)' },
            { l: '默认学段',   v: '完全中学 K-12' },
          ].map((r) => (
            <div key={r.l} className="grid grid-cols-[120px_1fr] gap-4 py-3" style={{ borderBottom: '1px solid var(--border-soft)' }}>
              <label className="text-[13px] pt-2.5 text-right" style={{ color: 'var(--text-medium)' }}>{r.l}</label>
              <div className="flex items-center h-10 rounded-lg px-3" style={{ background: '#F8FAFC', border: '1px solid transparent' }}>
                <input defaultValue={r.v} className="flex-1 bg-transparent outline-none text-[13.5px]"
                  style={{ color: 'var(--text-strong)', fontFamily: r.mono ? 'Geist Mono' : undefined }} />
              </div>
            </div>
          ))}
          <div className="grid grid-cols-[120px_1fr] gap-4 py-3">
            <label className="text-[13px] pt-2.5 text-right" style={{ color: 'var(--text-medium)' }}>系统 Logo</label>
            <div className="rounded-xl p-4 flex flex-col items-center text-center" style={{ background: '#F8FAFC', border: '1.5px dashed #CBD5E1' }}>
              <div className="w-10 h-10 rounded-lg flex items-center justify-center mb-2 bg-white" style={{ boxShadow: 'var(--elevation-1)', color: 'var(--brand)' }}>
                <IconUpload size={16} strokeWidth={2} />
              </div>
              <div className="text-[12px]" style={{ color: 'var(--text-strong)' }}>点击或拖拽上传 Logo</div>
              <div className="text-[10.5px] mt-1" style={{ color: 'var(--text-soft)' }}>建议 .svg / .png · ≤ 200KB</div>
            </div>
          </div>
        </div>

        {/* Right rail: switches */}
        <div className="bg-white rounded-2xl p-5" style={{ border: '1px solid var(--border-soft)', boxShadow: 'var(--elevation-1)' }}>
          <SectionTitle title="全局开关" sub="影响所有租户的功能行为" />
          <ul className="space-y-3">
            {[
              { k: 'emailNotif',   label: '邮件通知',   desc: '考试发布、冲突等关键事件邮件通知',  color: '#4F46E5' },
              { k: 'twoFactor',    label: '强制二次验证', desc: '所有管理员登录必须二次验证(邮箱/短信)', color: '#10B981' },
              { k: 'guestAccess',  label: '访客只读访问', desc: '允许未注册用户查看公开考试时间表',   color: '#0EA5E9' },
              { k: 'autoBackup',   label: '自动备份',     desc: '每日 03:00 自动备份数据库到云端',     color: '#9333EA' },
              { k: 'maintenance',  label: '维护模式',     desc: '所有用户访问将看到维护提示页',       color: '#EF4444' },
            ].map((s) => (
              <li key={s.k} className="flex items-start gap-3 py-2.5" style={{ borderBottom: '1px solid var(--border-soft)' }}>
                <span className="w-8 h-8 rounded-lg inline-flex items-center justify-center shrink-0"
                  style={{ background: switches[s.k] ? s.color + '15' : '#F1F5F9', color: switches[s.k] ? s.color : 'var(--text-soft)' }}>
                  <IconSettings size={14} strokeWidth={2} />
                </span>
                <div className="flex-1 min-w-0">
                  <div className="text-[13px] font-semibold leading-tight" style={{ color: 'var(--text-strong)' }}>{s.label}</div>
                  <div className="text-[11px] mt-1 leading-relaxed" style={{ color: 'var(--text-soft)' }}>{s.desc}</div>
                </div>
                <button
                  onClick={() => { setSwitches({ ...switches, [s.k]: !switches[s.k] }); window.ui.toast(`${s.label}: ${!switches[s.k] ? '已启用' : '已关闭'}`, { kind: !switches[s.k] ? 'success' : 'info' }); }}
                  className="relative w-10 h-6 rounded-full transition-colors shrink-0 mt-0.5"
                  style={{ background: switches[s.k] ? s.color : '#CBD5E1' }}
                >
                  <span className="absolute top-0.5 w-5 h-5 rounded-full bg-white transition-all"
                    style={{ left: switches[s.k] ? 18 : 2, boxShadow: 'var(--elevation-1)' }} />
                </button>
              </li>
            ))}
          </ul>
        </div>
      </div>
    </PageShell>
  );
}

// ── 5. Audit ──────────────────────────────────────────────────────────
function SuperAuditPage() {
  const [auditRows] = useSuperResource('/admin/audit-logs', SUPER_AUDIT);
  const cols = [
    { key: 'id', label: '日志 ID', width: 90, mono: true,
      render: (r) => <span className="font-mono text-[11.5px]" style={{ color: 'var(--text-soft)' }}>{r.id}</span> },
    { key: 'at', label: '时间', width: 140, mono: true,
      render: (r) => <span className="font-mono text-[12px]" style={{ color: 'var(--text-medium)' }}>{r.at}</span> },
    { key: 'actor', label: '操作人', width: 130,
      render: (r) => (
        <div className="inline-flex items-center gap-1.5">
          <AvatarChip name={r.actor} size={22} />
          <span className="text-[12.5px]" style={{ color: 'var(--text-strong)' }}>{r.actor}</span>
        </div>
      ),
    },
    { key: 'tenant', label: '租户', width: 80, render: (r) => <Tag color="violet">{r.tenant}</Tag> },
    { key: 'action', label: '操作内容', wrap: true },
    { key: 'ip', label: 'IP', width: 130, mono: true,
      render: (r) => <span className="font-mono text-[11.5px]" style={{ color: 'var(--text-soft)' }}>{r.ip}</span> },
    { key: 'severity', label: '级别', width: 80,
      render: (r) => {
        const c = { info: 'sky', warn: 'amber', high: 'rose' }[r.severity];
        const l = { info: '常规', warn: '警告', high: '高危' }[r.severity];
        return <Tag color={c} dot>{l}</Tag>;
      },
    },
    { key: 'actions', label: '详情', width: 80, align: 'right',
      render: (r) => (
        <button className="text-[12px] font-medium hover:underline" style={{ color: 'var(--brand)' }}
          onClick={() => window.ui.drawer({
            title: '日志详情 · ' + r.id,
            subtitle: r.at,
            width: 480,
            content: (
              <div className="space-y-4">
                <div className="rounded-lg p-3" style={{ background: '#F8FAFC' }}>
                  <div className="font-mono text-[11px] uppercase tracking-wider mb-2" style={{ color: 'var(--text-soft)' }}>操作摘要</div>
                  <div className="text-[14px] font-semibold" style={{ color: 'var(--text-strong)' }}>{r.action}</div>
                  <div className="text-[12px] mt-1" style={{ color: 'var(--text-medium)' }}>目标对象 · <span className="font-mono">{r.target}</span></div>
                </div>
                <div className="rounded-lg p-3 font-mono text-[11.5px]" style={{ background: '#0F172A', color: '#CBD5E1' }}>
                  <pre style={{ whiteSpace: 'pre-wrap' }}>
{`{
  "id":       "${r.id}",
  "actor":    "${r.actor}",
  "tenant":   "${r.tenant}",
  "action":   "${r.action}",
  "target":   "${r.target}",
  "severity": "${r.severity}",
  "ip":       "${r.ip}",
  "userAgent":"Mozilla/5.0 (Macintosh)",
  "timestamp":"${r.at}"
}`}
                  </pre>
                </div>
              </div>
            ),
            footer: <Button onClick={() => window.ui.closeDrawer()}>关闭</Button>,
          })}>
          查看 →
        </button>
      ),
    },
  ];

  return (
    <PageShell
      crumbs={['JKXT 平台', '超管控制台', '审计日志']}
      title="审计日志"
      badge={`${auditRows.length} 条记录`}
      subtitle="全平台所有关键操作的审计记录,支持时间/级别/操作类型筛选"
      actions={<Button variant="primary" icon={IconUpload} onClick={() => window.ui.modal({
        title: '导出审计日志',
        subtitle: '选择导出范围、级别与格式',
        size: 'md',
        content: (
          <div className="space-y-3">
            {[
              { l: '时间范围', kind: 'select', opts: ['近 24 小时', '近 7 天', '近 30 天', '本月', '本学期', '自定义'] },
              { l: '级别',     kind: 'select', opts: ['全部', '仅高危', '高危 + 警告', '仅常规'] },
              { l: '租户',     kind: 'select', opts: ['全部租户', 'TSY', 'NK', 'YH', 'TG', 'BSD', 'WJX', 'WQ'] },
              { l: '格式',     kind: 'select', opts: ['CSV (.csv)', 'Excel (.xlsx)', 'JSON (.json)', 'PDF 报告 (.pdf)'] },
            ].map(f => (
              <div key={f.l} className="grid grid-cols-[80px_1fr] gap-3 items-center">
                <label className="text-[12.5px] text-right" style={{ color: 'var(--text-medium)' }}>{f.l}</label>
                <select className="h-10 px-3 rounded-lg text-[13px] outline-none"
                  style={{ background: '#F8FAFC', border: '1px solid var(--border-soft)' }}>
                  {f.opts.map(o => <option key={o}>{o}</option>)}
                </select>
              </div>
            ))}
            <div className="rounded-lg p-3 text-[11.5px] mt-2" style={{ background: '#FEF3C7', border: '1px solid #FCD34D', color: '#92400E' }}>
              ⚠ 导出操作本身会被记录在审计日志中
            </div>
          </div>
        ),
        footer: (
          <>
            <Button onClick={() => window.ui.closeModal()}>取消</Button>
            <Button variant="primary" icon={IconUpload} onClick={() => { window.ui.closeModal(); window.ui.toast('正在导出审计日志…', { kind: 'info', desc: '完成后将通过邮件发送下载链接' }); }}>
              立即导出
            </Button>
          </>
        ),
      })}>导出日志</Button>}
    >
      <div className="grid grid-cols-4 gap-4">
        <FeatureKPI
          label="今日记录" sublabel="TODAY"
          value={auditRows.filter(l => String(l.at || '').startsWith('今天') || String(l.at || '').includes('T')).length} unit="条"
          footnote="较昨日 +12 条"
          icon={IconList} accent="#2563EB"
          gradient={FEATURE_GRADIENTS.azure} orbColor="rgba(96,165,250,.4)"
        />
        <FeatureKPI
          label="高危事件" sublabel="HIGH SEVERITY"
          value={auditRows.filter(l => l.severity === 'high').length} unit="条"
          footnote="均已处理"
          icon={IconAlert} accent="#DC2626"
          gradient={FEATURE_GRADIENTS.rose} orbColor="rgba(244,114,182,.42)"
        />
        <FeatureKPI
          label="警告事件" sublabel="WARNINGS"
          value={auditRows.filter(l => l.severity === 'warn').length} unit="条"
          footnote="需关注"
          icon={IconBell} accent="#D97706"
          gradient={FEATURE_GRADIENTS.amber} orbColor="rgba(251,191,36,.42)"
        />
        <FeatureKPI
          label="活跃操作员" sublabel="ACTORS · 24H"
          value={new Set(auditRows.map(l => l.actor)).size} unit="人"
          footnote="跨 5 个租户"
          icon={IconUsers} accent="#7C3AED"
          gradient={FEATURE_GRADIENTS.violet} orbColor="rgba(167,139,250,.4)"
        />
      </div>

      <FilterBar
        search="搜索操作人、IP、目标..."
        filters={[
          { label: '时间:', value: '近 7 天', options: ['近 24 小时', '近 7 天', '近 30 天', '全部'] },
          { label: '级别:', value: '全部', options: ['全部', '信息', '警告', '严重'] },
          { label: '操作类型:', value: '全部', options: ['全部', '登录', '新建', '修改', '删除', '导出'] },
          { label: '租户:', value: '全部', options: ['全部', '天津市实验中学', '北京一中', '上海中学'] },
        ]}
        total={auditRows.length}
      />
      <DataTable columns={cols} rows={auditRows} />
    </PageShell>
  );
}

// ── 6. Security ───────────────────────────────────────────────────────
function SuperSecurityPage() {
  const [security, reloadSecurity] = useSuperObject('/admin/security/overview', SUPER_SEC);
  const threats = Array.isArray(security.threats) ? security.threats : SUPER_SEC.threats;
  const cols = [
    { key: 'ip',       label: 'IP 地址',   width: 150, mono: true,
      render: (r) => <span className="font-mono font-semibold" style={{ color: 'var(--text-strong)' }}>{r.ip}</span> },
    { key: 'country',  label: '来源地区', width: 110,
      render: (r) => <Tag color="slate">{r.country}</Tag> },
    { key: 'attempts', label: '尝试次数', width: 100, align: 'center', mono: true,
      render: (r) => <span className="num font-semibold" style={{ color: '#EF4444' }}>{r.attempts}</span> },
    { key: 'lastAt',   label: '最近时间', width: 130, mono: true,
      render: (r) => <span className="font-mono text-[12px]" style={{ color: 'var(--text-soft)' }}>{r.lastAt}</span> },
    { key: 'severity', label: '威胁等级', width: 100,
      render: (r) => {
        const c = { high: 'rose', med: 'amber', low: 'slate' }[r.severity];
        const l = { high: '高危', med: '中', low: '低' }[r.severity];
        return <Tag color={c} dot>{l}</Tag>;
      },
    },
    { key: 'actions',  label: '操作', width: 200, align: 'right',
      render: (r) => (
        <div className="inline-flex items-center gap-1" onClick={(e) => e.stopPropagation()}>
          <button className="text-[12px] font-medium px-1.5 hover:underline" style={{ color: 'var(--brand)' }}
            onClick={() => window.ui.drawer({
              title: `IP 详情 · ${r.ip}`,
              subtitle: `来源 ${r.country} · 威胁等级 ${r.severity === 'high' ? '高危' : r.severity === 'med' ? '中' : '低'}`,
              width: 460,
              content: (
                <div className="space-y-4">
                  <div className="rounded-xl p-4" style={{ background: '#FEF2F2', border: '1px solid #FCA5A5' }}>
                    <div className="font-mono text-[20px] font-bold tracking-tight" style={{ color: '#991B1B' }}>{r.ip}</div>
                    <div className="text-[11.5px] mt-1.5 grid grid-cols-2 gap-2">
                      <div><span style={{ color: 'var(--text-soft)' }}>失败次数:</span> <span className="num font-semibold" style={{ color: '#DC2626' }}>{r.attempts}</span></div>
                      <div><span style={{ color: 'var(--text-soft)' }}>最近活动:</span> <span className="font-mono" style={{ color: 'var(--text-strong)' }}>{r.lastAt}</span></div>
                    </div>
                  </div>
                  <div>
                    <div className="font-mono text-[10.5px] uppercase tracking-wider mb-2" style={{ color: 'var(--text-soft)' }}>尝试登录的目标账号</div>
                    <ul className="space-y-1.5">
                      {['admin@jkxt.cn', 'root@jkxt.cn', 'support@jkxt.cn'].map((u, i) => (
                        <li key={i} className="flex items-center justify-between py-1.5 text-[12px]"
                          style={{ borderBottom: '1px dashed var(--border-soft)' }}>
                          <span className="font-mono" style={{ color: 'var(--text-strong)' }}>{u}</span>
                          <Tag color="rose" dot>已拦截</Tag>
                        </li>
                      ))}
                    </ul>
                  </div>
                </div>
              ),
              footer: <Button onClick={() => window.ui.closeDrawer()}>关闭</Button>,
            })}>详情</button>
          <span className="text-slate-300">·</span>
          <button className="text-[12px] font-medium px-1.5 hover:underline" style={{ color: '#DC2626' }}
            onClick={async () => {
              const ok = await window.ui.confirm({
                title: `永久封禁 ${r.ip}?`,
                desc: '该 IP 将立即被加入防火墙黑名单,所有来源此 IP 的请求会被拒绝。操作记录在审计日志中。',
                danger: true, confirmText: '确认封禁',
              });
              if (ok) await superPost('/admin/security/ip-ban', { ip: r.ip, reason: 'security-center' }, reloadSecurity, `已封禁 IP ${r.ip}`);
            }}>立即封禁</button>
          <span className="text-slate-300">·</span>
          <button className="text-[12px] font-medium px-1.5 hover:underline" style={{ color: 'var(--text-medium)' }}
            onClick={async () => {
              const ok = await window.ui.confirm({
                title: `将 ${r.ip} 加入白名单?`,
                desc: '该 IP 的访问将不再触发安全告警 · 仅在确认是可信内部 IP 时使用',
                confirmText: '加入白名单',
              });
              if (ok) await superPost('/admin/security/ip-allowlist', { ip: r.ip, reason: 'security-center' }, reloadSecurity, `${r.ip} 已加入白名单`);
            }}>白名单</button>
        </div>
      ),
    },
  ];

  return (
    <PageShell
      crumbs={['JKXT 平台', '超管控制台', '安全中心']}
      title="安全中心"
      badge={`安全分 ${security.score}/100`}
      subtitle="实时监控登录尝试、异常 IP、备份状态;响应威胁告警"
      actions={<Button variant="primary" icon={IconUpload} onClick={async () => {
        const ok = await window.ui.confirm({
          title: '立即执行手动备份?',
          desc: '将快照全部 7 个租户的数据库 · 预计 2 分钟完成 · 备份将上传至云端并校验',
          confirmText: '开始备份',
        });
        if (ok) {
          superPost('/admin/backups/run', {}, reloadSecurity, '备份完成 · 已写入审计日志');
        }
      }}>立即备份</Button>}
    >
      {/* Hero strip: gauge + summary */}
      <div className="grid grid-cols-[280px_1fr] gap-5">
        <div className="relative rounded-3xl p-6 overflow-hidden flex flex-col items-center text-white"
          style={{
            background: 'linear-gradient(135deg, #047857 0%, #059669 45%, #10B981 100%)',
            boxShadow: '0 18px 48px -14px rgba(16,185,129,.42), inset 0 1px 0 rgba(255,255,255,.18)',
          }}>
          <svg className="absolute inset-0 opacity-15 pointer-events-none" width="100%" height="100%">
            <defs>
              <pattern id="grid-sec" width="22" height="22" patternUnits="userSpaceOnUse">
                <path d="M 22 0 L 0 0 0 22" fill="none" stroke="#fff" strokeWidth=".6" />
              </pattern>
            </defs>
            <rect width="100%" height="100%" fill="url(#grid-sec)" />
          </svg>
          <div className="relative">
            <div className="text-[10.5px] uppercase font-mono tracking-[.22em] text-center opacity-80 mb-3">PLATFORM SECURITY SCORE</div>
            <RadialGauge value={security.score} sublabel="/ 100"
              color="#FFFFFF" valueColor="#FFFFFF" size={160} thickness={14} />
            <div className="text-center mt-4">
              <Tag color="emerald" dot>等级:良好</Tag>
            </div>
          </div>
        </div>
        <GlassPanel className="p-6">
          <h3 className="text-[16px] font-bold mb-4 tracking-tight" style={{ color: 'var(--text-strong)' }}>安全维度评分</h3>
          {[
            { label: '密码策略强度',     value: 92, color: '#10B981', trend: 'up',   sub: '所有租户启用强密码' },
            { label: '2FA 覆盖率',       value: 71, color: '#F59E0B', trend: 'up',   sub: '后端账号策略已接入' },
            { label: '会话保护',         value: 88, color: '#06B6D4', trend: 'up',   sub: '8h 自动登出' },
            { label: '可疑 IP 拦截率',   value: 95, color: '#10B981', trend: 'up',   sub: '近 7 天 32 次拦截' },
            { label: '数据备份新鲜度',   value: 82, color: '#4F46E5', trend: 'up',   sub: security.lastBackup },
          ].map((m, i, arr) => (
            <div key={m.label} style={{ borderBottom: i < arr.length - 1 ? '1px solid var(--border-soft)' : 'none' }}>
              <HealthBar label={m.label} value={m.value} color={m.color} trend={m.trend} sub={m.sub} />
            </div>
          ))}
        </GlassPanel>
      </div>

      <div className="grid grid-cols-4 gap-4">
        <FeatureKPI
          label="失败登录" sublabel="FAILED LOGIN · 24H"
          value={security.failedLogins} unit="次"
          footnote="较前日 -28% · 趋势良好"
          icon={IconAlert} accent="#DC2626"
          gradient={FEATURE_GRADIENTS.rose} orbColor="rgba(244,114,182,.42)"
        />
        <FeatureKPI
          label="可疑 IP" sublabel="SUSPICIOUS"
          value={security.suspiciousIps} unit="个"
          footnote="待审核 · 1 个高危"
          icon={IconAlert} accent="#D97706"
          gradient={FEATURE_GRADIENTS.amber} orbColor="rgba(251,191,36,.42)"
        />
        <FeatureKPI
          label="今日拦截" sublabel="BLOCKED TODAY"
          value="32" unit="次"
          footnote="WAF · 规则引擎"
          icon={IconCheck} accent="#2563EB"
          gradient={FEATURE_GRADIENTS.azure} orbColor="rgba(96,165,250,.4)"
        />
        <FeatureKPI
          label="最近备份" sublabel="LAST BACKUP"
          value={security.lastBackup}
          footnote="自动 · 每日 03:00 · 4.2 GB"
          icon={IconArchive} accent="#7C3AED"
          gradient={FEATURE_GRADIENTS.violet} orbColor="rgba(167,139,250,.4)"
        />
      </div>

      <GlassPanel className="p-5">
        <SectionTitle title="可疑访问 IP" sub="近 7 天 · 按威胁等级排序" right={<Tag color="amber" dot>需要审核</Tag>} />
        <DataTable columns={cols} rows={threats} footer={<></>} />
      </GlassPanel>
    </PageShell>
  );
}

// ── 7. Announcements ──────────────────────────────────────────────────

// Announcement content templates by ID — so detail drawer has real body
const ANNOUNCEMENT_BODIES = {
  AN001: `# v2.0.4 系统升级 · 新增 K-12 完全中学排班模板

本次升级包含以下重点更新:

- **K-12 完全中学排班模板** — 自动识别初高中复合学段,生成跨学段冲突检测
- **AI 策略排班 · 性能提升 38%** — 大型任务(>200 场)平均出结果时间缩短至 12 秒
- **审计日志增强** — 新增导出为 PDF 报表、按租户筛选
- 已知问题修复 11 项 · 详见 changelog`,
  AN002: `# 12 月底前完成本学期归档

学期即将结束,请各校管理员在 **12 月 31 日前** 完成以下事项:

1. 关闭未完成的监考任务,或转为新学期草稿
2. 导出本学期完整统计报表(教师 / 班级 / 考场)
3. 在「信息设置」生成下学期初始排班骨架

新学期周期将于 2026-02-15 起算。`,
  AN003: `# 安全提醒 · 请所有教务管理员开启二次验证

近期检测到多个租户的管理员账号被尝试爆破登录。为保护数据安全,**强制要求所有管理员开启 2FA**:

- 邮箱验证 / 短信验证 / TOTP 应用三选一
- 启用后,异地登录将需要二次确认
- **截止 12 月 15 日**,未启用 2FA 的账号将被临时限制登录

请联系超管协助配置。`,
  AN004: `# 寒假期间系统维护通告

为保障平台稳定,寒假期间将进行以下维护:

- **2026-01-20 02:00 ~ 2026-02-05 06:00** 数据库迁移至新集群
- 期间系统每日 02:00-06:00 不可用 (4 小时)
- 学校历史报表可正常查看
- 维护期间不影响数据完整性`,
};

function openAnnouncementDrawer(a, sev, actions = {}) {
  const body = a.body || ANNOUNCEMENT_BODIES[a.id] || a.title;
  // mock per-tenant reading stats
  const tenantReads = [
    { tenant: 'TSY 天津市实验中学', total: 32, read: 28 },
    { tenant: 'NK 天津南开中学',    total: 38, read: 34 },
    { tenant: 'YH 天津耀华小学',    total: 14, read: 12 },
    { tenant: 'TG 天津大学附属中学', total: 24, read: 22 },
    { tenant: 'BSD 天津宝坻一中',   total: 22, read: 18 },
    { tenant: 'WJX 天津外国语小学', total: 8,  read: 4  },
    { tenant: 'WQ 天津武清外国语',  total: 4,  read: 0  },
  ];
  window.ui.drawer({
    title: a.title,
    subtitle: `${a.id} · ${announcementStatusLabel(a.status)} · 目标 ${announcementTargetLabel(a.target)}`,
    width: 560,
    content: (
      <div className="space-y-5">
        {/* Header strip */}
        <div className="rounded-2xl p-4 relative overflow-hidden"
          style={{ background: sev.gradient, border: `1px solid ${sev.accent}33` }}>
          <div className="flex items-center gap-2 flex-wrap">
            <Tag color={sev.color} dot>{sev.label}</Tag>
            <Tag color={announcementStatusColor(a.status)} dot>{announcementStatusLabel(a.status)}</Tag>
            <span className="font-mono text-[10.5px]" style={{ color: sev.accent }}>{a.id}</span>
          </div>
          <div className="mt-2 grid grid-cols-2 gap-3 text-[11.5px]">
            <div>
              <div className="font-mono text-[10px] uppercase tracking-wider" style={{ color: sev.accent }}>发布时间</div>
              <div className="font-mono mt-0.5 font-semibold" style={{ color: 'var(--text-strong)' }}>{a.publishAt}</div>
            </div>
            <div>
              <div className="font-mono text-[10px] uppercase tracking-wider" style={{ color: sev.accent }}>失效时间</div>
              <div className="font-mono mt-0.5 font-semibold" style={{ color: 'var(--text-strong)' }}>{a.expireAt}</div>
            </div>
          </div>
        </div>

        {/* Body */}
        <div>
          <div className="font-mono text-[10.5px] uppercase tracking-wider mb-2" style={{ color: 'var(--text-soft)' }}>公告正文 · Markdown</div>
          <div className="rounded-xl p-4 text-[13px] leading-relaxed whitespace-pre-wrap"
            style={{ background: '#FAFBFC', border: '1px solid var(--border-soft)', color: 'var(--text-strong)', fontFamily: 'Geist Mono, monospace' }}>
            {body}
          </div>
        </div>

        {/* Per-tenant reading stats */}
        {a.status === 'published' && (
          <div>
            <div className="flex items-end justify-between mb-2">
              <div className="font-mono text-[10.5px] uppercase tracking-wider" style={{ color: 'var(--text-soft)' }}>各租户阅读情况</div>
              <span className="font-mono text-[11px]" style={{ color: 'var(--text-medium)' }}>
                总计 <span className="num font-bold" style={{ color: sev.accent }}>{a.readBy}</span> 人次
              </span>
            </div>
            <ul className="space-y-1.5">
              {tenantReads.map((t, i) => {
                const pct = t.total > 0 ? Math.round((t.read / t.total) * 100) : 0;
                return (
                  <li key={i} className="flex items-center gap-2 text-[12px] py-1.5"
                    style={{ borderBottom: '1px dashed var(--border-soft)' }}>
                    <span className="flex-1 font-medium truncate" style={{ color: 'var(--text-medium)' }}>{t.tenant}</span>
                    <div className="w-24 h-1.5 rounded-full overflow-hidden" style={{ background: '#F1F5F9' }}>
                      <div className="h-full rounded-full" style={{ width: `${pct}%`, background: sev.accent }} />
                    </div>
                    <span className="font-mono text-[11px] w-16 text-right" style={{ color: 'var(--text-strong)' }}>
                      {t.read}/{t.total}
                    </span>
                  </li>
                );
              })}
            </ul>
          </div>
        )}
      </div>
    ),
    footer: (
      <>
        <Button onClick={() => window.ui.closeDrawer()}>关闭</Button>
        <Button icon={IconEdit} onClick={() => { window.ui.closeDrawer(); openAnnouncementEditDrawer(a, actions); }}>编辑</Button>
        {a.status === 'published' ? (
          <Button variant="primary" icon={IconUpload} onClick={async () => {
            window.ui.closeDrawer();
            if (actions.push) await actions.push(a);
            else window.ui.toast('已重新推送至未阅读用户', { kind: 'success', desc: '邮件 + 站内信' });
          }}>重新推送</Button>
        ) : (
          <Button variant="primary" icon={IconCheck} onClick={async () => {
            window.ui.closeDrawer();
            if (actions.publish) await actions.publish(a);
            else window.ui.toast('公告已发布', { kind: 'success', desc: '将通过站内信、邮件推送' });
          }}>立即发布</Button>
        )}
      </>
    ),
  });
}

function openAnnouncementEditDrawer(a, actions = {}) {
  const body = a.body || ANNOUNCEMENT_BODIES[a.id] || '';
  const formId = `edit-announcement-${a.uuid || a.id}`;
  window.ui.drawer({
    title: `编辑公告 · ${a.id}`,
    subtitle: '修改后需重新发布;已阅读用户记录会保留',
    width: 600,
    content: (
      <div className="space-y-4" data-announcement-form={formId}>
        <div>
          <label className="block text-[12px] font-medium mb-1.5" style={{ color: 'var(--text-medium)' }}>公告标题 *</label>
          <input name="title" defaultValue={a.title}
            className="w-full h-10 px-3 rounded-lg text-[13.5px] outline-none"
            style={{ background: '#F8FAFC', border: '1px solid var(--border-soft)' }} />
        </div>
        <div className="grid grid-cols-3 gap-3">
          <div>
            <label className="block text-[12px] font-medium mb-1.5" style={{ color: 'var(--text-medium)' }}>级别</label>
            <select name="severity" defaultValue={a.severity === 'critical' ? '紧急' : a.severity === 'reminder' ? '提醒' : '常规'}
              className="w-full h-10 px-3 rounded-lg text-[13.5px] outline-none"
              style={{ background: '#F8FAFC', border: '1px solid var(--border-soft)' }}>
              <option>常规</option><option>提醒</option><option>紧急</option>
            </select>
          </div>
          <div>
            <label className="block text-[12px] font-medium mb-1.5" style={{ color: 'var(--text-medium)' }}>目标</label>
            <select name="target" defaultValue={announcementTargetLabel(a.target)}
              className="w-full h-10 px-3 rounded-lg text-[13.5px] outline-none"
              style={{ background: '#F8FAFC', border: '1px solid var(--border-soft)' }}>
              <option>全部租户</option><option>选定租户</option><option>管理员</option>
            </select>
          </div>
          <div>
            <label className="block text-[12px] font-medium mb-1.5" style={{ color: 'var(--text-medium)' }}>失效日期</label>
            <input name="expireAt" type="text" defaultValue={a.expireAt}
              className="w-full h-10 px-3 rounded-lg text-[13.5px] outline-none font-mono"
              style={{ background: '#F8FAFC', border: '1px solid var(--border-soft)' }} />
          </div>
        </div>
        <div>
          <label className="block text-[12px] font-medium mb-1.5" style={{ color: 'var(--text-medium)' }}>内容 (Markdown)</label>
          <textarea name="body" defaultValue={body}
            className="w-full h-60 p-3 rounded-lg text-[12.5px] outline-none resize-none font-mono leading-relaxed"
            style={{ background: '#F8FAFC', border: '1px solid var(--border-soft)', color: 'var(--text-strong)' }} />
        </div>
      </div>
    ),
    footer: (
      <>
        <Button onClick={() => window.ui.closeDrawer()}>取消</Button>
        <Button icon={IconArchive} onClick={async () => {
          const payload = announcementFormPayload(formId, { ...a, status: 'draft' });
          window.ui.closeDrawer();
          if (actions.saveDraft) await actions.saveDraft(a, payload);
          else window.ui.toast('已保存草稿', { kind: 'success' });
        }}>保存草稿</Button>
        <Button variant="primary" icon={IconCheck} onClick={async () => {
          const payload = announcementFormPayload(formId, { ...a, status: 'published' });
          window.ui.closeDrawer();
          if (actions.saveAndPush) await actions.saveAndPush(a, payload);
          else window.ui.toast('公告已更新并重新推送', { kind: 'success', desc: '站内信 + 邮件' });
        }}>
          保存并推送
        </Button>
      </>
    ),
  });
}

function SuperAnnouncementsPage() {
  const [announcements, reloadAnnouncements] = useSuperResource('/admin/announcements', SUPER_ANNOUNCEMENTS);
  const SEV = {
    info:     { label: '常规',   color: 'sky',    accent: '#0EA5E9', gradient: 'linear-gradient(135deg, #DBEAFE 0%, #E0F2FE 100%)' },
    reminder: { label: '提醒',   color: 'amber',  accent: '#D97706', gradient: 'linear-gradient(135deg, #FEF3C7 0%, #FFF7ED 100%)' },
    critical: { label: '紧急',   color: 'rose',   accent: '#DC2626', gradient: 'linear-gradient(135deg, #FEE2E2 0%, #FECACA 100%)' },
  };
  const announcementAction = async (a, action, label) => {
    const announcementId = superBackendId(a);
    if (!announcementId) {
      window.ui.toast('公告缺少后端 ID', { kind: 'warning' });
      return null;
    }
    return superPost(`/admin/announcements/${announcementId}/${action}`, {}, reloadAnnouncements, label);
  };
  const createAnnouncement = async (status) => {
    const payload = announcementFormPayload('new-announcement', { status });
    payload.status = status;
    const result = await superPost(
      '/admin/announcements',
      payload,
      reloadAnnouncements,
      status === 'published' ? '公告已发布' : '公告草稿已保存'
    );
    if (result) window.ui.closeModal();
  };
  const updateAnnouncement = async (a, payload, reload, label) => {
    const announcementId = superBackendId(a);
    if (!announcementId) {
      window.ui.toast('公告缺少后端 ID', { kind: 'warning' });
      return null;
    }
    return superPut(`/admin/announcements/${announcementId}`, payload, reload ? reloadAnnouncements : null, label);
  };
  const announcementActions = {
    publish: (a) => announcementAction(a, 'publish', '公告已发布'),
    push: (a) => announcementAction(a, 'push', '公告已重新推送'),
    retract: (a) => announcementAction(a, 'retract', '公告已撤回'),
    saveDraft: (a, payload) => updateAnnouncement(a, { ...payload, status: 'draft' }, true, '公告草稿已保存'),
    saveAndPush: async (a, payload) => {
      await updateAnnouncement(a, payload, false, false);
      await announcementAction(a, a.status === 'published' ? 'push' : 'publish', '公告已更新并推送');
    },
  };
  const publishedCount = announcements.filter(a => a.status === 'published').length;
  const draftCount = announcements.filter(a => a.status === 'draft').length;
  const totalReads = announcements.reduce((sum, a) => sum + Number(a.readBy || 0), 0);
  const readRate = Math.min(100, Math.round((totalReads / Math.max(1, publishedCount * 180)) * 100));
  return (
    <PageShell
      crumbs={['JKXT 平台', '超管控制台', '公告发布']}
      title="公告发布"
      badge={`${announcements.length} 条公告`}
      subtitle="平台级公告管理,可定向租户、定时发布、统计阅读率"
      actions={<Button variant="primary" icon={IconPlus} onClick={() => window.ui.modal({
        title: '撰写公告',
        subtitle: '支持 Markdown · 可选发布时间与失效时间',
        size: 'lg',
        content: (
          <div className="space-y-4" data-announcement-form="new-announcement">
            <div>
              <label className="block text-[12px] font-medium mb-1.5" style={{ color: 'var(--text-medium)' }}>公告标题 *</label>
              <input name="title" className="w-full h-10 px-3 rounded-lg text-[13.5px] outline-none" style={{ background: '#F8FAFC', border: '1px solid var(--border-soft)' }} />
            </div>
            <div className="grid grid-cols-3 gap-3">
              <div>
                <label className="block text-[12px] font-medium mb-1.5" style={{ color: 'var(--text-medium)' }}>级别</label>
                <select name="severity" className="w-full h-10 px-3 rounded-lg text-[13.5px] outline-none" style={{ background: '#F8FAFC', border: '1px solid var(--border-soft)' }}>
                  <option>常规</option><option>提醒</option><option>紧急</option>
                </select>
              </div>
              <div>
                <label className="block text-[12px] font-medium mb-1.5" style={{ color: 'var(--text-medium)' }}>目标</label>
                <select name="target" className="w-full h-10 px-3 rounded-lg text-[13.5px] outline-none" style={{ background: '#F8FAFC', border: '1px solid var(--border-soft)' }}>
                  <option>全部租户</option><option>选定租户</option><option>仅管理员</option>
                </select>
              </div>
              <div>
                <label className="block text-[12px] font-medium mb-1.5" style={{ color: 'var(--text-medium)' }}>失效日期</label>
                <input name="expireAt" type="date" className="w-full h-10 px-3 rounded-lg text-[13.5px] outline-none font-mono" style={{ background: '#F8FAFC', border: '1px solid var(--border-soft)' }} />
              </div>
            </div>
            <div>
              <label className="block text-[12px] font-medium mb-1.5" style={{ color: 'var(--text-medium)' }}>内容 (Markdown)</label>
              <textarea name="body" defaultValue="" placeholder="公告内容..."
                className="w-full h-40 p-3 rounded-lg text-[13.5px] outline-none resize-none font-mono"
                style={{ background: '#F8FAFC', border: '1px solid var(--border-soft)' }} />
            </div>
          </div>
        ),
        footer: (
          <>
            <Button onClick={() => createAnnouncement('draft')}>保存草稿</Button>
            <Button variant="primary" icon={IconCheck} onClick={() => createAnnouncement('published')}>立即发布</Button>
          </>
        ),
      })}>撰写公告</Button>}
    >
      <div className="grid grid-cols-4 gap-4">
        <FeatureKPI
          label="已发布" sublabel="PUBLISHED"
          value={publishedCount} unit="条"
          footnote="覆盖全部租户"
          icon={IconCheck} accent="#059669"
          gradient={FEATURE_GRADIENTS.mint} orbColor="rgba(52,211,153,.4)"
        />
        <FeatureKPI
          label="草稿" sublabel="DRAFTS"
          value={draftCount} unit="条"
          footnote="待审核与发布"
          icon={IconEdit} accent="#7C3AED"
          gradient={FEATURE_GRADIENTS.violet} orbColor="rgba(167,139,250,.4)"
        />
        <FeatureKPI
          label="总阅读量" sublabel="TOTAL READS"
          value={totalReads} unit="人次"
          footnote="近 30 天累计"
          icon={IconUsers} accent="#2563EB"
          gradient={FEATURE_GRADIENTS.azure} orbColor="rgba(96,165,250,.4)"
        />
        <FeatureKPI
          label="平均阅读率" sublabel="READ RATE"
          value={readRate} unit="%"
          footnote={`平均 ${publishedCount ? Math.round(totalReads / publishedCount) : 0} 人 / 条`}
          icon={IconChart} accent="#D97706"
          gradient={FEATURE_GRADIENTS.amber} orbColor="rgba(251,191,36,.42)"
        />
      </div>

      <div className="space-y-4">
        {announcements.map((a) => {
          const sev = SEV[a.severity] || SEV.info;
          return (
            <div key={a.id} className="relative bg-white rounded-2xl overflow-hidden group transition-all cursor-pointer"
              style={{
                border: '1px solid var(--border-soft)',
                boxShadow: '0 1px 3px rgba(15,23,42,.04), 0 8px 24px -8px rgba(15,23,42,.06)',
              }}
              onClick={() => openAnnouncementDrawer(a, sev, announcementActions)}
              onMouseEnter={(e) => { e.currentTarget.style.transform = 'translateY(-2px)'; e.currentTarget.style.boxShadow = '0 4px 8px rgba(15,23,42,.06), 0 16px 32px -8px rgba(15,23,42,.10)'; }}
              onMouseLeave={(e) => { e.currentTarget.style.transform = 'translateY(0)'; e.currentTarget.style.boxShadow = '0 1px 3px rgba(15,23,42,.04), 0 8px 24px -8px rgba(15,23,42,.06)'; }}>
              {/* Severity stripe */}
              <div className="absolute left-0 top-0 bottom-0 w-1" style={{ background: sev.accent }} />
              <div className="p-5 pl-6 flex items-start gap-4">
                <div className="w-11 h-11 rounded-xl flex items-center justify-center shrink-0"
                  style={{ background: sev.gradient, color: sev.accent, border: `1px solid ${sev.accent}33` }}>
                  {a.severity === 'critical' ? <IconAlert size={18} strokeWidth={2.2} /> :
                   a.severity === 'reminder' ? <IconBell  size={18} strokeWidth={2.2} /> :
                                               <IconSparkle size={18} strokeWidth={2.2} />}
                </div>
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 mb-2 flex-wrap">
                    <Tag color={sev.color} dot>{sev.label}</Tag>
                    <Tag color={announcementStatusColor(a.status)} dot>{announcementStatusLabel(a.status)}</Tag>
                    <span className="font-mono text-[10.5px] uppercase tracking-[.14em]" style={{ color: 'var(--text-soft)' }}>{a.id}</span>
                    <span className="text-[11px]" style={{ color: 'var(--text-soft)' }}>· 目标 · {announcementTargetLabel(a.target)}</span>
                  </div>
                  <h4 className="text-[15.5px] font-bold tracking-tight" style={{ color: 'var(--text-strong)', letterSpacing: '-0.005em' }}>{a.title}</h4>
                  <div className="mt-2.5 flex items-center gap-4 text-[11.5px]" style={{ color: 'var(--text-soft)' }}>
                    <span className="inline-flex items-center gap-1.5">
                      <IconUpload size={11} strokeWidth={2} />
                      <span className="font-mono">{a.publishAt}</span>
                    </span>
                    <span className="inline-flex items-center gap-1.5">
                      <IconClock size={11} strokeWidth={2} />
                      失效 <span className="font-mono">{a.expireAt}</span>
                    </span>
                  </div>
                </div>
                <div className="text-right shrink-0 pl-3">
                  <div className="num text-[28px] font-bold tracking-tight" style={{ color: sev.accent, letterSpacing: '-0.02em' }}>{a.readBy}</div>
                  <div className="text-[10px] font-mono uppercase tracking-[.16em]" style={{ color: 'var(--text-soft)' }}>已阅读</div>
                </div>
              </div>
              <div className="px-5 pl-6 py-2.5 flex items-center gap-2"
                style={{ borderTop: '1px solid var(--border-soft)', background: '#FAFBFC' }}
                onClick={(e) => e.stopPropagation()}>
                <Button size="sm" variant="ghost" onClick={() => openAnnouncementDrawer(a, sev, announcementActions)}>查看详情</Button>
                <Button size="sm" variant="ghost" onClick={() => openAnnouncementEditDrawer(a, announcementActions)}>编辑</Button>
                {a.status === 'published' && (
                  <Button size="sm" variant="ghost" danger onClick={async () => {
                    const ok = await window.ui.confirm({ title: '撤回此公告?', desc: '已阅读用户的记录会保留', danger: true });
                    if (ok) await announcementActions.retract(a);
                  }}>撤回</Button>
                )}
                <div className="flex-1" />
                <span className="font-mono text-[10.5px]" style={{ color: 'var(--text-soft)' }}>
                  阅读率 <span className="font-semibold" style={{ color: 'var(--text-medium)' }}>{Math.min(100, Math.round(Number(a.readBy || 0) / 1.62))}%</span>
                </span>
              </div>
            </div>
          );
        })}
      </div>
    </PageShell>
  );
}

Object.assign(window, {
  SuperDashboardPage, SuperTenantsPage, SuperAccountsPage,
  SuperSystemSettingsPage, SuperAuditPage, SuperSecurityPage, SuperAnnouncementsPage,
});
