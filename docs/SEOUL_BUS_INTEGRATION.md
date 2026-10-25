# 서울 버스·서울 소속 광역버스 실시간 연동

확인일: 2026-10-25 (한국 시각). 서울시가 제공하는 버스 도착정보를 기존 HTTPS Worker와 Android 앱에 연결한다. 경기·인천 버스가 서울 정류장에 선다는 이유만으로 서울 소속 노선으로 취급하지 않는다. 서울시 API의 공식 정류소·노선·정류소 순번을 사용하며 Kakao 장소 ID를 서울시 정류소 ID로 재사용하지 않는다.

## API 키를 넣는 위치

**API 키는 Cloudflare Worker의 `SEOUL_BUS_KEY` Secret에 저장한다.** 앱 설정, Android `BuildConfig`, `local.properties`, GitHub 소스에는 넣지 않는다. 기존 `SEOUL_SUBWAY_KEY`는 지하철용이므로 그대로 유지한다.

Cloudflare Dashboard에서 다음 순서로 등록한다.

1. **Workers & Pages → 기존 `run-or-walk` → Settings → Variables and Secrets**를 연다.
2. **Add**에서 종류를 **Secret**으로 선택한다.
3. 이름에 **`SEOUL_BUS_KEY`**, 값에 공공데이터포털의 **일반 인증키(Decoding)**를 입력한다.
4. 저장하고 새 Worker 소스·설정으로 배포한다. 기존 지하철 Secret을 지우거나 다른 이름으로 바꾸지 않는다.

터미널에서 등록할 수도 있다. 기존 Worker 계정에 로그인한 상태로 저장소의 `worker` 폴더에서 실행하고, 키는 명령이 띄운 입력창에 넣는다. 키 값을 명령문에 직접 적지 않는다.

```sh
npx --no-install wrangler secret put SEOUL_BUS_KEY
```

공공데이터포털은 Encoding/Decoding 두 표현을 표시한다. Decoding 값을 저장하면 Worker가 요청 URL을 만들 때 한 번 인코딩한다. 잘못된 이중 인코딩이나 네이티브 Kakao 키·ODsay 키·서울 지하철 키 사용은 버스 인증 오류의 원인이 된다. 키를 채팅으로 보낼 필요가 없다.

## 필요한 활용 신청

같은 공공데이터포털 인증키를 사용하더라도 서비스별 활용 승인은 별개다. 도착정보뿐 아니라 정류소 찾기와 정확한 노선·방향 연결을 위해 다음 두 서비스를 활성화한다.

- [서울특별시 버스도착정보조회 서비스](https://www.data.go.kr/data/15000314/openapi.do)
- [서울특별시 정류소정보조회 서비스](https://www.data.go.kr/data/15000303/openapi.do)

두 공식 서비스 페이지의 개발계정 트래픽 표기는 각각 1,000이다. 실제 승인 계정의 한도와 산정 방식을 확인한다. 지하철 API와 같은 사용량 장부라고 가정하지 않으며, 앱 사용자 수가 늘면 해당 제공기관의 운영계정 한도 신청을 검토한다.

## 공식 API 계약

공식 페이지에 첨부된 `서울특별시_버스도착정보조회_서비스_활용가이드_20250814.docx`와 `서울특별시_정류소정보조회_서비스_활용가이드_20250924.docx`를 확인했다. 포털 화면은 XML을 표시하지만 두 활용가이드는 `resultType`에 `xml`, `json`을 명시한다. Worker는 문서화된 JSON 응답을 요청할 수 있으며 실패 원문을 앱으로 전달하지 않는다.

| 목적 | 원본 경로 | 요청·응답의 핵심 |
| --- | --- | --- |
| 정류소 이름 검색 | `/api/rest/stationinfo/getStationByName` | `stSrch` → `stId`, `stNm`, `arsId`, `tmX`, `tmY` |
| 좌표 주변 정류소 검색 | `/api/rest/stationinfo/getStationByPos` | `tmX`, `tmY`, `radius` → `stationId`, `stationNm`, `arsId`, `gpsX`, `gpsY` |
| 정류소 경유 노선·순번 확인 | `/api/rest/stationinfo/getStationByUid` | `arsId` → `busRouteId`, `rtNm`, `routeType`, `staOrd`, `adirection`, `stId` |
| 선택한 정류소·노선의 도착정보 | `/api/rest/arrive/getArrInfoByRoute` | **`stId`, `busRouteId`, `ord`** → `stId`, `busRouteId`, **`staOrd`**, `dir`, `mkTm`, `exps1/2`, `vehId1/2` |

원본 호스트는 문서의 `ws.bus.go.kr`로 고정한다. 전화기→Worker는 HTTPS이며, 문서의 Worker→서울시 원본 주소는 HTTP다. 제공기관이 보장하는 HTTPS 주소를 확인하지 않고 전체 구간을 HTTPS라고 주장하지 않는다. 임의 호스트·URL·경로를 앱이 전달할 수 없도록 한다.

`stId/stationId`는 9자리 정류소 ID이고 `arsId`는 정류장 표지에 적힌 5자리 번호다. ARS 번호는 앞의 0을 보존한다. 이름 검색의 `tmX/tmY`, 좌표 검색의 `gpsX/gpsY`는 WGS84 경도·위도다. `posX/posY`는 GRS80 투영 좌표여서 그대로 지도 경도·위도로 사용하면 안 된다. 주변 검색의 공식 반경 범위는 0~1,500m다.

단순 `getRouteByStation`은 노선 목록만 주고 요청 정류소 순번과 방향을 제공하지 않아 도착 API의 `ord`를 완성하기에 부족하다. `getStationByUid`의 `staOrd`와 `adirection`을 사용하고, 요청한 공식 정류소 ID와 응답을 대조한다. 순환 노선이나 같은 정류소를 두 번 지나는 노선은 순번이 다르면 다른 승차 흐름이다.

## 도착시간·차량·혼잡 정보 해석

- `exps1`, `exps2`: 첫째·둘째 차량의 **지수평활 도착예정시간(초)**다. `arrmsg1/2`의 한국어 문장에서 숫자를 추측해 ETA를 만들지 않는다.
- `vehId1`, `vehId2`: 실제 차량 ID다. 0·누락 값을 실제 차량으로 간주하지 않는다. 차량 번호나 노선 번호를 임의 차량 ID로 대체하지 않는다.
- `mkTm`: **제공 시각**이다. 공식 예시는 `2023-09-27 16:59:54.0`이며 한국 시각·소수 초를 지원해 해석한다. `repTm1`은 차량의 최종 보고 시각으로 뜻이 다르다. Worker가 받은 시각을 원본 생성 시각으로 바꾸지 않는다.
- `isArrive1/2`: 최종 정류소 도착·출발 상태(0 운행, 1 도착)다. 이 값만으로 사용자가 서 있는 정류장에 도착했다고 판단하지 않는다.
- `full1/2`: 만차 여부다. 실제 만차 응답은 탑승 가능 안내와 구분한다. 정보가 없으면 빈자리 있다고 가정하지 않는다.
- `rerdie_Div1/2`와 `reride_Num1/2`: 재차 구분과 값이다. 구분 4는 혼잡도(0 정보 없음, 3 여유, 4 보통, 5 혼잡), 구분 2는 재차 인원 또는 서울시 광역버스(`routeType=6`)의 잔여좌석 의미다. 구분을 확인하지 않고 모든 숫자를 잔여좌석으로 표시하지 않는다.
- `routeType`: 2 마을, 3 간선, 4 지선, 5 순환, 6 광역이다. 7 인천, 8 경기는 이번 서울 소속 지원과 구분한다. 폐지·공용·알 수 없는 유형을 확정 탑승 판단에 사용하지 않는다.

0초·누락 ETA·미래 생성 시각·오래된 원본·정류소/노선/순번 불일치·만차를 유효한 탑승 가능으로 승격하지 않는다. 다음 차량 추천은 실제 두 번째 차량이 확인되고 도보시간·안전 여유로 탑승 가능한 경우에만 제공한다.

## 정상·오류 응답

공식 결과는 `msgHeader.headerCd`, `msgHeader.headerMsg`, `msgBody.itemList`를 갖는다. **HTTP 200만으로 데이터 성공을 판정하지 않는다.** 활용가이드의 코드는 다음과 같다.

| 코드 | 공식 의미 |
| --- | --- |
| 0 | 정상 처리 |
| 1 | 시스템 오류 |
| 2 | 잘못된 쿼리 |
| 3 | 정류소 없음 |
| 4 | 노선 없음 |
| 5 | 잘못된 위치 |
| 6 | 실시간 정보 읽기 실패 |
| 7 | 경로 검색 결과 없음 |
| 8 | 운행 종료 |

정류소·노선 없음, 운행 종료, 인증 실패, 서비스 활용 승인 누락, 요청 한도, 네트워크 오류를 구분한다. 특히 코드 4를 정상 빈 목록으로 오인하지 않는다. 원본의 자유 형식 오류 메시지나 키가 들어 있는 요청 URL은 앱·로그에 노출하지 않는다.

## 검증 범위

공식 서비스 페이지·활용가이드를 확인했으며 실제 사용자 API 키로 원본 버스 도착정보를 호출하지 않았다. API 키는 사용자 Cloudflare 계정의 Secret으로 등록해야 한다. 로컬 모의 테스트·빌드 통과와 실제 계정의 활용 승인·배포·운행 응답 확인은 별개다.

## 배포 코드 다운로드와 배포

채팅의 로컬 파일 링크 대신 [GitHub ZIP](https://github.com/y-willy/run_or_walk/archive/refs/heads/main.zip)을 사용한다. 저장소의 **Code → Download ZIP**도 같다. 비공개 저장소라면 GitHub 계정에 로그인해 접근한다.

Node.js 22 이상을 설치하고 압축을 영문 경로에 해제한다. 압축 해제된 `run_or_walk-main` 폴더의 터미널에서:

```sh
cd worker
npm ci
npm run check
npm run build
npx --no-install wrangler login
npx --no-install wrangler whoami
```

기존 `run-or-walk.willy0723.workers.dev`가 있는 계정인지 확인하고, 위 Dashboard 절차로 Secret을 등록했으면 다음을 실행한다.

```sh
npm run deploy
```

터미널에서 Secret을 등록할 때는 `npx --no-install wrangler secret put SEOUL_BUS_KEY`의 보안 입력창을 사용한다. 키를 명령 뒤에 붙이거나 문서에 넣지 않는다. Secret 등록과 새 Worker 배포가 모두 필요하며, APK에 버스 키를 넣을 필요는 없다.

`wrangler.jsonc`는 기존 이름 `run-or-walk`, `SUBWAY_COORDINATOR`, `v1` migration을 유지하고 **`SEOUL_BUS_COORDINATOR` → `SeoulBusCoordinator` 및 `v2` migration**을 추가한다. 기존 `IP_RATE_LIMITER`도 사용한다. 기존 계정에 이미 다른 migration 이력이 있으면 그 이력을 보존하고 태그 충돌을 해소해야 한다. 코드 본문만 Dashboard Edit code에 붙여 넣으면 이 바인딩·migration이 적용되지 않는다.

버스 키가 없어도 지하철·`/health`는 유지된다. 버스 경로만 `SEOUL_BUS_NOT_CONFIGURED`로 설정 필요를 알린다. **지하철과 버스 모두 초기 24시간 대기가 없다.** 이전 지하철 버전의 BUDGET_WARMUP 대기는 새 Worker 배포 때 해제하고 실제 호출 기록과 각 서비스의 900회 상한은 유지한다. 이전 앱에 24시간 재시도 시간이 남아 있으면 앱을 완전히 종료 후 재실행하거나 새 앱을 설치한다. 새 앱은 이전 서버의 BUDGET_WARMUP에 한해서 최대 30초 뒤 다시 확인하며, 실제 사용량 제한의 Retry-After는 유지한다.

## Worker 경로와 호출 정책

- `GET /bus/stops?query=서울역`: 공식 정류소 이름 검색. 5자리 ARS 번호도 검색할 수 있다.
- `GET /bus/stops?x=경도&y=위도`: 승차 장소 주변 500m 공식 정류소 검색.
- `GET /bus/routes?stopId=공식정류소ID&arsId=ARS번호`: 해당 정류소의 노선·방향·순번·지원 여부.
- `GET /bus/arrivals?stopId=공식정류소ID&routeId=공식노선ID&stopOrder=정류소순번`: 정확히 일치하는 노선의 첫째·둘째 차량 도착정보.

앞 단계의 실제 응답 ID를 사용한다. ARS 번호를 9자리 정류소 ID로 대신하거나 Kakao 장소 ID를 넣으면 안 된다. 원본의 `stId + busRouteId + staOrd`가 요청과 일치하지 않으면 판단하지 않는다. 응답의 원본 생성 시각은 `mkTm`에서 계산한 `observedAt`, Worker 조회 시각은 별도 `fetchedAt`이다.

버스 singleton은 30초 캐시·동일 진행 요청 병합과 정류소정보/도착정보 서비스 각각 **최근 24시간 최대 900회 원본 시도**를 관리한다. 실패한 시도도 사용량에 포함한다. 다른 서비스나 이전 직접 호출은 이 장부에 없으므로 실제 계정 한도 전체를 보장하지 않는다. 원본 429는 `Retry-After`를 존중하고, 일반 원본 오류는 역별이 아닌 조회 항목별 10초 대기를 저장한다. 지하철 예산·호출 기록은 변경하지 않는다. 이전 지하철의 초기 대기 시각만 해제한다.

## 앱에서 사용

1. **정류장 찾기**에서 버스 정류장을 선택하거나 **경로로 찾기**에서 버스 경로를 선택한다.
2. 실제 정류장 이름·좌표가 유일하게 일치하면 공식 정류소를 자동 연결한다. 후보가 여러 개면 표지의 ARS 번호와 위치를 확인해 고른다.
3. 탑승할 노선·방향을 선택한다. 이미 고른 경로의 노선 번호가 해당 공식 정류소에서 유일하면 자동 연결한다. 같은 노선이 순환하며 여러 번 정차하면 순번을 구분한다.
4. 화면이 보이는 동안 약 30초마다 도착정보를, 약 20초마다 위치·도보 상태를 확인한다. 수동 갱신도 서버·앱의 대기 시간을 따른다.
5. 실제 도착시각과 도보시간·안전 여유를 비교해 걷기·빠른 걸음·확인된 다음 버스 안내를 표시한다. 진동은 사용자가 켰을 때만 제공한다.

실제 만차, 만차 여부 미확인, 운행 종료·출발 대기·회차 대기, 우회·차량 상태 미확인, 0초, 차량 ID 누락, 미래·오래된 원본을 탑승 가능으로 바꾸지 않는다. 원본 도착 메시지·막차·만차 안내는 화면에 보존한다. 경기·인천·공항 유형은 서울 소속 지원 노선과 구분해 선택을 차단한다.

## 이번 구현 검증 결과

- 앱 `compileDebugKotlin`, `testDebugUnitTest`, `assembleDebug`, `lintDebug` 통과. **139개 테스트**, 실패·오류·건너뜀 0개. 린트 오류 0개·경고 31개·힌트 1개.
- Worker TypeScript 검사, 실제 workerd/SQLite 기반 **190개 테스트**, Wrangler dry bundle 통과. 번들은 52.32KiB이며 세 바인딩이 포함된다.
- 공유 JSON fixture를 Worker가 실제 정규화한 결과와 비교하고, 앱에서 같은 응답을 파싱해 걷기·빠른 걸음·실제 다음 버스·만차 제외까지 연결해 검증한다.
- 반대편 정류장·순환 순번·공식 ID 혼동, 불완전/미래/0초/운행 종료 데이터, 동시 조회·900건 상한·재시작 후 예산 보존·응답 키 제거를 검증했다.
- 이전 24시간 대기 시각이 있는 실제 SQLite 장부를 업그레이드했을 때 기존 호출 ID·시각을 보존하면서 첫 조회가 진행되는 경우, 900회 소진 장부는 계속 차단되는 경우, 재시작 후 기록·캐시 유지도 검증했다.
- 실제 API 키 등록, 활용 승인, Cloudflare 실배포, 실제 스마트폰 버스 운행 데이터는 이 환경에서 검증하지 못했다. 이 환경에는 해당 계정 인증이나 새 버스 Secret이 없다. 사용자가 키를 채팅에 제공할 필요는 없다.
