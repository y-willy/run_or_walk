import { env } from 'cloudflare:workers';
import { reset, runInDurableObject } from 'cloudflare:test';
import { afterEach, describe, expect, it, vi } from 'vitest';
import worker, { type Env } from '../src/index';

const bindings = env as unknown as Env;
const key = 'test-gbis-station-key-only';
const near = '/gyeonggi/bus/nearby?x=127.05&y=37.25';
const via = '/gyeonggi/bus/via?stationId=200000177';
const coordinator = () => bindings.GYEONGGI_BUS_COORDINATOR!.get(
  bindings.GYEONGGI_BUS_COORDINATOR!.idFromName('gyeonggi-bus-global-v1'));
function request(path: string, override: Partial<Env> = {}) {
  return worker.fetch(new Request('https://example.test' + path, {
    headers: { 'CF-Connecting-IP': '198.51.100.77' },
  }), {
    ...bindings, GYEONGGI_STATION_KEY: key, GYEONGGI_BUS_KEY: 'test-arrival-key',
    IP_RATE_LIMITER: { limit: async () => ({ success: true }) } as RateLimit,
    ...override,
  });
}
function source(path: string) {
  const row = path.endsWith('getBusStationAroundListv2') ? {
    busStationAroundList: [{ stationId: 200000177, stationName: '영통역', mobileNo: 12345,
      x: 127.05, y: 37.25, distance: 12 }],
  } : { busRouteList: [{ routeId: 200000037, routeName: '5100',
    routeDestName: '강남', staOrder: 19 }] };
  return { response: { msgHeader: { resultCode: 0 }, msgBody: row } };
}
afterEach(async () => { vi.restoreAllMocks(); await reset(); });

describe('GBIS automatic station discovery uses the same conservative SQLite ledger', () => {
  it('connects the documented nearby response through official route order to two actual ETA slots', async () => {
    const upstream = vi.spyOn(globalThis, 'fetch').mockImplementation(async input => {
      const path = new URL(String(input)).pathname;
      if (!path.endsWith('getBusArrivalListv2')) return new Response(JSON.stringify(source(path)));
      const queryTime = new Date(Date.now() + 9 * 3_600_000 - 2_000).toISOString().slice(0, 19).replace('T', ' ');
      return new Response(JSON.stringify({ response: { msgHeader: { resultCode: 0, queryTime },
        msgBody: { busArrivalList: [{ stationId: 200000177, routeId: 200000037, staOrder: 19,
          routeName: '5100', routeDestName: '강남', flag: 'RUN',
          vehId1: 123456, vehId2: 123457, predictTimeSec1: 180, predictTimeSec2: 360 }] } } }));
    });
    const nearby: any = await (await request(near)).json();
    expect(nearby.stops[0].stationId).toBe('200000177');
    const via: any = await (await request('/gyeonggi/bus/via?stationId=' + nearby.stops[0].stationId)).json();
    expect(via.routes[0]).toMatchObject({ routeName: '5100', routeId: '200000037', staOrder: '19', destination: '강남' });
    const arrivals: any = await (await request('/gyeonggi/bus/arrivals?stationId=' + nearby.stops[0].stationId)).json();
    expect(arrivals.arrivals).toMatchObject([
      { routeId: via.routes[0].routeId, staOrder: via.routes[0].staOrder, sequence: 1, arrivalSeconds: 180, trustState: 'VALID' },
      { routeId: via.routes[0].routeId, staOrder: via.routes[0].staOrder, sequence: 2, arrivalSeconds: 360, trustState: 'VALID' },
    ]);
    expect(upstream).toHaveBeenCalledTimes(3);
  });
  it.each([[401, 'GYEONGGI_KEY_REJECTED'], [403, 'GYEONGGI_ACCESS_FORBIDDEN'],
    [503, 'GYEONGGI_UPSTREAM_UNAVAILABLE']])('distinguishes upstream HTTP %s without exposing its body', async (status, code) => {
    const upstream = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('serviceKey=secret-value', { status: Number(status) }));
    const result = await request(near);
    expect(result.status).toBe(502);
    const body = await result.text();
    expect(JSON.parse(body)).toMatchObject({ error: { code } });
    expect(body).not.toContain('secret-value');
    expect(upstream).toHaveBeenCalledTimes(1);
    expect((await request(near)).headers.get('X-Cache')).toBe('ERROR_HIT');
    expect(upstream).toHaveBeenCalledTimes(1);
  });
  it.each([['30', 'KEY_REJECTED', 502], ['20', 'ACCESS_FORBIDDEN', 502], ['22', 'RATE_LIMITED', 429]])(
    'handles XML error %s even with upstream HTTP 200', async (code, suffix, status) => {
      vi.spyOn(globalThis, 'fetch').mockImplementation(async () => new Response(
        `<OpenAPI_ServiceResponse><cmmMsgHeader><errMsg>${key}</errMsg><returnReasonCode>${code}</returnReasonCode></cmmMsgHeader></OpenAPI_ServiceResponse>`));
      const result = await request(near);
      expect(result.status).toBe(status);
      const text = await result.text();
      expect(JSON.parse(text)).toMatchObject({ error: { code: 'GYEONGGI_GATEWAY_' + suffix } });
      expect(text).not.toContain(key);
    });
  it('advertises the deployed contract independently of optional API secrets', async () => {
    const response = await worker.fetch(new Request('https://example.test/health'), bindings);
    expect(response.headers.get('X-PaceNudge-Contract')).toBe('2026-10-25.2');
    expect(await response.json()).toEqual({ status: 'ok' });
  });
  it('requires the separately approved station service secret', async () => {
    const network = vi.spyOn(globalThis, 'fetch');
    const result = await request(near, { GYEONGGI_STATION_KEY: undefined });
    expect(result.status).toBe(503);
    expect(await result.json()).toMatchObject({ error: { code: 'GYEONGGI_NOT_CONFIGURED' } });
    expect(network).not.toHaveBeenCalled();
  });
  it('resolves nearby stops and route order with two budgeted attempts and cache reuse', async () => {
    const upstream = vi.spyOn(globalThis, 'fetch').mockImplementation(async input => {
      const path = new URL(String(input)).pathname;
      return new Response(JSON.stringify(source(path)), { status: 200 });
    });
    const stops = await request(near);
    expect(stops.status).toBe(200);
    expect(await stops.json()).toMatchObject({ kind: 'nearby',
      stops: [{ stationId: '200000177', name: '영통역' }] });
    const routes = await request(via);
    expect(routes.status).toBe(200);
    expect(await routes.json()).toMatchObject({ kind: 'via', stationId: '200000177',
      routes: [{ routeId: '200000037', routeName: '5100', staOrder: '19' }] });
    expect(upstream).toHaveBeenCalledTimes(2);
    for (const args of upstream.mock.calls) {
      expect(args[1]).toMatchObject({ redirect: 'manual' });
      expect(String(args[0])).not.toContain('test-arrival-key');
    }
    const again = await request(near);
    expect(again.headers.get('X-Cache')).toBe('HIT');
    expect(upstream).toHaveBeenCalledTimes(2);
    const count = await runInDurableObject(coordinator(), (_instance, state) =>
      state.storage.sql.exec<{ total: number }>('SELECT COUNT(*) AS total FROM attempts').one().total);
    expect(count).toBe(2);
  });
  it('rejects malformed or duplicated official lookup params without consuming budget', async () => {
    const fetch = vi.spyOn(globalThis, 'fetch');
    expect((await request('/gyeonggi/bus/nearby?x=127&x=126&y=37')).status).toBe(400);
    expect((await request('/gyeonggi/bus/via?stationId=123')).status).toBe(400);
    expect(fetch).not.toHaveBeenCalled();
  });
});
