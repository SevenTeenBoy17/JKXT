// Shared visual primitives for the Lumen Glass interface layer.
// The primitives stay presentation-only so business behavior remains in page components.

const GLASS_ICON_TONES = new Set(['indigo', 'violet', 'sky', 'emerald', 'amber', 'rose']);

function GlassIconTile({
  icon: Icn,
  children,
  tone = 'indigo',
  active = false,
  size = 'md',
  className = '',
  decorative = true,
}) {
  const safeTone = GLASS_ICON_TONES.has(tone) ? tone : 'indigo';
  return (
    <span
      className={`glass-icon-tile glass-icon-tile--${size} ${active ? 'is-active' : ''} ${className}`.trim()}
      data-tone={safeTone}
      aria-hidden={decorative ? 'true' : undefined}
    >
      <span className="glass-icon-tile__shine" />
      <span className="glass-icon-tile__glyph">
        {Icn ? <Icn size={size === 'sm' ? 14 : size === 'lg' ? 22 : 17} strokeWidth={active ? 2.2 : 1.9} /> : children}
      </span>
    </span>
  );
}

function BrandLockup({ compact = false, version = 'v2.0', className = '', markSrc = '' }) {
  return (
    <span className={`brand-lockup ${compact ? 'brand-lockup--compact' : ''} ${className}`.trim()}>
      <span className={`brand-lockup__mark ${markSrc ? 'brand-lockup__mark--image' : ''}`.trim()} aria-hidden="true">
        {markSrc
          ? <img className="brand-lockup__mark-image" src={markSrc} alt="" />
          : <BrandMark size={compact ? 27 : 32} />}
      </span>
      <span className="brand-lockup__copy">
        <span className="brand-lockup__name">Quick 排监考</span>
        {!compact && <span className="brand-lockup__descriptor">智能教务排班</span>}
      </span>
      {version && <span className="brand-lockup__version">{version}</span>}
    </span>
  );
}

function flattenPageTitle(value) {
  if (typeof value === 'string' || typeof value === 'number') return String(value);
  if (Array.isArray(value)) return value.map(flattenPageTitle).join(' ');
  if (React.isValidElement(value)) return flattenPageTitle(value.props?.children);
  return '';
}

const JKXT_PAGE_GLYPHS = Object.freeze({
  dashboard: 'dashboard', quick: 'quick', ai: 'strategy', edit: 'edit', tasks: 'tasks',
  'task-detail': 'tasks', 'stats-supervise': 'stats', people: 'people', classrooms: 'room',
  classes: 'classes', subjects: 'subjects', history: 'history', settings: 'settings',
  'super-dashboard': 'dashboard', 'super-tenants': 'tenant', 'super-accounts': 'account',
  'super-system-settings': 'settings', 'super-audit': 'audit', 'super-security': 'security',
  'super-announcements': 'announcement',
});

function pageGlyphFor(title, crumbs = [], routeId) {
  if (routeId && JKXT_PAGE_GLYPHS[routeId]) return JKXT_PAGE_GLYPHS[routeId];
  const titleText = flattenPageTitle(title);
  const crumbText = crumbs.map(flattenPageTitle).join(' ');
  const rules = [
    [/设置|账户安全/, 'settings'], [/安全/, 'security'], [/公告/, 'announcement'], [/租户|单位管理/, 'tenant'],
    [/管理员账号|账号管理/, 'account'], [/审计/, 'audit'], [/历史/, 'history'],
    [/监考人员|教师/, 'people'], [/考场|教室/, 'room'], [/班级/, 'classes'], [/科目|学科/, 'subjects'],
    [/监考次数|统计|报表/, 'stats'], [/修改监考|人工改派/, 'edit'], [/策略|智能排班/, 'strategy'],
    [/一键排监考|新建排监考/, 'quick'], [/任务|排班详情/, 'tasks'], [/仪表盘|总览/, 'dashboard'],
  ];
  const match = (text) => rules.find(([pattern]) => pattern.test(text))?.[1];
  return match(titleText) || match(crumbText) || 'tasks';
}

function PageTitleMark({ title, crumbs = [], glyph, routeId }) {
  const resolvedRoute = routeId || window.__jkxtActivePage;
  const name = glyph || pageGlyphFor(title, crumbs, resolvedRoute);
  return (
    <span className="page-title-mark" data-page-glyph={name} data-page-route={resolvedRoute || undefined} aria-hidden="true">
      <ProductGlyph name={name} size={30} active />
    </span>
  );
}

Object.assign(window, {
  GlassIconTile,
  BrandLockup,
  PageTitleMark,
  JKXT_PAGE_GLYPHS,
});
