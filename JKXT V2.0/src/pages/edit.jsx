// 修改监考安排 — 可视化排班网格(教室 × 时段) + 冲突检测 + 拖拽提示

// Schedule shape: room × time-slot grid with assignment per cell
const ROOMS = [
  { code: 'A-301', name: '高三(1)班教室' },
  { code: 'A-302', name: '高三(2)班教室' },
  { code: 'A-303', name: '高三(3)班教室' },
  { code: 'A-401', name: '高二(1)班教室' },
  { code: 'A-402', name: '高二(2)班教室' },
  { code: 'B-201', name: '初三(1)班教室' },
  { code: 'B-202', name: '初三(2)班教室' },
  { code: 'C-101', name: '物理实验室' },
];

const SLOTS = [
  { day: '11-21 周三', period: '08:00 - 09:30' },
  { day: '11-21 周三', period: '10:00 - 11:30' },
  { day: '11-21 周三', period: '14:30 - 16:00' },
  { day: '11-22 周四', period: '08:00 - 09:30' },
  { day: '11-22 周四', period: '10:00 - 11:30' },
  { day: '11-22 周四', period: '14:30 - 16:00' },
];

// row=room idx, col=slot idx
const ASSIGNMENTS = {
  '0-0': { subject: '高三·英语', t1: '王伟明', t2: '李慧君',  color: 1, count: 30 },
  '0-2': { subject: '高三·数学', t1: '王伟明', t2: '张丽华',  color: 4, count: 30 },
  '0-3': { subject: '高三·语文', t1: '李慧君', t2: '黄建国',  color: 4, count: 30 },
  '1-0': { subject: '高三·英语', t1: '陈志远', t2: '徐安琪',  color: 1, count: 30 },
  '1-2': { subject: '高三·数学', t1: '陈志远', t2: '刘文涛',  color: 4, count: 30 },
  '1-3': { subject: '高三·语文', t1: '张丽华', t2: '周晓梅',  color: 4, count: 30 },
  '2-0': { subject: '高三·英语', t1: '刘文涛', t2: '周晓梅',  color: 1, count: 32 },
  '2-3': { subject: '高三·语文', t1: '徐安琪', t2: '黄建国',  color: 4, count: 32, conflict: '黄建国 14:30 已被高三(2)班占用' },
  '3-1': { subject: '高二·物理', t1: '陈志远', t2: '赵思琪',  color: 4, count: 30 },
  '3-4': { subject: '高二·英语', t1: '张丽华', t2: '徐安琪',  color: 2, count: 30 },
  '4-1': { subject: '高二·物理', t1: '刘文涛', t2: '杨海洋',  color: 4, count: 30, draft: true },
  '5-2': { subject: '初三·化学', t1: '赵思琪', t2: '黄建国',  color: 1, count: 32 },
  '5-5': { subject: '初三·英语', t1: '徐安琪', t2: '周晓梅',  color: 1, count: 32 },
  '6-2': { subject: '初三·化学', t1: '杨海洋', t2: '黄建国',  color: 1, count: 32 },
  '7-4': { subject: '物理 单元测', t1: '陈志远', t2: '刘文涛', color: 3, count: 20 },
};

const SLOT_COLORS = {
  1: '#6366F1', 2: '#10B981', 3: '#F59E0B', 4: '#EF4444', 5: '#06B6D4', 6: '#EC4899',
};

function ConflictBanner() {
  return (
    <div
      className="rounded-xl p-4 flex items-start gap-3"
      style={{ background: 'linear-gradient(135deg, #FEF2F2 0%, #FFF7ED 100%)', border: '1px solid #FCA5A5' }}
    >
      <div className="w-9 h-9 rounded-lg flex items-center justify-center shrink-0"
        style={{ background: '#FEE2E2', color: '#DC2626' }}>
        <IconAlert size={18} strokeWidth={2} />
      </div>
      <div className="flex-1">
        <div className="text-[14px] font-semibold inline-flex items-center gap-2" style={{ color: '#991B1B' }}>
          检测到 1 处排班冲突
          <Tag color="rose" dot>需立即处理</Tag>
        </div>
        <div className="text-[12.5px] mt-1" style={{ color: '#B91C1C' }}>
          <span className="font-mono font-semibold">A-303 · 11-21 14:30</span> 监考人员
          <span className="font-mono font-semibold mx-0.5">黄建国</span>
          同时段已被 <span className="font-mono font-semibold">A-302</span> 占用 ·
          <button className="ml-2 font-medium underline"
            onClick={() => window.ui.toast('建议方案', { kind: 'info', desc: '推荐替换为 周晓梅 / 杜文涛 / 刘文涛' })}>查看建议方案</button>
        </div>
      </div>
      <Button variant="primary" size="sm" icon={IconSparkle}
        onClick={async () => {
          const ok = await window.ui.confirm({
            title: '使用 AI 自动修复冲突?',
            desc: 'AI 将基于当前 6 条策略重新调配冲突班次。修复后可一键撤销。',
            confirmText: 'AI 修复',
          });
          if (ok) {
            window.ui.toast('AI 修复中…', { kind: 'info' });
            setTimeout(() => window.ui.toast('冲突已修复', { kind: 'success', desc: '黄建国 → 换为 周晓梅 · 可撤销' }), 1200);
          }
        }}
      >AI 自动修复</Button>
    </div>
  );
}

function ScheduleCell({ ass, r, c }) {
  if (!ass) {
    return (
      <td
        className="border transition-colors cursor-pointer relative group"
        style={{
          borderColor: 'var(--border-soft)',
          background: '#FAFBFC',
          height: 72,
          minWidth: 168,
        }}
      >
        <span className="absolute inset-0 flex items-center justify-center opacity-0 group-hover:opacity-100 transition-opacity">
          <span className="w-6 h-6 rounded-md inline-flex items-center justify-center"
            style={{ background: 'var(--brand-soft)', color: 'var(--brand)' }}>
            <IconPlus size={14} strokeWidth={2} />
          </span>
        </span>
      </td>
    );
  }
  const color = SLOT_COLORS[ass.color];
  const conflict = !!ass.conflict;
  const draft = ass.draft;
  return (
    <td
      className="border p-1.5 transition-all relative"
      style={{
        borderColor: 'var(--border-soft)',
        background: conflict ? '#FEF2F2' : draft ? '#F8FAFC' : '#fff',
        height: 72,
      }}
    >
      <div
        className="rounded-lg p-2 h-full flex flex-col justify-between cursor-grab transition-all hover:scale-[1.02]"
        style={{
          background: conflict ? '#FEE2E2' : draft ? '#fff' : color + '0D',
          border: `1px ${draft ? 'dashed' : 'solid'} ${conflict ? '#EF4444' : color}55`,
        }}
      >
        <div className="flex items-center gap-1">
          <span className="w-1.5 h-1.5 rounded-full" style={{ background: conflict ? '#EF4444' : color }} />
          <span className="text-[11.5px] font-semibold leading-tight" style={{ color: conflict ? '#991B1B' : 'var(--text-strong)' }}>
            {ass.subject}
          </span>
          {draft && <span className="ml-auto font-mono text-[9px] uppercase tracking-wider" style={{ color: 'var(--text-soft)' }}>草稿</span>}
        </div>
        <div className="flex items-center justify-between gap-1">
          <div className="inline-flex items-center gap-1 min-w-0">
            <AvatarChip name={ass.t1} size={18} />
            <span className="text-[10.5px] truncate" style={{ color: 'var(--text-medium)' }}>
              {ass.t1}
            </span>
            <span className="text-[10px]" style={{ color: 'var(--text-soft)' }}>+</span>
            <AvatarChip name={ass.t2} size={18} />
            <span className="text-[10.5px] truncate" style={{ color: 'var(--text-medium)' }}>
              {ass.t2}
            </span>
          </div>
          <span className="font-mono text-[9.5px] shrink-0" style={{ color: 'var(--text-soft)' }}>
            {ass.count} 人
          </span>
        </div>
        {conflict && (
          <span
            className="absolute -top-1 -right-1 w-4 h-4 rounded-full inline-flex items-center justify-center"
            style={{ background: '#EF4444', color: '#fff', boxShadow: '0 0 0 2px #fff' }}
          >
            <IconAlert size={9} strokeWidth={3} />
          </span>
        )}
      </div>
    </td>
  );
}

function EditSchedulePage() {
  return (
    <PageShell
      crumbs={['天津市实验中学', '排班作业', '修改监考安排']}
      title="修改监考安排"
      badge="T-2025-017 · 草稿"
      subtitle="拖拽调整考场与监考人员;支持撤销、保存草稿、批量替换"
      actions={
        <>
          <Button icon={IconList} onClick={() => window.navigateTo('tasks')}>切换任务</Button>
          <div className="inline-flex items-center gap-px ml-2"
            style={{ border: '1px solid var(--border-soft)', borderRadius: 8, background: '#fff' }}>
            <button className="w-9 h-9 inline-flex items-center justify-center hover:bg-slate-50 rounded-l-lg" style={{ color: 'var(--text-medium)' }} title="撤销 (⌘Z)"
              onClick={() => window.ui.toast('已撤销上一步', { kind: 'info' })}>
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round"><path d="M3 7h11a5 5 0 0 1 5 5v0a5 5 0 0 1-5 5h-6"/><path d="M7 3 3 7l4 4"/></svg>
            </button>
            <button className="w-9 h-9 inline-flex items-center justify-center hover:bg-slate-50 rounded-r-lg" style={{ color: 'var(--text-medium)' }} title="重做 (⌘⇧Z)"
              onClick={() => window.ui.toast('已重做', { kind: 'info' })}>
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round"><path d="M21 7H10a5 5 0 0 0-5 5v0a5 5 0 0 0 5 5h6"/><path d="M17 3l4 4-4 4"/></svg>
            </button>
          </div>
          <Button onClick={() => window.ui.toast('草稿已保存', { kind: 'success', desc: '24 小时内可随时恢复' })}>保存草稿</Button>
          <Button variant="primary" icon={IconCheck} onClick={async () => {
            const ok = await window.ui.confirm({
              title: '确认发布排班?',
              desc: '发布后将通过邮件通知所有监考教师，并进入「任务总览」可查看。仍可后续调整。',
              confirmText: '确认发布',
            });
            if (ok) {
              window.ui.toast('排班发布成功', { kind: 'success', desc: '已发送 24 封邮件通知', action: { label: '查看任务', onClick: () => window.navigateTo('tasks') } });
            }
          }}>发布排班</Button>
        </>
      }
    >
      <ConflictBanner />

      {/* Toolbar */}
      <div className="bg-white rounded-xl p-3 flex items-center gap-3 flex-wrap"
        style={{ border: '1px solid var(--border-soft)' }}>
        <FilterBar
          search="搜索教室或教师..."
          filters={[
            { label: '年级:', value: '高三 + 高二 + 初三' },
            { label: '日期:', value: '11-21 ~ 11-22' },
            { label: '冲突:', value: '仅显示 1 处' },
          ]}
          right={
            <div className="inline-flex items-center gap-2">
              <Button size="sm" variant="ghost" icon={IconSparkle}>AI 优化</Button>
              <Button size="sm" variant="ghost" icon={IconChart}>批量替换</Button>
            </div>
          }
        />
      </div>

      {/* Grid */}
      <div className="bg-white rounded-2xl overflow-hidden"
        style={{ border: '1px solid var(--border-soft)', boxShadow: 'var(--elevation-1)' }}>
        <div className="overflow-x-auto">
          <table className="w-full" style={{ borderCollapse: 'separate', borderSpacing: 0 }}>
            <thead>
              <tr style={{ background: '#FAFBFC' }}>
                <th className="sticky left-0 z-10 px-4 py-3 text-left"
                  style={{ background: '#FAFBFC', borderBottom: '1px solid var(--border-soft)', minWidth: 200 }}>
                  <div className="text-[11px] uppercase tracking-wider font-mono" style={{ color: 'var(--text-soft)' }}>
                    考场 \ 时段
                  </div>
                </th>
                {SLOTS.map((s, i) => (
                  <th key={i} className="px-3 py-2.5 text-center"
                    style={{ borderBottom: '1px solid var(--border-soft)', minWidth: 168 }}>
                    <div className="font-mono text-[10.5px] uppercase tracking-wider" style={{ color: 'var(--text-soft)' }}>
                      {s.day}
                    </div>
                    <div className="text-[12.5px] font-semibold mt-0.5" style={{ color: 'var(--text-strong)' }}>
                      {s.period}
                    </div>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {ROOMS.map((room, ri) => (
                <tr key={ri}>
                  <td className="sticky left-0 z-10 px-4 py-2"
                    style={{ background: '#fff', borderBottom: '1px solid var(--border-soft)', borderRight: '1px solid var(--border-soft)' }}>
                    <div className="flex items-center gap-2">
                      <span className="font-mono text-[12px] font-semibold px-1.5 py-0.5 rounded"
                        style={{ background: '#F1F5F9', color: 'var(--text-medium)' }}>
                        {room.code}
                      </span>
                      <span className="text-[12.5px]" style={{ color: 'var(--text-strong)' }}>{room.name}</span>
                    </div>
                  </td>
                  {SLOTS.map((_, ci) => (
                    <ScheduleCell key={ci} ass={ASSIGNMENTS[`${ri}-${ci}`]} r={ri} c={ci} />
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {/* Footer summary */}
        <div className="px-5 py-3 flex items-center justify-between"
          style={{ borderTop: '1px solid var(--border-soft)', background: '#FAFBFC' }}>
          <div className="inline-flex items-center gap-4 text-[11.5px]">
            <span className="inline-flex items-center gap-1.5">
              <span className="w-2 h-2 rounded" style={{ background: '#6366F1' }} />
              <span style={{ color: 'var(--text-medium)' }}>月考 <span className="font-mono font-semibold">6</span></span>
            </span>
            <span className="inline-flex items-center gap-1.5">
              <span className="w-2 h-2 rounded" style={{ background: '#EF4444' }} />
              <span style={{ color: 'var(--text-medium)' }}>期中 <span className="font-mono font-semibold">5</span></span>
            </span>
            <span className="inline-flex items-center gap-1.5">
              <span className="w-2 h-2 rounded" style={{ background: '#F59E0B' }} />
              <span style={{ color: 'var(--text-medium)' }}>单元测试 <span className="font-mono font-semibold">1</span></span>
            </span>
            <span className="inline-flex items-center gap-1.5">
              <span className="w-2 h-2 rounded" style={{ background: '#10B981' }} />
              <span style={{ color: 'var(--text-medium)' }}>已发布 <span className="font-mono font-semibold">14</span></span>
            </span>
            <span className="inline-flex items-center gap-1.5">
              <span className="w-2 h-2 rounded" style={{ background: 'transparent', border: '1px dashed #94A3B8' }} />
              <span style={{ color: 'var(--text-medium)' }}>草稿 <span className="font-mono font-semibold">1</span></span>
            </span>
          </div>
          <div className="text-[11.5px]" style={{ color: 'var(--text-soft)' }}>
            最后编辑 <span className="font-mono">2 分钟前</span> · 共 <span className="font-mono">15</span> 个考场已安排
          </div>
        </div>
      </div>

      {/* Right hint about drag interaction */}
      <div className="bg-white rounded-xl px-4 py-3 inline-flex items-center gap-3"
        style={{ border: '1px solid var(--border-soft)' }}>
        <span className="font-mono text-[10.5px] uppercase tracking-wider px-1.5 py-0.5 rounded"
          style={{ background: 'var(--brand-soft)', color: 'var(--brand)' }}>
          快捷键
        </span>
        <span className="text-[11.5px]" style={{ color: 'var(--text-medium)' }}>
          <span className="kbd">drag</span> 移动 · <span className="kbd ml-1">click</span> 编辑 · <span className="kbd ml-1">⌘</span> + <span className="kbd ml-0.5">click</span> 多选 · <span className="kbd ml-1">⌘</span> + <span className="kbd ml-0.5">Z</span> 撤销 · <span className="kbd ml-1">delete</span> 清除
        </span>
      </div>
    </PageShell>
  );
}

window.EditSchedulePage = EditSchedulePage;
