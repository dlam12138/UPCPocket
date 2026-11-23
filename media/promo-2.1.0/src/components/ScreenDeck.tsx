import React from 'react';
import {staticFile, useCurrentFrame} from 'remotion';
import {PhoneFrame} from './Phone';
import {MockScreen} from '../ui/MockScreen';
import {mockIdFor} from './Capture';
import {CAPTURES} from '../screens';
import {SCREEN} from '../theme';
import {easeOut, il} from '../lib/anim';

export type DeckEntry = {id: string; at: number};

/**
 * Cycles app screens inside one phone. If a real capture exists for an id it
 * wins; otherwise a clearly labelled code-drawn mock is used.
 */
export const ScreenDeck: React.FC<{
  screens: DeckEntry[];
  phoneWidth: number;
  glow?: string;
  tilt?: number;
  screenStyle?: React.CSSProperties;
}> = ({screens, phoneWidth, glow, tilt, screenStyle}) => {
  const frame = useCurrentFrame();
  const sorted = [...screens].sort((a, b) => a.at - b.at);
  return (
    <PhoneFrame width={phoneWidth} glow={glow} tilt={tilt}>
      <div
        style={{
          width: SCREEN.w,
          height: SCREEN.h,
          position: 'relative',
          overflow: 'hidden',
          background: '#FAF8FF',
          ...screenStyle,
        }}
      >
        {sorted.map((entry, i) => {
          const next = sorted[i + 1];
          const inP = il(frame, [entry.at, entry.at + 12], [0, 1], easeOut);
          const outP = next ? il(frame, [next.at, next.at + 12], [1, 0], easeOut) : 1;
          const opacity = Math.min(inP, outP);
          if (opacity <= 0.001) {
            return null;
          }
          const y = (1 - inP) * 24 - (1 - outP) * 24;
          const capture = CAPTURES[entry.id];
          return (
            <div
              key={`${entry.id}-${i}`}
              style={{
                position: 'absolute',
                inset: 0,
                opacity,
                transform: `translateY(${y}px)`,
              }}
            >
              {capture ? (
                <img
                  src={staticFile(capture.file)}
                  style={{width: SCREEN.w, height: SCREEN.h, objectFit: 'cover', display: 'block'}}
                  alt=""
                />
              ) : (
                <MockScreen id={mockIdFor(entry.id)} />
              )}
            </div>
          );
        })}
      </div>
    </PhoneFrame>
  );
};
