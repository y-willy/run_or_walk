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
    busStationList: [{ stationId: 200000177, stationName: '영통역', mobileNo: 12345,
      x: 127.05, y: 37.25, distance: 12 }],
  } : { busRouteList: [{ routeId: 200000037, routeName: '5100',
    routeDestName: '강남', staOrder: 19 }] };
  return { response: { msgHeader: { resultCode: 0 }, msgBody: row } };
}
afterEach(async () => { vi.restoreAllMocks(); await reset(); });

describe('GBIS automatic station discovery uses the same conservative SQLite ledger', () => {
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
