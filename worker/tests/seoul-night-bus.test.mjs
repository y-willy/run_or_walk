import assert from 'node:assert/strict';
import { test } from 'node:test';
import { normalizeBusPayload, refreshBusPayload } from '../src/bus-model.ts';

// Synthetic upstream envelopes with the observed official N37 IDs/type. No live API calls.
const now = Date.parse('2026-10-30T00:00:00+09:00');
const source = itemList => ({ msgHeader: { headerCd: '0' }, msgBody: { itemList } });
const route = { stId: '100000004', arsId: '01004', busRouteId: '100100585', rtNm: 'N37',
  staOrd: '81', adirection: '복정역환승센터', routeType: '15' };
const routesOperation = { kind: 'routes', stopId: route.stId, arsId: route.arsId };

test('official class 15 supports N37 and other Seoul night routes without a name allowlist', () => {
  for (const name of ['N37', 'N13', 'N31', 'N75']) {
    const payload = normalizeBusPayload(routesOperation, source([{ ...route, rtNm: name }]), now, 'fixture-key');
    assert.equal(payload.routes[0].supported, true);
    assert.equal(payload.routes[0].routeType, '15');
  }
});

test('night ETA and cached countdown remain usable without unsupported-provider issue', () => {
  const operation = { kind: 'arrivals', stopId: route.stId, routeId: route.busRouteId, stopOrder: route.staOrd };
  const payload = normalizeBusPayload(operation, source([{ ...route, mkTm: '2026-10-30 00:00:00.0',
    exps1: '998', vehId1: '111049481', arrmsg1: '16분38초후[9번째 전]', full1: '0',
    isArrive1: '0', isLast1: '0', deTourAt: '00', exps2: '5572', vehId2: '123060174',
    arrmsg2: '92분52초후[50번째 전]', full2: '0', isArrive2: '0', isLast2: '0' }]), now, 'fixture-key');
  assert.deepEqual(payload.arrivals.map(row => row.arrivalSeconds), [998, 5572]);
  assert.ok(payload.arrivals.every(row => row.trustState === 'VALID' && !row.issues.includes('UNSUPPORTED_PROVIDER')));
  const cached = JSON.parse(refreshBusPayload(JSON.stringify(payload), now + 30_000));
  assert.equal(cached.fetchedAt, payload.fetchedAt);
  assert.ok(cached.arrivals.every(row => row.trustState === 'VALID'));
  const expired = JSON.parse(refreshBusPayload(JSON.stringify(payload), now + 60_001));
  assert.ok(expired.arrivals.every(row => row.trustState === 'STALE'));
});

test('another provider or airport is not admitted by an N route display name', () => {
  for (const type of ['1', '7', '8', '99']) {
    const payload = normalizeBusPayload(routesOperation, source([{ ...route, routeType: type }]), now, 'fixture-key');
    assert.equal(payload.routes[0].supported, false);
  }
});
