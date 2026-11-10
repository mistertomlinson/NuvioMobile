import { randomBytes, randomUUID } from 'node:crypto';
import { chmod, lstat, mkdir, open, readFile, rename, rm, writeFile } from 'node:fs/promises';
import { dirname, isAbsolute, join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const valid = value => typeof value === 'string' && value.length > 0 && value.length <= 160;

async function privateRegularFile(path) {
  const stat = await lstat(path);
  if (!stat.isFile() || (stat.mode & 0o077) !== 0) {
    throw Error('Refusing non-private file or symbolic link');
  }
}

async function readCredentials(path) {
  await privateRegularFile(path);
  const credentials = JSON.parse(await readFile(path, 'utf8'));
  if (!credentials || typeof credentials !== 'object' || Array.isArray(credentials) ||
      Object.entries(credentials).some(([token, owner]) =>
        token.length < 32 || !valid(owner?.principal) || !valid(owner?.deviceId)
      )) {
    throw Error('Invalid existing staging credentials');
  }
  const ids = Object.values(credentials).map(x => x.deviceId);
  if (new Set(ids).size !== ids.length) throw Error('Duplicate device bindings');
  return credentials;
}

/**
 * On-VPS, administrator-controlled enrollment. No HTTP enrollment endpoint.
 * Returns only the PRIVATE receipt path; neither token nor principal is logged.
 *
 * new-person: creates an independent server-side person identity.
 * link-device: adds another device using an existing private enrollment receipt.
 */
export async function enrollStagingDevice({
  mode, credentialsFile, receiptsDirectory, existingReceiptFile = null
}) {
  if (!['new-person', 'link-device'].includes(mode) ||
      !isAbsolute(credentialsFile) || !isAbsolute(receiptsDirectory) ||
      (mode === 'link-device' && !isAbsolute(existingReceiptFile || '')) ||
      (mode === 'new-person' && existingReceiptFile != null)) {
    throw Error('Invalid enrollment arguments');
  }

  // Restrict receipts to the same parent-owned private credentials tree.
  if (resolve(receiptsDirectory) !== resolve(dirname(credentialsFile), 'receipts')) {
    throw Error('Receipts must be under the private credentials directory');
  }
  const lockPath = credentialsFile + '.enroll.lock';
  const lock = await open(lockPath, 'wx', 0o600);
  let receiptFile = null;
  let tempFile = null;
  let configPublished = false;
  try {
    const credentials = await readCredentials(credentialsFile);
    let principal;
    if (mode === 'new-person') {
      principal = 'person:' + randomUUID();
    } else {
      await privateRegularFile(existingReceiptFile);
      const source = JSON.parse(await readFile(existingReceiptFile, 'utf8'));
      if (!valid(source.principal) || !valid(source.deviceId) ||
          !Object.values(credentials).some(x =>
            x.principal === source.principal && x.deviceId === source.deviceId
          )) {
        throw Error('Link receipt does not identify an enrolled device');
      }
      principal = source.principal;
    }
    const deviceId = 'device:' + randomUUID();
    const token = randomBytes(32).toString('hex');
    const receipt = { schemaVersion: 1, principal, deviceId, token };
    await mkdir(receiptsDirectory, { recursive: true, mode: 0o700 });
    const dirStat = await lstat(receiptsDirectory);
    if (!dirStat.isDirectory() || (dirStat.mode & 0o077) !== 0) {
      throw Error('Receipt directory must be private');
    }
    receiptFile = join(receiptsDirectory, deviceId + '.json');
    await writeFile(receiptFile, JSON.stringify(receipt) + '\n', { flag: 'wx', mode: 0o600 });
    await chmod(receiptFile, 0o600);

    tempFile = credentialsFile + '.' + randomUUID() + '.tmp';
    const next = { ...credentials, [token]: { principal, deviceId } };
    const handle = await open(tempFile, 'wx', 0o600);
    try {
      await handle.writeFile(JSON.stringify(next) + '\n');
      await handle.sync();
    } finally {
      await handle.close();
    }
    await rename(tempFile, credentialsFile);
    tempFile = null;
    configPublished = true;
    const directory = await open(dirname(credentialsFile), 'r');
    try { await directory.sync(); } finally { await directory.close(); }
    return { receiptFile, mode };
  } catch (error) {
    if (!configPublished && receiptFile) await rm(receiptFile, { force: true });
    throw error;
  } finally {
    if (tempFile) await rm(tempFile, { force: true });
    await lock.close();
    await rm(lockPath, { force: true });
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const [mode, existingReceiptFile] = process.argv.slice(2);
  const credentialsFile = process.env.REWATCH_CREDENTIALS_FILE;
  if (!credentialsFile) throw Error('REWATCH_CREDENTIALS_FILE required');
  const result = await enrollStagingDevice({
    mode, credentialsFile,
    receiptsDirectory: join(dirname(credentialsFile), 'receipts'),
    existingReceiptFile: mode === 'link-device' ? existingReceiptFile : null
  });
  // No secrets in stdout or Copy From VPS output.
  console.log('RESULT=DEVICE_ENROLLMENT_STAGED');
  console.log('RECEIPT_FILE=' + result.receiptFile);
  console.log('CREDENTIALS_RELOAD_REQUIRED=YES');
  console.log('SERVICE_RESTARTED=NO');
}
