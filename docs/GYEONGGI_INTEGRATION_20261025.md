# 경기버스 도착정보 도입과 자동 승차 연결 — 2026-10-25

## 구현 경계

- 경로 선택 시 첫 승차 장소를 카카오 교통 카테고리·역명·호선·좌표와 대조해 유일한 경우 자동 연결한다. 지도는 실제 선택된 승차 위치를 표시하고 GPS에서 해당 위치까지 실제 도보 경로를 계산한다. 지하철 방향도 유일한 공식 열차 실시간 정보가 있을 때만 자동 연결한다.
- GPS에서 승차 위치까지 도보 검색이 실패하면 실제 GPS에서 사용자가 선택한 출발지까지의 대중교통 경로를 최대 세 개 조회해 예상 이동 시간을 표시한다. 출발지와 승차점이 100m 이내이고 유효한 메인 차량 ETA가 있는 때에만 참고 여유시간을 표시한다. 환승 차량 실시간 도착이 확인된 것은 아니므로 WALK/FAST_WALK 판단에는 사용하지 않는다.
- 경기도 버스는 별도 Worker SQLite Durable Object를 통해 공식 도착 목록/항목 API를 호출한다. Android에는 공식 stationId를 수동 입력하여 정보를 확인할 수 있는 UI를 제공한다.

**미완료**: 카카오 정류장 좌표·ID와 경기 공식 stationId 매핑, 공식 routeId·staOrder와 실제 진행방향 자동 연결, 경기버스 실시간 기반 자동 뛰기/걷기 판단. 별도 정류소·경유노선 조회 API 권한과 실기기 검증이 있어야 안전하게 완성 가능하다.

## Worker API

- GET /gyeonggi/bus/arrivals?stationId=200000177 → GBIS getBusArrivalListv2
- GET /gyeonggi/bus/arrival?stationId=200000177&routeId=200000037&staOrder=19 → GBIS getBusArrivalItemv2
- 샘플 ID는 공식 문서의 예시일 뿐 실제 승차역의 ID가 아니다.
- 30초 역/노선별 캐시, 동일 요청 합류, 10초 오류 backoff, IP 제한, 전역 SQLite 최근 24시간 900회 원본 시도 예산. 두 엔드포인트가 같은 예산을 공유한다.
- 오류·실패한 원본 호출도 시도 기록으로 남는다. 다른 서비스가 같은 키를 사용한 이전 사용량은 이 장부에서 알 수 없으므로 공공데이터포털 전체 1000회 보장은 아니다.
- 원본 생성 시각과 조회 시각을 구분하며 ETA 0초·미래 시각·불명확한 차량은 안전한 탑승 가능 정보로 처리하지 않는다.
- 기존 서울 지하철/버스 객체와 SQL 기록을 변경하지 않고 새 v3 Durable Object migration만 추가한다.
- 키는 GYEONGGI_BUS_KEY Cloudflare Secret으로만 입력하고 앱 코드·저장소·로그에 넣지 않는다.

## 사용자 할 일

1. 공공데이터포털에서 신청한 경기 버스 도착정보 조회의 상세 기능 2개가 승인·활성화됐는지 확인한다.
2. 경기버스 공유서비스에서 **정류소 조회** 및 **정류소 경유노선 목록** 기능을 추가 신청한다. 자동 지도 승차 정류장 매칭에는 GBIS stationId / routeId / staOrder를 독립적으로 얻어야 한다. 현재 2개의 도착 API만으로 자동 매핑은 불가능하다.
3. 기존 Cloudflare 계정에서 Worker 폴더를 열고 아래 명령을 실행한다. 프롬프트가 나오면 본인 키를 입력하며 이 문서나 채팅에 붙이지 않는다.
   - cd worker
   - npx --no-install wrangler whoami
   - npx --no-install wrangler secret put GYEONGGI_BUS_KEY
   - npm ci
   - npm run check
   - npm run build
   - npm run deploy
4. 출력된 배포 URL이 기존 run-or-walk.willy0723.workers.dev인지 확인한다. 기존 서울 API Secret을 삭제·재발급하지 않는다.
5. 몇 건만 실제 Worker 응답을 확인하고, Android Studio에서 :app:compileDebugKotlin, :app:assembleDebug, :app:lintDebug 수행 뒤 새 APK로 GPS·지도를 직접 검증한다.

**GitHub 브랜치에 코드가 기록됐다는 것과 Cloudflare 배포·테스트 통과·실기기 동작은 별개다.** 이 작업에서는 실제 키를 사용한 호출과 Cloudflare 배포를 하지 않았다.

공식 근거:
- https://www.gbis.go.kr/gbis2014/publicService.action?cmd=mBusArrivalStation
- https://www.gbis.go.kr/gbis2014/publicService.action?cmd=mBusArrival
- https://www.gbis.go.kr/gbis2014/publicService.action?cmd=mBusStationRoute
