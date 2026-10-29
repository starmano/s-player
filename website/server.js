// The download site at s-player.bmk.si. GitHub Actions builds the APK and attaches it to a
// GitHub release on every push. This server passes the newest one through, so phones download
// it from s-player.bmk.si instead of being sent on to GitHub's file servers, which some mobile
// networks reach slowly.
'use strict';

const http = require('node:http');
const fs = require('node:fs/promises');
const path = require('node:path');
const { Readable } = require('node:stream');

const APK_URL = 'https://github.com/starmano/s-player/releases/latest/download/s-player.apk';

// How long GitHub gets to start answering before visitors are redirected there instead.
const UPSTREAM_TIMEOUT_MS = 15_000;

const PAGES = {
  '/': { file: 'index.html', type: 'text/html; charset=utf-8' },
  '/index.html': { file: 'index.html', type: 'text/html; charset=utf-8' },
  '/icon.svg': { file: 'icon.svg', type: 'image/svg+xml' },
};

function createServer({ apkUrl = APK_URL } = {}) {
  return http.createServer(async (req, res) => {
    if (req.method !== 'GET' && req.method !== 'HEAD') {
      res.writeHead(405, { Allow: 'GET, HEAD' }).end();
      return;
    }

    const { pathname } = new URL(req.url, 'http://localhost');
    if (pathname === '/s-player.apk') {
      await sendApk(req, res, apkUrl);
      return;
    }

    const page = PAGES[pathname];
    if (!page) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' }).end('Not found');
      return;
    }

    try {
      // Read on every request so that a `git pull` shows up without restarting the app.
      const body = await fs.readFile(path.join(__dirname, page.file));
      res.writeHead(200, {
        'Content-Type': page.type,
        'Content-Length': body.length,
        'Cache-Control': 'no-cache',
      });
      res.end(req.method === 'HEAD' ? undefined : body);
    } catch (error) {
      console.error(error);
      res.writeHead(500, { 'Content-Type': 'text/plain; charset=utf-8' }).end('Server error');
    }
  });
}

/** Streams the newest APK from GitHub, or redirects there if GitHub can't be reached. */
async function sendApk(req, res, apkUrl) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), UPSTREAM_TIMEOUT_MS);
  let upstream;
  try {
    upstream = await fetch(apkUrl, { method: req.method, signal: controller.signal });
  } catch (error) {
    console.error(`Fetching the APK failed: ${error.message}`);
  } finally {
    clearTimeout(timer);
  }

  if (!upstream?.ok) {
    controller.abort();
    res.writeHead(302, { Location: apkUrl, 'Cache-Control': 'no-cache' }).end();
    return;
  }

  const headers = {
    'Content-Type': 'application/vnd.android.package-archive',
    'Content-Disposition': 'attachment; filename="s-player.apk"',
    'Cache-Control': 'no-cache',
  };
  const length = upstream.headers.get('content-length');
  if (length) headers['Content-Length'] = length;
  res.writeHead(200, headers);

  if (req.method === 'HEAD' || !upstream.body) {
    res.end();
    return;
  }
  // Stop downloading from GitHub if the visitor goes away.
  res.on('close', () => controller.abort());
  Readable.fromWeb(upstream.body)
    .on('error', () => res.destroy())
    .pipe(res);
}

module.exports = { createServer, APK_URL };
