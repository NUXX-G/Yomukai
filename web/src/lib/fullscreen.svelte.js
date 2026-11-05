// Full screen for the readers: hides the browser bar and, on phones, the system's status bar, so only
// the page and the small clock remain.
//  - Android and computers: the browser's full screen.
//  - iPad: Safari's own (prefixed in older versions), when it allows it.
//  - iPhone (and any iPad where Safari refuses): Safari does not let web pages go full screen at all,
//    only videos. There Yomukai uses its own immersive mode: every bar of the app goes away, the page
//    takes the whole window on black, and a tap brings the controls back for a moment, like in full
//    screen. Opened from the home screen icon there are no Safari bars either, which is as close to
//    real full screen as Apple allows (the clock stays; in landscape it goes too).

import { isIOS, isStandalone } from './platform.js';

/** full: in full screen, real or immersive. immersive: the iOS kind (no browser full screen). */
export const screen = $state({ full: false, immersive: false });
/** The "add to home screen" guide for iPhone and iPad. */
export const installGuide = $state({ open: false });

const root = document.documentElement;
const request = root.requestFullscreen || root.webkitRequestFullscreen;
const current = () => document.fullscreenElement || document.webkitFullscreenElement || null;
const nativeFullscreen = !!request && (document.fullscreenEnabled ?? document.webkitFullscreenEnabled ?? true) !== false;

/** Whether the readers show the full-screen button: always on iOS (immersive mode), elsewhere if the browser can. */
export const canFullscreen = nativeFullscreen || isIOS;

const HINT_KEY = 'yk.immersiveHint';

const sync = () => { screen.full = !!current() || screen.immersive; };
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

function setImmersive(on) {
  screen.immersive = on;
  root.classList.toggle('immersive', on);
  sync();
  // In Safari (not from the home screen) the bars of Safari itself stay: say once how to lose them too.
  if (on && isIOS && !isStandalone) {
    let shown = false;
    try { shown = localStorage.getItem(HINT_KEY) === '1'; localStorage.setItem(HINT_KEY, '1'); } catch {}
    if (!shown) installGuide.open = true;
  }
}

/** From a button: full screen; on iOS, where browsers cannot, the immersive mode. */
export function enterFullscreen() {
  if (current() || screen.immersive) return;
  if (!nativeFullscreen) { setImmersive(true); return; }
  ask().catch(() => { if (isIOS) setImmersive(true); });
}

/** On the first touch in a reader, when the user chose full screen: never shows any guide by itself. */
export function enterFullscreenQuietly() {
  if (current() || screen.immersive) return;
  if (!nativeFullscreen) {
    if (isIOS) { screen.immersive = true; root.classList.add('immersive'); sync(); }
    return;
  }
  ask().catch(() => { if (isIOS) { screen.immersive = true; root.classList.add('immersive'); sync(); } });
}

export function exitFullscreen() {
  if (screen.immersive) setImmersive(false);
  if (!current()) return;
  (document.exitFullscreen || document.webkitExitFullscreen)?.call(document)?.catch?.(() => {});
}

export function toggleFullscreen() {
  if (current() || screen.immersive) exitFullscreen();
  else enterFullscreen();
}

/** Leaving a reader leaves the immersive mode too: the rest of the app keeps its bars. */
export function leaveImmersive() {
  if (screen.immersive) { screen.immersive = false; root.classList.remove('immersive'); sync(); }
}
