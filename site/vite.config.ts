import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { renderSVG } from 'uqr';
import { defineConfig, loadEnv } from 'vite';

const root = dirname(fileURLToPath(import.meta.url));

// Where the site is published (ADR 0039).
const host = 'dekoi.sysnordic.no';

// The code a visitor on a computer scans with their phone. Made here, so
// the dev server shows the same one the build publishes.
const qrPath = 'download/qr.svg';
const qrSvg = renderSVG(`https://${host}/download/`, { border: 2 });

// site.yml sets these from the release it publishes (ADR 0039). Without
// them the download page would link to files that are not there.
const release = [
  'VITE_VERSION',
  'VITE_APK_SHA256',
  'VITE_APK_MB',
  'VITE_MSI_SHA256',
  'VITE_MSI_MB',
  'VITE_CERT_SHA256',
];

export default defineConfig(({ command, mode }) => {
  const env = loadEnv(mode, root, 'VITE_');
  const missing = release.filter((name) => !env[name]);
  if (command === 'build' && missing.length > 0) {
    throw new Error(`The build needs ${missing.join(', ')}`);
  }
  return {
    define: { 'import.meta.env.VITE_SITE_HOST': JSON.stringify(host) },
    plugins: [
      {
        name: 'qr-code',
        configureServer(server) {
          server.middlewares.use(`/${qrPath}`, (_request, response) => {
            response.setHeader('Content-Type', 'image/svg+xml');
            response.end(qrSvg);
          });
        },
        generateBundle() {
          this.emitFile({ type: 'asset', fileName: qrPath, source: qrSvg });
        },
      },
    ],
    server: { port: 5173, open: false },
    build: {
      target: 'es2022',
      sourcemap: false,
      rollupOptions: {
        input: {
          main: resolve(root, 'index.html'),
          download: resolve(root, 'download/index.html'),
        },
        output: {
          manualChunks: { three: ['three'] },
        },
      },
    },
  };
});
