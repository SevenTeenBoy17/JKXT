// Dashboard page — composes the dashboard widgets

function DashboardPage() {
  const openNewTask = () => {
    window.ui.modal({
      title: '新建排监考任务',
      subtitle: '一步设置基础信息,后续可在「一键排监考」或「AI 策略」继续完善',
      size: 'md',
      content: (
        <div className="space-y-4">
          <div>
            <label className="block text-[12px] font-medium mb-1.5" style={{ color: 'var(--text-medium)' }}>任务名称 <span className="text-rose-500">*</span></label>
            <input defaultValue="2025-2026 学年下学期 期中考试"
              className="w-full h-10 px-3 rounded-lg text-[13px] outline-none"
              style={{ background: '#F8FAFC', border: '1px solid var(--border-soft)', color: 'var(--text-strong)' }} />
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-[12px] font-medium mb-1.5" style={{ color: 'var(--text-medium)' }}>适用年级</label>
              <select className="w-full h-10 px-3 rounded-lg text-[13px] outline-none" style={{ background: '#F8FAFC', border: '1px solid var(--border-soft)', color: 'var(--text-strong)' }}>
                <option>初二</option><option>初三</option><option>高一</option><option>高二</option><option>高三</option><option>全校</option>
              </select>
            </div>
            <div>
              <label className="block text-[12px] font-medium mb-1.5" style={{ color: 'var(--text-medium)' }}>考试类型</label>
              <select className="w-full h-10 px-3 rounded-lg text-[13px] outline-none" style={{ background: '#F8FAFC', border: '1px solid var(--border-soft)', color: 'var(--text-strong)' }}>
                <option>月考</option><option>期中考</option><option>期末考</option><option>单元测试</option><option>联考统考</option><option>模拟考</option>
              </select>
            </div>
            <div>
              <label className="block text-[12px] font-medium mb-1.5" style={{ color: 'var(--text-medium)' }}>开始日期</label>
              <input type="date" defaultValue="2025-11-22"
                className="w-full h-10 px-3 rounded-lg text-[13px] outline-none font-mono"
                style={{ background: '#F8FAFC', border: '1px solid var(--border-soft)', color: 'var(--text-strong)' }} />
            </div>
            <div>
              <label className="block text-[12px] font-medium mb-1.5" style={{ color: 'var(--text-medium)' }}>考期 (天数)</label>
              <input type="number" defaultValue="3"
                className="w-full h-10 px-3 rounded-lg text-[13px] outline-none font-mono"
                style={{ background: '#F8FAFC', border: '1px solid var(--border-soft)', color: 'var(--text-strong)' }} />
            </div>
          </div>
          <div>
            <label className="block text-[12px] font-medium mb-1.5" style={{ color: 'var(--text-medium)' }}>备注</label>
            <textarea defaultValue=""
              placeholder="可选,关于本次考试的注意事项"
              className="w-full h-20 px-3 py-2 rounded-lg text-[13px] outline-none resize-none"
              style={{ background: '#F8FAFC', border: '1px solid var(--border-soft)', color: 'var(--text-strong)' }} />
          </div>
        </div>
      ),
      footer: (
        <>
          <Button onClick={() => window.ui.closeModal()}>取消</Button>
          <Button variant="primary" icon={IconCheck} onClick={() => {
            window.ui.closeModal();
            window.ui.toast('任务已创建', { kind: 'success', desc: '可继续在「一键排监考」上传 Excel,或使用「AI 策略监考」', action: { label: '前往一键排监考', onClick: () => window.navigateTo('quick') } });
          }}>创建并继续</Button>
        </>
      ),
    });
  };

  return (
    <PageShell
      crumbs={['天津市实验中学', '教务处', '仪表盘']}
      title="仪表盘"
      badge="2025-11-21 · 周三"
      subtitle={
        <>
          您好,王明远 · 今日 <span className="num font-semibold" style={{ color: 'var(--text-strong)' }}>2</span> 场考试进行中,<span className="num font-semibold" style={{ color: 'var(--text-strong)' }}> 1</span> 个待处理 · 高三一模 <span className="num font-semibold" style={{ color: 'var(--brand)' }}>3</span> 天后启动
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
      <div className="grid grid-cols-[1fr_360px] gap-5">
        <div><TimelineCard /></div>
        <div><RecentTasksCard /></div>
      </div>
      <QuickActionsRow />
    </PageShell>
  );
}

window.DashboardPage = DashboardPage;
