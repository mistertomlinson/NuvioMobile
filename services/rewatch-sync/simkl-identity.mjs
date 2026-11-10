const USER_SETTINGS_URL = 'https://api.simkl.com/users/settings';
const VALID_TOKEN = /^[\x21-\x7e]{20,1024}$/;
const VALID_CLIENT = /^[A-Za-z0-9_-]{6,200}$/;

export function verifiedAccountId(settings) {
  const raw = settings?.account?.id;
  if (typeof raw === 'number') {
    return Number.isSafeInteger(raw) && raw > 0 ? String(raw) : null;
  }
  if (typeof raw === 'string' && /^[1-9][0-9]{0,15}$/.test(raw)) {
    const asNumber = Number(raw);
    return Number.isSafeInteger(asNumber) ? String(asNumber) : null;
  }
  return null;
}

/**
 * Server-side proof of existing Simkl login.
 *
 * Every invocation checks Simkl itself. An accountId in client JSON is NEVER
 * trusted. No tokens are logged, retained or added to an error message.
 * fetchImpl is injected so tests cannot contact real Simkl.
 */
export function makeSimklVerifier({
  allowedClientIds,
  fetchImpl = fetch,
  timeoutMs = 6000
}) {
  if (!Array.isArray(allowedClientIds) || allowedClientIds.length === 0 ||
      allowedClientIds.some(x => typeof x !== 'string' || !VALID_CLIENT.test(x))) {
    throw Error('Explicit allowed Simkl app client IDs required');
  }
  if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 100 || timeoutMs > 10000) {
    throw Error('Invalid Simkl verification timeout');
  }
  const allowed = new Set(allowedClientIds);
  return async function verify({ clientId, accessToken }) {
    if (typeof clientId !== 'string' || !allowed.has(clientId)) {
      return { ok: false, status: 403, error: 'client_not_allowed' };
    }
    if (typeof accessToken !== 'string' || !VALID_TOKEN.test(accessToken)) {
      return { ok: false, status: 401, error: 'invalid_simkl_token' };
    }

    // Fixed URL, no user-supplied upstream origin, no account ID in request.
    const url = new URL(USER_SETTINGS_URL);
    url.searchParams.set('client_id', clientId);
    url.searchParams.set('app-name', 'nuvio-rewatch-sync');
    url.searchParams.set('app-version', '0.1.0');
    try {
      const response = await fetchImpl(url, {
        method: 'POST', redirect: 'manual',
        headers: {
          Authorization: 'Bearer ' + accessToken,
          'simkl-api-key': clientId,
          'Content-Type': 'application/json',
          'User-Agent': 'nuvio-rewatch-sync/0.1.0',
          Accept: 'application/json'
        },
        signal: AbortSignal.timeout(timeoutMs)
      });
      if (response.status === 401 || response.status === 403) {
        return { ok: false, status: 401, error: 'invalid_simkl_token' };
      }
      if (response.status !== 200) {
        return { ok: false, status: 503, error: 'simkl_unavailable' };
      }
      const text = await response.text();
      if (text.length > 16_384) {
        return { ok: false, status: 502, error: 'simkl_invalid_response' };
      }
      let settings;
      try { settings = JSON.parse(text); }
      catch { return { ok: false, status: 502, error: 'simkl_invalid_response' }; }
      const accountId = verifiedAccountId(settings);
      if (!accountId) {
        return { ok: false, status: 502, error: 'simkl_account_missing' };
      }
      return { ok: true, principal: 'simkl:' + accountId };
    } catch {
      return { ok: false, status: 503, error: 'simkl_unavailable' };
    }
  };
}
