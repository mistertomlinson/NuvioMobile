import { test } from 'node:test';
import assert from 'node:assert/strict';
import { makeSimklVerifier, verifiedAccountId } from './simkl-identity.mjs';

const CLIENT_A = 'nuvio-mobile-client-verified';
const CLIENT_B = 'nuvio-android-tv-client';
const token = 'simkl_test_token_01234567890123456789';
const settings = { user: { name: 'Test' }, account: { id: 771902, type: 'free' } };
const verifier = (callback, ids = [CLIENT_A, CLIENT_B]) =>
  makeSimklVerifier({ allowedClientIds: ids, fetchImpl: callback });

test('existing Simkl token is proven by upstream POST and only account.id is used', async () => {
  let called = 0;
  const verify = verifier(async (url, request) => {
    called++;
    assert.equal(url.origin, 'https://api.simkl.com');
    assert.equal(url.pathname, '/users/settings');
    assert.equal(url.searchParams.get('client_id'), CLIENT_A);
    assert.equal(url.searchParams.get('app-name'), 'nuvio-rewatch-sync');
    assert.equal(request.method, 'POST');
    assert.equal(request.redirect, 'manual');
    assert.equal(request.headers['simkl-api-key'], CLIENT_A);
    assert.equal(request.headers.Authorization, 'Bearer ' + token);
    assert.ok(request.signal);
    return new Response(JSON.stringify(settings), { status: 200 });
  });
  const result = await verify({
    clientId: CLIENT_A, accessToken: token, accountId: 444444
  });
  assert.deepEqual(result, { ok: true, principal: 'simkl:771902' });
  assert.equal(called, 1);
});

test('another app client may verify same Simkl account and obtain same principal', async () => {
  const verify = verifier(async (url) =>
    new Response(JSON.stringify({ account: { id: '771902' } }), { status: 200 }));
  const a = await verify({ clientId: CLIENT_A, accessToken: token });
  const b = await verify({ clientId: CLIENT_B, accessToken: token });
  assert.equal(a.principal, b.principal);
});

test('unknown client ID is rejected without contacting Simkl', async () => {
  const verify = verifier(async () => { throw Error('should not call'); });
  assert.deepEqual(await verify({ clientId: 'attacker-app', accessToken: token }),
    { ok: false, status: 403, error: 'client_not_allowed' });
});

test('invalid Simkl token is rejected without contacting Simkl', async () => {
  const verify = verifier(async () => { throw Error('should not call'); });
  assert.equal((await verify({ clientId: CLIENT_A, accessToken: '' })).status, 401);
  assert.equal((await verify({ clientId: CLIENT_A, accessToken: 'x\nmalicious' })).status, 401);
});

test('expired or revoked Simkl auth cannot create replay identity', async () => {
  for (const code of [401, 403]) {
    const verify = verifier(async () => new Response('', { status: code }));
    assert.deepEqual(await verify({ clientId: CLIENT_A, accessToken: token }),
      { ok: false, status: 401, error: 'invalid_simkl_token' });
  }
});

test('Simkl timeout, rate limit, 500, and redirects fail closed', async () => {
  for (const response of [
    () => { throw Error('network down'); },
    () => new Response('', { status: 429 }),
    () => new Response('', { status: 500 }),
    () => new Response('', { status: 302, headers: { Location: 'https://other.example' } })
  ]) {
    const verify = verifier(async () => response());
    const result = await verify({ clientId: CLIENT_A, accessToken: token });
    assert.equal(result.status, 503);
    assert.equal(result.ok, false);
    assert.equal(JSON.stringify(result).includes(token), false);
  }
});

test('Simkl account identity must be valid, stable, positive and unambiguous', async () => {
  for (const accountId of [0, null, -1, 1.5, 2 ** 53, '0001', '0', 'garbage']) {
    const verify = verifier(async () =>
      new Response(JSON.stringify({ account: { id: accountId } }), { status: 200 }));
    const result = await verify({ clientId: CLIENT_A, accessToken: token });
    assert.equal(result.status, 502, 'unexpected accountId: ' + accountId);
  }
  assert.equal(verifiedAccountId({ account: { id: 1234 } }), '1234');
  assert.equal(verifiedAccountId({ account: { id: '1234' } }), '1234');
  assert.equal(verifiedAccountId({ user: { id: 1234 } }), null);
});

test('invalid settings response cannot manufacture identity', async () => {
  for (const payload of ['not json', '{}', JSON.stringify({ account: { id: 0 } }), 'x'.repeat(20000)]) {
    const verify = verifier(async () => new Response(payload, { status: 200 }));
    const result = await verify({ clientId: CLIENT_A, accessToken: token });
    assert.equal(result.ok, false);
    assert.ok([502].includes(result.status));
  }
});

test('verifier requires explicit allowlist; rejects unreasonable timeout', () => {
  assert.throws(() => makeSimklVerifier({ allowedClientIds: [] }));
  assert.throws(() => makeSimklVerifier({ allowedClientIds: ['a'] }));
  assert.throws(() => makeSimklVerifier({ allowedClientIds: [CLIENT_A], timeoutMs: 100000 }));
});
