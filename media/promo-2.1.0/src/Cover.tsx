import React from 'react';
import {AbsoluteFill} from 'remotion';
import {Backdrop} from './components/Backdrop';
import {BrandIcon} from './components/BrandIcon';
import {VersionChip, Wordmark} from './components/Brand';
import {PhoneFrame} from './components/Phone';
import {Shot} from './components/Capture';
import {C, FONT, SCREEN} from './theme';
import {usePromoFont} from './lib/usePromoFont';

const COVERED = [
  '课表', '校园卡', '电量', '空闲教室', '图书馆', '石光', 'e站通',
  '校园地图', '通讯录', '学习平台', '待办', '专注', '桌面组件',
];

export const Cover: React.FC = () => {
  usePromoFont();
  return (
    <AbsoluteFill style={{backgroundColor: C.paper}}>
      <Backdrop />
      <div style={{position: 'absolute', left: 76, top: 58}}>
        <Wordmark size={24} sub />
      </div>
      <div style={{position: 'absolute', right: 80, top: 66}}>
        <VersionChip />
      </div>

      <div style={{position: 'absolute', left: 116, top: 252}}>
        <div
          style={{
            fontFamily: FONT.mono,
            fontSize: 14,
            letterSpacing: 4,
            color: C.ink3,
          }}
        >
          UPC POCKET · 2026 AUTUMN
        </div>
        <div
          style={{
            fontFamily: FONT.cjk,
            fontSize: 118,
            fontWeight: 800,
            letterSpacing: -2,
            lineHeight: 1.08,
            color: C.ink,
            marginTop: 22,
          }}
        >
          从早八
          <br />
          到晚自习<span style={{color: C.brand}}>。</span>
        </div>
        <div
          style={{
            fontFamily: FONT.cjk,
            fontSize: 30,
            lineHeight: 1.6,
            color: C.ink2,
            marginTop: 28,
          }}
        >
          一条待办，串起石大的一天。
        </div>
        <div
          style={{
            display: 'flex',
            gap: 10,
            flexWrap: 'wrap',
            maxWidth: 850,
            marginTop: 28,
          }}
        >
          {COVERED.map((t) => (
            <span
              key={t}
              style={{
                padding: '7px 13px',
                borderRadius: 999,
                border: `1px solid ${C.line}`,
                background: 'rgba(255,255,255,0.5)',
                fontFamily: FONT.cjk,
                fontSize: 15,
                color: C.ink2,
              }}
            >
              {t}
            </span>
          ))}
        </div>
      </div>

      <div style={{position: 'absolute', left: 120, bottom: 62, display: 'flex', alignItems: 'center', gap: 12}}>
        <BrandIcon size={32} radius={8} />
        <div style={{fontFamily: FONT.cjk, fontSize: 15, color: C.ink3}}>
          中国石油大学（华东）非官方 Android 校园工具 · Android 12+
        </div>
      </div>

      <div
        style={{
          position: 'absolute',
          left: 1300,
          top: 104,
          transform: 'rotate(-2.4deg)',
          transformOrigin: 'top center',
        }}
      >
        <PhoneFrame width={396} glow="rgba(27,107,74,0.20)">
          <Shot id="home-demo" width={SCREEN.w} />
        </PhoneFrame>
      </div>
      <div
        style={{
          position: 'absolute',
          right: 84,
          bottom: 116,
          padding: '9px 14px',
          borderRadius: 12,
          background: 'rgba(255,255,255,0.78)',
          border: `1px solid ${C.line}`,
          fontFamily: FONT.cjk,
          fontSize: 13,
          color: C.ink3,
        }}
      >
        封面演示画面 · 课程与待办为合成数据
      </div>
    </AbsoluteFill>
  );
};
