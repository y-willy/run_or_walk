import { DurableObject } from 'cloudflare:workers';
import {
  GYEONGGI_BUDGET, GYEONGGI_CACHE_MS, GYEONGGI_WINDOW_MS,
  GyeonggiPayloadError, gyeonggiSourceUrl, normalizeGyeonggiResponse,
  parseGyeonggiOperation, refreshGyeonggiPayload, type GyeonggiOperation,
} from './gyeonggi-bus-model';
import { parseGyeonggiLookup, gyeonggiLookupSourceUrl, normalizeGyeonggiLookup,
  type GyeonggiLookupOperation } from './gyeonggi-lookup-model';

export interface GyeonggiEnv {
  GYEONGGI_BUS_KEY?: string;
  GYEONGGI_STATION_KEY?: string;
  GYEONGGI_BUS_COORDINATOR?: DurableObjectNamespace<GyeonggiBusCoordinator>;
  IP_RATE_LIMITER: RateLimit;
}
const COORDINATOR = 'gyeonggi-bus-global-v1';
const BACKOFF_MS = 10_000;
const REQUEST_TIMEOUT_MS = 5_000;
const MAX_BYTES = 1_048_576;
type Outcome = { status: number; body: string; headers?: Record<string, string> };
function ok(value: unknown): Outcome { return { status: 200, body: JSON.stringify(value) }; }
function fail(code: string, status: number, retry?: number): Outcome {
  const seconds = retry === undefined ? undefined : Math.max(1, Math.ceil(retry));
  return { status, body: JSON.stringify({ error: { code, ...(seconds === undefined ? {} : {
    retryAfterSeconds: seconds, retryAt: new Date(Date.now() + seconds * 1000).toISOString(),
  }) } }), headers: seconds === undefined ? {} : { 'Retry-After': String(seconds) } };
}
function respond(data: Outcome, additional: Record<string, string> = {}): Response {
  return new Response(data.body, { status: data.status, headers: {
    'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff', ...data.headers, ...additional,
  } });
}
export async function handleGyeonggiRequest(request: Request, env: GyeonggiEnv): Promise<Response> {
  try {
    const op = parseGyeonggiOperation(new URL(request.url)) ?? parseGyeonggiLookup(new URL(request.url));
    if (!op) return respond(fail('GYEONGGI_INVALID_QUERY', 400));
    const isLookup = op.kind === 'nearby' || op.kind === 'search' || op.kind === 'via';
    if (!(isLookup ? env.GYEONGGI_STATION_KEY : env.GYEONGGI_BUS_KEY) ||
        !env.GYEONGGI_BUS_COORDINATOR || !env.IP_RATE_LIMITER) {
      return respond(fail('GYEONGGI_NOT_CONFIGURED', 503));
    }
    const ip = request.headers.get('CF-Connecting-IP');
    if (!ip || ip.length > 64) return respond(fail('CLIENT_IP_UNAVAILABLE', 503));
    let allowed: boolean;
    try { allowed = (await env.IP_RATE_LIMITER.limit({ key: ip })).success; }
    catch { return respond(fail('RATE_LIMITER_UNAVAILABLE', 503, 60)); }
    if (!allowed) return respond(fail('IP_RATE_LIMITED', 429, 60));
    const stub = env.GYEONGGI_BUS_COORDINATOR.get(env.GYEONGGI_BUS_COORDINATOR.idFromName(COORDINATOR));
    const params = new URLSearchParams();
    let path: string;
    if (op.kind === 'nearby') {
      path = '/gyeonggi/bus/nearby';
      params.set('x', String(op.x)); params.set('y', String(op.y));
    } else if (op.kind === 'search') {
      path = '/gyeonggi/bus/search';
      params.set('query', op.query);
    } else if (op.kind === 'via') {
      path = '/gyeonggi/bus/via';
      params.set('stationId', op.stationId);
    } else {
      path = op.kind === 'item' ? '/gyeonggi/bus/arrival' : '/gyeonggi/bus/arrivals';
      params.set('stationId', op.stationId);
      if (op.kind === 'item') {
        params.set('routeId', op.routeId); params.set('staOrder', op.staOrder);
      }
    }
    return await stub.fetch(new Request('https://gyeonggi-coordinator' + path + '?' + params));
  } catch { return respond(fail('GYEONGGI_SERVICE_UNAVAILABLE', 503, 30)); }
}
/** Independent SQLite ledger; never share/reinitialize the existing Seoul coordinator. */
export class GyeonggiBusCoordinator extends DurableObject<GyeonggiEnv> {
  private readonly pending = new Map<string, Promise<Outcome>>();
  constructor(ctx: DurableObjectState, env: GyeonggiEnv) {
    super(ctx, env);
    ctx.storage.sql.exec('CREATE TABLE IF NOT EXISTS attempts (id INTEGER PRIMARY KEY AUTOINCREMENT, at_ms INTEGER NOT NULL)');
    ctx.storage.sql.exec('CREATE INDEX IF NOT EXISTS gbis_attempt_time ON attempts (at_ms)');
    ctx.storage.sql.exec('CREATE TABLE IF NOT EXISTS arrival_cache (cache_key TEXT PRIMARY KEY, body TEXT NOT NULL, expires_at INTEGER NOT NULL)');
    ctx.storage.sql.exec('CREATE TABLE IF NOT EXISTS failures (cache_key TEXT PRIMARY KEY, code TEXT NOT NULL, status INTEGER NOT NULL, retry_at INTEGER NOT NULL)');
  }
  async fetch(request: Request): Promise<Response> {
    try {
      const op = parseGyeonggiOperation(new URL(request.url)) ?? parseGyeonggiLookup(new URL(request.url));
      if (!op) return respond(fail('GYEONGGI_INVALID_QUERY', 400));
      const key = JSON.stringify(op);
      const running = this.pending.get(key);
      if (running) return respond(await running, { 'X-Cache': 'COALESCED' });
      const now = Date.now();
      const cached = this.ctx.storage.sql.exec<{ body: string; expires_at: number }>(
        'SELECT body, expires_at FROM arrival_cache WHERE cache_key = ?', key).toArray()[0];
      if (cached && cached.expires_at > now) return respond({
        status: 200, body: (op.kind === 'list' || op.kind === 'item') ?
          refreshGyeonggiPayload(cached.body, now) : cached.body,
      }, { 'X-Cache': 'HIT' });
      const error = this.ctx.storage.sql.exec<{ code: string; status: number; retry_at: number }>(
        'SELECT code, status, retry_at FROM failures WHERE cache_key = ?', key).toArray()[0];
      if (error && error.retry_at > now) return respond(fail(error.code, error.status,
        (error.retry_at - now) / 1000), { 'X-Cache': 'ERROR_HIT' });
      const task = this.load(op, key);
      this.pending.set(key, task);
      try { return respond(await task, { 'X-Cache': 'MISS' }); }
      finally { this.pending.delete(key); }
    } catch { return respond(fail('GYEONGGI_SERVICE_UNAVAILABLE', 503, 30)); }
  }
  private async load(op: GyeonggiOperation | GyeonggiLookupOperation, cacheKey: string): Promise<Outcome> {
    if ((op.kind === 'list' || op.kind === 'item') ? !this.env.GYEONGGI_BUS_KEY :
        !this.env.GYEONGGI_STATION_KEY) return fail('GYEONGGI_NOT_CONFIGURED', 503);
    const now = Date.now();
    const limit = this.ctx.storage.transactionSync(() => {
      const sql = this.ctx.storage.sql;
      sql.exec('DELETE FROM attempts WHERE at_ms <= ?', now - GYEONGGI_WINDOW_MS);
      const used = sql.exec<{ total: number; oldest: number | null }>(
        'SELECT COUNT(*) AS total, MIN(at_ms) AS oldest FROM attempts').one();
      if (used.total >= GYEONGGI_BUDGET) return Math.max(1,
        Math.ceil(((used.oldest ?? now) + GYEONGGI_WINDOW_MS - now) / 1000));
      sql.exec('INSERT INTO attempts (at_ms) VALUES (?)', now);
      return null;
    });
    if (limit !== null) return fail('GYEONGGI_DAILY_BUDGET', 429, limit);
    await this.ctx.storage.sync(); // Reserve before HTTP, including failed attempts.
    const outcome = await this.upstream(op);
    const completed = Date.now();
    this.ctx.storage.transactionSync(() => {
      if (outcome.status === 200) {
        this.ctx.storage.sql.exec('DELETE FROM arrival_cache WHERE expires_at <= ?', completed);
        this.ctx.storage.sql.exec('DELETE FROM failures WHERE cache_key = ?', cacheKey);
        this.ctx.storage.sql.exec('INSERT OR REPLACE INTO arrival_cache (cache_key, body, expires_at) VALUES (?, ?, ?)',
          cacheKey, outcome.body, completed + GYEONGGI_CACHE_MS);
      } else {
        const code = (JSON.parse(outcome.body) as { error: { code: string } }).error.code;
        const specified = Number(outcome.headers?.['Retry-After']);
        const backoff = Number.isFinite(specified) && specified > 0
          ? Math.max(BACKOFF_MS, Math.min(specified * 1000, GYEONGGI_WINDOW_MS)) : BACKOFF_MS;
        this.ctx.storage.sql.exec('DELETE FROM failures WHERE retry_at <= ?', completed);
        this.ctx.storage.sql.exec('INSERT OR REPLACE INTO failures (cache_key, code, status, retry_at) VALUES (?, ?, ?, ?)',
          cacheKey, code, outcome.status, completed + backoff);
      }
    });
    await this.ctx.storage.sync();
    return outcome;
  }
  private async upstream(op: GyeonggiOperation | GyeonggiLookupOperation): Promise<Outcome> {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
    try {
      const url = (op.kind === 'list' || op.kind === 'item') ?
        gyeonggiSourceUrl(op, this.env.GYEONGGI_BUS_KEY!) :
        gyeonggiLookupSourceUrl(op, this.env.GYEONGGI_STATION_KEY!);
      const response = await fetch(url, { redirect: 'manual', signal: controller.signal,
        headers: { Accept: 'application/json' } });
      if (response.status >= 300 && response.status < 400) return fail('GYEONGGI_UPSTREAM_REDIRECT', 502, 10);
      if ([401, 403].includes(response.status)) return fail('GYEONGGI_AUTHENTICATION', 502, 10);
      if (response.status === 429) return fail('GYEONGGI_UPSTREAM_RATE_LIMIT', 429, 60);
      if (!response.ok) return fail('GYEONGGI_UPSTREAM_UNAVAILABLE', 502, 10);
      const reader = response.body?.getReader();
      if (!reader) return fail('GYEONGGI_INVALID_RESPONSE', 502, 10);
      let used = 0;
      const chunks: Uint8Array[] = [];
      while (true) {
        const part = await reader.read();
        if (part.done) break;
        used += part.value.byteLength;
        if (used > MAX_BYTES) { await reader.cancel(); return fail('GYEONGGI_INVALID_RESPONSE', 502, 10); }
        chunks.push(part.value);
      }
      const all = new Uint8Array(used);
      let cursor = 0;
      for (const part of chunks) { all.set(part, cursor); cursor += part.byteLength; }
      let decoded: unknown;
      try { decoded = JSON.parse(new TextDecoder('utf-8', { fatal: true, ignoreBOM: false }).decode(all)); }
      catch { return fail('GYEONGGI_INVALID_RESPONSE', 502, 10); }
      return ok((op.kind === 'list' || op.kind === 'item') ?
        normalizeGyeonggiResponse(decoded, op, Date.now()) : normalizeGyeonggiLookup(decoded, op));
    } catch (error) {
      if (error instanceof GyeonggiPayloadError) return fail(error.code, 502, 10);
      return fail(controller.signal.aborted ? 'GYEONGGI_TIMEOUT' : 'GYEONGGI_UPSTREAM_UNAVAILABLE', 502, 10);
    } finally { clearTimeout(timeout); }
  }
}
