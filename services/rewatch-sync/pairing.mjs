import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { mkdir, open, readFile, rename } from 'node:fs/promises';
import { dirname } from 'node:path';

const SECRET = /^[a-f0-9]{64}$/i;
const digest = value => createHash('sha256').update(value).digest('hex');
const secret = () => randomBytes(32).toString('hex');
const clone = value => structuredClone(value);

function newState() {
  return { schemaVersion: 1, invitations: {}, devices: {} };
}

/**
 * Prototype pairing state. Entirely independent of Simkl.
 * This deliberately has NO public HTTP enrollment or invitation-creation endpoint.
 *
 * A server-side operator must issue a first-person invite. An existing authenticated
 * device can authorize a linked-device invite. All invitation secrets and issued
 * bearer credentials are returned ONLY once; disk stores their SHA-256 hashes.
 *
 * Single PROCESS owner per file, just like the current rewatch staging store.
 */
export class PairingRegistry {
  constructor(file, { now = () => Date.now(), inviteLifetimeMs = 600000 } = {}) {
    if (!Number.isSafeInteger(inviteLifetimeMs) || inviteLifetimeMs < 60000 ||
        inviteLifetimeMs > 900000) throw Error('Unsafe invitation expiry');
    this.file = file;
    this.now = now;
    this.inviteLifetimeMs = inviteLifetimeMs;
    this.state = newState();
    this.pending = Promise.resolve();
  }

  async init() {
    try {
      const parsed = JSON.parse(await readFile(this.file, 'utf8'));
      if (parsed?.schemaVersion !== 1 ||
          !parsed.invitations || typeof parsed.invitations !== 'object' ||
          Array.isArray(parsed.invitations) ||
          !parsed.devices || typeof parsed.devices !== 'object' ||
          Array.isArray(parsed.devices)) throw Error('Invalid pairing state');
      this.state = parsed;
    } catch (error) {
      if (error.code !== 'ENOENT') throw error;
      await mkdir(dirname(this.file), { recursive: true, mode: 0o700 });
    }
  }

  serialize(operation) {
    const result = this.pending.then(operation);
    this.pending = result.catch(() => {});
    return result;
  }

  async persist(next) {
    const path = this.file + '.' + randomUUID() + '.tmp';
    const file = await open(path, 'wx', 0o600);
    try {
      await file.writeFile(JSON.stringify(next) + '\n');
      await file.sync();
    } finally {
      await file.close();
    }
    await rename(path, this.file);
    const directory = await open(dirname(this.file), 'r');
    try { await directory.sync(); } finally { await directory.close(); }
  }

  identify(token) {
    if (typeof token !== 'string' || !SECRET.test(token)) return null;
    const item = this.state.devices[digest(token)];
    if (!item || item.revokedAt !== null) return null;
    return { principal: item.principal, deviceId: item.deviceId };
  }

  async createNewPersonInvite() {
    // Privileged operator-only API: never expose as unauthenticated HTTP.
    return this.serialize(async () => this.issue('person:' + randomUUID()));
  }

  async createLinkedDeviceInvite(existingDeviceToken) {
    return this.serialize(async () => {
      const identity = this.identify(existingDeviceToken);
      if (!identity) return { ok: false, error: 'unauthorized' };
      return this.issue(identity.principal);
    });
  }

  async issue(principal) {
    const code = secret();
    const expiry = this.now() + this.inviteLifetimeMs;
    const next = clone(this.state);
    // Eliminate expired/used codes rather than accumulating indefinitely.
    for (const [key, invite] of Object.entries(next.invitations)) {
      if (invite.usedAt !== null || invite.expiresAt <= this.now()) {
        delete next.invitations[key];
      }
    }
    next.invitations[digest(code)] = {
      principal, expiresAt: expiry, usedAt: null
    };
    await this.persist(next);
    this.state = next;
    return { ok: true, invitation: code, expiresAtEpochMs: expiry };
  }

  async redeem(invitation) {
    return this.serialize(async () => {
      if (typeof invitation !== 'string' || !SECRET.test(invitation)) {
        return { ok: false, error: 'invalid_or_expired_invitation' };
      }
      const key = digest(invitation);
      const record = this.state.invitations[key];
      if (!record || record.usedAt !== null || record.expiresAt <= this.now()) {
        return { ok: false, error: 'invalid_or_expired_invitation' };
      }
      const token = secret();
      const deviceId = 'device:' + randomUUID();
      const next = clone(this.state);
      next.invitations[key].usedAt = this.now();
      next.devices[digest(token)] = {
        deviceId, principal: record.principal, revokedAt: null
      };
      await this.persist(next);
      this.state = next;
      // Token never goes to disk or logs. Client must store in secure key storage.
      return {
        ok: true, credential: { token, deviceId },
        principal: record.principal
      };
    });
  }

  async revokeDevice(actingDeviceToken, targetDeviceId) {
    return this.serialize(async () => {
      const identity = this.identify(actingDeviceToken);
      if (!identity) return { ok: false, error: 'unauthorized' };
      if (typeof targetDeviceId !== 'string') return { ok: false, error: 'not_found' };
      const [key, target] = Object.entries(this.state.devices).find(([, device]) =>
        device.principal === identity.principal && device.deviceId === targetDeviceId &&
        device.revokedAt === null
      ) ?? [];
      if (!target) return { ok: false, error: 'not_found' };
      const next = clone(this.state);
      next.devices[key].revokedAt = this.now();
      await this.persist(next);
      this.state = next;
      return { ok: true };
    });
  }
}
