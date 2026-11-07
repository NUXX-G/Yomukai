// Every interface text of the web: the first argument of t('…') and tk('…') in src/. Used by the i18n test (no text
// without its English translation) and, with --missing, to list the ones still to translate.
import { readFileSync, readdirSync } from 'node:fs';
import process from 'node:process';
import { fileURLToPath } from 'node:url';
import { dirname, join, resolve } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
export const DICT = resolve(here, '../../shared/src/main/resources/i18n/en.json');

export function keys(dir = resolve(here, '../src')) {
  const found = new Set();
  (function walk(d) {
    for (const e of readdirSync(d, { withFileTypes: true })) {
      const p = join(d, e.name);
      if (e.isDirectory()) walk(p);
      else if (/\.(svelte|js)$/.test(e.name)) {
        const src = readFileSync(p, 'utf8');
        for (const m of src.matchAll(/\btk?\(\s*'((?:[^'\\\n]|\\.)*)'/g)) found.add(m[1].replace(/\\(.)/g, '$1'));
      }
    }
  })(dir);
  return [...found].sort();
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  let dict = {};
  try { dict = JSON.parse(readFileSync(DICT, 'utf8')); } catch { /* none yet */ }
  const all = keys();
  const list = process.argv.includes('--missing') ? all.filter((k) => !dict[k]) : all;
  process.stdout.write(JSON.stringify(list, null, 1) + '\n');
}
