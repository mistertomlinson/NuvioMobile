import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { PairingRegistry } from './pairing.mjs';

async function fixture(task) {
  const dir = await mkdtemp(join(tmpdir(), 'nuvio-pairing-test-'));
  const file = join(dir, 'pairing.json');
  let now = 1762819200000;
  const registry = new PairingRegistry(file, { now: () => now, inviteLifetimeMs: 60000 });
  await registry.init();
  try {
    await task({ registry, file, setTime: value => { now = value; }, currentTime: () => now });
  } finally { await rm(dir, { recursive: true, force: true }); }
}

test('new person invitations isolate friends and never store secrets in plaintext', () =>
  fixture(async ({ registry, file }) => {
    const aInvite = await registry.createNewPersonInvite();
    const bInvite = await registry.createNewPersonInvite();
    assert.equal(aInvite.ok, true);
    assert.notEqual(aInvite.invitation, bInvite.invitation);
    const a = await registry.redeem(aInvite.invitation);
    const b = await registry.redeem(bInvite.invitation);
    assert.equal(a.ok, true);
    assert.equal(b.ok, true);
    assert.notEqual(a.principal, b.principal);
    assert.notEqual(a.credential.token, b.credential.token);
    assert.notEqual(a.credential.deviceId, b.credential.deviceId);
    assert.equal(registry.identify(a.credential.token)?.principal, a.principal);
    assert.equal(registry.identify(b.credential.token)?.principal, b.principal);
    const persisted = await readFile(file, 'utf8');
    for (const value of [aInvite.invitation, bInvite.invitation,
      a.credential.token, b.credential.token]) {
      assert.ok(!persisted.includes(value), 'Plaintext secret leaked into persistent state');
    }
    assert.equal((await stat(file)).mode & 0o777, 0o600);
  })
);

test('a second device joins only when authorized by existing device token', () =>
  fixture(async ({ registry }) => {
    const fresh = await registry.createNewPersonInvite();
    const first = await registry.redeem(fresh.invitation);
    assert.equal((await registry.createLinkedDeviceInvite('not-a-token')).error, 'unauthorized');
    const linkedInvite = await registry.createLinkedDeviceInvite(first.credential.token);
    const second = await registry.redeem(linkedInvite.invitation);
    assert.equal(second.principal, first.principal);
    assert.notEqual(first.credential.deviceId, second.credential.deviceId);
    assert.notEqual(first.credential.token, second.credential.token);
    assert.equal(registry.identify(second.credential.token)?.principal, first.principal);
  })
);

test('invitations are one-use, including concurrent redemption attempts', () =>
  fixture(async ({ registry }) => {
    const invite = await registry.createNewPersonInvite();
    const both = await Promise.all([
      registry.redeem(invite.invitation),
      registry.redeem(invite.invitation)
    ]);
    assert.equal(both.filter(x => x.ok).length, 1);
    assert.equal(both.filter(x => !x.ok).length, 1);
    assert.equal((await registry.redeem(invite.invitation)).ok, false);
  })
);

test('expired invitations cannot enroll new devices', () =>
  fixture(async ({ registry, currentTime, setTime }) => {
    const invite = await registry.createNewPersonInvite();
    setTime(currentTime() + 60000);
    assert.equal((await registry.redeem(invite.invitation)).error, 'invalid_or_expired_invitation');
  })
);

test('revocation immediately blocks credential and forbids unauthorized cross-person revocations', () =>
  fixture(async ({ registry }) => {
    const alice = await registry.redeem((await registry.createNewPersonInvite()).invitation);
    const bob = await registry.redeem((await registry.createNewPersonInvite()).invitation);
    assert.equal((await registry.revokeDevice(bob.credential.token, alice.credential.deviceId)).error, 'not_found');
    assert.equal((await registry.revokeDevice(alice.credential.token, bob.credential.deviceId)).error, 'not_found');
    assert.equal((await registry.revokeDevice(alice.credential.token, alice.credential.deviceId)).ok, true);
    assert.equal(registry.identify(alice.credential.token), null);
    assert.equal((await registry.createLinkedDeviceInvite(alice.credential.token)).error, 'unauthorized');
    assert.equal(registry.identify(bob.credential.token)?.principal, bob.principal);
  })
);

test('link and revocation decisions persist across registry restarts', () =>
  fixture(async ({ registry, file, currentTime }) => {
    const first = await registry.redeem((await registry.createNewPersonInvite()).invitation);
    const linked = await registry.createLinkedDeviceInvite(first.credential.token);
    const reloaded = new PairingRegistry(file, {
      now: currentTime, inviteLifetimeMs: 60000
    });
    await reloaded.init();
    const second = await reloaded.redeem(linked.invitation);
    assert.equal(second.principal, first.principal);
    assert.equal((await reloaded.revokeDevice(first.credential.token, second.credential.deviceId)).ok, true);
    const secondReload = new PairingRegistry(file, {
      now: currentTime, inviteLifetimeMs: 60000
    });
    await secondReload.init();
    assert.equal(secondReload.identify(second.credential.token), null);
    assert.ok(secondReload.identify(first.credential.token));
  })
);

test('unknown, malformed and reused codes fail without revealing person identity', () =>
  fixture(async ({ registry }) => {
    for (const invalid of ['bad-code', '', 'x'.repeat(64), null, 32]) {
      const result = await registry.redeem(invalid);
      assert.deepEqual(result, { ok: false, error: 'invalid_or_expired_invitation' });
    }
    const invite = await registry.createNewPersonInvite();
    const first = await registry.redeem(invite.invitation);
    assert.equal(first.ok, true);
    assert.deepEqual(await registry.redeem(invite.invitation), {
      ok: false, error: 'invalid_or_expired_invitation'
    });
  })
);

test('reject invitation TTL outside tenable pairing window', () => {
  assert.throws(() => new PairingRegistry('/tmp/noop', { inviteLifetimeMs: 0 }), /Unsafe/);
  assert.throws(() => new PairingRegistry('/tmp/noop', { inviteLifetimeMs: 3600000 }), /Unsafe/);
});
