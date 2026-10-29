// The download site at s-player.bmk.si: serves the page and sends the download button to the
// newest APK. GitHub Actions builds the APK and attaches it to a GitHub release on every push,
// so the site never has to keep a copy of it.
'use strict';

const http = require('node:http');
const fs = require('node:fs/promises');
const path = require('node:path');

const APK_URL = 'https://github.com/starmano/s-player/releases/latest/download/s-player.apk';

const PAGES = {
  '/': { file: 'index.html', type: 'text/html; charset=utf-8' },
  '/index.html': { file: 'index.html', type: 'text/html; charset=utf-8' },
  '/icon.svg': { file: 'icon.svg', type: 'image/svg+xml' },
};

function createServer() {
  return http.createServer(async (req, res) => {
    if (req.method !== 'GET' && req.method !== 'HEAD') {
      res.writeHead(405, { Allow: 'GET, HEAD' }).end();
      return;
    }

    const { pathname } = new URL(req.url, 'http://localhost');
    if (pathname === '/s-player.apk') {
      res.writeHead(302, { Location: APK_URL, 'Cache-Control': 'no-cache' }).end();
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

module.exports = { createServer, APK_URL };
