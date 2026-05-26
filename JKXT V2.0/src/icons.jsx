// Custom 2px stroke icon set — no Lucide defaults
// Sized via className, color via currentColor

const Icon = ({ children, size = 18, className = '', strokeWidth = 1.75 }) => (
  <svg
    width={size} height={size} viewBox="0 0 24 24"
    fill="none" stroke="currentColor"
    strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round"
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

// Brand mark — abstract "stack of exam slots"
const BrandMark = ({ size = 28 }) => (
  <svg width={size} height={size} viewBox="0 0 32 32" fill="none">
    <rect x="3" y="6" width="11" height="4" rx="1" fill="#4F46E5" />
    <rect x="3" y="14" width="20" height="4" rx="1" fill="#6366F1" />
    <rect x="3" y="22" width="14" height="4" rx="1" fill="#A5B4FC" />
    <circle cx="26" cy="8" r="3" fill="#10B981" />
  </svg>
);

Object.assign(window, {
  Icon,
  IconDashboard, IconBolt, IconSparkle, IconEdit, IconList,
  IconChart, IconUsers, IconBuilding, IconBook, IconArchive,
  IconSettings, IconLogout, IconChevron, IconSearch, IconBell,
  IconHelp, IconCalendar, IconArrowUp, IconArrowDown, IconUpload,
  IconPlus, IconClock, IconCheck, IconAlert, IconMore,
  BrandMark,
});
