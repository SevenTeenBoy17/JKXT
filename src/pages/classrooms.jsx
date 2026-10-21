// 考场管理 — 真实教室档案与状态；当前接口没有可验证的占用时长统计。

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

function normalizeClassroomRow(row, index) {
  const type = row.type || (row.roomType === 'lab' ? 'lab' : row.roomType === 'meeting' ? 'lecture' : 'normal');
  const floor = row.floor === undefined || row.floor === null || row.floor === ''
    ? '-'
    : String(row.floor).replace(/\s*F$/i, '');
  return {
    id: row.code || row.id || row.uuid || `R-${index + 1}`,
    uuid: row.uuid,
    code: row.code || row.id || `R-${index + 1}`,
    name: row.name || '未命名考场',
    building: row.building || '-',
    floor,
    capacity: Number(row.capacity || 0),
    examCapacity: Number(row.examCapacity || 0),
    type: TYPE_MAP[type] ? type : 'normal',
    roomType: row.roomType || 'standard',
    features: Array.isArray(row.features) ? row.features : [],
    // Legacy API usage is a capacity-derived placeholder, not measured occupancy.
    // Do not render it as a percentage or infer an available-time denominator.
    usage: null,
    status: row.status === 'maintenance' ? 'maintenance' : row.status === 'disabled' ? 'disabled'
      : ['active', 'available'].includes(row.status) ? 'available' : 'unknown',
  };
}

function ClassroomState({ title, sub, action }) {
  return (
    <div className="bg-white rounded-xl py-14 flex flex-col items-center text-center"
      style={{ border: '1px solid var(--border-soft)', boxShadow: 'var(--elevation-1)' }}>
      <div className="w-12 h-12 rounded-full flex items-center justify-center mb-3"
        style={{ background: '#EEF2FF', color: 'var(--brand)' }}>
        <IconBuilding size={20} strokeWidth={2} />
      </div>
      <div className="text-[14px] font-semibold" style={{ color: 'var(--text-strong)' }}>{title}</div>
      {sub && <div className="text-[12px] mt-1 max-w-md" style={{ color: 'var(--text-soft)' }}>{sub}</div>}
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}

const CR_FIELD_STYLE = {
  border: '1px solid var(--border-soft)', borderRadius: 10, padding: '8px 11px',
  fontSize: 13, color: 'var(--text-strong)', background: '#fff', width: '100%', outline: 'none',
};

function CrField({ label, required, span, children }) {
  return (
    <label className="flex flex-col gap-1.5" style={span ? { gridColumn: '1 / -1' } : undefined}>
      <span className="text-[11.5px] font-medium" style={{ color: 'var(--text-medium)' }}>
        {label}{required && <span style={{ color: '#EF4444' }}> *</span>}
      </span>
      {children}
    </label>
  );
}

// 新增 / 编辑 考场 — 真实写入后端 (POST / PUT /api/v1/classrooms)
function ClassroomFormModal({ classroom, onSaved }) {
  const isEdit = Boolean(classroom && classroom.uuid);
  const [form, setForm] = React.useState({
    code: '', name: '', building: '', floor: '', capacity: '', examCapacity: '',
    roomType: 'standard', status: 'available', features: '',
  });
  const [loading, setLoading] = React.useState(isEdit);
  const [saving, setSaving] = React.useState(false);
  const [err, setErr] = React.useState('');

  React.useEffect(() => {
    if (!isEdit) return undefined;
    let alive = true;
    window.api.get('/classrooms/' + classroom.uuid)
      .then((d) => {
        if (!alive) return;
        setForm({
          code: d.code || '', name: d.name || '', building: d.building || '',
          floor: d.floor == null ? '' : String(d.floor),
          capacity: d.capacity == null ? '' : String(d.capacity),
          examCapacity: d.examCapacity == null ? '' : String(d.examCapacity),
          roomType: d.roomType || 'standard', status: d.status || 'available',
          features: Array.isArray(d.features) ? d.features.join(', ') : '',
        });
      })
      .catch((e) => { if (alive) setErr(e.message || '读取考场档案失败'); })
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, [isEdit, classroom]);

  const bind = (key) => (e) => {
    const value = e && e.target ? e.target.value : e;
    setForm((prev) => ({ ...prev, [key]: value }));
  };
  const toInt = (v) => { const n = parseInt(String(v).trim(), 10); return Number.isFinite(n) ? n : 0; };

  const submit = async () => {
    if (saving) return;
    if (!form.code.trim() || !form.name.trim()) { setErr('编号和名称为必填项'); return; }
    setSaving(true); setErr('');
    const body = {
      code: form.code.trim(), name: form.name.trim(),
      building: form.building.trim() || null, floor: form.floor.trim() || null,
      capacity: toInt(form.capacity), examCapacity: toInt(form.examCapacity),
      roomType: form.roomType, status: form.status,
      features: form.features.split(/[,，;；]/).map((s) => s.trim()).filter(Boolean),
    };
    try {
      if (isEdit) await window.api.put('/classrooms/' + classroom.uuid, body);
      else await window.api.post('/classrooms', body);
      window.ui.closeModal();
      window.ui.toast(isEdit ? '考场已更新' : '考场已新增', { kind: 'success', desc: body.name + '（' + body.code + '）' });
      if (onSaved) onSaved();
    } catch (e) {
      setErr(e.message || '保存失败');
      setSaving(false);
    }
  };

  if (loading) {
    return <div className="py-10 text-center text-[13px]" style={{ color: 'var(--text-soft)' }}>正在读取考场档案…</div>;
  }

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <CrField label="编号" required><input style={CR_FIELD_STYLE} value={form.code} onChange={bind('code')} placeholder="如 A-301" /></CrField>
        <CrField label="名称" required><input style={CR_FIELD_STYLE} value={form.name} onChange={bind('name')} placeholder="如 高三(1)班教室" /></CrField>
        <CrField label="楼栋"><input style={CR_FIELD_STYLE} value={form.building} onChange={bind('building')} placeholder="如 A 栋(教学楼一)" /></CrField>
        <CrField label="楼层"><input style={CR_FIELD_STYLE} value={form.floor} onChange={bind('floor')} placeholder="如 3" /></CrField>
        <CrField label="座位数"><input type="number" min="0" style={CR_FIELD_STYLE} value={form.capacity} onChange={bind('capacity')} placeholder="如 48" /></CrField>
        <CrField label="考试容量"><input type="number" min="0" style={CR_FIELD_STYLE} value={form.examCapacity} onChange={bind('examCapacity')} placeholder="如 30" /></CrField>
        <CrField label="类型">
          <select style={CR_FIELD_STYLE} value={form.roomType} onChange={bind('roomType')}>
            <option value="standard">标准考场</option>
            <option value="lab">实验室</option>
            <option value="meeting">阶梯 / 会议室</option>
            <option value="corridor">楼道</option>
            <option value="other">其他</option>
          </select>
        </CrField>
        <CrField label="状态">
          <select style={CR_FIELD_STYLE} value={form.status} onChange={bind('status')}>
            <option value="available">可用</option>
            <option value="maintenance">维护中</option>
            <option value="disabled">已停用</option>
          </select>
        </CrField>
        <CrField label="配套设施（逗号分隔）" span>
          <input style={CR_FIELD_STYLE} value={form.features} onChange={bind('features')} placeholder="多媒体, 空调, 监控" />
        </CrField>
      </div>
      {err && <div role="alert" className="rounded-lg px-3 py-2 text-[12px]" style={{ background: '#FEF2F2', color: '#B91C1C', border: '1px solid #FECACA' }}>{err}</div>}
      <div className="flex items-center justify-end gap-2 pt-1">
        <Button onClick={() => window.ui.closeModal()}>取消</Button>
        <Button variant="primary" icon={IconCheck} disabled={saving} onClick={submit}>{saving ? '保存中…' : isEdit ? '保存修改' : '确认新增'}</Button>
      </div>
    </div>
  );
}

// 批量导入考场 — 真实落库 (POST /api/v1/import/classrooms/commit)
function ClassroomImportModal({ onDone }) {
  const [file, setFile] = React.useState(null);
  const [busy, setBusy] = React.useState(false);
  const [err, setErr] = React.useState('');
  const inputRef = React.useRef(null);

  const submit = async () => {
    if (busy) return;
    if (!file) { setErr('请先选择 Excel 文件'); return; }
    setBusy(true); setErr('');
    try {
      const res = await window.api.upload('/import/classrooms/commit', file, {});
      window.ui.closeModal();
      window.ui.toast('导入完成', {
        kind: 'success',
        desc: '新增 ' + (res.inserted || 0) + ' · 更新 ' + (res.updated || 0) + ' · 跳过 ' + (res.skipped || 0),
      });
      if (onDone) onDone();
    } catch (e) {
      setErr(e.message || '导入失败：请检查表头与必填字段');
      setBusy(false);
    }
  };

  return (
    <div className="space-y-4">
      <input ref={inputRef} type="file" accept=".xlsx,.xls" className="hidden"
        onChange={(e) => { setErr(''); setFile(e.target.files && e.target.files[0] ? e.target.files[0] : null); }} />
      <div className="rounded-xl p-5 flex flex-col items-center text-center cursor-pointer"
        style={{ background: '#F8FAFC', border: '1.5px dashed #CBD5E1' }}
        onClick={() => inputRef.current && inputRef.current.click()}>
        <div className="w-12 h-12 rounded-xl flex items-center justify-center mb-3 bg-white" style={{ boxShadow: 'var(--elevation-1)', color: 'var(--brand)' }}>
          <IconUpload size={20} strokeWidth={2} />
        </div>
        <div className="text-[13px] font-medium" style={{ color: 'var(--text-strong)' }}>{file ? file.name : '点击选择 Excel 文件'}</div>
        <div className="text-[11px] mt-1" style={{ color: 'var(--text-soft)' }}>
          {file ? (Math.max(1, Math.round(file.size / 1024)) + ' KB · 点击可重新选择') : '支持 .xlsx / .xls，需包含 编号 / 名称 / 容量 等表头'}
        </div>
      </div>
      <div className="rounded-lg p-3 flex items-start gap-2.5" style={{ background: '#EEF2FF' }}>
        <IconHelp size={14} className="shrink-0 mt-0.5" style={{ color: '#4F46E5' }} />
        <div className="text-[11.5px]" style={{ color: '#3730A3' }}>导入将真实写入本校考场名册（按编号去重 upsert）；校验未通过的文件会被拒绝。</div>
      </div>
      {err && <div className="rounded-lg px-3 py-2 text-[12px]" style={{ background: '#FEF2F2', color: '#B91C1C', border: '1px solid #FECACA' }}>{err}</div>}
      <div className="flex items-center justify-end gap-2">
        <Button onClick={() => window.ui.closeModal()}>取消</Button>
        <Button variant="primary" icon={IconUpload} disabled={busy} onClick={submit}>{busy ? '导入中…' : '开始导入'}</Button>
      </div>
    </div>
  );
}

function ClassroomsPage() {
  const [classrooms, setClassrooms] = React.useState([]);
  const [loadingClassrooms, setLoadingClassrooms] = React.useState(true);
  const [classroomError, setClassroomError] = React.useState('');

  const loadClassrooms = React.useCallback(() => {
    let alive = true;
    setLoadingClassrooms(true);
    setClassroomError('');
    window.api.get('/classrooms')
      .then((rows) => {
        if (!alive) return;
        setClassrooms(Array.isArray(rows) ? rows.map(normalizeClassroomRow) : []);
      })
      .catch((error) => {
        if (!alive) return;
        setClassrooms([]);
        setClassroomError(error.message || '考场数据加载失败');
      })
      .finally(() => {
        if (alive) setLoadingClassrooms(false);
      });
    return () => { alive = false; };
  }, []);

  React.useEffect(() => loadClassrooms(), [loadClassrooms]);

  const [search, setSearch] = React.useState('');
  const DEFAULT_FILTERS = { building: '全部', type: '全部', status: '全部' };
  const [filters, setFilters] = React.useState(DEFAULT_FILTERS);
  const setF = (key) => (v) => setFilters((prev) => ({ ...prev, [key]: v }));
  const resetAll = () => { setSearch(''); setFilters(DEFAULT_FILTERS); };

  const buildingOpts = Array.from(new Set(classrooms.map((c) => c.building).filter((b) => b && b !== '-')));
  const typeOpts = Array.from(new Set(classrooms.map((c) => (TYPE_MAP[c.type] || TYPE_MAP.normal).label)));
  const q = search.trim().toLowerCase();
  const filtered = classrooms.filter((c) => {
    if (q) { const hay = `${c.code} ${c.name} ${c.building}`.toLowerCase(); if (!hay.includes(q)) return false; }
    if (filters.building !== '全部' && c.building !== filters.building) return false;
    if (filters.type !== '全部' && (TYPE_MAP[c.type] || TYPE_MAP.normal).label !== filters.type) return false;
    if (filters.status !== '全部') { const label = ({ maintenance: '维护中', available: '可用', disabled: '已停用', unknown: '未知' })[c.status]; if (label !== filters.status) return false; }
    return true;
  });
  const activeFilterCount = (q ? 1 : 0) + Object.keys(DEFAULT_FILTERS).filter((k) => filters[k] !== DEFAULT_FILTERS[k]).length;

  const openClassroomForm = (classroom) => {
    window.ui.modal({
      title: classroom ? '编辑考场' : '新增考场',
      subtitle: classroom ? `${classroom.name} · ${classroom.code}` : '录入考场编号 / 名称 / 容量等信息，保存后写入本校名册',
      size: 'md',
      content: <ClassroomFormModal classroom={classroom} onSaved={loadClassrooms} />,
    });
  };

  const removeClassroom = async (classroom) => {
    const ok = await window.ui.confirm({
      title: '删除该考场?',
      desc: `将永久删除「${classroom.name}（${classroom.code}）」。若有考试已绑定该考场，请先调整考试安排。此操作不可撤销。`,
      confirmText: '确认删除', cancelText: '取消', danger: true,
    });
    if (!ok) return;
    try {
      await window.api.del('/classrooms/' + classroom.uuid);
      window.ui.toast('考场已删除', { kind: 'success', desc: `${classroom.name}（${classroom.code}）` });
      loadClassrooms();
    } catch (e) {
      window.ui.toast('删除失败', { kind: 'error', desc: e.message || '请稍后重试' });
    }
  };

  const total = classrooms.length;
  const byType = classrooms.reduce((acc, c) => {
    acc[c.type] = (acc[c.type] || 0) + 1;
    return acc;
  }, {});
  const available = classrooms.filter(c => c.status === 'available').length;
  const maintenance = classrooms.filter(c => c.status === 'maintenance').length;

  const columns = [
    { key: 'code', label: '编号', width: 90, mono: true,
      render: (r) => <span className="font-mono font-semibold" style={{ color: 'var(--text-strong)' }}>{r.code}</span> },
    { key: 'name', label: '名称', width: 200,
      render: (r) => (
        <div className="inline-flex items-center gap-2.5">
          {(() => {
            const t = TYPE_MAP[r.type] || TYPE_MAP.normal;
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
      render: (r) => {
        const type = TYPE_MAP[r.type] || TYPE_MAP.normal;
        return <Tag color={type.color}>{type.label}</Tag>;
      },
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
    { key: 'usage', label: '占用率', width: 150,
      render: () => (
        <span className="text-[12px]" style={{ color: 'var(--text-soft)' }}
          title="尚未接入已占用时长与可用时长统计，不以考场容量估算占用率。">未接入统计</span>
      ),
    },
    { key: 'status', label: '状态', width: 100,
      render: (r) => r.status === 'available' ? <Tag color="emerald" dot>可用</Tag>
        : r.status === 'maintenance' ? <Tag color="amber" dot>维护中</Tag>
        : <Tag color="slate" dot>{r.status === 'disabled' ? '已停用' : '未知'}</Tag>,
    },
    { key: 'actions', label: '操作', width: 130, align: 'right',
      render: (r) => (
        <div className="inline-flex items-center gap-1" onClick={(e) => e.stopPropagation()}>
          <button className="text-[12px] font-medium px-1.5 hover:underline" style={{ color: 'var(--text-medium)' }}
            onClick={() => openClassroomForm(r)}>编辑</button>
          <span className="text-slate-300">·</span>
          <button className="text-[12px] font-medium px-1.5 hover:underline" style={{ color: '#DC2626' }}
            onClick={() => removeClassroom(r)}>删除</button>
        </div>
      ),
    },
  ];

  return (
    <PageShell
      crumbs={[orgName(), '数据与资源', '考场管理']}
      title="考场管理"
      badge={`${total} 间教室`}
      subtitle="维护考场容量、设施与可用状态。占用率统计暂未接入，不以容量估算使用情况。"
      actions={
        <>
          <Button icon={IconUpload} onClick={() => window.ui.modal({
            title: '批量导入考场',
            subtitle: '上传考场名单 Excel，按编号 upsert 写入本校名册',
            size: 'md',
            content: <ClassroomImportModal onDone={loadClassrooms} />,
          })}>批量导入</Button>
          <Button variant="primary" icon={IconPlus} onClick={() => openClassroomForm(null)}>新增教室</Button>
        </>
      }
    >
      <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-5 gap-4">
        <MiniKPI label="教室总数" value={total} unit="间" accent="#4F46E5" icon={IconBuilding} />
        <MiniKPI label="标准考场" value={byType.normal || 0} unit="间" accent="#6366F1" icon={IconBuilding} sub={`占 ${total ? Math.round((byType.normal || 0)/total*100) : 0}%`} />
        <MiniKPI label="实验/机房" value={(byType.lab || 0) + (byType.computer || 0)} unit="间" accent="#F59E0B" icon={IconArchive} />
        <MiniKPI label="可用考场" value={available} unit="间" accent="#10B981" icon={IconChart} sub="按档案状态统计，非实时空闲" />
        <MiniKPI label="维护中" value={maintenance} unit="间" accent="#EF4444" icon={IconAlert} sub={maintenance > 0 ? '需关注' : '正常'} />
      </div>

      <FilterBar
        search="搜索编号、名称、楼栋..."
        searchValue={search}
        onSearch={setSearch}
        filters={[
          { label: '楼栋:', value: filters.building, options: ['全部', ...buildingOpts], onChange: setF('building') },
          { label: '类型:', value: filters.type, options: ['全部', ...typeOpts], onChange: setF('type') },
          { label: '状态:', value: filters.status, options: ['全部', '可用', '维护中', '已停用', '未知'], onChange: setF('status') },
        ]}
        total={filtered.length}
        onReset={activeFilterCount > 0 ? resetAll : undefined}
      />

      {loadingClassrooms ? (
        <ClassroomState title="正在加载考场数据" sub="正在从后端读取当前租户的考场容量、类型、设施和状态。" />
      ) : classroomError ? (
        <EmptyState
          title="考场数据加载失败"
          sub={classroomError}
          action={<Button variant="primary" onClick={loadClassrooms}>重新加载</Button>}
        />
      ) : filtered.length > 0 ? (
        <DataTable columns={columns} rows={filtered} selectable />
      ) : classrooms.length > 0 ? (
        <EmptyState
          title="没有符合条件的考场"
          sub={`当前筛选条件下没有匹配结果${q ? `，关键词「${search.trim()}」` : ''}。可调整楼栋、类型或状态，或清空筛选。`}
          action={<Button variant="primary" onClick={resetAll}>清空筛选</Button>}
        />
      ) : (
        <EmptyState
          title="暂无考场数据"
          sub="当前租户还没有维护考场，可通过导入模板或新增考场补齐。"
          action={<Button variant="primary" onClick={() => openClassroomForm(null)}>新增考场</Button>}
        />
      )}
    </PageShell>
  );
}

window.ClassroomsPage = ClassroomsPage;
