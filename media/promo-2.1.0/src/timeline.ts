import generated from './timeline.generated.json';

export type Segment = {
  id: string;
  from: number;
  localFrom: number;
  duration: number;
  seconds: number;
  text: string;
  lines: string[];
};

export type Scene = {
  id: string;
  label: string;
  from: number;
  duration: number;
  segments: Segment[];
};

export const FPS = generated.fps;
export const TOTAL_FRAMES = generated.totalFrames;
export const TOTAL_SECONDS = generated.totalSeconds;
export const SCENES = generated.scenes as Scene[];

export const SCENE_BY_ID: Record<string, Scene> = Object.fromEntries(
  SCENES.map((s) => [s.id, s]),
);

export const ALL_SEGMENTS: Segment[] = SCENES.flatMap((s) => s.segments);

export const scene = (id: string): Scene => {
  const found = SCENE_BY_ID[id];
  if (!found) {
    throw new Error(`unknown scene ${id}`);
  }
  return found;
};

export const seg = (sceneId: string, index = 0): Segment => {
  const s = scene(sceneId);
  const found = s.segments[index];
  if (!found) {
    throw new Error(`scene ${sceneId} has no segment ${index}`);
  }
  return found;
};
