import {existsSync} from 'node:fs';
import {Config} from '@remotion/cli/config';

Config.setVideoImageFormat('jpeg');
Config.setOverwriteOutput(true);
Config.setConcurrency(4);
Config.setCodec('h264');
Config.setCrf(17);
Config.setPixelFormat('yuv420p');
Config.setAudioCodec('aac');
Config.setAudioBitrate('192k');

/**
 * This workstation has no Remotion-managed headless shell. Edge is a Chromium
 * build and renders the film correctly; override with REMOTION_BROWSER on
 * another machine, or leave unset elsewhere to let Remotion download its own.
 */
const edge = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const browser = process.env.REMOTION_BROWSER ?? (existsSync(edge) ? edge : null);
if (browser) {
  Config.setBrowserExecutable(browser);
}
