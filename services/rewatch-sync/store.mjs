import { createHash, randomUUID } from 'node:crypto';
import { mkdir, open, readFile, rename } from 'node:fs/promises';
import { dirname } from 'node:path';

const id = x => typeof x === 'string' && x.length > 0 && x.length <= 160;
const nonnegative = x => Number.isSafeInteger(x) && x >= 0;
const positive = x => Number.isSafeInteger(x) && x > 0;
const episode = x => x && positive(x.season) && positive(x.episode);
const phases = new Set(['PLAYING', 'NEXT_UP', 'AWAITING_METADATA', 'FINISHED']);
const VALID_EVENTS = new Set(['START', 'PROGRESS', 'COMPLETE_EPISODE', 'STOP']);
const safeKey = (principal, series) => JSON.stringify([principal, series]);
const hashBody = value => createHash('sha256').update(JSON.stringify(value)).digest('hex');
const fail = (status, error) => ({ status, body: { error } });
const ok = (session, revision) => ({ status: 200, body: { schemaVersion: 1, revision, session } });
const copy = x => structuredClone(x);

function sanitizeSession(s) {
  if (!s || !id(s.sessionId) || !id(s.canonicalSeriesId) || !episode(s.episode) ||
      !nonnegative(s.positionMs) || !nonnegative(s.durationMs) ||
      (s.lastCompletedEpisode != null && !episode(s.lastCompletedEpisode)) ||
      !phases.has(s.phase)) return null;
  return {
    sessionId: s.sessionId, canonicalSeriesId: s.canonicalSeriesId,
    episode: { season: s.episode.season, episode: s.episode.episode },
    positionMs: s.positionMs, durationMs: s.durationMs,
    lastCompletedEpisode: s.lastCompletedEpisode == null ? null : {
      season: s.lastCompletedEpisode.season, episode: s.lastCompletedEpisode.episode
    }, phase: s.phase
  };
}
function equalEpisode(a, b) { return a?.season === b?.season && a?.episode === b?.episode; }
function successorIsPlausible(previous, next) {
  return next == null || (episode(next) && (
    (next.season === previous.season && next.episode > previous.episode) ||
    (next.season > previous.season && next.episode === 1)
  ));
}

/** One writer per database path; deliberately single-process. */
export class RewatchStore {
  constructor(file) {
    this.file = file;
    this.data = { schemaVersion: 1, records: {}, events: {} };
    this.chain = Promise.resolve();
  }
  async init() {
    try {
      const data = JSON.parse(await readFile(this.file, 'utf8'));
      if (data.schemaVersion !== 1 || typeof data.records !== 'object' ||
          Array.isArray(data.records) || typeof data.events !== 'object' || Array.isArray(data.events)) {
        throw new Error('Unsupported or corrupt sync database');
      }
      this.data = data;
    } catch (e) {
      if (e.code !== 'ENOENT') throw e;
      await mkdir(dirname(this.file), { recursive: true });
    }
  }
  async persist(next) {
    const temp = this.file + '.' + randomUUID() + '.tmp';
    const fh = await open(temp, 'wx', 0o600);
    try {
      await fh.writeFile(JSON.stringify(next));
      await fh.sync();
    } finally { await fh.close(); }
    await rename(temp, this.file);
    const dir = await open(dirname(this.file), 'r');
    try { await dir.sync(); } finally { await dir.close(); }
  }
  runExclusive(fn) {
    const result = this.chain.then(fn);
    this.chain = result.catch(() => {});
    return result;
  }
  list(principal) {
    return Object.entries(this.data.records).filter(([key]) => {
      try { return JSON.parse(key)[0] === principal; } catch { return false; }
    }).map(([, rec]) => copy(rec));
  }
  get(principal, series) {
    const value = this.data.records[safeKey(principal, series)];
    return value ? copy(value) : null;
  }
  apply(principal, event) {
    return this.runExclusive(async () => {
      if (!event || !id(event.eventId) || !id(event.deviceId) ||
          !id(event.canonicalSeriesId) || !id(event.sessionId) ||
          !nonnegative(event.expectedRevision) || !VALID_EVENTS.has(event.type)) {
        return fail(400, 'invalid_event');
      }
      // Idempotency keys are scoped by authenticated principal, not device or show.
      const eventKey = safeKey(principal, event.eventId);
      const fingerprint = hashBody(event);
      const prior = this.data.events[eventKey];
      if (prior) return prior.fingerprint === fingerprint ? copy(prior.result) : fail(409, 'event_id_reused');
      const key = safeKey(principal, event.canonicalSeriesId);
      const stored = this.data.records[key] ?? null;
      const revision = stored?.revision ?? 0;
      if (event.expectedRevision !== revision) return {
        status: 409, body: { error: 'revision_conflict', current: copy(stored) }
      };
      if (event.type === 'START' && stored && stored.session.phase !== 'FINISHED') {
        return fail(409, 'active_session_exists');
      }
      if (event.type !== 'START' && (!stored || stored.session.sessionId !== event.sessionId)) {
        return fail(409, 'session_mismatch');
      }
      let session;
      if (event.type === 'START') {
        session = sanitizeSession(event.session);
        if (!session || session.sessionId !== event.sessionId ||
            session.canonicalSeriesId !== event.canonicalSeriesId ||
            session.phase !== 'PLAYING' || session.positionMs !== 0 ||
            session.lastCompletedEpisode !== null) return fail(400, 'invalid_start');
      } else {
        session = copy(stored.session);
        if (event.type === 'PROGRESS') {
          if (!['PLAYING', 'NEXT_UP'].includes(session.phase) || !equalEpisode(event.episode, session.episode) ||
              !nonnegative(event.positionMs) || !nonnegative(event.durationMs)) return fail(409, 'invalid_progress');
          if (session.phase === 'NEXT_UP' && event.positionMs < 1000) return fail(409, 'next_up_not_started');
          session = { ...session, phase: 'PLAYING', positionMs: event.positionMs, durationMs: event.durationMs };
        } else if (event.type === 'COMPLETE_EPISODE') {
          if (session.phase !== 'PLAYING' || !equalEpisode(event.episode, session.episode) ||
              !['NEXT_UP', 'AWAITING_METADATA', 'FINISHED'].includes(event.resultPhase)) {
            return fail(409, 'invalid_completion');
          }
          if (event.resultPhase === 'NEXT_UP' && !episode(event.nextEpisode)) return fail(400, 'missing_successor');
          if (!successorIsPlausible(session.episode, event.nextEpisode)) return fail(409, 'invalid_successor');
          if (event.resultPhase !== 'NEXT_UP' && event.nextEpisode != null) return fail(400, 'unexpected_successor');
          const completed = session.episode;
          session = { ...session, lastCompletedEpisode: completed,
            episode: event.resultPhase === 'NEXT_UP' ? { ...event.nextEpisode } : completed,
            phase: event.resultPhase, positionMs: 0, durationMs: 0 };
        } else if (event.type === 'STOP') {
          session.phase = 'FINISHED';
        }
      }
      const result = ok(session, revision + 1);
      const next = copy(this.data);
      next.records[key] = { revision: revision + 1, session };
      next.events[eventKey] = { fingerprint, result };
      await this.persist(next);
      this.data = next;
      return copy(result);
    });
  }
}
