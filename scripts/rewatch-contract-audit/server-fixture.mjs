// Isolated local contract fixture. It never reads live data or Simkl credentials.
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const source = resolve(process.argv[2]);
const { RewatchStore } = await import(pathToFileURL(join(source, 'store.mjs')));
const { makeServer } = await import(pathToFileURL(join(source, 'server.mjs')));
const dir = await mkdtemp(join(tmpdir(), 'nuvio-contract-audit-'));
const store = new RewatchStore(join(dir, 'rewatches.json'));
await store.init();
const session = {
  sessionId: 'audit-session', canonicalSeriesId: 'tt0386676',
  episode: { season: 5, episode: 26 }, positionMs: 0, durationMs: 0,
  lastCompletedEpisode: { season: 5, episode: 25 }, phase: 'NEXT_UP'
};
// Produce revision 666 via real store transitions; no private DB injection.
const initial = { ...session, phase: 'PLAYING', lastCompletedEpisode: null };
for (let revision = 0; revision < 664; revision++) {
  const result = await store.apply('simkl:12003', {
    eventId: `seed-${revision}`, deviceId: 'simkl-device:seed',
    canonicalSeriesId: session.canonicalSeriesId, sessionId: session.sessionId,
    expectedRevision: revision,
    ...(revision === 0 ? { type: 'IMPORT_SNAPSHOT', session: initial }
      : { type: 'PROGRESS', episode: initial.episode, positionMs: revision * 1000, durationMs: 1300000 })
  });
  if (result.status !== 200) throw Error('Fixture seed rejected');
}
// The final completion produces the exact reported S5E26 NEXT_UP cursor.
// First reposition to E25, then complete it, starting at revision 664.
const current = store.get('simkl:12003', 'tt0386676');
const move = await store.apply('simkl:12003', {
  eventId: 'seed-e25', deviceId: 'simkl-device:seed', canonicalSeriesId: session.canonicalSeriesId,
  sessionId: session.sessionId, expectedRevision: current.revision,
  type: 'REPOSITION', episode: { season: 5, episode: 25 }
});
if (move.status !== 200) throw Error('Fixture reposition rejected');
const completed = await store.apply('simkl:12003', {
  eventId: 'seed-next-up', deviceId: 'simkl-device:seed', canonicalSeriesId: session.canonicalSeriesId,
  sessionId: session.sessionId, expectedRevision: move.body.revision,
  type: 'COMPLETE_EPISODE', episode: { season: 5, episode: 25 },
  resultPhase: 'NEXT_UP', nextEpisode: { season: 5, episode: 26 }
});
if (completed.status !== 200) throw Error('Fixture completion rejected');
const server = makeServer(store, {
  ['a'.repeat(64)]: { principal: 'simkl:12003', deviceId: 'simkl-device:ipad-audit' },
  ['b'.repeat(64)]: { principal: 'simkl:12003', deviceId: 'simkl-device:tv-audit' },
  ['c'.repeat(64)]: { principal: 'simkl:99999', deviceId: 'simkl-device:other-audit' }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
console.log(JSON.stringify({ origin: `http://127.0.0.1:${server.address().port}`, revision: completed.body.revision }));
async function stop() {
  await new Promise(resolve => server.close(resolve));
  await rm(dir, { recursive: true, force: true });
  process.exit(0);
}
process.on('SIGTERM', stop);
process.on('SIGINT', stop);
