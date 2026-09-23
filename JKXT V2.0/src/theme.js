(function () {
  const USER_KEY = 'jkxt:theme:v1';
  const SCHOOL_KEY = 'jkxt:school-theme:v1';
  const IDENTITY_KEY = 'jkxt:theme-identity:v1';
  // Identity-scoped caches are deliberately inactive until /auth/me identifies
  // the current account. Reading the old generic keys before that point leaked
  // the previous account's palette onto the login/first-paint surface.
  let activeUserKey = '';
  let activeSchoolKey = '';
  const modes = new Set(['system', 'light', 'dark']);
  const palettes = new Set(['indigo', 'blue', 'green', 'rose', 'cyan', 'purple']);
  const defaults = Object.freeze({
    version: 1,
    mode: 'system',
    paletteSource: 'school',
    palette: 'indigo',
  });

  const safeParse = (value) => {
    try { return JSON.parse(value); } catch { return null; }
  };

  const normalize = (value) => ({
    version: 1,
    mode: modes.has(value?.mode) ? value.mode : defaults.mode,
    // `personal` existed only in an early browser cache. The public API uses
    // `user`; accept the legacy value when reading but never emit it again.
    paletteSource: value?.paletteSource === 'user' || value?.paletteSource === 'personal' ? 'user' : 'school',
    palette: palettes.has(value?.palette) ? value.palette : defaults.palette,
  });

  const read = (key) => {
    try { return safeParse(localStorage.getItem(key)); } catch { return null; }
  };

  const appearancePalette = (appearance) => appearance?.defaultPalette
    || appearance?.appearance?.defaultPalette
    || appearance?.appearance?.palette
    || appearance?.palette
    || appearance?.theme?.palette;

  const getSchoolAppearance = () => {
    const cached = activeSchoolKey ? read(activeSchoolKey) : null;
    const palette = appearancePalette(cached);
    return {
      ...(cached && typeof cached === 'object' ? cached : {}),
      defaultPalette: palettes.has(palette) ? palette : defaults.palette,
      palette: palettes.has(palette) ? palette : defaults.palette,
    };
  };

  const getPreferences = () => normalize(activeUserKey ? read(activeUserKey) : null);

  const resolveScheme = (mode) => {
    if (mode === 'light' || mode === 'dark') return mode;
    return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  };

  const highContrast = () => Boolean(
    window.matchMedia?.('(forced-colors: active)').matches
    || window.matchMedia?.('(prefers-contrast: more)').matches
  );

  const apply = (preferences = getPreferences(), schoolAppearance = getSchoolAppearance(), options = {}) => {
    const normalized = normalize(preferences);
    const schoolCandidate = appearancePalette(schoolAppearance);
    const schoolPalette = palettes.has(schoolCandidate) ? schoolCandidate : defaults.palette;
    const palette = normalized.paletteSource === 'user' ? normalized.palette : schoolPalette;
    const root = document.documentElement;
    root.dataset.scheme = resolveScheme(normalized.mode);
    root.dataset.palette = palette;
    root.dataset.contrast = highContrast() ? 'more' : 'normal';
    root.style.colorScheme = root.dataset.scheme;
    if (!options.silent) {
      window.dispatchEvent(new CustomEvent('jkxt:theme-changed', {
        detail: { preferences: normalized, schoolAppearance: { palette: schoolPalette }, resolvedScheme: root.dataset.scheme, resolvedPalette: palette },
      }));
    }
    return normalized;
  };

  const cachePreferences = (preferences) => {
    const normalized = normalize(preferences);
    try {
      if (activeUserKey) localStorage.setItem(activeUserKey, JSON.stringify(normalized));
    } catch {}
    apply(normalized, getSchoolAppearance());
    return normalized;
  };

  const cacheSchoolAppearance = (appearance) => {
    const candidate = appearancePalette(appearance);
    const palette = palettes.has(candidate) ? candidate : defaults.palette;
    // Preserve the complete response for version/permission metadata and future
    // fields instead of collapsing the server payload to a single palette.
    const normalized = {
      ...(appearance && typeof appearance === 'object' ? appearance : {}),
      defaultPalette: palette,
      palette,
    };
    try {
      if (activeSchoolKey) localStorage.setItem(activeSchoolKey, JSON.stringify(normalized));
    } catch {}
    apply(getPreferences(), normalized);
    return normalized;
  };

  const safeIdentityPart = (value) => String(value || '').trim().replace(/[^a-zA-Z0-9_.-]/g, '_');
  const identityScope = (profile) => {
    const user = safeIdentityPart(profile?.user?.id || profile?.user?.username || profile?.id || profile?.username);
    const org = safeIdentityPart(profile?.org?.id || profile?.org?.uuid || profile?.org?.slug || profile?.orgId);
    return user || org ? `${org || 'no-org'}__${user || 'no-user'}` : '';
  };

  const setIdentity = (profile) => {
    const scope = identityScope(profile);
    if (!scope) return '';
    const previous = (() => { try { return localStorage.getItem(IDENTITY_KEY) || ''; } catch { return ''; } })();
    activeUserKey = `${USER_KEY}:${scope}`;
    activeSchoolKey = `${SCHOOL_KEY}:${scope}`;
    try {
      // A bootstrap cache belongs to the last authenticated identity. Remove it
      // before resolving this account so one user's theme never flashes for another.
      if (previous !== scope) {
        localStorage.removeItem(USER_KEY);
        localStorage.removeItem(SCHOOL_KEY);
      }
      localStorage.setItem(IDENTITY_KEY, scope);
    } catch {}
    apply(getPreferences(), getSchoolAppearance(), { silent: true });
    return scope;
  };

  const clearIdentity = () => {
    activeUserKey = '';
    activeSchoolKey = '';
    try {
      localStorage.removeItem(IDENTITY_KEY);
      localStorage.removeItem(USER_KEY);
      localStorage.removeItem(SCHOOL_KEY);
    } catch {}
    apply(defaults, { defaultPalette: defaults.palette }, { silent: true });
  };

  const listeners = [
    window.matchMedia?.('(prefers-color-scheme: dark)'),
    window.matchMedia?.('(forced-colors: active)'),
    window.matchMedia?.('(prefers-contrast: more)'),
  ].filter(Boolean);
  listeners.forEach((query) => query.addEventListener?.('change', () => apply()));

  window.JKXTTheme = {
    USER_KEY,
    SCHOOL_KEY,
    IDENTITY_KEY,
    defaults,
    modes: Array.from(modes),
    palettes: Array.from(palettes),
    normalize,
    getPreferences,
    getSchoolAppearance,
    cachePreferences,
    cacheSchoolAppearance,
    setIdentity,
    clearIdentity,
    apply,
  };

  // Blocking head execution makes the correct scheme available before CSS paints.
  // Before identity is known, render a neutral system/indigo shell. Account
  // preferences are applied synchronously by setIdentity after /auth/me.
  apply(defaults, { defaultPalette: defaults.palette }, { silent: true });
})();
