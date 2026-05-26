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
    <span style={{ display: 'inline-flex' }}>
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
        BEIJING · UTC+8
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

// ── ChromaVideo: plays a video and chroma-keys out a green background ──
// Renders a transparent canvas — useful for "alpha video" sourced from a
// green-screen .mp4 without needing a separate alpha track.
function ChromaVideo({ src, playing, size = 72, onReady }) {
  const videoRef  = React.useRef(null);
  const canvasRef = React.useRef(null);
  const rafRef    = React.useRef(0);
  const readyRef  = React.useRef(false);

  // Start / stop playback in lockstep with `playing`
  React.useEffect(() => {
    const v = videoRef.current;
    if (!v) return;
    if (playing) {
      v.currentTime = v.currentTime || 0;
      const p = v.play();
      if (p && typeof p.catch === 'function') p.catch(() => {});
    } else {
      try { v.pause(); } catch {}
    }
  }, [playing]);

  // Per-frame chroma-key loop (only runs while playing)
  React.useEffect(() => {
    if (!playing) return;
    const v = videoRef.current;
    const c = canvasRef.current;
    if (!v || !c) return;
    const ctx = c.getContext('2d', { willReadFrequently: true });

    const tick = () => {
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
      rafRef.current = requestAnimationFrame(tick);
    };
    rafRef.current = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(rafRef.current);
  }, [playing, onReady]);

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

function LoginPage({ onSuccess }) {
  const now = useClock();
  const [role, setRole]         = React.useState('admin');
  const [username, setUsername] = React.useState('wangmy');
  const [password, setPassword] = React.useState('Admin@2025');
  const [captcha, setCaptcha]   = React.useState('A7K9');
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
    : { right: 56, top: 88, left: 'auto', transform: 'none' };

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
    : { right: 32, bottom: 150, left: 'auto', top: 'auto' };

  // 预填账号密码 · 随 role 切换
  const PRESET = {
    admin: { user: 'wangmy',         pwd: 'Admin@2025',   captcha: 'A7K9', hint: '本校教务主任' },
    super: { user: 'superadmin@jkxt', pwd: 'Admin123!!!',  captcha: 'A7K9', hint: '跨校平台超管' },
  };
  React.useEffect(() => {
    const p = PRESET[role];
    setUsername(p.user);
    setPassword(p.pwd);
    setCaptcha(p.captcha);
  }, [role]);

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

  const handleLogin = (e) => {
    e?.preventDefault();
    if (!password) {
      setShake(true);
      setTimeout(() => setShake(false), 500);
      window.ui?.toast('请输入密码', { kind: 'warning' });
      return;
    }
    setLoading(true);
    setTimeout(() => {
      setLoading(false);
      if (role === 'super') {
        try {
          localStorage.setItem('jkxt:mode', 'super');
          localStorage.setItem('jkxt:page', 'super-dashboard');
        } catch {}
        window.ui?.toast('超管登录成功', { kind: 'success', desc: '欢迎进入 JKXT 平台超级管理员控制台' });
      } else {
        try {
          localStorage.setItem('jkxt:mode', 'admin');
          localStorage.setItem('jkxt:page', 'dashboard');
        } catch {}
        window.ui?.toast('登录成功', { kind: 'success', desc: '欢迎回到 天津市实验中学 教务管理系统' });
      }
      onSuccess?.({ username, role });
    }, 800);
  };

  return (
    <div className="min-h-screen w-screen flex flex-col login-root relative" style={{
      background: 'linear-gradient(180deg, #F1ECF5 0%, #E9E2F0 8%, #DCD2E7 16%, #CFC2DD 22%, #CFC2DD 100%)',
      fontFamily: 'Geist, system-ui',
    }}>
      {/* ── TOP STRIP — brand + role ── */}
      <div className="px-8 py-4 flex items-center justify-between shrink-0">
        <div className="flex items-center gap-2.5">
          <BrandMark size={26} />
          <span className="text-[15px] font-semibold tracking-tight" style={{ color: '#1F1F1F' }}>
            排监考系统
          </span>
          <span className="font-mono text-[10px] uppercase tracking-[0.16em]" style={{ color: '#6B6960' }}>
            v2.0 · K-12
          </span>
        </div>
        <div className="flex items-center gap-3">
          <span className="inline-flex items-center gap-1.5 text-[12px] font-mono" style={{ color: '#6B6960' }}>
            <span className="w-1.5 h-1.5 rounded-full" style={{ background: '#10B981' }} />
            系统在线 · 服务正常
          </span>
          <button className="h-8 px-3 rounded-md inline-flex items-center gap-1.5 text-[12px] hover:bg-black/5"
            style={{ color: '#1F1F1F' }}
            onClick={() => window.ui?.toast('帮助文档将在新窗口打开', { kind: 'info' })}>
            <IconHelp size={13} /> 帮助
          </button>
        </div>
      </div>

      {/* ── UPPER: GIANT CLOCK ── */}
      <div className="flex-[3] min-h-0 px-12 pt-2 pb-5 flex flex-col justify-center relative" style={{ maxHeight: '36vh', zIndex: 10 }}>
        {/* Labels row */}
        <div className="flex items-end justify-between w-full" style={{ gap: '0.18em', fontSize: 'clamp(96px, 16vw, 260px)' }}>
          <div className="flex flex-col items-start">
            <span className="font-mono uppercase mb-1.5" style={{ fontSize: '12px', letterSpacing: '0.22em', color: '#6B6960' }}>
              hours
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
          {/* Colon separator */}
          <span
            className="leading-none flex items-end"
            style={{
              color: '#1F1F1F',
              fontFamily: '"Archivo Black", sans-serif',
              fontWeight: 400,
              letterSpacing: '-0.05em',
              paddingBottom: '0.06em',
              opacity: 0.5,
            }}
          >
            :
          </span>
          <div className="flex flex-col items-start">
            <span className="font-mono uppercase mb-1.5" style={{ fontSize: '12px', letterSpacing: '0.22em', color: '#6B6960' }}>
              minutes
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
          {/* Colon separator (smaller for seconds gap) */}
          <span
            className="leading-none flex items-end"
            style={{
              color: '#1F1F1F',
              fontFamily: '"Archivo Black", sans-serif',
              fontWeight: 400,
              letterSpacing: '-0.05em',
              paddingBottom: '0.05em',
              fontSize: '0.72em',
              opacity: 0.42,
            }}
          >
            :
          </span>
          <div className="flex flex-col items-start">
            <span className="font-mono uppercase mb-1.5" style={{ fontSize: '12px', letterSpacing: '0.22em', color: '#6B6960' }}>
              seconds
            </span>
            <div
              className="leading-none"
              style={{
                color: '#1F1F1F',
                fontFamily: '"Archivo Black", "Geist", system-ui, sans-serif',
                fontWeight: 400,
                letterSpacing: '-0.05em',
                fontSize: '0.72em',
                paddingBottom: '0.04em',
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
        <video
          ref={(el) => {
            if (!el) return;
            // try unmute on first user gesture (browsers block unmuted autoplay)
            const tryUnmute = () => {
              try { el.muted = false; el.volume = 0.8; el.play().catch(() => {}); } catch {}
              document.removeEventListener('click', tryUnmute);
              document.removeEventListener('keydown', tryUnmute);
            };
            document.addEventListener('click', tryUnmute, { once: true });
            document.addEventListener('keydown', tryUnmute, { once: true });
          }}
          src="assets/login-bg.mp4"
          autoPlay
          loop
          muted
          playsInline
          className="absolute w-full"
          style={{ top: -160, height: 'calc(100% + 160px)', left: 0, right: 0, objectFit: 'cover', objectPosition: 'center' }}
        />
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
        <div className="absolute left-10 bottom-9 z-10 pointer-events-none select-none">
          <div
            className="text-white leading-none whitespace-nowrap"
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
            }}>@</span>
            十七岁少年想当歌手
          </div>
          <div
            className="mt-2 font-mono whitespace-nowrap"
            style={{
              fontSize: 'clamp(10px, 0.9vw, 12.5px)',
              letterSpacing: '0.32em',
              color: 'rgba(250,246,233,.7)',
              textShadow: '0 1px 4px rgba(0,0,0,.5)',
            }}
          >
            JKXT · K-12 EXAM PROCTORING SYSTEM
          </div>
        </div>

      {/* Bottom-right Beijing date — glass card */}
        <BeijingDateCard now={now} />

        {/* ── LOGIN CARD (floating, draggable, higher default position) ── */}
        {phase !== 'ball' && (
          <div
            data-login-card
            onMouseDown={startCardDrag}
            className={`fixed w-[380px] z-20 ${shake ? 'shake-anim' : ''}`}
            style={{
              ...cardPositionStyle,
              background: '#fff',
              borderRadius: 20,
              padding: 28,
              boxShadow: dragging
                ? '0 32px 80px rgba(0,0,0,.55), 0 8px 20px rgba(0,0,0,.22), 0 1px 0 rgba(255,255,255,.6) inset'
                : '0 24px 60px rgba(0,0,0,.42), 0 6px 16px rgba(0,0,0,.16), 0 1px 0 rgba(255,255,255,.6) inset',
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
            {/* Minimize button (top-right) */}
            <button
              onClick={goMinimize}
              className="absolute top-3 right-3 w-7 h-7 rounded-full inline-flex items-center justify-center hover:bg-slate-100 transition-colors"
              style={{ color: '#6B6960' }}
              title="收起登录卡 · 浏览校园"
            >
              <svg width="13" height="13" viewBox="0 0 14 14" fill="none">
                <path d="M2 12 L12 2 M8 2 H12 V6" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"/>
              </svg>
            </button>
          {/* Role tabs */}
          <div className="inline-flex p-1 rounded-lg w-full mb-5" style={{ background: '#F1F5F9' }}>
            {[
              { id: 'admin', label: '教务管理员', desc: '本校' },
              { id: 'super', label: '超级管理员', desc: '跨校' },
            ].map((r) => {
              const active = role === r.id;
              return (
                <button
                  key={r.id}
                  onClick={() => setRole(r.id)}
                  className="flex-1 h-9 rounded-md text-[12.5px] font-medium transition-all"
                  style={{
                    background: active ? '#fff' : 'transparent',
                    color: active ? '#1F1F1F' : '#6B6960',
                    boxShadow: active ? '0 1px 2px rgba(0,0,0,.06)' : 'none',
                  }}
                >
                  {r.label}
                </button>
              );
            })}
          </div>

          <h2 className="text-[20px] font-bold leading-tight" style={{ color: '#1F1F1F' }}>
            欢迎回来
          </h2>
          <p className="text-[12.5px] mt-1.5 mb-4" style={{ color: '#6B6960' }}>
            登录以管理本学期监考排班与统计
          </p>

          {/* Demo 凭证提示 */}
          <div className="mb-4 rounded-lg px-3 py-2 flex items-center gap-2 text-[11.5px]"
            style={{ background: '#EEF2FF', border: '1px solid #C7D2FE', color: '#3730A3' }}>
            <IconSparkle size={13} strokeWidth={2} />
            <span><b>演示凭证已预填</b> · {PRESET[role].hint} · 点击登录即可</span>
          </div>

          <form onSubmit={handleLogin} className="space-y-3">
            <div>
              <label className="block text-[11px] font-mono uppercase tracking-wider mb-1.5" style={{ color: '#6B6960' }}>
                工号 / 邮箱
              </label>
              <div className="flex items-center h-11 rounded-lg px-3" style={{ background: '#F8FAFC', border: '1px solid #E2E8F0' }}>
                <IconUsers size={15} className="text-slate-400" />
                <input
                  className="ml-2 flex-1 bg-transparent outline-none text-[13.5px]"
                  value={username}
                  onChange={(e) => setUsername(e.target.value)}
                  placeholder="请输入工号或邮箱"
                  style={{ color: '#1F1F1F' }}
                />
              </div>
            </div>

            <div>
              <div className="flex items-center justify-between mb-1.5">
                <label className="block text-[11px] font-mono uppercase tracking-wider" style={{ color: '#6B6960' }}>
                  密码
                </label>
                <button type="button" className="text-[11px] font-medium hover:underline"
                  style={{ color: 'var(--brand)' }}
                  onClick={() => window.ui?.toast('请联系教务处管理员重置密码', { kind: 'info' })}>
                  忘记密码?
                </button>
              </div>
              <div className="flex items-center h-11 rounded-lg px-3" style={{ background: '#F8FAFC', border: '1px solid #E2E8F0' }}>
                <IconSettings size={15} className="text-slate-400" />
                <input
                  type="password"
                  className="ml-2 flex-1 bg-transparent outline-none text-[13.5px] font-mono tracking-wider"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="请输入密码"
                  style={{ color: '#1F1F1F' }}
                />
              </div>
            </div>

            <div className="flex items-center gap-2">
              <div className="flex-1 flex items-center h-10 rounded-lg px-3" style={{ background: '#F8FAFC', border: '1px solid #E2E8F0' }}>
                <input
                  className="flex-1 bg-transparent outline-none text-[13px] font-mono tracking-widest"
                  value={captcha}
                  onChange={(e) => setCaptcha(e.target.value)}
                  placeholder="验证码"
                  style={{ color: '#1F1F1F' }}
                />
              </div>
              <button type="button" className="h-10 px-3 rounded-lg font-mono text-[14px] font-bold tracking-wider transition-colors hover:opacity-80"
                style={{ background: '#1F1F1F', color: '#FAF6E9', letterSpacing: '0.2em' }}
                onClick={() => window.ui?.toast('已刷新验证码', { kind: 'info' })}>
                A7K9
              </button>
            </div>

            <label className="inline-flex items-center gap-2 cursor-pointer">
              <Checkbox checked />
              <span className="text-[12px]" style={{ color: '#6B6960' }}>记住 7 天</span>
            </label>

            <button
              type="submit"
              disabled={loading}
              className="w-full h-11 rounded-lg text-[14px] font-semibold inline-flex items-center justify-center gap-2 transition-all"
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
            <span>登录即同意 <a className="font-medium hover:underline" style={{ color: 'var(--brand)' }}>服务条款</a></span>
            <span className="font-mono">v2.0.4</span>
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
              width: 140, height: 140,
              borderRadius: '50%',
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
            {/* Subtle halo glow behind the video — adds depth without competing with it */}
            <span
              className="absolute inset-0 rounded-full pointer-events-none"
              style={{
                background: 'radial-gradient(circle, rgba(255,255,255,.18) 0%, rgba(255,255,255,0) 62%)',
                animation: 'ballRipple 2.8s ease-out infinite',
              }}
            />
            {/* Chroma-keyed video — only renders frames while we're in ball phase */}
            <ChromaVideo
              src="assets/login-button.mp4"
              playing={phase === 'ball' || phase === 'collapsing'}
              size={140}
            />
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
