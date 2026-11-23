import React from 'react';
import {SCREEN} from '../theme';

/**
 * Phone body with a 1080x2280 screen canvas. Contents are laid out at the
 * capture resolution and scaled, so screenshots and code-drawn mock screens
 * share exactly the same geometry.
 */
export const PhoneFrame: React.FC<{
  width: number;
  children: React.ReactNode;
  style?: React.CSSProperties;
  glow?: string;
  tilt?: number;
}> = ({width, children, style, glow, tilt = 0}) => {
  const bezel = Math.max(7, width * 0.028);
  const height = (width * SCREEN.h) / SCREEN.w;
  const radius = width * 0.132;
  return (
    <div
      style={{
        width: width + bezel * 2,
        height: height + bezel * 2,
        padding: bezel,
        borderRadius: radius,
        background:
          'linear-gradient(152deg, #434B46 0%, #111714 34%, #1C2420 70%, #343C37 100%)',
        boxShadow: `0 64px 110px -46px rgba(8, 26, 18, 0.55), 0 12px 30px rgba(8, 26, 18, 0.18), inset 0 0 0 1.4px rgba(255,255,255,0.18)${
          glow ? `, 0 0 100px ${glow}` : ''
        }`,
        transform: tilt ? `rotate(${tilt}deg)` : undefined,
        ...style,
      }}
    >
      <div
        style={{
          width,
          height,
          borderRadius: radius - bezel * 0.82,
          overflow: 'hidden',
          position: 'relative',
          background: '#000',
          boxShadow: 'inset 0 0 0 1px rgba(0,0,0,0.55)',
        }}
      >
        <div
          style={{
            width: SCREEN.w,
            height: SCREEN.h,
            transform: `scale(${width / SCREEN.w})`,
            transformOrigin: 'top left',
          }}
        >
          {children}
        </div>
        <div
          style={{
            position: 'absolute',
            inset: 0,
            pointerEvents: 'none',
            background:
              'linear-gradient(116deg, rgba(255,255,255,0.10) 0%, rgba(255,255,255,0.02) 24%, rgba(255,255,255,0) 43%)',
          }}
        />
      </div>
    </div>
  );
};
