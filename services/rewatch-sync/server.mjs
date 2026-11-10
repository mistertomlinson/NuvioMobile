import http from 'node:http';
import { createHash, timingSafeEqual } from 'node:crypto';
import { RewatchStore } from './store.mjs';
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

export function makeServer(store, config) {
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
    const identity = principalFor(req, credentials);
    if (!identity) return json(res, 401, { error: 'unauthorized' });
    const principal = identity.principal;
    const path = (req.url ?? '').split('?')[0];
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
  makeServer(store, config).listen(port, host, () => {
    console.log('Rewatch staging listening on ' + host + ':' + port);
  });
}
