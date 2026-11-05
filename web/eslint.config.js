import js from '@eslint/js';
import svelte from 'eslint-plugin-svelte';
import globals from 'globals';

export default [
  { ignores: ['dist/', 'node_modules/', 'public/sw.js'] },
  js.configs.recommended,
  ...svelte.configs.recommended,
  {
    languageOptions: { globals: { ...globals.browser }, ecmaVersion: 'latest', sourceType: 'module' },
    rules: {
      // Seguridad: nunca HTML crudo con datos de usuario o scrapeados.
      'svelte/no-at-html-tags': 'error',
      'no-unused-vars': ['error', { argsIgnorePattern: '^_', caughtErrors: 'none' }],
      'no-empty': ['error', { allowEmptyCatch: true }],
      // Avisos (se revisan en el rediseño de la Fase 5): claves en {#each} y Set/Map reactivos de Svelte.
      'svelte/require-each-key': 'warn',
      'svelte/prefer-svelte-reactivity': 'warn'
    }
  },
  { files: ['**/*.svelte.js'], languageOptions: { parserOptions: { svelteFeatures: { runes: true } } } },
  { files: ['vite.config.js', 'eslint.config.js'], languageOptions: { globals: { ...globals.node } } }
];
