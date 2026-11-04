// Full screen for the readers: hides the browser bar and, on phones, the system's status bar, so only
// the page and the small clock remain. Not every browser allows it (iPhones do not): then nothing shows.

export const screen = $state({ full: false });

export const canFullscreen = !!document.documentElement.requestFullscreen && document.fullscreenEnabled !== false;

document.addEventListener('fullscreenchange', () => { screen.full = !!document.fullscreenElement; });

export function enterFullscreen() {
  if (!canFullscreen || document.fullscreenElement) return;
  document.documentElement.requestFullscreen({ navigationUI: 'hide' }).catch(() => {});
}

export function exitFullscreen() {
  if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
}

export function toggleFullscreen() {
  if (document.fullscreenElement) exitFullscreen();
  else enterFullscreen();
}
