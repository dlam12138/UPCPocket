import React from 'react';
import {C} from '../theme';

/** Launcher artwork, reproduced from docs/images/app-icon.svg. */
export const BrandIcon: React.FC<{
  size: number;
  radius?: number;
  style?: React.CSSProperties;
  mono?: boolean;
}> = ({size, radius, style, mono}) => (
  <svg
    width={size}
    height={size}
    viewBox="0 0 108 108"
    style={{display: 'block', borderRadius: radius ?? size * 0.24, ...style}}
    aria-label="UPC Pocket"
  >
    <rect width="108" height="108" rx="26" fill={mono ? 'transparent' : C.brand} />
    <path
      d="M40 34v22c0 10 6 16 14 16s14-6 14-16V34"
      fill="none"
      stroke={mono ? C.brand : '#fff'}
      strokeWidth="7"
      strokeLinecap="round"
    />
    <path d="M46 44h16v5H46z" fill={mono ? C.brand : '#fff'} />
  </svg>
);
