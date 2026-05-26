// 监考人员管理 — 上半屏教师表 + 下半屏选中教师 30 天排班 timeline

const MOCK_TEACHERS = [
  { id: 'T240101', name: '王伟明', gender: '男', grade: '高三', gradeColor: 'rose', subjects: ['数学'], isHomeroom: true, homeroomOf: '高三(7)班', age: 38, seniority: 12, edu: '硕士', phone: '138****1234', email: 'wangwm@tsy.edu.cn', supCount: 18, status: 'active' },
  { id: 'T240102', name: '李慧君', gender: '女', grade: '高三', gradeColor: 'rose', subjects: ['语文'], isHomeroom: true, homeroomOf: '高三(2)班', age: 35, seniority: 10, edu: '硕士', phone: '139****5678', email: 'lihj@tsy.edu.cn', supCount: 16, status: 'active' },
  { id: 'T240201', name: '张丽华', gender: '女', grade: '高二', gradeColor: 'amber', subjects: ['英语'], isHomeroom: false, age: 32, seniority: 8, edu: '本科', phone: '136****9012', email: 'zhanglh@tsy.edu.cn', supCount: 12, status: 'active' },
  { id: 'T240202', name: '陈志远', gender: '男', grade: '高二', gradeColor: 'amber', subjects: ['物理'], isHomeroom: true, homeroomOf: '高二(5)班', age: 42, seniority: 16, edu: '硕士', phone: '137****3456', email: 'chenzy@tsy.edu.cn', supCount: 14, status: 'active' },
  { id: 'T240301', name: '刘文涛', gender: '男', grade: '高一', gradeColor: 'cyan', subjects: ['化学'], isHomeroom: false, age: 29, seniority: 5, edu: '硕士', phone: '135****7890', email: 'liuwt@tsy.edu.cn', supCount: 10, status: 'active' },
  { id: 'J240901', name: '赵思琪', gender: '女', grade: '初三', gradeColor: 'indigo', subjects: ['生物'], isHomeroom: true, homeroomOf: '初三(4)班', age: 31, seniority: 7, edu: '本科', phone: '138****2345', email: 'zhaosq@tsy.edu.cn', supCount: 11, status: 'active' },
  { id: 'J240902', name: '黄建国', gender: '男', grade: '初三', gradeColor: 'indigo', subjects: ['历史'], isHomeroom: false, age: 45, seniority: 20, edu: '本科', phone: '139****6789', email: 'huangjg@tsy.edu.cn', supCount: 8, status: 'active' },
  { id: 'J240801', name: '周晓梅', gender: '女', grade: '初二', gradeColor: 'emerald', subjects: ['地理'], isHomeroom: true, homeroomOf: '初二(3)班', age: 36, seniority: 11, edu: '本科', phone: '136****0123', email: 'zhouxm@tsy.edu.cn', supCount: 9, status: 'active' },
  { id: 'J240802', name: '杨海洋', gender: '男', grade: '初二', gradeColor: 'emerald', subjects: ['体育', '健康'], isHomeroom: false, age: 33, seniority: 9, edu: '本科', phone: '137****4567', email: 'yanghy@tsy.edu.cn', supCount: 6, status: 'leave' },
  { id: 'J240701', name: '徐安琪', gender: '女', grade: '七年级', gradeColor: 'violet', subjects: ['英语'], isHomeroom: true, homeroomOf: '七(2)班', age: 27, seniority: 3, edu: '本科', phone: '135****8901', email: 'xuaq@tsy.edu.cn', supCount: 7, status: 'active' },
];

// Per-teacher schedule: 30 天 timeline 数据 (selected teacher 显示)
const MOCK_SCHEDULE = {
  T240101: [
    { day: 5,  span: 1, label: '高二·数学 单元测', color: 3 },
    { day: 8,  span: 1, label: '初三·数学 月考', color: 1 },
    { day: 12, span: 2, label: '高三·一模联考 监场', color: 5 },
    { day: 18, span: 1, label: '高一·数学 月考', color: 1 },
    { day: 21, span: 1, label: '初二·数学 期中考', color: 4 },
    { day: 25, span: 2, label: '高二·物理 期中考 监场', color: 4 },
    { day: 28, span: 1, label: '七年级·数学 单元测', color: 3 },
  ],
};

const SCH_COLORS = {
  1: '#6366F1', 2: '#10B981', 3: '#F59E0B', 4: '#EF4444', 5: '#06B6D4', 6: '#EC4899',
};

function GenderIcon({ g }) {
  return g === '男' ? (
    <span className="inline-flex items-center justify-center w-5 h-5 rounded-full" style={{ background: '#E0F2FE', color: '#0369A1' }}>
      <svg width="11" height="11" viewBox="0 0 12 12" fill="none"><circle cx="5" cy="7" r="3" stroke="currentColor" strokeWidth="1.5"/><path d="M7.5 4.5 L11 1 M11 1 V4 M11 1 H8" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"/></svg>
    </span>
  ) : (
    <span className="inline-flex items-center justify-center w-5 h-5 rounded-full" style={{ background: '#FCE7F3', color: '#BE185D' }}>
      <svg width="11" height="11" viewBox="0 0 12 12" fill="none"><circle cx="6" cy="5" r="3" stroke="currentColor" strokeWidth="1.5"/><path d="M6 8 V11 M4 10 H8" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"/></svg>
    </span>
  );
}

function TeacherScheduleTimeline({ teacher }) {
  const schedule = MOCK_SCHEDULE[teacher?.id] || MOCK_SCHEDULE['T240101'];
  const days = 30;
  const colW = 30;
  const gap = 2;

  // generate day labels
  const dayLabels = Array.from({ length: days }, (_, i) => ({
    n: i + 1,
    isToday: i + 1 === 21,
    isWeekend: ((i + 1) % 7 === 6) || ((i + 1) % 7 === 0),
  }));

  // group pills by row (auto-pack)
  const rows = [[], [], []];
  schedule.forEach(p => {
    for (const r of rows) {
      if (r.every(q => q.day + q.span <= p.day || p.day + p.span <= q.day)) {
        r.push(p);
        return;
      }
    }
  });

  return (
    <div className="bg-white rounded-2xl p-5" style={{ border: '1px solid var(--border-soft)', boxShadow: 'var(--elevation-1)' }}>
      <SectionTitle
        title={teacher ? `${teacher.name} · 近 30 天监考排班` : '请选择教师查看排班'}
        sub={teacher ? `共 ${schedule.length} 场 · 累计 ${teacher.supCount} 场监考 · ${teacher.grade} ${teacher.subjects.join('/')}` : '从上方表格点击行查看个人排班'}
        right={
          teacher && (
            <div className="flex items-center gap-2">
              <Button size="sm" icon={IconCalendar}>切换月份</Button>
              <Button size="sm" variant="soft" icon={IconEdit}>调整排班</Button>
            </div>
          )
        }
      />

      {/* Day grid header */}
      <div className="flex gap-0.5 mb-2 mt-2">
        {dayLabels.map((d) => (
          <div key={d.n} className="flex flex-col items-center" style={{ width: colW }}>
            <span
              className="font-mono text-[10px]"
              style={{
                color: d.isToday ? 'var(--brand)' : d.isWeekend ? '#CBD5E1' : 'var(--text-soft)',
                fontWeight: d.isToday ? 700 : 400,
              }}
            >
              {d.n}
            </span>
          </div>
        ))}
      </div>

      {/* Grid + pills */}
      <div className="relative" style={{ height: rows.length * 38 + 8 }}>
        {/* vertical guides */}
        <div className="absolute inset-0 flex gap-0.5 pointer-events-none">
          {dayLabels.map((d, i) => (
            <div
              key={i}
              className="rounded"
              style={{
                width: colW,
                height: '100%',
                background: d.isToday ? 'rgba(238,242,255,.55)' : d.isWeekend ? '#FAFBFC' : 'transparent',
              }}
            />
          ))}
        </div>
        {/* today vertical line */}
        <div
          className="absolute top-0 bottom-0 w-px pointer-events-none"
          style={{ left: 20 * (colW + 2) + colW / 2, background: 'var(--brand)', opacity: 0.4 }}
        />
        {/* pills */}
        {rows.map((row, ri) =>
          row.map((p, pi) => (
            <div
              key={`${ri}-${pi}`}
              className="absolute rounded-full px-2.5 flex items-center gap-1.5 text-[10.5px] font-semibold text-white cursor-pointer transition-all hover:scale-[1.04]"
              style={{
                left: (p.day - 1) * (colW + 2),
                top: ri * 38,
                width: p.span * colW + (p.span - 1) * 2,
                height: 28,
                background: SCH_COLORS[p.color],
                boxShadow: 'var(--elevation-1), inset 0 1px 0 rgba(255,255,255,.18)',
              }}
            >
              <span className="w-1.5 h-1.5 rounded-full bg-white shrink-0" />
              <span className="truncate">{p.label}</span>
            </div>
          ))
        )}
      </div>

      {/* Stats summary */}
      <div className="grid grid-cols-4 gap-4 mt-5 pt-4" style={{ borderTop: '1px solid var(--border-soft)' }}>
        <div>
          <div className="text-[10.5px] uppercase tracking-wider font-mono" style={{ color: 'var(--text-soft)' }}>累计监考</div>
          <div className="mt-1 num text-[20px] font-bold" style={{ color: 'var(--text-strong)' }}>{teacher?.supCount || 0} <span className="text-[12px] font-normal" style={{ color: 'var(--text-medium)' }}>场</span></div>
        </div>
        <div>
          <div className="text-[10.5px] uppercase tracking-wider font-mono" style={{ color: 'var(--text-soft)' }}>本月监考</div>
          <div className="mt-1 num text-[20px] font-bold" style={{ color: 'var(--text-strong)' }}>{schedule.length} <span className="text-[12px] font-normal" style={{ color: 'var(--text-medium)' }}>场</span></div>
        </div>
        <div>
          <div className="text-[10.5px] uppercase tracking-wider font-mono" style={{ color: 'var(--text-soft)' }}>累计津贴</div>
          <div className="mt-1 num text-[20px] font-bold" style={{ color: 'var(--text-strong)' }}>¥{(teacher?.supCount || 0) * 120}</div>
        </div>
        <div>
          <div className="text-[10.5px] uppercase tracking-wider font-mono" style={{ color: 'var(--text-soft)' }}>本年级均值</div>
          <div className="mt-1 num text-[20px] font-bold" style={{ color: 'var(--text-medium)' }}>14.2 <span className="text-[12px] font-normal" style={{ color: 'var(--text-soft)' }}>场</span></div>
        </div>
      </div>
    </div>
  );
}

function PeoplePage() {
  const [selectedTeacher, setSelectedTeacher] = React.useState(MOCK_TEACHERS[0]);

  const total = MOCK_TEACHERS.length;
  const homerooms = MOCK_TEACHERS.filter(t => t.isHomeroom).length;
  const onLeave = MOCK_TEACHERS.filter(t => t.status === 'leave').length;
  const totalSup = MOCK_TEACHERS.reduce((a, b) => a + b.supCount, 0);

  const columns = [
    { key: 'id', label: '工号', width: 90, mono: true,
      render: (r) => <span className="font-mono text-[12px]" style={{ color: 'var(--text-soft)' }}>{r.id}</span> },
    { key: 'name', label: '姓名', width: 160,
      render: (r) => (
        <div className="inline-flex items-center gap-2">
          <AvatarChip name={r.name} size={28} />
          <div>
            <div className="font-medium" style={{ color: 'var(--text-strong)' }}>{r.name}</div>
            <div className="text-[11px] mt-0.5" style={{ color: 'var(--text-soft)' }}>
              {r.gender} · {r.edu}
            </div>
          </div>
        </div>
      ),
    },
    { key: 'grade', label: '任教年级', width: 100,
      render: (r) => <Tag color={r.gradeColor}>{r.grade}</Tag> },
    { key: 'subjects', label: '任教科目', width: 130,
      render: (r) => (
        <div className="inline-flex gap-1 flex-wrap">
          {r.subjects.map((s) => <Tag key={s} color="slate">{s}</Tag>)}
        </div>
      ),
    },
    { key: 'isHomeroom', label: '班主任', width: 130,
      render: (r) => r.isHomeroom
        ? <span className="inline-flex items-center gap-1.5"><Tag color="indigo" dot>{r.homeroomOf}</Tag></span>
        : <span className="text-[12px]" style={{ color: 'var(--text-soft)' }}>—</span>,
    },
    { key: 'seniority', label: '教龄', width: 70, align: 'center', mono: true,
      render: (r) => <span>{r.seniority} 年</span> },
    { key: 'supCount', label: '本学期监考', width: 110, align: 'center',
      render: (r) => (
        <div className="inline-flex items-center gap-2">
          <span className="num font-semibold" style={{ color: 'var(--text-strong)' }}>{r.supCount}</span>
          <div className="w-12 h-1 rounded-full" style={{ background: '#F1F5F9' }}>
            <div className="h-full rounded-full" style={{ width: `${(r.supCount / 20) * 100}%`, background: 'var(--brand)' }} />
          </div>
        </div>
      ),
    },
    { key: 'phone', label: '联系方式', width: 130, mono: true,
      render: (r) => <span className="text-[12px]" style={{ color: 'var(--text-medium)' }}>{r.phone}</span> },
    { key: 'status', label: '状态', width: 80,
      render: (r) => r.status === 'leave'
        ? <Tag color="amber" dot>请假</Tag>
        : <Tag color="emerald" dot>在职</Tag>,
    },
    { key: 'actions', label: '操作', width: 110, align: 'right',
      render: (r) => (
        <div className="inline-flex items-center gap-1" onClick={(e) => e.stopPropagation()}>
          <button className="text-[12px] font-medium px-1.5 hover:underline" style={{ color: 'var(--brand)' }}
            onClick={() => openTeacherDetail(r)}>查看</button>
          <span className="text-slate-300">·</span>
          <button className="text-[12px] font-medium px-1.5 hover:underline" style={{ color: 'var(--text-medium)' }}
            onClick={() => window.ui.toast(`打开 ${r.name} 编辑面板`, { kind: 'info' })}>编辑</button>
        </div>
      ),
    },
  ];

  const openTeacherDetail = (t) => {
    setSelectedTeacher(t);
    window.ui.drawer({
      title: t.name,
      subtitle: `${t.id} · ${t.grade} · ${t.subjects.join('/')}`,
      width: 480,
      content: (
        <div className="space-y-5">
          <div className="flex items-center gap-4 p-4 rounded-xl"
            style={{ background: 'linear-gradient(135deg, #EEF2FF, #FAF5FF)' }}>
            <div className="w-16 h-16 rounded-full inline-flex items-center justify-center text-[22px] font-semibold text-white"
              style={{ background: 'linear-gradient(135deg, #6366F1, #9333EA)' }}>{t.name[0]}</div>
            <div className="flex-1">
              <div className="text-[16px] font-semibold" style={{ color: 'var(--text-strong)' }}>{t.name}</div>
              <div className="text-[12px] mt-0.5" style={{ color: 'var(--text-medium)' }}>{t.gender} · {t.edu} · {t.seniority} 年教龄</div>
              <div className="mt-1.5 inline-flex items-center gap-1.5">
                <Tag color={t.gradeColor} dot>{t.grade}</Tag>
                {t.isHomeroom && <Tag color="indigo" dot>{t.homeroomOf}</Tag>}
              </div>
            </div>
          </div>
          <div className="grid grid-cols-2 gap-3">
            {[
              { l: '工号',     v: t.id, mono: true },
              { l: '联系电话', v: t.phone, mono: true },
              { l: '邮箱',     v: t.email, mono: true, span: 2 },
              { l: '本学期监考', v: `${t.supCount} 场`, mono: true },
              { l: '状态',     v: t.status === 'leave' ? '请假' : '在职' },
            ].map((it, i) => (
              <div key={i} className="rounded-lg p-3" style={{ background: '#F8FAFC', gridColumn: it.span === 2 ? 'span 2' : undefined }}>
                <div className="text-[10.5px] font-mono uppercase tracking-wider" style={{ color: 'var(--text-soft)' }}>{it.l}</div>
                <div className="mt-1 text-[13px] font-semibold truncate" style={{ color: 'var(--text-strong)', fontFamily: it.mono ? 'Geist Mono' : undefined }}>{it.v}</div>
              </div>
            ))}
          </div>
          <div>
            <div className="text-[12.5px] font-medium mb-2" style={{ color: 'var(--text-medium)' }}>策略受限</div>
            <div className="space-y-2">
              {[
                { c: t.isHomeroom, t: `不安排监考 ${t.homeroomOf || ''}` },
                { c: true,           t: `不安排监考本年级 ${t.grade}` },
                { c: true,           t: `不安排监考本科目 ${t.subjects.join('/')}` },
              ].filter((x) => x.c).map((it, i) => (
                <div key={i} className="flex items-center gap-2 text-[12.5px]" style={{ color: 'var(--text-medium)' }}>
                  <IconCheck size={12} strokeWidth={2.5} style={{ color: '#10B981' }} />
                  {it.t}
                </div>
              ))}
            </div>
          </div>
        </div>
      ),
      footer: (
        <>
          <Button onClick={() => window.ui.closeDrawer()}>关闭</Button>
          <Button icon={IconCalendar} onClick={() => window.ui.toast(`查看 ${t.name} 详细排班`, { kind: 'info' })}>查看排班</Button>
          <Button variant="primary" icon={IconEdit} onClick={() => window.ui.toast(`编辑 ${t.name} 信息`, { kind: 'info' })}>编辑档案</Button>
        </>
      ),
    });
  };

  return (
    <PageShell
      crumbs={['天津市实验中学', '数据与资源', '监考人员管理']}
      title="监考人员管理"
      badge={`${total} 名教职工`}
      subtitle="维护教师档案、查看个人排班、调整年级与任教科目"
      actions={
        <>
          <Button icon={IconUpload} onClick={() => window.ui.modal({
            title: '批量导入教师',
            subtitle: '上传教师名单 Excel 表，系统自动识别字段',
            size: 'md',
            content: (
              <div className="space-y-4">
                <div className="rounded-xl p-5 flex flex-col items-center text-center"
                  style={{ background: '#F8FAFC', border: '1.5px dashed #CBD5E1' }}>
                  <div className="w-12 h-12 rounded-xl flex items-center justify-center mb-3 bg-white" style={{ boxShadow: 'var(--elevation-1)', color: 'var(--brand)' }}>
                    <IconUpload size={20} strokeWidth={2} />
                  </div>
                  <div className="text-[13px] font-medium" style={{ color: 'var(--text-strong)' }}>点击或拖拽 Excel 文件</div>
                  <div className="text-[11px] mt-1" style={{ color: 'var(--text-soft)' }}>支持 .xlsx / .xls，需包含工号 / 姓名 / 年级 / 科目 字段</div>
                </div>
                <div className="rounded-lg p-3 flex items-start gap-2.5" style={{ background: '#EEF2FF' }}>
                  <IconHelp size={14} className="shrink-0 mt-0.5" style={{ color: '#4F46E5' }} />
                  <div className="text-[11.5px]" style={{ color: '#3730A3' }}>
                    首次使用？点击 <a className="font-medium underline">下载标准模板</a>，按表头填写后上传。
                  </div>
                </div>
              </div>
            ),
            footer: (
              <>
                <Button onClick={() => window.ui.closeModal()}>取消</Button>
                <Button variant="primary" icon={IconUpload} onClick={() => { window.ui.closeModal(); window.ui.toast('导入中…', { kind: 'info', desc: '正在解析 142 行数据' }); setTimeout(() => window.ui.toast('导入完成', { kind: 'success', desc: '新增 8 人，更新 134 人' }), 1500); }}>开始导入</Button>
              </>
            ),
          })}>批量导入</Button>
          <Button variant="primary" icon={IconPlus} onClick={() => window.ui.toast('新增教师表单已打开', { kind: 'info' })}>新增教师</Button>
        </>
      }
    >
      <div className="grid grid-cols-4 gap-4">
        <MiniKPI label="在职教职工" value={total - onLeave} unit="人" accent="#4F46E5" icon={IconUsers} trend="1.4" trendUp />
        <MiniKPI label="班主任" value={homerooms} unit="人" accent="#9333EA" icon={IconCheck} sub="覆盖 6 个年级" />
        <MiniKPI label="本学期总监考" value={totalSup} unit="场次" accent="#059669" icon={IconCalendar} sub={`人均 ${(totalSup/total).toFixed(1)} 场`} />
        <MiniKPI label="请假/休假" value={onLeave} unit="人" accent="#D97706" icon={IconClock} sub="需调整排班" />
      </div>

      <FilterBar
        search="搜索姓名、工号、电话..."
        filters={[
          { label: '年级:', value: '全部' },
          { label: '科目:', value: '全部' },
          { label: '班主任:', value: '全部' },
          { label: '状态:', value: '在职' },
        ]}
        total={total}
        onReset={() => {}}
        right={<Button size="sm" variant="ghost" icon={IconMore}>更多筛选</Button>}
      />

      <DataTable
        columns={columns}
        rows={MOCK_TEACHERS}
        selectable
        onRowClick={(row) => setSelectedTeacher(row)}
      />

      <TeacherScheduleTimeline teacher={selectedTeacher} />
    </PageShell>
  );
}

window.PeoplePage = PeoplePage;
