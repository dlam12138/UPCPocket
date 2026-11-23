import React from 'react';
import {AbsoluteFill, useCurrentFrame} from 'remotion';
import {il} from '../lib/anim';

/** Gentle entry/exit so scene cuts read as dissolves rather than hard switches. */
export const SceneShell: React.FC<{
  duration: number;
  children: React.ReactNode;
  style?: React.CSSProperties;
}> = ({duration, children, style}) => {
  const frame = useCurrentFrame();
  const enter = il(frame, [0, 13], [0, 1]);
  const exit = il(frame, [duration - 11, duration - 1], [1, 0]);
  const opacity = Math.min(enter, exit);
  return (
    <AbsoluteFill
      style={{
        opacity,
        transform: `translateY(${(1 - enter) * 10}px)`,
        ...style,
      }}
    >
      {children}
    </AbsoluteFill>
  );
};
