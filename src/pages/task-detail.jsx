// 任务详情子页 — 4 tab: 概览 / 监考人员 / 排班表 / 操作记录

function taskAllowance(rows, unitRate) {
  if (!Array.isArray(rows) || unitRate === null || unitRate === undefined || unitRate === '' || !Number.isFinite(Number(unitRate)) || Number(unitRate) < 0) return null;
  const seconds = (time) => {
    const match = /^(\d{2}):(\d{2})(?::(\d{2}))?$/.exec(String(time || ''));
    if (!match || Number(match[1]) > 23 || Number(match[2]) > 59 || Number(match[3] || 0) > 59) return null;
    return Number(match[1]) * 3600 + Number(match[2]) * 60 + Number(match[3] || 0);
  };
  let total = 0;
  for (const row of rows) {
    const start = seconds(row.startTime), end = seconds(row.endTime);
    if (start === null || end === null || end <= start) return null;
    total += end - start;
  }
  return Math.round(total / 3600 * Number(unitRate) * 100) / 100;
}

function mapBackendDetail(task, assignments, unitRate = null) {
  const rows = assignments?.rows || [];
  const teacherMap = new Map();
  rows.forEach((row) => {
    const key = row.teacherId || row.teacherName;
    // code 带上真实工号（AssignmentRow.teacherNo）——此前监考人员表的「工号」列
    // 用 'T'+(2025000+i) 顺序编造，看起来可信实则全是假的。
    const prev = teacherMap.get(key) || { name: row.teacherName || '未知教师', grade: row.grade || '', code: row.teacherNo || '', count: 0 };
    prev.count += 1;
    teacherMap.set(key, prev);
  });
  return {
    id: task.id,
    name: task.name || task.taskNo || '后端任务',
    status: task.status || assignments?.status || '未提供',
    source: 'backend',
    createdAt: task.createdAt,
    updatedAt: task.updatedAt,
    publishedAt: task.publishedAt,
    progress: Number.isFinite(task.latestJob?.progress) ? task.latestJob.progress : null,
    exports: assignments?.downloads || {},
    stats: {
      exams: new Set(rows.map((row) => row.examId)).size,
      teachersUsed: teacherMap.size,
      rooms: new Set(rows.map((row) => row.roomId || row.roomCode)).size,
      supervisions: rows.length,
      students: null, // AssignmentRow does not contain candidate counts.
      allowance: taskAllowance(rows, unitRate),
      avg: teacherMap.size ? (rows.length / teacherMap.size).toFixed(1) : '0',
      std: 'backend',
      spread: '',
    },
    schedule: rows.map((row) => ({
      date: row.examDate,
      period: `${row.startTime || ''}-${row.endTime || ''}`,
      subject: row.subjectName,
      room: row.roomCode || row.roomName,
      building: row.roomName,
      supervisors: [row.teacherName || '未知教师'],
      students: null,
    })),
    teacherLoad: Array.from(teacherMap.values()),
  };
}

function TaskDetailPage() {
  const [tab, setTab] = React.useState('overview');

  // 按选定 id 读取任务；本地预览保持显式隔离，不作为 API 失败回退。
  const detailId = (() => { try { return localStorage.getItem('jkxt:taskDetailId'); } catch { return null; } })();
  const [backendDetail, setBackendDetail] = React.useState(null);
  const [backendAssignments, setBackendAssignments] = React.useState(null);
  const [loadState, setLoadState] = React.useState({ status: 'loading', error: '' });
  const [reloadKey, setReloadKey] = React.useState(0);
  const [unitRate, setUnitRate] = React.useState(null);
  const [busy, setBusy] = React.useState('');
  const [publication, setPublication] = React.useState(null);
  const [publicationError, setPublicationError] = React.useState('');
  const actionRef = React.useRef(false);
  const localPreview = detailId && !/^[0-9a-f]{8}-[0-9a-f-]{27}$/i.test(detailId) ? window.JKXT?.getTask?.(detailId) : null;
  React.useEffect(() => {
    let alive = true;
    setLoadState({ status: 'loading', error: '' });
    setUnitRate(null);
    if (localPreview) { setLoadState({ status: 'local', error: '' }); return undefined; }
    if (!detailId) { setLoadState({ status: 'empty', error: '' }); return undefined; }
    if (!window.api) { setLoadState({ status: 'error', error: '任务 API 未就绪' }); return undefined; }
    Promise.all([
      window.api.get(`/exam-tasks/${detailId}`),
      window.api.get(`/exam-tasks/${detailId}/assignments`),
    ]).then(([task, assignments]) => {
      if (!alive) return;
      if (!task?.id || !Array.isArray(assignments?.rows)) throw new Error('任务接口返回格式不完整，请重试或联系管理员');
      setBackendDetail(task);
      setBackendAssignments(assignments);
      setLoadState({ status: 'ready', error: '' });
    }).catch((error) => {
      if (!alive) return;
      setBackendDetail(null);
      setBackendAssignments(null);
      setLoadState({ status: error?.status === 403 ? 'permission' : 'error', error: error?.message || '任务读取失败' });
    });
    window.api.get('/org/settings').then((settings) => {
      if (alive) setUnitRate(settings?.allowanceUnit ?? null);
    }).catch(() => { if (alive) setUnitRate(null); });
    return () => { alive = false; };
  }, [detailId, reloadKey]);
  React.useEffect(() => {
    let alive = true;
    setPublication(null); setPublicationError('');
    if (backendAssignments?.runId) {
      window.api.legacyPublication.get(detailId).then(value => {
        if (alive) setPublication(value);
      }).catch(error => { if (alive) setPublicationError(error.message || '审批状态读取失败，请刷新核对'); });
    }
    return () => { alive = false; };
  }, [detailId, backendAssignments?.runId, reloadKey]);
  const backendTask = backendDetail ? mapBackendDetail(backendDetail, backendAssignments, unitRate) : null;
  const realTask = backendTask || localPreview;
  const isBackend = !!backendTask;
  const isReal = !!realTask;

  // 拿不到真实任务时如实说明，绝不拿编造数据充数。
  //
  // 此前这里会整页渲染 __TD_MOCK/__TD_SUPERVISORS/__TD_SCHEDULE/__TD_LOG —— 一份看起来完全可信的
  // 假任务（编号 T-2025-016、负责人「李慧君」、480 名学生、4800 元津贴、进度 72%），外加伪造的
  // AI 操作记录（「AI 自动修复 1 处冲突」「评分 96/100 · 0 冲突」）和「已在站内通知 20 位监考教师」
  // 的假回执。更危险的是上面 fetch 的 .catch 也降级到这条分支：接口一出错，用户看到的不是错误，
  // 而是一份像真的一样的排班方案——据此决策会直接出事。
  if (!isReal || loadState.status === 'loading') {
    return (
      <PageShell
        crumbs={[orgName(), '排班作业', '任务总览', '任务详情']}
        title="任务详情"
        subtitle={<span>{loadState.status === 'loading' ? '正在读取任务与排班结果' : '请检查任务及服务状态'}</span>}
      >
        <EmptyState
          title={loadState.status === 'loading' ? '正在读取任务…' : loadState.status === 'permission' ? '无权查看此任务' : detailId ? '无法读取该任务的数据' : '尚未选择任务'}
          sub={detailId
            ? loadState.error || '读取期间不会展示旧任务或示例数据。'
            : '请先在「任务总览」中选择一个任务查看详情，或从「一键排监考」生成新的排班方案。'}
          action={
            <div className="flex items-center gap-2">
              <Button icon={IconList} onClick={() => window.navigateTo('tasks')}>返回任务总览</Button>
              {detailId && loadState.status !== 'loading' ? <Button variant="primary" onClick={() => setReloadKey((value) => value + 1)}>重新读取</Button> : <Button variant="primary" onClick={() => window.navigateTo('quick')}>一键排监考</Button>}
            </div>
          }
        />
      </PageShell>
    );
  }

  const t = isReal ? (() => {
    const s = realTask.stats || {};
    const dates = Array.from(new Set((realTask.schedule || []).map((r) => r.date).filter(Boolean))).sort();
    const students = isBackend ? null : (realTask.schedule || []).every((row) => Number.isFinite(row.students)) ? (realTask.schedule || []).reduce((a, r) => a + r.students, 0) : null;
    return {
      id: realTask.id, name: realTask.name,
      type: isBackend ? '后端任务' : realTask.status === 'draft' ? '草稿' : '预览',
      typeColor: isBackend ? 'emerald' : realTask.status === 'draft' ? 'slate' : 'indigo',
      grade: '—', days: dates.length, exams: s.exams ?? 0, supervisors: s.teachersUsed ?? 0,
      status: realTask.status || 'preview', progress: realTask.progress ?? null,
      owner: '未提供', createdAt: realTask.createdAt, updatedAt: realTask.updatedAt || '未提供',
      startDate: dates[0] || '—', endDate: dates[dates.length - 1] || '—',
      classrooms: s.rooms || 0, students, allowance: isBackend ? s.allowance : null,
      std: s.std, avg: s.avg, spread: s.spread, supervisions: s.supervisions, source: realTask.source,
    };
  })() : null;

  // 不再编造：工号用真实 teacherNo（无则 —），去掉写死 'pending' 的假「响应状态」
  //（后端没有任何响应追踪数据，那台状态机除「待响应」外的分支永远不可达）。
  const supervisors = isReal
    ? (realTask.teacherLoad || []).map((tl) => ({ id: tl.code || '—', name: tl.name, grade: tl.grade || '—', subject: '—', sessions: tl.count }))
    : [];

  const scheduleRows = isReal
    ? (realTask.schedule || []).map((r) => ({ date: r.date, period: r.period, subject: r.subject, rooms: r.room + (r.building ? ` · ${r.building}` : ''), supervisors: (r.supervisors || []).join('、'), students: r.students }))
    : [];

  // This API exposes solver events, not tenant audit records. Never invent actors/events.
  const log = Array.isArray(backendDetail?.latestJob?.logs) ? backendDetail.latestJob.logs.map((entry) => ({
    at: entry.at || '未提供', action: entry.stage || entry.status || '执行事件', detail: entry.message || '',
    icon: IconList, color: 'var(--brand)',
  })) : [];
  const statusLabel = ({ draft: '草稿', validated: '已校验', scheduling: '排班中', scheduled: '已排班', published: '已发布', failed: '失败', infeasible: '无可行解' })[t.status] || t.status;
  const openEdit = () => window.JKXTTaskTools?.open({ id: t.id, __backend: isBackend }, 'edit');
  const exportLog = () => {
    if (!log.length) return;
    const csv = '\uFEFF' + [['时间', '阶段', '消息'], ...log.map((entry) => [entry.at, entry.action, entry.detail])].map((row) => row.map(window.JKXTTaskTools.csvCell).join(',')).join('\r\n');
    window.JKXTTaskTools.downloadBlob(new Blob([csv], { type: 'text/csv;charset=utf-8' }), '任务执行日志.csv');
  };

  const downloadReal = async (cardId, title) => {
    if (actionRef.current) return;
    if (isBackend) {
      const format = cardId === 'pdf' ? 'pdf' : 'xlsx';
      actionRef.current = true;
      setBusy('download');
      try {
        const response = await fetch(window.api.url(`/exam-tasks/${encodeURIComponent(realTask.id)}/export`, { format }), { credentials: 'include' });
        if (!response.ok) throw new Error(`导出失败（HTTP ${response.status}）`);
        const blob = await response.blob();
        if (!blob.size || /json|text\/html/i.test(blob.type)) throw new Error('导出接口未返回有效文件');
        window.JKXTTaskTools.downloadBlob(blob, `仅监考安排-未含考生座位名单-${title}.${format}`);
        window.ui?.toast?.('文件已生成', { kind: 'success', desc: '已交给浏览器下载。' });
      } catch (error) { window.ui?.toast?.('导出失败', { kind: 'error', desc: error.message }); }
      finally { actionRef.current = false; setBusy(''); }
      return;
    }
    if (isReal && window.JKXT) {
      const ok = window.JKXT.downloadOne({ rows: realTask.schedule, teachers: (realTask.teacherLoad || []).map((x) => ({ ...x })), stats: realTask.stats }, realTask, cardId, title);
      if (ok) window.ui?.toast?.(`已生成 ${title}.xlsx`, { kind: 'success', desc: '本地预览数据，未发布至服务器。' });
    }
  };

  const TABS = [
    { id: 'overview',    label: '概览',     icon: IconDashboard, count: null },
    { id: 'supervisors', label: '监考人员', icon: IconUsers,     count: t.supervisors },
    { id: 'schedule',    label: '排班表',   icon: IconCalendar,  count: t.exams },
    { id: 'log',         label: '执行记录', icon: IconArchive,   count: log.length },
  ];

  return (
    <PageShell
      crumbs={[orgName(), '排班作业', '任务总览', t.name]}
      title={t.name}
      badge={<span className="inline-flex items-center gap-1.5"><Tag color={t.typeColor}>{t.type}</Tag><Tag color={t.status === 'published' ? 'emerald' : 'slate'} dot>{statusLabel}</Tag></span>}
      subtitle={
        <span>编号 <span className="font-mono">{t.id}</span> · 负责人 <span className="font-semibold" style={{ color: 'var(--text-strong)' }}>{t.owner}</span> · {isBackend ? '数据来源 后端任务' : isReal ? `数据来源 ${t.source === 'upload' ? '上传 Excel' : '示例'}` : `最近更新 ${t.updatedAt}`}</span>
      }
      actions={
        <>
          <Button disabled={!!busy} icon={IconUpload} onClick={() => downloadReal('overall', '完整监考排班表')}>{busy === 'download' ? '导出中…' : '导出'}</Button>
          <Button disabled={!isBackend || !!busy} icon={IconEdit} onClick={openEdit}>修改排班</Button>
          <Button disabled={!isBackend || !!busy || t.status === 'published' || !publication?.sourceCurrent || !['APPROVED', 'PUBLISHED', 'EXECUTING'].includes(publication?.status)} variant="primary" icon={IconCheck} onClick={async () => {
            if (actionRef.current) return;
            const taskId = realTask?.id;
            if (!isBackend || !taskId) { window.ui.toast('该任务暂无后端排班,无法通知', { kind: 'warning', desc: '请先从「一键排监考」或「AI 策略监考」生成排班' }); return; }
            try {
              actionRef.current = true;
              setBusy('publish');
              const r = await window.api.post(`/tasks/${taskId}/publish`, {});
              if (r?.status !== 'published') throw new Error('发布接口未确认成功，请刷新核对任务与审批状态');
              window.ui.toast(r.alreadyPublished ? '该版本已发布' : '排班已发布', { kind: 'success', desc: `通知状态：${r.notificationStatus || '未提供'}，接收人数 ${r.recipientCount ?? '未提供'}。` });
              setReloadKey((value) => value + 1);
            } catch (e) {
              setPublication(null);
              setPublicationError(e.message || '发布失败，请刷新审批与排班状态');
              window.ui.toast('发布未完成', { kind: 'error', desc: e.message || '请稍后重试' });
            } finally { actionRef.current = false; setBusy(''); }
          }}>{t.status === 'published' ? '已发布' : busy === 'publish' ? '发布中…' : '发布并通知'}</Button>
        </>
      }
    >
      {/* Top KPI row */}
      <div className="grid grid-cols-2 lg:grid-cols-3 xl:grid-cols-6 gap-3">
        <MiniKPI label="考期"     value={t.days} unit="天" accent="#4F46E5" icon={IconCalendar} />
        <MiniKPI label="考试场次" value={t.exams} unit="场" accent="#0EA5E9" icon={IconList} />
        <MiniKPI label="监考人数" value={t.supervisors} unit="人" accent="#059669" icon={IconUsers} />
        <MiniKPI label="使用教室" value={t.classrooms} unit="间" accent="#D97706" icon={IconBuilding} />
        <MiniKPI label="参考学生" value={t.students == null ? '未提供' : t.students.toLocaleString()} unit={t.students == null ? '' : '人'} accent="#9333EA" icon={IconUsers} />
        <MiniKPI label="预计津贴" value={t.allowance == null ? '未提供' : `¥${t.allowance.toLocaleString()}`} sub={t.allowance == null ? '需完整时段与学校单价' : `按监考时长 × ${unitRate} 元/小时`} accent="#EF4444" icon={IconChart} />
      </div>

      {/* Server progress is not approval or publication status. */}
      {isBackend && <section className="rounded-xl p-5" aria-label="旧任务审批与发布" style={{ background: 'var(--surface-raised)', border: '1px solid var(--border-soft)' }}>
        <h2 className="text-[16px] font-semibold">仅监考安排，未含考生座位名单</h2>
        <p className="mt-2">提交当前排班后，须由另一位管理员在对应计划版本中审批。批准后再发布；任何排班或来源变化都需要重新送审。</p>
        <p className="mt-2" role="status">审批状态：{({ NOT_SUBMITTED: '尚未提交', PENDING_APPROVAL: '等待独立审批', APPROVED: '已批准，可发布', PUBLISHED: '统一计划已发布', EXECUTING: '执行中', REJECTED: '已驳回', REVIEW_REQUIRED: '需要重新送审' })[publication?.status] || (backendAssignments?.runId ? '正在读取或尚不可用' : '请先完成排班')}{publication?.revisionId && !publication.sourceCurrent ? '；来源已变化，请重新提交' : ''}</p>
        {publicationError && <p className="mt-2" role="alert">{publicationError}</p>}
        <div className="mt-3 flex flex-wrap gap-2">
          <Button disabled={!!busy || !backendAssignments?.runId || !Number.isInteger(backendAssignments?.stats?.revisionCursor) || !['scheduled', 'published'].includes(t.status) || (publication?.sourceCurrent && ['PENDING_APPROVAL', 'APPROVED', 'PUBLISHED', 'EXECUTING'].includes(publication.status))} onClick={async () => {
            if (actionRef.current) return;
            actionRef.current = true; setBusy('approval'); setPublicationError('');
            try {
              const cursor = backendAssignments?.stats?.revisionCursor;
              if (!Number.isInteger(cursor) || cursor < 0) throw new Error('缺少有效排班修订号，请刷新后再提交审批');
              const submitted = await window.api.legacyPublication.submitApproval(t.id, backendAssignments.runId, cursor);
              if (!submitted?.planId || !submitted?.revisionId || !submitted?.status) throw new Error('接口未返回审批版本，请刷新核对，尚未确认发布');
              setPublication(submitted);
            }
            catch (error) { setPublicationError(error.message || '提交审批失败，未发布'); }
            finally { actionRef.current = false; setBusy(''); }
          }}>{busy === 'approval' ? '正在提交…' : '提交当前排班审批'}</Button>
          <Button disabled={!!busy || !publication?.revisionId} onClick={() => {
            if (!window.JKXTPlanNavigation?.open({ planId: publication.planId, revisionId: publication.revisionId, returnRoute: 'task-detail', returnLabel: '返回旧任务审批与发布' })) setPublicationError('计划审批页面未就绪，请刷新后重试');
          }}>打开对应版本审批</Button>
          <Button disabled={!!busy} onClick={() => setReloadKey(value => value + 1)}>刷新审批状态</Button>
        </div>
      </section>}
      <div className="rounded-xl p-5" style={{ background: 'var(--surface-raised)', border: '1px solid var(--border-soft)' }}>
        <div className="flex items-center justify-between gap-4 flex-wrap">
          <div><h2 className="text-[16px] font-semibold">任务状态：{statusLabel}</h2><p className="mt-2 text-[13px]" style={{ color: 'var(--text-soft)' }}>最近更新：{t.updatedAt}。求解进度不代表已审批或已通知。</p></div>
          <div className="text-[14px]">求解进度：{t.progress == null ? '未提供' : `${t.progress}%`}</div>
        </div>
      </div>

      {/* Tabs */}
      <div className="task-detail-tabs bg-white rounded-xl p-1 flex items-center gap-1 max-w-full overflow-x-auto"
        style={{ border: '1px solid var(--border-soft)', boxShadow: 'var(--elevation-1)' }}>
        {TABS.map((tb) => {
          const active = tab === tb.id;
          return (
            <button key={tb.id} onClick={() => setTab(tb.id)}
              className="h-9 px-4 rounded-lg inline-flex items-center gap-2 transition-all shrink-0"
              style={{
                background: active ? 'var(--brand-soft)' : 'transparent',
                color:      active ? 'var(--brand)' : 'var(--text-medium)',
                fontWeight: active ? 600 : 500,
                fontSize: 13,
              }}>
              <tb.icon size={14} strokeWidth={active ? 2.25 : 1.75} />
              {tb.label}
              {tb.count != null && (
                <span className="font-mono text-[10.5px] px-1.5 py-0.5 rounded"
                  style={{ background: active ? '#fff' : '#F1F5F9', color: active ? 'var(--brand)' : 'var(--text-soft)' }}>
                  {tb.count}
                </span>
              )}
            </button>
          );
        })}
      </div>

      {tab === 'overview' && (
        <div className="grid grid-cols-1 gap-5 xl:grid-cols-[minmax(0,1fr)_360px]">
          <div className="bg-white rounded-2xl p-5" style={{ border: '1px solid var(--border-soft)', boxShadow: 'var(--elevation-1)' }}>
            <SectionTitle title="任务摘要" sub="根据当前读取的排班记录汇总，不是 AI 评审结论" />
            <div className="rounded-lg p-4 text-[14px] leading-relaxed" style={{ background: 'var(--surface-muted)', color: 'var(--text-medium)' }}>
              本任务为<strong style={{ color: 'var(--text-strong)' }}>{realTask.name}</strong>，当前读取到 <strong>{t.exams} 场考试</strong>、<strong>{t.classrooms} 间考场</strong>、<strong>{t.supervisions} 人次监考</strong>，涉及 <strong>{t.supervisors} 名教师</strong>。
              <p className="mt-3">{isBackend ? '来源：服务器任务与监考分配接口。请结合冲突检查及审批记录复核，本文不判断全部硬约束已满足。' : '来源：浏览器本地预览。未作为正式任务保存或发布至服务器。'}</p>
            </div>
            <SectionTitle title="关键时间" sub="仅显示已提供的任务时间，不推测审批和通知状态" />
            <dl className="space-y-3 text-[14px]">
              {[['任务创建', realTask.createdAt], ['最近更新', realTask.updatedAt], ['发布记录', realTask.publishedAt]].map(([label, value]) => (
                <div key={label} className="flex justify-between gap-3 flex-wrap"><dt style={{ color: 'var(--text-medium)' }}>{label}</dt><dd>{value || '未提供'}</dd></div>
              ))}
            </dl>
          </div>

          <div className="space-y-4">
            <div className="bg-white rounded-2xl p-5" style={{ border: '1px solid var(--border-soft)', boxShadow: 'var(--elevation-1)' }}>
              <SectionTitle title="文件下载" />
              {(isBackend ? [
                { l: '完整监考排班表', s: 'XLSX', c: '#4F46E5', cid: 'overall' },
                { l: '监考排班打印版', s: 'PDF', c: '#059669', cid: 'pdf' },
              ] : [
                { l: '完整监考排班表', s: 'XLSX', c: '#4F46E5', cid: 'overall' },
                { l: '按教师视图',     s: 'XLSX', c: '#059669', cid: 'teacher' },
                { l: '按考场视图',     s: 'XLSX', c: '#D97706', cid: 'room' },
                { l: '按考试视图',     s: 'XLSX', c: '#9333EA', cid: 'exam' },
              ]).map((f) => (
                <button key={f.l} disabled={!!busy} onClick={() => downloadReal(f.cid, f.l)}
                  className="w-full mt-2 rounded-lg p-3 flex items-center gap-3 transition-colors hover:bg-slate-50"
                  style={{ border: '1px solid var(--border-soft)' }}>
                  <div className="w-9 h-11 rounded flex items-end justify-center pb-1" style={{ background: '#fff', border: `1px solid ${f.c}55` }}>
                    <span className="font-mono text-[8px] font-bold" style={{ color: f.c }}>{f.s}</span>
                  </div>
                  <div className="flex-1 text-left">
                    <div className="text-[12.5px] font-medium" style={{ color: 'var(--text-strong)' }}>{f.l}</div>
                    <div className="text-[10.5px] mt-0.5 font-mono" style={{ color: isReal ? '#047857' : 'var(--text-soft)' }}>{isBackend ? '后端导出文件 · xlsx/pdf' : isReal ? '本地生成 · 可用 Excel 打开' : f.s}</div>
                  </div>
                  <IconArrowDown size={14} className="text-slate-400" />
                </button>
              ))}
            </div>

            <div className="rounded-2xl p-5 text-white relative overflow-hidden"
              style={{ background: 'linear-gradient(135deg, #9333EA 0%, #4F46E5 100%)', boxShadow: '0 12px 32px rgba(147,51,234,.32)' }}>
              <div className="font-mono text-[10px] uppercase tracking-wider opacity-80">质量指标</div>
              <div className="text-[14px] font-semibold mt-1">排班结果实测</div>
              <div className="text-[11.5px] opacity-80 mt-2">以下数据来自当前任务统计</div>
              <div className="mt-3 space-y-1 text-[11px]">
                {[
                  { l: '工作量标准差', v: t.std === 'backend' || t.std === '' || t.std == null ? '—' : t.std },
                  { l: '最大场次差', v: t.spread === 'backend' || t.spread === '' || t.spread == null ? '—' : t.spread },
                  { l: '监考总人次', v: t.supervisions ?? 0 },
                ].map((r) => (
                  <div key={r.l} className="flex items-center justify-between">
                    <span className="opacity-70">{r.l}</span>
                    <span className="font-mono font-semibold">{r.v}</span>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </div>
      )}

      {tab === 'supervisors' && (
        <DataTable
          columns={[
            { key: 'id',     label: '工号', width: 100, mono: true, render: (r) => <span className="font-mono text-[12px]" style={{ color: 'var(--text-soft)' }}>{r.id}</span> },
            { key: 'name',   label: '姓名', width: 150,
              render: (r) => (
                <div className="inline-flex items-center gap-2"><AvatarChip name={r.name} size={26} />
                  <span className="font-medium" style={{ color: 'var(--text-strong)' }}>{r.name}</span>
                </div>
              ),
            },
            { key: 'grade',   label: '监考年级', width: 100, render: (r) => <Tag color="indigo">{r.grade}</Tag> },
            { key: 'subject', label: '任教科目', width: 100, render: (r) => <Tag color="slate">{r.subject}</Tag> },
            { key: 'sessions', label: '监考场次', width: 100, align: 'center', mono: true, render: (r) => <span className="font-semibold" style={{ color: 'var(--text-strong)' }}>{r.sessions}</span> },
            // 「响应状态」列已删：后端没有任何响应追踪数据，原列写死 pending，
            // 「已确认/已拒绝」分支永远不可达——一台只亮一个灯的假状态机。
            { key: 'actions', label: '操作', width: 160, align: 'right',
              render: (r) => (
                <div className="inline-flex items-center gap-1">
                  <button className="text-[12px] font-medium px-1.5 hover:underline" style={{ color: 'var(--text-medium)' }}
                    disabled={!isBackend || !!busy} onClick={openEdit}>打开调度</button>
                </div>
              ),
            },
          ]}
          rows={supervisors}
        />
      )}

      {tab === 'schedule' && (
        <DataTable
          columns={[
            { key: 'date',     label: '日期',     width: 130, mono: true, render: (r) => <span className="font-mono" style={{ color: 'var(--text-strong)' }}>{r.date}</span> },
            { key: 'period',   label: '时段',     width: 130, mono: true, render: (r) => <span className="font-mono font-semibold" style={{ color: 'var(--brand)' }}>{r.period}</span> },
            { key: 'subject',  label: '科目',     width: 160, render: (r) => <Tag color="indigo">{r.subject}</Tag> },
            { key: 'rooms',    label: '考场',     width: 130, mono: true, render: (r) => <span className="font-mono text-[12px]" style={{ color: 'var(--text-medium)' }}>{r.rooms}</span> },
            { key: 'supervisors', label: '监考人员', wrap: true,
              render: (r) => <span className="text-[12.5px]" style={{ color: 'var(--text-medium)' }}>{r.supervisors}</span> },
            { key: 'students', label: '考生数', width: 90, align: 'center', mono: true, render: (r) => <span>{r.students ?? '未提供'}</span> },
          ]}
          rows={scheduleRows}
        />
      )}

      {tab === 'log' && (
        <div className="bg-white rounded-2xl p-5" style={{ border: '1px solid var(--border-soft)', boxShadow: 'var(--elevation-1)' }}>
          <SectionTitle title="执行记录" sub="来源：最近一次求解任务返回的日志；不等同于操作者审计" right={<Button disabled={!log.length} size="sm" variant="ghost" icon={IconUpload} onClick={exportLog}>导出执行日志</Button>} />
          <p className="mb-4 text-[13px]" style={{ color: 'var(--text-soft)' }}>当前任务接口尚未提供操作者审计记录，不能在此查看或导出审批、人工修改等审计事件。</p>
          {log.length ? <ul className="divide-y" style={{ borderColor: 'var(--border-soft)' }}>{log.map((entry, index) => (
            <li key={index} className="py-3"><div className="flex justify-between gap-3 flex-wrap"><strong>{entry.action}</strong><span className="text-[12px]" style={{ color: 'var(--text-soft)' }}>{entry.at}</span></div><p className="mt-1 text-[14px]">{entry.detail || '未提供消息'}</p></li>
          ))}</ul> : <EmptyState title="没有可展示的执行日志" sub="服务端未返回执行事件；此处不会补充示例或推测记录。" />}
        </div>
      )}
    </PageShell>
  );
}

window.TaskDetailPage = TaskDetailPage;
