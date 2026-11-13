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
test('manual reposition follows authenticated active replay across devices', () => fixture(async ({ request }) => {
  await post(request, tokens.ipad, event('START', 0, { session: initial }));
  const jump = event('REPOSITION', 1, {
    eventId: 'user-selected-s5e18',
    episode: { season: 5, episode: 18 }
  });
  const response = await post(request, tokens.ipad, jump);
  assert.equal(response.status, 200);
  assert.equal(response.body.revision, 2);
  assert.deepEqual(response.body.session.episode, { season: 5, episode: 18 });
  assert.equal(response.body.session.phase, 'PLAYING');
  assert.equal(response.body.session.sessionId, initial.sessionId);
  assert.equal(response.body.session.positionMs, 0);
  assert.equal(response.body.session.durationMs, 0);
  assert.equal(response.body.session.lastCompletedEpisode, null);
  assert.deepEqual(await post(request, tokens.ipad, jump), response);
  assert.equal((await list(request, tokens.tv)).body.items[0].session.episode.episode, 18);
  assert.equal((await list(request, tokens.other)).body.items.length, 0);
  const progress = await post(request, tokens.ipad, event('PROGRESS', 2, {
    episode: { season: 5, episode: 18 },
    positionMs: 45000, durationMs: 1100000
  }));
  assert.equal(progress.status, 200);
  const next = await post(request, tokens.ipad, event('COMPLETE_EPISODE', 3, {
    episode: { season: 5, episode: 18 }, resultPhase: 'NEXT_UP',
    nextEpisode: { season: 5, episode: 19 }
  }));
  assert.equal(next.status, 200);
  assert.equal((await list(request, tokens.tv)).body.items[0].session.episode.episode, 19);
}));

test('backward manual reposition is allowed but stale events and malformed requests fail closed',
  () => fixture(async ({ request }) => {
    await post(request, tokens.ipad, event('START', 0, { session: initial }));
    const back = await post(request, tokens.ipad, event('REPOSITION', 1, {
      episode: { season: 2, episode: 4 }
    }));
    assert.equal(back.status, 200);
    assert.equal(back.body.session.episode.season, 2);
    assert.equal((await post(request, tokens.tv, event('REPOSITION', 1, {
      eventId: 'stale-tv-jump', deviceId: 'tv-1',
      episode: { season: 6, episode: 1 }
    }))).status, 409);
    for (const [suffix, fields] of [
      ['same', { episode: { season: 2, episode: 4 } }],
      ['nonseason', { episode: { season: 0, episode: 5 } }],
      ['inject', { episode: { season: 2, episode: 5 }, positionMs: 6000 }],
      ['add-session', { episode: { season: 2, episode: 5 }, session: initial }]
    ]) {
      assert.equal((await post(request, tokens.ipad, event('REPOSITION', 2, {
        eventId: 'invalid-' + suffix, ...fields
      }))).status, 409, suffix);
    }
    assert.equal((await list(request, tokens.ipad)).body.items[0].session.episode.episode, 4);
  })
);

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

test('in-place upgrade imports existing NEXT_UP rewatch without losing its episode', () =>
  fixture(async ({ request }) => {
    const existing = {
      ...initial,
      sessionId: 'office-local-rewatch',
      episode: { season: 3, episode: 18 },
      lastCompletedEpisode: { season: 3, episode: 17 },
      phase: 'NEXT_UP'
    };
    const imported = await post(request, tokens.ipad, event('IMPORT_SNAPSHOT', 0, {
      eventId: 'upgrade-next-up',
      sessionId: existing.sessionId,
      session: existing,
      // Untrusted device-local identifiers must not become authorization.
      ownerKey: 'local-profile:9999'
    }));
    assert.equal(imported.status, 200);
    assert.equal(imported.body.revision, 1);
    assert.deepEqual(imported.body.session.episode, { season: 3, episode: 18 });
    assert.deepEqual(imported.body.session.lastCompletedEpisode,
      { season: 3, episode: 17 });
    assert.equal(imported.body.session.phase, 'NEXT_UP');
    assert.equal(imported.body.session.ownerKey, undefined);
    assert.equal((await list(request, tokens.tv)).body.items.length, 1);
    assert.equal((await list(request, tokens.other)).body.items.length, 0);
  })
);

test('in-place upgrade imports paused episode and terminal tombstone', () =>
  fixture(async ({ request }) => {
    const paused = await post(request, tokens.ipad, event('IMPORT_SNAPSHOT', 0, {
      eventId: 'import-paused',
      session: { ...initial, positionMs: 65000, durationMs: 1800000 }
    }));
    assert.equal(paused.status, 200);
    assert.equal(paused.body.session.positionMs, 65000);
    const tombstone = {
      ...initial, sessionId: 'session-finished',
      canonicalSeriesId: 'imdb:tt0944947',
      phase: 'FINISHED'
    };
    const finished = await post(request, tokens.ipad, {
      ...event('IMPORT_SNAPSHOT', 0, {
        eventId: 'import-finished',
        sessionId: tombstone.sessionId,
        canonicalSeriesId: tombstone.canonicalSeriesId
      }),
      session: tombstone
    });
    assert.equal(finished.status, 200);
    assert.equal(finished.body.session.phase, 'FINISHED');
    assert.equal((await list(request, tokens.tv)).body.items.length, 2);
  })
);

test('existing remote progress cannot be overwritten by initial offline import', () =>
  fixture(async ({ request }) => {
    const first = await post(request, tokens.tv, event('START', 0, {
      eventId: 'remote-start', deviceId: 'tv-1', session: initial
    }));
    assert.equal(first.status, 200);
    const fromIpad = await post(request, tokens.ipad,
      event('IMPORT_SNAPSHOT', 0, {
        eventId: 'stale-import',
        sessionId: 'another-local-session',
        session: { ...initial, sessionId: 'another-local-session',
          episode: { season: 3, episode: 19 }, phase: 'NEXT_UP',
          lastCompletedEpisode: { season: 3, episode: 18 } }
      }));
    assert.equal(fromIpad.status, 409);
    assert.equal((await list(request, tokens.tv)).body.items[0].session.episode.episode, 5);
  })
);

test('invalid imported cursor never erases the existing server database', () =>
  fixture(async ({ request }) => {
    for (const [id, altered] of [
      ['no-last-completed', { ...initial, phase: 'NEXT_UP' }],
      ['same-last-completed', { ...initial, phase: 'NEXT_UP',
        lastCompletedEpisode: initial.episode }],
      ['bad-awaiting', { ...initial, phase: 'AWAITING_METADATA',
        lastCompletedEpisode: { season: 2, episode: 9 } }],
      ['bad-position', { ...initial, phase: 'NEXT_UP',
        lastCompletedEpisode: { season: 3, episode: 4 },
        positionMs: 18000 }]
    ]) {
      const result = await post(request, tokens.ipad, event('IMPORT_SNAPSHOT', 0, {
        eventId: id,
        session: altered
      }));
      assert.equal(result.status, 400, id);
    }
    assert.equal((await list(request, tokens.ipad)).body.items.length, 0);
  })
);

test('late metadata resolution advances existing AWAITING_METADATA replay once', () =>
  fixture(async ({ request }) => {
    assert.equal((await post(request, tokens.ipad,
      event('START', 0, { session: initial }))).status, 200);
    const awaiting = await post(request, tokens.ipad,
      event('COMPLETE_EPISODE', 1, {
        episode: initial.episode, resultPhase: 'AWAITING_METADATA'
      }));
    assert.equal(awaiting.status, 200);
    assert.equal(awaiting.body.session.phase, 'AWAITING_METADATA');
    const resolution = event('RESOLVE_METADATA', 2, {
      eventId: 'resolve-episode',
      deviceId: 'tv-1',
      completedEpisode: initial.episode,
      resultPhase: 'NEXT_UP',
      nextEpisode: { season: 3, episode: 6 }
    });
    const resolved = await post(request, tokens.tv, resolution);
    assert.equal(resolved.status, 200);
    assert.equal(resolved.body.session.phase, 'NEXT_UP');
    assert.equal(resolved.body.session.episode.episode, 6);
    assert.deepEqual(await post(request, tokens.tv, resolution), resolved);
    assert.equal((await post(request, tokens.ipad, event('RESOLVE_METADATA', 2, {
      eventId: 'stale-second-resolution',
      completedEpisode: initial.episode,
      resultPhase: 'FINISHED'
    }))).status, 409);
    assert.equal((await list(request, tokens.ipad)).body.items[0].session.episode.episode, 6);
  })
);

test('late metadata cannot jump backward or resolve before a real completion', () =>
  fixture(async ({ request }) => {
    await post(request, tokens.ipad, event('START', 0, { session: initial }));
    assert.equal((await post(request, tokens.ipad, event('RESOLVE_METADATA', 1, {
      completedEpisode: initial.episode, resultPhase: 'NEXT_UP',
      nextEpisode: { season: 3, episode: 6 }
    }))).status, 409);
    await post(request, tokens.ipad, event('COMPLETE_EPISODE', 1, {
      episode: initial.episode, resultPhase: 'AWAITING_METADATA'
    }));
    assert.equal((await post(request, tokens.ipad, event('RESOLVE_METADATA', 2, {
      completedEpisode: initial.episode, resultPhase: 'NEXT_UP',
      nextEpisode: { season: 3, episode: 4 }
    }))).status, 409);
    assert.equal((await list(request, tokens.ipad)).body.items[0].session.phase,
      'AWAITING_METADATA');
  })
);
