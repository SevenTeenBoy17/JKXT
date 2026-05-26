// 班级科目管理 — 班级 / 科目 子 Tab 切换

const MOCK_CLASSES = [
  { id: 'C7-01', grade: '七年级', code: '七(1)班', students: 45, homeroom: '徐安琪', subjects: 7, gradeColor: 'violet' },
  { id: 'C7-02', grade: '七年级', code: '七(2)班', students: 46, homeroom: '徐安琪', subjects: 7, gradeColor: 'violet' },
  { id: 'C8-01', grade: '初二', code: '初二(1)班', students: 48, homeroom: '周晓梅', subjects: 9, gradeColor: 'emerald' },
  { id: 'C8-03', grade: '初二', code: '初二(3)班', students: 48, homeroom: '周晓梅', subjects: 9, gradeColor: 'emerald' },
  { id: 'C9-04', grade: '初三', code: '初三(4)班', students: 50, homeroom: '赵思琪', subjects: 9, gradeColor: 'indigo' },
  { id: 'C10-02', grade: '高一', code: '高一(2)班', students: 50, homeroom: '刘文涛', subjects: 9, gradeColor: 'cyan' },
  { id: 'C11-05', grade: '高二', code: '高二(5)班', students: 48, homeroom: '陈志远', subjects: 8, gradeColor: 'amber' },
  { id: 'C12-02', grade: '高三', code: '高三(2)班', students: 46, homeroom: '李慧君', subjects: 7, gradeColor: 'rose' },
  { id: 'C12-07', grade: '高三', code: '高三(7)班', students: 47, homeroom: '王伟明', subjects: 7, gradeColor: 'rose' },
];

const MOCK_SUBJECTS = [
  { id: 'S001', name: '语文',         category: '主科', grades: ['全年级'], hours: 5, examType: '笔试', color: 'rose' },
  { id: 'S002', name: '数学',         category: '主科', grades: ['全年级'], hours: 5, examType: '笔试', color: 'indigo' },
  { id: 'S003', name: '英语',         category: '主科', grades: ['全年级'], hours: 5, examType: '笔试 + 听力', color: 'sky' },
  { id: 'S004', name: '物理',         category: '理科', grades: ['初二', '初三', '高一', '高二', '高三'], hours: 3, examType: '笔试', color: 'cyan' },
  { id: 'S005', name: '化学',         category: '理科', grades: ['初三', '高一', '高二', '高三'], hours: 3, examType: '笔试', color: 'emerald' },
  { id: 'S006', name: '生物',         category: '理科', grades: ['初二', '初三', '高一', '高二'], hours: 2, examType: '笔试', color: 'amber' },
  { id: 'S007', name: '历史',         category: '文科', grades: ['初一-高三'], hours: 2, examType: '笔试', color: 'violet' },
  { id: 'S008', name: '地理',         category: '文科', grades: ['初一-高三'], hours: 2, examType: '笔试', color: 'pink' },
  { id: 'S009', name: '道德与法治',   category: '文科', grades: ['初一-初三'], hours: 2, examType: '笔试 (开卷)', color: 'slate' },
  { id: 'S010', name: '思想政治',     category: '文科', grades: ['高一-高三'], hours: 2, examType: '笔试', color: 'slate' },
  { id: 'S011', name: '信息技术',     category: '副科', grades: ['全年级'], hours: 1, examType: '机考', color: 'indigo' },
  { id: 'S012', name: '体育与健康',   category: '副科', grades: ['全年级'], hours: 2, examType: '体测', color: 'emerald' },
];

function ClassesSubjectsPage() {
  const [tab, setTab] = React.useState('classes');

  const classColumns = [
    { key: 'code', label: '班级名称', width: 160,
      render: (r) => (
        <div className="inline-flex items-center gap-2.5">
          <Tag color={r.gradeColor} soft={false}>{r.grade.slice(0, 2)}</Tag>
          <span className="font-medium" style={{ color: 'var(--text-strong)' }}>{r.code}</span>
        </div>
      ),
    },
    { key: 'grade', label: '所属年级', width: 110,
      render: (r) => <Tag color={r.gradeColor}>{r.grade}</Tag> },
    { key: 'homeroom', label: '班主任', width: 140,
      render: (r) => (
        <div className="inline-flex items-center gap-1.5">
          <AvatarChip name={r.homeroom} size={24} />
          <span className="text-[12.5px]" style={{ color: 'var(--text-medium)' }}>{r.homeroom}</span>
        </div>
      ),
    },
    { key: 'students', label: '学生人数', width: 100, align: 'center',
      render: (r) => <span className="num font-semibold" style={{ color: 'var(--text-strong)' }}>{r.students}</span> },
    { key: 'subjects', label: '开设科目', width: 100, align: 'center', mono: true,
      render: (r) => <span>{r.subjects} 门</span> },
    { key: 'examRoom', label: '默认考场', width: 140,
      render: (r) => <span className="font-mono text-[12px]" style={{ color: 'var(--text-medium)' }}>{r.code.replace(/[\u4e00-\u9fa5]/g, '')}班教室</span> },
    { key: 'actions', label: '操作', width: 140, align: 'right',
      render: (r) => (
        <div className="inline-flex items-center gap-1" onClick={(e) => e.stopPropagation()}>
          <button className="text-[12px] font-medium px-1.5 hover:underline" style={{ color: 'var(--brand)' }}
            onClick={() => window.ui.toast(`${r.code} · 学生名单`, { kind: 'info', desc: `${r.students} 名学生，点击导出` })}>查看学生</button>
          <span className="text-slate-300">·</span>
          <button className="text-[12px] font-medium px-1.5 hover:underline" style={{ color: 'var(--text-medium)' }}
            onClick={() => window.ui.toast(`编辑 ${r.code}`, { kind: 'info' })}>编辑</button>
        </div>
      ),
    },
  ];

  const subjectColumns = [
    { key: 'id', label: '编号', width: 80, mono: true,
      render: (r) => <span className="font-mono text-[12px]" style={{ color: 'var(--text-soft)' }}>{r.id}</span> },
    { key: 'name', label: '科目名称', width: 150,
      render: (r) => (
        <div className="inline-flex items-center gap-2">
          <span className="w-1 h-5 rounded-full" style={{ background: { rose: '#EF4444', indigo: '#6366F1', sky: '#0EA5E9', cyan: '#06B6D4', emerald: '#10B981', amber: '#F59E0B', violet: '#9333EA', pink: '#EC4899', slate: '#94A3B8' }[r.color] }} />
          <span className="font-medium" style={{ color: 'var(--text-strong)' }}>{r.name}</span>
        </div>
      ),
    },
    { key: 'category', label: '类别', width: 90,
      render: (r) => <Tag color={r.category === '主科' ? 'rose' : r.category === '理科' ? 'cyan' : r.category === '文科' ? 'violet' : 'slate'}>{r.category}</Tag>,
    },
    { key: 'grades', label: '适用年级', width: 220,
      render: (r) => (
        <div className="inline-flex gap-1 flex-wrap">
          {r.grades.map((g) => <Tag key={g} color="slate">{g}</Tag>)}
        </div>
      ),
    },
    { key: 'hours', label: '周课时', width: 90, align: 'center', mono: true,
      render: (r) => <span>{r.hours} 节</span> },
    { key: 'examType', label: '考试形式', width: 160,
      render: (r) => <span className="text-[12.5px]" style={{ color: 'var(--text-medium)' }}>{r.examType}</span> },
    { key: 'examCount', label: '本学期考试', width: 120, align: 'center', mono: true,
      render: () => <span>{Math.floor(Math.random() * 8) + 2} 场</span> },
    { key: 'actions', label: '操作', width: 100, align: 'right',
      render: (r) => (
        <div className="inline-flex items-center gap-1" onClick={(e) => e.stopPropagation()}>
          <button className="text-[12px] font-medium px-1.5 hover:underline" style={{ color: 'var(--brand)' }}
            onClick={() => window.ui.toast(`${r.name} 本学期考试`, { kind: 'info' })}>查看</button>
          <span className="text-slate-300">·</span>
          <button className="text-[12px] font-medium px-1.5 hover:underline" style={{ color: 'var(--text-medium)' }}
            onClick={() => window.ui.toast(`编辑 ${r.name}`, { kind: 'info' })}>编辑</button>
        </div>
      ),
    },
  ];

  const tabs = [
    { id: 'classes',  label: '班级管理',  icon: IconBook, count: MOCK_CLASSES.length },
    { id: 'subjects', label: '科目管理',  icon: IconArchive, count: MOCK_SUBJECTS.length },
  ];

  return (
    <PageShell
      crumbs={['天津市实验中学', '数据与资源', '班级科目管理', tab === 'classes' ? '班级管理' : '科目管理']}
      title="班级科目管理"
      subtitle="维护班级编制与学科开设;数据将自动用于排监考与统计"
      actions={
        <>
          <Button icon={IconUpload} onClick={() => window.ui.toast(`批量导入${tab === 'classes' ? '班级' : '科目'}`, { kind: 'info' })}>批量导入</Button>
          <Button variant="primary" icon={IconPlus} onClick={() => window.ui.toast(`新增${tab === 'classes' ? '班级' : '科目'}`, { kind: 'info', desc: '表单已准备' })}>{tab === 'classes' ? '新增班级' : '新增科目'}</Button>
        </>
      }
    >
      {/* Sub-tabs */}
      <div className="bg-white rounded-xl p-1 inline-flex items-center gap-1"
        style={{ border: '1px solid var(--border-soft)', boxShadow: 'var(--elevation-1)' }}>
        {tabs.map((t) => {
          const active = tab === t.id;
          return (
            <button
              key={t.id}
              onClick={() => setTab(t.id)}
              className="h-9 px-4 rounded-lg inline-flex items-center gap-2 transition-all"
              style={{
                background: active ? 'var(--brand-soft)' : 'transparent',
                color: active ? 'var(--brand)' : 'var(--text-medium)',
                fontWeight: active ? 600 : 500,
                fontSize: 13,
              }}
            >
              <t.icon size={15} strokeWidth={active ? 2.25 : 1.75} />
              {t.label}
              <span
                className="font-mono text-[11px] px-1.5 py-0.5 rounded"
                style={{
                  background: active ? '#fff' : '#F1F5F9',
                  color: active ? 'var(--brand)' : 'var(--text-soft)',
                  fontWeight: 600,
                }}
              >
                {t.count}
              </span>
            </button>
          );
        })}
      </div>

      <FilterBar
        search={tab === 'classes' ? '搜索班级、班主任...' : '搜索科目名称...'}
        filters={tab === 'classes' ? [
          { label: '年级:', value: '全部' },
          { label: '人数:', value: '不限' },
        ] : [
          { label: '类别:', value: '全部' },
          { label: '考试形式:', value: '全部' },
        ]}
        total={tab === 'classes' ? MOCK_CLASSES.length : MOCK_SUBJECTS.length}
        onReset={() => {}}
      />

      <DataTable
        columns={tab === 'classes' ? classColumns : subjectColumns}
        rows={tab === 'classes' ? MOCK_CLASSES : MOCK_SUBJECTS}
        selectable
      />
    </PageShell>
  );
}

window.ClassesSubjectsPage = ClassesSubjectsPage;
