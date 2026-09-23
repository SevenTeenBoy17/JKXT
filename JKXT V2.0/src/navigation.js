(function () {
  const pageMap = {
    dashboard:         { Comp: () => window.DashboardPage,        title: '工作台',          mode: 'admin' },
    risks:             { Comp: () => window.ExamRisksPage,        title: '风险与待办',      mode: 'admin' },
    'exam-plans':      { Comp: () => window.ExamPlansPage,        title: '考试计划',        mode: 'admin' },
    quick:             { Comp: () => window.QuickSchedulePage,    title: '排班工作台',      mode: 'admin' },
    ai:                { Comp: () => window.AIPolicyPage,         title: '策略与约束',      mode: 'admin' },
    edit:              { Comp: () => window.EditSchedulePage,     title: '人工调度',        mode: 'admin' },
    tasks:             { Comp: () => window.TasksPage,            title: '考务任务',        mode: 'admin' },
    'stats-supervise': { Comp: () => window.StatsSupervisePage,   title: '工作量与公平性',  mode: 'admin' },
    people:            { Comp: () => window.PeoplePage,           title: '监考人员',        mode: 'admin' },
    classrooms:        { Comp: () => window.ClassroomsPage,       title: '考场与座位',      mode: 'admin' },
    classes:           { Comp: () => window.ClassesSubjectsPage,  title: '班级管理',        mode: 'admin' },
    subjects:          { Comp: () => window.ClassesSubjectsPage,  title: '科目管理',        mode: 'admin' },
    approvals:         { Comp: () => window.ExamApprovalsPage,    title: '审批与发布',      mode: 'admin' },
    emergency:         { Comp: () => window.ExamEmergencyPage,    title: '应急与事件',      mode: 'admin' },
    history:           { Comp: () => window.HistoryPage,          title: '历史统计与导出',  mode: 'admin' },
    settings:          { Comp: () => window.SettingsPage,         title: '个人与学校设置',  mode: 'admin' },
    'task-detail':     { Comp: () => window.TaskDetailPage,       title: '任务详情',        mode: 'admin' },
    'super-dashboard':       { Comp: () => window.SuperDashboardPage,        title: '超管仪表盘', mode: 'super' },
    'super-tenants':         { Comp: () => window.SuperTenantsPage,          title: '租户管理',   mode: 'super' },
    'super-accounts':        { Comp: () => window.SuperAccountsPage,         title: '管理员账号', mode: 'super' },
    'super-system-settings': { Comp: () => window.SuperSystemSettingsPage,   title: '系统设置',   mode: 'super' },
    'super-audit':           { Comp: () => window.SuperAuditPage,            title: '审计日志',   mode: 'super' },
    'super-security':        { Comp: () => window.SuperSecurityPage,         title: '安全中心',   mode: 'super' },
    'super-announcements':   { Comp: () => window.SuperAnnouncementsPage,    title: '公告发布',   mode: 'super' },
  };

  const adminGroups = (counts = {}) => [
    {
      label: '考务概览',
      items: [
        { id: 'dashboard', glyph: 'dashboard', label: '工作台' },
        { id: 'risks', glyph: 'security', label: '风险与待办' },
      ],
    },
    {
      label: '考务作业',
      items: [
        { id: 'exam-plans', glyph: 'calendar', label: '考试计划' },
        { id: 'tasks', glyph: 'tasks', label: '考务任务', count: counts.tasks },
        { id: 'quick', glyph: 'quick', label: '排班工作台' },
        { id: 'edit', glyph: 'edit', label: '人工调度' },
      ],
    },
    {
      label: '资源与规则',
      items: [
        { id: 'people', glyph: 'people', label: '监考人员' },
        { id: 'classrooms', glyph: 'room', label: '考场与座位' },
        {
          id: 'classes-subjects', glyph: 'subjects', label: '班级、科目与考生',
          children: [
            { id: 'classes', glyph: 'classes', label: '班级管理' },
            { id: 'subjects', glyph: 'subjects', label: '科目管理' },
          ],
        },
        { id: 'ai', glyph: 'strategy', label: '策略与约束' },
      ],
    },
    {
      label: '结果与档案',
      items: [
        { id: 'approvals', glyph: 'audit', label: '审批与发布' },
        { id: 'stats-supervise', glyph: 'stats', label: '工作量与公平性' },
        { id: 'history', glyph: 'history', label: '历史统计与导出' },
        { id: 'emergency', glyph: 'announcement', label: '应急与事件' },
      ],
    },
    {
      label: '设置',
      items: [
        { id: 'settings', glyph: 'settings', label: '个人与学校设置' },
      ],
    },
  ];

  const superGroups = (counts = {}) => [
    { label: '总览', items: [{ id: 'super-dashboard', glyph: 'dashboard', label: '超管仪表盘' }] },
    {
      label: '租户与账号', items: [
        { id: 'super-tenants', glyph: 'tenant', label: '租户管理', count: counts.tenants },
        { id: 'super-accounts', glyph: 'account', label: '管理员账号', count: counts.accounts },
      ],
    },
    {
      label: '平台运维', items: [
        { id: 'super-system-settings', glyph: 'settings', label: '系统设置' },
        { id: 'super-audit', glyph: 'audit', label: '审计日志' },
        { id: 'super-security', glyph: 'security', label: '安全中心' },
        { id: 'super-announcements', glyph: 'announcement', label: '公告发布' },
      ],
    },
  ];

  window.JKXTNavigation = {
    pageMap,
    groups(mode, counts) { return mode === 'super' ? superGroups(counts) : adminGroups(counts); },
    resolveActive(pageId) { return pageId === 'task-detail' ? 'tasks' : pageId; },
    parentByChild: { classes: 'classes-subjects', subjects: 'classes-subjects' },
  };
})();
