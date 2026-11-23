import React from 'react';
import {Composition} from 'remotion';
import {Promo} from './Promo';
import {Cover} from './Cover';
import {FPS, TOTAL_FRAMES} from './timeline';
import {H, W} from './theme';

export const RemotionRoot: React.FC = () => (
  <>
    <Composition
      id="Promo"
      component={Promo}
      durationInFrames={TOTAL_FRAMES}
      fps={FPS}
      width={W}
      height={H}
    />
    <Composition
      id="Cover"
      component={Cover}
      durationInFrames={1}
      fps={FPS}
      width={W}
      height={H}
    />
  </>
);
