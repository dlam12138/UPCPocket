import manifest from './screens.generated.json';

export type Capture = {file: string; width: number; height: number};

export const CAPTURES = manifest.screens as Record<string, Capture>;

export const hasCapture = (id: string): boolean => Boolean(CAPTURES[id]);

export const captureFile = (id: string): string | null =>
  CAPTURES[id]?.file ?? null;

export const captureReport = {
  generatedAt: manifest.generatedAt,
  available: Object.keys(CAPTURES),
  missing: manifest.missing,
  unmapped: manifest.unmapped,
};
