import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { RewatchStore } from './store.mjs';
import { makeServer } from './server.mjs';
import { makeSimklVerifier } from './simkl-identity.mjs';
import { SimklReplayCredentials } from './simkl-sessions.mjs';

const TOKEN1 = 'existing_simkl_logged_in_access_token_11111';
const TOKEN2 = 'existing_simkl_logged_in_access_token_22222';
const OTHER = 'existing_simkl_logged_in_access_token_33333';
const CLIENT_A = 'existing-nuvio-ipad-app';
const CLIENT_B = 'existing-nuvio-android-tv-app';
const LEGACY = 'legacy-staging-credential-1234567890abcdef';
const ID_IPAD = '00000000-0000-4000-8000-000000000001';
const ID_TV = '00000000-0000-4000-8000-000000000002';
const ID_FRIEND = '00000000-0000-4000-8000-000000000003';
let eventCounter = 0;
const ep = { season: 3, episode: 5 };
const session = {
  sessionId: 'office-rewatch', canonicalSeriesId: 'imdb:tt0386676',
  episode: ep, positionMs: 0, durationMs: 0,
  phase: 'PLAYING', lastCompletedEpisode: null
};

async function fixture(task, options = {}) {
  const dir = await mkdtemp(join(tmpdir(), 'nuvio-existing-simkl-'));
  const store = new RewatchStore(join(dir, 'rewatch.json'));
  await store.init();
  let current = 1_763_000_000_000;
  const now = () => current;
  const registry = new SimklReplayCredentials(join(dir, 'credentials.json'), {
    now, lifetimeMs: 60000
  });
  await registry.init();
  let requests = 0;
  const verifier = makeSimklVerifier({
    allowedClientIds: [CLIENT_A, CLIENT_B],
    fetchImpl: async (url, request) => {
      requests += 1;
      assert.equal(request.method, 'POST');
      assert.equal(url.pathname, '/users/settings');
      const token = request.headers.Authorization.replace('Bearer ', '');
      if (token === 'expired-simkl-token-01234567890123456789') {
        return new Response('', { status: 401 });
      }
      if (token === TOKEN1 || token === TOKEN2) {
        return new Response(JSON.stringify({ account: { id: 12003 } }), { status: 200 });
      }
      if (token === OTHER) {
        return new Response(JSON.stringify({ account: { id: 99221 } }), { status: 200 });
      }
      return new Response('', { status: 401 });
    }
  });
  const staticConfig = {
    [LEGACY]: { principal: 'staging-only', deviceId: 'staging-1' }
  };
  const server = makeServer(store, staticConfig, options.disabled ? {} : {
    simklVerifier: verifier, simklCredentials: registry
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const origin = 'http://127.0.0.1:' + server.address().port;
  async function send(method, endpoint, token, data) {
    const response = await fetch(origin + endpoint, {
      method,
      headers: {
        ...(token ? { Authorization: 'Bearer ' + token } : {}),
        'Content-Type': 'application/json'
      },
      body: data === undefined ? undefined : JSON.stringify(data)
    });
    return { status: response.status, data: await response.json() };
  }
  const bootstrap = (accessToken = TOKEN1, clientId = CLIENT_A, extra = {}) =>
    send('POST', '/v1/simkl/bootstrap', null, { clientId, accessToken, installationId: ID_IPAD, ...extra });
  const list = token => send('GET', '/v1/rewatches', token);
  const start = (identity, token, revision = 0) => send('POST', '/v1/rewatches/events',
    token, {
      eventId: 'event-' + (++eventCounter),
      expectedRevision: revision,
      type: 'START', canonicalSeriesId: session.canonicalSeriesId,
      sessionId: session.sessionId, session, deviceId: identity.deviceId
    });
  try {
    await task({ bootstrap, list, start, send, store, registry, now,
      advance: ms => { current += ms; }, requests: () => requests, dir });
  } finally {
    await new Promise(resolve => server.close(resolve));
    await rm(dir, { recursive: true, force: true });
  }
}

test('upgrade: already-connected iPad/Android reuse Simkl access tokens without new login', () =>
  fixture(async ({ bootstrap, list, start }) => {
    const ipad = await bootstrap(TOKEN1, CLIENT_A, { accountId: 123456 });
    const tv = await bootstrap(TOKEN2, CLIENT_B, { accountId: 987654, installationId: ID_TV });
    assert.equal(ipad.status, 201);
    assert.equal(tv.status, 201);
    // App-supplied account IDs were deliberately wrong: only Simkl's verified
    // account.id may be returned to the clients for profile binding.
    assert.equal(ipad.data.simklAccountId, '12003');
    assert.equal(tv.data.simklAccountId, '12003');
    assert.notEqual(ipad.data.token, tv.data.token);
    assert.notEqual(ipad.data.deviceId, tv.data.deviceId);
    assert.ok(ipad.data.expiresAtEpochMs > 0);
    assert.equal((await start(ipad.data, ipad.data.token)).status, 200);
    assert.equal((await list(tv.data.token)).data.items.length, 1);
    assert.equal((await list(tv.data.token)).data.items[0].session.episode.episode, 5);
    assert.equal('accountId' in ipad.data, false);
  })
);

test('friends using their own Simkl account cannot see another account replay', () =>
  fixture(async ({ bootstrap, list, start }) => {
    const family = (await bootstrap()).data;
    const friend = (await bootstrap(OTHER, CLIENT_B, { installationId: ID_FRIEND })).data;
    assert.equal(friend.simklAccountId, '99221');
    assert.equal((await start(family, family.token)).status, 200);
    assert.equal((await list(friend.token)).data.items.length, 0);
  })
);

test('unverified or revoked Simkl token never creates a replay session', () =>
  fixture(async ({ bootstrap, list, requests }) => {
    const rejected = await bootstrap('expired-simkl-token-01234567890123456789');
    assert.equal(rejected.status, 401);
    assert.equal(rejected.data.error, 'invalid_simkl_token');
    const invalidClient = await bootstrap(TOKEN1, 'some-other-client');
    assert.equal(invalidClient.status, 403);
    assert.equal(requests(), 1);
    assert.equal((await list('unknown')).status, 401);
  })
);

test('short-lived credential expiry triggers silent Simkl verification again', () =>
  fixture(async ({ bootstrap, list, advance }) => {
    const first = (await bootstrap()).data;
    assert.equal((await list(first.token)).status, 200);
    advance(60000);
    assert.equal((await list(first.token)).status, 401);
    // Existing Simkl token is still present in the app. No new login flow.
    const renewed = (await bootstrap()).data;
    assert.notEqual(first.token, renewed.token);
    assert.equal(first.deviceId, renewed.deviceId, 'queued offline events keep original device ID');
    assert.equal((await list(renewed.token)).status, 200);
  })
);

test('replay token revocation cannot be performed with static staging token', () =>
  fixture(async ({ bootstrap, send, list }) => {
    const issued = (await bootstrap()).data;
    assert.equal((await send('POST', '/v1/simkl/revoke', LEGACY)).status, 403);
    assert.equal((await send('POST', '/v1/simkl/revoke', issued.token)).status, 200);
    assert.equal((await list(issued.token)).status, 401);
  })
);

test('bootstrap route is OFF by default', () =>
  fixture(async ({ bootstrap, list }) => {
    assert.equal((await bootstrap()).status, 401);
    assert.equal((await list(LEGACY)).status, 200);
  }, { disabled: true })
);

test('server refuses half-configured Simkl bootstrap', () => {
  const store = new RewatchStore('/tmp/staging-not-used');
  assert.throws(() => makeServer(store, {
    [LEGACY]: { principal: 'legacy', deviceId: 'legacy-device' }
  }, { simklVerifier: async () => ({ ok: false }) }),
  /configured together/);
});

test('queued rewatch event from before token renewal still has valid authenticated device ID', () =>
  fixture(async ({ bootstrap, start, advance }) => {
    const first = (await bootstrap(TOKEN1, CLIENT_A, { installationId: ID_IPAD })).data;
    // The app queued this local event offline with first.deviceId.
    advance(60000);
    const renewed = (await bootstrap(TOKEN1, CLIENT_A, { installationId: ID_IPAD })).data;
    assert.equal(first.deviceId, renewed.deviceId);
    assert.equal((await start(first, renewed.token)).status, 200);
    assert.equal((await start(first, first.token)).status, 401);
  })
);

test('missing, malformed and non-v4 installation IDs fail before any Simkl request', () =>
  fixture(async ({ bootstrap, requests }) => {
    for (const installationId of [
      undefined, null, '', 'device-1',
      '00000000-0000-1000-8000-000000000001'
    ]) {
      const response = await bootstrap(TOKEN1, CLIENT_A, { installationId });
      assert.equal(response.status, 400);
      assert.equal(response.data.error, 'invalid_installation_id');
    }
    assert.equal(requests(), 0);
  })
);

test('same installed device linked to a different verified Simkl user remains isolated', () =>
  fixture(async ({ bootstrap, list, start }) => {
    const first = (await bootstrap(TOKEN1, CLIENT_A, {
      installationId: ID_IPAD
    })).data;
    const differentAccount = (await bootstrap(OTHER, CLIENT_A, {
      installationId: ID_IPAD
    })).data;
    assert.notEqual(first.deviceId, differentAccount.deviceId);
    assert.equal((await start(first, first.token)).status, 200);
    assert.equal((await list(differentAccount.token)).data.items.length, 0);
    assert.equal((await list(first.token)).data.items.length, 1);
  })
);
