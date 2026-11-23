/**
 * Visual tokens for the UPC Pocket 2.1.0 film.
 *
 * Brand green is the launcher background (#1B6B4A). Product UI colours are
 * sampled from the 2.1.0 emulator captures in public/screens: lavender surface
 * #FAF8FF, primary #495D92, outline #DDDCE2.
 */

export const C = {
  paper: '#F1F3EC',
  paper2: '#EBEFE6',
  paper3: '#E3E8DC',
  ink: '#10201A',
  ink2: '#3C4A43',
  ink3: '#7C8880',
  line: 'rgba(16, 32, 26, 0.10)',
  lineStrong: 'rgba(16, 32, 26, 0.20)',

  brand: '#1B6B4A',
  brandDeep: '#0D3B28',
  brandMid: '#2F7D5C',
  brandSoft: '#DCEAE1',
  brandGlow: 'rgba(27, 107, 74, 0.30)',

  blue: '#495D92',
  blueSoft: '#E3E7F6',
  blueDeep: '#2E3E69',

  amber: '#A9762F',
  amberSoft: '#F3E9D6',

  coral: '#B4564A',
  coralSoft: '#F5E3DE',

  white: '#FFFFFF',
  night: '#0A1410',
  nightInk: '#EDF2EA',

  // product UI (light), from the real captures
  uiSurface: '#FAF8FF',
  uiContainer: '#F1EFF8',
  uiContainer2: '#E9E6F4',
  uiOutline: '#DDDCE2',
  uiPrimary: '#495D92',
  uiPrimarySoft: '#DDE2F9',
  uiOnSurface: '#1A1B21',
  uiOnVariant: '#474751',
  uiGreen: '#1F7A52',
  uiGreenSoft: '#D9EEE3',
  uiError: '#B3261E',
  uiAmber: '#8A5A12',
} as const;

export const FONT = {
  cjk: "'PromoSC', 'Noto Sans SC', 'Microsoft YaHei', sans-serif",
  mono: "'Cascadia Mono', 'Consolas', monospace",
} as const;

/** 1920x1080 type scale. */
export const T = {
  hero: 104,
  h1: 82,
  h2: 62,
  h3: 44,
  lead: 32,
  body: 26,
  small: 21,
  label: 17,
  micro: 14,
} as const;

export const W = 1920;
export const H = 1080;
export const PAD = 120;

/** In-phone UI canvas: 411 x 868 dp at 1080 px width (19:9, matches captures). */
export const SCREEN = {
  w: 1080,
  h: 2280,
  dockH: 118,
  dockBottom: 18,
  pxPerDp: 1080 / 411,
} as const;

export const dp = (v: number) => v * SCREEN.pxPerDp;
