import React from 'react';
import {BrandIcon} from './BrandIcon';
import {C, FONT} from '../theme';

export const Wordmark: React.FC<{size?: number; sub?: boolean; dark?: boolean}> = ({
  size = 30,
  sub = false,
  dark = false,
}) => (
  <div style={{display: 'flex', alignItems: 'center', gap: size * 0.42}}>
    <BrandIcon size={size * 1.5} radius={size * 0.36} />
    <div>
      <div
        style={{
          fontFamily: FONT.cjk,
          fontSize: size,
          fontWeight: 800,
          letterSpacing: -0.2,
          color: dark ? '#F4F7F1' : C.ink,
          lineHeight: 1.1,
        }}
      >
        UPC Pocket
      </div>
      {sub ? (
        <div
          style={{
            fontFamily: FONT.cjk,
            fontSize: size * 0.43,
            fontWeight: 500,
            letterSpacing: 1.6,
            color: dark ? 'rgba(244,247,241,0.56)' : C.ink3,
            marginTop: size * 0.2,
          }}
        >
          中国石油大学（华东）· 非官方校园工具
        </div>
      ) : null}
    </div>
  </div>
);

export const VersionChip: React.FC<{dark?: boolean; style?: React.CSSProperties}> = ({
  dark,
  style,
}) => (
  <div
    style={{
      display: 'inline-flex',
      alignItems: 'center',
      gap: 9,
      padding: '8px 16px',
      borderRadius: 999,
      border: `1px solid ${dark ? 'rgba(255,255,255,0.22)' : C.lineStrong}`,
      background: dark ? 'rgba(255,255,255,0.08)' : 'rgba(255,255,255,0.52)',
      fontFamily: FONT.cjk,
      fontSize: 17,
      fontWeight: 600,
      letterSpacing: 0.6,
      color: dark ? 'rgba(255,255,255,0.86)' : C.ink2,
      ...style,
    }}
  >
    <span
      style={{
        width: 7,
        height: 7,
        borderRadius: 999,
        background: dark ? '#8FD4B0' : C.brand,
        display: 'inline-block',
      }}
    />
    2.1.0 功能预览
  </div>
);
