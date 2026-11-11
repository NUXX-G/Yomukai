// Applies a theme: only the validated tokens (colours by name, a font from a fixed list, radius, density, a background
// uploaded to the server, cover accent on/off), as CSS variables on <html>. Never CSS written by anyone. The same JSON
// is applied by the Android app. The last theme is remembered in this browser so pages open already in it.

import { t, tk, tMap } from './i18n.svelte.js';

const COLOR_KEYS = ['canvas', 'surface', 'surfaceRaised', 'surfaceHigh', 'surfaceHighest', 'border', 'borderStrong', 'text', 'textDim',
  'textFaint', 'accent', 'onAccent', 'accentSoft', 'onAccentSoft', 'info', 'success', 'warning', 'danger'];
const HEX = /^#[0-9a-f]{6}([0-9a-f]{2})?$/i;
export const FONTS = {
  inter: "'Inter Variable', Inter, system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif",
  system: "system-ui, -apple-system, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif",
  serif: "'Iowan Old Style', Georgia, 'Times New Roman', serif",
  rounded: "ui-rounded, 'SF Pro Rounded', 'Nunito', 'Varela Round', system-ui, sans-serif",
  mono: "ui-monospace, SFMono-Regular, Menlo, Consolas, 'Liberation Mono', monospace"
};
export const FONT_NAMES = tMap({ inter: 'Inter', system: tk('Del sistema'), serif: tk('Con serifa'), rounded: tk('Redondeada'), mono: tk('Monoespaciada') });
const RADIUS = { sharp: 0.35, normal: 1, round: 1.6 };
const RADIUS_BASE = { sm: 6, md: 8, lg: 12, xl: 16 };
const DENSITY = { compact: 0.75, normal: 1, comfy: 1.25 };
const kebab = (s) => s.replace(/[A-Z]/g, (c) => '-' + c.toLowerCase());

/** Whether title pages tint themselves with each cover's colour (a theme can turn it off). */
// Black and white with one accent by default (ESTILO.md): title pages take the cover's color only if a theme asks.
export const themeState = $state({ coverAccent: false, current: null });

function luminance(hex) {
  const c = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255).map((v) => (v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4));
  return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
}

/** Applies [theme] (or the default when null). */
export function applyTheme(theme) {
  const root = document.documentElement;
  for (const k of COLOR_KEYS) root.style.removeProperty('--c-' + kebab(k));
  for (const [k, v] of Object.entries(theme?.colors || {})) {
    if (COLOR_KEYS.includes(k) && HEX.test(v)) root.style.setProperty('--c-' + kebab(k), v);
  }
  root.style.setProperty('--font', FONTS[theme?.font] || FONTS.inter);
  const r = RADIUS[theme?.radius] || 1;
  for (const [k, v] of Object.entries(RADIUS_BASE)) root.style.setProperty('--r-' + k, Math.round(v * r) + 'px');
  const d = DENSITY[theme?.density] || 1;
  root.style.setProperty('--s-section', Math.round(40 * d) + 'px');
  root.style.setProperty('--s-xl', Math.round(24 * d) + 'px');
  // A light canvas needs light browser controls (scrollbars, form fields) and a light status bar.
  const canvas = theme?.colors?.canvas && HEX.test(theme.colors.canvas) ? theme.colors.canvas : '#050505';
  const light = luminance(canvas) > 0.5;
  root.style.colorScheme = light ? 'light' : 'dark';
  root.classList.toggle('light-theme', light);
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', canvas.slice(0, 7));
  const bg = Number.isInteger(theme?.background) && theme.background > 0 ? theme.background : null;
  root.classList.toggle('has-bg', !!bg);
  if (bg) root.style.setProperty('--bg-image', `url('/api/themes/backgrounds/${bg}')`);
  else root.style.removeProperty('--bg-image');
  themeState.coverAccent = theme ? theme.coverAccent === true : false;
  themeState.current = theme || null;
}

/** Remembered in this browser only, so the next visit starts in the right colours before the server answers. */
export function rememberTheme(theme) {
  try { theme ? localStorage.setItem('theme', JSON.stringify(theme)) : localStorage.removeItem('theme'); } catch { /* private mode */ }
}

export function bootTheme() {
  try { const t = JSON.parse(localStorage.getItem('theme') || 'null'); if (t) applyTheme(t); } catch { /* nothing remembered */ }
}

/** Export/import code: "YMKT1:" + base64url of the theme's JSON. */
export function exportCode(theme) {
  const bytes = new TextEncoder().encode(JSON.stringify({ ...theme, background: undefined }));
  let bin = '';
  bytes.forEach((b) => (bin += String.fromCharCode(b)));
  return 'YMKT1:' + btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function importCode(code) {
  const raw = String(code || '').trim();
  if (!raw.startsWith('YMKT1:') || raw.length > 12_000) throw new Error(t('Ese código no es un tema de Yomukai'));
  const b64 = raw.slice(6).replace(/-/g, '+').replace(/_/g, '/');
  const bin = atob(b64 + '='.repeat((4 - (b64.length % 4)) % 4));
  return JSON.parse(new TextDecoder().decode(Uint8Array.from(bin, (c) => c.charCodeAt(0))));
}

export const EDITABLE = [
  ['canvas', 'Fondo'], ['surface', 'Paneles'], ['surfaceHigh', 'Paneles destacados'], ['border', 'Bordes'],
  ['text', 'Texto'], ['textDim', 'Texto secundario'], ['accent', 'Acento'], ['onAccent', 'Texto sobre acento'],
  ['accentSoft', 'Acento suave'], ['onAccentSoft', 'Texto sobre acento suave']
];
