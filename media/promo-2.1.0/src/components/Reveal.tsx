import React from 'react';
import {useCurrentFrame} from 'remotion';
import {C, FONT} from '../theme';
import {easeOut, il} from '../lib/anim';

export const Rise: React.FC<{
  start: number;
  dur?: number;
  y?: number;
  children: React.ReactNode;
  style?: React.CSSProperties;
}> = ({start, dur = 22, y = 26, children, style}) => {
  const frame = useCurrentFrame();
  const p = il(frame, [start, start + dur], [0, 1], easeOut);
  return (
    <div
      style={{
        opacity: p,
        transform: `translateY(${(1 - p) * y}px)`,
        ...style,
      }}
    >
      {children}
    </div>
  );
};

export const Fade: React.FC<{
  start: number;
  dur?: number;
  out?: number;
  outDur?: number;
  children: React.ReactNode;
  style?: React.CSSProperties;
}> = ({start, dur = 16, out, outDur = 14, children, style}) => {
  const frame = useCurrentFrame();
  const a = il(frame, [start, start + dur], [0, 1], easeOut);
  const b = out === undefined ? 1 : il(frame, [out, out + outDur], [1, 0], easeOut);
  return <div style={{opacity: Math.min(a, b), ...style}}>{children}</div>;
};

export const Eyebrow: React.FC<{
  index: string;
  text: string;
  start?: number;
  dark?: boolean;
  style?: React.CSSProperties;
}> = ({index, text, start = 0, dark, style}) => {
  const frame = useCurrentFrame();
  const p = il(frame, [start, start + 18], [0, 1], easeOut);
  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 16,
        opacity: p,
        transform: `translateY(${(1 - p) * 14}px)`,
        ...style,
      }}
    >
      <div
        style={{
          fontFamily: FONT.mono,
          fontSize: 17,
          fontWeight: 600,
          letterSpacing: 2,
          color: dark ? 'rgba(255,255,255,0.6)' : C.brand,
        }}
      >
        {index}
      </div>
      <div
        style={{
          width: 44,
          height: 1,
          background: dark ? 'rgba(255,255,255,0.28)' : C.lineStrong,
        }}
      />
      <div
        style={{
          fontFamily: FONT.cjk,
          fontSize: 20,
          fontWeight: 600,
          letterSpacing: 5,
          color: dark ? 'rgba(255,255,255,0.72)' : C.ink2,
        }}
      >
        {text}
      </div>
    </div>
  );
};

export const SceneTitle: React.FC<{
  lines: string[];
  start?: number;
  size?: number;
  color?: string;
  style?: React.CSSProperties;
  weight?: number;
}> = ({lines, start = 0, size = 86, color = C.ink, style, weight = 800}) => {
  const frame = useCurrentFrame();
  return (
    <div style={{fontFamily: FONT.cjk, ...style}}>
      {lines.map((line, i) => {
        const at = start + i * 7;
        const p = il(frame, [at, at + 30], [0, 1], easeOut);
        return (
          <div key={line} style={{overflow: 'hidden', paddingBottom: size * 0.06}}>
            <div
              style={{
                fontSize: size,
                lineHeight: 1.07,
                fontWeight: weight,
                letterSpacing: size * -0.035,
                color,
                transform: `translateY(${(1 - p) * size * 0.9}px)`,
                opacity: p === 0 ? 0 : 1,
              }}
            >
              {line}
            </div>
          </div>
        );
      })}
    </div>
  );
};

export const Lead: React.FC<{
  children: React.ReactNode;
  start?: number;
  dark?: boolean;
  style?: React.CSSProperties;
}> = ({children, start = 0, dark, style}) => {
  const frame = useCurrentFrame();
  const p = il(frame, [start, start + 24], [0, 1], easeOut);
  return (
    <div
      style={{
        fontFamily: FONT.cjk,
        fontSize: 27,
        lineHeight: 1.65,
        fontWeight: 400,
        color: dark ? 'rgba(255,255,255,0.68)' : C.ink2,
        opacity: p,
        transform: `translateY(${(1 - p) * 18}px)`,
        ...style,
      }}
    >
      {children}
    </div>
  );
};

export const Tag: React.FC<{
  children: React.ReactNode;
  start?: number;
  tone?: 'green' | 'blue' | 'plain' | 'warn';
  dark?: boolean;
  icon?: React.ReactNode;
  style?: React.CSSProperties;
}> = ({children, start = 0, tone = 'plain', dark, icon, style}) => {
  const frame = useCurrentFrame();
  const p = il(frame, [start, start + 20], [0, 1], easeOut);
  const palette = dark
    ? {
        green: {bg: 'rgba(122,200,164,0.16)', fg: '#A9E3C6', bd: 'rgba(169,227,198,0.24)'},
        blue: {bg: 'rgba(143,164,220,0.16)', fg: '#C4D1F4', bd: 'rgba(196,209,244,0.22)'},
        plain: {bg: 'rgba(255,255,255,0.08)', fg: 'rgba(255,255,255,0.74)', bd: 'rgba(255,255,255,0.16)'},
        warn: {bg: 'rgba(232,190,120,0.16)', fg: '#F0D5A7', bd: 'rgba(240,213,167,0.24)'},
      }[tone]
    : {
        green: {bg: C.brandSoft, fg: C.brand, bd: 'rgba(27,107,74,0.16)'},
        blue: {bg: C.blueSoft, fg: C.blue, bd: 'rgba(73,93,146,0.16)'},
        plain: {bg: 'rgba(255,255,255,0.58)', fg: C.ink2, bd: C.line},
        warn: {bg: C.amberSoft, fg: C.amber, bd: 'rgba(169,118,47,0.18)'},
      };
  return (
    <div
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 9,
        padding: '10px 17px',
        borderRadius: 999,
        background: palette.bg,
        border: `1px solid ${palette.bd}`,
        color: palette.fg,
        fontFamily: FONT.cjk,
        fontSize: 20,
        fontWeight: 600,
        lineHeight: 1,
        opacity: p,
        transform: `translateY(${(1 - p) * 12}px)`,
        ...style,
      }}
    >
      {icon}
      {children}
    </div>
  );
};

export const Rule: React.FC<{start?: number; width?: number; dark?: boolean}> = ({
  start = 0,
  width = 96,
  dark,
}) => {
  const frame = useCurrentFrame();
  const p = il(frame, [start, start + 28], [0, 1], easeOut);
  return (
    <div
      style={{
        width: width * p,
        height: 2,
        borderRadius: 2,
        background: dark ? 'rgba(255,255,255,0.32)' : C.brand,
        opacity: 0.8,
      }}
    />
  );
};
