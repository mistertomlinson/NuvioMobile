import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { lstat, mkdir, open, readFile, rename } from 'node:fs/promises';
import { dirname } from 'node:path';

const hash = value => createHash('sha256').update(value).digest('hex');
const VALID = /^[a-f0-9]{64}$/;
const clone = value => structuredClone(value);

/** Scoped, revocable and expiring replay tokens, entirely separate from Simkl tokens. */
export class SimklReplayCredentials {
  constructor(file, { now = () => Date.now(), lifetimeMs = 24 * 60 * 60 * 1000 } = {}) {
    if (!Number.isSafeInteger(lifetimeMs) || lifetimeMs < 60_000 ||
        lifetimeMs > 48 * 60 * 60 * 1000) {
      throw Error('Invalid replay credential lifetime');
    }
    this.file = file;
    this.now = now;
    this.lifetimeMs = lifetimeMs;
    this.data = { schemaVersion: 1, devices: {} };
    this.chain = Promise.resolve();
  }

  async init() {
    try {
      const status = await lstat(this.file);
      if (!status.isFile() || (status.mode & 0o077) !== 0) {
        throw Error('Replay credential store must be a private regular file');
      }
      const obj = JSON.parse(await readFile(this.file, 'utf8'));
      if (obj.schemaVersion !== 1 || !obj.devices ||
          typeof obj.devices !== 'object' || Array.isArray(obj.devices) ||
          Object.entries(obj.devices).some(([key, item]) =>
            !/^[a-f0-9]{64}$/.test(key) || !item ||
            !/^simkl:[1-9][0-9]{0,15}$/.test(item.principal) ||
            typeof item.deviceId !== 'string' ||
            !item.deviceId.startsWith('simkl-device:') ||
            typeof item.revoked !== 'boolean' ||
            !Number.isSafeInteger(item.expiresAtEpochMs))) {
        throw Error('Unsupported or corrupted replay credential store');
      }
      this.data = obj;
    } catch (error) {
      if (error.code !== 'ENOENT') throw error;
      await mkdir(dirname(this.file), { recursive: true, mode: 0o700 });
    }
  }

  serialize(fn) {
    const result = this.chain.then(fn);
    this.chain = result.catch(() => {});
    return result;
  }

  async persist(next) {
    const temp = this.file + '.' + randomUUID() + '.tmp';
    const handle = await open(temp, 'wx', 0o600);
    try {
      await handle.writeFile(JSON.stringify(next) + '\n');
      await handle.sync();
    } finally { await handle.close(); }
    await rename(temp, this.file);
    const directory = await open(dirname(this.file), 'r');
    try { await directory.sync(); } finally { await directory.close(); }
  }

  identify(token) {
    if (typeof token !== 'string' || !VALID.test(token)) return null;
    const record = this.data.devices[hash(token)];
    if (!record || record.revoked || record.expiresAtEpochMs <= this.now()) return null;
    return { principal: record.principal, deviceId: record.deviceId };
  }

  async issue(principal) {
    if (typeof principal !== 'string' || !/^simkl:[1-9][0-9]{0,15}$/.test(principal)) {
      throw Error('Only Simkl-verified principals may obtain credentials');
    }
    return this.serialize(async () => {
      const token = randomBytes(32).toString('hex');
      const deviceId = 'simkl-device:' + randomUUID();
      const expiresAtEpochMs = this.now() + this.lifetimeMs;
      const next = clone(this.data);
      for (const [key, item] of Object.entries(next.devices)) {
        if (item.revoked || item.expiresAtEpochMs <= this.now()) delete next.devices[key];
      }
      next.devices[hash(token)] = {
        principal, deviceId, revoked: false, expiresAtEpochMs
      };
      await this.persist(next);
      this.data = next;
      return { token, deviceId, expiresAtEpochMs };
    });
  }

  async revoke(token) {
    return this.serialize(async () => {
      if (typeof token !== 'string' || !VALID.test(token) ||
          !this.data.devices[hash(token)]) return false;
      const next = clone(this.data);
      next.devices[hash(token)].revoked = true;
      await this.persist(next);
      this.data = next;
      return true;
    });
  }
}
