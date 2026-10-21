// Login Page — World Time 风格 二段式 (上数字钟 / 下视频背景)

function useClock() {
  const [now, setNow] = React.useState(new Date());
  React.useEffect(() => {
    const t = setInterval(() => setNow(new Date()), 1000);
    return () => clearInterval(t);
  }, []);
  return now;
}

// ── Flip Digit (vertical roll-up — digit jumps UP) ────────────────
function FlipDigit({ value }) {
  const [displayed, setDisplayed] = React.useState(value);
  const [animating, setAnimating] = React.useState(false);
  const prevRef = React.useRef(value);

  React.useEffect(() => {
    if (value !== prevRef.current) {
      setAnimating(true);
      const t = setTimeout(() => {
        setDisplayed(value);
        prevRef.current = value;
        setAnimating(false);
      }, 520);
      return () => clearTimeout(t);
    }
  }, [value]);

  return (
    <span
      style={{
        position: 'relative',
        display: 'inline-block',
        // Widened to 1.08ch + extra height 1.18em so heavy Archivo Black glyphs
        // (which extend beyond the em-square) render fully — no edge clipping.
        width: '1.08ch',
        height: '1.18em',
        overflow: 'hidden',
        verticalAlign: 'baseline',
        fontVariantNumeric: 'tabular-nums',
        fontFeatureSettings: '"tnum" 1',
        lineHeight: 1,
      }}
    >
      <span
        style={{
          display: 'block',
          // Inner wrapper is 2 items × 1.18em = 2.36em. Translate by 50% = 1.18em = exactly 1 item.
          transform: animating ? 'translateY(-50%)' : 'translateY(0)',
          transition: animating ? 'transform 520ms cubic-bezier(.55,.05,.25,1)' : 'none',
          willChange: 'transform',
        }}
      >
        <span style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '1.18em', lineHeight: 1 }}>{displayed}</span>
        <span style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '1.18em', lineHeight: 1 }}>{value}</span>
      </span>
    </span>
  );
}

function FlipPair({ value }) {
  return (
    <span style={{ display: 'inline-flex', gap: '0.015em' }}>
      <FlipDigit value={value.charAt(0)} />
      <FlipDigit value={value.charAt(1)} />
    </span>
  );
}

// ── Beijing real-time date card (bottom-right) ─────────────────────
function BeijingDateCard({ now }) {
  // Convert any local time to Beijing (UTC+8)
  const beijing = new Date(now.getTime() + (now.getTimezoneOffset() + 480) * 60000);
  const WEEKDAYS = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六'];
  const wd = WEEKDAYS[beijing.getDay()];
  const dateLine = `${beijing.getMonth() + 1} 月 ${beijing.getDate()} 日 · ${beijing.getFullYear()} 年`;

  return (
    <div
      className="absolute right-10 bottom-9 z-10 select-none beijing-date-card"
      style={{
        background: 'linear-gradient(135deg, rgba(255,255,255,.18) 0%, rgba(255,255,255,.08) 50%, rgba(255,255,255,.14) 100%)',
        backdropFilter: 'blur(22px) saturate(140%)',
        WebkitBackdropFilter: 'blur(22px) saturate(140%)',
        border: '1px solid rgba(255,255,255,.32)',
        borderRadius: 18,
        padding: '16px 24px',
        boxShadow: '0 10px 32px rgba(0,0,0,.28), 0 2px 6px rgba(0,0,0,.18), 0 1px 0 rgba(255,255,255,.22) inset, 0 -1px 0 rgba(255,255,255,.08) inset',
        color: '#FAF6E9',
        minWidth: 210,
        textAlign: 'right',
        cursor: 'default',
        transition: 'transform 360ms cubic-bezier(.2,.85,.25,1.02), box-shadow 360ms ease, border-color 360ms ease, background 360ms ease',
      }}
    >
      <div
        className="leading-none"
        style={{
          fontSize: 'clamp(14px, 1.1vw, 17px)',
          fontFamily: '"Noto Serif SC", serif',
          fontWeight: 500,
          letterSpacing: '0.18em',
          opacity: 0.86,
        }}
      >
        {wd}
      </div>
      <div
        className="mt-2 leading-none whitespace-nowrap"
        style={{
          fontSize: 'clamp(20px, 1.6vw, 26px)',
          fontFamily: '"Archivo Black", "Noto Serif SC", sans-serif',
          fontWeight: 900,
          letterSpacing: '0.02em',
          textShadow: '0 1px 4px rgba(0,0,0,.4)',
        }}
      >
        {dateLine}
      </div>
      <div
        className="mt-1.5 font-mono"
        style={{
          fontSize: 10.5,
          letterSpacing: '0.24em',
          color: 'rgba(250,246,233,.6)',
        }}
      >
        北京 · UTC+8
      </div>
    </div>
  );
}

function CampusSilhouette() {
  // Decorative SVG of school building silhouette + bookshelves (sepia tone)
  return (
    <svg className="absolute inset-0 w-full h-full" viewBox="0 0 1440 540" preserveAspectRatio="xMidYMid slice">
      <defs>
        <linearGradient id="sky" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="#E8DBC0" />
          <stop offset="60%" stopColor="#D9C6A4" />
          <stop offset="100%" stopColor="#B89A6F" />
        </linearGradient>
        <linearGradient id="ground" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="#9C8862" />
          <stop offset="100%" stopColor="#6F5C3D" />
        </linearGradient>
        <filter id="grain">
          <feTurbulence baseFrequency="0.9" numOctaves="2" stitchTiles="stitch"/>
          <feColorMatrix values="0 0 0 0 0.3 0 0 0 0 0.2 0 0 0 0 0.1 0 0 0 0.08 0"/>
        </filter>
      </defs>
      {/* sky */}
      <rect width="1440" height="540" fill="url(#sky)" />

      {/* sun glow */}
      <circle cx="1080" cy="160" r="100" fill="#F2DBA4" opacity="0.65" />
      <circle cx="1080" cy="160" r="60" fill="#F8E7BD" opacity="0.85" />

      {/* trees silhouette far back */}
      {[120, 220, 1280, 1360].map((x, i) => (
        <g key={i} opacity="0.55">
          <ellipse cx={x} cy="380" rx="32" ry="28" fill="#5A4729" />
          <rect x={x - 3} y="380" width="6" height="40" fill="#3B2C18" />
        </g>
      ))}

      {/* Main academic building (centered, brutalist + classical mix) */}
      <g transform="translate(420, 200)">
        {/* main rectangle body */}
        <rect x="0" y="40" width="600" height="240" fill="#8C7449" />
        <rect x="0" y="40" width="600" height="12" fill="#6F5C3D" />
        {/* roof pediment */}
        <path d="M -20 40 L 300 -10 L 620 40 Z" fill="#6F5C3D" />
        <path d="M 30 40 L 300 20 L 570 40 Z" fill="#5A4729" opacity="0.6" />

        {/* columns (6 tall) */}
        {[80, 160, 240, 320, 400, 480].map((x, i) => (
          <g key={i}>
            <rect x={x} y="60" width="24" height="180" fill="#A38A5A" />
            <rect x={x - 6} y="58" width="36" height="8" fill="#6F5C3D" />
            <rect x={x - 6} y="232" width="36" height="8" fill="#6F5C3D" />
          </g>
        ))}

        {/* steps */}
        <rect x="-30" y="280" width="660" height="14" fill="#6F5C3D" />
        <rect x="-50" y="294" width="700" height="14" fill="#5A4729" />

        {/* windows (between columns) */}
        {[114, 194, 274, 354, 434].map((x, i) => (
          <rect key={i} x={x} y="100" width="40" height="120" fill="#3B2C18" opacity="0.4" />
        ))}

        {/* central door */}
        <rect x="285" y="170" width="30" height="100" fill="#3B2C18" />
        <circle cx="300" cy="65" r="16" fill="#6F5C3D" />
      </g>

      {/* side wings (lower) */}
      <g transform="translate(80, 290)" opacity="0.85">
        <rect x="0" y="0" width="340" height="150" fill="#7E693F" />
        <rect x="0" y="0" width="340" height="10" fill="#5A4729" />
        {Array.from({ length: 7 }).map((_, i) => (
          <rect key={i} x={20 + i * 44} y="30" width="24" height="60" fill="#3B2C18" opacity="0.35" />
        ))}
      </g>
      <g transform="translate(1020, 290)" opacity="0.85">
        <rect x="0" y="0" width="340" height="150" fill="#7E693F" />
        <rect x="0" y="0" width="340" height="10" fill="#5A4729" />
        {Array.from({ length: 7 }).map((_, i) => (
          <rect key={i} x={20 + i * 44} y="30" width="24" height="60" fill="#3B2C18" opacity="0.35" />
        ))}
      </g>

      {/* ground */}
      <rect x="0" y="440" width="1440" height="100" fill="url(#ground)" />

      {/* paving lines */}
      {[470, 500, 530].map((y, i) => (
        <line key={i} x1="0" x2="1440" y1={y} y2={y} stroke="#3B2C18" strokeWidth="0.6" opacity="0.3" />
      ))}

      {/* small figures (silhouettes of students) */}
      {[
        { x: 540, s: 0.9 }, { x: 580, s: 0.85 }, { x: 720, s: 1.0 }, { x: 920, s: 0.9 }, { x: 950, s: 0.88 },
      ].map((p, i) => (
        <g key={i} transform={`translate(${p.x}, 460) scale(${p.s})`}>
          <circle cx="0" cy="-30" r="6" fill="#3B2C18" />
          <rect x="-5" y="-24" width="10" height="18" fill="#3B2C18" />
          <rect x="-5" y="-6" width="4" height="14" fill="#3B2C18" />
          <rect x="1" y="-6" width="4" height="14" fill="#3B2C18" />
        </g>
      ))}

      {/* grain overlay */}
      <rect width="1440" height="540" filter="url(#grain)" opacity="0.7" />

      {/* ken-burns slow drift will be added via CSS animation */}
    </svg>
  );
}

// Decorative canvases share one cancellable loop. Reduced motion keeps a still
// frame, while a hidden page does no drawing or video playback at all.
const LOGIN_DECORATION_OWNERS = new WeakMap();
function createLoginDecorationLoop({ draw = null, media = [], activeVideo = null, enabled = true, unmuteOnGesture = false }) {
  const preference = window.matchMedia?.('(prefers-reduced-motion: reduce)');
  const owner = {};
  media.forEach((item) => LOGIN_DECORATION_OWNERS.set(item, owner));
  let frame = 0;
  let disposed = false;
  let running = false;
  const visible = () => !document.hidden;
  const active = () => !disposed && enabled && visible() && !preference?.matches;
  const pause = () => media.forEach((item) => {
    // A late play promise from an unmounted effect must not pause its successor.
    if (LOGIN_DECORATION_OWNERS.get(item) !== owner) return;
    try { item.pause?.(); } catch {}
  });
  const stop = () => {
    running = false;
    if (frame) cancelAnimationFrame(frame);
    frame = 0;
    pause();
  };
  const drawStill = () => { if (!disposed && visible()) draw?.(); };
  const play = () => {
    if (!activeVideo || !active()) return;
    try {
      const playback = activeVideo.play();
      playback?.then?.(() => { if (!active()) pause(); }, () => {});
    } catch {}
  };
  const removeGestureListeners = () => {
    document.removeEventListener('click', unmute);
    document.removeEventListener('keydown', unmute);
  };
  const unmute = () => {
    // A gesture while hidden or in reduced-motion mode must not start sound.
    if (!active() || !activeVideo) return;
    try { activeVideo.muted = false; activeVideo.volume = 0.8; } catch {}
    play();
    removeGestureListeners();
  };
  const tick = () => {
    frame = 0;
    if (!active()) { stop(); return; }
    draw();
    if (active()) frame = requestAnimationFrame(tick);
  };
  const sync = () => {
    if (disposed) return;
    if (!active()) { stop(); drawStill(); return; }
    if (running) return;
    running = true;
    pause();
    play();
    drawStill();
    if (draw && active()) frame = requestAnimationFrame(tick);
  };
  const mediaEvents = ['load', 'loadeddata', 'seeked'];
  media.forEach((item) => mediaEvents.forEach((event) => item.addEventListener?.(event, drawStill)));
  document.addEventListener('visibilitychange', sync);
  if (preference?.addEventListener) preference.addEventListener('change', sync);
  else preference?.addListener?.(sync);
  if (unmuteOnGesture) {
    document.addEventListener('click', unmute);
    document.addEventListener('keydown', unmute);
  }
  sync();
  return () => {
    disposed = true;
    stop();
    document.removeEventListener('visibilitychange', sync);
    if (preference?.removeEventListener) preference.removeEventListener('change', sync);
    else preference?.removeListener?.(sync);
    removeGestureListeners();
    media.forEach((item) => mediaEvents.forEach((event) => item.removeEventListener?.(event, drawStill)));
  };
}

function LoginBackgroundVideo() {
  const videoRef = React.useRef(null);
  React.useEffect(() => {
    const video = videoRef.current;
    if (!video) return;
    return createLoginDecorationLoop({ media: [video], activeVideo: video, unmuteOnGesture: true });
  }, []);
  return (
    <video
      ref={videoRef}
      src="assets/login-bg.mp4"
      loop
      muted
      playsInline
      className="absolute w-full"
      style={{ top: -160, height: 'calc(100% + 160px)', left: 0, right: 0, objectFit: 'cover', objectPosition: 'center' }}
    />
  );
}

// ── ChromaVideo: plays a video and chroma-keys out a green background ──
// Renders a transparent canvas — useful for "alpha video" sourced from a
// green-screen .mp4 without needing a separate alpha track.
function ChromaVideo({ src, playing, size = 72, onReady }) {
  const videoRef  = React.useRef(null);
  const canvasRef = React.useRef(null);
  const readyRef  = React.useRef(false);

  // Per-frame keying is decorative, independent of authentication state.
  React.useEffect(() => {
    const v = videoRef.current;
    const c = canvasRef.current;
    if (!v || !c) return;
    const ctx = c.getContext('2d', { willReadFrequently: true });

    const draw = () => {
      if (v.readyState >= 2 && v.videoWidth) {
        if (c.width !== v.videoWidth) {
          c.width  = v.videoWidth;
          c.height = v.videoHeight;
          if (!readyRef.current) { readyRef.current = true; onReady?.(); }
        }
        try {
          ctx.drawImage(v, 0, 0, c.width, c.height);
          const img = ctx.getImageData(0, 0, c.width, c.height);
          const d = img.data;
          // Green-screen key — tuned for typical "chroma green" (#00B140-ish)
          // Pixel is keyed when green dominates both red and blue by a margin.
          for (let i = 0; i < d.length; i += 4) {
            const r = d[i], g = d[i + 1], b = d[i + 2];
            const mx = r > b ? r : b;
            const excess = g - mx;
            if (excess > 36 && g > 70) {
              // Fully transparent
              d[i + 3] = 0;
            } else if (excess > 14) {
              // Edge feather + despill (suppress green tint)
              d[i + 1] = mx;
              d[i + 3] = Math.max(0, 255 - excess * 7);
            }
          }
          ctx.putImageData(img, 0, 0);
        } catch {
          // CORS-tainted canvas — fall back to plain video render
        }
      }
    };
    return createLoginDecorationLoop({ draw, media: [v], activeVideo: v, enabled: playing });
  }, [src, playing, onReady]);

  return (
    <>
      <video
        ref={videoRef}
        src={src}
        loop
        muted
        playsInline
        preload="auto"
        style={{ display: 'none' }}
      />
      <canvas
        ref={canvasRef}
        style={{
          width: size,
          height: size,
          display: 'block',
          borderRadius: '50%',
          pointerEvents: 'none',
        }}
      />
    </>
  );
}

// Chroma-keyed animated GIF → transparent canvas (live per-frame keying).
// Keeps the GIF's animation while removing its green background.
function ChromaGif({ src, width = 200, height = 200 }) {
  const imgRef    = React.useRef(null);
  const canvasRef = React.useRef(null);

  React.useEffect(() => {
    const img = imgRef.current;
    const c   = canvasRef.current;
    if (!img || !c) return;
    const ctx = c.getContext('2d', { willReadFrequently: true });

    const draw = () => {
      if (img.complete && img.naturalWidth) {
        if (c.width !== img.naturalWidth) {
          c.width  = img.naturalWidth;
          c.height = img.naturalHeight;
        }
        try {
          ctx.clearRect(0, 0, c.width, c.height);
          ctx.drawImage(img, 0, 0, c.width, c.height);
          const im = ctx.getImageData(0, 0, c.width, c.height);
          const d = im.data;
          for (let i = 0; i < d.length; i += 4) {
            const r = d[i], g = d[i + 1], b = d[i + 2];
            const mx = r > b ? r : b;
            const excess = g - mx;
            if (excess > 36 && g > 70) {
              d[i + 3] = 0;                                  // fully key
            } else if (excess > 12) {
              d[i + 1] = mx;                                 // despill
              d[i + 3] = Math.max(0, 255 - excess * 8);      // feather edge
            }
          }
          ctx.putImageData(im, 0, 0);
        } catch {}
      }
    };
    return createLoginDecorationLoop({ draw, media: [img] });
  }, [src]);

  return (
    <>
      {/* hidden but laid-out so the GIF keeps advancing frames */}
      <img
        ref={imgRef}
        src={src}
        alt=""
        aria-hidden="true"
        style={{ position: 'absolute', width: 1, height: 1, opacity: 0, pointerEvents: 'none', left: -9999 }}
      />
      <canvas
        ref={canvasRef}
        style={{ width, height, display: 'block', pointerEvents: 'none' }}
      />
    </>
  );
}

// Floating light particles — drifting motes for a premium, breathing feel.
function LoginGlowParticles({ count = 8, color = '255,255,255', area = 200, spread = 0.5 }) {
  const motes = React.useMemo(() => Array.from({ length: count }).map((_, i) => ({
    id: i,
    x: Math.round(8 + Math.random() * 84),
    delay: (Math.random() * 4).toFixed(2),
    dur: (3.2 + Math.random() * 3.2).toFixed(2),
    size: (1.5 + Math.random() * 2.8).toFixed(1),
    drift: Math.round((Math.random() - 0.5) * 26),
    op: (0.35 + Math.random() * 0.5).toFixed(2),
  })), [count]);
  return (
    <span className="absolute inset-0 overflow-visible pointer-events-none" aria-hidden="true">
      {motes.map((m) => (
        <span
          key={m.id}
          style={{
            position: 'absolute',
            left: `${m.x}%`,
            bottom: `${spread * 8}%`,
            width: `${m.size}px`,
            height: `${m.size}px`,
            borderRadius: '50%',
            background: `radial-gradient(circle, rgba(${color},${m.op}) 0%, rgba(${color},0) 70%)`,
            boxShadow: `0 0 ${4 + Number(m.size) * 2}px rgba(${color},${m.op})`,
            '--drift': `${m.drift}px`,
            animation: `loginMote ${m.dur}s ease-in-out ${m.delay}s infinite`,
            willChange: 'transform, opacity',
          }}
        />
      ))}
    </span>
  );
}

// Cute axolotl mascot — covers its eyes while typing the password.
function LoginMascot({ covering, lookDown }) {
  const B1 = '#B49CF2', B2 = '#9C81EE', FACE = '#E4DBFC', GILL = '#F4A6DE', CHEEK = '#F6A6D8', PUP = '#3B2E66';
  return (
    <div className={`lm-wrap${covering ? ' is-covering' : ''}${lookDown ? ' look-down' : ''}`} data-no-drag>
      <svg viewBox="0 0 200 200" width="156" height="156" style={{ overflow: 'visible', display: 'block' }}>
        <defs>
          <radialGradient id="lmBody" cx="50%" cy="38%" r="70%">
            <stop offset="0%" stopColor={B1} />
            <stop offset="100%" stopColor={B2} />
          </radialGradient>
        </defs>

        {/* gills — 3 frills each side */}
        <g className="lm-gills">
          {[[-1, 70], [-1, 96], [-1, 122], [1, 70], [1, 96], [1, 122]].map(([s, y], i) => (
            <ellipse key={i} cx={100 + s * 66} cy={y} rx="15" ry="11" fill={GILL}
              transform={`rotate(${s * 22} ${100 + s * 66} ${y})`} opacity="0.92" />
          ))}
        </g>
        {/* top tufts */}
        <ellipse cx="84" cy="40" rx="9" ry="13" fill={GILL} transform="rotate(-18 84 40)" />
        <ellipse cx="116" cy="40" rx="9" ry="13" fill={GILL} transform="rotate(18 116 40)" />

        {/* head / body */}
        <ellipse cx="100" cy="106" rx="70" ry="64" fill="url(#lmBody)" />
        {/* face patch */}
        <ellipse cx="100" cy="118" rx="52" ry="46" fill={FACE} />

        {/* cheeks */}
        <ellipse cx="62" cy="122" rx="11" ry="8" fill={CHEEK} opacity="0.85" />
        <ellipse cx="138" cy="122" rx="11" ry="8" fill={CHEEK} opacity="0.85" />

        {/* eyes */}
        <g className="lm-eyes">
          <ellipse cx="76" cy="104" rx="15" ry="17" fill="#fff" />
          <ellipse cx="124" cy="104" rx="15" ry="17" fill="#fff" />
          <g style={{ transform: lookDown ? 'translateY(5px)' : 'translateY(0)', transition: 'transform .28s cubic-bezier(.2,.9,.3,1)', opacity: covering ? 0 : 1 }}>
            <circle cx="78" cy="106" r="7.5" fill={PUP} />
            <circle cx="122" cy="106" r="7.5" fill={PUP} />
            <circle cx="80.5" cy="103" r="2.6" fill="#fff" />
            <circle cx="124.5" cy="103" r="2.6" fill="#fff" />
          </g>
          {/* closed happy eyes shown under paws when covering */}
          <g stroke={PUP} strokeWidth="3" strokeLinecap="round" fill="none"
             style={{ opacity: covering ? 1 : 0, transition: 'opacity .2s ease .12s' }}>
            <path d="M68 106 Q76 99 84 106" />
            <path d="M116 106 Q124 99 132 106" />
          </g>
        </g>

        {/* mouth */}
        <path d="M92 126 Q100 133 108 126" stroke={PUP} strokeWidth="2.6" fill="none" strokeLinecap="round" />

        {/* resting little paws (peek from behind body) */}
        <g style={{ opacity: covering ? 0 : 1, transition: 'opacity .22s ease' }}>
          <ellipse cx="44" cy="150" rx="13" ry="12" fill={B1} stroke={B2} strokeWidth="1.5" />
          <ellipse cx="156" cy="150" rx="13" ry="12" fill={B1} stroke={B2} strokeWidth="1.5" />
        </g>

        {/* covering arms + paws over eyes */}
        <g style={{
          opacity: covering ? 1 : 0,
          transform: covering ? 'translateY(0) scale(1)' : 'translateY(40px) scale(.86)',
          transformOrigin: '100px 150px',
          transition: 'opacity .3s ease, transform .34s cubic-bezier(.2,.9,.3,1.15)',
        }}>
          <path d="M58 160 Q40 120 66 106" stroke={B2} strokeWidth="15" fill="none" strokeLinecap="round" />
          <path d="M142 160 Q160 120 134 106" stroke={B2} strokeWidth="15" fill="none" strokeLinecap="round" />
          <ellipse cx="76" cy="104" rx="18" ry="16" fill={B1} stroke={B2} strokeWidth="1.5" />
          <ellipse cx="124" cy="104" rx="18" ry="16" fill={B1} stroke={B2} strokeWidth="1.5" />
          <ellipse cx="76" cy="104" rx="7" ry="6" fill={CHEEK} opacity=".7" />
          <ellipse cx="124" cy="104" rx="7" ry="6" fill={CHEEK} opacity=".7" />
        </g>
      </svg>

      <style>{`
        .lm-wrap { animation: lmFloat 4.2s ease-in-out infinite; transform-origin: 50% 100%; }
        .lm-wrap.is-covering { animation: lmFloatSlow 4.2s ease-in-out infinite; }
        @keyframes lmFloat { 0%,100% { transform: translateY(0) rotate(0deg); } 50% { transform: translateY(-6px) rotate(-1.5deg); } }
        @keyframes lmFloatSlow { 0%,100% { transform: translateY(0); } 50% { transform: translateY(-3px); } }
        .lm-gills { animation: lmGill 3.4s ease-in-out infinite; transform-origin: 100px 100px; }
        @keyframes lmGill { 0%,100% { transform: scaleX(1); } 50% { transform: scaleX(1.04); } }
      `}</style>
    </div>
  );
}

// Panda mascot — two green-screen videos, chroma-keyed live onto one canvas.
//   covering=false → "idle" panda loops forever (default + when password blurred)
//   covering=true  → "cover" panda plays ONCE (hands over eyes), then holds last frame
function LoginPandaMascot({ covering, size = 184 }) {
  const idleRef   = React.useRef(null);
  const coverRef  = React.useRef(null);
  const canvasRef = React.useRef(null);

  // Only the selected video plays; preference/visibility changes preserve its
  // current frame. A password-focus change still restarts the covering gesture.
  React.useEffect(() => {
    const c = canvasRef.current;
    if (!c) return;
    const vi = idleRef.current;
    const vc = coverRef.current;
    if (!vi || !vc) return;
    try { vc.currentTime = 0; } catch {}
    const ctx = c.getContext('2d', { willReadFrequently: true });
    const keyDraw = (v) => {
      if (!v || v.readyState < 2 || !v.videoWidth) return;
      if (c.width !== v.videoWidth) { c.width = v.videoWidth; c.height = v.videoHeight; }
      try {
        ctx.drawImage(v, 0, 0, c.width, c.height);
        const img = ctx.getImageData(0, 0, c.width, c.height);
        const d = img.data;
        for (let i = 0; i < d.length; i += 4) {
          const r = d[i], g = d[i + 1], b = d[i + 2];
          const mx = r > b ? r : b;
          const ex = g - mx;
          if (ex > 34 && g > 60) {
            d[i + 3] = 0;                                   // key out green
          } else if (ex > 12) {
            d[i + 1] = mx;                                  // despill
            d[i + 3] = Math.max(0, 255 - ex * 8);           // feather edge
          }
        }
        ctx.putImageData(img, 0, 0);
      } catch {}
    };
    const selected = covering ? vc : vi;
    return createLoginDecorationLoop({ draw: () => keyDraw(selected), media: [vi, vc], activeVideo: selected });
  }, [covering]);

  return (
    <div className="lp-wrap" data-no-drag style={{ position: 'relative', width: size, height: size }}>
      <video ref={idleRef}  src="assets/panda-idle.mp4"  loop muted playsInline preload="auto" style={{ display: 'none' }} />
      <video ref={coverRef} src="assets/panda-cover.mp4"      muted playsInline preload="auto" style={{ display: 'none' }} />
      <canvas
        ref={canvasRef}
        style={{ width: size, height: size, display: 'block', pointerEvents: 'none',
                 filter: 'drop-shadow(0 8px 16px rgba(80,60,140,.22))' }}
      />
      <style>{`
        .lp-wrap { animation: lpFloat 4.4s ease-in-out infinite; transform-origin: 50% 100%; }
        @keyframes lpFloat { 0%,100% { transform: translateY(0); } 50% { transform: translateY(-6px); } }
      `}</style>
    </div>
  );
}

const LOCAL_CAPTCHA_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';

function randomLocalCaptchaCode() {
  const values = new Uint32Array(5);
  if (window.crypto?.getRandomValues) window.crypto.getRandomValues(values);
  else for (let i = 0; i < values.length; i++) values[i] = Math.floor(Math.random() * 100000);
  return Array.from(values, (value) => LOCAL_CAPTCHA_ALPHABET[value % LOCAL_CAPTCHA_ALPHABET.length]).join('');
}

function localCaptchaImage(code) {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="132" height="44" viewBox="0 0 132 44">
    <rect width="132" height="44" rx="12" fill="#EEF6FF"/>
    <path d="M10 34 C30 8 58 50 86 12 S118 30 126 10" fill="none" stroke="#93C5FD" stroke-width="2" opacity=".7"/>
    <path d="M8 14 L124 32" fill="none" stroke="#BFDBFE" stroke-width="1.5" opacity=".55"/>
    <text x="66" y="29" text-anchor="middle" font-family="ui-monospace, SFMono-Regular, Menlo, Consolas, monospace" font-size="24" font-weight="800" letter-spacing="4" fill="#1D4ED8">${code}</text>
  </svg>`;
  return 'data:image/svg+xml;base64,' + window.btoa(svg);
}

function normalizeCaptchaImage(value) {
  if (!value) return '';
  const text = String(value);
  if (text.trim().startsWith('<svg')) {
    return 'data:image/svg+xml;base64,' + window.btoa(unescape(encodeURIComponent(text)));
  }
  return text.startsWith('data:') ? text : 'data:image/svg+xml;base64,' + text;
}

function LoginPage({ onSuccess, sessionError }) {
  const now = useClock();
  // The API adapter owns the document's entry, including legacy URLs and storage failure handling.
  // Keep the displayed form on the same entry used by login, session restoration, logout and SSE.
  const loginPortal = React.useMemo(() => window.api?.portal?.() === 'platform' ? 'super' : 'admin', []);
  const isSuperPortal = loginPortal === 'super';
  const portalCopy = isSuperPortal
    ? {
        eyebrow: 'JKXT 超级管理员',
        title: '超级管理员登录',
        subtitle: '仅限平台运维与跨租户管理员访问',
        badge: '独立超管入口',
      }
    : {
        eyebrow: 'JKXT 教务管理员',
        title: '欢迎回来',
        subtitle: '登录以管理本学期监考排班与统计',
        badge: '教务管理员登录',
      };
  const openLoginGuide = (kind) => {
    const support = isSuperPortal ? '平台运维人员' : '学校管理员';
    const guides = {
      help: {
        title: '登录帮助',
        content: (
          <div className="space-y-4 text-sm leading-7" style={{ color: 'var(--text-medium)' }}>
            <p>当前为{isSuperPortal ? '超级管理员' : '学校'}入口。学校账号与平台超管账号不能混用，请使用部署方提供的对应入口。</p>
            <section>
              <h4 className="font-semibold" style={{ color: 'var(--text-strong)' }}>登录前准备</h4>
              <ol className="list-decimal pl-5 space-y-1">
                <li>{isSuperPortal ? '使用平台运维分配的超管账号；学校编码通常无需填写。' : '输入学校管理员分配的账号；同名账号需要填写学校编码，可向学校管理员获取。'}</li>
                <li>输入密码及图片中的验证码。验证码不清晰或已过期时，点击验证码图片刷新后重新填写。</li>
                <li>“记住 7 天”仅保存账号信息，不保存密码，也不延长服务端会话。</li>
              </ol>
            </section>
            <section>
              <h4 className="font-semibold" style={{ color: 'var(--text-strong)' }}>遇到问题</h4>
              <p>先检查入口、学校编码和账号。账号锁定或忘记密码请联系{support}核验身份；服务不可用时，请提供发生时间和错误提示，不要发送密码、验证码或登录 Cookie。</p>
            </section>
          </div>
        ),
      },
      recovery: {
        title: '找回账号与密码',
        content: (
          <div className="space-y-4 text-sm leading-7" style={{ color: 'var(--text-medium)' }}>
            <p>此入口不支持匿名自助重置，也不会自动发送密码或重置邮件。</p>
            <ol className="list-decimal pl-5 space-y-2">
              <li>联系{support}，说明学校、账号及遇到的问题，通过部署方认可的渠道核验身份。</li>
              <li>由具备权限的管理员按账号恢复流程处理。不要将原密码、验证码或登录 Cookie 发给他人。</li>
              <li>收到处理确认后，从原登录入口重新登录，并按照系统提示修改初始密码。</li>
            </ol>
            <p>如暂时无法联系负责人，请向学校或部署方索取正式支持渠道；本页不会代替管理员执行账号变更。</p>
          </div>
        ),
      },
      safety: {
        title: '使用与安全说明',
        content: (
          <div className="space-y-4 text-sm leading-7" style={{ color: 'var(--text-medium)' }}>
            <p>请仅处理你获准访问的学校和考务数据，不要共享账号、密码或验证码。</p>
            <p>在公共设备上不要勾选“记住 7 天”；使用后点击“退出登录”，并关闭浏览器。退出学校入口不会自动退出另一个超管入口，两者需分别退出。</p>
            <p>导出的教师和考务文件应按学校要求保管、传递和清理。正式的服务条款、隐私说明和支持渠道由学校或部署方提供，本说明不替代这些文件，也不表示你已同意未展示的条款。</p>
          </div>
        ),
      },
    };
    const guide = guides[kind];
    if (guide) window.ui?.modal({ ...guide, size: 'md' });
  };
  const rememberedLogin = React.useMemo(() => {
    try {
      const value = JSON.parse(localStorage.getItem('jkxt:remembered-login') || 'null');
      if (!value?.username || Number(value.expiresAt) <= Date.now()) {
        localStorage.removeItem('jkxt:remembered-login');
        return null;
      }
      return value;
    } catch { return null; }
  }, []);
  const defaultUsername = rememberedLogin?.username || '';
  const [username, setUsername] = React.useState(defaultUsername);
  const [organization, setOrganization] = React.useState(rememberedLogin?.organization || '');
  const [password, setPassword] = React.useState('');
  const [rememberLogin, setRememberLogin] = React.useState(Boolean(rememberedLogin));
  const [captcha, setCaptcha]   = React.useState('');
  const [captchaId, setCaptchaId] = React.useState('');
  const [captchaImage, setCaptchaImage] = React.useState('');
  const [captchaLoading, setCaptchaLoading] = React.useState(false);
  const [errors, setErrors] = React.useState({});
  const clearError = React.useCallback((k) => setErrors((prev) => (prev[k] ? { ...prev, [k]: undefined } : prev)), []);
  const useLocalCaptcha = React.useCallback((opts = {}) => {
    const code = randomLocalCaptchaCode();
    setCaptchaId(`local-${Date.now()}`);
    setCaptchaImage(localCaptchaImage(code));
    setCaptcha(code);
    clearError('captcha');
    if (opts.toast) window.ui?.toast('已显示本地验证码预览', { kind: 'info' });
  }, [clearError]);
  const loadCaptcha = React.useCallback(async (opts = {}) => {
    if (!window.api?.auth?.captcha) {
      useLocalCaptcha(opts);
      return;
    }
    setCaptchaLoading(true);
    const controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
    const timeoutId = controller ? window.setTimeout(() => controller.abort(), 1800) : 0;
    try {
      const res = window.api?.post
        ? await window.api.post('/auth/captcha', {}, controller ? { signal: controller.signal } : {})
        : await window.api.auth.captcha();
      const image = normalizeCaptchaImage(res?.imageBase64);
      if (!res?.captchaId || !image) throw new Error('CAPTCHA_IMAGE_MISSING');
      setCaptchaId(res?.captchaId || '');
      setCaptchaImage(image);
      setCaptcha('');
      clearError('captcha');
      if (opts.toast) window.ui?.toast('验证码已刷新', { kind: 'info' });
    } catch (error) {
      useLocalCaptcha(opts);
    } finally {
      if (timeoutId) window.clearTimeout(timeoutId);
      setCaptchaLoading(false);
    }
  }, [clearError, useLocalCaptcha]);
  const refreshCaptcha = () => loadCaptcha({ toast: true });
  const serviceStatus = React.useMemo(() => {
    if (captchaLoading || !captchaId) {
      return { label: '连接检测中', color: '#64748B' };
    }
    if (String(captchaId).startsWith('local-')) {
      return { label: '静态预览 · 未接通后端', color: '#F97316' };
    }
    return { label: '系统在线 · 服务正常', color: '#10B981' };
  }, [captchaId, captchaLoading]);
  const [pwFocused, setPwFocused] = React.useState(false);
  const [userFocused, setUserFocused] = React.useState(false);
  const [loading, setLoading]   = React.useState(false);
  const [shake, setShake]       = React.useState(false);
  const [minimized, setMinimized] = React.useState(false);
  // morph phase between card ↔ ball, drives smooth scale animations
  const [phase, setPhase] = React.useState('card'); // 'card' | 'collapsing' | 'ball' | 'expanding'
  const phaseTimer = React.useRef(0);
  const goMinimize = React.useCallback(() => {
    clearTimeout(phaseTimer.current);
    setPhase('collapsing');
    setMinimized(true);
    phaseTimer.current = setTimeout(() => setPhase('ball'), 440);
  }, []);
  const goExpand = React.useCallback(() => {
    clearTimeout(phaseTimer.current);
    setPhase('expanding');
    setMinimized(false);
    phaseTimer.current = setTimeout(() => setPhase('card'), 440);
  }, []);
  React.useEffect(() => () => clearTimeout(phaseTimer.current), []);

  React.useEffect(() => {
    if (sessionError) window.ui?.toast(sessionError, { kind: 'warning' });
  }, [sessionError]);

  // ── Water-drop cursor ripples · only while the login page is mounted ──
  React.useEffect(() => {
    const dot = document.createElement('span');
    dot.className = 'cursor-dot';
    document.body.appendChild(dot);

    let lastSpawn = 0;
    let lastX = 0, lastY = 0;
    let moving = false;
    let idleT = 0;

    const spawn = (x, y) => {
      const r = document.createElement('span');
      r.className = 'cursor-ripple';
      r.style.left = x + 'px';
      r.style.top  = y + 'px';
      document.body.appendChild(r);
      setTimeout(() => r.remove(), 950);
    };

    const onMove = (e) => {
      const x = e.clientX, y = e.clientY;
      dot.style.transform = `translate(${x}px, ${y}px) translate(-50%, -50%)`;
      dot.classList.add('active');
      clearTimeout(idleT);
      idleT = setTimeout(() => dot.classList.remove('active'), 600);

      const now = performance.now();
      const dist = Math.hypot(x - lastX, y - lastY);
      // Throttle: spawn at most every 55ms, and only if moved enough
      if (now - lastSpawn > 55 && dist > 6) {
        lastSpawn = now;
        spawn(x, y);
        lastX = x; lastY = y;
      }
    };
    const onClick = (e) => spawn(e.clientX, e.clientY);

    window.addEventListener('mousemove', onMove);
    window.addEventListener('mousedown', onClick);
    return () => {
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mousedown', onClick);
      clearTimeout(idleT);
      dot.remove();
      // Sweep any lingering ripples
      document.querySelectorAll('.cursor-ripple').forEach((n) => n.remove());
    };
  }, []);
  const [cardPos, setCardPos] = React.useState(null); // null = use default; object {x,y} when dragged
  const [dragging, setDragging] = React.useState(false);
  const [ballPos, setBallPos] = React.useState(null); // floating-ball position when dragged
  const [ballDragging, setBallDragging] = React.useState(false);

  // Card drag handler — works on entire card EXCEPT interactive elements
  const startCardDrag = React.useCallback((e) => {
    // Don't drag if the user clicked on an input/button/etc. — only drag from inert areas
    const t = e.target;
    if (t.closest('input, button, select, textarea, a, label, [data-no-drag]')) return;
    e.preventDefault();
    const cardEl = e.currentTarget.closest('[data-login-card]') || e.currentTarget;
    if (!cardEl) return;
    const rect = cardEl.getBoundingClientRect();
    const offsetX = e.clientX - rect.left;
    const offsetY = e.clientY - rect.top;
    setDragging(true);

    const onMove = (ev) => {
      const x = Math.max(8, Math.min(window.innerWidth - rect.width - 8, ev.clientX - offsetX));
      const y = Math.max(8, Math.min(window.innerHeight - rect.height - 8, ev.clientY - offsetY));
      setCardPos({ x, y });
    };
    const onUp = () => {
      setDragging(false);
      document.removeEventListener('mousemove', onMove);
      document.removeEventListener('mouseup', onUp);
    };
    document.addEventListener('mousemove', onMove);
    document.addEventListener('mouseup', onUp);
  }, []);

  // Card position style — default high+right, dragged uses absolute left+top
  const cardPositionStyle = cardPos
    ? { left: cardPos.x, top: cardPos.y, right: 'auto', transform: 'none' }
    : { right: 'clamp(16px, 3.9vw, 56px)', top: 88, left: 'auto', transform: 'none' };

  // Floating-ball drag (when minimized). Distinguishes click vs drag with a 5px threshold.
  const ballDragInfo = React.useRef({ moved: false, downX: 0, downY: 0 });
  const startBallDrag = React.useCallback((e) => {
    if (e.button !== 0) return;
    e.preventDefault();
    const ballEl = e.currentTarget;
    const rect = ballEl.getBoundingClientRect();
    const offsetX = e.clientX - rect.left;
    const offsetY = e.clientY - rect.top;
    ballDragInfo.current = { moved: false, downX: e.clientX, downY: e.clientY };

    const onMove = (ev) => {
      const dx = ev.clientX - ballDragInfo.current.downX;
      const dy = ev.clientY - ballDragInfo.current.downY;
      if (!ballDragInfo.current.moved && Math.hypot(dx, dy) < 5) return;
      if (!ballDragInfo.current.moved) {
        ballDragInfo.current.moved = true;
        setBallDragging(true);
      }
      const x = Math.max(8, Math.min(window.innerWidth - rect.width - 8, ev.clientX - offsetX));
      const y = Math.max(8, Math.min(window.innerHeight - rect.height - 8, ev.clientY - offsetY));
      setBallPos({ x, y });
    };
    const onUp = () => {
      document.removeEventListener('mousemove', onMove);
      document.removeEventListener('mouseup', onUp);
      // If user didn't drag, treat as click → expand login card
      if (!ballDragInfo.current.moved) {
        goExpand();
      }
      setBallDragging(false);
    };
    document.addEventListener('mousemove', onMove);
    document.addEventListener('mouseup', onUp);
  }, []);

  // Default ball position: above the date card, clear of overlap
  const ballPositionStyle = ballPos
    ? { left: ballPos.x, top: ballPos.y, right: 'auto', bottom: 'auto' }
    : { right: 8, bottom: 118, left: 'auto', top: 'auto' };

  React.useEffect(() => {
    setUsername(defaultUsername);
    setPassword('');
    setCaptcha('');
    setErrors({});
    loadCaptcha();
  }, [defaultUsername, loadCaptcha]);

  const pad = (n) => String(n).padStart(2, '0');
  const hh = pad(now.getHours());
  const mm = pad(now.getMinutes());
  const ss = pad(now.getSeconds());
  const dateStr = now.toLocaleDateString('zh-CN', { weekday: 'long', month: 'long', day: 'numeric' });

  // worldwide / domestic clocks (illustrative)
  const cities = [
    { name: '北京', offset: 0 },
    { name: '天津', offset: 0 },
    { name: '上海', offset: 0 },
    { name: '香港', offset: 0 },
    { name: '东京', offset: 1 },
    { name: '伦敦', offset: -8 },
  ];

  const handleLogin = async (e) => {
    e?.preventDefault();
    const errs = {};
    const u = username.trim();
    if (!u) errs.username = '请输入工号或邮箱';
    else if (u.length < 3) errs.username = '工号/邮箱至少需 3 个字符';
    else if (u.includes('@') && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(u)) errs.username = '邮箱格式不正确';

    if (!password) errs.password = '请输入密码';
    else if (password.length < 6) errs.password = '密码至少需 6 位';

    if (!captcha.trim()) errs.captcha = '请输入验证码';
    if (!captchaId) errs.captcha = '验证码未加载，请点击刷新';

    if (Object.keys(errs).length) {
      setErrors(errs);
      setShake(true);
      setTimeout(() => setShake(false), 500);
      window.ui?.toast(errs.username || errs.password || errs.captcha, { kind: 'warning' });
      return;
    }

    if (String(captchaId).startsWith('local-')) {
      const message = '当前静态预览环境未连接后端，验证码仅用于显示预览';
      setErrors({ captcha: message });
      setShake(true);
      setTimeout(() => setShake(false), 500);
      window.ui?.toast(message, { kind: 'warning' });
      return;
    }

    setErrors({});
    setLoading(true);
    try {
      // PB-4: the entry endpoint decides the portal; a wrong-entry account gets 403 AUTH_PORTAL_MISMATCH
      // without a session, so the other tab's session is never replaced.
      const profile = await window.api.auth.login({
        username: u,
        organization: organization.trim() || undefined,
        password,
        captchaId,
        captcha: captcha.trim(),
      });
      const backendRole = String(profile?.user?.role || '').toUpperCase();
      if (backendRole === 'SUPER_ADMIN' && !isSuperPortal) {
        try { await window.api?.auth?.logout?.(); } catch {}
        const message = '超级管理员请使用独立登录链接进入';
        setErrors({ password: message });
        setShake(true);
        setTimeout(() => setShake(false), 500);
        await loadCaptcha();
        window.ui?.toast(message, { kind: 'warning' });
        return;
      }
      if (backendRole !== 'SUPER_ADMIN' && isSuperPortal) {
        try { await window.api?.auth?.logout?.(); } catch {}
        const message = '该链接仅允许超级管理员账号登录';
        setErrors({ password: message });
        setShake(true);
        setTimeout(() => setShake(false), 500);
        await loadCaptcha();
        window.ui?.toast(message, { kind: 'warning' });
        return;
      }
      window.ui?.toast(backendRole === 'SUPER_ADMIN' ? '超级管理员登录成功' : '登录成功', { kind: 'success' });
      try {
        if (rememberLogin) {
          localStorage.setItem('jkxt:remembered-login', JSON.stringify({
            username: u,
            organization: organization.trim(),
            expiresAt: Date.now() + 7 * 24 * 60 * 60 * 1000,
          }));
        } else {
          localStorage.removeItem('jkxt:remembered-login');
        }
      } catch {}
      onSuccess?.(profile);
    } catch (error) {
      const nextErrors = {};
      if (error?.code === 'AUTH_CAPTCHA_FAILED') nextErrors.captcha = error.message || '验证码错误或已过期';
      else if (error?.code === 'AUTH_INVALID_CREDENTIALS') nextErrors.password = error.message || '用户名或密码错误';
      else if (error?.code === 'AUTH_LOCKED') nextErrors.password = error.message || '账号已被临时锁定';
      else nextErrors.password = error?.message || '登录失败，请重试';
      setErrors(nextErrors);
      setShake(true);
      setTimeout(() => setShake(false), 500);
      await loadCaptcha();
      window.ui?.toast(nextErrors.username || nextErrors.password || nextErrors.captcha, { kind: 'warning' });
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="w-full flex flex-col login-root" role="main" style={{
      position: 'fixed', inset: 0, height: '100dvh', minHeight: 0, overflow: 'clip',
      background: 'linear-gradient(180deg, #F1ECF5 0%, #E9E2F0 8%, #DCD2E7 16%, #CFC2DD 22%, #CFC2DD 100%)',
      fontFamily: 'Geist, system-ui',
    }}>
      {/* ── TOP STRIP — brand + role ── */}
      <div className="login-top-strip px-8 py-4 flex items-center justify-between shrink-0" style={{ position: 'relative', zIndex: 30 }}>
        <div className="login-legacy-brand flex items-center gap-2.5 relative" aria-label="Quick 排监考">
          <span className="relative inline-flex items-center justify-center">
            <img src="assets/logo-mark.png" alt="排监考系统 Logo"
              style={{ height: 54, width: 'auto', display: 'block', position: 'relative',
                       filter: 'drop-shadow(0 2px 5px rgba(37,99,235,.26))' }} />
          </span>
          <img src="assets/quick-logo.png" alt="Quick 排监考"
            style={{ height: 52, width: 'auto', display: 'block',
                     transform: 'scaleX(1.12)', transformOrigin: 'left center',
                     filter: 'drop-shadow(0 2px 6px rgba(56,130,246,.28))' }} />
        </div>
        <div className="flex items-center gap-3">
          <span className="inline-flex items-center gap-1.5 text-[12px] font-mono" style={{ color: '#6B6960' }}>
            <span className="w-1.5 h-1.5 rounded-full" style={{ background: serviceStatus.color }} />
            {serviceStatus.label}
          </span>
          <button className="h-11 px-3 rounded-md inline-flex items-center gap-1.5 text-[12px] hover:bg-black/5"
            style={{ color: '#1F1F1F' }}
            onClick={() => openLoginGuide('help')}>
            <IconHelp size={13} /> 帮助
          </button>
        </div>
      </div>

      {/* ── UPPER: GIANT CLOCK ── */}
      <div
        className="login-clock-panel flex-[3] min-h-0 px-12 pt-0 pb-4 flex flex-col justify-center relative overflow-visible"
        style={{ maxHeight: '43vh', zIndex: 9, pointerEvents: 'none' }}
      >
        {/* Labels row */}
        <div
          className="login-clock-face grid items-end"
          style={{
            gridTemplateColumns: 'auto 1fr auto 1fr auto',
            columnGap: 'clamp(8px, 1vw, 20px)',
            width: 'calc(100vw - clamp(40px, 6vw, 96px))',
            maxWidth: 1900,
            fontSize: 'clamp(150px, 15.8vw, 292px)',
            opacity: 0.96,
          }}
        >
          <div className="grid items-end" style={{ gridTemplateRows: '18px 1fr', height: '1.16em', minWidth: 0 }}>
            <span className="font-mono uppercase" style={{ fontSize: '12.5px', letterSpacing: '0.26em', color: '#6B6960' }}>
              时
            </span>
            <div
              className="leading-none"
              style={{
                color: '#1F1F1F',
                fontFamily: '"Archivo Black", "Geist", system-ui, sans-serif',
                fontWeight: 400,
                letterSpacing: '-0.05em',
              }}
            >
              <FlipPair value={hh} />
            </div>
          </div>
          <span
            className="leading-none flex items-end justify-center"
            style={{
              color: '#1F1F1F',
              fontFamily: '"Archivo Black", sans-serif',
              fontWeight: 400,
              letterSpacing: '-0.05em',
              paddingBottom: '0.06em',
              fontSize: '0.92em',
              opacity: 0.5,
            }}
          >
            :
          </span>
          <div className="grid items-end" style={{ gridTemplateRows: '18px 1fr', height: '1.16em', minWidth: 0 }}>
            <span className="font-mono uppercase" style={{ fontSize: '12.5px', letterSpacing: '0.26em', color: '#6B6960' }}>
              分
            </span>
            <div
              className="leading-none"
              style={{
                color: '#1F1F1F',
                fontFamily: '"Archivo Black", "Geist", system-ui, sans-serif',
                fontWeight: 400,
                letterSpacing: '-0.05em',
              }}
            >
              <FlipPair value={mm} />
            </div>
          </div>
          <span
            className="leading-none flex items-end justify-center"
            style={{
              color: '#1F1F1F',
              fontFamily: '"Archivo Black", sans-serif',
              fontWeight: 400,
              letterSpacing: '-0.05em',
              paddingBottom: '0.09em',
              fontSize: '0.70em',
              opacity: 0.42,
            }}
          >
            :
          </span>
          <div className="grid items-end" style={{ gridTemplateRows: '18px 1fr', height: '1.16em', minWidth: 0 }}>
            <span className="font-mono uppercase" style={{ fontSize: '12.5px', letterSpacing: '0.26em', color: '#6B6960' }}>
              秒
            </span>
            <div
              className="leading-none"
              style={{
                color: '#1F1F1F',
                fontFamily: '"Archivo Black", "Geist", system-ui, sans-serif',
                fontWeight: 400,
                letterSpacing: '-0.05em',
                fontSize: '0.80em',
                paddingBottom: '0.08em',
                opacity: 0.88,
              }}
            >
              <FlipPair value={ss} />
            </div>
          </div>
        </div>
      </div>

      {/* ── LOWER: VIDEO BACKGROUND + LOGIN CARD ── */}
      {/* overflow:visible — lets the video element extend 160px upward into
          the clock area so the viewer sees MORE of the scene. The clock is
          z-indexed above (zIndex: 10) so it always stays on top. */}
      <div className="flex-1 min-h-0 relative" style={{ background: '#0a0a0a', overflow: 'visible' }}>
        {/* Background video — autoplay, loop, full-bleed.
            Extended 160px upward via negative top + extra height. */}
        <LoginBackgroundVideo />
        {/* Top edge feather — extended 160px upward to match the video's
            new top. Solid #CFC2DD at the new top edge so it matches the
            (flat) root color above; long smooth fade into transparency. */}
        <div className="absolute left-0 right-0 pointer-events-none z-[5]"
          style={{
            top: -160,
            height: 620,
            background: `linear-gradient(to bottom,
              #CFC2DD 0%,
              rgba(207,194,221,.998) 4%,
              rgba(204,190,219,.99) 9%,
              rgba(196,182,214,.97) 15%,
              rgba(184,168,204,.93) 22%,
              rgba(168,148,190,.86) 30%,
              rgba(148,128,172,.76) 38%,
              rgba(126,106,150,.64) 46%,
              rgba(104,86,128,.51) 54%,
              rgba(82,68,104,.38) 62%,
              rgba(62,50,80,.26) 70%,
              rgba(44,34,58,.16) 78%,
              rgba(26,20,38,.08) 86%,
              rgba(14,10,22,.03) 93%,
              rgba(10,10,10,0) 100%
            )`,
          }} />
        {/* Layer 2: cloud-like atmospheric variation — extended 160px up
            in lockstep with the video & feather. Wisps still centered in
            the lower half of THIS layer (matching upper half of the
            feather's fade) so they don't create brightness pops at the
            new top edge. */}
        <div className="absolute left-0 right-0 pointer-events-none z-[6]"
          style={{
            top: -160,
            height: 560,
            background: `
              radial-gradient(ellipse 80% 60% at 25% 42%, rgba(196,182,216,.40), transparent 72%),
              radial-gradient(ellipse 65% 55% at 78% 46%, rgba(178,158,202,.34), transparent 68%),
              radial-gradient(ellipse 50% 50% at 55% 52%, rgba(160,140,184,.28), transparent 65%),
              radial-gradient(ellipse 70% 50% at 12% 62%, rgba(130,108,158,.20), transparent 62%),
              radial-gradient(ellipse 55% 48% at 86% 64%, rgba(108,88,138,.16), transparent 58%)
            `,
            filter: 'blur(20px)',
            mixBlendMode: 'normal',
          }} />
        {/* Cinematic darkening + vignette for text legibility — start AFTER the
            feather has finished so the top transition stays clean. */}
        <div className="absolute inset-0 pointer-events-none" style={{
          background:
            'linear-gradient(180deg, rgba(0,0,0,0) 0%, rgba(0,0,0,0) 35%, rgba(0,0,0,0) 60%, rgba(0,0,0,.55) 100%)',
        }} />
        <div className="absolute inset-0 pointer-events-none" style={{
          background:
            'radial-gradient(ellipse at center, rgba(0,0,0,0) 32%, rgba(0,0,0,.22) 100%)',
        }} />

        {/* Bottom-left signature */}
        <div className="login-brand-signature absolute left-11 bottom-9 z-10 pointer-events-none select-none" style={{ textAlign: 'left' }}>
          <div
            className="text-white leading-none whitespace-nowrap"
            data-login-brand-title
            style={{
              fontFamily: '"Noto Serif SC", "Source Han Serif SC", "Songti SC", serif',
              fontWeight: 700,
              fontSize: 'clamp(28px, 3.6vw, 52px)',
              letterSpacing: '0.04em',
              textShadow: '0 2px 16px rgba(0,0,0,.55), 0 1px 2px rgba(0,0,0,.4)',
            }}
          >
            <span style={{
              display: 'inline-block',
              marginRight: '0.18em',
              fontFamily: '"Archivo Black", sans-serif',
              opacity: 0.92,
            }}>
              <span style={{
                fontSize: '1.9em',
                lineHeight: 0.7,
                verticalAlign: '-0.04em',
                marginRight: '0.01em',
                display: 'inline-block',
                textShadow: '0 4px 24px rgba(124,58,237,.45), 0 2px 6px rgba(0,0,0,.5)',
              }}>Q</span>uick</span>
            智能排监考平台
          </div>
          <div
            className="mt-2 font-mono whitespace-nowrap"
            data-login-brand-subtitle
            style={{
              fontSize: 'clamp(10px, 0.9vw, 12.5px)',
              letterSpacing: '0.32em',
              color: 'rgba(250,246,233,.7)',
              textShadow: '0 1px 4px rgba(0,0,0,.5)',
              marginLeft: 0,
            }}
          >
            JKXT · K-12 监考管理系统
          </div>
        </div>

      {/* Bottom-right Beijing date — glass card */}
        <BeijingDateCard now={now} />

        {/* ── LOGIN CARD (floating, draggable, higher default position) ── */}
        {phase !== 'ball' && (
          <div
            data-login-card
            onMouseDown={startCardDrag}
            className={`fixed w-[430px] max-w-[calc(100vw-48px)] z-20 ${shake ? 'shake-anim' : ''}`}
            style={{
              ...cardPositionStyle,
              background: 'transparent',
              borderRadius: 22,
              padding: 32,
              boxShadow: 'none',
              // Morph animations driven by `phase`. Use bottom-right transform-origin
              // so the card scales toward the floating-ball area.
              transformOrigin: 'calc(100% - 28px) calc(100% - 28px)',
              animation:
                phase === 'collapsing'
                  ? 'cardCollapse 440ms cubic-bezier(.55,.0,.6,.4) forwards'
                  : phase === 'expanding'
                  ? 'cardMorphIn 440ms cubic-bezier(.2,.85,.25,1.02) both'
                  : cardPos
                  ? 'none'
                  : 'cardEnter 320ms cubic-bezier(.2,.8,.2,1)',
              transition: dragging ? 'none' : 'box-shadow 200ms ease',
              cursor: dragging ? 'grabbing' : 'grab',
              pointerEvents: phase === 'collapsing' ? 'none' : 'auto',
              willChange: 'transform, opacity',
            }}
          >
            {/* Panda mascot — BOTTOM layer (z-index:-1), painted behind the white
                card panel so the panda looks like it is hiding behind the login
                card; only its head + upper body peek above the card's top edge. */}
            <div
              data-no-drag
              style={{ position: 'absolute', left: '50%', bottom: 'calc(100% - 96px)', transform: 'translateX(-50%)', pointerEvents: 'none', zIndex: -1 }}
            >
              <LoginPandaMascot covering={pwFocused} />
            </div>
            {/* White card surface — also z-index:-1 but ordered AFTER the panda, so
                it paints over the panda's lower body. Card content (no z-index) sits
                above both. Carries the card's white fill + drop shadow. */}
            <div
              aria-hidden="true"
              style={{
                position: 'absolute', inset: 0, borderRadius: 22, background: '#fff', zIndex: -1,
                boxShadow: dragging
                  ? '0 32px 80px rgba(0,0,0,.55), 0 8px 20px rgba(0,0,0,.22), 0 1px 0 rgba(255,255,255,.6) inset'
                  : '0 24px 60px rgba(0,0,0,.42), 0 6px 16px rgba(0,0,0,.16), 0 1px 0 rgba(255,255,255,.6) inset',
                transition: dragging ? 'none' : 'box-shadow 200ms ease',
                pointerEvents: 'none',
              }}
            />
            {/* Drag handle indicator (visual cue only — entire card is draggable) */}
            <div
              data-no-drag
              className="absolute left-0 right-12 top-0 h-8 flex items-start justify-center pointer-events-none"
            >
              <span
                className="mt-2 inline-flex items-center gap-[3px] transition-opacity"
                style={{ opacity: dragging ? 0.9 : 0.4 }}
              >
                <span style={{ width: 4, height: 4, borderRadius: '50%', background: '#6B6960' }} />
                <span style={{ width: 4, height: 4, borderRadius: '50%', background: '#6B6960' }} />
                <span style={{ width: 4, height: 4, borderRadius: '50%', background: '#6B6960' }} />
                <span style={{ width: 4, height: 4, borderRadius: '50%', background: '#6B6960', marginLeft: 5 }} />
                <span style={{ width: 4, height: 4, borderRadius: '50%', background: '#6B6960' }} />
                <span style={{ width: 4, height: 4, borderRadius: '50%', background: '#6B6960' }} />
              </span>
            </div>
            {/* Minimize button (top-right) — universal minus */}
            <button
              onClick={goMinimize}
              data-no-drag
              className="absolute top-1.5 right-1.5 w-11 h-11 rounded-full inline-flex items-center justify-center transition-colors"
              style={{ color: '#6B6960', background: '#F1F5F9' }}
              onMouseEnter={(e) => { e.currentTarget.style.background = '#E2E8F0'; }}
              onMouseLeave={(e) => { e.currentTarget.style.background = '#F1F5F9'; }}
              title="收起登录卡 · 浏览校园"
              aria-label="收起登录卡"
            >
              <svg width="13" height="13" viewBox="0 0 14 14" fill="none">
                <path d="M3 7 H11" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"/>
              </svg>
            </button>
          <div className="login-card-content" style={{ maxHeight: `calc(100dvh - ${(cardPos?.y ?? 88) + 80}px)`, overflowY: 'auto', overscrollBehavior: 'contain' }}>
          <div
            className="mb-4 inline-flex items-center gap-2 rounded-full px-3 py-1.5"
            style={{
              background: isSuperPortal ? '#FAF5FF' : '#F8FAFC',
              border: `1px solid ${isSuperPortal ? '#E9D5FF' : '#E2E8F0'}`,
              color: isSuperPortal ? '#7E22CE' : '#475569',
            }}
          >
            <span
              className="w-1.5 h-1.5 rounded-full"
              style={{
                background: isSuperPortal ? '#A855F7' : '#10B981',
                boxShadow: isSuperPortal ? '0 0 8px rgba(168,85,247,.55)' : '0 0 8px rgba(16,185,129,.45)',
              }}
            />
            <span className="text-[10.5px] font-mono tracking-[.18em] uppercase">{portalCopy.eyebrow}</span>
            <span className="text-[11.5px] font-medium">{portalCopy.badge}</span>
          </div>

          <h1 className="text-[20px] font-bold leading-tight" style={{ color: '#1F1F1F' }}>
            {portalCopy.title}
          </h1>
          <p className="text-[12.5px] mt-1.5 mb-4" style={{ color: '#6B6960' }}>
            {portalCopy.subtitle}
          </p>

          <h2 className="hidden text-[20px] font-bold leading-tight" style={{ color: '#1F1F1F' }}>
            欢迎回来
          </h2>
          <p className="hidden text-[12.5px] mt-1.5 mb-4" style={{ color: '#6B6960' }}>
            登录以管理本学期监考排班与统计
          </p>

          {sessionError && (
            <div
              role="alert"
              aria-live="assertive"
              className="mb-4 rounded-md px-2.5 py-2 text-[11.5px] font-medium"
              style={{ background: '#FEF2F2', border: '1px solid #FECACA', color: '#B91C1C' }}
            >
              {sessionError}
            </div>
          )}

          <form onSubmit={handleLogin} className="space-y-3">
            <div>
              <label htmlFor="login-organization" className="block text-[11px] mb-1.5" style={{ color: '#6B6960' }}>学校编码（同名账号时必填）</label>
              <input id="login-organization" name="organization" autoComplete="organization" value={organization}
                onChange={event => setOrganization(event.target.value)} placeholder="可向学校管理员获取"
                className="w-full h-11 rounded-lg px-3.5 text-[13px]" style={{ background: '#F8FAFC', border: '1px solid #E2E8F0', color: '#1F1F1F' }} />
            </div>
            <div>
              <label htmlFor="login-username" className="block text-[11px] font-mono uppercase tracking-wider mb-1.5" style={{ color: '#6B6960' }}>
                工号 / 邮箱
              </label>
              <div className="flex items-center h-12 rounded-lg px-3.5" style={{ background: '#F8FAFC', border: '1px solid #E2E8F0' }}>
                <IconUsers size={15} className="text-slate-400" />
                <input
                  id="login-username"
                  name="username"
                  type="text"
                  autoComplete="username"
                  aria-invalid={Boolean(errors.username)}
                  aria-describedby={errors.username ? 'login-username-error' : undefined}
                  className="ml-2 h-full flex-1 bg-transparent outline-none text-[14px]"
                  value={username}
                  onChange={(e) => { setUsername(e.target.value); clearError('username'); }}
                  onFocus={() => setUserFocused(true)}
                  onBlur={() => setUserFocused(false)}
                  placeholder="请输入工号或邮箱"
                  style={{ color: '#1F1F1F' }}
                />
              </div>
              {errors.username && <div id="login-username-error" role="alert" className="mt-1 text-[11px]" style={{ color: '#B91C1C' }}>{errors.username}</div>}
            </div>

            <div>
              <div className="flex items-center justify-between mb-1.5">
                <label htmlFor="login-password" className="block text-[11px] font-mono uppercase tracking-wider" style={{ color: '#6B6960' }}>
                  密码
                </label>
                <button type="button" className="min-h-[44px] px-1 inline-flex items-center text-[11px] font-medium hover:underline"
                  style={{ color: 'var(--brand)' }}
                  onClick={() => openLoginGuide('recovery')}>
                  忘记密码?
                </button>
              </div>
              <div className="flex items-center h-12 rounded-lg px-3.5" style={{ background: '#F8FAFC', border: '1px solid #E2E8F0' }}>
                <IconSettings size={15} className="text-slate-400" />
                <input
                  id="login-password"
                  name="password"
                  type="password"
                  autoComplete="current-password"
                  aria-invalid={Boolean(errors.password)}
                  aria-describedby={errors.password ? 'login-password-error' : undefined}
                  className="ml-2 h-full flex-1 bg-transparent outline-none text-[14px] font-mono tracking-wider"
                  value={password}
                  onChange={(e) => { setPassword(e.target.value); clearError('password'); }}
                  onFocus={() => setPwFocused(true)}
                  onBlur={() => setPwFocused(false)}
                  placeholder="请输入密码"
                  style={{ color: '#1F1F1F' }}
                />
              </div>
              {errors.password && <div id="login-password-error" role="alert" className="mt-1 text-[11px]" style={{ color: '#B91C1C' }}>{errors.password}</div>}
            </div>

            <div className="login-captcha-row flex items-center gap-2">
              <div className="login-captcha-field flex-1 flex items-center h-12 rounded-lg px-3.5" style={{ background: '#F8FAFC', border: errors.captcha ? '1px solid #EF4444' : '1px solid #E2E8F0' }}>
                <input
                  id="login-captcha"
                  name="captcha"
                  className="h-full flex-1 bg-transparent outline-none text-[13px] font-mono tracking-widest uppercase"
                  value={captcha}
                  onChange={(e) => { setCaptcha(e.target.value); clearError('captcha'); }}
                  placeholder="验证码"
                  maxLength={8}
                  aria-invalid={!!errors.captcha}
                  aria-label="验证码"
                  aria-describedby={errors.captcha ? 'login-captcha-error' : undefined}
                  style={{ color: '#1F1F1F' }}
                />
              </div>
              <button type="button"
                className="login-captcha-button h-12 min-w-[108px] px-2 rounded-lg font-mono text-[13px] font-bold transition-colors hover:opacity-85 select-none relative overflow-hidden inline-flex items-center justify-center"
                style={{ background: '#1F1F1F', color: '#FAF6E9' }}
                onClick={refreshCaptcha}
                title="刷新验证码"
                aria-label="刷新验证码">
                {captchaImage ? (
                  <img className="login-captcha-img" src={captchaImage} alt="验证码图片" style={{ height: 34, width: 102, maxWidth: '100%', display: 'block', objectFit: 'contain' }} />
                ) : (
                  <span style={{ pointerEvents: 'none' }}>{captchaLoading ? '...' : '验证码'}</span>
                )}
              </button>
            </div>
            {errors.captcha && <div id="login-captcha-error" role="alert" className="text-[11px]" style={{ color: '#B91C1C' }}>{errors.captcha}</div>}

            <div className="min-h-[44px] inline-flex items-center gap-2">
              <Checkbox id="login-remember" checked={rememberLogin} onChange={(event) => setRememberLogin(event.target.checked)} label="记住 7 天" />
              <label htmlFor="login-remember" className="cursor-pointer text-[12px]" style={{ color: '#6B6960' }}>记住 7 天（仅保存账号）</label>
            </div>

            <button
              type="submit"
              disabled={loading}
              className="w-full h-12 rounded-lg text-[14.5px] font-semibold inline-flex items-center justify-center gap-2 transition-all"
              style={{
                background: loading ? '#94908A' : '#1F1F1F',
                color: '#FAF6E9',
                boxShadow: loading ? 'none' : '0 6px 16px rgba(31,31,31,.32)',
              }}
            >
              {loading ? (
                <>
                  <span className="w-3.5 h-3.5 rounded-full border-2 border-current border-t-transparent animate-spin" />
                  登录中…
                </>
              ) : (
                <>登录 →</>
              )}
            </button>
          </form>

          <div className="mt-5 pt-4 text-[11px] flex items-center justify-between" style={{ borderTop: '1px solid #E2E8F0', color: '#6B6960' }}>
            <span className="inline-flex items-center">
              <button
                type="button"
                className="min-h-[44px] inline-flex items-center font-medium hover:underline"
                style={{ color: 'var(--brand)' }}
                onClick={() => openLoginGuide('safety')}
              >
                使用与安全说明
              </button>
            </span>
            <span className="font-mono">v2.0.4</span>
          </div>
          </div>
        </div>
        )}

        {/* ── MINIMIZED FLOATING BALL — chroma-keyed video button ── */}
        {phase !== 'card' && (
          <button
            onMouseDown={startBallDrag}
            className="fixed z-20 group"
            style={{
              ...ballPositionStyle,
              width: 315, height: 315,
              borderRadius: '24px',
              background: 'transparent',
              boxShadow: 'none',
              color: '#FAF6E9',
              cursor: ballDragging ? 'grabbing' : 'grab',
              border: 'none',
              padding: 0,
              animation:
                phase === 'expanding'
                  ? 'ballMorphOut 380ms cubic-bezier(.55,.0,.7,.3) forwards'
                  : phase === 'collapsing'
                  ? 'ballMorphIn 480ms cubic-bezier(.25,.85,.3,1.08) both'
                  : ballPos
                  ? 'ballFloat 4.2s ease-in-out infinite'
                  : 'ballFloat 4.2s ease-in-out infinite',
              transition: ballDragging ? 'none' : 'filter 220ms ease',
              touchAction: 'none',
              filter: ballDragging
                ? 'drop-shadow(0 18px 36px rgba(0,0,0,.55)) drop-shadow(0 4px 10px rgba(0,0,0,.3))'
                : 'drop-shadow(0 12px 28px rgba(0,0,0,.42)) drop-shadow(0 2px 6px rgba(0,0,0,.25))',
              pointerEvents: phase === 'expanding' ? 'none' : 'auto',
              willChange: 'transform, opacity',
            }}
            title="点击展开 · 拖动移动位置"
            aria-label="展开登录卡"
          >
            {/* Soft elliptical glow + drifting light particles behind the plane button */}
            <span
              className="absolute inset-0 pointer-events-none"
              style={{
                background: 'radial-gradient(ellipse 60% 45% at 50% 62%, rgba(120,180,255,.22) 0%, rgba(120,180,255,0) 70%)',
                animation: 'ballRipple 3.2s ease-out infinite',
              }}
            />
            <LoginGlowParticles count={9} color="150,200,255" area={315} spread={0.9} />
            {/* Chroma-keyed GIF — paper-plane "登录" button (green removed live) */}
            <ChromaGif src="assets/login-plane.gif" width={315} height={315} />
            {/* Tooltip bubble */}
            <span className="absolute right-full mr-3 top-1/2 -translate-y-1/2 whitespace-nowrap rounded-lg px-3 py-1.5 text-[11.5px] font-medium pointer-events-none opacity-0 group-hover:opacity-100 transition-opacity"
              style={{ background: 'rgba(31,31,31,.92)', color: '#FAF6E9', boxShadow: '0 4px 12px rgba(0,0,0,.2)' }}>
              点击展开 · 拖动移动
              <span className="absolute left-full top-1/2 -translate-y-1/2 w-0 h-0"
                style={{ borderTop: '5px solid transparent', borderBottom: '5px solid transparent', borderLeft: '6px solid rgba(31,31,31,.92)' }} />
            </span>
          </button>
        )}
      </div>

      <style>{`
        html:has(.login-root), body:has(.login-root) {
          overflow: hidden;
          overscroll-behavior: none;
        }
        @media (max-width: 767px) {
          .login-legacy-brand > span { flex-shrink: 0; }
          .login-legacy-brand > span img { height: 34px !important; max-width: none; }
        }
        @keyframes loginMote {
          0%   { transform: translate(0, 0) scale(.7); opacity: 0; }
          20%  { opacity: 1; }
          80%  { opacity: .8; }
          100% { transform: translate(var(--drift), -38px) scale(1.1); opacity: 0; }
        }
        @keyframes kenBurns {
          0%   { transform: scale(1.02) translate(0, 0); }
          100% { transform: scale(1.06) translate(-1.5%, -1%); }
        }
        @keyframes shake {
          0%, 100% { transform: translateX(0); }
          20%, 60% { transform: translateX(-6px); }
          40%, 80% { transform: translateX(6px); }
        }
        @keyframes cardExpand {
          from { opacity: 0; transform: scale(.85); }
          to   { opacity: 1; transform: scale(1); }
        }
        @keyframes cardEnter {
          from { opacity: 0; transform: scale(.92) translateY(-6px); filter: blur(4px); }
          to   { opacity: 1; transform: scale(1) translateY(0); filter: blur(0); }
        }
        @keyframes cardMorphIn {
          0%   { opacity: 0; transform: scale(.08); filter: blur(8px); }
          35%  { opacity: 1; filter: blur(2px); }
          70%  { transform: scale(1.018); filter: blur(0); }
          100% { opacity: 1; transform: scale(1); filter: blur(0); }
        }
        @keyframes cardCollapse {
          0%   { opacity: 1; transform: scale(1); filter: blur(0); }
          35%  { opacity: .9; filter: blur(1px); }
          100% { opacity: 0; transform: scale(.06); filter: blur(10px); }
        }
        @keyframes ballEnter {
          from { opacity: 0; transform: scale(.4) translateY(20px); }
          to   { opacity: 1; transform: scale(1) translateY(0); }
        }
        @keyframes ballMorphIn {
          0%   { opacity: 0; transform: scale(.05); filter: blur(6px); }
          50%  { opacity: 1; filter: blur(1px); }
          75%  { transform: scale(1.12); filter: blur(0); }
          100% { opacity: 1; transform: scale(1); filter: blur(0); }
        }
        @keyframes ballMorphOut {
          0%   { opacity: 1; transform: scale(1); filter: blur(0); }
          100% { opacity: 0; transform: scale(.08); filter: blur(8px); }
        }
        @keyframes ballFloat {
          0%, 100% { transform: translateY(0) scale(1); }
          50%      { transform: translateY(-4px) scale(1.03); }
        }
        @keyframes ballPulse {
          0%, 100% { transform: scale(1); }
          50%      { transform: scale(1.04); }
        }
        @keyframes ballRipple {
          0%   { transform: scale(.95); opacity: .7; }
          80%  { transform: scale(1.6); opacity: 0; }
          100% { transform: scale(1.6); opacity: 0; }
        }
        @keyframes flapTopDown {
          0%   { transform: rotateX(0deg); }
          100% { transform: rotateX(-90deg); }
        }
        @keyframes flapBottomUp {
          0%   { transform: rotateX(90deg); }
          100% { transform: rotateX(0deg); }
        }
        .shake-anim { animation: shake 380ms ease-in-out; }

        /* ── Beijing date card · frosted glass hover ── */
        .beijing-date-card:hover {
          transform: translateY(-4px) scale(1.012);
          background: linear-gradient(135deg, rgba(255,255,255,.26) 0%, rgba(255,255,255,.14) 50%, rgba(255,255,255,.22) 100%);
          border-color: rgba(196,178,232,.65);
          box-shadow:
            0 18px 44px rgba(0,0,0,.35),
            0 4px 12px rgba(0,0,0,.22),
            0 0 0 1px rgba(196,178,232,.35),
            0 0 32px rgba(186,160,232,.32),
            0 1px 0 rgba(255,255,255,.34) inset;
        }
        .beijing-date-card::before {
          content: '';
          position: absolute;
          inset: 0;
          border-radius: inherit;
          padding: 1px;
          background: linear-gradient(135deg, rgba(255,255,255,.32), rgba(255,255,255,.04) 40%, rgba(255,255,255,0) 70%);
          -webkit-mask: linear-gradient(#000, #000) content-box, linear-gradient(#000, #000);
          -webkit-mask-composite: xor;
                  mask-composite: exclude;
          pointer-events: none;
          opacity: .8;
        }

        /* ── Cursor water-drop ripple ── */
        .cursor-ripple {
          position: fixed;
          width: 14px;
          height: 14px;
          border-radius: 50%;
          pointer-events: none;
          transform: translate(-50%, -50%) scale(.5);
          z-index: 9999;
          mix-blend-mode: screen;
          background: radial-gradient(circle, rgba(196,178,232,.55) 0%, rgba(180,160,232,.22) 45%, rgba(180,160,232,0) 70%);
          border: 1.4px solid rgba(214,198,242,.7);
          box-shadow: 0 0 14px rgba(196,178,232,.5), inset 0 0 6px rgba(255,255,255,.4);
          animation: cursorRipple 920ms cubic-bezier(.2,.7,.25,1) forwards;
          will-change: transform, opacity;
        }
        .cursor-ripple.dark {
          mix-blend-mode: normal;
          background: radial-gradient(circle, rgba(120,90,180,.32) 0%, rgba(120,90,180,.12) 45%, rgba(120,90,180,0) 70%);
          border-color: rgba(150,120,210,.6);
          box-shadow: 0 0 10px rgba(150,120,210,.5), inset 0 0 5px rgba(255,255,255,.18);
        }
        @keyframes cursorRipple {
          0%   { transform: translate(-50%, -50%) scale(.45); opacity: .85; border-width: 1.6px; }
          55%  { opacity: .55; }
          100% { transform: translate(-50%, -50%) scale(3.2); opacity: 0; border-width: .4px; }
        }
        .cursor-dot {
          position: fixed;
          width: 6px;
          height: 6px;
          border-radius: 50%;
          pointer-events: none;
          transform: translate(-50%, -50%);
          z-index: 9998;
          background: radial-gradient(circle, rgba(255,255,255,.95) 0%, rgba(214,198,242,.6) 60%, rgba(196,178,232,0) 100%);
          box-shadow: 0 0 8px rgba(196,178,232,.7);
          opacity: 0;
          transition: opacity 180ms ease, transform 60ms linear;
          will-change: transform;
        }
        .cursor-dot.active { opacity: 1; }
      `}</style>
    </div>
  );
}

window.LoginPage = LoginPage;
