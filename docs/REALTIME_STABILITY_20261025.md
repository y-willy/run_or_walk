# 실시간 도착정보 수신 오류 수정 및 검증

기준: 2026-10-25, `fix/auto-boarding-direction-20261025`, PR #2. 최초 확인한 원격 main은 `840ed16`, PR head는 `dbcc4fe`였다. PR에 main 변경이 이미 포함되어 있었고 작업 사본은 깨끗했다. 기존 수정 내용을 초기화하지 않았다. 이후 사용자 요청에 따라 **실시간 지하철·버스 도착정보 수신을 최우선**으로 수정했다.

## 1. 실제 확인한 문제

| 문제 | 재현·근거 | 원인 | 처리 |
| --- | --- | --- | --- |
| 경기 주변 정류소를 정상 수신해도 빈 목록 | 공식 Swagger와 기존 파서 및 테스트 비교로 재현 | 공식 `busStationAroundList`를 `busStationList`로 읽음. 테스트도 잘못된 필드로 만들어져 놓쳤음 | 공식 필드로 수정, 스키마 불일치는 빈 성공 대신 오류, 정류소→경유노선→2대 ETA 회귀 테스트 |
| 현재 서버의 경기 정류소 이름 검색 실패 | 실제 `/gyeonggi/bus/search?query=망포역` HTTP 400 `GYEONGGI_INVALID_QUERY` | 현재 배포본에 새 이름 검색 경로가 없음 | PR의 이름 검색 구현 유지. 새 Worker 배포 필요 |
| 현재 서버의 서울 버스 정류소 검색 실패 | 실제 `/bus/stops?query=강남역` HTTP 502 `BUS_UPSTREAM_AUTHENTICATION` | 배포본이 원본 401/403을 합쳐 반환. 현재 권한으로 정확한 원본 상태와 Secret 내용은 확인할 수 없음 | 새 코드의 401/403 분리 유지, XML·JSON 공공데이터 오류 코드 분류 추가. 승인 누락·키 부재로 단정하지 않음 |
| 실제 ETA 수신과 자동 이동 판단의 보류가 화면에서 혼동됨 | `MainActivity`가 신뢰 가능한 snapshot에만 ETA를 표시함 | 열차 종류 등 일부 메타데이터 부족 때문에 UNKNOWN인 경우에도 받은 원본 예정시간을 숨김 | 독립된 공식 도착정보 카드. 신선한 양수 원본 ETA는 참고 예정시간으로 표시, 자동 이동 판단은 별도 검증 |
| 영통·망포·수원·용산에서 ETA 없음 | 아래 실제 API 관측 | 해당 조회의 `barvlDt`가 모두 0. 일부 원본 생성 시각도 오래됨 | 실제 운행 메시지 및 갱신 상태 표시. 0을 양수로 바꾸거나 신선도를 늘리지 않음 |
| 반대 방향 후보가 하나면 자동 연결 가능 | GBIS 매칭 함수 단위 재현 | 방향 비교가 후보 여러 개일 때만 실행됨 | 후보 수와 관계없이 명시된 방향과 공식 종점 비교. 서울 노선 자동 연결도 방향 힌트 검증 |

## 2. 수정 기능과 파일

- **정류소→노선→ETA 연결**: `worker/src/gyeonggi-lookup-model.ts`가 공식 주변 응답 필드를 읽는다. `GyeonggiArrivalMatching.kt`를 화면과 서비스가 공유하여 공식 정류소·노선·순번, 서로 다른 차량, 중복 충돌, 소스 시각, 다음 차량 만료시각을 동일하게 검사한다. 분 단위만 제공되면 참고 표시와 자동 이동 판단을 구분한다.
- **인증 오류 구분**: `worker/src/public-api-errors.ts`, `bus.ts`, `gyeonggi-bus.ts`에서 HTTP 401/403과 HTTP 200 안의 XML/JSON 오류를 구분한다. 제공자 코드 30(키 인식 실패), 31(만료), 20/29/32(접근 제한), 22/23(호출 제한)을 로컬 오류 코드로만 반환한다. 원문·URL·키는 반환하지 않는다. 코드가 없으면 인증 오류로 추정하지 않는다.
- **앱의 수신 표시**: `OfficialArrivalStatusCard.kt`, `BoardingResolution.kt`, `SeoulSubwayRepository.kt`가 원본 운행정보를 자동 판단과 분리한다. 오래된 시각, 미래 시각, 0초, 도착·출발 상태로 양수 카운트다운을 만들지 않는다. 메타데이터 부족으로 자동 판단이 보류되어도 유효한 원본 ETA 자체는 볼 수 있다. 첫 두 행에 행선지·방향을 표시한다.
- **경기 재조회 보호**: `GyeonggiApiFailures.kt`와 두 경기 저장소가 실제 Retry-After를 보존한다. 일반 503을 키 부재로 설명하지 않는다. 화면과 서비스에서 재조회 대기 시각을 사용하고 실패 뒤 이전 ETA를 재사용하지 않는다.
- **기존 요청의 보완**: 경기 공식 선택의 저장·복원과 명시적 화면 꺼짐 안내 요청 지원, `nextStopName` 저장 누락 보완, 실제 도보선의 시작·끝·거리 검증을 추가했다. `JourneySessionStore.kt`, `JourneyMonitoringRequest.kt`, `JourneyMonitoringService.kt`, `WalkingAccessPolicy.kt` 및 관련 테스트에 반영했다. GPS·실시간 차량·ETA는 저장하지 않는다.
- **사용자가 선택한 접근 대안 설계**: `MainActivity.kt`, `AccessFallbackPolicy.kt`에서 도보 경로 미제공일 때 현재 GPS→실제 첫 승차 지점을 조회한다. 인증·네트워크·스키마 오류를 도보 불가능으로 해석하지 않는다. 대안은 환승·도보를 포함한 참고 예상시간이며 자동 이동 판단에 합치지 않는다. GPS 이동·노후화 시 이전 참고 여유시간을 숨긴다.
- **배포 식별**: `/health`의 본문은 유지하고 `X-PaceNudge-Contract: 2026-10-25.2` 헤더를 추가했다. Secret이나 계정 정보는 노출하지 않는다. 호출 예산·캐시·동시 요청 병합·Durable Object 네임스페이스와 저장 내역은 유지한다.

## 3. 실제 API 검증 결과

2026-10-25 **22:23 한국 시각** 부근, 사용자가 운영 중인 Worker를 소량 조회했다. 같은 역도 조회 시점·운행 상태에 따라 결과가 달라진다. 이 결과는 수정한 로컬 Worker의 배포 결과가 아니다.

| API | HTTP | 데이터 관측 | 선택 경로의 실시간 차량 연결 |
| --- | --- | --- | --- |
| 지하철 영통 | 200 | 4행, ETA 0초 4행, VALID 0행 | 원본 ETA 미제공으로 자동 판단 불가 |
| 지하철 망포 | 200 | 4행, ETA 0초 4행, VALID 0행 | 원본 ETA 미제공으로 자동 판단 불가 |
| 지하철 수원 | 200 | 8행, ETA 0초 8행, VALID 0행 | 원본 ETA 미제공으로 자동 판단 불가 |
| 지하철 서울 | 200 | 18행 중 ETA 0초 12행, VALID 6행 | 유효한 행 수신 확인. 실제 선택 경로·폰 연결은 미검증 |
| 지하철 용산 | 200 | 11행, ETA 0초 11행, VALID 0행 | 원본 ETA 미제공으로 자동 판단 불가 |
| 서울 버스 정류소 강남역 | 502 | 구버전 `BUS_UPSTREAM_AUTHENTICATION` | 인증 계열 응답으로 차단. 401/403 세부 원인 미확인 |
| 경기 주변 정류소 영통역 좌표 | 200 | 현재 배포본이 빈 목록 반환 | 잘못된 필드 파싱과 일치하는 현상. 원본 비공개이므로 실제 원본 목록 수는 확인 불가 |
| 경기 정류소 이름 망포역 | 400 | `GYEONGGI_INVALID_QUERY` | 새 이름 검색 코드 배포 필요 |
| Kakao 장소·도보·대중교통 경로 | 각 200 | documents/route/routes 응답 구조 확인 | 공공장소 좌표로 통신 확인. 실기기 GPS 연결은 별도 |

**실제 5100·3번 도착시간 수신 성공이라고 보고하지 않는다.** 공식 응답 모형에 대한 파싱·매칭 테스트와 운영 API 성공은 다르다. 운영 서버에 수정본을 배포한 후 해당 노선의 실제 정류소·방향·순번으로 확인해야 한다.

확인한 공식 문서:

- [경기도 정류소 조회](https://www.data.go.kr/data/15080666/openapi.do): `getBusStationAroundListv2`의 `busStationAroundList`, 이름 검색의 `busStationList`, 경유노선의 `busRouteList`와 `routeDestName`·`staOrder`를 직접 확인했다.
- [경기도 버스도착정보 조회](https://www.data.go.kr/data/15080346/openapi.do): `predictTimeSec1/2`는 초, `predictTime1/2`는 분. `vehId1/2`, `flag`, `staOrder`, `queryTime` 명세를 확인했다. 원본에 초 정보가 없으면 초 단위 정밀도를 만들어내지 않는다.
- [서울시 지하철 실시간 도착정보](https://data.seoul.go.kr/dataList/OA-12764/A/1/datasetView.do): 원본 시간 지연 가능성이 있다. 현 API의 0초를 다른 숫자로 치환하지 않는다. 새 공급원·유료 서비스·추가 인증은 도입하지 않았다.

## 4. 테스트

실행 환경: 저장소 기존 SDK 37·Gradle·AGP 버전을 유지한 Linux 클라우드. Windows나 실제 스마트폰에서 실행한 결과가 아니다.

| 검사 | 실행 | 결과 | 제한 |
| --- | --- | --- | --- |
| Android Kotlin 컴파일 | 실행 | 통과 | — |
| Android 단위 테스트 | 실행 | 211개 통과, 실패·오류·건너뜀 0 | 실 GPS·실 운행망 모의 아님 |
| Debug APK | 실행 | 생성 성공 | 폰 설치·실행 미검증 |
| Android Lint | 실행 | 오류 0, 경고 36 | 경고가 없는 빌드라는 의미는 아님 |
| Worker `npm ci` | 실행 | 작업 공간 캐시 경로 지정 후 성공 | 기본 npm 캐시 경로 오류가 있었음 |
| Worker TypeScript | 실행 | 통과 | — |
| Worker Vitest | 실행 | 8개 파일, 228개 통과 | 실제 제공자 키를 쓴 테스트가 아닌 Workers 런타임·SQLite·공식 응답 구조 모의 테스트 |
| Worker build | 실행 | dry-run 성공 | 운영 배포 아님 |
| 영통·망포·수원·서울·용산 및 5100·3번 실제 폰 | 미실행 | 미검증 | 연결된 실제 스마트폰 없음 |
| Cloudflare 운영 배포 | 미실행 | 차단 | 이 환경의 Wrangler 계정 인증 없음 |

초기 추가 테스트의 XML 응답 모형을 동일 Response 객체로 재사용하여 body 재독 오류가 발생했다. 각 호출마다 새 Response를 반환하도록 테스트를 바로잡았고 최종 전체 Worker 테스트가 통과했다.

## 5. GitHub 및 실제 배포

PR #2의 브랜치에 수정 내용을 푸시한다. **main에는 병합하지 않는다.** 커밋·최종 단위 테스트 수는 PR 본문에서 확인할 수 있다.

사용자의 Cloudflare 로그인이 된 PC에서 다음 브랜치의 `worker` 폴더를 배포해야 서버 수신 경로가 바뀐다. GitHub 푸시만으로 기존 Worker가 자동 업데이트되는 구성은 확인되지 않았다.

```powershell
git switch fix/auto-boarding-direction-20261025
git pull --ff-only
cd worker
npm.cmd ci
npx.cmd wrangler login
npm.cmd run deploy
```

이미 로그인되어 있으면 login 단계는 필요 없다. 기존 `SEOUL_SUBWAY_KEY`, `SEOUL_BUS_KEY`, `GYEONGGI_BUS_KEY`, `GYEONGGI_STATION_KEY` Secret은 유지한다. 값을 채팅이나 GitHub로 보내지 않는다. Secret이나 Durable Object를 삭제해서 재배포하지 않는다.

배포 후 확인:

```powershell
(Invoke-WebRequest 'https://run-or-walk.willy0723.workers.dev/health').Headers['X-PaceNudge-Contract']
```

예상 헤더는 `2026-10-25.2`다. 이후 `/bus/stops?query=강남역`, `/gyeonggi/bus/search?query=영통역`을 소량 확인한다. 서울 버스가 여전히 실패하면 새 응답의 **오류 코드만** 확인하면 된다. 키 재발급이나 승인 재신청이 필요하다고 현재 단계에서 단정할 수 없다. Android도 이 브랜치로 다시 빌드·설치해야 새 수신 표시가 적용된다.

## 6. 남은 문제의 구분

- **코드 수정 완료, 배포 대기**: 경기 주변 목록 파싱, 이름 검색, 인증 오류 구분, 수신한 원본 정보 표시. 운영 서버에서 적용 여부 확인이 남아 있다.
- **원본 데이터 제약**: 조회 당시 수인분당선 등에서 0초 ETA, 생성 지연이 관측되었다. 운행 메시지는 표시할 수 있으나 신뢰할 수 있는 양수 ETA를 코드만으로 만들 수 없다.
- **운영 설정·상류 확인 필요**: 서울 버스 현재 인증 계열 오류의 세부 원인. 이 환경은 Cloudflare 계정/Secret을 조회하거나 배포할 수 없었다. 기존 승인과 Secret 등록을 부정하지 않는다.
- **별도 도입 결정 필요**: 현재 제공자가 신뢰 가능한 ETA를 제공하지 않는 역의 다른 공급원. 새 API·비용·키가 필요한 서비스는 사용자 승인 없이 도입하지 않는다.
- **실기기 확인 필요**: 실제 정류소 방향·5100/3번 운행, GPS, 화면 꺼짐·권한 변화·기기 절전 정책, 백그라운드 안내 시점.
- **이번 작업 범위 밖**: 사용자 요청으로 보류한 Kakao `map_configuration` 문제.
