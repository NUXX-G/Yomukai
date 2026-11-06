// Generates src/tokens.css from the design tokens shared with the Android app (shared/src/main/resources/design).
// Runs before dev and build; the CSS file is generated, never edited by hand.
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const tokens = JSON.parse(readFileSync(resolve(here, '../../shared/src/main/resources/design/tokens.json'), 'utf8'));
const kebab = (s) => s.replace(/[A-Z]/g, (c) => '-' + c.toLowerCase());
const px = (v) => (typeof v === 'number' ? v + 'px' : v);

const lines = [];
for (const [k, v] of Object.entries(tokens.color)) lines.push(`  --c-${kebab(k)}: ${v};`);
for (const [k, v] of Object.entries(tokens.space)) lines.push(`  --s-${kebab(k)}: ${px(v)};`);
for (const [k, v] of Object.entries(tokens.radius)) lines.push(`  --r-${kebab(k)}: ${px(v)};`);
for (const [k, v] of Object.entries(tokens.shadow)) lines.push(`  --shadow-${kebab(k)}: ${v};`);
lines.push(`  --font: ${tokens.font.family};`);
for (const k of ['hero', 'title', 'section', 'body', 'small', 'tiny']) lines.push(`  --fs-${k}: ${px(tokens.font[k])};`);
for (const [k, v] of Object.entries(tokens.motion)) lines.push(`  --motion-${kebab(k)}: ${typeof v === 'number' ? v + 'ms' : v};`);
for (const [k, v] of Object.entries(tokens.layout)) lines.push(`  --layout-${kebab(k)}: ${k === 'posterRatio' ? v : px(v)};`);

writeFileSync(resolve(here, '../src/tokens.css'), `/* Generado por scripts/tokens.mjs desde shared/src/main/resources/design/tokens.json. No editar a mano. */\n:root {\n${lines.join('\n')}\n}\n`);
console.log('tokens.css generado');
