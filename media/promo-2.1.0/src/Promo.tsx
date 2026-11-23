import React from 'react';
import {AbsoluteFill, Sequence} from 'remotion';
import {Backdrop} from './components/Backdrop';
import {AudioTrack} from './components/AudioTrack';
import {Captions} from './components/Captions';
import {Chrome} from './components/Chrome';
import {SCENES} from './timeline';
import {usePromoFont} from './lib/usePromoFont';
import {C} from './theme';
import {Scene01} from './scenes/Scene01';
import {Scene02} from './scenes/Scene02';
import {Scene03} from './scenes/Scene03';
import {Scene04} from './scenes/Scene04';
import {Scene05} from './scenes/Scene05';
import {Scene06} from './scenes/Scene06';
import {Scene07} from './scenes/Scene07';
import {Scene08} from './scenes/Scene08';
import {Scene09} from './scenes/Scene09';
import {Scene10} from './scenes/Scene10';
import {Scene11} from './scenes/Scene11';
import {Scene12} from './scenes/Scene12';
import {Scene13} from './scenes/Scene13';

const COMPONENTS = [
  Scene01, Scene02, Scene03, Scene04, Scene05, Scene06, Scene07,
  Scene08, Scene09, Scene10, Scene11, Scene12, Scene13,
];

/** The 3:06 film: one measured timeline drives picture, VO and subtitles. */
export const Promo: React.FC = () => {
  usePromoFont();
  return (
    <AbsoluteFill style={{backgroundColor: C.paper}}>
      <Backdrop />
      {SCENES.map((scene, i) => {
        const Scene = COMPONENTS[i];
        return (
          <Sequence
            key={scene.id}
            from={scene.from}
            durationInFrames={scene.duration}
            name={`${scene.id} ${scene.label}`}
          >
            <Scene duration={scene.duration} />
          </Sequence>
        );
      })}
      <Chrome />
      <Captions />
      <AudioTrack />
    </AbsoluteFill>
  );
};
