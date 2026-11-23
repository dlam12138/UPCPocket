import React from 'react';
import {useCurrentFrame} from 'remotion';
import {SCENES, TOTAL_FRAMES} from '../timeline';
import {C, FONT} from '../theme';
import {Wordmark} from './Brand';
import {il} from '../lib/anim';

export const Chrome: React.FC = () => {
  const frame = useCurrentFrame();
  const sceneIndex = Math.max(
    0,
    SCENES.findIndex((s) => frame >= s.from && frame < s.from + s.duration),
  );
  const scene = SCENES[sceneIndex] ?? SCENES[SCENES.length - 1];
  const progress = Math.min(1, frame / (TOTAL_FRAMES - 1));
  const endCard = SCENES[SCENES.length - 1].from + 328;
  const fade = Math.min(
    il(frame, [0, 24], [0, 1]),
    il(frame, [endCard, endCard + 24], [1, 0]),
    il(frame, [TOTAL_FRAMES - 34, TOTAL_FRAMES - 6], [1, 0]),
  );
  return (
    <div style={{position: 'absolute', inset: 0, pointerEvents: 'none', opacity: fade}}>
      <div style={{position: 'absolute', left: 64, top: 42}}>
        <Wordmark size={21} />
      </div>
      <div
        style={{
          position: 'absolute',
          left: 286,
          top: 44,
          display: 'flex',
          alignItems: 'center',
          gap: 18,
        }}
      >
        <div
          style={{
            fontFamily: FONT.mono,
            fontSize: 14,
            letterSpacing: 1.5,
            color: C.ink3,
          }}
        >
          {String(sceneIndex + 1).padStart(2, '0')} / {String(SCENES.length).padStart(2, '0')}
        </div>
        <div style={{width: 28, height: 1, background: C.lineStrong}} />
        <div
          style={{
            fontFamily: FONT.cjk,
            fontSize: 16,
            fontWeight: 600,
            letterSpacing: 2.4,
            color: C.ink2,
          }}
        >
          {scene.label}
        </div>
      </div>

      <div
        style={{
          position: 'absolute',
          left: 64,
          right: 64,
          bottom: 27,
          height: 2,
          background: 'rgba(16,32,26,0.10)',
          borderRadius: 2,
          overflow: 'hidden',
        }}
      >
        <div
          style={{
            width: `${progress * 100}%`,
            height: '100%',
            background: `linear-gradient(90deg, ${C.brand} 0%, ${C.brandMid} 70%, ${C.blue} 100%)`,
            borderRadius: 2,
          }}
        />
      </div>
      <div
        style={{
          position: 'absolute',
          right: 64,
          bottom: 40,
          fontFamily: FONT.cjk,
          fontSize: 13,
          letterSpacing: 0.8,
          color: C.ink3,
        }}
      >
        非官方 Android 校园工具 · 演示画面
      </div>
    </div>
  );
};
