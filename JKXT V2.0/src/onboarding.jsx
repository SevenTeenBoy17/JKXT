// 新手导览 — 欢迎说明 + 最多 5 步真实界面聚光引导。

const JKXT_TOUR_VERSION = 'v8';
const JKXT_TOUR_HERO = 'assets/ui/onboarding-v8/school-scheduling-tour-hero.svg';

const ADMIN_TOUR_STEPS = [
  {
    id: 'term-context', route: 'dashboard', target: '[data-tour-id="term-selector"]',
    placement: 'bottom', glyph: 'calendar', title: '先确认当前学期',
    body: '任务、统计和历史记录都按学年学期归档。排考前确认一次，后续报表就不会串期。',
  },
  {
    id: 'create-or-import', route: 'dashboard', target: '[data-tour-id="page-actions"]',
    placement: 'bottom', glyph: 'upload', title: '从真实数据开始',
    body: '先确认当前学校，再选择办理路径：已有三张表可进入排班工作台；需要统筹场次、考场与审批时进入考试计划。两条流程不会自动同步修改。',
  },
  {
    id: 'quick-schedule', route: 'dashboard', target: '[data-tour-id="nav-quick"]',
    placement: 'right', glyph: 'quick', title: '三张表完成一次排考',
    body: '进入「排班工作台」后上传考试安排、监考人员和考场信息，先查看真实校验结果，再按条件生成可复核安排。',
  },
  {
    id: 'global-search', route: 'dashboard', target: '[data-tour-id="global-search"]',
    placement: 'bottom', glyph: 'search', title: '不用记住每个入口',
    body: '搜索页面、教师或操作，也可以使用快捷键打开命令面板。高频工作无需反复翻找导航。',
  },
  {
    id: 'reopen-tour', route: 'dashboard', target: '[data-tour-id="tour-help"]',
    placement: 'bottom', glyph: 'help', title: '随时回来查看',
    body: '帮助入口可以重新打开这套导览。重看教程不会清空任务，也不会改变已经完成的首次使用状态。',
  },
];

const SUPER_TOUR_STEPS = [
  {
    id: 'platform-overview', route: 'super-dashboard', target: '[data-tour-id="page-heading"]',
    placement: 'bottom', glyph: 'dashboard', title: '先看平台运行概况',
    body: '超管首页只呈现跨租户的关键状态。进入具体学校前，先在这里确认告警和待处理事项。',
  },
  {
    id: 'platform-context', route: 'super-dashboard', target: '[data-tour-id="platform-context"]',
    placement: 'bottom', glyph: 'tenant', title: '确认当前操作范围',
    body: '看到「全平台视图」时，后续操作可能影响多个租户；进入租户页面后再处理单校事务。',
  },
  {
    id: 'tenant-navigation', route: 'super-dashboard', target: '[data-tour-id="nav-super-tenants"]',
    placement: 'right', glyph: 'tenant', title: '从租户进入学校',
    body: '在租户管理中检索学校、查看状态并进入对应单位。涉及安全和账号的操作都会进入审计日志。',
  },
  {
    id: 'global-search', route: 'super-dashboard', target: '[data-tour-id="global-search"]',
    placement: 'bottom', glyph: 'search', title: '快速定位运维对象',
    body: '全局搜索可直接定位租户、账号、审计记录和平台操作，适合跨模块处理问题。',
  },
  {
    id: 'reopen-tour', route: 'super-dashboard', target: '[data-tour-id="tour-help"]',
    placement: 'bottom', glyph: 'help', title: '从帮助入口重新查看',
    body: '需要交接或复核流程时，可从这里随时重开导览；不会修改平台配置或已完成状态。',
  },
];

const safeIdentityPart = (value, fallback) => {
  const normalized = String(value ?? '').trim().replace(/[^a-zA-Z0-9_-]/g, '-').slice(0, 64);
  return normalized || fallback;
};
const profileUserId = (profile) => profile?.user?.id || profile?.user?.username || profile?.username;
const profileOrgId = (profile) => profile?.organization?.id || profile?.organization?.slug
  || profile?.org?.id || profile?.tenant?.id || profile?.tenant?.slug || profile?.orgId;
const tourStorageKey = (profile, mode = 'admin') => {
  const org = safeIdentityPart(profileOrgId(profile), 'default-org');
  const user = safeIdentityPart(profileUserId(profile), 'default-user');
  return `jkxt:tour:${JKXT_TOUR_VERSION}:${mode}:${org}:${user}`;
};
const readTourState = (profile, mode = 'admin') => {
  try {
    const raw = localStorage.getItem(tourStorageKey(profile, mode));
    return raw ? JSON.parse(raw) : null;
  } catch { return null; }
};
const writeTourState = (profile, mode, next) => {
  const value = {
    version: JKXT_TOUR_VERSION,
    status: next.status,
    lastStepId: next.lastStepId || null,
    needsSync: !!next.needsSync,
    updatedAt: new Date().toISOString(),
  };
  try { localStorage.setItem(tourStorageKey(profile, mode), JSON.stringify(value)); } catch {}
  return value;
};

const syncPendingTourState = async (profile, mode = 'admin') => {
  const state = readTourState(profile, mode);
  if (!state?.needsSync || !['completed', 'skipped'].includes(state.status)) return false;
  try {
    if (!window.api?.post) return false;
    await window.api.post('/auth/me/onboarded', {});
    writeTourState(profile, mode, { status: state.status, lastStepId: state.lastStepId, needsSync: false });
    return true;
  } catch { return false; }
};

window.JKXTOnboarding = {
  version: JKXT_TOUR_VERSION,
  storageKey: tourStorageKey,
  read: readTourState,
  syncPending: syncPendingTourState,
  shouldAutoOpen(profile, mode = 'admin') {
    if (!profile || profile?.onboarded === true || profile?.user?.onboarded === true) return false;
    const state = readTourState(profile, mode);
    return !state || !['completed', 'skipped'].includes(state.status);
  },
};

const clampTour = (value, min, max) => Math.min(Math.max(value, min), max);
const resumeTourStepIndex = (state, steps) => state?.status === 'paused'
  ? Math.max(0, steps.findIndex((step) => step.id === state.lastStepId)) : 0;
const targetIsVisible = (node) => {
  if (!node) return false;
  const style = getComputedStyle(node);
  const rect = node.getBoundingClientRect();
  return style.display !== 'none' && style.visibility !== 'hidden' && rect.width > 0 && rect.height > 0;
};

function SpotlightMask({ rect }) {
  const pad = 9;
  const top = clampTour(rect.top - pad, 0, window.innerHeight);
  const left = clampTour(rect.left - pad, 0, window.innerWidth);
  const right = clampTour(rect.right + pad, 0, window.innerWidth);
  const bottom = clampTour(rect.bottom + pad, 0, window.innerHeight);
  const pieces = [
    { top: 0, left: 0, width: '100vw', height: top },
    { top, left: 0, width: left, height: Math.max(0, bottom - top) },
    { top, left: right, width: Math.max(0, window.innerWidth - right), height: Math.max(0, bottom - top) },
    { top: bottom, left: 0, width: '100vw', height: Math.max(0, window.innerHeight - bottom) },
  ];
  return (
    <>
      {pieces.map((style, index) => <div key={index} className="product-tour__mask" style={style} />)}
      <div className="product-tour__spotlight" style={{ top, left, width: Math.max(0, right - left), height: Math.max(0, bottom - top) }} aria-hidden="true" />
    </>
  );
}

function OnboardingModal({ onClose, onFinish, mode = 'admin', me, activePage, source = 'auto' }) {
  const steps = mode === 'super' ? SUPER_TOUR_STEPS : ADMIN_TOUR_STEPS;
  const [phase, setPhase] = React.useState('welcome');
  const [stepIndex, setStepIndex] = React.useState(() => resumeTourStepIndex(readTourState(me, mode), steps));
  const [targetRect, setTargetRect] = React.useState(null);
  const [targetMissing, setTargetMissing] = React.useState(false);
  const [resolving, setResolving] = React.useState(false);
  const [mobile, setMobile] = React.useState(() => window.innerWidth <= 720);
  const panelRef = React.useRef(null);
  const triggerRef = React.useRef(document.activeElement);
  const current = steps[stepIndex];
  const isLast = stepIndex === steps.length - 1;
  const isFirst = stepIndex === 0;

  const syncServerCompletion = (status) => {
    const resolvedStepId = phase === 'tour' ? current?.id : null;
    try {
      const request = window.api?.post?.('/auth/me/onboarded', {});
      if (!request?.then) {
        writeTourState(me, mode, { status, lastStepId: resolvedStepId, needsSync: true });
        return;
      }
      request
        .then(() => writeTourState(me, mode, { status, lastStepId: resolvedStepId, needsSync: false }))
        .catch(() => writeTourState(me, mode, { status, lastStepId: resolvedStepId, needsSync: true }));
    } catch { writeTourState(me, mode, { status, lastStepId: resolvedStepId, needsSync: true }); }
  };
  const closePaused = () => {
    const previousState = readTourState(me, mode);
    const preservesTerminalState = source !== 'auto' && ['completed', 'skipped'].includes(previousState?.status);
    if (!preservesTerminalState) {
      writeTourState(me, mode, {
        status: 'paused',
        lastStepId: phase === 'tour' ? current?.id : null,
        needsSync: previousState?.needsSync,
      });
    }
    onClose?.({ status: preservesTerminalState ? previousState.status : 'paused' });
  };
  const skip = () => {
    writeTourState(me, mode, { status: 'skipped', lastStepId: phase === 'tour' ? current?.id : null });
    try { localStorage.setItem('jkxt:onboarded', '1'); } catch {}
    syncServerCompletion('skipped');
    onFinish?.({ status: 'skipped' });
    onClose?.({ status: 'skipped' });
    window.ui?.toast('已跳过新手导览', {
      kind: 'info', desc: '可从顶栏帮助入口随时重新打开',
      action: { label: '重新查看', onClick: () => window.__openOnboarding?.({ source: 'toast' }) },
    });
  };
  const finish = () => {
    writeTourState(me, mode, { status: 'completed', lastStepId: current?.id });
    try { localStorage.setItem('jkxt:onboarded', '1'); } catch {}
    syncServerCompletion('completed');
    onFinish?.({ status: 'completed' });
    onClose?.({ status: 'completed' });
    window.ui?.toast('新手导览已完成', { kind: 'success', desc: '帮助入口仍可随时重看' });
  };

  React.useEffect(() => {
    const appNodes = Array.from(document.querySelectorAll('.app-topbar, .app-layout, .app-super-mode-banner')).map((node) => ({
      node,
      ariaHidden: node.getAttribute('aria-hidden'),
      inertAttribute: node.getAttribute('inert'),
      inertProperty: node.inert,
    }));
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    appNodes.forEach(({ node }) => {
      node.setAttribute('aria-hidden', 'true');
      try { node.inert = true; } catch {}
    });
    return () => {
      document.body.style.overflow = previousOverflow;
      appNodes.forEach(({ node, ariaHidden, inertAttribute, inertProperty }) => {
        if (ariaHidden === null) node.removeAttribute('aria-hidden');
        else node.setAttribute('aria-hidden', ariaHidden);
        try { node.inert = inertProperty; } catch {}
        if (inertAttribute === null) node.removeAttribute('inert');
        else node.setAttribute('inert', inertAttribute);
      });
      const previous = triggerRef.current;
      const fallbackSelector = source === 'profile-menu' ? '[data-tour-id="profile-trigger"]' : '[data-tour-id="tour-help"]';
      const focusTarget = previous?.isConnected ? previous : document.querySelector(fallbackSelector);
      if (focusTarget?.focus) { try { focusTarget.focus({ preventScroll: true }); } catch {} }
    };
  }, [source]);

  React.useEffect(() => {
    const panel = panelRef.current;
    if (!panel) return undefined;
    const selector = 'a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"])';
    const focusables = () => Array.from(panel.querySelectorAll(selector)).filter((node) => node.offsetParent !== null);
    const frame = requestAnimationFrame(() => (focusables()[0] || panel).focus({ preventScroll: true }));
    const onKey = (event) => {
      if (event.key === 'Escape') { event.preventDefault(); closePaused(); return; }
      if (event.key !== 'Tab') return;
      const items = focusables();
      if (!items.length) { event.preventDefault(); return; }
      const index = items.indexOf(document.activeElement);
      if (event.shiftKey && index <= 0) { event.preventDefault(); items[items.length - 1].focus(); }
      else if (!event.shiftKey && (index === -1 || index === items.length - 1)) { event.preventDefault(); items[0].focus(); }
    };
    document.addEventListener('keydown', onKey, true);
    return () => { cancelAnimationFrame(frame); document.removeEventListener('keydown', onKey, true); };
  }, [phase, stepIndex]);

  React.useEffect(() => {
    const updateViewportMode = () => setMobile(window.innerWidth <= 720);
    window.addEventListener('resize', updateViewportMode);
    window.visualViewport?.addEventListener('resize', updateViewportMode);
    return () => {
      window.removeEventListener('resize', updateViewportMode);
      window.visualViewport?.removeEventListener('resize', updateViewportMode);
    };
  }, []);

  React.useEffect(() => {
    if (phase !== 'tour') return undefined;
    if (mobile) {
      setResolving(false);
      setTargetMissing(false);
      setTargetRect(null);
      if (current.route && activePage !== current.route) window.navigateTo?.(current.route);
      return undefined;
    }
    let cancelled = false;
    let targetNode = null;
    let timeoutId = null;
    let frameId = null;
    let resizeObserver = null;
    const startedAt = performance.now();
    setResolving(true);
    setTargetMissing(false);
    setTargetRect(null);
    if (current.route && activePage !== current.route) window.navigateTo?.(current.route);
    const updateRect = () => {
      if (cancelled || !targetNode || !targetIsVisible(targetNode)) return;
      const rect = targetNode.getBoundingClientRect();
      setTargetRect({ top: rect.top, left: rect.left, right: rect.right, bottom: rect.bottom, width: rect.width, height: rect.height });
    };
    const scheduleRect = () => { cancelAnimationFrame(frameId); frameId = requestAnimationFrame(updateRect); };
    const resolve = () => {
      if (cancelled) return;
      const routeReady = !current.route || document.querySelector('.app-shell')?.dataset.pageId === current.route;
      const candidate = routeReady ? document.querySelector(current.target) : null;
      if (targetIsVisible(candidate)) {
        targetNode = candidate;
        const rect = candidate.getBoundingClientRect();
        if (rect.top < 8 || rect.bottom > window.innerHeight - 8) {
          candidate.scrollIntoView({ block: 'center', behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' });
        }
        scheduleRect();
        setResolving(false);
        resizeObserver = window.ResizeObserver ? new ResizeObserver(scheduleRect) : null;
        resizeObserver?.observe(candidate);
        return;
      }
      if (performance.now() - startedAt > 2200) {
        setResolving(false); setTargetMissing(true); setTargetRect(null); return;
      }
      timeoutId = setTimeout(resolve, 90);
    };
    const onViewport = scheduleRect;
    window.addEventListener('resize', onViewport);
    window.addEventListener('scroll', scheduleRect, true);
    window.visualViewport?.addEventListener('resize', onViewport);
    window.visualViewport?.addEventListener('scroll', scheduleRect);
    resolve();
    return () => {
      cancelled = true;
      clearTimeout(timeoutId);
      cancelAnimationFrame(frameId);
      resizeObserver?.disconnect();
      window.removeEventListener('resize', onViewport);
      window.removeEventListener('scroll', scheduleRect, true);
      window.visualViewport?.removeEventListener('resize', onViewport);
      window.visualViewport?.removeEventListener('scroll', scheduleRect);
    };
  }, [phase, stepIndex, current.id, activePage, mobile]);

  const cardStyle = (() => {
    if (phase !== 'tour' || mobile || !targetRect || targetMissing) return undefined;
    const width = 372;
    const estimatedHeight = 246;
    const gap = 16;
    let left = clampTour(targetRect.left + targetRect.width / 2 - width / 2, 18, window.innerWidth - width - 18);
    let top = targetRect.bottom + gap;
    if (current.placement === 'right' && targetRect.right + width + gap < window.innerWidth) {
      left = targetRect.right + gap;
      top = clampTour(targetRect.top, 18, window.innerHeight - estimatedHeight - 18);
    }
    if (top + estimatedHeight > window.innerHeight - 18) top = targetRect.top - estimatedHeight - gap;
    if (top < 18) return undefined;
    return { left, top, width };
  })();

  if (phase === 'welcome') {
    return (
      <div className="onboarding-layer" data-tour-phase="welcome">
        <div className="onboarding-layer__scrim" aria-hidden="true" />
        <section ref={panelRef} className="onboarding-welcome" role="dialog" aria-modal="true"
          aria-labelledby="onboarding-welcome-title" aria-describedby="onboarding-welcome-desc" tabIndex={-1}>
          <div className="onboarding-welcome__copy">
            <span className="onboarding-kicker">首次使用 · 约 2 分钟</span>
            <div className="onboarding-welcome__title-row">
              <span className="onboarding-welcome__mark" aria-hidden="true"><ProductGlyph name="quick" size={34} active /></span>
              <h2 id="onboarding-welcome-title">把第一次排监考走顺</h2>
            </div>
            <p id="onboarding-welcome-desc">这套导览会直接指向真实界面，带你确认学期、找到数据入口，并完成第一次排考前的准备。</p>
            <ul className="onboarding-benefits">
              <li><IconCheck size={15} strokeWidth={2.5} /><span>5 个步骤，只讲最常用的入口</span></li>
              <li><IconCheck size={15} strokeWidth={2.5} /><span>不会代替你上传、创建或提交数据</span></li>
              <li><IconCheck size={15} strokeWidth={2.5} /><span>可跳过，也可从帮助入口随时重看</span></li>
            </ul>
            <div className="onboarding-welcome__actions">
              <Button onClick={skip}>暂时跳过</Button>
              <Button variant="primary" onClick={() => setPhase('tour')}>{stepIndex > 0 ? `继续第 ${stepIndex + 1} 步` : '开始导览'}</Button>
            </div>
            <button className="onboarding-pause-link" onClick={closePaused}>稍后提醒我</button>
          </div>
          <div className="onboarding-welcome__visual" aria-hidden="true"><img src={JKXT_TOUR_HERO} alt="" draggable="false" /></div>
        </section>
      </div>
    );
  }

  const CurrentIcon = PRODUCT_ICON_COMPONENTS[current.glyph] || PRODUCT_ICON_COMPONENTS.tasks;
  return (
    <div className="product-tour" data-tour-phase="spotlight" data-target-missing={targetMissing ? 'true' : 'false'}>
      {targetRect && !mobile && !targetMissing ? <SpotlightMask rect={targetRect} /> : <div className="product-tour__mask product-tour__mask--full" />}
      <section ref={panelRef} className={`product-tour__coachmark ${mobile ? 'is-mobile' : ''} ${!cardStyle ? 'is-centered' : ''}`}
        style={cardStyle} role="dialog" aria-modal="true" aria-labelledby="product-tour-title" aria-describedby="product-tour-body" tabIndex={-1}>
        <div className="product-tour__head">
          <span className="product-tour__icon" aria-hidden="true"><CurrentIcon size={28} active /></span>
          <div>
            <div className="product-tour__progress">第 {stepIndex + 1} 步，共 {steps.length} 步</div>
            <h2 id="product-tour-title">{current.title}</h2>
          </div>
          <button className="product-tour__close" onClick={closePaused} aria-label="暂停并关闭新手导览"><span aria-hidden="true">×</span></button>
        </div>
        <p id="product-tour-body">{current.body}</p>
        {(resolving || targetMissing) && <div className="product-tour__status" role="status">{resolving ? '正在定位界面…' : '当前界面未显示该控件，仍可继续下一步。'}</div>}
        <div className="product-tour__meter" aria-hidden="true"><span style={{ width: `${((stepIndex + 1) / steps.length) * 100}%` }} /></div>
        <div className="product-tour__actions">
          <button className="product-tour__skip" onClick={skip}>跳过导览</button>
          <div className="product-tour__nav">
            <Button onClick={() => setStepIndex((value) => Math.max(0, value - 1))} disabled={isFirst}>上一步</Button>
            {isLast
              ? <Button variant="primary" icon={IconCheck} onClick={finish}>完成</Button>
              : <Button variant="primary" onClick={() => setStepIndex((value) => Math.min(steps.length - 1, value + 1))}>下一步</Button>}
          </div>
        </div>
      </section>
    </div>
  );
}

window.OnboardingModal = OnboardingModal;

// 学习确认与真实排考进度分开存储。此卡不调用业务 API，也不写入首次导览的完成状态。
const JKXT_LEARNING_VERSION = 'v1';
const JKXT_LEARNING_PATHS = [
  {
    id: 'quick', title: '三表快速排班', description: '已有教师、考场和考试安排，先完成一次可复核排班。',
    steps: [
      { id: 'quick-prepare', title: '准备三张表', route: 'quick', action: '打开排班工作台', checklist: ['准备本校教师、考场、考试三张真实数据表。', '先查看空白模板，再核对列名、日期和编号。', '不要把示例数据当作本校正式数据。'] },
      { id: 'quick-validate', title: '上传并校验', route: 'quick', action: '查看上传与校验', checklist: ['按表格类型上传，逐项查看服务返回的校验结果。', '修正缺失字段、重复编号和时间冲突后再继续。', '校验通过只表示数据可处理，不代表已经排班。'] },
      { id: 'quick-solve', title: '设置规则并求解', route: 'quick', action: '打开规则与求解入口', checklist: ['核对回避条件、人员可用性和工作量限制。', '区分系统实际执行的约束与标注“仅参考”的建议。', '等待实际求解结果；失败或无可行解时先处理原因。'] },
      { id: 'quick-review', title: '人工复核安排', route: 'tasks', action: '打开考务任务', checklist: ['从对应任务进入详情，逐场核对教师、考场和时段。', '需要调换时进入人工调度，检查改派后的冲突提示。', '该任务不会自动转为联合考试计划，复核后按本校流程处理。'] },
    ],
  },
  {
    id: 'plan', title: '联合考试计划', description: '统筹场次、座位和监考安排，按同一版本完成审查发布。',
    steps: [
      { id: 'plan-prepare', title: '建立考试计划', route: 'exam-plans', action: '打开考试计划', checklist: ['选择或新建本校的考试计划，确认考期与计划名称。', '在计划详情内核对当前版本及数据来源。', '联合计划与三表快速排班是不同路径，不会自动同步修改。'] },
      { id: 'plan-arrange', title: '统筹场次与考场', route: 'exam-plans', action: '查看计划工作区', checklist: ['在同一版本中核对场次、考场、座位和监考输入。', '查看求解结果及冲突审查，不把部分结果当成成功。', '版本变化后重新核对安排，避免使用旧版结果。'] },
      { id: 'plan-publish', title: '审查与发布', route: 'approvals', action: '打开审批与发布', checklist: ['核对计划名称、版本和提交审批的内容。', '审批通过与正式发布是不同状态，分别确认。', '只有具备权限且满足条件时，才执行发布操作。'] },
      { id: 'plan-followup', title: '通知与应急', route: 'emergency', action: '打开应急与事件', checklist: ['发布不等于通知已送达，也不等于教师已确认。', '出现请假、停用考场或临时变更时登记并核对影响。', '应急处理后复核安排与记录，不只看“已处理”标签。'] },
    ],
  },
];
const learningStorageKey = (profile, mode = 'admin') => {
  const org = profileOrgId(profile);
  const user = profileUserId(profile);
  if (!String(org ?? '').trim() || !String(user ?? '').trim()) return null;
  return `jkxt:learning:${JKXT_LEARNING_VERSION}:${encodeURIComponent(mode)}:${encodeURIComponent(String(org))}:${encodeURIComponent(String(user))}`;
};
const normalizeLearningState = (value) => {
  const input = value && typeof value === 'object' && value.version === JKXT_LEARNING_VERSION ? value : {};
  const path = JKXT_LEARNING_PATHS.find((item) => item.id === input.pathId) || JKXT_LEARNING_PATHS[0];
  const validIds = new Set(JKXT_LEARNING_PATHS.flatMap((item) => item.steps.map((step) => step.id)));
  const learnedIds = [...new Set((Array.isArray(input.learnedIds) ? input.learnedIds : []).filter((id) => validIds.has(id)))];
  const selectedId = path.steps.some((step) => step.id === input.selectedId)
    ? input.selectedId : (path.steps.find((step) => !learnedIds.includes(step.id)) || path.steps[0]).id;
  return { version: JKXT_LEARNING_VERSION, pathId: path.id, learnedIds, selectedId, collapsed: input.collapsed === true };
};
const readLearningState = (profile, mode = 'admin') => {
  const key = learningStorageKey(profile, mode);
  try {
    const stored = key ? localStorage.getItem(key) : null;
    if (stored) return normalizeLearningState(JSON.parse(stored));
    // 老用户先看到可展开的轻量入口；已有导览状态不折算为任何学习确认。
    const introduced = profile?.onboarded === true || profile?.user?.onboarded === true
      || ['completed', 'skipped'].includes(readTourState(profile, mode)?.status);
    return normalizeLearningState({ version: JKXT_LEARNING_VERSION, collapsed: introduced });
  }
  catch { return normalizeLearningState(null); }
};
const writeLearningState = (profile, mode, next) => {
  const key = learningStorageKey(profile, mode);
  const normalized = normalizeLearningState(next);
  try {
    if (!key) return false;
    localStorage.setItem(key, JSON.stringify(normalized));
    return true;
  } catch { return false; }
};
const updateLearningState = (state, action) => {
  const current = normalizeLearningState(state);
  if (action.type === 'reset') return normalizeLearningState({ version: JKXT_LEARNING_VERSION, pathId: current.pathId });
  if (action.type === 'path') return normalizeLearningState({ ...current, pathId: action.id, selectedId: null });
  if (action.type === 'select') return normalizeLearningState({ ...current, selectedId: action.id });
  if (action.type === 'collapse') return normalizeLearningState({ ...current, collapsed: !!action.value });
  if (action.type === 'learn') {
    const path = JKXT_LEARNING_PATHS.find((item) => item.id === current.pathId);
    if (!path.steps.some((step) => step.id === action.id)) return current;
    return normalizeLearningState({ ...current, learnedIds: [...current.learnedIds, action.id] });
  }
  return current;
};
window.JKXTLearningGuides = { paths: JKXT_LEARNING_PATHS, storageKey: learningStorageKey, read: readLearningState };

function OnboardingLearningCard({ profile = window.__jkxtMe, mode = 'admin' }) {
  const [learning, setLearning] = React.useState(() => readLearningState(profile, mode));
  const [storageNotice, setStorageNotice] = React.useState('');
  const [resetting, setResetting] = React.useState(false);
  const [announcement, setAnnouncement] = React.useState('');
  const ringRef = React.useRef(null);
  const summaryRef = React.useRef(null);
  const resetButtonRef = React.useRef(null);
  const pendingResetFocusRef = React.useRef(false);
  const previousPercent = React.useRef(0);
  const aliveRef = React.useRef(true);
  const path = JKXT_LEARNING_PATHS.find((item) => item.id === learning.pathId);
  const selected = path.steps.find((step) => step.id === learning.selectedId);
  const learnedCount = path.steps.filter((step) => learning.learnedIds.includes(step.id)).length;
  const percent = learnedCount / path.steps.length * 100;
  const selectedLearned = learning.learnedIds.includes(selected.id);
  const persist = (action) => {
    const next = updateLearningState(learning, action);
    if (!writeLearningState(profile, mode, next)) setStorageNotice('当前浏览器无法保存，学习记录仅保留到本次离开页面。');
    setLearning(next);
  };

  React.useEffect(() => {
    aliveRef.current = true;
    return () => { aliveRef.current = false; pendingResetFocusRef.current = false; };
  }, []);
  React.useLayoutEffect(() => {
    if (resetting || !pendingResetFocusRef.current) return;
    pendingResetFocusRef.current = false;
    const button = resetButtonRef.current;
    if (!aliveRef.current || !button?.isConnected || button.disabled || !button.getClientRects().length || button.closest('[inert]')) return;
    if (window.JKXTDialogLayers?.hasOpen?.() || document.querySelector('[role="dialog"][aria-modal="true"]')) return;
    const active = document.activeElement;
    // Do not override an intentional focus move while the confirmation settles.
    if (active && active !== document.body && active !== document.documentElement && active !== button && active.isConnected) return;
    button.focus({ preventScroll: true });
  }, [resetting]);
  React.useEffect(() => {
    const element = ringRef.current;
    if (!element) return undefined;
    const from = previousPercent.current;
    previousPercent.current = percent;
    if (window.JKXTMotion?.animateLearning) return window.JKXTMotion.animateLearning(element, from, percent);
    element.style.strokeDashoffset = String(100 - percent);
    return undefined;
  }, [percent]);

  const markLearned = () => {
    persist({ type: 'learn', id: selected.id });
    setAnnouncement(`已记录“${selected.title}”的学习确认；未修改任何排考数据。`);
  };
  const resetLearning = async () => {
    if (resetting || pendingResetFocusRef.current) return;
    if (!window.ui?.confirm) { setAnnouncement('暂时无法打开确认框，学习记录保持不变。'); return; }
    pendingResetFocusRef.current = true;
    setResetting(true);
    try {
      const confirmed = await window.ui.confirm({
        title: '重置学习记录？', desc: '仅清除本账号在当前浏览器的两条路径学习确认。考试计划、排班任务和导览完成状态都不会改变。',
        confirmText: '重置学习记录', cancelText: '保留记录',
      });
      if (!aliveRef.current) return;
      if (confirmed) { persist({ type: 'reset' }); setAnnouncement('学习记录已重置，业务数据未改动。'); }
    } catch { if (aliveRef.current) setAnnouncement('未能确认重置，学习记录保持不变。'); }
    finally { if (aliveRef.current) setResetting(false); }
  };

  if (mode !== 'admin') return null;
  return (
    <details className="learning-guide" open={!learning.collapsed} data-testid="learning-guide"
      onToggle={(event) => { const collapsed = !event.currentTarget.open; if (collapsed !== learning.collapsed) persist({ type: 'collapse', value: collapsed }); }}>
      <summary ref={summaryRef} className="learning-guide__summary">
        <span className="learning-guide__ring" aria-hidden="true">
          <svg viewBox="0 0 56 56" width="56" height="56" focusable="false"><circle className="learning-guide__ring-track" cx="28" cy="28" r="23" /><circle ref={ringRef} className="learning-guide__ring-value" cx="28" cy="28" r="23" pathLength="100" strokeDasharray="100" strokeDashoffset={100 - percent} /></svg>
          <span>{learnedCount}<small>/{path.steps.length}</small></span>
        </span>
        <span className="learning-guide__intro"><strong>排监考上手指南</strong><span>先确认学校与学期，再选择适合的办理路径。</span></span>
        <span className="learning-guide__summary-meta"><span>{path.title} · 学习 {learnedCount}/{path.steps.length}</span><span className="learning-guide__toggle-label">{learning.collapsed ? '继续学习' : '收起指南'}<span aria-hidden="true">{learning.collapsed ? '＋' : '−'}</span></span></span>
      </summary>
      <div className="learning-guide__body">
        <div className="learning-guide__context"><strong>开始前请核对</strong><span>顶栏中的当前学校、学年与学期。两条路径独立办理，不会自动同步修改。</span></div>
        <fieldset className="learning-guide__paths"><legend>选择办理路径</legend>{JKXT_LEARNING_PATHS.map((item) => <button key={item.id} type="button" aria-pressed={item.id === path.id} disabled={resetting} onClick={() => persist({ type: 'path', id: item.id })}>{item.title}</button>)}</fieldset>
        <p className="learning-guide__path-description">{path.description}</p>
        <ol className="learning-guide__steps" aria-label={`${path.title}学习单元`}>
          {path.steps.map((step, index) => <li key={step.id}><button type="button" className={step.id === selected.id ? 'is-selected' : ''}
            aria-expanded={step.id === selected.id} aria-controls="learning-guide-detail" disabled={resetting} onClick={() => persist({ type: 'select', id: step.id })}>
            <span className="learning-guide__step-number" aria-hidden="true">{learning.learnedIds.includes(step.id) ? <IconCheck size={15} /> : String(index + 1).padStart(2, '0')}</span>
            <span>{step.title}<small>{learning.learnedIds.includes(step.id) ? '已了解' : '待学习'}</small></span>
          </button></li>)}
        </ol>
        <section className="learning-guide__detail" id="learning-guide-detail" aria-labelledby="learning-guide-detail-title">
          <div key={selected.id} className="learning-guide__detail-copy"><h3 id="learning-guide-detail-title">{selected.title}</h3><ul>{selected.checklist.map((item) => <li key={item}>{item}</li>)}</ul></div>
          <div className="learning-guide__detail-actions">
            <Button variant="primary" onClick={() => window.navigateTo?.(selected.route)}>{selected.action}</Button>
            <Button onClick={markLearned} disabled={selectedLearned || resetting}>{selectedLearned ? '已了解本步骤' : '我已了解'}</Button>
            <span>打开页面不会自动执行操作。</span>
          </div>
        </section>
        <div className="learning-guide__footer"><p>仅记录本地学习确认，不代表业务办理完成。</p><div><button ref={resetButtonRef} type="button" disabled={resetting} onClick={resetLearning}>{resetting ? '等待确认…' : '重置学习记录'}</button><button type="button" onClick={() => { summaryRef.current?.focus(); persist({ type: 'collapse', value: true }); }}>稍后继续</button></div></div>
        <span className="sr-only" role="status" aria-live="polite">{announcement}</span>
        {storageNotice && <p className="learning-guide__storage-note" role="status">{storageNotice}</p>}
      </div>
    </details>
  );
}
window.OnboardingLearningCard = OnboardingLearningCard;
