// Full screen for the readers: hides the browser bar and, on phones, the system's status bar, so only
// the page and the small clock remain.
//  - Android and computers: the browser's full screen.
//  - iPad: Safari's own (prefixed in older versions).
//  - iPhone: Safari does not allow it at all for web pages; the closest is opening Yomukai from the
//    home screen (no Safari bars), so the button explains how to add it there.

import { isIOS, isStandalone } from './platform.js';

export const screen = $state({ full: false });
/** The "add to home screen" guide for iPhone and iPad. */
export const installGuide = $state({ open: false });

const root = document.documentElement;
const request = root.requestFullscreen || root.webkitRequestFullscreen;
const current = () => document.fullscreenElement || document.webkitFullscreenElement || null;
const nativeFullscreen = !!request && (document.fullscreenEnabled ?? document.webkitFullscreenEnabled ?? true) !== false;

/** Whether the readers show the full-screen button: real full screen, or the guide on iOS in Safari. */
export const canFullscreen = nativeFullscreen || (isIOS && !isStandalone);

const sync = () => { screen.full = !!current(); };
document.addEventListener('fullscreenchange', sync);
document.addEventListener('webkitfullscreenchange', sync);

function ask() {
  try {
    const result = request.call(root);
    return result && typeof result.then === 'function' ? result : Promise.resolve();
  } catch (e) {
    return Promise.reject(e);
  }
}

/** From a button: full screen, or on iOS (where it is not possible) how to get the closest thing. */
export function enterFullscreen() {
  if (current()) return;
  if (!nativeFullscreen) { if (isIOS && !isStandalone) installGuide.open = true; return; }
  ask().catch(() => { if (isIOS && !isStandalone) installGuide.open = true; });
}

/** On the first touch in a reader, when the user chose full screen: never shows the guide by itself. */
export function enterFullscreenQuietly() {
  if (!nativeFullscreen || current()) return;
  ask().catch(() => {});
}

export function exitFullscreen() {
  if (!current()) return;
  (document.exitFullscreen || document.webkitExitFullscreen)?.call(document)?.catch?.(() => {});
}

export function toggleFullscreen() {
  if (current()) exitFullscreen();
  else enterFullscreen();
}
