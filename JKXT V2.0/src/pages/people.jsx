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
          <div className="mt-1 num text-[20px] font-bold" style={{ color: 'var(--text-strong)' }}>¥{Number(teacher?.allowance || 0).toLocaleString()}</div>
        </div>
        <div>
          <div className="text-[10.5px] uppercase tracking-wider font-mono" style={{ color: 'var(--text-soft)' }}>本年级均值</div>
          <div className="mt-1 num text-[20px] font-bold" style={{ color: 'var(--text-medium)' }}>14.2 <span className="text-[12px] font-normal" style={{ color: 'var(--text-soft)' }}>场</span></div>
        </div>
      </div>
    </div>
  );
}

function normalizeTeacherRow(row, index) {
  const gender = row.gender === 'male' ? '男' : row.gender === 'female' ? '女' : '未知';
  return {
    id: row.teacherNo || row.id || row.uuid || `T-${index + 1}`,
    uuid: row.uuid,
    name: row.name || '未命名教师',
    gender,
    grade: row.grade || '未设置',
    gradeColor: row.gradeColor || teacherGradeColor(row.grade),
    subjects: Array.isArray(row.subjects) ? row.subjects : [],
    isHomeroom: Boolean(row.isHomeroom ?? row.homeroom),
    homeroomOf: row.homeroomOf || '',
    age: row.age || 0,
    seniority: row.seniority || 0,
    edu: row.edu || '未录入',
    phone: row.phone || '-',
    email: row.email || '-',
    supCount: Number(row.supCount || 0),
    allowance: Number(row.allowance || 0),
    status: row.status === 'active' ? 'active' : 'leave',
  };
}

function teacherGradeColor(grade) {
  const text = String(grade || '');
  if (text.includes('高三') || text.includes('12')) return 'rose';
  if (text.includes('高二') || text.includes('11')) return 'amber';
  if (text.includes('高一') || text.includes('10')) return 'cyan';
  if (text.includes('初三') || text.includes('9')) return 'indigo';
  if (text.includes('初二') || text.includes('8')) return 'emerald';
  if (text.includes('初一') || text.includes('七')) return 'violet';
  return 'slate';
}

function MasterDataState({ title, sub, action, icon: Icon = IconUsers }) {
  return (
    <div className="bg-white rounded-xl py-14 flex flex-col items-center text-center"
      style={{ border: '1px solid var(--border-soft)', boxShadow: 'var(--elevation-1)' }}>
      <div className="w-12 h-12 rounded-full flex items-center justify-center mb-3"
        style={{ background: '#EEF2FF', color: 'var(--brand)' }}>
        <Icon size={20} strokeWidth={2} />
      </div>
      <div className="text-[14px] font-semibold" style={{ color: 'var(--text-strong)' }}>{title}</div>
      {sub && <div className="text-[12px] mt-1 max-w-md" style={{ color: 'var(--text-soft)' }}>{sub}</div>}
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}

// 教师档案表单输入样式 — 与全站圆角/边框/字号一致
const TEACHER_FIELD_STYLE = {
  border: '1px solid var(--border-soft)', borderRadius: 10, padding: '8px 11px',
  fontSize: 13, color: 'var(--text-strong)', background: '#fff', width: '100%', outline: 'none',
};

function TeacherField({ label, required, children }) {
  return (
    <label className="flex flex-col gap-1.5">
      <span className="text-[11.5px] font-medium" style={{ color: 'var(--text-medium)' }}>
        {label}{required && <span style={{ color: '#EF4444' }}> *</span>}
      </span>
      {children}
    </label>
  );
}

// 新增 / 编辑 教师 — 真实写入后端 (POST / PUT /api/v1/teachers)
function TeacherFormModal({ teacher, onSaved }) {
  const isEdit = Boolean(teacher && teacher.uuid);
  const [form, setForm] = React.useState({
    teacherNo: '', name: '', gender: 'male', grade: '', phone: '', email: '', status: 'active', isHomeroom: false,
  });
  const [loading, setLoading] = React.useState(isEdit);
  const [saving, setSaving] = React.useState(false);
  const [err, setErr] = React.useState('');

  React.useEffect(() => {
    if (!isEdit) return undefined;
    let alive = true;
    // 拉取规范记录（含未脱敏的电话/邮箱、原始性别/状态），避免用列表展示值回写造成数据损坏
    window.api.get('/teachers/' + teacher.uuid)
      .then((d) => {
        if (!alive) return;
        setForm({
          teacherNo: d.teacherNo || '', name: d.name || '', gender: d.gender || 'male',
          grade: d.grade || '', phone: d.phone || '', email: d.email || '',
          status: d.status || 'active', isHomeroom: Boolean(d.isHomeroom),
        });
      })
      .catch((e) => { if (alive) setErr(e.message || '读取教师档案失败'); })
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, [isEdit, teacher]);

  const bind = (key) => (e) => {
    const value = e && e.target ? e.target.value : e;
    setForm((prev) => ({ ...prev, [key]: value }));
  };

  const submit = async () => {
    if (saving) return;
    if (!form.teacherNo.trim() || !form.name.trim()) { setErr('工号和姓名为必填项'); return; }
    setSaving(true); setErr('');
    const body = {
      teacherNo: form.teacherNo.trim(), name: form.name.trim(), gender: form.gender,
      grade: form.grade.trim() || null, phone: form.phone.trim() || null,
      email: form.email.trim() || null, status: form.status, isHomeroom: form.isHomeroom,
    };
    try {
      if (isEdit) await window.api.put('/teachers/' + teacher.uuid, body);
      else await window.api.post('/teachers', body);
      window.ui.closeModal();
      window.ui.toast(isEdit ? '教师档案已更新' : '教师已新增', { kind: 'success', desc: body.name + '（' + body.teacherNo + '）' });
      if (onSaved) onSaved();
    } catch (e) {
      setErr(e.message || '保存失败');
      setSaving(false);
    }
  };

  if (loading) {
    return <div className="py-10 text-center text-[13px]" style={{ color: 'var(--text-soft)' }}>正在读取教师档案…</div>;
  }

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-2 gap-3">
        <TeacherField label="工号" required>
          <input style={TEACHER_FIELD_STYLE} value={form.teacherNo} onChange={bind('teacherNo')} placeholder="如 T240101" />
        </TeacherField>
        <TeacherField label="姓名" required>
          <input style={TEACHER_FIELD_STYLE} value={form.name} onChange={bind('name')} placeholder="如 王伟明" />
        </TeacherField>
        <TeacherField label="性别">
          <select style={TEACHER_FIELD_STYLE} value={form.gender} onChange={bind('gender')}>
            <option value="male">男</option>
            <option value="female">女</option>
            <option value="unknown">未知</option>
          </select>
        </TeacherField>
        <TeacherField label="任教年级">
          <input style={TEACHER_FIELD_STYLE} value={form.grade} onChange={bind('grade')} placeholder="如 高三" />
        </TeacherField>
        <TeacherField label="联系电话">
          <input style={TEACHER_FIELD_STYLE} value={form.phone} onChange={bind('phone')} placeholder="如 13800001234" />
        </TeacherField>
        <TeacherField label="邮箱">
          <input style={TEACHER_FIELD_STYLE} value={form.email} onChange={bind('email')} placeholder="name@school.edu.cn" />
        </TeacherField>
        <TeacherField label="状态">
          <select style={TEACHER_FIELD_STYLE} value={form.status} onChange={bind('status')}>
            <option value="active">在职</option>
            <option value="leave">请假</option>
            <option value="inactive">停用</option>
          </select>
        </TeacherField>
        <TeacherField label="班主任">
          <label style={{ height: 38, display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer' }}>
            <input type="checkbox" checked={form.isHomeroom}
              onChange={(e) => setForm((prev) => ({ ...prev, isHomeroom: e.target.checked }))} />
            <span className="text-[12.5px]" style={{ color: 'var(--text-medium)' }}>担任班主任</span>
          </label>
        </TeacherField>
      </div>

      {err && (
        <div className="rounded-lg px-3 py-2 text-[12px]" style={{ background: '#FEF2F2', color: '#B91C1C', border: '1px solid #FECACA' }}>{err}</div>
      )}

      <div className="flex items-center justify-end gap-2 pt-1">
        <Button onClick={() => window.ui.closeModal()}>取消</Button>
        <Button variant="primary" icon={IconCheck} disabled={saving} onClick={submit}>
          {saving ? '保存中…' : isEdit ? '保存修改' : '确认新增'}
        </Button>
      </div>
    </div>
  );
}

// 批量导入教师 — 真实落库 (POST /api/v1/import/teachers/commit，复用 Epic1 导入提交)
function TeacherImportModal({ onDone }) {
  const [file, setFile] = React.useState(null);
  const [busy, setBusy] = React.useState(false);
  const [err, setErr] = React.useState('');
  const inputRef = React.useRef(null);

  const submit = async () => {
    if (busy) return;
    if (!file) { setErr('请先选择 Excel 文件'); return; }
    setBusy(true); setErr('');
    try {
      const res = await window.api.upload('/import/teachers/commit', file, {});
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
        <div className="text-[13px] font-medium" style={{ color: 'var(--text-strong)' }}>
          {file ? file.name : '点击选择 Excel 文件'}
        </div>
        <div className="text-[11px] mt-1" style={{ color: 'var(--text-soft)' }}>
          {file ? (Math.max(1, Math.round(file.size / 1024)) + ' KB · 点击可重新选择') : '支持 .xlsx / .xls，需包含 工号 / 姓名 等表头'}
        </div>
      </div>
      <div className="rounded-lg p-3 flex items-start gap-2.5" style={{ background: '#EEF2FF' }}>
        <IconHelp size={14} className="shrink-0 mt-0.5" style={{ color: '#4F46E5' }} />
        <div className="text-[11.5px]" style={{ color: '#3730A3' }}>
          导入将真实写入本校教师名册（按工号去重 upsert）；校验未通过的文件会被拒绝。
        </div>
      </div>
      {err && (
        <div className="rounded-lg px-3 py-2 text-[12px]" style={{ background: '#FEF2F2', color: '#B91C1C', border: '1px solid #FECACA' }}>{err}</div>
      )}
      <div className="flex items-center justify-end gap-2">
        <Button onClick={() => window.ui.closeModal()}>取消</Button>
        <Button variant="primary" icon={IconUpload} disabled={busy} onClick={submit}>{busy ? '导入中…' : '开始导入'}</Button>
      </div>
    </div>
  );
}

function PeoplePage() {
  const [selectedTeacher, setSelectedTeacher] = React.useState(null);
  const [teachers, setTeachers] = React.useState([]);
  const [loadingTeachers, setLoadingTeachers] = React.useState(true);
  const [teacherError, setTeacherError] = React.useState('');
  const [search, setSearch] = React.useState('');
  const DEFAULT_FILTERS = { grade: '全部', subject: '全部', homeroom: '全部', status: '全部' };
  const [filters, setFilters] = React.useState(DEFAULT_FILTERS);

  const loadTeachers = React.useCallback(() => {
    let alive = true;
    setLoadingTeachers(true);
    setTeacherError('');
    window.api.get('/teachers')
      .then((rows) => {
        if (!alive) return;
        const next = Array.isArray(rows) ? rows.map(normalizeTeacherRow) : [];
        setTeachers(next);
        setSelectedTeacher((current) => {
          if (!next.length) return null;
          return current && next.some((teacher) => teacher.id === current.id) ? current : next[0];
        });
      })
      .catch((error) => {
        if (!alive) return;
        setTeachers([]);
        setTeacherError(error.message || '教师数据加载失败');
      })
      .finally(() => {
        if (alive) setLoadingTeachers(false);
      });
    return () => { alive = false; };
  }, []);

  React.useEffect(() => loadTeachers(), [loadTeachers]);

  // KPI 概览始终基于全量花名册 (汇总指标),与下方表格筛选解耦
  const total = teachers.length;
  const homerooms = teachers.filter(t => t.isHomeroom).length;
  const onLeave = teachers.filter(t => t.status === 'leave').length;
  const totalSup = teachers.reduce((a, b) => a + b.supCount, 0);

  // 选项从真实数据派生 — 杜绝"选了没有结果"的死选项
  const STATUS_LABEL = { active: '在职', leave: '请假' };
  const gradeOpts = ['全部', ...Array.from(new Set(teachers.map(t => t.grade).filter(Boolean)))];
  const subjectOpts = ['全部', ...Array.from(new Set(teachers.flatMap(t => t.subjects)))];

  // 真实筛选：搜索 (姓名/工号/电话/邮箱) + 年级 + 科目 + 班主任 + 状态
  const q = search.trim().toLowerCase();
  const filteredTeachers = teachers.filter((t) => {
    if (q) {
      const hay = `${t.name} ${t.id} ${t.phone} ${t.email}`.toLowerCase();
      if (!hay.includes(q)) return false;
    }
    if (filters.grade !== '全部' && t.grade !== filters.grade) return false;
    if (filters.subject !== '全部' && !t.subjects.includes(filters.subject)) return false;
    if (filters.homeroom === '是' && !t.isHomeroom) return false;
    if (filters.homeroom === '否' && t.isHomeroom) return false;
    if (filters.status !== '全部' && STATUS_LABEL[t.status] !== filters.status) return false;
    return true;
  });

  const setF = (key) => (v) => setFilters((prev) => ({ ...prev, [key]: v }));
  const activeFilterCount =
    (q ? 1 : 0) + Object.keys(DEFAULT_FILTERS).filter((k) => filters[k] !== DEFAULT_FILTERS[k]).length;
  const resetAll = () => {
    setSearch('');
    setFilters(DEFAULT_FILTERS);
    window.ui?.toast?.('已清空全部筛选', { kind: 'info' });
  };

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
    { key: 'actions', label: '操作', width: 150, align: 'right',
      render: (r) => (
        <div className="inline-flex items-center gap-1" onClick={(e) => e.stopPropagation()}>
          <button className="text-[12px] font-medium px-1.5 hover:underline" style={{ color: 'var(--brand)' }}
            onClick={() => openTeacherDetail(r)}>查看</button>
          <span className="text-slate-300">·</span>
          <button className="text-[12px] font-medium px-1.5 hover:underline" style={{ color: 'var(--text-medium)' }}
            onClick={() => openTeacherForm(r)}>编辑</button>
          <span className="text-slate-300">·</span>
          <button className="text-[12px] font-medium px-1.5 hover:underline" style={{ color: '#DC2626' }}
            onClick={() => removeTeacher(r)}>删除</button>
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
          <Button variant="primary" icon={IconEdit} onClick={() => { window.ui.closeDrawer(); openTeacherForm(t); }}>编辑档案</Button>
        </>
      ),
    });
  };

  const openTeacherForm = (teacher) => {
    window.ui.modal({
      title: teacher ? '编辑教师' : '新增教师',
      subtitle: teacher ? `${teacher.name} · ${teacher.id}` : '录入教师基本档案，保存后写入本校名册',
      size: 'md',
      content: <TeacherFormModal teacher={teacher} onSaved={loadTeachers} />,
    });
  };

  const removeTeacher = async (teacher) => {
    const ok = await window.ui.confirm({
      title: '删除该教师?',
      desc: `将永久删除「${teacher.name}（${teacher.id}）」。若其为某班班主任，将自动解除关联。此操作不可撤销。`,
      confirmText: '确认删除',
      cancelText: '取消',
      danger: true,
    });
    if (!ok) return;
    try {
      await window.api.del('/teachers/' + teacher.uuid);
      window.ui.toast('教师已删除', { kind: 'success', desc: `${teacher.name}（${teacher.id}）` });
      loadTeachers();
    } catch (e) {
      window.ui.toast('删除失败', { kind: 'error', desc: e.message || '请稍后重试' });
    }
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
            subtitle: '上传教师名单 Excel，按工号 upsert 写入本校名册',
            size: 'md',
            content: <TeacherImportModal onDone={loadTeachers} />,
          })}>批量导入</Button>
          <Button variant="primary" icon={IconPlus} onClick={() => openTeacherForm(null)}>新增教师</Button>
        </>
      }
    >
      <div className="grid grid-cols-4 gap-4">
        <MiniKPI label="在职教职工" value={total - onLeave} unit="人" accent="#4F46E5" icon={IconUsers} trend="1.4" trendUp />
        <MiniKPI label="班主任" value={homerooms} unit="人" accent="#9333EA" icon={IconCheck} sub="覆盖 6 个年级" />
        <MiniKPI label="本学期总监考" value={totalSup} unit="场次" accent="#059669" icon={IconCalendar} sub={`人均 ${total ? (totalSup/total).toFixed(1) : '0.0'} 场`} />
        <MiniKPI label="请假/休假" value={onLeave} unit="人" accent="#D97706" icon={IconClock} sub="需调整排班" />
      </div>

      <FilterBar
        search="搜索姓名、工号、电话..."
        searchValue={search}
        onSearch={setSearch}
        filters={[
          { label: '年级:', value: filters.grade, options: gradeOpts, onChange: setF('grade') },
          { label: '科目:', value: filters.subject, options: subjectOpts, onChange: setF('subject') },
          { label: '班主任:', value: filters.homeroom, options: ['全部', '是', '否'], onChange: setF('homeroom') },
          { label: '状态:', value: filters.status, options: ['全部', '在职', '请假'], onChange: setF('status') },
        ]}
        total={filteredTeachers.length}
        onReset={activeFilterCount > 0 ? resetAll : undefined}
        right={<Button size="sm" variant="ghost" icon={IconMore}>更多筛选</Button>}
      />

      {loadingTeachers ? (
        <MasterDataState title="正在加载教师数据" sub="正在从后端读取当前租户的教师、任教学科和班主任信息。" />
      ) : teacherError ? (
        <EmptyState
          title="教师数据加载失败"
          sub={teacherError}
          action={<Button variant="primary" onClick={loadTeachers}>重新加载</Button>}
        />
      ) : filteredTeachers.length > 0 ? (
        <DataTable
          columns={columns}
          rows={filteredTeachers}
          selectable
          onRowClick={(row) => setSelectedTeacher(row)}
        />
      ) : (
        <EmptyState
          title="没有符合条件的教职工"
          sub={`当前筛选条件下没有匹配结果${q ? `，关键词「${search.trim()}」` : ''}。可调整年级、科目或状态，或清空筛选重新查看。`}
          action={<Button variant="primary" onClick={resetAll}>清空筛选</Button>}
        />
      )}

      <TeacherScheduleTimeline teacher={selectedTeacher} />
    </PageShell>
  );
}

window.PeoplePage = PeoplePage;
