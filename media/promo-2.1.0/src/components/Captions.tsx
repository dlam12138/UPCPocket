import React from 'react';
import {useCurrentFrame} from 'remotion';
import {ALL_SEGMENTS} from '../timeline';
import {C, FONT} from '../theme';
import {il} from '../lib/anim';

/**
 * Burned-in subtitles. Cues come from the same measured segment list as the
 * voice-over and the SRT export, so text can never drift from the audio.
 */
export const Captions: React.FC<{left?: number; bottom?: number; width?: number}> = ({
  left = 120,
  bottom = 82,
  width = 860,
}) => {
  const frame = useCurrentFrame();
  const active = ALL_SEGMENTS.find(
    (s) => frame >= s.from - 3 && frame <= s.from + s.duration + 3,
  );
  if (!active) return null;
  const a = il(frame, [active.from - 3, active.from + 5], [0, 1]);
  const b = il(
    frame,
    [active.from + active.duration - 5, active.from + active.duration + 3],
    [1, 0],
  );
  const opacity = Math.min(a, b);
  const rise = (1 - a) * 10;
  return (
    <div
      style={{
        position: 'absolute',
        left,
        bottom,
        width,
        opacity,
        transform: `translateY(${rise}px)`,
        display: 'flex',
        alignItems: 'stretch',
        gap: 16,
        pointerEvents: 'none',
      }}
    >
      <div
        style={{
          width: 3,
          borderRadius: 3,
          background: C.brand,
          opacity: 0.72,
          flex: 'none',
        }}
      />
      <div
        style={{
          background: 'rgba(249, 250, 244, 0.78)',
          border: '1px solid rgba(255,255,255,0.72)',
          borderRadius: 14,
          padding: '13px 18px 14px',
          boxShadow: '0 14px 36px rgba(16,32,26,0.08)',
          backdropFilter: 'blur(10px)',
          fontFamily: FONT.cjk,
          fontSize: 31,
          lineHeight: 1.42,
          fontWeight: 500,
          letterSpacing: 0.2,
          color: C.ink,
          textShadow: '0 1px 0 rgba(255,255,255,0.4)',
        }}
      >
        {active.lines.map((line) => (
          <div key={line}>{line}</div>
        ))}
      </div>
    </div>
  );
};
