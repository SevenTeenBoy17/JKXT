// 一键排监考 — 4 步向导:上传 → 校验 → 执行 → 结果

function StepIndicator({ step, steps }) {
  return (
    <div className="bg-white rounded-2xl px-6 py-5"
      style={{ border: '1px solid var(--border-soft)', boxShadow: 'var(--elevation-1)' }}>
      <div className="flex items-center">
        {steps.map((s, i) => {
          const done = i < step;
          const active = i === step;
          return (
            <React.Fragment key={s.id}>
              <div className="flex items-center gap-3 shrink-0">
                <span
                  className="relative w-10 h-10 rounded-full inline-flex items-center justify-center font-mono font-bold text-[13px]"
                  style={{
                    background: done ? '#10B981' : active ? 'var(--brand)' : '#F1F5F9',
                    color: (done || active) ? '#fff' : 'var(--text-soft)',
                    boxShadow: active ? '0 0 0 4px rgba(79,70,229,.12)' : 'none',
                  }}
                >
                  {done ? <IconCheck size={16} strokeWidth={3} /> : i + 1}
                </span>
                <div>
                  <div className="text-[10.5px] uppercase font-mono tracking-wider" style={{ color: 'var(--text-soft)' }}>
                    STEP {String(i + 1).padStart(2, '0')}
                  </div>
                  <div className="text-[13.5px] font-semibold mt-0.5" style={{ color: (done || active) ? 'var(--text-strong)' : 'var(--text-soft)' }}>
                    {s.label}
                  </div>
                </div>
              </div>
              {i < steps.length - 1 && (
                <div className="flex-1 mx-4 h-px" style={{
                  background: `linear-gradient(to right, ${done ? '#10B981' : '#E2E8F0'} 50%, #E2E8F0 50%)`,
                }} />
              )}
            </React.Fragment>
          );
        })}
      </div>
    </div>
  );
}

function ExcelDropzone({ title, sub, status = 'idle', filename, rows, sizeKB, color, num, onClick, onFileDrop }) {
  const [dragging, setDragging] = React.useState(false);
  const tones = {
    indigo:  { bg: '#EEF2FF', fg: '#4F46E5' },
    emerald: { bg: '#ECFDF5', fg: '#059669' },
    amber:   { bg: '#FFF7ED', fg: '#D97706' },
  }[color] || { bg: '#EEF2FF', fg: '#4F46E5' };

  const handleDragOver = (e) => {
    e.preventDefault();
    if (!dragging) setDragging(true);
  };
  const handleDragLeave = (e) => {
    e.preventDefault();
    setDragging(false);
  };
  const handleDrop = (e) => {
    e.preventDefault();
    setDragging(false);
    const file = e.dataTransfer?.files?.[0];
    if (file) {
      onFileDrop?.(file);
    } else {
      onClick?.();
    }
  };

  return (
    <div
      className="bg-white rounded-2xl p-5 flex flex-col transition-all hover-lift cursor-pointer"
      onClick={onClick}
      onDragOver={status === 'idle' ? handleDragOver : undefined}
      onDragLeave={status === 'idle' ? handleDragLeave : undefined}
      onDrop={status === 'idle' ? handleDrop : undefined}
      style={{
        border: dragging ? `2px solid ${tones.fg}` :
                status === 'uploaded' ? `1px solid ${tones.fg}` :
                '1px solid var(--border-soft)',
        boxShadow: dragging ? `0 0 0 4px ${tones.fg}22, var(--shadow-hover)` : 'var(--elevation-1)',
        background: dragging ? tones.bg : '#fff',
        transform: dragging ? 'scale(1.02)' : 'scale(1)',
      }}
    >
      <div className="flex items-start justify-between mb-3">
        <span
          className="font-mono text-[10.5px] uppercase tracking-wider px-2 py-0.5 rounded"
          style={{ background: tones.bg, color: tones.fg, fontWeight: 600 }}
        >
          表 {num}
        </span>
        {status === 'uploaded' && <Tag color="emerald" dot>已上传</Tag>}
      </div>
      <div className="text-[15px] font-semibold mb-1" style={{ color: 'var(--text-strong)' }}>
        {title}
      </div>
      <div className="text-[12px] mb-4" style={{ color: 'var(--text-soft)' }}>
        {sub}
      </div>

      {status === 'idle' && (
        <div
          className="rounded-xl p-5 flex flex-col items-center text-center transition-colors cursor-pointer"
          style={{
            background: dragging ? '#fff' : '#F8FAFC',
            border: dragging ? `2px solid ${tones.fg}` : '1.5px dashed #CBD5E1',
          }}
        >
          <div className="w-12 h-12 rounded-xl flex items-center justify-center mb-3"
            style={{
              background: dragging ? tones.fg : '#fff',
              color: dragging ? '#fff' : tones.fg,
              boxShadow: 'var(--elevation-1)',
              transform: dragging ? 'translateY(-4px)' : 'translateY(0)',
              transition: 'all 200ms cubic-bezier(.4,0,.2,1)',
            }}>
            <IconUpload size={20} strokeWidth={2} />
          </div>
          <div className="text-[13px] font-medium" style={{ color: dragging ? tones.fg : 'var(--text-strong)' }}>
            {dragging ? '松开以上传' : '点击或拖拽文件'}
          </div>
          <div className="text-[11px] mt-1" style={{ color: 'var(--text-soft)' }}>
            支持 .xlsx / .xls 格式
          </div>
        </div>
      )}

      {status === 'uploaded' && (
        <div className="rounded-xl p-4 space-y-3" style={{ background: tones.bg }}>
          <div className="flex items-start gap-3">
            <div className="w-10 h-12 rounded flex flex-col items-center justify-end pb-1"
              style={{ background: '#fff', border: `1px solid ${tones.fg}33` }}>
              <span className="font-mono text-[9px] font-bold" style={{ color: tones.fg }}>XLSX</span>
            </div>
            <div className="flex-1 min-w-0">
              <div className="text-[12.5px] font-semibold truncate" style={{ color: 'var(--text-strong)' }}>
                {filename}
              </div>
              <div className="text-[10.5px] font-mono mt-0.5" style={{ color: 'var(--text-soft)' }}>
                {rows} 行 · {sizeKB} KB
              </div>
            </div>
            <button className="text-[11px] font-medium" style={{ color: tones.fg }}>替换</button>
          </div>
          <div className="flex items-center gap-2 text-[11px]" style={{ color: tones.fg }}>
            <IconCheck size={12} strokeWidth={2.5} />
            <span>结构校验通过</span>
          </div>
        </div>
      )}

      <div className="mt-3 pt-3 flex items-center justify-between text-[11.5px]"
        style={{ borderTop: '1px solid var(--border-soft)' }}>
        <span style={{ color: 'var(--text-soft)' }}>
          首次使用?
        </span>
        <button className="font-medium inline-flex items-center gap-1 hover:underline" style={{ color: tones.fg }}>
          <IconArrowDown size={11} strokeWidth={2.5} />
          下载模板
        </button>
      </div>
    </div>
  );
}

function QuickSchedulePage() {
  const steps = [
    { id: 'upload',  label: '上传 Excel' },
    { id: 'verify',  label: '数据校验' },
    { id: 'run',     label: '智能排班' },
    { id: 'result',  label: '生成结果' },
  ];
  const [step, setStep] = React.useState(0);
  const [running, setRunning] = React.useState(false);
  const [allUploaded, setAllUploaded] = React.useState(false);

  const startSchedule = () => {
    if (!allUploaded) {
      window.ui.toast('请先上传考场信息表', { kind: 'warning', desc: '3 张 Excel 全部就绪后才能启动' });
      return;
    }
    setRunning(true);
    setStep(1);
    window.ui.toast('数据校验中…', { kind: 'info', desc: '中考考试/教师/教室表互相校验' });
    setTimeout(() => {
      setStep(2);
      window.ui.toast('AI 智能排班进行中…', { kind: 'info', desc: '预计 30 秒 · 正在应用本校 5 条策略' });
      setTimeout(() => {
        setStep(3);
        setRunning(false);
        window.ui.toast('排班完成', { kind: 'success', desc: '成功分配 48 人次监考 · 0 冲突', action: { label: '查看结果', onClick: () => window.navigateTo('tasks') } });
      }, 2200);
    }, 1200);
  };

  return (
    <PageShell
      crumbs={['天津市实验中学', '排班作业', '一键排监考']}
      title="一键排监考"
      badge="3 步上手 · 平均 2 分钟"
      subtitle="上传 3 张 Excel,系统将依据预置策略自动完成监考排班并生成可下载的结果文件"
      actions={
        <>
          <Button icon={IconHelp} onClick={() => window.ui.toast('样例文件已在新窗口打开', { kind: 'info' })}>查看示例</Button>
          <Button icon={IconList} onClick={() => window.navigateTo('tasks')}>历史任务</Button>
        </>
      }
    >
      <StepIndicator step={step} steps={steps} />

      <div className="grid grid-cols-3 gap-5">
        <ExcelDropzone
          num="01" color="indigo"
          title="考试安排表"
          sub="考试场次 / 时间 / 班级 / 科目"
          status="uploaded"
          filename="2025秋·初二期中考_排考.xlsx"
          rows={48} sizeKB={32}
        />
        <ExcelDropzone
          num="02" color="emerald"
          title="监考人员表"
          sub="教师 / 性别 / 年级 / 班主任标记"
          status="uploaded"
          filename="实验中学_教师档案_2025秋.xlsx"
          rows={142} sizeKB={56}
        />
        <ExcelDropzone
          num="03" color="amber"
          title="考场信息表"
          sub="教室 / 容量 / 楼栋 / 类型"
          status={allUploaded ? 'uploaded' : 'idle'}
          filename={allUploaded ? '实验中学_考场信息_2025秋.xlsx' : undefined}
          rows={allUploaded ? 36 : undefined} sizeKB={allUploaded ? 18 : undefined}
          onClick={() => { setAllUploaded(true); window.ui.toast('上传成功', { kind: 'success', desc: '识别 36 间教室 · 查验完成' }); }}
          onFileDrop={(file) => {
            setAllUploaded(true);
            window.ui.toast(`已上传 ${file.name}`, { kind: 'success', desc: `${(file.size/1024).toFixed(0)} KB · 36 行数据 · 查验完成` });
          }}
        />
      </div>

      {/* Bottom info + CTA */}
      <div className="grid grid-cols-[1fr_360px] gap-5">
        {/* Pre-check summary */}
        <div className="bg-white rounded-2xl p-5"
          style={{ border: '1px solid var(--border-soft)', boxShadow: 'var(--elevation-1)' }}>
          <SectionTitle title="数据预览 · 智能识别" sub="已自动识别字段,如需修改可点击列名调整" />
          <div className="grid grid-cols-3 gap-3">
            {[
              { k: '排考场次', v: '8', u: '场', c: '#4F46E5' },
              { k: '涉及考期', v: '3', u: '天', c: '#0EA5E9' },
              { k: '考试时段', v: '6', u: '段', c: '#06B6D4' },
              { k: '可用教师', v: '142', u: '人', c: '#059669' },
              { k: '可用考场', v: '24', u: '间', c: '#D97706' },
              { k: '需要监考', v: '48', u: '人次', c: '#9333EA' },
            ].map((r) => (
              <div key={r.k} className="rounded-lg p-3" style={{ background: '#F8FAFC' }}>
                <div className="text-[10.5px] font-mono uppercase tracking-wider" style={{ color: 'var(--text-soft)' }}>{r.k}</div>
                <div className="mt-1 flex items-baseline gap-1">
                  <span className="num text-[20px] font-bold" style={{ color: r.c }}>{r.v}</span>
                  <span className="text-[12px]" style={{ color: 'var(--text-medium)' }}>{r.u}</span>
                </div>
              </div>
            ))}
          </div>

          {/* Validation warnings (visual placeholder) */}
          <div className="mt-4 rounded-lg p-3 flex items-start gap-3"
            style={{ background: '#FFF7ED', border: '1px solid #FED7AA' }}>
            <IconAlert size={16} className="shrink-0 mt-0.5" style={{ color: '#D97706' }} />
            <div className="text-[12px]" style={{ color: '#92400E' }}>
              <div className="font-semibold mb-0.5">还有 1 张 Excel 未上传</div>
              <div style={{ color: '#B45309' }}>上传考场信息表后即可一键生成。完整数据可大幅提升排班质量。</div>
            </div>
          </div>
        </div>

        {/* CTA card */}
        <div className="rounded-2xl p-6 text-white relative overflow-hidden flex flex-col"
          style={{
            background: 'linear-gradient(135deg, #4F46E5 0%, #7C3AED 100%)',
            boxShadow: '0 12px 32px rgba(79,70,229,.32)',
          }}>
          <div className="flex items-center gap-2 font-mono text-[10.5px] uppercase tracking-wider opacity-80">
            <IconBolt size={12} /> READY TO GO
          </div>
          <div className="text-[22px] font-bold leading-tight mt-2">
            智能排班引擎
          </div>
          <div className="text-[12.5px] opacity-80 mt-1.5 leading-relaxed">
            已就绪 2/3 张表。完成最后一张表的上传,即可一键启动算法,平均耗时 30-90 秒。
          </div>
          <div className="mt-4 space-y-2 text-[12px]">
            {['班主任避监本班', '同年级回避', '男女混合监考', '科目错峰', '工作量均衡'].map((s) => (
              <div key={s} className="flex items-center gap-2 opacity-90">
                <IconCheck size={12} strokeWidth={2.5} />
                <span>{s}</span>
              </div>
            ))}
          </div>
          <button
            className="mt-5 h-11 rounded-xl text-[14px] font-semibold inline-flex items-center justify-center gap-2 transition-all"
            style={{ background: 'rgba(255,255,255,.95)', color: 'var(--brand)', opacity: running ? 0.7 : 1 }}
            onClick={startSchedule}
            disabled={running}
          >
            {running ? (
              <>
                <span className="w-3.5 h-3.5 rounded-full border-2 border-current border-t-transparent animate-spin" />
                AI 正在排班…
              </>
            ) : (
              <>
                <IconBolt size={15} strokeWidth={2.25} />
                开始智能排班
                <span className="kbd" style={{ background: 'rgba(79,70,229,.08)', color: 'var(--brand)', borderColor: 'transparent' }}>↵</span>
              </>
            )}
          </button>
          <svg className="absolute -right-8 -bottom-8 opacity-20 pointer-events-none" width="200" height="200" viewBox="0 0 200 200">
            <g stroke="#fff" strokeWidth="1" fill="none">
              <circle cx="160" cy="50" r="40" />
              <circle cx="160" cy="50" r="60" />
              <circle cx="160" cy="50" r="80" />
              <circle cx="160" cy="50" r="100" />
            </g>
          </svg>
        </div>
      </div>
    </PageShell>
  );
}

window.QuickSchedulePage = QuickSchedulePage;
