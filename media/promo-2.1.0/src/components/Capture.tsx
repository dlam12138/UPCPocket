import React from 'react';
import {Img, staticFile, useCurrentFrame} from 'remotion';
import {CAPTURES} from '../screens';
import {C, FONT, SCREEN} from '../theme';
import {MockScreen} from '../ui/MockScreen';
import {il} from '../lib/anim';

export type Focus = {x: number; y: number; w: number; h: number};

const FULL: Focus = {x: 0, y: 0, w: 1, h: 1};

const MOCK_ALIAS: Record<string, string> = {
  'home-demo': 'home',
  'home-synced': 'home',
  'schedule-demo': 'schedule',
  'schedule-day': 'schedule',
  'course-detail': 'course-detail',
  'todo-demo': 'todo',
  'focus-demo': 'focus',
  'library-records': 'library',
  'library-venues': 'library',
  'map-origin': 'map',
  'map-point': 'map',
  'map-guzhenkou': 'map',
  'power-guzhenkou': 'power',
  'gym-swim': 'gym',
  'services-search': 'services',
  'classroom-query': 'classroom',
  'card-history': 'card-history',
  'card-recharge': 'card-recharge',
  'shiguang': 'shiguang',
  'estation': 'estation',
};

export const captureExists = (id: string) => Boolean(CAPTURES[id]);

export const mockIdFor = (id: string) => MOCK_ALIAS[id] ?? id;

/**
 * A screenshot (or a labelled code-drawn stand-in) rendered at an exact crop.
 * focus is expressed in fractions of the source image, so capture and mock
 * share one interface. The container always matches the crop aspect ratio.
 */
export const Shot: React.FC<{
  id: string;
  width: number;
  focus?: Focus;
  radius?: number;
  badge?: string;
  style?: React.CSSProperties;
  imageStyle?: React.CSSProperties;
  shadow?: boolean;
  breathe?: number;
  onDark?: boolean;
}> = ({
  id,
  width,
  focus = FULL,
  radius = 0,
  badge,
  style,
  imageStyle,
  shadow = false,
  breathe = 0,
  onDark,
}) => {
  const frame = useCurrentFrame();
  const cap = CAPTURES[id];
  const mockId = MOCK_ALIAS[id] ?? id;
  const sourceW = cap?.width ?? SCREEN.w;
  const sourceH = cap?.height ?? SCREEN.h;
  const ar = (sourceW * focus.w) / (sourceH * focus.h);
  const height = width / ar;
  const zoom = breathe ? 1 + il(frame, [0, 240], [0, breathe]) : 1;
  const badgeEl = badge ? (
    <div
      style={{
        position: 'absolute',
        left: 12,
        top: 12,
        zIndex: 3,
        padding: '5px 10px',
        borderRadius: 999,
        background: onDark ? 'rgba(10,20,16,0.62)' : 'rgba(255,255,255,0.84)',
        border: `1px solid ${onDark ? 'rgba(255,255,255,0.16)' : 'rgba(16,32,26,0.10)'}`,
        fontFamily: FONT.cjk,
        fontSize: 11,
        fontWeight: 600,
        letterSpacing: 0.5,
        color: onDark ? 'rgba(255,255,255,0.8)' : C.ink3,
        backdropFilter: 'blur(6px)',
      }}
    >
      {badge}
    </div>
  ) : null;

  return (
    <div
      style={{
        width,
        height,
        borderRadius: radius,
        overflow: 'hidden',
        position: 'relative',
        background: '#FAF8FF',
        boxShadow: shadow
          ? '0 42px 90px -34px rgba(15, 35, 26, 0.5), 0 10px 26px rgba(15,35,26,0.12)'
          : undefined,
        ...style,
      }}
    >
      {cap ? (
        <Img
          src={staticFile(cap.file)}
          style={{
            position: 'absolute',
            width: `${100 / focus.w}%`,
            height: `${100 / focus.h}%`,
            left: `${(-focus.x * 100) / focus.w}%`,
            top: `${(-focus.y * 100) / focus.h}%`,
            maxWidth: 'none',
            objectFit: 'fill',
            transform: `scale(${zoom})`,
            transformOrigin: 'center',
            ...imageStyle,
          }}
        />
      ) : (
        <div
          style={{
            width: SCREEN.w,
            height: SCREEN.h,
            transform: `scale(${width / SCREEN.w})`,
            transformOrigin: 'top left',
          }}
        >
          <MockScreen id={mockId} />
        </div>
      )}
      {badgeEl}
    </div>
  );
};

/** A whole source image shown inside its natural aspect, no crop. */
export const PlainShot: React.FC<{
  id: string;
  width: number;
  radius?: number;
  style?: React.CSSProperties;
  shadow?: boolean;
}> = ({id, width, radius = 0, style, shadow}) => {
  const cap = CAPTURES[id];
  const ar = cap ? cap.width / cap.height : SCREEN.w / SCREEN.h;
  return (
    <div
      style={{
        width,
        height: width / ar,
        borderRadius: radius,
        overflow: 'hidden',
        background: '#fff',
        boxShadow: shadow ? '0 36px 80px -36px rgba(15,35,26,0.5)' : undefined,
        ...style,
      }}
    >
      {cap ? (
        <Img
          src={staticFile(cap.file)}
          style={{width: '100%', height: '100%', objectFit: 'cover', display: 'block'}}
        />
      ) : (
        <div
          style={{
            width: SCREEN.w,
            height: SCREEN.h,
            transform: `scale(${width / SCREEN.w})`,
            transformOrigin: 'top left',
          }}
        >
          <MockScreen id={id} />
        </div>
      )}
    </div>
  );
};

/** Small "this is a real capture / synthetic data" note placed outside phones. */
export const SourceNote: React.FC<{
  children: React.ReactNode;
  dark?: boolean;
  style?: React.CSSProperties;
}> = ({children, dark, style}) => (
  <div
    style={{
      display: 'flex',
      alignItems: 'flex-start',
      gap: 8,
      fontFamily: FONT.cjk,
      fontSize: 16,
      lineHeight: 1.55,
      color: dark ? 'rgba(255,255,255,0.46)' : C.ink3,
      ...style,
    }}
  >
    <span
      style={{
        width: 5,
        height: 5,
        borderRadius: 999,
        marginTop: 8,
        background: dark ? 'rgba(255,255,255,0.34)' : C.ink3,
        flex: 'none',
      }}
    />
    <span>{children}</span>
  </div>
);
