import http from 'node:http';
import { createHash, timingSafeEqual } from 'node:crypto';
import { RewatchStore } from './store.mjs';
import { PairingRegistry } from './pairing.mjs';
import { makeSimklVerifier } from './simkl-identity.mjs';
import { SimklReplayCredentials, isValidClientInstallationId } from './simkl-sessions.mjs';
import { readFile, stat } from 'node:fs/promises';

const digest = s => createHash('sha256').update(s).digest();
const json = (res, status, data) => {
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff'
  });
  res.end(JSON.stringify(data));
};
function principalFor(req, credentials) {
  const match = /^Bearer ([A-Za-z0-9_.~-]+)$/.exec(req.headers.authorization ?? '');
  if (!match) return null;
  const given = digest(match[1]);
  for (const credential of credentials) {
    if (credential.digest.length === given.length &&
        timingSafeEqual(credential.digest, given)) {
      return { principal: credential.principal, deviceId: credential.deviceId };
    }
  }
  return null;
}

export function makeServer(store, config, {
  pairingRegistry = null,
  simklVerifier = null,
  simklCredentials = null
} = {}) {
  if (Boolean(simklVerifier) !== Boolean(simklCredentials)) {
    throw Error('Simkl verifier and credential registry must be configured together');
  }
  // Each device has its own credential and bound server-side person identity.
  const credentials = Object.entries(config).map(([token, binding]) => ({
    digest: digest(token), principal: binding?.principal,
    deviceId: binding?.deviceId, token
  }));
  if (!credentials.length || credentials.some(x =>
    x.token.length < 32 || typeof x.principal !== 'string' || !x.principal ||
    typeof x.deviceId !== 'string' || !x.deviceId
  )) {
    throw Error('Provision a random 32+-character token with principal and deviceId per device');
  }
  // Device identities must not be bound to multiple people or tokens.
  if (new Set(credentials.map(x => x.deviceId)).size !== credentials.length) {
    throw Error('Duplicate device identity in staging credentials');
  }
  return http.createServer(async (req, res) => {
    const path = (req.url ?? '').split('?')[0];
    // The pairing API is disabled by default. Existing Caddy staging hostname
    // remains 503-only; do not route public traffic here yet.
    async function readSmallJson() {
      let raw = '';
      for await (const chunk of req) {
        raw += chunk;
        if (raw.length > 2048) return { error: 'payload_too_large' };
      }
      try { return { value: JSON.parse(raw) }; }
      catch { return { error: 'invalid_json' }; }
    }
    // Optional automatic upgrade bootstrap. Disabled until explicitly configured.
    // Existing Simkl sign-in is the ONLY user login.
    if (simklVerifier && req.method === 'POST' && path === '/v1/simkl/bootstrap') {
      const input = await readSmallJson();
      if (input.error) {
        return json(res, input.error === 'payload_too_large' ? 413 : 400,
          { error: input.error });
      }
      // Client creates this random UUID once per local Nuvio profile.
      // It is NOT evidence of a Simkl identity; we verify Simkl independently.
      const installationId = input.value?.installationId;
      if (!isValidClientInstallationId(installationId)) {
        return json(res, 400, { error: 'invalid_installation_id' });
      }
      const verified = await simklVerifier({
        clientId: input.value?.clientId,
        accessToken: input.value?.accessToken
      });
      if (!verified.ok) {
        return json(res, verified.status, { error: verified.error });
      }
      try {
        const issued = await simklCredentials.issue(verified.principal, installationId);
        return json(res, 201, {
          schemaVersion: 1,
          // Identity comes from verified Simkl /users/settings, never the app.
          simklAccountId: verified.principal.slice('simkl:'.length),
          token: issued.token,
          deviceId: issued.deviceId,
          expiresAtEpochMs: issued.expiresAtEpochMs
        });
      } catch {
        return json(res, 503, { error: 'credential_store_unavailable' });
      }
    }

    if (pairingRegistry && req.method === 'POST' && path === '/v1/pairing/redeem') {
      const input = await readSmallJson();
      if (input.error) {
        return json(res, input.error === 'payload_too_large' ? 413 : 400,
          { error: input.error });
      }
      const result = await pairingRegistry.redeem(input.value?.invitation);
      return result.ok
        ? json(res, 201, {
            schemaVersion: 1, deviceId: result.credential.deviceId,
            token: result.credential.token
          })
        : json(res, 401, { error: 'invalid_or_expired_invitation' });
    }

    const legacyIdentity = principalFor(req, credentials);
    const authorization = /^Bearer ([A-Za-z0-9_.~-]+)$/.exec(req.headers.authorization ?? '');
    const registryIdentity = authorization && pairingRegistry
      ? pairingRegistry.identify(authorization[1]) : null;
    const simklIdentity = authorization && simklCredentials
      ? simklCredentials.identify(authorization[1]) : null;
    const identity = simklIdentity ?? registryIdentity ?? legacyIdentity;
    if (!identity) return json(res, 401, { error: 'unauthorized' });
    const principal = identity.principal;

    if (simklCredentials && req.method === 'POST' && path === '/v1/simkl/revoke') {
      if (!simklIdentity) return json(res, 403, { error: 'simkl_replay_credential_required' });
      await simklCredentials.revoke(authorization[1]);
      return json(res, 200, { status: 'revoked' });
    }

    if (pairingRegistry && req.method === 'POST' && path === '/v1/pairing/invite-linked') {
      // Only a credential issued by pairing may authorize linked-device invites.
      // Legacy static tokens cannot silently enroll others.
      if (!registryIdentity) return json(res, 403, { error: 'pairing_identity_required' });
      const result = await pairingRegistry.createLinkedDeviceInvite(authorization[1]);
      return result.ok
        ? json(res, 201, {
            schemaVersion: 1, invitation: result.invitation,
            expiresAtEpochMs: result.expiresAtEpochMs
          })
        : json(res, 401, { error: 'unauthorized' });
    }
    if (req.method === 'GET' && path === '/v1/rewatches') {
      return json(res, 200, { schemaVersion: 1, items: store.list(principal) });
    }
    if (req.method === 'GET' && path.startsWith('/v1/rewatches/')) {
      const sessionId = decodeURIComponent(path.slice('/v1/rewatches/'.length));
      const item = store.list(principal).find(x => x.session.sessionId === sessionId);
      return item
        ? json(res, 200, { schemaVersion: 1, ...item })
        : json(res, 404, { error: 'not_found' });
    }
    if (req.method !== 'POST' || path !== '/v1/rewatches/events') {
      return json(res, 404, { error: 'not_found' });
    }
    let raw = '';
    try {
      for await (const chunk of req) {
        raw += chunk;
        if (raw.length > 16_384) return json(res, 413, { error: 'payload_too_large' });
      }
      const event = JSON.parse(raw);
      if (event?.deviceId !== identity.deviceId) {
        return json(res, 403, { error: 'device_mismatch' });
      }
      const result = await store.apply(principal, event);
      return json(res, result.status, result.body);
    } catch (error) {
      const syntax = error instanceof SyntaxError;
      return json(res, syntax ? 400 : 500, {
        error: syntax ? 'invalid_json' : 'storage_unavailable'
      });
    }
  });
}

if (process.argv[1] && import.meta.url === new URL('file://' + process.argv[1]).href) {
  const credentialsFile = process.env.REWATCH_CREDENTIALS_FILE;
  if (!credentialsFile) throw Error('REWATCH_CREDENTIALS_FILE is required');
  const permissions = (await stat(credentialsFile)).mode & 0o777;
  if ((permissions & 0o077) !== 0) {
    throw Error('Credentials file must not be accessible to group/others');
  }
  const config = JSON.parse(await readFile(credentialsFile, 'utf8'));
  const store = new RewatchStore(process.env.REWATCH_DB_PATH || './data/rewatches.json');
  await store.init();
  const host = process.env.REWATCH_BIND || '127.0.0.1';
  if (!['127.0.0.1', '::1'].includes(host)) {
    throw Error('Staging listener must bind to loopback only');
  }
  const port = Number(process.env.REWATCH_PORT || '8765');
  if (!Number.isSafeInteger(port) || port < 1 || port > 65535) {
    throw Error('Invalid port');
  }
  // Pairing must be explicitly enabled, with its own private state file.
  // No public exposure is configured by this change.
  const pairingFile = process.env.REWATCH_PAIRING_DB_PATH;
  let pairingRegistry = null;
  if (pairingFile) {
    const { isAbsolute } = await import('node:path');
    if (!isAbsolute(pairingFile)) throw Error('Pairing state must have an absolute path');
    pairingRegistry = new PairingRegistry(pairingFile);
    await pairingRegistry.init();
  }
  // Optional zero-extra-login Simkl verification remains OFF by default.
  // All Simkl access tokens are used ephemerally and are never stored here.
  const enabled = process.env.REWATCH_SIMKL_BOOTSTRAP === '1';
  const pairedSettings = [
    process.env.REWATCH_SIMKL_CLIENT_IDS,
    process.env.REWATCH_SIMKL_CREDENTIALS_DB_PATH
  ];
  if (!enabled && pairedSettings.some(Boolean)) {
    throw Error('Simkl bootstrap is disabled: remove partial Simkl environment');
  }
  let simklVerifier = null;
  let simklCredentials = null;
  if (enabled) {
    const { isAbsolute } = await import('node:path');
    const ids = (process.env.REWATCH_SIMKL_CLIENT_IDS || '').split(',')
      .map(x => x.trim()).filter(Boolean);
    const database = process.env.REWATCH_SIMKL_CREDENTIALS_DB_PATH;
    if (!database || !isAbsolute(database)) {
      throw Error('Absolute private Simkl session database path required');
    }
    simklVerifier = makeSimklVerifier({ allowedClientIds: ids });
    simklCredentials = new SimklReplayCredentials(database);
    await simklCredentials.init();
  }
  makeServer(store, config, { pairingRegistry, simklVerifier, simklCredentials }).listen(port, host, () => {
    console.log('Rewatch staging listening on ' + host + ':' + port);
  });
}
