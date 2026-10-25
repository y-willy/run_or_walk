import { DurableObject } from 'cloudflare:workers';
import { handleBusRequest, type BusEnv } from './bus';
import { handleGyeonggiRequest, type GyeonggiEnv } from './gyeonggi-bus';
export { GyeonggiBusCoordinator } from './gyeonggi-bus';
export { SeoulBusCoordinator } from './bus';
import {
  CACHE_TTL_MS, FAILURE_BACKOFF_MS, UPSTREAM_BUDGET, WINDOW_MS, normalizeStation,
  normalizeUpstream, refreshCachedPayload, SourcePayloadError,
} from './model';

export interface Env extends BusEnv, GyeonggiEnv {
  SEOUL_SUBWAY_KEY: string;
  SUBWAY_COORDINATOR: DurableObjectNamespace<SubwayCoordinator>;
  IP_RATE_LIMITER: RateLimit;
}
export const COORDINATOR_NAME = 'global-v1';
const UPSTREAM_TIMEOUT_MS = 5_000;
const MAX_BODY_BYTES = 1_048_576;
interface Result { status: number; body: string; headers?: Record<string, string>; }

function json(body: unknown, status = 200, headers: Record<string, string> = {}): Result {
  return { status, body: JSON.stringify(body), headers };
}
function failure(code: string, status: number, retryAfterSeconds?: number): Result {
  const retry = retryAfterSeconds === undefined ? {} : { retryAfterSeconds, retryAt: new Date(Date.now() + retryAfterSeconds * 1000).toISOString() };
  return json({ error: { code, ...retry } }, status,
    retryAfterSeconds === undefined ? {} : { 'Retry-After': String(retryAfterSeconds) });
}
function respond(result: Result, extra: Record<string, string> = {}): Response {
  return new Response(result.body, {
    status: result.status,
    headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store',
      'X-Content-Type-Options': 'nosniff', ...result.headers, ...extra },
  });
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    try {
      const url = new URL(request.url);
      if (request.method !== 'GET') return respond(failure('METHOD_NOT_ALLOWED', 405), { Allow: 'GET' });
      if (url.pathname === '/health') return respond(json({ status: 'ok' }));
      if (url.pathname.startsWith('/bus/')) return await handleBusRequest(request, env);
       if (url.pathname.startsWith('/gyeonggi/bus/')) return await handleGyeonggiRequest(request, env);
      if (url.pathname !== '/subway') return respond(failure('NOT_FOUND', 404));
      if (url.searchParams.getAll('station').length !== 1) return respond(failure('INVALID_STATION', 400));
      const station = normalizeStation(url.searchParams.get('station'));
      if (!station) return respond(failure('INVALID_STATION', 400));
      if (!env.SEOUL_SUBWAY_KEY || !env.SUBWAY_COORDINATOR || !env.IP_RATE_LIMITER) {
        return respond(failure('SERVICE_NOT_CONFIGURED', 503));
      }
      const ip = request.headers.get('CF-Connecting-IP');
      if (!ip || ip.length > 64) return respond(failure('CLIENT_IP_UNAVAILABLE', 503));
      let allowed: boolean;
      try { allowed = (await env.IP_RATE_LIMITER.limit({ key: ip })).success; }
      catch { return respond(failure('RATE_LIMITER_UNAVAILABLE', 503, 60)); }
      if (!allowed) return respond(failure('IP_RATE_LIMITED', 429, 60));
      // All POPs, stations and clients deliberately select the same object.
      const coordinator = env.SUBWAY_COORDINATOR.get(env.SUBWAY_COORDINATOR.idFromName(COORDINATOR_NAME));
      return await coordinator.fetch(new Request(`https://coordinator/subway?station=${encodeURIComponent(station)}`));
    } catch { return respond(failure('SERVICE_UNAVAILABLE', 503, 30)); }
  },
} satisfies ExportedHandler<Env>;

export class SubwayCoordinator extends DurableObject<Env> {
  private readonly pending = new Map<string, Promise<Result>>();

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    ctx.storage.sql.exec(`CREATE TABLE IF NOT EXISTS upstream_attempts (
      id INTEGER PRIMARY KEY AUTOINCREMENT, attempted_at INTEGER NOT NULL
    )`);
    ctx.storage.sql.exec('CREATE INDEX IF NOT EXISTS attempts_time ON upstream_attempts(attempted_at)');
    ctx.storage.sql.exec(`CREATE TABLE IF NOT EXISTS station_cache (
      station TEXT PRIMARY KEY, payload TEXT NOT NULL, expires_at INTEGER NOT NULL
    )`);
    ctx.storage.sql.exec(`CREATE TABLE IF NOT EXISTS station_failures (
      station TEXT PRIMARY KEY, error_code TEXT NOT NULL, status INTEGER NOT NULL,
      retry_at INTEGER NOT NULL
    )`);
    ctx.storage.sql.exec('CREATE TABLE IF NOT EXISTS ledger_metadata (singleton INTEGER PRIMARY KEY CHECK(singleton = 1), upstream_not_before INTEGER NOT NULL)');
    // Retire the old bootstrap hold in place. Do not remove the object, namespace,
    // attempt ledger or cached data: the actual rolling 900-attempt budget survives.
    ctx.storage.sql.exec('INSERT OR IGNORE INTO ledger_metadata (singleton, upstream_not_before) VALUES (1, 0)');
    ctx.storage.sql.exec('UPDATE ledger_metadata SET upstream_not_before = 0 WHERE singleton = 1 AND upstream_not_before != 0');
  }

  async fetch(request: Request): Promise<Response> {
    try {
      const station = normalizeStation(new URL(request.url).searchParams.get('station'));
      if (!station) return respond(failure('INVALID_STATION', 400));
      const running = this.pending.get(station);
      if (running) return respond(await running, { 'X-Cache': 'COALESCED' });
      const now = Date.now();
      const cached = this.ctx.storage.sql.exec<{ payload: string; expires_at: number }>(
        'SELECT payload, expires_at FROM station_cache WHERE station = ?', station).toArray()[0];
      if (cached && cached.expires_at > now) return respond({ status: 200, body: refreshCachedPayload(cached.payload, now) }, { 'X-Cache': 'HIT' });
      const backoff = this.ctx.storage.sql.exec<{ error_code: string; status: number; retry_at: number }>(
        'SELECT error_code, status, retry_at FROM station_failures WHERE station = ?', station).toArray()[0];
      if (backoff && backoff.retry_at > now) return respond(failure(backoff.error_code, backoff.status,
        Math.max(1, Math.ceil((backoff.retry_at - now) / 1000))), { 'X-Cache': 'ERROR_HIT' });
      const task = this.load(station);
      this.pending.set(station, task);
      try { return respond(await task, { 'X-Cache': 'MISS' }); }
      finally { this.pending.delete(station); }
    } catch { return respond(failure('SERVICE_UNAVAILABLE', 503, 30)); }
  }

  private async load(station: string): Promise<Result> {
    if (!this.env.SEOUL_SUBWAY_KEY) return failure('SERVICE_NOT_CONFIGURED', 503);
    const now = Date.now();
    const denied = this.ctx.storage.transactionSync(() => {
      const sql = this.ctx.storage.sql;
      // The open interval (now - 24h, now] is rolling, without a midnight reset.
      sql.exec('DELETE FROM upstream_attempts WHERE attempted_at <= ?', now - WINDOW_MS);
      const usage = sql.exec<{ total: number; oldest: number | null }>(
        'SELECT COUNT(*) AS total, MIN(attempted_at) AS oldest FROM upstream_attempts').one();
      if (usage.total >= UPSTREAM_BUDGET) return Math.max(1, Math.ceil(((usage.oldest ?? now) + WINDOW_MS - now) / 1000));
      sql.exec('INSERT INTO upstream_attempts (attempted_at) VALUES (?)', now);
      return null;
    });
    if (denied !== null) return failure('UPSTREAM_BUDGET_EXHAUSTED', 429, denied);
    // Persist a reservation before starting the HTTP attempt. Failed, timed-out,
    // and redirected attempts consume one slot and never get an automatic retry.
    await this.ctx.storage.sync();
    const result = await this.fetchSource(station);
    if (result.status >= 500) {
      const failedAt = Date.now();
      const errorCode = (JSON.parse(result.body) as { error: { code: string } }).error.code;
      this.ctx.storage.transactionSync(() => {
        this.ctx.storage.sql.exec('DELETE FROM station_failures WHERE retry_at <= ?', failedAt);
        this.ctx.storage.sql.exec('INSERT OR REPLACE INTO station_failures (station, error_code, status, retry_at) VALUES (?, ?, ?, ?)',
          station, errorCode, result.status, failedAt + FAILURE_BACKOFF_MS);
      });
      await this.ctx.storage.sync();
    }
    return result;
  }

  private async fetchSource(station: string): Promise<Result> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), UPSTREAM_TIMEOUT_MS);
    try {
      const key = this.env.SEOUL_SUBWAY_KEY;
      const upstreamUrl = `http://swopenapi.seoul.go.kr/api/subway/${encodeURIComponent(key)}/json/realtimeStationArrival/0/100/${encodeURIComponent(station)}`;
      const response = await fetch(upstreamUrl, { redirect: 'manual', signal: controller.signal });
      if (response.status >= 300 && response.status < 400) return failure('UPSTREAM_REDIRECT', 502, 10);
      if (!response.ok) return failure('UPSTREAM_UNAVAILABLE', 502, 10);
      const reader = response.body?.getReader();
      if (!reader) return failure('UPSTREAM_INVALID_JSON', 502, 10);
      const chunks: Uint8Array[] = [];
      let size = 0;
      while (true) {
        const part = await reader.read();
        if (part.done) break;
        size += part.value.byteLength;
        if (size > MAX_BODY_BYTES) { await reader.cancel(); return failure('UPSTREAM_INVALID_JSON', 502, 10); }
        chunks.push(part.value);
      }
      const all = new Uint8Array(size);
      let offset = 0;
      for (const part of chunks) { all.set(part, offset); offset += part.byteLength; }
      let raw: unknown;
      try { raw = JSON.parse(new TextDecoder('utf-8', { fatal: true, ignoreBOM: false }).decode(all)); }
      catch { return failure('UPSTREAM_INVALID_JSON', 502, 10); }
      const fetchedAtMs = Date.now();
      const normalized = normalizeUpstream(raw, station, fetchedAtMs, key);
      const result = json(normalized);
      this.ctx.storage.transactionSync(() => {
        this.ctx.storage.sql.exec('DELETE FROM station_failures WHERE station = ?', station);
        this.ctx.storage.sql.exec('DELETE FROM station_cache WHERE expires_at <= ?', fetchedAtMs);
        this.ctx.storage.sql.exec('INSERT OR REPLACE INTO station_cache (station, payload, expires_at) VALUES (?, ?, ?)',
          station, result.body, fetchedAtMs + CACHE_TTL_MS);
      });
      return result;
    } catch (error) {
      if (error instanceof SourcePayloadError) return failure(error.code, 502, 10);
      return failure(controller.signal.aborted ? 'UPSTREAM_TIMEOUT' : 'UPSTREAM_UNAVAILABLE', 502, 10);
    } finally { clearTimeout(timer); }
  }
}
