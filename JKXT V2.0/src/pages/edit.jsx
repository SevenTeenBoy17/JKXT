// 修改监考安排 - P9 real drag reassignment layer.
// Keeps the existing page shell and visual language, while replacing mock grid actions with backend schedule edit APIs.

function useScheduleEditData() {
  const [state, setState] = React.useState({
    loading: true,
    error: '',
    taskId: null,
    assignments: null,
    teachers: [],
  });

  const load = React.useCallback(async (preferredTaskId) => {
    setState((s) => ({ ...s, loading: true, error: '' }));
    try {
      let taskId = preferredTaskId;
      if (!taskId) {
        try { taskId = localStorage.getItem('jkxt:taskDetailId'); } catch {}
      }
      if (!taskId) {
        const tasks = await window.api.get('/exam-tasks');
        const task = (tasks || []).find((item) => item?.stats?.assignments > 0 || item?.latestJob?.status === 'succeeded') || (tasks || [])[0];
        taskId = task?.id;
      }
      if (!taskId) throw new Error('当前还没有可编辑的排考任务');

      const [assignments, teachers] = await Promise.all([
        window.api.get(`/exam-tasks/${taskId}/assignments`),
        window.api.get('/teachers', { status: 'active' }),
      ]);
      try { localStorage.setItem('jkxt:taskDetailId', taskId); } catch {}
      setState({
        loading: false,
        error: '',
        taskId,
        assignments,
        teachers: Array.isArray(teachers) ? teachers : [],
      });
    } catch (error) {
      setState((s) => ({
        ...s,
        loading: false,
        error: error?.message || '加载排班数据失败',
      }));
    }
  }, []);

  React.useEffect(() => { load(); }, [load]);
  return { ...state, reload: load };
}

function slotKey(row) {
  return `${row.examDate}|${row.startTime}|${row.endTime}`;
}

function cellKey(row) {
  return `${slotKey(row)}|${row.roomId}|${row.examId}`;
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

function ConflictBanner({ response, activeIndex, onPrev, onNext }) {
  const conflicts = response?.conflicts || [];
  if (!conflicts.length) return null;
  const item = conflicts[Math.min(activeIndex, conflicts.length - 1)] || conflicts[0];
  return (
    <div
      data-testid="p9-conflict-banner"
      className="rounded-xl p-4 flex items-start gap-3"
      style={{ background: 'linear-gradient(135deg, #FEF2F2 0%, #FFF7ED 100%)', border: '1px solid #FCA5A5' }}
    >
      <div className="w-9 h-9 rounded-lg flex items-center justify-center shrink-0"
        style={{ background: '#FEE2E2', color: '#DC2626' }}>
        <IconAlert size={18} strokeWidth={2} />
      </div>
      <div className="flex-1 min-w-0">
        <div className="text-[14px] font-semibold inline-flex items-center gap-2 flex-wrap" style={{ color: '#991B1B' }}>
          检测到 {conflicts.length} 处排班冲突
          <Tag color="rose" dot>后端硬约束拦截</Tag>
        </div>
        <div className="text-[12.5px] mt-1 leading-relaxed" style={{ color: '#B91C1C' }}>
          <span className="font-mono font-semibold">{item.code}</span>
          <span className="mx-1">·</span>
          {item.message}
          <span className="ml-2 font-mono">
            {item.examDate} {String(item.startTime || '').slice(0, 5)}-{String(item.endTime || '').slice(0, 5)}
          </span>
        </div>
      </div>
      {conflicts.length > 1 && (
        <div className="inline-flex items-center gap-px shrink-0"
          style={{ border: '1px solid #FCA5A5', borderRadius: 8, background: '#fff' }}>
          <button className="w-8 h-8 hover:bg-rose-50 rounded-l-lg" onClick={onPrev} style={{ color: '#B91C1C' }}>‹</button>
          <span className="w-10 text-center text-[11px] font-mono" style={{ color: '#991B1B' }}>{activeIndex + 1}/{conflicts.length}</span>
          <button className="w-8 h-8 hover:bg-rose-50 rounded-r-lg" onClick={onNext} style={{ color: '#B91C1C' }}>›</button>
        </div>
      )}
    </div>
  );
}

function TeacherCandidateRail({ teachers, draggedTeacherId, onDragStart, onDragEnd }) {
  return (
    <aside
      data-testid="p9-candidate-rail"
      className="bg-white rounded-2xl p-4 shrink-0"
      style={{ width: 270, border: '1px solid var(--border-soft)', boxShadow: 'var(--elevation-1)' }}
    >
      <div className="flex items-center justify-between gap-3">
        <div>
          <div className="text-[13.5px] font-semibold" style={{ color: 'var(--text-strong)' }}>候选教师栏</div>
          <div className="text-[11px] mt-0.5" style={{ color: 'var(--text-soft)' }}>拖拽教师到任一监考员槽位</div>
        </div>
        <Tag color="violet" dot>{teachers.length}</Tag>
      </div>
      <div className="mt-3 space-y-2 max-h-[520px] overflow-y-auto pr-1">
        {teachers.map((teacher) => {
          const teacherId = teacher.uuid || teacher.id;
          const active = teacherId === draggedTeacherId;
          return (
            <div
              key={teacherId}
              data-testid="p9-candidate-teacher"
              data-teacher-id={teacherId}
              draggable
              onDragStart={(event) => onDragStart(event, teacher)}
              onDragEnd={onDragEnd}
              className="rounded-xl p-3 cursor-grab active:cursor-grabbing transition-all"
              style={{
                background: active ? 'var(--brand-soft)' : '#FAFBFC',
                border: active ? '1px solid rgba(79,70,229,.38)' : '1px solid var(--border-soft)',
                boxShadow: active ? '0 8px 24px -14px rgba(79,70,229,.45)' : '0 1px 0 rgba(255,255,255,.9) inset',
              }}
            >
              <div className="flex items-center gap-2.5">
                <AvatarChip name={teacher.name} size={26} />
                <div className="min-w-0 flex-1">
                  <div className="text-[12.5px] font-semibold truncate" style={{ color: 'var(--text-strong)' }}>{teacher.name}</div>
                  <div className="text-[10.5px] font-mono truncate" style={{ color: 'var(--text-soft)' }}>
                    {teacher.teacherNo || teacher.id} · {teacher.grade || '全学段'}
                  </div>
                </div>
                <span className="text-[10.5px] font-mono" style={{ color: 'var(--brand)' }}>{teacher.supCount ?? 0}</span>
              </div>
            </div>
          );
        })}
      </div>
    </aside>
  );
}

function AssignmentDropSlot({ row, pendingAssignmentId, onDropTeacher }) {
  const active = pendingAssignmentId === row.assignmentId;
  const allowDrop = (event) => {
    event.preventDefault();
    event.dataTransfer.dropEffect = 'move';
  };
  return (
    <div
      data-testid="p9-assignment-slot"
      data-assignment-id={row.assignmentId}
      onDragOver={allowDrop}
      onDrop={(event) => onDropTeacher(event, row)}
      className="rounded-lg px-2 py-1.5 flex items-center gap-1.5 transition-all"
      style={{
        background: active ? 'rgba(79,70,229,.10)' : '#fff',
        border: active ? '1px solid rgba(79,70,229,.42)' : '1px solid var(--border-soft)',
      }}
      title="拖入候选教师以改派该监考员"
    >
      <AvatarChip name={row.teacherName} size={18} />
      <div className="min-w-0 flex-1">
        <div className="text-[10.5px] font-medium truncate" style={{ color: 'var(--text-medium)' }}>{row.teacherName}</div>
        <div className="text-[9.5px] font-mono truncate" style={{ color: 'var(--text-soft)' }}>{row.role} · {row.source}</div>
      </div>
    </div>
  );
}

function ScheduleCell({ cell, pendingAssignmentId, onDropTeacher }) {
  if (!cell) {
    return (
      <td
        className="border relative"
        style={{ borderColor: 'var(--border-soft)', background: '#FAFBFC', height: 96, minWidth: 188 }}
      />
    );
  }
  const color = '#6366F1';
  return (
    <td
      className="border p-1.5 transition-all relative"
      style={{ borderColor: 'var(--border-soft)', background: '#fff', height: 96, minWidth: 188 }}
    >
      <div
        className="rounded-xl p-2 h-full flex flex-col gap-2"
        style={{ background: color + '0D', border: `1px solid ${color}40` }}
      >
        <div className="flex items-center gap-1.5">
          <span className="w-1.5 h-1.5 rounded-full" style={{ background: color }} />
          <span className="text-[11.5px] font-semibold leading-tight truncate" style={{ color: 'var(--text-strong)' }}>
            {cell.grade ? `${cell.grade} · ` : ''}{cell.subjectName}
          </span>
        </div>
        <div className="grid gap-1.5">
          {cell.rows.map((row) => (
            <AssignmentDropSlot
              key={row.assignmentId}
              row={row}
              pendingAssignmentId={pendingAssignmentId}
              onDropTeacher={onDropTeacher}
            />
          ))}
        </div>
      </div>
    </td>
  );
}

function EditSchedulePage() {
  const { loading, error, taskId, assignments, teachers, reload } = useScheduleEditData();
  const [draggedTeacher, setDraggedTeacher] = React.useState(null);
  const [pendingAssignmentId, setPendingAssignmentId] = React.useState(null);
  const [conflictResponse, setConflictResponse] = React.useState(null);
  const [conflictIndex, setConflictIndex] = React.useState(0);
  const [saving, setSaving] = React.useState(false);

  const rows = assignments?.rows || [];
  const grouped = React.useMemo(() => groupSchedule(rows), [rows]);
  const revisionCursor = Number(assignments?.stats?.revisionCursor || 0);

  const onDragStart = (event, teacher) => {
    const teacherId = teacher.uuid || teacher.id;
    event.dataTransfer.setData('application/x-jkxt-teacher-id', teacherId);
    event.dataTransfer.setData('text/plain', teacherId);
    setDraggedTeacher(teacher);
  };

  const onDropTeacher = async (event, row) => {
    event.preventDefault();
    if (!taskId) return;
    const teacherId = event.dataTransfer.getData('application/x-jkxt-teacher-id') || event.dataTransfer.getData('text/plain');
    if (!teacherId || !row?.assignmentId) return;
    setPendingAssignmentId(row.assignmentId);
    setSaving(true);
    try {
      const check = await window.api.post(`/tasks/${taskId}/schedule/check-conflicts`, {
        assignmentId: row.assignmentId,
        teacherId,
      });
      setConflictResponse(check);
      setConflictIndex(0);
      if (!check.allowed) {
        window.ui?.toast?.('改派存在冲突，已阻止写入', { kind: 'warning', desc: check.conflicts?.[0]?.message || '请更换候选教师' });
        return;
      }
      const patched = await window.api.patch(`/tasks/${taskId}/schedule`, {
        assignmentId: row.assignmentId,
        teacherId,
        expectedRevision: revisionCursor,
        reason: 'drag-drop',
      });
      setConflictResponse({ conflicts: [], count: 0, allowed: true });
      window.ui?.toast?.('监考员已改派', { kind: 'success', desc: `${row.teacherName} → ${patched.teacherName}` });
      await reload(taskId);
    } catch (error) {
      window.ui?.toast?.('改派失败', { kind: 'error', desc: error?.message || '后端拒绝本次改派' });
    } finally {
      setSaving(false);
      setPendingAssignmentId(null);
      setDraggedTeacher(null);
    }
  };

  const undo = async () => {
    if (!taskId) return;
    try {
      await window.api.post(`/tasks/${taskId}/schedule/undo`, {});
      window.ui?.toast?.('已撤销上一笔改派', { kind: 'success' });
      setConflictResponse(null);
      await reload(taskId);
    } catch (error) {
      window.ui?.toast?.('无法撤销', { kind: 'warning', desc: error?.message || '没有可撤销的改派' });
    }
  };

  const redo = async () => {
    if (!taskId) return;
    try {
      await window.api.post(`/tasks/${taskId}/schedule/redo`, {});
      window.ui?.toast?.('已重做改派', { kind: 'success' });
      setConflictResponse(null);
      await reload(taskId);
    } catch (error) {
      window.ui?.toast?.('无法重做', { kind: 'warning', desc: error?.message || '没有可重做的改派' });
    }
  };

  const publish = async () => {
    if (!taskId) return;
    const ok = await window.ui.confirm({
      title: '确认发布排班?',
      desc: '发布后将写入任务状态，并生成站内通知事件。后续仍可继续产生新的人工修订。',
      confirmText: '确认发布',
    });
    if (!ok) return;
    try {
      const result = await window.api.post(`/tasks/${taskId}/publish`, {});
      window.ui?.toast?.('排班发布成功', {
        kind: 'success',
        desc: `已通知 ${result.recipientCount ?? result.notificationCount ?? 0} 位教师 · ${result.notificationStatus}`,
        action: { label: '查看任务', onClick: () => window.navigateTo('tasks') },
      });
      await reload(taskId);
    } catch (error) {
      window.ui?.toast?.('发布失败', { kind: 'error', desc: error?.message || '后端发布接口未通过' });
    }
  };

  const statusBadge = assignments?.status ? `${assignments.status} · rev ${revisionCursor}` : 'loading';

  return (
    <PageShell
      crumbs={['天津市实验中学', '排班作业', '修改监考安排']}
      title="修改监考安排"
      badge={taskId ? `${String(taskId).slice(0, 8)} · ${statusBadge}` : '未选择任务'}
      subtitle="拖拽候选教师到监考员槽位，后端会先检查冲突，再写入人工 revision。"
      actions={
        <>
          <Button icon={IconList} onClick={() => window.navigateTo('tasks')}>切换任务</Button>
          <div className="inline-flex items-center gap-px ml-2"
            style={{ border: '1px solid var(--border-soft)', borderRadius: 8, background: '#fff' }}>
            <button className="w-9 h-9 inline-flex items-center justify-center hover:bg-slate-50 rounded-l-lg" style={{ color: 'var(--text-medium)' }} title="撤销" onClick={undo}>
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round"><path d="M3 7h11a5 5 0 0 1 5 5v0a5 5 0 0 1-5 5h-6"/><path d="M7 3 3 7l4 4"/></svg>
            </button>
            <button className="w-9 h-9 inline-flex items-center justify-center hover:bg-slate-50 rounded-r-lg" style={{ color: 'var(--text-medium)' }} title="重做" onClick={redo}>
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round"><path d="M21 7H10a5 5 0 0 0-5 5v0a5 5 0 0 0 5 5h6"/><path d="M17 3l4 4-4 4"/></svg>
            </button>
          </div>
          <Button onClick={() => reload(taskId)}>刷新</Button>
          <Button variant="primary" icon={IconCheck} onClick={publish}>发布排班</Button>
        </>
      }
    >
      <ConflictBanner
        response={conflictResponse}
        activeIndex={conflictIndex}
        onPrev={() => setConflictIndex((i) => Math.max(0, i - 1))}
        onNext={() => setConflictIndex((i) => Math.min((conflictResponse?.conflicts?.length || 1) - 1, i + 1))}
      />

      <div className="bg-white rounded-xl p-3 flex items-center gap-3 flex-wrap"
        style={{ border: '1px solid var(--border-soft)' }}>
        <FilterBar
          search="搜索考场、科目或教师..."
          filters={[
            { label: '任务:', value: taskId ? String(taskId).slice(0, 8) : '未选择', options: [taskId ? String(taskId).slice(0, 8) : '未选择'] },
            { label: '状态:', value: assignments?.status || 'loading', options: [assignments?.status || 'loading'] },
            { label: '冲突:', value: conflictResponse?.conflicts?.length ? `${conflictResponse.conflicts.length} 条` : '0 条', options: ['0 条', '仅显示冲突'] },
          ]}
          right={
            <div className="inline-flex items-center gap-2">
              <Button size="sm" variant="ghost" icon={IconSparkle} onClick={() => window.ui?.toast?.('AI 修复建议将在 P10 接入', { kind: 'info' })}>AI 优化</Button>
              <Button size="sm" variant="ghost" icon={IconChart} onClick={() => reload(taskId)}>同步后端</Button>
            </div>
          }
        />
      </div>

      {loading ? (
        <div className="bg-white rounded-2xl p-8 text-center" style={{ border: '1px solid var(--border-soft)' }}>
          <div className="text-[13px]" style={{ color: 'var(--text-medium)' }}>正在加载真实排班数据...</div>
        </div>
      ) : error ? (
        <div className="bg-white rounded-2xl p-8 text-center" style={{ border: '1px solid var(--border-soft)' }}>
          <div className="text-[14px] font-semibold" style={{ color: 'var(--danger)' }}>无法进入真实改派</div>
          <div className="text-[12.5px] mt-2" style={{ color: 'var(--text-soft)' }}>{error}</div>
          <div className="mt-4 inline-flex gap-2">
            <Button onClick={() => window.navigateTo('quick')}>创建 Quick 任务</Button>
            <Button variant="primary" onClick={() => reload()}>重试加载</Button>
          </div>
        </div>
      ) : (
        <div className="flex gap-4 items-start">
          <div className="bg-white rounded-2xl overflow-hidden flex-1 min-w-0"
            style={{ border: '1px solid var(--border-soft)', boxShadow: 'var(--elevation-1)' }}>
            <div className="overflow-x-auto">
              <table className="w-full" style={{ borderCollapse: 'separate', borderSpacing: 0 }}>
                <thead>
                  <tr style={{ background: '#FAFBFC' }}>
                    <th className="sticky left-0 z-10 px-4 py-3 text-left"
                      style={{ background: '#FAFBFC', borderBottom: '1px solid var(--border-soft)', minWidth: 210 }}>
                      <div className="text-[11px] uppercase tracking-wider font-mono" style={{ color: 'var(--text-soft)' }}>
                        考场 \ 时段
                      </div>
                    </th>
                    {grouped.slots.map((slot) => {
                      const label = formatSlot(slot);
                      return (
                        <th key={slot.key} className="px-3 py-2.5 text-center"
                          style={{ borderBottom: '1px solid var(--border-soft)', minWidth: 188 }}>
                          <div className="font-mono text-[10.5px] uppercase tracking-wider" style={{ color: 'var(--text-soft)' }}>
                            {label.day}
                          </div>
                          <div className="text-[12.5px] font-semibold mt-0.5" style={{ color: 'var(--text-strong)' }}>
                            {label.period}
                          </div>
                        </th>
                      );
                    })}
                  </tr>
                </thead>
                <tbody>
                  {grouped.rooms.map((room) => (
                    <tr key={room.key}>
                      <td className="sticky left-0 z-10 px-4 py-2"
                        style={{ background: '#fff', borderBottom: '1px solid var(--border-soft)', borderRight: '1px solid var(--border-soft)' }}>
                        <div className="flex items-center gap-2">
                          <span className="font-mono text-[12px] font-semibold px-1.5 py-0.5 rounded"
                            style={{ background: '#F1F5F9', color: 'var(--text-medium)' }}>
                            {room.roomCode}
                          </span>
                          <span className="text-[12.5px]" style={{ color: 'var(--text-strong)' }}>{room.roomName}</span>
                        </div>
                      </td>
                      {grouped.slots.map((slot) => {
                        const cell = Array.from(grouped.cells.values()).find((item) => item.roomId === room.roomId && item.slotKey === slot.key);
                        return (
                          <ScheduleCell
                            key={`${room.key}-${slot.key}`}
                            cell={cell}
                            pendingAssignmentId={pendingAssignmentId}
                            onDropTeacher={onDropTeacher}
                          />
                        );
                      })}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="px-5 py-3 flex items-center justify-between gap-3 flex-wrap"
              style={{ borderTop: '1px solid var(--border-soft)', background: '#FAFBFC' }}>
              <div className="inline-flex items-center gap-4 text-[11.5px]">
                <span style={{ color: 'var(--text-medium)' }}>排班行 <span className="font-mono font-semibold">{rows.length}</span></span>
                <span style={{ color: 'var(--text-medium)' }}>考场 <span className="font-mono font-semibold">{grouped.rooms.length}</span></span>
                <span style={{ color: 'var(--text-medium)' }}>时段 <span className="font-mono font-semibold">{grouped.slots.length}</span></span>
                <span style={{ color: saving ? 'var(--brand)' : 'var(--text-soft)' }}>{saving ? '正在校验并写入...' : '后端冲突校验已启用'}</span>
              </div>
              <div className="text-[11.5px]" style={{ color: 'var(--text-soft)' }}>
                run <span className="font-mono">{String(assignments?.runId || '').slice(0, 8)}</span> · revision <span className="font-mono">{revisionCursor}</span>
              </div>
            </div>
          </div>

          <TeacherCandidateRail
            teachers={teachers}
            draggedTeacherId={draggedTeacher ? (draggedTeacher.uuid || draggedTeacher.id) : null}
            onDragStart={onDragStart}
            onDragEnd={() => setDraggedTeacher(null)}
          />
        </div>
      )}

      <div className="bg-white rounded-xl px-4 py-3 inline-flex items-center gap-3"
        style={{ border: '1px solid var(--border-soft)' }}>
        <span className="font-mono text-[10.5px] uppercase tracking-wider px-1.5 py-0.5 rounded"
          style={{ background: 'var(--brand-soft)', color: 'var(--brand)' }}>
          P9
        </span>
        <span className="text-[11.5px]" style={{ color: 'var(--text-medium)' }}>
          <span className="kbd">drag</span> 候选教师 · <span className="kbd ml-1">drop</span> 监考员槽位 · 后端预检 · 无冲突写入 revision · 支持撤销/重做/发布通知
        </span>
      </div>
    </PageShell>
  );
}

window.EditSchedulePage = EditSchedulePage;
