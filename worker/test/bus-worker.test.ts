import { env } from 'cloudflare:workers';
import { abortAllDurableObjects, reset, runInDurableObject } from 'cloudflare:test';
import { afterEach, describe, expect, it, vi } from 'vitest';
import worker, { type Env } from '../src/index';
import { BUS_COORDINATOR_NAME } from '../src/bus';
import { BUS_WINDOW_MS } from '../src/bus-model';

const bindings = env as unknown as Env;
const secret = 'test-only-never-a-real-bus-key';
const arrivalPath = '/bus/arrivals?stopId=100000001&routeId=100100001&stopOrder=7';
function stub() { return bindings.SEOUL_BUS_COORDINATOR!.get(bindings.SEOUL_BUS_COORDINATOR!.idFromName(BUS_COORDINATOR_NAME)); }
function request(path = arrivalPath, overrides: Partial<Env> = {}) {
  return worker.fetch(new Request(`https://example.test${path}`, { headers: { 'CF-Connecting-IP': '198.51.100.21' } }), {
    ...bindings, IP_RATE_LIMITER: { limit: async () => ({ success: true }) } as RateLimit, ...overrides,
  });
}
function sourceTime() { return new Date(Date.now() + 9 * 3_600_000 - 2_000).toISOString().slice(0, 23).replace('T', ' '); }
function source() {
  return { msgHeader: { headerCd: '0' }, msgBody: { itemList: [{
    stId: '100000001', busRouteId: '100100001', staOrd: '7', arsId: '01001',
    stNm: '서울역', adirection: '시청 방향', dir: '시청 방향', rtNm: '9401', routeType: '6',
    tmX: '126.97', tmY: '37.55', mkTm: sourceTime(), vehId1: '123456789', vehId2: '123456790',
    exps1: '300', exps2: '600', full1: '0', full2: '0', isArrive1: '0', isArrive2: '0',
    isLast1: '0', isLast2: '1', deTourAt: '00', arrmsg1: '5분 후', arrmsg2: '10분 후',
  }] } };
}
function upstream(body: unknown = source(), status = 200) {
  return vi.spyOn(globalThis, 'fetch').mockImplementation(async () => new Response(JSON.stringify(body), { status }));
}
async function count(service: string) {
  return runInDurableObject(stub(), (_instance, state) => state.storage.sql.exec<{ total: number }>(
    'SELECT COUNT(*) AS total FROM bus_upstream_attempts WHERE service = ?', service).one().total);
}
async function seed(service: string, total: number, at = Date.now()) {
  await runInDurableObject(stub(), (_instance, state) => state.storage.transactionSync(() => {
    for (let i = 0; i < total; i++) state.storage.sql.exec(
      'INSERT INTO bus_upstream_attempts (service, attempted_at) VALUES (?, ?)', service, at);
  }));
}
afterEach(async () => { vi.restoreAllMocks(); await reset(); });

describe('Seoul bus endpoints in real Workers runtime and SQLite', () => {
  it.each([['30', 'KEY_REJECTED', 502], ['20', 'ACCESS_FORBIDDEN', 502], ['31', 'KEY_EXPIRED', 502], ['22', 'RATE_LIMITED', 429]])(
    'classifies gateway XML %s separately from HTTP authentication', async (code, suffix, status) => {
      vi.spyOn(globalThis, 'fetch').mockImplementation(async () => new Response(
        `<OpenAPI_ServiceResponse><cmmMsgHeader><errMsg>${secret}</errMsg><returnReasonCode>${code}</returnReasonCode></cmmMsgHeader></OpenAPI_ServiceResponse>`));
      const result = await request();
      expect(result.status).toBe(status);
      const body = await result.text();
      expect(JSON.parse(body)).toMatchObject({ error: { code: 'BUS_GATEWAY_' + suffix } });
      expect(body).not.toContain(secret);
      expect(await count('arrivals')).toBe(1);
    });
  it('provides first and actual next bus, preserving exact identities and source time', async () => {
    const fetch = upstream();
    const response = await request();
    expect(response.status).toBe(200);
    const data: any = await response.json();
    expect(data.provider).toBe('seoul-bus');
    expect(data.arrivals).toHaveLength(2);
    expect(data.arrivals[0]).toMatchObject({ stopId: '100000001', routeId: '100100001', stopOrder: '7',
      vehicleId: '123456789', arrivalSeconds: 300, trustState: 'VALID' });
    expect(data.arrivals[1].vehicleId).not.toBe(data.arrivals[0].vehicleId);
    expect(fetch.mock.calls[0][1]).toMatchObject({ redirect: 'manual' });
    expect(new URL(String(fetch.mock.calls[0][0])).searchParams.get('resultType')).toBe('json');
    expect(await count('arrivals')).toBe(1);
  });
  it('adds stop search and direction-aware route lookup through official station IDs', async () => {
    const fetch = upstream();
    const stops: any = await (await request('/bus/stops?query=서울역')).json();
    expect(stops.stops).toEqual([{ stopId: '100000001', arsId: '01001', name: '서울역', x: 126.97, y: 37.55 }]);
    const routes: any = await (await request('/bus/routes?stopId=100000001&arsId=01001')).json();
    expect(routes.routes[0]).toEqual({ routeId: '100100001', name: '9401', stopOrder: '7', direction: '시청 방향', routeType: '6', supported: true });
    expect(new URL(String(fetch.mock.calls[1][0])).pathname).toBe('/api/rest/stationinfo/getStationByUid');
    expect(await count('stations')).toBe(2);
    expect(await count('arrivals')).toBe(0);
  });
  it('does not break subway health when the optional bus key is not configured', async () => {
    const fetch = upstream();
    const missing = await request(arrivalPath, { SEOUL_BUS_KEY: undefined });
    expect(missing.status).toBe(503);
    expect(await missing.json()).toMatchObject({ error: { code: 'SEOUL_BUS_NOT_CONFIGURED' } });
    expect(await (await worker.fetch(new Request('https://example.test/health'), { ...bindings, SEOUL_BUS_KEY: undefined })).json())
      .toEqual({ status: 'ok' });
    expect(fetch).not.toHaveBeenCalled();
  });
  it('fails closed without required bindings or IP and rejects duplicate/foreign query parameters', async () => {
    const fetch = upstream();
    expect((await request(arrivalPath, { SEOUL_BUS_COORDINATOR: undefined })).status).toBe(503);
    expect((await request(arrivalPath, { IP_RATE_LIMITER: undefined })).status).toBe(503);
    expect((await worker.fetch(new Request(`https://example.test${arrivalPath}`), bindings)).status).toBe(503);
    expect((await request(`${arrivalPath}&routeId=100100002`)).status).toBe(400);
    expect((await request(`${arrivalPath}&url=https://attacker.test`)).status).toBe(400);
    expect(fetch).not.toHaveBeenCalled();
  });
  it('shares the configured IP request limit with existing transit endpoints', async () => {
    const fetch = upstream();
    const response = await request(arrivalPath, { IP_RATE_LIMITER: { limit: async () => ({ success: false }) } as RateLimit });
    expect(response.status).toBe(429);
    expect(response.headers.get('Retry-After')).toBe('60');
    expect(await response.json()).toMatchObject({ error: { code: 'IP_RATE_LIMITED' } });
    expect(fetch).not.toHaveBeenCalled();
  });
  it('caches requests and preserves fetchedAt/source time across restart', async () => {
    const fetch = upstream();
    const first = await request();
    const data: any = await first.json();
    await abortAllDurableObjects();
    const second = await request();
    expect(second.headers.get('X-Cache')).toBe('HIT');
    expect(await second.json()).toEqual(data);
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(await count('arrivals')).toBe(1);
  });
  it('coalesces simultaneous clients without charging their cache hits', async () => {
    const fetch = upstream();
    const results = await Promise.all(Array.from({ length: 20 }, () => request()));
    expect(results.every(result => result.status === 200)).toBe(true);
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(await count('arrivals')).toBe(1);
  });
  it('stores failed requests in a separate persisted short backoff and charges one attempt', async () => {
    const fetch = upstream({}, 503);
    const first = await request();
    expect(first.status).toBe(502);
    await abortAllDurableObjects();
    const second = await request();
    expect(second.headers.get('X-Cache')).toBe('ERROR_HIT');
    expect(await second.json()).toMatchObject({ error: { code: 'BUS_UPSTREAM_UNAVAILABLE' } });
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(await count('arrivals')).toBe(1);
  });
  it('honors the upstream 429 Retry-After across restarts without making repeated provider requests', async () => {
    const fetch = vi.spyOn(globalThis, 'fetch').mockImplementation(async () => new Response('', {
      status: 429, headers: { 'Retry-After': '600' },
    }));
    const first = await request();
    expect(first.status).toBe(429);
    expect(first.headers.get('Retry-After')).toBe('600');
    expect(await first.json()).toMatchObject({ error: { code: 'BUS_UPSTREAM_RATE_LIMITED' } });
    await abortAllDurableObjects();
    const again = await request();
    expect(again.status).toBe(429);
    expect(Number(again.headers.get('Retry-After'))).toBeGreaterThanOrEqual(599);
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(await count('arrivals')).toBe(1);
  });
  it.each([301, 302, 307, 308])('blocks redirect %s without leaking key/URL or retrying', async status => {
    const fetch = upstream({}, status);
    const response = await request();
    expect(await response.json()).toMatchObject({ error: { code: 'BUS_UPSTREAM_REDIRECT' } });
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(await count('arrivals')).toBe(1);
  });
  it('separates the station and arrival service budgets without a new 24-hour warmup', async () => {
    const fetch = upstream();
    await seed('stations', 900);
    const limited = await request('/bus/stops?query=서울역');
    expect(limited.status).toBe(429);
    expect(await limited.json()).toMatchObject({ error: { code: 'BUS_UPSTREAM_BUDGET_EXHAUSTED' } });
    expect((await request()).status).toBe(200);
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(await count('stations')).toBe(900);
    expect(await count('arrivals')).toBe(1);
  });
  it('reserves the last available bus slot atomically across distinct concurrent route queries', async () => {
    const fetch = upstream();
    await seed('arrivals', 899);
    const results = await Promise.all(Array.from({ length: 12 }, (_value, i) => request(
      `/bus/arrivals?stopId=100000001&routeId=${100100001 + i}&stopOrder=7`)));
    expect(results.filter(result => result.status === 429)).toHaveLength(11);
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(await count('arrivals')).toBe(900);
  });
  it('expires rolling attempts at 24 hours and preserves the ledger across restart', async () => {
    upstream();
    await seed('arrivals', 900);
    await abortAllDurableObjects();
    expect((await request()).status).toBe(429);
    await runInDurableObject(stub(), (_instance, state) => state.storage.sql.exec(
      'UPDATE bus_upstream_attempts SET attempted_at = ?', Date.now() - BUS_WINDOW_MS - 1));
    expect((await request()).status).toBe(200);
    expect(await count('arrivals')).toBe(1);
  });
  it('sanitizes malformed, credential-gateway XML and secret-bearing exceptions', async () => {
    const fetch = vi.spyOn(globalThis, 'fetch').mockImplementation(async () => new Response(
      `<OpenAPI_ServiceResponse><cmmMsgHeader><errMsg>${secret}</errMsg></cmmMsgHeader></OpenAPI_ServiceResponse>`));
    const response = await request();
    expect(await response.json()).toMatchObject({ error: { code: 'BUS_GATEWAY_REJECTED' } });
    expect(fetch).toHaveBeenCalledTimes(1);
    vi.restoreAllMocks();
    await reset();
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new Error(`http://ws.bus.go.kr/?serviceKey=${secret}`));
    const failed = await request();
    expect(failed.status).toBe(502);
    expect(await failed.text()).not.toContain(secret);
  });
  it('does not turn mismatched provider route/order rows into a successful arrival', async () => {
    const body = source(); body.msgBody.itemList[0].staOrd = '41';
    upstream(body);
    const response = await request();
    expect(response.status).toBe(409);
    expect(await response.json()).toMatchObject({ error: { code: 'BUS_ARRIVAL_MISMATCH' } });
  });
  it('aborts stalled bus upstream fetches after five seconds with no automatic retry', async () => {
    const fetch = vi.spyOn(globalThis, 'fetch').mockImplementation((_input, init) => new Promise((_resolve, reject) => {
      init?.signal?.addEventListener('abort', () => reject(new Error(secret)), { once: true });
    }));
    const response = await request();
    expect(response.status).toBe(502);
    expect(await response.json()).toMatchObject({ error: { code: 'BUS_UPSTREAM_TIMEOUT' } });
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(await count('arrivals')).toBe(1);
  });
});
