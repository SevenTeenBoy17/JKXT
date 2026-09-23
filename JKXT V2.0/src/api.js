(function () {
  const externalBase = '/api/v1';

  function normalizeBase(base) {
    return String(base || externalBase).replace(/\/+$/, '');
  }

  function defaultBase() {
    try {
      const storedBase = localStorage.getItem('jkxt:apiBase');
      if (storedBase) return normalizeBase(storedBase);
    } catch {}
    if (window.JKXT_API_BASE) return normalizeBase(window.JKXT_API_BASE);
    // Production, private Compose, preview and local reverse proxies all expose
    // the backend below the same origin.  A localhost port is not enough
    // information to infer a different backend (and used to misroute 19180 to
    // an unrelated :8080 listener).  Cross-origin development remains an
    // explicit opt-in via jkxt:apiBase or window.JKXT_API_BASE above.
    return externalBase;
  }

  function encodeQuery(query) {
    if (!query) return '';
    const params = query instanceof URLSearchParams ? query : new URLSearchParams();
    if (!(query instanceof URLSearchParams)) {
      Object.entries(query).forEach(([key, value]) => {
        if (value === undefined || value === null || value === '') return;
        if (Array.isArray(value)) value.forEach((item) => params.append(key, item));
        else params.set(key, value);
      });
    }
    const text = params.toString();
    return text ? '?' + text : '';
  }

  function resolveUrl(path, query) {
    if (/^https?:\/\//i.test(path)) return path + encodeQuery(query);
    const cleanPath = String(path || '').startsWith('/') ? String(path) : '/' + String(path || '');
    return api.base + cleanPath + encodeQuery(query);
  }

  async function parseBody(response) {
    const text = await response.text();
    if (!text) return null;
    try { return JSON.parse(text); }
    catch { return { message: text }; }
  }

  function normalizeError(response, payload) {
    const nested = payload?.error || {};
    const message = payload?.message || nested.message || `请求失败（HTTP ${response.status}）`;
    const error = new Error(message);
    error.status = response.status;
    error.code = payload?.code || nested.code || 'API_ERROR';
    error.details = payload?.details || nested.details || payload?.errors || payload;
    error.traceId = payload?.traceId || nested.traceId || response.headers.get('X-Trace-Id') || null;
    error.response = payload;
    return error;
  }

  function bodyInit(body, headers) {
    if (body === undefined || body === null) return undefined;
    if (body instanceof FormData || body instanceof Blob || body instanceof ArrayBuffer) return body;
    if (typeof body === 'string') return body;
    headers['Content-Type'] = headers['Content-Type'] || 'application/json';
    return JSON.stringify(body);
  }

  const api = {
    externalBase,
    base: defaultBase(),

    setBase(base) {
      api.base = normalizeBase(base);
      return api.base;
    },

    url(path, query) {
      return resolveUrl(path, query);
    },

    async request(path, options = {}) {
      const headers = { ...(options.headers || {}) };
      const init = {
        method: options.method || 'GET',
        credentials: options.credentials || 'include',
        headers,
        signal: options.signal,
      };

      const body = bodyInit(options.body, headers);
      if (body !== undefined) init.body = body;

      const response = await fetch(resolveUrl(path, options.query), init);
      const payload = await parseBody(response);

      if (!response.ok) {
        const error = normalizeError(response, payload);
        if (response.status === 401) {
          window.dispatchEvent(new CustomEvent('jkxt:api-unauthorized', { detail: error }));
        }
        throw error;
      }

      return payload;
    },

    get(path, query, options = {}) {
      return api.request(path, { ...options, method: 'GET', query });
    },

    post(path, body, options = {}) {
      return api.request(path, { ...options, method: 'POST', body });
    },

    put(path, body, options = {}) {
      return api.request(path, { ...options, method: 'PUT', body });
    },

    patch(path, body, options = {}) {
      return api.request(path, { ...options, method: 'PATCH', body });
    },

    del(path, body, options = {}) {
      return api.request(path, { ...options, method: 'DELETE', body });
    },

    upload(path, files, fields = {}, options = {}) {
      const form = new FormData();
      Object.entries(fields || {}).forEach(([key, value]) => {
        if (value === undefined || value === null) return;
        form.append(key, value);
      });

      if (files instanceof File || files instanceof Blob) {
        form.append(options.fileField || 'file', files);
      } else if (Array.isArray(files)) {
        files.forEach((file) => form.append(options.fileField || 'files', file));
      } else if (files && typeof files === 'object') {
        Object.entries(files).forEach(([key, value]) => {
          if (Array.isArray(value)) value.forEach((file) => form.append(key, file));
          else if (value !== undefined && value !== null) form.append(key, value);
        });
      }

      return api.request(path, { ...options, method: options.method || 'POST', body: form });
    },

    eventSource(path, query) {
      return new EventSource(resolveUrl(path, query), { withCredentials: true });
    },

    wsUrl(path, query) {
      const url = new URL(resolveUrl(path, query), location.origin);
      url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
      return url.toString();
    },

    webSocket(path, protocols, query) {
      const url = api.wsUrl(path, query);
      return protocols === undefined ? new WebSocket(url) : new WebSocket(url, protocols);
    },
  };

  api.auth = {
    captcha: () => api.post('/auth/captcha', {}),
    login: (body) => api.post('/auth/login', body),
    logout: () => api.post('/auth/logout', {}),
    me: () => api.get('/auth/me'),
  };

  api.preferences = {
    get: () => api.get('/auth/me/preferences'),
    update: (body, options = {}) => api.patch('/auth/me/preferences', body, options),
  };

  api.appearance = {
    get: () => api.get('/org/settings/appearance'),
    update: (body, options = {}) => api.put('/org/settings/appearance', body, options),
  };

  const jointInputResources = Object.freeze([
    'demands', 'demand-classes', 'demand-windows', 'candidates', 'enrollments',
    'candidate-windows', 'special-requirements', 'room-pool', 'room-windows',
    'seat-topology', 'teacher-pool', 'avoidances', 'campus-travel',
  ]);

  const jointInputPath = (revisionId, resource) => {
    if (!jointInputResources.includes(resource)) throw new Error('Unsupported joint-planning input resource: ' + resource);
    return '/plan-revisions/' + encodeURIComponent(revisionId) + '/inputs/' + encodeURIComponent(resource);
  };

  api.examPlans = {
    list: (query) => api.get('/exam-plans', query),
    get: (id) => api.get('/exam-plans/' + encodeURIComponent(id)),
    create: (body, options = {}) => api.post('/exam-plans', body, options),
    revisions: (id) => api.get('/exam-plans/' + encodeURIComponent(id) + '/revisions'),
    revision: (planId, revisionId) => api.get('/exam-plans/' + encodeURIComponent(planId) + '/revisions/' + encodeURIComponent(revisionId)),
    validate: (planId, revisionId, options = {}) => api.post('/exam-plans/' + encodeURIComponent(planId) + '/revisions/' + encodeURIComponent(revisionId) + '/validate', {}, options),
    createRevision: (planId, body, options = {}) => api.post('/exam-plans/' + encodeURIComponent(planId) + '/revisions', body, options),
    startPlanning: (planId, revisionId, body, options = {}) => api.post('/exam-plans/' + encodeURIComponent(planId) + '/revisions/' + encodeURIComponent(revisionId) + '/planning-jobs', body, options),
    planningJob: (jobId) => api.get('/planning-jobs/' + encodeURIComponent(jobId)),
    cancelPlanning: (jobId) => api.post('/planning-jobs/' + encodeURIComponent(jobId) + '/cancel', {}),
    submitApproval: (planId, revisionId, options = {}) => api.post('/exam-plans/' + encodeURIComponent(planId) + '/revisions/' + encodeURIComponent(revisionId) + '/submit-approval', {}, options),
    approve: (planId, revisionId, body = {}, options = {}) => api.post('/exam-plans/' + encodeURIComponent(planId) + '/revisions/' + encodeURIComponent(revisionId) + '/approve', body, options),
    reject: (planId, revisionId, body = {}, options = {}) => api.post('/exam-plans/' + encodeURIComponent(planId) + '/revisions/' + encodeURIComponent(revisionId) + '/reject', body, options),
    publish: (planId, revisionId, options = {}) => api.post('/exam-plans/' + encodeURIComponent(planId) + '/revisions/' + encodeURIComponent(revisionId) + '/publish', {}, options),
    emergencyChange: (planId, revisionId, body, options = {}) => api.post('/exam-plans/' + encodeURIComponent(planId) + '/revisions/' + encodeURIComponent(revisionId) + '/emergency-changes', body, options),
    sessions: (revisionId) => api.get('/plan-revisions/' + encodeURIComponent(revisionId) + '/sessions'),
    rooms: (revisionId) => api.get('/plan-revisions/' + encodeURIComponent(revisionId) + '/rooms'),
    seats: (revisionId) => api.get('/plan-revisions/' + encodeURIComponent(revisionId) + '/seats'),
    assignments: (revisionId) => api.get('/plan-revisions/' + encodeURIComponent(revisionId) + '/duties'),
    constraints: (revisionId) => api.get('/plan-revisions/' + encodeURIComponent(revisionId) + '/constraints'),
    jointInputResources,
    jointInputSnapshot: (revisionId, resource, options = {}) => api.get(jointInputPath(revisionId, resource), undefined, options),
    replaceJointInput: (revisionId, resource, items, options = {}) => api.post(jointInputPath(revisionId, resource), { items }, options),
  };

  api.operations = {
    readiness: () => api.get('/operations/readiness'),
    approvals: () => api.get('/approval-requests'),
    emergencyChanges: () => api.get('/emergency-changes'),
  };

  // 学年学期（后端 org/api/AcademicTermController）。此前前端连"想调都没得调"——
  // api.js 里没有任何 term 方法，顶栏只能提示"请到信息设置操作"而那里没有该功能。
  // 机构业务设置（org/api/OrgSettingsController）。津贴单价此前全库无修改接口——
  // onboarding 承诺「场次×时长×单价」，但单价建租户后焊死在默认 120。
  api.orgSettings = {
    get: () => api.get('/org/settings'),
    update: (body) => api.put('/org/settings', body),
  };

  api.terms = {
    list: () => api.get('/terms'),
    current: () => api.get('/terms/current'),
    create: (body) => api.post('/terms', body),
    update: (id, body) => api.put('/terms/' + id, body),
    activate: (id) => api.post('/terms/' + id + '/activate', {}),
    remove: (id) => api.del('/terms/' + id),
  };

  window.api = api;
})();
