/** Gyeonggi GBIS v2 arrival APIs. Kakao IDs are never official GBIS IDs. */
export const GYEONGGI_CACHE_MS = 30_000;
export const GYEONGGI_WINDOW_MS = 86_400_000;
export const GYEONGGI_BUDGET = 900; // Reserved under the advertised 1,000/day allowance.
const MAX_SOURCE_AGE_MS = 600_000;
const MAX_RECEIPT_AGE_MS = 60_000;
const CLOCK_SKEW_MS = 60_000;
export type GyeonggiOperation =
  | { kind: 'list'; stationId: string }
  | { kind: 'item'; stationId: string; routeId: string; staOrder: string };
export type GyeonggiTrust = 'VALID' | 'STALE' | 'FUTURE_TIMESTAMP' | 'INVALID_ETA' | 'UNKNOWN';
export class GyeonggiPayloadError extends Error {
  constructor(readonly code: string) { super(code); }
}
const officialId = (value: string | null): value is string =>
  value !== null && /^[1-9][0-9]{5,11}$/.test(value);
const order = (value: string | null): value is string =>
  value !== null && /^[1-9][0-9]{0,3}$/.test(value);
export function parseGyeonggiOperation(url: URL): GyeonggiOperation | null {
  const params = url.searchParams;
  if ([...params.keys()].some(key => params.getAll(key).length !== 1)) return null;
  const stationId = params.get('stationId');
  if (!officialId(stationId)) return null;
  if (url.pathname === '/gyeonggi/bus/arrivals' &&
      [...params.keys()].every(key => key === 'stationId')) return { kind: 'list', stationId };
  const routeId = params.get('routeId'), staOrder = params.get('staOrder');
  if (url.pathname === '/gyeonggi/bus/arrival' && officialId(routeId) && order(staOrder) &&
      [...params.keys()].every(key => ['stationId', 'routeId', 'staOrder'].includes(key))) {
    return { kind: 'item', stationId, routeId, staOrder };
  }
  return null;
}
export function gyeonggiSourceUrl(op: GyeonggiOperation, secret: string): string {
  let key = secret.trim();
  // data.go.kr may show both encoded and decoded variants. Encode exactly once.
  if (/%[0-9a-f]{2}/i.test(key)) {
    try { key = decodeURIComponent(key); }
    catch { throw new GyeonggiPayloadError('GYEONGGI_KEY_INVALID'); }
  }
  if (!key || /[\u0000-\u0020\u007f]/u.test(key)) throw new GyeonggiPayloadError('GYEONGGI_KEY_INVALID');
  const path = op.kind === 'list' ? 'getBusArrivalListv2' : 'getBusArrivalItemv2';
  const params = new URLSearchParams({ format: 'json', serviceKey: key, stationId: op.stationId });
  if (op.kind === 'item') {
    params.set('routeId', op.routeId);
    params.set('staOrder', op.staOrder);
  }
  return 'https://apis.data.go.kr/6410000/busarrivalservice/v2/' + path + '?' + params.toString();
}
function record(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown> : null;
}
function numeric(value: unknown): number | null {
  if ((typeof value !== 'string' && typeof value !== 'number') || String(value).trim() === '') return null;
  const x = Number(value);
  return Number.isSafeInteger(x) && x >= 0 ? x : null;
}
function text(value: unknown): string | null {
  if (typeof value !== 'number' && typeof value !== 'string') return null;
  const s = String(value).trim();
  return s && s.length <= 80 && !/[\u0000-\u001f\u007f]/u.test(s) ? s : null;
}
function parseKst(value: unknown): number | null {
  if (typeof value !== 'string') return null;
  // GBIS queryTime may include fractional seconds (e.g. 23:25:47.599).
  const m = /^(\d{4})[-/]?(\d{2})[-/]?(\d{2})[ T]?(\d{2}):?(\d{2}):?(\d{2})(?:\.(\d{1,9}))?$/.exec(value.trim());
  if (!m) return null;
  const [year, month, day, hour, minute, second] = m.slice(1, 7).map(Number);
  const fraction = Number((m[7] ?? '').padEnd(3, '0').slice(0, 3));
  const ms = Date.UTC(year, month - 1, day, hour, minute, second, fraction);
  const d = new Date(ms);
  return d.getUTCFullYear() === year && d.getUTCMonth() + 1 === month && d.getUTCDate() === day &&
    hour < 24 && minute < 60 && second < 60 ? ms - 9 * 3600_000 : null;
}
export interface GyeonggiArrival {
  stationId: string;
  routeId: string;
  staOrder: string;
  routeName: string | null;
  direction: string | null;
  vehicleId: string | null;
  sequence: 1 | 2;
  arrivalSeconds: number | null;
  observedAt: string | null;
  status: string | null;
  trustState: GyeonggiTrust;
  etaPrecision: 'seconds' | 'minutes' | 'unknown';
  locationStopsAway: number | null;
  availableSeats: number | null;
  lowFloor: number | null;
}
export interface GyeonggiPayload {
  provider: 'gyeonggi-gbis';
  fetchedAt: string;
  stationId: string;
  sourceQueryTime: string | null;
  arrivals: GyeonggiArrival[];
}
export function computeGyeonggiTrust(row: GyeonggiArrival, fetchedAtMs: number, nowMs: number): GyeonggiTrust {
  const observedMs = row.observedAt ? Date.parse(row.observedAt) : NaN;
  if (!Number.isFinite(fetchedAtMs) || !Number.isFinite(nowMs)) return 'UNKNOWN';
  if (fetchedAtMs > nowMs + CLOCK_SKEW_MS ||
      (Number.isFinite(observedMs) && observedMs > fetchedAtMs + CLOCK_SKEW_MS)) return 'FUTURE_TIMESTAMP';
  // A missing source clock does not invalidate an official ETA. fetchedAt is its receipt
  // baseline only; observedAt remains null so clients never mistake it for a source clock.
  const baseline = Number.isFinite(observedMs) ? Math.min(observedMs, fetchedAtMs) : fetchedAtMs;
  if (nowMs - fetchedAtMs > MAX_RECEIPT_AGE_MS || nowMs - baseline > MAX_SOURCE_AGE_MS) return 'STALE';
  if (row.arrivalSeconds === null || !Number.isSafeInteger(row.arrivalSeconds) ||
      row.arrivalSeconds < 0 || row.arrivalSeconds > 86_400)
    return 'INVALID_ETA';
  // Vehicle identity, minute precision and optional status metadata do not erase an ETA.
  if (/^(?:END|ENDED|CLOSED|TERMINATED)$|운행\s*종료/iu.test(row.status ?? '')) return 'UNKNOWN';
  if (baseline + row.arrivalSeconds * 1_000 <= nowMs) return 'STALE';
  return 'VALID';
}
export function normalizeGyeonggiResponse(payload: unknown, op: GyeonggiOperation, fetchedAtMs: number): GyeonggiPayload {
  const root = record(payload);
  const envelope = record(root?.response) ?? root;
  const header = record(envelope?.msgHeader);
  const body = record(envelope?.msgBody);
  if (!header) throw new GyeonggiPayloadError('GYEONGGI_INVALID_RESPONSE');
  const code = text(header.resultCode);
  if (code === '4') return { provider: 'gyeonggi-gbis', fetchedAt: new Date(fetchedAtMs).toISOString(),
    stationId: op.stationId, sourceQueryTime: text(header.queryTime), arrivals: [] };
  if (code !== '0' && code !== '00') throw new GyeonggiPayloadError('GYEONGGI_UPSTREAM_REJECTED');
  if (!body) throw new GyeonggiPayloadError('GYEONGGI_INVALID_RESPONSE');
  const sourceRows = op.kind === 'list' ? body.busArrivalList : body.busArrivalItem;
  if (sourceRows === undefined || sourceRows === null || sourceRows === '') {
    return { provider: 'gyeonggi-gbis', fetchedAt: new Date(fetchedAtMs).toISOString(),
      stationId: op.stationId, sourceQueryTime: text(header.queryTime), arrivals: [] };
  }
  const rows = Array.isArray(sourceRows) ? sourceRows : [sourceRows];
  if (rows.length > 300) throw new GyeonggiPayloadError('GYEONGGI_INVALID_RESPONSE');
  const sourceObservedMs = parseKst(header.queryTime);
  const observedAt = sourceObservedMs === null ? null : new Date(sourceObservedMs).toISOString();
  const arrivals: GyeonggiArrival[] = [];
  for (const original of rows) {
    const row = record(original);
    if (!row) throw new GyeonggiPayloadError('GYEONGGI_INVALID_RESPONSE');
    const stationId = text(row.stationId) ?? op.stationId;
    const routeId = text(row.routeId) ?? (op.kind === 'item' ? op.routeId : null);
    const staOrder = text(row.staOrder) ?? (op.kind === 'item' ? op.staOrder : '');
    if (stationId !== op.stationId || !officialId(routeId) || (staOrder !== '' && !order(staOrder))) continue;
    if (op.kind === 'item' && (routeId !== op.routeId || staOrder !== op.staOrder)) continue;
    for (const sequence of [1, 2] as const) {
      const exactSeconds = numeric(row['predictTimeSec' + sequence]);
      const minutes = numeric(row['predictTime' + sequence]);
      // A zero/empty second field must not discard a positive official minute estimate.
      const useSeconds = exactSeconds !== null && exactSeconds > 0;
      const precision = useSeconds ? 'seconds' : minutes !== null ? 'minutes' : 'unknown';
      const rawVehicleId = text(row['vehId' + sequence]);
      const lowFloor = numeric(row['lowPlate' + sequence]);
      const item: GyeonggiArrival = {
        stationId, routeId, staOrder, routeName: text(row.routeName),
        direction: text(row.routeDestName),
        vehicleId: rawVehicleId !== null && /^[1-9]\d+$/.test(rawVehicleId) ? rawVehicleId : null,
        sequence, arrivalSeconds: useSeconds ? exactSeconds :
          (minutes === null ? exactSeconds : minutes * 60),
        observedAt, status: text(row.flag),
        trustState: 'UNKNOWN', etaPrecision: precision,
        locationStopsAway: numeric(row['locationNo' + sequence]),
        availableSeats: numeric(row['remainSeatCnt' + sequence]),
        lowFloor: lowFloor !== null && [0, 1, 2, 5, 6, 7].includes(lowFloor) ? lowFloor : null,
      };
      item.trustState = computeGyeonggiTrust(item, fetchedAtMs, fetchedAtMs);
      if (item.vehicleId || item.arrivalSeconds !== null) arrivals.push(item);
    }
  }
  return { provider: 'gyeonggi-gbis', fetchedAt: new Date(fetchedAtMs).toISOString(),
    stationId: op.stationId, sourceQueryTime: text(header.queryTime), arrivals };
}
export function refreshGyeonggiPayload(payload: string, nowMs: number): string {
  const output = JSON.parse(payload) as GyeonggiPayload;
  const fetchedMs = Date.parse(output.fetchedAt);
  for (const row of output.arrivals) row.trustState = computeGyeonggiTrust(row, fetchedMs, nowMs);
  return JSON.stringify(output);
}
