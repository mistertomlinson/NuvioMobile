import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { SimklReplayCredentials } from './simkl-sessions.mjs';

async function fixture(task) {
  const dir = await mkdtemp(join(tmpdir(), 'nuvio-simkl-credential-'));
  const file = join(dir, 'sessions.json');
  let current = 1_763_000_000_000;
  const now = () => current;
  const registry = new SimklReplayCredentials(file, { now, lifetimeMs: 60000 });
  await registry.init();
  try { await task({ registry, file, now, advance: ms => { current += ms; } }); }
  finally { await rm(dir, { recursive: true, force: true }); }
}

test('separate accounts receive unique device-scoped replay credentials', () =>
  fixture(async ({ registry, file }) => {
    const a = await registry.issue('simkl:99911');
    const b = await registry.issue('simkl:99911');
    const c = await registry.issue('simkl:77722');
    assert.notEqual(a.token, b.token);
    assert.notEqual(a.deviceId, b.deviceId);
    assert.equal(registry.identify(a.token).principal, registry.identify(b.token).principal);
    assert.notEqual(registry.identify(a.token).principal, registry.identify(c.token).principal);
    const data = await readFile(file, 'utf8');
    for (const secret of [a.token, b.token, c.token]) {
      assert.equal(data.includes(secret), false, 'plaintext credential leaked');
    }
    assert.equal((await stat(file)).mode & 0o777, 0o600);
  })
);

test('replay credentials expire without requiring Simkl login again', () =>
  fixture(async ({ registry, advance }) => {
    const a = await registry.issue('simkl:12345');
    advance(59999);
    assert.ok(registry.identify(a.token));
    advance(1);
    assert.equal(registry.identify(a.token), null);
  })
);

test('credentials are durable across restart and revocation survives restart', () =>
  fixture(async ({ registry, file, now }) => {
    const a = await registry.issue('simkl:11111');
    const reopened = new SimklReplayCredentials(file, { now, lifetimeMs: 60000 });
    await reopened.init();
    assert.equal(reopened.identify(a.token)?.principal, 'simkl:11111');
    assert.equal(await reopened.revoke(a.token), true);
    const third = new SimklReplayCredentials(file, { now, lifetimeMs: 60000 });
    await third.init();
    assert.equal(third.identify(a.token), null);
  })
);

test('invalid or user-claimed account identities never issue session tokens', () =>
  fixture(async ({ registry }) => {
    for (const id of ['simkl:0', 'simkl:00001', 'simkl:bad', 'person:1', '', null]) {
      await assert.rejects(registry.issue(id));
    }
    assert.equal(registry.identify('not-real'), null);
  })
);

test('simultaneous replay credential issues keep both accounts', () =>
  fixture(async ({ registry }) => {
    const all = await Promise.all(Array.from({ length: 12 }, (_, i) =>
      registry.issue('simkl:' + (10000 + i))));
    const known = all.filter((entry) => registry.identify(entry.token));
    assert.equal(known.length, 12);
  })
);
