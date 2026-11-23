import React from 'react';
import {staticFile, useCurrentFrame} from 'remotion';
import {C, H, W} from '../theme';
import {drift} from '../lib/anim';

/**
 * Warm paper backdrop shared by every scene. A faint campus-plan grid and two
 * slow colour fields keep cuts calm without introducing stock imagery.
 */
export const Backdrop: React.FC<{tone?: 'paper' | 'mist' | 'deep'}> = ({tone = 'paper'}) => {
  const frame = useCurrentFrame();
  const dx = drift(frame, 16, 0.0045);
  const dy = drift(frame, 12, 0.0035, 1.6);
  const deep = tone === 'deep';
  return (
    <div
      style={{
        position: 'absolute',
        inset: 0,
        overflow: 'hidden',
        background: deep
          ? 'radial-gradient(1200px 760px at 78% 18%, #173D2E 0%, #0C2118 58%, #081711 100%)'
          : `linear-gradient(158deg, ${C.paper} 0%, #F6F5EE 48%, ${C.paper2} 100%)`,
      }}
    >
      <div
        style={{
          position: 'absolute',
          width: 1280,
          height: 1280,
          left: -380 + dx,
          top: -560 + dy,
          borderRadius: '50%',
          background: deep
            ? 'radial-gradient(circle, rgba(84,164,128,0.16) 0%, rgba(84,164,128,0) 68%)'
            : 'radial-gradient(circle, rgba(27,107,74,0.13) 0%, rgba(27,107,74,0) 68%)',
        }}
      />
      <div
        style={{
          position: 'absolute',
          width: 980,
          height: 980,
          right: -260 - dx * 0.7,
          bottom: -420 - dy,
          borderRadius: '50%',
          background: deep
            ? 'radial-gradient(circle, rgba(110,140,220,0.12) 0%, rgba(110,140,220,0) 70%)'
            : 'radial-gradient(circle, rgba(73,93,146,0.12) 0%, rgba(73,93,146,0) 70%)',
        }}
      />
      <div
        style={{
          position: 'absolute',
          inset: 0,
          opacity: deep ? 0.1 : 0.26,
          backgroundImage: `linear-gradient(${deep ? 'rgba(255,255,255,0.06)' : 'rgba(16,32,26,0.055)'} 1px, transparent 1px), linear-gradient(90deg, ${
            deep ? 'rgba(255,255,255,0.06)' : 'rgba(16,32,26,0.055)'
          } 1px, transparent 1px)`,
          backgroundSize: '96px 96px',
          maskImage: 'radial-gradient(85% 85% at 48% 44%, black 30%, transparent 100%)',
          WebkitMaskImage: 'radial-gradient(85% 85% at 48% 44%, black 30%, transparent 100%)',
        }}
      />
      <img
        src={staticFile('textures/grain.png')}
        alt=""
        style={{
          position: 'absolute',
          inset: 0,
          width: W,
          height: H,
          objectFit: 'cover',
          opacity: deep ? 0.12 : 0.13,
          mixBlendMode: 'multiply',
        }}
      />
      <img
        src={staticFile('textures/vignette.png')}
        alt=""
        style={{
          position: 'absolute',
          inset: 0,
          width: W,
          height: H,
          objectFit: 'cover',
          opacity: deep ? 0.62 : 0.38,
        }}
      />
    </div>
  );
};
