// 人工调度：选择岗位和教师只生成预览，明确确认后才执行真实改派。

function useScheduleEditData() {
  const loadCursor = React.useRef(0);
  const [state, setState] = React.useState({
    loading: true,
    error: '',
    taskId: null,
    task: null,
    assignments: null,
    teachers: [],
  });

  const load = React.useCallback(async (preferredTaskId) => {
    const cursor = ++loadCursor.current;
    setState((s) => ({ ...s, loading: true, error: '', task: null, assignments: null, teachers: [] }));
    try {
      let taskId = preferredTaskId;
      if (!taskId) {
        try { taskId = localStorage.getItem('jkxt:taskDetailId'); } catch {}
      }
      if (!taskId) {
        if (cursor === loadCursor.current) setState({ loading: false, error: '', taskId: null, task: null, assignments: null, teachers: [] });
        return;
      }
      if (!window.api?.get) throw new Error('任务服务尚未连接，请稍后重试');
      const [assignments, teachers, task] = await Promise.all([
        window.api.get(`/exam-tasks/${taskId}/assignments`),
        window.api.get('/teachers', { status: 'active' }),
        window.api.get(`/exam-tasks/${taskId}`),
      ]);
      if (!Array.isArray(assignments?.rows) || !Array.isArray(teachers)) throw new Error('排班或教师接口返回格式不正确');
      if (task?.id !== taskId || (assignments.taskId != null && assignments.taskId !== taskId)) throw new Error('任务详情与当前选择不一致，请重新选择任务');
      if (cursor !== loadCursor.current) return;
      try { localStorage.setItem('jkxt:taskDetailId', taskId); } catch {}
      setState({
        loading: false,
        error: '',
        taskId,
        task,
        assignments,
        teachers: Array.isArray(teachers) ? teachers : [],
      });
      return true;
    } catch (error) {
      if (cursor !== loadCursor.current) return;
      setState((s) => ({
        ...s,
        loading: false,
        error: error?.message || '加载排班数据失败',
      }));
    }
  }, []);

  React.useEffect(() => { load(); return () => { loadCursor.current += 1; }; }, [load]);
  return { ...state, reload: load };
}

async function requestScheduleReplacement(api, taskId, row, teacherId, revisionCursor, reason, isCurrent = () => true) {
  if (!taskId || !row?.assignmentId || !teacherId) throw new Error('缺少任务、监考岗位或候选教师标识');
  if (!Number.isInteger(revisionCursor) || revisionCursor < 0) throw new Error('缺少有效修订号，请刷新排班后重试');
  const base = `/tasks/${encodeURIComponent(taskId)}/schedule`;
  if (!isCurrent()) throw new Error('改派上下文已变化，请重新选择岗位');
  const check = await api.post(`${base}/check-conflicts`, { assignmentId: row.assignmentId, teacherId });
  if (check?.allowed !== true) return { check: check || { allowed: false, conflicts: [] }, patched: null };
  if (!isCurrent()) throw new Error('改派上下文已变化，未继续提交，请重新选择岗位');
  const patched = await api.patch(base, { assignmentId: row.assignmentId, teacherId, expectedRevision: revisionCursor, reason });
  if (patched?.assignmentId !== row.assignmentId || patched?.teacherId !== teacherId) throw new Error('服务端未返回匹配的改派结果，请刷新核对，勿重复提交');
  return { check, patched };
}

function slotKey(row) {
  return `${row.examDate}|${row.startTime}|${row.endTime}`;
}

function cellKey(row) {
  return `${slotKey(row)}|${row.roomId || row.roomCode}|${row.examId}`;
}

function groupSchedule(rows) {
  const slots = [];
  const rooms = [];
  const cells = new Map();
  const seenSlots = new Set();
  const seenRooms = new Set();

  (rows || []).forEach((row) => {
    const sk = slotKey(row);
    if (!seenSlots.has(sk)) {
      seenSlots.add(sk);
      slots.push({ key: sk, examDate: row.examDate, startTime: row.startTime, endTime: row.endTime });
    }

    const rk = row.roomId || row.roomCode;
    if (!seenRooms.has(rk)) {
      seenRooms.add(rk);
      rooms.push({ key: rk, roomId: row.roomId, roomCode: row.roomCode, roomName: row.roomName });
    }

    const ck = cellKey(row);
    if (!cells.has(ck)) {
      cells.set(ck, {
        key: ck,
        roomId: row.roomId,
        examId: row.examId,
        slotKey: sk,
        subjectName: row.subjectName,
        grade: row.grade,
        roomCode: row.roomCode,
        roomName: row.roomName,
        rows: [],
      });
    }
    cells.get(ck).rows.push(row);
  });

  slots.sort((a, b) => `${a.examDate} ${a.startTime}`.localeCompare(`${b.examDate} ${b.startTime}`));
  rooms.sort((a, b) => String(a.roomCode || '').localeCompare(String(b.roomCode || '')));
  return { slots, rooms, cells };
}

function formatSlot(slot) {
  return {
    day: slot.examDate || '-',
    period: `${String(slot.startTime || '').slice(0, 5)} - ${String(slot.endTime || '').slice(0, 5)}`,
  };
}

function ConflictBanner({ response }) {
  const conflicts = Array.isArray(response?.conflicts) ? response.conflicts : [];
  if (!conflicts.length) return null;
  return <div className="md-conflicts" data-testid="p9-conflict-banner" role="alert">
    <strong>本次改派未通过校验</strong>
    <ul>{conflicts.map((item, index) => <li key={item.code + '-' + index}>{item.message || '服务未提供冲突说明'}
      {item.examDate && <span>（{item.examDate} {String(item.startTime || '').slice(0, 5)}）</span>}
    </li>)}</ul>
  </div>;
}

function manualRoleLabel(role) {
  return ({ main: '主监考', assistant: '副监考', reserve: '备用监考' })[role] || role || '岗位未提供';
}

function TeacherCandidateRail({ teachers, draggedTeacherId, onDragStart, onDragEnd, onSelectTeacher, onDetails, disabled, query = '', onSearch }) {
  return <section className="md-candidates" data-testid="p9-candidate-rail" aria-label="选择替补教师">
    <h3>待核对候选</h3>
    <p className="md-candidate-guidance"><IconHelp size={18} />选择候选后预览差异</p>
    {onSearch && <label className="md-field"><span className="sr-only">搜索教师</span>
      <input data-testid="md-candidate-search" type="search" value={query} onChange={event => onSearch(event.target.value)} placeholder="教师姓名或工号" disabled={disabled} />
    </label>}
    <div className="md-candidate-list">
      {teachers.length ? teachers.map(teacher => {
        const teacherId = teacher.uuid || teacher.id;
        const active = teacherId === draggedTeacherId;
        return <article key={teacherId} data-testid="p9-candidate-teacher" className={'md-candidate' + (active ? ' is-selected' : '')}>
          <div className="md-candidate-identity"><strong>{teacher.name || '姓名未提供'}</strong><span className="md-meta">{teacher.teacherNo || '工号未提供'}</span></div>
          <button type="button" data-teacher-id={teacherId} className="md-candidate-select" draggable={!disabled} disabled={disabled} aria-pressed={active}
            aria-label={'选择候选教师 ' + (teacher.name || '姓名未提供') + '，工号 ' + (teacher.teacherNo || '未提供')}
            onClick={() => onSelectTeacher(teacher)} onDragStart={event => onDragStart?.(event, teacher)} onDragEnd={onDragEnd}>选择</button>
          {onDetails && <button className="md-candidate-details" type="button" disabled={disabled} onClick={() => onDetails(teacher)} aria-label={`查看${teacher.name || '教师'}的资料`}>查看资料</button>}
          <span className="md-meta md-candidate-count">累计监考 {Number.isFinite(teacher.supCount) ? teacher.supCount : '未提供'}</span>
        </article>;
      }) : <p className="md-muted">没有匹配的候选教师，请调整搜索条件。</p>}
    </div>
    <p className="md-candidate-warning"><IconAlert size={20} />请核对跨任务安排、资格与转场要求</p>
  </section>;
}

function AssignmentDropSlot({ row, pendingAssignmentId, selectedAssignmentId, onDropTeacher, onAssignSelected, selectedTeacher, disabled }) {
  const active = selectedAssignmentId === row.assignmentId || pendingAssignmentId === row.assignmentId;
  return <button type="button" data-testid="p9-assignment-slot" data-assignment-id={row.assignmentId}
    onDragOver={event => { if (!disabled) { event.preventDefault(); event.dataTransfer.dropEffect = 'move'; } }}
    onDrop={event => onDropTeacher?.(event, row)} onClick={event => onAssignSelected(row, event)}
    disabled={disabled} aria-pressed={active}
    aria-label={(active ? '已选择，' : '选择岗位，') + (row.roomName || row.roomCode || '') + '，' + manualRoleLabel(row.role) + '，当前监考 ' + row.teacherName + (selectedTeacher ? '，预览改派给 ' + selectedTeacher.name : '')}
    className={'md-assignment' + (active ? ' is-selected' : '')}>
    <span className="md-meta">{manualRoleLabel(row.role)}</span>
    <strong>{row.teacherName || '教师姓名未提供'}</strong>
    {active && <span className="md-selection-label">已选择此岗位</span>}
  </button>;
}

function ScheduleCell({ cell, cells, pendingAssignmentId, selectedAssignmentId, onDropTeacher, onAssignSelected, selectedTeacher, disabled }) {
  const entries = cells || (cell ? [cell] : []);
  if (!entries.length) return <td className="md-empty-cell"><span>本时段无安排</span></td>;
  return <td className="md-schedule-cell">
    {entries.map(entry => <div className="md-exam-cell" key={entry.key}><div className="md-subject">{entry.grade ? entry.grade + ' · ' : ''}{entry.subjectName || '科目未提供'}</div>
    <div className="md-assignment-list">{entry.rows.map(row => <AssignmentDropSlot key={row.assignmentId}
      row={row} pendingAssignmentId={pendingAssignmentId} selectedAssignmentId={selectedAssignmentId}
      onDropTeacher={onDropTeacher} onAssignSelected={onAssignSelected} selectedTeacher={selectedTeacher} disabled={disabled} />)}</div></div>)}
  </td>;
}

function manualVisibleRows(rows, query, date) {
  const search = String(query || '').trim().toLowerCase();
  return rows.filter(row => (!date || row.examDate === date) && (!search || [row.roomCode, row.roomName, row.subjectName, row.teacherName].some(value => String(value || '').toLowerCase().includes(search))));
}

function ManualScheduleGrid({ grouped, orientation, draft, onDropTeacher, selectSlot, disabled }) {
  const columns = orientation === 'room' ? grouped.rooms : grouped.slots;
  const lines = orientation === 'room' ? grouped.slots : grouped.rooms;
  const label = (item, isRoom) => isRoom ? <><strong>{item.roomCode || '编号未提供'}</strong><span>{item.roomName || '考场名称未提供'}</span></> : <><span>{formatSlot(item).day}</span><strong>{formatSlot(item).period}</strong></>;
  return <table className="md-table" data-testid={`md-grid-${orientation}`}><thead><tr><th scope="col">{orientation === 'room' ? '时段 / 考场' : '考场 / 时段'}</th>{columns.map(column => <th scope="col" key={column.key}>{label(column, orientation === 'room')}</th>)}</tr></thead><tbody>{lines.map(line => <tr key={line.key}><th scope="row">{label(line, orientation !== 'room')}</th>{columns.map(column => {
    const room = orientation === 'room' ? column : line;
    const slot = orientation === 'room' ? line : column;
    const cells = Array.from(grouped.cells.values()).filter(item => (item.roomId || item.roomCode) === room.key && item.slotKey === slot.key);
    return <ScheduleCell key={`${room.key}:${slot.key}`} cells={cells} selectedAssignmentId={draft?.row?.assignmentId} selectedTeacher={draft?.teacher} onDropTeacher={onDropTeacher} onAssignSelected={selectSlot} disabled={disabled} />;
  })}</tr>)}</tbody></table>;
}

function ManualReplacementDialog({ title, busy, onClose, returnFocusRef, children, footer, legacyFooter }) {
  const Dialog = window.BlueprintDialog;
  // Preserve isolated legacy contract runners that intentionally load only edit.jsx.
  if (!Dialog) return <section data-testid="md-preview">{children}{legacyFooter || footer}</section>;
  return <Dialog title={title} busy={busy} onClose={onClose} returnFocusRef={returnFocusRef} className="manual-v5-dialog" footer={footer}><div data-testid="md-preview">{children}</div></Dialog>;
}

function EditSchedulePage() {
  const { loading, error, taskId, task, assignments, teachers, reload } = useScheduleEditData();
  const [draft, setDraft] = React.useState(null);
  const [reason, setReason] = React.useState('');
  const [candidateQuery, setCandidateQuery] = React.useState('');
  const [q, setQ] = React.useState('');
  const [dateFilter, setDateFilter] = React.useState('');
  const [orientation, setOrientation] = React.useState('time');
  const [teacherInfo, setTeacherInfo] = React.useState(null);
  const [saving, setSaving] = React.useState(false);
  const [actionError, setActionError] = React.useState('');
  const [needsRefresh, setNeedsRefresh] = React.useState(false);
  const [conflictResponse, setConflictResponse] = React.useState(null);
  const actionRef = React.useRef(false);
  const refreshRequiredRef = React.useRef(false);
  const draftRef = React.useRef(null);
  const generationRef = React.useRef(0);
  const mountedRef = React.useRef(true);
  const selectedButtonRef = React.useRef(null);
  const contextRef = React.useRef(null);
  const rows = assignments?.rows || [];
  const revisionCursor = Number.isInteger(assignments?.stats?.revisionCursor) ? assignments.stats.revisionCursor : null;
  contextRef.current = { taskId, runId: assignments?.runId, revision: revisionCursor, loading, error };
  const setRefreshRequired = value => { refreshRequiredRef.current = value; setNeedsRefresh(value); };
  const setPreview = next => { draftRef.current = next; setDraft(next); };
  const clearPreview = React.useCallback(() => {
    generationRef.current += 1;
    draftRef.current = null;
    setDraft(null); setReason(''); setCandidateQuery(''); setActionError(''); setConflictResponse(null);setTeacherInfo(null);
  }, []);
  React.useEffect(() => {
    clearPreview();
    setRefreshRequired(false);
  }, [taskId, assignments?.runId, revisionCursor, clearPreview]);
  React.useEffect(() => {setDateFilter('');}, [taskId]);
  React.useEffect(() => {
    mountedRef.current = true;
    const onKey = event => {
      if (event.key === 'Escape' && !event.defaultPrevented && !actionRef.current) {
        event.preventDefault(); clearPreview(); selectedButtonRef.current?.focus?.();
      }
    };
    const onStorage = event => {
      if (event.key === 'jkxt:taskDetailId' || event.key === null) { clearPreview(); reload(); }
    };
    window.addEventListener('keydown', onKey);
    window.addEventListener('storage', onStorage);
    return () => {
      mountedRef.current = false; generationRef.current += 1; draftRef.current = null;
      window.removeEventListener('keydown', onKey); window.removeEventListener('storage', onStorage);
    };
  }, [clearPreview, reload]);

  const controlsDisabled = !taskId || loading || !!error || saving || revisionCursor === null;
  const dates = [...new Set(rows.map(row => row.examDate).filter(Boolean))].sort();
  const visibleRows = manualVisibleRows(rows, q, dateFilter);
  const grouped = groupSchedule(visibleRows);
  const candidateSearch = candidateQuery.trim().toLowerCase();
  const candidates = teachers.filter(teacher => (teacher.uuid || teacher.id) !== draft?.row?.teacherId
    && (!candidateSearch || [teacher.name, teacher.teacherNo].some(value => String(value || '').toLowerCase().includes(candidateSearch))));

  const selectSlot = (row, event, teacher = null) => {
    if (controlsDisabled || actionRef.current) return;
    const liveRow = rows.find(item => item.assignmentId === row?.assignmentId);
    if (!liveRow) return;
    selectedButtonRef.current = event?.currentTarget || selectedButtonRef.current;
    generationRef.current += 1;
    setPreview({ row: liveRow, teacher: teacher && (teacher.uuid || teacher.id) !== liveRow.teacherId ? teacher : null,
      taskId, runId: assignments?.runId, revision: revisionCursor, generation: generationRef.current });
    setReason(''); setCandidateQuery(''); setActionError(''); setConflictResponse(null);
  };
  const selectTeacher = teacher => {
    if (controlsDisabled || actionRef.current || !draftRef.current) return;
    const teacherId = teacher?.uuid || teacher?.id;
    const live = teachers.find(item => (item.uuid || item.id) === teacherId);
    if (!live || teacherId === draftRef.current.row.teacherId) return;
    generationRef.current += 1;
    setPreview({ ...draftRef.current, teacher: live, generation: generationRef.current });
    setActionError(''); setConflictResponse(null);
  };
  const onDropTeacher = (event, row) => {
    event.preventDefault();
    if (controlsDisabled || actionRef.current) return;
    const id = event.dataTransfer.getData('application/x-jkxt-teacher-id') || event.dataTransfer.getData('text/plain');
    const teacher = teachers.find(item => (item.uuid || item.id) === id);
    if (!teacher) { setActionError('未识别到当前名单中的教师，请重新选择。'); return; }
    selectSlot(row, event, teacher);
  };
  const isCurrent = preview => {
    const context = contextRef.current;
    return mountedRef.current && preview && draftRef.current === preview
      && generationRef.current === preview.generation && !context.loading && !context.error
      && context.taskId === preview.taskId && context.runId === preview.runId && context.revision === preview.revision;
  };
  const captureOperation = () => ({ taskId, runId: assignments?.runId, revision: revisionCursor, generation: generationRef.current });
  const isCurrentOperation = operation => {
    const context = contextRef.current;
    return mountedRef.current && operation && !refreshRequiredRef.current && !context.loading && !context.error
      && operation.generation === generationRef.current && operation.taskId === context.taskId
      && operation.runId === context.runId && operation.revision === context.revision;
  };

  const confirmReplacement = async () => {
    const preview = draft;
    const note = reason.trim();
    if (actionRef.current || controlsDisabled || refreshRequiredRef.current || !preview?.teacher || !note || note.length > 500 || !isCurrent(preview)) return;
    actionRef.current = true; setSaving(true); setActionError(''); setConflictResponse(null);
    try {
      const { check, patched } = await requestScheduleReplacement(window.api, preview.taskId, preview.row,
        preview.teacher.uuid || preview.teacher.id, preview.revision, note, () => isCurrent(preview));
      if (!isCurrent(preview)) return;
      if (!patched) {
        setConflictResponse(check);
        setActionError(check?.conflicts?.[0]?.message || '服务端未明确允许本次改派，未写入。请核对或选择其他教师。');
        return;
      }
      window.ui?.toast?.('监考员已改派', { kind: 'success', desc: preview.row.teacherName + ' → ' + (patched.teacherName || preview.teacher.name) });
      clearPreview();
      await reload(preview.taskId);
    } catch (failure) {
      if (!isCurrent(preview)) return;
      setRefreshRequired(true);
      setActionError((failure?.message || '服务未确认改派结果') + '。请刷新排班核对后再操作，勿重复提交。');
    } finally {
      actionRef.current = false;
      if (mountedRef.current) setSaving(false);
    }
  };
  const refresh = async () => {
    if (actionRef.current) return;
    clearPreview();
    if (await reload(taskId)) setRefreshRequired(false);
  };
  const switchTask = () => {
    if (actionRef.current) return;
    clearPreview(); window.navigateTo?.('tasks');
  };
  const cancel = () => {
    if (actionRef.current) return;
    clearPreview(); selectedButtonRef.current?.focus?.();
  };
  const reselectCandidate = () => {
    if (actionRef.current || !draftRef.current) return;
    generationRef.current += 1;
    setPreview({ ...draftRef.current, teacher: null, generation: generationRef.current });
    setActionError('');setConflictResponse(null);
  };

  const changeHistory = async direction => {
    if (controlsDisabled || refreshRequiredRef.current || draftRef.current || actionRef.current) return;
    const operation = captureOperation();
    if (!isCurrentOperation(operation)) return;
    actionRef.current = true; setSaving(true); setActionError(''); setConflictResponse(null);
    try {
      await window.api.post('/tasks/' + operation.taskId + '/schedule/' + direction, {});
      if (!isCurrentOperation(operation)) return;
      window.ui?.toast?.(direction === 'undo' ? '已撤销上一笔改派' : '已重做改派', { kind: 'success' });
      await reload(operation.taskId);
    } catch (failure) {
      if (!isCurrentOperation(operation)) return;
      setRefreshRequired(true);
      setActionError(failure?.message || '修订操作未成功，请刷新核对');
    } finally { actionRef.current = false; if (mountedRef.current) setSaving(false); }
  };
  const publish = () => {
    if (controlsDisabled || refreshRequiredRef.current || draftRef.current || actionRef.current || !rows.length || assignments?.status === 'published') return;
    const operation = captureOperation();
    if (!isCurrentOperation(operation)) return;
    if (!window.JKXTTaskTools?.open({ id: operation.taskId }, 'task-detail')) setActionError('无法打开审批与发布，请刷新后重试');
  };

  const statusLabel = ({ draft: '草稿', validated: '已校验', scheduling: '排班中', scheduled: '已排班', succeeded: '已排班', published: '已发布', failed: '失败', infeasible: '无可行解', cancelled: '已取消' })[task?.status || assignments?.status] || '状态未提供';
  const taskName = task?.name || (taskId ? '任务名称未提供' : '未选择任务');
  const period = task?.startsOn ? task.startsOn + (task.endsOn && task.endsOn !== task.startsOn ? ' 至 ' + task.endsOn : '') : '考期未提供';
  const canConfirm = !controlsDisabled && !needsRefresh && !!draft?.teacher && !!reason.trim() && reason.trim().length <= 500;

  return <PageShell crumbs={[orgName(), '考务任务', '人工调度']} title="人工调度"
    subtitle={<span className="md-page-context"><strong>{taskName}</strong><span>{loading ? '正在读取任务' : period + ' · ' + statusLabel + ' · 修订 ' + (revisionCursor ?? '未提供')}</span></span>}
    actions={<><Button data-testid="md-task-switch" disabled={saving} icon={IconList} onClick={switchTask}>切换任务</Button><Button disabled={controlsDisabled || needsRefresh || !!draft || !rows.length || assignments?.status === 'published'} onClick={publish}>前往审批与发布</Button><Button data-testid="md-refresh" disabled={loading || saving} onClick={refresh}>刷新排班</Button></>}>
    <div className="manual-dispatch-v2 manual-v5 blueprint-v5">
      <div className="md-selection-context" role="status">{draft ? <><span>已选</span><strong>{draft.row.roomCode || draft.row.roomName} · {manualRoleLabel(draft.row.role)} · {draft.row.teacherName || '姓名未提供'}</strong></> : <><IconHelp size={20} /><span>先选择要调整的监考岗位，再核对候选教师。</span></>}</div>
      {needsRefresh && <div className="md-action-error" role="alert" data-testid="md-refresh-required">上次改派的结果或修订尚未核对。请先刷新排班，再确认新的调整。<Button disabled={loading || saving} onClick={refresh}>刷新并核对</Button></div>}
      {loading ? <div className="md-state" role="status">正在读取当前任务、排班和教师名单…</div>
        : error ? <div className="md-state" role="alert"><h2>无法读取当前任务</h2><p>{error}</p><Button onClick={switchTask}>重新选择任务</Button><Button onClick={refresh}>重试读取</Button></div>
        : !taskId ? <div className="md-state"><h2>请先选择考务任务</h2><p>人工调度不会自动打开或修改第一条历史任务。</p><Button variant="primary" onClick={switchTask}>选择考务任务</Button></div>
        : !rows.length ? <div className="md-state"><h2>当前任务没有可改派的岗位</h2><p>请先生成真实排班，或选择其他已排班任务。</p><Button onClick={switchTask}>选择其他任务</Button></div>
        : <div className="md-workspace">
          <section className="md-board" aria-label="监考岗位工作区">
            <div className="md-toolbar"><label className="md-date-filter"><span className="sr-only">考试日期</span><select data-testid="md-date-filter" value={dateFilter} disabled={saving} onChange={event => setDateFilter(event.target.value)}><option value="">全部日期</option>{dates.map(date => <option key={date} value={date}>{date}</option>)}</select></label><div className="md-view-switch" role="group" aria-label="岗位表排列方式"><button type="button" data-testid="md-view-time" aria-pressed={orientation === 'time'} disabled={saving} onClick={() => setOrientation('time')}>时段</button><button type="button" data-testid="md-view-room" aria-pressed={orientation === 'room'} disabled={saving} onClick={() => setOrientation('room')}>考场</button></div><label className="md-field md-board-search"><span className="sr-only">查找岗位</span><input data-testid="md-board-search" type="search" value={q} onChange={event => setQ(event.target.value)} placeholder="考场、科目或教师" disabled={saving} /></label>{(q || dateFilter) && <Button disabled={saving} onClick={() => {setQ('');setDateFilter('');}}>清空筛选</Button>}</div>
            {!visibleRows.length ? <div className="md-state"><p>没有匹配的监考岗位。</p><Button onClick={() => {setQ('');setDateFilter('');}}>查看全部岗位</Button></div>
              : <div className="md-table-scroll" tabIndex="0" aria-label="岗位表，可横向滚动"><ManualScheduleGrid grouped={grouped} orientation={orientation} draft={draft} onDropTeacher={onDropTeacher} selectSlot={selectSlot} disabled={controlsDisabled} /></div>}
            <div className="md-board-footer">{visibleRows.length} 个岗位 · {grouped.rooms.length} 个考场 · {grouped.slots.length} 个时段<span>点击或拖动只选择，不保存</span></div>
          </section>
          <aside className="md-editor" aria-label="本次改派" aria-busy={saving}>
            {!draft ? <div className="md-editor-empty"><h2>待核对候选</h2><p>先在左侧选择需要调整的岗位。</p><p className="md-muted">选定岗位后，展示当前学校在职教师，选择候选后核对原、新安排。</p>{revisionCursor === null && <p role="alert">缺少有效修订号，请刷新后再操作。</p>}</div>
              : <div>
                <TeacherCandidateRail teachers={candidates} draggedTeacherId={draft.teacher?.uuid || draft.teacher?.id} onSelectTeacher={selectTeacher}
                  query={candidateQuery} onSearch={setCandidateQuery} onDetails={setTeacherInfo} disabled={saving}
                  onDragStart={(event, teacher) => { event.dataTransfer.setData('application/x-jkxt-teacher-id', teacher.uuid || teacher.id); }} />
              </div>}
          </aside>
        </div>}
      {draft?.teacher && <ManualReplacementDialog title={actionError ? (needsRefresh ? '请核对改派结果' : '改派未保存') : '确认改派'} busy={saving} onClose={cancel} returnFocusRef={selectedButtonRef}
        legacyFooter={<div className="md-confirm-actions"><Button data-testid="md-cancel" disabled={saving} onClick={cancel}>取消</Button><Button data-testid="md-confirm" disabled={!canConfirm} variant="primary" onClick={confirmReplacement}>{saving ? '正在校验并提交…' : '校验并保存'}</Button></div>}
        footer={actionError ? <><Button data-testid="md-reselect" disabled={saving || needsRefresh} variant="primary" onClick={reselectCandidate}>重新选择候选</Button><Button disabled={loading || saving} onClick={refresh}>刷新任务</Button><Button data-testid="md-cancel" disabled={saving} onClick={cancel}>取消选择</Button></> : <><Button data-testid="md-cancel" disabled={saving} onClick={cancel}>取消选择</Button><Button data-testid="md-confirm" disabled={!canConfirm} variant="primary" onClick={confirmReplacement}>{saving ? '正在校验并提交…' : '校验并保存'}</Button></>}>
        <header className="md-preview-heading"><strong>{taskName}</strong><p>{draft.row.examDate} {String(draft.row.startTime || '').slice(0, 5)}–{String(draft.row.endTime || '').slice(0, 5)} / {draft.row.roomName || draft.row.roomCode} / {draft.row.subjectName || '科目未提供'} / {manualRoleLabel(draft.row.role)}</p></header>
        {actionError ? <><div data-testid="md-error">{Array.isArray(conflictResponse?.conflicts) && conflictResponse.conflicts.length ? <ConflictBanner response={conflictResponse} /> : <div className="md-action-error" role="alert">{actionError}</div>}</div><div className="md-original-fact"><IconHelp size={18} /><span>{needsRefresh ? '上次已读取的原安排' : '原安排保持不变'}：{manualRoleLabel(draft.row.role)} {draft.row.teacherName || '姓名未提供'}</span></div><div className="md-blocked-next"><h3>下一步</h3><ul><li>{needsRefresh ? '先刷新任务，确认服务端最新安排和修订。' : '重新选择候选教师。'}</li><li>核对该教师现有安排、资格与跨校区转场要求。</li></ul></div></> : <div className="md-diff" aria-label="改派前后差异"><div><span>原安排</span><strong>{draft.row.teacherName || '未提供'}</strong><small>{draft.row.teacherNo || teachers.find(teacher => (teacher.uuid || teacher.id) === draft.row.teacherId)?.teacherNo || '工号未提供'}</small></div><span aria-hidden="true">→</span><div><span>新安排</span><strong>{draft.teacher.name || '姓名未提供'}</strong><small>{draft.teacher.teacherNo || '工号未提供'}</small></div></div>}
        <label className="md-field">改派原因 <span className="md-required">*</span><textarea data-testid="md-reason" rows="3" maxLength="500" value={reason} disabled={saving} onChange={event => setReason(event.target.value)} placeholder="说明调整原因及已核实的信息" /></label>
        {(!actionError || needsRefresh) && <div className="md-safety-note"><IconAlert size={22} /><div><strong>{needsRefresh ? '网络或版本变化导致结果待核对，请勿重复提交。' : '确认后由后端校验，校验通过才保存。'}</strong><p>仅检查系统当前支持的条件，跨任务安排、资格与转场仍需人工核对。</p></div></div>}
        <p className="md-muted" role={saving ? 'status' : undefined}>{saving ? '提交期间请等待结果，离开页面后可在任务中核对最新修订。' : '本次操作不会自动发布或发送通知。'}</p>
      </ManualReplacementDialog>}
      {teacherInfo && window.BlueprintDrawer && React.createElement(window.BlueprintDrawer, {title:'候选教师资料',onClose:()=>setTeacherInfo(null),className:'manual-v5-profile',footer:<Button onClick={()=>setTeacherInfo(null)}>返回候选列表</Button>},
        <div><h2>{teacherInfo.name || '姓名未提供'}</h2><p>资料来自当前学校教师名单，不代表已通过岗位资格校验。</p><dl>{[['工号',teacherInfo.teacherNo],['年级',teacherInfo.grade],['学科',teacherInfo.subject || teacherInfo.subjectName],['部门',teacherInfo.department || teacherInfo.departmentName],['累计监考次数',Number.isFinite(teacherInfo.supCount) ? teacherInfo.supCount : null]].map(([label,value])=><div key={label}><dt>{label}</dt><dd>{value == null || value === '' ? '未提供' : String(value)}</dd></div>)}</dl><p className="md-candidate-warning">跨任务安排、资格、请假与转场要求仍需结合学校资料核对。</p></div>)}
      {!draft && actionError && <div data-testid="md-error" className="md-action-error" role="alert">{actionError}</div>}
      {!loading && !error && taskId && <details className="md-secondary">
        <summary>修订与发布 <span>与本次改派分开操作</span></summary>
        <p>仅监考安排，未含考生座位名单。改派后须重新提交当前版本，由另一位管理员审批，批准后方可发布。下面的按钮打开审批与发布页面，不会立即发送通知。</p>
        <div className="md-secondary-actions">
          <Button aria-label="撤销上一笔改派" disabled={controlsDisabled || needsRefresh || !!draft} onClick={() => changeHistory('undo')}>撤销上一笔改派</Button>
          <Button aria-label="重做上一笔改派" disabled={controlsDisabled || needsRefresh || !!draft} onClick={() => changeHistory('redo')}>重做</Button>
          <Button disabled={controlsDisabled || needsRefresh || !!draft || !rows.length || assignments?.status === 'published'} onClick={publish}>{assignments?.status === 'published' ? '当前排班已发布' : '前往审批与发布'}</Button>
          <Button disabled title="当前没有自动改派接口">AI 自动优化 · 暂不可用</Button>
        </div>
        {draft && <p className="md-muted">请先确认或取消本次改派，再操作修订与发布。</p>}
        <p className="md-technical">任务 {taskId} · 运行 {assignments?.runId || '未提供'} · 修订 {revisionCursor ?? '未提供'}</p>
      </details>}
      {!loading && !error && taskId && <div className="md-workflow-footnote"><IconHelp size={20} /><span>{draft?.teacher ? '尚未提交改派' : draft ? '等待选择候选教师' : '尚未选择改派岗位'} · 保存不等于发布</span></div>}
    </div>
  </PageShell>;
}

window.EditSchedulePage = EditSchedulePage;
