// Dashboard page — composes the dashboard widgets

// 新建排监考任务 — 真实写入后端 (POST /api/v1/exam-tasks)。考试科目/日期/考场在后续导入时确定。
function DashboardNewTaskModal() {
  const [name, setName] = React.useState('2025-2026 学年下学期 期中考试');
  const [saving, setSaving] = React.useState(false);
  const [err, setErr] = React.useState('');

  const submit = async () => {
    if (saving) return;
    if (!name.trim()) { setErr('任务名称为必填项'); return; }
    setSaving(true); setErr('');
    try {
      const task = await window.api.post('/exam-tasks', { name: name.trim(), source: 'manual' });
      window.ui.closeModal();
      window.ui.toast('排监考任务已创建', {
        kind: 'success',
        desc: `编号 ${task.taskNo || task.id} · 接下来上传三张表生成真实排班`,
        action: { label: '前往一键排监考', onClick: () => window.navigateTo('quick') },
      });
    } catch (e) {
      setErr(e.message || '创建失败');
      setSaving(false);
    }
  };

  return (
    <div className="space-y-4">
      <div>
        <label className="block text-[12px] font-medium mb-1.5" style={{ color: 'var(--text-medium)' }}>任务名称 <span className="text-rose-500">*</span></label>
        <input value={name} onChange={(e) => setName(e.target.value)}
          className="w-full h-10 px-3 rounded-lg text-[13px] outline-none"
          style={{ background: '#F8FAFC', border: '1px solid var(--border-soft)', color: 'var(--text-strong)' }} />
      </div>
      <div className="rounded-lg p-3 flex items-start gap-2.5" style={{ background: '#EEF2FF' }}>
        <IconHelp size={14} className="shrink-0 mt-0.5" style={{ color: '#4F46E5' }} />
        <div className="text-[11.5px]" style={{ color: '#3730A3' }}>
          创建后进入「一键排监考」——上传教师 / 考场 / 考试三张表，系统据此真实排考；考试科目、日期、考场等在导入时确定。
        </div>
      </div>
      {err && <div className="rounded-lg px-3 py-2 text-[12px]" style={{ background: '#FEF2F2', color: '#B91C1C', border: '1px solid #FECACA' }}>{err}</div>}
      <div className="flex items-center justify-end gap-2">
        <Button onClick={() => window.ui.closeModal()}>取消</Button>
        <Button variant="primary" icon={IconCheck} disabled={saving} onClick={submit}>{saving ? '创建中…' : '创建并继续'}</Button>
      </div>
    </div>
  );
}

function DashboardPage() {
  const openNewTask = () => {
    window.ui.modal({
      title: '新建排监考任务',
      subtitle: '填写任务名称即可创建,随后在「一键排监考」上传三张表生成真实排班',
      size: 'md',
      content: <DashboardNewTaskModal />,
    });
  };

  return (
    <PageShell
      crumbs={[orgName(), '教务处', '仪表盘']}
      title="仪表盘"
      badge={(() => { const d = new Date(); return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')} · 周${'日一二三四五六'[d.getDay()]}`; })()}
      subtitle={
        <>
          您好,{window.__jkxtMe?.user?.displayName || '管理员'} · 以下为本校实时考务概览
        </>
      }
      actions={
        <>
          <Button icon={IconUpload} onClick={() => window.ui.toast('导入数据', { kind: 'info', desc: '请前往对应模块进行批量导入', action: { label: '前往教师管理', onClick: () => window.navigateTo('people') } })}>
            导入数据
          </Button>
          <Button variant="primary" icon={IconPlus} onClick={openNewTask}>新建任务</Button>
        </>
      }
    >
      <KPIRow />
      {window.OnboardingLearningCard && <OnboardingLearningCard key={window.JKXTLearningGuides?.storageKey(window.__jkxtMe, 'admin') || 'session-learning'} profile={window.__jkxtMe} />}
      <div className="dashboard-main-grid grid grid-cols-[minmax(0,1fr)_360px] gap-5">
        <div className="min-w-0"><TimelineCard /></div>
        <div><RecentTasksCard /></div>
      </div>
      <QuickActionsRow />
    </PageShell>
  );
}

window.DashboardPage = DashboardPage;
