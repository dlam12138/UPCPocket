import React from 'react';

export type GlyphName =
  | 'home'
  | 'calendar'
  | 'grid'
  | 'timer'
  | 'search'
  | 'pin'
  | 'map'
  | 'walk'
  | 'bike'
  | 'navigate'
  | 'phone'
  | 'users'
  | 'book'
  | 'card'
  | 'bolt'
  | 'classroom'
  | 'check'
  | 'checkCircle'
  | 'plus'
  | 'clock'
  | 'bell'
  | 'settings'
  | 'refresh'
  | 'link'
  | 'web'
  | 'wechat'
  | 'chart'
  | 'graduation'
  | 'event'
  | 'receipt'
  | 'list'
  | 'close'
  | 'arrow'
  | 'layers'
  | 'seat'
  | 'building'
  | 'compass'
  | 'spark'
  | 'lock'
  | 'info';

/**
 * Small custom line-icon set, drawn to one 24px grid so chips and mock UI keep
 * a consistent weight. Material paths are deliberately avoided here; the app
 * screenshots already carry the product's real iconography.
 */
const PATHS: Record<GlyphName, React.ReactNode> = {
  home: <path d="M4 10.5 12 4l8 6.5V20a1 1 0 0 1-1 1h-4.5v-6h-5v6H5a1 1 0 0 1-1-1Z" />,
  calendar: (
    <>
      <rect x="3.5" y="5" width="17" height="15.5" rx="3" />
      <path d="M7.5 3v4M16.5 3v4M3.5 10h17" />
    </>
  ),
  grid: (
    <>
      <rect x="4" y="4" width="6.4" height="6.4" rx="1.6" />
      <rect x="13.6" y="4" width="6.4" height="6.4" rx="1.6" />
      <rect x="4" y="13.6" width="6.4" height="6.4" rx="1.6" />
      <rect x="13.6" y="13.6" width="6.4" height="6.4" rx="1.6" />
    </>
  ),
  timer: (
    <>
      <circle cx="12" cy="13.2" r="7.2" />
      <path d="M12 9.6v3.9l2.4 1.5M9.5 3h5" />
    </>
  ),
  search: (
    <>
      <circle cx="10.8" cy="10.8" r="6.3" />
      <path d="m15.6 15.6 4 4" />
    </>
  ),
  pin: (
    <>
      <path d="M9 3h6l-1.1 5.2 3.1 3.1H7l3.1-3.1Z" />
      <path d="M12 11.3V21" />
    </>
  ),
  map: (
    <>
      <path d="m3.5 6.5 5-2 7 2 5-2v13l-5 2-7-2-5 2Z" />
      <path d="M8.5 4.5v13M15.5 6.5v13" />
    </>
  ),
  walk: (
    <>
      <circle cx="13.2" cy="4.6" r="1.9" />
      <path d="m11 9 3 2.2 1.2 4.2M11 9l-2.3 4.5M11 9l-1 5.2 3 2.3L12 21M9 13.7 5.8 16M13.8 15.4 17.4 21" />
    </>
  ),
  bike: (
    <>
      <circle cx="6" cy="17" r="3.4" />
      <circle cx="18" cy="17" r="3.4" />
      <path d="m6.4 17 4.2-7h3.8l3.6 7M9.8 10h5.6M12.2 7h2.6" />
    </>
  ),
  navigate: <path d="M20.4 3.6 3.8 10.4l7 2.8 2.8 7Z" />,
  phone: (
    <path d="M6.2 3.8h3l1.3 4-2 1.4a12.6 12.6 0 0 0 6.3 6.3l1.4-2 4 1.3v3a2 2 0 0 1-2.2 2A16.8 16.8 0 0 1 4.2 6a2 2 0 0 1 2-2.2Z" />
  ),
  users: (
    <>
      <circle cx="9" cy="8.2" r="3.1" />
      <path d="M3.8 20c.4-3.5 2.4-5.4 5.2-5.4s4.8 1.9 5.2 5.4" />
      <path d="M15.2 5.6a3 3 0 0 1 0 5.4M16.6 14.8c2 .6 3.2 2.3 3.5 5" />
    </>
  ),
  book: (
    <>
      <path d="M4.2 4.6c2.8-.8 5-.4 7.8 1.3 2.8-1.7 5-2.1 7.8-1.3v14c-2.8-.8-5-.4-7.8 1.3-2.8-1.7-5-2.1-7.8-1.3Z" />
      <path d="M12 5.9v14" />
    </>
  ),
  card: (
    <>
      <rect x="3" y="5.5" width="18" height="13" rx="2.6" />
      <path d="M3 9.6h18M6.6 14.6h4" />
    </>
  ),
  bolt: <path d="M13.2 2.8 5.6 13h5.2l-1 8.2L17.8 11h-5.3Z" />,
  classroom: (
    <>
      <path d="M4 20V5.2A1.2 1.2 0 0 1 5.2 4h10.6A1.2 1.2 0 0 1 17 5.2V20" />
      <path d="M17 10h2.2A1.2 1.2 0 0 1 20.4 11.2V20M3 20h18M7 8h2M7 12h2M7 16h2M12.6 8h1.8M12.6 12h1.8" />
    </>
  ),
  check: <path d="m4.5 12.6 4.6 4.6L19.8 6.5" />,
  checkCircle: (
    <>
      <circle cx="12" cy="12" r="8.6" />
      <path d="m8 12.2 2.7 2.7 5.4-5.6" />
    </>
  ),
  plus: <path d="M12 5v14M5 12h14" />,
  clock: (
    <>
      <circle cx="12" cy="12" r="8.4" />
      <path d="M12 7.6V12l3 1.9" />
    </>
  ),
  bell: (
    <>
      <path d="M6.4 10a5.6 5.6 0 0 1 11.2 0c0 4 1.4 5.6 2 6.2H4.4c.6-.6 2-2.2 2-6.2Z" />
      <path d="M9.8 19.2a2.4 2.4 0 0 0 4.4 0" />
    </>
  ),
  settings: (
    <>
      <circle cx="12" cy="12" r="3.2" />
      <path d="M19 13.8a1.5 1.5 0 0 0 .3 1.7l.1.1-2 3.4-.2-.1a1.5 1.5 0 0 0-1.7.1 1.5 1.5 0 0 0-.7 1.5v.2h-4v-.2a1.5 1.5 0 0 0-.7-1.5 1.5 1.5 0 0 0-1.7-.1l-.2.1-2-3.4.1-.1a1.5 1.5 0 0 0 .3-1.7 1.5 1.5 0 0 0-1.3-.9H5v-4h.2a1.5 1.5 0 0 0 1.3-.9 1.5 1.5 0 0 0-.3-1.7l-.1-.1 2-3.4.2.1a1.5 1.5 0 0 0 1.7-.1 1.5 1.5 0 0 0 .7-1.5v-.2h4v.2a1.5 1.5 0 0 0 .7 1.5 1.5 1.5 0 0 0 1.7.1l.2-.1 2 3.4-.1.1a1.5 1.5 0 0 0-.3 1.7 1.5 1.5 0 0 0 1.3.9h.2v4h-.2a1.5 1.5 0 0 0-1.3.9Z" />
    </>
  ),
  refresh: (
    <>
      <path d="M20 11.2A8 8 0 1 0 18.6 17" />
      <path d="M20.4 5v6h-6" />
    </>
  ),
  link: (
    <>
      <path d="M10 13.8a4.2 4.2 0 0 0 6 0l2.6-2.6a4.2 4.2 0 0 0-6-6l-1.4 1.4" />
      <path d="M14 10.2a4.2 4.2 0 0 0-6 0l-2.6 2.6a4.2 4.2 0 0 0 6 6l1.4-1.4" />
    </>
  ),
  web: (
    <>
      <circle cx="12" cy="12" r="8.6" />
      <path d="M3.6 12h16.8M12 3.4c2.3 2.3 3.4 5.2 3.4 8.6s-1.1 6.3-3.4 8.6c-2.3-2.3-3.4-5.2-3.4-8.6S9.7 5.7 12 3.4Z" />
    </>
  ),
  wechat: (
    <>
      <path d="M9.4 4.2c-3.6 0-6.4 2.3-6.4 5.2 0 1.7 1 3.2 2.5 4.1l-.6 2.2 2.3-1.2a8 8 0 0 0 2.2.3c.4 0 .8 0 1.1-.1" />
      <path d="M21 13.4c0-2.5-2.4-4.5-5.4-4.5s-5.4 2-5.4 4.5 2.4 4.5 5.4 4.5c.6 0 1.2-.1 1.8-.2l2 1-.5-1.8c1.3-.8 2.1-2.1 2.1-3.5Z" />
    </>
  ),
  chart: (
    <>
      <path d="M4 20V4M4 20h16" />
      <path d="M8 16.4V12M12.2 16.4V7.6M16.4 16.4v-6.6M20.4 16.4V5.8" />
    </>
  ),
  graduation: (
    <>
      <path d="m2.8 8.6 9.2-4.2 9.2 4.2-9.2 4.2Z" />
      <path d="M6.4 10.6v4.6c0 1.6 2.5 3 5.6 3s5.6-1.4 5.6-3v-4.6M21.2 8.6v6" />
    </>
  ),
  event: (
    <>
      <rect x="3.6" y="5" width="16.8" height="15.4" rx="2.6" />
      <path d="M7.6 3v4M16.4 3v4M3.6 10h16.8" />
      <path d="m8.6 15 1.6 1.6 3.4-3.6" />
    </>
  ),
  receipt: (
    <>
      <path d="M6 3.6h12v17l-2.4-1.6-2.4 1.6-2.4-1.6L8.4 20.6 6 19Z" />
      <path d="M9 8h6M9 11.6h6M9 15.2h4" />
    </>
  ),
  list: (
    <>
      <path d="M9 6.5h11M9 12h11M9 17.5h11" />
      <path d="M4.2 6.5h.1M4.2 12h.1M4.2 17.5h.1" />
    </>
  ),
  close: <path d="M6 6l12 12M18 6 6 18" />,
  arrow: <path d="M4 12h15M13.6 6.4 19.4 12l-5.8 5.6" />,
  layers: (
    <>
      <path d="m12 3.4 8.6 4.4L12 12.2 3.4 7.8Z" />
      <path d="m4.6 12 7.4 3.8 7.4-3.8M4.6 16.2l7.4 3.8 7.4-3.8" />
    </>
  ),
  seat: (
    <>
      <path d="M6.4 12V6.2A2.2 2.2 0 0 1 8.6 4h6.8a2.2 2.2 0 0 1 2.2 2.2V12" />
      <path d="M5 12h14v5.6H5zM7.4 17.6V20M16.6 17.6V20" />
    </>
  ),
  building: (
    <>
      <path d="M4 20V6.6L12 3l8 3.6V20M2.6 20h18.8" />
      <path d="M8 9h2M14 9h2M8 13h2M14 13h2M10 20v-3.4h4V20" />
    </>
  ),
  compass: (
    <>
      <circle cx="12" cy="12" r="8.6" />
      <path d="m15.6 8.4-2 5.2-5.2 2 2-5.2Z" />
    </>
  ),
  spark: <path d="M12 3.2 13.6 9l5.8 1.6-5.8 1.6L12 18l-1.6-5.8L4.6 10.6 10.4 9Z" />,
  lock: (
    <>
      <rect x="5" y="10" width="14" height="10.4" rx="2.2" />
      <path d="M8.2 10V7.4a3.8 3.8 0 0 1 7.6 0V10" />
    </>
  ),
  info: (
    <>
      <circle cx="12" cy="12" r="8.6" />
      <path d="M12 10.6V17M12 7.4h.1" />
    </>
  ),
};

export const Glyph: React.FC<{
  name: GlyphName;
  size?: number;
  color?: string;
  strokeWidth?: number;
  style?: React.CSSProperties;
  opacity?: number;
}> = ({name, size = 24, color = 'currentColor', strokeWidth = 1.7, style, opacity = 1}) => (
  <svg
    width={size}
    height={size}
    viewBox="0 0 24 24"
    fill="none"
    stroke={color}
    strokeWidth={strokeWidth}
    strokeLinecap="round"
    strokeLinejoin="round"
    opacity={opacity}
    style={{display: 'block', flex: 'none', ...style}}
    aria-hidden
  >
    {PATHS[name]}
  </svg>
);
