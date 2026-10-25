import { describe, expect, it } from 'vitest';
import { parseGyeonggiLookup, gyeonggiLookupSourceUrl, normalizeGyeonggiLookup } from './gyeonggi-lookup-model';

const stopId = '200000177', routeId = '200000037';
const nearby = { kind: 'nearby' as const, x: 127.05, y: 37.25 };
const search = { kind: 'search' as const, query: '영통역' };
const via = { kind: 'via' as const, stationId: stopId };
const envelope = (key: string, row: unknown) => ({
  response: { msgHeader: { resultCode: 0, queryTime: '2026-10-25 16:25:50' },
    msgBody: { [key]: [row] } },
});
describe('GBIS station discovery contract', () => {
  it('strictly validates coordinate and station operations', () => {
    expect(parseGyeonggiLookup(new URL('https://x/gyeonggi/bus/nearby?x=127.05&y=37.25')))
      .toEqual(nearby);
    expect(parseGyeonggiLookup(new URL('https://x/gyeonggi/bus/search?query=%EC%98%81%ED%86%B5%EC%97%AD')))
      .toEqual(search);
    expect(parseGyeonggiLookup(new URL('https://x/gyeonggi/bus/search?query='))).toBeNull();
    expect(parseGyeonggiLookup(new URL('https://x/gyeonggi/bus/via?stationId='+stopId)))
      .toEqual(via);
    expect(parseGyeonggiLookup(new URL('https://x/gyeonggi/bus/nearby?x=NaN&y=37'))).toBeNull();
    expect(parseGyeonggiLookup(new URL('https://x/gyeonggi/bus/nearby?x=127&x=128&y=37'))).toBeNull();
    expect(parseGyeonggiLookup(new URL('https://x/gyeonggi/bus/nearby?x=127&y=37&secret=x'))).toBeNull();
    expect(parseGyeonggiLookup(new URL('https://x/gyeonggi/bus/via?stationId=123'))).toBeNull();
  });
  it('builds only the official station service URLs and keeps key off responses', () => {
    const a = new URL(gyeonggiLookupSourceUrl(nearby, 'fake-key'));
    expect(a.pathname).toBe('/6410000/busstationservice/v2/getBusStationAroundListv2');
    expect(a.searchParams.get('x')).toBe('127.05');
    const b = new URL(gyeonggiLookupSourceUrl(via, 'fake-key'));
    expect(b.pathname).toBe('/6410000/busstationservice/v2/getBusStationViaRouteListv2');
    expect(b.searchParams.get('stationId')).toBe(stopId);
    const c = new URL(gyeonggiLookupSourceUrl(search, 'fake-key'));
    expect(c.pathname).toBe('/6410000/busstationservice/v2/getBusStationListv2');
    expect(c.searchParams.get('keyword')).toBe('영통역');
  });
  it('normalizes official station and its route/order without guessing', () => {
    const stations = normalizeGyeonggiLookup(envelope('busStationList', {
      stationId: 200000177, stationName: '영통역', x: 127.05, y: 37.25,
      mobileNo: 12345, distance: 35,
    }), nearby);
    expect(stations).toEqual({ provider: 'gyeonggi-gbis', kind: 'nearby',
      stops: [{ stationId: stopId, name: '영통역', mobileNo: '12345', x: 127.05,
        y: 37.25, distanceMeters: 35 }] });
    const foundByName = normalizeGyeonggiLookup(envelope('busStationList', {
      stationId: 200000177, stationName: '영통역', x: 127.05, y: 37.25,
    }), search);
    expect(foundByName).toEqual({ provider: 'gyeonggi-gbis', kind: 'search',
      stops: [{ stationId: stopId, name: '영통역', mobileNo: null,
        x: 127.05, y: 37.25, distanceMeters: null }] });
    const routes = normalizeGyeonggiLookup(envelope('busRouteList', {
      routeId: 200000037, routeName: '5100', staOrder: 19, routeDestName: '강남',
    }), via);
    expect(routes).toEqual({ provider: 'gyeonggi-gbis', kind: 'via', stationId: stopId,
      routes: [{ routeId, routeName: '5100', staOrder: '19', destination: '강남' }] });
  });
  it('fails closed for bad result codes and unreasonable stations', () => {
    expect(() => normalizeGyeonggiLookup({
      response: { msgHeader: { resultCode: 1 }, msgBody: {} },
    }, nearby)).toThrow();
    const data = normalizeGyeonggiLookup(envelope('busStationList', {
      stationId: 200000177, stationName: '영통역', x: 0, y: 0, distance: 35,
    }), nearby);
    expect(data.kind === 'nearby' && data.stops).toEqual([]);
  });
});
