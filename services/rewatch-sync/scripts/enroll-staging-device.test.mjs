import { test } from 'node:test';
import assert from 'node:assert/strict';
import { chmod, lstat, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { enrollStagingDevice } from './enroll-staging-device.mjs';
import { makeServer } from '../server.mjs';
import { RewatchStore } from '../store.mjs';

async function fixture(run) {
  const dir = await mkdtemp(join(tmpdir(), 'rewatch-enroll-'));
  const credentialsFile = join(dir, 'credentials.json');
  const receiptsDirectory = join(dir, 'receipts');
  const initial = {
    'staging-initial-0123456789abcdef0123456789abcdef': {
      principal: 'original-staging', deviceId: 'old-test-device'
    }
  };
  await writeFile(credentialsFile, JSON.stringify(initial), { mode: 0o600 });
  try {
    await run({ dir, credentialsFile, receiptsDirectory, initial });
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
}

test('new friends each get a separate randomly generated person identity', () =>
  fixture(async ({ credentialsFile, receiptsDirectory }) => {
    const one = await enrollStagingDevice({
      mode: 'new-person', credentialsFile, receiptsDirectory
    });
    const two = await enrollStagingDevice({
      mode: 'new-person', credentialsFile, receiptsDirectory
    });
    const a = JSON.parse(await readFile(one.receiptFile, 'utf8'));
    const b = JSON.parse(await readFile(two.receiptFile, 'utf8'));
    const bindings = JSON.parse(await readFile(credentialsFile, 'utf8'));
    assert.notEqual(a.principal, b.principal);
    assert.notEqual(a.deviceId, b.deviceId);
    assert.notEqual(a.token, b.token);
    assert.ok(a.token.length >= 64);
    assert.deepEqual(bindings[a.token], { principal: a.principal, deviceId: a.deviceId });
    assert.deepEqual(bindings[b.token], { principal: b.principal, deviceId: b.deviceId });
    assert.equal((await lstat(credentialsFile)).mode & 0o777, 0o600);
    assert.equal((await lstat(one.receiptFile)).mode & 0o777, 0o600);
    assert.equal((await lstat(receiptsDirectory)).mode & 0o777, 0o700);
  })
);

test('linking second device uses existing person but unique device credentials', () =>
  fixture(async ({ credentialsFile, receiptsDirectory }) => {
    const first = await enrollStagingDevice({
      mode: 'new-person', credentialsFile, receiptsDirectory
    });
    const second = await enrollStagingDevice({
      mode: 'link-device', credentialsFile, receiptsDirectory,
      existingReceiptFile: first.receiptFile
    });
    const a = JSON.parse(await readFile(first.receiptFile, 'utf8'));
    const b = JSON.parse(await readFile(second.receiptFile, 'utf8'));
    assert.equal(a.principal, b.principal);
    assert.notEqual(a.deviceId, b.deviceId);
    assert.notEqual(a.token, b.token);
  })
);

test('separate person token cannot read another person replay; linked devices can', () =>
  fixture(async ({ dir, credentialsFile, receiptsDirectory }) => {
    const first = await enrollStagingDevice({
      mode: 'new-person', credentialsFile, receiptsDirectory
    });
    const linked = await enrollStagingDevice({
      mode: 'link-device', credentialsFile, receiptsDirectory,
      existingReceiptFile: first.receiptFile
    });
    const stranger = await enrollStagingDevice({
      mode: 'new-person', credentialsFile, receiptsDirectory
    });
    const a = JSON.parse(await readFile(first.receiptFile, 'utf8'));
    const b = JSON.parse(await readFile(linked.receiptFile, 'utf8'));
    const c = JSON.parse(await readFile(stranger.receiptFile, 'utf8'));
    const store = new RewatchStore(join(dir, 'db.json'));
    await store.init();
    const server = makeServer(store, JSON.parse(await readFile(credentialsFile, 'utf8')));
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const url = 'http://127.0.0.1:' + server.address().port;
    const request = async (token, method = 'GET', body = undefined) => {
      const response = await fetch(url + '/v1/rewatches' + (method === 'POST' ? '/events' : ''), {
        method, headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' },
        body: body == null ? undefined : JSON.stringify(body)
      });
      return { status: response.status, body: await response.json() };
    };
    try {
      const result = await request(a.token, 'POST', {
        eventId: 'e1', type: 'START', deviceId: a.deviceId,
        sessionId: 's1', canonicalSeriesId: 'imdb:tt0386676',
        expectedRevision: 0,
        session: {
          sessionId: 's1', canonicalSeriesId: 'imdb:tt0386676',
          episode: { season: 2, episode: 3 },
          phase: 'PLAYING', positionMs: 0, durationMs: 0,
          lastCompletedEpisode: null
        }
      });
      assert.equal(result.status, 200);
      assert.equal((await request(b.token)).body.items.length, 1);
      assert.equal((await request(c.token)).body.items.length, 0);
    } finally {
      await new Promise(resolve => server.close(resolve));
    }
  })
);

test('unsafe credential permissions prevent enrollment', () =>
  fixture(async ({ credentialsFile, receiptsDirectory }) => {
    await chmod(credentialsFile, 0o644);
    await assert.rejects(enrollStagingDevice({
      mode: 'new-person', credentialsFile, receiptsDirectory
    }), /non-private/);
  })
);

test('linking rejects invented or unbound receipts', () =>
  fixture(async ({ dir, credentialsFile, receiptsDirectory }) => {
    const forged = join(dir, 'invented.json');
    await writeFile(forged, JSON.stringify({
      principal: 'unknown', deviceId: 'fake', token: 'fake'
    }), { mode: 0o600 });
    await assert.rejects(enrollStagingDevice({
      mode: 'link-device', credentialsFile, receiptsDirectory,
      existingReceiptFile: forged
    }), /does not identify an enrolled device/);
  })
);

test('duplicate device identities across accounts are rejected at startup', () => {
  const db = new RewatchStore('/tmp/rewatch-no-write');
  assert.throws(() => makeServer(db, {
    '0123456789abcdef0123456789abcdef': {
      principal: 'one', deviceId: 'duplicate'
    },
    'abcdef0123456789abcdef0123456789': {
      principal: 'two', deviceId: 'duplicate'
    }
  }), /Duplicate device identity/);
});
