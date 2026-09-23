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

function classSubjectWriteId(row) {
  const direct = row && (row.uuid || row.recordId || row.resourceId);
  if (direct) return String(direct);
  const legacyId = String((row && row.id) || '');
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(legacyId)
    ? legacyId
    : '';
}

function normalizeClassRow(row, index) {
  const writeId = classSubjectWriteId(row);
  return {
    id: row.code || row.id || writeId || `C-${index + 1}`,
    uuid: writeId,
    grade: row.grade || '未设置',
    code: row.name || row.code || '未命名班级',
    students: Number(row.students ?? row.studentCount ?? 0),
    homeroom: row.homeroom || '未设置',
    defaultClassroom: row.defaultClassroom || '',
    subjects: Number(row.subjects || 0),
    gradeColor: row.gradeColor || classGradeColor(row.grade),
  };
}

function normalizeSubjectRow(row, index) {
  const writeId = classSubjectWriteId(row);
  return {
    id: row.code || row.id || writeId || `S-${index + 1}`,
    uuid: writeId,
    name: row.name || '未命名科目',
    category: subjectCategoryLabel(row.category, row.name),
    grades: Array.isArray(row.grades) ? row.grades : ['全年级'],
    hours: Number(row.hours || 0),
    examType: examTypeLabel(row.examType),
    color: row.color || 'slate',
    examCount: Number(row.examCount || 0),
  };
}

function classGradeColor(grade) {
  const text = String(grade || '');
  if (text.includes('高三') || text.includes('12')) return 'rose';
  if (text.includes('高二') || text.includes('11')) return 'amber';
  if (text.includes('高一') || text.includes('10')) return 'cyan';
  if (text.includes('初三') || text.includes('9')) return 'indigo';
  if (text.includes('初二') || text.includes('8')) return 'emerald';
  if (text.includes('初一') || text.includes('七')) return 'violet';
  return 'slate';
}

function subjectCategoryLabel(category, name = '') {
  if (category === 'main') return '主科';
  if (category === 'secondary') {
    if (/历史|地理|政治|道德/.test(name)) return '文科';
    return '理科';
  }
  if (category === 'activity') return '副科';
  if (category === 'humanities') return '文科';
  if (category === 'science') return '理科';
  if (category === 'technology') return '副科';
  return category || '副科';
}

function examTypeLabel(type) {
  if (type === 'written') return '笔试';
  if (type === 'oral') return '口试';
  if (type === 'practical') return '实操';
  return type || '其他';
}

function ClassesSubjectsState({ title, sub, action }) {
  return (
    <div className="bg-white rounded-xl py-14 flex flex-col items-center text-center"
      style={{ border: '1px solid var(--border-soft)', boxShadow: 'var(--elevation-1)' }}>
      <div className="w-12 h-12 rounded-full flex items-center justify-center mb-3"
        style={{ background: '#EEF2FF', color: 'var(--brand)' }}>
        <IconBook size={20} strokeWidth={2} />
      </div>
      <div className="text-[14px] font-semibold" style={{ color: 'var(--text-strong)' }}>{title}</div>
      {sub && <div className="text-[12px] mt-1 max-w-md" style={{ color: 'var(--text-soft)' }}>{sub}</div>}
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}

const CS_FIELD_STYLE = {
  border: '1px solid var(--border-soft)', borderRadius: 10, padding: '8px 11px',
  fontSize: 13, color: 'var(--text-strong)', background: '#fff', width: '100%', outline: 'none',
};

function CsField({ label, required, span, children }) {
  return (
    <label className="flex flex-col gap-1.5" style={span ? { gridColumn: '1 / -1' } : undefined}>
      <span className="text-[11.5px] font-medium" style={{ color: 'var(--text-medium)' }}>
        {label}{required && <span style={{ color: '#EF4444' }}> *</span>}
      </span>
      {children}
    </label>
  );
}

const csToInt = (v) => { const n = parseInt(String(v).trim(), 10); return Number.isFinite(n) ? n : 0; };

// 新增 / 编辑 班级 — 真实写入后端 (POST / PUT /api/v1/classes)
function ClassFormModal({ klass, onSaved }) {
  const isEdit = Boolean(klass);
  const writeId = klass && klass.uuid;
  const [form, setForm] = React.useState({ code: '', name: '', grade: '', stage: '', studentCount: '', status: 'active' });
  // 编辑时保留不在本表单内编辑的关联字段，避免被置空
  const [keep, setKeep] = React.useState({ homeroomTeacherId: null, defaultClassroomId: null });
  const [loading, setLoading] = React.useState(Boolean(isEdit && writeId));
  const [saving, setSaving] = React.useState(false);
  const [err, setErr] = React.useState('');

  React.useEffect(() => {
    if (!isEdit) return undefined;
    if (!writeId) {
      setErr('该班级缺少后端记录标识，无法编辑；请刷新数据后重试');
      return undefined;
    }
    let alive = true;
    window.api.get('/classes/' + writeId)
      .then((d) => {
        if (!alive) return;
        setForm({
          code: d.code || '', name: d.name || '', grade: d.grade || '', stage: d.stage || '',
          studentCount: d.studentCount == null ? '' : String(d.studentCount), status: d.status || 'active',
        });
        setKeep({ homeroomTeacherId: d.homeroomTeacherId || null, defaultClassroomId: d.defaultClassroomId || null });
      })
      .catch((e) => { if (alive) setErr(e.message || '读取班级档案失败'); })
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, [isEdit, writeId]);

  const bind = (key) => (e) => {
    const value = e && e.target ? e.target.value : e;
    setForm((prev) => ({ ...prev, [key]: value }));
  };

  const submit = async () => {
    if (saving) return;
    if (isEdit && !writeId) { setErr('该班级缺少后端记录标识，无法保存修改'); return; }
    if (!form.code.trim() || !form.name.trim() || !form.grade.trim()) { setErr('班级编号、名称和年级为必填项'); return; }
    setSaving(true); setErr('');
    const body = {
      code: form.code.trim(), name: form.name.trim(), grade: form.grade.trim(),
      stage: form.stage.trim() || null, studentCount: csToInt(form.studentCount), status: form.status,
      homeroomTeacherId: keep.homeroomTeacherId, defaultClassroomId: keep.defaultClassroomId,
    };
    try {
      if (isEdit) await window.api.put('/classes/' + writeId, body);
      else await window.api.post('/classes', body);
      window.ui.closeModal();
      window.ui.toast(isEdit ? '班级已更新' : '班级已新增', { kind: 'success', desc: body.name + '（' + body.code + '）' });
      if (onSaved) onSaved();
    } catch (e) {
      setErr(e.message || '保存失败');
      setSaving(false);
    }
  };

  if (loading) return <div className="py-10 text-center text-[13px]" style={{ color: 'var(--text-soft)' }}>正在读取班级档案…</div>;

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <CsField label="班级编号" required><input style={CS_FIELD_STYLE} value={form.code} onChange={bind('code')} placeholder="如 C7-01" /></CsField>
        <CsField label="班级名称" required><input style={CS_FIELD_STYLE} value={form.name} onChange={bind('name')} placeholder="如 七(1)班" /></CsField>
        <CsField label="所属年级" required><input style={CS_FIELD_STYLE} value={form.grade} onChange={bind('grade')} placeholder="如 七年级 / 高三" /></CsField>
        <CsField label="学段"><input style={CS_FIELD_STYLE} value={form.stage} onChange={bind('stage')} placeholder="如 初中 / 高中" /></CsField>
        <CsField label="学生人数"><input type="number" min="0" style={CS_FIELD_STYLE} value={form.studentCount} onChange={bind('studentCount')} placeholder="如 45" /></CsField>
        <CsField label="状态">
          <select style={CS_FIELD_STYLE} value={form.status} onChange={bind('status')}>
            <option value="active">启用</option>
            <option value="inactive">停用</option>
          </select>
        </CsField>
      </div>
      {err && <div role="alert" className="rounded-lg px-3 py-2 text-[12px]" style={{ background: '#FEF2F2', color: '#B91C1C', border: '1px solid #FECACA' }}>{err}</div>}
      <div className="flex items-center justify-end gap-2 pt-1">
        <Button onClick={() => window.ui.closeModal()}>取消</Button>
        <Button variant="primary" icon={IconCheck} disabled={saving} onClick={submit}>{saving ? '保存中…' : isEdit ? '保存修改' : '确认新增'}</Button>
      </div>
    </div>
  );
}

// 新增 / 编辑 科目 — 真实写入后端 (POST / PUT /api/v1/subjects)
function SubjectFormModal({ subject, onSaved }) {
  const isEdit = Boolean(subject);
  const writeId = subject && subject.uuid;
  const [form, setForm] = React.useState({ code: '', name: '', category: 'main', examType: 'written', hours: '', color: 'slate', status: 'active' });
  const [loading, setLoading] = React.useState(Boolean(isEdit && writeId));
  const [saving, setSaving] = React.useState(false);
  const [err, setErr] = React.useState('');

  React.useEffect(() => {
    if (!isEdit) return undefined;
    if (!writeId) {
      setErr('该科目缺少后端记录标识，无法编辑；请刷新数据后重试');
      return undefined;
    }
    let alive = true;
    window.api.get('/subjects/' + writeId)
      .then((d) => {
        if (!alive) return;
        setForm({
          code: d.code || '', name: d.name || '', category: d.category || 'main',
          examType: d.examType || 'written', hours: d.hours == null ? '' : String(d.hours),
          color: d.color || 'slate', status: d.status || 'active',
        });
      })
      .catch((e) => { if (alive) setErr(e.message || '读取科目档案失败'); })
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, [isEdit, writeId]);

  const bind = (key) => (e) => {
    const value = e && e.target ? e.target.value : e;
    setForm((prev) => ({ ...prev, [key]: value }));
  };

  const submit = async () => {
    if (saving) return;
    if (isEdit && !writeId) { setErr('该科目缺少后端记录标识，无法保存修改'); return; }
    if (!form.name.trim()) { setErr('科目名称为必填项'); return; }
    setSaving(true); setErr('');
    const body = {
      code: form.code.trim() || null, name: form.name.trim(), category: form.category,
      examType: form.examType, hours: String(form.hours).trim() === '' ? null : Number(form.hours),
      color: form.color, status: form.status,
    };
    try {
      if (isEdit) await window.api.put('/subjects/' + writeId, body);
      else await window.api.post('/subjects', body);
      window.ui.closeModal();
      window.ui.toast(isEdit ? '科目已更新' : '科目已新增', { kind: 'success', desc: body.name });
      if (onSaved) onSaved();
    } catch (e) {
      setErr(e.message || '保存失败');
      setSaving(false);
    }
  };

  if (loading) return <div className="py-10 text-center text-[13px]" style={{ color: 'var(--text-soft)' }}>正在读取科目档案…</div>;

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <CsField label="科目编号"><input style={CS_FIELD_STYLE} value={form.code} onChange={bind('code')} placeholder="如 S001" /></CsField>
        <CsField label="科目名称" required><input style={CS_FIELD_STYLE} value={form.name} onChange={bind('name')} placeholder="如 数学" /></CsField>
        <CsField label="类别">
          <select style={CS_FIELD_STYLE} value={form.category} onChange={bind('category')}>
            <option value="main">主科</option>
            <option value="secondary">副科</option>
            <option value="activity">术科</option>
          </select>
        </CsField>
        <CsField label="考试形式">
          <select style={CS_FIELD_STYLE} value={form.examType} onChange={bind('examType')}>
            <option value="written">笔试</option>
            <option value="oral">口试</option>
            <option value="practical">实操</option>
          </select>
        </CsField>
        <CsField label="周课时"><input type="number" min="0" step="0.5" style={CS_FIELD_STYLE} value={form.hours} onChange={bind('hours')} placeholder="如 5" /></CsField>
        <CsField label="标识色">
          <select style={CS_FIELD_STYLE} value={form.color} onChange={bind('color')}>
            {[['rose','玫红'], ['indigo','靛蓝'], ['sky','天蓝'], ['cyan','青色'], ['emerald','翠绿'], ['amber','琥珀'], ['violet','紫罗兰'], ['pink','粉红'], ['slate','石板灰']].map(([c, zh]) => <option key={c} value={c}>{zh}</option>)}
          </select>
        </CsField>
        <CsField label="状态">
          <select style={CS_FIELD_STYLE} value={form.status} onChange={bind('status')}>
            <option value="active">启用</option>
            <option value="inactive">停用</option>
          </select>
        </CsField>
      </div>
      {err && <div role="alert" className="rounded-lg px-3 py-2 text-[12px]" style={{ background: '#FEF2F2', color: '#B91C1C', border: '1px solid #FECACA' }}>{err}</div>}
      <div className="flex items-center justify-end gap-2 pt-1">
        <Button onClick={() => window.ui.closeModal()}>取消</Button>
        <Button variant="primary" icon={IconCheck} disabled={saving} onClick={submit}>{saving ? '保存中…' : isEdit ? '保存修改' : '确认新增'}</Button>
      </div>
    </div>
  );
}

// 批量导入 班级 / 科目 — 真实落库 (POST /api/v1/import/{kind}/commit)
function MasterImportModal({ kind, label, onDone }) {
  const [file, setFile] = React.useState(null);
  const [busy, setBusy] = React.useState(false);
  const [err, setErr] = React.useState('');
  const inputRef = React.useRef(null);

  const submit = async () => {
    if (busy) return;
    if (!file) { setErr('请先选择 Excel 文件'); return; }
    setBusy(true); setErr('');
    try {
      const res = await window.api.upload('/import/' + kind + '/commit', file, {});
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
          {file ? (Math.max(1, Math.round(file.size / 1024)) + ' KB · 点击可重新选择') : '支持 .xlsx / .xls，需包含对应表头'}
        </div>
      </div>
      <div className="rounded-lg p-3 flex items-start gap-2.5" style={{ background: '#EEF2FF' }}>
        <IconHelp size={14} className="shrink-0 mt-0.5" style={{ color: '#4F46E5' }} />
        <div className="text-[11.5px]" style={{ color: '#3730A3' }}>导入将真实写入本校{label}名册（按编号去重 upsert）；校验未通过的文件会被拒绝。</div>
      </div>
      {err && <div className="rounded-lg px-3 py-2 text-[12px]" style={{ background: '#FEF2F2', color: '#B91C1C', border: '1px solid #FECACA' }}>{err}</div>}
      <div className="flex items-center justify-end gap-2">
        <Button onClick={() => window.ui.closeModal()}>取消</Button>
        <Button variant="primary" icon={IconUpload} disabled={busy} onClick={submit}>{busy ? '导入中…' : '开始导入'}</Button>
      </div>
    </div>
  );
}

function ClassesSubjectsPage({ routeId }) {
  const routeTab = routeId === 'subjects' ? 'subjects' : 'classes';
  const [tab, setTab] = React.useState(routeTab);
  const [classes, setClasses] = React.useState([]);
  const [subjects, setSubjects] = React.useState([]);
  const [loading, setLoading] = React.useState(true);
  const [loadError, setLoadError] = React.useState('');

  React.useEffect(() => {
    setTab(routeId === 'subjects' ? 'subjects' : 'classes');
  }, [routeId]);

  const loadMasterData = React.useCallback(() => {
    let alive = true;
    setLoading(true);
    setLoadError('');
    Promise.all([window.api.get('/classes'), window.api.get('/subjects')])
      .then(([classRows, subjectRows]) => {
        if (!alive) return;
        setClasses(Array.isArray(classRows) ? classRows.map(normalizeClassRow) : []);
        setSubjects(Array.isArray(subjectRows) ? subjectRows.map(normalizeSubjectRow) : []);
      })
      .catch((error) => {
        if (!alive) return;
        setClasses([]);
        setSubjects([]);
        setLoadError(error.message || '班级科目数据加载失败');
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => { alive = false; };
  }, []);

  React.useEffect(() => loadMasterData(), [loadMasterData]);

  const [search, setSearch] = React.useState('');
  const [classGrade, setClassGrade] = React.useState('全部');
  const [subjectCat, setSubjectCat] = React.useState('全部');

  const openClassForm = (klass) => {
    if (klass && !klass.uuid) {
      window.ui.toast('无法编辑班级', { kind: 'error', desc: '记录缺少后端标识，请刷新后重试' });
      return;
    }
    window.ui.modal({
      title: klass ? '编辑班级' : '新增班级',
      subtitle: klass ? `${klass.code} · ${klass.grade}` : '录入班级编号 / 名称 / 年级等信息，保存后写入名册',
      size: 'md',
      content: <ClassFormModal klass={klass} onSaved={loadMasterData} />,
    });
  };
  const removeClass = async (klass) => {
    if (!klass || !klass.uuid) {
      window.ui.toast('无法删除班级', { kind: 'error', desc: '记录缺少后端标识，请刷新后重试' });
      return;
    }
    const ok = await window.ui.confirm({ title: '删除该班级?', desc: `将永久删除「${klass.code}（${klass.grade}）」。此操作不可撤销。`, confirmText: '确认删除', cancelText: '取消', danger: true });
    if (!ok) return;
    try { await window.api.del('/classes/' + klass.uuid); window.ui.toast('班级已删除', { kind: 'success', desc: klass.code }); loadMasterData(); }
    catch (e) { window.ui.toast('删除失败', { kind: 'error', desc: e.message || '请稍后重试' }); }
  };
  const openSubjectForm = (subject) => {
    if (subject && !subject.uuid) {
      window.ui.toast('无法编辑科目', { kind: 'error', desc: '记录缺少后端标识，请刷新后重试' });
      return;
    }
    window.ui.modal({
      title: subject ? '编辑科目' : '新增科目',
      subtitle: subject ? subject.name : '录入科目名称 / 类别 / 考试形式等信息，保存后写入名册',
      size: 'md',
      content: <SubjectFormModal subject={subject} onSaved={loadMasterData} />,
    });
  };
  const removeSubject = async (subject) => {
    if (!subject || !subject.uuid) {
      window.ui.toast('无法删除科目', { kind: 'error', desc: '记录缺少后端标识，请刷新后重试' });
      return;
    }
    const ok = await window.ui.confirm({ title: '删除该科目?', desc: `将永久删除「${subject.name}」。此操作不可撤销。`, confirmText: '确认删除', cancelText: '取消', danger: true });
    if (!ok) return;
    try { await window.api.del('/subjects/' + subject.uuid); window.ui.toast('科目已删除', { kind: 'success', desc: subject.name }); loadMasterData(); }
    catch (e) { window.ui.toast('删除失败', { kind: 'error', desc: e.message || '请稍后重试' }); }
  };

  const q = search.trim().toLowerCase();
  const gradeOpts = Array.from(new Set(classes.map((c) => c.grade).filter(Boolean)));
  const catOpts = Array.from(new Set(subjects.map((s) => s.category).filter(Boolean)));
  const filteredClasses = classes.filter((c) => {
    if (q && !`${c.code} ${c.grade} ${c.homeroom}`.toLowerCase().includes(q)) return false;
    if (classGrade !== '全部' && c.grade !== classGrade) return false;
    return true;
  });
  const filteredSubjects = subjects.filter((s) => {
    if (q && !`${s.id} ${s.name}`.toLowerCase().includes(q)) return false;
    if (subjectCat !== '全部' && s.category !== subjectCat) return false;
    return true;
  });
  const rows = tab === 'classes' ? filteredClasses : filteredSubjects;
  const rawCount = tab === 'classes' ? classes.length : subjects.length;
  const resetAll = () => { setSearch(''); setClassGrade('全部'); setSubjectCat('全部'); };

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
      render: (r) => <span className="font-mono text-[12px]" style={{ color: 'var(--text-medium)' }}>{r.defaultClassroom || '未绑定'}</span> },
    { key: 'actions', label: '操作', width: 140, align: 'right',
      render: (r) => (
        <div className="inline-flex items-center gap-1" onClick={(e) => e.stopPropagation()}>
          <button className="text-[12px] font-medium px-1.5 hover:underline" style={{ color: 'var(--text-medium)' }}
            onClick={() => openClassForm(r)}>编辑</button>
          <span className="text-slate-300">·</span>
          <button className="text-[12px] font-medium px-1.5 hover:underline" style={{ color: '#DC2626' }}
            onClick={() => removeClass(r)}>删除</button>
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
      render: (r) => <span>{r.examCount} 场</span> },
    { key: 'actions', label: '操作', width: 100, align: 'right',
      render: (r) => (
        <div className="inline-flex items-center gap-1" onClick={(e) => e.stopPropagation()}>
          <button className="text-[12px] font-medium px-1.5 hover:underline" style={{ color: 'var(--text-medium)' }}
            onClick={() => openSubjectForm(r)}>编辑</button>
          <span className="text-slate-300">·</span>
          <button className="text-[12px] font-medium px-1.5 hover:underline" style={{ color: '#DC2626' }}
            onClick={() => removeSubject(r)}>删除</button>
        </div>
      ),
    },
  ];

  const tabs = [
    { id: 'classes',  label: '班级管理',  icon: PRODUCT_ICON_COMPONENTS.classes, count: classes.length },
    { id: 'subjects', label: '科目管理',  icon: PRODUCT_ICON_COMPONENTS.subjects, count: subjects.length },
  ];

  return (
    <PageShell
      crumbs={[orgName(), '数据与资源', '班级科目管理', tab === 'classes' ? '班级管理' : '科目管理']}
      title={tab === 'classes' ? '班级管理' : '科目管理'}
      subtitle={tab === 'classes' ? '维护班级编制、人数与班主任信息;数据将自动用于排监考' : '维护学科开设、年级范围与考试形式;数据将自动用于排监考与统计'}
      actions={
        <>
          <Button icon={IconUpload} onClick={() => window.ui.modal({
            title: tab === 'classes' ? '批量导入班级' : '批量导入科目',
            subtitle: '上传 Excel，按编号 upsert 写入本校名册',
            size: 'md',
            content: <MasterImportModal kind={tab === 'classes' ? 'classes' : 'subjects'} label={tab === 'classes' ? '班级' : '科目'} onDone={loadMasterData} />,
          })}>批量导入</Button>
          <Button variant="primary" icon={IconPlus} onClick={() => (tab === 'classes' ? openClassForm(null) : openSubjectForm(null))}>{tab === 'classes' ? '新增班级' : '新增科目'}</Button>
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
              onClick={() => window.navigateTo(t.id)}
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
        searchValue={search}
        onSearch={setSearch}
        filters={tab === 'classes' ? [
          { label: '年级:', value: classGrade, options: ['全部', ...gradeOpts], onChange: setClassGrade },
        ] : [
          { label: '类别:', value: subjectCat, options: ['全部', ...catOpts], onChange: setSubjectCat },
        ]}
        total={rows.length}
        onReset={(q || classGrade !== '全部' || subjectCat !== '全部') ? resetAll : undefined}
      />

      {loading ? (
        <ClassesSubjectsState title="正在加载班级科目数据" sub="正在从后端读取当前租户的班级编制、默认考场和科目配置。" />
      ) : loadError ? (
        <EmptyState
          title="班级科目数据加载失败"
          sub={loadError}
          action={<Button variant="primary" onClick={loadMasterData}>重新加载</Button>}
        />
      ) : rows.length > 0 ? (
        <DataTable
          columns={tab === 'classes' ? classColumns : subjectColumns}
          rows={rows}
          selectable
        />
      ) : rawCount > 0 ? (
        <EmptyState
          title={tab === 'classes' ? '没有符合条件的班级' : '没有符合条件的科目'}
          sub="当前筛选条件下没有匹配结果，可调整筛选或清空。"
          action={<Button variant="primary" onClick={resetAll}>清空筛选</Button>}
        />
      ) : (
        <EmptyState
          title={tab === 'classes' ? '暂无班级数据' : '暂无科目数据'}
          sub="当前租户还没有维护这类主数据，可通过导入模板或新增表单补齐。"
          action={<Button variant="primary" onClick={() => (tab === 'classes' ? openClassForm(null) : openSubjectForm(null))}>新增{tab === 'classes' ? '班级' : '科目'}</Button>}
        />
      )}
    </PageShell>
  );
}

window.ClassesSubjectsPage = ClassesSubjectsPage;
