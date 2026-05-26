// Overlay system — global Toast / Confirm / Modal / Drawer
// Public API on window.ui — usable from any component:
//   ui.toast('保存成功', { kind: 'success', desc: '...' })
//   await ui.confirm({ title, desc, confirmText, danger })   → boolean
//   await ui.modal({ title, content: <ReactNode>, size })    → result or null
//   await ui.drawer({ title, content: <ReactNode>, width })  → result or null

const _ui = {
  toasts: [],
  modal: null,
  drawer: null,
  confirmation: null,
  subscribers: new Set(),
};

const _notify = () => _ui.subscribers.forEach((f) => f());

window.ui = {
  toast(msg, opts = {}) {
    const id = Date.now() + Math.random();
    _ui.toasts.push({
      id, msg,
      kind: opts.kind || 'info',
      desc: opts.desc,
      action: opts.action,
    });
    _notify();
    setTimeout(() => {
      _ui.toasts = _ui.toasts.filter((t) => t.id !== id);
      _notify();
    }, opts.duration || 3500);
  },
  confirm(opts) {
    return new Promise((resolve) => {
      _ui.confirmation = {
        title: opts.title || '确认操作',
        desc: opts.desc,
        confirmText: opts.confirmText || '确认',
        cancelText: opts.cancelText || '取消',
        danger: !!opts.danger,
        icon: opts.icon,
        _resolve: resolve,
      };
      _notify();
    });
  },
  modal(opts) {
    return new Promise((resolve) => {
      _ui.modal = { ...opts, _resolve: resolve };
      _notify();
    });
  },
  drawer(opts) {
    return new Promise((resolve) => {
      _ui.drawer = { ...opts, _resolve: resolve };
      _notify();
    });
  },
  closeModal(value) {
    if (_ui.modal) { _ui.modal._resolve?.(value ?? null); _ui.modal = null; _notify(); }
  },
  closeDrawer(value) {
    if (_ui.drawer) { _ui.drawer._resolve?.(value ?? null); _ui.drawer = null; _notify(); }
  },
};

function _useOverlay() {
  const [, force] = React.useReducer((x) => x + 1, 0);
  React.useEffect(() => {
    _ui.subscribers.add(force);
    return () => _ui.subscribers.delete(force);
  }, []);
  return _ui;
}

// ── Toast ─────────────────────────────────────────────────────────
function Toast({ t }) {
  const map = {
    success: { bg: '#ECFDF5', border: '#A7F3D0', fg: '#047857', icon: IconCheck, iconBg: '#10B981' },
    info:    { bg: '#EEF2FF', border: '#C7D2FE', fg: '#4338CA', icon: IconBell,  iconBg: '#6366F1' },
    warning: { bg: '#FFF7ED', border: '#FED7AA', fg: '#92400E', icon: IconAlert, iconBg: '#F59E0B' },
    error:   { bg: '#FEE2E2', border: '#FCA5A5', fg: '#991B1B', icon: IconAlert, iconBg: '#EF4444' },
  };
  const s = map[t.kind] || map.info;
  const I = s.icon;
  return (
    <div
      className="pointer-events-auto rounded-xl px-4 py-3 flex items-start gap-3 min-w-[280px] max-w-[420px] toast-enter"
      style={{
        background: '#fff',
        border: `1px solid ${s.border}`,
        boxShadow: '0 12px 32px rgba(15,23,42,.12), 0 4px 8px rgba(15,23,42,.06)',
      }}
    >
      <span
        className="w-7 h-7 rounded-lg flex items-center justify-center shrink-0"
        style={{ background: s.iconBg, color: '#fff' }}
      >
        <I size={14} strokeWidth={2.5} />
      </span>
      <div className="flex-1 min-w-0">
        <div className="text-[13px] font-semibold leading-tight" style={{ color: 'var(--text-strong)' }}>
          {t.msg}
        </div>
        {t.desc && (
          <div className="text-[11.5px] mt-1 leading-relaxed" style={{ color: 'var(--text-medium)' }}>
            {t.desc}
          </div>
        )}
        {t.action && (
          <button
            className="mt-1.5 text-[11.5px] font-medium hover:underline"
            style={{ color: s.iconBg }}
            onClick={t.action.onClick}
          >
            {t.action.label} →
          </button>
        )}
      </div>
    </div>
  );
}

// ── ConfirmDialog ─────────────────────────────────────────────────
function ConfirmDialog({ c }) {
  const [closing, setClosing] = React.useState(false);
  const close = (result) => {
    setClosing(true);
    setTimeout(() => {
      c._resolve(result);
      _ui.confirmation = null;
      _notify();
    }, 140);
  };
  const Icn = c.icon || (c.danger ? IconAlert : IconHelp);
  return (
    <div
      className={`fixed inset-0 z-[110] flex items-center justify-center ${closing ? 'overlay-exit' : 'overlay-enter'}`}
      onClick={() => close(false)}
    >
      <div className="absolute inset-0" style={{ background: 'rgba(15,23,42,.48)', backdropFilter: 'blur(4px)' }} />
      <div
        onClick={(e) => e.stopPropagation()}
        className={`relative bg-white rounded-2xl p-6 min-w-[400px] max-w-[460px] ${closing ? 'modal-exit' : 'modal-enter'}`}
        style={{ boxShadow: '0 32px 80px rgba(15,23,42,.24)' }}
      >
        <div className="flex items-start gap-4">
          <span
            className="w-12 h-12 rounded-2xl flex items-center justify-center shrink-0"
            style={{
              background: c.danger ? '#FEE2E2' : 'var(--brand-soft)',
              color: c.danger ? '#DC2626' : 'var(--brand)',
            }}
          >
            <Icn size={22} strokeWidth={2} />
          </span>
          <div className="flex-1 pt-1">
            <h3 className="text-[16px] font-semibold" style={{ color: 'var(--text-strong)' }}>
              {c.title}
            </h3>
            {c.desc && (
              <p className="mt-2 text-[13px] leading-relaxed" style={{ color: 'var(--text-medium)' }}>
                {c.desc}
              </p>
            )}
          </div>
        </div>
        <div className="mt-6 flex items-center justify-end gap-2">
          <Button onClick={() => close(false)}>{c.cancelText}</Button>
          <Button variant={c.danger ? 'secondary' : 'primary'} danger={c.danger} onClick={() => close(true)}>
            {c.confirmText}
          </Button>
        </div>
      </div>
    </div>
  );
}

// ── Modal ─────────────────────────────────────────────────────────
function Modal({ m }) {
  const sizes = { sm: 420, md: 560, lg: 720, xl: 920 };
  const w = sizes[m.size] || sizes.md;
  return (
    <div className="fixed inset-0 z-[110] flex items-center justify-center overlay-enter"
      onClick={() => window.ui.closeModal(null)}>
      <div className="absolute inset-0" style={{ background: 'rgba(15,23,42,.48)', backdropFilter: 'blur(4px)' }} />
      <div onClick={(e) => e.stopPropagation()}
        className="relative bg-white rounded-2xl flex flex-col modal-enter max-h-[88vh] overflow-hidden"
        style={{ width: w, boxShadow: '0 32px 80px rgba(15,23,42,.24)' }}
      >
        <div className="px-6 py-4 flex items-center justify-between shrink-0" style={{ borderBottom: '1px solid var(--border-soft)' }}>
          <div>
            <h3 className="text-[16px] font-semibold" style={{ color: 'var(--text-strong)' }}>{m.title}</h3>
            {m.subtitle && <p className="text-[12px] mt-0.5" style={{ color: 'var(--text-soft)' }}>{m.subtitle}</p>}
          </div>
          <button onClick={() => window.ui.closeModal(null)}
            className="w-8 h-8 rounded-lg inline-flex items-center justify-center hover:bg-slate-100" style={{ color: 'var(--text-soft)' }}>
            <svg width="14" height="14" viewBox="0 0 14 14" fill="none"><path d="M3 3 L11 11 M11 3 L3 11" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"/></svg>
          </button>
        </div>
        <div className="px-6 py-5 overflow-y-auto flex-1 scrollbar-thin">
          {m.content}
        </div>
        {m.footer && (
          <div className="px-6 py-4 flex items-center justify-end gap-2 shrink-0" style={{ borderTop: '1px solid var(--border-soft)', background: '#FAFBFC' }}>
            {m.footer}
          </div>
        )}
      </div>
    </div>
  );
}

// ── Drawer ────────────────────────────────────────────────────────
function Drawer({ d }) {
  const w = d.width || 480;
  return (
    <div className="fixed inset-0 z-[110] flex justify-end overlay-enter"
      onClick={() => window.ui.closeDrawer(null)}>
      <div className="absolute inset-0" style={{ background: 'rgba(15,23,42,.36)', backdropFilter: 'blur(2px)' }} />
      <div onClick={(e) => e.stopPropagation()}
        className="relative bg-white flex flex-col drawer-enter h-full overflow-hidden"
        style={{ width: w, boxShadow: '-24px 0 60px rgba(15,23,42,.16)' }}
      >
        <div className="px-6 py-4 flex items-center justify-between shrink-0" style={{ borderBottom: '1px solid var(--border-soft)' }}>
          <div>
            <h3 className="text-[16px] font-semibold" style={{ color: 'var(--text-strong)' }}>{d.title}</h3>
            {d.subtitle && <p className="text-[12px] mt-0.5" style={{ color: 'var(--text-soft)' }}>{d.subtitle}</p>}
          </div>
          <div className="flex items-center gap-1">
            {d.headerRight}
            <button onClick={() => window.ui.closeDrawer(null)}
              className="w-8 h-8 rounded-lg inline-flex items-center justify-center hover:bg-slate-100" style={{ color: 'var(--text-soft)' }}>
              <svg width="14" height="14" viewBox="0 0 14 14" fill="none"><path d="M3 3 L11 11 M11 3 L3 11" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"/></svg>
            </button>
          </div>
        </div>
        <div className="px-6 py-5 overflow-y-auto flex-1 scrollbar-thin">
          {d.content}
        </div>
        {d.footer && (
          <div className="px-6 py-4 flex items-center justify-end gap-2 shrink-0" style={{ borderTop: '1px solid var(--border-soft)', background: '#FAFBFC' }}>
            {d.footer}
          </div>
        )}
      </div>
    </div>
  );
}

// ── Host ──────────────────────────────────────────────────────────
function OverlayHost() {
  const s = _useOverlay();
  return (
    <>
      <style>{`
        @keyframes overlayIn { from { opacity: 0; } to { opacity: 1; } }
        @keyframes overlayOut { from { opacity: 1; } to { opacity: 0; } }
        @keyframes modalIn { from { opacity: 0; transform: scale(.96) translateY(8px); } to { opacity: 1; transform: scale(1) translateY(0); } }
        @keyframes modalOut { from { opacity: 1; transform: scale(1); } to { opacity: 0; transform: scale(.96); } }
        @keyframes drawerIn { from { transform: translateX(100%); } to { transform: translateX(0); } }
        @keyframes toastIn { from { opacity: 0; transform: translateX(20px); } to { opacity: 1; transform: translateX(0); } }
        .overlay-enter { animation: overlayIn 160ms ease-out; }
        .overlay-exit  { animation: overlayOut 140ms ease-in forwards; }
        .modal-enter   { animation: modalIn 220ms cubic-bezier(0,0,.2,1); }
        .modal-exit    { animation: modalOut 140ms ease-in forwards; }
        .drawer-enter  { animation: drawerIn 240ms cubic-bezier(0,0,.2,1); }
        .toast-enter   { animation: toastIn 220ms cubic-bezier(0,0,.2,1); }
      `}</style>
      <div className="fixed top-[76px] right-6 z-[100] space-y-2.5 pointer-events-none">
        {s.toasts.map((t) => <Toast key={t.id} t={t} />)}
      </div>
      {s.confirmation && <ConfirmDialog c={s.confirmation} />}
      {s.modal && <Modal m={s.modal} />}
      {s.drawer && <Drawer d={s.drawer} />}
    </>
  );
}

window.OverlayHost = OverlayHost;
