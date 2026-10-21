// 监考人员管理 — 教师名册与选定任务的真实个人监考明细

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

function resourceTeacherRows(payload, teacher) {
  const id = teacherWriteId(teacher);
  if (!id) throw new Error('教师记录缺少服务器标识，无法准确查询个人排班。');
  if (!Array.isArray(payload?.rows)) throw new Error('排班接口返回格式不正确，请重试。');
  return payload.rows.filter((row) => String(row.teacherId || '') === id)
    .sort((a, b) => String(a.examDate || '').localeCompare(String(b.examDate || '')) || String(a.startTime || '').localeCompare(String(b.startTime || '')));
}

function TeacherScheduleTimeline({ teacher, gradeAverage }) {
  const teacherId = teacherWriteId(teacher);
  const [tasks, setTasks] = React.useState([]);
  const [selectedTaskId, setSelectedTaskId] = React.useState('');
  const [taskState, setTaskState] = React.useState({ status: 'loading', error: '' });
  const [scheduleState, setScheduleState] = React.useState({ status: 'idle', error: '', rows: [], teacherId: '', taskId: '' });
  const [reloadKey, setReloadKey] = React.useState(0);
  React.useEffect(() => {
    let alive = true;
    if (!teacherId) { setTaskState({ status: 'idle', error: '' }); return undefined; }
    setTaskState({ status: 'loading', error: '' });
    Promise.resolve().then(() => window.api.get('/exam-tasks')).then((items) => {
      if (!Array.isArray(items)) throw new Error('任务列表返回格式不正确');
      if (!alive) return;
      setTasks(items);
      let saved = '';
      try { saved = localStorage.getItem('jkxt:taskDetailId') || ''; } catch {}
      setSelectedTaskId((current) => items.some((item) => item.id === current) ? current : items.some((item) => item.id === saved) ? saved : items[0]?.id || '');
      setTaskState({ status: 'ready', error: '' });
    }).catch((error) => {
      if (alive) { setTasks([]); setTaskState({ status: 'error', error: error.message || '任务读取失败' }); }
    });
    return () => { alive = false; };
  }, [teacherId, reloadKey]);
  React.useEffect(() => {
    let alive = true;
    if (!teacherId || !selectedTaskId) return undefined;
    setScheduleState({ status: 'loading', error: '', rows: [], teacherId, taskId: selectedTaskId });
    Promise.resolve().then(() => window.api.get(`/exam-tasks/${encodeURIComponent(selectedTaskId)}/assignments`)).then((payload) => {
      const rows = resourceTeacherRows(payload, teacher);
      if (alive) setScheduleState({ status: 'ready', error: '', rows, teacherId, taskId: selectedTaskId });
    }).catch((error) => {
      if (alive) setScheduleState({ status: 'error', error: error.message || '个人排班读取失败', rows: [], teacherId, taskId: selectedTaskId });
    });
    return () => { alive = false; };
  }, [teacherId, selectedTaskId, reloadKey]);

  const current = scheduleState.teacherId === teacherId && scheduleState.taskId === selectedTaskId;
  const selectedTask = tasks.find((item) => item.id === selectedTaskId);
  const openTask = (route) => {
    if (!selectedTaskId) return;
    if (window.JKXTTaskTools?.open({ id: selectedTaskId }, route)) window.ui?.closeDrawer?.();
  };
  const error = taskState.error || (current ? scheduleState.error : '');
  const loading = teacherId && (taskState.status === 'loading' || (selectedTaskId && (!current || scheduleState.status === 'loading')));
  return (
    <section className="rounded-2xl p-5 min-w-0" style={{ background: 'var(--surface-raised)', border: '1px solid var(--border-soft)', boxShadow: 'var(--elevation-1)' }} aria-label="教师逐场排班">
      <SectionTitle title={teacher ? `${teacher.name} · 逐场排班` : '请选择教师查看排班'} sub="只显示所选任务中服务器返回的该教师监考安排，不合并示例或其他教师记录" />
      {!teacherId ? <p className="text-[14px]" style={{ color: 'var(--text-soft)' }}>{teacher ? '该记录缺少服务器教师标识，请刷新教师名册。' : '从上方表格选择教师，或打开教师详情查看排班。'}</p> : <>
        <div className="flex items-end gap-3 flex-wrap mb-4">
          <label className="flex-1 min-w-[160px] text-[13px]"><span className="block mb-1.5" style={{ color: 'var(--text-medium)' }}>选择考务任务</span>
            <select aria-label="选择考务任务" className="w-full min-h-11 rounded-lg px-3" style={{ background: 'var(--surface-raised)', border: '1px solid var(--border-soft)' }} value={selectedTaskId} disabled={taskState.status !== 'ready' || !tasks.length} onChange={(event) => setSelectedTaskId(event.target.value)}>
              {!tasks.length && <option value="">暂无任务</option>}
              {tasks.map((item) => <option key={item.id} value={item.id}>{item.name || item.taskNo || item.id}</option>)}
            </select>
          </label>
          <Button disabled={!selectedTaskId || taskState.status !== 'ready'} icon={IconList} onClick={() => openTask('task-detail')}>任务详情</Button>
          <Button disabled={!selectedTaskId || taskState.status !== 'ready'} icon={IconEdit} onClick={() => openTask('edit')}>调整此任务</Button>
        </div>
        {error ? <div role="alert" data-testid="teacher-schedule-error" className="rounded-lg p-4" style={{ background: 'var(--surface-muted)' }}><p>{error}</p><Button onClick={() => setReloadKey((key) => key + 1)}>重新读取</Button></div> : loading ? <p role="status" data-testid="teacher-schedule-loading">正在读取个人排班…</p> : !tasks.length ? <p className="py-6 text-[14px]">当前没有考务任务，无法查询逐场排班。</p> : current && scheduleState.status === 'ready' ? <>
          <p className="mb-3 text-[13px]" style={{ color: 'var(--text-medium)' }}>任务：{selectedTask?.name || selectedTaskId} · {scheduleState.rows.length} 场监考分配</p>
          {scheduleState.rows.length ? <div className="overflow-x-auto"><table className="w-full text-left text-[13px] min-w-[420px]"><caption className="sr-only">{teacher.name} 在所选任务中的监考安排</caption><thead><tr style={{ color: 'var(--text-soft)', borderBottom: '1px solid var(--border-soft)' }}>{['日期', '时间', '科目', '考场', '岗位'].map((label) => <th key={label} scope="col" className="py-3 pr-3 font-medium">{label}</th>)}</tr></thead><tbody>{scheduleState.rows.map((row, index) => <tr key={row.assignmentId || index} style={{ borderBottom: '1px solid var(--border-soft)' }}><td className="py-3 pr-3 whitespace-nowrap">{row.examDate || '未提供'}</td><td className="py-3 pr-3 whitespace-nowrap">{row.startTime && row.endTime ? `${row.startTime.slice(0,5)}–${row.endTime.slice(0,5)}` : '未提供'}</td><td className="py-3 pr-3">{row.subjectName || '未提供'}</td><td className="py-3 pr-3">{row.roomCode || row.roomName || '未提供'}</td><td className="py-3 pr-3">{{chief:'主监考',assistant:'副监考',invigilator:'监考',primary:'主监考',secondary:'副监考'}[row.role] || row.role || '未提供'}</td></tr>)}</tbody></table></div> : <p className="py-5 text-[14px]" style={{ color: 'var(--text-soft)' }}>该教师在所选任务中没有监考安排。可切换其他任务查看，不代表其他任务或整月无监考。</p>}
        </> : null}
      </>}
    </section>
  );
}

function teacherWriteId(row) {
  const direct = row && (row.uuid || row.recordId || row.resourceId);
  if (direct) return String(direct);
  const legacyId = String((row && row.id) || '');
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(legacyId)
    ? legacyId
    : '';
}

function normalizeTeacherRow(row, index) {
  const gender = row.gender === 'male' ? '男' : row.gender === 'female' ? '女' : '未知';
  const uuid = teacherWriteId(row);
  const id = row.teacherNo || row.id || uuid || `T-${index + 1}`;
  return {
    id,
    uuid,
    stableId: uuid || `teacher-no:${id}`,
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

// TeacherRequest is a replacement DTO. Keep its non-editable fields from the
// exact detail response; never send derived names, display IDs or arbitrary GET keys.
function teacherEditSnapshot(record, expectedId) {
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
  if (!record || !uuid.test(String(expectedId || '')) || record.uuid !== expectedId) {
    throw new Error('教师档案标识不匹配，请关闭窗口并重新读取后再编辑');
  }
  const fields = ['age', 'position', 'leaveWindows', 'functionalAccommodations',
    'dutyWindows', 'qualifications', 'qualificationExpiry', 'maxDailySessions',
    'preferredCampus', 'homeroomClassId'];
  const owns = (key) => Object.prototype.hasOwnProperty.call(record, key);
  const basicFields = ['teacherNo', 'name', 'gender', 'grade', 'phone', 'email', 'status', 'isHomeroom'];
  const incomplete = [...fields, ...basicFields].some(key => !owns(key) || record[key] === undefined)
    || typeof record.teacherNo !== 'string' || !record.teacherNo.trim()
    || typeof record.name !== 'string' || !record.name.trim()
    || typeof record.isHomeroom !== 'boolean'
    || ['leaveWindows', 'functionalAccommodations', 'dutyWindows', 'qualifications'].some(key => !Array.isArray(record[key]))
    || !Number.isInteger(record.maxDailySessions) || record.maxDailySessions < 1 || record.maxDailySessions > 12
    || (record.homeroomClassId !== null && !uuid.test(String(record.homeroomClassId)));
  if (incomplete) throw new Error('教师专业档案返回不完整，已禁止保存以避免覆盖资质或排班要求；请重新读取');
  const preserved = Object.fromEntries(fields.map(key => [key, record[key]]));
  // Current TeacherDto returns subject display names, not subjectIds. Omitting
  // subjectIds preserves existing links in updateTeacher; names are never IDs.
  if (owns('subjectIds')) {
    if (!Array.isArray(record.subjectIds) || record.subjectIds.some(id => !uuid.test(String(id)))) {
      throw new Error('教师科目关联标识不完整，已禁止保存；请重新读取');
    }
    preserved.subjectIds = record.subjectIds;
  }
  return { uuid: expectedId, preserved: JSON.parse(JSON.stringify(preserved)) };
}

// 新增 / 编辑 教师 — 真实写入后端 (POST / PUT /api/v1/teachers)
function TeacherFormModal({ teacher, onSaved }) {
  const isEdit = Boolean(teacher);
  const writeId = teacher && teacher.uuid;
  const [form, setForm] = React.useState({
    teacherNo: '', name: '', gender: 'male', grade: '', phone: '', email: '', status: 'active', isHomeroom: false,
    qualifications: ['INVIGILATOR'], qualificationExpiry: '', maxDailySessions: '4',
  });
  const [loading, setLoading] = React.useState(Boolean(isEdit && writeId));
  const [saving, setSaving] = React.useState(false);
  const [err, setErr] = React.useState('');
  const [sourceProfile, setSourceProfile] = React.useState(null);
  const canSave = !saving && !loading && (!isEdit || Boolean(writeId && sourceProfile?.uuid === writeId));

  React.useEffect(() => {
    setSourceProfile(null);
    if (!isEdit) return undefined;
    if (!writeId) {
      setErr('该教师缺少后端记录标识，无法编辑；请刷新数据后重试');
      return undefined;
    }
    let alive = true;
    setLoading(true);
    setErr('');
    // 拉取规范记录（含未脱敏的电话/邮箱、原始性别/状态），避免用列表展示值回写造成数据损坏
    window.api.get('/teachers/' + writeId)
      .then((d) => {
        if (!alive) return;
        const snapshot = teacherEditSnapshot(d, writeId);
        setForm({
          teacherNo: d.teacherNo || '', name: d.name || '', gender: d.gender || 'male',
          grade: d.grade || '', phone: d.phone || '', email: d.email || '',
          status: d.status || 'active', isHomeroom: Boolean(d.isHomeroom),
          qualifications: [...d.qualifications], qualificationExpiry: d.qualificationExpiry || '',
          maxDailySessions: String(d.maxDailySessions),
        });
        setSourceProfile(snapshot);
      })
      .catch((e) => { if (alive) setErr(e.message || '读取教师档案失败'); })
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, [isEdit, writeId]);

  const bind = (key) => (e) => {
    const value = e && e.target ? e.target.value : e;
    setForm((prev) => ({ ...prev, [key]: value }));
  };

  const submit = async () => {
    if (!canSave) return;
    if (isEdit && !writeId) { setErr('该教师缺少后端记录标识，无法保存修改'); return; }
    if (!form.teacherNo.trim() || !form.name.trim()) { setErr('工号和姓名为必填项'); return; }
    const dailyLimit = Number(form.maxDailySessions);
    if (!/^\d+$/.test(String(form.maxDailySessions)) || !Number.isInteger(dailyLimit) || dailyLimit < 1 || dailyLimit > 12) {
      setErr('每日监考上限须为 1～12 场的整数'); return;
    }
    if (!Array.isArray(form.qualifications) || form.qualifications.some(q => !['INVIGILATOR', 'CHIEF'].includes(q))
        || new Set(form.qualifications).size !== form.qualifications.length
        || (form.qualifications.includes('CHIEF') && !form.qualifications.includes('INVIGILATOR'))) {
      setErr('主监考资格须同时具备监考资格，请重新选择'); return;
    }
    if (form.qualificationExpiry) {
      const expiry = new Date(form.qualificationExpiry + 'T00:00:00Z');
      if (!/^\d{4}-\d{2}-\d{2}$/.test(form.qualificationExpiry) || Number.isNaN(expiry.getTime())
          || expiry.toISOString().slice(0, 10) !== form.qualificationExpiry) {
        setErr('资格有效期须为真实的年月日，也可留空'); return;
      }
    }
    setSaving(true); setErr('');
    const body = {
      ...(isEdit ? sourceProfile.preserved : {}),
      teacherNo: form.teacherNo.trim(), name: form.name.trim(), gender: form.gender,
      grade: form.grade.trim() || null, phone: form.phone.trim() || null,
      email: form.email.trim() || null, status: form.status, isHomeroom: form.isHomeroom,
      qualifications: [...form.qualifications], qualificationExpiry: form.qualificationExpiry || null,
      maxDailySessions: dailyLimit,
    };
    try {
      if (isEdit) await window.api.put('/teachers/' + writeId, body);
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

      <fieldset className="rounded-xl p-3 space-y-3" style={{ border: '1px solid var(--border-soft)', background: 'var(--surface-muted)' }}>
        <legend className="px-1 text-[13px] font-semibold" style={{ color: 'var(--text-strong)' }}>监考资格与工作量</legend>
        <div className="grid grid-cols-2 gap-3">
          <TeacherField label="监考资格">
            <select aria-label="监考资格" style={TEACHER_FIELD_STYLE}
              value={[...form.qualifications].sort().reverse().join(',')}
              onChange={e => setForm(prev => ({ ...prev, qualifications: e.target.value ? e.target.value.split(',') : [] }))}>
              <option value="">无监考资格（不参与排班）</option>
              <option value="INVIGILATOR">监考资格</option>
              <option value="INVIGILATOR,CHIEF">监考及主监考资格</option>
              {form.qualifications.includes('CHIEF') && !form.qualifications.includes('INVIGILATOR')
                && <option value="CHIEF" disabled>仅主监标签（请补齐监考资格）</option>}
            </select>
          </TeacherField>
          <TeacherField label="每日监考上限">
            <input aria-label="每日监考上限" type="number" min={1} max={12} step={1} inputMode="numeric"
              style={TEACHER_FIELD_STYLE} value={form.maxDailySessions} onChange={bind('maxDailySessions')} />
          </TeacherField>
          <TeacherField label="资格有效期">
            <input aria-label="资格有效期" type="date" style={TEACHER_FIELD_STYLE}
              value={form.qualificationExpiry} onChange={bind('qualificationExpiry')} />
          </TeacherField>
          <p className="self-center text-[12px] leading-relaxed" style={{ color: 'var(--text-soft)' }}>
            有效期包含所选当日；留空表示无到期限制。每日上限包含其他已发布考试的监考任务。
          </p>
        </div>
      </fieldset>

      {err && (
        <div role="alert" className="rounded-lg px-3 py-2 text-[12px]" style={{ background: '#FEF2F2', color: '#B91C1C', border: '1px solid #FECACA' }}>{err}</div>
      )}

      <div className="flex items-center justify-end gap-2 pt-1">
        <Button onClick={() => window.ui.closeModal()}>取消</Button>
        <Button variant="primary" icon={IconCheck} disabled={!canSave} onClick={submit}>
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
        if (!Array.isArray(rows)) throw new Error('教师接口返回格式不正确');
        const next = rows.map(normalizeTeacherRow);
        setTeachers(next);
        setSelectedTeacher((current) => {
          if (!next.length) return null;
          if (!current) return next[0];
          return next.find((teacher) => teacher.stableId === current.stableId) || next[0];
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
  const homeroomGradeCount = new Set(
    teachers
      .filter((teacher) => teacher.isHomeroom)
      .map((teacher) => teacher.grade)
      .filter((grade) => grade && grade !== '未设置'),
  ).size;
  const selectedGradeTeachers = selectedTeacher && selectedTeacher.grade && selectedTeacher.grade !== '未设置'
    ? teachers.filter((teacher) => teacher.grade === selectedTeacher.grade)
    : [];
  const selectedGradeAverage = selectedGradeTeachers.length
    ? selectedGradeTeachers.reduce((sum, teacher) => sum + teacher.supCount, 0) / selectedGradeTeachers.length
    : null;

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
    { key: 'supCount', label: '累计监考', width: 110, align: 'center',
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
              { l: '累计监考', v: `${t.supCount} 场`, mono: true },
              { l: '状态',     v: t.status === 'leave' ? '请假' : '在职' },
            ].map((it, i) => (
              <div key={i} className="rounded-lg p-3" style={{ background: '#F8FAFC', gridColumn: it.span === 2 ? 'span 2' : undefined }}>
                <div className="text-[10.5px] font-mono uppercase tracking-wider" style={{ color: 'var(--text-soft)' }}>{it.l}</div>
                <div className="mt-1 text-[13px] font-semibold truncate" style={{ color: 'var(--text-strong)', fontFamily: it.mono ? 'Geist Mono' : undefined }}>{it.v}</div>
              </div>
            ))}
          </div>
          <div className="text-[13px] leading-relaxed" style={{ color: 'var(--text-medium)' }}>累计监考按本校各已发布考务任务的最近一次成功排班汇总，不随顶部学期筛选，不含考试计划的新版本排班。回避规则以所选任务的策略和后端冲突校验为准。教师档案本身不代表已启用“本年级、本学科或班主任回避”。</div>
        </div>
      ),
      footer: (
        <>
          <Button onClick={() => window.ui.closeDrawer()}>关闭</Button>
          <Button icon={IconCalendar} disabled={!teacherWriteId(t)} onClick={() => window.ui.drawer({ title: `${t.name} · 逐场排班`, subtitle: '按真实考务任务查看个人监考安排', width: 720, content: <TeacherScheduleTimeline teacher={t} />, footer: <Button onClick={() => window.ui.closeDrawer()}>关闭</Button> })}>查看排班</Button>
          <Button variant="primary" icon={IconEdit} onClick={() => { window.ui.closeDrawer(); openTeacherForm(t); }}>编辑档案</Button>
        </>
      ),
    });
  };

  const openTeacherForm = (teacher) => {
    if (teacher && !teacher.uuid) {
      window.ui.toast('无法编辑教师', { kind: 'error', desc: '记录缺少后端标识，请刷新后重试' });
      return;
    }
    window.ui.modal({
      title: teacher ? '编辑教师' : '新增教师',
      subtitle: teacher ? `${teacher.name} · ${teacher.id}` : '录入教师基本档案，保存后写入本校名册',
      size: 'md',
      content: <TeacherFormModal teacher={teacher} onSaved={loadTeachers} />,
    });
  };

  const removeTeacher = async (teacher) => {
    if (!teacher || !teacher.uuid) {
      window.ui.toast('无法删除教师', { kind: 'error', desc: '记录缺少后端标识，请刷新后重试' });
      return;
    }
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
      crumbs={[orgName(), '数据与资源', '监考人员管理']}
      title="监考人员管理"
      badge={`${total} 名教职工`}
      subtitle="维护教师档案与个人排班。累计监考按本校各已发布考务任务的最近一次成功排班汇总，不随顶部学期筛选，不含考试计划的新版本排班。"
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
        <MiniKPI label="在职教职工" value={total - onLeave} unit="人" accent="#4F46E5" icon={IconUsers} />
        <MiniKPI label="班主任" value={homerooms} unit="人" accent="#9333EA" icon={IconCheck} sub={homeroomGradeCount ? `覆盖 ${homeroomGradeCount} 个年级` : '暂无年级数据'} />
        <MiniKPI label="累计监考人次" value={totalSup} unit="人次" accent="#059669" icon={IconCalendar} sub={`人均 ${total ? (totalSup/total).toFixed(1) : '0.0'} 场`} />
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
      />

      {loadingTeachers ? (
        <MasterDataState title="正在加载教师数据" sub="正在从后端读取当前租户的教师、任教学科和班主任信息。" />
      ) : teacherError ? (
        <EmptyState
          title="教师数据加载失败"
          sub={teacherError}
          action={<Button variant="primary" onClick={loadTeachers}>重新加载</Button>}
        />
      ) : teachers.length === 0 && activeFilterCount === 0 ? (
        <EmptyState
          glyph="activeStaff"
          title="尚未添加教师"
          sub="当前学校尚未添加教师。可点击“新增教师”录入档案，或使用页面上方的“批量导入”导入名单。"
          action={<Button variant="primary" icon={IconPlus} onClick={() => openTeacherForm(null)}>新增教师</Button>}
        />
      ) : filteredTeachers.length > 0 ? (
        <DataTable
          columns={columns}
          rows={filteredTeachers}
          onRowClick={(row) => setSelectedTeacher(row)}
        />
      ) : (
        <EmptyState
          title="没有符合条件的教职工"
          sub={`当前筛选条件下没有匹配结果${q ? `，关键词「${search.trim()}」` : ''}。可调整年级、科目或状态，或清空筛选重新查看。`}
          action={<Button variant="primary" onClick={resetAll}>清空筛选</Button>}
        />
      )}

      {!loadingTeachers && !teacherError && teachers.length > 0 && <TeacherScheduleTimeline teacher={selectedTeacher} gradeAverage={selectedGradeAverage} />}
    </PageShell>
  );
}

window.PeoplePage = PeoplePage;
