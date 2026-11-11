// Generates the Android app's DesignTokens.kt from the design tokens shared with the web
// (shared/src/main/resources/design/tokens.json). Run after editing the tokens: node scripts/tokens-android.mjs
// DesignTokensTest fails if the file and the JSON ever disagree.
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const tokens = JSON.parse(readFileSync(resolve(root, 'shared/src/main/resources/design/tokens.json'), 'utf8'));
const argb = (hex) => '0xFF' + hex.replace('#', '').toUpperCase();
const cap = (s) => s[0].toUpperCase() + s.slice(1);

const out = [];
out.push('// Generado por scripts/tokens-android.mjs desde shared/src/main/resources/design/tokens.json. No editar a mano.');
out.push('package com.nelson.yomukai.ui.theme');
out.push('');
out.push('import androidx.compose.ui.graphics.Color');
out.push('import androidx.compose.ui.unit.dp');
out.push('import androidx.compose.ui.unit.sp');
out.push('');
out.push('/** The same design tokens the web uses (colours, sizes, radii, motion), so both look like one product. */');
out.push('object DesignTokens {');
for (const [k, v] of Object.entries(tokens.color)) out.push(`    val ${k} = Color(${argb(v)})`);
out.push('');
for (const k of ['hero', 'title', 'section', 'body', 'small', 'tiny']) out.push(`    val font${cap(k)} = ${tokens.font[k]}.sp`);
for (const [k, v] of Object.entries(tokens.space)) out.push(`    val space${cap(k)} = ${v}.dp`);
for (const [k, v] of Object.entries(tokens.radius)) if (k !== 'pill') out.push(`    val radius${cap(k)} = ${v}.dp`);
for (const [k, v] of Object.entries(tokens.motion)) if (typeof v === 'number') out.push(`    const val motion${cap(k)} = ${v}`);
out.push(`    const val POSTER_RATIO = ${tokens.layout.posterRatio}f`);
for (const [k, v] of Object.entries(tokens.brutal)) out.push(k === 'halftone' ? `    const val BRUTAL_HALFTONE = ${v}f` : `    val brutal${cap(k)} = ${v}.dp`);
out.push('');
out.push('    /** Raw hex values, compared with tokens.json by DesignTokensTest. */');
out.push('    val HEX = mapOf(');
for (const [k, v] of Object.entries(tokens.color)) out.push(`        "${k}" to "${v.toLowerCase()}",`);
out.push('    )');
out.push('}');
writeFileSync(resolve(root, 'app/src/main/java/com/nelson/yomukai/ui/theme/DesignTokens.kt'), out.join('\n') + '\n');
console.log('DesignTokens.kt generado');
