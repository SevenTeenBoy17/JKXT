// Shared reversible motion: CSS owns controls, GSAP surfaces, Anime learning SVG.
(function initJKXTMotion() {
  'use strict';
  window.JKXTMotion?.revert?.();
  const root = document.documentElement;
  const preference = window.matchMedia('(prefers-reduced-motion: reduce)');
  const gsap = window.gsap;
  const active = new Set();
  let pageCleanup = null;
  let disposed = false;
  const reduced = () => preference.matches || document.hidden;
  const syncPreference = () => {
    root.dataset.motion = preference.matches ? 'reduced' : 'full';
    root.dataset.motionPaused = document.hidden ? 'true' : 'false';
    if (reduced()) [...active].forEach(finish => finish());
  };

  // GSAP contexts restore original inline values, never clear unrelated styles.
  // Cancellation restores the surface without resolving an obsolete close request.
  function surface(element, { kind = 'dialog', exit = false, onComplete } = {}) {
    if (!element || disposed || !gsap || reduced()) { onComplete?.(); return () => {}; }
    let context, settled = false;
    const release = (complete) => {
      if (settled) return;
      settled = true;
      active.delete(finish);
      context?.revert();
      if (complete) onComplete?.();
    };
    const finish = () => release(true);
    active.add(finish);
    const offset = kind === 'page' ? {} : kind === 'drawer' ? { x: 16 } : { y: 6 };
    context = gsap.context(() => {
      gsap.fromTo(element,
        exit ? { opacity: 1, x: 0, y: 0 } : { opacity: 0, ...offset },
        { opacity: exit ? 0 : 1, x: exit ? (offset.x || 0) : 0, y: exit ? (offset.y || 0) : 0,
          duration: exit ? .12 : .2, ease: exit ? 'power1.in' : 'power2.out', onComplete: finish });
    });
    return () => release(false);
  }

  function enterPage(stage, pageKey) {
    pageCleanup?.();
    const layer = stage?.querySelector('.page-layer-current');
    pageCleanup = surface(layer, { kind: 'page' });
    window.dispatchEvent(new CustomEvent('jkxt:motion-ready', { detail: { pageKey } }));
    return pageCleanup;
  }

  function animateLearning(element, fromPercent, toPercent) {
    if (!element) return () => {};
    const clamp = value => Math.max(0, Math.min(100, Number(value) || 0));
    const end = 100 - clamp(toPercent);
    let animation, settled = false;
    const finish = () => {
      if (settled) return;
      settled = true;
      animation?.cancel();
      element.style.strokeDashoffset = String(end);
      active.delete(finish);
    };
    if (disposed || reduced() || !window.anime?.animate || fromPercent === toPercent) {
      element.style.strokeDashoffset = String(end);
      return () => {};
    }
    active.add(finish);
    animation = window.anime.animate(element, {
      strokeDashoffset: [100 - clamp(fromPercent), end], duration: 260,
      ease: 'outQuad', onComplete: finish,
    });
    return finish;
  }

  function revert() {
    disposed = true;
    pageCleanup?.();
    [...active].forEach(finish => finish());
    preference.removeEventListener('change', syncPreference);
    document.removeEventListener('visibilitychange', syncPreference);
    window.removeEventListener('beforeunload', revert);
  }
  preference.addEventListener('change', syncPreference);
  document.addEventListener('visibilitychange', syncPreference);
  window.addEventListener('beforeunload', revert, { once: true });
  syncPreference();
  window.JKXTMotion = { enterPage, surface, animateLearning, reduced, refresh: syncPreference, revert };
  root.dataset.motionEngine = gsap ? 'ready' : 'fallback';
})();
