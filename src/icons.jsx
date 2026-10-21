// Custom 2px stroke icon set — no Lucide defaults
// Sized via className, color via currentColor

const Icon = ({ children, size = 18, className = '', strokeWidth = 1.75 }) => (
  <svg
    width={size} height={size} viewBox="0 0 24 24"
    fill="none" stroke="currentColor"
    strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round"
    aria-hidden="true" focusable="false"
    className={className}
  >
    {children}
  </svg>
);

const IconDashboard = (p) => (
  <Icon {...p}>
    <rect x="3" y="3" width="7" height="9" rx="1.5" />
    <rect x="14" y="3" width="7" height="5" rx="1.5" />
    <rect x="14" y="12" width="7" height="9" rx="1.5" />
    <rect x="3" y="16" width="7" height="5" rx="1.5" />
  </Icon>
);

const IconBolt = (p) => (
  <Icon {...p}>
    <path d="M13 2 L4 14 H11 L10 22 L20 9 H13 L13 2 Z" />
  </Icon>
);

const IconSparkle = (p) => (
  <Icon {...p}>
    <path d="M12 3 L13.5 9.5 L20 11 L13.5 12.5 L12 19 L10.5 12.5 L4 11 L10.5 9.5 Z" />
    <path d="M19 4 L19.7 6 L21.7 6.7 L19.7 7.3 L19 9 L18.3 7.3 L16.3 6.7 L18.3 6 Z" />
  </Icon>
);

const IconEdit = (p) => (
  <Icon {...p}>
    <path d="M4 20 L8 19 L19.5 7.5 L16.5 4.5 L5 16 L4 20 Z" />
    <path d="M14 7 L17 10" />
  </Icon>
);

const IconList = (p) => (
  <Icon {...p}>
    <path d="M8 6 H21" />
    <path d="M8 12 H21" />
    <path d="M8 18 H21" />
    <circle cx="4" cy="6" r="1" fill="currentColor" stroke="none" />
    <circle cx="4" cy="12" r="1" fill="currentColor" stroke="none" />
    <circle cx="4" cy="18" r="1" fill="currentColor" stroke="none" />
  </Icon>
);

const IconChart = (p) => (
  <Icon {...p}>
    <path d="M3 3 V21 H21" />
    <rect x="7" y="13" width="2.5" height="5" rx=".5" />
    <rect x="11.5" y="9" width="2.5" height="9" rx=".5" />
    <rect x="16" y="5" width="2.5" height="13" rx=".5" />
  </Icon>
);

const IconUsers = (p) => (
  <Icon {...p}>
    <circle cx="9" cy="8" r="3.2" />
    <path d="M3 20 C3 16.5 6 14.5 9 14.5 C12 14.5 15 16.5 15 20" />
    <path d="M16 5 A3 3 0 0 1 16 11" />
    <path d="M17 14.5 C19.5 15 21 16.5 21 20" />
  </Icon>
);

const IconBuilding = (p) => (
  <Icon {...p}>
    <rect x="4" y="3" width="16" height="18" rx="1" />
    <path d="M9 8 H10 M14 8 H15 M9 12 H10 M14 12 H15 M9 16 H10 M14 16 H15" />
    <path d="M10 21 V18 H14 V21" />
  </Icon>
);

const IconBook = (p) => (
  <Icon {...p}>
    <path d="M4 4 H10 A2 2 0 0 1 12 6 V20 A2 2 0 0 0 10 18 H4 Z" />
    <path d="M20 4 H14 A2 2 0 0 0 12 6 V20 A2 2 0 0 1 14 18 H20 Z" />
  </Icon>
);

const IconArchive = (p) => (
  <Icon {...p}>
    <rect x="3" y="4" width="18" height="4" rx="1" />
    <path d="M5 8 V19 A1 1 0 0 0 6 20 H18 A1 1 0 0 0 19 19 V8" />
    <path d="M10 13 H14" />
  </Icon>
);

const IconSettings = (p) => (
  <Icon {...p}>
    <circle cx="12" cy="12" r="2.8" />
    <path d="M12 3 V5 M12 19 V21 M3 12 H5 M19 12 H21 M5.6 5.6 L7 7 M17 17 L18.4 18.4 M5.6 18.4 L7 17 M17 7 L18.4 5.6" />
  </Icon>
);

const IconLogout = (p) => (
  <Icon {...p}>
    <path d="M14 4 H6 A2 2 0 0 0 4 6 V18 A2 2 0 0 0 6 20 H14" />
    <path d="M10 12 H21" />
    <path d="M17 8 L21 12 L17 16" />
  </Icon>
);

const IconChevron = ({ dir = 'down', ...p }) => {
  const rot = { down: 0, up: 180, left: 90, right: -90 }[dir];
  return (
    <Icon {...p}>
      <g transform={`rotate(${rot} 12 12)`}>
        <path d="M6 9 L12 15 L18 9" />
      </g>
    </Icon>
  );
};

const IconSearch = (p) => (
  <Icon {...p}>
    <circle cx="11" cy="11" r="6.5" />
    <path d="M16 16 L20.5 20.5" />
  </Icon>
);

const IconBell = (p) => (
  <Icon {...p}>
    <path d="M6 16 V11 A6 6 0 0 1 18 11 V16 L19.5 18 H4.5 Z" />
    <path d="M10 21 A2 2 0 0 0 14 21" />
  </Icon>
);

const IconHelp = (p) => (
  <Icon {...p}>
    <circle cx="12" cy="12" r="9" />
    <path d="M9.5 9.5 A2.5 2.5 0 0 1 14.5 9.5 C14.5 11 12 11.5 12 13.5" />
    <circle cx="12" cy="17" r=".5" fill="currentColor" stroke="none" strokeWidth="2" />
  </Icon>
);

const IconCalendar = (p) => (
  <Icon {...p}>
    <rect x="3" y="5" width="18" height="16" rx="1.5" />
    <path d="M3 10 H21" />
    <path d="M8 3 V7 M16 3 V7" />
  </Icon>
);

const IconArrowUp = (p) => (
  <Icon {...p}>
    <path d="M12 19 V5" />
    <path d="M6 11 L12 5 L18 11" />
  </Icon>
);
const IconArrowDown = (p) => (
  <Icon {...p}>
    <path d="M12 5 V19" />
    <path d="M6 13 L12 19 L18 13" />
  </Icon>
);

const IconUpload = (p) => (
  <Icon {...p}>
    <path d="M12 16 V4" />
    <path d="M7 9 L12 4 L17 9" />
    <path d="M4 17 V19 A1 1 0 0 0 5 20 H19 A1 1 0 0 0 20 19 V17" />
  </Icon>
);

const IconPlus = (p) => (
  <Icon {...p}>
    <path d="M12 5 V19 M5 12 H19" />
  </Icon>
);

const IconClock = (p) => (
  <Icon {...p}>
    <circle cx="12" cy="12" r="9" />
    <path d="M12 7 V12 L15.5 14" />
  </Icon>
);

const IconCheck = (p) => (
  <Icon {...p}>
    <path d="M5 12.5 L10 17 L19 7" />
  </Icon>
);

const IconAlert = (p) => (
  <Icon {...p}>
    <path d="M12 3 L22 20 H2 Z" />
    <path d="M12 10 V14" />
    <circle cx="12" cy="17" r=".5" fill="currentColor" stroke="none" strokeWidth="2" />
  </Icon>
);

const IconMore = (p) => (
  <Icon {...p}>
    <circle cx="5" cy="12" r="1.4" fill="currentColor" stroke="none" />
    <circle cx="12" cy="12" r="1.4" fill="currentColor" stroke="none" />
    <circle cx="19" cy="12" r="1.4" fill="currentColor" stroke="none" />
  </Icon>
);

const IconShield = (p) => (
  <Icon {...p}>
    <path d="M12 3 L20 6 V11 C20 16 16.5 19.5 12 21 C7.5 19.5 4 16 4 11 V6 Z" />
    <path d="M9 12 L11 14 L15.5 9.5" />
  </Icon>
);

// ImageGen-authored brand mark. The production copy has genuine PNG alpha;
// small interface glyphs below stay deterministic SVG so they remain crisp.
const BrandMark = ({ size = 28, className = '' }) => (
  <img
    src="assets/brand/jkxt-q-seats-imagegen-v4.png"
    width={size}
    height={size}
    className={className}
    alt="Quick 排监考"
    draggable="false"
  />
);

// Friendly flat-colour semantic family, reconstructed as transparent SVG from
// the approved visual references. No tile, gradient, shadow or generic AI motif.
const PRODUCT_PALETTE = {
  blue: '#4C73D9', sky: '#83BDEC', purple: '#7664CF', green: '#35A47F',
  lime: '#8ACB57', yellow: '#EFC552', orange: '#E99B43', coral: '#E2736E',
  white: '#FFFFFF', ink: '#43536A', mist: '#E9F0F8',
};

const ProductGlyph = ({ name = 'dashboard', size = 24, className = '', active = false }) => {
  const c = PRODUCT_PALETTE;
  const glyphs = {
    dashboard: <>
      <rect x="2" y="2" width="8" height="8" rx="2.6" fill={c.blue} />
      <rect x="13" y="2" width="9" height="6" rx="2.6" fill={c.yellow} />
      <rect x="13" y="11" width="9" height="11" rx="3" fill={c.green} />
      <rect x="2" y="13" width="8" height="9" rx="2.7" fill={c.purple} />
    </>,
    quick: <>
      <rect x="2" y="4" width="13" height="6" rx="3" fill={c.blue} />
      <rect x="2" y="14" width="13" height="6" rx="3" fill={c.sky} />
      <path d="M14 7h3.2c1.8 0 2.8 1.2 2.8 3v2" fill="none" stroke={c.yellow} strokeWidth="2.6" strokeLinecap="round" />
      <circle cx="20" cy="16" r="3.5" fill={c.green} />
      <path d="m18.4 16 1.1 1.1 2.1-2.4" fill="none" stroke={c.white} strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
    </>,
    strategy: <>
      <circle cx="5" cy="6" r="3" fill={c.purple} />
      <circle cx="5" cy="18" r="3" fill={c.sky} />
      <circle cx="19" cy="12" r="4" fill={c.green} />
      <path d="M8 6h2c3.8 0 3.1 6 7 6M8 18h2c3.8 0 3.1-6 7-6" fill="none" stroke={c.yellow} strokeWidth="2.4" strokeLinecap="round" />
    </>,
    edit: <>
      <rect x="2" y="3" width="14" height="18" rx="4" fill={c.sky} />
      <rect x="5" y="7" width="8" height="2.2" rx="1.1" fill={c.white} />
      <rect x="5" y="12" width="6" height="2.2" rx="1.1" fill={c.white} />
      <path d="m11 19 1.1-4 6.8-6.8 3 3-6.8 6.8Z" fill={c.coral} />
      <path d="m18.9 8.2 1.5-1.5c.5-.5 1.3-.5 1.8 0l1.1 1.1c.5.5.5 1.3 0 1.8l-1.4 1.5Z" fill={c.yellow} />
    </>,
    tasks: <>
      <rect x="3" y="2" width="18" height="20" rx="4.5" fill={c.blue} />
      <circle cx="7" cy="8" r="1.5" fill={c.yellow} />
      <rect x="10" y="7" width="7.5" height="2" rx="1" fill={c.white} />
      <circle cx="7" cy="15.5" r="2" fill={c.green} />
      <path d="m5.9 15.5.8.8 1.6-1.8" fill="none" stroke={c.white} strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round" />
      <rect x="10" y="14.5" width="7.5" height="2" rx="1" fill={c.white} />
    </>,
    stats: <>
      <rect x="2" y="13" width="5" height="9" rx="2.5" fill={c.orange} />
      <rect x="9.5" y="7" width="5" height="15" rx="2.5" fill={c.blue} />
      <rect x="17" y="3" width="5" height="19" rx="2.5" fill={c.green} />
      <circle cx="19.5" cy="3.5" r="2" fill={c.yellow} />
    </>,
    people: <>
      <circle cx="9" cy="7" r="4" fill={c.blue} />
      <path d="M2.5 21c.4-5.2 2.7-8 6.5-8s6.1 2.8 6.5 8Z" fill={c.sky} />
      <circle cx="17.5" cy="9" r="3" fill={c.yellow} />
      <path d="M13.5 21c.2-3.8 1.6-6.2 4.2-6.2s4 2.4 4.3 6.2Z" fill={c.green} />
    </>,
    room: <>
      <path d="m2 10 10-7 10 7v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2Z" fill={c.sky} />
      <rect x="5" y="11" width="5" height="4" rx="1.4" fill={c.yellow} />
      <rect x="14" y="11" width="5" height="4" rx="1.4" fill={c.green} />
      <rect x="9" y="17" width="6" height="5" rx="1.5" fill={c.blue} />
    </>,
    classes: <>
      <rect x="2" y="3" width="15" height="18" rx="4" fill={c.sky} />
      <rect x="6" y="7" width="7" height="3" rx="1.5" fill={c.white} />
      <rect x="6" y="13" width="7" height="3" rx="1.5" fill={c.white} />
      <circle cx="18.5" cy="8" r="3.5" fill={c.yellow} />
      <path d="M14.5 21c.2-4 1.6-6.2 4-6.2s3.9 2.2 4.1 6.2Z" fill={c.green} />
    </>,
    subjects: <>
      <path d="M2 5.2A3.2 3.2 0 0 1 5.2 2H10c1.1 0 2 .9 2 2v17c0-1.7-1.4-3-3-3H2Z" fill={c.blue} />
      <path d="M22 5.2A3.2 3.2 0 0 0 18.8 2H14c-1.1 0-2 .9-2 2v17c0-1.7 1.4-3 3-3h7Z" fill={c.orange} />
      <path d="M16 2h4v7l-2-1.5L16 9Z" fill={c.yellow} />
    </>,
    history: <>
      <circle cx="12" cy="12" r="9.5" fill={c.orange} />
      <path d="M12 6v6l4 2" fill="none" stroke={c.white} strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M4.5 5.5V2.8M4.5 2.8h3" fill="none" stroke={c.blue} strokeWidth="2.5" strokeLinecap="round" />
    </>,
    audit: <>
      <rect x="2" y="3" width="15" height="19" rx="3.5" fill={c.sky} />
      <rect x="5" y="7" width="8" height="2" rx="1" fill={c.white} />
      <rect x="5" y="12" width="6" height="2" rx="1" fill={c.white} />
      <path d="M17 8.5 22 10v4.1c0 3.3-2 5.8-5 7-3-1.2-5-3.7-5-7V10Z" fill={c.green} />
      <path d="m14.8 14.5 1.5 1.5 3-3.5" fill="none" stroke={c.white} strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
    </>,
    settings: <>
      <rect x="2" y="4" width="20" height="3" rx="1.5" fill={c.sky} />
      <rect x="2" y="10.5" width="20" height="3" rx="1.5" fill={c.blue} />
      <rect x="2" y="17" width="20" height="3" rx="1.5" fill={c.purple} />
      <circle cx="8" cy="5.5" r="3" fill={c.yellow} />
      <circle cx="17" cy="12" r="3" fill={c.green} />
      <circle cx="10" cy="18.5" r="3" fill={c.coral} />
    </>,
    logout: <>
      <path d="M3 4.5A2.5 2.5 0 0 1 5.5 2H14v20H5.5A2.5 2.5 0 0 1 3 19.5Z" fill={c.coral} />
      <path d="M10 12h11M17 8l4 4-4 4" fill="none" stroke={c.white} strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" />
      <circle cx="7" cy="12" r="1.1" fill={c.yellow} />
    </>,
    tenant: <>
      <path d="m2 8 10-6 10 6v14H2Z" fill={c.blue} />
      <path d="M2 8h20L12 2Z" fill={c.yellow} />
      <rect x="5" y="10" width="4" height="4" rx="1.2" fill={c.sky} />
      <rect x="15" y="10" width="4" height="4" rx="1.2" fill={c.green} />
      <rect x="9.5" y="16" width="5" height="6" rx="1.4" fill={c.white} />
    </>,
    account: <>
      <rect x="2" y="3" width="20" height="18" rx="4" fill={c.purple} />
      <circle cx="8" cy="9" r="3" fill={c.yellow} />
      <path d="M4.5 17c.3-3 1.5-4.5 3.5-4.5s3.2 1.5 3.5 4.5Z" fill={c.sky} />
      <rect x="14" y="7" width="5" height="2" rx="1" fill={c.white} />
      <rect x="14" y="12" width="5" height="2" rx="1" fill={c.white} />
      <rect x="14" y="17" width="3.5" height="2" rx="1" fill={c.white} />
    </>,
    security: <>
      <path d="M12 2 21 5.5v6.2c0 5.3-3.5 8.8-9 10.3-5.5-1.5-9-5-9-10.3V5.5Z" fill={c.green} />
      <circle cx="17.5" cy="6.5" r="3" fill={c.yellow} />
      <path d="m7.5 12 3 3 6-7" fill="none" stroke={c.white} strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" />
    </>,
    announcement: <>
      <path d="M3 9h6l9-5v16l-9-5H3Z" fill={c.coral} />
      <path d="M9 15 11 22H6.5L5 15Z" fill={c.yellow} />
      <path d="M21 8c1.2 1 1.8 2.3 1.8 4S22.2 15 21 16" fill="none" stroke={c.blue} strokeWidth="2.3" strokeLinecap="round" />
    </>,
    upload: <>
      <path d="M3 15h18v5a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z" fill={c.blue} />
      <path d="M12 3v13M7.5 7.5 12 3l4.5 4.5" fill="none" stroke={c.green} strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" />
      <circle cx="18" cy="18.5" r="1.5" fill={c.yellow} />
    </>,
    archive: <>
      <rect x="3" y="6" width="18" height="16" rx="3" fill={c.orange} />
      <rect x="2" y="3" width="20" height="6" rx="2.5" fill={c.yellow} />
      <rect x="8" y="12" width="8" height="2.5" rx="1.25" fill={c.white} />
      <circle cx="18.5" cy="18.5" r="2.5" fill={c.blue} />
    </>,
    calendar: <>
      <rect x="2" y="4" width="20" height="18" rx="4" fill={c.coral} />
      <path d="M2 9h20v9a4 4 0 0 1-4 4H6a4 4 0 0 1-4-4Z" fill={c.white} />
      <rect x="6" y="2" width="3" height="6" rx="1.5" fill={c.blue} />
      <rect x="15" y="2" width="3" height="6" rx="1.5" fill={c.blue} />
      <circle cx="12" cy="15" r="3.5" fill={c.yellow} />
    </>,
    search: <>
      <circle cx="10.5" cy="10.5" r="7" fill={c.sky} />
      <circle cx="10.5" cy="10.5" r="3.8" fill={c.white} />
      <path d="m15.7 15.7 5.5 5.5" fill="none" stroke={c.blue} strokeWidth="3.2" strokeLinecap="round" />
      <circle cx="6" cy="6" r="2.2" fill={c.yellow} />
    </>,
    help: <>
      <path d="M12 2.2c5.5 0 9.8 3.8 9.8 9 0 5.5-4 10-9.8 10.6C6.2 21.2 2.2 16.7 2.2 11.2c0-5.2 4.3-9 9.8-9Z" fill={c.sky} />
      <path d="M8.7 8.8c.4-2.2 2-3.4 4.1-3.4 2.4 0 4.2 1.4 4.2 3.7 0 2.1-1.3 3-2.7 3.8-1 .6-1.5 1.2-1.5 2.3" fill="none" stroke={c.white} strokeWidth="2.3" strokeLinecap="round" />
      <circle cx="12.7" cy="18" r="1.5" fill={c.yellow} />
    </>,
    examTasks: <>
      <rect x="3" y="3" width="18" height="19" rx="4" fill={c.blue} />
      <rect x="8" y="1" width="8" height="4" rx="2" fill={c.sky} />
      <rect x="6" y="8" width="3.5" height="3.5" rx="1" fill={c.white} />
      <path d="m6.8 9.8.8.8 1.5-1.8" fill="none" stroke={c.green} strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round" />
      <rect x="11.5" y="8.7" width="6.5" height="2" rx="1" fill={c.white} />
      <rect x="6" y="14" width="3.5" height="3.5" rx="1" fill={c.yellow} />
      <rect x="11.5" y="14.7" width="6.5" height="2" rx="1" fill={c.white} />
    </>,
    supervisionHours: <>
      <circle cx="10.5" cy="11" r="8.5" fill={c.blue} />
      <circle cx="10.5" cy="11" r="5.4" fill={c.white} />
      <path d="M10.5 7.2V11l3 1.8" fill="none" stroke={c.blue} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
      <circle cx="18.5" cy="17.5" r="4.3" fill={c.yellow} />
    </>,
    taskConflict: <>
      <path d="M10.1 3.6a2.2 2.2 0 0 1 3.8 0l8 14a2.2 2.2 0 0 1-1.9 3.3H4a2.2 2.2 0 0 1-1.9-3.3Z" fill={c.coral} />
      <rect x="10.7" y="8" width="2.6" height="6.8" rx="1.3" fill={c.white} />
      <circle cx="12" cy="18" r="1.5" fill={c.white} />
      <path d="m18.2 7.5 2.4 2.4" stroke={c.yellow} strokeWidth="2.4" strokeLinecap="round" />
    </>,
    taskCompleted: <>
      <path d="M4 2h12l4 4v16H4Z" fill={c.green} />
      <path d="M16 2v5h4" fill={c.yellow} />
      <path d="m8 14 2.7 2.7 5.6-6" fill="none" stroke={c.white} strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" />
    </>,
    uploadTeachers: <>
      <rect x="2" y="3" width="16" height="18" rx="3" fill={c.sky} />
      <rect x="4.5" y="7" width="3" height="3" rx=".8" fill={c.white} />
      <rect x="9.5" y="7" width="5.5" height="2" rx="1" fill={c.white} />
      <rect x="4.5" y="12" width="3" height="3" rx=".8" fill={c.white} />
      <rect x="9.5" y="12" width="5.5" height="2" rx="1" fill={c.white} />
      <circle cx="18.5" cy="10" r="3.4" fill={c.green} />
      <path d="M14.5 22c.2-4.4 1.6-6.5 4-6.5s3.9 2.1 4 6.5Z" fill={c.green} />
    </>,
    uploadRooms: <>
      <rect x="2" y="3" width="17" height="18" rx="3" fill={c.sky} />
      <rect x="4.5" y="7" width="3" height="3" rx=".8" fill={c.white} />
      <rect x="9.5" y="7" width="5.5" height="2" rx="1" fill={c.white} />
      <rect x="4.5" y="12" width="3" height="3" rx=".8" fill={c.white} />
      <rect x="9.5" y="12" width="5.5" height="2" rx="1" fill={c.white} />
      <path d="m13 14.5 5-4 5 4V22H13Z" fill={c.orange} />
      <rect x="17" y="17" width="3" height="5" rx="1" fill={c.white} />
    </>,
    uploadExams: <>
      <rect x="2" y="3" width="17" height="18" rx="3" fill={c.sky} />
      <rect x="4.5" y="7" width="3" height="3" rx=".8" fill={c.white} />
      <rect x="9.5" y="7" width="5.5" height="2" rx="1" fill={c.white} />
      <rect x="4.5" y="12" width="3" height="3" rx=".8" fill={c.white} />
      <rect x="9.5" y="12" width="5.5" height="2" rx="1" fill={c.white} />
      <circle cx="18" cy="17" r="5" fill={c.yellow} />
      <path d="M18 14v3l2 1.2" fill="none" stroke={c.white} strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
    </>,
    reportResults: <>
      <path d="M4 2h12l4 4v16H4Z" fill={c.blue} />
      <path d="M16 2v5h4" fill={c.sky} />
      <rect x="7" y="13" width="2.5" height="5" rx="1.2" fill={c.green} />
      <rect x="10.8" y="10" width="2.5" height="8" rx="1.2" fill={c.yellow} />
      <rect x="14.6" y="12" width="2.5" height="6" rx="1.2" fill={c.coral} />
      <rect x="7" y="7" width="6" height="2" rx="1" fill={c.white} />
    </>,
  };
  const aliases = {
    termSupervisions: 'calendar', activeStaff: 'people', availableRooms: 'room',
    allowanceTotal: 'stats', homeroomTeachers: 'classes', staffLeave: 'supervisionHours',
    prepareData: 'uploadTeachers', validateData: 'taskCompleted',
    generateSchedule: 'strategy', reviewResult: 'reportResults',
    taskTotal: 'examTasks', taskRunning: 'supervisionHours',
    supervisionCount: 'calendar', teacherParticipation: 'people',
  };
  const resolvedName = aliases[name] || name;
  return (
    <svg
      width={size} height={size} viewBox="0 0 24 24"
      className={`product-glyph ${className}`.trim()}
      data-product-glyph={name}
      data-active={active ? 'true' : 'false'}
      aria-hidden="true" focusable="false"
    >
      {glyphs[resolvedName] || glyphs.dashboard}
    </svg>
  );
};

const createProductIcon = (name) => (props) => <ProductGlyph name={name} {...props} />;
const ProductIconDashboard = createProductIcon('dashboard');
const ProductIconQuick = createProductIcon('quick');
const ProductIconStrategy = createProductIcon('strategy');
const ProductIconEdit = createProductIcon('edit');
const ProductIconTasks = createProductIcon('tasks');
const ProductIconStats = createProductIcon('stats');
const ProductIconPeople = createProductIcon('people');
const ProductIconRoom = createProductIcon('room');
const ProductIconClasses = createProductIcon('classes');
const ProductIconSubjects = createProductIcon('subjects');
const ProductIconHistory = createProductIcon('history');
const ProductIconAudit = createProductIcon('audit');
const ProductIconSettings = createProductIcon('settings');
const ProductIconLogout = createProductIcon('logout');
const ProductIconTenant = createProductIcon('tenant');
const ProductIconAccount = createProductIcon('account');
const ProductIconSecurity = createProductIcon('security');
const ProductIconAnnouncement = createProductIcon('announcement');
const ProductIconUpload = createProductIcon('upload');
const ProductIconArchive = createProductIcon('archive');
const ProductIconCalendar = createProductIcon('calendar');
const ProductIconSearch = createProductIcon('search');
const ProductIconHelp = createProductIcon('help');

const PRODUCT_ICON_COMPONENTS = {
  dashboard: ProductIconDashboard, quick: ProductIconQuick, strategy: ProductIconStrategy,
  edit: ProductIconEdit, tasks: ProductIconTasks, stats: ProductIconStats,
  people: ProductIconPeople, room: ProductIconRoom, classes: ProductIconClasses,
  subjects: ProductIconSubjects, history: ProductIconHistory, audit: ProductIconAudit,
  settings: ProductIconSettings, logout: ProductIconLogout,
  tenant: ProductIconTenant, account: ProductIconAccount, security: ProductIconSecurity,
  announcement: ProductIconAnnouncement, upload: ProductIconUpload,
  archive: ProductIconArchive, calendar: ProductIconCalendar,
  search: ProductIconSearch, help: ProductIconHelp,
};

const SidebarGlyph = ({ name, size = 20, className = '', active = false }) => (
  <ProductGlyph name={name} size={size} className={className} active={active} />
);

Object.assign(window, {
  Icon,
  IconDashboard, IconBolt, IconSparkle, IconEdit, IconList,
  IconChart, IconUsers, IconBuilding, IconBook, IconArchive,
  IconSettings, IconLogout, IconChevron, IconSearch, IconBell,
  IconHelp, IconCalendar, IconArrowUp, IconArrowDown, IconUpload,
  IconPlus, IconClock, IconCheck, IconAlert, IconMore, IconShield,
  BrandMark, ProductGlyph, PRODUCT_ICON_COMPONENTS, SidebarGlyph,
});
