import stationNames from './stations.json';

export const CACHE_TTL_MS = 30_000;
export const FAILURE_BACKOFF_MS = 10_000;
export const WINDOW_MS = 24 * 60 * 60 * 1000;
export const UPSTREAM_BUDGET = 900;
export const MAX_SOURCE_AGE_MS = 60_000;
export type TrustState = 'VALID' | 'STALE' | 'FUTURE_TIMESTAMP' | 'INVALID_ETA' | 'UNKNOWN';
export type ArrivalRow = Record<string, string | number> & { trustState: TrustState };
export interface ArrivalResponse {
  station: string;
  fetchedAt: string;
  realtime: true;
  arrivals: ArrivalRow[];
  realtimeArrivalList: ArrivalRow[];
}
const names = new Set(stationNames);

/** Canonical keys come only from the repository's official station-name asset. */
export function normalizeStation(value: string | null): string | null {
  if (value === null || value.length > 100 || /[\u0000-\u001f\u007f]/u.test(value)) return null;
  const name = value.normalize('NFC').trim().replace(/\s+/gu, ' ');
  if (names.has(name)) return name;
  const withoutSuffix = name.endsWith('역') ? name.slice(0, -1).trimEnd() : '';
  return names.has(withoutSuffix) ? withoutSuffix : null;
}

/** OA-12764 recptnDt is a Seoul local timestamp, not a UTC timestamp. */
export function parseSourceTime(value: unknown): number | null {
  if (typeof value !== 'string') return null;
  const match = /^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})$/.exec(value);
  if (!match) return null;
  const [, y, mo, d, h, mi, s] = match.map(Number);
  if (y < 2000 || mo < 1 || mo > 12 || d < 1 || h > 23 || mi > 59 || s > 59) return null;
  const local = Date.UTC(y, mo - 1, d, h, mi, s);
  const check = new Date(local);
  if (check.getUTCFullYear() !== y || check.getUTCMonth() !== mo - 1 || check.getUTCDate() !== d) return null;
  return local - 9 * 60 * 60 * 1000;
}

export function trustState(row: Record<string, unknown>, fetchedAtMs: number, nowMs = fetchedAtMs): TrustState {
  const sourceTime = parseSourceTime(row.recptnDt ?? row.receivedAt);
  if (!Number.isFinite(fetchedAtMs) || sourceTime === null) return 'UNKNOWN';
  if (sourceTime > fetchedAtMs) return 'FUTURE_TIMESTAMP';
  if (!Number.isFinite(nowMs) || nowMs < fetchedAtMs) return 'UNKNOWN';
  const elapsed = nowMs - sourceTime;
  if (elapsed > MAX_SOURCE_AGE_MS) return 'STALE';
  const raw = row.barvlDt ?? row.arrivalSeconds;
  if ((typeof raw !== 'number' && typeof raw !== 'string') ||
      (typeof raw === 'string' && !/^\d+$/.test(raw))) return 'INVALID_ETA';
  const seconds = Number(raw);
  if (!Number.isSafeInteger(seconds) || seconds < 0 || seconds > 86_400) return 'INVALID_ETA';
  // Zero does not prove that a train is at this station. A prior-station message
  // and arrival/departure codes cannot authorize a boarding recommendation.
  if (seconds === 0 || !['3', '4', '5', '99'].includes(String(row.arvlCd ?? ''))) return 'UNKNOWN';
  if (seconds * 1000 <= elapsed) return 'STALE';
  const present = (value: unknown) => (typeof value === 'string' && value.trim().length > 0) ||
    (typeof value === 'number' && Number.isFinite(value));
  if (!present(row.statnId ?? row.stationId) || !present(row.subwayId ?? row.lineId) ||
      !['statnTid', 'btrainNo', 'bstatnId'].every(field => present(row[field])) ||
      !['statnNm', 'trainLineNm', 'btrainSttus', 'bstatnNm'].every(field =>
        typeof row[field] === 'string' && row[field].trim().length > 0) ||
      !['상행', '하행', '내선', '외선'].includes(String(row.updnLine ?? row.direction ?? '').trim())) return 'UNKNOWN';
  return 'VALID';
}

const sourceFields = [
  'subwayId', 'statnId', 'statnNm', 'updnLine', 'trainLineNm', 'barvlDt',
  'recptnDt', 'arvlMsg2', 'arvlMsg3', 'arvlCd', 'btrainNo', 'ordkey',
  'btrainSttus', 'bstatnId', 'bstatnNm', 'statnFid', 'statnTid', 'subwayList',
  'statnList', 'lstcarAt', 'rowNum', 'totalCount',
] as const;
const aliases: Record<string, string> = {
  station: 'statnNm', stationId: 'statnId', lineId: 'subwayId', direction: 'updnLine',
  destination: 'trainLineNm', arrivalSeconds: 'barvlDt',
  arrivalMessage: 'arvlMsg2', receivedAt: 'recptnDt',
};
function safeScalar(value: unknown, secret: string): string | number | undefined {
  if (typeof value === 'number') return Number.isFinite(value) ? value : undefined;
  if (typeof value !== 'string' || value.length > 512) return undefined;
  if (/[\u0000-\u001f\u007f]/u.test(value) || /(?:https?:\/\/|swopenapi\.seoul\.go\.kr)/i.test(value)) return undefined;
  if (secret && (value.includes(secret) || value.includes(encodeURIComponent(secret)))) return undefined;
  return value;
}

export class SourcePayloadError extends Error {
  constructor(readonly code: 'UPSTREAM_INVALID_JSON' | 'UPSTREAM_REJECTED') { super(code); }
}

export function normalizeUpstream(payload: unknown, station: string, fetchedAtMs: number, secret: string): ArrivalResponse {
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) throw new SourcePayloadError('UPSTREAM_INVALID_JSON');
  const object = payload as Record<string, unknown>;
  const error = object.errorMessage;
  if (!error || typeof error !== 'object' || Array.isArray(error)) throw new SourcePayloadError('UPSTREAM_INVALID_JSON');
  const metadata = error as Record<string, unknown>;
  const code = metadata.code;
  if (typeof code !== 'string') throw new SourcePayloadError('UPSTREAM_INVALID_JSON');
  if (code !== 'INFO-000' && code !== 'INFO-200') throw new SourcePayloadError('UPSTREAM_REJECTED');
  if (metadata.status !== undefined && metadata.status !== 200) throw new SourcePayloadError('UPSTREAM_REJECTED');
  const rows = object.realtimeArrivalList;
  if (rows !== undefined && !Array.isArray(rows)) throw new SourcePayloadError('UPSTREAM_INVALID_JSON');
  if ((rows === undefined && code !== 'INFO-200') || (code === 'INFO-200' && Array.isArray(rows) && rows.length !== 0)) {
    throw new SourcePayloadError('UPSTREAM_INVALID_JSON');
  }
  const fetchedAt = new Date(fetchedAtMs).toISOString();
  const arrivals: ArrivalRow[] = [];
  for (const raw of (rows ?? []).slice(0, 100)) {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) continue;
    const original = raw as Record<string, unknown>;
    const row: ArrivalRow = { fetchedAt, trustState: 'UNKNOWN' };
    for (const field of sourceFields) {
      const value = safeScalar(original[field], secret);
      if (value !== undefined) row[field] = value;
    }
    for (const [alias, field] of Object.entries(aliases)) {
      if (row[field] !== undefined) row[alias] = row[field];
    }
    row.trustState = trustState(row, fetchedAtMs);
    if (row.trustState === 'VALID' && row.statnNm !== undefined && normalizeStation(String(row.statnNm)) !== station) row.trustState = 'UNKNOWN';
    arrivals.push(row);
  }
  return { station, fetchedAt, realtime: true, arrivals, realtimeArrivalList: arrivals };
}

/** Keep the original query timestamp, including its future-clock verdict. */
export function refreshCachedPayload(payload: string, nowMs: number): string {
  const response = JSON.parse(payload) as ArrivalResponse;
  const fetchedAtMs = Date.parse(response.fetchedAt);
  for (const row of response.arrivals) {
    const originalState = row.trustState;
    row.trustState = trustState(row, fetchedAtMs, nowMs);
    if (originalState === 'FUTURE_TIMESTAMP') row.trustState = originalState;
    if (row.trustState === 'VALID' && row.statnNm !== undefined && normalizeStation(String(row.statnNm)) !== response.station) row.trustState = 'UNKNOWN';
  }
  response.realtimeArrivalList = response.arrivals;
  return JSON.stringify(response);
}
