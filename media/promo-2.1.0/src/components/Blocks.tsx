import React from 'react';
import {useCurrentFrame} from 'remotion';
import {C, FONT} from '../theme';
import {il} from '../lib/anim';
import {Glyph, GlyphName} from './Glyph';

/** Reusable editorial cards shared by the feature scenes. */

export const Panel: React.FC<{
  children: React.ReactNode;
  width?: number;
  at?: number;
  out?: number;
  tone?: 'paper' | 'green' | 'blue' | 'dark';
  style?: React.CSSProperties;
}> = ({children, width, at = 0, out, tone = 'paper', style}) => {
  const frame = useCurrentFrame();
  const p = il(frame, [at, at + 22], [0, 1]);
  const o = out === undefined ? 1 : il(frame, [out, out + 14], [1, 0]);
  const palette = {
    paper: {
      background: 'rgba(255,255,255,0.52)',
      border: `1px solid ${C.line}`,
      color: C.ink,
    },
    green: {
      background: 'rgba(220,234,225,0.68)',
      border: '1px solid rgba(27,107,74,0.16)',
      color: C.ink,
    },
    blue: {
      background: 'rgba(227,231,246,0.64)',
      border: '1px solid rgba(73,93,146,0.14)',
      color: C.ink,
    },
    dark: {
      background: 'rgba(13,38,28,0.9)',
      border: '1px solid rgba(255,255,255,0.12)',
      color: '#EDF2EA',
    },
  }[tone];
  return (
    <div
      style={{
        width,
        borderRadius: 20,
        padding: '18px 20px',
        boxSizing: 'border-box',
        boxShadow: '0 20px 46px -28px rgba(16,32,26,0.32)',
        opacity: p * o,
        transform: `translateY(${(1 - p) * 18}px)`,
        ...palette,
        ...style,
      }}
    >
      {children}
    </div>
  );
};

export const FeatureRow: React.FC<{
  icon: GlyphName;
  title: string;
  sub?: string;
  n?: string;
  at: number;
  width?: number;
  active?: number;
  tone?: 'green' | 'blue';
  style?: React.CSSProperties;
}> = ({icon, title, sub, n, at, width = 620, active = 0, tone = 'green', style}) => {
  const frame = useCurrentFrame();
  const p = il(frame, [at, at + 22], [0, 1]);
  const a = Math.min(1, Math.max(0, active));
  const accent = tone === 'blue' ? C.blue : C.brand;
  const accentSoft = tone === 'blue' ? C.blueSoft : C.brandSoft;
  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 15,
        width,
        padding: '10px 16px',
        borderRadius: 16,
        background: `rgba(255,255,255,${0.4 + a * 0.34})`,
        border: `1px solid ${a > 0.35 ? `${accent}44` : C.line}`,
        boxShadow: a > 0.35 ? `0 16px 36px -24px ${accent}` : 'none',
        opacity: p,
        transform: `translateX(${(1 - p) * 16}px)`,
        ...style,
      }}
    >
      <div
        style={{
          width: 42,
          height: 42,
          borderRadius: 13,
          background: a > 0.35 ? accentSoft : 'rgba(255,255,255,0.6)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          flex: 'none',
        }}
      >
        <Glyph name={icon} size={21} color={accent} />
      </div>
      <div style={{flex: 1, minWidth: 0}}>
        <div style={{fontFamily: FONT.cjk, fontSize: 20, fontWeight: 650, color: C.ink}}>
          {title}
        </div>
        {sub ? (
          <div style={{fontFamily: FONT.cjk, fontSize: 15, color: C.ink3, marginTop: 2}}>{sub}</div>
        ) : null}
      </div>
      {n ? (
        <div style={{fontFamily: FONT.mono, fontSize: 12, color: C.ink3, letterSpacing: 1}}>{n}</div>
      ) : null}
    </div>
  );
};

export const Step: React.FC<{
  n: string;
  title: string;
  sub?: string;
  at: number;
  active?: number;
  last?: boolean;
  width?: number;
  style?: React.CSSProperties;
}> = ({n, title, sub, at, active = 0, last, width = 600, style}) => {
  const frame = useCurrentFrame();
  const p = il(frame, [at, at + 20], [0, 1]);
  const a = Math.min(1, Math.max(0, active));
  return (
    <div style={{display: 'flex', gap: 15, width, opacity: p, ...style}}>
      <div style={{position: 'relative', width: 30, flex: 'none'}}>
        <div
          style={{
            width: 27,
            height: 27,
            borderRadius: 999,
            background: a > 0.35 ? C.brand : 'rgba(255,255,255,0.64)',
            border: `1px solid ${a > 0.35 ? C.brand : C.lineStrong}`,
            color: a > 0.35 ? '#fff' : C.ink3,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            fontFamily: FONT.mono,
            fontSize: 12,
            fontWeight: 700,
          }}
        >
          {n}
        </div>
        {last ? null : (
          <div
            style={{
              position: 'absolute',
              left: 13,
              top: 31,
              bottom: -8,
              width: 1,
              background: C.line,
            }}
          />
        )}
      </div>
      <div style={{paddingTop: 1}}>
        <div style={{fontFamily: FONT.cjk, fontSize: 19, fontWeight: 650, color: C.ink}}>
          {title}
        </div>
        {sub ? (
          <div style={{fontFamily: FONT.cjk, fontSize: 14, lineHeight: 1.55, color: C.ink3, marginTop: 3}}>
            {sub}
          </div>
        ) : null}
      </div>
    </div>
  );
};

export const StatTile: React.FC<{
  value: string;
  unit?: string;
  label: string;
  at: number;
  width?: number;
  tone?: 'green' | 'blue' | 'amber';
  style?: React.CSSProperties;
}> = ({value, unit, label, at, width = 210, tone = 'green', style}) => {
  const frame = useCurrentFrame();
  const p = il(frame, [at, at + 22], [0, 1]);
  const color = tone === 'blue' ? C.blue : tone === 'amber' ? C.amber : C.brand;
  return (
    <div
      style={{
        width,
        borderRadius: 18,
        padding: '15px 17px',
        background: 'rgba(255,255,255,0.5)',
        border: `1px solid ${C.line}`,
        opacity: p,
        transform: `translateY(${(1 - p) * 16}px)`,
        ...style,
      }}
    >
      <div style={{display: 'flex', alignItems: 'flex-end', gap: 5}}>
        <div style={{fontFamily: FONT.cjk, fontSize: 35, fontWeight: 800, color, lineHeight: 1}}>
          {value}
        </div>
        {unit ? (
          <div style={{fontFamily: FONT.cjk, fontSize: 14, color: C.ink3, paddingBottom: 3}}>{unit}</div>
        ) : null}
      </div>
      <div style={{fontFamily: FONT.cjk, fontSize: 13, color: C.ink3, marginTop: 7}}>{label}</div>
    </div>
  );
};

export const CheckLine: React.FC<{
  text: string;
  at: number;
  checked?: number;
  width?: number;
  note?: string;
  style?: React.CSSProperties;
}> = ({text, at, checked = 0, width = 420, note, style}) => {
  const frame = useCurrentFrame();
  const p = il(frame, [at, at + 20], [0, 1]);
  const c = Math.min(1, Math.max(0, checked));
  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 12,
        width,
        padding: '12px 15px',
        borderRadius: 14,
        background: 'rgba(255,255,255,0.58)',
        border: `1px solid ${C.line}`,
        opacity: p,
        transform: `translateY(${(1 - p) * 14}px)`,
        ...style,
      }}
    >
      <div
        style={{
          width: 26,
          height: 26,
          borderRadius: 8,
          border: `1.6px solid ${c > 0.4 ? C.brand : C.lineStrong}`,
          background: c > 0.4 ? C.brand : 'transparent',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          flex: 'none',
          transform: `scale(${0.9 + Math.min(1, c * 2) * 0.1})`,
        }}
      >
        <Glyph
          name="check"
          size={15}
          color="#fff"
          strokeWidth={3}
          opacity={Math.min(1, c * 2)}
        />
      </div>
      <div style={{flex: 1}}>
        <div
          style={{
            fontFamily: FONT.cjk,
            fontSize: 17,
            fontWeight: 600,
            color: c > 0.4 ? C.ink3 : C.ink,
            textDecoration: c > 0.4 ? 'line-through' : 'none',
          }}
        >
          {text}
        </div>
        {note ? (
          <div style={{fontFamily: FONT.cjk, fontSize: 12, color: C.ink3, marginTop: 2}}>{note}</div>
        ) : null}
      </div>
    </div>
  );
};

/** Small floating pill used wherever a screen shows synthetic demo content. */
export const DemoBadge: React.FC<{
  left: number;
  top: number;
  at?: number;
  label?: string;
}> = ({left, top, at = 0, label = '演示数据'}) => {
  const frame = useCurrentFrame();
  const p = il(frame, [at, at + 16], [0, 1]);
  return (
    <div
      style={{
        position: 'absolute',
        left,
        top,
        display: 'flex',
        alignItems: 'center',
        gap: 6,
        padding: '5px 11px',
        borderRadius: 999,
        background: 'rgba(13,38,28,0.82)',
        border: '1px solid rgba(255,255,255,0.2)',
        boxShadow: '0 10px 22px -12px rgba(8,24,16,0.55)',
        color: '#EDF2EA',
        fontFamily: FONT.cjk,
        fontSize: 13,
        fontWeight: 600,
        letterSpacing: 0.6,
        whiteSpace: 'nowrap',
        opacity: p,
        transform: `translateY(${(1 - p) * 8}px)`,
      }}
    >
      <Glyph name="info" size={12} color="#8FD4B0" />
      {label}
    </div>
  );
};

export const SectionLabel: React.FC<{
  children: React.ReactNode;
  at?: number;
  dark?: boolean;
  style?: React.CSSProperties;
}> = ({children, at = 0, dark, style}) => {
  const frame = useCurrentFrame();
  const p = il(frame, [at, at + 18], [0, 1]);
  return (
    <div
      style={{
        fontFamily: FONT.mono,
        fontSize: 12,
        fontWeight: 600,
        letterSpacing: 2,
        textTransform: 'uppercase',
        color: dark ? 'rgba(255,255,255,0.45)' : C.ink3,
        opacity: p,
        ...style,
      }}
    >
      {children}
    </div>
  );
};
