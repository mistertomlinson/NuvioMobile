import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { RewatchStore } from './store.mjs';
import { makeServer } from './server.mjs';

const tokens = {
  ipad: 'ipad-secret-0123456789abcdef0123456789abcdef',
  tv: 'tv-secret-0123456789abcdef0123456789abcdef',
  other: 'other-secret-0123456789abcdef0123456789abcdef'
};
const initial = {
  sessionId: 'session-1', canonicalSeriesId: 'imdb:tt0386676',
  episode: { season: 3, episode: 5 }, positionMs: 0, durationMs: 0,
  lastCompletedEpisode: null, phase: 'PLAYING'
};
const event = (type, expectedRevision, fields = {}) => ({
  eventId: 'event-' + type + '-' + expectedRevision,
  deviceId: 'ipad-1', sessionId: 'session-1',
  canonicalSeriesId: 'imdb:tt0386676',
  type, expectedRevision, ...fields
});
async function fixture(fn) {
  const dir = await mkdtemp(join(tmpdir(), 'rewatch-sync-test-'));
  const path = join(dir, 'db.json');
  const store = new RewatchStore(path);
  await store.init();
  const server = makeServer(store, {
    [tokens.ipad]: { principal: 'person-A', deviceId: 'ipad-1' },
    [tokens.tv]: { principal: 'person-A', deviceId: 'tv-1' },
    [tokens.other]: { principal: 'person-B', deviceId: 'other-1' }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const url = 'http://127.0.0.1:' + server.address().port;
  async function request(token, method, route, data) {
    const response = await fetch(url + route, {
      method,
      headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' },
      body: data == null ? undefined : JSON.stringify(data)
    });
    return { status: response.status, body: await response.json() };
  }
  try { await fn({ store, path, request }); }
  finally {
    await new Promise(resolve => server.close(resolve));
    await rm(dir, { recursive: true, force: true });
  }
}
const post = (request, token, body) =>
  request(token, 'POST', '/v1/rewatches/events', body);
const list = (request, token) => request(token, 'GET', '/v1/rewatches');

test('unknown tokens are unauthorized', () => fixture(async ({ request }) => {
  assert.equal((await list(request, 'unknown')).status, 401);
}));
test('two devices share a person, not another person', () => fixture(async ({ request }) => {
  assert.equal((await post(request, tokens.ipad, event('START', 0, { session: initial }))).status, 200);
  assert.equal((await list(request, tokens.tv)).body.items.length, 1);
  assert.equal((await list(request, tokens.other)).body.items.length, 0);
  assert.equal((await request(tokens.other, 'GET', '/v1/rewatches/session-1')).status, 404);
}));
test('event retries do not advance twice; reused event IDs conflict', () => fixture(async ({ request }) => {
  const start = event('START', 0, { session: initial });
  const first = await post(request, tokens.ipad, start);
  assert.deepEqual(await post(request, tokens.ipad, start), first);
  assert.equal(first.body.revision, 1);
  assert.equal((await post(request, tokens.ipad, {
    ...start, session: { ...initial, positionMs: 3000 }
  })).status, 409);
}));
test('stale offline write cannot rewind another device', () => fixture(async ({ request }) => {
  await post(request, tokens.ipad, event('START', 0, { session: initial }));
  const progress = event('PROGRESS', 1, {
    deviceId: 'tv-1', episode: initial.episode,
    positionMs: 50000, durationMs: 900000
  });
  assert.equal((await post(request, tokens.tv, progress)).body.revision, 2);
  const stale = await post(request, tokens.ipad, event('PROGRESS', 1, {
    eventId: 'offline-progress', episode: initial.episode,
    positionMs: 12000, durationMs: 900000
  }));
  assert.equal(stale.status, 409);
  assert.equal(stale.body.current.session.positionMs, 50000);
}));
test('episode completion, then STOP persists tombstone', () => fixture(async ({ request, path }) => {
  await post(request, tokens.ipad, event('START', 0, { session: initial }));
  const completed = await post(request, tokens.tv, event('COMPLETE_EPISODE', 1, {
    deviceId: 'tv-1', episode: initial.episode,
    resultPhase: 'NEXT_UP', nextEpisode: { season: 3, episode: 6 }
  }));
  assert.equal(completed.status, 200);
  assert.equal(completed.body.session.episode.episode, 6);
  assert.equal(completed.body.session.phase, 'NEXT_UP');
  const stopped = await post(request, tokens.ipad, event('STOP', 2));
  assert.equal(stopped.body.session.phase, 'FINISHED');
  const persisted = JSON.parse(await readFile(path, 'utf8'));
  assert.equal(Object.values(persisted.records)[0].session.phase, 'FINISHED');
}));
test('stale completion and zero-position next-up cannot advance', () => fixture(async ({ request }) => {
  await post(request, tokens.ipad, event('START', 0, { session: initial }));
  assert.equal((await post(request, tokens.ipad, event('COMPLETE_EPISODE', 1, {
    episode: { season: 3, episode: 4 }, resultPhase: 'NEXT_UP',
    nextEpisode: { season: 3, episode: 5 }
  }))).status, 409);
  await post(request, tokens.ipad, event('COMPLETE_EPISODE', 1, {
    episode: initial.episode, resultPhase: 'NEXT_UP',
    nextEpisode: { season: 3, episode: 6 }
  }));
  assert.equal((await post(request, tokens.ipad, event('PROGRESS', 2, {
    episode: { season: 3, episode: 6 }, positionMs: 0, durationMs: 900000
  }))).status, 409);
}));
test('restart restores state and deduplicates event', () => fixture(async ({ request, path }) => {
  const start = event('START', 0, { session: initial });
  await post(request, tokens.ipad, start);
  const restarted = new RewatchStore(path);
  await restarted.init();
  assert.equal(restarted.list('person-A')[0].revision, 1);
  assert.equal((await restarted.apply('person-A', start)).body.revision, 1);
}));
test('concurrent writes grant just one revision', () => fixture(async ({ request }) => {
  await post(request, tokens.ipad, event('START', 0, { session: initial }));
  const [a, b] = await Promise.all([
    post(request, tokens.ipad, event('PROGRESS', 1, {
      eventId: 'concurrent-a', episode: initial.episode,
      positionMs: 5000, durationMs: 900000
    })),
    post(request, tokens.tv, event('PROGRESS', 1, {
      deviceId: 'tv-1', eventId: 'concurrent-b', episode: initial.episode,
      positionMs: 7000, durationMs: 900000
    }))
  ]);
  assert.deepEqual([a.status, b.status].sort(), [200, 409]);
  assert.equal((await list(request, tokens.ipad)).body.items[0].revision, 2);
}));
test('one device cannot impersonate another', () => fixture(async ({ request }) => {
  const spoof = await post(request, tokens.tv, event('START', 0, { session: initial }));
  assert.equal(spoof.status, 403);
  assert.equal((await list(request, tokens.tv)).body.items.length, 0);
}));
test('weak tokens or incomplete device bindings are rejected', () => {
  const fakeStore = new RewatchStore('/tmp/not-used.json');
  assert.throws(() => makeServer(fakeStore, {
    short: { principal: 'person-A', deviceId: 'ipad-1' }
  }));
  assert.throws(() => makeServer(fakeStore, {
    [tokens.ipad]: { principal: 'person-A' }
  }));
});
