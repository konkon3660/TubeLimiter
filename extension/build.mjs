import esbuild from 'esbuild';
import { ZipArchive } from 'archiver';
import {
  cpSync,
  rmSync,
  mkdirSync,
  existsSync,
  readFileSync,
  statSync,
  createWriteStream
} from 'node:fs';

const watch = process.argv.includes('--watch');
// `npm run zip` builds a release bundle (no sourcemaps) and packs dist/ for the
// Chrome Web Store. Dev builds keep their sourcemaps.
const zip = process.argv.includes('--zip');

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
  sourcemap: !zip,
  logLevel: 'info'
};

function readManifestVersion() {
  const manifest = JSON.parse(readFileSync('public/manifest.json', 'utf8'));
  if (!manifest.version) throw new Error('public/manifest.json has no "version" field');
  return manifest.version;
}

// Packs dist/ into release/tubelimiter-<manifest version>.zip. The zip root is
// the extension root (manifest.json at the top level), which is what the Web
// Store developer dashboard expects.
async function packRelease() {
  const version = readManifestVersion();
  mkdirSync('release', { recursive: true });
  const outFile = `release/tubelimiter-${version}.zip`;
  rmSync(outFile, { force: true });

  await new Promise((resolve, reject) => {
    const output = createWriteStream(outFile);
    const archive = new ZipArchive({ zlib: { level: 9 } });
    output.on('close', resolve);
    output.on('error', reject);
    archive.on('warning', reject);
    archive.on('error', reject);
    archive.pipe(output);
    archive.directory('dist/', false);
    archive.finalize();
  });

  const kb = (statSync(outFile).size / 1024).toFixed(1);
  console.log(`Packaged: ${outFile} (${kb} KB)`);
}

async function run() {
  if (watch) {
    const ctxEsm = await esbuild.context({
      ...common,
      entryPoints: esmEntryPoints,
      format: 'esm',
      splitting: true,
      chunkNames: 'chunks/[name]-[hash]'
    });
    const ctxIife = await esbuild.context({
      ...common,
      entryPoints: iifeEntryPoints,
      format: 'iife'
    });
    await Promise.all([ctxEsm.watch(), ctxIife.watch()]);
    console.log('Watching for changes...');
    return;
  }

  await Promise.all([
    esbuild.build({
      ...common,
      entryPoints: esmEntryPoints,
      format: 'esm',
      splitting: true,
      chunkNames: 'chunks/[name]-[hash]'
    }),
    esbuild.build({ ...common, entryPoints: iifeEntryPoints, format: 'iife' })
  ]);
  console.log('Build complete: dist/');

  if (zip) await packRelease();
}

await run();
