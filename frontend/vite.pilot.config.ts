import { defineConfig, mergeConfig } from 'vite';
import path from 'path';
import normalConfig from './vite.config';

export default defineConfig(async (environment) => mergeConfig(
  await (typeof normalConfig === 'function' ? normalConfig(environment) : normalConfig),
  { build: { outDir: 'dist-autocase-pilot', emptyOutDir: true,
    rollupOptions: { input: path.resolve(__dirname, 'pilot.html') } } },
));
