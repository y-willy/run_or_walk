import { describe, expect, it } from 'vitest';
import currentGangnam from '../../app/src/test/resources/worker_current_gangnam_response.json';
import stationNames from '../src/stations.json';
import {
  MAX_SOURCE_AGE_MS,
  normalizeStation,
  normalizeUpstream,
  parseSourceTime,
  refreshCachedPayload,
  SourcePayloadError,
  trustState,
} from '../src/model';

const fetchedAt = Date.parse('2026-10-24T03:00:30.000Z');
const validRow = () => ({
  statnNm: '강남',
  statnTid: '1002000221',
  btrainNo: '1234',
  trainLineNm: '성수행 - 역삼방면',
  btrainSttus: '일반',
  bstatnId: '1002000211',
  statnId: '1002000222',
  subwayId: '1002',
  updnLine: '외선',
  bstatnNm: '성수',
  barvlDt: '120',
  recptnDt: '2026-10-24 12:00:00',
  arvlCd: '99',
});
const requiredIdentity = ['statnId', 'subwayId', 'updnLine', 'statnNm', 'statnTid',
  'btrainNo', 'trainLineNm', 'btrainSttus', 'bstatnId', 'bstatnNm'];
const info000 = (rows: unknown[]) => ({ errorMessage: { code: 'INFO-000', status: 200 }, realtimeArrivalList: rows });

describe('station input contract', () => {
  it('normalizes whitespace, NFC, and a station suffix to one allowlisted key', () => {
    expect(normalizeStation(' 강남역 ')).toBe('강남');
    expect(normalizeStation('강남')).toBe('강남');
    expect(normalizeStation('서울역')).toBe('서울');
  });

  it('preserves official spaces and parentheses', () => {
    expect(normalizeStation('4.19   민주묘지역')).toBe('4.19 민주묘지');
    expect(normalizeStation('총신대입구(이수)')).toBe('총신대입구(이수)');
  });

  it('preserves every exact canonical catalog name before considering a trailing suffix', () => {
    for (const name of stationNames) expect(normalizeStation(name)).toBe(name);
  });

  it.each([null, '', '없는역', 'https://example.com', '../강남', '강남/../서울',
    '강남\u0000', '강남\n', '강남\u007f', '강'.repeat(101)])('rejects unsafe or unknown input %j', input => {
    expect(normalizeStation(input)).toBeNull();
  });
});

describe('Seoul timestamp contract', () => {
  it('interprets recptnDt as KST, not UTC or the machine time zone', () => {
    expect(parseSourceTime('2026-10-25 06:45:37')).toBe(Date.parse('2026-10-24T21:45:37Z'));
  });

  it('accepts a real leap day', () => {
    expect(parseSourceTime('2024-02-29 09:00:00')).toBe(Date.parse('2024-02-29T00:00:00Z'));
  });

  it.each(['2026-02-29 12:00:00', '2026-04-31 12:00:00', '2026-00-01 12:00:00',
    '2026-13-01 12:00:00', '2026-10-24 24:00:00', '2026-10-24 12:60:00',
    '2026-10-24 12:00:60', '2026-10-24T12:00:00Z', '', null, 0])('rejects invalid source timestamp %j', value => {
    expect(parseSourceTime(value)).toBeNull();
  });
});

describe('arrival trust contract', () => {
  it('classifies the user-provided live response without inventing omitted official fields', () => {
    const queriedAt = Date.parse(currentGangnam.fetchedAt);
    expect(currentGangnam.arrivals.map(row => trustState(row, queriedAt))).toEqual([
      'FUTURE_TIMESTAMP', 'UNKNOWN', 'FUTURE_TIMESTAMP', 'UNKNOWN',
      'FUTURE_TIMESTAMP', 'UNKNOWN', 'UNKNOWN',
    ]);
    expect(parseSourceTime(currentGangnam.arrivals[0].receivedAt)! - queriedAt).toBe(266_944);
    expect(currentGangnam.arrivals[1]).not.toHaveProperty('arvlCd');
    expect(currentGangnam.arrivals[1]).not.toHaveProperty('btrainNo');
  });

  it('accepts a fresh positive ETA with sufficient train identity and state', () => {
    expect(trustState(validRow(), fetchedAt)).toBe('VALID');
  });

  it('marks the supplied real KST/UTC mismatch as future data', () => {
    expect(trustState({ ...validRow(), recptnDt: '2026-10-25 06:45:37' },
      Date.parse('2026-10-24T21:40:52.482Z'))).toBe('FUTURE_TIMESTAMP');
  });

  it('does not allow even one millisecond of unexplained source clock future', () => {
    expect(trustState(validRow(), Date.parse('2026-10-24T02:59:59.999Z'))).toBe('FUTURE_TIMESTAMP');
  });

  it('does not interpret a zero ETA or a prior-station message as boarding arrival', () => {
    expect(trustState({ ...validRow(), barvlDt: '0', arvlMsg2: '[2]번째 전역 (양재시민의숲)' }, fetchedAt)).toBe('UNKNOWN');
  });

  it.each(['0', '1', '2', '', 'unrecognized'])('does not use arrival/departure/unknown code %j for a positive ETA', arvlCd => {
    expect(trustState({ ...validRow(), arvlCd }, fetchedAt)).toBe('UNKNOWN');
  });

  it.each(requiredIdentity)('requires identity field %s', field => {
    const row: Record<string, unknown> = validRow();
    delete row[field];
    expect(trustState(row, fetchedAt)).toBe('UNKNOWN');
  });

  it.each(requiredIdentity)('rejects whitespace identity field %s', field => {
    expect(trustState({ ...validRow(), [field]: '   ' }, fetchedAt)).toBe('UNKNOWN');
  });

  it.each(['미확인', 'UP', '1'])('does not interpret unknown upstream direction %s', updnLine => {
    expect(trustState({ ...validRow(), updnLine }, fetchedAt)).toBe('UNKNOWN');
  });

  it('rejects old or already elapsed predictions', () => {
    const source = parseSourceTime(validRow().recptnDt)!;
    expect(trustState({ ...validRow(), barvlDt: '300' }, source + MAX_SOURCE_AGE_MS + 1)).toBe('STALE');
    expect(trustState({ ...validRow(), barvlDt: '30' }, fetchedAt)).toBe('STALE');
  });

  it.each([-1, '-1', NaN, Infinity, 'NaN', 'Infinity', '1.5', '', ' 120 ', null,
    undefined, true, '86401', Number.MAX_SAFE_INTEGER + 1])('rejects invalid ETA %j without inventing zero', barvlDt => {
    expect(trustState({ ...validRow(), barvlDt }, fetchedAt)).toBe('INVALID_ETA');
  });

  it('fails safely for missing source time and an invalid query clock', () => {
    expect(trustState({ ...validRow(), recptnDt: undefined }, fetchedAt)).toBe('UNKNOWN');
    expect(trustState(validRow(), NaN)).toBe('UNKNOWN');
  });
});

describe('public response contract and secret filtering', () => {
  it('retains real official source fields and canonical aliases without invented missing data', () => {
    const source = { ...validRow(), statnNm: '강남', trainLineNm: '성수행 - 역삼방면',
      arvlMsg2: '2분 후', btrainNo: '1234', ordkey: '01001', bstatnId: '1002000211',
      btrainSttus: '일반', extraUntrusted: 'must not survive' };
    const response = normalizeUpstream(info000([source]), '강남', fetchedAt, 'test-only-key');
    expect(response.fetchedAt).toBe('2026-10-24T03:00:30.000Z');
    expect(response.realtime).toBe(true);
    expect(response.realtimeArrivalList).toEqual(response.arrivals);
    expect(response.arrivals).toHaveLength(1);
    expect(response.arrivals[0]).toMatchObject({ ...validRow(), station: '강남',
      stationId: source.statnId, lineId: source.subwayId, direction: source.updnLine,
      destination: source.trainLineNm, arrivalSeconds: source.barvlDt, arrivalMessage: source.arvlMsg2,
      receivedAt: source.recptnDt, fetchedAt: response.fetchedAt, trustState: 'VALID',
      btrainNo: '1234', ordkey: '01001', btrainSttus: '일반', bstatnId: '1002000211' });
    expect(response.arrivals[0].destination).toBe('성수행 - 역삼방면');
    expect(response.arrivals[0].bstatnNm).toBe('성수');
    expect(response.arrivals[0]).not.toHaveProperty('extraUntrusted');
    expect(response.arrivals[0]).not.toHaveProperty('arvlMsg3');
    expect(response.arrivals[0]).not.toHaveProperty('remainingSeconds');
  });

  it('filters secret values, upstream URLs, nested values and control characters even in allowed fields', () => {
    const key = 'test/sentinel-key';
    const source = { ...validRow(), arvlMsg2: `failure ${key}`, trainLineNm: encodeURIComponent(key),
      btrainNo: 'http://swopenapi.seoul.go.kr/api/key', ordkey: 'bad\nvalue', arvlMsg3: { key },
      rowNum: Infinity };
    const serialized = JSON.stringify(normalizeUpstream(info000([source]), '강남', fetchedAt, key));
    expect(serialized).not.toContain(key);
    expect(serialized).not.toContain(encodeURIComponent(key));
    expect(serialized).not.toContain('http://');
    const row = JSON.parse(serialized).arrivals[0];
    for (const field of ['arvlMsg2', 'arrivalMessage', 'trainLineNm', 'btrainNo', 'ordkey', 'arvlMsg3', 'rowNum']) {
      expect(row).not.toHaveProperty(field);
    }
  });

  it('returns a genuine no-data result without a fabricated train', () => {
    expect(normalizeUpstream({ errorMessage: { code: 'INFO-200', message: 'no data' } },
      '강남', fetchedAt, 'test-only-key').arrivals).toEqual([]);
    expect(normalizeUpstream(info000([]), '강남', fetchedAt, 'test-only-key').arrivals).toEqual([]);
    expect(normalizeUpstream({ errorMessage: { code: 'INFO-200', status: 200 }, realtimeArrivalList: [] },
      '강남', fetchedAt, 'test-only-key').arrivals).toEqual([]);
  });

  it('does not invent a row station alias from the requested station when the source omitted its name', () => {
    const source: Record<string, unknown> = validRow();
    delete source.statnNm;
    const response = normalizeUpstream(info000([source]), '강남', fetchedAt, 'test-only-key');
    expect(response.station).toBe('강남');
    expect(response.arrivals[0]).not.toHaveProperty('statnNm');
    expect(response.arrivals[0]).not.toHaveProperty('station');
    expect(response.arrivals[0].trustState).toBe('UNKNOWN');
    const cached = JSON.parse(refreshCachedPayload(JSON.stringify(response), fetchedAt + 1_000));
    expect(cached.arrivals[0]).not.toHaveProperty('station');
    expect(cached.arrivals[0].trustState).toBe('UNKNOWN');
  });

  it('preserves the real query timestamp while rechecking source age when serving cached rows', () => {
    const queriedAt = Date.parse('2026-10-24T03:00:50.000Z');
    const original = normalizeUpstream(info000([validRow()]), '강남', queriedAt, 'test-only-key');
    expect(original.arrivals[0].trustState).toBe('VALID');
    const served = JSON.parse(refreshCachedPayload(JSON.stringify(original), queriedAt + 20_000));
    expect(served.fetchedAt).toBe(original.fetchedAt);
    expect(served.arrivals[0].fetchedAt).toBe(original.fetchedAt);
    expect(served.arrivals[0].trustState).toBe('STALE');
    expect(served.realtimeArrivalList).toEqual(served.arrivals);
  });

  it('never rehabilitates a future-at-query source timestamp while serving a cached row', () => {
    const queriedAt = Date.parse('2026-10-24T02:59:59.000Z');
    const original = normalizeUpstream(info000([validRow()]), '강남', queriedAt, 'test-only-key');
    expect(original.arrivals[0].trustState).toBe('FUTURE_TIMESTAMP');
    const served = JSON.parse(refreshCachedPayload(JSON.stringify(original), queriedAt + 20_000));
    expect(served.arrivals[0].trustState).toBe('FUTURE_TIMESTAMP');
    expect(served.fetchedAt).toBe(original.fetchedAt);
  });

  it('keeps a source station mismatch unknown both initially and in cached responses', () => {
    const original = normalizeUpstream(info000([{ ...validRow(), statnNm: '역삼' }]),
      '강남', fetchedAt, 'test-only-key');
    expect(original.arrivals[0].statnNm).toBe('역삼');
    expect(original.arrivals[0].station).toBe('역삼');
    expect(original.arrivals[0].trustState).toBe('UNKNOWN');
    const served = JSON.parse(refreshCachedPayload(JSON.stringify(original), fetchedAt + 1_000));
    expect(served.arrivals[0].trustState).toBe('UNKNOWN');
    expect(served.arrivals[0].station).toBe('역삼');
  });

  it.each([undefined, null, 'INFO-000', [], {}, { status: 200 }, { code: null },
    { code: 200 }, { code: true }])('rejects malformed source error metadata %j', errorMessage => {
    expect(() => normalizeUpstream({ errorMessage, realtimeArrivalList: [validRow()] },
      '강남', fetchedAt, 'test-only-key')).toThrow(SourcePayloadError);
  });

  it.each([500, 400, '200', null, false, {}])('rejects a present non-success or malformed source status %j', status => {
    expect(() => normalizeUpstream({ errorMessage: { code: 'INFO-000', status }, realtimeArrivalList: [validRow()] },
      '강남', fetchedAt, 'test-only-key')).toThrow(SourcePayloadError);
  });

  it('allows success metadata to omit optional status without inventing it', () => {
    const response = normalizeUpstream({ errorMessage: { code: 'INFO-000' }, realtimeArrivalList: [validRow()] },
      '강남', fetchedAt, 'test-only-key');
    expect(response.arrivals).toHaveLength(1);
    expect(response.arrivals[0].trustState).toBe('VALID');
  });

  it('rejects contradictory no-data metadata and success metadata lacking the rows field', () => {
    expect(() => normalizeUpstream({ errorMessage: { code: 'INFO-200' }, realtimeArrivalList: [validRow()] },
      '강남', fetchedAt, 'test-only-key')).toThrow(SourcePayloadError);
    expect(() => normalizeUpstream({ errorMessage: { code: 'INFO-000' } },
      '강남', fetchedAt, 'test-only-key')).toThrow(SourcePayloadError);
  });

  it.each([null, [], 'invalid', {}, { realtimeArrivalList: 'not an array' }])('rejects malformed source payload %j', payload => {
    expect(() => normalizeUpstream(payload, '강남', fetchedAt, 'test-only-key')).toThrow(SourcePayloadError);
  });

  it('does not copy source error messages into thrown errors', () => {
    const key = 'test-only-secret-sentinel';
    try {
      normalizeUpstream({ errorMessage: { code: 'ERROR-500', message: `http://upstream/${key}` } },
        '강남', fetchedAt, key);
      expect.fail('expected a rejected source payload');
    } catch (error) {
      expect(error).toBeInstanceOf(SourcePayloadError);
      expect(String(error)).toContain('UPSTREAM_REJECTED');
      expect(String(error)).not.toContain(key);
      expect(String(error)).not.toContain('http://');
    }
  });
});
