import React from 'react';
import {Audio, Sequence, interpolate, staticFile} from 'remotion';
import {ALL_SEGMENTS, FPS, SCENES, TOTAL_FRAMES, seg} from '../timeline';

const BGM_VOLUME = 0.17;
const BGM_FADE_SECONDS = 3.5;
const BGM_FADE_FRAMES = Math.round(BGM_FADE_SECONDS * FPS);

/** Keep the bed at its usual level, then fade it to zero over the final 3.5s. */
const bgmVolume = (frame: number) =>
  BGM_VOLUME *
  interpolate(
    frame,
    [TOTAL_FRAMES - BGM_FADE_FRAMES, TOTAL_FRAMES],
    [1, 0],
    {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'},
  );

const Sfx: React.FC<{from: number; file: string; volume: number}> = ({
  from,
  file,
  volume,
}) => (
  <Sequence from={Math.max(0, Math.round(from))}>
    <Audio src={staticFile(`audio/sfx/${file}.mp3`)} volume={volume} />
  </Sequence>
);

/**
 * One soundtrack for the film: narration at measured absolute positions, a
 * quiet original bed, and a handful of UI sounds. No third-party samples.
 */
export const AudioTrack: React.FC = () => {
  const transitions = SCENES.slice(1).map((s) => s.from - 4);
  const pin = seg('s09', 1).from + 48;
  const done = seg('s11', 3).from + 54;
  const widget = seg('s12', 1).from + 46;
  const end = seg('s13', 3).from + 12;
  return (
    <>
      <Audio src={staticFile('audio/bgm.mp3')} volume={bgmVolume} />
      {ALL_SEGMENTS.map((s) => (
        <Sequence key={s.id} from={s.from}>
          <Audio src={staticFile(`audio/vo/${s.id}.mp3`)} volume={1} />
        </Sequence>
      ))}
      <Sfx from={0} file="impact-low" volume={0.34} />
      {transitions.map((from, i) => (
        <Sfx key={`w${i}`} from={from} file="whoosh-soft" volume={0.16} />
      ))}
      <Sfx from={pin} file="pop" volume={0.26} />
      <Sfx from={done} file="chime" volume={0.22} />
      <Sfx from={widget} file="pop" volume={0.2} />
      <Sfx from={end} file="chime" volume={0.24} />
    </>
  );
};
