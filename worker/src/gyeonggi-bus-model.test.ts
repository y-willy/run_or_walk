import { describe, expect, it } from 'vitest';
import {
  parseGyeonggiOperation, gyeonggiSourceUrl, normalizeGyeonggiResponse,
  computeGyeonggiTrust, refreshGyeonggiPayload,
} from './gyeonggi-bus-model';

const fetched = Date.parse('2026-10-25T07:25:59.000Z');
const fixture = (change: Record<string, unknown> = {}) => ({
  response: {
    msgHeader: { resultCode: 0, queryTime: '2026-10-25 16:25:50' },
    msgBody: { busArrivalList: [{
      stationId: 200000177, routeId: 200000037, staOrder: 19, routeName: '9',
      routeDestName: '수원역', flag: 'RUN', vehId1: 123456, predictTimeSec1: 180,
      locationNo1: 3, remainSeatCnt1: 4, ...change,
    }] },
  },
});
const list = { kind: 'list' as const, stationId: '200000177' };
describe('Gyeonggi GBIS query boundary', () => {
  it('accepts only the two official operations with valid GBIS IDs', () => {
    expect(parseGyeonggiOperation(new URL('https://host/gyeonggi/bus/arrivals?stationId=200000177'))).toEqual(list);
    expect(parseGyeonggiOperation(new URL('https://host/gyeonggi/bus/arrival?stationId=200000177&routeId=200000037&staOrder=19'))).toEqual({
      kind: 'item', stationId: '200000177', routeId: '200000037', staOrder: '19',
    });
    expect(parseGyeonggiOperation(new URL('https://host/gyeonggi/bus/arrival?stationId=200000177&routeId=200000037'))).toBeNull();
    expect(parseGyeonggiOperation(new URL('https://host/gyeonggi/bus/arrivals?stationId=200000177&stationId=200000177'))).toBeNull();
    expect(parseGyeonggiOperation(new URL('https://host/gyeonggi/bus/arrivals?stationId=200000177&url=https://evil'))).toBeNull();
    expect(parseGyeonggiOperation(new URL('https://host/gyeonggi/bus/arrivals?stationId=kakao-abc'))).toBeNull();
  });
  it('constructs a fixed HTTPS URL and encodes a supplied key only once', () => {
    const url = new URL(gyeonggiSourceUrl(list, 'fake+only/key'));
    expect(url.origin).toBe('https://apis.data.go.kr');
    expect(url.pathname).toMatch(/getBusArrivalListv2$/);
    expect(url.searchParams.get('serviceKey')).toBe('fake+only/key');
    expect(new URL(gyeonggiSourceUrl(list, 'fake%2Bonly%2Fkey')).searchParams.get('serviceKey')).toBe('fake+only/key');
  });
});
describe('GBIS normalization and conservative timestamps', () => {
  it('keeps authoritative stop, route and direction, and a second-level ETA', () => {
    const output = normalizeGyeonggiResponse(fixture(), list, fetched);
    expect(output.arrivals).toHaveLength(1);
    expect(output.arrivals[0]).toMatchObject({
      stationId: '200000177', routeId: '200000037', staOrder: '19',
      direction: '수원역', vehicleId: '123456', arrivalSeconds: 180, trustState: 'VALID',
    });
    expect(output.arrivals[0].observedAt).toBe('2026-10-25T07:25:50.000Z');
  });
  it('does not interpret a zero-second ETA as an arriving vehicle', () => {
    const output = normalizeGyeonggiResponse(fixture({ predictTimeSec1: 0 }), list, fetched);
    expect(output.arrivals[0].trustState).toBe('UNKNOWN');
  });
  it('marks minute-only estimates as imprecise for catchability', () => {
    const output = normalizeGyeonggiResponse(fixture({ predictTimeSec1: undefined, predictTime1: 3 }), list, fetched);
    expect(output.arrivals[0].etaPrecision).toBe('minutes');
    expect(output.arrivals[0].trustState).toBe('UNKNOWN');
  });
  it('rejects future and expired source clocks and preserves them after cache hits', () => {
    const future = fixture();
    future.response.msgHeader.queryTime = '2026-10-25 16:30:00';
    const result = normalizeGyeonggiResponse(future, list, fetched);
    expect(result.arrivals[0].trustState).toBe('FUTURE_TIMESTAMP');
    expect(JSON.parse(refreshGyeonggiPayload(JSON.stringify(result), fetched + 5_000)).arrivals[0].trustState).toBe('FUTURE_TIMESTAMP');
    const old = normalizeGyeonggiResponse(fixture(), list, fetched);
    expect(computeGyeonggiTrust(old.arrivals[0], fetched, fetched + 90_000)).toBe('STALE');
  });
  it('does not accept an operation for the wrong route or stop', () => {
    expect(normalizeGyeonggiResponse(fixture({ routeId: 200000999 }), {
      kind: 'item', stationId: '200000177', routeId: '200000037', staOrder: '19',
    }, fetched).arrivals).toEqual([]);
  });
  it('does not leak unrelated response fields or a key', () => {
    const output = normalizeGyeonggiResponse(fixture({ rawUrl: 'https://example/?serviceKey=secret', plateNo1: '12가3456' }), list, fetched);
    expect(JSON.stringify(output)).not.toContain('secret');
    expect(JSON.stringify(output)).not.toContain('12가3456');
  });
  it('rejects missing and rejected GBIS headers', () => {
    expect(() => normalizeGyeonggiResponse({ response: { msgBody: {} } }, list, fetched)).toThrow();
    expect(() => normalizeGyeonggiResponse({ response: { msgHeader: { resultCode: 1 } } }, list, fetched)).toThrow();
  });
});
