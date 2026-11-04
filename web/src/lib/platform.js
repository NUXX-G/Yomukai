// iPhone and iPad: Safari has its own rules (no full screen on iPhone, the status bar over the page),
// so the app adapts to them here.

export const isIOS = /iPhone|iPad|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
export const isIPhone = isIOS && /iPhone|iPod/.test(navigator.userAgent);
/** Opened from the home screen icon: no Safari bars at all. */
export const isStandalone = navigator.standalone === true || matchMedia('(display-mode: standalone)').matches;

/**
 * Safari 26 draws the page under the status bar, and at times reports no safe area for it, so the
 * buttons of the top bars ended up under the clock and battery, hidden and impossible to tap. When
 * the page clearly takes the whole screen height and no inset is reported, a minimum is put back.
 */
export function adaptToIOS() {
  if (!isIOS) return;
  const root = document.documentElement;
  root.classList.add('ios');
  if (isStandalone) root.classList.add('standalone');
  const probe = document.createElement('div');
  probe.style.cssText = 'position:fixed;top:0;left:0;width:0;height:0;visibility:hidden;pointer-events:none;padding-top:env(safe-area-inset-top)';
  document.body.appendChild(probe);
  const update = () => {
    const reported = parseFloat(getComputedStyle(probe).paddingTop) || 0;
    let minimum = 0;
    const portrait = innerHeight > innerWidth;
    if (!isStandalone && reported === 0 && portrait && screen.height - innerHeight < 120) {
      // Phones with a notch or island have a ~50 px status bar; older iPhones and iPads about 20-24.
      minimum = isIPhone ? (screen.height >= 812 ? 50 : 20) : 24;
    }
    root.style.setProperty('--ios-top', minimum + 'px');
  };
  update();
  addEventListener('resize', update);
  addEventListener('orientationchange', () => setTimeout(update, 300));
}
