// Step 4 · 生成结果 (Result Panel)
// 5 段架构: 摘要 / 公平性 / AI 建议 / 4 下载 / 底部操作
// 叙事重心: "已生成预览方案" → 引导教务老师"复核",而非"庆祝完成"

// Result adapters never synthesize operational facts from demonstration data.
function qkBackendFairness(raw) {
  if (!Array.isArray(raw)) return [];
  const idMap = { avgLoad: 'avg', maxDiff: 'spread', homeroomShare: 'head', corridorShare: 'bldg', notesApplied: 'notes' };
  return raw.filter((item) => item && typeof item === 'object').map((item, index) => {
    const legacyPlaceholder = ['corridorShare', 'notesApplied'].includes(item.id) && /^P6 fixture/.test(item.detail || '');
    const unavailable = item.status === 'unavailable' || legacyPlaceholder;
    return {
    id: idMap[item.id] || item.id || `f${index}`,
    label: item.label || '未命名指标', value: unavailable ? '—' : item.value ?? '未提供', unit: '',
    detail: unavailable ? (!legacyPlaceholder && item.detail || '此项未统计，不计入公平性评估。') : item.detail || '',
    dots: unavailable ? null : item.status === 'good' ? 5 : item.status === 'warn' ? 3 : item.status === 'ok' ? 4 : null,
    rating: unavailable ? '未统计' : item.status === 'good' ? '良好' : item.status === 'warn' ? '待复核' : item.status === 'ok' ? '合格' : '未评级',
  }; });
}

function qkBackendAi(raw) {
  if (!raw || !Array.isArray(raw.targets) || !Array.isArray(raw.summaries)) return null;
  const targets = raw.targets.filter((target) => target && typeof target === 'object').map((target) => ({
    kind: target.kind, name: target.label || '未命名对象', meta: target.reason || '',
  }));
  return {
    verdict: raw.summaries[0] || '服务端未提供整体评估，不能据此判断约束全部通过。',
    reviews: targets.map((target) => ({ sev: target.kind === 'teacher' ? 'warn' : 'info', who: target.name, msg: target.meta })),
    targets, nextSteps: raw.summaries.slice(1),
  };
}

function qkBackendReady(result) {
  return ['succeeded', 'scheduled', 'published'].includes(String(result?.status || '').toLowerCase());
}

function qkBackendHref(path) {
  if (!path) return '';
  const clean = String(path).replace(/^\/api\/v1/, '');
  return window.api?.url ? window.api.url(clean) : path;
}

function qkBackendDownloads(result) {
  const definitions = [
    { id: 'overall', key: 'xlsx', title: '完整监考排班表', sub: '服务器提供的完整 XLSX', format: 'XLSX', icon: IconList },
    { id: 'pdf', key: 'pdf', title: '监考排班打印版', sub: '服务器提供的 PDF 打印文件', format: 'PDF', icon: IconBook },
  ];
  return definitions.filter((item) => result?.downloads?.[item.key]).map((item) => ({
    ...item, href: qkBackendHref(result.downloads[item.key]), rows: null,
  }));
}

function qkLocalDownloads(schedule) {
  const stats = schedule?.stats || {};
  return [
    { id: 'overall', title: '监考安排总表', rows: schedule?.rows?.length, icon: IconList },
    { id: 'teacher', title: '教师监考分表', rows: stats.teachersUsed, icon: IconUsers },
    { id: 'room', title: '考场安排分表', rows: stats.rooms, icon: IconBuilding },
    { id: 'exam', title: '考试安排分表', rows: stats.exams, icon: IconBook },
  ].map((item) => ({ ...item, sub: '浏览器本地预览数据', format: 'XLSX' }));
}

// ──────────────────────────────────────────────────────────────
// 公平性指标 tooltip 解释 (按 id 索引)
// ──────────────────────────────────────────────────────────────
const QK_FAIRNESS_TIPS = {
  avg:    '反映教师平均工作量 · 数值适中较好 · 过低代表分配不足,过高代表负担偏重',
  spread: '最大与最小监考次数的差距 · 数值越小,教师之间工作量越均衡',
  head:   '班主任承担监考的比例 · 偏低更好,以保障班主任日常班级管理',
  bldg:   '不同楼道分配的均衡性 · 均衡有助于减少教师移动距离',
  notes:  '已自动应用的人员 / 教室特殊备注数量 · 反映备注处理完成度',
};

// ──────────────────────────────────────────────────────────────
// A · 结果摘要 (Receipt 横条)
// ──────────────────────────────────────────────────────────────
function QkResultSummary({ resultAt, base, taskId, dataSource, onScrollToReview, backendReal = false }) {
  const displayId = taskId || '未提供任务编号';
  const fmtTime = (d) => d
    ? d.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
    : '—';

  const fields = [
    { label: '考试场次',   value: base.exams,        unit: '场' },
    { label: '监考人次',   value: base.supervisions, unit: '' },
    { label: '涉及教师',   value: base.teachers,     unit: '人' },
    { label: '涉及考场',   value: base.rooms,        unit: '间' },
  ];

  return (
    <div
      className="rounded-2xl p-5 relative overflow-hidden"
      style={{
        background: 'linear-gradient(180deg, #FFFFFF 0%, #FAFBFC 100%)',
        border: '1px solid var(--border-soft)',
        boxShadow: '0 1px 0 rgba(255,255,255,.95) inset, 0 1px 3px rgba(15,23,42,.04), 0 8px 24px -10px rgba(15,23,42,.10)',
      }}
    >
      <span
        className="absolute inset-x-0 top-0 h-px pointer-events-none"
        style={{ background: 'linear-gradient(90deg, transparent, rgba(16,185,129,.5), transparent)' }}
      />

      <div className="flex items-center gap-5 flex-wrap">
        {/* 状态 */}
        <div className="flex items-center gap-3.5 min-w-0">
          <div
            className="w-12 h-12 rounded-xl flex items-center justify-center shrink-0"
            style={{
              background: 'linear-gradient(180deg, #10B981, #059669)',
              color: '#fff',
              boxShadow: '0 1px 0 rgba(255,255,255,.3) inset, 0 2px 4px -1px rgba(16,185,129,.30), 0 6px 16px -4px rgba(16,185,129,.40)',
            }}
          >
            <IconCheck size={22} strokeWidth={2.5} />
          </div>
          <div className="min-w-0">
            <div className="flex items-center gap-2 flex-wrap">
              <span className="text-[15.5px] font-semibold whitespace-nowrap" style={{ color: 'var(--text-strong)' }}>
                {backendReal ? '已生成后端排监考方案' : '已生成排监考预览方案'}
              </span>
              <span
                className="font-mono text-[9.5px] uppercase tracking-wider px-1.5 py-0.5 rounded font-bold whitespace-nowrap"
                style={{ background: dataSource === 'upload' ? '#DCFCE7' : '#EDE9FE', color: dataSource === 'upload' ? '#15803D' : '#6D28D9', border: `1px solid ${dataSource === 'upload' ? '#86EFAC' : '#C4B5FD'}` }}
              >
                {backendReal ? '后端任务结果' : dataSource === 'upload' ? '基于上传数据' : '基于示例数据'}
              </span>
            </div>
            <div className="text-[11.5px] mt-1 flex items-center gap-2 flex-wrap" style={{ color: 'var(--text-soft)' }}>
              <span className="font-mono font-medium px-1.5 py-0.5 rounded whitespace-nowrap" style={{ background: '#F1F5F9', color: 'var(--text-medium)' }}>
                {displayId}
              </span>
              <span>·</span>
              <IconClock size={11} />
              <span className="font-mono whitespace-nowrap">{fmtTime(resultAt)}</span>
            </div>
          </div>
        </div>

        <div className="h-10 w-px" style={{ background: 'var(--border-soft)' }} />

        {/* 4 个普通字段 */}
        <div className="flex items-end gap-6 flex-wrap flex-1">
          {fields.map((f) => (
            <div key={f.label}>
              <div className="text-[10.5px] font-mono uppercase tracking-wider whitespace-nowrap" style={{ color: 'var(--text-soft)' }}>
                {f.label}
              </div>
              <div className="mt-1 flex items-baseline gap-1">
                <span className="num text-[22px] font-bold tracking-tight" style={{ color: 'var(--text-strong)', letterSpacing: '-0.02em' }}>
                  {f.value ?? '未提供'}
                </span>
                {f.unit && <span className="text-[11.5px]" style={{ color: 'var(--text-medium)' }}>{f.unit}</span>}
              </div>
            </div>
          ))}

          {/* 待人工复核 — 琥珀色突出 + ▲ + 锚点 */}
          <button
            onClick={onScrollToReview}
            className="rounded-lg px-3 py-2 transition-all text-left"
            style={{
              background: 'linear-gradient(180deg, #FFFBEB 0%, #FEF3C7 100%)',
              border: '1px solid #FCD34D',
              boxShadow: '0 1px 0 rgba(255,255,255,.7) inset, 0 1px 2px rgba(180,83,9,.06)',
            }}
            onMouseEnter={(e) => { e.currentTarget.style.transform = 'translateY(-1px)'; e.currentTarget.style.boxShadow = '0 1px 0 rgba(255,255,255,.7) inset, 0 4px 10px -2px rgba(245,158,11,.30)'; }}
            onMouseLeave={(e) => { e.currentTarget.style.transform = 'translateY(0)'; e.currentTarget.style.boxShadow = '0 1px 0 rgba(255,255,255,.7) inset, 0 1px 2px rgba(180,83,9,.06)'; }}
          >
            <div className="text-[10.5px] font-mono uppercase tracking-wider whitespace-nowrap flex items-center gap-1" style={{ color: '#B45309' }}>
              <svg width="9" height="9" viewBox="0 0 10 10"><path d="M5 1 L9 9 L1 9 Z" fill="#D97706" /></svg>
              待人工复核
            </div>
            <div className="mt-1 flex items-baseline gap-1.5">
              <span className="num text-[22px] font-bold tracking-tight" style={{ color: '#92400E', letterSpacing: '-0.02em' }}>
                {base.review ?? '未提供'}
              </span>
              <span className="text-[11.5px]" style={{ color: '#B45309' }}>项</span>
              <span className="text-[11px] font-medium ml-1 inline-flex items-center gap-0.5 whitespace-nowrap" style={{ color: '#92400E' }}>
                查看建议
                <IconChevron dir="down" size={11} strokeWidth={2.5} />
              </span>
            </div>
          </button>
        </div>
      </div>
    </div>
  );
}

// ──────────────────────────────────────────────────────────────
// B · 公平性指标 (5 卡, 点阵评级)
// ──────────────────────────────────────────────────────────────
function QkFairnessDots({ filled, total = 5 }) {
  return (
    <div className="flex items-center gap-0.5">
      {Array.from({ length: total }).map((_, i) => (
        <span
          key={i}
          className="rounded-full"
          style={{
            width: 5, height: 5,
            background: i < filled ? 'var(--brand)' : '#E2E8F0',
            boxShadow: i < filled ? '0 1px 1px rgba(79,70,229,.20)' : 'none',
          }}
        />
      ))}
    </div>
  );
}

function QkFairnessStrip({ fairness }) {
  const ratingColor = {
    '良好':   '#0369A1',
    '合格':   '#047857',
    '一般':   '#B45309',
    '偏低':   '#B91C1C',
    '已处理': '#475569',
  };

  return (
    <div
      className="rounded-2xl p-5"
      style={{
        background: 'linear-gradient(180deg, #FFFFFF 0%, #FCFCFD 100%)',
        border: '1px solid var(--border-soft)',
        boxShadow: '0 1px 0 rgba(255,255,255,.95) inset, 0 1px 3px rgba(15,23,42,.04)',
      }}
    >
      <div className="flex items-center gap-2 mb-4">
        <span
          className="w-7 h-7 rounded-lg flex items-center justify-center shrink-0"
          style={{
            background: 'linear-gradient(180deg, #EEF2FF, #E0E7FF)',
            color: '#4F46E5',
            border: '1px solid rgba(79,70,229,.18)',
          }}
        >
          <IconChart size={14} strokeWidth={2} />
        </span>
        <div className="flex-1">
          <h3 className="text-[14px] font-semibold" style={{ color: 'var(--text-strong)' }}>
            公平性指标
          </h3>
        </div>
        <span className="text-[11px]" style={{ color: 'var(--text-soft)' }}>
          经验指标 · 不代表全部约束已通过
        </span>
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-5 gap-3">
        {fairness.map((f) => (
          <window.QkTooltip
            key={f.id}
            position="top"
            maxWidth={240}
            text={
              <>
                <div className="font-semibold" style={{ color: '#F8FAFC' }}>{f.label}</div>
                <div className="mt-1" style={{ color: '#CBD5E1' }}>{f.detail || QK_FAIRNESS_TIPS[f.id] || '服务器提供的指标，具体口径请以约束配置为准'}</div>
              </>
            }
          >
            <div
              className="rounded-xl p-3.5 cursor-help w-full"
              style={{
                background: '#fff',
                border: '1px solid var(--border-soft)',
                boxShadow: '0 1px 0 rgba(255,255,255,.95) inset, 0 1px 2px rgba(15,23,42,.03)',
              }}
            >
            <div className="text-[10px] font-mono uppercase tracking-[0.12em] sm:whitespace-nowrap" style={{ color: 'var(--text-soft)' }}>
              {f.label}
            </div>
            <div className="mt-1.5 flex items-baseline gap-1">
              <span className="num text-[20px] font-bold tracking-tight" style={{ color: 'var(--text-strong)', letterSpacing: '-0.02em' }}>
                {f.value}
              </span>
              {f.unit && <span className="text-[11px]" style={{ color: 'var(--text-medium)' }}>{f.unit}</span>}
            </div>
            <div className="mt-2.5 flex items-center justify-between">
              {f.dots == null ? (f.rating === '未统计' ? null : <span>未评级</span>) : <QkFairnessDots filled={f.dots} />}
              <span
                className="text-[10.5px] font-medium font-mono sm:whitespace-nowrap"
                style={{ color: ratingColor[f.rating] || 'var(--text-soft)' }}
              >
                {f.rating}
              </span>
            </div>
            </div>
          </window.QkTooltip>
        ))}
      </div>
    </div>
  );
}

// ──────────────────────────────────────────────────────────────
// C · AI 总结建议 (视觉重心 · 4 子区域)
// ──────────────────────────────────────────────────────────────
function QkAISection({ id, title, count, children, accent = '#6366F1', innerRef }) {
  return (
    <div ref={innerRef}>
      <div className="flex items-center gap-2 mb-2.5">
        <span
          className="w-1 h-3.5 rounded-sm"
          style={{ background: accent }}
        />
        <span className="text-[12.5px] font-semibold whitespace-nowrap" style={{ color: '#312E81' }}>
          {title}
        </span>
        {count !== undefined && (
          <span
            className="font-mono text-[10px] font-bold px-1.5 py-0.5 rounded whitespace-nowrap"
            style={{ background: '#fff', color: accent, border: `1px solid ${accent}33` }}
          >
            {count}
          </span>
        )}
      </div>
      <div className="pl-3">
        {children}
      </div>
    </div>
  );
}

function QkAITargetChip({ target }) {
  return <span className="inline-flex items-center gap-2 rounded-lg px-3 py-2 text-[13px]" style={{ background: 'var(--surface-raised)', border: '1px solid var(--border-soft)', color: 'var(--text-medium)' }}><strong>{target.name}</strong><span>{target.meta}</span></span>;
}

function QkAIReviewItem({ item, idx }) {
  const sev = item.sev === 'warn'
    ? { bg: '#FFFBEB', bd: '#FDE68A', dot: '#F59E0B', fg: '#92400E', label: '警告' }
    : { bg: '#F0F9FF', bd: '#BAE6FD', dot: '#0EA5E9', fg: '#075985', label: '建议' };
  return (
    <li className="flex items-start gap-3 py-2 text-[12.5px] leading-relaxed">
      <span
        className="w-5 h-5 rounded-md inline-flex items-center justify-center font-mono text-[10.5px] font-bold shrink-0 mt-0.5"
        style={{ background: '#fff', color: '#4F46E5', border: '1px solid #C7D2FE' }}
      >
        {idx + 1}
      </span>
      <div className="flex-1 min-w-0">
        <div className="flex items-baseline gap-2 flex-wrap">
          <span className="font-semibold whitespace-nowrap" style={{ color: '#3730A3' }}>
            {item.who}
          </span>
          <span
            className="inline-flex items-center gap-1 text-[10px] font-mono px-1.5 py-0.5 rounded whitespace-nowrap"
            style={{ background: sev.bg, color: sev.fg, border: `1px solid ${sev.bd}`, fontWeight: 600 }}
          >
            <span className="w-1.5 h-1.5 rounded-full" style={{ background: sev.dot }} />
            {sev.label}
          </span>
        </div>
        <div className="text-[12px] mt-0.5" style={{ color: '#3730A3', opacity: 0.85 }}>
          {item.msg}
        </div>
      </div>
    </li>
  );
}

function QkAISummary({ ai, innerRef, highlight, backendReal = false }) {
  return (
    <div
      ref={innerRef}
      className="rounded-2xl p-6 relative overflow-hidden"
      style={{
        background: 'linear-gradient(135deg, #F5F3FF 0%, #EEF2FF 60%, #F0F9FF 100%)',
        border: highlight ? '1px solid #6366F1' : '1px solid #C7D2FE',
        boxShadow: highlight
          ? '0 0 0 4px rgba(99,102,241,.18), 0 1px 0 rgba(255,255,255,.7) inset, 0 12px 32px -12px rgba(79,70,229,.30)'
          : '0 1px 0 rgba(255,255,255,.7) inset, 0 1px 3px rgba(79,70,229,.06), 0 12px 32px -12px rgba(79,70,229,.20)',
        transition: 'box-shadow 400ms ease, border-color 400ms ease',
      }}
    >
      {/* 装饰圆环 */}
      <svg className="absolute right-0 top-0 opacity-[0.07] pointer-events-none" width="280" height="200" viewBox="0 0 280 200">
        <g stroke="#4F46E5" strokeWidth="1" fill="none">
          <circle cx="230" cy="50" r="45" />
          <circle cx="230" cy="50" r="80" />
          <circle cx="230" cy="50" r="115" />
        </g>
      </svg>

      {/* 头 */}
      <div className="flex items-center gap-3 mb-5 relative">
        <div
          className="w-11 h-11 rounded-xl flex items-center justify-center shrink-0"
          style={{
            background: 'linear-gradient(180deg, #6366F1, #4F46E5)',
            color: '#fff',
            boxShadow: '0 1px 0 rgba(255,255,255,.3) inset, 0 2px 6px -1px rgba(79,70,229,.40), 0 8px 20px -6px rgba(79,70,229,.40)',
          }}
        >
          <IconSparkle size={20} strokeWidth={2} />
        </div>
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2 flex-wrap">
            <span className="text-[15px] font-semibold whitespace-nowrap" style={{ color: '#312E81' }}>
              AI 总结参考建议
            </span>
            <span
              className="font-mono text-[9.5px] uppercase tracking-wider px-1.5 py-0.5 rounded font-bold whitespace-nowrap"
              style={{ background: '#4F46E5', color: '#fff' }}
            >
              基于本次方案
            </span>
            {backendReal ? <window.QkBackendChip /> : <QkMockChip />}
          </div>
          <div className="text-[11px] mt-1 whitespace-nowrap" style={{ color: '#6366F1' }}>
            分 4 段输出 · 帮助锁定复核重点
          </div>
        </div>
      </div>

      <div className="space-y-5 relative">
        {/* 整体评价 — 引用块 */}
        <QkAISection title="整体评估" accent="#7C3AED">
          <div
            className="rounded-lg p-3 pl-4 relative"
            style={{
              background: 'rgba(255,255,255,.6)',
              borderLeft: '3px solid #7C3AED',
              boxShadow: '0 1px 2px rgba(15,23,42,.04)',
            }}
          >
            <div className="text-[13px] leading-relaxed italic" style={{ color: '#3730A3' }}>
              {ai.verdict}
            </div>
          </div>
        </QkAISection>

        {/* 需要人工复核 — 编号列表 */}
        <QkAISection title="需要人工复核" count={ai.reviews.length} accent="#F59E0B">
          <ul className="space-y-0.5 divide-y" style={{ '--tw-divide-opacity': 1, borderColor: 'rgba(199,210,254,.5)' }}>
            {ai.reviews.map((r, i) => (
              <QkAIReviewItem key={i} item={r} idx={i} />
            ))}
          </ul>
        </QkAISection>

        {/* 建议优先检查 — chip 行 */}
        <QkAISection title="建议优先复核" count={ai.targets.length} accent="#0EA5E9">
          <div className="flex flex-wrap gap-2">
            {ai.targets.map((t, i) => <QkAITargetChip key={i} target={t} backendReal={backendReal} />)}
          </div>
        </QkAISection>

        {/* 下一步建议 — 动作清单 */}
        <QkAISection title="下一步建议" count={ai.nextSteps.length} accent="#10B981">
          <ul className="space-y-1.5">
            {ai.nextSteps.map((s, i) => (
              <li key={i} className="flex items-start gap-2 text-[12.5px] leading-relaxed">
                <span className="font-mono shrink-0 mt-0.5" style={{ color: '#10B981' }}>→</span>
                <span style={{ color: '#3730A3' }}>{s}</span>
              </li>
            ))}
          </ul>
        </QkAISection>
      </div>

      <div className="mt-5 text-[10.5px] font-mono pt-3 relative" style={{ color: '#6366F1', borderTop: '1px dashed rgba(199,210,254,.7)' }}>
        {backendReal
          ? '※ 求解器是最终分配权威; AI 仅用于解释、复核目标与下一步建议。'
          : '※ 此建议为前端模拟内容,不基于真实推理。服务端智能分析接入后将替换为真实结果。'}
      </div>
    </div>
  );
}

// ──────────────────────────────────────────────────────────────
// D · 4 个下载入口 (2×2 grid + 一键下载横条)
// ──────────────────────────────────────────────────────────────
function QkDownloadCard({ item, real, backendReal = false, onDownload }) {
  const Icon = item.icon;
  return (
    <div role="button" tabIndex={0} aria-label={`下载${item.title}`}
      onKeyDown={(event) => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); onDownload?.(item.id, item.title); } }}
      className="rounded-2xl p-5 transition-all cursor-pointer group"
      style={{
        background: 'linear-gradient(180deg, #FFFFFF 0%, #FCFCFD 100%)',
        border: '1px solid var(--border-soft)',
        boxShadow: '0 1px 0 rgba(255,255,255,.95) inset, 0 1px 3px rgba(15,23,42,.04), 0 4px 12px -4px rgba(15,23,42,.06)',
      }}
      onClick={() => onDownload?.(item.id, item.title)}
      onMouseEnter={(e) => { e.currentTarget.style.transform = 'translateY(-2px)'; e.currentTarget.style.boxShadow = '0 1px 0 rgba(255,255,255,.95) inset, 0 4px 10px rgba(15,23,42,.06), 0 16px 32px -10px rgba(15,23,42,.14)'; e.currentTarget.style.borderColor = 'rgba(79,70,229,.30)'; }}
      onMouseLeave={(e) => { e.currentTarget.style.transform = 'translateY(0)'; e.currentTarget.style.boxShadow = '0 1px 0 rgba(255,255,255,.95) inset, 0 1px 3px rgba(15,23,42,.04), 0 4px 12px -4px rgba(15,23,42,.06)'; e.currentTarget.style.borderColor = 'var(--border-soft)'; }}
    >
      <div className="flex items-start gap-3.5">
        {/* xlsx 缩略 */}
        <div
          className="relative rounded shrink-0 overflow-hidden"
          style={{
            width: 44, height: 52,
            background: '#fff',
            border: '1px solid #C7D2FE',
            boxShadow: '0 1px 0 rgba(255,255,255,.9) inset, 0 1px 3px rgba(15,23,42,.06), 0 4px 10px -2px rgba(79,70,229,.12)',
          }}
        >
          <svg className="absolute top-0 right-0" width="12" height="12" viewBox="0 0 12 12">
            <path d="M 0 0 L 12 0 L 12 12 Z" fill="#EEF2FF" />
            <path d="M 0 0 L 12 12" stroke="rgba(79,70,229,.4)" strokeWidth="0.5" />
          </svg>
          <div className="absolute inset-x-1.5 top-3 space-y-1">
            {[0.7, 0.5, 0.6, 0.4].map((w, i) => (
              <div key={i} className="h-px rounded" style={{ background: 'rgba(79,70,229,.22)', width: `${w * 100}%` }} />
            ))}
          </div>
          <span
            className="absolute bottom-1 left-1 right-1 text-center font-mono font-bold tracking-wider rounded"
            style={{ background: '#4F46E5', color: '#fff', fontSize: 9, padding: '1px 0', lineHeight: 1.2 }}
          >
            {item.format || 'XLSX'}
          </span>
        </div>

        <div className="flex-1 min-w-0">
          <div className="flex items-start gap-2 flex-wrap">
            <Icon size={14} strokeWidth={2} style={{ color: 'var(--brand)' }} />
            <span className="text-[14px] font-semibold sm:whitespace-nowrap" style={{ color: 'var(--text-strong)' }}>
              {item.title}
            </span>
          </div>
          <div className="text-[11.5px] mt-1" style={{ color: 'var(--text-soft)' }}>
            {item.sub}
          </div>
          <div className="text-[10.5px] mt-1.5 font-mono sm:whitespace-nowrap" style={{ color: 'var(--text-soft)' }}>
            {item.rows == null ? '行数以导出文件为准' : `${item.rows} 行`} · {item.format || 'XLSX'}
          </div>
        </div>
      </div>

      <div className="mt-4 pt-3 flex items-center justify-between gap-2 flex-wrap" style={{ borderTop: '1px solid var(--border-soft)' }}>
        <div className="flex items-center gap-1.5 flex-wrap">
          <span
            className="font-mono text-[9.5px] uppercase tracking-wider px-1 rounded whitespace-nowrap"
            style={real
              ? { background: 'rgba(16,185,129,.16)', color: '#047857', fontWeight: 700, lineHeight: 1.4, letterSpacing: '0.08em' }
              : { background: 'rgba(245,158,11,.16)', color: '#92400E', fontWeight: 700, lineHeight: 1.4, letterSpacing: '0.08em' }}
          >
            {backendReal ? '后端导出' : real ? '本地生成' : '模拟'}
          </span>
          <span
            className="text-[10.5px] whitespace-nowrap"
            style={{ color: 'var(--text-soft)' }}
          >
            {backendReal ? '后端生成 · 基于 assignments' : real ? '浏览器内生成 · 可用 Excel 打开' : '前端占位 · 待接入服务端导出'}
          </span>
        </div>
        <span
          className="inline-flex items-center gap-1 px-2.5 py-1 rounded-md text-[12px] font-medium transition-colors whitespace-nowrap group-hover:bg-indigo-50"
          style={{ color: 'var(--brand)' }}
        >
          <IconArrowDown size={12} strokeWidth={2.5} />
          {backendReal ? '下载后端文件' : real ? '下载 .xlsx' : '下载（占位）'}
        </span>
      </div>
    </div>
  );
}

function QkDownloadGrid({ downloads, real, backendReal = false, onDownload, onDownloadAll, innerRef }) {
  return (
    <div ref={innerRef}>
      <div className="flex items-center gap-2 mb-4">
        <span
          className="w-7 h-7 rounded-lg flex items-center justify-center shrink-0"
          style={{
            background: 'linear-gradient(180deg, #EEF2FF, #E0E7FF)',
            color: '#4F46E5',
            border: '1px solid rgba(79,70,229,.18)',
          }}
        >
          <IconArrowDown size={14} strokeWidth={2.5} />
        </span>
        <div className="flex-1">
          <h3 className="text-[14px] font-semibold" style={{ color: 'var(--text-strong)' }}>
            下载排监考结果 · {downloads.length} 个文件
          </h3>
        </div>
        <span className="text-[11px]" style={{ color: real ? '#047857' : 'var(--text-soft)' }}>
          {backendReal ? '后端实时导出 · 数据库 assignments' : real ? '浏览器内真实生成 · 可用 Excel 打开' : '全部为占位 .xlsx · 不含真实数据'}
        </span>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {downloads.map((d) => <QkDownloadCard key={d.id} item={d} real={real} backendReal={backendReal} onDownload={onDownload} />)}
      </div>

      {/* 一键下载横条 */}
      <div
        className="mt-4 rounded-2xl px-5 py-3.5 flex items-center gap-3 flex-wrap"
        style={{
          background: 'linear-gradient(180deg, #FFFFFF 0%, #FAFBFC 100%)',
          border: '1px dashed var(--border-strong)',
          boxShadow: '0 1px 0 rgba(255,255,255,.95) inset',
        }}
      >
        <span
          className="w-9 h-9 rounded-lg flex items-center justify-center shrink-0"
          style={{ background: '#F1F5F9', color: 'var(--text-medium)', border: '1px solid var(--border-soft)' }}
        >
          <IconArchive size={16} strokeWidth={2} />
        </span>
        <div className="flex-1 min-w-0">
          <div className="text-[13px] font-semibold sm:whitespace-nowrap" style={{ color: 'var(--text-strong)' }}>
            {backendReal ? '一键导出后端结果' : real ? '一键导出全部（单个多 sheet xlsx）' : '一键打包下载（.zip 占位）'}
          </div>
          <div className="text-[11px] mt-0.5 sm:whitespace-nowrap" style={{ color: 'var(--text-soft)' }}>
            {backendReal ? '由后端任务 API 生成 · 可下载 xlsx/pdf 基础版' : real ? '4 张表合并为一个 Excel 文件 · 含说明页' : '4 张表合并下载 · 当前为前端占位'}
          </div>
        </div>
        <Button
          icon={IconArrowDown}
          onClick={() => onDownloadAll?.()}
        >
          {backendReal ? '导出后端文件' : real ? '导出全部 .xlsx' : '批量下载'}
          {!real && <QkMockChip />}
        </Button>
      </div>
    </div>
  );
}

// ──────────────────────────────────────────────────────────────
// E · 底部操作栏
// ──────────────────────────────────────────────────────────────
function QkResultActions({ onGoEdit, onSaveDraft, onBackToUpload, onViewDetail, backendReal }) {
  return (
    <div
      className="rounded-2xl p-5 flex items-center gap-3 flex-wrap"
      style={{
        background: 'linear-gradient(180deg, #FFFFFF 0%, #FAFBFC 100%)',
        border: '1px solid var(--border-soft)',
        boxShadow: '0 1px 0 rgba(255,255,255,.95) inset, 0 1px 3px rgba(15,23,42,.04), 0 6px 16px -8px rgba(15,23,42,.08)',
      }}
    >
      <div className="text-[12.5px] whitespace-nowrap" style={{ color: 'var(--text-medium)' }}>
        <span style={{ color: 'var(--text-strong)', fontWeight: 600 }}>下一步：</span>
        <span style={{ marginLeft: 4 }}>建议先复核标注项,再进入修改安排</span>
      </div>

      <div className="flex-1" />

      <button
        onClick={onBackToUpload}
        className="text-[12.5px] font-medium px-3 py-1.5 rounded-lg transition-colors inline-flex items-center gap-1 whitespace-nowrap"
        style={{ color: 'var(--text-soft)', border: '1px solid transparent' }}
        onMouseEnter={(e) => { e.currentTarget.style.background = '#FEF2F2'; e.currentTarget.style.color = '#B91C1C'; e.currentTarget.style.borderColor = '#FECACA'; }}
        onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; e.currentTarget.style.color = 'var(--text-soft)'; e.currentTarget.style.borderColor = 'transparent'; }}
      >
        <span style={{ opacity: 0.7 }}>←</span> 返回上传
      </button>

      {!backendReal && <Button icon={IconArchive} onClick={onSaveDraft}>保存本地草稿</Button>}

      {onViewDetail && (
        <Button icon={IconList} onClick={onViewDetail}>
          查看任务详情
        </Button>
      )}

      <Button
        variant="primary"
        icon={IconEdit}
        onClick={onGoEdit}
      >
        进入修改监考安排 <span style={{ opacity: 0.7, marginLeft: 4 }}>→</span>
      </Button>
    </div>
  );
}

// ──────────────────────────────────────────────────────────────
// Step 4 主体
// ──────────────────────────────────────────────────────────────
function QkStep4({ resultAt, volume, aiCount, taskId, schedule, result, dataSource, onGoEdit, onReschedule, onRestart }) {
  const aiRef = React.useRef(null);
  const reviewRef = React.useRef(null);
  const [aiHighlight, setAiHighlight] = React.useState(false);
  const [downloadState, setDownloadState] = React.useState({ busy: false, error: '' });
  const downloadRef = React.useRef(false);
  const backendReal = !!result;
  const real = backendReal || !!(schedule && window.JKXT);
  const failed = ['failed', 'infeasible', 'cancelled'].includes(String(result?.status || '').toLowerCase());
  const task = taskId && window.JKXT?.getTask ? window.JKXT.getTask(taskId) : null;
  const fairness = qkBackendFairness(result?.fairness);
  const ai = qkBackendAi(result?.ai);
  const stats = backendReal ? result?.stats || {} : schedule?.stats || {};
  const base = {
    exams: stats.exams ?? null,
    supervisions: stats.assignments ?? stats.supervisions ?? null,
    teachers: stats.teachers ?? stats.teachersUsed ?? null,
    rooms: stats.rooms ?? null,
    review: ai ? ai.targets.length : null,
  };
  const downloads = backendReal ? qkBackendDownloads(result) : qkLocalDownloads(schedule);

  const fetchDownload = async (cardId, title) => {
    if (downloadRef.current) return;
    downloadRef.current = true;
    setDownloadState({ busy: true, error: '' });
    try {
      if (!taskId) throw new Error('任务编号缺失，无法下载');
      const format = cardId === 'pdf' ? 'pdf' : 'xlsx';
      const response = await fetch(window.api.url(`/exam-tasks/${encodeURIComponent(taskId)}/export`, { format }), { credentials: 'include' });
      if (!response.ok) throw new Error(`导出接口返回 HTTP ${response.status}`);
      const blob = await response.blob();
      if (!blob.size || /json|text\/html/i.test(blob.type)) throw new Error('服务端未返回有效文件');
      window.JKXTTaskTools.downloadBlob(blob, `${title}.${format}`);
      setDownloadState({ busy: false, error: '' });
      window.ui?.toast?.('文件已生成', { kind: 'success', desc: '已交给浏览器下载。' });
    } catch (error) {
      setDownloadState({ busy: false, error: error?.message || '下载失败，请重试' });
    } finally { downloadRef.current = false; }
  };

  const downloadOne = (cardId, title) => {
    if (backendReal) {
      fetchDownload(cardId, title);
      return;
    }
    if (real) {
      const ok = window.JKXT.downloadOne(schedule, task || { id: taskId }, cardId, title);
      if (ok) window.ui?.toast?.(`正在下载 ${title}.xlsx`, { kind: 'success', desc: '浏览器内真实生成 · 可用 Excel 打开' });
    } else {
      window.ui?.toast?.('结果尚未就绪', { kind: 'warning', desc: '请先完成排监考' });
    }
  };
  const downloadAll = () => {
    if (backendReal) {
      if (downloads.length) fetchDownload(downloads[0].id, downloads[0].title);
      return;
    }
    if (real) {
      const ok = window.JKXT.downloadAll(schedule, task || { id: taskId });
      if (ok) window.ui?.toast?.('正在导出全部结果.xlsx', { kind: 'success', desc: '单个多 sheet 文件 · 可用 Excel 打开' });
    } else {
      window.ui?.toast?.('结果尚未就绪', { kind: 'warning', desc: '请先完成排监考' });
    }
  };

  const scrollToReview = () => {
    const el = reviewRef.current || aiRef.current;
    if (!el) return;
    el.scrollIntoView({ block: 'center', behavior: window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth' });
    setAiHighlight(true);
    setTimeout(() => setAiHighlight(false), 1400);
  };

  const saveDraft = () => {
    if (backendReal) return;
    if (task && window.JKXT) {
      window.JKXT.saveTask({ ...task, status: 'draft' });
      window.ui?.toast?.('已保存为任务草稿', { kind: 'success', desc: `已写入本地任务库 · ${task.id}` });
    } else {
      window.ui?.toast?.('结果尚未就绪', { kind: 'warning', desc: '请先完成排监考' });
    }
  };

  const viewDetail = real && taskId ? () => {
    window.JKXTTaskTools.open({ id: taskId }, 'task-detail');
  } : null;

  if (!real || failed || (backendReal && !qkBackendReady(result))) return <EmptyState title={failed ? '本次排班未成功' : '排班结果尚未就绪'} sub={result?.errorMessage || '未获取成功的排班结果，不展示示例指标或虚构建议。'} action={<Button onClick={onRestart}>返回检查数据</Button>} />;

  return (
    <section className="space-y-5">
      <QkResultSummary
        resultAt={resultAt}
        base={base}
        taskId={taskId}
        dataSource={dataSource}
        onScrollToReview={scrollToReview}
        backendReal={backendReal}
      />
      {fairness.length ? <QkFairnessStrip fairness={fairness} /> : <div data-testid="qk-fairness-unavailable" className="rounded-xl p-5" style={{ background: 'var(--surface-raised)', border: '1px solid var(--border-soft)' }}><h3 className="text-[16px] font-semibold">公平性指标未提供</h3><p className="mt-2 text-[14px]">本次结果未返回可用指标，不能推断工作量公平或约束已通过。</p></div>}
      {ai ? <QkAISummary ai={ai} innerRef={aiRef} highlight={aiHighlight} backendReal={backendReal} /> : <div ref={aiRef} data-testid="qk-ai-unavailable" className="rounded-xl p-5" style={{ background: 'var(--surface-raised)', border: '1px solid var(--border-soft)' }}><h3 className="text-[16px] font-semibold">本次未提供复核建议</h3><p className="mt-2 text-[14px]">请依据排班明细和冲突检查人工复核；未调用成功的 AI 不会展示生成结论。</p></div>}
      {/* 隐藏锚点 — 摘要的"待人工复核 查看↓"指向 AI 卡的"需要人工复核"区 */}
      {downloadState.busy && <p role="status">正在请求导出文件…</p>}
      {downloadState.error && <p role="alert" style={{ color: 'var(--danger)' }}>{downloadState.error}，可再次点击文件重试。</p>}
      {downloads.length ? <QkDownloadGrid downloads={downloads} real={real} backendReal={backendReal} onDownload={downloadOne} onDownloadAll={downloadAll} /> : <EmptyState title="未提供下载文件" sub="后端未返回导出入口，请在任务详情中检查任务状态后重试。" />}
      <QkResultActions backendReal={backendReal}
        onGoEdit={onGoEdit}
        onSaveDraft={saveDraft}
        onBackToUpload={onRestart}
        onViewDetail={viewDetail}
      />
    </section>
  );
}

window.QkStep4 = QkStep4;
