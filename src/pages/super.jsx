// 超管控制台 — 7 个子页 (in single file for compactness)
// Pages: super-dashboard / super-tenants / super-accounts / super-system-settings /
//        super-audit / super-security / super-announcements

// ── Mock data ─────────────────────────────────────────────────────────
// P18 批次7：以下原是 5 块编造的兜底数据（假租户「天津市实验中学/南开/耀华」、假账号、假审计、
// 假威胁 IP、假公告——连「自动备份完成 4.2GB」这种批次1 已定性为伪造的内容都在）。
// useSuperResource/useSuperObject 在接口失败时会**静默**换上它们——超管在后端故障时看到的
// 是一屏可信的假运营数据，据此做租户管理决策会直接出事。现改为诚实空值 + 错误横幅。
const SUPER_TENANTS = [];
const SUPER_ACCOUNTS = [];
const SUPER_AUDIT = [];
// 安全总览的空形状：字段占位不是编造——0/—/空数组配合页面顶部的加载失败横幅一起呈现。
const SUPER_SEC = { failedLogins: null, suspiciousIps: null, activeRules: null, lastBackup: null, threats: [], backups: [] };
const SUPER_HEALTH = {};
const SUPER_ANNOUNCEMENTS = [];

/** 超管接口加载失败时的显式横幅——空列表/零值绝不能被误读成「真实为空/一切正常」。 */
function SuperApiErrorBanner({ errors }) {
  const failed = (errors || []).filter(Boolean);
  if (!failed.length) return null;
  return (
    <div className="rounded-xl p-3.5 flex items-start gap-3"
      style={{ background: '#FEF2F2', border: '1px solid #FCA5A5' }}>
      <IconAlert size={16} className="shrink-0 mt-0.5" style={{ color: '#DC2626' }} />
      <div className="text-[12.5px] leading-relaxed" style={{ color: '#991B1B' }}>
        <span className="font-semibold">平台数据加载失败</span>
        <span className="mx-1">·</span>
        以下内容当前显示为空或零值，<span className="font-semibold">并非真实状态</span>：
        {failed.map((e, i) => <span key={i} className="font-mono text-[11.5px] ml-1">{e}</span>)}
        <span className="ml-2" style={{ color: '#B91C1C' }}>请检查后端服务后刷新。</span>
      </div>
    </div>
  );
}

function useSuperResource(path, fallback) {
  const [rows, setRows] = React.useState(fallback);
  const [loading, setLoading] = React.useState(false);
  const [error, setError] = React.useState(null);
  const [loadedPath, setLoadedPath] = React.useState(null);
  const requestRef = React.useRef(0);
  const reload = React.useCallback(async () => {
    const requestId = ++requestRef.current;
    if (!window.api?.get) { setError(path); setLoading(false); return fallback; }
    setLoading(true);
    try {
      const data = await window.api.get(path);
      if (!Array.isArray(data)) throw new Error('列表接口返回格式无效');
      if (requestId !== requestRef.current) return fallback;
      const next = data;
      setRows(next);
      setError(null);
      setLoadedPath(path);
      return next;
    } catch (error) {
      if (requestId !== requestRef.current) return fallback;
      console.warn(`[super] ${path} failed`, error);
      setRows(fallback);       // fallback 现为诚实空值；配合页面顶部 SuperApiErrorBanner 一起呈现
      setError(path);
      return fallback;
    } finally {
      if (requestId === requestRef.current) setLoading(false);
    }
  }, [path, fallback]);
  React.useEffect(() => { reload(); return () => { requestRef.current += 1; }; }, [reload]);
  return [rows, reload, loading, setRows, error, loadedPath];
}

function useSuperObject(path, fallback) {
  const [value, setValue] = React.useState(fallback);
  const [error, setError] = React.useState(null);
  const requestRef = React.useRef(0);
  const reload = React.useCallback(async () => {
    const requestId = ++requestRef.current;
    if (!window.api?.get) { setError(path); return fallback; }
    try {
      const data = await window.api.get(path);
      if (!data || typeof data !== 'object' || Array.isArray(data)) throw new Error('对象接口返回格式无效');
      if (requestId !== requestRef.current) return fallback;
      const next = data;
      setValue(next);
      setError(null);
      return next;
    } catch (error) {
      if (requestId !== requestRef.current) return fallback;
      console.warn(`[super] ${path} failed`, error);
      setValue(fallback);
      setError(path);
      return fallback;
    }
  }, [path, fallback]);
  React.useEffect(() => { reload(); return () => { requestRef.current += 1; }; }, [reload]);
  return [value, reload, setValue, error];
}

async function superPost(path, body, reload, successText) {
  const shouldToast = successText !== false;
  if (!window.api?.post) {
    // 迭代9 T3-P0-1：后端客户端不可用时不得伪装成功(原 '操作已记录' 让超管误以为封禁/重置/备份已生效)。
    window.ui.toast('操作未执行：后端接口不可用', { kind: 'error', desc: '请刷新页面或检查服务状态后重试' });
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
    // 迭代9 T3-P0-1：后端客户端不可用时不得伪装成功(原 '操作已记录' 让超管误以为封禁/重置/备份已生效)。
    window.ui.toast('操作未执行：后端接口不可用', { kind: 'error', desc: '请刷新页面或检查服务状态后重试' });
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
    return String(value == null ? fallback : value).trim();
  };
  return {
    title: field('title', defaults.title || ''),
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
function SuperHero({ tenantsCount, accountsCount, uptimeSeconds, announcementsCount }) {
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

      <div className="relative p-8 grid grid-cols-1 xl:grid-cols-[minmax(0,1fr)_360px] gap-8 items-center">
        <div>
          <div className="inline-flex items-center gap-2 px-2.5 py-1 rounded-full mb-5"
            style={{ background: 'rgba(255,255,255,.14)', border: '1px solid rgba(255,255,255,.22)', backdropFilter: 'blur(8px)' }}>
            <span className="w-1.5 h-1.5 rounded-full" style={{ background: '#22D3EE', boxShadow: '0 0 8px #22D3EE' }} />
            <span className="font-mono text-[10.5px] uppercase tracking-[.22em] text-white/90">
              JKXT 平台 · 超级管理控制台
            </span>
          </div>
          <div className="flex items-baseline gap-3 flex-wrap">
            <h2 className="text-[24px] font-bold tracking-tight text-white" style={{ letterSpacing: '-0.02em' }}>
              {greeting},超级管理员
            </h2>
            <span className="font-mono text-[13px] text-white/60">{dateStr}</span>
          </div>
          <p className="text-[13.5px] text-white/75 mt-2 leading-relaxed">
            {tenantsCount ?? '—'} 个租户 · {accountsCount ?? '—'} 个管理账号 · 查看后台已加载的运营记录
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
              查看安全事件
            </button>
          </div>
        </div>

        {/* Right: micro metric grid */}
        <div className="grid grid-cols-2 gap-2.5">
          {/* 真实指标（原「在线率 99.98% / 活跃 6/7 / AI 调用 142」全为编造；无数据源的维度不展示） */}
          <MetricChip icon={IconBuilding} label="租户"     value={String(tenantsCount ?? '—')} />
          <MetricChip icon={IconUsers}    label="账号"     value={String(accountsCount ?? '—')} />
          <MetricChip icon={IconCheck}    label="运行"     value={uptimeSeconds != null ? (uptimeSeconds >= 3600 ? Math.floor(uptimeSeconds/3600) + ' 时' : Math.floor(uptimeSeconds/60) + ' 分') : '—'} />
          <MetricChip icon={IconUsers}    label="在线人数" value="未提供" />
        </div>
      </div>
    </div>
  );
}

// ── Super Dashboard ─────────────────────────────────────────────
function SuperDashboardPage() {
  const [logRange, setLogRange] = React.useState('近 24 小时');

  // 所有运营指标来自后台；失败和未提供的维度保持显式未知，不生成占位统计。
  const [security, , , securityError] = useSuperObject('/admin/security/overview', SUPER_SEC);
  const auditPath = `/admin/audit-logs?hours=${({ '近 24 小时': 24, '近 7 天': 168, '近 30 天': 720 })[logRange]}`;
  const [auditRows, , auditLoading, , auditError, auditLoadedPath] = useSuperResource(auditPath, SUPER_AUDIT);
  const [tenants, , , , tenantsError] = useSuperResource('/admin/tenants', SUPER_TENANTS);
  const [accounts, , , , accountsError] = useSuperResource('/admin/accounts', SUPER_ACCOUNTS);

  // P18 批次7b/7c：平台健康度接真实 /admin/system-health（进程内 MeterRegistry/Runtime；
  // 7c 补 CPU/磁盘——Boot 自动绑定的 ProcessorMetrics/DiskSpaceMetrics）。
  // 原五条写死示例全部废除；取不到的值显示「暂无数据」，绝不编数。
  const [health, , , healthError] = useSuperObject('/admin/system-health', SUPER_HEALTH);
  const pct = (used, max) => (used != null && max > 0 ? Math.min(100, Math.round((used / max) * 100)) : null);
  const fmtUptime = (sec) => sec == null ? '暂无数据'
    : sec >= 86400 ? `${Math.floor(sec / 86400)} 天 ${Math.floor((sec % 86400) / 3600)} 时`
    : sec >= 3600 ? `${Math.floor(sec / 3600)} 时 ${Math.floor((sec % 3600) / 60)} 分`
    : `${Math.floor(sec / 60)} 分`;
  const fmtGb = (bytes) => (bytes / 1073741824).toFixed(1);
  const diskUsedPct = (health.diskTotalBytes > 0 && health.diskFreeBytes != null)
    ? Math.round(((health.diskTotalBytes - health.diskFreeBytes) / health.diskTotalBytes) * 100) : null;
  const healthMetrics = [
    // 7c：CPU/磁盘为 Boot ProcessorMetrics/DiskSpaceMetrics 真值（容器视角）；usage 首采样可能为空。
    { label: 'CPU 使用率', value: health.cpuSystem != null ? Math.round(health.cpuSystem * 100) : null, color: '#10B981',
      sub: health.cpuSystem != null ? `系统 ${(health.cpuSystem * 100).toFixed(1)}% · 本进程 ${health.cpuProcess != null ? (health.cpuProcess * 100).toFixed(1) : '—'}% · ${health.cpuCount ?? '—'} 核` : '暂无数据' },
    { label: '磁盘使用', value: diskUsedPct, color: '#06B6D4',
      sub: diskUsedPct != null ? `${fmtGb(health.diskTotalBytes - health.diskFreeBytes)} / ${fmtGb(health.diskTotalBytes)} GB` : '暂无数据' },
    { label: 'JVM 堆内存', value: pct(health.heapUsedMb, health.heapMaxMb), color: '#4F46E5',
      sub: health.heapUsedMb != null ? `${health.heapUsedMb} / ${health.heapMaxMb} MB` : '暂无数据' },
    { label: '数据库连接池', value: pct(health.dbPoolActive, health.dbPoolMax), color: '#F59E0B',
      sub: health.dbPoolActive != null ? `${Math.round(health.dbPoolActive)} 活跃 / ${Math.round(health.dbPoolMax)} 上限` : '暂无数据' },
    { label: 'API 请求（自启动累计）', value: null, color: '#9333EA',
      sub: health.httpRequestsTotal != null ? `${Number(health.httpRequestsTotal).toLocaleString()} 次` : '暂无数据' },
    { label: '持续运行', value: null, color: '#10B981',
      sub: fmtUptime(health.uptimeSeconds) },
  ];

  return (
    <PageShell
      crumbs={['JKXT 平台', '超管控制台', '仪表盘']}
      title={<span className="inline-flex items-center gap-2.5">超管仪表盘 <Tag color="violet" dot>超管</Tag></span>}
      badge={`已加载 ${tenants.length} 租户 · ${accounts.length} 账号`}
      subtitle="跨租户视角的平台运营总览、近期操作日志与安全状态"
      actions={
        <>
          <Button icon={IconChart} disabled title="周报生成与邮件通道尚未接入">周报未接入</Button>
          <Button variant="primary" icon={IconPlus} onClick={openNewTenantModal}>注册新租户</Button>
        </>
      }
    >
      <SuperApiErrorBanner errors={[securityError, auditError, tenantsError, accountsError, healthError]} />
      <SuperHero tenantsCount={tenants.length} accountsCount={accounts.length} uptimeSeconds={health.uptimeSeconds} announcementsCount={null} />

      {/* Feature KPI row — reference-inspired gradient cards */}
      <div className="grid grid-cols-4 gap-4">
        <FeatureKPI
          label="平台安全分"
          sublabel="安全评分"
          value="—"
          footnote="安全评估尚未接入"
          icon={IconCheck}
          accent="#059669"
          gradient={FEATURE_GRADIENTS.mint}
          orbColor="rgba(52,211,153,.4)"
          onClick={() => window.navigateTo('super-security')}
        />
        <FeatureKPI
          label="运行状态租户"
          sublabel="租户状态记录"
          value={tenants.filter((t) => t.status === 'active').length}
          unit="个"
          footnote={`共加载 ${tenants.length} 个租户，不代表近期活跃度`}
          icon={IconBuilding}
          accent="#7C3AED"
          gradient={FEATURE_GRADIENTS.violet}
          orbColor="rgba(167,139,250,.45)"
          onClick={() => window.navigateTo('super-tenants')}
        />
        <FeatureKPI
          label="2FA 覆盖率"
          sublabel="二次验证覆盖率"
          value="—"
          footnote="后台未提供真实二次验证启用状态"
          icon={IconUsers}
          accent="#2563EB"
          gradient={FEATURE_GRADIENTS.azure}
          orbColor="rgba(96,165,250,.4)"
          onClick={() => window.navigateTo('super-accounts')}
        />
        <FeatureKPI
          label="失败登录记录"
          sublabel="留存审计累计"
          value={security.failedLogins ?? '—'}
          unit="次"
          footnote={`${security.suspiciousIps ?? '—'} 条未处理安全事件`}
          icon={IconAlert}
          accent="#DC2626"
          gradient={FEATURE_GRADIENTS.rose}
          orbColor="rgba(244,114,182,.42)"
          onClick={() => window.navigateTo('super-security')}
        />
      </div>

      {/* Main row: activity feed (left) + side rail (right) */}
      <div className="grid grid-cols-1 xl:grid-cols-[minmax(0,1fr)_360px] gap-5">
        <GlassPanel className="p-6">
          <div className="flex items-end justify-between mb-5">
            <div>
              <h3 className="text-[16px] font-bold tracking-tight" style={{ color: 'var(--text-strong)' }}>
                近期操作活动
              </h3>
              <div className="text-[12px] mt-0.5" style={{ color: 'var(--text-soft)' }}>
                跨租户审计记录 · 所选时间范围内最近 200 条
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
            {auditLoading || auditLoadedPath !== auditPath ? <p role="status">正在加载所选时间范围…</p> : auditRows.length === 0 ? <p style={{ color: 'var(--text-soft)' }}>当前范围暂无已加载记录。</p> : auditRows.slice(0, 6).map((l) => {
              const [date, time] = (l.at || '').split(' ');
              return (
                <ActivityRow
                  key={l.id}
                  when={date || '未提供日期'}
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
          {/* Available security records, not a synthetic safety score */}
          <GlassPanel className="p-5">
            <div className="flex items-end justify-between mb-3">
              <div>
                <h3 className="text-[14.5px] font-bold" style={{ color: 'var(--text-strong)' }}>安全记录摘要</h3>
                <div className="text-[12px] mt-0.5" style={{ color: 'var(--text-soft)' }}>后台记录，不代表安全评估结论</div>
              </div>
              <Tag color="slate">记录</Tag>
            </div>
            <div className="flex items-center gap-4">
              <div className="flex-1 space-y-1.5 text-[11.5px]">
                {[
                  { l: '二次验证', v: '未提供', c: 'var(--text-medium)' },
                  { l: '未处理事件', v: `${security.suspiciousIps ?? '—'} 条`, c: 'var(--text-strong)' },
                  { l: '已登记 IP 规则', v: `${security.activeRules ?? '—'} 条`, c: 'var(--text-strong)' },
                  { l: '最近备份记录', v: security.lastBackup && security.lastBackup !== 'No backup' ? security.lastBackup : '暂无记录', c: 'var(--text-medium)' },
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
                  <div className="text-[10px] uppercase font-mono tracking-[.2em] opacity-70">平台版本</div>
                  <div className="text-[24px] font-bold mt-1.5 tracking-tight" style={{ letterSpacing: '-0.02em' }}>
                    JKXT <span className="text-white/60">v</span>2.0.4
                  </div>
                </div>
                <span className="px-2 py-0.5 rounded-md font-mono text-[10px] font-semibold"
                  style={{ background: 'rgba(52,211,153,.22)', color: '#86EFAC', border: '1px solid rgba(52,211,153,.4)' }}>
                  {health.uptimeSeconds == null ? '指标未提供' : '已加载指标'}
                </span>
              </div>
              <div className="grid grid-cols-2 gap-3 mt-4 pt-3" style={{ borderTop: '1px solid rgba(255,255,255,.12)' }}>
                <div>
                  <div className="font-mono text-[9.5px] uppercase tracking-[.18em] opacity-60">运行时长</div>
                  <div className="num text-[16px] font-bold mt-0.5">{fmtUptime(health.uptimeSeconds)}</div>
                </div>
                <div>
                  <div className="font-mono text-[9.5px] uppercase tracking-[.18em] opacity-60">故障事件</div>
                  <div className="text-[14px] mt-0.5">未提供</div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* Bottom row: platform health + tenant overview */}
      <div className="grid grid-cols-1 xl:grid-cols-[420px_minmax(0,1fr)] gap-5">
        <GlassPanel className="p-6">
          <div className="flex items-end justify-between mb-4">
            <div>
              <h3 className="text-[16px] font-bold tracking-tight" style={{ color: 'var(--text-strong)' }}>
                平台健康度
              </h3>
              <div className="text-[12px] mt-0.5" style={{ color: 'var(--text-soft)' }}>
                已加载进程指标 · /admin/system-health
              </div>
            </div>
            {healthError ? <Tag color="rose" dot>指标不可用</Tag> : <Tag color="slate">{health.uptimeSeconds == null ? '等待指标' : '已加载'}</Tag>}
          </div>
          {healthMetrics.map((m, i) => (
            <div key={m.label} style={{ borderBottom: i < healthMetrics.length - 1 ? '1px solid var(--border-soft)' : 'none' }}>
              {m.value == null ? <div className="py-3 text-[14px]"><div style={{ color: 'var(--text-strong)' }}>{m.label}</div><div className="text-[12px] mt-1" style={{ color: 'var(--text-soft)' }}>{m.sub}</div></div> : <HealthBar label={m.label} value={m.value} color={m.color} sub={m.sub} />}
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
                {tenants.length} 所学校 · 按学生规模排序
              </div>
            </div>
            <button onClick={() => window.navigateTo('super-tenants')}
              className="text-[12px] font-medium px-2 hover:underline"
              style={{ color: 'var(--brand)' }}>管理租户 →</button>
          </div>
          <div className="grid grid-cols-2 gap-3">
            {tenants.map((t) => {
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

// 新增租户 — 真实写入后端 (POST /api/v1/admin/tenants，SUPER_ADMIN)。
// 私有部署固定单租户时后端会返回 403，表单会如实提示。
function SuperTenantFormModal({ onSaved }) {
  const [form, setForm] = React.useState({ name: '', city: '天津', plan: 'trial', students: '', contactName: '', contactPhone: '' });
  const [saving, setSaving] = React.useState(false);
  const [err, setErr] = React.useState('');
  const bind = (key) => (e) => setForm((prev) => ({ ...prev, [key]: e.target.value }));

  const submit = async () => {
    if (saving) return;
    if (!form.name.trim()) { setErr('学校名称为必填项'); return; }
    setSaving(true); setErr('');
    try {
      await window.api.post('/admin/tenants', {
        name: form.name.trim(), city: form.city.trim() || null, plan: form.plan,
        students: parseInt(form.students, 10) || 0,
        contactName: form.contactName.trim() || null, contactPhone: form.contactPhone.trim() || null,
      });
      window.ui.closeModal();
      window.ui.toast('租户已创建', { kind: 'success', desc: form.name.trim() + ' · 审计已记录' });
      if (onSaved) onSaved();
    } catch (e) {
      setErr(e.message || '创建失败（私有部署固定单租户时不支持新增）');
      setSaving(false);
    }
  };

  return (
    <div className="space-y-3">
      {[
        ['学校名称 *', 'name', '例:天津市第二实验小学'],
        ['所在城市', 'city', '例:天津 · 河西区'],
        ['预估学生数', 'students', '例:1200'],
        ['联系人姓名', 'contactName', '例:张明远'],
        ['联系电话', 'contactPhone', '13800138000'],
      ].map(([label, key, ph]) => (
        <div key={key} className="grid grid-cols-[100px_1fr] gap-3 items-center">
          <label className="text-[12.5px] text-right" style={{ color: 'var(--text-medium)' }}>{label}</label>
          <input style={SUPER_FIELD_STYLE} value={form[key]} onChange={bind(key)} placeholder={ph} />
        </div>
      ))}
      <div className="grid grid-cols-[100px_1fr] gap-3 items-center">
        <label className="text-[12.5px] text-right" style={{ color: 'var(--text-medium)' }}>套餐</label>
        <select style={SUPER_FIELD_STYLE} value={form.plan} onChange={bind('plan')}>
          <option value="trial">试用 (trial)</option>
          <option value="standard">标准 (standard)</option>
          <option value="premium">旗舰 (premium)</option>
        </select>
      </div>
      {err && <div className="rounded-lg px-3 py-2 text-[12px]" style={{ background: '#FEF2F2', color: '#B91C1C', border: '1px solid #FECACA' }}>{err}</div>}
      <div className="flex items-center justify-end gap-2 pt-1">
        <Button onClick={() => window.ui.closeModal()}>取消</Button>
        <Button variant="primary" icon={IconCheck} disabled={saving} onClick={submit}>{saving ? '创建中…' : '创建租户'}</Button>
      </div>
    </div>
  );
}

// Open the New Tenant registration modal. onSaved (optional) reloads the tenant list.
function openNewTenantModal(onSaved) {
  window.ui.modal({
    title: '注册新租户',
    subtitle: '填写学校基础信息,创建后可在租户管理继续完善',
    size: 'md',
    content: <SuperTenantFormModal onSaved={typeof onSaved === 'function' ? onSaved : undefined} />,
  });
}

function openTenantAudit(t) {
  const slug = String(t.slug || t.id || '').trim();
  window.__jkxtSuperAuditTenant = slug ? slug.toUpperCase() : '全部';
  window.ui.closeDrawer();
  window.navigateTo('super-audit');
}

// Tenant detail uses only fields returned by /admin/tenants.
function openTenantDrawer(t) {
  const sLabel = t.status === 'active' ? '活跃' : t.status === 'trial' ? '试用' : '已暂停';
  const sColor = t.status === 'active' ? 'emerald' : t.status === 'trial' ? 'amber' : 'rose';
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
              租户标识：{String(t.slug || t.id || '未提供').toUpperCase()}
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
              { label: '管理员', value: t.admins ?? '未提供', unit: '人', color: 'var(--text-strong)' },
              { label: '登记学生数', value: t.students == null ? '未提供' : Number(t.students).toLocaleString('zh-CN'), unit: '人', color: 'var(--text-strong)' },
              { label: '存储使用量', value: '未提供', unit: '', color: 'var(--text-medium)' },
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
            <button onClick={() => openTenantAudit(t)}
              className="text-[12px] font-semibold hover:underline" style={{ color: 'var(--brand)' }}>查看该租户审计 →</button>
          </div>
          <p className="text-[13px] leading-relaxed" style={{ color: 'var(--text-medium)' }}>租户资料接口未返回活动记录。审计页可查看已加载的最新记录，并按此租户筛选。</p>
        </div>

        {/* Sys info */}
        <div className="rounded-xl p-4" style={{ background: '#FAFBFC', border: '1px solid var(--border-soft)' }}>
          <div className="grid grid-cols-2 gap-3 text-[12px]">
            <div>
              <div className="text-[10.5px] font-mono uppercase tracking-wider" style={{ color: 'var(--text-soft)' }}>API 调用 · 本月</div>
              <div className="mt-0.5" style={{ color: 'var(--text-strong)' }}>接口未提供</div>
            </div>
            <div>
              <div className="text-[10.5px] font-mono uppercase tracking-wider" style={{ color: 'var(--text-soft)' }}>最近登录</div>
              <div className="mt-0.5" style={{ color: 'var(--text-strong)' }}>接口未提供</div>
            </div>
            <div>
              <div className="text-[10.5px] font-mono uppercase tracking-wider" style={{ color: 'var(--text-soft)' }}>套餐到期</div>
              <div className="mt-0.5" style={{ color: 'var(--text-strong)' }}>接口未提供</div>
            </div>
            <div>
              <div className="text-[12px]" style={{ color: 'var(--text-soft)' }}>联系人</div>
              <div className="mt-0.5" style={{ color: 'var(--text-strong)' }}>{t.contactName || '未提供'}</div>
            </div>
          </div>
        </div>
      </div>
    ),
    footer: (
      <>
        <Button onClick={() => window.ui.closeDrawer()}>关闭</Button>
        <Button icon={IconList} onClick={() => { window.ui.closeDrawer(); openTenantEditDrawer(t); }}>资料信息</Button>
        <Button variant="primary" icon={IconList} onClick={() => openTenantAudit(t)}>
          查看该租户审计
        </Button>
      </>
    ),
  });
}

function openTenantEditDrawer(t) {
  window.ui.drawer({
    title: `租户资料 · ${t.name}`,
    subtitle: '当前资料面板只读，未接入的编辑字段不提供保存按钮',
    width: 540,
    content: (
      <div className="space-y-4">
        {[
          { l: '学校名称',       v: t.name,   mono: false },
          { l: '租户标识',       v: String(t.slug || t.id || '').toUpperCase(), mono: true, lock: true, hint: '创建后不可修改' },
          { l: '所在城市',       v: t.city,   mono: false },
          { l: '学段类型',       v: t.type,   mono: false, kind: 'select', options: ['完全中学', '初中', '高中', '小学'] },
          { l: '套餐',           v: t.plan,   mono: false, kind: 'select', options: ['企业版', '专业版', '标准版', '试用版'] },
          { l: '管理员上限',     v: t.adminCap ?? '未提供', mono: true, kind: 'number' },
          { l: '登记学生数',     v: t.students ?? '未提供', mono: true, kind: 'number' },
        ].map((f) => (
          <div key={f.l} className="grid grid-cols-[100px_1fr] gap-3 items-center">
            <label className="text-[12.5px] text-right" style={{ color: 'var(--text-medium)' }}>{f.l}</label>
            <input value={f.v ?? '未提供'} readOnly aria-label={`${f.l}（只读）`}
              className="h-10 px-3 rounded-lg text-[13px] outline-none"
              style={{ background: 'var(--surface-muted)', border: '1px solid var(--border-soft)', color: 'var(--text-strong)', fontFamily: f.mono ? 'Geist Mono' : undefined }} />
          </div>
        ))}
        <div className="rounded-xl p-3.5" style={{ background: '#FEF3C7', border: '1px solid #FCD34D' }}>
          <div className="text-[12px] font-semibold flex items-center gap-1.5" style={{ color: '#92400E' }}>
            <IconAlert size={12} strokeWidth={2.25} />
            此处仅查看后台返回的资料，不会修改套餐、人数上限或产生计费
          </div>
        </div>
      </div>
    ),
    footer: (
      <>
        <Button onClick={() => window.ui.closeDrawer()}>关闭</Button>
      </>
    ),
  });
}

function SuperTenantsPage() {
  const [tenants, reloadTenants, , , tenantsError] = useSuperResource('/admin/tenants', SUPER_TENANTS);
  const [tenantQ, setTenantQ] = React.useState('');
  const [tenantPlanF, setTenantPlanF] = React.useState('全部');
  const [tenantCityF, setTenantCityF] = React.useState('全部');
  const [tenantStatusF, setTenantStatusF] = React.useState('全部');
  const TENANT_STATUS_MAP = { '正常': 'active', '试用': 'trial', '停用': 'suspended' };
  const filteredTenants = tenants
    .filter((t) => !tenantQ || String(t.name || '').includes(tenantQ) || String(t.slug || '').toLowerCase().includes(tenantQ.toLowerCase()))
    .filter((t) => tenantPlanF === '全部' || t.plan === tenantPlanF)
    .filter((t) => tenantCityF === '全部' || t.city === tenantCityF)
    .filter((t) => tenantStatusF === '全部' || t.status === TENANT_STATUS_MAP[tenantStatusF]);
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
            onClick={() => openTenantEditDrawer(r)}>资料</button>
          {r.status !== 'suspended' && (
            <button className="text-[12px] font-medium px-1.5 hover:underline" style={{ color: '#DC2626' }}
              onClick={async () => {
                const ok = await window.ui.confirm({ title: `暂停 ${r.name}?`, desc: '将更新租户状态并停止其账号访问，保留现有业务记录。当前未设置自动清理期限。', danger: true, confirmText: '暂停租户' });
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
          <Button icon={IconUpload} disabled title="尚无租户批量导入接口，请使用注册新租户">批量导入未接入</Button>
          <Button variant="primary" icon={IconPlus} onClick={openNewTenantModal}>注册新租户</Button>
        </>
      }
    >
      <div className="grid grid-cols-4 gap-4">
        <FeatureKPI
          label="运行状态租户" sublabel="后台状态"
          value={tenants.filter(t => t.status === 'active').length} unit={`/ ${tenants.length}`}
          footnote="按租户状态统计，不代表近期活跃度"
          icon={IconCheck} accent="#059669"
          gradient={FEATURE_GRADIENTS.mint} orbColor="rgba(52,211,153,.4)"
        />
        <FeatureKPI
          label="试用中" sublabel="试用中"
          value={tenants.filter(t => t.status === 'trial').length} unit="所"
          footnote="后台未提供试用剩余天数"
          icon={IconClock} accent="#D97706"
          gradient={FEATURE_GRADIENTS.amber} orbColor="rgba(251,191,36,.42)"
        />
        <FeatureKPI
          label="已暂停" sublabel="已暂停"
          value={tenants.filter(t => t.status === 'suspended').length} unit="所"
          footnote="需联系恢复"
          icon={IconAlert} accent="#DC2626"
          gradient={FEATURE_GRADIENTS.rose} orbColor="rgba(244,114,182,.42)"
        />
        <FeatureKPI
          label="累计学生" sublabel="学生总数"
          value={(totalStudents/1000).toFixed(1)} unit="K"
          footnote={`${totalStudents.toLocaleString()} 人`}
          icon={IconUsers} accent="#4F46E5"
          gradient={FEATURE_GRADIENTS.violet} orbColor="rgba(167,139,250,.42)"
        />
      </div>

      <SuperApiErrorBanner errors={[tenantsError]} />

      {/* P18 批次7：筛选真实接线。选项从真实数据推导——原写死的「基础版/专业版/旗舰版」「北京/上海/广州」
          与后端 plan/城市 取值并不对应，选了也永远筛不出结果。 */}
      <FilterBar
        search="搜索学校名称、标识..."
        searchValue={tenantQ}
        onSearch={setTenantQ}
        filters={[
          { label: '套餐:', value: tenantPlanF, options: ['全部', ...Array.from(new Set(tenants.map((t) => t.plan).filter(Boolean)))], onChange: setTenantPlanF },
          { label: '城市:', value: tenantCityF, options: ['全部', ...Array.from(new Set(tenants.map((t) => t.city).filter(Boolean)))], onChange: setTenantCityF },
          { label: '状态:', value: tenantStatusF, options: ['全部', '正常', '试用', '停用'], onChange: setTenantStatusF },
        ]}
        total={filteredTenants.length}
        onReset={() => { setTenantQ(''); setTenantPlanF('全部'); setTenantCityF('全部'); setTenantStatusF('全部'); }}
      />

      <DataTable columns={cols} rows={filteredTenants} selectable onRowClick={(r) => openTenantDrawer(r)} />
    </PageShell>
  );
}

// ── 3. Accounts ───────────────────────────────────────────────────────
const SUPER_FIELD_STYLE = {
  minHeight: 44, padding: '8px 12px', borderRadius: 8, fontSize: 14,
  color: 'var(--text-strong)', background: 'var(--surface-muted)', border: '1px solid var(--border-soft)', width: '100%', minWidth: 0,
};

// 新增管理员账号 — 真实写入后端 (POST /api/v1/admin/accounts，SUPER_ADMIN)
function SuperAccountFormModal({ onSaved }) {
  const [form, setForm] = React.useState({ name: '', email: '', phone: '', tenantId: '', role: 'admin' });
  const [tenants, setTenants] = React.useState([]);
  const [tenantsLoading, setTenantsLoading] = React.useState(true);
  const [tenantsError, setTenantsError] = React.useState('');
  const [tenantReload, setTenantReload] = React.useState(0);
  const [saving, setSaving] = React.useState(false);
  const savingRef = React.useRef(false);
  const [err, setErr] = React.useState('');

  React.useEffect(() => {
    let alive = true;
    setTenantsLoading(true); setTenantsError('');
    Promise.resolve().then(() => {
      if (!window.api?.get) throw new Error('后端接口不可用');
      return window.api.get('/admin/tenants');
    })
      .then((rows) => {
        if (!alive) return;
        if (!Array.isArray(rows)) throw new Error('租户列表格式无效');
        const list = rows.filter((row) => row.uuid);
        setTenants(list);
        setForm((prev) => ({ ...prev, tenantId: list.some((row) => row.uuid === prev.tenantId) ? prev.tenantId : list[0]?.uuid || '' }));
      })
      .catch((error) => { if (alive) { setTenants([]); setTenantsError(error.message || '租户加载失败'); } })
      .finally(() => { if (alive) setTenantsLoading(false); });
    return () => { alive = false; };
  }, [tenantReload]);

  const bind = (key) => (e) => setForm((prev) => ({ ...prev, [key]: e.target.value }));

  const submit = async () => {
    if (savingRef.current || tenantsLoading) return;
    if (!form.name.trim() || !form.email.trim()) { setErr('姓名和邮箱为必填项'); return; }
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email.trim())) { setErr('请填写有效邮箱地址'); return; }
    if (tenantsError || !tenants.some((row) => row.uuid === form.tenantId)) { setErr('请先成功加载并选择所属租户'); return; }
    savingRef.current = true;
    setSaving(true); setErr('');
    try {
      const created = await window.api.post('/admin/accounts', {
        name: form.name.trim(), email: form.email.trim(), phone: form.phone.trim() || null,
        tenantId: form.tenantId, role: form.role,
      });
      window.ui.closeModal();
      if (onSaved) onSaved();
      // 迭代9 F1：后端一次性返回初始口令,如实展示给超管转交(本系统无邮件通道,不能谎称"已发送")。
      const pwd = created && created.initialPassword;
      if (pwd) {
        await window.ui.modal({
          title: '账号已创建 · 初始密码',
          content: (
            <div className="space-y-2 text-[13px]">
              <div style={{ color: 'var(--text-medium)' }}>请将初始密码转交给 <b>{form.name.trim()}</b>（{form.email.trim()}）。<b>此密码仅显示一次</b>,用户首次登录后须修改。</div>
              <div className="font-mono text-[16px] px-3 py-2 rounded-lg select-all" style={{ background: '#F1F5F9', border: '1px solid #CBD5E1', color: 'var(--text-strong)' }}>{pwd}</div>
            </div>
          ),
          size: 'sm',
        });
      } else {
        window.ui.toast('账号已创建', { kind: 'success', desc: '首次登录需修改密码 · 审计已记录' });
      }
    } catch (e) {
      setErr(e.message || '创建失败');
    } finally { savingRef.current = false; setSaving(false); }
  };

  return (
    <div className="space-y-3">
      <div className="grid grid-cols-[100px_1fr] gap-3 items-center">
        <label className="text-[12.5px] text-right" style={{ color: 'var(--text-medium)' }}>姓名 *</label>
        <input aria-label="姓名" style={SUPER_FIELD_STYLE} value={form.name} onChange={bind('name')} placeholder="例:张明远" />
      </div>
      <div className="grid grid-cols-[100px_1fr] gap-3 items-center">
        <label className="text-[12.5px] text-right" style={{ color: 'var(--text-medium)' }}>邮箱 *</label>
        <input aria-label="邮箱" type="email" style={SUPER_FIELD_STYLE} value={form.email} onChange={bind('email')} placeholder="name@school.edu.cn" />
      </div>
      <div className="grid grid-cols-[100px_1fr] gap-3 items-center">
        <label className="text-[12.5px] text-right" style={{ color: 'var(--text-medium)' }}>手机号</label>
        <input aria-label="手机号" type="tel" style={SUPER_FIELD_STYLE} value={form.phone} onChange={bind('phone')} placeholder="13800138000" />
      </div>
      <div className="grid grid-cols-[100px_1fr] gap-3 items-center">
        <label className="text-[12.5px] text-right" style={{ color: 'var(--text-medium)' }}>所属租户</label>
        <select aria-label="所属租户" disabled={tenantsLoading || Boolean(tenantsError)} style={SUPER_FIELD_STYLE} value={form.tenantId} onChange={bind('tenantId')}>
          {tenants.length === 0 && <option value="">{tenantsLoading ? '正在加载租户…' : '暂无可选租户'}</option>}
          {tenants.map((t) => <option key={t.uuid} value={t.uuid}>{t.name || t.slug || t.uuid}</option>)}
        </select>
      </div>
      <div className="grid grid-cols-[100px_1fr] gap-3 items-center">
        <label className="text-[12.5px] text-right" style={{ color: 'var(--text-medium)' }}>角色 *</label>
        <select aria-label="角色" style={SUPER_FIELD_STYLE} value={form.role} onChange={bind('role')}>
          <option value="admin">本校管理员 (admin)</option>
          <option value="viewer">只读 (viewer)</option>
          <option value="super">超级管理员 (super)</option>
        </select>
      </div>
      <div className="rounded-lg p-3 text-[11.5px]" style={{ background: '#EEF2FF', border: '1px solid #C7D2FE', color: 'var(--text-medium)' }}>
        系统将自动生成临时密码，首次登录需修改。二次验证功能尚未接入。
      </div>
      {tenantsError && <div role="alert" className="text-[13px]">租户加载失败：{tenantsError} <Button size="sm" onClick={() => setTenantReload((value) => value + 1)}>重试</Button></div>}
      {err && <div role="alert" className="rounded-lg px-3 py-2 text-[12px]" style={{ background: '#FEF2F2', color: '#B91C1C', border: '1px solid #FECACA' }}>{err}</div>}
      <div className="flex items-center justify-end gap-2 pt-1">
        <Button onClick={() => window.ui.closeModal()}>取消</Button>
        <Button variant="primary" icon={IconCheck} disabled={saving || tenantsLoading || Boolean(tenantsError) || !form.tenantId} onClick={submit}>{saving ? '创建中…' : '创建账号'}</Button>
      </div>
    </div>
  );
}

function SuperAccountsPage() {
  const [accounts, reloadAccounts, , , accountsError] = useSuperResource('/admin/accounts', SUPER_ACCOUNTS);
  const [accountQ, setAccountQ] = React.useState('');
  const [accountRoleF, setAccountRoleF] = React.useState('全部');
  const [accountTenantF, setAccountTenantF] = React.useState('全部');
  const [accountStatusF, setAccountStatusF] = React.useState('全部');
  const accountStatusValue = { '近 1 小时登录': 'online', '其他账号': 'offline' }[accountStatusF];
  const filteredAccounts = accounts
    .filter((a) => !accountQ || String(a.name || '').includes(accountQ)
      || String(a.username || '').toLowerCase().includes(accountQ.toLowerCase())
      || String(a.email || '').toLowerCase().includes(accountQ.toLowerCase()))
    .filter((a) => accountRoleF === '全部' || a.role === accountRoleF)
    .filter((a) => accountTenantF === '全部' || a.tenant === accountTenantF)
    .filter((a) => accountStatusF === '全部' || a.status === accountStatusValue);
  const accountAction = async (acc, action, label) => {
    const accountId = acc.uuid || acc.id;
    if (!accountId) {
      window.ui.toast('账号缺少后端 ID', { kind: 'warning' });
      return;
    }
    return superPost(`/admin/accounts/${accountId}/${action}`, {}, reloadAccounts, label);
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
    { key: 'department', label: '角色分类', width: 100,
      render: (r) => <span className="text-[12.5px]" style={{ color: 'var(--text-medium)' }}>{r.department}</span> },
    { key: 'role',       label: '角色',     width: 100,
      render: (r) => {
        const c = { super: 'rose', admin: 'indigo', viewer: 'slate' }[r.role];
        const l = { super: '超管', admin: '管理员', viewer: '只读' }[r.role];
        return <Tag color={c} dot>{l}</Tag>;
      },
    },
    { key: 'twoFactor', label: '二次验证', width: 100, align: 'center',
      render: () => <Tag color="slate">未提供</Tag>,
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
              // 迭代9 F1：原文案谎称"发送至验证邮箱"(本系统无邮件通道)。改为如实：生成一次性临时密码并显示转交。
              const ok = await window.ui.confirm({ title: `重置 ${r.name} 密码?`, desc: '将生成一次性临时密码并显示给你转交（本系统无邮件通道）,该用户全部会话会被登出,其首次登录须改密。', danger: true, confirmText: '重置密码' });
              if (!ok) return;
              const res = await accountAction(r, 'reset-password', false);
              const pwd = res && res.tempPassword;
              if (pwd) {
                await window.ui.modal({
                  title: `${r.name} · 新临时密码`,
                  content: (
                    <div className="space-y-2 text-[13px]">
                      <div style={{ color: 'var(--text-medium)' }}>请将临时密码转交给 <b>{r.name}</b>（{r.email}）。<b>此密码仅显示一次</b>,其首次登录须修改。</div>
                      <div className="font-mono text-[16px] px-3 py-2 rounded-lg select-all" style={{ background: '#F1F5F9', border: '1px solid #CBD5E1', color: 'var(--text-strong)' }}>{pwd}</div>
                    </div>
                  ),
                  size: 'sm',
                });
              }
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
                <Tag color="slate">二次验证状态未提供</Tag>
              </div>
            </div>
          </div>

          {/* 当前接口仅支持读取资料，重置口令使用单独的真实操作。 */}
          <div>
            <div className="font-mono text-[10.5px] uppercase tracking-wider mb-2.5" style={{ color: 'var(--text-soft)' }}>
              登录凭证 · 资料只读
            </div>
            <div className="space-y-3">
              {[
                { l: '登录账号', v: acc.username || acc.email || '未提供', mono: true, hint: '当前登录标识' },
                { l: '当前密码', v: '不可读取', hint: '重置后仅展示一次临时口令' },
                { l: '联系邮箱', v: acc.email || '未提供', mono: true, hint: '仅显示后台记录，未核验邮箱' },
                { l: '手机号码', v: acc.phone || '未提供', mono: true, hint: '仅显示后台记录，未接入短信' },
              ].map((f) => (
                <div key={f.l} className="grid grid-cols-[90px_1fr_auto] gap-3 items-center">
                  <label className="text-[12px] text-right" style={{ color: 'var(--text-medium)' }}>{f.l}</label>
                  <div className="flex items-center h-10 px-3 rounded-lg"
                    style={{ background: 'var(--surface-muted)', border: '1px solid var(--border-soft)' }}>
                    <input value={f.v} readOnly aria-label={f.l}
                      className="flex-1 bg-transparent outline-none text-[13px]"
                      style={{ color: 'var(--text-medium)', fontFamily: f.mono ? 'Geist Mono' : undefined, minWidth: 0 }} />
                    {(
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
            <p className="text-[13px] leading-relaxed" style={{ color: 'var(--text-medium)' }}>二次验证、密码周期、跨地区登录与单账号会话时长设置尚无可用接口，不能在此查看或修改。下方仅提供已接入的账号操作。</p>
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
                  const ok = await window.ui.confirm({ title: `禁用 ${acc.name} 账号?`, desc: '账号将无法登录，其现有会话将被撤销。当前页面未提供恢复账号接口。', danger: true });
                  if (ok) await accountAction(acc, 'disable', `${acc.name} 账号已禁用`);
                }}>禁用账号</Button>
              <Button size="sm" danger
                onClick={async () => {
                  const ok = await window.ui.confirm({ title: `删除 ${acc.name}?`, desc: '将软删除账号、撤销全部会话并释放原登录标识。关联历史记录保留，当前没有恢复入口。', danger: true, confirmText: '确认删除' });
                  if (ok) await accountAction(acc, 'delete', `${acc.name} 已删除`);
                }}>删除账号</Button>
            </div>
          </div>
        </div>
      ),
      footer: (
        <>
          <Button onClick={() => window.ui.closeDrawer()}>关闭</Button>
          <Button icon={IconUpload}
            onClick={async () => {
              // 迭代10 P0：此前是纯假 toast「已发送临时密码至验证邮箱」——未调用任何后端、本系统也无邮件通道,
              // 口令实际没重置、会话没登出。改为与行内「重置密码」(见上方 columns 操作列)一致的真副作用：
              // 生成一次性临时密码→弹窗显示转交,并登出该用户全部会话、其首次登录须改密。
              const ok = await window.ui.confirm({ title: `重置 ${acc.name} 密码?`, desc: '将生成一次性临时密码并显示给你转交（本系统无邮件通道）,该用户全部会话会被登出,其首次登录须改密。', danger: true, confirmText: '重置密码' });
              if (!ok) return;
              const res = await accountAction(acc, 'reset-password', false);
              const pwd = res && res.tempPassword;
              if (pwd) {
                await window.ui.modal({
                  title: `${acc.name} · 新临时密码`,
                  content: (
                    <div className="space-y-2 text-[13px]">
                      <div style={{ color: 'var(--text-medium)' }}>请将临时密码转交给 <b>{acc.name}</b>（{acc.email}）。<b>此密码仅显示一次</b>,其首次登录须修改。</div>
                      <div className="font-mono text-[16px] px-3 py-2 rounded-lg select-all" style={{ background: '#F1F5F9', border: '1px solid #CBD5E1', color: 'var(--text-strong)' }}>{pwd}</div>
                    </div>
                  ),
                  size: 'sm',
                });
              }
            }}>
            重置密码
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
          <Button icon={IconUpload} disabled title="尚无账号批量导入接口，请使用新增账号">批量导入未接入</Button>
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
          label="最近登录账号" sublabel="近 1 小时登录"
          value={accounts.filter(a => a.status === 'online').length} unit="人"
          footnote="按上次登录时间统计，不代表当前在线会话"
          icon={IconCheck} accent="#059669"
          gradient={FEATURE_GRADIENTS.mint} orbColor="rgba(52,211,153,.4)"
        />
        <FeatureKPI
          label="超级管理员" sublabel="超级管理员"
          value={accounts.filter(a => a.role === 'super').length} unit="人"
          footnote="平台级最高权限"
          icon={IconSettings} accent="#7C3AED"
          gradient={FEATURE_GRADIENTS.violet} orbColor="rgba(167,139,250,.4)"
        />
        <FeatureKPI
          label="二次验证状态" sublabel="尚未接入"
          value="—"
          footnote="后台未提供真实二次验证状态"
          icon={IconAlert} accent="#DC2626"
          gradient={FEATURE_GRADIENTS.rose} orbColor="rgba(244,114,182,.42)"
        />
        <FeatureKPI
          label="已加载账号" sublabel="当前记录"
          value={accounts.length} unit="人"
          footnote="接口返回的未删除账号"
          icon={IconUsers} accent="#2563EB"
          gradient={FEATURE_GRADIENTS.azure} orbColor="rgba(96,165,250,.4)"
        />
      </div>

      <SuperApiErrorBanner errors={[accountsError]} />

      {/* P18 批次7：筛选真实接线；角色/租户选项从真实数据推导（原「北京一中/上海中学」是编造租户）。 */}
      <FilterBar
        search="搜索姓名、用户名、邮箱..."
        searchValue={accountQ}
        onSearch={setAccountQ}
        filters={[
          { label: '角色:', value: accountRoleF, options: ['全部', ...Array.from(new Set(accounts.map((a) => a.role).filter(Boolean)))], onChange: setAccountRoleF },
          { label: '租户:', value: accountTenantF, options: ['全部', ...Array.from(new Set(accounts.map((a) => a.tenant).filter(Boolean)))], onChange: setAccountTenantF },
          { label: '登录记录:', value: accountStatusF, options: ['全部', '近 1 小时登录', '其他账号'], onChange: setAccountStatusF },
        ]}
        total={filteredAccounts.length}
        onReset={() => { setAccountQ(''); setAccountRoleF('全部'); setAccountTenantF('全部'); setAccountStatusF('全部'); }}
      />

      <DataTable columns={cols} rows={filteredAccounts} selectable />
    </PageShell>
  );
}

// ── 4. System Settings ────────────────────────────────────────────────
const SUPER_PLATFORM_SETTING_KEYS = ['emailNotif', 'twoFactor', 'guestAccess', 'autoBackup', 'maintenance'];

function normalizeSuperPlatformSettings(value) {
  if (!value || typeof value !== 'object' || SUPER_PLATFORM_SETTING_KEYS.some((key) => typeof value[key] !== 'boolean')) {
    throw new Error('配置数据不完整，请重新加载后核对');
  }
  return Object.fromEntries(SUPER_PLATFORM_SETTING_KEYS.map((key) => [key, value[key]]));
}

async function saveSuperPlatformSettings(draft) {
  if (!window.api?.put || !window.api?.get) throw new Error('配置接口不可用，请刷新后重试');
  const payload = normalizeSuperPlatformSettings(draft);
  await window.api.put('/admin/platform-settings', payload);
  let persisted;
  try {
    persisted = normalizeSuperPlatformSettings(await window.api.get('/admin/platform-settings'));
  } catch (_) {
    throw new Error('保存请求已提交，但回读失败。请重新加载核对，勿将此状态视为保存完成');
  }
  if (SUPER_PLATFORM_SETTING_KEYS.some((key) => persisted[key] !== payload[key])) {
    throw new Error('配置回读与本次修改不一致，可能已被其他管理员修改。请重新加载核对');
  }
  return persisted;
}

function SuperSystemSettingsPage() {
  const [switches, setSwitches] = React.useState(null);
  const [savedSettings, setSavedSettings] = React.useState(null);
  const [settingsLoading, setSettingsLoading] = React.useState(true);
  const [saving, setSaving] = React.useState(false);
  const [settingsError, setSettingsError] = React.useState(null);
  const [message, setMessage] = React.useState('');
  const [reloadToken, setReloadToken] = React.useState(0);
  const savingRef = React.useRef(false);
  React.useEffect(() => {
    let live = true;
    setSettingsLoading(true);
    setSettingsError(null);
    Promise.resolve().then(() => {
      if (!window.api?.get) throw new Error('配置接口不可用，请刷新后重试');
      return window.api.get('/admin/platform-settings');
    }).then((response) => {
      const next = normalizeSuperPlatformSettings(response);
      if (live) { setSwitches(next); setSavedSettings(next); setMessage('已加载后台保存的配置值'); }
    }).catch((error) => {
      if (live) setSettingsError(error.message || '配置加载失败，请重试');
    }).finally(() => { if (live) setSettingsLoading(false); });
    return () => { live = false; };
  }, [reloadToken]);
  const dirty = Boolean(savedSettings && switches && SUPER_PLATFORM_SETTING_KEYS.some((key) => switches[key] !== savedSettings[key]));
  const saveSettings = async () => {
    if (savingRef.current || settingsLoading || !dirty) return;
    savingRef.current = true;
    setSaving(true);
    try {
      const ok = await window.ui.confirm({
        title: '保存平台配置值？',
        desc: '此操作只保存配置并记录审计，不会开启尚未接入的邮件、二次验证、访客访问、自动备份或维护页面。',
        confirmText: '确认保存配置',
      });
      if (!ok) return;
      const next = await saveSuperPlatformSettings(switches);
      setSavedSettings(next);
      setSwitches(next);
      setSettingsError(null);
      setMessage('配置已保存并回读确认；功能接入状态未发生变化');
      window.ui.toast('配置值已保存', { kind: 'success', desc: '已通过后台回读确认' });
    } catch (error) {
      setSettingsError(error.message || '保存失败，修改已保留，请重试');
    } finally {
      savingRef.current = false;
      setSaving(false);
    }
  };

  return (
    <PageShell
      crumbs={['JKXT 平台', '超管控制台', '系统设置']}
      title="系统设置"
      subtitle="查看部署信息与保存平台配置，功能接入状态单独说明"
      actions={
        <>
          <Button disabled={settingsLoading || saving || !dirty} onClick={() => {
            setSwitches({ ...savedSettings });
            setMessage('已撤销尚未保存的修改，恢复为上次回读的配置');
          }}>撤销修改</Button>
          <Button variant="primary" icon={IconCheck} loading={saving} disabled={settingsLoading || !dirty} onClick={saveSettings}>保存配置值</Button>
        </>
      }
    >
      {settingsError && <div className="settings-status settings-status--error" role="alert">
        <span>{settingsError}</span>
        <Button size="sm" disabled={settingsLoading || saving} onClick={() => setReloadToken((value) => value + 1)}>重新加载</Button>
      </div>}
      <p className="settings-caption" role="status">{settingsLoading ? '正在读取平台配置…' : dirty ? '有未保存的配置修改' : message}</p>
      <div className="settings-layout">
        {/* Basics */}
        <section className="settings-panel" aria-label="平台部署信息">
          <SectionTitle title="平台基础信息" sub="当前为只读信息，后台尚未提供品牌信息修改接口" />
          <dl className="settings-readonly-fields">
          {[
            { l: '产品名称', v: 'Quick 排监考' },
            { l: '当前访问地址', v: window.location?.origin || '未提供', mono: true },
            { l: '联系邮箱', v: '后台未提供' },
            { l: '平台时区', v: '后台未提供可配置项' },
            { l: '界面语言', v: '简体中文' },
            { l: '默认学段', v: '后台未提供' },
          ].map((r) => (
            <div key={r.l} className="settings-field">
              <dt>{r.l}</dt>
              <dd className={r.mono ? 'settings-identifier' : ''}>{r.v}</dd>
            </div>
          ))}
          <div className="settings-field">
            <dt>系统 Logo</dt>
            <dd>
              <span>由部署资源管理</span>
              <p className="settings-caption">暂不支持在线上传；当前页面不会替换已有 Logo。</p>
            </dd>
          </div>
          </dl>
        </section>

        <section className="settings-panel" aria-label="平台配置值">
          <SectionTitle title="平台配置值" sub="以下开关仅保存配置，不代表对应功能已启用" />
          <div className="settings-notice">
            邮件通道、强制二次验证、公开访客页、定时云备份与维护拦截尚未接入这些配置。请勿将开关状态作为安全或备份已生效的依据。
          </div>
          <ul className="settings-switch-list" aria-busy={settingsLoading || saving}>
            {[
              { k: 'emailNotif', label: '邮件通知', desc: '通知通道尚未接入' },
              { k: 'twoFactor', label: '二次验证', desc: '未实现：后端拒绝开启，状态恒为「否」', unavailable: true },
              { k: 'guestAccess', label: '访客访问', desc: '公开只读页面尚未接入' },
              { k: 'autoBackup', label: '自动备份', desc: '未实现：后端拒绝开启，状态恒为「否」', unavailable: true },
              { k: 'maintenance', label: '维护模式', desc: '未实现：后端拒绝开启，状态恒为「否」', unavailable: true },
            ].map((s) => (
              <li key={s.k} className="settings-switch-row">
                <div className="settings-switch-copy">
                  <div className="settings-switch-label" id={`platform-${s.k}-label`}>{s.label}</div>
                  <p className="settings-caption" id={`platform-${s.k}-hint`}>{s.desc}</p>
                </div>
                <button type="button" role="switch" className="settings-switch"
                  aria-labelledby={`platform-${s.k}-label`} aria-describedby={`platform-${s.k}-hint`}
                  aria-checked={Boolean(switches?.[s.k])}
                  disabled={settingsLoading || saving || !switches || s.unavailable}
                  onClick={() => setSwitches((previous) => ({ ...previous, [s.k]: !previous[s.k] }))}>
                  <span className="settings-switch-track" aria-hidden="true"><span /></span>
                  <span className="settings-caption">{switches ? (switches[s.k] ? '是' : '否') : '未加载'}</span>
                </button>
              </li>
            ))}
          </ul>
        </section>
      </div>
    </PageShell>
  );
}

// ── 5. Audit ──────────────────────────────────────────────────────────
function buildSuperAuditExport(rows, format, filters) {
  if (!Array.isArray(rows)) throw new Error('审计记录不可用');
  if (format === 'json') {
    return {
      extension: 'json', mime: 'application/json;charset=utf-8',
      content: JSON.stringify({ exportedAt: new Date().toISOString(), filters, sourceLimit: 200, recordCount: rows.length, records: rows }, null, 2),
    };
  }
  if (format !== 'csv') throw new Error('不支持的导出格式');
  const columns = [
    ['uuid', '记录 UUID'], ['id', '显示编号'], ['at', '时间'], ['actor', '操作人'],
    ['tenant', '租户'], ['action', '操作'], ['target', '目标类型'], ['targetId', '目标 ID'],
    ['severity', '级别'], ['ip', 'IP'], ['payload', '原始内容'],
  ];
  const cell = (value) => {
    let text = value == null ? '' : typeof value === 'object' ? JSON.stringify(value) : String(value);
    // Quoting alone does not stop spreadsheet formula execution after opening CSV.
    if (/^[\s\uFEFF]*[=+@-]/.test(text) || /^[\t\r\n]/.test(text)) text = "'" + text;
    return '"' + text.replace(/"/g, '""') + '"';
  };
  return {
    extension: 'csv', mime: 'text/csv;charset=utf-8',
    content: '\uFEFF' + [columns.map(([, label]) => cell(label)).join(','), ...rows.map((row) => columns.map(([key]) => cell(row[key])).join(','))].join('\r\n'),
  };
}

function downloadSuperAuditExport(rows, format, filters) {
  const output = buildSuperAuditExport(rows, format, filters);
  const url = URL.createObjectURL(new Blob([output.content], { type: output.mime }));
  const link = document.createElement('a');
  link.href = url;
  link.download = `JKXT-审计日志-${new Date().toISOString().slice(0, 10)}.${output.extension}`;
  try { document.body.appendChild(link); link.click(); }
  finally { link.remove(); setTimeout(() => URL.revokeObjectURL(url), 1000); }
}

function SuperAuditPage() {
  // P18 批次7b：时间窗为后端数据层过滤（?hours=N），不是前端装样子；path 变化触发 hook 自动重取。
  const [auditWindow, setAuditWindow] = React.useState('近 7 天');
  const AUDIT_WINDOW_HOURS = { '近 24 小时': 24, '近 7 天': 168, '近 30 天': 720, '全部': null };
  const auditPath = AUDIT_WINDOW_HOURS[auditWindow]
    ? `/admin/audit-logs?hours=${AUDIT_WINDOW_HOURS[auditWindow]}` : '/admin/audit-logs';
  const [auditRows, reloadAudit, auditLoading, , auditError, auditLoadedPath] = useSuperResource(auditPath, SUPER_AUDIT);
  const auditPending = auditLoading || (!auditError && auditLoadedPath !== auditPath);
  const [auditQ, setAuditQ] = React.useState('');
  const [auditSevF, setAuditSevF] = React.useState('全部');
  const [auditTenantF, setAuditTenantF] = React.useState(() => {
    const requestedTenant = window.__jkxtSuperAuditTenant;
    delete window.__jkxtSuperAuditTenant;
    return requestedTenant || '全部';
  });
  const filteredAuditRows = auditRows
    .filter((r) => !auditQ || String(r.actor || '').includes(auditQ)
      || String(r.action || '').toLowerCase().includes(auditQ.toLowerCase())
      || String(r.target || '').toLowerCase().includes(auditQ.toLowerCase()))
    .filter((r) => auditSevF === '全部' || r.severity === auditSevF)
    .filter((r) => auditTenantF === '全部' || r.tenant === auditTenantF);
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
                  <div className="settings-caption mb-2" style={{ color: 'var(--text-soft)' }}>操作摘要</div>
                  <div className="text-[14px] font-semibold" style={{ color: 'var(--text-strong)' }}>{r.action}</div>
                  <div className="text-[12px] mt-1" style={{ color: 'var(--text-medium)' }}>目标对象 · <span className="font-mono">{r.target}</span></div>
                </div>
                <div className="rounded-lg p-3 font-mono text-[11.5px]" style={{ background: '#0F172A', color: '#CBD5E1' }}>
                  <pre style={{ whiteSpace: 'pre-wrap' }}>
                    {JSON.stringify(r, null, 2)}
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
      badge={auditPending ? '加载中' : `${auditRows.length} 条已加载记录`}
      subtitle="展示所选时间范围内最新 200 条记录；筛选与导出仅针对当前已加载数据"
      actions={<Button variant="primary" icon={IconUpload} disabled={auditPending || Boolean(auditError) || !filteredAuditRows.length} onClick={() => {
        const snapshot = filteredAuditRows.map((row) => ({ ...row }));
        const filters = { window: auditWindow, severity: auditSevF, tenant: auditTenantF, search: auditQ };
        let format = 'csv';
        window.ui.modal({
        title: '导出审计日志',
        subtitle: `当前筛选结果，共 ${snapshot.length} 条`,
        size: 'md',
        content: (
          <div className="settings-audit-export space-y-3">
            <p>{filters.window} · 级别：{filters.severity} · 租户：{filters.tenant}</p>
            {filters.search && <p>关键词：{filters.search}</p>}
            <label htmlFor="audit-export-format">文件格式</label>
            <select id="audit-export-format" defaultValue="csv" onChange={(event) => { format = event.target.value; }}>
              <option value="csv">CSV (.csv)，可用 Excel 打开</option>
              <option value="json">JSON (.json)，保留原始字段与筛选信息</option>
            </select>
            <p className="settings-caption">文件直接下载到本机，不发送邮件。当前接口最多返回 200 条，不能用本文件代替全量归档；本地下载行为未写入服务端审计。</p>
          </div>
        ),
        footer: (
          <>
            <Button onClick={() => window.ui.closeModal()}>取消</Button>
            <Button variant="primary" icon={IconUpload} onClick={() => {
              try {
                downloadSuperAuditExport(snapshot, format, filters);
                window.ui.closeModal();
                window.ui.toast('已生成文件并发起下载', { kind: 'success', desc: `${snapshot.length} 条记录；请在浏览器下载列表查看` });
              } catch (error) {
                window.ui.toast('导出失败', { kind: 'error', desc: error.message || '请重试' });
              }
            }}>
              下载文件
            </Button>
          </>
        ),
      }); }}>导出日志</Button>}
    >
      <div className="grid grid-cols-4 gap-4">
        <FeatureKPI
          label="已加载记录" sublabel="当前时间范围"
          value={auditRows.length} unit="条"
          footnote="最多显示最新 200 条"
          icon={IconList} accent="#2563EB"
          gradient={FEATURE_GRADIENTS.azure} orbColor="rgba(96,165,250,.4)"
        />
        <FeatureKPI
          label="高危事件" sublabel="高危"
          value={auditRows.filter(l => l.severity === 'high').length} unit="条"
          footnote="仅表示事件级别，不代表已处理"
          icon={IconAlert} accent="#DC2626"
          gradient={FEATURE_GRADIENTS.rose} orbColor="rgba(244,114,182,.42)"
        />
        <FeatureKPI
          label="警告事件" sublabel="警告"
          value={auditRows.filter(l => l.severity === 'warn').length} unit="条"
          footnote="需关注"
          icon={IconBell} accent="#D97706"
          gradient={FEATURE_GRADIENTS.amber} orbColor="rgba(251,191,36,.42)"
        />
        <FeatureKPI
          label="出现的操作员" sublabel="当前已加载记录"
          value={new Set(auditRows.map(l => l.actor)).size} unit="人"
          footnote={`涉及 ${new Set(auditRows.map((row) => row.tenant).filter((tenant) => tenant && tenant !== '-')).size} 个租户`}
          icon={IconUsers} accent="#7C3AED"
          gradient={FEATURE_GRADIENTS.violet} orbColor="rgba(167,139,250,.4)"
        />
      </div>

      <SuperApiErrorBanner errors={[auditError]} />
      {auditError && <Button size="sm" onClick={reloadAudit} disabled={auditLoading}>重新加载日志</Button>}
      {auditPending && <p className="settings-caption" role="status">正在读取审计记录，暂不可导出…</p>}

      {/* P18 批次7：筛选真实接线；7b 补时间窗（后端 ?hours=N 数据层过滤，非前端装样子）。
          级别/租户选项从真实数据推导（原「北京一中/上海中学」是编造租户）。 */}
      <FilterBar
        search="搜索操作人、操作、目标..."
        searchValue={auditQ}
        onSearch={setAuditQ}
        filters={[
          { label: '时间:', value: auditWindow, options: ['近 24 小时', '近 7 天', '近 30 天', '全部'], onChange: setAuditWindow },
          { label: '级别:', value: auditSevF, options: ['全部', ...Array.from(new Set(auditRows.map((r) => r.severity).filter(Boolean)))], onChange: setAuditSevF },
          { label: '租户:', value: auditTenantF, options: ['全部', ...Array.from(new Set([auditTenantF, ...auditRows.map((r) => r.tenant)].filter((tenant) => tenant && tenant !== '全部')))], onChange: setAuditTenantF },
        ]}
        total={filteredAuditRows.length}
        onReset={() => { setAuditQ(''); setAuditSevF('全部'); setAuditTenantF('全部'); setAuditWindow('近 7 天'); }}
      />
      <DataTable columns={cols} rows={filteredAuditRows} />
    </PageShell>
  );
}

// ── 6. Security ───────────────────────────────────────────────────────
async function scanSuperBackupArtifacts(reload) {
  const result = await superPost('/admin/backups/run', {}, null, false);
  if (!result) return null;
  if (reload) await reload();
  if (result.status === 'unavailable') {
    window.ui.toast('未登记新的备份文件', { kind: 'info', desc: result.message || '请先由运维在宿主机执行备份，再扫描登记。' });
  } else if (result.status === 'succeeded' && result.id) {
    window.ui.toast('备份文件已登记', { kind: 'success', desc: '本次仅扫描现有文件，不代表执行了备份、云端同步或恢复验证。' });
  } else {
    window.ui.toast('无法确认备份登记结果', { kind: 'error', desc: '后台未返回有效备份记录，请刷新后核对。' });
  }
  return result;
}

function SuperSecurityPage() {
  const [security, reloadSecurity, , securityError] = useSuperObject('/admin/security/overview', SUPER_SEC);
  const [scanning, setScanning] = React.useState(false);
  const scanningRef = React.useRef(false);
  const threats = Array.isArray(security.threats) ? security.threats : SUPER_SEC.threats;
  const backups = Array.isArray(security.backups) ? security.backups : [];
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
                    <p className="text-[13px] leading-relaxed" style={{ color: 'var(--text-medium)' }}>此接口未提供目标账号或网关拦截结果，不能据此确认请求已被拦截。请结合实际审计日志与网关记录核查。</p>
                  </div>
                </div>
              ),
              footer: <Button onClick={() => window.ui.closeDrawer()}>关闭</Button>,
            })}>详情</button>
          <span className="text-slate-300">·</span>
          {/* PB-8 (round 9): the backend does not enforce IP rules and refuses to store them (501). */}
          <button type="button" disabled className="text-[12px] font-medium px-1.5" style={{ color: 'var(--text-soft)', cursor: 'not-allowed' }}
            title="IP 封禁未接入：本系统不执行 IP 拦截，请在防火墙/网关配置">封禁（未接入）</button>
          <span className="text-slate-300">·</span>
          <button type="button" disabled className="text-[12px] font-medium px-1.5" style={{ color: 'var(--text-soft)', cursor: 'not-allowed' }}
            title="IP 白名单未接入：本系统不执行 IP 放行，请在防火墙/网关配置">可信（未接入）</button>
        </div>
      ),
    },
  ];

  return (
    <PageShell
      crumbs={['JKXT 平台', '超管控制台', '安全中心']}
      title="安全中心"
      badge="后台记录"
      subtitle="查看登录失败、安全事件、规则登记与现有备份文件；不代表已完成安全评估"
      actions={<Button variant="primary" icon={IconUpload} disabled={scanning} onClick={async () => {
        if (scanningRef.current) return;
        scanningRef.current = true;
        setScanning(true);
        try {
        const ok = await window.ui.confirm({
          title: '扫描并登记现有备份文件?',
          desc: '本操作扫描服务器备份目录，不会创建新备份或上传云端。请先由运维执行宿主机备份脚本；登记成功也不代表已验证可恢复。',
          confirmText: '扫描文件',
        });
        if (ok) await scanSuperBackupArtifacts(reloadSecurity);
        } finally { scanningRef.current = false; setScanning(false); }
      }}>{scanning ? '扫描中…' : '扫描备份文件'}</Button>}
    >
      <SuperApiErrorBanner errors={[securityError]} />
      <GlassPanel className="p-6">
        <h3 className="text-[16px] font-bold mb-2" style={{ color: 'var(--text-strong)' }}>安全能力与记录边界</h3>
        <p className="text-[14px] leading-relaxed" style={{ color: 'var(--text-medium)' }}>当前可查询安全事件并扫描备份文件；IP 封禁/白名单未接入（系统不执行也不登记 IP 规则）。安全评分、二次验证覆盖率、网关拦截统计与备份恢复验证尚未接入，不以固定分数表示平台安全状态。</p>
        <p className="text-[13px] leading-relaxed mt-2" style={{ color: 'var(--text-soft)' }}>IP 拦截请在防火墙/网关配置；备份登记不等于创建备份、云端同步或恢复演练。以上执行能力需要运维系统支持。</p>
      </GlassPanel>

      <div className="grid grid-cols-4 gap-4">
        <FeatureKPI
          label="失败登录记录" sublabel="审计记录累计"
          value={security.failedLogins ?? '—'} unit="次"
          footnote="当前留存审计记录，不限最近 24 小时"
          icon={IconAlert} accent="#DC2626"
          gradient={FEATURE_GRADIENTS.rose} orbColor="rgba(244,114,182,.42)"
        />
        <FeatureKPI
          label="未处理安全事件" sublabel="待核查"
          value={security.suspiciousIps ?? '—'} unit="条"
          footnote="事件数量，不代表去重 IP 数"
          icon={IconAlert} accent="#D97706"
          gradient={FEATURE_GRADIENTS.amber} orbColor="rgba(251,191,36,.42)"
        />
        <FeatureKPI
          label="已登记 IP 规则" sublabel="有效规则记录"
          value={security.activeRules ?? '—'} unit="条"
          footnote="仅登记，未接入网关执行"
          icon={IconCheck} accent="#2563EB"
          gradient={FEATURE_GRADIENTS.azure} orbColor="rgba(96,165,250,.4)"
        />
        <FeatureKPI
          label="最近备份记录" sublabel="文件登记时间"
          value={security.lastBackup && security.lastBackup !== 'No backup' ? security.lastBackup : '暂无记录'}
          footnote="未提供自动执行或恢复验证结果"
          icon={IconArchive} accent="#7C3AED"
          gradient={FEATURE_GRADIENTS.violet} orbColor="rgba(167,139,250,.4)"
        />
      </div>

      <GlassPanel className="p-5">
        <SectionTitle title="待核查安全事件" sub="当前未处理记录 · 按威胁等级排序" right={<Button onClick={reloadSecurity}>刷新记录</Button>} />
        <DataTable columns={cols} rows={threats} footer={<></>} />
      </GlassPanel>
      <GlassPanel className="p-5">
        <SectionTitle title="备份文件登记" sub="最近 50 条后台记录 · 不代表已验证可恢复" />
        <DataTable rows={backups} columns={[
          { key: 'id', label: '文件', render: (r) => <span className="break-all">{r.id || '未提供'}</span> },
          { key: 'status', label: '登记状态', render: (r) => r.status || '未提供' },
          { key: 'storage', label: '存储位置', render: (r) => r.storage || '未提供' },
          { key: 'sizeMb', label: '大小（MB）', render: (r) => r.sizeMb == null ? '未提供' : String(r.sizeMb) },
          { key: 'checksum', label: '校验值', render: (r) => <span className="font-mono break-all">{r.checksum || '未提供'}</span> },
          { key: 'finishedAt', label: '记录时间', render: (r) => r.finishedAt || '未提供' },
        ]} footer={<></>} />
      </GlassPanel>
    </PageShell>
  );
}

// ── 7. Announcements ──────────────────────────────────────────────────

function openAnnouncementDrawer(a, sev, actions = {}) {
  const body = a.body || '后台未提供公告正文';
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
          <div className="text-[12px] mb-2" style={{ color: 'var(--text-soft)' }}>公告正文 · 原始文本</div>
          <div className="rounded-xl p-4 text-[14px] leading-relaxed whitespace-pre-wrap"
            style={{ background: 'var(--surface-muted)', border: '1px solid var(--border-soft)', color: 'var(--text-strong)' }}>
            {body}
          </div>
        </div>

        {a.status === 'published' && (
          <p className="text-[13px] leading-relaxed" style={{ color: 'var(--text-medium)' }}>阅读回执、收件人总数与分租户阅读统计尚未接入，当前不能计算阅读率。历史 readBy 字段不代表已核验的阅读人数。</p>
        )}
      </div>
    ),
    footer: (
      <>
        <Button onClick={() => window.ui.closeDrawer()}>关闭</Button>
        <Button icon={IconEdit} disabled={!actions.saveDraft} onClick={() => { window.ui.closeDrawer(); openAnnouncementEditDrawer(a, actions); }}>编辑</Button>
        {a.status === 'published' ? (
          <Button disabled title="邮件与站内信投递通道尚未接入">通知通道未接入</Button>
        ) : (
          <Button variant="primary" icon={IconCheck} disabled={!actions.publish} onClick={async () => {
            const result = await actions.publish(a);
            if (result) window.ui.closeDrawer();
          }}>立即发布</Button>
        )}
      </>
    ),
  });
}

function openAnnouncementEditDrawer(a, actions = {}) {
  const body = a.body || '';
  const formId = `edit-announcement-${a.uuid || a.id}`;
  window.ui.drawer({
    title: `编辑公告 · ${a.id}`,
    subtitle: '保存修改会更新公告正文；发布仅更新状态，不承诺通知送达',
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
              <option>全部租户</option><option disabled>选定租户</option><option>管理员</option>
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
        <Button icon={IconArchive} disabled={!actions.saveDraft} onClick={async () => {
          const payload = announcementFormPayload(formId, a);
          const result = await actions.saveDraft(a, payload);
          if (result) window.ui.closeDrawer();
        }}>保存修改</Button>
        <Button variant="primary" icon={IconCheck} disabled={!actions.saveAndPublish} onClick={async () => {
          const payload = announcementFormPayload(formId, { ...a, status: 'published' });
          const result = await actions.saveAndPublish(a, payload);
          if (result) window.ui.closeDrawer();
        }}>
          保存并发布
        </Button>
      </>
    ),
  });
}

function SuperAnnouncementsPage() {
  const [announcements, reloadAnnouncements, , , announcementsError] = useSuperResource('/admin/announcements', SUPER_ANNOUNCEMENTS);
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
    if (!payload.title) { window.ui.toast('请填写公告标题', { kind: 'error' }); return null; }
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
    if (!payload.title?.trim()) { window.ui.toast('请填写公告标题', { kind: 'error' }); return null; }
    const announcementId = superBackendId(a);
    if (!announcementId) {
      window.ui.toast('公告缺少后端 ID', { kind: 'warning' });
      return null;
    }
    const { status, ...fields } = payload;
    return superPut(`/admin/announcements/${announcementId}`, fields, reload ? reloadAnnouncements : null, label);
  };
  const announcementActions = {
    publish: (a) => announcementAction(a, 'publish', '公告已发布'),
    retract: (a) => announcementAction(a, 'retract', '公告已撤回'),
    saveDraft: (a, payload) => updateAnnouncement(a, payload, true, '公告修改已保存'),
    saveAndPublish: async (a, payload) => {
      const updated = await updateAnnouncement(a, payload, false, false);
      if (!updated) return null;
      return announcementAction(a, 'publish', '公告已保存并发布；通知通道未接入');
    },
  };
  const publishedCount = announcements.filter(a => a.status === 'published').length;
  const draftCount = announcements.filter(a => a.status === 'draft').length;
  return (
    <PageShell
      crumbs={['JKXT 平台', '超管控制台', '公告发布']}
      title="公告发布"
      badge={`${announcements.length} 条公告`}
      subtitle="管理公告正文与发布状态；定时发布、通知投递与阅读回执尚未接入"
      actions={<Button variant="primary" icon={IconPlus} onClick={() => window.ui.modal({
        title: '撰写公告',
        subtitle: '保存原始正文，可设置失效日期；不支持定时发布与邮件投递',
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
                  <option>全部租户</option><option disabled>选定租户（尚未接入）</option><option>仅管理员</option>
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
          label="已发布" sublabel="已发布"
          value={publishedCount} unit="条"
          footnote="仅统计公告发布状态"
          icon={IconCheck} accent="#059669"
          gradient={FEATURE_GRADIENTS.mint} orbColor="rgba(52,211,153,.4)"
        />
        <FeatureKPI
          label="草稿" sublabel="草稿"
          value={draftCount} unit="条"
          footnote="待审核与发布"
          icon={IconEdit} accent="#7C3AED"
          gradient={FEATURE_GRADIENTS.violet} orbColor="rgba(167,139,250,.4)"
        />
        <FeatureKPI
          label="阅读回执" sublabel="尚未接入"
          value="—"
          footnote="没有可核验的收件人阅读记录"
          icon={IconUsers} accent="#2563EB"
          gradient={FEATURE_GRADIENTS.azure} orbColor="rgba(96,165,250,.4)"
        />
        <FeatureKPI
          label="平均阅读率" sublabel="阅读率"
          value="—"
          footnote="收件人分母与阅读回执未提供"
          icon={IconChart} accent="#D97706"
          gradient={FEATURE_GRADIENTS.amber} orbColor="rgba(251,191,36,.42)"
        />
      </div>

      <SuperApiErrorBanner errors={[announcementsError]} />

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
                                               <ProductGlyph name="announcement" size={22} />}
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
                  <div className="text-[12px]" style={{ color: 'var(--text-soft)' }}>阅读回执</div>
                  <div className="text-[13px] mt-1" style={{ color: 'var(--text-medium)' }}>尚未接入</div>
                </div>
              </div>
              <div className="px-5 pl-6 py-2.5 flex items-center gap-2"
                style={{ borderTop: '1px solid var(--border-soft)', background: '#FAFBFC' }}
                onClick={(e) => e.stopPropagation()}>
                <Button size="sm" variant="ghost" onClick={() => openAnnouncementDrawer(a, sev, announcementActions)}>查看详情</Button>
                <Button size="sm" variant="ghost" onClick={() => openAnnouncementEditDrawer(a, announcementActions)}>编辑</Button>
                {a.status === 'published' && (
                  <Button size="sm" variant="ghost" danger onClick={async () => {
                    const ok = await window.ui.confirm({ title: '撤回此公告?', desc: '将公告状态改为已撤回，并保留后台审计记录', danger: true });
                    if (ok) await announcementActions.retract(a);
                  }}>撤回</Button>
                )}
                <div className="flex-1" />
                <span className="text-[12px]" style={{ color: 'var(--text-soft)' }}>暂不提供阅读率</span>
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
