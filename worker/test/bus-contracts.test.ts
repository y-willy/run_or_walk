import { describe, expect, it } from 'vitest';
import androidSharedFixture from '../../app/src/test/resources/seoul_bus_worker_response.json';
import {
  busSourceUrl, normalizeBusPayload, parseBusOperation, parseBusSourceTime, readBusSource,
  refreshBusPayload, type BusOperation,
} from '../src/bus-model';

const now = Date.parse('2026-10-25T10:00:00Z');
const key = 'fake-bus-key-never-a-real-key';
const operation: BusOperation = { kind: 'arrivals', stopId: '100000001', routeId: '100100001', stopOrder: '7' };
function row(overrides: Record<string, unknown> = {}) {
  return { stId: '100000001', busRouteId: '100100001', staOrd: '7', arsId: '01001',
    stNm: '서울역', dir: '시청 방향', adirection: '시청역 방향', rtNm: '9401', routeType: '6',
    mkTm: '2026-10-25 18:59:50.0', vehId1: '123456789', vehId2: '123456790',
    exps1: '300', exps2: '600', full1: '0', full2: '0', isArrive1: '0', isArrive2: '0',
    isLast1: '0', isLast2: '1', deTourAt: '00', arrmsg1: '5분 후', arrmsg2: '10분 후', ...overrides };
}
function source(rows: unknown = [row()]) { return { msgHeader: { headerCd: '0' }, msgBody: { itemList: rows } }; }
function normalize(overrides: Record<string, unknown> = {}) {
  return normalizeBusPayload(operation, source([row(overrides)]), now, key) as any;
}

describe('Seoul bus request identities and fixed official origins', () => {
  it('requires the official stop, route and stop order, not Kakao IDs or a name-only match', () => {
    expect(parseBusOperation(new URL('https://w/bus/arrivals?stopId=100000001&routeId=100100001&stopOrder=007')))
      .toEqual(operation);
    for (const query of ['stopId=kakao-1&routeId=100100001&stopOrder=7',
      'stopId=100000001&routeId=100100001', 'stopId=100000001&routeId=100100001&stopOrder=0',
      'stopId=100000001&routeId=100100001&stopOrder=7&stopOrder=8',
      'stopId=100000001&routeId=100100001&stopOrder=7&url=https://attacker.test']) {
      expect(parseBusOperation(new URL(`https://w/bus/arrivals?${query}`))).toBeNull();
    }
  });
  it('requires the matching ARS number for direction-aware stop routes', () => {
    expect(parseBusOperation(new URL('https://w/bus/routes?stopId=100000001&arsId=01001')))
      .toEqual({ kind: 'routes', stopId: '100000001', arsId: '01001' });
    expect(parseBusOperation(new URL('https://w/bus/routes?stopId=100000001'))).toBeNull();
    expect(parseBusOperation(new URL('https://w/bus/routes?stopId=100000001&arsId=00000'))).toBeNull();
  });
  it('accepts a station name or bounded WGS84 nearby query, not arbitrary URLs', () => {
    expect(parseBusOperation(new URL('https://w/bus/stops?query=%20서울역%20'))).toEqual({ kind: 'stops', query: '서울역' });
    expect(parseBusOperation(new URL('https://w/bus/stops?x=127.000&y=37.5'))).toEqual({ kind: 'nearby', x: '127', y: '37.5' });
    for (const query of ['query=https://attacker.test', 'query=서울역&x=127&y=37', 'x=181&y=37', 'x=127', 'query=']) {
      expect(parseBusOperation(new URL(`https://w/bus/stops?${query}`))).toBeNull();
    }
  });
  it('uses documented resultType=json, exact arrival ord and encodes the key only once', () => {
    const plain = new URL(busSourceUrl(operation, 'fake+key/=='));
    const encoded = new URL(busSourceUrl(operation, 'fake%2Bkey%2F%3D%3D'));
    expect(encoded.toString()).toBe(plain.toString());
    expect(plain.origin).toBe('http://ws.bus.go.kr');
    expect(plain.pathname).toBe('/api/rest/arrive/getArrInfoByRoute');
    expect(Object.fromEntries(plain.searchParams)).toMatchObject({ stId: '100000001', busRouteId: '100100001', ord: '7', resultType: 'json' });
    expect(new URL(busSourceUrl({ kind: 'routes', stopId: '100000001', arsId: '01001' }, key)).pathname)
      .toBe('/api/rest/stationinfo/getStationByUid');
  });
});

describe('official source envelopes and stop/direction mappings', () => {
  it('accepts a single item and a ServiceResult envelope but never an error as an empty success', () => {
    expect(readBusSource({ ServiceResult: source(row()) })).toHaveLength(1);
    expect(readBusSource({ msgHeader: { headerCd: 0 }, msgBody: null })).toEqual([]);
    expect(() => readBusSource({ msgHeader: { headerCd: '4', headerMsg: key }, msgBody: null }))
      .toThrow('BUS_ROUTE_NOT_FOUND');
    expect(() => readBusSource({ msgHeader: { headerCd: '8' }, msgBody: null })).toThrow('BUS_SERVICE_ENDED');
    expect(() => readBusSource({ msgHeader: { headerCd: '30', headerMsg: key } })).toThrow('BUS_UPSTREAM_REJECTED');
  });
  it('never treats projected GRS80 posX/posY as geographic coordinates', () => {
    const data = normalizeBusPayload({ kind: 'stops', query: '서울역' }, source([
      { stId: '100000001', arsId: '01001', stNm: '서울역', tmX: '126.97', tmY: '37.55' },
      { stId: '100000002', arsId: '01002', stNm: '투영좌표', posX: '193630', posY: '457015' },
    ]), now, key) as any;
    expect(data.stops).toEqual([{ stopId: '100000001', arsId: '01001', name: '서울역', x: 126.97, y: 37.55 }]);
  });
  it('preserves five-digit ARS identity even if an upstream JSON number drops its leading zero', () => {
    const data = normalizeBusPayload({ kind: 'stops', query: '01001' }, source(row({
      arsId: 1001, gpsX: '126.97', gpsY: '37.55',
    })), now, key) as any;
    expect(data.stops[0].arsId).toBe('01001');
    const routes = normalizeBusPayload({ kind: 'routes', stopId: '100000001', arsId: '01001' },
      source(row({ arsId: 1001 })), now, key) as any;
    expect(routes.routes[0].routeId).toBe('100100001');
  });
  it('keeps both route stop orders distinct and does not use the route-list array order as direction', () => {
    const data = normalizeBusPayload({ kind: 'routes', stopId: '100000001', arsId: '01001' }, source([
      row({ staOrd: '41', adirection: '반대 방향' }), row(), row(),
      row({ stId: '100000002', staOrd: '8' }), row({ routeType: '8', busRouteId: '200100001', rtNm: '경기' }),
    ]), now, key) as any;
    expect(data.routes).toHaveLength(3);
    expect(data.routes.map((r: any) => r.stopOrder)).toEqual(['41', '7', '7']);
    expect(data.routes.find((r: any) => r.routeType === '8').supported).toBe(false);
    expect(data.routes.find((r: any) => r.routeType === '6').supported).toBe(true);
  });
  it('rejects conflicting metadata and mismatched stop/route/order responses', () => {
    expect(() => normalizeBusPayload({ kind: 'routes', stopId: '100000001', arsId: '01001' },
      source([row(), row({ adirection: '다른 방향' })]), now, key)).toThrow('BUS_ROUTE_AMBIGUOUS');
    expect(() => normalize({ staOrd: '8' })).toThrow('BUS_ARRIVAL_MISMATCH');
    expect(() => normalize({ stId: '100000002' })).toThrow('BUS_ARRIVAL_MISMATCH');
    expect(() => normalize({ busRouteId: '100100002' })).toThrow('BUS_ARRIVAL_MISMATCH');
  });
});

describe('numeric ETA, real vehicles, source-time trust and capacity', () => {
  it('produces the exact canonical response consumed by the Android integration fixture', () => {
    const officialRow = row({ staOrd: '12', stNm: '종로2가', dir: '서울역 방면', rtNm: '100', routeType: '3',
      mkTm: '2026-10-25 17:00:00.0', vehId1: '100001001', vehId2: '100001002' });
    const result = normalizeBusPayload({ ...operation, stopOrder: '12' }, source([officialRow]),
      Date.parse(androidSharedFixture.fetchedAt), key);
    expect(result).toEqual(androidSharedFixture);
  });
  it('parses the documented KST fractional mkTm and uses it as the ETA baseline', () => {
    expect(parseBusSourceTime('2026-10-25 19:00:00.123456')).toBe(now + 123);
    expect(parseBusSourceTime('2026-02-30 19:00:00.0')).toBeNull();
    const data = normalize();
    expect(data.sourceGeneratedAt).toBe('2026-10-25T09:59:50.000Z');
    expect(data.arrivals[0]).toMatchObject({ observedAt: data.sourceGeneratedAt, arrivalSeconds: 300,
      vehicleId: '123456789', routeType: '6', full: false, last: false, trustState: 'VALID' });
    expect(data.arrivals[1]).toMatchObject({ vehicleId: '123456790', arrivalSeconds: 600, last: true, trustState: 'VALID' });
  });
  it.each(['0', '-1', 'NaN', 'Infinity', '1.2', '', null, 86_401])('does not manufacture a usable ETA from %s', eta => {
    const data = normalize({ exps1: eta });
    expect(data.arrivals[0].trustState).not.toBe('VALID');
    expect(data.arrivals[0].message).toBe('5분 후');
  });
  it.each([{ full1: '1' }, { full1: undefined }, { vehId1: '0' }, { vehId1: '' }, { vehId1: 'invented' },
    { isArrive1: '1' }, { isArrive1: undefined }, { deTourAt: '11' }, { deTourAt: undefined },
    { routeType: '8' }, { routeType: '7' }, { arrmsg1: '운행종료' }])('does not recommend uncertain or full buses: %o', fields => {
    expect(normalize(fields).arrivals[0].trustState).toBe('UNKNOWN');
  });
  it('never pairs the same repeated vehicle as the next bus', () => {
    const data = normalize({ vehId2: '123456789' });
    expect(data.arrivals.every((r: any) => r.trustState === 'UNKNOWN' && r.issues.includes('DUPLICATE_VEHICLE'))).toBe(true);
  });
  it('keeps future-at-query data quarantined even when wall time catches up', () => {
    const data = normalize({ mkTm: '2026-10-25 19:00:05.0' });
    expect(data.arrivals[0].trustState).toBe('FUTURE_TIMESTAMP');
    expect(JSON.parse(refreshBusPayload(JSON.stringify(data), now + 10_000)).arrivals[0].trustState).toBe('FUTURE_TIMESTAMP');
  });
  it('does not renew source freshness or a passed ETA on a cache hit', () => {
    const data = normalize();
    const expired = JSON.parse(refreshBusPayload(JSON.stringify(data), now + 51_000));
    expect(expired.arrivals[0].trustState).toBe('STALE');
    expect(expired.fetchedAt).toBe(data.fetchedAt);
    const passed = normalize({ exps1: '15' });
    expect(JSON.parse(refreshBusPayload(JSON.stringify(passed), now + 6_000)).arrivals[0].trustState).toBe('STALE');
  });
  it('does not return key-bearing URLs or secret strings in any allowed field', () => {
    const data = normalize({ arrmsg1: `http://ws.bus.go.kr/?serviceKey=${key}`, plainNo1: key, rtNm: key });
    expect(JSON.stringify(data)).not.toContain(key);
    expect(JSON.stringify(data)).not.toContain('http://');
  });
});
