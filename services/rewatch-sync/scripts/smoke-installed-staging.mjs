import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

// Exercises the actual installed service modules, but NEVER the live credentials,
// live database, or running daemon. Server binds to an OS-assigned loopback port.
const installedRoot = '/opt/nuvio-rewatch-staging/services/rewatch-sync';
const suppliedRoot = process.argv[2];
const root = suppliedRoot ? resolve(suppliedRoot) : '';
if (!root || (root !== installedRoot && process.env.REWATCH_SMOKE_ALLOW_TEST_DIR !== '1')) {
  console.error('RESULT=STOP_INVALID_INSTALLED_ROOT');
  process.exit(1);
}
const { RewatchStore } = await import(pathToFileURL(join(root, 'store.mjs')).href);
const { makeServer } = await import(pathToFileURL(join(root, 'server.mjs')).href);
const { enrollStagingDevice } = await import(
  pathToFileURL(join(root, 'scripts/enroll-staging-device.mjs')).href
);
const temp = await mkdtemp(join(tmpdir(), 'nuvio-private-e2e-'));
let server;
let success = false;
try {
  const credentialsFile = join(temp, 'credentials.json');
  const receiptsDirectory = join(dirname(credentialsFile), 'receipts');
  const starterToken = randomBytes(32).toString('hex');
  await writeFile(credentialsFile, JSON.stringify({
    [starterToken]: { principal: 'staging-bootstrap-only', deviceId: 'staging-bootstrap-device' }
  }), { mode: 0o600, flag: 'wx' });

  const first = await enrollStagingDevice({
    mode: 'new-person', credentialsFile, receiptsDirectory
  });
  const linked = await enrollStagingDevice({
    mode: 'link-device', credentialsFile, receiptsDirectory,
    existingReceiptFile: first.receiptFile
  });
  const friend = await enrollStagingDevice({
    mode: 'new-person', credentialsFile, receiptsDirectory
  });
  const [a, b, c] = await Promise.all(
    [first, linked, friend].map(x => readFile(x.receiptFile, 'utf8').then(JSON.parse))
  );
  assert.equal(a.principal, b.principal);
  assert.notEqual(a.deviceId, b.deviceId);
  assert.notEqual(a.token, b.token);
  assert.notEqual(a.principal, c.principal);
  assert.notEqual(b.token, c.token);

  const store = new RewatchStore(join(temp, 'isolated-db.json'));
  await store.init();
  server = makeServer(store, JSON.parse(await readFile(credentialsFile, 'utf8')));
  await new Promise((res, rej) => {
    server.once('error', rej);
    server.listen(0, '127.0.0.1', res);
  });
  assert.equal(server.address().address, '127.0.0.1');
  const base = 'http://127.0.0.1:' + server.address().port;
  async function request(token, method, path, event) {
    const headers = { 'Content-Type': 'application/json' };
    if (token) headers.Authorization = 'Bearer ' + token;
    const response = await fetch(base + path, {
      method, headers,
      body: event === undefined ? undefined : JSON.stringify(event),
      signal: AbortSignal.timeout(4000)
    });
    return { status: response.status, data: await response.json() };
  }
  const series = 'staging:smoke-' + randomUUID();
  const sessionId = 'smoke-' + randomUUID();
  const ep = { season: 2, episode: 3 };
  const session = {
    sessionId, canonicalSeriesId: series, episode: ep,
    positionMs: 0, durationMs: 0, phase: 'PLAYING', lastCompletedEpisode: null
  };
  const start = {
    type: 'START', eventId: randomUUID(), expectedRevision: 0,
    deviceId: a.deviceId, canonicalSeriesId: series, sessionId, session
  };
  const startResponse = await request(a.token, 'POST', '/v1/rewatches/events', start);
  assert.equal(startResponse.status, 200);
  assert.equal(startResponse.data.revision, 1);

  const [personA, personB, stranger, unauthorized, forbidden] = await Promise.all([
    request(a.token, 'GET', '/v1/rewatches'),
    request(b.token, 'GET', '/v1/rewatches'),
    request(c.token, 'GET', '/v1/rewatches'),
    request(null, 'GET', '/v1/rewatches'),
    request(c.token, 'GET', '/v1/rewatches/' + encodeURIComponent(sessionId))
  ]);
  assert.equal(personA.data.items.length, 1);
  assert.equal(personB.data.items.length, 1);
  assert.equal(stranger.data.items.length, 0);
  assert.equal(unauthorized.status, 401);
  assert.equal(forbidden.status, 404);

  const progress = {
    type: 'PROGRESS', eventId: randomUUID(), expectedRevision: 1,
    deviceId: b.deviceId, canonicalSeriesId: series, sessionId,
    episode: ep, positionMs: 12000, durationMs: 60000
  };
  assert.equal((await request(b.token, 'POST', '/v1/rewatches/events', progress)).status, 200);
  const seen = await request(a.token, 'GET', '/v1/rewatches');
  assert.equal(seen.data.items[0].session.positionMs, 12000);
  const stale = await request(a.token, 'POST', '/v1/rewatches/events', {
    ...progress, deviceId: a.deviceId, expectedRevision: 1, eventId: randomUUID(), positionMs: 1000
  });
  assert.equal(stale.status, 409);
  assert.equal(stale.data.current.session.positionMs, 12000);

  const stop = await request(a.token, 'POST', '/v1/rewatches/events', {
    type: 'STOP', eventId: randomUUID(), expectedRevision: 2,
    deviceId: a.deviceId, canonicalSeriesId: series, sessionId
  });
  assert.equal(stop.status, 200);
  assert.equal(stop.data.session.phase, 'FINISHED');
  const reopened = new RewatchStore(join(temp, 'isolated-db.json'));
  await reopened.init();
  assert.equal(reopened.list(a.principal)[0].revision, 3);
  assert.equal(reopened.list(c.principal).length, 0);

  console.log('PERSON_ISOLATION=PASS');
  console.log('LINKED_DEVICE_SYNC=PASS');
  console.log('DEVICE_AUTH=PASS');
  console.log('REVISION_CONFLICT=PASS');
  console.log('TOMBSTONE_RESTART=PASS');
  success = true;
} catch (error) {
  // Avoid logging private receipts, tokens or remote response bodies.
  console.error('SMOKE_ERROR=' + (error?.name || 'unknown'));
} finally {
  if (server) {
    await new Promise(resolve => server.close(resolve));
  }
  await rm(temp, { recursive: true, force: true });
  console.log('LIVE_CREDENTIALS=UNTOUCHED');
  console.log('LIVE_REWATCH_DATABASE=UNTOUCHED');
  console.log('RUNNING_STAGING_DAEMON=UNTOUCHED');
  console.log('PRIVATE_SMOKE_FILES=REMOVED');
  console.log('RESULT=' + (success ? 'MULTIUSER_INSTALLED_SMOKE_PASSED' : 'MULTIUSER_INSTALLED_SMOKE_FAILED'));
  if (!success) process.exitCode = 1;
}
