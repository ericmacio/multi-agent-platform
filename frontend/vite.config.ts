/// <reference types="vitest" />
import { defineConfig, type PluginOption } from 'vite';
import react from '@vitejs/plugin-react';
import { visualizer } from 'rollup-plugin-visualizer';
import { fileURLToPath } from 'node:url';

// `ANALYZE=true npm run build` (via the `build:analyze` script) mounts the
// treemap visualizer that writes `dist/stats.html`. The plugin is skipped
// on regular production builds so it does not incur the analysis cost.
export default defineConfig(() => {
  const analyze = process.env.ANALYZE === 'true';
  const plugins: PluginOption[] = [react()];
  if (analyze) {
    plugins.push(
      visualizer({
        filename: 'dist/stats.html',
        gzipSize: true,
        brotliSize: false,
        template: 'treemap',
        open: false,
      }) as unknown as PluginOption,
    );
  }

  return {
    plugins,
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      port: 5173,
      strictPort: true,
      proxy: {
        '/api': {
          target: 'http://localhost:8080',
          changeOrigin: true,
        },
      },
    },
    build: {
      outDir: 'dist',
      sourcemap: true,
    },
    define: {
      __APP_VERSION__: JSON.stringify(process.env.VITE_BUILD_VERSION ?? 'dev'),
    },
    test: {
      globals: true,
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.ts'],
      css: true,
    },
  };
});
