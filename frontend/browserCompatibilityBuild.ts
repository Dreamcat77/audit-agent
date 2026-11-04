import {execFileSync} from 'node:child_process';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import type {Plugin} from 'vite';

/** Every production build includes the existing Chrome 109 color treatment. */
export function browserCompatibilityBuild(): Plugin {
  let output = '';
  let written = false;
  return {
    name: 'audit-browser-compatibility-output',
    apply: 'build',
    configResolved(config) { output = path.resolve(config.root, config.build.outDir); },
    writeBundle() { written = true; },
    closeBundle() {
      if (!written) return;
      written = false;
      execFileSync(process.execPath, [fileURLToPath(new URL('../tools/browser/prepare-chrome109.mjs', import.meta.url)), output], {stdio: 'inherit'});
    },
  };
}
