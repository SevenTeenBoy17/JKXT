/* ──────────────────────────────────────────────────────────────────────────
   FluidCursor — transparent water-ripple cursor interaction
   ----------------------------------------------------------------------------
   A real water-surface simulation: a damped 2-D wave-equation height field
   (two ping-pong buffers, the classic "Hugo Elias" water effect). Wherever the
   cursor hovers or sweeps it drops a disturbance into the surface, which
   propagates outward as concentric ripples (涟漪) that interfere, reflect and
   fade. The surface itself is rendered COMPLETELY TRANSPARENT — only the ripple
   wavefronts catch light (thin bright refractive rings + a faint chromatic
   edge), so the login UI shows straight through, like ripples on clear water.

   Pure Canvas 2D — no WebGL context, no dependency, no injected <script>
   string. Mounted by a React effect on the login page only (src/pages/login.jsx).
   Usage:
     const fx = window.FluidCursor.mount();   // overlay + listeners
     fx.destroy();                            // full teardown
   ────────────────────────────────────────────────────────────────────────── */
(function () {
  'use strict';

  function mount(opts) {
    opts = opts || {};
    // Respect reduced-motion — no animated overlay for those users.
    if (window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      return { destroy: function () {} };
    }

    // ── Tunables ──────────────────────────────────────────────────────────────
    var COLS        = opts.cols        || 210;   // horizontal grid res (rows from aspect → round ripples)
    var DAMP        = opts.damp        || 0.962;  // ripple persistence (→1 = longer-lived)
    var SPREAD      = opts.spread      || 0.5;    // wave propagation factor (0.5 = standard)
    var DROP_R      = opts.dropRadius  || 2.4;    // disturbance radius (cells)
    var MOVE_AMP    = opts.moveAmp     || 0.45;   // base ripple strength on move
    var SPEED_AMP   = opts.speedAmp    || 0.020;  // extra strength per px/move of speed
    var MAX_AMP     = opts.maxAmp      || 1.6;    // cap on a single disturbance
    var IDLE_MS     = opts.idleMs      || 620;    // gentle ripple cadence while hovering still
    // render (transparent — only wavefronts catch light)
    var EDGE        = opts.edge        || 3.2;    // gradient → ripple-ring brightness
    var SPEC        = opts.spec        || 1.25;   // specular sparkle on the lit face
    var LX          = opts.lightX      || -0.6;   // light direction (screen-space)
    var LY          = opts.lightY      || -0.62;
    var CA_GAIN     = opts.caGain      || 2.6;    // gradient → chromatic split
    var CA_MAX      = opts.caMax       || 0.8;    // cap on chromatic fringe
    var A_EDGE      = opts.edgeAlpha   || 2.0;    // ring coverage
    var A_SPEC      = opts.specAlpha   || 0.7;    // sparkle coverage
    var MAX_ALPHA   = opts.maxAlpha    || 0.5;    // cap so it stays transparent

    // ── Overlay canvas + low-res ripple buffer ─────────────────────────────────
    var canvas = document.createElement('canvas');
    canvas.setAttribute('aria-hidden', 'true');
    canvas.style.cssText =
      'position:fixed;inset:0;width:100%;height:100%;pointer-events:none;' +
      'z-index:9999;mix-blend-mode:screen;filter:blur(0.5px);';
    document.body.appendChild(canvas);
    var ctx = canvas.getContext('2d');

    var grid = document.createElement('canvas');
    var gctx = grid.getContext('2d');

    var vw = 0, vh = 0, dpr = Math.min(window.devicePixelRatio || 1, 2);
    var gw = 0, gh = 0;
    var cur, prev, img, data;

    function buildGrid() {
      gw = COLS;
      gh = Math.max(8, Math.round(COLS * vh / vw));   // match aspect → circular ripples
      cur  = new Float32Array(gw * gh);
      prev = new Float32Array(gw * gh);
      grid.width = gw; grid.height = gh;
      img = gctx.createImageData(gw, gh);
      data = img.data;
    }

    function resize() {
      vw = window.innerWidth; vh = window.innerHeight;
      canvas.width = Math.floor(vw * dpr);
      canvas.height = Math.floor(vh * dpr);
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.imageSmoothingEnabled = true;
      ctx.imageSmoothingQuality = 'high';
      buildGrid();
    }
    resize();

    // ── Wave step (damped 2-D wave equation, ping-pong buffers) ─────────────────
    function step() {
      var w = gw;
      for (var j = 1; j < gh - 1; j++) {
        var row = j * w;
        for (var i = 1; i < w - 1; i++) {
          var k = row + i;
          var val = (cur[k - 1] + cur[k + 1] + cur[k - w] + cur[k + w]) * SPREAD - prev[k];
          prev[k] = val * DAMP;
        }
      }
      var t = cur; cur = prev; prev = t;   // swap
    }

    // ── Disturbance (a "drop" into the surface) ─────────────────────────────────
    function drop(cx, cy, amp) {
      if (amp > MAX_AMP) amp = MAX_AMP;
      var gx = cx / vw * gw, gy = cy / vh * gh;
      var r = DROP_R, r2 = r * r;
      var i0 = Math.max(1, Math.floor(gx - r)), i1 = Math.min(gw - 2, Math.ceil(gx + r));
      var j0 = Math.max(1, Math.floor(gy - r)), j1 = Math.min(gh - 2, Math.ceil(gy + r));
      for (var j = j0; j <= j1; j++) {
        for (var i = i0; i <= i1; i++) {
          var dx = i - gx, dy = j - gy, d2 = dx * dx + dy * dy;
          if (d2 > r2) continue;
          cur[i + j * gw] += amp * (1 - d2 / r2);
        }
      }
    }

    // ── Render — transparent surface, only wavefronts catch light ───────────────
    function render() {
      var w = gw, caScale = caNow;
      for (var j = 1; j < gh - 1; j++) {
        var row = j * w;
        for (var i = 1; i < w - 1; i++) {
          var k = row + i;
          var gx = cur[k - 1] - cur[k + 1];     // ∂h/∂x
          var gy = cur[k - w] - cur[k + w];     // ∂h/∂y
          var mag = Math.sqrt(gx * gx + gy * gy);
          var o = k * 4;
          if (mag < 0.0015) { data[o + 3] = 0; continue; }  // flat → fully transparent
          var d = gx * LX + gy * LY;            // directional lighting on the wavefront
          var hi = d > 0 ? d : 0;
          var lum = mag * EDGE + hi * SPEC;
          if (lum > 1.25) lum = 1.25;
          var white = lum * 255;
          var ca = mag * CA_GAIN * caScale; if (ca > CA_MAX) ca = CA_MAX;
          var caV = ca * 255;
          var R = white + (gx > 0 ? caV : 0);
          var B = white + (gx < 0 ? caV : 0);
          var G = white;
          var a = mag * A_EDGE + hi * A_SPEC;
          if (a > MAX_ALPHA) a = MAX_ALPHA;
          data[o]     = R > 255 ? 255 : R;
          data[o + 1] = G > 255 ? 255 : G;
          data[o + 2] = B > 255 ? 255 : B;
          data[o + 3] = a * 255;
        }
      }
      gctx.putImageData(img, 0, 0);
      ctx.clearRect(0, 0, vw, vh);
      ctx.drawImage(grid, 0, 0, gw, gh, 0, 0, vw, vh);
    }

    // ── Cursor input → ripples ──────────────────────────────────────────────────
    var lastX = null, lastY = null, hasPos = false;
    var lastMoveT = 0, lastIdleT = 0;
    var caNow = 1, caTarget = 1;          // chromatic intensity (eased ← cursor speed)

    function onMove(e) {
      var cx = e.clientX, cy = e.clientY;
      if (cx == null) return;
      var now = performance.now();
      if (lastX == null) { lastX = cx; lastY = cy; hasPos = true; lastMoveT = now; return; }
      var speed = Math.sqrt((cx - lastX) * (cx - lastX) + (cy - lastY) * (cy - lastY));
      drop(cx, cy, MOVE_AMP + speed * SPEED_AMP);
      var t = 1 + speed * 0.05; if (t > 5) t = 5;
      if (t > caTarget) caTarget = t;
      lastX = cx; lastY = cy; hasPos = true; lastMoveT = now;
    }
    function onDown(e) {
      lastX = e.clientX; lastY = e.clientY; hasPos = true;
      drop(e.clientX, e.clientY, MAX_AMP);     // a full splash on click
    }
    function onLeave() { hasPos = false; }

    window.addEventListener('pointermove', onMove, { passive: true });
    window.addEventListener('pointerdown', onDown, { passive: true });
    window.addEventListener('pointerout', onLeave, { passive: true });
    window.addEventListener('resize', resize);

    // ── Loop (paused when tab hidden) ───────────────────────────────────────────
    var raf = 0, alive = true;
    function frame() {
      if (!alive) return;
      if (!document.hidden) {
        var now = performance.now();
        // gentle ripple while the cursor rests over the page ("停留")
        if (hasPos && now - lastMoveT > 140 && now - lastIdleT > IDLE_MS) {
          drop(lastX, lastY, MOVE_AMP * 0.5);
          lastIdleT = now;
        }
        caNow += (caTarget - caNow) * 0.18;
        caTarget = caTarget > 1 ? (caTarget - 1) * 0.9 + 1 : 1;
        step();
        render();
      }
      raf = requestAnimationFrame(frame);
    }
    raf = requestAnimationFrame(frame);

    return {
      destroy: function () {
        alive = false;
        cancelAnimationFrame(raf);
        window.removeEventListener('pointermove', onMove);
        window.removeEventListener('pointerdown', onDown);
        window.removeEventListener('pointerout', onLeave);
        window.removeEventListener('resize', resize);
        if (canvas.parentNode) canvas.parentNode.removeChild(canvas);
      }
    };
  }

  window.FluidCursor = { mount: mount };
})();
