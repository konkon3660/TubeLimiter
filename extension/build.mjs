import esbuild from 'esbuild';
import { cpSync, rmSync, mkdirSync, existsSync } from 'node:fs';

const watch = process.argv.includes('--watch');

// Content scripts registered via manifest.json content_scripts are always
// classic scripts (no "type": "module" support there), so it must be IIFE.
// Everything else (service worker with type:module, and pages loaded via
// <script type="module">) can stay ESM.
const esmEntryPoints = {
  'background/service-worker': 'src/background/service-worker.js',
  'popup/popup': 'src/popup/popup.js',
  'options/options': 'src/options/options.js',
  'dashboard/dashboard': 'src/dashboard/dashboard.js',
  'auth/auth': 'src/auth/auth.js'
};
const iifeEntryPoints = {
  'content/content': 'src/content/content.js'
};

if (existsSync('dist')) rmSync('dist', { recursive: true, force: true });
mkdirSync('dist', { recursive: true });
cpSync('public', 'dist', { recursive: true });

const common = {
  outdir: 'dist',
  bundle: true,
  target: 'chrome110',
  sourcemap: true,
  logLevel: 'info'
};

async function run() {
  if (watch) {
    const ctxEsm = await esbuild.context({ ...common, entryPoints: esmEntryPoints, format: 'esm', splitting: true, chunkNames: 'chunks/[name]-[hash]' });
    const ctxIife = await esbuild.context({ ...common, entryPoints: iifeEntryPoints, format: 'iife' });
    await Promise.all([ctxEsm.watch(), ctxIife.watch()]);
    console.log('Watching for changes...');
  } else {
    await Promise.all([
      esbuild.build({ ...common, entryPoints: esmEntryPoints, format: 'esm', splitting: true, chunkNames: 'chunks/[name]-[hash]' }),
      esbuild.build({ ...common, entryPoints: iifeEntryPoints, format: 'iife' })
    ]);
    console.log('Build complete: dist/');
  }
}

await run();
