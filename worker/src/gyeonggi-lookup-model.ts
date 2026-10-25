/** GBIS station discovery APIs; separate entitlement from the arrival APIs. */
import { GyeonggiPayloadError } from './gyeonggi-bus-model';

export type GyeonggiLookupOperation =
  | { kind: 'nearby'; x: number; y: number }
  | { kind: 'search'; query: string }
  | { kind: 'via'; stationId: string };

const validId = (s: string | null): s is string => s !== null && /^[1-9][0-9]{5,11}$/.test(s);
const plain = (v: unknown): Record<string, unknown> | null =>
  v !== null && typeof v === 'object' && !Array.isArray(v) ? v as Record<string, unknown> : null;
const text = (v: unknown): string | null => {
  if (typeof v !== 'string' && typeof v !== 'number') return null;
  const s = String(v).trim();
  return s && s.length <= 100 && !/[\u0000-\u001f\u007f]/.test(s) ? s : null;
};
const number = (v: unknown): number | null => {
  if (typeof v !== 'number' && typeof v !== 'string' || v === '') return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
};

export function parseGyeonggiLookup(url: URL): GyeonggiLookupOperation | null {
  const p = url.searchParams;
  if ([...p.keys()].some(k => p.getAll(k).length !== 1)) return null;
  const names = [...p.keys()];
  if (url.pathname === '/gyeonggi/bus/nearby' && names.length === 2 &&
    names.every(k => k === 'x' || k === 'y')) {
    const x = p.get('x'), y = p.get('y');
    if (!x || !y || !/^-?\d{1,3}(?:\.\d{1,7})?$/.test(x) ||
      !/^-?\d{1,3}(?:\.\d{1,7})?$/.test(y)) return null;
    const lon = Number(x), lat = Number(y);
    if (lon >= 124 && lon <= 132 && lat >= 32 && lat <= 40) {
      return { kind: 'nearby', x: lon, y: lat };
    }
  }
  if (url.pathname === '/gyeonggi/bus/search' && names.length === 1 &&
    names[0] === 'query') {
    const query = p.get('query')?.normalize('NFC').trim().replace(/\s+/gu, ' ');
    if (query && /^[\p{L}\p{N} ().·,\-]{1,40}$/u.test(query)) return { kind: 'search', query };
  }
  const stationId = p.get('stationId');
  if (url.pathname === '/gyeonggi/bus/via' && names.length === 1 &&
    names[0] === 'stationId' && validId(stationId)) return { kind: 'via', stationId };
  return null;
}

export function gyeonggiLookupSourceUrl(op: GyeonggiLookupOperation, keyInput: string): string {
  let key = keyInput.trim();
  if (/%[0-9a-f]{2}/i.test(key)) {
    try { key = decodeURIComponent(key); } catch {
      throw new GyeonggiPayloadError('GYEONGGI_KEY_INVALID');
    }
  }
  if (!key || /[\u0000-\u0020\u007f]/.test(key)) throw new GyeonggiPayloadError('GYEONGGI_KEY_INVALID');
  const suffix = op.kind === 'nearby' ? 'getBusStationAroundListv2' :
    op.kind === 'search' ? 'getBusStationListv2' : 'getBusStationViaRouteListv2';
  const params = new URLSearchParams({ format: 'json', serviceKey: key });
  if (op.kind === 'nearby') {
    params.set('x', String(op.x));
    params.set('y', String(op.y));
  } else if (op.kind === 'search') params.set('keyword', op.query);
  else params.set('stationId', op.stationId);
  return 'https://apis.data.go.kr/6410000/busstationservice/v2/' + suffix + '?' + params;
}

export interface NearbyGyeonggiStop {
  stationId: string;
  name: string;
  mobileNo: string | null;
  x: number;
  y: number;
  distanceMeters: number | null;
}
export interface GyeonggiViaRoute {
  routeId: string;
  routeName: string;
  destination: string | null;
  staOrder: string;
}
export type GyeonggiLookupPayload =
  | { provider: 'gyeonggi-gbis'; kind: 'nearby' | 'search'; stops: NearbyGyeonggiStop[] }
  | { provider: 'gyeonggi-gbis'; kind: 'via'; stationId: string; routes: GyeonggiViaRoute[] };

export function normalizeGyeonggiLookup(input: unknown, op: GyeonggiLookupOperation): GyeonggiLookupPayload {
  const root = plain(input);
  const envelope = plain(root?.response) ?? root;
  const header = plain(envelope?.msgHeader);
  const body = plain(envelope?.msgBody);
  if (!header) throw new GyeonggiPayloadError('GYEONGGI_INVALID_RESPONSE');
  const code = text(header.resultCode);
  if (!['0', '00', '4'].includes(code ?? '')) throw new GyeonggiPayloadError('GYEONGGI_UPSTREAM_REJECTED');
  // The official around-list response has a DIFFERENT field from station-name search.
  // Reading busStationList here silently discarded every successful nearby response.
  const field = op.kind === 'via' ? 'busRouteList' :
    op.kind === 'nearby' ? 'busStationAroundList' : 'busStationList';
  const raw = code === '4' ? [] : body?.[field];
  if (code !== '4' && !body) throw new GyeonggiPayloadError('GYEONGGI_INVALID_RESPONSE');
  if (code !== '4' && body && !(field in body)) throw new GyeonggiPayloadError('GYEONGGI_INVALID_RESPONSE');
  const rows = raw == null || raw === '' ? [] : Array.isArray(raw) ? raw : [raw];
  if (rows.length > 100) throw new GyeonggiPayloadError('GYEONGGI_INVALID_RESPONSE');
  if (op.kind !== 'via') {
    const stops: NearbyGyeonggiStop[] = [];
    for (const item of rows) {
      const row = plain(item);
      const stationId = text(row?.stationId);
      const name = text(row?.stationName);
      const x = number(row?.x), y = number(row?.y), dist = number(row?.distance);
      if (!validId(stationId) || !name || x === null || y === null ||
        x < 124 || x > 132 || y < 32 || y > 40 ||
        (op.kind === 'nearby' && (dist === null || dist < 0 || dist > 500))) continue;
      stops.push({ stationId, name, mobileNo: text(row?.mobileNo),
        x, y, distanceMeters: op.kind === 'nearby' ? dist : null });
    }
    return { provider: 'gyeonggi-gbis', kind: op.kind, stops };
  }
  const routes: GyeonggiViaRoute[] = [];
  for (const item of rows) {
    const row = plain(item);
    const routeId = text(row?.routeId), routeName = text(row?.routeName);
    const order = text(row?.staOrder);
    if (!validId(routeId) || !routeName || !order || !/^[1-9][0-9]{0,3}$/.test(order)) continue;
    routes.push({ routeId, routeName, staOrder: order, destination: text(row?.routeDestName) });
  }
  return { provider: 'gyeonggi-gbis', kind: 'via', stationId: op.stationId, routes };
}
