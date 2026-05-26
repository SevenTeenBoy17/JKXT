// 考场管理 — 教室列表 + 类型筛选 + 占用率

const MOCK_CLASSROOMS = [
  { id: 'A0301', code: 'A-301', name: '高三(1)班教室', building: 'A 栋(教学楼一)', floor: 3, capacity: 48, examCapacity: 30, type: 'normal', features: ['多媒体', '空调'], usage: 86, status: 'available' },
  { id: 'A0302', code: 'A-302', name: '高三(2)班教室', building: 'A 栋(教学楼一)', floor: 3, capacity: 48, examCapacity: 30, type: 'normal', features: ['多媒体', '空调'], usage: 84, status: 'available' },
  { id: 'A0303', code: 'A-303', name: '高三(3)班教室', building: 'A 栋(教学楼一)', floor: 3, capacity: 50, examCapacity: 32, type: 'normal', features: ['多媒体', '空调'], usage: 90, status: 'available' },
  { id: 'A0401', code: 'A-401', name: '高二(1)班教室', building: 'A 栋(教学楼一)', floor: 4, capacity: 48, examCapacity: 30, type: 'normal', features: ['多媒体'], usage: 72, status: 'available' },
  { id: 'A0402', code: 'A-402', name: '高二(2)班教室', building: 'A 栋(教学楼一)', floor: 4, capacity: 48, examCapacity: 30, type: 'normal', features: ['多媒体'], usage: 70, status: 'maintenance' },
  { id: 'B0201', code: 'B-201', name: '初三(1)班教室', building: 'B 栋(教学楼二)', floor: 2, capacity: 50, examCapacity: 32, type: 'normal', features: ['多媒体'], usage: 65, status: 'available' },
  { id: 'B0202', code: 'B-202', name: '初三(2)班教室', building: 'B 栋(教学楼二)', floor: 2, capacity: 50, examCapacity: 32, type: 'normal', features: ['多媒体'], usage: 60, status: 'available' },
  { id: 'C0101', code: 'C-101', name: '物理实验室一', building: 'C 栋(实验楼)', floor: 1, capacity: 40, examCapacity: 20, type: 'lab', features: ['实验台', '通风'], usage: 32, status: 'available' },
  { id: 'C0102', code: 'C-102', name: '化学实验室一', building: 'C 栋(实验楼)', floor: 1, capacity: 40, examCapacity: 20, type: 'lab', features: ['实验台', '排风'], usage: 28, status: 'available' },
  { id: 'D0201', code: 'D-201', name: '机房 A', building: 'D 栋(信息楼)', floor: 2, capacity: 60, examCapacity: 60, type: 'computer', features: ['电脑×60', '联网'], usage: 45, status: 'available' },
  { id: 'D0202', code: 'D-202', name: '机房 B', building: 'D 栋(信息楼)', floor: 2, capacity: 60, examCapacity: 60, type: 'computer', features: ['电脑×60', '联网'], usage: 48, status: 'available' },
  { id: 'E0101', code: 'E-101', name: '阶梯教室·大', building: 'E 栋(综合楼)', floor: 1, capacity: 200, examCapacity: 120, type: 'lecture', features: ['投影', '音响'], usage: 22, status: 'available' },
];

const TYPE_MAP = {
  normal:   { label: '标准考场', color: 'indigo',  icon: IconBuilding },
  lab:      { label: '实验室',   color: 'amber',   icon: IconArchive },
  computer: { label: '机房',     color: 'cyan',    icon: IconBolt },
  lecture:  { label: '阶梯教室', color: 'violet',  icon: IconUsers },
  language: { label: '语音室',   color: 'pink',    icon: IconHelp },
};

function ClassroomsPage() {
  const total = MOCK_CLASSROOMS.length;
  const byType = MOCK_CLASSROOMS.reduce((acc, c) => {
    acc[c.type] = (acc[c.type] || 0) + 1;
    return acc;
  }, {});
  const avgUsage = Math.round(MOCK_CLASSROOMS.reduce((a, b) => a + b.usage, 0) / total);
  const maintenance = MOCK_CLASSROOMS.filter(c => c.status === 'maintenance').length;

  const columns = [
    { key: 'code', label: '编号', width: 90, mono: true,
      render: (r) => <span className="font-mono font-semibold" style={{ color: 'var(--text-strong)' }}>{r.code}</span> },
    { key: 'name', label: '名称', width: 200,
      render: (r) => (
        <div className="inline-flex items-center gap-2.5">
          {(() => {
            const t = TYPE_MAP[r.type];
            const Icn = t.icon;
            return (
              <div className="w-8 h-8 rounded-lg flex items-center justify-center"
                style={{ background: `var(--bg-elevated)`, border: '1px solid var(--border-soft)' }}>
                <Icn size={14} className="text-slate-500" />
              </div>
            );
          })()}
          <div>
            <div className="font-medium" style={{ color: 'var(--text-strong)' }}>{r.name}</div>
            <div className="text-[11px] mt-0.5" style={{ color: 'var(--text-soft)' }}>{r.building} · {r.floor} F</div>
          </div>
        </div>
      ),
    },
    { key: 'type', label: '类型', width: 110,
      render: (r) => <Tag color={TYPE_MAP[r.type].color}>{TYPE_MAP[r.type].label}</Tag>,
    },
    { key: 'capacity', label: '座位数', width: 90, align: 'center', mono: true,
      render: (r) => <span>{r.capacity}</span> },
    { key: 'examCapacity', label: '考试容量', width: 100, align: 'center',
      render: (r) => (
        <span className="inline-flex items-baseline gap-1">
          <span className="num font-semibold" style={{ color: 'var(--text-strong)' }}>{r.examCapacity}</span>
          <span className="text-[10.5px]" style={{ color: 'var(--text-soft)' }}>/{r.capacity}</span>
        </span>
      ),
    },
    { key: 'features', label: '配套设施', width: 180,
      render: (r) => (
        <div className="inline-flex gap-1 flex-wrap">
          {r.features.map((f) => <Tag key={f} color="slate">{f}</Tag>)}
        </div>
      ),
    },
    { key: 'usage', label: '本学期占用率', width: 150,
      render: (r) => (
        <div className="flex items-center gap-2 min-w-[130px]">
          <div className="flex-1 h-1.5 rounded-full overflow-hidden" style={{ background: '#F1F5F9' }}>
            <div className="h-full rounded-full" style={{
              width: `${r.usage}%`,
              background: r.usage > 80 ? '#EF4444' : r.usage > 50 ? '#F59E0B' : '#10B981',
            }} />
          </div>
          <span className="num text-[11.5px] font-semibold w-9 text-right" style={{ color: 'var(--text-medium)' }}>
            {r.usage}%
          </span>
        </div>
      ),
    },
    { key: 'status', label: '状态', width: 100,
      render: (r) => r.status === 'maintenance'
        ? <Tag color="amber" dot>维护中</Tag>
        : <Tag color="emerald" dot>可用</Tag>,
    },
    { key: 'actions', label: '操作', width: 130, align: 'right',
      render: (r) => (
        <div className="inline-flex items-center gap-1" onClick={(e) => e.stopPropagation()}>
          <button className="text-[12px] font-medium px-1.5 hover:underline" style={{ color: 'var(--brand)' }}
            onClick={() => window.ui.toast(`查看 ${r.code} 本周排程`, { kind: 'info', desc: '本周 4 场考试 · 下周 3 场' })}>查看排程</button>
          <span className="text-slate-300">·</span>
          <button className="text-[12px] font-medium px-1.5 hover:underline" style={{ color: 'var(--text-medium)' }}
            onClick={() => window.ui.toast(`编辑 ${r.name}`, { kind: 'info' })}>编辑</button>
        </div>
      ),
    },
  ];

  return (
    <PageShell
      crumbs={['天津市实验中学', '数据与资源', '考场管理']}
      title="考场管理"
      badge={`${total} 间教室`}
      subtitle="维护教学楼、实验室、机房等考场资源,实时监控占用率"
      actions={
        <>
          <Button icon={IconUpload} onClick={() => window.ui.toast('批量导入教室表', { kind: 'info', desc: '打开上传面板' })}>批量导入</Button>
          <Button variant="primary" icon={IconPlus} onClick={() => window.ui.toast('新增教室', { kind: 'info', desc: '请填写编号 / 名称 / 楼栋 / 容量 等信息' })}>新增教室</Button>
        </>
      }
    >
      <div className="grid grid-cols-5 gap-4">
        <MiniKPI label="教室总数" value={total} unit="间" accent="#4F46E5" icon={IconBuilding} />
        <MiniKPI label="标准考场" value={byType.normal || 0} unit="间" accent="#6366F1" icon={IconBuilding} sub={`占 ${Math.round((byType.normal || 0)/total*100)}%`} />
        <MiniKPI label="实验/机房" value={(byType.lab || 0) + (byType.computer || 0)} unit="间" accent="#F59E0B" icon={IconArchive} />
        <MiniKPI label="平均占用率" value={avgUsage} unit="%" accent="#10B981" icon={IconChart} trend="2.1" trendUp />
        <MiniKPI label="维护中" value={maintenance} unit="间" accent="#EF4444" icon={IconAlert} sub={maintenance > 0 ? '需关注' : '正常'} />
      </div>

      <FilterBar
        search="搜索编号、名称、楼栋..."
        filters={[
          { label: '楼栋:', value: '全部' },
          { label: '类型:', value: '全部' },
          { label: '容量:', value: '不限' },
          { label: '状态:', value: '可用' },
        ]}
        total={total}
        onReset={() => {}}
      />

      <DataTable columns={columns} rows={MOCK_CLASSROOMS} selectable />
    </PageShell>
  );
}

window.ClassroomsPage = ClassroomsPage;
