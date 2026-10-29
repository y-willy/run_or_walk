# N37 심야버스 공식 연결 수정 — 2026-10-30

작업 브랜치: `fix/n37-boarding-direction-20261030` (기준 main `d04d081`).

## 실제 원인

운영 Worker와 실제 Kakao 길찾기 응답을 각각 확인했다.

1. 서울 공식 정류소 API는 N37을 `routeType: "15"`로 반환한다. Worker `supportedType`과 Android `parseWorkerSeoulBusRoutes`가 2~6만 지원해 심야 노선을 제외했다.
2. Kakao의 `N37(심야)`와 서울의 `N37`이 기존 `normalizeBusRouteName`에서 서로 다른 노선으로 처리됐다.
3. 첫 버스 하차 정류장은 Kakao에서 `지하철2호선강남역(중)`, 서울에서 `지하철2호선강남역`이다. `(중)`을 포함한 공식 검색은 실제로 `BUS_ROUTE_NOT_FOUND`를 반환했다. 기존 이름 대조도 서로 불일치했다.
4. 운영 `/bus/route-stops`는 여전히 `BUS_UPSTREAM_KEY_REJECTED`다. 기존의 별도 정류소/노선 서비스에 의한 공식 정차 순서 확인을 유지하며, 위 세 문제로 이 확인까지 진행되지 못하던 부분을 수정했다. 이 작업에서 서버 인증 설정은 변경하지 않았다.

## 변경

- `SeoulBusRepository.kt`: 서울 심야 유형 15 지원, 검색어의 `(중)` 접미사 정규화. 구 Worker가 유형 15만 잘못 분류한 `supported:false`도 호환 처리한다. 다른 유형의 명시적 지원 제외는 유지한다.
- `BusBoardingResolution.kt`: 숫자 N 노선의 `(심야)` 표시 접미사만 제거하고, 정류장의 `(중)` 표시 접미사를 이름 비교에서 정규화한다. 출구 번호·노선 번호·A 접미사는 유지한다.
- `worker/src/bus-model.ts`: 공식 심야 유형 15를 지원해 노선의 `supported`와 도착정보의 `UNSUPPORTED_PROVIDER` 오분류를 함께 수정한다.
- `SeoulNightBusTest.kt` 및 `n37_official_20261030.json`: 실제 공개 응답 재생 테스트 6건. 잘못된 방향, 다른 제공기관, 유사한 노선명 오연결도 검사한다.
- `worker/tests/seoul-night-bus.test.mjs`: 심야 유형, 첫/다음 ETA·캐시 만료, 다른 제공기관 제외 테스트 3건.

N37 이름을 개별 허용 목록에 추가하지 않았다. 공식 정류소·노선 ID 및 승차 순서 검증, Retry-After, 예산, 캐시, ETA 만료 및 기존 이동 계산기는 유지했다.

## 실제 API 검증

2026-10-30 00:51 KST에 실제 길찾기와 운영 Worker를 수정된 Android Repository로 호출했다. [호출 결과](validation/n37-20261030/live-api-proof.txt).

- 공개 출발지 `삼일교` → `강남역 2호선` 길찾기에 `N37(심야)`가 포함됐다.
- 첫 승차점 `종로2가.삼일교`: stopId `100000004`, ARS `01004`.
- N37: routeId `100100585`, stopOrder `81`, routeType `15`.
- 실제 공식 경유 순서: 명동성당 82 → 순천향대학병원 83 → 신사역 84 → 논현역 85 → 지하철2호선강남역 86. 반대편 강남역은 26이므로 제외됐다.
- 자동 공식 노선 선택과 `verifySeoulBusBoardingDirection`이 성공했다.
- 유효 ETA 2건 수신. 당시 원본 예정시간은 584초 / 2444초로, 현재 시간의 도착정보를 의미하지 않는다.
- 실제 도보 API 131m / 203초, 위 실제 ETA와 공개 검색 장소의 **모의 GPS**를 입력한 결과 `RELAXED`(여유 있어요), UNKNOWN 원인 없음, 화면·서비스 계산 결과 일치.

실제 운영 API 응답 검증과 모의 GPS 계산을 구분한다. 휴대폰 GPS·실제 탑승·실제 진동·백그라운드 실행은 검증하지 않았다. 다른 심야 노선 전체의 실시간 운행을 확인했다고 주장하지 않는다.

## 테스트와 빌드

- 수정 전 재현 테스트: 6건 중 관련 4건 실패.
- 수정 후 `:app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug`: 성공, 302건 통과 / 실패 0 / 오류 0 / 건너뜀 0.
- 별도 실제 API 프로브 1건 통과 (기본 단위 테스트에는 네트워크 프로브를 넣지 않음).
- Worker 테스트: `node --experimental-transform-types --test tests/seoul-night-bus.test.mjs`, 3건 통과.
- Worker `npm run check`: 성공.
- Worker `npm run build` (`wrangler deploy --dry-run`): 성공. 환경의 기본 로그 디렉터리 생성 실패 후 쓰기 가능한 XDG_CONFIG_HOME으로 재실행해 성공했다.

## 적용 상태

새 APK 재빌드·설치가 필요하다. 수정 앱은 구 Worker의 유형 15 오분류도 처리하므로 현재 운영 API에서 연결·판단까지 검증했다. Worker의 지원 표시도 바로잡으려면 승인 후 재배포해야 한다. 운영 Worker 배포 및 main 병합은 이 작업에서 진행하지 않았다.
