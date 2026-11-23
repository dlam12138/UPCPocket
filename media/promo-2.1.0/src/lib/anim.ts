import {Easing, interpolate, spring} from 'remotion';

export const easeOut = Easing.bezier(0.16, 1, 0.3, 1);
export const easeInOut = Easing.bezier(0.65, 0, 0.35, 1);
export const easeIn = Easing.bezier(0.5, 0, 0.75, 0);

export const clamp01 = (v: number) => Math.min(1, Math.max(0, v));

export function il(
  frame: number,
  input: [number, number],
  output: [number, number],
  easing: (t: number) => number = easeOut,
): number {
  return interpolate(frame, input, output, {
    easing,
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
}

export function multi(
  frame: number,
  input: number[],
  output: number[],
  easing: (t: number) => number = easeOut,
): number {
  return interpolate(frame, input, output, {
    easing,
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
}

export const fade = (frame: number, start: number, dur = 14) =>
  il(frame, [start, start + dur], [0, 1]);

export const fadeOut = (frame: number, start: number, dur = 14) =>
  il(frame, [start, start + dur], [1, 0]);

export function pop(frame: number, fps: number, start: number, damping = 200, mass = 0.7) {
  return spring({frame: frame - start, fps, config: {damping, mass, stiffness: 120}});
}

export function riseStyle(
  frame: number,
  start: number,
  dur = 20,
  distance = 24,
  easing = easeOut,
) {
  const p = il(frame, [start, start + dur], [0, 1], easing);
  return {
    opacity: p,
    transform: `translateY(${(1 - p) * distance}px)`,
  } as const;
}

/** Smooth 0..1..0 pulse. */
export const pulse = (frame: number, start: number, dur: number) =>
  Math.sin(clamp01((frame - start) / dur) * Math.PI);

/** Slow drift in px for backgrounds. */
export const drift = (frame: number, amount = 6, speed = 0.006, phase = 0) =>
  Math.sin(frame * speed + phase) * amount;
