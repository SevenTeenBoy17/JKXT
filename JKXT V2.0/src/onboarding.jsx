// 新手引导 — 7 步全屏 modal,首次登录触发

const ONBOARDING_STEPS = [
  {
    id: 'welcome',
    title: '欢迎使用 排监考系统 v2',
    sub: 'K-12 完全中学专属版 · 让监考排班从 2 小时变成 2 分钟',
    icon: IconSparkle,
    accent: '#4F46E5',
    visual: (
      <div className="relative rounded-2xl p-8 overflow-hidden h-[280px]"
        style={{ background: 'linear-gradient(135deg, #EEF2FF 0%, #FAF5FF 100%)' }}>
        <svg width="200" height="200" viewBox="0 0 200 200" className="mx-auto" style={{ marginTop: 20 }}>
          <circle cx="100" cy="100" r="80" fill="#fff" stroke="#4F46E5" strokeWidth="2" />
          <rect x="55" y="65" width="90" height="14" rx="3" fill="#6366F1" />
          <rect x="55" y="85" width="60" height="14" rx="3" fill="#A5B4FC" />
          <rect x="55" y="105" width="75" height="14" rx="3" fill="#C7D2FE" />
          <rect x="55" y="125" width="40" height="14" rx="3" fill="#E0E7FF" />
          <circle cx="145" cy="145" r="22" fill="#10B981" />
          <path d="M137 145 L142 150 L153 139" stroke="#fff" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" fill="none" />
        </svg>
      </div>
    ),
    points: [
      '一键上传 3 张 Excel,30-90 秒自动生成监考排班',
      '6 条 K-12 专属策略 · 班主任避监本班 / 同年级回避',
      '冲突实时检测 · AI 自动修复',
      '4 维度统计 · 教师 / 学科 / 年级 / 学期',
    ],
  },
  {
    id: 'excel',
    title: '准备 3 张 Excel 数据表',
    sub: '系统会自动识别表头,你只需准备好这 3 张表',
    icon: IconUpload,
    accent: '#059669',
    visual: (
      <div className="grid grid-cols-3 gap-3 h-[280px]">
        {[
          { n: '01', t: '考试安排表', c: '考试场次 / 班级 / 科目', color: '#4F46E5', bg: '#EEF2FF' },
          { n: '02', t: '监考人员表', c: '工号 / 年级 / 班主任', color: '#059669', bg: '#ECFDF5' },
          { n: '03', t: '考场信息表', c: '编号 / 容量 / 楼栋',     color: '#D97706', bg: '#FFF7ED' },
        ].map((e) => (
          <div key={e.n} className="rounded-xl p-4 flex flex-col" style={{ background: e.bg, border: `1px solid ${e.color}33` }}>
            <span className="font-mono text-[10px] uppercase tracking-wider px-1.5 py-0.5 rounded inline-block w-fit"
              style={{ background: '#fff', color: e.color, fontWeight: 600 }}>表 {e.n}</span>
            <div className="flex-1 flex items-center justify-center">
              <div className="w-16 h-20 rounded flex flex-col items-center justify-end pb-1.5"
                style={{ background: '#fff', border: `1.5px solid ${e.color}55` }}>
                <span className="font-mono text-[9px] font-bold" style={{ color: e.color }}>XLSX</span>
              </div>
            </div>
            <div className="text-[13px] font-semibold mb-0.5" style={{ color: 'var(--text-strong)' }}>{e.t}</div>
            <div className="text-[10.5px]" style={{ color: 'var(--text-soft)' }}>{e.c}</div>
          </div>
        ))}
      </div>
    ),
    points: [
      '每张 Excel 表头自动识别 · 兼容多种字段命名',
      '首次使用?点击「下载模板」获取标准模板',
      '数据可以分次更新 · 系统会智能合并',
    ],
  },
  {
    id: 'quick',
    title: '一键排监考 · 4 步流程',
    sub: '上传 → 校验 → AI 排班 → 生成结果',
    icon: IconBolt,
    accent: '#9333EA',
    visual: (
      <div className="rounded-2xl p-6 h-[280px] flex flex-col justify-center"
        style={{ background: 'linear-gradient(135deg, #FAF5FF 0%, #EEF2FF 100%)' }}>
        <div className="flex items-center gap-3">
          {['上传 Excel', '数据校验', '智能排班', '生成结果'].map((s, i) => (
            <React.Fragment key={s}>
              <div className="flex flex-col items-center gap-2 flex-1">
                <span className="w-12 h-12 rounded-full flex items-center justify-center font-mono font-bold text-[14px] text-white"
                  style={{ background: i < 2 ? '#10B981' : i === 2 ? '#4F46E5' : '#CBD5E1' }}>
                  {i < 2 ? <IconCheck size={18} strokeWidth={3} /> : i + 1}
                </span>
                <div className="text-[11.5px] font-semibold text-center" style={{ color: 'var(--text-strong)' }}>{s}</div>
              </div>
              {i < 3 && <div className="flex-1 h-px" style={{ background: i < 2 ? '#10B981' : '#E2E8F0' }} />}
            </React.Fragment>
          ))}
        </div>
        <div className="mt-6 text-center">
          <div className="font-mono text-[10.5px] uppercase tracking-wider" style={{ color: 'var(--text-soft)' }}>平均耗时</div>
          <div className="num text-[38px] font-bold" style={{ color: 'var(--brand)' }}>30-90<span className="text-[16px] ml-1" style={{ color: 'var(--text-medium)' }}>秒</span></div>
        </div>
      </div>
    ),
    points: [
      '上传后自动校验数据完整性',
      '系统应用预设策略,30-90 秒内出结果',
      '可下载 4 个文件 · 完整排班表 / 教师视图 / 考场视图 / AI 摘要',
    ],
  },
  {
    id: 'ai',
    title: 'AI 策略监考 · 个性化定制',
    sub: '6 条预置策略 + 自然语言定制 = 完美贴合本校实际',
    icon: IconSparkle,
    accent: '#EC4899',
    visual: (
      <div className="rounded-2xl p-6 h-[280px] flex flex-col gap-2.5"
        style={{ background: 'linear-gradient(135deg, #FAF5FF 0%, #FCE7F3 100%)' }}>
        {['班主任避监本班', '同年级回避', '男女混合监考', '副科监主科', '工作量均衡 ±2 场', '跨年级优先'].map((s) => (
          <div key={s} className="bg-white rounded-lg p-2.5 flex items-center gap-2.5"
            style={{ boxShadow: 'var(--elevation-1)' }}>
            <IconCheck size={14} strokeWidth={2.5} style={{ color: '#10B981' }} />
            <span className="text-[12.5px] font-medium" style={{ color: 'var(--text-strong)' }}>{s}</span>
            <span className="ml-auto px-1.5 py-0.5 rounded text-[10px] font-mono"
              style={{ background: 'var(--brand-soft)', color: 'var(--brand)' }}>K-12 专属</span>
          </div>
        ))}
      </div>
    ),
    points: [
      '6 条策略一键勾选 · 中小学场景专属',
      '自然语言补充 "王老师周一外出请避开"',
      '可切换 4 大主流 AI 模型 · Qwen / DeepSeek / 豆包 / GPT',
    ],
  },
  {
    id: 'edit',
    title: '拖拽即调整 · 实时冲突检测',
    sub: '生成的排班可以随时调整,冲突会实时高亮提示',
    icon: IconEdit,
    accent: '#0EA5E9',
    visual: (
      <div className="rounded-2xl p-5 h-[280px] flex items-center justify-center"
        style={{ background: 'linear-gradient(135deg, #E0F2FE 0%, #EEF2FF 100%)' }}>
        <div className="grid grid-cols-3 gap-2 w-full">
          {[
            { c: '#10B981', l: '高三·英语', t1: '王伟明', t2: '李慧君', ok: true },
            { c: '#EF4444', l: '高三·数学', t1: '张丽华', t2: '黄建国', conflict: true },
            { c: '#F59E0B', l: '高二·物理', t1: '陈志远', t2: '刘文涛', ok: true },
            { c: '#CBD5E1', l: '— 空闲 —', empty: true },
            { c: '#6366F1', l: '高一·化学', t1: '徐安琪', t2: '周晓梅', ok: true },
            { c: '#CBD5E1', l: '— 空闲 —', empty: true },
          ].map((cell, i) => (
            <div key={i} className="rounded-lg p-2.5"
              style={{
                background: cell.empty ? '#fff' : cell.c + '20',
                border: cell.conflict ? '2px solid #EF4444' : `1px solid ${cell.c}55`,
                animation: cell.conflict ? 'shake 600ms ease-in-out infinite' : undefined,
              }}>
              <div className="text-[11px] font-semibold leading-tight" style={{ color: cell.empty ? 'var(--text-soft)' : 'var(--text-strong)' }}>
                {cell.l}
              </div>
              {!cell.empty && (
                <div className="text-[10px] mt-1 font-mono" style={{ color: 'var(--text-medium)' }}>
                  {cell.t1}+{cell.t2}
                </div>
              )}
              {cell.conflict && (
                <div className="mt-1 text-[9.5px] font-semibold" style={{ color: '#DC2626' }}>
                  ⚠ 冲突
                </div>
              )}
            </div>
          ))}
        </div>
      </div>
    ),
    points: [
      '考场 × 时段网格视图 · 一目了然',
      '拖拽监考人员 · 冲突 cell 红色高亮 + 抖动提示',
      '一键 AI 自动修复 · 撤销/重做随时可逆',
    ],
  },
  {
    id: 'rules',
    title: '中小学专属业务规则',
    sub: '系统深度理解 K-12 教务场景,而非套用大学逻辑',
    icon: IconCheck,
    accent: '#10B981',
    visual: (
      <div className="rounded-2xl p-5 h-[280px]"
        style={{ background: 'linear-gradient(135deg, #ECFDF5 0%, #EEF2FF 100%)' }}>
        <div className="grid grid-cols-2 gap-3 h-full">
          {[
            { icon: IconUsers,    t: '班主任标记', d: '班主任老师不监考自己班级,系统自动识别' },
            { icon: IconBuilding, t: '完中学段',   d: '初一-高三 6 个年级一站管理,年级配置灵活' },
            { icon: IconCalendar, t: '月考密集',  d: '月考 / 期中 / 期末 / 模拟考 / 联考 5 种类型' },
            { icon: IconChart,    t: '津贴自动核算', d: '场次 × 时长 × 单价,按学期/月生成报表' },
          ].map((r) => (
            <div key={r.t} className="bg-white rounded-xl p-3"
              style={{ boxShadow: 'var(--elevation-1)' }}>
              <span className="w-9 h-9 rounded-lg inline-flex items-center justify-center mb-2"
                style={{ background: 'var(--brand-soft)', color: 'var(--brand)' }}>
                <r.icon size={16} strokeWidth={2} />
              </span>
              <div className="text-[13px] font-semibold leading-tight" style={{ color: 'var(--text-strong)' }}>{r.t}</div>
              <div className="text-[11px] mt-1 leading-relaxed" style={{ color: 'var(--text-soft)' }}>{r.d}</div>
            </div>
          ))}
        </div>
      </div>
    ),
    points: [
      '所有数据字段都是中小学教务的真实业务字段',
      '不是从高校系统硬改的 · 从零为 K-12 设计',
      '支持完全中学 / 初中 / 高中 / 小学 / 九年一贯 5 种学段',
    ],
  },
  {
    id: 'done',
    title: '现在开始使用',
    sub: '从仪表盘开始,或直接上传 Excel 完成首次排班',
    icon: IconCheck,
    accent: '#10B981',
    visual: (
      <div className="relative rounded-2xl h-[280px] flex flex-col items-center justify-center overflow-hidden text-white"
        style={{ background: 'linear-gradient(135deg, #4F46E5 0%, #7C3AED 100%)' }}>
        <svg className="absolute -right-8 -bottom-8 opacity-20" width="200" height="200" viewBox="0 0 200 200">
          <circle cx="100" cy="100" r="60" fill="none" stroke="#fff" strokeWidth="1.5" />
          <circle cx="100" cy="100" r="80" fill="none" stroke="#fff" strokeWidth="1.5" />
          <circle cx="100" cy="100" r="100" fill="none" stroke="#fff" strokeWidth="1.5" />
        </svg>
        <div className="text-[72px] font-bold leading-none">🎉</div>
        <div className="text-[24px] font-bold mt-3">一切就绪</div>
        <div className="text-[13px] opacity-80 mt-1.5">3 分钟,你已掌握全部核心功能</div>
      </div>
    ),
    points: [
      '随时可在「帮助 ❓」中重新查看本引导',
      '所有功能均提供 toast / 详情抽屉等交互反馈',
      '问题反馈请联系 wangmy@tsy.edu.cn',
    ],
  },
];

function OnboardingModal({ onClose, onFinish }) {
  const [step, setStep] = React.useState(0);
  const total = ONBOARDING_STEPS.length;
  const cur = ONBOARDING_STEPS[step];
  const isLast = step === total - 1;
  const isFirst = step === 0;

  // 服务端持久化 onboarded 标记 — best-effort、不阻塞关闭 modal。
  // 若不回写后端，下次登录时 /auth/me 仍返回 onboarded=false，引导会被错误地重新触发。
  const persistOnboarded = () => {
    try { localStorage.setItem('jkxt:onboarded', '1'); } catch {}
    try { window.api?.post('/auth/me/onboarded', {})?.catch?.(() => {}); } catch {}
  };

  const finish = () => {
    persistOnboarded();
    onFinish?.();
    onClose();
    window.ui.toast('引导已完成', { kind: 'success', desc: '随时可在「帮助 ❓」中重新查看' });
  };
  // 跳过 — 非阻塞即时关闭 (此前用 async confirm 弹窗会阻塞浏览器自动化导航，P1)。
  // 用一条可撤销的 toast 替代二次确认弹窗，既不打断流程又可挽回误触。
  const skip = () => {
    persistOnboarded();
    onFinish?.();
    onClose();
    window.ui.toast('已跳过新手引导', {
      kind: 'info',
      desc: '随时可在顶栏「帮助 ❓」重新打开',
      action: { label: '重新打开', onClick: () => window.__openOnboarding?.() },
    });
  };

  // 键盘流：Esc 跳过 · ← / → 翻页 (可访问性)
  React.useEffect(() => {
    const onKey = (e) => {
      if (e.key === 'Escape') { e.preventDefault(); skip(); }
      else if (e.key === 'ArrowRight') { setStep((s) => Math.min(total - 1, s + 1)); }
      else if (e.key === 'ArrowLeft')  { setStep((s) => Math.max(0, s - 1)); }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [total]);

  return (
    <div className="fixed inset-0 z-[120] flex items-center justify-center px-6"
      style={{ background: 'rgba(15,23,42,.56)', backdropFilter: 'blur(6px)' }}>
      <div className="bg-white rounded-2xl flex flex-col overflow-hidden"
        style={{
          width: 880,
          maxHeight: '90vh',
          boxShadow: '0 40px 100px rgba(15,23,42,.32)',
          animation: 'modalIn 320ms cubic-bezier(0,0,.2,1)',
        }}>

        {/* Header strip */}
        <div className="px-7 py-5 flex items-center gap-4" style={{ borderBottom: '1px solid var(--border-soft)' }}>
          <span className="w-10 h-10 rounded-xl inline-flex items-center justify-center shrink-0 text-white"
            style={{ background: cur.accent }}>
            <cur.icon size={18} strokeWidth={2.25} />
          </span>
          <div className="flex-1">
            <div className="font-mono text-[10.5px] uppercase tracking-[0.18em]" style={{ color: 'var(--text-soft)' }}>
              第 {step + 1} 步 / 共 {total} 步 · ONBOARDING
            </div>
            <h2 className="text-[20px] font-bold mt-0.5" style={{ color: 'var(--text-strong)' }}>{cur.title}</h2>
            <div className="text-[12.5px] mt-0.5" style={{ color: 'var(--text-medium)' }}>{cur.sub}</div>
          </div>
          <button onClick={skip}
            className="text-[12px] font-medium hover:underline" style={{ color: 'var(--text-soft)' }}>
            跳过引导 ✕
          </button>
        </div>

        {/* Body: visual + points */}
        <div className="flex-1 overflow-y-auto px-7 py-6 scrollbar-thin">
          <div className="grid grid-cols-[1fr_280px] gap-6">
            <div>{cur.visual}</div>
            <ul className="space-y-3">
              <div className="font-mono text-[10.5px] uppercase tracking-wider mb-2" style={{ color: 'var(--text-soft)' }}>
                关键能力
              </div>
              {cur.points.map((p, i) => (
                <li key={i} className="flex items-start gap-2.5">
                  <span className="w-5 h-5 rounded-full flex items-center justify-center shrink-0 mt-0.5"
                    style={{ background: cur.accent + '20', color: cur.accent }}>
                    <IconCheck size={11} strokeWidth={3} />
                  </span>
                  <span className="text-[13px] leading-relaxed" style={{ color: 'var(--text-medium)' }}>{p}</span>
                </li>
              ))}
            </ul>
          </div>
        </div>

        {/* Footer: progress dots + nav */}
        <div className="px-7 py-4 flex items-center justify-between" style={{ borderTop: '1px solid var(--border-soft)', background: '#FAFBFC' }}>
          <div className="flex items-center gap-2">
            {ONBOARDING_STEPS.map((s, i) => (
              <button
                key={s.id}
                onClick={() => setStep(i)}
                className="transition-all rounded-full"
                style={{
                  width: i === step ? 28 : 8,
                  height: 8,
                  background: i === step ? cur.accent : i < step ? '#94A3B8' : '#E2E8F0',
                }}
                title={s.title}
              />
            ))}
            <span className="ml-3 font-mono text-[11.5px]" style={{ color: 'var(--text-soft)' }}>
              {step + 1} / {total}
            </span>
          </div>
          <div className="flex items-center gap-2">
            <Button onClick={() => setStep(Math.max(0, step - 1))} disabled={isFirst}
              style={{ opacity: isFirst ? 0.4 : 1, pointerEvents: isFirst ? 'none' : 'auto' }}>
              ← 上一步
            </Button>
            {isLast ? (
              <Button variant="primary" icon={IconCheck} onClick={finish}>
                开始使用
              </Button>
            ) : (
              <Button variant="primary" onClick={() => setStep(step + 1)}>
                下一步 →
              </Button>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

window.OnboardingModal = OnboardingModal;
