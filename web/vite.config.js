import { defineConfig } from 'vite';
import { svelte } from '@sveltejs/vite-plugin-svelte';

// In development the API runs apart (./gradlew :server:run); Vite forwards to it.
const api = process.env.YOMUKAI_API || 'http://localhost:8099';

export default defineConfig({
  plugins: [svelte()],
  server: { proxy: { '/api': api, '/auth': api } },
  build: {
    target: ['es2020', 'safari14'],
    cssCodeSplit: true
  }
});
