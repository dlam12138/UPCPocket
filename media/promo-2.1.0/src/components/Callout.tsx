import React from 'react';
import {useCurrentFrame} from 'remotion';
import {C, FONT} from '../theme';
import {il} from '../lib/anim';

export const Callout: React.FC<{
  x: number;
  y: number;
  text: string;
  side?: 'left' | 'right';
  start?: number;
  out?: number;
  tone?: 'green' | 'blue' | 'plain' | 'warn';
  dark?: boolean;
  line?: number;
  style?: React.CSSProperties;
}> = ({x, y, text, side = 'right', start = 0, out, tone = 'plain', dark, line = 42, style}) => {
  const frame = useCurrentFrame();
  const p = il(frame, [start, start + 18], [0, 1]);
  const o = out === undefined ? 1 : il(frame, [out, out + 12], [1, 0]);
  const colors = dark
    ? {
        green: {fg: '#B7E9CE', dot: '#7CC8A4', bd: 'rgba(183,233,206,0.24)', bg: 'rgba(12,31,23,0.72)'},
        blue: {fg: '#CBD6F5', dot: '#8FA4DC', bd: 'rgba(203,214,245,0.24)', bg: 'rgba(12,31,23,0.72)'},
        plain: {fg: 'rgba(255,255,255,0.8)', dot: 'rgba(255,255,255,0.56)', bd: 'rgba(255,255,255,0.18)', bg: 'rgba(12,31,23,0.72)'},
        warn: {fg: '#F1D6A7', dot: '#D9B26E', bd: 'rgba(241,214,167,0.24)', bg: 'rgba(12,31,23,0.72)'},
      }[tone]
    : {
        green: {fg: C.brand, dot: C.brand, bd: 'rgba(27,107,74,0.18)', bg: 'rgba(255,255,255,0.84)'},
        blue: {fg: C.blue, dot: C.blue, bd: 'rgba(73,93,146,0.18)', bg: 'rgba(255,255,255,0.84)'},
        plain: {fg: C.ink2, dot: C.ink3, bd: C.line, bg: 'rgba(255,255,255,0.82)'},
        warn: {fg: C.amber, dot: C.amber, bd: 'rgba(169,118,47,0.2)', bg: 'rgba(255,255,255,0.86)'},
      };
  return (
    <div
      style={{
        position: 'absolute',
        left: x,
        top: y,
        display: 'flex',
        alignItems: 'center',
        flexDirection: side === 'right' ? 'row' : 'row-reverse',
        transform: side === 'left' ? 'translateX(-100%)' : undefined,
        opacity: p * o,
        ...style,
      }}
    >
      <div
        style={{
          width: 9,
          height: 9,
          borderRadius: 999,
          background: colors.dot,
          boxShadow: `0 0 0 5px ${dark ? 'rgba(255,255,255,0.08)' : 'rgba(255,255,255,0.7)'}`,
          flex: 'none',
        }}
      />
      <div
        style={{
          width: line * p,
          height: 1,
          background: dark ? 'rgba(255,255,255,0.3)' : C.lineStrong,
          flex: 'none',
        }}
      />
      <div
        style={{
          maxWidth: 330,
          padding: '10px 15px',
          borderRadius: 14,
          background: colors.bg,
          border: `1px solid ${colors.bd}`,
          fontFamily: FONT.cjk,
          fontSize: 19,
          fontWeight: 600,
          lineHeight: 1.4,
          color: colors.fg,
          boxShadow: dark ? '0 12px 28px rgba(0,0,0,0.28)' : '0 12px 28px rgba(16,32,26,0.10)',
          backdropFilter: 'blur(8px)',
          whiteSpace: 'nowrap',
        }}
      >
        {text}
      </div>
    </div>
  );
};

/** Soft tap indicator used over a phone screen. x/y are percentages. */
export const Tap: React.FC<{
  x: number;
  y: number;
  at: number;
  color?: string;
  size?: number;
}> = ({x, y, at, color = C.brand, size = 72}) => {
  const frame = useCurrentFrame();
  const p = il(frame, [at, at + 30], [0, 1]);
  const fade = il(frame, [at + 20, at + 46], [1, 0]);
  if (frame < at) return null;
  return (
    <div
      style={{
        position: 'absolute',
        left: `${x}%`,
        top: `${y}%`,
        width: size,
        height: size,
        marginLeft: -size / 2,
        marginTop: -size / 2,
        borderRadius: 999,
        border: `2px solid ${color}`,
        opacity: (1 - p) * 0.9 + 0.1,
        transform: `scale(${0.35 + p * 0.9})`,
      }}
    >
      <div
        style={{
          position: 'absolute',
          inset: '36%',
          borderRadius: 999,
          background: color,
          opacity: fade,
        }}
      />
    </div>
  );
};

export const SwipeHint: React.FC<{
  x: number;
  y: number;
  at: number;
  dir?: 'left' | 'right';
  label?: string;
  dark?: boolean;
}> = ({x, y, at, dir = 'left', label = '滑动', dark}) => {
  const frame = useCurrentFrame();
  const p = il(frame, [at, at + 22], [0, 1]);
  const fade = il(frame, [at + 40, at + 64], [1, 0]);
  return (
    <div
      style={{
        position: 'absolute',
        left: x,
        top: y,
        display: 'flex',
        alignItems: 'center',
        gap: 10,
        opacity: Math.min(p, fade),
        fontFamily: FONT.cjk,
        fontSize: 17,
        fontWeight: 600,
        color: dark ? 'rgba(255,255,255,0.7)' : C.ink3,
      }}
    >
      <span>{label}</span>
      <span style={{fontSize: 22, transform: `scaleX(${dir === 'left' ? 1 : -1})`}}>→</span>
    </div>
  );
};

export const NoteRail: React.FC<{
  x: number;
  y: number;
  width?: number;
  items: {title: string; sub?: string}[];
  start?: number;
  dark?: boolean;
  style?: React.CSSProperties;
}> = ({x, y, width = 236, items, start = 0, dark, style}) => {
  return (
    <div style={{position: 'absolute', left: x, top: y, width, ...style}}>
      <div
        style={{
          position: 'absolute',
          left: 4,
          top: 6,
          bottom: 6,
          width: 1,
          background: dark ? 'rgba(255,255,255,0.16)' : C.line,
        }}
      />
      {items.map((item, i) => {
        const Elem = () => {
          const frame = useCurrentFrame();
          const p = il(frame, [start + i * 12, start + i * 12 + 20], [0, 1]);
          return (
            <div
              style={{
                position: 'relative',
                paddingLeft: 22,
                marginBottom: 22,
                opacity: p,
                transform: `translateX(${(1 - p) * 10}px)`,
              }}
            >
              <div
                style={{
                  position: 'absolute',
                  left: 0,
                  top: 7,
                  width: 9,
                  height: 9,
                  borderRadius: 999,
                  background: i === 0 ? C.brand : dark ? 'rgba(255,255,255,0.3)' : C.ink3,
                  boxShadow: i === 0 ? '0 0 0 4px rgba(27,107,74,0.10)' : undefined,
                }}
              />
              <div
                style={{
                  fontFamily: FONT.cjk,
                  fontSize: 18,
                  fontWeight: 600,
                  lineHeight: 1.4,
                  color: dark ? 'rgba(255,255,255,0.82)' : C.ink,
                }}
              >
                {item.title}
              </div>
              {item.sub ? (
                <div
                  style={{
                    fontFamily: FONT.cjk,
                    fontSize: 14,
                    lineHeight: 1.5,
                    color: dark ? 'rgba(255,255,255,0.42)' : C.ink3,
                    marginTop: 3,
                  }}
                >
                  {item.sub}
                </div>
              ) : null}
            </div>
          );
        };
        return <Elem key={item.title} />;
      })}
    </div>
  );
};
