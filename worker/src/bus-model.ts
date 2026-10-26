/** Seoul city-bus API contract (2025-08/09 official guides). No sample data is used at runtime. */
export const BUS_CACHE_TTL_MS = 30_000;
export const BUS_WINDOW_MS = 86_400_000;
export const BUS_UPSTREAM_BUDGET = 900;
export const BUS_MAX_SOURCE_AGE_MS = 600_000;
const BUS_MAX_RECEIPT_AGE_MS = 60_000;
const BUS_CLOCK_SKEW_MS = 60_000;
export const BUS_PROVIDER = 'seoul-bus';
export type BusTrust = 'VALID' | 'STALE' | 'FUTURE_TIMESTAMP' | 'INVALID_ETA' | 'UNKNOWN';
export type BusOperation =
  | { kind: 'stops'; query: string }
  | { kind: 'nearby'; x: string; y: string }
  | { kind: 'routes'; stopId: string; arsId: string }
  | { kind: 'arrivals'; stopId: string; routeId: string; stopOrder: string };
export type BusSourceRow = Record<string, unknown>;

export class BusSourceError extends Error {
  constructor(readonly code: string, readonly status = 502) { super(code); }
}
const officialId = (value: string | null) => value !== null && /^[1-9]\d{8}$/.test(value);
export function canonicalBusOrder(value: unknown): string | null {
  const text = scalarText(value);
  return text !== null && /^\d{1,4}$/.test(text) && Number(text) > 0 ? String(Number(text)) : null;
}
export function parseBusOperation(url: URL): BusOperation | null {
  const params = url.searchParams;
  if ([...new Set(params.keys())].some(key => params.getAll(key).length !== 1)) return null;
  const only = (...allowed: string[]) => [...params.keys()].every(key => allowed.includes(key));
  if (url.pathname === '/bus/stops') {
    if (params.has('query') && only('query')) {
      const query = params.get('query')!.normalize('NFC').trim().replace(/\s+/gu, ' ');
      return /^[\p{L}\p{N} ().·,\-]{1,40}$/u.test(query) ? { kind: 'stops', query } : null;
    }
    if (only('x', 'y') && params.has('x') && params.has('y')) {
      const x = params.get('x')!, y = params.get('y')!;
      if (/^-?\d{1,3}(\.\d{1,10})?$/.test(x) && /^-?\d{1,2}(\.\d{1,10})?$/.test(y) &&
          Number(x) >= -180 && Number(x) <= 180 && Number(y) >= -90 && Number(y) <= 90) {
        return { kind: 'nearby', x: String(Number(x)), y: String(Number(y)) };
      }
    }
    return null;
  }
  const stopId = params.get('stopId');
  if (!officialId(stopId)) return null;
  if (url.pathname === '/bus/routes' && only('stopId', 'arsId')) {
    const arsId = params.get('arsId');
    return arsId !== null && /^\d{5}$/.test(arsId) && arsId !== '00000'
      ? { kind: 'routes', stopId: stopId!, arsId } : null;
  }
  if (url.pathname === '/bus/arrivals' && only('stopId', 'routeId', 'stopOrder')) {
    const routeId = params.get('routeId'), stopOrder = canonicalBusOrder(params.get('stopOrder'));
    return officialId(routeId) && stopOrder !== null
      ? { kind: 'arrivals', stopId: stopId!, routeId: routeId!, stopOrder } : null;
  }
  return null;
}

/** Keys may be supplied as the portal's decoded or URL-encoded variant; encode exactly once. */
export function busSourceUrl(operation: BusOperation, key: string): string {
  let decoded = key.trim();
  if (/%[\da-f]{2}/i.test(decoded)) {
    try { decoded = decodeURIComponent(decoded); } catch { throw new BusSourceError('BUS_KEY_INVALID', 503); }
  }
  if (!decoded || /[\u0000-\u0020\u007f]/u.test(decoded)) throw new BusSourceError('BUS_KEY_INVALID', 503);
  const params = new URLSearchParams({ serviceKey: decoded, resultType: 'json' });
  let endpoint: string;
  if (operation.kind === 'stops') {
    if (/^\d{5}$/.test(operation.query)) {
      endpoint = 'stationinfo/getStationByUid'; params.set('arsId', operation.query);
    } else {
      endpoint = 'stationinfo/getStationByName'; params.set('stSrch', operation.query);
    }
  } else if (operation.kind === 'nearby') {
    endpoint = 'stationinfo/getStationByPos';
    params.set('tmX', operation.x); params.set('tmY', operation.y); params.set('radius', '500');
  } else if (operation.kind === 'routes') {
    endpoint = 'stationinfo/getStationByUid'; params.set('arsId', operation.arsId);
  } else {
    endpoint = 'arrive/getArrInfoByRoute';
    params.set('stId', operation.stopId); params.set('busRouteId', operation.routeId); params.set('ord', operation.stopOrder);
  }
  // This fixed origin is the official documented endpoint. Never accept a client-supplied URL.
  return `http://ws.bus.go.kr/api/rest/${endpoint}?${params}`;
}

function scalarText(value: unknown): string | null {
  if (typeof value !== 'string' && typeof value !== 'number') return null;
  const text = String(value).trim();
  return text.length > 0 && text.length <= 512 && !/[\u0000-\u001f\u007f]/u.test(text) ? text : null;
}
function safeText(value: unknown, key: string): string | null {
  const text = scalarText(value);
  if (text === null || /https?:\/\/|ws\.bus\.go\.kr/i.test(text)) return null;
  let decoded = key;
  try { decoded = decodeURIComponent(key); } catch { /* compare the supplied value as well */ }
  return key && (text.includes(key) || text.includes(decoded) || text.includes(encodeURIComponent(decoded))) ? null : text;
}
const integer = (value: unknown) => {
  const text = scalarText(value);
  return text !== null && /^\d+$/.test(text) && Number.isSafeInteger(Number(text)) ? Number(text) : null;
};
const booleanFlag = (value: unknown) => scalarText(value) === '0' ? false : scalarText(value) === '1' ? true : null;
const supportedType = (type: string | null) => type !== null && ['2', '3', '4', '5', '6'].includes(type);
function canonicalArsId(value: unknown, key: string): string | null {
  const text = safeText(value, key);
  return text !== null && /^\d{1,5}$/.test(text) && Number(text) > 0 ? text.padStart(5, '0') : null;
}
function coordinates(row: BusSourceRow): { x: number; y: number } | null {
  // posX/posY are projected GRS80 coordinates and must never become WGS84 longitude/latitude.
  const rawX = row.tmX ?? row.gpsX, rawY = row.tmY ?? row.gpsY;
  if (rawX === undefined || rawY === undefined || rawX === '' || rawY === '') return null;
  const x = Number(rawX), y = Number(rawY);
  return Number.isFinite(x) && Number.isFinite(y) && x >= 124 && x <= 132 && y >= 33 && y <= 40 ? { x, y } : null;
}

/** mkTm is the documented prediction provision timestamp in Asia/Seoul. */
export function parseBusSourceTime(value: unknown): number | null {
  if (typeof value !== 'string') return null;
  const match = /^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?$/.exec(value.trim());
  if (!match) return null;
  const [, ys, mos, ds, hs, mis, ss, fraction] = match;
  const [y, mo, d, h, mi, s] = [ys, mos, ds, hs, mis, ss].map(Number);
  if (y < 2000 || mo < 1 || mo > 12 || d < 1 || h > 23 || mi > 59 || s > 59) return null;
  const local = Date.UTC(y, mo - 1, d, h, mi, s, Number((fraction ?? '').padEnd(3, '0').slice(0, 3)));
  const check = new Date(local);
  return check.getUTCFullYear() === y && check.getUTCMonth() === mo - 1 && check.getUTCDate() === d
    ? local - 9 * 3_600_000 : null;
}

export function readBusSource(payload: unknown): BusSourceRow[] {
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) throw new BusSourceError('BUS_UPSTREAM_INVALID_JSON');
  const raw = payload as Record<string, unknown>;
  const source = (raw.ServiceResult ?? raw) as Record<string, unknown>;
  if (!source || typeof source !== 'object' || Array.isArray(source)) throw new BusSourceError('BUS_UPSTREAM_INVALID_JSON');
  const header = source.msgHeader as Record<string, unknown> | undefined;
  if (!header || typeof header !== 'object') throw new BusSourceError('BUS_UPSTREAM_REJECTED');
  const code = scalarText(header.headerCd);
  if (code !== '0') {
    const known: Record<string, string> = { '1': 'BUS_UPSTREAM_UNAVAILABLE', '2': 'BUS_UPSTREAM_REJECTED',
      '3': 'BUS_STOP_NOT_FOUND', '4': 'BUS_ROUTE_NOT_FOUND', '5': 'BUS_COORDINATES_REJECTED',
      '6': 'BUS_REALTIME_UNAVAILABLE', '7': 'BUS_ROUTE_NOT_FOUND', '8': 'BUS_SERVICE_ENDED' };
    // Header text can include a key-bearing URL. Emit only an allowlisted local code.
    throw new BusSourceError(known[code ?? ''] ?? 'BUS_UPSTREAM_REJECTED', code === '3' || code === '4' || code === '7' ? 404 : 502);
  }
  const body = source.msgBody;
  if (body === null || body === undefined || body === '') return [];
  if (typeof body !== 'object' || Array.isArray(body)) throw new BusSourceError('BUS_UPSTREAM_INVALID_JSON');
  const items = (body as Record<string, unknown>).itemList;
  if (items === null || items === undefined || items === '') return [];
  const rows = Array.isArray(items) ? items : [items];
  if (rows.length > 2_000) throw new BusSourceError('BUS_UPSTREAM_INVALID_JSON');
  if (rows.some(row => !row || typeof row !== 'object' || Array.isArray(row))) throw new BusSourceError('BUS_UPSTREAM_INVALID_JSON');
  return rows as BusSourceRow[];
}

export interface BusArrivalRow {
  stopId: string; routeId: string; stopOrder: string; direction: string; routeName: string; routeType: string;
  vehicleId: string; arrivalSeconds: number | null; observedAt: string | null; message: string;
  full: boolean | null; last: boolean | null; trustState: BusTrust; issues: string[];
  raw: Record<string, string>;
}
function arrivalTrust(row: BusArrivalRow, fetchedAt: number, now: number): BusTrust {
  const observed = row.observedAt === null ? NaN : Date.parse(row.observedAt);
  if (!Number.isFinite(fetchedAt) || !Number.isFinite(now)) return 'UNKNOWN';
  if (fetchedAt > now + BUS_CLOCK_SKEW_MS ||
      (Number.isFinite(observed) && observed > fetchedAt + BUS_CLOCK_SKEW_MS)) return 'FUTURE_TIMESTAMP';
  // Receipt time is a fallback ETA baseline, never a fabricated source timestamp.
  const baseline = Number.isFinite(observed) ? Math.min(observed, fetchedAt) : fetchedAt;
  if (now - fetchedAt > BUS_MAX_RECEIPT_AGE_MS || now - baseline > BUS_MAX_SOURCE_AGE_MS) return 'STALE';
  if (row.arrivalSeconds === null || !Number.isSafeInteger(row.arrivalSeconds) ||
      row.arrivalSeconds < 0 || row.arrivalSeconds > 86_400) return 'INVALID_ETA';
  // Capacity, vehicle IDs, detour flags and other optional metadata remain display metadata.
  if (/운행\s*종료/u.test(row.message)) return 'UNKNOWN';
  if (baseline + row.arrivalSeconds * 1_000 <= now) return 'STALE';
  return 'VALID';
}

export function normalizeBusPayload(operation: BusOperation, payload: unknown, fetchedAtMs: number, key: string): Record<string, unknown> {
  const rows = readBusSource(payload);
  const base = { provider: BUS_PROVIDER, fetchedAt: new Date(fetchedAtMs).toISOString() };
  if (operation.kind === 'stops' || operation.kind === 'nearby') {
    const stops = new Map<string, Record<string, unknown>>();
    for (const row of rows) {
      const stopId = safeText(row.stId ?? row.stationId, key), arsId = canonicalArsId(row.arsId, key), name = safeText(row.stNm ?? row.stationNm, key);
      const point = coordinates(row);
      if (!officialId(stopId) || arsId === null || !/^\d{5}$/.test(arsId) || arsId === '00000' || !name || !point) continue;
      if (operation.kind === 'stops' && /^\d{5}$/.test(operation.query) && arsId !== operation.query) continue;
      const previous = stops.get(stopId!);
      const stop = { stopId: stopId!, arsId, name, ...point };
      if (previous && JSON.stringify(previous) !== JSON.stringify(stop)) throw new BusSourceError('BUS_STOP_AMBIGUOUS', 409);
      stops.set(stopId!, stop);
    }
    return { ...base, stops: [...stops.values()].slice(0, 100) };
  }
  if (operation.kind === 'routes') {
    const routes = new Map<string, Record<string, unknown>>();
    for (const row of rows) {
      if (scalarText(row.stId) !== operation.stopId || canonicalArsId(row.arsId, key) !== operation.arsId) continue;
      const routeId = safeText(row.busRouteId, key), name = safeText(row.rtNm ?? row.busRouteAbrv, key), stopOrder = canonicalBusOrder(row.staOrd);
      const direction = safeText(row.adirection, key), routeType = safeText(row.routeType, key);
      if (!officialId(routeId) || !name || stopOrder === null || !direction || routeType === null) continue;
      const route = { routeId: routeId!, name, stopOrder, direction, routeType, supported: supportedType(routeType) };
      const identity = `${routeId}:${stopOrder}`;
      const previous = routes.get(identity);
      if (previous && JSON.stringify(previous) !== JSON.stringify(route)) throw new BusSourceError('BUS_ROUTE_AMBIGUOUS', 409);
      routes.set(identity, route);
    }
    if (rows.length > 0 && routes.size === 0) throw new BusSourceError('BUS_STOP_MISMATCH', 409);
    return { ...base, stopId: operation.stopId, arsId: operation.arsId, routes: [...routes.values()] };
  }
  const matching = rows.filter(row => scalarText(row.stId) === operation.stopId &&
    scalarText(row.busRouteId) === operation.routeId &&
    (scalarText(row.staOrd) === null || canonicalBusOrder(row.staOrd) === operation.stopOrder));
  if (rows.length > 0 && matching.length === 0) throw new BusSourceError('BUS_ARRIVAL_MISMATCH', 409);
  if (matching.length > 1 && new Set(matching.map(row => JSON.stringify(row))).size > 1) throw new BusSourceError('BUS_ARRIVAL_AMBIGUOUS', 409);
  const arrivals: BusArrivalRow[] = [];
  for (const source of matching.slice(0, 1)) {
    const sourceTime = parseBusSourceTime(source.mkTm);
    for (const number of [1, 2]) {
      const raw: Record<string, string> = {};
      for (const name of ['mkTm', 'dir', 'stNm', 'arsId', 'deTourAt']) {
        const value = safeText(source[name], key); if (value !== null) raw[name] = value;
      }
      for (const name of ['exps', 'vehId', 'arrmsg', 'full', 'isArrive', 'isLast', 'repTm', 'reride_Num', 'rerdie_Div', 'plainNo', 'stationNm']) {
        const value = safeText(source[`${name}${number}`], key); if (value !== null) raw[name] = value;
      }
      const row: BusArrivalRow = {
        stopId: operation.stopId, routeId: operation.routeId, stopOrder: operation.stopOrder,
        direction: safeText(source.dir, key) ?? '', routeName: safeText(source.rtNm ?? source.busRouteAbrv, key) ?? '',
        routeType: safeText(source.routeType, key) ?? '', vehicleId: safeText(source[`vehId${number}`], key) ?? '',
        arrivalSeconds: integer(source[`exps${number}`]), observedAt: sourceTime === null ? null : new Date(sourceTime).toISOString(),
        message: safeText(source[`arrmsg${number}`], key) ?? '', full: booleanFlag(source[`full${number}`]),
        last: booleanFlag(source[`isLast${number}`]), trustState: 'UNKNOWN', issues: [], raw,
      };
      if (sourceTime === null) row.issues.push('SOURCE_TIMESTAMP_MISSING');
      else if (sourceTime > fetchedAtMs + BUS_CLOCK_SKEW_MS) row.issues.push('SOURCE_FUTURE');
      if (row.arrivalSeconds === null || row.arrivalSeconds > 86_400) row.issues.push('INVALID_ETA');
      else if (row.arrivalSeconds === 0) row.issues.push('ETA_UNAVAILABLE');
      if (row.full === true) row.issues.push('FULL');
      if (row.full === null) row.issues.push('CAPACITY_UNKNOWN');
      if (!/^[1-9]\d{8,9}$/.test(row.vehicleId)) row.issues.push('VEHICLE_ID_MISSING');
      if (!supportedType(row.routeType)) row.issues.push('UNSUPPORTED_PROVIDER');
      if (raw.isArrive !== '0') row.issues.push('VEHICLE_STATE_UNCERTAIN');
      if (raw.deTourAt !== '00') row.issues.push('DETOUR_STATE_UNCERTAIN');
      if (/운행\s*종료|출발\s*대기|회차\s*대기|정보\s*없음/u.test(row.message)) row.issues.push('SERVICE_STATE_UNCERTAIN');
      row.trustState = arrivalTrust(row, fetchedAtMs, fetchedAtMs);
      arrivals.push(row);
    }
  }
  // Keep the first official ETA even if both slots repeat one actual vehicle. Clients
  // must not use that repeated vehicle as evidence of a second bus.
  if (arrivals.length === 2 && /^[1-9]\d{8,9}$/.test(arrivals[0].vehicleId) &&
      arrivals[0].vehicleId === arrivals[1].vehicleId) {
    for (const row of arrivals) row.issues.push('DUPLICATE_VEHICLE');
  }
  return { ...base, stopId: operation.stopId, routeId: operation.routeId, stopOrder: operation.stopOrder,
    sourceGeneratedAt: arrivals[0]?.observedAt ?? null, arrivals };
}

export function refreshBusPayload(payload: string, now: number): string {
  const response = JSON.parse(payload) as { fetchedAt: string; arrivals?: BusArrivalRow[] };
  for (const row of response.arrivals ?? []) {
    row.trustState = arrivalTrust(row, Date.parse(response.fetchedAt), now);
  }
  return JSON.stringify(response);
}
