import { env } from 'cloudflare:workers';
import { abortAllDurableObjects, reset, runInDurableObject } from 'cloudflare:test';
import { afterEach, describe, expect, it, vi } from 'vitest';
import worker, { COORDINATOR_NAME, type Env } from '../src/index';
import { WINDOW_MS } from '../src/model';

const bindings = env as unknown as Env;
const secret = 'test-only-never-a-real-key';
function stub() { return bindings.SUBWAY_COORDINATOR.get(bindings.SUBWAY_COORDINATOR.idFromName(COORDINATOR_NAME)); }
function request(station = '강남', overrides: Partial<Env> = {}, ip = '198.51.100.1') {
  return worker.fetch(new Request(`https://example.test/subway?station=${encodeURIComponent(station)}`,
    { headers: { 'CF-Connecting-IP': ip } }), {
      ...bindings, IP_RATE_LIMITER: { limit: async () => ({ success: true }) } as RateLimit, ...overrides,
    });
}
function sourceTime(now = Date.now()) { return new Date(now + 9 * 3600_000).toISOString().slice(0, 19).replace('T', ' '); }
function source() {
  return { errorMessage: { code: 'INFO-000' }, realtimeArrivalList: [{
    statnNm: '강남', statnId: '1002000222', subwayId: '1002', updnLine: '외선',
    bstatnNm: '성수', barvlDt: '240', recptnDt: sourceTime(), arvlCd: '99',
    btrainNo: '2211', arvlMsg2: '4분 후', trainLineNm: '성수행 - 역삼방면',
    btrainSttus: '일반', bstatnId: '1002000211', statnTid: '1002000221',
  }] };
}
function upstream(body: unknown = source(), status = 200) {
  return vi.spyOn(globalThis, 'fetch').mockImplementation(async () => new Response(JSON.stringify(body), { status }));
}
async function count() {
  return runInDurableObject(stub(), (_instance, state) => state.storage.sql.exec<{ total: number }>(
    'SELECT COUNT(*) AS total FROM upstream_attempts').one().total);
}
async function seedAttempts(total: number, attemptedAt = Date.now()) {
  await runInDurableObject(stub(), (_instance, state) => {
    state.storage.transactionSync(() => {
      for (let i = 0; i < total; i++) state.storage.sql.exec('INSERT INTO upstream_attempts (attempted_at) VALUES (?)', attemptedAt);
    });
  });
}
afterEach(async () => { vi.restoreAllMocks(); await reset(); });

describe('real Workers runtime endpoints and upstream failures', () => {
  it('keeps GET /health and the existing public /subway response contract', async () => {
    const fetch = upstream();
    const health = await worker.fetch(new Request('https://example.test/health'), bindings);
    expect(await health.json()).toEqual({ status: 'ok' });
    const response = await request();
    expect(response.status).toBe(200);
    const body: any = await response.json();
    expect(body.realtime).toBe(true);
    expect(body.arrivals[0]).toMatchObject({ station: '강남', lineId: '1002',
      direction: '외선', destination: '성수행 - 역삼방면', bstatnNm: '성수', arrivalSeconds: '240', subwayId: '1002', arvlCd: '99', trustState: 'VALID' });
    expect(body.realtimeArrivalList).toEqual(body.arrivals);
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(fetch.mock.calls[0][1]).toMatchObject({ redirect: 'manual' });
    expect(await count()).toBe(1);
  });

  it('rejects missing key and unknown station before an upstream attempt', async () => {
    const fetch = upstream();
    expect((await request('강남', { SEOUL_SUBWAY_KEY: '' })).status).toBe(503);
    expect((await request('https://attacker.test')).status).toBe(400);
    expect(fetch).not.toHaveBeenCalled();
  });

  it('fails closed if the IP binding is absent, throws, or the IP is unavailable', async () => {
    const fetch = upstream();
    expect((await request('강남', { IP_RATE_LIMITER: undefined })).status).toBe(503);
    expect((await request('강남', { IP_RATE_LIMITER: { limit: async () => { throw new Error(secret); } } as RateLimit })).status).toBe(503);
    const response = await worker.fetch(new Request('https://example.test/subway?station=강남'), bindings);
    expect(response.status).toBe(503);
    expect(fetch).not.toHaveBeenCalled();
  });

  it('returns a clear 429 and retry delay for a blocked IP', async () => {
    const fetch = upstream();
    const response = await request('강남', { IP_RATE_LIMITER: { limit: async () => ({ success: false }) } as RateLimit });
    expect(response.status).toBe(429);
    expect(response.headers.get('Retry-After')).toBe('60');
    expect(await response.json()).toMatchObject({ error: { code: 'IP_RATE_LIMITED', retryAfterSeconds: 60 } });
    expect(fetch).not.toHaveBeenCalled();
  });

  it('exercises the real per-location rate limiter binding at 60 requests', async () => {
    upstream();
    for (let i = 0; i < 60; i++) expect((await request('강남', { IP_RATE_LIMITER: bindings.IP_RATE_LIMITER }, '198.51.100.61')).status).toBe(200);
    expect((await request('강남', { IP_RATE_LIMITER: bindings.IP_RATE_LIMITER }, '198.51.100.61')).status).toBe(429);
    expect(await count()).toBe(1);
  });

  it.each([301, 302, 307, 308])('does not follow redirect %s and charges one attempted fetch', async status => {
    const fetch = upstream({}, status);
    const response = await request();
    expect(await response.json()).toMatchObject({ error: { code: 'UPSTREAM_REDIRECT' } });
    expect(response.status).toBe(502);
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(await count()).toBe(1);
  });

  it('rejects malformed JSON and source API errors without exposing raw text', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async () => new Response(`not-json ${secret}`));
    const response = await request();
    expect(response.status).toBe(502);
    expect(await response.text()).not.toContain(secret);
    expect(await count()).toBe(1);
  });

  it('handles official no-data replies without making up an arrival', async () => {
    upstream({ errorMessage: { code: 'INFO-200', message: 'no data' } });
    const response = await request();
    expect(response.status).toBe(200);
    expect(await response.json()).toMatchObject({ arrivals: [], realtimeArrivalList: [] });
  });

  it('redacts secrets in allowed fields, exceptions, response, and logs', async () => {
    const log = vi.spyOn(console, 'log');
    const error = vi.spyOn(console, 'error');
    const body = source();
    body.realtimeArrivalList[0].arvlMsg2 = `http://swopenapi.seoul.go.kr/${secret}`;
    upstream(body);
    expect(await (await request()).text()).not.toContain(secret);
    expect(log).not.toHaveBeenCalled();
    expect(error).not.toHaveBeenCalled();
  });

  it('does not expose secret-bearing fetch exceptions', async () => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new Error(`http://swopenapi.seoul.go.kr/${secret}`));
    const response = await request();
    expect(response.status).toBe(502);
    expect(await response.text()).not.toContain(secret);
    expect(await count()).toBe(1);
  });

  it('rejects a success code paired with an upstream error status', async () => {
    upstream({ ...source(), errorMessage: { code: 'INFO-000', status: 500, message: secret } });
    const response = await request();
    expect(response.status).toBe(502);
    expect(await response.text()).not.toContain(secret);
    expect(await count()).toBe(1);
  });

  it('aborts a stalled upstream request after five seconds without automatic retry', async () => {
    const fetch = vi.spyOn(globalThis, 'fetch').mockImplementation((_input, init) => new Promise((_resolve, reject) => {
      init?.signal?.addEventListener('abort', () => reject(new Error(secret)), { once: true });
    }));
    const response = await request();
    expect(response.status).toBe(502);
    expect(await response.json()).toMatchObject({ error: { code: 'UPSTREAM_TIMEOUT' } });
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(await count()).toBe(1);
  });
});

describe('real SQLite Durable Object shared cache and rolling budget', () => {
  it('backs off sequential failures without charging a second upstream attempt', async () => {
    const fetch = vi.spyOn(globalThis, 'fetch').mockRejectedValue(new Error(secret));
    const first = await request();
    expect(first.status).toBe(502);
    expect(first.headers.get('Retry-After')).toBe('10');
    const again = await request('강남역');
    expect(again.status).toBe(502);
    expect(again.headers.get('X-Cache')).toBe('ERROR_HIT');
    expect(Number(again.headers.get('Retry-After'))).toBeGreaterThan(0);
    expect(Number(again.headers.get('Retry-After'))).toBeLessThanOrEqual(10);
    expect(await again.json()).toMatchObject({ error: { code: 'UPSTREAM_UNAVAILABLE' } });
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(await count()).toBe(1);
  });

  it('retains failure backoff across an object restart', async () => {
    const fetch = upstream({}, 503);
    expect((await request()).status).toBe(502);
    await abortAllDurableObjects();
    const response = await request();
    expect(response.status).toBe(502);
    expect(response.headers.get('X-Cache')).toBe('ERROR_HIT');
    expect(await response.json()).toMatchObject({ error: { code: 'UPSTREAM_UNAVAILABLE' } });
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(await count()).toBe(1);
  });

  it('charges a new attempt after failure backoff expires, then clears it on success', async () => {
    const fetch = upstream({}, 503);
    expect((await request()).status).toBe(502);
    await runInDurableObject(stub(), (_instance, state) => state.storage.sql.exec('UPDATE station_failures SET retry_at = ?', Date.now() - 1));
    fetch.mockImplementation(async () => new Response(JSON.stringify(source())));
    expect((await request()).status).toBe(200);
    expect(fetch).toHaveBeenCalledTimes(2);
    expect(await count()).toBe(2);
    expect(await runInDurableObject(stub(), (_instance, state) => state.storage.sql.exec<{ total: number }>('SELECT COUNT(*) AS total FROM station_failures').one().total)).toBe(0);
  });

  it('does not bypass a full global budget when failure backoff expires', async () => {
    const fetch = upstream({}, 503);
    expect((await request()).status).toBe(502);
    await seedAttempts(899);
    await runInDurableObject(stub(), (_instance, state) => state.storage.sql.exec('UPDATE station_failures SET retry_at = 0'));
    const response = await request();
    expect(response.status).toBe(429);
    expect(await response.json()).toMatchObject({ error: { code: 'UPSTREAM_BUDGET_EXHAUSTED' } });
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(await count()).toBe(900);
  });

  it('coalesces simultaneous normalized station misses and serves cache without consuming budget', async () => {
    const fetch = vi.spyOn(globalThis, 'fetch').mockImplementation(async () => {
      await new Promise(resolve => setTimeout(resolve, 50));
      return new Response(JSON.stringify(source()));
    });
    const requests = Array.from({ length: 20 }, (_, i) => request(i % 2 ? '강남역' : ' 강남 '));
    const responses = await Promise.all(requests);
    expect(responses.every(x => x.status === 200)).toBe(true);
    const bodies = await Promise.all(responses.map(r => r.json())) as any[];
    expect(new Set(bodies.map(x => x.fetchedAt)).size).toBe(1);
    const cached = await request();
    expect(cached.headers.get('X-Cache')).toBe('HIT');
    expect((await cached.json() as any).fetchedAt).toBe(bodies[0].fetchedAt);
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(await count()).toBe(1);
  });

  it('does not serve expired cache as fresh and does a new upstream fetch', async () => {
    const fetch = upstream();
    await request();
    await runInDurableObject(stub(), (_instance, state) => state.storage.sql.exec('UPDATE station_cache SET expires_at = ?', Date.now() - 1));
    expect((await request()).headers.get('X-Cache')).toBe('MISS');
    expect(fetch).toHaveBeenCalledTimes(2);
    expect(await count()).toBe(2);
  });

  it('preserves cache and budget across an object restart', async () => {
    const fetch = upstream();
    const original: any = await (await request()).json();
    await abortAllDurableObjects();
    const again = await request();
    expect(again.headers.get('X-Cache')).toBe('HIT');
    expect((await again.json() as any).fetchedAt).toBe(original.fetchedAt);
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(await count()).toBe(1);
  });

  it('enforces exactly 900 attempts across concurrent station misses', async () => {
    const fetch = upstream();
    await seedAttempts(895);
    const stations = ['강남', '역삼', '선릉', '삼성', '잠실', '신촌', '신림', '사당', '교대', '홍대입구', '왕십리', '을지로입구', '종각', '서울', '수원', '청량리', '용산', '시청', '강변', '건대입구'];
    const responses = await Promise.all(stations.map(name => request(name)));
    expect(responses.filter(x => x.status === 200)).toHaveLength(5);
    expect(responses.filter(x => x.status === 429)).toHaveLength(15);
    expect(fetch).toHaveBeenCalledTimes(5);
    expect(await count()).toBe(900);
    const blocked = await request('안국');
    expect(blocked.status).toBe(429);
    expect(Number(blocked.headers.get('Retry-After'))).toBeGreaterThan(86_390);
    expect(await blocked.json()).toMatchObject({ error: { code: 'UPSTREAM_BUDGET_EXHAUSTED' } });
  });

  it('prunes rolling-window entries at 24 hours without a KST midnight reset', async () => {
    upstream();
    await seedAttempts(900, Date.now() - WINDOW_MS);
    expect((await request()).status).toBe(200);
    expect(await count()).toBe(1);
  });

  it('blocks an expired cache when the global budget is exhausted', async () => {
    const fetch = upstream();
    await request();
    await seedAttempts(899);
    await runInDurableObject(stub(), (_instance, state) => state.storage.sql.exec('UPDATE station_cache SET expires_at = 0'));
    expect((await request()).status).toBe(429);
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it('allows a cache hit even when the upstream budget is exhausted', async () => {
    const fetch = upstream();
    await request();
    await seedAttempts(899);
    expect((await request()).status).toBe(200);
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(await count()).toBe(900);
  });

  it('keeps failed same-station attempts coalesced and charges only once', async () => {
    const fetch = vi.spyOn(globalThis, 'fetch').mockImplementation(async () => {
      await new Promise(resolve => setTimeout(resolve, 50));
      throw new Error(secret);
    });
    const requests = Array.from({ length: 10 }, () => request());
    expect((await Promise.all(requests)).every(x => x.status === 502)).toBe(true);
    expect(await count()).toBe(1);
  });

  it('fetches on the first default request without a bootstrap flag or a 24-hour wait', async () => {
    const fetch = upstream();
    const response = await request();
    expect(response.status).toBe(200);
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(await count()).toBe(1);
    const metadata = await runInDurableObject(stub(), (_instance, state) => state.storage.sql.exec<{
      upstream_not_before: number;
    }>('SELECT upstream_not_before FROM ledger_metadata WHERE singleton = 1').one());
    expect(metadata.upstream_not_before).toBe(0);
  });

  it('automatically unlocks an existing future warmup deadline while preserving recorded attempts', async () => {
    const fetch = upstream();
    await seedAttempts(3);
    const recorded = await runInDurableObject(stub(), (_instance, state) => state.storage.sql.exec<{
      id: number; attempted_at: number;
    }>('SELECT id, attempted_at FROM upstream_attempts ORDER BY id').toArray());
    await runInDurableObject(stub(), (_instance, state) => state.storage.sql.exec('UPDATE ledger_metadata SET upstream_not_before = ?', Date.now() + WINDOW_MS));
    await abortAllDurableObjects();
    const response = await request();
    expect(response.status).toBe(200);
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(await count()).toBe(4);
    const after = await runInDurableObject(stub(), (_instance, state) => ({
      attempts: state.storage.sql.exec<{ id: number; attempted_at: number }>(
        'SELECT id, attempted_at FROM upstream_attempts ORDER BY id LIMIT 3').toArray(),
      deadline: state.storage.sql.exec<{ upstream_not_before: number }>(
        'SELECT upstream_not_before FROM ledger_metadata WHERE singleton = 1').one().upstream_not_before,
    }));
    expect(after.attempts).toEqual(recorded);
    expect(after.deadline).toBe(0);
  });

  it('keeps a genuinely exhausted 900-attempt budget blocked when retiring the old warmup', async () => {
    const fetch = upstream();
    await seedAttempts(900);
    await runInDurableObject(stub(), (_instance, state) => state.storage.sql.exec(
      'UPDATE ledger_metadata SET upstream_not_before = ?', Date.now() + WINDOW_MS));
    await abortAllDurableObjects();
    const response = await request();
    expect(response.status).toBe(429);
    expect(await response.json()).toMatchObject({ error: { code: 'UPSTREAM_BUDGET_EXHAUSTED' } });
    expect(Number(response.headers.get('Retry-After'))).toBeGreaterThan(86_390);
    expect(fetch).not.toHaveBeenCalled();
    expect(await count()).toBe(900);
  });

  it('does not recreate a bootstrap wait or reset the attempt ledger on later restarts', async () => {
    const fetch = upstream();
    expect((await request()).status).toBe(200);
    await abortAllDurableObjects();
    const cached = await request();
    expect(cached.headers.get('X-Cache')).toBe('HIT');
    expect(await count()).toBe(1);
    await runInDurableObject(stub(), (_instance, state) => state.storage.sql.exec('DELETE FROM station_cache'));
    await abortAllDurableObjects();
    expect((await request()).status).toBe(200);
    expect(fetch).toHaveBeenCalledTimes(2);
    expect(await count()).toBe(2);
  });
});
