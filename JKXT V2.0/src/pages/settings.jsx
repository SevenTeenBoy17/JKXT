// 信息设置 — 5 个模块的表单页:单位 / 地区 / 修改密码 / 确认密码 / 验证邮箱

function FormField({ label, required, hint, error, children }) {
  return (
    <div className="grid grid-cols-1 gap-2 py-4 md:grid-cols-[140px_minmax(0,1fr)_220px] md:gap-4" style={{ borderBottom: '1px solid var(--border-soft)' }}>
      <label className="text-[13px] text-left md:pt-2 md:text-right" style={{ color: 'var(--text-medium)' }}>
        {label}
        {required && <span className="ml-1 text-rose-500">*</span>}
      </label>
      <div className="min-w-0">
        {children}
        {error && (
          <div className="mt-1.5 text-[11.5px] inline-flex items-center gap-1" style={{ color: '#DC2626' }}>
            <IconAlert size={11} /> {error}
          </div>
        )}
      </div>
      <div className="text-[11.5px] md:pt-2.5" style={{ color: 'var(--text-soft)' }}>
        {hint}
      </div>
    </div>
  );
}

function Input({ value, placeholder, prefix, suffix, type = 'text', error }) {
  return (
    <div
      className="flex items-center min-h-11 rounded-lg px-3 transition-all"
      style={{
        background: 'var(--surface-muted)',
        border: `1px solid ${error ? '#EF4444' : 'transparent'}`,
      }}
    >
      {prefix && <span className="mr-2 text-[13px]" style={{ color: 'var(--text-soft)' }}>{prefix}</span>}
      <input
        type={type}
        defaultValue={value}
        placeholder={placeholder}
        className="flex-1 bg-transparent outline-none text-[13.5px]"
        style={{ color: 'var(--text-strong)' }}
      />
      {suffix && <span className="ml-2">{suffix}</span>}
    </div>
  );
}

function FormSection({ icon: Icn, title, sub, num, children }) {
  return (
    <div
      className="theme-surface rounded-2xl"
      style={{ border: '1px solid var(--border-soft)', boxShadow: 'var(--elevation-1)' }}
    >
      <div
        className="px-6 py-4 flex items-center gap-3"
        style={{ borderBottom: '1px solid var(--border-soft)' }}
      >
        <span
          className="w-9 h-9 rounded-lg flex items-center justify-center font-mono font-bold text-[12px]"
          style={{ background: 'var(--brand-soft)', color: 'var(--brand)' }}
        >
          {num}
        </span>
        <div className="flex-1">
          <div className="text-[14.5px] font-semibold inline-flex items-center gap-2" style={{ color: 'var(--text-strong)' }}>
            {Icn && <Icn size={14} className="text-slate-400" />}
            {title}
          </div>
          <div className="text-[11.5px] mt-0.5" style={{ color: 'var(--text-soft)' }}>
            {sub}
          </div>
        </div>
      </div>
      <div className="px-6 py-2">
        {children}
      </div>
    </div>
  );
}

function PasswordStrength({ level = 2 }) {
  const labels = ['弱', '中', '强', '极强'];
  const colors = ['#EF4444', '#F59E0B', '#10B981', '#059669'];
  return (
    <div className="mt-2 inline-flex items-center gap-2">
      <div className="flex gap-1">
        {[0, 1, 2, 3].map((i) => (
          <span
            key={i}
            className="w-7 h-1 rounded-full"
            style={{ background: i < level ? colors[level - 1] : '#E2E8F0' }}
          />
        ))}
      </div>
      <span className="text-[11px] font-medium" style={{ color: colors[level - 1] }}>
        密码强度: {labels[level - 1]}
      </span>
    </div>
  );
}

function CascadeSelect({ items }) {
  return (
    <div className="flex items-center gap-2 flex-wrap">
      {items.map((it, i) => (
        <button
          key={i}
          className="semantic-hover min-h-11 px-3 rounded-lg inline-flex items-center gap-2 transition-colors min-w-[110px]"
          style={{ background: 'var(--surface-muted)' }}
        >
          <span className="text-[12px]" style={{ color: 'var(--text-soft)' }}>{it.label}</span>
          <span className="flex-1 text-[13.5px] font-medium text-left" style={{ color: 'var(--text-strong)' }}>{it.value}</span>
          <IconChevron size={12} className="text-slate-400" />
        </button>
      ))}
    </div>
  );
}

function PwInput({ value, onChange, placeholder }) {
  return (
    <div className="flex items-center min-h-11 rounded-lg px-3" style={{ background: 'var(--surface-muted)', border: '1px solid transparent' }}>
      <input type="password" value={value} placeholder={placeholder}
        onChange={(e) => onChange(e.target.value)}
        className="flex-1 bg-transparent outline-none text-[13.5px]" style={{ color: 'var(--text-strong)' }} />
    </div>
  );
}

/**
 * 新建学期的受控表单。window.ui.modal 只渲染 content 并在确认时 resolve，
 * 不提供字段能力，故把用户输入直接写回调用方传入的 draft 对象。
 */
function TermForm({ draft }) {
  const y = new Date().getFullYear();
  const [yearLabel, setYearLabel] = React.useState('');
  const [term, setTerm] = React.useState('S1');
  const [startDate, setStartDate] = React.useState('');
  const [endDate, setEndDate] = React.useState('');
  React.useEffect(() => {
    draft.yearLabel = yearLabel.trim(); draft.term = term;
    draft.startDate = startDate; draft.endDate = endDate;
  }, [yearLabel, term, startDate, endDate, draft]);
  const inp = 'w-full h-11 rounded-lg px-3 text-[13px] outline-none';
  const inpStyle = { background: 'var(--surface-muted)', border: '1px solid var(--border-soft)', color: 'var(--text-strong)' };
  return (
    <div className="space-y-3">
      <div>
        <div className="text-[12px] mb-1" style={{ color: 'var(--text-medium)' }}>学年 *</div>
        <input className={inp} style={inpStyle} value={yearLabel} placeholder={`${y}-${y + 1}`}
          onChange={(e) => setYearLabel(e.target.value)} />
      </div>
      <div>
        <div className="text-[12px] mb-1" style={{ color: 'var(--text-medium)' }}>学期 *</div>
        <select className={inp} style={inpStyle} value={term} onChange={(e) => setTerm(e.target.value)}>
          <option value="S1">第一学期</option>
          <option value="S2">第二学期</option>
        </select>
      </div>
      <div className="grid grid-cols-2 gap-3">
        <div>
          <div className="text-[12px] mb-1" style={{ color: 'var(--text-medium)' }}>开始日期 *</div>
          <input type="date" className={inp} style={inpStyle} value={startDate}
            onChange={(e) => setStartDate(e.target.value)} />
        </div>
        <div>
          <div className="text-[12px] mb-1" style={{ color: 'var(--text-medium)' }}>结束日期 *</div>
          <input type="date" className={inp} style={inpStyle} value={endDate}
            onChange={(e) => setEndDate(e.target.value)} />
        </div>
      </div>
      <div className="text-[12px] leading-relaxed" style={{ color: 'var(--text-weak)' }}>
        学期用于报表口径与档案标识；切换当前学期不会改动已生成的排班结果。
      </div>
    </div>
  );
}

const APPEARANCE_MODES = [
  { value: 'system', label: '跟随系统', desc: '随设备浅色或深色外观切换' },
  { value: 'light', label: '浅色', desc: '适合明亮教务办公室' },
  { value: 'dark', label: '深色', desc: '适合值班室与低照度环境' },
];

const APPEARANCE_PALETTES = [
  { value: 'indigo', label: '靛蓝', color: '#4f5fd1' },
  { value: 'blue', label: '蓝', color: '#2872c7' },
  { value: 'green', label: '绿', color: '#268568' },
  { value: 'rose', label: '玫红', color: '#b65370' },
  { value: 'cyan', label: '青', color: '#14788e' },
  { value: 'purple', label: '紫', color: '#7659ba' },
];

const readThemePayload = (payload) => window.JKXTTheme?.normalize?.(
  payload?.theme || payload?.preferences?.theme || payload
) || window.JKXTTheme.defaults;

const readSchoolAppearance = (payload) => ({
  palette: APPEARANCE_PALETTES.some((item) => item.value === (payload?.appearance?.defaultPalette || payload?.defaultPalette || payload?.appearance?.palette || payload?.palette || payload?.theme?.palette))
    ? (payload?.appearance?.defaultPalette || payload?.defaultPalette || payload?.appearance?.palette || payload?.palette || payload?.theme?.palette)
    : 'indigo',
});

function SettingsInlineState({ kind, children, onRetry }) {
  const tone = kind === 'error' ? { bg: 'var(--danger-soft)', fg: '#b42318' } : { bg: 'var(--surface-muted)', fg: 'var(--text-medium)' };
  return (
    <div role={kind === 'error' ? 'alert' : 'status'} className="flex items-center justify-between gap-3 rounded-xl px-3 py-2.5 text-[12.5px]"
      style={{ background: tone.bg, color: tone.fg, border: '1px solid var(--border-soft)' }}>
      <span>{children}</span>
      {onRetry && <button type="button" className="underline underline-offset-2 font-medium" onClick={onRetry}>重试</button>}
    </div>
  );
}

const settingsRequestState = (error, fallback) => ({
  status: error?.status === 403 ? 'permission' : 'error',
  message: error?.status === 403 ? '当前账号无权读取此设置。' : (error?.message || fallback),
});

function AppearanceSettings({ me }) {
  const cachedPreferences = window.JKXTTheme?.getPreferences?.() || window.JKXTTheme.defaults;
  const cachedSchool = window.JKXTTheme?.getSchoolAppearance?.() || { palette: 'indigo' };
  const [preferences, setPreferences] = React.useState(cachedPreferences);
  const [savedPreferences, setSavedPreferences] = React.useState(cachedPreferences);
  const [school, setSchool] = React.useState(cachedSchool);
  const [savedSchool, setSavedSchool] = React.useState(cachedSchool);
  const [preferenceState, setPreferenceState] = React.useState({ status: 'loading', message: '' });
  const [schoolState, setSchoolState] = React.useState({ status: 'loading', message: '' });
  const role = String(me?.user?.role || '').toUpperCase();
  const canManageSchool = role === 'ADMIN' || role === 'SUPER_ADMIN';

  const load = React.useCallback(async () => {
    setPreferenceState({ status: 'loading', message: '' });
    setSchoolState({ status: 'loading', message: '' });
    const [prefResult, schoolResult] = await Promise.allSettled([
      window.api.preferences.get(),
      window.api.appearance.get(),
    ]);
    if (prefResult.status === 'fulfilled') {
      const next = readThemePayload(prefResult.value);
      setPreferences(next); setSavedPreferences(next);
      window.JKXTTheme?.cachePreferences?.(next);
      setPreferenceState({ status: 'ready', message: '' });
    } else {
      setPreferenceState({ status: 'error', message: prefResult.reason?.message || '个人偏好读取失败' });
    }
    if (schoolResult.status === 'fulfilled') {
      const next = readSchoolAppearance(schoolResult.value);
      setSchool(next); setSavedSchool(next);
      window.JKXTTheme?.cacheSchoolAppearance?.(next);
      setSchoolState({ status: 'ready', message: '' });
    } else {
      setSchoolState({ status: 'error', message: schoolResult.reason?.message || '学校外观读取失败' });
    }
  }, []);

  React.useEffect(() => { load(); }, [load]);

  const savePreferences = async () => {
    setPreferenceState({ status: 'saving', message: '' });
    try {
      const result = await window.api.preferences.update({ theme: preferences });
      const next = readThemePayload(result || preferences);
      setPreferences(next); setSavedPreferences(next);
      window.JKXTTheme?.cachePreferences?.(next);
      setPreferenceState({ status: 'ready', message: '' });
      window.ui?.toast?.('个人外观已保存', { kind: 'success' });
    } catch (error) {
      setPreferenceState({ status: 'error', message: error?.message || '个人外观保存失败' });
    }
  };

  const saveSchool = async () => {
    if (!canManageSchool) return;
    setSchoolState({ status: 'saving', message: '' });
    try {
      const result = await window.api.appearance.update({ version: 1, defaultPalette: school.palette });
      const next = readSchoolAppearance(result || school);
      setSchool(next); setSavedSchool(next);
      window.JKXTTheme?.cacheSchoolAppearance?.(next);
      setSchoolState({ status: 'ready', message: '' });
      window.ui?.toast?.('学校默认色板已保存', { kind: 'success' });
    } catch (error) {
      setSchoolState({ status: 'error', message: error?.message || '学校外观保存失败' });
    }
  };

  const prefChanged = JSON.stringify(preferences) !== JSON.stringify(savedPreferences);
  const schoolChanged = school.palette !== savedSchool.palette;
  const disabled = preferenceState.status === 'loading' || preferenceState.status === 'saving';

  return (
    <>
      <FormSection num="01" icon={IconSettings} title="个人偏好" sub="外观仅影响当前账号；系统高对比度设置始终优先">
        {preferenceState.status === 'loading' && <SettingsInlineState>正在读取账号外观设置…</SettingsInlineState>}
        {preferenceState.status === 'error' && <SettingsInlineState kind="error" onRetry={load}>{preferenceState.message}；当前显示的是本机缓存，尚未视为已保存。</SettingsInlineState>}
        <div className="space-y-5" aria-busy={preferenceState.status === 'loading' || preferenceState.status === 'saving'}>
          <fieldset disabled={disabled}>
            <legend className="text-[12.5px] font-semibold mb-2" style={{ color: 'var(--text-strong)' }}>明暗模式</legend>
            <div className="grid grid-cols-1 gap-2 sm:grid-cols-3">
              {APPEARANCE_MODES.map((option) => (
                <button key={option.value} type="button" className="theme-choice rounded-xl px-3 py-3 text-left"
                  data-testid={`theme-mode-${option.value}`}
                  aria-pressed={preferences.mode === option.value}
                  onClick={() => setPreferences((current) => ({ ...current, mode: option.value }))}
                  style={{
                    background: preferences.mode === option.value ? 'var(--brand-soft)' : 'var(--surface-muted)',
                    border: `1px solid ${preferences.mode === option.value ? 'var(--brand)' : 'var(--border-soft)'}`,
                  }}>
                  <span className="block text-[13px] font-semibold" style={{ color: 'var(--text-strong)' }}>{option.label}</span>
                  <span className="block mt-1 text-[11.5px] leading-relaxed" style={{ color: 'var(--text-soft)' }}>{option.desc}</span>
                </button>
              ))}
            </div>
          </fieldset>
          <fieldset disabled={disabled}>
            <legend className="text-[12.5px] font-semibold mb-2" style={{ color: 'var(--text-strong)' }}>色板来源</legend>
            <div className="inline-flex rounded-xl p-1" style={{ background: 'var(--surface-muted)', border: '1px solid var(--border-soft)' }}>
              {[{ value: 'school', label: '跟随学校' }, { value: 'user', label: '个人选择' }].map((option) => (
                <button key={option.value} type="button" className="theme-choice h-11 rounded-lg px-4 text-[12.5px] font-medium"
                  aria-pressed={preferences.paletteSource === option.value}
                  onClick={() => setPreferences((current) => ({ ...current, paletteSource: option.value }))}
                  style={{ background: preferences.paletteSource === option.value ? 'var(--surface-raised)' : 'transparent', color: preferences.paletteSource === option.value ? 'var(--text-strong)' : 'var(--text-medium)', boxShadow: preferences.paletteSource === option.value ? 'var(--elevation-1)' : 'none' }}>
                  {option.label}
                </button>
              ))}
            </div>
          </fieldset>
          <fieldset disabled={disabled || preferences.paletteSource !== 'user'}>
            <legend className="text-[12.5px] font-semibold mb-2" style={{ color: 'var(--text-strong)' }}>个人色板</legend>
            <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-6">
              {APPEARANCE_PALETTES.map((option) => (
                <button key={option.value} type="button" className="palette-choice rounded-xl px-3 py-2.5 text-left disabled:opacity-50"
                  data-testid={`theme-palette-${option.value}`}
                  aria-pressed={preferences.palette === option.value}
                  onClick={() => setPreferences((current) => ({ ...current, palette: option.value }))}
                  style={{ background: 'var(--surface-muted)', border: `1px solid ${preferences.palette === option.value ? 'var(--brand)' : 'var(--border-soft)'}` }}>
                  <span className="inline-flex items-center gap-2 text-[12.5px] font-medium" style={{ color: 'var(--text-strong)' }}>
                    <span className="w-4 h-4 rounded-md" style={{ background: option.color, boxShadow: 'inset 0 1px 0 rgba(255,255,255,.35)' }} />{option.label}
                  </span>
                </button>
              ))}
            </div>
          </fieldset>
          <div className="flex items-center justify-end gap-2">
            <Button disabled={!prefChanged || preferenceState.status === 'saving'} onClick={() => setPreferences(savedPreferences)}>撤销</Button>
            <Button variant="primary" loading={preferenceState.status === 'saving'} disabled={!prefChanged} onClick={savePreferences}>保存个人偏好</Button>
          </div>
        </div>
      </FormSection>

      <FormSection num="02" icon={IconBuilding} title="学校外观" sub={canManageSchool ? '设置普通用户“跟随学校”时使用的默认色板' : '由学校管理员维护；当前账号只读'}>
        {schoolState.status === 'loading' && <SettingsInlineState>正在读取学校默认外观…</SettingsInlineState>}
        {schoolState.status === 'error' && <SettingsInlineState kind="error" onRetry={load}>{schoolState.message}</SettingsInlineState>}
        <fieldset disabled={!canManageSchool || schoolState.status === 'loading' || schoolState.status === 'saving'}>
          <legend className="sr-only">学校默认色板</legend>
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-6">
            {APPEARANCE_PALETTES.map((option) => (
              <button key={option.value} type="button" className="palette-choice rounded-xl px-3 py-3 text-left disabled:opacity-60"
                aria-pressed={school.palette === option.value}
                onClick={() => setSchool({ palette: option.value })}
                style={{ background: school.palette === option.value ? 'var(--brand-soft)' : 'var(--surface-muted)', border: `1px solid ${school.palette === option.value ? 'var(--brand)' : 'var(--border-soft)'}` }}>
                <span className="inline-flex items-center gap-2 text-[12.5px] font-medium" style={{ color: 'var(--text-strong)' }}>
                  <span className="w-4 h-4 rounded-md" style={{ background: option.color, boxShadow: 'inset 0 1px 0 rgba(255,255,255,.35)' }} />{option.label}
                </span>
              </button>
            ))}
          </div>
        </fieldset>
        <div className="mt-4 flex items-center justify-between gap-3">
          <span className="text-[11.5px]" style={{ color: 'var(--text-soft)' }}>{canManageSchool ? '不会提供任意色值输入，以保证对比度与品牌一致性。' : '如需修改，请联系学校管理员。'}</span>
          {canManageSchool && <Button variant="primary" loading={schoolState.status === 'saving'} disabled={!schoolChanged} onClick={saveSchool}>保存学校外观</Button>}
        </div>
      </FormSection>
    </>
  );
}

function SettingsPage() {
  const [me, setMe] = React.useState(null);
  const [meState, setMeState] = React.useState({ status: 'loading', message: '' });
  const [cur, setCur] = React.useState('');
  const [nw, setNw] = React.useState('');
  const [cfm, setCfm] = React.useState('');
  const [saving, setSaving] = React.useState(false);
  // 学年学期：此前顶栏提示「切换学期需在信息设置中操作」，但本页根本没有该区块——死循环。
  const [terms, setTerms] = React.useState([]);
  const [termsBusy, setTermsBusy] = React.useState(false);
  const [termsState, setTermsState] = React.useState({ status: 'loading', message: '' });
  // 津贴单价：organization.settings.allowanceUnit 决定所有津贴金额，onboarding 承诺
  // 「场次×时长×单价」，但此前全库没有任何接口能改它——建租户后焊死在默认 120 元/小时。
  const [allowanceUnit, setAllowanceUnit] = React.useState(null);
  const [allowanceDraft, setAllowanceDraft] = React.useState('');
  const [allowanceBusy, setAllowanceBusy] = React.useState(false);
  const [orgSettingsState, setOrgSettingsState] = React.useState({ status: 'loading', message: '' });

  const loadMe = React.useCallback(async () => {
    setMeState({ status: 'loading', message: '' });
    try {
      const result = await window.api.get('/auth/me');
      setMe(result || null);
      setMeState({ status: result ? 'ready' : 'empty', message: result ? '' : '登录资料为空。' });
    } catch (error) {
      setMe(null);
      setMeState(settingsRequestState(error, '账户与单位资料读取失败。'));
    }
  }, []);

  React.useEffect(() => { loadMe(); }, [loadMe]);

  const loadTerms = React.useCallback(async () => {
    setTermsState({ status: 'loading', message: '' });
    try {
      const rows = await window.api.terms.list();
      const next = Array.isArray(rows) ? rows : [];
      setTerms(next);
      setTermsState({ status: next.length ? 'ready' : 'empty', message: next.length ? '' : '尚未设置任何学年学期。' });
    } catch (error) {
      setTerms([]);
      setTermsState(settingsRequestState(error, '学年学期读取失败。'));
    }
  }, []);

  React.useEffect(() => { loadTerms(); }, [loadTerms]);

  const loadOrgSettings = React.useCallback(async () => {
    setOrgSettingsState({ status: 'loading', message: '' });
    try {
      const result = await window.api.orgSettings.get();
      const value = result?.allowanceUnit;
      if (value === null || value === undefined || value === '') {
        setAllowanceUnit(null); setAllowanceDraft('');
        setOrgSettingsState({ status: 'empty', message: '学校尚未配置津贴单价。' });
        return;
      }
      setAllowanceUnit(value); setAllowanceDraft(String(value));
      setOrgSettingsState({ status: 'ready', message: '' });
    } catch (error) {
      setAllowanceUnit(null); setAllowanceDraft('');
      setOrgSettingsState(settingsRequestState(error, '学校业务设置读取失败。'));
    }
  }, []);

  React.useEffect(() => { loadOrgSettings(); }, [loadOrgSettings]);

  const saveAllowanceUnit = async () => {
    const value = Number(allowanceDraft);
    if (!Number.isFinite(value) || value <= 0 || value > 9999) {
      window.ui.toast('津贴单价需为 0–9999 之间的数字（元/小时）', { kind: 'warning' });
      return;
    }
    if (value === Number(allowanceUnit)) return;
    const ok = await window.ui.confirm({
      title: `将津贴单价改为 ${value} 元/小时?`,
      desc: '金额为现算口径：修改后所有统计与报表（含历史统计页面）即时按新单价计算;已导出到本地的文件不受影响。',
      confirmText: '确认修改',
    });
    if (!ok) { setAllowanceDraft(String(allowanceUnit)); return; }
    setAllowanceBusy(true);
    try {
      const r = await window.api.orgSettings.update({ allowanceUnit: value });
      setAllowanceUnit(r.allowanceUnit);
      setAllowanceDraft(String(r.allowanceUnit));
      setOrgSettingsState({ status: 'ready', message: '' });
      window.ui.toast('津贴单价已更新', { kind: 'success', desc: `现为 ${r.allowanceUnit} 元/小时 · 全部统计即时生效` });
    } catch (e) {
      setOrgSettingsState(settingsRequestState(e, '津贴单价保存失败。'));
      window.ui.toast('保存失败', { kind: 'error', desc: (e && e.message) || '请稍后重试' });
      setAllowanceDraft(String(allowanceUnit));
    } finally { setAllowanceBusy(false); }
  };

  const activateTerm = async (t) => {
    if (t.current) return;
    const ok = await window.ui.confirm({
      title: `将「${t.name}」设为当前学期?`,
      desc: '学期用于报表口径与档案标识;切换不会改动已生成的排班结果。',
      confirmText: '设为当前',
    });
    if (!ok) return;
    setTermsBusy(true);
    try {
      await window.api.terms.activate(t.id);
      await loadTerms();
      // 顶栏徽章读的是 /auth/me 的 term，切换后需要刷新它
      try { const r = await window.api.get('/auth/me'); setMe(r); setMeState({ status: 'ready', message: '' }); window.__jkxtMe = r; } catch (_) {}
      window.dispatchEvent(new CustomEvent('jkxt:term-changed'));
      window.ui.toast('当前学期已切换', { kind: 'success', desc: t.name });
    } catch (e) {
      window.ui.toast('切换失败', { kind: 'error', desc: (e && e.message) || '请稍后重试' });
    } finally { setTermsBusy(false); }
  };

  // 注：window.ui.modal 只接受 content:<ReactNode>（见 src/overlay.jsx:47），没有 fields 能力，
  // 因此这里用一个受控子组件承载表单，通过 onSubmit 回调把值交回。
  const createTerm = async () => {
    const draft = { yearLabel: '', term: 'S1', startDate: '', endDate: '' };
    const result = await window.ui.modal({
      title: '新建学年学期',
      size: 'sm',
      content: <TermForm draft={draft} />,
    });
    if (!result && !draft.yearLabel) return;   // 取消
    if (!draft.yearLabel || !draft.startDate || !draft.endDate) {
      window.ui.toast('请填写完整', { kind: 'warning', desc: '学年、开始与结束日期均为必填' });
      return;
    }
    setTermsBusy(true);
    try {
      await window.api.terms.create({
        yearLabel: draft.yearLabel,
        term: draft.term || 'S1',
        startDate: draft.startDate,
        endDate: draft.endDate,
      });
      await loadTerms();
      window.ui.toast('学期已创建', { kind: 'success' });
    } catch (e) {
      window.ui.toast('创建失败', { kind: 'error', desc: (e && e.message) || '请检查填写内容' });
    } finally { setTermsBusy(false); }
  };

  const user = (me && me.user) || {};
  const org = (me && me.org) || {};
  const roleLabel = { SUPER_ADMIN: '超级管理员', ADMIN: '管理员', JIAOWU: '教务', VIEWER: '只读', TEACHER: '教师' }[user.role] || user.role || '—';
  const account = user.username || '—';
  const displayName = user.displayName || user.username || '—';
  const pwLevel = nw.length === 0 ? 0 : nw.length < 8 ? 1 : (/[A-Z]/.test(nw) && /[a-z]/.test(nw) && /\d/.test(nw)) ? 3 : 2;

  const changePassword = async () => {
    if (nw.length < 8) { window.ui.toast('新密码至少 8 位', { kind: 'warning' }); return; }
    if (nw !== cfm) { window.ui.toast('两次输入的新密码不一致', { kind: 'warning' }); return; }
    const ok = await window.ui.confirm({ title: '修改登录密码?', desc: '修改后下次登录请使用新密码，本次操作将记录审计日志。', confirmText: '确认修改' });
    if (!ok) return;
    setSaving(true);
    try {
      await window.api.post('/auth/me/change-password', { currentPassword: cur, newPassword: nw });
      window.ui.toast('密码已修改', { kind: 'success', desc: '下次登录请使用新密码 · 已记录审计' });
      setCur(''); setNw(''); setCfm('');
    } catch (e) {
      const msg = e && (e.code === 'AUTH_CURRENT_PASSWORD_INVALID' ? '当前密码不正确'
        : e.code === 'PASSWORD_TOO_WEAK' ? '新密码至少 8 位' : (e.message || '请稍后重试'));
      window.ui.toast('修改失败', { kind: 'error', desc: msg });
    } finally { setSaving(false); }
  };

  return (
    <PageShell
      crumbs={[org.name || '设置', '设置', '个人与学校设置']}
      title="个人与学校设置"
      subtitle="管理账号外观、学校默认色板、学期口径、津贴标准与账户安全"
    >
      <div className="grid grid-cols-1 gap-5 xl:grid-cols-[minmax(0,1fr)_320px]">
        <div className="space-y-5">

          <AppearanceSettings me={me} />

          {/* Module 1: 单位信息（真实数据，只读；如需修改单位档案请联系平台运营） */}
          <FormSection num="03" icon={IconBuilding} title="单位信息" sub="来自后端的真实单位档案;如需修改请联系平台运营">
            {meState.status === 'loading' && <SettingsInlineState>正在读取账户与单位资料…</SettingsInlineState>}
            {(meState.status === 'error' || meState.status === 'permission') && <SettingsInlineState kind="error" onRetry={loadMe}>{meState.message}</SettingsInlineState>}
            {meState.status === 'empty' && <SettingsInlineState onRetry={loadMe}>{meState.message}</SettingsInlineState>}
            {meState.status === 'ready' && <>
              <FormField label="单位名称" hint="显示于顶栏、登录页与导出报表页眉">
                <div className="inline-flex items-center gap-2">
                  <span className="text-[14px] font-semibold" style={{ color: 'var(--text-strong)' }}>{org.name || '—'}</span>
                  <Tag color={org.status === 'active' ? 'emerald' : 'slate'} dot>{org.status === 'active' ? '运行中' : (org.status || '—')}</Tag>
                </div>
              </FormField>
              <FormField label="租户标识" hint="系统内部唯一标识，创建后不可修改">
                <span className="font-mono text-[13px]" style={{ color: 'var(--text-medium)' }}>{(org.slug || '—')}</span>
              </FormField>
            </>}
          </FormSection>

          {/* Module 2: 学年学期 — 顶栏「切换学期」的真实落点（此前提示来这里，但这里没有该功能） */}
          <FormSection num="04" icon={IconCalendar} title="学年学期" sub="设定当前学期;用于报表口径与档案标识，切换不会改动已生成的排班结果">
            <div className="space-y-2">
              {termsState.status === 'loading' && <SettingsInlineState>正在读取学年学期…</SettingsInlineState>}
              {(termsState.status === 'error' || termsState.status === 'permission') && <SettingsInlineState kind="error" onRetry={loadTerms}>{termsState.message}</SettingsInlineState>}
              {termsState.status === 'empty' ? (
                <div className="text-[13px] py-2" style={{ color: 'var(--text-weak)' }}>
                  尚未设置任何学年学期 — 点击下方「新建学期」开始。
                </div>
              ) : termsState.status === 'ready' ? terms.map((t) => (
                <div key={t.id} className="settings-term-row flex flex-wrap items-center justify-between gap-3 rounded-lg px-3 py-3"
                  style={{ background: t.current ? 'var(--brand-soft)' : 'var(--surface-muted)',
                           border: t.current ? '1px solid var(--brand)' : '1px solid transparent' }}>
                  <div className="flex-1 min-w-0">
                    <div className="flex flex-wrap items-start gap-2">
                    <span className="text-[14px] font-semibold break-words" style={{ color: 'var(--text-strong)', overflowWrap: 'anywhere' }}>
                      {t.name || `${t.yearLabel} ${t.termLabel}`}
                    </span>
                    {t.current && <Tag color="indigo" dot>当前</Tag>}
                    </div>
                    <div className="text-[12px] leading-[18px] mt-1" style={{ color: 'var(--text-soft)' }}>
                      {t.startDate || '开始日期未提供'} ~ {t.endDate || '结束日期未提供'}
                    </div>
                  </div>
                  {!t.current && (
                    <button type="button" disabled={termsBusy} onClick={() => activateTerm(t)}
                      className="min-h-11 px-3 rounded-md text-[14px] font-medium shrink-0"
                      style={{ background: 'var(--surface-raised)', border: '1px solid var(--border-soft)', color: 'var(--text-medium)',
                               opacity: termsBusy ? .6 : 1 }}>
                      设为当前
                    </button>
                  )}
                </div>
              )) : null}
              {(termsState.status === 'ready' || termsState.status === 'empty') && <div className="pt-1">
                <button type="button" disabled={termsBusy} onClick={createTerm}
                  className="h-8 px-3 rounded-lg text-[12.5px] font-medium"
                  style={{ background: 'var(--surface-muted)', border: '1px solid var(--border-soft)', color: 'var(--text-medium)',
                           opacity: termsBusy ? .6 : 1 }}>
                  + 新建学期
                </button>
              </div>}
            </div>
          </FormSection>

          {/* Module 3: 账户安全 — 真实账户 + 可用的修改密码 */}
          {/* Module 3: 津贴标准 — 此前单价无任何修改入口（onboarding 承诺「场次×时长×单价」却焊死 120） */}
          <FormSection num="05" icon={IconChart} title="津贴标准" sub="监考津贴 = 时长 × 单价;金额为现算口径，修改后全部统计与报表即时生效">
            {orgSettingsState.status === 'loading' && <SettingsInlineState>正在读取学校业务设置…</SettingsInlineState>}
            {(orgSettingsState.status === 'error' || orgSettingsState.status === 'permission') && <SettingsInlineState kind="error" onRetry={loadOrgSettings}>{orgSettingsState.message}</SettingsInlineState>}
            {orgSettingsState.status === 'empty' && <SettingsInlineState onRetry={loadOrgSettings}>{orgSettingsState.message} 可在下方补充。</SettingsInlineState>}
            <FormField label="津贴单价（元/小时）" hint="所有津贴金额（教师列表、历史统计、导出报表）都按此单价现算">
              <div className="inline-flex items-center gap-2">
                <input
                  type="number" min="1" max="9999" step="1"
                  value={allowanceDraft}
                  disabled={allowanceBusy || !['ready', 'empty'].includes(orgSettingsState.status)}
                  onChange={(e) => setAllowanceDraft(e.target.value)}
                  onKeyDown={(e) => { if (e.key === 'Enter') saveAllowanceUnit(); }}
                  className="h-11 w-32 px-3 rounded-lg text-[13.5px] outline-none num"
                  style={{ background: 'var(--surface-muted)', border: '1px solid var(--border-strong)', color: 'var(--text-strong)' }}
                />
                <Button icon={IconCheck} disabled={allowanceBusy || !['ready', 'empty'].includes(orgSettingsState.status) || !allowanceDraft || Number(allowanceDraft) === Number(allowanceUnit)}
                  onClick={saveAllowanceUnit}>{allowanceBusy ? '保存中…' : '保存'}</Button>
              </div>
            </FormField>
          </FormSection>

          {/* Module 4: 账户安全 */}
          <FormSection num="06" icon={IconSettings} title="账户安全" sub="当前登录账户信息与登录密码">
            {meState.status === 'loading' && <SettingsInlineState>正在读取账户资料…</SettingsInlineState>}
            {(meState.status === 'error' || meState.status === 'permission') && <SettingsInlineState kind="error" onRetry={loadMe}>{meState.message}</SettingsInlineState>}
            {meState.status === 'empty' && <SettingsInlineState onRetry={loadMe}>{meState.message}</SettingsInlineState>}
            {meState.status === 'ready' && <>
            <FormField label="登录账号" hint="账号创建后不可修改">
              <div className="inline-flex items-center gap-2">
                <span className="font-mono text-[14px] font-semibold" style={{ color: 'var(--text-strong)' }}>{account}</span>
                <Tag color="indigo" dot>{roleLabel}</Tag>
              </div>
            </FormField>
            <FormField label="当前密码" required hint="为保护账户，修改密码需先验证当前密码">
              <PwInput value={cur} onChange={setCur} placeholder="输入当前登录密码" />
            </FormField>
            <FormField label="新密码" required hint="≥ 8 位，建议含大小写字母与数字">
              <PwInput value={nw} onChange={setNw} placeholder="输入新密码" />
              {nw.length > 0 && <PasswordStrength level={pwLevel} />}
            </FormField>
            <FormField label="确认新密码" required hint="再次输入以确认无误">
              <PwInput value={cfm} onChange={setCfm} placeholder="再次输入新密码" />
              {cfm.length > 0 && nw !== cfm && (
                <div className="mt-1.5 text-[11.5px] inline-flex items-center gap-1" style={{ color: '#DC2626' }}>
                  <IconAlert size={11} /> 两次输入的新密码不一致
                </div>
              )}
            </FormField>
            <div className="py-4 flex justify-end">
              <Button variant="primary" icon={IconCheck} disabled={saving || !cur || !nw || !cfm}
                onClick={changePassword}>{saving ? '修改中…' : '修改密码'}</Button>
            </div>
            </>}
          </FormSection>

        </div>

        {/* Right rail — 真实账户档案 */}
        <div className="space-y-4">
          <div className="theme-surface rounded-2xl p-5"
            style={{ border: '1px solid var(--border-soft)', boxShadow: 'var(--elevation-1)' }}>
            <div className="text-[13px] font-semibold mb-3" style={{ color: 'var(--text-strong)' }}>账户档案</div>
            {meState.status !== 'ready' ? <SettingsInlineState kind={meState.status === 'error' || meState.status === 'permission' ? 'error' : undefined} onRetry={loadMe}>{meState.status === 'loading' ? '正在读取账户档案…' : meState.message}</SettingsInlineState> : <><div className="flex items-center gap-3 mb-4">
              <AvatarChip name={displayName} size={56} />
              <div>
                <div className="text-[14px] font-semibold" style={{ color: 'var(--text-strong)' }}>{displayName}</div>
                <div className="text-[11.5px] mt-0.5" style={{ color: 'var(--text-soft)' }}>{roleLabel} · {org.name || '—'}</div>
              </div>
            </div>
            <div className="space-y-2 text-[12.5px]">
              {[
                { k: '登录账号', v: account, mono: true },
                { k: '角色', v: roleLabel, mono: false },
                { k: '所属单位', v: org.name || '—', mono: false },
                { k: '当前学期', v: (me && me.term && me.term.name) || '—', mono: false },
              ].map((r) => (
                <div key={r.k} className="flex items-baseline justify-between gap-3">
                  <span style={{ color: 'var(--text-soft)' }}>{r.k}</span>
                  <span className={r.mono ? 'font-mono' : ''} style={{ color: 'var(--text-strong)', fontSize: r.mono ? 12 : 13, textAlign: 'right' }}>{r.v}</span>
                </div>
              ))}
            </div>
            </>}
          </div>

          <div className="rounded-2xl p-5 text-white relative overflow-hidden"
            style={{ background: 'var(--brand)', boxShadow: 'var(--tile-shadow)' }}>
            <div className="text-[13px] font-semibold mb-1">安全提示</div>
            <div className="text-[11.5px] opacity-80 mb-3">定期更换密码、避免使用生日/电话;如遇异常登录请立即修改密码并联系管理员。</div>
            <svg className="absolute -right-4 -bottom-6 opacity-20" width="120" height="120" viewBox="0 0 120 120">
              <circle cx="60" cy="60" r="40" fill="none" stroke="currentColor" strokeWidth="1" />
              <circle cx="60" cy="60" r="55" fill="none" stroke="currentColor" strokeWidth="1" />
            </svg>
          </div>
        </div>
      </div>
    </PageShell>
  );
}

window.SettingsPage = SettingsPage;
