import { DurableObject } from 'cloudflare:workers';
import { publicApiFailure } from './public-api-errors';
import {
  BUS_CACHE_TTL_MS, BUS_UPSTREAM_BUDGET, BUS_WINDOW_MS, BusSourceError,
  busSourceUrl, normalizeBusPayload, parseBusOperation, refreshBusPayload, type BusOperation,
} from './bus-model';

export interface BusEnv {
  SEOUL_BUS_KEY?: string;
  SEOUL_BUS_COORDINATOR?: DurableObjectNamespace<SeoulBusCoordinator>;
  IP_RATE_LIMITER: RateLimit;
}
export const BUS_COORDINATOR_NAME = 'seoul-bus-global-v1';
const ERROR_BACKOFF_MS = 10_000;
const SOURCE_TIMEOUT_MS = 5_000;
const MAX_BODY_BYTES = 1_048_576;
function upstreamRetrySeconds(value: string | null): number {
  if (value !== null) {
    const seconds = /^\d+$/.test(value.trim()) ? Number(value) : (Date.parse(value) - Date.now()) / 1_000;
    if (Number.isFinite(seconds) && seconds > 0 && seconds <= 172_800) return Math.ceil(seconds);
  }
  return 60;
}
interface BusResult { status: number; body: string; headers?: Record<string, string> }
const json = (body: unknown): BusResult => ({ status: 200, body: JSON.stringify(body) });
function fail(code: string, status: number, retryAfter?: number): BusResult {
  return { status, body: JSON.stringify({ error: { code, ...(retryAfter === undefined ? {} : {
    retryAfterSeconds: retryAfter, retryAt: new Date(Date.now() + retryAfter * 1_000).toISOString(),
  }) } }), headers: retryAfter === undefined ? {} : { 'Retry-After': String(retryAfter) } };
}
function response(result: BusResult, headers: Record<string, string> = {}): Response {
  return new Response(result.body, { status: result.status, headers: {
    'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff', ...result.headers, ...headers,
  } });
}

export async function handleBusRequest(request: Request, env: BusEnv): Promise<Response> {
  try {
    const url = new URL(request.url);
    if (!['/bus/stops', '/bus/routes', '/bus/arrivals'].includes(url.pathname)) return response(fail('NOT_FOUND', 404));
    if (!parseBusOperation(url)) return response(fail('BUS_INVALID_QUERY', 400));
    if (!env.SEOUL_BUS_KEY || !env.SEOUL_BUS_COORDINATOR || !env.IP_RATE_LIMITER) {
      return response(fail('SEOUL_BUS_NOT_CONFIGURED', 503));
    }
    const ip = request.headers.get('CF-Connecting-IP');
    if (!ip || ip.length > 64) return response(fail('CLIENT_IP_UNAVAILABLE', 503));
    let permitted: boolean;
    try { permitted = (await env.IP_RATE_LIMITER.limit({ key: ip })).success; }
    catch { return response(fail('RATE_LIMITER_UNAVAILABLE', 503, 60)); }
    if (!permitted) return response(fail('IP_RATE_LIMITED', 429, 60));
    const object = env.SEOUL_BUS_COORDINATOR.get(env.SEOUL_BUS_COORDINATOR.idFromName(BUS_COORDINATOR_NAME));
    return await object.fetch(new Request(`https://seoul-bus${url.pathname}${url.search}`));
  } catch { return response(fail('BUS_SERVICE_UNAVAILABLE', 503, 30)); }
}

/** One independent singleton ledger for Seoul bus services. Subway storage/migrations stay intact. */
export class SeoulBusCoordinator extends DurableObject<BusEnv> {
  private readonly pending = new Map<string, Promise<BusResult>>();

  constructor(ctx: DurableObjectState, env: BusEnv) {
    super(ctx, env);
    ctx.storage.sql.exec(`CREATE TABLE IF NOT EXISTS bus_upstream_attempts (
      id INTEGER PRIMARY KEY AUTOINCREMENT, service TEXT NOT NULL, attempted_at INTEGER NOT NULL
    )`);
    ctx.storage.sql.exec('CREATE INDEX IF NOT EXISTS bus_attempt_time ON bus_upstream_attempts(service, attempted_at)');
    ctx.storage.sql.exec(`CREATE TABLE IF NOT EXISTS bus_cache (
      cache_key TEXT PRIMARY KEY, payload TEXT NOT NULL, expires_at INTEGER NOT NULL
    )`);
    ctx.storage.sql.exec(`CREATE TABLE IF NOT EXISTS bus_failures (
      cache_key TEXT PRIMARY KEY, code TEXT NOT NULL, status INTEGER NOT NULL, retry_at INTEGER NOT NULL
    )`);
  }

  async fetch(request: Request): Promise<Response> {
    try {
      const operation = parseBusOperation(new URL(request.url));
      if (!operation) return response(fail('BUS_INVALID_QUERY', 400));
      const key = JSON.stringify(operation);
      const running = this.pending.get(key);
      if (running) return response(await running, { 'X-Cache': 'COALESCED' });
      const now = Date.now();
      const cached = this.ctx.storage.sql.exec<{ payload: string; expires_at: number }>(
        'SELECT payload, expires_at FROM bus_cache WHERE cache_key = ?', key).toArray()[0];
      if (cached && cached.expires_at > now) return response({ status: 200,
        body: refreshBusPayload(cached.payload, now) }, { 'X-Cache': 'HIT' });
      const previous = this.ctx.storage.sql.exec<{ code: string; status: number; retry_at: number }>(
        'SELECT code, status, retry_at FROM bus_failures WHERE cache_key = ?', key).toArray()[0];
      if (previous && previous.retry_at > now) return response(fail(previous.code, previous.status,
        Math.max(1, Math.ceil((previous.retry_at - now) / 1_000))), { 'X-Cache': 'ERROR_HIT' });
      const task = this.load(operation, key);
      this.pending.set(key, task);
      try { return response(await task, { 'X-Cache': 'MISS' }); }
      finally { this.pending.delete(key); }
    } catch { return response(fail('BUS_SERVICE_UNAVAILABLE', 503, 30)); }
  }

  private async load(operation: BusOperation, cacheKey: string): Promise<BusResult> {
    if (!this.env.SEOUL_BUS_KEY) return fail('SEOUL_BUS_NOT_CONFIGURED', 503);
    const now = Date.now();
    // Station metadata and arrival APIs have distinct published service allocations.
    // This ledger covers this Worker only; another consumer of the same key is outside it.
    const service = operation.kind === 'arrivals' ? 'arrivals' : 'stations';
    const retryAfter = this.ctx.storage.transactionSync(() => {
      const sql = this.ctx.storage.sql;
      sql.exec('DELETE FROM bus_upstream_attempts WHERE attempted_at <= ?', now - BUS_WINDOW_MS);
      const used = sql.exec<{ total: number; oldest: number | null }>(
        'SELECT COUNT(*) AS total, MIN(attempted_at) AS oldest FROM bus_upstream_attempts WHERE service = ?', service).one();
      if (used.total >= BUS_UPSTREAM_BUDGET) return Math.max(1, Math.ceil(((used.oldest ?? now) + BUS_WINDOW_MS - now) / 1_000));
      sql.exec('INSERT INTO bus_upstream_attempts (service, attempted_at) VALUES (?, ?)', service, now);
      return null;
    });
    if (retryAfter !== null) return fail('BUS_UPSTREAM_BUDGET_EXHAUSTED', 429, retryAfter);
    await this.ctx.storage.sync();
    const result = await this.fetchSource(operation);
    const completedAt = Date.now();
    if (result.status === 200) {
      this.ctx.storage.transactionSync(() => {
        this.ctx.storage.sql.exec('DELETE FROM bus_cache WHERE expires_at <= ?', completedAt);
        this.ctx.storage.sql.exec('DELETE FROM bus_failures WHERE cache_key = ?', cacheKey);
        this.ctx.storage.sql.exec('INSERT OR REPLACE INTO bus_cache (cache_key, payload, expires_at) VALUES (?, ?, ?)',
          cacheKey, result.body, completedAt + BUS_CACHE_TTL_MS);
      });
      await this.ctx.storage.sync();
    } else {
      const code = (JSON.parse(result.body) as { error: { code: string } }).error.code;
      const retrySeconds = Number(result.headers?.['Retry-After']);
      const backoff = Number.isFinite(retrySeconds) && retrySeconds > 0 && retrySeconds <= 172_800
        ? Math.max(ERROR_BACKOFF_MS, retrySeconds * 1_000) : ERROR_BACKOFF_MS;
      this.ctx.storage.transactionSync(() => {
        this.ctx.storage.sql.exec('DELETE FROM bus_failures WHERE retry_at <= ?', completedAt);
        this.ctx.storage.sql.exec('INSERT OR REPLACE INTO bus_failures (cache_key, code, status, retry_at) VALUES (?, ?, ?, ?)',
          cacheKey, code, result.status, completedAt + backoff);
      });
      await this.ctx.storage.sync();
    }
    return result;
  }

  private async fetchSource(operation: BusOperation): Promise<BusResult> {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), SOURCE_TIMEOUT_MS);
    try {
      const key = this.env.SEOUL_BUS_KEY!;
      const upstream = await fetch(busSourceUrl(operation, key), {
        redirect: 'manual', signal: controller.signal, headers: { Accept: 'application/json' },
      });
      if (upstream.status >= 300 && upstream.status < 400) return fail('BUS_UPSTREAM_REDIRECT', 502, 10);
      if (upstream.status === 401) return fail('BUS_UPSTREAM_KEY_REJECTED', 502, 10);
      if (upstream.status === 403) return fail('BUS_UPSTREAM_ACCESS_FORBIDDEN', 502, 10);
      if (upstream.status === 429) return fail('BUS_UPSTREAM_RATE_LIMITED', 429,
        upstreamRetrySeconds(upstream.headers.get('Retry-After')));
      if (!upstream.ok) return fail('BUS_UPSTREAM_UNAVAILABLE', 502, 10);
      const reader = upstream.body?.getReader();
      if (!reader) return fail('BUS_UPSTREAM_INVALID_JSON', 502, 10);
      const chunks: Uint8Array[] = [];
      let size = 0;
      while (true) {
        const chunk = await reader.read();
        if (chunk.done) break;
        size += chunk.value.byteLength;
        if (size > MAX_BODY_BYTES) { await reader.cancel(); return fail('BUS_UPSTREAM_INVALID_JSON', 502, 10); }
        chunks.push(chunk.value);
      }
      const bytes = new Uint8Array(size);
      let offset = 0;
      for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
      let payload: unknown;
      const text = new TextDecoder('utf-8', { fatal: true, ignoreBOM: false }).decode(bytes);
      const gatewayError = publicApiFailure(text);
      if (gatewayError) return fail('BUS_GATEWAY_' + gatewayError,
        gatewayError === 'RATE_LIMITED' ? 429 : 502, gatewayError === 'RATE_LIMITED' ? 60 : 10);
      try { payload = JSON.parse(text); }
      catch {
        return fail('BUS_UPSTREAM_INVALID_JSON', 502, 10);
      }
      return json(normalizeBusPayload(operation, payload, Date.now(), key));
    } catch (error) {
      if (error instanceof BusSourceError) return fail(error.code, error.status, 10);
      return fail(controller.signal.aborted ? 'BUS_UPSTREAM_TIMEOUT' : 'BUS_UPSTREAM_UNAVAILABLE', 502, 10);
    } finally { clearTimeout(timeout); }
  }
}
