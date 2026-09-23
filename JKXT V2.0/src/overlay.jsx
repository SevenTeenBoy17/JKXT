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
let _overlayId = 0;
function _settleOverlay(type, item, value) {
  if (item._settled) return;
  item._settled = true;
  item._resolve?.(value ?? null);
  if (_ui[type] === item) { _ui[type] = null; _notify(); }
}
function _requestOverlayClose(type, value) {
  const item = _ui[type];
  if (!item || item._closing) return;
  item._closing = true;
  item._value = value;
  _notify();
}
function _dismissToast(t) { if (!t.closing) { t.closing = true; _notify(); } }
function useSurfaceMotion(ref, closing, kind, complete) {
  const completeRef = React.useRef(complete);
  completeRef.current = complete;
  React.useLayoutEffect(() => {
    if (window.JKXTMotion?.surface) return window.JKXTMotion.surface(ref.current, {
      kind, exit: closing, onComplete: closing ? () => completeRef.current?.() : undefined,
    });
    if (closing) completeRef.current?.();
  }, [closing, kind]);
}


window.ui = {
  toast(msg, opts = {}) {
    const id = Date.now() + Math.random();
    _ui.toasts.push({
      id, msg,
      kind: opts.kind || 'info',
      desc: opts.desc,
      action: opts.action,
      duration: opts.duration === 0 || opts.action ? 0 : Math.max(5000, opts.duration || 5000),
    });
    _notify();
    return id;
  },
  confirm(opts) {
    return new Promise((resolve) => {
      if (_ui.confirmation) _settleOverlay('confirmation', _ui.confirmation, false);
      _ui.confirmation = {
        _id: ++_overlayId,
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
      if (_ui.modal) _settleOverlay('modal', _ui.modal, null);
      _ui.modal = { ...opts, _id: ++_overlayId, _resolve: resolve };
      _notify();
    });
  },
  drawer(opts) {
    return new Promise((resolve) => {
      if (_ui.drawer) _settleOverlay('drawer', _ui.drawer, null);
      _ui.drawer = { ...opts, _id: ++_overlayId, _resolve: resolve };
      _notify();
    });
  },
  closeModal(value) {
    _requestOverlayClose('modal', value);
  },
  closeDrawer(value) {
    _requestOverlayClose('drawer', value);
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

// Both legacy and Blueprint portals share one ordering and background lock.
// Snapshots are held for the entire stack, so out-of-order unmount cannot
// restore an older layer's stale inert/overflow values over a newer layer.
window.JKXTDialogLayers = (() => {
  const entries = [];
  const snapshots = new Map();
  let overflow = '', originalTrigger = null;
  const top = () => entries[entries.length - 1];
  const restoreNode = (node, state) => {
    node.inert = state.inert;
    if (state.aria === null) node.removeAttribute('aria-hidden');
    else node.setAttribute('aria-hidden', state.aria);
  };
  const sync = () => {
    snapshots.forEach((state, node) => restoreNode(node, state));
    if (!entries.length) {
      snapshots.clear();
      document.body.style.overflow = overflow;
      return;
    }
    const current = top().container;
    Array.from(document.body.children).filter(node => !['SCRIPT','STYLE','LINK'].includes(node.tagName)).forEach(node => {
      if (!snapshots.has(node)) snapshots.set(node, { inert: node.inert, aria: node.getAttribute('aria-hidden') });
      if (node !== current && !node.contains(current)) { node.inert = true; node.setAttribute('aria-hidden', 'true'); }
    });
    entries.forEach((entry, index) => entry.container.style.setProperty('--jkxt-layer-index', String(120 + index * 2)));
    document.body.style.overflow = 'hidden';
  };
  const canFocus = node => node?.isConnected && !node.disabled && !node.closest('[inert]') && node.getClientRects().length;
  return {
    hasOpen: () => entries.length > 0,
    isTop: panel => top()?.panel === panel,
    register(panel, container, returnFocus) {
      const previousFocus = document.activeElement;
      if (!entries.length) { overflow = document.body.style.overflow; originalTrigger = previousFocus; }
      const entry = { panel, container, previousFocus, returnFocus };
      entries.push(entry);
      if (!container.contains(document.activeElement)) document.activeElement?.blur?.();
      sync();
      let released = false;
      return {
        isTop: () => top() === entry,
        release() {
          if (released) return;
          released = true;
          const wasTop = top() === entry;
          entries.splice(entries.indexOf(entry), 1);
          container.style.removeProperty('--jkxt-layer-index');
          sync();
          if (wasTop) {
            const candidates = [returnFocus?.(), previousFocus, top()?.panel, originalTrigger];
            const target = candidates.find(node => canFocus(node) && (!top() || top().panel.contains(node)));
            target?.focus({ preventScroll: true });
          }
          if (!entries.length) originalTrigger = null;
        },
      };
    },
  };
})();

function useDialogA11y(panelRef, onClose) {
  const closeRef = React.useRef(onClose);
  closeRef.current = onClose;
  React.useLayoutEffect(() => {
    const panel = panelRef.current;
    if (!panel) return;
    const layer = window.JKXTDialogLayers.register(panel, panel.closest('.ui-overlay') || panel);
    const sel = 'a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"])';
    const focusables = () => Array.from(panel.querySelectorAll(sel)).filter(el => el.getClientRects().length && !el.closest('[inert]'));
    (focusables()[0] || panel).focus({ preventScroll: true });
    const onKey = e => {
      if (!layer.isTop()) return;
      if (e.key === 'Escape') { e.preventDefault(); e.stopImmediatePropagation(); closeRef.current?.(); return; }
      if (e.key !== 'Tab') return;
      const f = focusables();
      if (!f.length) { e.preventDefault(); panel.focus(); return; }
      const index = f.indexOf(document.activeElement);
      if (e.shiftKey && index <= 0) { e.preventDefault(); f[f.length - 1].focus(); }
      else if (!e.shiftKey && (index < 0 || index === f.length - 1)) { e.preventDefault(); f[0].focus(); }
    };
    const containFocus = e => { if (layer.isTop() && !panel.contains(e.target)) panel.focus({ preventScroll: true }); };
    document.addEventListener('keydown', onKey, true);
    document.addEventListener('focusin', containFocus, true);
    return () => {
      document.removeEventListener('keydown', onKey, true);
      document.removeEventListener('focusin', containFocus, true);
      layer.release();
    };
  }, []);
}

// ── Toast ─────────────────────────────────────────────────────────
function Toast({ t }) {
  const ref = React.useRef(null);
  const [hovered, setHovered] = React.useState(false);
  const [focused, setFocused] = React.useState(false);
  const paused = hovered || focused;
  useSurfaceMotion(ref, !!t.closing, 'toast', () => {
    _ui.toasts = _ui.toasts.filter(item => item !== t); _notify();
  });
  React.useEffect(() => {
    if (paused || t.closing || !t.duration) return;
    const timer = setTimeout(() => _dismissToast(t), t.duration);
    return () => clearTimeout(timer);
  }, [paused, t.closing, t.duration]);

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
      ref={ref} role={t.kind === 'error' ? 'alert' : 'status'} aria-atomic="true"
      onMouseEnter={() => setHovered(true)} onMouseLeave={() => setHovered(false)}
      onFocusCapture={() => setFocused(true)} onBlurCapture={e => { if (!e.currentTarget.contains(e.relatedTarget)) setFocused(false); }}
      className="ui-toast pointer-events-auto rounded-xl px-4 py-3 flex items-start gap-3 min-w-[280px] max-w-[420px]"
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
          <div className="text-[12px] mt-1 leading-relaxed" style={{ color: 'var(--text-medium)' }}>
            {t.desc}
          </div>
        )}
        {t.action && (
          <button
            className="mt-1.5 text-[12px] font-medium hover:underline"
            style={{ color: s.iconBg }}
            onClick={t.action.onClick}
          >
            {t.action.label} →
          </button>
        )}
      </div>
      <button type="button" aria-label="关闭提示" className="ui-toast-close" onClick={() => _dismissToast(t)}>×</button>
    </div>
  );
}

// ── ConfirmDialog ─────────────────────────────────────────────────
function ConfirmDialog({ c }) {
  const closing = !!c._closing;
  const panelRef = React.useRef(null);
  const shellRef = React.useRef(null);
  const close = result => _requestOverlayClose('confirmation', result);
  useSurfaceMotion(shellRef, closing, 'confirm', () => _settleOverlay('confirmation', c, c._value));
  useDialogA11y(panelRef, () => close(false));
  const Icn = c.icon || (c.danger ? IconAlert : IconHelp);
  return (
    <div
      ref={shellRef} className="ui-overlay fixed inset-0 z-[114] flex items-center justify-center"
      
      onClick={() => close(false)}
    >
      <div className="absolute inset-0" style={{ background: 'rgba(15,23,42,.48)', backdropFilter: 'blur(4px)' }} />
      <div
        ref={panelRef}
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="qk-confirm-title"
        aria-describedby={c.desc ? 'qk-confirm-desc' : undefined}
        tabIndex={-1}
        onClick={(e) => e.stopPropagation()}
        className="ui-modal relative bg-white rounded-2xl p-6 w-full max-w-[460px] outline-none"
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
            <h3 id="qk-confirm-title" className="text-[16px] font-semibold" style={{ color: 'var(--text-strong)' }}>
              {c.title}
            </h3>
            {c.desc && (
              <p id="qk-confirm-desc" className="mt-2 text-[13px] leading-relaxed" style={{ color: 'var(--text-medium)' }}>
                {c.desc}
              </p>
            )}
          </div>
        </div>
        <div className="mt-6 flex items-center justify-end gap-2">
          <Button disabled={closing} onClick={() => close(false)}>{c.cancelText}</Button>
          <Button disabled={closing} variant={c.danger ? 'secondary' : 'primary'} danger={c.danger} onClick={() => close(true)}>
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
  const panelRef = React.useRef(null);
  const shellRef = React.useRef(null);
  useSurfaceMotion(shellRef, !!m._closing, 'dialog', () => _settleOverlay('modal', m, m._value));
  useDialogA11y(panelRef, () => window.ui.closeModal(null));
  return (
    <div ref={shellRef} className="ui-overlay fixed inset-0 z-[112] flex items-center justify-center" 
      onClick={() => window.ui.closeModal(null)}>
      <div className="absolute inset-0" style={{ background: 'rgba(15,23,42,.48)', backdropFilter: 'blur(4px)' }} />
      <div ref={panelRef} role="dialog" aria-modal="true" aria-labelledby="qk-modal-title" tabIndex={-1}
        onClick={(e) => e.stopPropagation()}
        className="ui-modal relative bg-white rounded-2xl flex flex-col max-h-[88vh] overflow-hidden outline-none"
        style={{ width: w, boxShadow: '0 32px 80px rgba(15,23,42,.24)' }}
      >
        <div className="px-6 py-4 flex items-center justify-between shrink-0" style={{ borderBottom: '1px solid var(--border-soft)' }}>
          <div>
            <h3 id="qk-modal-title" className="text-[16px] font-semibold" style={{ color: 'var(--text-strong)' }}>{m.title}</h3>
            {m.subtitle && <p className="text-[12px] mt-0.5" style={{ color: 'var(--text-soft)' }}>{m.subtitle}</p>}
          </div>
          <button onClick={() => window.ui.closeModal(null)} aria-label="关闭"
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
  const panelRef = React.useRef(null);
  const shellRef = React.useRef(null);
  useSurfaceMotion(shellRef, !!d._closing, 'drawer', () => _settleOverlay('drawer', d, d._value));
  useDialogA11y(panelRef, () => window.ui.closeDrawer(null));
  return (
    <div ref={shellRef} className="ui-overlay fixed inset-0 z-[110] flex justify-end" 
      onClick={() => window.ui.closeDrawer(null)}>
      <div className="absolute inset-0" style={{ background: 'rgba(15,23,42,.36)', backdropFilter: 'blur(2px)' }} />
      <div ref={panelRef} role="dialog" aria-modal="true" aria-labelledby="qk-drawer-title" tabIndex={-1}
        onClick={(e) => e.stopPropagation()}
        className="ui-drawer relative bg-white flex flex-col h-full overflow-hidden outline-none"
        style={{ width: w, boxShadow: '-24px 0 60px rgba(15,23,42,.16)' }}
      >
        <div className="px-6 py-4 flex items-center justify-between shrink-0" style={{ borderBottom: '1px solid var(--border-soft)' }}>
          <div>
            <h3 id="qk-drawer-title" className="text-[16px] font-semibold" style={{ color: 'var(--text-strong)' }}>{d.title}</h3>
            {d.subtitle && <p className="text-[12px] mt-0.5" style={{ color: 'var(--text-soft)' }}>{d.subtitle}</p>}
          </div>
          <div className="flex items-center gap-1">
            {d.headerRight}
            <button onClick={() => window.ui.closeDrawer(null)} aria-label="关闭"
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
  return ReactDOM.createPortal(
    <>
      <div className="ui-toast-stack fixed top-[76px] right-6 z-[100] space-y-2.5 pointer-events-none">
        {s.toasts.map((t) => <Toast key={t.id} t={t} />)}
      </div>
      {s.drawer && <Drawer key={s.drawer._id} d={s.drawer} />}
      {s.modal && <Modal key={s.modal._id} m={s.modal} />}
      {s.confirmation && <ConfirmDialog key={s.confirmation._id} c={s.confirmation} />}
    </>, document.body
  );
}

window.OverlayHost = OverlayHost;
