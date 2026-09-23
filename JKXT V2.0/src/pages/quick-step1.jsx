// Step 1 · 准备数据
//   - 模板与示例区 (折叠 · 默认收起)
//   - 三张 Excel 上传卡片 (idle / validating / uploaded / warning / error)
//   - 示例数据来源标识
//   - 底部工具条: 使用示例数据 / 下一步

// ──────────────────────────────────────────────────────────────
// 表 metadata + 各表的预置问题文案 (mock)
// ──────────────────────────────────────────────────────────────
const QK_SLOTS = [
  {
    key: 't01', templateKind: 'teachers', num: '01', tone: 'indigo',
    glyph: 'people',
    title: '教师安排',     sub: '教师 / 性别 / 年级 / 班主任标记',
    sampleCols: ['编号', '姓名', '性别', '年级', '班主任'],
    sampleRows: [
      ['T1024', '李志强', '男', '初二', '是'],
      ['T1031', '王慧',   '女', '初二', '否'],
      ['T1107', '张红',   '—',  '初三', '是'],
      ['T1145', '陈睿明', '男', '初一', '否'],
    ],
    issues: {
      warn: {
        summary: '2 位教师存在特殊备注',
        detail:  '张红 (T1107) · 孕产期免监 — 已自动排除\n王慧 (T1031) · 跨年级监考 — 需教务确认',
      },
      err: {
        summary: '缺少必填字段「性别」',
        detail:  '第 24-31 行性别字段为空,影响男女混合策略生效。\n请补全源表后重新上传。',
      },
    },
  },
  {
    key: 't02', templateKind: 'classrooms', num: '02', tone: 'emerald',
    glyph: 'room',
    title: '考场信息',     sub: '教室 / 楼栋 / 容量 / 类型',
    sampleCols: ['编号', '楼栋', '房间', '容量', '类型'],
    sampleRows: [
      ['R-101', 'A 栋', '101', 40, '普通教室'],
      ['R-205', 'B 栋', '205', 36, '普通教室'],
      ['R-305', 'C 栋', '305', 48, '多媒体'],
      ['R-410', 'D 栋', '410', 30, '小教室'],
    ],
    issues: {
      warn: {
        summary: '1 个考场容量临近上限',
        detail:  'C-305 第 3 时段需 480 座位 · 当前可用 460 (-20)\n建议启用备用考场 D-410 分流',
      },
      err: {
        summary: '缺少必填字段「容量」',
        detail:  '第 5、11 行容量字段为空,无法计算座位余量。\n请补全源表后重新上传。',
      },
    },
  },
  {
    key: 't03', templateKind: 'exams', num: '03', tone: 'amber',
    glyph: 'calendar',
    title: '考试安排',     sub: '考期 / 时段 / 科目 / 班级 / 人数',
    sampleCols: ['考期', '时段', '科目', '班级', '人数', '备注'],
    sampleRows: [
      ['11-26', '上午1', '语文', '初二1 班', 36, '—'],
      ['11-26', '上午2', '数学', '初二2 班', 38, '—'],
      ['11-26', '下午1', '英语', '初二3 班', 35, '听力'],
      ['11-27', '上午1', '物理', '初三1 班', 40, '—'],
    ],
    issues: {
      warn: {
        summary: '2 个考试时段间隔过短',
        detail:  '11-26 上午1 与 上午2 实际间隔仅 10 分钟\n建议在「修改监考安排」中调整时段安排',
      },
      err: {
        summary: '缺少必填字段「考试日期」',
        detail:  '第 5 行『考试日期』字段为空,无法排入时间网格。\n请补全源表后重新上传。',
      },
    },
  },
];

// 下载后端真实空白模板 (GET /api/v1/import/templates/{kind})。带 session cookie 取 blob 再触发下载，
// 避免之前"已模拟下载…占位文件"的假提示。
async function downloadQkTemplate(slot) {
  const kind = slot?.templateKind;
  if (!kind || !window.api?.url) { window.ui?.toast?.('模板下载暂不可用', { kind: 'warning' }); return; }
  try {
    const resp = await fetch(window.api.url('/import/templates/' + kind), { credentials: 'include' });
    if (!resp.ok) throw new Error('HTTP ' + resp.status);
    const blob = await resp.blob();
    const href = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = href; a.download = `${slot.title}-模板.xlsx`;
    document.body.appendChild(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(href), 1500);
    window.ui?.toast?.(`已下载 ${slot.title}模板`, { kind: 'success', desc: '后端真实空白模板 · 可直接填写上传' });
  } catch (e) {
    window.ui?.toast?.('模板下载失败', { kind: 'error', desc: e?.message || '请稍后重试' });
  }
}

const QK_TONES = {
  indigo:  { bg: '#EEF2FF', fg: '#4F46E5', soft: '#F5F3FF', border: '#C7D2FE' },
  emerald: { bg: '#ECFDF5', fg: '#047857', soft: '#F0FDF4', border: '#A7F3D0' },
  amber:   { bg: '#FFF7ED', fg: '#B45309', soft: '#FFFBEB', border: '#FCD34D' },
};

// 状态 → Tag 颜色映射
const QK_STATE_TAG = {
  idle:       { color: 'slate',   label: '待上传',   dot: false, pulse: false },
  validating: null, // 用 tone 色 + pulse,见 QkStateTag
  uploaded:   { color: 'emerald', label: '已上传',   dot: true,  pulse: false },
  warning:    { color: 'amber',   label: '有警告',   dot: true,  pulse: false },
  error:      { color: 'rose',    label: '有错误',   dot: true,  pulse: false },
};

// 相对时间格式化 (mock)
function qkRelTime(d) {
  if (!d) return '—';
  const diff = Math.floor((Date.now() - d.getTime()) / 1000);
  if (diff < 5)    return '刚刚';
  if (diff < 60)   return `${diff} 秒前`;
  if (diff < 3600) return `${Math.floor(diff / 60)} 分钟前`;
  return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
}

// ──────────────────────────────────────────────────────────────
// 模板预览迷你表格 (折叠区里的示例)
// ──────────────────────────────────────────────────────────────
function QkSampleTable({ slot }) {
  const tone = QK_TONES[slot.tone];
  return (
    <div
      className="rounded-xl overflow-hidden"
      style={{ border: '1px solid var(--border-soft)', background: '#fff' }}
    >
      <div
        className="flex items-center gap-2 px-3 py-2"
        style={{ background: tone.soft, borderBottom: `1px solid ${tone.border}66` }}
      >
        <span
          className="text-[12px] px-2 py-0.5 rounded font-semibold"
          style={{ background: tone.fg, color: '#fff' }}
        >
          表 {slot.num}
        </span>
        <span className="text-[12.5px] font-semibold" style={{ color: 'var(--text-strong)' }}>
          {slot.title}示例表
        </span>
      </div>
      <div className="overflow-x-auto">
        <table className="w-full text-[12px]" style={{ fontFamily: 'Geist, system-ui, sans-serif' }}>
          <thead>
            <tr style={{ background: '#FAFBFC' }}>
              {slot.sampleCols.map((c, i) => (
                <th
                  key={i}
                  className="px-3 py-2 text-left font-medium text-[12px] whitespace-nowrap"
                  style={{ color: 'var(--text-soft)', borderBottom: '1px solid var(--border-soft)' }}
                >
                  {c}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {slot.sampleRows.map((row, ri) => (
              <tr key={ri}>
                {row.map((cell, ci) => (
                  <td
                    key={ci}
                    className="px-3 py-1.5 whitespace-nowrap"
                    style={{
                      color: ci === 0 ? tone.fg : 'var(--text-medium)',
                      fontFamily: ci === 0 || typeof cell === 'number' ? 'Geist Mono, monospace' : undefined,
                      fontWeight: ci === 0 ? 600 : 400,
                      borderBottom: ri < slot.sampleRows.length - 1 ? '1px solid #F1F5F9' : 'none',
                      fontVariantNumeric: 'tabular-nums',
                    }}
                  >
                    {cell}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div
        className="flex items-center justify-between px-3 py-2 text-[12px]"
        style={{ background: '#FAFBFC', borderTop: '1px solid var(--border-soft)' }}
      >
        <span style={{ color: 'var(--text-soft)' }}>
          共 <span className="font-mono">{slot.sampleCols.length}</span> 列必填字段 · 示例 4 行
        </span>
        <button
          onClick={() => downloadQkTemplate(slot)}
          className="inline-flex items-center gap-1 font-medium hover:underline"
          style={{ color: tone.fg }}
        >
          <IconArrowDown size={11} strokeWidth={2.5} />
          下载模板
        </button>
      </div>
    </div>
  );
}

// ──────────────────────────────────────────────────────────────
// 模板与示例区 (折叠)
// ──────────────────────────────────────────────────────────────
function QkTemplatesAccordion({ innerRef }) {
  const [open, setOpen] = React.useState(false);
  return (
    <div
      ref={innerRef}
      className="rounded-2xl overflow-hidden"
      style={{
        background: 'linear-gradient(180deg, #FFFFFF 0%, #FCFCFD 100%)',
        border: '1px solid var(--border-soft)',
        boxShadow: '0 1px 0 rgba(255,255,255,.95) inset, 0 1px 3px rgba(15,23,42,.04)',
      }}
    >
      <button
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
        className="w-full flex items-center gap-3 px-5 py-3.5 transition-colors"
        onMouseEnter={(e) => (e.currentTarget.style.background = '#F8FAFC')}
        onMouseLeave={(e) => (e.currentTarget.style.background = 'transparent')}
      >
        <span className="w-9 h-9 flex items-center justify-center shrink-0">
          <ProductGlyph name="help" size={28} />
        </span>
        <div className="text-left min-w-0 flex-1">
          <div className="text-[14px] font-semibold" style={{ color: 'var(--text-strong)' }}>
            不熟悉模板?先看示例字段
          </div>
          <div className="text-[12px] mt-0.5" style={{ color: 'var(--text-soft)' }}>
            展开查看 3 张表的字段对照，并可下载后端空白模板
          </div>
        </div>
        <span
          className="text-[12px] font-medium px-2 py-0.5 rounded"
          style={{ background: '#F1F5F9', color: 'var(--text-medium)' }}
        >
          {open ? '收起' : '展开'}
        </span>
        <IconChevron dir={open ? 'up' : 'down'} size={16} className="text-slate-400" />
      </button>

      {open && (
        <div
          className="px-5 pb-5 pt-1 grid grid-cols-1 lg:grid-cols-3 gap-3"
          style={{ borderTop: '1px solid var(--border-soft)' }}
        >
          {QK_SLOTS.map((s) => <QkSampleTable key={s.key} slot={s} />)}
        </div>
      )}
    </div>
  );
}

// ──────────────────────────────────────────────────────────────
// 原子组件: 状态 Tag
// ──────────────────────────────────────────────────────────────
function QkStateTag({ state, tone }) {
  if (state === 'validating') {
    return (
      <span
        className="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-md text-[12px] font-medium whitespace-nowrap"
        style={{ background: tone.bg, color: tone.fg, border: `1px solid ${tone.fg}22` }}
      >
        <span
          className="w-1.5 h-1.5 rounded-full"
          style={{ background: tone.fg, animation: 'qkBlink 1s ease-in-out infinite' }}
        />
        校验中
      </span>
    );
  }
  const meta = QK_STATE_TAG[state];
  if (!meta) return null;
  return <Tag color={meta.color} dot={meta.dot}>{meta.label}</Tag>;
}

// ──────────────────────────────────────────────────────────────
// 原子组件: 示例数据 chip (紫底)
// ──────────────────────────────────────────────────────────────
function QkSourceTag() {
  return (
    <span
      className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[12px] font-semibold whitespace-nowrap"
      style={{
        background: '#F5F1FF',
        color: '#7C3AED',
        border: '1px solid rgba(124,58,237,.22)',
      }}
    >
      <IconSparkle size={10} strokeWidth={2.5} />
      示例
    </span>
  );
}

// ──────────────────────────────────────────────────────────────
// 原子组件: xlsx 文件缩略图 (带折角细节)
// ──────────────────────────────────────────────────────────────
function QkXlsxIcon({ tone, size = 'md' }) {
  const dims = size === 'sm' ? { w: 36, h: 44, fs: 8 } : { w: 40, h: 48, fs: 9 };
  return (
    <div
      className="relative rounded shrink-0 overflow-hidden"
      style={{
        width: dims.w,
        height: dims.h,
        background: '#fff',
        border: `1px solid ${tone.fg}33`,
        boxShadow: '0 1px 0 rgba(255,255,255,.9) inset, 0 1px 3px rgba(15,23,42,.06), 0 2px 4px -2px rgba(15,23,42,.08)',
      }}
    >
      {/* fold corner */}
      <svg
        className="absolute top-0 right-0"
        width="11" height="11" viewBox="0 0 11 11"
        style={{ filter: 'none' }}
      >
        <path d={`M 0 0 L 11 0 L 11 11 Z`} fill={tone.soft} />
        <path d={`M 0 0 L 11 11 L 0 11 Z`} fill="transparent" />
        <path d={`M 0 0 L 11 11`} stroke={`${tone.fg}66`} strokeWidth="0.5" />
      </svg>
      {/* lined rows mimic */}
      <div className="absolute inset-x-1.5 top-2.5 space-y-1">
        {[0.7, 0.5, 0.6].map((w, i) => (
          <div
            key={i}
            className="h-px rounded"
            style={{ background: `${tone.fg}22`, width: `${w * 100}%` }}
          />
        ))}
      </div>
      {/* XLSX badge */}
      <span
        className="absolute bottom-1 left-1 right-1 text-center font-mono font-bold tracking-wider rounded"
        style={{
          background: tone.fg,
          color: '#fff',
          fontSize: dims.fs,
          padding: '1px 0',
          lineHeight: 1.2,
        }}
      >
        XLSX
      </span>
    </div>
  );
}

// ──────────────────────────────────────────────────────────────
// 原子组件: 文件信息条 (uploaded/warning/error 共用)
// ──────────────────────────────────────────────────────────────
function QkFileChip({ tone, name, rows, size, updatedAt, source }) {
  return (
    <div className="flex items-start gap-3 min-w-0">
      <QkXlsxIcon tone={tone} />
      <div className="flex-1 min-w-0">
        <div className="text-[12.5px] font-semibold truncate" style={{ color: 'var(--text-strong)' }} title={name}>
          {name}
        </div>
        <div className="text-[12px] mt-1 flex items-center gap-1.5 flex-wrap" style={{ color: 'var(--text-soft)' }}>
          <span className="whitespace-nowrap">{rows == null ? '行数未确认' : `${rows} 行`}</span>
          <span style={{ opacity: 0.5 }}>·</span>
          <span className="whitespace-nowrap">{size == null ? '大小未提供' : `${size} KB`}</span>
          <span style={{ opacity: 0.5 }}>·</span>
          {source === 'sample'
            ? <span className="whitespace-nowrap" style={{ color: '#7C3AED' }}>演示数据 · 提交到演示学校</span>
            : <span className="whitespace-nowrap">{qkRelTime(updatedAt)}</span>}
        </div>
      </div>
    </div>
  );
}

// ──────────────────────────────────────────────────────────────
// 原子组件: 操作按钮行 (替换 / 删除 / 重新上传)
// ──────────────────────────────────────────────────────────────
function QkRowActions({ tone, isError, onReplace, onRemove }) {
  const replaceColor = isError ? '#B91C1C' : tone.fg;
  return (
    <div className="flex items-center gap-1 shrink-0">
      <button
        onClick={onReplace}
        className="text-[12px] font-medium px-2 py-1 rounded transition-colors whitespace-nowrap"
        style={{ color: replaceColor }}
        onMouseEnter={(e) => (e.currentTarget.style.background = isError ? '#FEE2E2' : '#fff')}
        onMouseLeave={(e) => (e.currentTarget.style.background = 'transparent')}
      >
        {isError ? '重新上传' : '替换'}
      </button>
      <button
        onClick={onRemove}
        className="text-[12px] font-medium px-2 py-1 rounded transition-colors whitespace-nowrap"
        style={{ color: 'var(--text-soft)' }}
        onMouseEnter={(e) => { e.currentTarget.style.background = '#FEE2E2'; e.currentTarget.style.color = '#B91C1C'; }}
        onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; e.currentTarget.style.color = 'var(--text-soft)'; }}
      >
        删除
      </button>
    </div>
  );
}

// ──────────────────────────────────────────────────────────────
// 原子组件: 结果强调条 (uploaded/warning/error 各一种)
// ──────────────────────────────────────────────────────────────
function QkResultBanner({ kind, tone, issue }) {
  const [open, setOpen] = React.useState(false);

  if (kind === 'uploaded') {
    return (
      <div
        className="flex items-center gap-2 text-[12px] pt-2.5 whitespace-nowrap"
        style={{ color: tone.fg, borderTop: `1px solid ${tone.border}55` }}
      >
        <IconCheck size={12} strokeWidth={2.5} />
        <span className="font-medium">结构校验通过 · 等待整表校验</span>
      </div>
    );
  }

  const palette = kind === 'warning'
    ? { stripe: '#F59E0B', bg: '#FFFBEB', bd: '#FDE68A', fg: '#92400E', iconBg: '#F59E0B' }
    : { stripe: '#EF4444', bg: '#FEF2F2', bd: '#FECACA', fg: '#991B1B', iconBg: '#EF4444' };

  return (
    <div className="pt-2.5 mt-0.5" style={{ borderTop: `1px solid ${tone.border}55` }}>
      <div
        className="rounded-md flex items-start gap-2.5 px-2.5 py-2 relative overflow-hidden"
        style={{ background: palette.bg, border: `1px solid ${palette.bd}` }}
      >
        {/* left accent stripe */}
        <span
          className="absolute left-0 top-0 bottom-0 w-[3px]"
          style={{ background: palette.stripe }}
        />
        <span
          className="w-5 h-5 rounded-md inline-flex items-center justify-center shrink-0 ml-0.5"
          style={{ background: palette.iconBg, color: '#fff' }}
        >
          <IconAlert size={12} strokeWidth={2.25} />
        </span>
        <div className="flex-1 min-w-0 text-[12px]" style={{ color: palette.fg }}>
          <div className="font-semibold leading-tight">{issue.summary}</div>
          {open && issue.detail && (
            <div
              className="mt-1.5 pt-1.5 text-[12px] leading-relaxed whitespace-pre-wrap"
              style={{ color: palette.fg, opacity: 0.85, borderTop: `1px dashed ${palette.bd}` }}
            >
              {issue.detail}
            </div>
          )}
        </div>
        {issue.detail && (
          <button
            onClick={() => setOpen((o) => !o)}
            className="text-[12px] font-medium px-1.5 py-0.5 rounded shrink-0 whitespace-nowrap"
            style={{ color: palette.fg }}
            onMouseEnter={(e) => (e.currentTarget.style.background = `${palette.stripe}22`)}
            onMouseLeave={(e) => (e.currentTarget.style.background = 'transparent')}
          >
            {open ? '收起 ↑' : '查看详情 →'}
          </button>
        )}
      </div>
    </div>
  );
}

// ──────────────────────────────────────────────────────────────
// 主体 · idle
// ──────────────────────────────────────────────────────────────
function QkBodyIdle({ tone, slot, dragging, onPick }) {
  return (
    <div
      onClick={onPick}
      onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onPick(); } }}
      tabIndex={0}
      role="button"
      aria-label="点击或拖拽上传 .xlsx 文件"
      className="rounded-xl p-5 flex flex-1 flex-col items-center justify-center text-center cursor-pointer transition-all focus:outline-none"
      style={{
        background: dragging ? '#fff' : '#F8FAFC',
        border: dragging ? `2px solid ${tone.fg}` : '1.5px dashed #CBD5E1',
        minHeight: 144,
      }}
      onFocus={(e) => { e.currentTarget.style.boxShadow = `0 0 0 3px ${tone.fg}1F`; }}
      onBlur={(e) => { e.currentTarget.style.boxShadow = 'none'; }}
    >
      <div className="relative w-14 h-14 flex items-center justify-center mb-3">
        <ProductGlyph name={slot.glyph} size={40} />
        <span
          className="absolute right-0 bottom-0 w-6 h-6 rounded-full inline-flex items-center justify-center"
          style={{ background: dragging ? tone.fg : '#fff', color: dragging ? '#fff' : tone.fg, border: `2px solid ${tone.soft}` }}
        >
          <IconUpload size={12} strokeWidth={2.25} />
        </span>
      </div>
      <div className="text-[13px] font-medium" style={{ color: dragging ? tone.fg : 'var(--text-strong)' }}>
        {dragging ? '松开以上传' : '点击或拖拽 .xlsx 文件'}
      </div>
      <div className="text-[12px] mt-1" style={{ color: 'var(--text-soft)' }}>
        支持 .xlsx / .xls 格式
      </div>
    </div>
  );
}

// ──────────────────────────────────────────────────────────────
// 主体 · validating
// ──────────────────────────────────────────────────────────────
function QkBodyValidating({ tone, name }) {
  return (
    <div
      className="rounded-xl p-4 flex flex-1 flex-col justify-center gap-3"
      style={{ background: tone.soft, border: `1px solid ${tone.border}`, minHeight: 144 }}
    >
      <div className="flex items-center gap-3">
        <div
          className="w-10 h-10 rounded-lg flex items-center justify-center shrink-0"
          style={{ background: '#fff', border: `1px solid ${tone.border}` }}
        >
          <span
            className="w-4 h-4 rounded-full border-2 border-current border-t-transparent animate-spin"
            style={{ color: tone.fg }}
          />
        </div>
        <div className="min-w-0 flex-1">
          <div className="text-[12.5px] font-semibold truncate" style={{ color: 'var(--text-strong)' }}>
            {name || '正在接收文件…'}
          </div>
          <div className="text-[12px] mt-0.5" style={{ color: tone.fg }}>
            正在上传并校验文件
          </div>
        </div>
      </div>
      <div className="h-1.5 rounded-full overflow-hidden" style={{ background: '#fff', border: `1px solid ${tone.border}` }}>
        <div
          className="h-full rounded-full"
          style={{
            width: '60%',
            background: `linear-gradient(90deg, ${tone.fg}88, ${tone.fg}, ${tone.fg}88)`,
            backgroundSize: '200% 100%',
            animation: 'qkProgress 1.4s ease-in-out infinite, qkSheen 2s linear infinite',
          }}
        />
      </div>
      <div className="flex items-center gap-2 text-[12px]" style={{ color: 'var(--text-soft)' }}>
        <span>① 接收</span>
        <span style={{ color: tone.fg, fontWeight: 600 }}>② 解析</span>
        <span>③ 结构校验</span>
      </div>
    </div>
  );
}

// ──────────────────────────────────────────────────────────────
// 主体 · uploaded / warning / error (共用)
// ──────────────────────────────────────────────────────────────
function QkBodyResult({ tone, kind, name, rows, size, updatedAt, source, issue, onReplace, onRemove }) {
  const isWarn  = kind === 'warning';
  const isError = kind === 'error';

  const bg = isError
    ? 'linear-gradient(180deg, #FFFFFF 0%, #FEF2F2 100%)'
    : isWarn
      ? 'linear-gradient(180deg, #FFFFFF 0%, #FFFBEB 100%)'
      : tone.soft;
  const border = isError
    ? '#FECACA'
    : isWarn
      ? '#FDE68A'
      : `${tone.border}66`;

  return (
    <div
      className="rounded-xl p-4 flex flex-1 flex-col gap-2.5"
      style={{
        background: bg,
        border: `1px solid ${border}`,
        minHeight: 144,
        boxShadow: '0 1px 0 rgba(255,255,255,.7) inset, 0 1px 2px rgba(15,23,42,.04)',
      }}
    >
      <div className="flex items-start gap-3">
        <div className="flex-1 min-w-0">
          <QkFileChip
            tone={tone}
            name={name}
            rows={rows}
            size={size}
            updatedAt={updatedAt}
            source={source}
          />
        </div>
        <QkRowActions tone={tone} isError={isError} onReplace={onReplace} onRemove={onRemove} />
      </div>
      <QkResultBanner kind={kind} tone={tone} issue={issue} />
    </div>
  );
}

// ──────────────────────────────────────────────────────────────
// 上传卡片主组件
// ──────────────────────────────────────────────────────────────
function QkDropzone({ slot, slotData, onUpload, onRemove, onScrollToTemplates }) {
  const tone = QK_TONES[slot.tone];
  const { state, name, rows, size, updatedAt, source, issue } = slotData;
  const [dragging, setDragging] = React.useState(false);
  const inputRef = React.useRef(null);

  const pickFile = () => inputRef.current?.click();
  const onChange = (e) => {
    const f = e.target.files?.[0];
    if (f) onUpload(slot.key, f);
    e.target.value = '';
  };
  const onDragOver  = (e) => { e.preventDefault(); if (!dragging) setDragging(true); };
  const onDragLeave = (e) => { e.preventDefault(); setDragging(false); };
  const onDrop = (e) => {
    e.preventDefault();
    setDragging(false);
    const f = e.dataTransfer?.files?.[0];
    if (f) onUpload(slot.key, f);
    else window.ui?.toast?.('未检测到文件', { kind: 'warning', desc: '请拖入本校的 .xlsx / .xls 文件，或点击选择文件。' });
  };

  // 卡片整体边框
  const cardBorder =
    dragging         ? `2px solid ${tone.fg}` :
    state === 'error'   ? '1px solid #FECACA' :
    state === 'warning' ? '1px solid #FDE68A' :
    state === 'uploaded'? `1px solid ${tone.fg}40` :
    state === 'validating' ? `1px solid ${tone.border}` :
    '1px solid var(--border-soft)';

  const cardShadow = dragging
    ? `0 0 0 4px ${tone.fg}1A, 0 12px 28px -8px ${tone.fg}33`
    : state === 'error'
      ? '0 1px 0 rgba(255,255,255,.95) inset, 0 1px 3px rgba(15,23,42,.04), 0 6px 18px -8px rgba(239,68,68,.22)'
      : state === 'warning'
        ? '0 1px 0 rgba(255,255,255,.95) inset, 0 1px 3px rgba(15,23,42,.04), 0 6px 18px -8px rgba(245,158,11,.22)'
        : state === 'uploaded'
          ? `0 1px 0 rgba(255,255,255,.95) inset, 0 1px 3px rgba(15,23,42,.04), 0 6px 18px -8px ${tone.fg}33`
          : '0 1px 0 rgba(255,255,255,.95) inset, 0 1px 3px rgba(15,23,42,.04), 0 4px 12px -4px rgba(15,23,42,.06)';

  return (
    <div
      className="rounded-2xl p-5 flex flex-col transition-all"
      style={{
        background: dragging ? tone.soft : 'linear-gradient(180deg, #FFFFFF 0%, #FCFCFD 100%)',
        border: cardBorder,
        boxShadow: cardShadow,
        transform: dragging ? 'scale(1.01)' : 'scale(1)',
      }}
      onDragOver={state === 'idle' ? onDragOver : undefined}
      onDragLeave={state === 'idle' ? onDragLeave : undefined}
      onDrop={state === 'idle' ? onDrop : undefined}
    >
      <input ref={inputRef} type="file" accept=".xlsx,.xls" hidden onChange={onChange} />

      {/* 顶部色码 + 状态 Tag */}
      <div className="flex items-start justify-between mb-3 gap-2">
        <span
          className="text-[12px] px-2 py-0.5 rounded font-semibold whitespace-nowrap"
          style={{ background: tone.bg, color: tone.fg }}
        >
          表 {slot.num}
        </span>
        <div className="flex items-center gap-1.5">
          {source === 'sample' && state !== 'idle' && <QkSourceTag />}
          <QkStateTag state={state} tone={tone} />
        </div>
      </div>

      {/* 语义主图标 + 标题 */}
      <div className="flex items-center gap-3 mb-4">
        <span
          className="w-12 h-12 rounded-xl flex items-center justify-center shrink-0"
          style={{ background: tone.soft, border: `1px solid ${tone.border}88` }}
        >
          <ProductGlyph name={slot.glyph} size={32} />
        </span>
        <div className="min-w-0">
          <div className="text-[15px] font-semibold" style={{ color: 'var(--text-strong)' }}>
            {slot.title}
          </div>
          <div className="text-[12px] mt-1 leading-snug" style={{ color: 'var(--text-soft)' }}>
            {slot.sub}
          </div>
        </div>
      </div>

      {/* 主体 (按状态切换) */}
      {state === 'idle' && <QkBodyIdle tone={tone} slot={slot} dragging={dragging} onPick={pickFile} />}
      {state === 'validating' && <QkBodyValidating tone={tone} name={name} />}
      {(state === 'uploaded' || state === 'warning' || state === 'error') && (
        <QkBodyResult
          tone={tone}
          kind={state}
          name={name}
          rows={rows}
          size={size}
          updatedAt={updatedAt}
          source={source}
          issue={issue}
          onReplace={pickFile}
          onRemove={() => onRemove(slot.key)}
        />
      )}

      {/* 底部公共栏 */}
      <div className="mt-3 pt-3 flex items-center justify-between text-[12px] gap-2"
        style={{ borderTop: '1px solid var(--border-soft)' }}>
        <span style={{ color: 'var(--text-soft)' }}>
          字段不清楚?
        </span>
        <div className="flex items-center gap-3">
          <button
            onClick={() => downloadQkTemplate(slot)}
            className="font-medium inline-flex items-center gap-1 hover:underline whitespace-nowrap"
            style={{ color: tone.fg }}
          >
            <IconArrowDown size={11} strokeWidth={2.5} />
            下载模板
          </button>
          <span style={{ color: 'var(--border-strong)' }}>·</span>
          <button
            onClick={onScrollToTemplates}
            className="font-medium inline-flex items-center gap-1 hover:underline whitespace-nowrap"
            style={{ color: 'var(--text-medium)' }}
          >
            查看字段对照
            <IconChevron dir="right" size={11} />
          </button>
        </div>
      </div>
    </div>
  );
}

// ──────────────────────────────────────────────────────────────
// Step 1 主体
// ──────────────────────────────────────────────────────────────
function QkStep1({ uploads, allReady, hasError, anyValidating, onStartUpload, onRemoveSlot, onUseExample, onNext, validating, templatesRef, backendMode = false, canImportDemo = false }) {
  const counts = Object.values(uploads).reduce((acc, u) => {
    if (u.state === 'uploaded' || u.state === 'warning') acc.ready++;
    if (u.state === 'warning')  acc.warn++;
    if (u.state === 'error')    acc.error++;
    if (u.state === 'validating') acc.validating++;
    return acc;
  }, { ready: 0, warn: 0, error: 0, validating: 0 });

  const scrollToTemplates = () => {
    const el = templatesRef?.current;
    if (!el) return;
    const btn = el.querySelector('button');
    if (btn && el.querySelector('table') === null) btn.click();
    setTimeout(() => {
      const top = el.getBoundingClientRect().top + window.scrollY - 84;
      window.scrollTo({ top, behavior: 'smooth' });
    }, 60);
  };

  return (
    <section className="space-y-5">
      <QkTemplatesAccordion innerRef={templatesRef} />

      {/* 3 张上传卡 */}
      <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-5">
        {QK_SLOTS.map((slot) => (
          <QkDropzone
            key={slot.key}
            slot={slot}
            slotData={uploads[slot.key]}
            onUpload={onStartUpload}
            onRemove={onRemoveSlot}
            onScrollToTemplates={scrollToTemplates}
          />
        ))}
      </div>

      {/* 步骤底栏 */}
      <div
        className="rounded-2xl px-5 py-3.5 flex items-center gap-4 flex-wrap"
        style={{
          background: 'linear-gradient(180deg, #FFFFFF 0%, #FAFBFC 100%)',
          border: '1px solid var(--border-soft)',
          boxShadow: '0 1px 0 rgba(255,255,255,.95) inset, 0 1px 3px rgba(15,23,42,.04)',
        }}
      >
        <div className="flex items-center gap-3">
          <div className="flex items-center gap-1">
            {[0, 1, 2].map((i) => {
              const slotStates = Object.values(uploads).map((u) => u.state);
              const s = slotStates[i];
              const color =
                s === 'error'      ? '#EF4444' :
                s === 'warning'    ? '#F59E0B' :
                s === 'uploaded'   ? '#10B981' :
                s === 'validating' ? '#6366F1' :
                                     '#E2E8F0';
              const ring =
                s === 'error'      ? '#FEE2E2' :
                s === 'warning'    ? '#FEF3C7' :
                s === 'uploaded'   ? '#ECFDF5' :
                                     null;
              return (
                <span
                  key={i}
                  className="w-2.5 h-2.5 rounded-full"
                  style={{
                    background: color,
                    boxShadow: ring ? `0 0 0 2px ${ring}` : 'none',
                    transition: 'all 200ms var(--ease)',
                    animation: s === 'validating' ? 'qkBlink 1.2s ease-in-out infinite' : 'none',
                  }}
                />
              );
            })}
          </div>
          <span className="text-[13px] whitespace-nowrap" style={{ color: 'var(--text-medium)' }}>
            <span className="font-mono font-semibold" style={{ color: 'var(--text-strong)' }}>{counts.ready}/3</span> 张表已就绪
          </span>
          {counts.warn > 0 && (
            <Tag color="amber" dot>{counts.warn} 警告</Tag>
          )}
          {counts.error > 0 && (
            <Tag color="rose" dot>{counts.error} 错误</Tag>
          )}
          {counts.validating > 0 && (
            <span className="text-[12px] inline-flex items-center gap-1" style={{ color: 'var(--brand)' }}>
              <span className="w-1.5 h-1.5 rounded-full" style={{ background: 'var(--brand)', animation: 'qkBlink 1s ease-in-out infinite' }} />
              {counts.validating} 校验中
            </span>
          )}
        </div>
        <span className="text-[12px] whitespace-nowrap" style={{ color: 'var(--text-soft)' }}>
          {hasError
            ? '请先修正错误项'
            : allReady
              ? '可开始校验实际导入数据'
              : '完成 3 张表后进入下一步'}
        </span>
        <div className="flex-1" />
        {canImportDemo && <Button icon={IconSparkle} onClick={onUseExample} disabled={anyValidating || validating}>
          导入演示数据（写入演示学校）
        </Button>}
        <Button
          variant="primary"
          onClick={onNext}
          loading={validating}
          disabled={!allReady || hasError || anyValidating}
          icon={validating ? null : IconCheck}
          style={{
            opacity: (allReady && !hasError) ? 1 : 0.55,
            pointerEvents: (allReady && !hasError) ? 'auto' : 'none',
          }}
        >
          {validating ? '正在校验…' : <>下一步：开始校验 <span style={{ opacity: 0.7, marginLeft: 4 }}>→</span></>}
        </Button>
      </div>

      <style>{`
        @keyframes qkProgress {
          0%   { width: 10%; }
          50%  { width: 80%; }
          100% { width: 10%; }
        }
        @keyframes qkSheen {
          0%   { background-position: 200% 0; }
          100% { background-position: -200% 0; }
        }
        @keyframes qkBlink {
          0%, 100% { opacity: 1; }
          50%      { opacity: 0.35; }
        }
      `}</style>
    </section>
  );
}

// 通用「模拟」角标
function QkMockChip() {
  return (
    <span
      className="text-[12px] px-1.5 py-0.5 rounded ml-1"
      style={{
        background: 'rgba(245,158,11,.16)',
        color: '#92400E',
        fontWeight: 600,
        lineHeight: 1.2,
      }}
    >
      模拟
    </span>
  );
}

function QkBackendChip() {
  return (
    <span
      className="text-[12px] px-1.5 py-0.5 rounded ml-1"
      style={{
        background: 'rgba(16,185,129,.16)',
        color: '#047857',
        fontWeight: 600,
        lineHeight: 1.2,
      }}
    >
      后端
    </span>
  );
}

window.QkStep1 = QkStep1;
window.QkMockChip = QkMockChip;
window.QkBackendChip = QkBackendChip;
window.QK_SLOTS = QK_SLOTS;
window.QK_TONES = QK_TONES;
