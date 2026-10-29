'use strict';

const assert = require('node:assert/strict');
const http = require('node:http');
const { after, before, test } = require('node:test');
const { createServer } = require('./server');

// Stands in for the GitHub release download.
const APK_BYTES = Buffer.from('PK fake apk bytes');

function listen(server) {
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => resolve(`http://127.0.0.1:${server.address().port}`));
  });
}

const servers = [];
let base;
let upstreamUrl;

before(async () => {
  const upstream = http.createServer((req, res) => {
    if (req.url !== '/s-player.apk') {
      res.writeHead(404).end();
      return;
    }
    res.writeHead(200, { 'Content-Type': 'application/octet-stream', 'Content-Length': APK_BYTES.length });
    res.end(req.method === 'HEAD' ? undefined : APK_BYTES);
  });
  upstreamUrl = `${await listen(upstream)}/s-player.apk`;
  const site = createServer({ apkUrl: upstreamUrl });
  base = await listen(site);
  servers.push(upstream, site);
});

after(() => servers.forEach((server) => server.close()));

test('serves the download page', async () => {
  const res = await fetch(`${base}/`);
  assert.equal(res.status, 200);
  assert.match(res.headers.get('content-type'), /^text\/html/);
  assert.match(await res.text(), /Download for Android/);
});

test('serves the icon', async () => {
  const res = await fetch(`${base}/icon.svg`);
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('content-type'), 'image/svg+xml');
});

test('serves the newest APK from its own address', async () => {
  const res = await fetch(`${base}/s-player.apk`, { redirect: 'manual' });
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('content-type'), 'application/vnd.android.package-archive');
  assert.equal(res.headers.get('content-disposition'), 'attachment; filename="s-player.apk"');
  assert.deepEqual(Buffer.from(await res.arrayBuffer()), APK_BYTES);
});

test('answers HEAD for the APK with its size and no body', async () => {
  const res = await fetch(`${base}/s-player.apk`, { method: 'HEAD' });
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('content-length'), String(APK_BYTES.length));
  assert.equal(await res.text(), '');
});

test('redirects to GitHub when the APK cannot be fetched', async () => {
  const missing = `${upstreamUrl}.missing`;
  const site = createServer({ apkUrl: missing });
  servers.push(site);
  const res = await fetch(`${await listen(site)}/s-player.apk`, { redirect: 'manual' });
  assert.equal(res.status, 302);
  assert.equal(res.headers.get('location'), missing);
});

test('redirects to GitHub when it cannot be reached at all', async () => {
  const unreachable = 'http://127.0.0.1:1/s-player.apk';
  const site = createServer({ apkUrl: unreachable });
  servers.push(site);
  const res = await fetch(`${await listen(site)}/s-player.apk`, { redirect: 'manual' });
  assert.equal(res.status, 302);
  assert.equal(res.headers.get('location'), unreachable);
});

test('answers HEAD for the page without a body', async () => {
  const res = await fetch(`${base}/`, { method: 'HEAD' });
  assert.equal(res.status, 200);
  assert.equal(await res.text(), '');
});

test('does not serve anything else', async () => {
  assert.equal((await fetch(`${base}/server.js`)).status, 404);
  assert.equal((await fetch(`${base}/`, { method: 'POST' })).status, 405);
});
